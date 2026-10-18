# Docker CLI plus the tools the scenario scripts use (bash, curl, GNU date for millisecond timestamps, python3)
FROM docker:27.3.1-cli
RUN apk add --no-cache bash curl coreutils python3 py3-psycopg libpq
WORKDIR /app
COPY runner.py ./
COPY static ./static
ENV PYTHONUNBUFFERED=1
CMD ["python3", "/app/runner.py"]
