import json
import sys
import pathlib
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from tool_harness.agentic_runner import AgenticRunner
from tool_harness.executors import MockExecutor


def _make_end_turn_response(text="Done."):
    """Anthropic SDK response object with stop_reason=end_turn and no tool_use."""
    block = MagicMock()
    block.type = "text"
    block.text = text
    resp = MagicMock()
    resp.stop_reason = "end_turn"
    resp.content = [block]
    return resp


def _make_tool_use_response(tool_name: str, tool_input: dict, tool_id="id-1"):
    """Anthropic SDK response with one tool_use block."""
    block = MagicMock()
    block.type = "tool_use"
    block.name = tool_name
    block.input = tool_input
    block.id = tool_id
    resp = MagicMock()
    resp.stop_reason = "tool_use"
    resp.content = [block]
    return resp


def test_agentic_runner_end_turn_immediately():
    """If Claude never calls tools, turn records empty tool list."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "Hello", "expected_tools": []}],
    }
    with patch("tool_harness.agentic_runner.anthropic.Anthropic") as mock_cls:
        mock_client = MagicMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create.return_value = _make_end_turn_response()

        results, error = runner.run_scenario(scenario)

    assert error is None
    assert results is not None
    assert results[0] == []  # no tool calls in turn 0


def test_agentic_runner_records_tool_calls():
    """Tool names from tool_use blocks are recorded correctly."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "List domains", "expected_tools": ["list_domains"]}],
    }
    with patch("tool_harness.agentic_runner.anthropic.Anthropic") as mock_cls:
        mock_client = MagicMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create.side_effect = [
            _make_tool_use_response("list_domains", {}),
            _make_end_turn_response(),
        ]

        results, error = runner.run_scenario(scenario)

    assert error is None
    assert "list_domains" in results[0]


def test_agentic_runner_multi_turn_accumulates_history():
    """Each turn appends to the messages list so context is preserved."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Two-turn test",
        "turns": [
            {"user_message": "Turn one", "expected_tools": []},
            {"user_message": "Turn two", "expected_tools": []},
        ],
    }
    with patch("tool_harness.agentic_runner.anthropic.Anthropic") as mock_cls:
        mock_client = MagicMock()
        mock_cls.return_value = mock_client
        mock_client.messages.create.return_value = _make_end_turn_response()

        results, error = runner.run_scenario(scenario)

    assert error is None
    assert len(results) == 2
    # messages.create called once per turn (no tools, so no extra calls)
    assert mock_client.messages.create.call_count == 2
    # second call includes both turns in messages
    second_call_messages = mock_client.messages.create.call_args_list[1][1]["messages"]
    user_messages = [m for m in second_call_messages if m["role"] == "user"]
    assert len(user_messages) == 2
