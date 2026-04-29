import sys
import pathlib
import os
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from tool_harness.server_manager import managed_server, auth_token


def test_managed_server_uses_env_url_when_set():
    """When CHATBOT_HTTP_URL is set, managed_server yields it without starting a subprocess."""
    with patch.dict(os.environ, {"CHATBOT_HTTP_URL": "http://localhost:9999"}):
        with managed_server() as url:
            assert url == "http://localhost:9999"


def test_managed_server_uses_default_token():
    """CHATBOT_AUTH_TOKEN defaults correctly."""
    env = {k: v for k, v in os.environ.items() if k != "CHATBOT_AUTH_TOKEN"}
    with patch.dict(os.environ, env, clear=True):
        assert auth_token() == "dev-token-change-me-in-production"


def test_managed_server_uses_custom_token():
    with patch.dict(os.environ, {
        "CHATBOT_HTTP_URL": "http://localhost:9999",
        "CHATBOT_AUTH_TOKEN": "my-custom-token",
    }):
        assert auth_token() == "my-custom-token"
