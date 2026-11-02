#!/usr/bin/env python3
"""Generate InstrumentData.java (the instrument catalog) from MuseScore's instruments.xml.

Usage:
  curl -LO https://raw.githubusercontent.com/musescore/MuseScore/main/share/instruments/instruments.xml
  python3 tools/gen_instruments.py instruments.xml app/src/main/java/io/github/vlesk/stave/model/InstrumentData.java

Every <Instrument> template becomes one row, in the file's order, with its <init> template resolved.
Stave shows scores at concert pitch, so staves get the concert clef (<concertClef>, falling back to <clef>).
"""
import sys
import xml.etree.ElementTree as ET

# Clef names Stave can engrave (model/Clef.java). Anything else is mapped to the nearest.
CLEFS = {'G', 'F', 'C1', 'C2', 'C3', 'C4', 'C5', 'G8vb', 'G8va', 'G15ma', 'F8vb', 'F8va', 'F15mb', 'F15ma', 'PERC'}
CLEF_FALLBACK = {'G15mb': 'G8vb', 'G_1': 'G', 'C4_8vb': 'C4', 'F_B': 'F', 'F_C': 'F', 'PERC2': 'PERC', 'TAB': 'G8vb'}


def text(el, tag):
    c = el.find(tag)
    return c.text.strip() if c is not None and c.text else None


def staff_idx(el):
    s = el.get('staff')
    return int(s) - 1 if s else 0


def parse(el, group, base):
    """Return the instrument's fields, starting from its <init> template's fields."""
    d = dict(base) if base else {'name': None, 'trackName': None, 'shortName': '', 'musicXmlId': '', 'program': None,
                                 'drums': False, 'tab': False, 'trait': None, 'unnamed': False, 'staves': 1, 'clef': {}, 'concert': {}}
    d = {k: (dict(v) if isinstance(v, dict) else v) for k, v in d.items()}
    d['id'] = el.get('id')
    d['group'] = group
    seen_channel = False
    for c in el:
        t = c.tag
        v = c.text.strip() if c.text else ''
        if t == 'longName':
            d['name'] = v
        elif t == 'trackName':
            d['trackName'] = v
        elif t == 'shortName':
            d['shortName'] = v
        elif t == 'traitName':
            d['trait'] = (c.get('type'), v)
        elif t == 'musicXMLid':
            d['musicXmlId'] = v
        elif t == 'staves':
            d['staves'] = int(v)
        elif t == 'clef':
            d['clef'][staff_idx(c)] = v
            d['concert'].pop(staff_idx(c), None)
        elif t == 'concertClef':
            d['concert'][staff_idx(c)] = v
        elif t == 'drumset':
            d['drums'] = v == '1'
        elif t == 'stafftype':
            d['tab'] = v == 'tablature'
            if v == 'percussion':
                d['drums'] = True
        elif t == 'Channel' and not seen_channel:
            # The first channel is the instrument's normal sound (later ones are pizzicato, mute ...).
            seen_channel = True
            p = c.find('program')
            if p is not None:
                d['program'] = int(p.get('value'))
    if d['name'] is None and d['trackName'] is None:
        # A family placeholder without a name of its own (MuseScore's picker leaves it out).
        d['unnamed'] = True
        d['name'] = d['id'].replace('-', ' ').capitalize()
    if d['name'] is None:
        d['name'] = d['trackName']
    if d['trackName'] is None:
        d['trackName'] = d['name']
    return d


def with_trait(d, name):
    """A name with the instrument's trait, as MuseScore 4 formats it ("Clarinet in B♭", "Lute (8-course)")."""
    if not d['trait']:
        return name
    kind, v = d['trait']
    v = v.lstrip('*')
    if v.startswith('('):  # a hidden trait (the only choice for this instrument)
        return name
    return ('%s (%s)' if kind == 'course' else '%s in %s') % (name, v)


def label(d):
    """The name shown in the instrument picker: the track name plus its trait."""
    return with_trait(d, d['trackName'])


def part_name(d):
    """The part's name on the score: the long name, with the trait when the instrument has one."""
    return label(d) if d['trait'] and not d['tab'] else d['name']


def clefs(d):
    out = []
    for i in range(d['staves']):
        c = d['concert'].get(i) or d['clef'].get(i) or 'G'
        if d['drums'] and i == 0 and c == 'G':
            c = 'PERC'
        c = CLEF_FALLBACK.get(c, c)
        if c not in CLEFS:
            raise SystemExit('unsupported clef %s in %s' % (c, d['id']))
        out.append(c)
    return out


def jstr(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def main(src, out):
    root = ET.parse(src).getroot()
    rows, byId, pending = [], {}, []
    for g in root.iter('InstrumentGroup'):
        gname = text(g, 'name')
        for el in g.findall('Instrument'):
            pending.append((el, gname))
    # Resolve <init> references (a template can point at one defined later in the file).
    while pending:
        rest = []
        for el, gname in pending:
            init = text(el, 'init')
            if init and init not in byId:
                rest.append((el, gname))
                continue
            d = parse(el, gname, byId.get(init))
            if init:
                d['init'] = init
            byId[d['id']] = d
        if len(rest) == len(pending):
            raise SystemExit('unresolved <init>: ' + ', '.join(e.get('id') for e, _ in rest))
        pending = rest
    order = [el.get('id') for g in root.iter('InstrumentGroup') for el in g.findall('Instrument')]
    for iid in order:
        d = byId[iid]
        alias = 'init' in d and not d['tab']  # e.g. double-bass = contrabass under another id
        flags = ('d' if d['drums'] else '') + ('t' if d['tab'] else '') + ('a' if alias else '') + ('h' if d['unnamed'] else '')
        prog = d['program'] if d['program'] is not None else 0
        f = [d['group'], d['id'], part_name(d), label(d), d['shortName'] or '', d['musicXmlId'] or '', str(prog), flags,
             ' '.join(clefs(d)), d.get('init', ''), d['name'] if d['name'] != part_name(d) else '']
        for x in f:
            if '|' in x:
                raise SystemExit('field contains "|": ' + x)
        rows.append('|'.join(f))

    L = []
    w = L.append
    w('// GENERATED by tools/gen_instruments.py from MuseScore\'s share/instruments/instruments.xml (GPL-3.0) - do not edit.')
    w('package io.github.vlesk.stave.model;')
    w('')
    w('/**')
    w(' * MuseScore\'s instrument templates, one row each, in the order of MuseScore\'s instrument list:')
    w(' * group|id|part name|picker label|shortName|musicXmlId|program|flags|concert clefs|base template id|')
    w(' * longName when it differs from the part name (which adds the trait: "Clarinet in A").')
    w(' * Flags: d = drum set (MIDI channel 10), t = tablature template (shown as standard notation),')
    w(' * a = another id for the instrument named in the last field, h = unnamed family placeholder.')
    w(' * Rows flagged t, a or h are not offered in the instrument picker but are known when reading files.')
    w(' */')
    w('final class InstrumentData {')
    w('    private InstrumentData() {}')
    w('')
    w('    static final String[] ROWS = {')
    for r in rows:
        w('            %s,' % jstr(r))
    w('    };')
    w('}')
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(L) + '\n')
    print('%d instruments -> %s' % (len(rows), out))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
