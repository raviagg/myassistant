import json
from unittest.mock import patch, MagicMock
from core.agentic_runner import TokenEvent, ToolCallEvent, ToolResultEvent, DoneEvent


def _fake_streaming_gen(user_message):
    yield TokenEvent(text="Hello ")
    yield TokenEvent(text="world.")
    yield ToolCallEvent(name="list_domains", input={})
    yield ToolResultEvent(tool_name="list_domains", result={"items": []})
    yield DoneEvent(
        full_text="Hello world.",
        debug_info={"apiCalls": [], "toolCalls": [{"name": "list_domains", "input": {}, "result": {}}]},
    )


def test_chat_returns_sse_stream(client):
    with patch("routers.chat._get_runner") as mock_runner_factory:
        runner = MagicMock()
        runner.chat_turn_streaming.side_effect = _fake_streaming_gen
        mock_runner_factory.return_value = runner

        resp = client.post(
            "/api/chat",
            json={"personId": "aaaaaaaa-0000-4000-a000-000000000001", "message": "hello", "filePaths": []},
            headers={"Accept": "text/event-stream"},
        )

    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    body = resp.text
    assert "event: token" in body
    assert '"text": "Hello "' in body
    assert "event: done" in body
    assert "Hello world." in body


def test_chat_injects_file_paths_into_message(client):
    received_messages = []

    def capture_gen(user_message):
        received_messages.append(user_message)
        yield DoneEvent(full_text="ok", debug_info={"apiCalls": [], "toolCalls": []})

    with patch("routers.chat._get_runner") as mock_runner_factory:
        runner = MagicMock()
        runner.chat_turn_streaming.side_effect = capture_gen
        mock_runner_factory.return_value = runner

        client.post(
            "/api/chat",
            json={
                "personId": "aaa",
                "message": "store this",
                "filePaths": ["/data/files/slip.pdf"],
            },
        )

    assert len(received_messages) == 1
    assert "/data/files/slip.pdf" in received_messages[0]
    assert "store this" in received_messages[0]
