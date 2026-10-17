-- Synthetic phone links for the demo. The demo PIN is 1234 for both; real PINs are set by the citizen.
INSERT INTO identity.phone_link (msisdn, subject, pin_hash) VALUES
    ('+250788000001', '00000000-0000-4000-a000-000000000001', crypt('1234', gen_salt('bf', 10))),
    ('+250788000002', '00000000-0000-4000-a000-000000000002', crypt('1234', gen_salt('bf', 10)));
