// Step 3: Deliver to TeleClinic
//
// One change per call (POST /sync/inbound), as a FHIR Patient whatever the EMR. The core answers 200 for an echo of its own write (dropped) and 202 for a
// change taken in: applied, or handed to identity matching. Then the cursor moves to this poll's start.

fn(async state => {
  const headers = { 'X-Sync-Key': state.configuration.syncKey };
  const outcomes = {};
  for (const patient of state.patients) {
    const key = `${patient.id}:${patient.meta?.lastUpdated || ''}:${Date.now()}`;
    const { data } = await post('sync/inbound',
      { externalSystem: SYSTEM, resource: patient, origin: SYSTEM, sourceVersion: patient.meta?.lastUpdated },
      { headers: { ...headers, 'Idempotency-Key': key } })(state);
    outcomes[data.outcome] = (outcomes[data.outcome] || 0) + 1;
    console.log(`${patient.id}: ${data.outcome}${data.teleclinicPatient ? ` (${data.teleclinicPatient})` : ''}`);
  }
  await post('internal/sync/inbound/cursor', { polledAt: state.polledAt }, { headers, query: { system: SYSTEM } })(state);
  console.log(`outcomes: ${JSON.stringify(outcomes)}; cursor ${state.polledAt}`);
  return { outcomes };
});
