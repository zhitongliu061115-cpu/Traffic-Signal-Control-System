"""
用调整后的路口坐标重新跑路径规划，生成最终 CityFlow roadnet
"""
import json, time, urllib.request, urllib.parse
from pathlib import Path

AMAP_KEY = "4ab584658b1cdc916345e3c20bc15add"
ADJUSTED_FILE = Path.home() / "Desktop" / "shanghai_adjusted (1).json"
OUT_DIR = Path(__file__).resolve().parent.parent / "sim-python" / "data" / "shanghai_final"
OUT_DIR.mkdir(parents=True, exist_ok=True)

def progress(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)

# 加载调整后的坐标
with open(ADJUSTED_FILE, encoding='utf-8') as f:
    data = json.load(f)

intersections = data['intersections']  # [{row, col, lng, lat, name}]

# 建立网格索引
grid = {}
for it in intersections:
    grid[(it['row'], it['col'])] = it

progress(f"加载 {len(intersections)} 个路口")

# ================================================================
# 路径规划
# ================================================================
def driving_path(origin, dest):
    query = urllib.parse.urlencode({
        "origin": f"{origin[0]},{origin[1]}",
        "destination": f"{dest[0]},{dest[1]}",
        "extensions": "all",
        "key": AMAP_KEY,
    })
    url = f"https://restapi.amap.com/v3/direction/driving?{query}"
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            data = json.loads(resp.read().decode())
        if data.get("status") == "1" and data.get("route", {}).get("paths"):
            steps = data["route"]["paths"][0]["steps"]
            path = []
            for step in steps:
                if not step.get("polyline"): continue
                for p in step["polyline"].split(";"):
                    lng, lat = p.split(",")
                    path.append({"x": float(lng), "y": float(lat)})
            if len(path) >= 2:
                return path
    except Exception as e:
        print(f"  path error: {e}")
    return None

# 确定行列范围
rows = sorted(set(it['row'] for it in intersections))
cols = sorted(set(it['col'] for it in intersections))

progress(f"行列范围: rows {rows}, cols {cols}")
progress("路径规划中...")

roads = []
# 横向：同行相邻列
for row in rows:
    for ci in range(len(cols) - 1):
        c1, c2 = cols[ci], cols[ci + 1]
        a = grid.get((row, c1))
        b = grid.get((row, c2))
        if not a or not b: continue
        path = driving_path((a['lng'], a['lat']), (b['lng'], b['lat']))
        if path:
            roads.append({"id": f"road_{row}_{c1}_h", "from_col": c1, "from_row": row, "to_col": c2, "to_row": row, "points": path})
            print(f"  H {a['name']} -> {b['name']}: {len(path)} pts")
        else:
            print(f"  H {a['name']} -> {b['name']}: FAILED")
        time.sleep(0.6)

# 纵向：同列相邻行
for col in cols:
    for ri in range(len(rows) - 1):
        r1, r2 = rows[ri], rows[ri + 1]
        a = grid.get((r1, col))
        b = grid.get((r2, col))
        if not a or not b: continue
        path = driving_path((a['lng'], a['lat']), (b['lng'], b['lat']))
        if path:
            roads.append({"id": f"road_{r1}_{col}_v", "from_col": col, "from_row": r1, "to_col": col, "to_row": r2, "points": path})
            print(f"  V {a['name']} -> {b['name']}: {len(path)} pts")
        else:
            print(f"  V {a['name']} -> {b['name']}: FAILED")
        time.sleep(0.6)

progress(f"路径规划完成: {len(roads)} 条道路")

# ================================================================
# 统计每个路口的道路数
# ================================================================
conn = {}
for r in roads:
    k1 = (r['from_row'], r['from_col'])
    k2 = (r['to_row'], r['to_col'])
    conn[k1] = conn.get(k1, 0) + 1
    conn[k2] = conn.get(k2, 0) + 1

# 筛选四向通达的路口
valid_its = []
for it in intersections:
    k = (it['row'], it['col'])
    n = conn.get(k, 0)
    it['road_count'] = n
    valid_its.append(it)
    status = 'OK' if n >= 4 else f'({n} roads)'
    print(f"  [{it['row']},{it['col']}] {it['name']:20s} roads={n} {status}".encode('ascii','replace').decode())

# ================================================================
# 保存结果
# ================================================================
result = {
    "intersections": valid_its,
    "roads": roads,
}
with open(OUT_DIR / "raw_network.json", "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
progress(f"已保存: {OUT_DIR / 'raw_network.json'}")
four_way = sum(1 for i in valid_its if i.get('road_count',0) >= 4)
progress(f"intersections: {len(valid_its)} | roads: {len(roads)} | 4-way: {four_way}")
