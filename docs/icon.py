#!/usr/bin/env python3
"""Source of truth for the app icon geometry.

The launcher icon, its monochrome (themed-icon) layer and the in-app header
mark are all the same drawing: one earbud with sound arriving at it. Rather
than keep three hand-tuned copies of the same magic numbers, this script emits
all three Android vector drawables from one set of parameters:

    python3 docs/icon.py     # writes ic_launcher_foreground / _monochrome / ic_logo

SHIFT and FIT are not arbitrary. SHIFT centres the *ink mass* rather than the
bounding box, because the solid bud carries the visual weight and centring the
box makes the icon look left-heavy. FIT scales the artwork until every inked
pixel sits inside the adaptive-icon safe circle, so the stem does not get
clipped by a round launcher mask.

Needs: inkscape, numpy, pillow (development only — not part of the app build).
"""

import math, subprocess
import numpy as np
from PIL import Image

# ---- chosen design (variant S) -------------------------------------------
STEM, SCALE = 52, 0.95
ARC1, ARC2, SWEEP, ASW = 26.0, 34.0, 42.0, 6.2
SHIFT = (8.9, 18.59)     # optically balanced: the solid bud carries the visual
                         # weight, so the ink mass is centred, not the bounding box
FIT   = 0.846

BUD = (f"M6,{STEM-9} L6,30 "
       "C4,15 9,3 19,2 "
       "C30,1 35.5,9 35.5,17.5 "
       "C35.5,27 29,34 21,34.5 "
       f"L20,34.5 L20,{STEM-9} "
       f"a5,5 0 0 1 -5,5 L11,{STEM-4} a5,5 0 0 1 -5,-5 Z")
CURL = "M13.5,24 C12,16 16,9.5 23.5,9"
HX, HY = 14 + 27*SCALE, 16 + 18*SCALE          # centre of the bud's head

def arc_d(r, sweep=SWEEP, cx=HX, cy=HY):
    t = math.radians(sweep)
    return (f"M{cx+r*math.cos(-t):.2f},{cy+r*math.sin(-t):.2f} "
            f"A{r},{r} 0 0 1 {cx+r*math.cos(t):.2f},{cy+r*math.sin(t):.2f}")

def android(fg, curl, accent, *, canvas=108, viewport=None, fit=True, with_curl=True, tint=None):
    """emit an Android <vector>; geometry identical to the rendered preview"""
    w, h, ox, oy = (viewport or (canvas, canvas, 0.0, 0.0))
    body = []
    body.append(f'        <path android:fillColor="{fg}"\n'
                f'            android:pathData="{BUD}" />')
    if with_curl:
        body.append(f'        <path android:pathData="{CURL}"\n'
                    f'            android:strokeColor="{curl}" android:strokeWidth="4.4"\n'
                    f'            android:strokeLineCap="round" android:fillColor="#00000000" />')
    arcs = []
    arcs.append(f'    <path android:pathData="{arc_d(ARC1)}"\n'
                f'        android:strokeColor="{accent}" android:strokeWidth="{ASW}"\n'
                f'        android:strokeLineCap="round" android:fillColor="#00000000" />')
    arcs.append(f'    <path android:pathData="{arc_d(ARC2)}"\n'
                f'        android:strokeColor="{accent}" android:strokeWidth="{ASW}"\n'
                f'        android:strokeLineCap="round" android:fillColor="#00000000"\n'
                f'        android:strokeAlpha="0.55" />')
    inner = ('    <group android:translateX="14" android:translateY="16"\n'
             f'        android:scaleX="{SCALE}" android:scaleY="{SCALE}">\n'
             + "\n".join(body) + "\n    </group>")
    art = inner + "\n" + "\n".join(arcs)
    shift = (f'<group android:translateX="{SHIFT[0]-ox:.2f}" android:translateY="{SHIFT[1]-oy:.2f}">\n'
             + art + "\n</group>")
    if fit:
        shift = (f'<group android:pivotX="54" android:pivotY="54"\n'
                 f'    android:scaleX="{FIT}" android:scaleY="{FIT}">\n{shift}\n</group>')
    tint_attr = f'\n    android:tint="{tint}"' if tint else ''
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- JabraLibre mark: one earbud, sound arriving at it.\n'
            '     Generated geometry — see docs/icon.py in the commit history. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{w:.0f}dp" android:height="{h:.0f}dp"\n'
            f'    android:viewportWidth="{w:.2f}" android:viewportHeight="{h:.2f}"{tint_attr}>\n'
            f'{shift}\n</vector>\n')

# ---- render the chosen design for a look, and measure the tight bbox ----
def svg_of(fg="#E6EDF3", curl="#102A43", accent="#4ADE80", with_curl=True, fit=True):
    bud = (f'<g transform="translate(14,16) scale({SCALE})">'
           f'<path d="{BUD}" fill="{fg}"/>'
           + (f'<path d="{CURL}" fill="none" stroke="{curl}" stroke-width="4.4" stroke-linecap="round"/>'
              if with_curl else '') + '</g>')
    a = (f'<path d="{arc_d(ARC1)}" fill="none" stroke="{accent}" stroke-width="{ASW}" stroke-linecap="round"/>'
         f'<path d="{arc_d(ARC2)}" fill="none" stroke="{accent}" stroke-width="{ASW}" stroke-linecap="round" opacity="0.55"/>')
    art = f'<g transform="translate({SHIFT[0]},{SHIFT[1]})">{bud}{a}</g>'
    if fit: art = f'<g transform="translate(54,54) scale({FIT}) translate(-54,-54)">{art}</g>'
    return art

def render(body, size, bg=None, path='/tmp/_g.png'):
    svg=['<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">']
    if bg: svg.append(f'<rect width="108" height="108" fill="{bg}"/>')
    svg+=[body,'</svg>']
    open('/tmp/_g.svg','w').write("\n".join(svg))
    subprocess.run(['inkscape','/tmp/_g.svg','-o',path,'-w',str(size),'-h',str(size)],capture_output=True)
    return Image.open(path).convert('RGBA')

# tight bbox of the unfitted artwork, for the header logo viewport
im = render(svg_of(fit=False), 1080)
a = np.array(im.split()[-1]); ys, xs = np.nonzero(a > 24); s = 1080/108.0
x0, x1, y0, y1 = xs.min()/s, xs.max()/s, ys.min()/s, ys.max()/s
print(f"logo bbox: x {x0:.1f}..{x1:.1f}  y {y0:.1f}..{y1:.1f}  ({x1-x0:.1f} x {y1-y0:.1f})")

open('ic_launcher_foreground.xml','w').write(
    android("#E6EDF3", "#102A43", "#4ADE80"))
open('ic_launcher_monochrome.xml','w').write(
    android("#FFFFFF", "#000000", "#FFFFFF", with_curl=False))
open('ic_logo.xml','w').write(
    android("@color/text_primary", "@color/bg", "@color/brand_green",
            viewport=(round(x1-x0)+1, round(y1-y0)+1, x0-0.5, y0-0.5), fit=False))
print("android vectors written")

# previews for the user
full = render(svg_of(), 512, bg="#102A43", path='final_full.png')
from PIL import ImageDraw
m = 512*36/108.0; c = 512/2
mask = Image.new('L', full.size, 0); ImageDraw.Draw(mask).ellipse([c-m,c-m,c+m,c+m], fill=255)
circ = Image.new('RGBA', full.size, (0,0,0,0)); circ.paste(full, mask=mask)
crop = circ.crop((int(c-m),int(c-m),int(c+m),int(c+m)))
out = Image.new('RGBA', (300+120, 300), (24,24,28,255))
out.paste(crop.resize((280,280)), (10,10))
out.paste(crop.resize((96,96)), (300,10))
out.paste(crop.resize((48,48)), (300,120))
out.save('final_preview.png')
print("final_preview.png")
