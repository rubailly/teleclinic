#!/usr/bin/env bash
# Scene 4a (second part): stop one Kafka broker. With replication factor 3 and min ISR 2,
# producers using acks=all keep writing without loss. Usage: kill-kafka-broker.sh [broker number, default 2]
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/cluster.sh
b=${1:-2}
other=$(( b % 3 + 1 ))
bootstrap=kafka-1:9092,kafka-2:9092,kafka-3:9092
kt() { docker exec "$(c kafka-$other)" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" "$@" 2>/dev/null; }
isr_summary() { kt --describe --topic consultation.paid | awk '/Partition:/{for(i=1;i<=NF;i++) if($i=="Isr:") print $(i+1)}' | awk -F, '{print NF}' | sort | uniq -c | awk '{printf "%s partitions with %s in-sync replicas; ", $1, $2}'; }

step "1. before: $(isr_summary)"
step "2. stop broker kafka-$b"
docker stop "$(c kafka-$b)" >/dev/null
sleep 5
note "$(isr_summary)"

step "3. produce 50 messages with acks=all while one broker is down"
marker="probe-$(date +%s)"
seq 1 50 | sed "s/^/$marker-/" | docker exec -i "$(c kafka-$other)" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$bootstrap" --topic audit.events --producer-property acks=all >/dev/null 2>"/tmp/kp.$$"
errors=$(grep -ci "error\|exception" "/tmp/kp.$$"); rm -f "/tmp/kp.$$"
got=$(docker exec "$(c kafka-$other)" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" \
  --topic audit.events --from-beginning --timeout-ms 8000 2>/dev/null | grep -c "$marker")
note "producer errors: $errors, messages read back: $got of 50"

step "4. start kafka-$b again; it catches up"
docker start "$(c kafka-$b)" >/dev/null
for i in $(seq 1 60); do s=$(isr_summary); [[ $s == *"6 partitions with 3"* ]] && break; sleep 2; done
note "$s"

[ "$errors" = 0 ] && [ "$got" = 50 ] || fail "messages lost or rejected with one broker down"
pass "one broker down: 50 of 50 messages written with acks=all, no errors"
