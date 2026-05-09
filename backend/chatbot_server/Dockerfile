# backend/chatbot_server/Dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY chatbot_server/pyproject.toml .
RUN pip install --no-cache-dir .

COPY chatbot_server/ .
COPY mcp_server/ ./mcp_server/

EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
