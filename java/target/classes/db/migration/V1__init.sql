CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS metadata_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    database_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS db_connections (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    metadata_id UUID NOT NULL REFERENCES metadata_records(id) ON DELETE CASCADE,
    host VARCHAR(255) NOT NULL,
    port VARCHAR(10) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tables_info (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    metadata_id UUID NOT NULL REFERENCES metadata_records(id) ON DELETE CASCADE,
    table_name VARCHAR(255) NOT NULL,
    schema_name VARCHAR(255) NOT NULL DEFAULT 'public'
);

CREATE TABLE IF NOT EXISTS columns_info (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    table_id UUID NOT NULL REFERENCES tables_info(id) ON DELETE CASCADE,
    metadata_id UUID NOT NULL REFERENCES metadata_records(id) ON DELETE CASCADE,
    column_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(255) NOT NULL,
    ordinal_position INTEGER NOT NULL
);
