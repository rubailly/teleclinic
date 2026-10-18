package rw.teleclinic.core.emrsync;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import rw.teleclinic.core.matching.EmrPatient;

/** A FHIR Patient from the EMR: its canonical content hash (echo detection) and the fields TeleClinic uses. */
final class PatientContent {

    static final String TELECLINIC_ID = "TeleClinic ID";
    static final String NATIONAL_ID = "National ID (NIDA)";
    static final String UPI = "UPI";

    private PatientContent() {
    }

    /** Same content gives the same hash, whatever the order of names, numbers or identifiers in the payload. */
    static String hash(JsonNode patient) {
        JsonNode name = patient.path("name").path(0);
        List<String> given = new ArrayList<>();
        name.path("given").forEach(g -> given.add(g.asText()));
        List<String> phones = new ArrayList<>();
        patient.path("telecom").forEach(t -> phones.add(t.path("value").asText()));
        phones.sort(null);
        TreeMap<String, String> ids = new TreeMap<>();
        patient.path("identifier").forEach(i -> ids.put(i.path("type").path("text").asText() + "#" + i.path("value").asText(), ""));
        String canonical = String.join("|", String.join(" ", given), name.path("family").asText(),
                patient.path("gender").asText(), patient.path("birthDate").asText(), String.join(",", phones),
                String.join(",", ids.keySet()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String identifier(JsonNode patient, String typeText) {
        for (JsonNode i : patient.path("identifier")) {
            if (typeText.equals(i.path("type").path("text").asText())) {
                return i.path("value").asText();
            }
        }
        return null;
    }

    static String phone(JsonNode patient) {
        for (JsonNode t : patient.path("telecom")) {
            if (!t.path("value").asText().isBlank()) {
                return t.path("value").asText();
            }
        }
        return null;
    }

    static EmrPatient toEmrPatient(JsonNode p) {
        JsonNode name = p.path("name").path(0);
        String given = name.path("given").isArray()
                ? java.util.stream.StreamSupport.stream(name.path("given").spliterator(), false).map(JsonNode::asText)
                        .collect(Collectors.joining(" "))
                : null;
        String gender = p.path("gender").asText();
        String sex = "female".equals(gender) ? "F" : "male".equals(gender) ? "M" : null;
        String birth = p.path("birthDate").asText(null);
        String other = null;
        for (JsonNode i : p.path("identifier")) {
            String type = i.path("type").path("text").asText();
            if (!List.of(TELECLINIC_ID, NATIONAL_ID, UPI).contains(type)) {
                other = i.path("value").asText();
            }
        }
        return new EmrPatient(p.path("id").asText(), given, name.path("family").asText(null), sex,
                birth == null ? null : LocalDate.parse(birth.substring(0, 10)), phone(p), identifier(p, NATIONAL_ID),
                identifier(p, UPI), other);
    }
}
