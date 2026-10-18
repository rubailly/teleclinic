// Step 1: Take due writes from TeleClinic
//
// The core keeps the outbound queue, per EMR (SYSTEM is set for each workflow from sync/project.yaml). Items waiting out a retry backoff are not handed out, so a facility EMR that is
// offline is retried with backoff instead of on every run. Oldest first.

fn(async state => {
  const headers = { 'X-Sync-Key': state.configuration.syncKey };
  const { data } = await get('internal/sync/outbound/due', { headers, query: { system: SYSTEM, limit: 20 } })(state);
  console.log(`${data.items.length} write(s) due`);
  return { ...state, items: data.items };
});
