#!/bin/bash
set -e

echo "Running Alembic migrations..."
# Check if tables already exist to avoid DuplicateTable errors on re-runs
TABLE_EXISTS=$(python3 -c "
import os, psycopg2
try:
    url = os.environ.get('SYNC_DATABASE_URL', '')
    # parse postgres://user:pass@host:port/db
    import re
    m = re.match(r'postgresql\+psycopg2://([^:]+):([^@]+)@([^:]+):(\d+)/(.+)', url)
    if not m: raise ValueError('bad url')
    conn = psycopg2.connect(user=m[1], password=m[2], host=m[3], port=m[4], dbname=m[5])
    cur = conn.cursor()
    cur.execute(\"SELECT to_regclass('public.metadata_records')\")
    print('exists' if cur.fetchone()[0] else 'missing')
    conn.close()
except Exception as e:
    print('missing')
")

if [ "$TABLE_EXISTS" = "exists" ]; then
    echo "Tables already exist — stamping alembic head to skip migration..."
    alembic stamp head 2>/dev/null || true
else
    alembic upgrade head
fi

echo "Starting application..."
exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port 8000 \
    --workers 1 \
    --log-level info
