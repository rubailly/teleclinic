// Step 1: Where to start
//
// The cursor is the start of the last poll, minus an overlap; what comes back twice is dropped by its content hash.
// This poll's start time is taken now, before reading OpenMRS, so nothing changed during the poll is missed.

fn(async state => {
  const headers = { 'X-Sync-Key': state.configuration.syncKey };
  const { data } = await get('internal/sync/inbound/since', { headers })(state);
  return { since: data.since, polledAt: new Date().toISOString() };
});
