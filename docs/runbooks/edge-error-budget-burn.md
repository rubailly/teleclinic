# Edge error budget burning fast

**Severity:** critical. **Alert:** `edge-error-budget-burn`. **Owner:** platform on-call.

## What it means
The share of requests answered with a 5xx at the public edge is above 14.4 times the 99.95% availability budget, over both the last 5 minutes and the last hour. At this rate a month's budget is gone in about two days. Two windows avoid paging for a short spike.

## Check first
1. Dashboard, "Server errors by release": are the errors coming from one release (a bad deploy) or from both?
2. Was a release in progress? `get weight core/core-blue` and `core/core-green` on the HAProxy runtime API, or the Edge pool in the ops cockpit.
3. "Writable database leader" on the dashboard: a database outage also shows up as 5xx.
4. Core logs: `docker logs --since 10m teleclinic-core-blue-1 | grep -E "ERROR|WARN"` (and `core-green`).

## What to do
- **One release is failing:** move traffic back to the previous colour at once: `make switch-back`. It refuses if the previous colour is not healthy.
- **Both releases fail:** look at their shared dependencies (database, Valkey sessions, IremboPay, Keycloak) before restarting anything.
- **Errors only from the edge itself (503 with no core errors):** no healthy core behind HAProxy; check `docker ps` and the core health checks.

## After
- Note how much of the budget was used. If the month's budget is spent, freeze non-urgent releases until it recovers.
