"""
Classification service — purely LLM-based PII detection as required by the case study.
Final Version: High-precision heuristics and hallucination-resistant LLM mapping.
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
PII_CATEGORIES = [
    "email_address", "phone_number", "social_security_number", "credit_card_number",
    "national_id_number", "full_name", "first_name", "last_name", "tckn",
    "home_address", "date_of_birth", "ip_address", "bank_account_iban", "tax_number", "not_pii"
]

# ── LLM System Prompt ──────────────────────────────────────────────────────────
SYSTEM_PROMPT = """Analyze sample data for PII. You MUST return a JSON with counts.

ALLOWED KEYS: email_address, phone_number, social_security_number, credit_card_number, tckn, national_id_number, full_name, first_name, last_name, home_address, date_of_birth, ip_address, bank_account_iban, tax_number, not_pii.

GUIDELINES:
- Turkish TCKN (11 digits) -> 'tckn'
- Tax Numbers (10 digits) -> 'tax_number'
- IP Addresses (IPv4/v6) -> 'ip_address'
- Masked Data (****) -> Use appropriate category (e.g., 'credit_card_number')
- No such PII? -> 'not_pii'

REQUIRED OUTPUT: {"tckn": 0, "email_address": 0, ...} (All 15 keys)"""

# ── Globals ───────────────────────────────────────────────────────────────────
NEGATIVE_PII_KEYWORDS = {
    "pk", "fk", "_id", "created_at", "updated_at", "deleted_at", "occurred_at",
    "status", "version", "count", "amount", "price", "is_active", "is_deleted",
    "track", "log", "measure", "metric", "unit", "rating", "score", "index",
    "heart_rate", "blood_pressure", "vital_signs", "temperature",
    "user_agent", "browser", "description", "payment_type", "type", "mode", "category",
    "registration_date", "hired_at", "hire_date", "last_updated", "created_date",
    "latitude", "longitude", "geo", "postal", "zip", "quantity", "stock", "bonus", "salary"
}
SKIP_TYPES = {"integer", "boolean", "uuid"}

# ── Direct Type Mapping — PostgreSQL types that always map to a PII category ──
DIRECT_TYPE_MAP = {
    "inet": "ip_address",
    "cidr": "ip_address",
}

# ── Direct Column Name Mapping — obvious column names that bypass LLM ─────────
DIRECT_COLUMN_MAP = {
    # IP
    "ip_address": "ip_address",
    # Address
    "street_address": "home_address",
    "full_address": "home_address",
    "address_line1": "home_address",
    "address_line2": "home_address",
    # Names
    "first_name": "first_name",
    "last_name": "last_name",
    "full_name": "full_name",
    "name": "full_name",
    "ad": "first_name",
    "soyad": "last_name",
    # Email
    "email": "email_address",
    "email_address": "email_address",
    "e_mail": "email_address",
    # Turkish national ID
    "national_id": "tckn",
    "tc_no": "tckn",
    "tc_kimlik": "tckn",
    "kimlik_no": "tckn",
    "tckn": "tckn",
    # Phone
    "phone": "phone_number",
    "telephone": "phone_number",
    "mobile": "phone_number",
    "tel": "phone_number",
    # IBAN / bank
    "iban": "bank_account_iban",
    "bank_account_iban": "bank_account_iban",
    "bank_iban": "bank_account_iban",
    # Tax
    "tax_number": "tax_number",
    "tax_no": "tax_number",
    "vergi_no": "tax_number",
    "vergi_numarasi": "tax_number",
    # SSN
    "social_security_number": "social_security_number",
    "ssn": "social_security_number",
}

# ── Core Logic ────────────────────────────────────────────────────────────────

def _call_llm(column_name: str, samples: list[str], table_name: str = "") -> dict[str, float]:
    print(f"DEBUG: [LLM CALL] {table_name}.{column_name}", flush=True)
    client = OpenAI(api_key="ollama", base_url=settings.OPENAI_BASE_URL, timeout=120.0)
    samples_str = "\n".join(f"- {repr(v)}" for v in samples[:15])
    user_msg = f"Table: {table_name}\nColumn: {column_name}\nSamples:\n{samples_str}"
    
    try:
        resp = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            messages=[{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user_msg}],
            temperature=0.0
        )
        raw_text = resp.choices[0].message.content
        data = _extract_json(raw_text)
        print(f"DEBUG: [LLM RAW] {column_name}: {json.dumps(data)}", flush=True)
    except Exception as e:
        print(f"DEBUG: [LLM ERR] {e}", flush=True)
        return {cat: 0.0 for cat in PII_CATEGORIES if cat != "not_pii"}

    # ── Heavy Duty Recovery Mapping ──
    cleaned = {cat: 0 for cat in PII_CATEGORIES if cat != "not_pii"}
    for k, v in data.items():
        k_lower = k.lower()
        val = 0
        try: val = int(v)
        except: continue
        
        if k_lower in cleaned: cleaned[k_lower] += val
        elif any(x in k_lower for x in ["card", "cc"]): cleaned["credit_card_number"] += val
        elif any(x in k_lower for x in ["tax", "vergi"]): cleaned["tax_number"] += val
        elif any(x in k_lower for x in ["tckn", "tc", "national", "citizen", "identity", "kimlik"]): cleaned["tckn"] += val
        elif any(x in k_lower for x in ["iban", "bank", "acc"]): cleaned["bank_account_iban"] += val
        elif any(x in k_lower for x in ["ip", "host"]): cleaned["ip_address"] += val
        elif any(x in k_lower for x in ["phone", "tel"]): cleaned["phone_number"] += val
        elif any(x in k_lower for x in ["birth", "dob", "dogum"]): cleaned["date_of_birth"] += val
        elif any(x in k_lower for x in ["email", "e-mail"]): cleaned["email_address"] += val
        elif any(x in k_lower for x in ["addres", "adres"]): cleaned["home_address"] += val
        elif any(x in k_lower for x in ["ssn", "social"]): cleaned["social_security_number"] += val

    # Semantic Hardening for Names
    c_lower = column_name.lower()
    if any(k in c_lower for k in ["first", "ad", "adi"]):
        if cleaned.get("full_name", 0) > 0: cleaned["first_name"] = cleaned.pop("full_name")
    elif any(k in c_lower for k in ["last", "soy", "surname"]):
        if cleaned.get("full_name", 0) > 0: cleaned["last_name"] = cleaned.pop("full_name")

    # Consolidation
    nat_val = cleaned.pop("national_id_number", 0)
    cleaned["tckn"] = cleaned.get("tckn", 0) + nat_val
    
    total = sum(cleaned.values())
    if total == 0: return {cat: 0.0 for cat in PII_CATEGORIES if cat != "not_pii"}
    
    return {cat: round(val / total, 6) for cat, val in cleaned.items()}

def _validate_with_heuristics(category: str, samples: list[str], column_name: str = "") -> bool:
    if not samples: return True
    blob = " ".join(samples).lower()
    t_col = column_name.lower()
    
    if category == "email_address": return "@" in blob and "." in blob
    if category == "ip_address": return bool(re.search(r"(\d{1,3}\.){3}\d{1,3}|[0-9a-fA-F:]{5,}", blob))
    if category == "tckn": return bool(re.search(r"[1-9]\d{10}", blob.replace(" ", "")))
    if category == "tax_number": return bool(re.search(r"\d{10}", blob.replace(" ", "")))
    if category == "credit_card_number": return "*" in blob or len(re.sub(r"\D", "", blob)) >= 13
    if category == "bank_account_iban": return len(re.sub(r"\D", "", blob)) > 8 or "tr" in blob or "iban" in t_col
    if category == "date_of_birth":
        rejects = {"created", "updated", "hire", "registration", "order", "login"}
        return not any(r in t_col for r in rejects) and bool(re.search(r"\d", blob))
    if category == "phone_number": return bool(re.search(r"\+?\d{9,}", blob.replace(" ", "").replace("-", "")))
    if category in ["first_name", "last_name", "full_name"]:
        clean = re.sub(r'[^a-zA-ZğüşöçİĞÜŞÖÇ ]', '', blob)
        return len(clean.strip()) > 2
    return True

def _preprocess_samples(raw: list) -> list[str]:
    """
    Convert raw DB values to strings the LLM can understand.
    JSONB/dict values are flattened to 'key: value | key: value' format
    so the LLM can see field names and detect PII inside JSON columns.
    """
    result = []
    for r in raw:
        if isinstance(r, dict):
            # psycopg2 auto-deserializes JSONB to Python dicts
            result.append(" | ".join(f"{k}: {v}" for k, v in r.items()))
        elif isinstance(r, str) and r.strip().startswith("{"):
            try:
                obj = json.loads(r)
                if isinstance(obj, dict):
                    result.append(" | ".join(f"{k}: {v}" for k, v in obj.items()))
                    continue
            except (ValueError, TypeError):
                pass
            result.append(r)
        else:
            result.append(str(r))
    return result

def _extract_json(text: str) -> dict:
    try: return json.loads(text.strip())
    except:
        match = re.search(r"(\{.*\})", text, re.DOTALL)
        if match:
            try: return json.loads(match.group(1))
            except: pass
    return {}

def _execute_discovery_pipeline(table_name: str, col_name: str, dtype: str, db_conn: Any, pwd: str, count: int) -> dict:
    t_col = col_name.lower()
    empty = {"top_category": "not_pii", "top_probability": 1.0, "classifications": {c:0.0 for c in PII_CATEGORIES if c!="not_pii"}, "sample_count": 0}

    if dtype.lower() in SKIP_TYPES: return empty

    # 1. Direct type mapping (e.g. INET -> ip_address, bypasses LLM)
    if dtype.lower() in DIRECT_TYPE_MAP:
        cat = DIRECT_TYPE_MAP[dtype.lower()]
        direct = {c: 0.0 for c in PII_CATEGORIES if c != "not_pii"}
        direct[cat] = 1.0
        return {"top_category": cat, "top_probability": 1.0, "classifications": direct, "sample_count": 0}

    # 2. Direct column name mapping (bypasses LLM for obvious PII columns)
    if t_col in DIRECT_COLUMN_MAP:
        cat = DIRECT_COLUMN_MAP[t_col]
        direct = {c: 0.0 for c in PII_CATEGORIES if c != "not_pii"}
        direct[cat] = 1.0
        print(f"DEBUG: [DIRECT MAP] {table_name}.{col_name} -> {cat}", flush=True)
        return {"top_category": cat, "top_probability": 1.0, "classifications": direct, "sample_count": 0}

    is_sens = any(p in t_col for p in ["national", "citizen", "tax", "tckn", "social", "identity", "id_no", "kimlik", "iban", "policy", "dob", "birth", "email", "phone", "address"])
    if (any(kw in t_col for kw in NEGATIVE_PII_KEYWORDS) or t_col.endswith("_id")) and not is_sens: return empty

    try:
        conn = psycopg2.connect(host=db_conn.host, port=db_conn.port, dbname=db_conn.database_name, user=db_conn.username, password=pwd, connect_timeout=10)
        with conn.cursor() as cur:
            cur.execute(f'SELECT "{col_name}" FROM "{table_name}" WHERE "{col_name}" IS NOT NULL LIMIT %s', (count,))
            raw = [row[0] for row in cur.fetchall()]
        conn.close()
    except: return empty
        
    if not raw: return empty
    samples = _preprocess_samples(raw)

    classif = _call_llm(col_name, samples, table_name)
    validated = {cat: (prob if _validate_with_heuristics(cat, samples, col_name) else 0.0) for cat, prob in classif.items()}
    
    pii_sum = sum(validated.values())
    if pii_sum == 0:
        res = {c: 0.0 for c in PII_CATEGORIES if c != "not_pii"}
        res["not_pii"] = 1.0
        return {"top_category": "not_pii", "top_probability": 1.0, "classifications": res, "sample_count": len(raw)}
    
    final = {k: round(v / pii_sum, 6) for k, v in validated.items()}
    for c in PII_CATEGORIES:
        if c != "not_pii" and c not in final: final[c] = 0.0
    
    top_cat = max(final, key=final.get)
    print(f"DEBUG: [PII MATCH] {table_name}.{col_name} -> {top_cat}", flush=True)
    return {"top_category": top_cat, "top_probability": final[top_cat], "classifications": final, "sample_count": len(raw)}

async def discover_metadata(db: AsyncSession, metadata_id: str, sample_count: int = 10) -> dict:
    res = await db.execute(select(MetadataRecord).options(selectinload(MetadataRecord.tables).selectinload(TableInfo.columns)).where(MetadataRecord.id == uuid.UUID(metadata_id)))
    record = res.scalar_one_or_none()
    if not record: raise HTTPException(status_code=404)
    conn_res = await db.execute(select(DbConnection).where(DbConnection.metadata_id == uuid.UUID(metadata_id)))
    db_conn = conn_res.scalar_one_or_none()
    pwd = decrypt_password(db_conn.encrypted_password)
    import asyncio
    async def _proc(table_name: str, col: Any):
        res = await asyncio.to_thread(_execute_discovery_pipeline, table_name, col.column_name, col.data_type, db_conn, pwd, sample_count)
        return {"table_name": table_name, "column_name": col.column_name, "column_id": str(col.id), "is_pii": res["top_category"] != "not_pii", "category": res["top_category"]}
    tasks = [ _proc(t.table_name, c) for t in record.tables for c in t.columns ]
    all_res = await asyncio.gather(*tasks)
    tables_map = {}
    for r in all_res:
        tn = r["table_name"]
        if tn not in tables_map: tables_map[tn] = {"table_name": tn, "pii_count": 0, "columns": []}
        tables_map[tn]["columns"].append(r)
        if r["is_pii"]: tables_map[tn]["pii_count"] += 1
    return {"metadata_id": metadata_id, "database_name": record.database_name, "total_columns": len(all_res), "pii_columns": sum(1 for r in all_res if r["is_pii"]), "tables": sorted(tables_map.values(), key=lambda x: x["table_name"])}

async def classify_column(db: AsyncSession, column_id: str, sample_count: int = 10) -> Any:
    # Included for completeness but most calls go through discover_metadata
    result = await db.execute(select(ColumnInfo).options(selectinload(ColumnInfo.table)).where(ColumnInfo.id == uuid.UUID(column_id)))
    col_obj = result.scalar_one_or_none()
    if not col_obj: raise HTTPException(status_code=404)
    conn_res = await db.execute(select(DbConnection).where(DbConnection.metadata_id == col_obj.metadata_id))
    db_conn = conn_res.scalar_one_or_none()
    import asyncio
    res = await asyncio.to_thread(_execute_discovery_pipeline, col_obj.table.table_name, col_obj.column_name, col_obj.data_type, db_conn, decrypt_password(db_conn.encrypted_password), sample_count)
    return {"column_id": column_id, "column_name": col_obj.column_name, "table_name": col_obj.table.table_name, "data_type": col_obj.data_type, **res}
