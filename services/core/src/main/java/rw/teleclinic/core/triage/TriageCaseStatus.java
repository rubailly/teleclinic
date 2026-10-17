package rw.teleclinic.core.triage;

/** Human-in-the-loop states (Figure 15). Clinical action is only possible in FINAL. */
public enum TriageCaseStatus {
    RULES_ONLY, AI_PROPOSED, UNDER_REVIEW, CONFIRMED, OVERRIDDEN, FINAL, UNREVIEWED_TIMEOUT;

    boolean reviewable() {
        return this == RULES_ONLY || this == AI_PROPOSED || this == UNREVIEWED_TIMEOUT || this == UNDER_REVIEW;
    }
}
