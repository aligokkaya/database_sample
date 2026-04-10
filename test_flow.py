import requests
import json

base_url = "http://localhost:8000"

print("1. Authenticating...")
auth_data = {
    "username": "aligkky",
    "password": "ali1233"
}
r1 = requests.post(f"{base_url}/auth", json=auth_data)
if r1.status_code != 200:
    print("Auth failed:", r1.text)
    exit(1)

token = r1.json()["access_token"]
headers = {"Authorization": f"Bearer {token}"}
print("Auth success, got token.")

print("2. Connecting to Target DB to extract metadata...")
metadata_req = {
    "host": "demo_db",
    "port": 5432,
    "database": "llm_discovery_demo",
    "username": "postgres",
    "password": "postgres"
}
r2 = requests.post(f"{base_url}/db/metadata", json=metadata_req, headers=headers)
if r2.status_code != 201:
    print("Metadata POST failed:", r2.text)
    exit(1)

meta_data = r2.json()
metadata_id = meta_data["metadata_id"]
print(f"Metadata extracted successfully. ID: {metadata_id}")

# Find a column to classify (e.g., first_name in customers table)
target_column_id = None
target_column_name = None
for table in meta_data["tables"]:
    if table["table_name"] == "customers":
        for col in table["columns"]:
            if col["column_name"] == "first_name":
                target_column_id = col["column_id"]
                target_column_name = col["column_name"]
                break

if not target_column_id:
    print("Could not find customers.first_name column")
    exit(1)

print(f"3. Classifying column: {target_column_name} (ID: {target_column_id})")
classify_req = {
    "column_id": target_column_id,
    "sample_count": 5
}
r3 = requests.post(f"{base_url}/classify", json=classify_req, headers=headers)
if r3.status_code != 200:
    print("Classify failed:", r3.text)
    exit(1)

print("Classify response:")
print(json.dumps(r3.json(), indent=2))
