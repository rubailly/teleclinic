// Step 2: Write to OpenMRS
//
// Conditional create, so a retry never duplicates anything: a patient is found by the TeleClinic ID on the record
// (unique in OpenMRS), else by UPI or national ID (then TeleClinic adopts that record and writes its ID onto it), else
// created. An encounter is written only if none of this patient's TeleClinic encounters carries the consultation id.
// If OpenMRS is unreachable, the run stops at the first item: the rest stay queued, in order.

const META = {
  teleclinicIdType: 'eb3b4bbc-d4f9-5ad8-90d3-e03062c5f39f',
  nationalIdType: '8cb8a5b6-e6ee-5ed1-8832-4a4730722d19',
  upiType: '466c8fe2-e4e8-56f1-bbdc-bdab1c3d428d',
  encounterType: 'b61c9e8f-ec18-5bdb-9f89-d97cd75f30e2',
  location: '289c0c8e-fc33-567b-a3ce-9d2d997769f3',
  urgencyConcept: '30a61813-fdb2-5f9a-adea-bc01613ef5ea',
  decisionConcept: '68c0144f-0168-5b35-9520-9e519add6b61',
  referenceConcept: 'd2d722a6-0ea7-51be-ab7b-8f0c673e0093',
  phoneAttribute: '14d4f066-15f5-102d-96e4-000c29c2a5d7',
};
const TYPE_OF = { teleclinicId: META.teleclinicIdType, nationalId: META.nationalIdType, upi: META.upiType };

const unreachable = e => /ECONNREFUSED|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|ECONNRESET|socket hang up|50[234]/.test(String(e?.message || e));

fn(async state => {
  const rest = async (method, path, body) => {
    const p = `ws/rest/v1/${path}`;
    const call = method === 'GET' ? get(p) : method === 'DELETE' ? del(p) : post(p, body);
    return (await call(state)).data;
  };
  const fhirPatient = async uuid => (await get(`ws/fhir2/R4/Patient/${uuid}`, { headers: { Accept: 'application/fhir+json' } })(state)).data;
  const findBy = async identifier => {
    if (!identifier) return null;
    const { results } = await rest('GET', `patient?identifier=${encodeURIComponent(identifier)}&v=custom:(uuid)`);
    return results[0]?.uuid || null;
  };
  const identifiersOf = async uuid => (await rest('GET', `patient/${uuid}/identifier?v=custom:(uuid,identifier,identifierType:(uuid))`)).results;
  const addIdentifier = async (uuid, key, value) => {
    const existing = await identifiersOf(uuid);
    if (existing.some(i => i.identifier === value && i.identifierType.uuid === TYPE_OF[key])) return false;
    await rest('POST', `patient/${uuid}/identifier`, { identifier: value, identifierType: TYPE_OF[key], location: META.location });
    return true;
  };

  const writeEncounter = async item => {
    const { patient: p, encounter: e } = item.payload;
    let uuid = await findBy(p.teleclinicId);
    let outcome = 'EXISTED';
    if (!uuid) {
      const adopted = (await findBy(p.upi)) || (await findBy(p.nationalId));
      if (adopted) {
        uuid = adopted;
        outcome = 'ADOPTED';
        await addIdentifier(uuid, 'teleclinicId', p.teleclinicId);
      }
    }
    if (!uuid) {
      const identifiers = [{ identifier: p.teleclinicId, identifierType: META.teleclinicIdType, preferred: true }];
      if (p.upi) identifiers.push({ identifier: p.upi, identifierType: META.upiType });
      if (p.nationalId) identifiers.push({ identifier: p.nationalId, identifierType: META.nationalIdType });
      const created = await rest('POST', 'patient', {
        person: {
          names: [{ givenName: p.givenName, familyName: p.familyName }],
          gender: p.gender,
          birthdate: p.birthDate,
          attributes: p.phone ? [{ attributeType: META.phoneAttribute, value: p.phone }] : [],
        },
        identifiers,
      });
      uuid = created.uuid;
      outcome = 'CREATED';
    }
    const encounters = (await rest('GET',
      `encounter?patient=${uuid}&encounterType=${META.encounterType}&v=custom:(uuid,obs:(value,concept:(uuid)))`)).results;
    let encounter = encounters.find(enc => enc.obs.some(o => o.concept.uuid === META.referenceConcept && o.value === e.consultationId));
    if (!encounter) {
      encounter = await rest('POST', 'encounter', {
        patient: uuid,
        encounterType: META.encounterType,
        location: META.location,
        encounterDatetime: e.datetime.replace(/\.\d+Z$|Z$/, '.000+0000'),
        obs: [
          { concept: META.urgencyConcept, value: e.urgency },
          { concept: META.decisionConcept, value: e.decision },
          { concept: META.referenceConcept, value: e.consultationId },
        ],
      });
    }
    return { outcome, emrPatient: await fhirPatient(uuid), emrEncounterUuid: encounter.uuid };
  };

  const changeIdentifiers = async (item, add) => {
    const uuid = item.payload.emrPatientUuid;
    for (const [key, value] of Object.entries(item.payload.identifiers)) {
      if (add) {
        await addIdentifier(uuid, key, value);
      } else {
        const match = (await identifiersOf(uuid)).find(i => i.identifier === value && i.identifierType.uuid === TYPE_OF[key]);
        // Purged, not voided: the TeleClinic ID is unique and must be free for a later, correct link
        if (match) await rest('DELETE', `patient/${uuid}/identifier/${match.uuid}?purge=true`);
      }
    }
    return { outcome: 'UPDATED', emrPatient: await fhirPatient(uuid), emrEncounterUuid: null };
  };

  const results = [];
  for (const item of state.items) {
    try {
      const r = item.kind === 'ENCOUNTER' ? await writeEncounter(item)
        : await changeIdentifiers(item, item.kind === 'ADD_IDENTIFIER');
      results.push({ id: item.id, ok: true, ...r });
      console.log(`${item.kind} ${item.reference}: ${r.outcome} (OpenMRS patient ${r.emrPatient.id})`);
    } catch (e) {
      const error = String(e?.message || e).slice(0, 400);
      results.push({ id: item.id, ok: false, error });
      console.log(`${item.kind} ${item.reference}: failed: ${error}`);
      if (unreachable(e)) {
        console.log('OpenMRS unreachable: stopping here; the remaining writes stay queued in order');
        break;
      }
    }
  }
  return { ...state, results, items: undefined };
});
