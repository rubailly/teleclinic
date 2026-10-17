FROM python:3.11.10-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --default-timeout=120 --retries 8 -r requirements.txt
COPY app.py recognizers.py ./
COPY tests ./tests
# The PII suite runs at build time: an image whose recognizers miss synthetic PII is never built
RUN VAULT_KEY=build-test VAULT_PATH=/tmp/vault-test.db python -m pytest -q tests
RUN useradd -r app && mkdir -p /data && chown app /data
USER app
EXPOSE 8200
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200", "--no-access-log"]
