import pytest
import httpx

@pytest.fixture
def http():
    return httpx.Client(base_url="http://testserver", headers={"Authorization": "Bearer test-token"})
