# Triage cases past their review deadline

**Severity:** warning (clinical safety). **Alert:** `triage-review-overdue`. **Owner:** clinical supervisor on duty, with platform on-call.

## What it means
At least one triage case passed its review deadline without a clinician's decision. The rules urgency stays in force and the patient has already been given the rules advice, but nobody has confirmed or overridden it. Overdue cases move to UNREVIEWED_TIMEOUT for the supervisor.

## Check first
1. Clinician queue: http://localhost:9080/clinician/ (overdue cases are at the top, with the deadline shown).
2. Are clinicians signed in and working? A queue that grows while decisions stay at zero points to staffing, not software.
3. Dashboard, Clinical safety row: "Triage case changes". FINAL decisions still arriving means the system works and the queue is simply long.

## What to do
- Supervisor: take the oldest EMERGENCY and URGENT cases first; call the patient back on the number in the case.
- If no decision can be saved (errors in the clinician queue), treat it as an outage: check the "Database has no writable leader" alert and the core's health.
- If the model is switched off (kill switch), cases still need review; nothing changes for the clinician except the missing AI suggestion.

## After
- Note how many cases were late and by how long. Repeated alerts mean the deadlines or the staffing need review with the clinical lead.
