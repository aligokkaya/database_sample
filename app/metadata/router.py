from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.router import get_current_user
from app.database import get_db
from app.metadata import service
from app.metadata.schemas import (
    ConnectRequest,
    ConnectResponse,
    DeleteResponse,
    MetadataDetailResponse,
    MetadataListItem,
)

router = APIRouter(tags=["Metadata"])


@router.post(
    "/db/metadata",
    response_model=ConnectResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Connect to a target DB and discover its schema",
)
async def connect_and_discover(
    body: ConnectRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """
    Connect to the specified PostgreSQL database, extract schema metadata
    from information_schema, persist it to the system DB, and return a
    structured summary.
    """
    try:
        return await service.create_metadata(
            db=db,
            host=body.host,
            port=body.port,
            database=body.database,
            username=body.username,
            password=body.password,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error: {exc}",
        ) from exc


@router.get(
    "/metadata",
    response_model=list[MetadataListItem],
    summary="List all metadata records",
)
async def list_all_metadata(
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """Return a summary list of all discovered databases."""
    try:
        return await service.list_metadata(db)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error: {exc}",
        ) from exc


@router.get(
    "/metadata/{metadata_id}",
    response_model=MetadataDetailResponse,
    summary="Get full metadata detail",
)
async def get_metadata(
    metadata_id: str,
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """Return the full schema detail for a specific metadata record."""
    try:
        return await service.get_metadata_detail(db, metadata_id)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error: {exc}",
        ) from exc


@router.delete(
    "/metadata/{metadata_id}",
    response_model=DeleteResponse,
    summary="Delete a metadata record",
)
async def delete_metadata(
    metadata_id: str,
    db: Annotated[AsyncSession, Depends(get_db)],
    _: Annotated[dict, Depends(get_current_user)],
) -> Any:
    """Delete a metadata record and all associated tables/columns/connection info."""
    try:
        return await service.delete_metadata(db, metadata_id)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error: {exc}",
        ) from exc
