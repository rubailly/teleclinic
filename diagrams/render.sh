#!/usr/bin/env bash
# Renders every diagram in src/ to an SVG and a 2x PNG next to this script.
# Needs D2 (https://d2lang.com, v0.6.x), Python 3, Node 22+ and Google Chrome.
# Usage: ./render.sh            all diagrams
#        ./render.sh d04_saga   one diagram
set -euo pipefail
cd "$(dirname "$0")"
D2="${D2:-d2}"
names=("$@")
if [ ${#names[@]} -eq 0 ]; then
  for f in src/d*.d2; do names+=("$(basename "$f" .d2)"); done
fi
pairs=()
for n in "${names[@]}"; do
  if ! out=$("$D2" --layout elk "src/$n.d2" "$n.svg" 2>&1); then
    echo "$out" | grep -v -i libarchive >&2; exit 1
  fi
  # Sequence diagrams get a white box behind each message so lifelines do not cross the text
  if grep -q 'sequence_diagram' "src/$n.d2"; then python3 tools/d2post.py "$n.svg" --backdrop >/dev/null
  else python3 tools/d2post.py "$n.svg" >/dev/null; fi
  pairs+=("$n.svg" "$n.png")
done
node --experimental-websocket tools/svg2png.mjs 2 "${pairs[@]}"
