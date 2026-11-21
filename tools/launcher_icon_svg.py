#!/usr/bin/env python3
"""
Renders the launcher icon as an SVG for the README, from the vector drawables tools/gen_launcher_icon.py writes:

    python3 -I tools/launcher_icon_svg.py

Writes docs/images/icon.svg: the background and foreground layers, cropped to the 72dp a launcher shows and
clipped to a rounded square. Handles only what those drawables use: paths with solid or gradient fills and
strokes, and groups with scale, translate and rotation.
"""
import os
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
OUT = os.path.join(ROOT, "docs", "images", "icon.svg")
A = "{http://schemas.android.com/apk/res/android}"
SHOWN, CORNER = 72.0, 16.0  # dp visible on a launcher (of 108), and the corner radius of the mask

defs = []


def colour(argb):
    """#AARRGGBB → (#RRGGBB, opacity)."""
    h = argb.lstrip("#")
    if len(h) == 6:
        return "#" + h, 1.0
    return "#" + h[2:], int(h[:2], 16) / 255


def gradient(el):
    gid = f"g{len(defs)}"
    stops = "".join(
        f'<stop offset="{i.get(A + "offset")}" stop-color="{colour(i.get(A + "color"))[0]}" stop-opacity="{colour(i.get(A + "color"))[1]:.3f}"/>'
        for i in el.findall("item"))
    if el.get(A + "type") == "radial":
        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{el.get(A + "centerX")}" '
                    f'cy="{el.get(A + "centerY")}" r="{el.get(A + "gradientRadius")}">{stops}</radialGradient>')
    else:
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{el.get(A + "startX")}" '
                    f'y1="{el.get(A + "startY")}" x2="{el.get(A + "endX")}" y2="{el.get(A + "endY")}">{stops}</linearGradient>')
    return f"url(#{gid})"


def paint(path, attr):
    """An SVG paint and opacity for android:fillColor / strokeColor, given inline or as an aapt:attr gradient."""
    if path.get(A + attr):
        return colour(path.get(A + attr))
    for child in path:
        if child.get("name") == "android:" + attr:
            return gradient(child.find("gradient")), 1.0
    return "none", 1.0


def render(el):
    if el.tag == "path":
        fill, fo = paint(el, "fillColor")
        stroke, so = paint(el, "strokeColor")
        attrs = f'd="{el.get(A + "pathData")}" fill="{fill}" fill-opacity="{fo:.3f}"'
        if stroke != "none":
            attrs += (f' stroke="{stroke}" stroke-opacity="{so:.3f}" stroke-width="{el.get(A + "strokeWidth", "1")}"'
                      f' stroke-linecap="{el.get(A + "strokeLineCap", "butt")}" stroke-linejoin="{el.get(A + "strokeLineJoin", "miter")}"')
        return f"<path {attrs}/>"
    if el.tag in ("group", "vector"):
        inner = "".join(render(c) for c in el if c.tag in ("path", "group"))
        if el.tag == "vector":
            return inner
        g = lambda k, d: float(el.get(A + k, d))
        # Android scales and rotates about the pivot, then translates.
        px, py = g("pivotX", 0), g("pivotY", 0)
        t = (f'translate({g("translateX", 0) + px} {g("translateY", 0) + py}) rotate({g("rotation", 0)}) '
             f'scale({g("scaleX", 1)} {g("scaleY", 1)}) translate({-px} {-py})')
        return f'<g transform="{t}">{inner}</g>'
    return ""


layers = "".join(render(ET.parse(os.path.join(RES, f"ic_launcher_{n}.xml")).getroot()) for n in ("background", "foreground"))
o = (108 - SHOWN) / 2
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{o} {o} {SHOWN} {SHOWN}" width="256" height="256">'
       f'<defs><clipPath id="shape"><rect x="{o}" y="{o}" width="{SHOWN}" height="{SHOWN}" rx="{CORNER}"/></clipPath>{"".join(defs)}</defs>'
       f'<g clip-path="url(#shape)">{layers}</g></svg>\n')
with open(OUT, "w") as fh:
    fh.write(svg)
print("wrote", os.path.relpath(OUT, ROOT))
