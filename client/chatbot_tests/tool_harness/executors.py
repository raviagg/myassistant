from common.live_executor import LiveExecutor  # noqa: F401
from .mock_server import MockServer


class MockExecutor:
    """Routes tool calls to static mock responses from mock_server.py."""

    def __init__(self):
        self._server = MockServer()

    def set_scenario(self, scenario: dict) -> None:
        self._server = MockServer(overrides=scenario.get("mock_overrides", {}))

    def call(self, tool_name: str, tool_input: dict) -> dict:
        return self._server.handle(tool_name, tool_input)
