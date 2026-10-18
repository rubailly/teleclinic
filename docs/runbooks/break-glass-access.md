# Break-glass access to a patient record

**Severity:** critical (review, not outage). **Alert:** `break-glass-access`. **Owner:** clinical supervisor on duty; privacy officer informed.

## What it means
A clinician read a patient's record with purpose BREAK_GLASS. That skips the consent check, which is allowed for emergencies (an unconscious patient, a guardian who cannot be reached). The read itself is legitimate until shown otherwise; what the design requires is that it never goes unnoticed and that a supervisor reviews it within 24 hours.

## Check first
1. Audit explorer, http://localhost:9080/audit/ (auditor sign-in): filter on the patient, find entries with purpose BREAK_GLASS. Each shows the clinician, the time and the reason they typed.
2. The patient's own report shows the same entry, with the reason ("Who accessed my record" in the patient app).
3. Was there a consultation for this patient around that time? A break-glass read with no clinical context is the case to look at closely.

## What to do
- **Reason and context agree** (emergency consultation, documented): record the review as justified in the review log.
- **Reason is thin or the context is missing:** ask the clinician the same day; escalate to the privacy officer if it is not explained.
- **Several break-glass reads by one person in a short time:** treat as a possible misuse; suspend the account pending review.

## After
- Keep the review outcome with the audit entry reference (its sequence number).
- Monthly: count break-glass reads per clinician and per facility; a rising trend means consent is not being captured at booking.
