# USSD steps slower than the budget

**Severity:** warning. **Alert:** `ussd-step-latency`. **Owner:** platform on-call.

## What it means
The 95th percentile time to answer one USSD step is above 1.5 seconds. Mobile networks end a USSD session that answers late, so patients on basic phones lose their place and must dial again.

## Check first
1. Dashboard, "USSD step p95": is it one step (for example the booking step, which runs the triage rules and writes to the database) or all steps?
2. "API p95 latency" and "Writable database leader": slow steps usually come from the core or the database, not the gateway.
3. Gateway health: `curl -s localhost:9380/health` and `docker logs --since 10m teleclinic-ussd-gateway-1`.
4. Valkey (USSD session state): `docker exec teleclinic-valkey-1 valkey-cli ping`.

## What to do
- **Core or database slow:** follow those alerts first; the gateway only forwards.
- **Gateway CPU bound:** add an instance; it keeps no state of its own (sessions are in Valkey).
- **Only the PIN step is slow:** the PIN check is bcrypt by design (about 100 ms); a sudden rise there points to CPU pressure on the core.

## After
- Compare with the simulator's latency chart (http://localhost:9380) during a test session.
