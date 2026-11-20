#!/usr/bin/env python3
"""Generate the Android glyph catalog from the current icon sources.

This tool is intentionally developer-invoked. Gradle and the runtime consume only the checked-in
Kotlin output and packaged Android resources; they never parse the source JSON/SVG files and do
not require a network connection.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
CATALOG = PROJECT / "design-system" / "stemwise-icons.json"
SOURCE_ICONS = PROJECT / "design-system" / "source-part-icons.svg"
OUTPUT = (
    PROJECT
    / "app"
    / "src"
    / "main"
    / "java"
    / "app"
    / "stemwise"
    / "ui"
    / "design"
    / "generated"
    / "StemwiseGeneratedGlyphCatalog.kt"
)

RTL_MIRRORS = {
    "library.queue_music",
    "library.open_external",
    "system.disclosure",
}
SUPPORTED_ELEMENTS = {"g", "path", "rect", "circle", "ellipse", "line"}


def kotlin_string(value: str) -> str:
    return (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def number(value: object, default: float = 0.0) -> float:
    if value is None:
        return default
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"Non-finite numeric value: {value!r}")
    return result


def fmt(value: float) -> str:
    if abs(value) < 0.0000005:
        value = 0.0
    rendered = f"{value:.9f}".rstrip("0").rstrip(".")
    if "." not in rendered:
        rendered += ".0"
    return rendered + "f"


def enum_name(role: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", role).strip("_").upper()


def shape_path(element: str, attrs: dict[str, object]) -> str:
    if element == "path":
        value = attrs.get("d")
        if not isinstance(value, str) or not value.strip():
            raise ValueError("Path layer has no path data")
        return value
    if element == "line":
        return (
            f"M{attrs['x1']} {attrs['y1']} "
            f"L{attrs['x2']} {attrs['y2']}"
        )
    if element == "circle":
        cx, cy, radius = (number(attrs[key]) for key in ("cx", "cy", "r"))
        return (
            f"M{cx - radius:g} {cy:g} "
            f"A{radius:g} {radius:g} 0 1 0 {cx + radius:g} {cy:g} "
            f"A{radius:g} {radius:g} 0 1 0 {cx - radius:g} {cy:g} Z"
        )
    if element == "ellipse":
        cx, cy, rx, ry = (number(attrs[key]) for key in ("cx", "cy", "rx", "ry"))
        return (
            f"M{cx - rx:g} {cy:g} "
            f"A{rx:g} {ry:g} 0 1 0 {cx + rx:g} {cy:g} "
            f"A{rx:g} {ry:g} 0 1 0 {cx - rx:g} {cy:g} Z"
        )
    if element == "rect":
        x, y = number(attrs["x"]), number(attrs["y"])
        width, height = number(attrs["width"]), number(attrs["height"])
        rx = min(number(attrs.get("rx"), 0.0), width / 2.0)
        ry = min(number(attrs.get("ry"), rx), height / 2.0)
        if rx <= 0.0 or ry <= 0.0:
            return f"M{x:g} {y:g} H{x + width:g} V{y + height:g} H{x:g} Z"
        return (
            f"M{x + rx:g} {y:g} H{x + width - rx:g} "
            f"A{rx:g} {ry:g} 0 0 1 {x + width:g} {y + ry:g} "
            f"V{y + height - ry:g} A{rx:g} {ry:g} 0 0 1 {x + width - rx:g} {y + height:g} "
            f"H{x + rx:g} A{rx:g} {ry:g} 0 0 1 {x:g} {y + height - ry:g} "
            f"V{y + ry:g} A{rx:g} {ry:g} 0 0 1 {x + rx:g} {y:g} Z"
        )
    raise ValueError(f"Cannot produce path for {element}")


def transform(raw: object) -> dict[str, float]:
    result = {
        "translation_x": 0.0,
        "translation_y": 0.0,
        "scale_x": 1.0,
        "scale_y": 1.0,
        "rotation": 0.0,
        "pivot_x": 0.0,
        "pivot_y": 0.0,
        "a": 1.0,
        "b": 0.0,
        "c": 0.0,
        "d": 1.0,
        "e": 0.0,
        "f": 0.0,
    }
    if raw is None:
        return result
    if not isinstance(raw, str):
        raise ValueError(f"Transform is not text: {raw!r}")
    translate_scale = re.fullmatch(
        r"translate\(([-+0-9.]+)(?:\s+([-+0-9.]+))?\)"
        r"(?:\s+scale\(([-+0-9.]+)(?:\s+([-+0-9.]+))?\))?",
        raw,
    )
    rotate = re.fullmatch(
        r"rotate\(([-+0-9.]+)(?:\s+([-+0-9.]+)\s+([-+0-9.]+))?\)", raw
    )
    if translate_scale:
        tx = number(translate_scale.group(1))
        ty = number(translate_scale.group(2), 0.0)
        sx = number(translate_scale.group(3), 1.0)
        sy = number(translate_scale.group(4), sx)
        result.update(
            translation_x=tx,
            translation_y=ty,
            scale_x=sx,
            scale_y=sy,
            a=sx,
            d=sy,
            e=tx,
            f=ty,
        )
        return result
    if rotate:
        degrees = number(rotate.group(1))
        px = number(rotate.group(2), 0.0)
        py = number(rotate.group(3), 0.0)
        radians = math.radians(degrees)
        cosine, sine = math.cos(radians), math.sin(radians)
        result.update(
            rotation=degrees,
            pivot_x=px,
            pivot_y=py,
            a=cosine,
            b=sine,
            c=-sine,
            d=cosine,
            e=px - cosine * px + sine * py,
            f=py - sine * px - cosine * py,
        )
        return result
    raise ValueError(f"Unsupported transform: {raw}")


def validate_catalog(catalog: dict[str, object]) -> list[dict[str, object]]:
    roles = catalog.get("roles")
    if not isinstance(roles, list) or not roles:
        raise ValueError("The catalog must contain roles")
    role_names = [role["role"] for role in roles]
    if len(set(role_names)) != len(roles):
        raise ValueError("Catalog role names are not unique")
    mirrored = {role["role"] for role in roles if role.get("rtl") == "mirror"}
    if mirrored != RTL_MIRRORS:
        raise ValueError(f"Unexpected RTL mirror roles: {sorted(mirrored)}")
    enum_names = [enum_name(role) for role in role_names]
    if len(set(enum_names)) != len(roles):
        raise ValueError("Generated enum names collide")
    consumers = PROJECT / "app/src/main/java/app/stemwise/ui"
    required = set()
    for name in ("StemwiseIcons.kt", "player/surface/PlayerSurfaceIconAtlas.kt", "player/native/FullPlayerSnapshot.kt"):
        references = set(re.findall(r"StemwiseIconRole\.([A-Z][A-Z_0-9]+)", (consumers / name).read_text(encoding="utf-8")))
        if not references:
            raise ValueError(f"Missing app glyph consumer: {name}")
        required.update(references)
    available = set(enum_names) | {enum_name(role["role"]) for role in source_icon_roles()}
    if missing := required - available:
        raise ValueError(f"Missing app glyph roles: {sorted(missing)}")
    for role in roles:
        if role["viewport"] != {"x": 0, "y": 0, "width": 24, "height": 24, "unit": "dp"}:
            raise ValueError(f"Unexpected viewport for {role['role']}")
        if role["opticalSize"] != {"width": 20, "height": 20, "inset": 2, "unit": "dp"}:
            raise ValueError(f"Unexpected optical size for {role['role']}")
        layers = role["layers"]
        if [layer["order"] for layer in layers] != list(range(len(layers))):
            raise ValueError(f"Layer order is not contiguous for {role['role']}")
        for layer in layers:
            if layer["element"] not in SUPPORTED_ELEMENTS:
                raise ValueError(f"Unsupported element {layer['element']} in {role['role']}")
            if layer["element"] == "g" and "transform" not in layer["attributes"]:
                raise ValueError(f"Group without transform in {role['role']}")
            transform(layer["attributes"].get("transform"))
    return roles


def layer_line(role: dict[str, object], layer: dict[str, object]) -> str:
    attrs = layer["attributes"]
    root = role["rootPaint"]
    element = layer["element"]
    fill = attrs.get("fill", root.get("fill", "none")) != "none"
    stroke = attrs.get("stroke", root.get("stroke", "none")) != "none"
    stroke_width = number(attrs.get("strokeWidth", root.get("strokeWidth")), 0.0)
    line_cap = str(attrs.get("strokeLinecap", root.get("strokeLinecap", "butt"))).upper()
    line_join = str(attrs.get("strokeLinejoin", root.get("strokeLinejoin", "miter"))).upper()
    path_data = "" if element == "g" else shape_path(element, attrs)
    tx = transform(attrs.get("transform"))
    return (
        "            StemwiseGlyphLayer("
        f"order = {layer['order']}, depth = {layer['depth']}, "
        f"element = StemwiseGlyphElement.{'GROUP' if element == 'g' else element.upper()}, "
        f"pathData = \"{kotlin_string(path_data)}\", "
        f"fill = {str(fill).lower()}, stroke = {str(stroke).lower()}, "
        f"strokeWidth = {fmt(stroke_width)}, "
        f"strokeLineCap = StemwiseStrokeLineCap.{line_cap}, "
        f"strokeLineJoin = StemwiseStrokeLineJoin.{line_join}, "
        "transform = StemwiseGlyphTransform("
        f"translationX = {fmt(tx['translation_x'])}, translationY = {fmt(tx['translation_y'])}, "
        f"scaleX = {fmt(tx['scale_x'])}, scaleY = {fmt(tx['scale_y'])}, "
        f"rotation = {fmt(tx['rotation'])}, pivotX = {fmt(tx['pivot_x'])}, pivotY = {fmt(tx['pivot_y'])}, "
        f"a = {fmt(tx['a'])}, b = {fmt(tx['b'])}, c = {fmt(tx['c'])}, "
        f"d = {fmt(tx['d'])}, e = {fmt(tx['e'])}, f = {fmt(tx['f'])})"
        ")"
    )


def render(catalog: dict[str, object], roles: list[dict[str, object]]) -> str:
    lines = [
        "// Generated by tools/generate-stemwise-design.py. Do not edit by hand.",
        "package app.stemwise.ui.design.generated",
        "",
        "import androidx.compose.runtime.Immutable",
        "",
        f"internal const val STEMWISE_ICON_ROLE_COUNT = {len(roles)}",
        f"internal const val STEMWISE_ICON_MASTER_COUNT = {len({role['master'] for role in roles})}",
        f"internal const val STEMWISE_ICON_FAMILY_COUNT = {len({role['family'] for role in roles})}",
        f"internal const val STEMWISE_ICON_REDUCTION_COUNT = {len(roles) - len({role['master'] for role in roles})}",
        "",
        "internal enum class StemwiseIconRole(val wireName: String) {",
    ]
    for index, role in enumerate(roles):
        suffix = "," if index < len(roles) - 1 else ";"
        lines.append(f"    {enum_name(role['role'])}(\"{role['role']}\"){suffix}")
    lines.extend(
        [
            "}",
            "",
            "internal enum class StemwiseGlyphElement { GROUP, PATH, RECT, CIRCLE, ELLIPSE, LINE }",
            "internal enum class StemwiseStrokeLineCap { BUTT, ROUND, SQUARE }",
            "internal enum class StemwiseStrokeLineJoin { MITER, ROUND, BEVEL }",
            "",
            "@Immutable",
            "internal data class StemwiseGlyphTransform(",
            "    val translationX: Float,",
            "    val translationY: Float,",
            "    val scaleX: Float,",
            "    val scaleY: Float,",
            "    val rotation: Float,",
            "    val pivotX: Float,",
            "    val pivotY: Float,",
            "    val a: Float,",
            "    val b: Float,",
            "    val c: Float,",
            "    val d: Float,",
            "    val e: Float,",
            "    val f: Float",
            ")",
            "",
            "@Immutable",
            "internal data class StemwiseGlyphLayer(",
            "    val order: Int,",
            "    val depth: Int,",
            "    val element: StemwiseGlyphElement,",
            "    val pathData: String,",
            "    val fill: Boolean,",
            "    val stroke: Boolean,",
            "    val strokeWidth: Float,",
            "    val strokeLineCap: StemwiseStrokeLineCap,",
            "    val strokeLineJoin: StemwiseStrokeLineJoin,",
            "    val transform: StemwiseGlyphTransform",
            ")",
            "",
            "@Immutable",
            "internal data class StemwiseGlyph(",
            "    val role: StemwiseIconRole,",
            "    val rtlMirror: Boolean,",
            "    val layers: List<StemwiseGlyphLayer>",
            ")",
            "",
            "internal object StemwiseGeneratedGlyphCatalog {",
            "    val all: List<StemwiseGlyph> = listOf(",
        ]
    )
    for role_index, role in enumerate(roles):
        lines.extend(
            [
                "        StemwiseGlyph(",
                f"            role = StemwiseIconRole.{enum_name(role['role'])},",
                f"            rtlMirror = {str(role['rtl'] == 'mirror').lower()},",
                "            layers = listOf(",
            ]
        )
        for layer_index, layer in enumerate(role["layers"]):
            suffix = "," if layer_index < len(role["layers"]) - 1 else ""
            lines.append(layer_line(role, layer) + suffix)
        lines.extend(
            [
                "            )",
                "        )" + ("," if role_index < len(roles) - 1 else ""),
            ]
        )
    lines.extend(
        [
            "    )",
            "",
            "    init {",
            "        check(all.size == StemwiseIconRole.entries.size)",
            "        all.forEachIndexed { index, glyph ->",
            "            check(glyph.role.ordinal == index)",
            "            check(glyph.layers.indices.all { glyph.layers[it].order == it })",
            "        }",
            "    }",
            "",
            "    fun glyph(role: StemwiseIconRole): StemwiseGlyph = all[role.ordinal]",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def source_icon_roles() -> list[dict[str, object]]:
    """Every selectable source has its own glyph, using the shared checked layer renderer."""
    source_catalog = PROJECT / "audio-engine/src/main/java/app/stemwise/model/StemSourceCatalogue.kt"
    expected = set(re.findall(r'"([a-z-]+)" to "', source_catalog.read_text(encoding="utf-8")))
    expected = (expected | {"remaining"}) - {"vocals", "drums", "bass", "guitar", "piano"}
    root = ET.parse(SOURCE_ICONS).getroot()
    roles = []
    seen = set()
    for symbol in root:
        name = symbol.attrib.get("id")
        if symbol.tag.split("}")[-1] != "symbol" or name not in expected or name in seen:
            raise ValueError("Unexpected or duplicate source icon identity")
        seen.add(name)
        layers = []
        for element in symbol:
            kind = element.tag.split("}")[-1]
            if kind not in SUPPORTED_ELEMENTS or kind == "g":
                raise ValueError(f"Unsupported source icon element: {kind}")
            attributes = dict(element.attrib)
            transform(attributes.get("transform"))
            shape_path(kind, attributes)
            layers.append({"order": len(layers), "depth": 1, "element": kind, "attributes": attributes})
        if not layers:
            raise ValueError(f"Empty source icon: {name}")
        roles.append({"role": f"player.stem.{name}", "family": "stem", "master": f"stemwise/source-{name}",
                      "source": "STEMWISE / 2DP", "svgSha256": hashlib.sha256(ET.tostring(symbol)).hexdigest(),
                      "geometrySha256": hashlib.sha256(ET.tostring(symbol)).hexdigest(),
                      "rtl": "fixed", "rootPaint": {"fill": "none", "stroke": "currentColor",
                      "strokeWidth": 2, "strokeLinecap": "round", "strokeLinejoin": "round"}, "layers": layers})
    if seen != expected:
        raise ValueError(f"Missing source glyphs: {sorted(expected - seen)}")
    return roles


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if checked-in output differs")
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    roles = validate_catalog(catalog)
    roles += source_icon_roles()
    rendered = render(catalog, roles)
    # Android resolves these independently. Keep the approved vector as the single input.
    resources = OUTPUT.parents[6] / "res" / "drawable"
    launcher = resources / "ic_launcher_foreground.xml"
    splash = resources / "ic_splash_mark.xml"
    competing = [path for path in resources.parent.glob("*/ic_launcher_foreground.*") if path != launcher]
    if competing:
        raise ValueError("Competing launcher foreground artwork: " + ", ".join(str(path) for path in competing))
    if args.check:
        if splash.read_bytes() != launcher.read_bytes():
            print(f"Generated splash mark is stale: {splash}", file=sys.stderr)
            return 1
    else:
        splash.write_bytes(launcher.read_bytes())
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != rendered:
            print(f"Generated catalog is stale: {OUTPUT}", file=sys.stderr)
            return 1
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(rendered, encoding="utf-8", newline="\n")
    print(
        "StemWise icon catalog OK: "
        f"{len(roles)} roles, {len({role['master'] for role in roles})} masters, "
        f"{len({role['family'] for role in roles})} families, {len(roles) - len({role['master'] for role in roles})} reductions"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
