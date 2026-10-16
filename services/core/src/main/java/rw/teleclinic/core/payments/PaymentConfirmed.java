package rw.teleclinic.core.payments;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * IremboPay confirmed the payment server to server. Published inside the same transaction that records it,
 * so the consultation moves to PAID atomically with the payment update.
 */
public record PaymentConfirmed(UUID consultationId, String providerPaymentId, BigDecimal amount, String currency,
                               String confirmedBy) {
}
