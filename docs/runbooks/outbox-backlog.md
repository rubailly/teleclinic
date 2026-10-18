# Events not reaching Kafka

**Severity:** warning. **Alert:** `outbox-backlog`. **Owner:** platform on-call.

## What it means
The oldest event in the core's outbox has waited more than 60 seconds to be published to Kafka. State changes are safe: they are committed in PostgreSQL together with their events. What is late is everything that consumes the events: the AI triage suggestion, the EMR sync and some notifications.

## Check first
1. Kafka brokers: `docker ps --filter name=kafka` and the Kafka tile in the ops cockpit (in-sync replicas).
2. Topic health: `docker exec teleclinic-kafka-1-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --under-replicated-partitions`
3. Core logs for producer errors: `docker logs --since 10m teleclinic-core-blue-1 | grep -i kafka`

## What to do
- **Two or more brokers down:** with min in-sync replicas 2, writes wait by design. Bring a broker back; the outbox publishes the backlog on its own, in order.
- **Brokers healthy but events stay pending:** restart the core instance that is not live first (blue/green), then the live one; outstanding events are republished on start.
- Do not delete rows from the outbox table.

## After
- Check that the backlog reached zero (dashboard "Outbox to Kafka") and that consumers caught up.
