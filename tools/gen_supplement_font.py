#!/usr/bin/env python3
"""Build the supplementary music font: the SMuFL glyphs Stave draws that Leland lacks, taken from Bravura.

MuseScore draws these from Bravura when the score font has no such glyph, so Stave does the same.
The subset is a Modified Version under the SIL OFL, so it must not carry the reserved name "Bravura";
it is renamed "Stave Supplement". Its glyph metrics (staff spaces) go to supplement_metadata.json,
which tools/gen_glyphs.py reads.

Usage (needs fontTools: pip install fonttools):
  curl -LO https://raw.githubusercontent.com/musescore/MuseScore/main/fonts/bravura/Bravura.otf
  python3 tools/gen_supplement_font.py Bravura.otf app/src/main/assets/fonts/StaveSupplement.otf \\
      third_party/smufl/supplement_metadata.json
"""
import json
import sys

from fontTools import subset
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont

# SMuFL name -> codepoint in Bravura (optional glyphs live in the private-use range above U+F400).
GLYPHS = {
    'gClef8vbCClef': 0xE056,
    'cClefSquare': 0xE060,
    'cClefFrench': 0xF408,
    'cClefFrench20C': 0xF4BF,
    'fClefFrench': 0xF406,
    'fClef19thCentury': 0xF407,
}
FAMILY = 'Stave Supplement'
PS_NAME = 'StaveSupplement'


def main(src, out_font, out_meta):
    font = TTFont(src)
    cmap = font.getBestCmap()
    gs = font.getGlyphSet()
    upm = font['head'].unitsPerEm
    space = upm / 4.0  # SMuFL: the em is four staff spaces
    meta = {'fontName': FAMILY, 'source': font['name'].getDebugName(4) + ' ' + font['name'].getDebugName(5),
            'glyphs': {}}
    for name, cp in GLYPHS.items():
        if cp not in cmap:
            raise SystemExit('%s (U+%04X) is not in %s' % (name, cp, src))
        pen = BoundsPen(gs)
        gs[cmap[cp]].draw(pen)
        x0, y0, x1, y1 = pen.bounds
        meta['glyphs'][name] = {'codepoint': 'U+%04X' % cp,
                                'bBoxSW': [round(x0 / space, 3), round(y0 / space, 3)],
                                'bBoxNE': [round(x1 / space, 3), round(y1 / space, 3)]}

    opts = subset.Options()
    opts.name_IDs = ['*']
    opts.name_languages = ['*']
    opts.notdef_outline = True
    opts.layout_features = []
    opts.drop_tables += ['GSUB', 'GPOS', 'GDEF']
    s = subset.Subsetter(opts)
    s.populate(unicodes=list(GLYPHS.values()))
    s.subset(font)

    # Rename (OFL condition 3: no reserved font name on a Modified Version).
    names = font['name']
    for rec in names.names:
        if rec.nameID in (1, 16):
            rec.string = FAMILY
        elif rec.nameID in (4, 18):
            rec.string = FAMILY
        elif rec.nameID == 3:
            rec.string = PS_NAME + '-' + font['name'].getDebugName(5).replace('Version ', '')
        elif rec.nameID == 6:
            rec.string = PS_NAME
        elif rec.nameID == 2:
            rec.string = 'Regular'
    if 'CFF ' in font:
        cff = font['CFF ']
        cff.cff.fontNames = [PS_NAME]
        top = cff.cff.topDictIndex[0]
        top.FullName = FAMILY
        top.FamilyName = FAMILY
    font.save(out_font)
    with open(out_meta, 'w', encoding='utf-8') as fh:
        json.dump(meta, fh, indent=2, ensure_ascii=False)
        fh.write('\n')
    print('%d glyphs -> %s, %s' % (len(GLYPHS), out_font, out_meta))


if __name__ == '__main__':
    main(*sys.argv[1:4])
