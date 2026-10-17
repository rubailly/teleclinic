FROM python:3.12.7-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --default-timeout=120 --retries 8 -r requirements.txt
COPY gateway.py menus.py ./
COPY static ./static
RUN useradd -r app
USER app
EXPOSE 8300
CMD ["uvicorn", "gateway:app", "--host", "0.0.0.0", "--port", "8300", "--no-access-log"]
