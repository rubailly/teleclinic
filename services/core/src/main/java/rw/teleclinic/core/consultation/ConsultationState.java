package rw.teleclinic.core.consultation;

/** Saga states of a consultation (design document, Figure 7). */
public enum ConsultationState {
    DRAFT, TRIAGED, PENDING_PAYMENT, PAID, QUEUED, IN_CONSULTATION, COMPLETED, SYNC_PENDING, SYNCED,
    // failure and compensation paths
    EXPIRED, REFUND_PENDING, REFUNDED, SYNC_FAILED
}
