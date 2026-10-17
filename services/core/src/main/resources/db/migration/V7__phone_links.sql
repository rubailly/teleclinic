-- Identity for feature phones (Section 4.3, USSD identity): a phone number is linked once to an IremboAccount
-- subject, a PIN unlocks it, and each USSD session gets a short-lived token with a narrow scope.
CREATE SCHEMA IF NOT EXISTS identity;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE identity.phone_link (
    msisdn           text PRIMARY KEY,               -- E.164, for example +250788000001
    subject          text NOT NULL,                  -- IremboAccount subject
    pin_hash         text NOT NULL,                  -- bcrypt
    failed_attempts  integer NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    linked_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.channel_token (
    token       text PRIMARY KEY,
    subject     text NOT NULL,
    msisdn      text NOT NULL,
    scope       text NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX channel_token_expiry_idx ON identity.channel_token (expires_at);
