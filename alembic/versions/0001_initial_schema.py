"""initial schema

Revision ID: 0001
Revises:
Create Date: 2024-01-01 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # metadata_records
    op.create_table(
        "metadata_records",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("database_name", sa.String(255), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )

    # db_connections
    op.create_table(
        "db_connections",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "metadata_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("metadata_records.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("host", sa.String(255), nullable=False),
        sa.Column("port", sa.String(10), nullable=False),
        sa.Column("database_name", sa.String(255), nullable=False),
        sa.Column("username", sa.String(255), nullable=False),
        sa.Column("encrypted_password", sa.Text, nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )

    # tables_info
    op.create_table(
        "tables_info",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "metadata_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("metadata_records.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("table_name", sa.String(255), nullable=False),
        sa.Column(
            "schema_name", sa.String(255), nullable=False, server_default="public"
        ),
    )

    # columns_info
    op.create_table(
        "columns_info",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "table_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("tables_info.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "metadata_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("metadata_records.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("column_name", sa.String(255), nullable=False),
        sa.Column("data_type", sa.String(255), nullable=False),
        sa.Column("ordinal_position", sa.Integer, nullable=False),
    )

    # Indexes for common lookups
    op.create_index("ix_db_connections_metadata_id", "db_connections", ["metadata_id"])
    op.create_index("ix_tables_info_metadata_id", "tables_info", ["metadata_id"])
    op.create_index("ix_columns_info_table_id", "columns_info", ["table_id"])
    op.create_index("ix_columns_info_metadata_id", "columns_info", ["metadata_id"])


def downgrade() -> None:
    op.drop_index("ix_columns_info_metadata_id", table_name="columns_info")
    op.drop_index("ix_columns_info_table_id", table_name="columns_info")
    op.drop_index("ix_tables_info_metadata_id", table_name="tables_info")
    op.drop_index("ix_db_connections_metadata_id", table_name="db_connections")
    op.drop_table("columns_info")
    op.drop_table("tables_info")
    op.drop_table("db_connections")
    op.drop_table("metadata_records")
