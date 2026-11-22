FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
RUN install -d -o 10001 -g 10001 /app
WORKDIR /app
COPY --chmod=0444 ai.tar.gz /tmp/ai.tar.gz
USER 10001:10001
RUN tar -xzf /tmp/ai.tar.gz --no-same-owner --no-same-permissions \
    && python -m venv .venv \
    && .venv/bin/python -m pip install --disable-pip-version-check --no-cache-dir --no-index --find-links wheels -r requirements.txt \
    && rm -rf wheels
ENV PYTHONUNBUFFERED=1 PATH=/app/.venv/bin:$PATH
CMD ["python", "-m", "uvicorn", "app.main:app_factory", "--factory", "--host", "127.0.0.1", "--port", "9344"]
