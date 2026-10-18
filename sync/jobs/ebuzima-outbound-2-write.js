// Step 2: Write to eBuzima
//
// eBuzima is one ERPNext site shared by every health centre (each one a Company, a tenant). Patients are national;
// the encounter is written in the tenant of the patient's home facility. Conditional create, so a retry never
// duplicates anything: a patient is found by the TeleClinic ID on the record (a unique custom field), else by UPI or
// national ID (then TeleClinic adopts that record and writes its ID onto it), else created. An encounter is written
// only if no encounter carries the consultation id (also unique). Contact phone is the facility's: never overwritten.
// If eBuzima is unreachable, the run stops at the first item: the rest stay queued, in order.

const TZ = '+02:00';   // eBuzima's system time zone, Africa/Kigali (no daylight saving)
const FIELD_OF = { teleclinicId: 'custom_teleclinic_id', nationalId: 'uid', upi: 'custom_upi' };
const PATIENT_FIELDS = ['name', 'first_name', 'last_name', 'sex', 'dob', 'mobile', 'uid', 'custom_upi', 'custom_teleclinic_id', 'modified'];

// The same mapping as ebuzima-inbound-2-fetch.js: the content hash of what we wrote must equal what the poll reads back
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

const kigali = iso => {
  const t = new Date(new Date(iso).getTime() + 2 * 3600 * 1000).toISOString();
  return { date: t.slice(0, 10), time: t.slice(11, 19) };
};

const unreachable = e => /ECONNREFUSED|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|ECONNRESET|socket hang up|50[234]/.test(String(e?.message || e));

fn(async state => {
  const headers = {
    Authorization: `token ${state.configuration.apiKey}:${state.configuration.apiSecret}`,
    Accept: 'application/json',
  };
  const resource = (doctype, name) => `api/resource/${encodeURIComponent(doctype)}${name ? `/${encodeURIComponent(name)}` : ''}`;
  const list = async (doctype, filters, fields = ['name']) => (await get(resource(doctype), {
    headers, query: { filters: JSON.stringify(filters), fields: JSON.stringify(fields), limit_page_length: 0 },
  })(state)).data.data;
  const create = async (doctype, body) => (await post(resource(doctype), body, { headers })(state)).data.data;
  const update = async (doctype, name, body) => (await put(resource(doctype, name), body, { headers })(state)).data.data;
  const findBy = async (field, value) => (value ? (await list('Patient', [[field, '=', value]]))[0]?.name : null) || null;
  const patientNow = async name => toFhir((await list('Patient', [['name', '=', name]], PATIENT_FIELDS))[0]);

  let practitioner;
  const writeEncounter = async item => {
    const { patient: p, encounter: e, facility } = item.payload;
    let name = await findBy('custom_teleclinic_id', p.teleclinicId);
    let outcome = 'EXISTED';
    if (!name) {
      const adopted = (await findBy('custom_upi', p.upi)) || (await findBy('uid', p.nationalId));
      if (adopted) {
        name = adopted;
        outcome = 'ADOPTED';
        await update('Patient', name, { custom_teleclinic_id: p.teleclinicId });
      }
    }
    if (!name) {
      name = (await create('Patient', {
        first_name: p.givenName,
        last_name: p.familyName,
        sex: { F: 'Female', M: 'Male', female: 'Female', male: 'Male' }[p.gender] || 'Prefer not to say',
        dob: p.birthDate,
        mobile: p.phone || undefined,
        uid: p.nationalId || undefined,
        custom_upi: p.upi || undefined,
        custom_teleclinic_id: p.teleclinicId,
      })).name;
      outcome = 'CREATED';
    }
    let encounter = (await list('Patient Encounter', [['custom_teleclinic_consultation', '=', e.consultationId]]))[0];
    if (!encounter) {
      practitioner ||= (await list('Healthcare Practitioner', [['first_name', '=', 'TeleClinic'], ['last_name', '=', 'Teleconsultation']]))[0].name;
      const at = kigali(e.datetime);
      encounter = await create('Patient Encounter', {
        patient: name,
        patient_name: `${p.givenName} ${p.familyName}`,
        company: facility.tenant,
        practitioner,
        medical_department: 'Teleconsultation',
        encounter_date: at.date,
        encounter_time: at.time,
        encounter_comment: e.decision,
        custom_teleclinic_consultation: e.consultationId,
        custom_urgency: e.urgency,
        custom_channel: e.channel,
        docstatus: 1,
      });
    }
    return { outcome, emrPatient: await patientNow(name), emrEncounterUuid: encounter.name };
  };

  const changeIdentifiers = async (item, add) => {
    const name = item.payload.emrPatientUuid;
    const body = {};
    for (const [key, value] of Object.entries(item.payload.identifiers)) {
      if (FIELD_OF[key]) body[FIELD_OF[key]] = add ? value : null;
    }
    await update('Patient', name, body);
    return { outcome: 'UPDATED', emrPatient: await patientNow(name), emrEncounterUuid: null };
  };

  const results = [];
  for (const item of state.items) {
    try {
      const r = item.kind === 'ENCOUNTER' ? await writeEncounter(item)
        : await changeIdentifiers(item, item.kind === 'ADD_IDENTIFIER');
      results.push({ id: item.id, ok: true, ...r });
      console.log(`${item.kind} ${item.reference}: ${r.outcome} (eBuzima patient ${r.emrPatient.id}${r.emrEncounterUuid ? `, encounter ${r.emrEncounterUuid} in ${item.payload.facility?.tenant}` : ''})`);
    } catch (e) {
      const error = String(e?.message || e).slice(0, 400);
      results.push({ id: item.id, ok: false, error });
      console.log(`${item.kind} ${item.reference}: failed: ${error}`);
      if (unreachable(e)) {
        console.log('eBuzima unreachable: stopping here; the remaining writes stay queued in order');
        break;
      }
    }
  }
  return { ...state, results, items: undefined };
});
