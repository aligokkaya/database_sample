"""
Metadata service – handles:
  - Connecting to target PostgreSQL databases (psycopg2)
  - Querying information_schema for schema discovery
  - Persisting metadata to the system DB via SQLAlchemy async ORM
  - Fernet encryption/decryption for stored passwords
"""
from __future__ import annotations

import uuid
from typing import Any

import psycopg2
import psycopg2.extras
from cryptography.fernet import Fernet
from fastapi import HTTPException, status
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import get_settings
from app.models import MetadataRecord, DbConnection, TableInfo, ColumnInfo

settings = get_settings()


# ---------- Encryption helpers ----------

def _get_fernet() -> Fernet:
    key = settings.ENCRYPTION_KEY
    if not key:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="ENCRYPTION_KEY is not configured.",
        )
    return Fernet(key.encode() if isinstance(key, str) else key)


def encrypt_password(plain: str) -> str:
    f = _get_fernet()
    return f.encrypt(plain.encode()).decode()


def decrypt_password(token: str) -> str:
    f = _get_fernet()
    return f.decrypt(token.encode()).decode()


# ---------- Target DB discovery ----------

def discover_schema(
    host: str,
    port: int,
    database: str,
    username: str,
    password: str,
) -> dict[str, list[dict[str, Any]]]:
    """
    Connect to the target PostgreSQL database and return a dict of:
        { table_name: [ { column_name, data_type, ordinal_position }, ... ] }
    Only tables in the 'public' schema are returned.
    """
    try:
        conn = psycopg2.connect(
            host=host,
            port=port,
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

    try:
        with conn.cursor(cursor_factory=psycopg2.extras.DictCursor) as cur:
            cur.execute(
                """
                SELECT c.table_name,
                       c.column_name,
                       c.data_type,
                       c.ordinal_position
                FROM   information_schema.columns c
                JOIN   information_schema.tables  t
                       ON  t.table_schema = c.table_schema
                       AND t.table_name   = c.table_name
                WHERE  c.table_schema = 'public'
                  AND  t.table_type   = 'BASE TABLE'
                ORDER  BY c.table_name, c.ordinal_position
                """
            )
            rows = cur.fetchall()
    finally:
        conn.close()

    tables: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        tname = row["table_name"]
        if tname not in tables:
            tables[tname] = []
        tables[tname].append(
            {
                "column_name": row["column_name"],
                "data_type": row["data_type"],
                "ordinal_position": row["ordinal_position"],
            }
        )
    return tables


# ---------- Persistence ----------

async def create_metadata(
    db: AsyncSession,
    host: str,
    port: int,
    database: str,
    username: str,
    password: str,
) -> dict[str, Any]:
    """
    Discover schema from target DB, persist everything to system DB,
    and return the structured response.
    """
    # 1. Discover schema
    tables_raw = discover_schema(host, port, database, username, password)

    # 2. Create MetadataRecord
    metadata_record = MetadataRecord(
        id=uuid.uuid4(),
        database_name=database,
    )
    db.add(metadata_record)
    await db.flush()  # get the id without committing

    # 3. Create DbConnection (with encrypted password)
    db_conn = DbConnection(
        id=uuid.uuid4(),
        metadata_id=metadata_record.id,
        host=host,
        port=str(port),
        database_name=database,
        username=username,
        encrypted_password=encrypt_password(password),
    )
    db.add(db_conn)

    # 4. Create TableInfo + ColumnInfo records
    response_tables = []
    for table_name, columns in tables_raw.items():
        table_obj = TableInfo(
            id=uuid.uuid4(),
            metadata_id=metadata_record.id,
            table_name=table_name,
            schema_name="public",
        )
        db.add(table_obj)
        await db.flush()  # get table_obj.id

        response_columns = []
        for col in columns:
            col_obj = ColumnInfo(
                id=uuid.uuid4(),
                table_id=table_obj.id,
                metadata_id=metadata_record.id,
                column_name=col["column_name"],
                data_type=col["data_type"],
                ordinal_position=col["ordinal_position"],
            )
            db.add(col_obj)
            await db.flush()

            response_columns.append(
                {
                    "column_id": str(col_obj.id),
                    "column_name": col_obj.column_name,
                    "data_type": col_obj.data_type,
                }
            )

        response_tables.append(
            {"table_name": table_name, "columns": response_columns}
        )

    await db.commit()

    return {
        "metadata_id": str(metadata_record.id),
        "database_name": database,
        "table_count": len(tables_raw),
        "tables": response_tables,
    }


async def list_metadata(db: AsyncSession) -> list[dict[str, Any]]:
    """Return all metadata records with table counts."""
    # Subquery: count tables per metadata_id
    result = await db.execute(
        select(MetadataRecord).order_by(MetadataRecord.created_at.desc())
    )
    records = result.scalars().all()

    # Fetch table counts in one query
    count_result = await db.execute(
        select(TableInfo.metadata_id, func.count(TableInfo.id).label("cnt")).group_by(
            TableInfo.metadata_id
        )
    )
    count_map: dict[Any, int] = {row.metadata_id: row.cnt for row in count_result}

    return [
        {
            "metadata_id": str(r.id),
            "database_name": r.database_name,
            "created_at": r.created_at.isoformat(),
            "table_count": count_map.get(r.id, 0),
        }
        for r in records
    ]


async def get_metadata_detail(
    db: AsyncSession, metadata_id: str
) -> dict[str, Any]:
    """Return full metadata detail including tables and columns."""
    try:
        metadata_uuid = uuid.UUID(metadata_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid metadata_id format: '{metadata_id}' is not a valid UUID.",
        )
    result = await db.execute(
        select(MetadataRecord)
        .options(
            selectinload(MetadataRecord.tables).selectinload(TableInfo.columns)
        )
        .where(MetadataRecord.id == metadata_uuid)
    )
    record = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Metadata record '{metadata_id}' not found.",
        )

    tables_out = []
    for t in record.tables:
        columns_out = [
            {
                "column_id": str(c.id),
                "column_name": c.column_name,
                "data_type": c.data_type,
            }
            for c in sorted(t.columns, key=lambda x: x.ordinal_position)
        ]
        tables_out.append(
            {
                "table_id": str(t.id),
                "table_name": t.table_name,
                "schema_name": t.schema_name,
                "columns": columns_out,
            }
        )

    return {
        "metadata_id": str(record.id),
        "database_name": record.database_name,
        "created_at": record.created_at.isoformat(),
        "tables": tables_out,
    }


async def delete_metadata(
    db: AsyncSession, metadata_id: str
) -> dict[str, str]:
    """Delete a metadata record and all cascade-related rows."""
    try:
        metadata_uuid = uuid.UUID(metadata_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid metadata_id format: '{metadata_id}' is not a valid UUID.",
        )
    result = await db.execute(
        select(MetadataRecord).where(MetadataRecord.id == metadata_uuid)
    )
    record = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Metadata record '{metadata_id}' not found.",
        )

    await db.delete(record)
    await db.commit()
    return {"status": "deleted", "metadata_id": metadata_id}
