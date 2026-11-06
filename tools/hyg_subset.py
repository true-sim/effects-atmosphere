"""Derives the bundled star catalogue from the HYG database (CC BY-SA 4.0, see sky/.../stars/LICENSE-HYG.md).

Usage: python -I tools/hyg_subset.py <hygdata_v41.csv> <out.csv> [limiting magnitude, default 7.0]

Keeps every star of visual magnitude <= the limit except the Sun (HYG id 0), sorted by HYG id, with the columns
hyg,hip,hr,bf,proper,con,ra,dec,pmra,pmdec,mag,ci as HYG defines them (ra in hours, dec in degrees, both J2000.0;
proper motions in milliarcseconds per year; ci is B-V, empty when unknown). Values are copied as text, unchanged.
"""
import csv
import sys

COLUMNS = ["id", "hip", "hr", "bf", "proper", "con", "ra", "dec", "pmra", "pmdec", "mag", "ci"]


def main(source, target, limit):
    with open(source, newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["id"] != "0" and float(r["mag"]) <= limit]
    rows.sort(key=lambda r: int(r["id"]))
    with open(target, "w", newline="\n", encoding="utf-8") as out:
        out.write("hyg,hip,hr,bf,proper,con,ra,dec,pmra,pmdec,mag,ci\n")
        for r in rows:
            fields = [r[c].strip().replace(",", " ") for c in COLUMNS]
            out.write(",".join(fields) + "\n")
    print(len(rows), "stars")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 7.0)
