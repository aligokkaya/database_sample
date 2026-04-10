"""
Classification service – handles:
  - Fetching sample data from a target DB column
  - Building the LLM prompt
  - Calling the OpenAI-compatible API
  - Parsing and normalising the probability response
"""
from __future__ import annotations

import json
import re
import uuid
from typing import Any

import psycopg2
import psycopg2.extras
from fastapi import HTTPException, status
from openai import OpenAI
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import get_settings
from app.metadata.service import decrypt_password
from app.models import ColumnInfo, DbConnection, MetadataRecord, TableInfo

settings = get_settings()

# ── PII Categories ────────────────────────────────────────────────────────────
# Exactly 12 categories from the case study + not_pii.
# iban is grouped under credit_card_number per case requirement #4.
PII_CATEGORIES = [
    "email_address",
    "phone_number",
    "social_security_number",
    "credit_card_number",    # includes IBAN / bank account numbers
    "national_id_number",
    "full_name",
    "first_name",
    "last_name",
    "tckn",
    "home_address",
    "date_of_birth",
    "ip_address",
    "not_pii",
]

SYSTEM_PROMPT = """/no_think
## ROLE
You are an expert data privacy engineer specialised in automated PII (Personally Identifiable Information) discovery within enterprise database systems. Your classifications are used in compliance pipelines governed by GDPR, KVKK, and CCPA regulations — accuracy is critical.

## OBJECTIVE
Given a database column name and a set of sample values extracted from that column, output a probability distribution across 13 predefined PII categories. The probabilities represent your confidence that the column stores each type of data.

## CLASSIFICATION CATEGORIES

| # | Category | Description | Examples |
|---|----------|-------------|---------|
| 1 | email_address | Personal or business email addresses | john@gmail.com, info@company.com |
| 2 | phone_number | Mobile, landline, or international phone numbers in any format | +90 532 123 45 67, (555) 123-4567 |
| 3 | social_security_number | US Social Security Numbers | 123-45-6789, 987654321 |
| 4 | credit_card_number | Payment card numbers (Visa, MC, Amex) AND bank IBAN numbers | 4532-1234-5678-9012, TR33 0006 1005 1978 6457 8413 26 |
| 5 | national_id_number | Government-issued IDs: passport, driver's license, national ID cards (non-Turkish) | AB1234567, D123-4567-8901 |
| 6 | full_name | Complete personal name (first + last, with or without middle) | John Michael Smith, Ayşe Kaya |
| 7 | first_name | Given/first name only | John, Maria, Mehmet, Sarah |
| 8 | last_name | Family/surname only | Smith, Johnson, Yılmaz, García |
| 9 | tckn | Turkish Republic Citizenship Number — exactly 11 digits, first digit non-zero | 12345678901, 38291047652 |
| 10 | home_address | Full physical/residential address including street line | 123 Main St Apt 4, Atatürk Cad. No:5 |
| 11 | date_of_birth | Birth dates in any format | 1985-03-15, 15/03/1985, March 15 1985 |
| 12 | ip_address | IPv4 or IPv6 addresses | 192.168.1.1, 2001:0db8::1 |
| 13 | not_pii | Non-personal data: metrics, codes, statuses, product info, system data | order_status, price, product_code |

## CLASSIFICATION METHODOLOGY

Apply the following reasoning pipeline in order:

1. **Pattern recognition** — Does the data match a known structural format?
   - Email: `*@*.*`
   - TCKN: exactly 11 digits, starts with non-zero
   - IP: `n.n.n.n` or IPv6
   - Phone: digit groups with separators
   - Credit card: 16-digit groups; IBAN: country prefix + digits

2. **Semantic analysis** — Does the column name provide strong context?
   - Column names are a reliable signal but must be consistent with the actual values
   - If values contradict the column name, weight values more heavily

3. **Contextual inference** — Is the value a name, address, or date?
   - Names: capitalised words, linguistically valid personal names across cultures
   - Addresses: contain street indicators, building numbers, directional cues
   - Dates: recognisable temporal formats

4. **Ambiguity handling**
   - If multiple PII types are plausible, distribute probability proportionally
   - For JSON/structured text containing mixed PII, classify by the most sensitive element
   - When genuinely uncertain, distribute low probability across candidates and raise not_pii

## CRITICAL RULES

- Output MUST be valid JSON — no markdown, no explanation, no extra keys
- All 13 keys MUST be present
- All values MUST be floats in range [0.0, 1.0]
- Probabilities MUST sum to EXACTLY 1.0 (normalise if needed)
- High confidence threshold: ≥ 0.85 | Medium: 0.40–0.84 | Low: < 0.40

## COMMON PITFALLS TO AVOID

- Do NOT classify `user_agent`, `browser`, `os_version` as `ip_address` — they contain version numbers that superficially resemble IPs
- Do NOT classify generic location fields (`city`, `district`, `country`) as `home_address` — they are not PII alone
- Do NOT classify order numbers, invoice numbers, or reference codes as `national_id_number`
- Do NOT classify timestamps or registration dates as `date_of_birth` — context matters

## OUTPUT FORMAT (STRICT)

Return ONLY this JSON structure with your probability values:

{
  "email_address": 0.0,
  "phone_number": 0.0,
  "social_security_number": 0.0,
  "credit_card_number": 0.0,
  "national_id_number": 0.0,
  "full_name": 0.0,
  "first_name": 0.0,
  "last_name": 0.0,
  "tckn": 0.0,
  "home_address": 0.0,
  "date_of_birth": 0.0,
  "ip_address": 0.0,
  "not_pii": 1.0
}"""


# ── Smart discovery constants ─────────────────────────────────────────────────

SKIP_TYPES = {
    "integer", "bigint", "smallint", "int", "int2", "int4", "int8",
    "serial", "bigserial", "boolean", "bool",
    "numeric", "decimal", "real", "double precision", "float4", "float8",
    "uuid", "bytea", "oid",
}

PII_NAME_RULES: list[tuple[str, list[str]]] = [
    ("email_address",          ["email", "mail"]),
    ("phone_number",           ["phone", "tel", "gsm", "mobile", "cellular"]),
    # tckn: Turkish citizen ID — various naming conventions
    ("tckn",                   ["tckn", "tc_kimlik", "tc_no", "kimlik_no", "citizen_no", "citizen_id", "kimlik"]),
    ("social_security_number", ["ssn", "social_security", "social_sec"]),
    # credit_card_number also covers IBAN / bank accounts (case category #4)
    ("credit_card_number",     ["credit_card", "card_number", "card_no",
                                "iban", "bank_account", "bank_iban", "holder_iban", "account_number"]),
    ("ip_address",             ["ip_address", "ip_addr", "ipaddress"]),
    ("full_name",              ["full_name", "fullname"]),
    # first_name: fname, given, name_first catch various naming patterns
    ("first_name",             ["first_name", "firstname", "given_name", "fname", "given", "name_first"]),
    # last_name: lname, family, name_last catch name_family / lname patterns
    ("last_name",              ["last_name", "lastname", "surname", "family_name", "soyad", "lname", "family", "name_last"]),
    ("home_address",           ["street_address", "home_address", "billing_address"]),
    ("date_of_birth",          ["date_of_birth", "birth_date", "dob", "birthday"]),
    ("national_id_number",     ["national_id", "passport", "driver_license", "drivers_license"]),
    # national_id covers tax IDs too
    ("national_id_number",     ["tax_number", "tax_no", "vergi_no", "tax_id"]),
]

# Data types that represent dates/timestamps
DATE_TYPES = {"date", "timestamp", "timestamp without time zone", "timestamp with time zone", "timestamptz"}

# NOT_PII_KEYWORDS uses WORD-BOUNDARY matching (split column name by "_").
# "number" is safe because DB regex runs FIRST: real PII (TCKN in id_number)
# gets caught before this check fires.
NOT_PII_KEYWORDS = [
    "count", "amount", "price", "cost", "total",
    "quantity", "status", "code", "type", "rating",
    "percentage", "level", "stock", "weight", "score",
    "number",
    "method", "provider",
    "position", "language", "currency", "timezone", "locale",
    "flag", "label",
    "agent",      # user_agent → {"user","agent"} → not_pii
    "version", "ref", "slug", "hash", "checksum", "rank",
    # standalone geographic terms — not PII alone (city, district, country etc.)
    "city", "district", "country", "region", "state", "province",
    "neighborhood", "continent", "county", "borough",
]

# ── DB-side PII regex patterns ────────────────────────────────────────────────
# IMPORTANT: tckn BEFORE phone — 11-digit Turkish IDs match phone regex otherwise.
PII_DB_PATTERNS: list[tuple[str, str]] = [
    ("email_address",          r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}'),
    ("tckn",                   r'\m[1-9][0-9]{10}\M'),
    ("social_security_number", r'\m[0-9]{3}-[0-9]{2}-[0-9]{4}\M'),
    ("phone_number",           r'(\+?[0-9]{1,3}[\s\-]?)?[\(]?[0-9]{3}[\)]?[\s\-]?[0-9]{3}[\s\-]?[0-9]{4}'),
    ("credit_card_number",     r'\m[0-9]{4}[\s\-]?[0-9]{4}[\s\-]?[0-9]{4}[\s\-]?[0-9]{4}\M'),
    ("ip_address",             r'\m[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\M'),
    ("national_id_number",     r'\m[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}[A-Z0-9]{0,16}\M'),
]

JSON_TYPES = {"jsonb", "json"}


# ── Helper functions ──────────────────────────────────────────────────────────

def _pii_name_classify(col_name: str, data_type: str) -> str | None:
    """
    Phase 2a — Positive PII name rules.
    Returns a category if the column name clearly matches a known PII pattern,
    or 'not_pii' for date columns that are NOT birth dates.
    Returns None when uncertain → caller proceeds to DB regex then NOT_PII check.
    """
    name_lower = col_name.lower()

    if data_type.lower() in DATE_TYPES:
        birth_keywords = ["date_of_birth", "birth_date", "dob", "birthday", "dogum", "born", "birth"]
        if any(kw in name_lower for kw in birth_keywords):
            return "date_of_birth"
        return "not_pii"

    for category, keywords in PII_NAME_RULES:
        if any(kw in name_lower for kw in keywords):
            return category

    return None


def _is_not_pii_by_name(col_name: str) -> bool:
    """
    Phase 2c — NOT_PII keyword check using WORD-BOUNDARY matching.
    Split by "_" so "badge_number" → {"badge","number"} → match.
    Avoids false negatives like "no" matching "notes".
    """
    name_parts = set(col_name.lower().split("_"))
    return any(kw in name_parts for kw in NOT_PII_KEYWORDS)


def _db_regex_classify(conn: Any, table_name: str, column_name: str) -> str | None:
    """
    Phase 3 — Run PII regex patterns directly on PostgreSQL.
    Returns first matching category, or None. Fast: PostgreSQL stops at LIMIT 1.
    """
    col = f'"{column_name}"'
    tbl = f'"{table_name}"'

    with conn.cursor() as cur:
        for category, pattern in PII_DB_PATTERNS:
            try:
                cur.execute(
                    f"SELECT 1 FROM {tbl} WHERE {col}::text ~ %s LIMIT 1",
                    (pattern,),
                )
                if cur.fetchone():
                    return category
            except psycopg2.Error:
                conn.rollback()
                continue

    return None


def _flatten_json_samples(samples: list[Any]) -> list[str]:
    """Flatten JSON/JSONB values into readable strings for the LLM."""
    result = []
    for val in samples:
        if val is None:
            continue
        try:
            obj = val if isinstance(val, dict) else json.loads(str(val))
            if isinstance(obj, dict):
                result.append(" | ".join(f"{k}: {v}" for k, v in obj.items()))
            else:
                result.append(str(val))
        except (ValueError, TypeError):
            result.append(str(val))
    return result


def _fetch_llm_samples(conn: Any, table_name: str, column_name: str, limit: int = 10) -> list[Any]:
    """Fetch non-null rows to send to the LLM."""
    col = f'"{column_name}"'
    tbl = f'"{table_name}"'
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT {col} FROM {tbl} WHERE {col} IS NOT NULL LIMIT %s",
            (limit,),
        )
        return [row[0] for row in cur.fetchall()]


def _open_connection(host: str, port: str, database: str, username: str, password: str) -> Any:
    """Open a psycopg2 connection, raising a clean 400 on failure."""
    try:
        return psycopg2.connect(
            host=host, port=int(port), dbname=database,
            user=username, password=password, connect_timeout=10,
        )
    except psycopg2.OperationalError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Failed to connect to target database: {exc}",
        ) from exc


def _extract_json(text: str) -> dict:
    """Extract JSON from LLM response, handling markdown code blocks."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    raise ValueError(f"No valid JSON found in LLM response: {text[:200]}")


def _call_llm(column_name: str, sample_values: list[Any], table_name: str = "") -> dict[str, float]:
    """Send sample data to the LLM and parse the JSON probability response."""
    client = OpenAI(
        api_key=settings.OPENAI_API_KEY or "ollama",
        base_url=settings.OPENAI_BASE_URL,
    )

    samples_str = "\n".join(f"  [{i+1}] {repr(v)}" for i, v in enumerate(sample_values[:50]))
    table_context = f"Table : {table_name}\n" if table_name else ""
    user_message = (
        f"## COLUMN UNDER ANALYSIS\n\n"
        f"{table_context}"
        f"Column: {column_name}\n"
        f"Sample values ({len(sample_values)} rows):\n\n"
        f"{samples_str}\n\n"
        f"## TASK\n\n"
        f"Analyse the column name and all sample values above. "
        f"Apply the classification methodology from the system prompt and return "
        f"the probability distribution across all 13 PII categories. "
        f"Return ONLY the JSON object — no explanation, no markdown."
    )

    call_kwargs: dict = {
        "model": settings.OPENAI_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_message},
        ],
        "temperature": 0.0,
    }
    if "openai.com" in (settings.OPENAI_BASE_URL or ""):
        call_kwargs["response_format"] = {"type": "json_object"}

    response = client.chat.completions.create(**call_kwargs)
    raw_json = response.choices[0].message.content

    try:
        data = _extract_json(raw_json)
    except (ValueError, Exception) as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"LLM returned invalid JSON: {exc}",
        ) from exc

    result: dict[str, float] = {cat: float(data.get(cat, 0.0)) for cat in PII_CATEGORIES}
    total = sum(result.values())
    if total > 0:
        result = {k: round(v / total, 6) for k, v in result.items()}
    else:
        result = {k: 0.0 for k in PII_CATEGORIES}
        result["not_pii"] = 1.0

    return result


# ── Public API ────────────────────────────────────────────────────────────────

async def classify_column(
    db: AsyncSession,
    column_id: str,
    sample_count: int = 10,
) -> dict[str, Any]:
    """
    Classify a single column for PII using LLM.
    Fetches sample_count rows and returns full probability distribution.
    """
    result = await db.execute(
        select(ColumnInfo)
        .options(selectinload(ColumnInfo.table))
        .where(ColumnInfo.id == uuid.UUID(column_id))
    )
    column_obj: ColumnInfo | None = result.scalar_one_or_none()
    if column_obj is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"Column '{column_id}' not found.")

    table_obj: TableInfo = column_obj.table
    metadata_id = column_obj.metadata_id

    conn_result = await db.execute(
        select(DbConnection).where(DbConnection.metadata_id == metadata_id)
    )
    db_conn: DbConnection | None = conn_result.scalar_one_or_none()
    if db_conn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"No DB connection found for metadata '{metadata_id}'.")

    try:
        plain_password = decrypt_password(db_conn.encrypted_password)
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=f"Failed to decrypt stored password: {exc}") from exc

    data_type = column_obj.data_type.lower()

    # Apply same rule-based pre-checks as discover to keep results consistent.
    # Phase 2a — positive PII name rules
    name_result = _pii_name_classify(column_obj.column_name, data_type)
    if name_result is not None:
        is_pii = name_result != "not_pii"
        classifications = {cat: 0.0 for cat in PII_CATEGORIES}
        classifications[name_result] = 1.0
        return {
            "column_id": column_id,
            "column_name": column_obj.column_name,
            "table_name": table_obj.table_name,
            "data_type": data_type,
            "sample_count": 0,
            "top_category": name_result,
            "top_probability": 1.0,
            "classifications": classifications,
        }

    # Phase 2c — NOT_PII word-boundary check
    if _is_not_pii_by_name(column_obj.column_name):
        classifications = {cat: 0.0 for cat in PII_CATEGORIES}
        classifications["not_pii"] = 1.0
        return {
            "column_id": column_id,
            "column_name": column_obj.column_name,
            "table_name": table_obj.table_name,
            "data_type": data_type,
            "sample_count": 0,
            "top_category": "not_pii",
            "top_probability": 1.0,
            "classifications": classifications,
        }

    # Phase 3 + 4 — DB regex then LLM
    conn = _open_connection(db_conn.host, db_conn.port,
                            db_conn.database_name, db_conn.username, plain_password)
    try:
        # Phase 3 — DB regex (skip for JSON types)
        if data_type not in JSON_TYPES:
            category = _db_regex_classify(conn, table_obj.table_name, column_obj.column_name)
            if category is not None:
                classifications = {cat: 0.0 for cat in PII_CATEGORIES}
                classifications[category] = 1.0
                return {
                    "column_id": column_id,
                    "column_name": column_obj.column_name,
                    "table_name": table_obj.table_name,
                    "data_type": data_type,
                    "sample_count": 0,
                    "top_category": category,
                    "top_probability": 1.0,
                    "classifications": classifications,
                }

        # Phase 4 — LLM
        samples = _fetch_llm_samples(conn, table_obj.table_name, column_obj.column_name, sample_count)
        if data_type in JSON_TYPES:
            samples = _flatten_json_samples(samples)
        classifications = _call_llm(column_obj.column_name, samples, table_name=table_obj.table_name)
        category = max(classifications, key=lambda k: classifications[k])
    finally:
        conn.close()

    return {
        "column_id": column_id,
        "column_name": column_obj.column_name,
        "table_name": table_obj.table_name,
        "data_type": data_type,
        "sample_count": len(samples),
        "top_category": category,
        "top_probability": classifications.get(category, 1.0),
        "classifications": classifications,
    }


async def discover_metadata(
    db: AsyncSession,
    metadata_id: str,
    sample_count: int = 10,
) -> dict[str, Any]:
    """
    Full PII discovery for an entire metadata record.
    4-phase smart filtering to minimise LLM calls:
      Phase 1  — Skip numeric/bool/uuid types
      Phase 1b — JSON/JSONB: flatten + LLM always
      Phase 2a — Positive PII name rules
      Phase 3  — DB regex scan (BEFORE NOT_PII check)
      Phase 2c — NOT_PII word-boundary check
      Phase 4  — LLM (truly ambiguous columns only)
    """
    try:
        meta_uuid = uuid.UUID(metadata_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail=f"Invalid metadata_id: '{metadata_id}'")

    result = await db.execute(
        select(MetadataRecord)
        .options(selectinload(MetadataRecord.tables).selectinload(TableInfo.columns))
        .where(MetadataRecord.id == meta_uuid)
    )
    record: MetadataRecord | None = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"Metadata '{metadata_id}' not found.")

    conn_result = await db.execute(
        select(DbConnection).where(DbConnection.metadata_id == meta_uuid)
    )
    db_conn: DbConnection | None = conn_result.scalar_one_or_none()
    if db_conn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"No DB connection for metadata '{metadata_id}'.")

    plain_password = decrypt_password(db_conn.encrypted_password)
    conn = _open_connection(db_conn.host, db_conn.port,
                            db_conn.database_name, db_conn.username, plain_password)

    total_columns = 0
    pii_count = 0
    tables_out = []

    try:
        for table in sorted(record.tables, key=lambda t: t.table_name):
            cols_out = []
            for col in sorted(table.columns, key=lambda c: c.ordinal_position):
                total_columns += 1
                col_id_str = str(col.id)
                dtype = col.data_type.lower()

                # Phase 1 — skip numeric/bool/uuid
                if dtype in SKIP_TYPES:
                    cols_out.append({
                        "column_id": col_id_str,
                        "column_name": col.column_name,
                        "is_pii": False,
                        "category": "not_pii",
                    })
                    continue

                # Phase 1b — JSON/JSONB: flatten + LLM
                if dtype in JSON_TYPES:
                    try:
                        samples = _fetch_llm_samples(conn, table.table_name, col.column_name, sample_count)
                        flat = _flatten_json_samples(samples)
                        classifications = _call_llm(col.column_name, flat, table_name=table.table_name)
                        top_cat = max(classifications, key=lambda k: classifications[k])
                        is_pii = top_cat != "not_pii"
                        if is_pii:
                            pii_count += 1
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                         "is_pii": is_pii, "category": top_cat})
                    except Exception:
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                         "is_pii": False, "category": "unknown"})
                    continue

                # Phase 2a — positive PII name rules
                name_result = _pii_name_classify(col.column_name, dtype)
                if name_result is not None:
                    is_pii = name_result != "not_pii"
                    if is_pii:
                        pii_count += 1
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                     "is_pii": is_pii, "category": name_result})
                    continue

                try:
                    # Phase 3 — DB regex (BEFORE NOT_PII check)
                    # Must run first so columns like "id_number" with real TCKN data
                    # are caught even though "number" is a NOT_PII keyword.
                    category = _db_regex_classify(conn, table.table_name, col.column_name)
                    if category is not None:
                        pii_count += 1
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                         "is_pii": True, "category": category})
                        continue

                    # Phase 2c — NOT_PII word-boundary check
                    if _is_not_pii_by_name(col.column_name):
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                         "is_pii": False, "category": "not_pii"})
                        continue

                    # Phase 4 — LLM (truly ambiguous columns only)
                    samples = _fetch_llm_samples(conn, table.table_name, col.column_name, sample_count)
                    classifications = _call_llm(col.column_name, samples, table_name=table.table_name)
                    category = max(classifications, key=lambda k: classifications[k])
                    is_pii = category != "not_pii"
                    if is_pii:
                        pii_count += 1
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                     "is_pii": is_pii, "category": category})
                except Exception:
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name,
                                     "is_pii": False, "category": "unknown"})

            tables_out.append({
                "table_name": table.table_name,
                "pii_count": sum(1 for c in cols_out if c["is_pii"]),
                "columns": cols_out,
            })
    finally:
        conn.close()

    return {
        "metadata_id": metadata_id,
        "database_name": record.database_name,
        "total_columns": total_columns,
        "pii_columns": pii_count,
        "tables": tables_out,
    }
