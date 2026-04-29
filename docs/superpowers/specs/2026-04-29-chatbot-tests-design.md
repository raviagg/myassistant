# Chatbot Tests — Kickstart + Multi-Mode Harness Design

## Goal

Add a `kickstart.md` to `client/chatbot_tests/` and extend the tool harness to support three test modes: the existing planning-only mock mode, a new agentic loop with mock responses, and a new agentic loop against a real running stack.

## Architecture Decision: Direct Tool Import vs MCP Subprocess

**We chose direct tool function import (Approach B) over spawning the MCP server as a subprocess (Approach A).**

Approach A would start `backend/mcp_server/server.py` as a subprocess and communicate with it via the MCP stdio protocol using the `mcp` Python client library. This tests the full MCP transport path but adds significant complexity: subprocess lifecycle management, stdio pipe handling, and MCP protocol deserialization — all testing infrastructure rather than agent behaviour.

Approach B imports the Python tool functions from `backend/mcp_server/tools/*.py` directly and calls them with an httpx client configured to point at the running http_server. The MCP protocol itself is already tested by `backend/mcp_server/tests/` (respx-mocked pytest suite), so there is no coverage gap from skipping it here.

---

## The Three Modes

| Mode | CLI flag | How Claude is invoked | Tool execution | Services needed |
|---|---|---|---|---|
| 1 | `--mode mock-plan` | `claude -p` subprocess (unchanged) | No execution — validates planned tool names | None |
| 2 | `--mode mock-loop` | Anthropic SDK agentic loop | `MockServer.handle()` from `mock_server.py` | None |
| 3 | `--mode live-loop` | Anthropic SDK agentic loop | Real `mcp_server` tool functions via httpx | http_server + PostgreSQL |

`--mode mock-plan` is the default, preserving all existing behaviour.

---

## Agentic Loop (Modes 2 & 3)

### Per-turn loop

```
1. Build messages list: system prompt + user message for this turn
2. Call anthropic.messages.create(model=..., tools=ALL_TOOLS, messages=...)
3. While response stop_reason == "tool_use":
     a. Extract all tool_use blocks from response
     b. Route each to executor.call(tool_name, tool_input) → result dict
     c. Append assistant message + tool_results to messages
     d. Call anthropic.messages.create(...) again
4. Record all tool names called during this turn
5. Validate recorded names against turn's expected_tools / forbidden_tools
```

### Executor interface

Both executors expose a single method: `call(tool_name: str, tool_input: dict) -> dict`.

**`MockExecutor`** — wraps `MockServer().handle(tool_name, tool_input)`. Returns static mock dicts from `mock_server.py`. No network calls. Used by Mode 2.

**`LiveExecutor`** — imports tool functions from `backend/mcp_server/tools/*.py`. At construction, sets `base_url` and `auth_token` on the module-level `httpx.Client` in `backend/mcp_server/client.py` (the same client all tool functions share). Dispatches by tool name to the matching Python function. Used by Mode 3.

Both executors are interchangeable — `AgenticRunner` holds an `Executor` and never knows which implementation it has.

---

## File Structure

```
client/chatbot_tests/
  kickstart.md                     ← new: prerequisites, modes, env vars, examples
  pyproject.toml                   ← new: anthropic, httpx dependencies
  tool_harness/
    harness.py                     ← extend: --mode flag, delegate to AgenticRunner
    mock_server.py                 ← unchanged
    scenarios.py                   ← unchanged
    tool_definitions.py            ← unchanged
    executors.py                   ← new: MockExecutor, LiveExecutor
    agentic_runner.py              ← new: Anthropic SDK loop, validation
    server_manager.py              ← new: http_server auto-start/teardown
```

No existing files are deleted. All new behaviour is additive.

---

## http_server Auto-Start (E2E Pattern)

`server_manager.py` follows the same pattern as the Cucumber E2E tests in `backend/http_server/`.

### Environment variables

| Env var | Default | Purpose |
|---|---|---|
| `CHATBOT_HTTP_URL` | *(not set)* | If set, target this URL — no auto-start |
| `CHATBOT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Bearer token for all requests |
| `CHATBOT_DB` | `myassistant` | PostgreSQL database: `myassistant` or `myassistanttest` |
| `ANTHROPIC_API_KEY` | *(required for modes 2 & 3)* | Anthropic API key |

### Startup behaviour

When `CHATBOT_HTTP_URL` is **not set** (Mode 3 only):
1. `server_manager.py` locates the http_server fat JAR at `backend/http_server/target/scala-3.4.2/myassistant-backend-assembly-*.jar`
2. Starts it as a subprocess with `DB_URL` set to `jdbc:postgresql://localhost:5432/<CHATBOT_DB>` and `SERVER_PORT=8181`
3. Polls `GET http://localhost:8181/health` every 500ms, timeout 30s
4. Runs all scenarios
5. Terminates the subprocess on exit

When `CHATBOT_HTTP_URL` **is set**, that URL is used directly — user is responsible for starting the server with the correct DB.

Modes 1 and 2 never invoke `server_manager.py`.

---

## Validation

Validation logic is identical across all three modes — only the source of the tool call list differs:

- **Mode 1**: tool names come from Claude's JSON plan output
- **Modes 2 & 3**: tool names come from the `tool_use` blocks Claude sent during the agentic loop

After each turn, `expected_tools` and `forbidden_tools` from `scenarios.py` are checked against the recorded tool names. Output format is unchanged.

### Future: `--verify-db` (out of scope)

A planned future addition for Mode 3: post-scenario SQL assertions that check data actually landed in the database (e.g. after a "create person" scenario, verify the row exists). Not part of this spec.

---

## Future: Mode 4 — Interactive Chatbot (next feature)

Mode 4 will reuse `AgenticRunner` and `LiveExecutor` directly. Instead of iterating over `SCENARIOS`, it runs a `input()` REPL loop: the user types each turn, the agentic loop executes against the real server, and tool calls made per turn are printed to the terminal. No scenarios, no validation. This is scoped as the next feature after this spec is implemented.

---

## kickstart.md Coverage

The `kickstart.md` will document:
- Prerequisites (Python 3.11+, `anthropic` package, http_server fat JAR for Mode 3)
- All env vars and their defaults
- How to run each mode with examples
- How to select the test database (`CHATBOT_DB=myassistanttest`)
- How to add a new scenario
- How to run a specific scenario by index
