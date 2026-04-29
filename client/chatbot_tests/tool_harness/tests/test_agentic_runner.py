import sys
import pathlib
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from tool_harness.agentic_runner import AgenticRunner
from tool_harness.executors import MockExecutor


def _make_run_result(stdout: str, returncode: int = 0) -> MagicMock:
    """Simulate a subprocess.run result."""
    r = MagicMock()
    r.returncode = returncode
    r.stdout = stdout
    r.stderr = ""
    return r


def test_agentic_runner_end_turn_immediately():
    """If Claude returns [] immediately, turn records empty tool list."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "Hello", "expected_tools": []}],
    }
    with patch("tool_harness.agentic_runner.subprocess.run",
               return_value=_make_run_result("[]")):
        results, error = runner.run_scenario(scenario)

    assert error is None
    assert results is not None
    assert results[0] == []


def test_agentic_runner_records_tool_calls():
    """Tool names from tool calls are recorded correctly."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Test",
        "turns": [{"user_message": "List domains", "expected_tools": ["list_domains"]}],
    }
    with patch("tool_harness.agentic_runner.subprocess.run") as mock_run:
        mock_run.side_effect = [
            _make_run_result('[{"tool": "list_domains", "params": {}}]'),
            _make_run_result("[]"),
        ]
        results, error = runner.run_scenario(scenario)

    assert error is None
    assert "list_domains" in results[0]


def test_agentic_runner_multi_turn_accumulates_history():
    """Each turn's context is passed into the next turn's prompt."""
    runner = AgenticRunner(executor=MockExecutor(), model="claude-sonnet-4-6")
    scenario = {
        "name": "Two-turn test",
        "turns": [
            {"user_message": "Turn one", "expected_tools": []},
            {"user_message": "Turn two", "expected_tools": []},
        ],
    }
    with patch("tool_harness.agentic_runner.subprocess.run",
               return_value=_make_run_result("[]")) as mock_run:
        results, error = runner.run_scenario(scenario)

    assert error is None
    assert len(results) == 2
    # One subprocess call per turn (no tools → single iteration each)
    assert mock_run.call_count == 2
    # Second call's prompt must include "Turn one" from conversation history
    second_prompt = mock_run.call_args_list[1].args[0][2]  # ["claude", "-p", prompt][2]
    assert "Turn one" in second_prompt
