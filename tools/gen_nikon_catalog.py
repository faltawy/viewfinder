#!/usr/bin/env python3
"""
Generates ptp/src/main/kotlin/app/viewfinder/ptp/vendor/nikon/NikonCatalog.kt from libgphoto2's
camlibs/ptp2/ptp.h and config.c: names for every Nikon operation, event, response and property,
plus the value tables gphoto2 uses to label property values.

    python3 -I tools/gen_nikon_catalog.py /path/to/ptp.h /path/to/config.c > NikonCatalog.kt

libgphoto2 is LGPL-2.1; these are its identifier names and value labels.
"""
import re
import sys

ptp_h, config_c = sys.argv[1], sys.argv[2]
header = open(ptp_h, encoding="utf-8", errors="ignore").read()
config = open(config_c, encoding="utf-8", errors="ignore").read()

defines = {}
for name, value in re.findall(r"#define\s+(PTP_[A-Z]+_\w+)\s+(0x[0-9A-Fa-f]+)\b", header):
    defines[name] = int(value, 16)


def humanize(ident: str) -> str:
    """ActiveDLighting -> Active D-Lighting; ISO_Auto -> ISO auto; WhiteBalanceAutoBias -> White balance auto bias."""
    special = {"ActiveDLighting": "Active D-Lighting", "DLighting": "D-Lighting"}
    for k, v in special.items():
        ident = ident.replace(k, v.replace(" ", "\0"))
    words = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_", " ", ident).split()
    out = []
    for i, w in enumerate(words):
        w = w.replace("\0", " ")
        keep = w.isupper() or any(c.isdigit() for c in w) or "-" in w
        out.append(w if keep or i == 0 else w.lower())
    return " ".join(out).strip()


def kstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


# Value tables: static struct deviceproptableXX name[] = { { N_("label"), value, flag }, ... };
tables = {}
for kind, tname, body in re.findall(r"static\s+struct\s+deviceproptable(\w+)\s+(\w+)\[\]\s*=\s*\{(.*?)\};", config, re.S):
    entries = {}
    for m in re.finditer(r'\{\s*(?:N_\(\s*"((?:[^"\\]|\\.)*)"\s*\)|"((?:[^"\\]|\\.)*)")\s*,\s*([^,{}]+?)\s*,\s*([^{}]+?)\s*\}', body):
        label = m.group(1) if m.group(1) is not None else m.group(2)
        flag = m.group(4).strip()
        if flag not in ("0", "PTP_VENDOR_NIKON"):
            continue  # entries limited to one model or another vendor
        try:
            value = int(m.group(3).strip().rstrip("uUlL"), 0)
        except ValueError:
            continue
        if kind.startswith("u") and value < 0:
            continue
        entries.setdefault(value, label.replace('\\"', '"'))
    if entries:
        tables[tname] = entries

# GENERIC8TABLE(Getter, table) and friends tie a _get_Getter function to a table.
getter_table = {}
for getter, tname in re.findall(r"GENERIC(?:I?8|I?16|I?32)TABLE\(\s*(\w+)\s*,\s*(\w+)\s*\)", config):
    getter_table["_get_" + getter] = tname

# Menu entries: { N_("Label"), "name", PTP_DPC_..., PTP_VENDOR_..., PTP_DTC_..., _get_..., _put_... }
labels, prop_tables = {}, {}
for label, dpc, vendor, getter in re.findall(
    r'\{\s*N_\("([^"]+)"\)\s*,\s*"[\w-]+"\s*,\s*(PTP_DPC_\w+)\s*,\s*(PTP_VENDOR_\w+|0)\s*,\s*PTP_DTC_\w+\s*,\s*(_get_\w+)', config
):
    if dpc not in defines or vendor not in ("PTP_VENDOR_NIKON", "0"):
        continue
    code = defines[dpc]
    if not (dpc.startswith("PTP_DPC_NIKON_") or code < 0xD000):
        continue
    if vendor == "PTP_VENDOR_NIKON" or code not in labels:
        labels.setdefault(code, label)
    tname = getter_table.get(getter)
    if tname in tables and tables[tname] not in prop_tables.setdefault(code, []):
        prop_tables[code].append(tables[tname])

prop_names = {}
for name, code in defines.items():
    if name.startswith("PTP_DPC_NIKON_"):
        prop_names.setdefault(code, labels.get(code) or humanize(name[len("PTP_DPC_NIKON_"):]))
for code, label in labels.items():
    prop_names.setdefault(code, label)


def section(prefix):
    out = {}
    for name, code in defines.items():
        if name.startswith(prefix):
            out.setdefault(code, humanize(name[len(prefix):]))
    return out


ops = section("PTP_OC_NIKON_")
events = section("PTP_EC_NIKON_")
events.update({k: v for k, v in section("PTP_EC_Nikon_").items() if k not in events})
responses = section("PTP_RC_NIKON_")


def map_chunks(fn_prefix, items, render, chunk=150):
    """Splits big maps across functions: one JVM method can't hold thousands of entries."""
    items = sorted(items)
    lines, names = [], []
    for i in range(0, len(items), chunk):
        fname = f"{fn_prefix}{i // chunk}"
        names.append(fname)
        lines.append(f"    private fun {fname}(m: MutableMap<Int, *>) {{")
        lines.append("        @Suppress(\"UNCHECKED_CAST\")")
        lines.append(f"        m as MutableMap<Int, Any>")
        for k, v in items[i:i + chunk]:
            lines.append(f"        m[0x{k:04X}] = {render(v)}")
        lines.append("    }")
    return lines, names


def render_tables(tbls):
    parts = []
    for t in tbls:
        entries = ", ".join(f"{v}L to {kstr(l)}" for v, l in sorted(t.items()))
        parts.append(f"mapOf({entries})")
    return "listOf(" + ", ".join(parts) + ")"


out = []
out.append("// Generated by tools/gen_nikon_catalog.py from libgphoto2 (camlibs/ptp2/ptp.h, config.c; LGPL-2.1). Do not edit.")
out.append("package app.viewfinder.ptp.vendor.nikon")
out.append("")
out.append("/** Names for every Nikon PTP code libgphoto2 knows, and the value labels it uses per property. */")
out.append("internal object NikonCatalog {")
blocks = [
    ("propNames", "String", prop_names, kstr),
    ("propTables", "List<Map<Long, String>>", prop_tables, render_tables),
    ("operationNames", "String", ops, kstr),
    ("eventNames", "String", events, kstr),
    ("responseNames", "String", responses, kstr),
]
bodies = []
for name, vtype, data, render in blocks:
    lines, fns = map_chunks("fill" + name[0].upper() + name[1:], data.items(), render)
    calls = " ".join(f"{f}(it);" for f in fns)
    out.append(f"    val {name}: Map<Int, {vtype}> by lazy {{ HashMap<Int, {vtype}>({len(data) * 2}).also {{ {calls} }} }}")
    bodies.extend(lines)
out.append("")
out.extend(bodies)
out.append("}")
print("\n".join(out))
print(f"// {len(prop_names)} properties, {len(prop_tables)} with value tables, {len(ops)} operations, {len(events)} events, {len(responses)} responses", file=sys.stderr)
