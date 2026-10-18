# Database has no writable leader

**Severity:** critical. **Alert:** `database-no-writable-leader`. **Owner:** platform on-call.

## What it means
pgproxy has no PostgreSQL primary behind it, so the core cannot write. Bookings, payments and triage decisions fail; reads that need the database fail too. USSD sessions answer "the service is busy".

Patroni promotes a replica on its own when the leader is lost. In the drills this takes 16 to 23 seconds. The alert fires within 10 seconds and usually resolves without action. Act if it lasts more than 60 seconds.

## Check first
1. Who holds the leader key, and what each node reports:
   ```
   docker exec teleclinic-pg-1-1 curl -s localhost:8008/cluster
   ```
   Try `pg-2` or `pg-3` if `pg-1` does not answer.
2. etcd quorum (Patroni can only elect a leader with 2 of 3 members):
   ```
   docker exec teleclinic-etcd-1-1 etcdctl --endpoints=etcd-1:2379,etcd-2:2379,etcd-3:2379 endpoint health
   ```
3. pgproxy's view: http://localhost:9405/stats (backend `primary`).
4. Ops cockpit, Nodes panel: a node marked CUT OFF or PG STOPPED.

## What to do
- **A node is down or cut off and the other two agree on a leader:** wait; pgproxy follows the new leader within 2 seconds. Then bring the old node back (`docker start teleclinic-pg-N-1`, or reconnect it to the network). It rejoins as a replica, rewinding if needed.
- **No quorum in etcd (fewer than 2 healthy members):** restore etcd first; Patroni will not elect a leader without it, by design, to prevent two leaders.
- **A leader exists but pgproxy shows none:** check pgproxy health checks (`check port 8008`); restart pgproxy only if it is stuck.
- Do **not** promote a node by hand while etcd is unhealthy. That is how split-brain happens.

## After
- Confirm no acknowledged write was lost: `make scenario-kill-db-leader` shows how the check works; the reconciliation panel on the cockpit compares payments with IremboPay's settlement report.
- Record the start, the end and the cause in the incident log.
