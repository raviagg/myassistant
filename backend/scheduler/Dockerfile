# backend/scheduler/Dockerfile
FROM python:3.11-slim

WORKDIR /app
COPY scheduler/pyproject.toml .
RUN pip install --no-cache-dir .

COPY scheduler/ .
CMD ["python", "main.py"]
