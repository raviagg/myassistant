# Kickstart — Chatbot

Everything you need to run the interactive personal assistant CLI from a fresh checkout.

---

## What This Is

An interactive command-line chatbot backed by the myassistant http_server and PostgreSQL.
You type a message, the agent calls MCP tools against the live database, and prints the
final response. In verbose mode, tool calls are shown between each user/assistant exchange.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Python | 3.11+ | `brew install python@3.11` |
| Java | 21+ | Required for http_server auto-start — `brew install openjdk@21` |
| PostgreSQL | any | Must be running with the myassistant schema applied |
| Bedrock credentials | — | Required for `bedrock` backend (default) — see section below |
| Claude CLI | any | Required only for `--backend claude-p` — must be on PATH |

---

## Running (no install required)

Run directly from the repo root — no pip install needed:

```bash
python client/chatbot/run.py --person-id <your-uuid>
```

---

## Install (optional — enables `python -m chatbot`)

```bash
# From the repo root — install shared library first, then the chatbot
pip install -e client/common
pip install -e client/chatbot
```

After installing you can also use `python -m chatbot` instead of `python client/chatbot/run.py`.

---

## Find Your person_id

The chatbot requires the UUID of your person record in the database.
If you don't know it, look it up:

```bash
# With the http_server running, call search_persons directly:
curl -s -H "Authorization: Bearer dev-token-change-me-in-production" \
  "http://localhost:8181/api/persons/search?name=Ravi" | python -m json.tool

# Or query the DB directly:
psql -d myassistant -c "SELECT id, full_name FROM person;"
```

---

## Running

### Basic (Bedrock backend — default)

```bash
export BEDROCK_API_KEY=ABSKYmVkcm9...

python client/chatbot/run.py --person-id <your-uuid>
```

The http_server is started automatically on port 8181 using the `myassistanttest` database.
Build the JAR first if not already done:

```bash
cd backend/http_server && sbt assembly && cd -
```

### Verbose mode — see tool calls

In verbose mode, every tool the agent calls is printed in yellow between your message
and the assistant's response, along with per-turn token stats.

```bash
python client/chatbot/run.py --person-id <your-uuid> --verbose
```

### No colors

```bash
python client/chatbot/run.py --person-id <your-uuid> --no-color
```

### claude-p backend (no AWS credentials needed)

Slower (~30 s/turn) because Claude generates explanation text alongside tool calls,
but works without Bedrock credentials.

```bash
python client/chatbot/run.py --person-id <your-uuid> --backend claude-p
```

### Point at a production database

```bash
CHATBOT_DB=myassistant python client/chatbot/run.py --person-id <your-uuid>
```

### Point at a manually started http_server

```bash
# Terminal 1
cd backend/http_server
DB_URL="jdbc:postgresql://localhost:5432/myassistant" sbt run

# Terminal 2
export CHATBOT_HTTP_URL=http://localhost:8080
export CHATBOT_AUTH_TOKEN=dev-token-change-me-in-production
export BEDROCK_API_KEY=ABSKYmVkcm9...
python client/chatbot/run.py --person-id <your-uuid>
```

---

## AWS Bedrock Credentials

Same credentials as the test harness. Two options:

### Option 1: Long-lived API key (recommended)

```bash
# Fetch from Secrets Manager (requires auth to AWS account 654654608322):
aws secretsmanager get-secret-value \
  --secret-id "arn:aws:secretsmanager:us-west-2:654654608322:secret:bedrock/AWS2942/STG/api-key-3LqIv7" \
  --region us-west-2 \
  --query 'SecretString' \
  --output text

export BEDROCK_API_KEY=ABSKYmVkcm9...   # valid 365 days
```

### Option 2: Temporary AWS credentials

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_SESSION_TOKEN=...
```

---

## Environment Variables

| Variable | Default | Notes |
|---|---|---|
| `CHATBOT_PERSON_ID` | — | Alternative to `--person-id` flag |
| `CHATBOT_HTTP_URL` | *(not set → auto-start)* | Point at a running http_server to skip auto-start |
| `CHATBOT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Auth token for http_server |
| `CHATBOT_DB` | `myassistanttest` | Database used during auto-start |
| `BEDROCK_API_KEY` | — | Long-lived Bearer token for Bedrock |
| `AWS_REGION` | `us-west-2` | Bedrock region |
| `AWS_ACCESS_KEY_ID` | — | SigV4 fallback if `BEDROCK_API_KEY` not set |
| `AWS_SECRET_ACCESS_KEY` | — | SigV4 fallback |
| `AWS_SESSION_TOKEN` | — | SigV4 fallback |

---

## CLI Flags

| Flag | Default | Notes |
|---|---|---|
| `--person-id UUID` | — | Required (or `CHATBOT_PERSON_ID` env var) |
| `--verbose` / `-v` | off | Show tool calls (yellow) and token stats (gray) per turn |
| `--no-color` | off | Disable ANSI color output |
| `--backend bedrock\|claude-p` | `bedrock` | Model backend |
| `--model MODEL_ID` | *(backend default)* | Override Claude model |

---

## What It Looks Like

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Personal Assistant  —  Ravi Aggarwal
  Type your message and press Enter. Ctrl-C or Ctrl-D to quit.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

You: I have a dentist appointment on Friday at 2pm