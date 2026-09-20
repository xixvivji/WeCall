FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.10.12 /uv /usr/local/bin/uv
WORKDIR /app
COPY ai/pyproject.toml ai/uv.lock ./
RUN uv sync --locked --no-dev
COPY ai/app ./app
CMD [".venv/bin/uvicorn","app.main:app","--host","127.0.0.1","--port","8000","--workers","1","--no-access-log"]
