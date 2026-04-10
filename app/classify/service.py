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

# All supported PII categories (16 including not_pii)
PII_CATEGORIES = [
    "email_address",
    "phone_number",
    "social_security_number",
    "credit_card_number",
    "iban",                  # Bank account / IBAN numbers
    "national_id_number",
    "full_name",
    "first_name",
    "last_name",
    "tckn",
    "home_address",
    "date_of_birth",
    "ip_address",
    "health_data",           # Medical/health information (GDPR Article 9)
    "credential",            # Passwords, secrets, API keys
    "not_pii",
]

SYSTEM_PROMPT = """/no_think
You are a data privacy expert specialising in PII (Personally Identifiable Information) detection.

Your task is to analyse a list of sample values from a single database column and determine the probability that the column belongs to each of the following 16 categories:

1. email_address     – Email addresses (e.g., user@example.com)
2. phone_number      – Phone numbers in any format (e.g., +1-555-123-4567, 05551234567)
3. social_security_number – US Social Security Numbers (e.g., 123-45-6789)
4. credit_card_number – Credit/debit card numbers (e.g., 4111111111111111, 4111-1111-1111-1111)
5. iban              – Bank account / IBAN numbers (e.g., TR33 0006 1005 1978 6457 8413 26)
6. national_id_number – National ID numbers from any country (non-Turkish)
7. full_name         – Full names (first + last, e.g., Ali Gökkaya, Ali Veli)
8. first_name        – First/given names only (e.g., John, Mary, Ali)
9. last_name         – Last/family names only (e.g., Smith, Johnson, Yılmaz)
10. tckn             – Turkish Citizenship Number (T.C. Kimlik No): exactly 11 digits, first digit non-zero
11. home_address     – Physical addresses (street, city, postal code, etc.)
12. date_of_birth    – Dates of birth in any format (e.g., 1990-05-15, 15/05/1990)
13. ip_address       – IPv4 or IPv6 addresses (e.g., 192.168.1.1, 2001:db8::1)
14. health_data      – Medical/health information (e.g., diagnosis, treatment plans, prescriptions, blood type, vital signs) — GDPR Article 9 special category
15. credential       – Passwords, secrets, API keys, encrypted tokens (even if hashed/encrypted, the column stores authentication credentials)
16. not_pii          – Data that does not match any PII category (e.g., product codes, prices, counts)

Rules:
- Probabilities MUST sum to exactly 1.0.
- Each probability is a float between 0.0 and 1.0.
- Return ONLY a valid JSON object with all 16 keys listed above.
- Do not include any explanation outside the JSON.
- Consider the column name as a strong hint, but base the classification primarily on the actual sample values.
- If the sample values are in JSON or complex text format and contain multiple different types of PII (e.g. an email and a name together), assign the highest probability to the most sensitive or prominent PII category found, rather than not_pii.
- If the data is clearly not any type of PII, assign most probability to "not_pii".

Example response format:
{
  "email_address": 0.95,
  "phone_number": 0.01,
  "social_security_number": 0.0,
  "credit_card_number": 0.0,
  "iban": 0.0,
  "national_id_number": 0.0,
  "full_name": 0.0,
  "first_name": 0.0,
  "last_name": 0.0,
  "tckn": 0.0,
  "home_address": 0.0,
  "date_of_birth": 0.0,
  "ip_address": 0.0,
  "health_data": 0.0,
  "credential": 0.0,
  "not_pii": 0.04
}"""


# ── Smart discovery constants ─────────────────────────────────────────────────

SKIP_TYPES = {
    "integer", "bigint", "smallint", "int", "int2", "int4", "int8",
    "serial", "bigserial", "boolean", "bool",
    "numeric", "decimal", "real", "double precision", "float4", "float8",
    "uuid", "bytea", "oid",
}

# Coordinate column name keywords — PII only if the table also has other PII
COORDINATE_KEYWORDS = ["latitude", "longitude", "lat", "lng", "lon", "coord"]

PII_NAME_RULES: list[tuple[str, list[str]]] = [
    ("email_address",          ["email", "mail"]),
    ("phone_number",           ["phone", "tel", "gsm", "mobile", "cellular"]),
    # tckn: Turkish citizen ID — various naming conventions
    ("tckn",                   ["tckn", "tc_kimlik", "tc_no", "kimlik_no", "citizen_no", "citizen_id", "kimlik"]),
    ("social_security_number", ["ssn", "social_security", "social_sec"]),
    ("credit_card_number",     ["credit_card", "card_number", "card_no"]),
    # iban: separate category from credit_card — bank account / IBAN numbers
    ("iban",                   ["iban", "bank_account", "bank_iban", "holder_iban", "account_number"]),
    ("ip_address",             ["ip_address", "ip_addr", "ipaddress"]),
    ("full_name",              ["full_name", "fullname"]),
    # first_name: added fname, given to catch name_given / fname column patterns
    ("first_name",             ["first_name", "firstname", "given_name", "fname", "given", "name_first"]),
    # last_name: added lname, family to catch name_family / lname column patterns
    ("last_name",              ["last_name", "lastname", "surname", "family_name", "soyad", "lname", "family", "name_last"]),
    ("home_address",           ["street_address", "home_address", "billing_address"]),
    ("date_of_birth",          ["date_of_birth", "birth_date", "dob", "birthday"]),
    ("national_id_number",     ["national_id", "passport", "driver_license", "drivers_license"]),
    # national_id covers tax IDs too
    ("national_id_number",     ["tax_number", "tax_no", "vergi_no", "tax_id"]),
    # health_data: GDPR Article 9 special category — medical information
    ("health_data",            ["diagnosis", "treatment", "vital_signs", "prescription", "blood_type",
                                "medical_history", "lab_result", "allergy", "symptom", "condition",
                                "blood_group", "health"]),
    # credential: passwords and authentication secrets (even if encrypted/hashed)
    ("credential",             ["password", "passwd", "secret", "credentials",
                                "encrypted_password", "hashed_password", "api_key", "auth_token"]),
]

# Data types that represent dates/timestamps
DATE_TYPES = {"date", "timestamp", "timestamp without time zone", "timestamp with time zone", "timestamptz"}

# NOT_PII_KEYWORDS uses WORD-BOUNDARY matching (split column name by "_").
# e.g. "payment_status" → ["payment","status"] → "status" matches → not_pii
# e.g. "notes"          → ["notes"]            → no match         → Phase 3
# Removed: "no", "num", "number", "_id" — too broad as substrings,
#   caused false negatives on "notes", "citizen_no", "id_number", "tax_id" etc.
NOT_PII_KEYWORDS = [
    "count", "amount", "price", "cost", "total",
    "quantity", "status", "code", "type", "rating",
    "percentage", "level", "stock", "weight", "score",
    # "number" is safe here because DB regex runs FIRST:
    # real PII like id_number (TCKN) gets caught by DB regex before this check fires.
    # Generic columns like badge_number, order_number correctly fall through to not_pii.
    "number",
    "method", "provider",
    "position", "language", "currency", "timezone", "locale",
    "flag", "label",
]

# Table name keywords that suggest the table holds personal/individual data.
# Used for coordinate (lat/lng) PII decision: coordinates are only PII
# when they belong to a person-linked table, NOT institutional tables.
PERSONAL_TABLE_KEYWORDS = [
    "patient", "person", "user", "customer", "employee",
    "staff", "member", "client", "contact", "individual",
    "shipping",   # shipping_addresses holds personal recipient coords
]


def _pii_name_classify(col_name: str, data_type: str) -> str | None:
    """
    Phase 2a — Positive PII name rules only.
    Returns a PII category if the column name clearly matches a known PII pattern,
    or 'not_pii' for date columns that are NOT birth dates.
    Returns None when uncertain → caller proceeds to DB regex then NOT_PII check.
    """
    name_lower = col_name.lower()

    # Date/timestamp columns → only PII if name clearly indicates birth date.
    # Prevents hire_date, start_date etc. from being classified as date_of_birth.
    if data_type.lower() in DATE_TYPES:
        birth_keywords = ["date_of_birth", "birth_date", "dob", "birthday", "dogum", "born", "birth"]
        if any(kw in name_lower for kw in birth_keywords):
            return "date_of_birth"
        return "not_pii"

    # Check PII name rules (substring match — intentional for multi-word patterns)
    for category, keywords in PII_NAME_RULES:
        if any(kw in name_lower for kw in keywords):
            return category

    return None  # uncertain → DB regex next


def _is_not_pii_by_name(col_name: str) -> bool:
    """
    Phase 2c — NOT_PII keyword check using WORD-BOUNDARY matching.
    Called AFTER DB regex has already confirmed no structured PII exists in the data.

    Split column name by "_" so "badge_number" → {"badge","number"} → "number" matches.
    This avoids false negatives like "no" matching "notes" or "number" matching "id_number"
    (because id_number values would have already been caught by DB regex as tckn/phone).

    Examples:
        "badge_number"   → {"badge","number"}   → "number" in keywords → True  (not PII)
        "payment_status" → {"payment","status"} → "status" in keywords → True  (not PII)
        "citizen_no"     → {"citizen","no"}     → neither matches      → False (→ LLM)
    """
    name_parts = set(col_name.lower().split("_"))
    return any(kw in name_parts for kw in NOT_PII_KEYWORDS)


JSON_TYPES = {"jsonb", "json"}




def _flatten_json_samples(samples: list[Any]) -> list[str]:
    """
    Flatten JSON/JSONB column values into readable strings for the LLM.

    Example:
        {"email": "a@b.com", "first_name": "Ali"}
        → 'email: a@b.com | first_name: Ali'

    This lets the LLM see actual field names + values inside the JSON,
    making PII detection inside audit logs / event columns possible.
    """
    result = []
    for val in samples:
        if val is None:
            continue
        try:
            # psycopg2 returns jsonb as dict already
            if isinstance(val, dict):
                obj = val
            else:
                obj = json.loads(str(val))

            if isinstance(obj, dict):
                # Flatten one level of keys
                parts = [f"{k}: {v}" for k, v in obj.items()]
                result.append(" | ".join(parts))
            else:
                result.append(str(val))
        except (ValueError, TypeError):
            result.append(str(val))
    return result


def _fetch_llm_samples(
    conn: Any,
    table_name: str,
    column_name: str,
    limit: int = 10,
) -> list[Any]:
    """
    Fetch a small number of non-null rows to send to the LLM.
    Only called when DB-side regex finds nothing (unstructured PII like names/addresses).
    """
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
            host=host,
            port=int(port),
            dbname=database,
            user=username,
            password=password,
            connect_timeout=10,
        )
    except psycopg2.OperationalError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Failed to connect to target database: {exc}",
        ) from exc


def _extract_json(text: str) -> dict:
    """Extract JSON from LLM response, handling markdown code blocks and extra text."""
    # Try direct parse first
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Strip markdown code block if present
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    # Find first { ... } block
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

    # Serialise sample values safely
    samples_str = "\n".join(
        f"  - {repr(v)}" for v in sample_values[:50]  # cap at 50 for token safety
    )

    table_context = f"Table name: {table_name}\n" if table_name else ""
    user_message = (
        f"{table_context}"
        f"Column name: {column_name}\n\n"
        f"Sample values ({len(sample_values)} rows):\n{samples_str}\n\n"
        "Classify this column according to the 13 PII categories described in the system prompt. "
        "Consider the table name and column name as strong context clues. "
        "For example, a 'description' column in a 'products' table likely contains product info, not personal data. "
        "Return ONLY a valid JSON object with all 13 keys, no extra text."
    )

    # Build kwargs — only add response_format for OpenAI-hosted endpoints
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

    # Ensure all categories are present and values are floats
    result: dict[str, float] = {}
    for cat in PII_CATEGORIES:
        result[cat] = float(data.get(cat, 0.0))

    # Normalise so probabilities sum to 1.0 (handle small floating-point drift)
    total = sum(result.values())
    if total > 0:
        result = {k: round(v / total, 6) for k, v in result.items()}
    else:
        # Fallback – assign everything to not_pii
        result = {k: 0.0 for k in PII_CATEGORIES}
        result["not_pii"] = 1.0

    return result


async def classify_column(
    db: AsyncSession,
    column_id: str,
    sample_count: int = 10,
) -> dict[str, Any]:
    """
    Main classification workflow:
    1. Resolve column → table → metadata → connection
    2. Fetch sample data from target DB
    3. Call LLM for classification
    4. Return structured result
    """
    # 1. Look up ColumnInfo
    result = await db.execute(
        select(ColumnInfo)
        .options(selectinload(ColumnInfo.table))
        .where(ColumnInfo.id == uuid.UUID(column_id))
    )
    column_obj: ColumnInfo | None = result.scalar_one_or_none()
    if column_obj is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Column '{column_id}' not found.",
        )

    table_obj: TableInfo = column_obj.table
    metadata_id = column_obj.metadata_id

    # 2. Look up DbConnection
    conn_result = await db.execute(
        select(DbConnection).where(DbConnection.metadata_id == metadata_id)
    )
    db_conn: DbConnection | None = conn_result.scalar_one_or_none()
    if db_conn is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No DB connection found for metadata '{metadata_id}'.",
        )

    # 3. Decrypt password
    try:
        plain_password = decrypt_password(db_conn.encrypted_password)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to decrypt stored password: {exc}",
        ) from exc

    data_type = column_obj.data_type.lower()
    conn = _open_connection(
        db_conn.host, db_conn.port,
        db_conn.database_name, db_conn.username, plain_password,
    )

    try:
        if data_type in JSON_TYPES:
            samples = _fetch_llm_samples(conn, table_obj.table_name, column_obj.column_name, sample_count)
            flat = _flatten_json_samples(samples)
            classifications = _call_llm(column_obj.column_name, flat, table_name=table_obj.table_name)
            category = max(classifications, key=lambda k: classifications[k])
        else:
            samples = _fetch_llm_samples(conn, table_obj.table_name, column_obj.column_name, sample_count)
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
) -> dict[str, Any]:
    """
    Full PII discovery for an entire metadata record.
    Uses 3-phase smart filtering to minimise LLM calls.
    """
    try:
        meta_uuid = uuid.UUID(metadata_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid metadata_id: '{metadata_id}'",
        )

    # Load metadata with tables and columns
    result = await db.execute(
        select(MetadataRecord)
        .options(
            selectinload(MetadataRecord.tables).selectinload(TableInfo.columns),
        )
        .where(MetadataRecord.id == meta_uuid)
    )
    record: MetadataRecord | None = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Metadata '{metadata_id}' not found.",
        )

    # Load DB connection
    conn_result = await db.execute(
        select(DbConnection).where(DbConnection.metadata_id == meta_uuid)
    )
    db_conn: DbConnection | None = conn_result.scalar_one_or_none()
    if db_conn is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No DB connection for metadata '{metadata_id}'.",
        )

    plain_password = decrypt_password(db_conn.encrypted_password)
    conn = _open_connection(
        db_conn.host, db_conn.port,
        db_conn.database_name, db_conn.username, plain_password,
    )

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

                # Phase 1 – skip numeric/bool/uuid types
                if dtype in SKIP_TYPES:
                    col_name_lower = col.column_name.lower()
                    is_coordinate = any(kw in col_name_lower for kw in COORDINATE_KEYWORDS)
                    cols_out.append({
                        "column_id": col_id_str,
                        "column_name": col.column_name,
                        "is_pii": False,
                        "category": "not_pii",
                        "_pending_coordinate": is_coordinate,
                    })
                    continue

                # Phase 1b – JSON/JSONB: flatten content and send to LLM
                if dtype in JSON_TYPES:
                    try:
                        samples = _fetch_llm_samples(conn, table.table_name, col.column_name)
                        flat = _flatten_json_samples(samples)
                        classifications = _call_llm(col.column_name, flat, table_name=table.table_name)
                        top_cat = max(classifications, key=lambda k: classifications[k])
                        is_pii = top_cat != "not_pii"
                        if is_pii:
                            pii_count += 1
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": is_pii, "category": top_cat})
                    except Exception:
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": False, "category": "unknown"})
                    continue

                # Phase 2a – positive PII name rules (date handling + keyword rules)
                name_result = _pii_name_classify(col.column_name, dtype)
                if name_result is not None:
                    is_pii = name_result != "not_pii"
                    if is_pii:
                        pii_count += 1
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": is_pii, "category": name_result})
                    continue

                try:
                    # Phase 2c – NOT_PII keyword check
                    # Safe to skip LLM for obviously non-PII columns like "status", "price"
                    if _is_not_pii_by_name(col.column_name):
                        cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": False, "category": "not_pii"})
                        continue

                    # Phase 4 – LLM (only truly ambiguous columns reach here)
                    samples = _fetch_llm_samples(conn, table.table_name, col.column_name)
                    classifications = _call_llm(col.column_name, samples, table_name=table.table_name)
                    category = max(classifications, key=lambda k: classifications[k])
                    is_pii = category != "not_pii"
                    if is_pii:
                        pii_count += 1
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": is_pii, "category": category})
                except Exception:
                    cols_out.append({"column_id": col_id_str, "column_name": col.column_name, "is_pii": False, "category": "unknown"})

            # Post-process: mark coordinate (lat/lng) columns as PII only when
            # the table is a PERSONAL data table (patient, user, staff, etc.)
            # AND it has other PII columns.
            # Clinic/branch location tables have coordinates too but they are
            # NOT personal PII — we guard against that cascade false positive.
            table_name_lower = table.table_name.lower()
            table_is_personal = any(kw in table_name_lower for kw in PERSONAL_TABLE_KEYWORDS)
            table_has_pii = any(c["is_pii"] for c in cols_out)

            for col_entry in cols_out:
                if col_entry.pop("_pending_coordinate", False):
                    if table_is_personal and table_has_pii:
                        col_entry["is_pii"] = True
                        col_entry["category"] = "home_address"
                        pii_count += 1

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
