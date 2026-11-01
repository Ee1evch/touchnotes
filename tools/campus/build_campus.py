#!/usr/bin/env python3
"""
Готовит карту кампуса для «Наставника» из данных навигатора ГМЦ (папка assets его APK).

Берёт только то, что нужно карте в приложении, и ужимает:
  * геометрия этажей — целые 16-битные координаты с шагом 2,5 см вместо float32,
    стены — индексированные треугольники (общие вершины не повторяются);
  * сетка проходимости 0,3 м — построчно сжатые серии (номер помещения, длина) вместо 11 МБ на этаж;
  * Wi-Fi-отпечатки — компактный двоичный файл вместо JSON;
  * отбрасываются записи прогулок (replay), автомобильные голосовые подсказки, отладочные правки.

Запуск: python3 build_campus.py <assets навигатора> <app/src/main/assets/campus>
"""
import json
import os
import struct
import sys

import numpy as np

UNIT = 0.025  # метра на единицу координат
KINDS = {"room_xy": 1, "wall_xy": 2, "door_xy": 3, "stair_xy": 4, "furn_xy": 5, "roof_xy": 6, "fence_xy": 7, "path_xy": 8}
GENERIC = {"Открытое пространство", "Помещение без номера", "Проход", "Тамбур", ""}


def load_floor(path):
    b = open(path, "rb").read()
    n = struct.unpack("<I", b[:4])[0]
    head = json.loads(b[4:4 + n])
    base = len(b) - sum(e["len"] for e in head)
    out = {}
    for e in head:
        a = np.frombuffer(b[base + e["off"]:base + e["off"] + e["len"]], dtype=e["dtype"])
        out[e["k"]] = a.reshape(e["shape"])
    return out


def varint(v):
    out = bytearray()
    while True:
        byte = v & 0x7F
        v >>= 7
        if v:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def quant(xy, center):
    q = np.round((xy.astype(np.float64) + np.asarray(center, dtype=np.float64)) / UNIT)
    assert q.min() >= -32768 and q.max() <= 32767, "координаты не помещаются в int16"
    return q.astype("<i2")


def layer(kind, mode, verts, colors=None, palette=None, idx=None):
    out = bytearray(struct.pack("<BBI", kind, mode, len(verts)))
    out += verts.tobytes()
    if colors is not None:
        out += struct.pack("<BB", 1, len(palette))
        out += palette.astype(np.uint8).tobytes()
        out += colors.astype(np.uint8).tobytes()
    else:
        out += struct.pack("<B", 0)
    if idx is None:
        out += struct.pack("<IB", 0, 0)
    else:
        wide = len(verts) >= 65536
        out += struct.pack("<IB", len(idx), 1 if wide else 0)
        out += idx.astype("<u4" if wide else "<u2").tobytes()
    return bytes(out)


def write_geo(src, dst, center):
    f = load_floor(src)
    layers = []
    if "room_xy" in f and len(f["room_xy"]):
        rgb = f["room_rgb"]
        palette, inv = np.unique(rgb, axis=0, return_inverse=True)
        layers.append(layer(1, 1, quant(f["room_xy"], center), inv.reshape(-1), palette, f["room_idx"]))
    if "wall_xy" in f and len(f["wall_xy"]):
        tri = quant(f["wall_xy"], center)[f["wall_idx"]]
        uniq, inv = np.unique(tri, axis=0, return_inverse=True)
        layers.append(layer(2, 1, uniq.astype("<i2"), idx=inv.reshape(-1)))
    for key in ("door_xy", "stair_xy", "furn_xy", "roof_xy", "fence_xy", "path_xy"):
        if key in f and len(f[key]):
            layers.append(layer(KINDS[key], 0, quant(f[key], center)))
    with open(dst, "wb") as out:
        out.write(b"CGEO" + struct.pack("<Bf", 1, UNIT) + struct.pack("<B", len(layers)))
        for l in layers:
            out.write(l)


def write_nav(src, dst, rows, cols):
    grid = np.fromfile(src, dtype="<u2").reshape(rows, cols)
    out = bytearray(b"CNAV" + struct.pack("<BII", 1, rows, cols))
    for r in range(rows):
        row = grid[r]
        change = np.flatnonzero(np.diff(row)) + 1
        starts = np.concatenate(([0], change))
        ends = np.concatenate((change, [cols]))
        out += varint(len(starts))
        for s, e in zip(starts, ends):
            out += varint(int(row[s])) + varint(int(e - s))
    open(dst, "wb").write(out)


def write_wifi(src, dst):
    w = json.load(open(src))
    fields = w["fields"]
    fi = {k: i for i, k in enumerate(fields)}
    out = bytearray(b"CWIF" + struct.pack("<BI", 1, len(w["bssids"])))
    for b in w["bssids"]:
        out += bytes(int(x, 16) for x in b.split(":"))
    pts = [p for p in w["points"] if p[fi["nScans"]] > 0]
    out += struct.pack("<I", len(pts))
    for p in pts:
        aps = p[fi["ap_idx"]]
        rssi = p[fi["rssi"]]
        pairs = sorted(zip(rssi, aps), reverse=True)[:255]
        out += struct.pack("<bhhB", int(p[fi["floor"]]), round(p[fi["x"]] / UNIT), round(p[fi["y"]] / UNIT), len(pairs))
        for r, a in pairs:
            out += struct.pack("<Hb", a, int(round(max(-127, min(0, r)))))
    open(dst, "wb").write(out)


def build_site(src_dir, out_dir, site_id, title, lat, lon):
    os.makedirs(out_dir, exist_ok=True)
    p = json.load(open(os.path.join(src_dir, "map", "places.json")))
    meta = json.load(open(os.path.join(src_dir, "map", "meta.json")))
    center = p.get("center") or meta.get("center")
    grid = p["grid"]
    restricted = {(a["floor"], a["room"]) for a in p.get("access_rooms", []) if a.get("level", 0) >= 2}
    rooms = []
    for r in p["rooms"]:
        if r.get("void"):
            continue
        nums = [n for n in r.get("nums", []) if n]
        names = [n for n in r.get("names", []) if n]
        func = [n for n in r.get("func", []) if n]
        item = {"id": r["id"], "f": r["floor"], "x": r["pole"][0], "y": r["pole"][1]}
        if nums: item["nums"] = nums
        if names: item["names"] = names
        if func: item["func"] = func
        if r.get("labels"): item["l"] = [[round(a, 2), round(b, 2)] for a, b in r["labels"]]
        if r.get("anchor"): item["a"] = r["anchor"]
        if not r.get("reach", True): item["unreach"] = 1
        if (r["floor"], r["id"]) in restricted: item["key"] = 1
        if r.get("area"): item["area"] = round(r["area"], 1)
        rooms.append(item)
    conns = []
    for c in p.get("conn", []):
        conns.append({"kind": c["kind"], "name": c.get("name", ""), "pos": {k: v for k, v in c["pos"].items()}})
    site = {
        "id": site_id, "title": title, "lat": lat, "lon": lon,
        "grid": grid, "unit": UNIT,
        "floors": [{"n": f["n"], "name": f["name"]} for f in p["floors"]],
        "rooms": rooms, "conns": conns,
    }
    if "geo" in p:
        site["geo"] = p["geo"]["plan_to_lonlat"]
    with open(os.path.join(out_dir, "site.json"), "w") as out:
        json.dump(site, out, ensure_ascii=False, separators=(",", ":"))
    for fl in p["floors"]:
        n = fl["n"]
        write_geo(os.path.join(src_dir, "map", f"floor{n}.bin"), os.path.join(out_dir, f"f{n}.geo"), center)
        write_nav(os.path.join(src_dir, "map", f"nav{n}.u16"), os.path.join(out_dir, f"f{n}.nav"), grid["rows"], grid["cols"])
    wifi = os.path.join(src_dir, "radio", "wifi.json")
    if os.path.exists(wifi):
        write_wifi(wifi, os.path.join(out_dir, "wifi.bin"))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    sites = json.load(open(os.path.join(src, "sites.json")))["sites"]
    index = []
    for s in sites:
        sid = s["id"]
        sdir = os.path.join(src, s["prefix"]) if s["prefix"] else src
        build_site(sdir, os.path.join(dst, sid), sid, s["title"], s["lat"], s["lon"])
        index.append({"id": sid, "title": s["title"], "lat": s["lat"], "lon": s["lon"], "radius": s["radius_m"]})
    with open(os.path.join(dst, "sites.json"), "w") as out:
        json.dump(index, out, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
