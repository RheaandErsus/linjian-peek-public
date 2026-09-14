FROM python:3.12-slim

WORKDIR /app
COPY server.py .

ENV PYTHONUNBUFFERED=1 \
    MCP_BIND=0.0.0.0 \
    THINKING_PROMPT_LANGUAGE=zh-CN

CMD ["sh", "-c", "python3 server.py ${PORT:-8787}"]
