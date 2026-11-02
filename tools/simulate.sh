#!/usr/bin/env bash
# Enters real scores through the editor the way a person does (duration, dot and tuplet buttons,
# piano keys and chords, respell button, tie, grace notes, slurs and hairpins, voices, palettes,
# lyric entry), then compares the result with the source note by note, checks the layout at phone width and renders
# PNGs. The scores come from the music21 corpus (installed with pip into a cache folder).
# Usage: tools/simulate.sh [corpus path ...]   (default: five complex pieces)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${STAVE_CACHE:-$HOME/.cache/stave-build}"
OUT="${SIM_OUT:-${TMPDIR:-/tmp}/stave-sim}"
mkdir -p "$CACHE" "$OUT"
if [ ! -x "$CACHE/m21/bin/python" ]; then
  python3 -m venv "$CACHE/m21" && "$CACHE/m21/bin/pip" install -q music21
fi
PIECES=("$@")
[ ${#PIECES[@]} -eq 0 ] && PIECES=(schubert/Lindenbaum.xml joplin/maple_leaf_rag.mxl beethoven/opus18no1/movement1.mxl
  schumann_clara/polonaise_op1n2.mxl beethoven/opus59no2/movement2.mxl)
TMPDIR="$OUT" "$ROOT/tools/test.sh" > /dev/null
CLASSES="$OUT/stave-test-classes"
for p in "${PIECES[@]}"; do
  name=$(echo "$p" | tr '/.' '__')
  "$CACHE/m21/bin/python" -I "$ROOT/tools/sim/extract.py" "$p" "$OUT/$name.json"
  java -Djava.awt.headless=true -Xmx3g -cp "$CLASSES" io.github.vlesk.stave.InputSim "$OUT/$name.json" "$OUT/$name" "$ROOT/app/src/main/assets/fonts"
done
echo "Results, .mscx, .musicxml and .png files in $OUT"
