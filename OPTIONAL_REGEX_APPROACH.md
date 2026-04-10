# Optional Optimization: PostgreSQL DB-Level Regex Classification

During the development of this case study, an advanced DB-level regex classification layer was designed to optimize performance. 

## Rationale
While the case study explicitly requested **Data classification using LLM**, sending every single column to the LLM (even highly structured data like Emails, SSNs, and IBANs) is not always cost-effective or fast in a production environment. 

This hybrid approach was designed to:
1. Catch 100% structured data immediately at the DB level (using PostgreSQL regex).
2. Completely bypass the LLM for these columns, saving token costs and API latency.
3. Fallback to the LLM **only** when the data is unstructured (e.g., names, addresses) or ambiguous.

*Note: This code was refactored out of the final submission to strictly adhere to the "Data classification using LLM" rule of the case study. However, the logic is preserved here to demonstrate production-level optimization thinking.*

## The Code

```python
# ── DB-side PII regex patterns ────────────────────────────────────────────────
# These patterns are pushed directly to PostgreSQL via WHERE column ~ pattern.
# PostgreSQL stops at the FIRST matching row → no sampling, no random, no limit.

PII_DB_PATTERNS: list[tuple[str, str]] = [
    ("email_address",          r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}'),
    # tckn BEFORE phone: 11-digit Turkish IDs would otherwise match the phone pattern first
    ("tckn",                   r'\m[1-9][0-9]{10}\M'),
    ("social_security_number", r'\m[0-9]{3}-[0-9]{2}-[0-9]{4}\M'),
    ("phone_number",           r'(\+?[0-9]{1,3}[\s\-]?)?[\(]?[0-9]{3}[\)]?[\s\-]?[0-9]{3}[\s\-]?[0-9]{4}'),
    ("credit_card_number",     r'\m[0-9]{4}[\s\-]?[0-9]{4}[\s\-]?[0-9]{4}[\s\-]?[0-9]{4}\M'),
    ("ip_address",             r'\m[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\M'),
    ("national_id_number",     r'\m[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}[A-Z0-9]{0,16}\M'),  # IBAN
]

def _db_regex_classify(
    conn: Any,
    table_name: str,
    column_name: str,
) -> str | None:
    """
    Run each PII regex pattern directly on PostgreSQL.
    Returns the first matching category, or None if nothing found.

    PostgreSQL stops scanning at the FIRST match (LIMIT 1),
    so this is fast even on tables with millions of rows.
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
                # Column cast may fail for exotic types — skip pattern
                conn.rollback()
                continue
    return None

# ------------- Example Usage inside classify_column() -------------
category = _db_regex_classify(conn, table_obj.table_name, column_obj.column_name)

if category is None:
    # Fallback to LLM if no structured pattern matched
    samples = _fetch_llm_samples(...)
    classifications = _call_llm(...)
else:
    # Instant classification!
    classifications = {k: 0.0 for k in PII_CATEGORIES}
    classifications[category] = 1.0
```
