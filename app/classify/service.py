"""
Classification service — purely LLM-based PII detection as required by the case study.

Pipeline per column:
  1. Skip non-text data types (integer, boolean, uuid, etc.) → these structurally cannot be PII text
  2. Flatten JSON/JSONB columns before sending to LLM
  3. Send column name + sample values to LLM
  4. Parse and normalise the probability distribution
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

# ── PII Categories ─────────────────────────────────────────────────────────────
# Exactly matching the 12 categories listed in the case study PDF + not_pii.
PII_CATEGORIES = [
    "email_address",
    "phone_number",
    "social_security_number",
    "credit_card_number",
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

# ── LLM System Prompt ──────────────────────────────────────────────────────────
SYSTEM_PROMPT = """/no_think
## ROLE
You are an expert data privacy engineer specialised in automated PII (Personally Identifiable Information) discovery within enterprise database systems.
Your classifications are used in compliance pipelines governed by GDPR, KVKK, and CCPA regulations — accuracy is critical.

## OBJECTIVE
Given a database column name and a set of sample values extracted from that column, output a probability distribution across 13 predefined PII categories.
The probabilities represent your confidence that the column stores each type of data.

## CLASSIFICATION CATEGORIES

| # | Category | Description | Examples |
|---|----------|-------------|---------|
| 1 | email_address | Personal and business email addresses | john.doe@gmail.com, user@company.com |
| 2 | phone_number | Mobile, landline, and international phone numbers | +1-555-123-4567, (555) 123-4567, 05321234567 |
| 3 | social_security_number | US Social Security Numbers and similar national ID numbers | 123-45-6789, 987654321 |
| 4 | credit_card_number | Payment card numbers (Visa, MasterCard, etc.) AND IBAN/bank account numbers | 4532-1234-5678-9012, TR33 0006 1005 1978 6457 8413 26 |
| 5 | national_id_number | Government-issued IDs: passport, driver's license, national ID card (non-Turkish) | AB1234567, D123-4567-8901 |
| 6 | full_name | Complete personal name with first and last name | John Michael Smith, Ayşe Kaya, Ali Gökkaya |
| 7 | first_name | Given/first name only | John, Maria, Mehmet, Sarah, Fatma |
| 8 | last_name | Family/surname only | Smith, Johnson, Yılmaz, García, Demir |
| 9 | tckn | Turkish Republic Citizenship Number — exactly 11 digits, first digit non-zero | 12345678901, 38291047652 |
| 10 | home_address | Full physical/residential addresses including street | 123 Main St Apt 4, Atatürk Cad. No:5 İstanbul |
| 11 | date_of_birth | Personal birth dates in any format | 1985-03-15, 15/03/1985, March 15 1985 |
| 12 | ip_address | IPv4 or IPv6 addresses | 192.168.1.1, 2001:0db8::1 |
| 13 | not_pii | Non-personal data: metrics, codes, statuses, product info, system data | order_status, price, product_code, UUID |

## CLASSIFICATION METHODOLOGY

Apply the following reasoning pipeline:

**Step 1 — Structural Pattern Recognition**
Does the data match a known structural format?
- Email: contains `@` and domain
- TCKN: exactly 11 digits, starts with non-zero digit (1-9)
- IP address: four groups of numbers separated by dots (n.n.n.n)
- Phone: digit groups with separators (+, -, spaces, parentheses)
- Credit card: 16-digit groups (4-4-4-4 with optional separators)
- IBAN: country code prefix (TR, DE, GB, etc.) followed by digits
- SSN: three-two-four digit pattern (NNN-NN-NNNN)

**Step 2 — Column Name Semantic Analysis**
The column name is a reliable signal — use it as a strong hint:
- `email`, `mail` → email_address
- `phone`, `tel`, `gsm`, `mobile` → phone_number
- `first_name`, `fname`, `given_name` → first_name
- `last_name`, `lname`, `surname`, `soyad` → last_name
- `full_name`, `fullname` → full_name
- `tckn`, `tc_no`, `kimlik_no` → tckn
- `ssn`, `social_security` → social_security_number
- `dob`, `birth_date`, `date_of_birth`, `birthday` → date_of_birth
- `ip_address`, `ip_addr` → ip_address
- `address`, `street` → home_address
- `card_no`, `credit_card`, `iban`, `account_number` → credit_card_number
- `passport`, `national_id`, `driver_license` → national_id_number
If values and column name conflict, weight the actual values more heavily.

**Step 3 — Contextual Inference**
- Names: capitalised words that are linguistically valid personal names
- Addresses: contain street indicators, building numbers, city/district names
- Dates: recognisable temporal formats — but distinguish birth dates from event timestamps
- Free text columns (notes, comments, description): may contain mixed PII — classify by the most sensitive element found

**Step 4 — Ambiguity Handling**
- If multiple PII types are plausible, distribute probability proportionally
- When genuinely uncertain, distribute low probability across candidates
- Never assign all probability to not_pii if ANY sample looks like PII

## CRITICAL RULES

- Output MUST be valid JSON — no markdown, no code blocks, no explanation
- All 13 keys MUST be present in output
- All values MUST be floats in range [0.0, 1.0]
- Probabilities MUST sum to EXACTLY 1.0
- High confidence example: email column with emails → email_address: 0.97, not_pii: 0.03

## COMMON PITFALLS — AVOID THESE

- Do NOT classify `user_agent`, `browser_version`, `os_version` as `ip_address` — they contain version numbers
- Do NOT classify `city`, `district`, `country`, `region` alone as `home_address` — they need street-level detail
- Do NOT classify order/invoice/reference numbers as `national_id_number`
- Do NOT classify `created_at`, `updated_at`, `hired_at` as `date_of_birth` — these are system/event timestamps
- Do NOT classify `status`, `type`, `code`, `price`, `amount` fields as any PII type

## OUTPUT FORMAT — STRICTLY FOLLOW THIS

Return ONLY this JSON object with your probability values substituted in:

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


# ── Type constants ─────────────────────────────────────────────────────────────

# Postgres types that structurally cannot contain PII text → skip without LLM call.
# This is a structural filter, NOT a classification decision.
SKIP_TYPES = {
    "integer", "bigint", "smallint", "int", "int2", "int4", "int8",
    "serial", "bigserial", "boolean", "bool",
    "numeric", "decimal", "real", "double precision", "float4", "float8",
    "uuid", "bytea", "oid",
}

# Postgres JSON types require flattening before LLM analysis
JSON_TYPES = {"jsonb", "json"}

# Date/timestamp types — let LLM decide based on column name context
DATE_TYPES = {"date", "timestamp", "timestamp without time zone", "timestamp with time zone", "timestamptz"}


# ── Helper functions ───────────────────────────────────────────────────────────

def _flatten_json_samples(samples: list[Any]) -> list[str]:
    """
    Flatten JSON/JSONB column values into readable key: value strings for the LLM.

    Example:
        {"email": "a@b.com", "first_name": "Ali"}
        → 'email: a@b.com | first_name: Ali'
    """
    result = []
    for val in samples:
        if val is None:
            continue
        try:
            obj = val if isinstance(val, dict) else json.loads(str(val))
            if isinstance(obj, dict):
                result.append(" | ".join(f"{k}: {v}" for k, v in obj.items()))
            elif isinstance(obj, list):
                result.append(str(obj[:5]))  # show first 5 elements of arrays
            else:
                result.append(str(val))
        except (ValueError, TypeError):
            result.append(str(val))
    return result


def _fetch_llm_samples(conn: Any, table_name: str, column_name: str, limit: int = 10) -> list[Any]:
    """Fetch non-null sample rows from the target database column."""
    col = f'"{column_name}"'
    tbl = f'"{table_name}"'
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT {col} FROM {tbl} WHERE {col} IS NOT NULL LIMIT %s",
            (limit,),
        )
        return [row[0] for row in cur.fetchall()]


def _open_connection(host: str, port: str, database: str, username: str, password: str) -> Any:
    """Open a psycopg2 connection, raising a clean HTTP 400 on failure."""
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
    """Extract JSON from LLM response, handling markdown code blocks gracefully."""
    # Try direct parse first
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # Try extracting from markdown code block
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass
    # Try finding any JSON object in the response
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    raise ValueError(f"No valid JSON found in LLM response: {text[:300]}")


def _call_llm(column_name: str, sample_values: list[Any], table_name: str = "") -> dict[str, float]:
    """
    Send column name + sample values to the LLM.
    Returns a normalised probability distribution over all PII categories.
    """
    client = OpenAI(
        api_key=settings.OPENAI_API_KEY or "ollama",
        base_url=settings.OPENAI_BASE_URL,
    )

    # Build the user message with table/column context and numbered samples
    samples_str = "\n".join(f"  [{i+1}] {repr(v)}" for i, v in enumerate(sample_values[:50]))
    table_ctx = f"Table  : {table_name}\n" if table_name else ""
    user_message = (
        f"## COLUMN UNDER ANALYSIS\n\n"
        f"{table_ctx}"
        f"Column : {column_name}\n"
        f"Samples: {len(sample_values)} non-null values\n\n"
        f"{samples_str}\n\n"
        f"## YOUR TASK\n\n"
        f"Analyse the column name AND all sample values above. "
        f"Return the probability distribution across all 13 PII categories. "
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
    # Enable JSON mode for OpenAI (not needed for Ollama)
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

    # Build result dict — default missing keys to 0.0
    result: dict[str, float] = {cat: float(data.get(cat, 0.0)) for cat in PII_CATEGORIES}

    # Normalise so probabilities always sum to 1.0
    total = sum(result.values())
    if total > 0:
        result = {k: round(v / total, 6) for k, v in result.items()}
    else:
        # Fallback: LLM returned all zeros or junk
        result = {k: 0.0 for k in PII_CATEGORIES}
        result["not_pii"] = 1.0

    return result


# ── Public API ─────────────────────────────────────────────────────────────────

async def classify_column(
    db: AsyncSession,
    column_id: str,
    sample_count: int = 10,
) -> dict[str, Any]:
    """
    Classify a single database column for PII using LLM.

    Steps:
      1. Fetch column + connection info from discovery DB
      2. If column type is structurally non-text → return not_pii immediately
      3. Fetch sample_count non-null values from target DB
      4. Flatten JSON values if needed
      5. Send to LLM → get probability distribution
      6. Return full result with top_category, top_probability, and classifications
    """
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

    conn_result = await db.execute(
        select(DbConnection).where(DbConnection.metadata_id == metadata_id)
    )
    db_conn: DbConnection | None = conn_result.scalar_one_or_none()
    if db_conn is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No DB connection found for metadata '{metadata_id}'.",
        )

    try:
        plain_password = decrypt_password(db_conn.encrypted_password)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to decrypt stored password: {exc}",
        ) from exc

    data_type = column_obj.data_type.lower()

    # Structural skip: numeric/bool/uuid types cannot contain PII text
    if data_type in SKIP_TYPES:
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

    # Open connection to target DB and classify via LLM
    conn = _open_connection(
        db_conn.host, db_conn.port,
        db_conn.database_name, db_conn.username, plain_password,
    )
    try:
        samples = _fetch_llm_samples(
            conn, table_obj.table_name, column_obj.column_name, sample_count,
        )
        # Flatten JSON/JSONB content so the LLM sees field names + values
        if data_type in JSON_TYPES:
            samples = _flatten_json_samples(samples)

        classifications = _call_llm(
            column_obj.column_name, samples, table_name=table_obj.table_name,
        )
    finally:
        conn.close()

    top_category = max(classifications, key=lambda k: classifications[k])

    return {
        "column_id": column_id,
        "column_name": column_obj.column_name,
        "table_name": table_obj.table_name,
        "data_type": data_type,
        "sample_count": len(samples),
        "top_category": top_category,
        "top_probability": classifications.get(top_category, 1.0),
        "classifications": classifications,
    }


async def discover_metadata(
    db: AsyncSession,
    metadata_id: str,
    sample_count: int = 10,
) -> dict[str, Any]:
    """
    Full PII discovery scan for an entire metadata record (= target database).

    For each column:
      - Numeric/bool/uuid types → not_pii (structural skip, no LLM call)
      - All other types → fetch samples → LLM → probability distribution
      - JSON/JSONB → flatten before LLM
    """
    try:
        meta_uuid = uuid.UUID(metadata_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid metadata_id: '{metadata_id}'",
        )

    result = await db.execute(
        select(MetadataRecord)
        .options(selectinload(MetadataRecord.tables).selectinload(TableInfo.columns))
        .where(MetadataRecord.id == meta_uuid)
    )
    record: MetadataRecord | None = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Metadata '{metadata_id}' not found.",
        )

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

                # Structural skip — integers, booleans, UUIDs cannot be PII text
                if dtype in SKIP_TYPES:
                    cols_out.append({
                        "column_id": col_id_str,
                        "column_name": col.column_name,
                        "is_pii": False,
                        "category": "not_pii",
                    })
                    continue

                # LLM classification for all text-capable types
                try:
                    samples = _fetch_llm_samples(
                        conn, table.table_name, col.column_name, sample_count,
                    )

                    # Flatten JSON/JSONB so LLM sees field names + values
                    if dtype in JSON_TYPES:
                        samples = _flatten_json_samples(samples)

                    classifications = _call_llm(
                        col.column_name, samples, table_name=table.table_name,
                    )
                    top_cat = max(classifications, key=lambda k: classifications[k])
                    is_pii = top_cat != "not_pii"
                    if is_pii:
                        pii_count += 1

                    cols_out.append({
                        "column_id": col_id_str,
                        "column_name": col.column_name,
                        "is_pii": is_pii,
                        "category": top_cat,
                    })

                except Exception as exc:
                    # On any error, mark as unknown and continue scanning
                    cols_out.append({
                        "column_id": col_id_str,
                        "column_name": col.column_name,
                        "is_pii": False,
                        "category": "unknown",
                    })

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
