// Step 2: Changed patients in OpenMRS
//
// FHIR search on _lastUpdated, a page at a time. OpenMRS includes a patient whose phone (a person attribute) changed.

function relative(url) {
  const { pathname, search } = new URL(url);
  return `${pathname.replace(/^.*?\/ws\//, 'ws/')}${search}`;
}

fn(async state => {
  const headers = { Accept: 'application/fhir+json' };
  const patients = [];
  let path = 'ws/fhir2/R4/Patient';
  let query = { _lastUpdated: `ge${state.since}`, _count: 100 };
  while (path) {
    const { data } = await get(path, { headers, query })(state);
    (data.entry || []).forEach(e => patients.push(e.resource));
    const next = (data.link || []).find(l => l.relation === 'next');
    path = next ? relative(next.url) : null;
    query = undefined;
  }
  console.log(`${patients.length} patient(s) changed since ${state.since}`);
  return { ...state, patients };
});
