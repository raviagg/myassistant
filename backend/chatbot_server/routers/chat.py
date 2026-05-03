import asyncio
import json
import os
from typing import Generator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from core.agentic_runner import AgenticRunner, TokenEvent, ToolCallEvent, ToolResultEvent, DoneEvent
from core.live_executor import LiveExecutor
from core.system_prompt import build_system_prompt, CHATBOT_PROMPT_ADDENDUM

router = APIRouter()

_BASE_URL   = os.environ.get("CHATBOT_HTTP_URL", "http://localhost:8080")
_AUTH_TOKEN = os.environ.get("CHATBOT_AUTH_TOKEN", "dev-token-change-me-in-production")


class ChatRequest(BaseModel):
    personId: str
    message: str
    filePaths: list[str] = []


def _get_runner(person_id: str) -> AgenticRunner:
    """
    Build a fully-configured AgenticRunner for the given person.
    Looks up person name and source_type_id from the HTTP server.
    Isolated as a module-level factory so tests can patch it.
    """
    executor = LiveExecutor(base_url=_BASE_URL, auth_token=_AUTH_TOKEN)

    # Resolve person display name
    person_result = executor.call("get_person", {"person_id": person_id})
    person_name = (
        person_result.get("preferredName")
        or person_result.get("fullName")
        or person_id
    )

    # Resolve user_input source type id
    st_result = executor.call("list_source_types", {})
    source_type_id = next(
        (st["id"] for st in st_result.get("items", []) if st.get("name") == "user_input"),
        None,
    )
    if source_type_id is None:
        raise RuntimeError("user_input source type not found")

    system_prompt = build_system_prompt(person_id, person_name, source_type_id) + CHATBOT_PROMPT_ADDENDUM
    return AgenticRunner(executor=executor, system_prompt=system_prompt, person_id=person_id)


def _build_user_message(message: str, file_paths: list[str]) -> str:
    if not file_paths:
        return message
    paths_block = "\n".join(f"- {p}" for p in file_paths)
    return f"{message}\n\nAttached files:\n{paths_block}"


def _event_line(event_type: str, payload: dict) -> str:
    return f"event: {event_type}\ndata: {json.dumps(payload)}\n\n"


def _stream_events(runner: AgenticRunner, user_message: str) -> Generator[str, None, None]:
    for event in runner.chat_turn_streaming(user_message):
        if isinstance(event, TokenEvent):
            yield _event_line("token", {"text": event.text})
        elif isinstance(event, ToolCallEvent):
            yield _event_line("tool_call", {"name": event.name, "input": event.input})
        elif isinstance(event, ToolResultEvent):
            yield _event_line("tool_result", {"toolName": event.tool_name, "result": event.result})
        elif isinstance(event, DoneEvent):
            yield _event_line("done", {"fullText": event.full_text, "debugInfo": event.debug_info})


@router.post("/api/chat")
async def chat(body: ChatRequest):
    runner = _get_runner(body.personId)
    user_message = _build_user_message(body.message, body.filePaths)

    loop = asyncio.get_event_loop()
    queue: asyncio.Queue = asyncio.Queue()

    def run_sync():
        for chunk in _stream_events(runner, user_message):
            loop.call_soon_threadsafe(queue.put_nowait, chunk)
        loop.call_soon_threadsafe(queue.put_nowait, None)

    loop.run_in_executor(None, run_sync)

    async def generate():
        while True:
            chunk = await queue.get()
            if chunk is None:
                break
            yield chunk

    return StreamingResponse(generate(), media_type="text/event-stream")
