# PostgreSQL 16 with Patroni (etcd v3 as the consensus store). pgAudit is installed here and enabled in step 7.
FROM postgres:16.4-bookworm
RUN apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=30 update \
  && apt-get -o Acquire::Retries=5 install -y --no-install-recommends python3 python3-venv postgresql-16-pgaudit curl \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/patroni \
 && /opt/patroni/bin/pip install --no-cache-dir --default-timeout=60 --retries 5 'patroni[etcd3,psycopg3]==4.0.3'
COPY patroni.yml /etc/patroni.yml
COPY post-bootstrap.sh /usr/local/bin/post-bootstrap.sh
ENV PATH=/opt/patroni/bin:$PATH
USER postgres
EXPOSE 5432 8008
ENTRYPOINT ["patroni", "/etc/patroni.yml"]
