#!/usr/bin/env python3
"""Convert the repository's existing food/activity SVG symbols to Android vectors."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design-lab/icons/food-icons.svg"
DEST = ROOT / "app/android/app/src/main/res/drawable"
ANDROID = "http://schemas.android.com/apk/res/android"


def number(value):
    return f"{float(value):g}"


def shape_path(element):
    tag = element.tag.split("}")[-1]
    a = element.attrib
    if tag == "path":
        return a["d"]
    if tag == "ellipse":
        x, y, rx, ry = (float(a[k]) for k in ("cx", "cy", "rx", "ry"))
        return f"M{number(x-rx)} {number(y)}A{number(rx)} {number(ry)} 0 1 0 {number(x+rx)} {number(y)}A{number(rx)} {number(ry)} 0 1 0 {number(x-rx)} {number(y)}Z"
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        r = min(float(a.get("rx", 0)), w / 2, h / 2)
        n = number
        if not r:
            return f"M{n(x)} {n(y)}h{n(w)}v{n(h)}h{n(-w)}Z"
        return f"M{n(x+r)} {n(y)}H{n(x+w-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w)} {n(y+r)}V{n(y+h-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w-r)} {n(y+h)}H{n(x+r)}A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y+h-r)}V{n(y+r)}A{n(r)} {n(r)} 0 0 1 {n(x+r)} {n(y)}Z"
    raise ValueError(f"Unsupported SVG shape {tag}")


def paths(element, inherited=None):
    styles = {**(inherited or {}), **element.attrib}
    if element.tag.split("}")[-1] in ("symbol", "g"):
        return [path for child in element for path in paths(child, styles)]
    attrs = {"pathData": shape_path(element), "fillColor": styles.get("fill", "#000000")}
    for source, target in (("stroke", "strokeColor"), ("stroke-width", "strokeWidth"), ("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin")):
        if source in styles:
            attrs[target] = styles[source]
    return ["    <path " + " ".join(f"android:{key}={quoteattr(value.replace('var(--food-icon-ink, #2C2721)', '#2C2721').replace('none', '#00000000'))}" for key, value in attrs.items()) + " />"]


def vector(symbol):
    if symbol.attrib["viewBox"] != "0 0 24 24":
        raise ValueError("Only the shared 24px catalog is supported")
    return '\n'.join([
        "<!-- Generated from design-lab/icons/food-icons.svg by scripts/generate-food-drawables.py. -->",
        f'<vector xmlns:android="{ANDROID}" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">',
        *paths(symbol), "</vector>", "",
    ])


def main():
    check = "--check" in sys.argv
    symbols = list(ET.parse(SOURCE).getroot())
    for symbol in symbols:
        name = symbol.attrib["id"]
        destination = DEST / ("mg_" + name.replace("-", "_") + ".xml")
        source = vector(symbol)
        if check:
            if not destination.exists() or destination.read_text() != source:
                raise SystemExit(f"Stale drawable {destination.name}; run scripts/generate-food-drawables.py")
        else:
            destination.write_text(source)
    print(f"{'Verified' if check else 'Generated'} {len(symbols)} existing SVG symbols as Android vectors")


if __name__ == "__main__":
    main()
