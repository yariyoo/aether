# builds the dwarven mines route graph from aether gameplay recordings (/aether record)
# usage: python3 tools/build_dwarven_routes.py rec1.log [rec2.log ...] > src/main/resources/assets/aether/routes/dwarven_mines.json
import bisect
import json
import math
import re
import sys

MERGE_H = 1.5
MERGE_V = 1.0
SAMPLE_H = 3.0
SAMPLE_V = 1.5
LINK_H = 2.5
LINK_V = 0.6
DROP = 1.5

nodes = []
grid = {}
edges = {}
forge = set()


def node(x, y, z):
    gx, gz = int(x // 4), int(z // 4)
    best, best_d = None, None
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            for i in grid.get((gx + dx, gz + dz), ()):
                nx, ny, nz = nodes[i]
                h = math.hypot(nx - x, nz - z)
                if h <= MERGE_H and abs(ny - y) <= MERGE_V and (best_d is None or h < best_d):
                    best, best_d = i, h
    if best is not None:
        return best
    nodes.append((round(x, 2), round(y, 2), round(z, 2)))
    grid.setdefault((gx, gz), []).append(len(nodes) - 1)
    return len(nodes) - 1


def walk(a, b, jumped):
    if a == b:
        return
    ay, by = nodes[a][1], nodes[b][1]
    for s, t in ((a, b), (b, a)):
        # dropping down a ledge can't be walked back up
        if nodes[t][1] > nodes[s][1] + DROP:
            continue
        key = (s, t, 'WALK')
        if key not in edges:
            edges[key] = {'a': s, 'b': t, 'type': 'WALK', 'jump': jumped}
        elif jumped:
            edges[key]['jump'] = True


def hop(a, b, kind, yaw, pitch, frm, to):
    key = (a, b, kind)
    if key in edges:
        edges[key]['seen'] += 1
        return
    edges[key] = {'a': a, 'b': b, 'type': kind, 'yaw': yaw, 'pitch': pitch,
                  'from': [round(v, 2) for v in frm], 'to': [round(v, 2) for v in to], 'seen': 1}


def process(path):
    ticks, uses, warps = [], [], []
    for line in open(path, encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) >= 9 and p[0] == 'T':
            ticks.append((int(p[1]), float(p[2]), float(p[3]), float(p[4]), p[7]))
        elif len(p) > 2 and p[0] == 'E':
            if p[2] == 'use' and 'Aspect of the' in line:
                m = re.search(r'looking (-?[\d.]+)/(-?[\d.]+)', line)
                uses.append((int(p[1]), float(m.group(1)), float(m.group(2))))
            elif p[2] == 'command' and len(p) > 4 and p[3] == '/warp' and p[4] == 'forge':
                warps.append(int(p[1]))
    times = [t[0] for t in ticks]

    def at(ms):
        return ticks[max(bisect.bisect_right(times, ms) - 1, 0)]

    used = set()
    last = None
    jumped = False
    skip_until = -1
    for i, t in enumerate(ticks):
        pos = t[1:4]
        if i > 0 and math.dist(ticks[i - 1][1:4], pos) > 2.5:
            ms = t[0]
            cands = [k for k, u in enumerate(uses) if k not in used and ms - 800 <= u[0] <= ms]
            if cands:
                k = cands[-1]
                used.add(k)
                u = uses[k]
                start = at(u[0])
                kind = 'ETHERWARP' if 'N' in start[4] else 'AOTV'
                a = node(*start[1:4])
                if last is not None:
                    walk(last, a, jumped)
                b = node(*pos)
                hop(a, b, kind, u[1], u[2], start[1:4], pos)
            else:
                b = node(*pos)
                if any(w <= ms <= w + 8000 for w in warps):
                    forge.add(b)
            last, jumped = b, False
            continue
        if 'J' in t[4]:
            jumped = True
        if last is None:
            last = node(*pos)
            continue
        lx, ly, lz = nodes[last]
        if math.hypot(pos[0] - lx, pos[2] - lz) >= SAMPLE_H or abs(pos[1] - ly) >= SAMPLE_V:
            n = node(*pos)
            walk(last, n, jumped)
            last, jumped = n, False


for f in sys.argv[1:]:
    process(f)

# join traces that pass right next to each other
for (gx, gz), members in list(grid.items()):
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            for i in members:
                for j in grid.get((gx + dx, gz + dz), ()):
                    if i >= j:
                        continue
                    a, b = nodes[i], nodes[j]
                    if math.hypot(a[0] - b[0], a[2] - b[2]) <= LINK_H and abs(a[1] - b[1]) <= LINK_V:
                        walk(i, j, False)

out = {'version': 1, 'nodes': [list(n) for n in nodes], 'forge': sorted(forge),
       'edges': sorted(edges.values(), key=lambda e: (e['a'], e['b'], e['type']))}
json.dump(out, sys.stdout, separators=(',', ':'))
from collections import Counter
print(f"{len(nodes)} nodes, {Counter(e['type'] for e in edges.values())}, forge {sorted(forge)}", file=sys.stderr)
