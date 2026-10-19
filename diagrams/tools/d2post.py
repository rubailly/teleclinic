#!/usr/bin/env python3
"""Tidy a D2 SVG: widen the gaps cut into lines behind connection labels, and with --backdrop
put a white box behind each label so lifelines in sequence diagrams do not run through the text."""
import re, sys

PAD_X, PAD_Y = 10, 3
path = sys.argv[1]
backdrop = "--backdrop" in sys.argv
s = open(path).read()
boxes = []

def widen(m):
    x, y, w, h = (float(v) for v in m.group(1, 2, 3, 4))
    x, y, w, h = x - PAD_X, y - PAD_Y, w + 2 * PAD_X, h + 2 * PAD_Y
    boxes.append((x, y, w, h))
    return f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" fill="black"></rect>'

s = re.sub(r'<rect x="([-\d.]+)" y="([-\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="black"></rect>', widen, s)

if backdrop:
    def back(m):
        tx, ty = float(m.group(1)), float(m.group(2))
        for x, y, w, h in boxes:
            if x <= tx <= x + w and y <= ty - 4 <= y + h:
                return (f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="3" fill="white"></rect>'
                        + m.group(0))
        return m.group(0)
    # only label text inside a connection group: the text directly follows a connection path
    out, pos = [], 0
    for m in re.finditer(r'<text x="([-\d.]+)" y="([-\d.]+)"', s):
        before = s[s.rfind("<g ", 0, m.start()):m.start()]
        out.append(s[pos:m.start()])
        out.append(back(m) if 'class="connection' in before and before.rstrip().endswith("/>") else m.group(0))
        pos = m.end()
    out.append(s[pos:])
    s = "".join(out)
open(path, "w").write(s)
print(f"{path}: {len(boxes)} labels")
