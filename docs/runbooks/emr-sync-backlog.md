# Writes waiting for the facility EMR

**Severity:** warning. **Alert:** `emr-sync-backlog`. **Owner:** integration on-call; the facility's EMR support if it is their side.

## What it means
A write from TeleClinic to the facility EMR (an encounter after a clinician's decision, or an identifier after a link) has waited more than 2 minutes. Clinicians are not blocked: TeleClinic keeps working and every write stays in the queue, retried with exponential backoff (5 s, 10 s, 20 s ... up to 5 minutes). After 10 failed attempts a write becomes a dead letter.

## Check first
1. Ops cockpit, Facility EMR panel: is OpenMRS up? Is Lightning up? How many writes wait, and what is the last error?
2. Lightning, http://localhost:9480: the "TeleClinic to OpenMRS" workflow's latest runs and their logs.
3. OpenMRS itself: `curl -s -u admin:<password> http://localhost:9880/openmrs/ws/rest/v1/session`
4. The queue: `SELECT kind, status, attempts, last_error FROM sync.outbound WHERE status <> 'DONE' ORDER BY created_at;`

## What to do
- **EMR down or unreachable:** nothing to do in TeleClinic; when it is back, the queue drains in order on its own, and conditional create means nothing is duplicated.
- **EMR rejects a write** (the last error is a 4xx with a validation message): fix the cause (missing metadata, a changed identifier type), then replay.
- **Dead letters:** after fixing the cause, replay them from the ops cockpit (or `POST /internal/sync/outbound/replay` with the sync key). They go back in their original order.
- Never delete rows from `sync.outbound`.

## After
- Check the backlog reached zero and the dead-letter count is zero.
- If the EMR was down for a long time, tell the facility which consultations arrived late.
