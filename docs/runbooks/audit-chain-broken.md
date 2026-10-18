# Audit chain broken

**Severity:** critical, security incident. **Alert:** `audit-chain-broken`. **Owner:** security team; not the database or application administrators (separation of duties).

## What it means
An audit entry no longer matches its hash, an entry is missing, or the trail ends before its recorded head. The application cannot change audit rows (the table is append-only and the trigger refuses updates and deletes), so this means someone changed the database directly, usually by switching the trigger off first.

## Check first
1. Audit explorer, http://localhost:9080/audit/: press **Verify now**. The banner names the first broken entry and why (content changed, entry missing, end of trail removed). That row is highlighted.
2. pgAudit entries just after the break, source DATABASE: look for `DDL ALTER TABLE` on `audit.event` (the trigger being disabled), then `WRITE UPDATE` or `WRITE DELETE` on `audit.event`. Each names the database role, the node and the client.
3. Who had that role at that time (bastion session recordings in production).

## What to do
- **Do not repair the chain by recomputing hashes.** That would hide the evidence.
- Preserve: export the affected entries and the pgAudit entries around them; in production, compare with the last signed daily checkpoint in the write-once archive to see what the entry said before.
- Revoke or rotate the credentials of the role that made the change; review who else holds superuser.
- Report through the incident process; notify the data protection officer if patient data or its access history was altered.

## After
- Once the original content is known (from the checkpoint archive), restoring it makes the chain verify again; the restore is itself recorded by pgAudit.
- Review why a role able to disable triggers was used outside change control.

## Drill
`make scenario-tamper` (or the cockpit button) rewrites one break-glass entry as a DBA would, shows the break and the pgAudit attribution, then restores it.
