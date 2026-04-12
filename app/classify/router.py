from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.router import get_current_user
from app.classify import service
from app.classify.schemas import (
    ClassifyRequest,
    ClassifyResponse,
    DiscoverRequest,
    DiscoverResponse,
)
from app.database import get_db

router = APIRouter(prefix="/classify", tags=["Classification"])


@router.post(
    "",
    response_model=ClassifyResponse,
    summary="Classify a database column for PII",
)
async def classify_column(
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[dict, Depends(get_current_user)],
    body: ClassifyRequest,
) -> Any:
    """Classify a single database column for PII using the 8-phase detection pipeline."""
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


@router.post(
    "/discover",
    response_model=DiscoverResponse,
    summary="Scan entire database for PII columns",
)
async def discover_pii(
    db: Annotated[AsyncSession, Depends(get_db)],
    user: Annotated[dict, Depends(get_current_user)],
    body: DiscoverRequest,
) -> Any:
    """Automatically scan ALL columns in a metadata record for PII."""
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
