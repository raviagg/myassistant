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
