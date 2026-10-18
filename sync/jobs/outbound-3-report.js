// Step 3: Report results to TeleClinic
//
// Done: the core records the EMR's ids, version and content hash in the sync ledger. Failed: the core schedules a retry
// with exponential backoff, and turns the item into a dead letter after its retry limit.

fn(async state => {
  const headers = { 'X-Sync-Key': state.configuration.syncKey };
  let done = 0;
  let failed = 0;
  for (const r of state.results || []) {
    if (r.ok) {
      await post(`internal/sync/outbound/${r.id}/done`,
        { outcome: r.outcome, emrPatient: r.emrPatient, emrEncounterUuid: r.emrEncounterUuid }, { headers })(state);
      done++;
    } else {
      await post(`internal/sync/outbound/${r.id}/failed`, { error: r.error }, { headers })(state);
      failed++;
    }
  }
  console.log(`reported ${done} done, ${failed} failed`);
  return { done, failed };
});
