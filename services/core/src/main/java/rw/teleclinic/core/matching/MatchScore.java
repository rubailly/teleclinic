package rw.teleclinic.core.matching;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import rw.teleclinic.core.patientindex.Patient;

/**
 * Scores one pair: log-likelihood style weights per field, a hard block for different national IDs, and the count of
 * strong identifiers that agree (national ID, UPI, and an exact name with an exact birth date).
 */
record MatchScore(double score, Band band, int strongAgreements, List<String> agreeing, List<String> conflicting) {

    enum Band { AUTO, REVIEW, NO_MATCH, BLOCKED }

    static final double AUTO_THRESHOLD = 12;
    static final double REVIEW_THRESHOLD = 4;

    static MatchScore of(Patient tc, EmrPatient emr) {
        List<String> agree = new ArrayList<>();
        List<String> conflict = new ArrayList<>();
        double score = 0;
        int strong = 0;

        if (present(tc.nationalId()) && present(emr.nationalId())) {
            if (tc.nationalId().equals(emr.nationalId())) {
                score += 10;
                strong++;
                agree.add("national ID");
            } else {
                conflict.add("national ID (different: never linked)");
                return new MatchScore(score, Band.BLOCKED, 0, agree, conflict);
            }
        }
        if (present(tc.upi()) && present(emr.upi())) {
            if (tc.upi().equals(emr.upi())) {
                score += 8;
                strong++;
                agree.add("UPI");
            } else {
                score -= 6;
                conflict.add("UPI");
            }
        }

        double name = nameSimilarity(tc.givenName() + " " + tc.familyName(), emr.displayName());
        boolean exactName = name >= 0.999;
        if (name >= 0.95) {
            score += 4;
            agree.add(exactName ? "name" : "name (spelling differs slightly)");
        } else if (name >= 0.85) {
            score += 2;
            agree.add("name (similar)");
        } else {
            score -= 3;
            conflict.add("name");
        }

        boolean exactBirth = false;
        LocalDate a = tc.birthDate();
        LocalDate b = emr.birthDate();
        if (a != null && b != null) {
            if (a.equals(b)) {
                score += 4;
                exactBirth = true;
                agree.add("birth date");
            } else if (swapped(a, b)) {
                score += 2;
                agree.add("birth date (day and month swapped)");
            } else if (a.getYear() == b.getYear()) {
                score += 0.5;
                conflict.add("birth date (same year)");
            } else {
                score -= 3;
                conflict.add("birth date");
            }
        }
        if (exactName && exactBirth) {
            strong++;   // a demographic key: exact normalised name and exact birth date
        }

        if (present(tc.sex()) && present(emr.sex())) {
            if (tc.sex().equalsIgnoreCase(emr.sex())) {
                score += 1;
                agree.add("sex");
            } else {
                score -= 4;
                conflict.add("sex");
            }
        }
        if (present(tc.phone()) && present(emr.phone())) {
            if (digits(tc.phone()).equals(digits(emr.phone()))) {
                score += 2;
                agree.add("phone");
            } else {
                conflict.add("phone (numbers change; not counted)");
            }
        }

        Band band = strong >= 2 && score >= AUTO_THRESHOLD ? Band.AUTO
                : score >= REVIEW_THRESHOLD ? Band.REVIEW : Band.NO_MATCH;
        return new MatchScore(Math.round(score * 10) / 10.0, band, strong, agree, conflict);
    }

    /** Cheap pre-filter: share a name token, an identifier, or a birth date (as is or swapped). */
    static boolean block(Patient tc, EmrPatient emr) {
        Set<String> a = tokens(tc.givenName() + " " + tc.familyName());
        Set<String> b = tokens(emr.displayName());
        a.retainAll(b);
        return !a.isEmpty()
                || (present(tc.nationalId()) && tc.nationalId().equals(emr.nationalId()))
                || (present(tc.upi()) && tc.upi().equals(emr.upi()))
                || (tc.birthDate() != null && emr.birthDate() != null
                    && (tc.birthDate().equals(emr.birthDate()) || swapped(tc.birthDate(), emr.birthDate())));
    }

    static boolean swapped(LocalDate a, LocalDate b) {
        return a.getYear() == b.getYear() && a.getMonthValue() == b.getDayOfMonth() && a.getDayOfMonth() == b.getMonthValue()
                && !a.equals(b);
    }

    /**
     * Token by token: each name part of the shorter name against its best match in the other, averaged. Order does not
     * matter ("Uwimana Marie" and "Marie Uwimana"), a typo in one part costs little, accents and case are ignored.
     */
    static double nameSimilarity(String a, String b) {
        Set<String> x = tokens(a);
        Set<String> y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> shorter = x.size() <= y.size() ? x : y;
        Set<String> longer = shorter == x ? y : x;
        return shorter.stream().mapToDouble(t -> longer.stream().mapToDouble(u -> jaroWinkler(t, u)).max().orElse(0))
                .average().orElse(0);
    }

    static Set<String> tokens(String name) {
        String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ");
        return Arrays.stream(plain.split("\\s+")).filter(t -> !t.isBlank()).collect(Collectors.toCollection(TreeSet::new));
    }

    static double jaroWinkler(String s, String t) {
        if (s.equals(t)) {
            return 1;
        }
        if (s.isEmpty() || t.isEmpty()) {
            return 0;
        }
        int range = Math.max(0, Math.max(s.length(), t.length()) / 2 - 1);
        boolean[] sm = new boolean[s.length()];
        boolean[] tm = new boolean[t.length()];
        int matches = 0;
        for (int i = 0; i < s.length(); i++) {
            for (int j = Math.max(0, i - range); j < Math.min(t.length(), i + range + 1); j++) {
                if (!tm[j] && s.charAt(i) == t.charAt(j)) {
                    sm[i] = tm[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        for (int i = 0, k = 0; i < s.length(); i++) {
            if (sm[i]) {
                while (!tm[k]) {
                    k++;
                }
                if (s.charAt(i) != t.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / s.length() + m / t.length() + (m - transpositions / 2.0) / m) / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(s.length(), t.length())) && s.charAt(prefix) == t.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private static String digits(String phone) {
        String d = phone.replaceAll("\\D", "");
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }
}
