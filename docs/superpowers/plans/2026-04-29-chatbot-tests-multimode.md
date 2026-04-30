# Chatbot Tests — Multi-Mode Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `pyproject.toml`, `kickstart.md`, and three harness modes (`mock-plan`, `mock-loop`, `live-loop`) to `client/chatbot_tests/`, where mock-plan is the existing behaviour and mock-loop/live-loop use the Anthropic SDK agentic loop.

**Architecture:** The Anthropic SDK drives a real tool-use conversation loop for modes 2 and 3. Tool calls are routed through an `Executor` interface — `MockExecutor` returns static responses from the existing `mock_server.py`; `LiveExecutor` imports Python functions from `backend/mcp_server/tools/` and calls the real http_server via httpx. Mode 1 (`mock-plan`) is unchanged.

**Tech Stack:** Python 3.11+, `anthropic` SDK, `httpx`, `pytest`, existing `mock_server.py` and `tool_definitions.py`.

---

## File Map

| File | Status | Responsibility |
|---|---|---|
| `client/chatbot_tests/pyproject.toml` | Create | Package metadata + `anthropic`, `httpx`, `pytest` dependencies |
| `client/chatbot_tests/tool_harness/executors.py` | Create | `MockExecutor` and `LiveExecutor` |
| `client/chatbot_tests/tool_harness/agentic_runner.py` | Create | Anthropic SDK loop + per-turn validation |
| `client/chatbot_tests/tool_harness/server_manager.py` | Create | http_server auto-start / env-var passthrough |
| `client/chatbot_tests/tool_harness/harness.py` | Modify | Add `--mode` and `--model` flags, delegate to agentic runner |
| `client/chatbot_tests/kickstart.md` | Create | All docs: prereqs, modes, env vars, examples |
| `client/chatbot_tests/tool_harness/tests/test_executors.py` | Create | Unit tests for both executors |
| `client/chatbot_tests/tool_harness/tests/test_agentic_runner.py` | Create | Unit tests for agentic loop (mocked Anthropic SDK) |
| `client/chatbot_tests/tool_harness/tests/test_server_manager.py` | Create | Unit tests for server_manager env-var passthrough |

---

## Task 1: pyproject.toml

**Files:**
- Create: `client/chatbot_tests/pyproject.toml`

- [ ] **Step 1: Create pyproject.toml**

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "myassistant-chatbot-tests"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "anthropic>=0.40",
    "httpx>=0.27",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[tool.pytest.ini_options]
testpaths = ["tool_harness/tests"]
```

- [ ] **Step 2: Install dependencies**

```bash
cd client/chatbot_tests
pip install -e ".[dev]"
```

Expected: `Successfully installed myassistant-chatbot-tests-0.1.0` (and anthropic, httpx, pytest)

- [ ] **Step 3: Commit**

```bash
git add client/chatbot_tests/pyproject.toml
git commit -m "feat(chatbot-tests): add pyproject.toml with anthropic + httpx deps"
```

---

## Task 2: executors.py — MockExecutor and LiveExecutor

**Files:**
- Create: `client/chatbot_tests/tool_harness/executors.py`
- Create: `client/chatbot_tests/tool_harness/tests/__init__.py`
- Create: `client/chatbot_tests/tool_harness/tests/test_executors.py`

- [ ] **Step 1: Write the failing tests**

Create `client/chatbot_tests/tool_harness/tests/__init__.py` (empty file).

Create `client/chatbot_tests/tool_harness/tests/test_executors.py`:

```python
import sys
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from executors import MockExecutor


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
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_executors.py -v
```

Expected: `ImportError: cannot import name 'MockExecutor' from 'executors'` (or ModuleNotFoundError)

- [ ] **Step 3: Implement executors.py**

Create `client/chatbot_tests/tool_harness/executors.py`:

```python
import pathlib
import sys
import httpx

from .mock_server import MockServer

# ── MockExecutor ─────────────────────────────────────────────────────────────

class MockExecutor:
    """Routes tool calls to static mock responses from mock_server.py."""

    def __init__(self):
        self._server = MockServer()

    def call(self, tool_name: str, tool_input: dict) -> dict:
        return self._server.handle(tool_name, tool_input)


# ── LiveExecutor ─────────────────────────────────────────────────────────────

def _build_dispatch(http: httpx.Client) -> dict:
    """Import mcp_server tool functions and build a name→callable dispatch table."""
    mcp_path = pathlib.Path(__file__).parents[3] / "backend" / "mcp_server"
    if str(mcp_path) not in sys.path:
        sys.path.insert(0, str(mcp_path))

    from tools import (
        persons, households, person_household, relationships,
        documents, facts, schemas, reference, audit, files,
    )

    return {
        # persons
        "create_person":               lambda i: persons.create_person(http, **i),
        "get_person":                  lambda i: persons.get_person(http, **i),
        "search_persons":              lambda i: persons.search_persons(http, **i),
        "update_person":               lambda i: persons.update_person(http, **i),
        "delete_person":               lambda i: persons.delete_person(http, **i),
        # households
        "create_household":            lambda i: households.create_household(http, **i),
        "get_household":               lambda i: households.get_household(http, **i),
        "search_households":           lambda i: households.search_households(http, **i),
        "update_household":            lambda i: households.update_household(http, **i),
        "delete_household":            lambda i: households.delete_household(http, **i),
        # person-household
        "add_person_to_household":     lambda i: person_household.add_person_to_household(http, **i),
        "remove_person_from_household":lambda i: person_household.remove_person_from_household(http, **i),
        "list_household_members":      lambda i: person_household.list_household_members(http, **i),
        "list_person_households":      lambda i: person_household.list_person_households(http, **i),
        # relationships
        "create_relationship":         lambda i: relationships.create_relationship(http, **i),
        "get_relationship":            lambda i: relationships.get_relationship(http, **i),
        "list_relationships":          lambda i: relationships.list_relationships(http, **i),
        "update_relationship":         lambda i: relationships.update_relationship(http, **i),
        "delete_relationship":         lambda i: relationships.delete_relationship(http, **i),
        "resolve_kinship":             lambda i: relationships.resolve_kinship(http, **i),
        # documents
        "create_document":             lambda i: documents.create_document(http, **i),
        "get_document":                lambda i: documents.get_document(http, **i),
        "list_documents":              lambda i: documents.list_documents(http, **i),
        "search_documents":            lambda i: documents.search_documents(http, **i),
        # facts
        "create_fact":                 lambda i: facts.create_fact(http, **i),
        "get_fact_history":            lambda i: facts.get_fact_history(http, **i),
        "get_current_fact":            lambda i: facts.get_current_fact(http, **i),
        "list_current_facts":          lambda i: facts.list_current_facts(http, **i),
        "search_current_facts":        lambda i: facts.search_current_facts(http, **i),
        # schemas
        "list_entity_type_schemas":    lambda i: schemas.list_entity_type_schemas(http, **i),
        "get_entity_type_schema":      lambda i: schemas.get_entity_type_schema(http, **i),
        "get_current_entity_type_schema": lambda i: schemas.get_current_entity_type_schema(http, **i),
        "create_entity_type_schema":   lambda i: schemas.create_entity_type_schema(http, **i),
        "update_entity_type_schema":   lambda i: schemas.update_entity_type_schema(http, **i),
        "deactivate_entity_type_schema": lambda i: schemas.deactivate_entity_type_schema(http, **i),
        # reference
        "list_domains":                lambda i: reference.list_domains(http, **i),
        "list_source_types":           lambda i: reference.list_source_types(http, **i),
        "list_kinship_aliases":        lambda i: reference.list_kinship_aliases(http, **i),
        # audit
        "log_interaction":             lambda i: audit.log_interaction(http, **i),
        # files
        "save_file":                   lambda i: files.save_file(http, **i),
        "extract_text_from_file":      lambda i: files.extract_text_from_file(http, **i),
        "get_file":                    lambda i: files.get_file(http, **i),
        "delete_file":                 lambda i: files.delete_file(http, **i),
    }


class LiveExecutor:
    """Routes tool calls to real mcp_server tool functions via a live http_server."""

    def __init__(self, base_url: str, auth_token: str):
        self._http = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {auth_token}"},
            timeout=30.0,
        )
        self._dispatch = _build_dispatch(self._http)

    def call(self, tool_name: str, tool_input: dict) -> dict:
        fn = self._dispatch.get(tool_name)
        if fn is None:
            return {"error": "unknown_tool", "tool_name": tool_name}
        return fn(tool_input)

    def close(self):
        self._http.close()
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_executors.py -v
```

Expected:
```
PASSED test_mock_executor_create_person
PASSED test_mock_executor_list_domains
PASSED test_mock_executor_unknown_tool_returns_error
3 passed
```

- [ ] **Step 5: Commit**

```bash
git add client/chatbot_tests/tool_harness/executors.py \
        client/chatbot_tests/tool_harness/tests/__init__.py \
        client/chatbot_tests/tool_harness/tests/test_executors.py
git commit -m "feat(chatbot-tests): add MockExecutor and LiveExecutor"
```

---

## Task 3: agentic_runner.py — Anthropic SDK loop

**Files:**
- Create: `client/chatbot_tests/tool_harness/agentic_runner.py`
- Create: `client/chatbot_tests/tool_harness/tests/test_agentic_runner.py`

- [ ] **Step 1: Write the failing tests**

Create `client/chatbot_tests/tool_harness/tests/test_agentic_runner.py`:

```python
import json
import sys
import pathlib
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from agentic_runner import AgenticRunner
from executors import MockExecutor


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
    with patch("agentic_runner.anthropic.Anthropic") as mock_cls:
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
    with patch("agentic_runner.anthropic.Anthropic") as mock_cls:
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
    with patch("agentic_runner.anthropic.Anthropic") as mock_cls:
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
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_agentic_runner.py -v
```

Expected: `ImportError: No module named 'agentic_runner'`

- [ ] **Step 3: Implement agentic_runner.py**

Create `client/chatbot_tests/tool_harness/agentic_runner.py`:

```python
import json
import anthropic

from .tool_definitions import ALL_TOOLS
from .scenarios import SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS

SEP  = "━" * 72
THIN = "─" * 72


class AgenticRunner:
    """
    Runs scenarios using the Anthropic SDK agentic loop.
    Tool calls are routed to the injected executor (MockExecutor or LiveExecutor).
    """

    def __init__(self, executor, model: str = "claude-sonnet-4-6"):
        self._executor = executor
        self._model    = model
        self._client   = anthropic.Anthropic()

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, str | None]:
        """
        Run all turns of a scenario.
        Returns (list_of_tool_name_lists_per_turn, error).
        """
        messages: list[dict] = []
        all_turn_tool_names: list[list[str]] = []

        for turn_idx, turn in enumerate(scenario["turns"]):
            messages.append({"role": "user", "content": turn["user_message"]})
            try:
                tool_names, messages = self._run_turn(messages, verbose)
            except Exception as exc:
                return None, f"Turn {turn_idx + 1}: {exc}"
            all_turn_tool_names.append(tool_names)

        return all_turn_tool_names, None

    # ── Internal ──────────────────────────────────────────────────────────

    def _run_turn(
        self, messages: list[dict], verbose: bool
    ) -> tuple[list[str], list[dict]]:
        """
        Drive the API loop until stop_reason == 'end_turn'.
        Returns (tool_names_called_this_turn, updated_messages).
        """
        tool_names: list[str] = []

        while True:
            response = self._client.messages.create(
                model=self._model,
                max_tokens=4096,
                system=SYSTEM_PROMPT,
                tools=ALL_TOOLS,
                messages=messages,
            )

            tool_use_blocks = [b for b in response.content if b.type == "tool_use"]
            tool_names.extend(b.name for b in tool_use_blocks)

            if verbose:
                for b in tool_use_blocks:
                    print(f"  [tool] {b.name}({json.dumps(b.input)[:120]})")

            # append assistant message to history
            messages = messages + [{"role": "assistant", "content": response.content}]

            if response.stop_reason == "end_turn" or not tool_use_blocks:
                break

            # execute tool calls and collect results
            tool_results = []
            for block in tool_use_blocks:
                try:
                    result = self._executor.call(block.name, block.input)
                    content = json.dumps(result)
                except Exception as exc:
                    content = str(exc)
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": content,
                })

            messages = messages + [{"role": "user", "content": tool_results}]

        return tool_names, messages

    # ── Output (same format as harness.py) ───────────────────────────────

    def print_result(
        self,
        scenario: dict,
        all_turn_tool_names: list[list[str]] | None,
        error: str | None,
    ) -> None:
        turns = scenario["turns"]
        scenario_forbidden = scenario.get("forbidden_tools", [])
        global_forbidden = list(dict.fromkeys(GLOBAL_FORBIDDEN_TOOLS + scenario_forbidden))

        print(f"\n{SEP}")
        print(f"  {scenario['name']}")
        print(SEP)

        if error:
            print(f"  ERROR: {error}")
            return

        scenario_pass = True

        for turn_idx, (turn, tool_names) in enumerate(zip(turns, all_turn_tool_names)):
            n_turns = len(turns)
            print(f"\n  ── Turn {turn_idx + 1} of {n_turns} " + "─" * (53 - len(str(n_turns))))
            print(f"  USER: \"{turn['user_message']}\"")
            print()
            print(f"  TOOL CALLS  ({len(tool_names)} total)")
            print(f"  {THIN}")
            for n, name in enumerate(tool_names, 1):
                print(f"  {n:2d}. {name}")
            print()

            expected       = turn.get("expected_tools", [])
            turn_forbidden = turn.get("forbidden_tools", [])
            all_forbidden  = list(dict.fromkeys(global_forbidden + turn_forbidden))

            if expected or all_forbidden:
                turn_ok = True
                print(f"  VALIDATION")
                print(f"  {THIN}")
                for exp in expected:
                    found = exp in tool_names
                    if not found:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✓' if found else '✗'}  {exp}")
                for forb in all_forbidden:
                    present = forb in tool_names
                    if present:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✗ SHOULD NOT appear:' if present else '✓ (absent)'}  {forb}")
                extra = [n for n in tool_names if n not in expected and n not in all_forbidden]
                for nm in extra:
                    print(f"    ?  {nm}  (not in expected list — may be fine)")
                print(f"    → {'all checks passed ✓' if turn_ok else 'ISSUES FOUND'}")
                print()

        status = "PASS ✓" if scenario_pass else "FAIL ✗"
        print(f"  SCENARIO {status}")
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_agentic_runner.py -v
```

Expected:
```
PASSED test_agentic_runner_end_turn_immediately
PASSED test_agentic_runner_records_tool_calls
PASSED test_agentic_runner_multi_turn_accumulates_history
3 passed
```

- [ ] **Step 5: Commit**

```bash
git add client/chatbot_tests/tool_harness/agentic_runner.py \
        client/chatbot_tests/tool_harness/tests/test_agentic_runner.py
git commit -m "feat(chatbot-tests): add AgenticRunner with Anthropic SDK loop"
```

---

## Task 4: server_manager.py — http_server auto-start

**Files:**
- Create: `client/chatbot_tests/tool_harness/server_manager.py`
- Create: `client/chatbot_tests/tool_harness/tests/test_server_manager.py`

- [ ] **Step 1: Write the failing tests**

Create `client/chatbot_tests/tool_harness/tests/test_server_manager.py`:

```python
import sys
import pathlib
import os
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).parents[2]))

from server_manager import managed_server


def test_managed_server_uses_env_url_when_set():
    """When CHATBOT_HTTP_URL is set, managed_server yields it without starting a subprocess."""
    with patch.dict(os.environ, {"CHATBOT_HTTP_URL": "http://localhost:9999"}):
        with managed_server() as url:
            assert url == "http://localhost:9999"


def test_managed_server_uses_default_token():
    """CHATBOT_AUTH_TOKEN defaults correctly."""
    with patch.dict(os.environ, {"CHATBOT_HTTP_URL": "http://localhost:9999"}, clear=False):
        os.environ.pop("CHATBOT_AUTH_TOKEN", None)
        from server_manager import auth_token
        assert auth_token() == "dev-token-change-me-in-production"


def test_managed_server_uses_custom_token():
    with patch.dict(os.environ, {
        "CHATBOT_HTTP_URL": "http://localhost:9999",
        "CHATBOT_AUTH_TOKEN": "my-custom-token",
    }):
        from server_manager import auth_token
        assert auth_token() == "my-custom-token"
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_server_manager.py -v
```

Expected: `ImportError: No module named 'server_manager'`

- [ ] **Step 3: Implement server_manager.py**

Create `client/chatbot_tests/tool_harness/server_manager.py`:

```python
import glob
import os
import pathlib
import subprocess
import sys
import time
from contextlib import contextmanager

import httpx

_DEFAULT_PORT  = 8181
_DEFAULT_TOKEN = "dev-token-change-me-in-production"
_DEFAULT_DB    = "myassistant"
_HEALTH_TIMEOUT = 30  # seconds


def auth_token() -> str:
    return os.environ.get("CHATBOT_AUTH_TOKEN", _DEFAULT_TOKEN)


def _find_jar() -> str:
    repo_root = pathlib.Path(__file__).parents[3]
    pattern   = str(repo_root / "backend" / "http_server" / "target" / "scala-3.4.2"
                    / "myassistant-backend-assembly-*.jar")
    matches   = glob.glob(pattern)
    if not matches:
        print(
            f"\nERROR: http_server fat JAR not found at {pattern}\n"
            "Build it first:\n"
            "  cd backend/http_server && sbt assembly\n",
            file=sys.stderr,
        )
        sys.exit(1)
    return matches[0]


def _wait_for_health(url: str, timeout: int = _HEALTH_TIMEOUT) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = httpx.get(f"{url}/health", timeout=2.0)
            if r.status_code == 200:
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError(f"http_server did not become healthy at {url} within {timeout}s")


@contextmanager
def managed_server():
    """
    Context manager that yields the http_server base URL.

    If CHATBOT_HTTP_URL is set  → yield it directly (user manages the server).
    If CHATBOT_HTTP_URL is unset → start the fat JAR as a subprocess on port 8181,
                                   wait for /health, yield URL, then kill on exit.
    """
    explicit_url = os.environ.get("CHATBOT_HTTP_URL")
    if explicit_url:
        yield explicit_url
        return

    db   = os.environ.get("CHATBOT_DB", _DEFAULT_DB)
    jar  = _find_jar()
    url  = f"http://localhost:{_DEFAULT_PORT}"
    env  = {
        **os.environ,
        "DB_URL":      f"jdbc:postgresql://localhost:5432/{db}",
        "DB_USER":     os.environ.get("DB_USER", "myassistant"),
        "DB_PASSWORD": os.environ.get("DB_PASSWORD", "changeme"),
        "AUTH_TOKEN":  auth_token(),
        "SERVER_PORT": str(_DEFAULT_PORT),
    }
    print(f"\n[server_manager] Starting http_server on port {_DEFAULT_PORT} (db={db})...")
    proc = subprocess.Popen(
        ["java", "-jar", jar],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        _wait_for_health(url)
        print(f"[server_manager] http_server ready at {url}")
        yield url
    finally:
        print("\n[server_manager] Stopping http_server...")
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd client/chatbot_tests
pytest tool_harness/tests/test_server_manager.py -v
```

Expected:
```
PASSED test_managed_server_uses_env_url_when_set
PASSED test_managed_server_uses_default_token
PASSED test_managed_server_uses_custom_token
3 passed
```

- [ ] **Step 5: Commit**

```bash
git add client/chatbot_tests/tool_harness/server_manager.py \
        client/chatbot_tests/tool_harness/tests/test_server_manager.py
git commit -m "feat(chatbot-tests): add server_manager with E2E-pattern auto-start"
```

---

## Task 5: Extend harness.py — `--mode` and `--model` flags

**Files:**
- Modify: `client/chatbot_tests/tool_harness/harness.py` (currently 359 lines)

The existing `main()` function uses `argparse` with `--scenario`, `--all`, and `--verbose`. We add `--mode` and `--model` and delegate to `AgenticRunner` for modes 2 and 3.

- [ ] **Step 1: Add imports at top of harness.py**

Open `client/chatbot_tests/tool_harness/harness.py`. After the existing imports block (after line 32, before `from .tool_definitions import ALL_TOOLS`), add:

```python
import os

from .agentic_runner import AgenticRunner
from .executors import MockExecutor, LiveExecutor
from .server_manager import managed_server, auth_token
```

- [ ] **Step 2: Replace the `main()` function**

Replace the entire `def main() -> None:` function (lines 321–358) with:

```python
def main() -> None:
    parser = argparse.ArgumentParser(description="MCP Tool Harness")
    parser.add_argument("--scenario", type=int, metavar="N", help="Run scenario N (1-based)")
    parser.add_argument("--all",      action="store_true",   help="Run all scenarios")
    parser.add_argument("--verbose",  action="store_true",   help="Show raw output per turn")
    parser.add_argument(
        "--mode",
        choices=["mock-plan", "mock-loop", "live-loop"],
        default="mock-plan",
        help=(
            "mock-plan: existing behaviour — claude -p subprocess, validates planned tool names (default). "
            "mock-loop: Anthropic SDK agentic loop with static mock responses (no services needed). "
            "live-loop: Anthropic SDK agentic loop with real http_server + PostgreSQL."
        ),
    )
    parser.add_argument(
        "--model",
        default="claude-sonnet-4-6",
        help="Claude model for mock-loop / live-loop (default: claude-sonnet-4-6)",
    )
    args = parser.parse_args()

    if args.scenario:
        idx = args.scenario - 1
        if not 0 <= idx < len(SCENARIOS):
            print(f"Error: --scenario must be 1–{len(SCENARIOS)}", file=sys.stderr)
            sys.exit(1)
        to_run = [SCENARIOS[idx]]
    elif args.all:
        to_run = SCENARIOS
    else:
        to_run = SCENARIOS[:3]
        print(f"Running first 3 of {len(SCENARIOS)} scenarios. "
              f"Use --all for all, --scenario N for one.")

    total_turns = sum(len(s["turns"]) for s in to_run)
    print(f"\n{len(ALL_TOOLS)} tools defined · {len(SCENARIOS)} scenarios available "
          f"· {total_turns} turns to run · mode={args.mode}")

    # ── Mode 1: existing mock-plan behaviour ─────────────────────────────
    if args.mode == "mock-plan":
        _check_claude_available()
        for scenario in to_run:
            n_turns = len(scenario["turns"])
            turn_label = f"{n_turns} turn{'s' if n_turns > 1 else ''}"
            print(f"\nRunning {scenario['name']} ({turn_label})...", end=" ", flush=True)
            all_turn_calls, error = run_scenario(scenario, verbose=args.verbose)
            print("done" if not error else "error")
            print_result(scenario, all_turn_calls, error)
        print(SEP)
        return

    # ── Modes 2 & 3: agentic loop ────────────────────────────────────────
    if "ANTHROPIC_API_KEY" not in os.environ:
        print("Error: ANTHROPIC_API_KEY env var is required for mock-loop and live-loop.",
              file=sys.stderr)
        sys.exit(1)

    if args.mode == "mock-loop":
        executor = MockExecutor()
        runner   = AgenticRunner(executor=executor, model=args.model)
        for scenario in to_run:
            n_turns = len(scenario["turns"])
            print(f"\nRunning {scenario['name']} ({n_turns} turn(s))...", end=" ", flush=True)
            tool_names_per_turn, error = runner.run_scenario(scenario, verbose=args.verbose)
            print("done" if not error else "error")
            runner.print_result(scenario, tool_names_per_turn, error)
        print(SEP)
        return

    # live-loop
    with managed_server() as base_url:
        token    = auth_token()
        executor = LiveExecutor(base_url=base_url, auth_token=token)
        runner   = AgenticRunner(executor=executor, model=args.model)
        try:
            for scenario in to_run:
                n_turns = len(scenario["turns"])
                print(f"\nRunning {scenario['name']} ({n_turns} turn(s))...", end=" ", flush=True)
                tool_names_per_turn, error = runner.run_scenario(scenario, verbose=args.verbose)
                print("done" if not error else "error")
                runner.print_result(scenario, tool_names_per_turn, error)
        finally:
            executor.close()
    print(SEP)
```

- [ ] **Step 3: Verify existing mode still works**

```bash
cd client/chatbot_tests
python -m tool_harness.harness --scenario 1
```

Expected: runs Scenario 1 in `mock-plan` mode (same output as before). No errors.

- [ ] **Step 4: Verify new flags are accepted**

```bash
cd client/chatbot_tests
python -m tool_harness.harness --help
```

Expected: help text shows `--mode {mock-plan,mock-loop,live-loop}` and `--model`.

- [ ] **Step 5: Commit**

```bash
git add client/chatbot_tests/tool_harness/harness.py
git commit -m "feat(chatbot-tests): add --mode and --model flags to harness"
```

---

## Task 6: kickstart.md

**Files:**
- Create: `client/chatbot_tests/kickstart.md`

- [ ] **Step 1: Write kickstart.md**

```markdown
# Kickstart — Chatbot Tests

Everything you need to run the MCP tool harness from a fresh checkout.

---

## What This Is

The tool harness validates that Claude selects the right MCP tools for a given user message.
It supports three modes:

| Mode | Flag | What it does | Services needed |
|---|---|---|---|
| mock-plan | `--mode mock-plan` (default) | `claude -p` subprocess; Claude outputs a JSON plan; validates tool names | None |
| mock-loop | `--mode mock-loop` | Anthropic SDK agentic loop; tool calls routed to static mocks | None |
| live-loop | `--mode live-loop` | Anthropic SDK agentic loop; tool calls hit a real http_server + PostgreSQL | http_server + PostgreSQL |

**Architecture note:** `live-loop` imports tool functions from `backend/mcp_server/tools/` directly
(not via the MCP stdio protocol). The MCP protocol is already tested by `backend/mcp_server/tests/`.
See `docs/superpowers/specs/2026-04-29-chatbot-tests-design.md` for the full rationale.

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Python | 3.11+ | `brew install python@3.11` |
| Claude CLI | any | Required for `mock-plan` only — must be on PATH |
| Java | 21+ | Required for `live-loop` auto-start — `brew install openjdk@21` |

---

## Install

```bash
cd client/chatbot_tests
pip install -e ".[dev]"
```

---

## Environment Variables

| Variable | Default | Required for |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | `mock-loop` and `live-loop` |
| `CHATBOT_HTTP_URL` | *(not set → auto-start)* | `live-loop` only |
| `CHATBOT_AUTH_TOKEN` | `dev-token-change-me-in-production` | `live-loop` |
| `CHATBOT_DB` | `myassistant` | `live-loop` auto-start only |

When `CHATBOT_HTTP_URL` is **not set** in `live-loop` mode, the harness starts the http_server fat JAR
automatically on port 8181. Build the JAR first:

```bash
cd backend/http_server
sbt assembly
```

When `CHATBOT_HTTP_URL` **is set**, point it at a running http_server and the harness will use it directly.

---

## Running the Tests

### Mode 1: mock-plan (default — no API key needed)

```bash
cd client/chatbot_tests

# Run first 3 scenarios (default)
python -m tool_harness.harness

# Run a specific scenario (1-based)
python -m tool_harness.harness --scenario 2

# Run all 25 scenarios
python -m tool_harness.harness --all

# Show raw claude output
python -m tool_harness.harness --scenario 4 --verbose
```

### Mode 2: mock-loop (Anthropic API, no server needed)

```bash
export ANTHROPIC_API_KEY="sk-ant-..."

python -m tool_harness.harness --mode mock-loop
python -m tool_harness.harness --mode mock-loop --scenario 1
python -m tool_harness.harness --mode mock-loop --all
python -m tool_harness.harness --mode mock-loop --model claude-opus-4-7
```

### Mode 3: live-loop against myassistant (auto-start)

Build the JAR if not already built:
```bash
cd backend/http_server && sbt assembly && cd -
```

```bash
export ANTHROPIC_API_KEY="sk-ant-..."

# Uses myassistant DB (default), auto-starts http_server on port 8181
python -m tool_harness.harness --mode live-loop --scenario 1
```

### Mode 3: live-loop against myassistanttest (auto-start)

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export CHATBOT_DB=myassistanttest

python -m tool_harness.harness --mode live-loop --all
```

### Mode 3: live-loop against a manually started server

```bash
# Terminal 1 — start http_server pointing at test DB
DB_URL="jdbc:postgresql://localhost:5432/myassistanttest" sbt run

# Terminal 2 — run harness
export ANTHROPIC_API_KEY="sk-ant-..."
export CHATBOT_HTTP_URL=http://localhost:8080
export CHATBOT_AUTH_TOKEN=dev-token-change-me-in-production

python -m tool_harness.harness --mode live-loop --scenario 1
```

---

## Unit Tests

```bash
cd client/chatbot_tests
pytest
```

Tests cover: `MockExecutor`, `LiveExecutor` dispatch table, `AgenticRunner` loop logic (mocked Anthropic SDK), `server_manager` env-var passthrough.

---

## Adding a New Scenario

Edit `tool_harness/scenarios.py`. Each scenario is a dict:

```python
{
    "name": "Scenario N: Short description",
    "turns": [
        {
            "user_message": "User says something",
            "expected_tools": ["tool_a", "tool_b"],
            "forbidden_tools": ["write_tool_c"],
        },
        {
            "user_message": "User approves",
            "expected_tools": ["create_document", "create_fact"],
        },
    ],
}
```

Append it to the `SCENARIOS` list. It will be run by `--all` and accessible by its 1-based index with `--scenario N`.

---

## Project Layout

```
client/chatbot_tests/
  kickstart.md              this file
  pyproject.toml            dependencies: anthropic, httpx, pytest
  tool_harness/
    harness.py              CLI entry point — --mode, --scenario, --all, --verbose
    agentic_runner.py       Anthropic SDK loop (modes 2 & 3)
    executors.py            MockExecutor (mock data) + LiveExecutor (real server)
    server_manager.py       http_server auto-start / env-var passthrough
    mock_server.py          static mock responses for all 43 tools
    scenarios.py            25 test scenarios with expected tool call sequences
    tool_definitions.py     43 tool definitions in Anthropic SDK format
    tests/
      test_executors.py
      test_agentic_runner.py
      test_server_manager.py
```
```

- [ ] **Step 2: Verify the file renders cleanly**

```bash
cat client/chatbot_tests/kickstart.md | head -20
```

Expected: clean markdown header output.

- [ ] **Step 3: Commit**

```bash
git add client/chatbot_tests/kickstart.md
git commit -m "docs(chatbot-tests): add kickstart.md covering all three modes"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `kickstart.md` with prereqs, modes, env vars, examples | Task 6 |
| `pyproject.toml` with anthropic + httpx | Task 1 |
| `--mode mock-plan` default, unchanged behaviour | Task 5 |
| `--mode mock-loop` Anthropic SDK + MockExecutor | Tasks 3 + 5 |
| `--mode live-loop` Anthropic SDK + LiveExecutor | Tasks 2 + 4 + 5 |
| `MockExecutor` wraps `mock_server.py` | Task 2 |
| `LiveExecutor` imports mcp_server tools, patches httpx client | Task 2 |
| `AgenticRunner` agentic loop, per-turn tool recording + validation | Task 3 |
| `server_manager.py` E2E pattern auto-start | Task 4 |
| `CHATBOT_DB` selects postgres database | Task 4 |
| Architecture decision documented | Spec file (committed) |
| Mode 4 noted as future feature | Spec file (committed) |

All requirements covered. No placeholders.
