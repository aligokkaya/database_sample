from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import JWTError, jwt
from pydantic import BaseModel

from app.config import get_settings

router = APIRouter()
settings = get_settings()
security = HTTPBearer()


# ---------- Pydantic schemas ----------

class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


# ---------- JWT helpers ----------

def create_access_token(data: dict, expires_delta: timedelta | None = None) -> str:
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + (
        expires_delta or timedelta(hours=settings.JWT_EXPIRY_HOURS)
    )
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(
        to_encode, settings.JWT_SECRET_KEY, algorithm=settings.JWT_ALGORITHM
    )
    return encoded_jwt


def verify_token(token: str) -> dict:
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET_KEY, algorithms=[settings.JWT_ALGORITHM]
        )
        return payload
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


# ---------- Dependency ----------

def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(security)],
) -> dict:
    return verify_token(credentials.credentials)


# ---------- Route ----------

@router.post("/auth", response_model=TokenResponse, tags=["Authentication"])
async def login(body: LoginRequest) -> TokenResponse:
    """
    Authenticate with username and password.
    Returns a JWT bearer token valid for JWT_EXPIRY_HOURS hours.
    """
    if (
        body.username != settings.BASIC_AUTH_USERNAME
        or body.password != settings.BASIC_AUTH_PASSWORD
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    expiry_seconds = settings.JWT_EXPIRY_HOURS * 3600
    token = create_access_token(
        data={"sub": body.username},
        expires_delta=timedelta(hours=settings.JWT_EXPIRY_HOURS),
    )
    return TokenResponse(access_token=token, expires_in=expiry_seconds)
