import json
import sys
import pathlib
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from tool_harness.agentic_runner import AgenticRunner
from tool_harness.executors import MockExecutor


def _make_envelope(result_text: str, returncode: int = 0) -> MagicMock:
    """Simulate a subprocess.run result returning a claude --output-format json envelope."""
    r = MagicMock()
    r.returncode = returncode
    r.stderr = ""
    r.stdout = json.dumps({
        "type": "result",
        "subtype": "success",
        "is_error": False,
        "result": result_text,
        "duration_api_ms": 950,
        "usage": {
            "input_tokens": 100,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
            "output_tokens": 20,
        },
        "total_cost_usd": 0.001,
    })
    return r


def test_agentic_runner_end_turn_immediately():
    """If Claude returns [] immediately, turn records empty tool list."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6", backend="claude-p")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "Hello", "expected_tools": []}],
    }
    with patch("tool_harness.agentic_runner.subprocess.run",
               return_value=_make_envelope("[]")):
        results, stats, error = runner.run_scenario(scenario)

    assert error is None
    assert results is not None
    assert results[0] == []
    assert stats is not None
    assert stats["total"]["num_calls"] == 1


def test_agentic_runner_records_tool_calls():
    """Tool names from tool calls are recorded correctly."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6", backend="claude-p")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "List domains", "expected_tools": ["list_domains"]}],
    }
    with patch("tool_harness.agentic_runner.subprocess.run") as mock_run:
        mock_run.side_effect = [
            _make_envelope('[{"tool": "list_domains", "params": {}}]'),
            _make_envelope("[]"),
        ]
        results, stats, error = runner.run_scenario(scenario)

    assert error is None
    assert "list_domains" in results[0]
    assert stats["total"]["num_calls"] == 2


def test_agentic_runner_multi_turn_accumulates_history():
    """Each turn's context is passed into the next turn's prompt."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6", backend="claude-p")
    scenario = {
        "name": "Two-turn test",
        "turns": [
            {"user_message": "Turn one", "expected_tools": []},
            {"user_message": "Turn two", "expected_tools": []},
        ],
    }
    with patch("tool_harness.agentic_runner.subprocess.run",
               return_value=_make_envelope("[]")) as mock_run:
        results, stats, error = runner.run_scenario(scenario)

    assert error is None
    assert len(results) == 2
    # One subprocess call per turn (no tools → single iteration each)
    assert mock_run.call_count == 2
    # Second call's prompt must include "Turn one" from conversation history
    # Command is ["claude", "-p", "--output-format", "json", prompt] → index 4
    second_prompt = mock_run.call_args_list[1].args[0][4]
    assert "Turn one" in second_prompt
    assert stats["total"]["num_turns"] == 2
