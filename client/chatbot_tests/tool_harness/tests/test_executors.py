import sys
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from tool_harness.executors import MockExecutor


def test_mock_executor_create_person():
    ex = MockExecutor()
    result = ex.call("create_person", {"full_name": "Ravi Aggarwal", "gender": "male"})
    assert result["full_name"] == "Ravi Aggarwal"
    assert "id" in result


def test_mock_executor_list_domains():
    ex = MockExecutor()
    result = ex.call("list_domains", {})
    assert isinstance(result, list)
    names = [d["name"] for d in result]
    assert "health" in names
    assert "todo" in names


def test_mock_executor_unknown_tool_returns_error():
    ex = MockExecutor()
    result = ex.call("nonexistent_tool", {})
    assert "error" in result


def test_live_executor_unknown_tool_returns_error():
    from unittest.mock import MagicMock, patch
    import httpx
    mock_http = MagicMock(spec=httpx.Client)
    with patch("tool_harness.executors._build_dispatch", return_value={}):
        from tool_harness.executors import LiveExecutor
        ex = LiveExecutor.__new__(LiveExecutor)
        ex._http = mock_http
        ex._dispatch = {}
        result = ex.call("nonexistent_tool", {})
        assert result == {"error": "unknown_tool", "tool_name": "nonexistent_tool"}
