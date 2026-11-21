# Python AI 服务容器：依赖由 CI 预下载为 wheel，容器内离线安装，不依赖外网。
FROM python:3.12-slim

RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY ai/requirements.txt /app/requirements.txt
COPY ai/wheels /app/wheels
RUN pip install --no-cache-dir --no-index --find-links /app/wheels -r /app/requirements.txt

COPY ai/app /app/app
COPY ai/migrations /app/migrations
COPY ai/alembic.ini /app/alembic.ini
COPY ai/pyproject.toml /app/pyproject.toml

ENV PYTHONUNBUFFERED=1
USER 10001:10001
EXPOSE 9344

CMD ["python", "-m", "uvicorn", "app.main:app_factory", "--factory", "--host", "127.0.0.1", "--port", "9344"]
