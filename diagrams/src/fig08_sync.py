"""Figure 8 (sync gateway data flow): writes fig08_sync.dot with a fixed layout, renders it with neato -n2.

Run from diagrams/src; it writes ../d05_sync.png."""
import subprocess

POS = {  # centre of each node, in points
    "change": (125, 650), "route": (125, 530), "queue": (440, 530), "ledger": (125, 360),
    "inproc": (440, 240), "model": (125, 110), "dlq": (440, 80),
    "w1": (790, 640), "w2": (790, 500), "w3": (790, 330), "w4": (790, 190),
    "ebz": (1170, 570), "omrs": (1170, 260),
}
PANELS = {"corebg": (10, 30, 615, 730), "gwbg": (650, 30, 930, 730)}  # x0, y0, x1, y1

BLUE = 'color="#0B5CC0", fillcolor="#E8F1FB"'
GREEN = 'shape=cylinder, style=filled, color="#1E8449", fillcolor="#E9F7EF"'
GREY = 'color="#666666", fillcolor="#F1F1F1"'
NODES = {
    "change": ("Clinical change\\nor identity link", BLUE),
    "route": ("Route by home facility\\n(level, EMR, tenant)", BLUE),
    "queue": ("Outbound queue per EMR\\nsame transaction as the change;\\nordered, backoff, dead letter, replay", GREEN),
    "ledger": ("Sync ledger per EMR\\nid map, version,\\nhash, origin", GREEN),
    "inproc": ("Inbound processor\\necho drop by hash, authority\\nrules, identity matching", BLUE),
    "model": ("Canonical\\nrelational model", GREEN),
    "dlq": ("Conflict review queue", 'color="#C0392B", fillcolor="#FDEDEC"'),
    "w1": ("W1  TeleClinic to eBuzima\\nconditional create; encounter\\nin the health centre's tenant", BLUE),
    "w2": ("W2  eBuzima to TeleClinic\\nchanges since cursor,\\nDocTypes mapped to FHIR", BLUE),
    "w3": ("W3  TeleClinic to OpenMRS\\nconditional create,\\nIf-Match update", BLUE),
    "w4": ("W4  OpenMRS to TeleClinic\\npoll _lastUpdated\\nsince cursor", BLUE),
    "ebz": ("eBuzima (health centres)\\none central instance,\\na tenant per centre;\\nFrappe REST + webhooks", GREY),
    "omrs": ("Hospital OpenMRS\\none per hospital;\\nFHIR2 API", GREY),
}
EDGES = [
    ("change", "route", ""), ("route", "queue", ""),
    ("queue", "w1", 'headlabel="pull due writes", labeldistance=9, labelangle=-8'),
    ("queue", "w3", 'headlabel="pull due writes", labeldistance=4, labelangle=-55'),
    ("w1", "ebz", 'headlabel="write as the\\nintegration user", labeldistance=6, labelangle=-35'),
    ("ebz", "w2", 'style=dashed, headlabel="webhook wakes it,\\nthen reads changes", labeldistance=7, labelangle=40'),
    ("w3", "omrs", 'headlabel="write, with\\norigin tag", labeldistance=6, labelangle=-35'),
    ("omrs", "w4", 'headlabel="changes", labeldistance=6, labelangle=30'),
    ("w2", "inproc", 'taillabel="FHIR Patient", labeldistance=11, labelangle=-22'),
    ("w4", "inproc", 'taillabel="FHIR Patient", labeldistance=6, labelangle=-25'),
    ("queue", "ledger", 'style=dashed, headlabel="on success:\\nids + hash", labeldistance=7, labelangle=-40'),
    ("inproc", "ledger", "style=dashed"),
    ("inproc", "model", 'headlabel="apply if allowed", labeldistance=8, labelangle=30'),
    ("inproc", "dlq", 'style=dashed, color="#C0392B", headlabel="conflict", labeldistance=3, labelangle=60'),
]

out = ['digraph G {',
       '  graph [fontname="DejaVu Sans", bgcolor="white", pad=0.25, splines=line, outputorder=nodesfirst];',
       '  node [fontname="DejaVu Sans", fontsize=13, shape=box, style="rounded,filled", penwidth=2, margin="0.18,0.1"];',
       '  edge [fontname="DejaVu Sans", fontsize=11, color="#4A5568", fontcolor="#1A202C", arrowsize=0.8, penwidth=1.3];']
labels = {"corebg": "TeleClinic Core\\l", "gwbg": "OpenFn sync gateway\\lone workflow pair per EMR\\l"}
for k, (x0, y0, x1, y1) in PANELS.items():
    style = ('style="rounded,filled", color="#0B5CC0", fillcolor="#F4F8FD"' if k == "corebg"
             else 'style="rounded,dashed", color="#7F7F7F"')
    out.append(f'  {k} [pos="{(x0 + x1) / 2},{(y0 + y1) / 2}!", width={(x1 - x0) / 72:.2f}, height={(y1 - y0) / 72:.2f}, '
               f'fixedsize=true, label="{labels[k]}", labelloc=t, fontcolor="#2D3748", penwidth=1.5, {style}];')
for k, (label, style) in NODES.items():
    x, y = POS[k]
    out.append(f'  {k} [pos="{x},{y}!", label="{label}", {style}];')
for a, b, attrs in EDGES:
    out.append(f'  {a} -> {b} [{attrs}];')
out.append("}")
open("fig08_sync.dot", "w").write("\n".join(out) + "\n")
subprocess.run(["neato", "-n2", "-Tpng", "-Gdpi=150", "fig08_sync.dot", "-o", "../d05_sync.png"], check=True)
