# Writes waiting for a facility EMR

**Severity:** warning. **Alert:** `emr-sync-backlog`. **Owner:** integration on-call; the facility's EMR support if it is their side.

## What it means
A write from TeleClinic to an EMR (eBuzima for health centres, or a hospital's OpenMRS: an encounter after a clinician's decision, or an identifier after a link) has waited more than 2 minutes. Clinicians are not blocked: TeleClinic keeps working and every write stays in the queue, retried with exponential backoff (5 s, 10 s, 20 s ... up to 5 minutes). After 10 failed attempts a write becomes a dead letter.

## Check first
1. Grafana or the ops cockpit (Facility EMRs panel): which EMR is waiting (the `emr` label, or the cockpit's per-EMR count)? Are eBuzima, OpenMRS and Lightning up? What is the last error?
2. Lightning, http://localhost:9480: that EMR's "TeleClinic to ..." workflow, its latest runs and their logs.
3. The EMR itself: eBuzima `curl -s http://localhost:9580/api/method/ping`; OpenMRS `curl -s -u admin:<password> http://localhost:9880/openmrs/ws/rest/v1/session`
4. The queue: `SELECT target, kind, status, attempts, last_error FROM sync.outbound WHERE status <> 'DONE' ORDER BY created_at;`

## What to do
- **EMR down or unreachable:** nothing to do in TeleClinic; when it is back, the queue drains in order on its own, and conditional create means nothing is duplicated.
- **EMR rejects a write** (the last error is a 4xx with a validation message): fix the cause (missing metadata, a changed identifier type), then replay that EMR's dead letters.
- **Dead letters:** after fixing the cause, replay them with `POST /internal/sync/outbound/replay?system=<emr>` and the sync key (internal port only, not through the edge). They go back in their original order.
- Never delete rows from `sync.outbound`.

## After
- Check the backlog reached zero and the dead-letter count is zero.
- If the EMR was down for a long time, tell the facilities concerned (for eBuzima, every health centre whose tenant had writes waiting) which consultations arrived late.
