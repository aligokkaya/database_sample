from pydantic import BaseModel, Field


class ClassifyRequest(BaseModel):
    column_id: str = Field(..., description="UUID of the column to classify")
    sample_count: int = Field(default=10, description="Number of sample values to check")


class ClassifyResponse(BaseModel):
    column_id: str
    column_name: str
    table_name: str
    data_type: str
    sample_count: int
    top_category: str
    top_probability: float
    classifications: dict[str, float]


class DiscoverRequest(BaseModel):
    metadata_id: str = Field(..., description="UUID of the metadata record to scan")
    sample_count: int = Field(default=10, description="Number of sample values to check per column")


class DiscoverColumnResult(BaseModel):
    column_id: str
    column_name: str
    is_pii: bool
    category: str


class DiscoverTableResult(BaseModel):
    table_name: str
    pii_count: int
    columns: list[DiscoverColumnResult]


class DiscoverResponse(BaseModel):
    metadata_id: str
    database_name: str
    total_columns: int
    pii_columns: int
    tables: list[DiscoverTableResult]
