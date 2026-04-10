# LLM-Based Database Data Discovery System

A FastAPI application that connects to any PostgreSQL database, discovers its schema, and uses an LLM to classify columns across **13 PII (Personally Identifiable Information) categories** with probability distributions.

---

## Features

- JWT-based authentication
- Dynamic connection to any PostgreSQL target database
- Automatic schema discovery via `information_schema`
- Fernet-encrypted password storage
- LLM-powered PII classification (OpenAI-compatible APIs)
- Full async SQLAlchemy 2.x ORM with asyncpg
- Alembic migrations
- Docker Compose deployment

---

## Quick Start

### 1. Clone and configure

```bash
cp .env.example .env
```

Edit `.env` with your values. Generate a Fernet key:

```python
from cryptography.fernet import Fernet
print(Fernet.generate_key().decode())
```

### 2. Start with Docker Compose

```bash
docker-compose up --build
```

The API will be available at `http://localhost:8000`.

Interactive API docs: `http://localhost:8000/docs`

---

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DATABASE_URL` | Async SQLAlchemy URL for the system DB | `postgresql+asyncpg://...` |
| `SYNC_DATABASE_URL` | Sync URL used by Alembic | `postgresql://...` |
| `OPENAI_API_KEY` | OpenAI (or compatible) API key | — |
| `OPENAI_BASE_URL` | Base URL for OpenAI-compatible API | `https://api.openai.com/v1` |
| `OPENAI_MODEL` | Model to use for classification | `gpt-4o-mini` |
| `JWT_SECRET_KEY` | Secret key for JWT signing | — |
| `JWT_ALGORITHM` | JWT algorithm | `HS256` |
| `JWT_EXPIRY_HOURS` | Token expiry in hours | `24` |
| `BASIC_AUTH_USERNAME` | Login username | `admin` |
| `BASIC_AUTH_PASSWORD` | Login password | `admin123` |
| `ENCRYPTION_KEY` | Fernet key for encrypting stored DB passwords | — |

---

## API Endpoints

### Authentication

#### `POST /auth`
Obtain a JWT token.

```json
{
  "username": "admin",
  "password": "admin123"
}
```

Response:
```json
{
  "access_token": "<jwt>",
  "token_type": "bearer",
  "expires_in": 86400
}
```

All subsequent requests must include:
```
Authorization: Bearer <token>
```

---

### Schema Discovery

#### `POST /db/metadata`
Connect to a target PostgreSQL database and discover its schema.

```json
{
  "host": "your-db-host",
  "port": 5432,
  "database": "mydb",
  "username": "dbuser",
  "password": "dbpassword"
}
```

Returns metadata with `metadata_id`, table count, and column details including `column_id` UUIDs.

---

### Metadata Management

#### `GET /metadata`
List all discovered database metadata records.

#### `GET /metadata/{metadata_id}`
Get full schema details for a specific record (tables + columns).

#### `DELETE /metadata/{metadata_id}`
Delete a metadata record and all associated data (cascades to connection, tables, columns).

---

### PII Classification

#### `POST /classify`
Classify a specific column for PII using the LLM.

```json
{
  "column_id": "<uuid from metadata>",
  "sample_count": 10
}
```

Response:
```json
{
  "column_id": "...",
  "column_name": "email",
  "table_name": "users",
  "sample_count": 10,
  "classifications": {
    "email_address": 0.95,
    "phone_number": 0.01,
    "social_security_number": 0.0,
    "credit_card_number": 0.0,
    "national_id_number": 0.0,
    "full_name": 0.0,
    "first_name": 0.0,
    "last_name": 0.0,
    "tckn": 0.0,
    "home_address": 0.0,
    "date_of_birth": 0.0,
    "ip_address": 0.0,
    "not_pii": 0.04
  }
}
```

### `GET /health`
No authentication required. Returns `{"status": "healthy"}`.

---

## PII Categories

| Category | Description |
|---|---|
| `email_address` | Email addresses |
| `phone_number` | Phone numbers in any format |
| `social_security_number` | US SSNs |
| `credit_card_number` | Credit/debit card numbers |
| `national_id_number` | National ID numbers (non-Turkish) |
| `full_name` | First + last name combined |
| `first_name` | Given names only |
| `last_name` | Family names only |
| `tckn` | Turkish Citizenship Number (11 digits) |
| `home_address` | Physical street addresses |
| `date_of_birth` | Dates of birth |
| `ip_address` | IPv4 or IPv6 addresses |
| `not_pii` | Not PII |

---

## Local Development (without Docker)

```bash
# Create a virtual environment
python -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Set up .env (point DATABASE_URL to a local postgres)
cp .env.example .env

# Run migrations
alembic upgrade head

# Start the server
uvicorn app.main:app --reload --port 8000
```

---

## Running Alembic Migrations Manually

```bash
# Generate a new migration
alembic revision --autogenerate -m "description"

# Apply migrations
alembic upgrade head

# Rollback one migration
alembic downgrade -1
```
