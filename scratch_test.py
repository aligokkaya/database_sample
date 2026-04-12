import asyncio
import json
import os
from typing import Any

# Mock settings
class Settings:
    OPENAI_BASE_URL = "http://ollama:11434/v1"
    OPENAI_MODEL = "qwen2.5:3b"

settings = Settings()
PII_CATEGORIES = [
    "email_address", "phone_number", "social_security_number", "credit_card_number",
    "national_id_number", "full_name", "first_name", "last_name", "tckn",
    "home_address", "date_of_birth", "ip_address", "not_pii"
]

from app.classify.service import _call_llm, _extract_json

def test_llm():
    print("Testing LLM call...")
    try:
        # Override base_url for local testing if needed
        # But here we just want to see if the function itself has logical errors
        res = _call_llm("notes", ["{'name': 'ali', 'tc': '12345678901'}"])
        print("Success:", res)
    except Exception as e:
        print("Error during LLM call:", e)
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    test_llm()
