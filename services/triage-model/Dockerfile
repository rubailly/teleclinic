FROM python:3.11.10-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --default-timeout=120 --retries 8 -r requirements.txt
COPY features.py train.py serve.py ./
# Train on synthetic data, export to ONNX, write the model card. Training fails the build if the
# validation gates (under-triage rate, per-group recall) are not met.
RUN python train.py
RUN useradd -r app && mkdir -p /data && chown app /data
USER app
EXPOSE 8100
CMD ["uvicorn", "serve:app", "--host", "0.0.0.0", "--port", "8100", "--no-access-log"]
