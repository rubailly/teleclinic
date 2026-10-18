// Step 2: Changed patients in eBuzima
//
// eBuzima calls the gateway's webhook when a patient record is saved (a Frappe document webhook), and this workflow
// also runs every minute as a safety net. Either way it reads every patient modified since the cursor, through the
// REST API, and maps each one to a FHIR Patient: the core takes FHIR from every EMR. Frappe stores local time.

const TZ = '+02:00';   // eBuzima's system time zone, Africa/Kigali (no daylight saving)
const PATIENT_FIELDS = ['name', 'first_name', 'last_name', 'sex', 'dob', 'mobile', 'uid', 'custom_upi', 'custom_teleclinic_id', 'modified'];

// The same mapping as ebuzima-outbound-2-write.js
const toFhir = d => ({
  resourceType: 'Patient',
  id: d.name,
  meta: { lastUpdated: `${d.modified.replace(' ', 'T')}${TZ}` },
  name: [{ given: [d.first_name].filter(Boolean), family: d.last_name || '' }],
  gender: { Female: 'female', Male: 'male' }[d.sex] || 'unknown',
  birthDate: d.dob || undefined,
  telecom: d.mobile ? [{ system: 'phone', value: d.mobile }] : [],
  identifier: [
    ['eBuzima ID', d.name], ['TeleClinic ID', d.custom_teleclinic_id], ['National ID (NIDA)', d.uid], ['UPI', d.custom_upi],
  ].filter(([, v]) => v).map(([text, value]) => ({ type: { text }, value })),
});

fn(async state => {
  const headers = {
    Authorization: `token ${state.configuration.apiKey}:${state.configuration.apiSecret}`,
    Accept: 'application/json',
  };
  const local = new Date(new Date(state.since).getTime() + 2 * 3600 * 1000).toISOString().slice(0, 19).replace('T', ' ');
  const { data } = await get('api/resource/Patient', {
    headers,
    query: {
      filters: JSON.stringify([['modified', '>=', local]]),
      fields: JSON.stringify(PATIENT_FIELDS),
      order_by: 'modified asc',
      limit_page_length: 0,
    },
  })(state);
  const patients = data.data.map(toFhir);
  console.log(`${patients.length} patient(s) changed since ${local} (Kigali time)`);
  return { ...state, patients };
});
