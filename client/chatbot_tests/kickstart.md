# Kickstart — Chatbot Tests

Everything you need to run the MCP tool harness from a fresh checkout.

---

## What This Is

The tool harness validates that Claude selects the right MCP tools for a given user message.
It supports three modes:

| Mode | Flag | What it does | Services needed |
|---|---|---|---|
| mock-plan | `--mode mock-plan` (default) | `claude -p` subprocess; Claude outputs a JSON plan; validates tool names | None |
| mock-loop | `--mode mock-loop` | Agentic loop; tool calls routed to static mocks | None (bedrock) or None (claude-p) |
| live-loop | `--mode live-loop` | Agentic loop; tool calls hit a real http_server + PostgreSQL | http_server + PostgreSQL |

Modes `mock-loop` and `live-loop` support two backends (see below).

**Architecture note:** `live-loop` imports tool functions from `backend/mcp_server/tools/` directly
(not via the MCP stdio protocol). The MCP protocol is already tested by `backend/mcp_server/tests/`.
See `docs/superpowers/specs/2026-04-29-chatbot-tests-design.md` for the full rationale.

---

## Backends (mock-loop and live-loop)

| Backend | Flag | How it works | Credentials needed |
|---|---|---|---|
| bedrock | `--backend bedrock` (default) | Anthropic SDK via AWS Bedrock; native tool use; prompt caching on system + tools | AWS credentials |
| claude-p | `--backend claude-p` | `claude -p` subprocess; Claude outputs JSON text | None (claude CLI on PATH) |

**Why bedrock is the default:**
- Native tool use means structured output — no wasted explanation tokens
- System prompt and all 43 tool definitions are prompt-cached after the first call
- Typical output per step: ~50-200 tokens vs ~1,500 with claude-p
- Much faster and cheaper per scenario

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Python | 3.11+ | `brew install python@3.11` |
| AWS credentials | — | Required for `bedrock` backend (default) |
| Claude CLI | any | Required for `mock-plan` and `claude-p` backend — must be on PATH |
| Java | 21+ | Required for `live-loop` auto-start — `brew install openjdk@21` |

---

## Install

```bash
cd client/chatbot_tests
pip install -e ".[dev]"
```

---

## Environment Variables

### AWS Bedrock (bedrock backend)

Two auth options — use whichever you have:

| Variable | Default | Notes |
|---|---|---|
| `BEDROCK_API_KEY` | — | Long-lived Bearer token (365 days). Retrieved from AWS Secrets Manager: `bedrock/AWS2942/STG/api-key`. Preferred — no rotation needed. |
| `AWS_REGION` | `us-west-2` | Bedrock region |

If `BEDROCK_API_KEY` is not set, falls back to SigV4 (temporary credentials, 12h):

| Variable | Default | Notes |
|---|---|---|
| `AWS_ACCESS_KEY_ID` | — | Temporary credential |
| `AWS_SECRET_ACCESS_KEY` | — | Temporary credential |
| `AWS_SESSION_TOKEN` | — | Temporary credential |

### live-loop server

| Variable | Default | Required for |
|---|---|---|
| `CHATBOT_HTTP_URL` | *(not set → auto-start)* | `live-loop` only |
| `CHATBOT_AUTH_TOKEN` | `dev-token-change-me-in-production` | `live-loop` |
| `CHATBOT_DB` | `myassistanttest` | `live-loop` auto-start only |

When `CHATBOT_HTTP_URL` is **not set** in `live-loop` mode, the harness starts the http_server fat JAR
automatically on port 8181 using the `myassistanttest` database. Build the JAR first:

```bash
cd backend/http_server
sbt assembly
```

When `CHATBOT_HTTP_URL` **is set**, point it at a running http_server and the harness will use it directly.

---

## Running the Tests

### Mode 1: mock-plan (default)

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

### Mode 2: mock-loop — Bedrock backend (default)

```bash
# Set AWS credentials first
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_SESSION_TOKEN=...

python -m tool_harness.harness --mode mock-loop
python -m tool_harness.harness --mode mock-loop --scenario 1
python -m tool_harness.harness --mode mock-loop --all
python -m tool_harness.harness --mode mock-loop --scenario 3 --verbose

# Use a different model
python -m tool_harness.harness --mode mock-loop --model us.anthropic.claude-haiku-4-5-20251001-v1:0
```

### Mode 2: mock-loop — claude-p backend (no AWS needed)

```bash
python -m tool_harness.harness --mode mock-loop --backend claude-p
python -m tool_harness.harness --mode mock-loop --backend claude-p --scenario 1
```

### Mode 3: live-loop — Bedrock backend (default)

Build the JAR if not already built:
```bash
cd backend/http_server && sbt assembly && cd -
```

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_SESSION_TOKEN=...

# Auto-starts http_server on port 8181 using myassistanttest DB (default)
python -m tool_harness.harness --mode live-loop --scenario 1
python -m tool_harness.harness --mode live-loop --all
```

To target the production `myassistant` database instead:
```bash
CHATBOT_DB=myassistant python -m tool_harness.harness --mode live-loop --scenario 1
```

### Mode 3: live-loop against a manually started server

```bash
# Terminal 1 — start http_server pointing at test DB
DB_URL="jdbc:postgresql://localhost:5432/myassistanttest" sbt run

# Terminal 2 — run harness
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

Tests cover: `MockExecutor`, `LiveExecutor` dispatch table, `AgenticRunner` subprocess loop logic, `server_manager` env-var passthrough.

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
  pyproject.toml            dependencies: anthropic, boto3, httpx, pytest
  tool_harness/
    harness.py              CLI entry point — --mode, --backend, --scenario, --all, --verbose
    agentic_runner.py       agentic loop — bedrock (native tool use) or claude-p (subprocess)
    executors.py            MockExecutor (mock data) + LiveExecutor (real server)
    server_manager.py       http_server auto-start / env-var passthrough
    mock_server.py          static mock responses for all 43 tools
    scenarios.py            25 test scenarios with expected tool call sequences
    tool_definitions.py     43 tool definitions in Anthropic tool-use format
    tests/
      test_executors.py
      test_agentic_runner.py
      test_server_manager.py
```
