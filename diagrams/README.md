# Diagrams

The figures of the design document (`docs/TeleClinic_Technical_Design_Document.docx`, version 1.3), as PNG.

| File | Figure in the document |
| --- | --- |
| `d01_context.png` | 1. System context |
| `d02_container.png` | 2. Container diagram |
| `d03a_seq_login.png` | 3. Integration flow, part 1: login with IremboAccount |
| `d03b_seq_triage_pay.png` | 4. Integration flow, part 2: consent, triage and payment request |
| `d03c_seq_confirm.png` | 5. Integration flow, part 3: payment confirmation |
| `d03d_seq_ussd.png` | 6. USSD variant |
| `d04_saga.png` | 7. Consultation saga states |
| `d05_sync.png` | 8. Sync gateway data flow |
| `d06_identity.png` | 9. Identity resolution |
| `d07_ha.png` | 10. High-availability topology |
| `d08_split.png` | 11. Network partition behaviour |
| `d09_deploy.png` | 12. Blue/green release |
| `d10_audit.png` | 13. Audit pipeline |
| `d11_ai.png` | 14. AI triage pipeline |
| `d12_hitl.png` | 15. Human-in-the-loop states |
| `d13_ops.png` | 16. Observability and alerting |

Updated in version 1.3, when eBuzima became the health centres' EMR beside each hospital's OpenMRS:
figures 1, 8, 9 and 13 are redrawn from sources in `src/`, and two labels on figures 2 and 4 are corrected by a script
(those two have no editable source). To regenerate them (Graphviz and Python with Pillow), from `src/`:

```bash
dot -Tpng -Gdpi=150 fig01_context.dot  -o ../d01_context.png
dot -Tpng -Gdpi=150 fig09_identity.dot -o ../d06_identity.png
dot -Tpng -Gdpi=150 fig13_audit.dot    -o ../d10_audit.png
python3 fig08_sync.py          # fixed layout, rendered with neato -n2: ../d05_sync.png
python3 patch_fig02_fig04.py   # *_base.png in: ../d02_container.png, ../d03b_seq_triage_pay.png
```

The figures show the production design. Where the reference implementation in this repository differs (one machine,
no Keepalived, metrics only, no write-once archive), Appendix D of the document says so.
