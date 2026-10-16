-- Payments module: payments at IremboPay and every webhook received.
CREATE TABLE payments.payment (
    id                   uuid PRIMARY KEY,
    consultation_id      uuid NOT NULL UNIQUE,     -- the internal reference sent to IremboPay
    provider_payment_id  text NOT NULL UNIQUE,
    amount               numeric(12, 2) NOT NULL,
    currency             text NOT NULL,
    status               text NOT NULL,
    checkout_url         text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

-- Deduplication by event id: the primary key makes a second delivery a no-op
CREATE TABLE payments.webhook_event (
    event_id             text PRIMARY KEY,
    provider_payment_id  text,
    event_type           text,
    outcome              text NOT NULL,
    received_at          timestamptz NOT NULL DEFAULT now()
);
