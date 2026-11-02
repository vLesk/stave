"""Reads a score with music21 and writes what a person would type, staff by staff, as JSON."""
import json, sys
from music21 import corpus, stream, note, chord, dynamics, expressions, articulations, meter, key, clef, spanner

ART = {'Staccato': 'staccato', 'Accent': 'accent', 'Tenuto': 'tenuto', 'StrongAccent': 'marcato',
       'Staccatissimo': 'staccatissimo', 'Spiccato': 'staccatissimo'}

def clef_name(c):
    if c is None: return None
    sign, line, oc = c.sign, c.line, c.octaveChange or 0
    if sign == 'G': return 'treble8vb' if oc == -1 else ('treble8va' if oc == 1 else 'treble')
    if sign == 'F': return 'bass8vb' if oc == -1 else 'bass'
    if sign == 'C': return 'tenor' if line == 4 else 'alto'
    return 'perc'

def main(path, out):
    s = corpus.parse(path)
    md = s.metadata
    res = {'title': (md.title or md.movementName or path) if md else path,
           'composer': (md.composer or '') if md else '', 'staves': [], 'unsupported': {}}
    def unsup(k):
        res['unsupported'][k] = res['unsupported'].get(k, 0) + 1
    where = {}  # id of a note, chord or rest -> (staff, measure, voice, event index)
    for si, p in enumerate(s.parts):
        inst = p.getInstrument(returnDefault=True)
        st = {'id': p.id, 'name': p.partName or '', 'instrument': (inst.instrumentName or ''), 'measures': []}
        for mi, m in enumerate(p.getElementsByClass(stream.Measure)):
            mm = {'number': m.number, 'ql': float(m.duration.quarterLength), 'bar': float(m.barDuration.quarterLength)}
            if m.timeSignature is not None: mm['ts'] = [m.timeSignature.numerator, m.timeSignature.denominator]
            if m.keySignature is not None: mm['key'] = m.keySignature.sharps
            cl = m.getElementsByClass(clef.Clef)
            if len(cl): mm['clef'] = clef_name(cl[0])
            dyns = []
            for d in m.recurse().getElementsByClass(dynamics.Dynamic):
                dyns.append([float(d.getOffsetInHierarchy(m)), d.value])
            mm['dyn'] = dyns
            voices = list(m.voices) if m.hasVoices() else [m]
            vs = []
            for vi, v in enumerate(voices):
                evs = []
                graces = []
                for el in v.notesAndRests:
                    if el.duration.isGrace:
                        # grace notes belong to the next main note, as entered in the app
                        ns = el.notes if isinstance(el, chord.Chord) else [el]
                        graces.append({'type': el.duration.type, 'slash': bool(getattr(el.duration, 'slash', False)),
                                       'pitches': [[n.pitch.midi, int(n.pitch.accidental.alter) if n.pitch.accidental else 0,
                                                    n.pitch.step] for n in ns]})
                        continue
                    where[id(el)] = (si, mi, vi, len(evs))
                    if isinstance(el, chord.Chord):
                        for n in el.notes: where[id(n)] = (si, mi, vi, len(evs))
                    ev = {'off': float(el.offset), 'ql': float(el.quarterLength), 'type': el.duration.type,
                          'dots': el.duration.dots}
                    if el.duration.tuplets:
                        t = el.duration.tuplets[0]
                        ev['tuplet'] = [t.numberNotesActual, t.numberNotesNormal]
                        ev['tunit'] = t.durationNormal.type if t.durationNormal else el.duration.type
                    if graces:
                        if el.isRest: unsup('grace notes before a rest')
                        else: ev['graces'] = graces
                        graces = []
                    if el.isRest:
                        ev['rest'] = True
                    else:
                        ns = el.notes if isinstance(el, chord.Chord) else [el]
                        ev['pitches'] = [[n.pitch.midi, int(n.pitch.accidental.alter) if n.pitch.accidental else 0,
                                          n.pitch.step] for n in ns]
                        ev['tie'] = [(n.tie.type if n.tie else None) for n in ns]
                        if el.lyrics:
                            ly = el.lyrics[0]
                            ev['lyric'] = ly.text
                            ev['syllabic'] = ly.syllabic or 'single'
                        arts = []
                        for a in el.articulations:
                            name = type(a).__name__
                            if name in ART: arts.append(ART[name])
                            else: unsup('articulation ' + name)
                        for e in el.expressions:
                            name = type(e).__name__
                            if name == 'Fermata': arts.append('fermata')
                            elif 'Trill' in name: arts.append('trill')
                            elif 'Mordent' in name: arts.append('mordent')
                            elif 'Turn' in name: arts.append('turn')
                        ev['arts'] = arts
                    evs.append(ev)
                vs.append(evs)
            mm['voices'] = vs
            st['measures'].append(mm)
        res['staves'].append(st)
    # slurs and hairpins: on their first event, where they end
    for sp in s.spannerBundle:
        if isinstance(sp, spanner.Slur): kind = 'slur'
        elif isinstance(sp, dynamics.Crescendo): kind = 'cresc'
        elif isinstance(sp, dynamics.Diminuendo): kind = 'dim'
        else: continue
        els = sp.getSpannedElements()
        ws = [where.get(id(e)) for e in els]
        if not els or None in ws:
            g = any(e.duration.isGrace for e in els)
            unsup(kind + (' from a grace note' if g else ' on an unknown element')); continue
        # music21 does not keep a wedge's ends in order, and gives some only their start
        a, b = min(ws, key=lambda w: (w[1], w[3])), max(ws, key=lambda w: (w[1], w[3]))
        if a[0] != b[0]: unsup(kind + ' across staves'); continue
        if a[2] != b[2]: unsup(kind + ' across voices'); continue
        ev = res['staves'][a[0]]['measures'][a[1]]['voices'][a[2]][a[3]]
        ev.setdefault('lines', []).append([kind, b[1], b[3]])
    json.dump(res, open(out, 'w'), indent=0)
    print(path, 'staves', len(res['staves']), 'measures', len(res['staves'][0]['measures']), 'unsupported', res['unsupported'])

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
