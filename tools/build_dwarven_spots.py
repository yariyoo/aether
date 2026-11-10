# lists where a recorded run stood while breaking ore, as seed spots for the miner's vein memory
# usage: python3 tools/build_dwarven_spots.py rec1.log [...] > src/main/resources/assets/aether/routes/dwarven_spots.json
import bisect
import json
import math
import sys

ORE = ('gray_wool', 'cyan_terracotta', 'prismarine', 'prismarine_bricks', 'dark_prismarine', 'light_blue_wool',
       'polished_diorite')
counts = {}
for path in sys.argv[1:]:
    ticks = []
    breaks = []
    for line in open(path, encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) >= 9 and p[0] == 'T':
            ticks.append((int(p[1]), float(p[2]), float(p[3]), float(p[4])))
        elif len(p) >= 5 and p[0] == 'E' and p[2] == 'blockchange':
            before, _, after = p[4].partition('->')
            if before in ORE and after == 'bedrock':
                breaks.append(int(p[1]))
    times = [t[0] for t in ticks]
    for ms in breaks:
        i = bisect.bisect_right(times, ms) - 1
        if i < 0:
            continue
        _, x, y, z = ticks[i]
        key = (math.floor(x), math.floor(y + 0.01), math.floor(z))
        counts[key] = counts.get(key, 0) + 1
spots = [{'pos': list(k), 'mined': v} for k, v in sorted(counts.items(), key=lambda kv: -kv[1]) if v >= 3]
json.dump({'version': 1, 'spots': spots}, sys.stdout, separators=(',', ':'))
print(f'{len(spots)} spots from {sum(counts.values())} breaks', file=sys.stderr)
