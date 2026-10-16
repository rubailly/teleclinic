package rw.teleclinic.core.triage;

/** Ordered from least to most urgent, so the safety merge is a plain maximum. */
public enum Urgency {
    ROUTINE, SEMI_URGENT, URGENT, EMERGENCY;

    public static Urgency max(Urgency a, Urgency b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
