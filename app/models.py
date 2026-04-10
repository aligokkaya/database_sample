import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, Integer, DateTime, ForeignKey, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import relationship

from app.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class MetadataRecord(Base):
    __tablename__ = "metadata_records"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    database_name = Column(String(255), nullable=False)
    created_at = Column(DateTime(timezone=True), default=utcnow, nullable=False)

    # Relationships
    db_connection = relationship(
        "DbConnection",
        back_populates="metadata_record",
        uselist=False,
        cascade="all, delete-orphan",
    )
    tables = relationship(
        "TableInfo",
        back_populates="metadata_record",
        cascade="all, delete-orphan",
    )
    columns = relationship(
        "ColumnInfo",
        back_populates="metadata_record",
        cascade="all, delete-orphan",
    )


class DbConnection(Base):
    __tablename__ = "db_connections"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    metadata_id = Column(
        UUID(as_uuid=True),
        ForeignKey("metadata_records.id", ondelete="CASCADE"),
        nullable=False,
    )
    host = Column(String(255), nullable=False)
    port = Column(String(10), nullable=False)
    database_name = Column(String(255), nullable=False)
    username = Column(String(255), nullable=False)
    encrypted_password = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), default=utcnow, nullable=False)

    # Relationships
    metadata_record = relationship("MetadataRecord", back_populates="db_connection")


class TableInfo(Base):
    __tablename__ = "tables_info"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    metadata_id = Column(
        UUID(as_uuid=True),
        ForeignKey("metadata_records.id", ondelete="CASCADE"),
        nullable=False,
    )
    table_name = Column(String(255), nullable=False)
    schema_name = Column(String(255), nullable=False, default="public")

    # Relationships
    metadata_record = relationship("MetadataRecord", back_populates="tables")
    columns = relationship(
        "ColumnInfo",
        back_populates="table",
        cascade="all, delete-orphan",
    )


class ColumnInfo(Base):
    __tablename__ = "columns_info"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    table_id = Column(
        UUID(as_uuid=True),
        ForeignKey("tables_info.id", ondelete="CASCADE"),
        nullable=False,
    )
    metadata_id = Column(
        UUID(as_uuid=True),
        ForeignKey("metadata_records.id", ondelete="CASCADE"),
        nullable=False,
    )
    column_name = Column(String(255), nullable=False)
    data_type = Column(String(255), nullable=False)
    ordinal_position = Column(Integer, nullable=False)

    # Relationships
    table = relationship("TableInfo", back_populates="columns")
    metadata_record = relationship("MetadataRecord", back_populates="columns")
