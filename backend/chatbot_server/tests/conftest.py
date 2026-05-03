import os
import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("CHATBOT_HTTP_URL", "http://localhost:8080")
os.environ.setdefault("CHATBOT_AUTH_TOKEN", "test-token")

from main import app

@pytest.fixture
def client():
    return TestClient(app)
