# Diagrams

The 16 figures of the [design document](../docs/TeleClinic_Technical_Design_Document.docx). Each one is here as an SVG
(sharp at any zoom) and as a PNG at twice the drawing size, which is the one placed in the document.

| File | Figure |
| --- | --- |
| [`d01_context`](d01_context.svg) | 1. System context |
| [`d02_container`](d02_container.svg) | 2. Container diagram |
| [`d03a_seq_login`](d03a_seq_login.svg) | 3. Phase 1: authorization code flow with PKCE (login with IremboAccount) |
| [`d03b_seq_triage_pay`](d03b_seq_triage_pay.svg) | 4. Phase 2: consent, synchronous rules triage and payment request |
| [`d03c_seq_confirm`](d03c_seq_confirm.svg) | 5. Phase 3: webhook verification and payment confirmation |
| [`d03d_seq_ussd`](d03d_seq_ussd.svg) | 6. USSD variant |
| [`d04_saga`](d04_saga.svg) | 7. Consultation saga states |
| [`d05_sync`](d05_sync.svg) | 8. Sync gateway data flow (eBuzima and OpenMRS) |
| [`d06_identity`](d06_identity.svg) | 9. Identity resolution |
| [`d07_ha`](d07_ha.svg) | 10. High-availability topology |
| [`d08_split`](d08_split.svg) | 11. Behaviour during a 2 to 1 network partition |
| [`d09_deploy`](d09_deploy.svg) | 12. Blue/green release |
| [`d10_audit`](d10_audit.svg) | 13. Audit pipeline |
| [`d11_ai`](d11_ai.svg) | 14. AI triage pipeline |
| [`d12_hitl`](d12_hitl.svg) | 15. Triage state machine |
| [`d13_ops`](d13_ops.svg) | 16. Observability and alerting |

## Sources

Every figure is drawn from a [D2](https://d2lang.com) file in `src/` with the ELK layout. `src/style.d2` holds the
shared look: one colour per kind of element (blue for TeleClinic, orange for IremboHub, grey for external systems,
green for data, purple for the AI trust zone, red for the edge and for failure paths).

To regenerate (D2 0.6, Python 3, Node 22 or later and Google Chrome):

```bash
./render.sh              # all figures
./render.sh d05_sync     # one figure
```

`render.sh` compiles each source to SVG, widens the gaps behind connection labels (and, in sequence diagrams, puts
a white box behind each message so lifelines do not run through the text) with `tools/d2post.py`, then turns the
SVGs into PNGs with headless Chrome (`tools/svg2png.mjs`).

The figures show the production design. Where the reference implementation in this repository differs (one machine,
no Keepalived, metrics only, no write-once archive), Appendix D of the document says so.
