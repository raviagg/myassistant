# Kickstart — Chatbot Server

## Prerequisites
- Python 3.11+, Java 21+, PostgreSQL running with myassistant schema
- Bedrock credentials (`BEDROCK_API_KEY` or AWS SigV4 env vars)

## Install
```bash
cd backend/chatbot_server
pip install -e ".[dev]"
```

## Environment variables
| Variable | Default | Purpose |
|---|---|---|
| `CHATBOT_HTTP_URL` | auto-start on 8181 | Point at running http_server |
| `CHATBOT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Bearer token |
| `CHATBOT_DB` | `myassistant` | DB for auto-start |
| `BEDROCK_API_KEY` | — | Long-lived Bedrock Bearer token |
| `AWS_REGION` | `us-west-2` | Bedrock region |
| `CORS_ORIGIN` | `http://localhost:5173` | Allowed frontend origin |

## Run
```bash
cd backend/chatbot_server
uvicorn main:app --reload --port 8000
```

## Test
```bash
cd backend/chatbot_server
pytest tests/ -v
```
