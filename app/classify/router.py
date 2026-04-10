from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.router import get_current_user
from app.database import get_db
from app.classify import service

router = APIRouter(prefix="/classify", tags=["Classification"])


# ---------- Classify schemas ----------

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


# ---------- Classify route ----------

@router.post(
    "",
    response_model=ClassifyResponse,
    summary="Classify a database column for PII",
)
async def classify_column(
    body: ClassifyRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """
    Classify a single database column for PII using LLM.
    Returns whether the column contains PII and its category.
    """
    try:
        return await service.classify_column(
            db=db,
            column_id=body.column_id,
            sample_count=body.sample_count,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error during classification: {exc}",
        ) from exc


# ---------- Discover schemas ----------

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


# ---------- Discover route ----------

@router.post(
    "/discover",
    response_model=DiscoverResponse,
    summary="Scan entire database for PII columns",
)
async def discover_pii(
    body: DiscoverRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """
    Automatically scan ALL columns in a metadata record for PII.
    """
    try:
        return await service.discover_metadata(
            db=db,
            metadata_id=body.metadata_id,
            sample_count=body.sample_count,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Discovery failed: {exc}",
        ) from exc
