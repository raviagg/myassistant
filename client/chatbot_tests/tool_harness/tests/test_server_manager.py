import os
import pathlib
import sys
from unittest.mock import patch

import httpx
import pytest

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


def test_find_jar_raises_when_no_jar():
    """_find_jar() raises FileNotFoundError when glob finds no JAR."""
    from tool_harness.server_manager import _find_jar
    with patch("tool_harness.server_manager.glob.glob", return_value=[]):
        with pytest.raises(FileNotFoundError, match="fat JAR not found"):
            _find_jar()


def test_wait_for_health_raises_on_timeout():
    """_wait_for_health() raises RuntimeError if health endpoint never responds."""
    from tool_harness.server_manager import _wait_for_health
    with patch("tool_harness.server_manager.httpx.get",
               side_effect=httpx.ConnectError("refused")):
        with pytest.raises(RuntimeError, match="did not become healthy"):
            _wait_for_health("http://localhost:9999", timeout=0)
