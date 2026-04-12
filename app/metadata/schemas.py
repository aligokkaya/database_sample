from pydantic import BaseModel, Field


class ConnectRequest(BaseModel):
    host: str = Field(..., description="Target DB hostname or IP")
    port: int = Field(default=5432, description="Target DB port")
    database: str = Field(..., description="Target database name")
    username: str = Field(..., description="Target DB username")
    password: str = Field(..., description="Target DB password")


class ColumnOut(BaseModel):
    column_id: str
    column_name: str
    data_type: str


class TableOut(BaseModel):
    table_name: str
    columns: list[ColumnOut]


class ConnectResponse(BaseModel):
    metadata_id: str
    database_name: str
    table_count: int
    tables: list[TableOut]


class MetadataListItem(BaseModel):
    metadata_id: str
    database_name: str
    created_at: str
    table_count: int


class TableDetailOut(BaseModel):
    table_id: str
    table_name: str
    schema_name: str
    columns: list[ColumnOut]


class MetadataDetailResponse(BaseModel):
    metadata_id: str
    database_name: str
    created_at: str
    tables: list[TableDetailOut]


class DeleteResponse(BaseModel):
    status: str
    metadata_id: str
