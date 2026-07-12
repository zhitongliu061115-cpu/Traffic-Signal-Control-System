"""
从上海 6横×8纵 主干道路网生成 CityFlow roadnet
步骤：地理编码 48 路口 → 路径规划 → 构建 roadnet
"""
import json, time, urllib.request, urllib.parse, sys
from pathlib import Path

AMAP_KEY = "4ab584658b1cdc916345e3c20bc15add"
OUT_DIR = Path(__file__).resolve().parent.parent / "sim-python" / "data" / "shanghai_expanded"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# ================================================================
# 1. 路网定义：6 横 × 8 纵
# ================================================================
H_ROADS = ["北京路", "南京路", "淮海路", "建国路", "复兴路", "徐家汇路"]
V_ROADS = ["江苏路", "华山路", "常熟路", "瑞金路", "黄陂路", "西藏路", "河南路", "四川路"]

# 已有 12 个路口坐标（从数据库 seed 直接拿）
# 已有 12 个路口坐标（key=(col,row)，0-based）
# col: 0=江苏路 1=华山路 2=常熟路 3=瑞金路 4=黄陂路 5=西藏路 6=河南路 7=四川路
# row: 0=北京路 1=南京路 2=淮海路 3=建国路 4=复兴路 5=徐家汇路
EXISTING = {
    (5,1): ("A01", 121.475600, 31.235600),  # 南京路-西藏路
    (4,1): ("A02", 121.471000, 31.233500),  # 南京路-黄陂路
    (3,1): ("A03", 121.461200, 31.231800),  # 南京路-瑞金路
    (2,1): ("A04", 121.451800, 31.230500),  # 南京路-常熟路
    (5,2): ("A05", 121.477000, 31.227500),  # 淮海路-西藏路
    (4,2): ("A06", 121.472500, 31.225500),  # 淮海路-黄陂路
    (3,2): ("A07", 121.463200, 31.223800),  # 淮海路-瑞金路
    (2,2): ("A08", 121.453000, 31.222500),  # 淮海路-常熟路
    (5,3): ("A09", 121.477800, 31.220800),  # 建国路-西藏路
    (4,3): ("A10", 121.473000, 31.219000),  # 建国路-黄陂路
    (3,3): ("A11", 121.464000, 31.216000),  # 建国路-瑞金路
    (2,3): ("A12", 121.453800, 31.214500),  # 建国路-常熟路
}

def progress(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)

# ================================================================
# 2. 高德地理编码 → 48 个路口坐标
# ================================================================
def geocode(address):
    query = urllib.parse.urlencode({"address": address, "city": "上海", "key": AMAP_KEY})
    url = f"https://restapi.amap.com/v3/geocode/geo?{query}"
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            data = json.loads(resp.read().decode())
        if data.get("status") == "1" and data.get("geocodes"):
            loc = data["geocodes"][0]["location"]
            lng, lat = loc.split(",")
            return float(lng), float(lat)
    except Exception as e:
        print(f"  geocode error: {e}")
    return None

progress("地理编码 48 个路口...")
intersections = []  # list of (row, col, lng, lat, name)
for row_idx, h in enumerate(H_ROADS):
    for col_idx, v in enumerate(V_ROADS):
        name = f"{h}-{v}"
        # 先用已有坐标
        existing = EXISTING.get((col_idx, row_idx))
        if existing:
            lng, lat = existing[1], existing[2]
            print(f"  [{row_idx},{col_idx}] {name} -> (existing) {lng:.4f}, {lat:.4f}")
        else:
            addr = f"上海市{h}与{v}交叉口"
            result = geocode(addr)
            if result:
                lng, lat = result
                print(f"  [{row_idx},{col_idx}] {name} -> {lng:.4f}, {lat:.4f}")
            else:
                print(f"  [{row_idx},{col_idx}] {name} -> NOT FOUND, skip")
                continue
            time.sleep(0.6)  # API rate limit
        intersections.append((row_idx, col_idx, lng, lat, name))

# 过滤：上海范围 + 去重
VALID_LNG = (121.40, 121.55)
VALID_LAT = (31.18, 31.28)
filtered = []
for it in intersections:
    r, c, lng, lat, name = it
    if not (VALID_LNG[0] < lng < VALID_LNG[1] and VALID_LAT[0] < lat < VALID_LAT[1]):
        print(f"  FILTERED (out of bounds): {name} lng={lng:.4f} lat={lat:.4f}")
        continue
    filtered.append(it)
# 去重：同一行列只保留一个（优先级：existing > 第一个）
seen_grid = {}
seen_coord = {}
deduped = []
for it in filtered:
    r, c, lng, lat, name = it
    key = (r, c)
    if key in seen_grid:
        print(f"  FILTERED (dup grid): {name}")
        continue
    coord_key = (round(lng, 4), round(lat, 4))
    if coord_key in seen_coord and not EXISTING.get((c, r)):
        print(f"  FILTERED (dup coord): {name}")
        continue
    seen_grid[key] = it
    seen_coord[coord_key] = it
    deduped.append(it)
intersections = deduped

progress(f"地理编码完成：{len(intersections)} 个有效路口")

# ================================================================
# 3. 路径规划 → 相邻路口之间的道路
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

# 建立索引：(row, col) -> intersection
grid = {}
for it in intersections:
    grid[(it[0], it[1])] = it

progress("路径规划相邻路口之间的道路...")
roads = []
road_id_counter = 0
pair_requests = 0
# 横向道路：同一行相邻列之间
for row_idx in range(len(H_ROADS)):
    for col_idx in range(len(V_ROADS) - 1):
        a = grid.get((row_idx, col_idx))
        b = grid.get((row_idx, col_idx + 1))
        if not a or not b: continue
        pair_requests += 1
        path = driving_path((a[2], a[3]), (b[2], b[3]))
        if path:
            road_id = f"road_{row_idx}_{col_idx}_h"
            roads.append({
                "id": road_id,
                "from_col": col_idx, "from_row": row_idx,
                "to_col": col_idx + 1, "to_row": row_idx,
                "points": path,
            })
            print(f"  H {a[4]} -> {b[4]}: {len(path)} pts")
        else:
            print(f"  H {a[4]} -> {b[4]}: FAILED")
        time.sleep(0.6)

# 纵向道路：同一列相邻行之间
for col_idx in range(len(V_ROADS)):
    for row_idx in range(len(H_ROADS) - 1):
        a = grid.get((row_idx, col_idx))
        b = grid.get((row_idx + 1, col_idx))
        if not a or not b: continue
        pair_requests += 1
        path = driving_path((a[2], a[3]), (b[2], b[3]))
        if path:
            road_id = f"road_{row_idx}_{col_idx}_v"
            roads.append({
                "id": road_id,
                "from_col": col_idx, "from_row": row_idx,
                "to_col": col_idx, "to_row": row_idx + 1,
                "points": path,
            })
            print(f"  V {a[4]} -> {b[4]}: {len(path)} pts")
        else:
            print(f"  V {a[4]} -> {b[4]}: FAILED")
        time.sleep(0.6)

progress(f"路径规划完成：{len(roads)} 条道路 ({pair_requests} 对)")

# ================================================================
# 4. 保存原始数据（带 lng/lat，供前端可视化）
# ================================================================
raw = {
    "intersections": [
        {"id": f"intersection_{r}_{c}", "row": r, "col": c,
         "name": name, "lng": lng, "lat": lat}
        for r, c, lng, lat, name in intersections
    ],
    "roads": roads,
    "hRoads": H_ROADS,
    "vRoads": V_ROADS,
}
with open(OUT_DIR / "raw_network.json", "w", encoding="utf-8") as f:
    json.dump(raw, f, ensure_ascii=False, indent=2)
progress(f"原始路网已保存: {OUT_DIR / 'raw_network.json'}")

# ================================================================
# 5. 生成 CityFlow roadnet（完整格式，可直接注册到 scenes.json）
# ================================================================
# intersection_id 格式: intersection_R_C (R=col 1-based, C=row 1-based)
cf_its = []
cf_roads = []
cf_road_links = []
cf_phases = []

# 路口
for r, c, lng, lat, name in intersections:
    conn_count = 0
    for rd in roads:
        if (rd["from_col"] == c and rd["from_row"] == r) or (rd["to_col"] == c and rd["to_row"] == r):
            conn_count += 1
    virtual = conn_count < 2
    cf_its.append({
        "id": f"intersection_{c+1}_{r+1}",
        "point": {"x": lng * 10000, "y": lat * 10000},
        "width": 0,
        "roads": [],
        "roadLinks": [],
        "trafficLight": {
            "roadLinkIndices": [],
            "lightphases": [
                {"time": 5 if i == 0 else 30, "availableRoadLinks": []}
                for i in range(9)
            ],
        },
        "virtual": virtual,
    })

# 道路映射: road id → index
road_id_to_idx = {}
for idx, rd in enumerate(roads):
    road_id_to_idx[rd["id"]] = idx
    fc, fr = rd["from_col"], rd["from_row"]
    tc, tr = rd["to_col"], rd["to_row"]
    cf_roads.append({
        "id": f"road_{fr}_{fc}_{idx}",
        "startIntersection": f"intersection_{fc+1}_{fr+1}",
        "endIntersection": f"intersection_{tc+1}_{tr+1}",
        "points": rd["points"],
        "lanes": [
            {"width": 3.2, "maxSpeed": 16.67},
            {"width": 3.2, "maxSpeed": 16.67},
            {"width": 3.2, "maxSpeed": 16.67},
        ],
    })

# 每个路口的 roadLinks
for it_idx, (r, c, _, _, _) in enumerate(intersections):
    connected = []
    for rd_idx, rd in enumerate(roads):
        if (rd["from_col"] == c and rd["from_row"] == r) or (rd["to_col"] == c and rd["to_row"] == r):
            connected.append(rd_idx)
    # 填充路口 roads 字段
    cf_its[it_idx]["roads"] = [f"road_{rd['from_row']}_{rd['from_col']}_{i}" for i, rd in enumerate(roads) if (rd["from_col"] == c and rd["from_row"] == r) or (rd["to_col"] == c and rd["to_row"] == r)]

    if cf_its[it_idx]["virtual"]:
        continue

    link_idx = 0
    for ri in connected:
        rd_i = roads[ri]
        for rj in connected:
            if ri == rj: continue
            rd_j = roads[rj]
            # 判断转向
            # 进入方向
            if rd_i["to_col"] == c and rd_i["to_row"] == r:
                in_dc, in_dr = rd_i["to_col"] - rd_i["from_col"], rd_i["to_row"] - rd_i["from_row"]
            else:
                in_dc, in_dr = rd_i["from_col"] - rd_i["to_col"], rd_i["from_row"] - rd_i["to_row"]
            # 离开方向
            if rd_j["from_col"] == c and rd_j["from_row"] == r:
                out_dc, out_dr = rd_j["to_col"] - rd_j["from_col"], rd_j["to_row"] - rd_j["from_row"]
            else:
                out_dc, out_dr = rd_j["from_col"] - rd_j["to_col"], rd_j["from_row"] - rd_j["to_row"]
            # 转向类型
            cross = in_dc * out_dr - in_dr * out_dc
            dot = in_dc * out_dc + in_dr * out_dr
            if dot > 0 and abs(cross) < 0.5:
                t = "go_straight"
            elif cross > 0:
                t = "turn_left"
            else:
                t = "turn_right"

            cf_its[it_idx]["roadLinks"].append({
                "index": link_idx,
                "startRoad": f"road_{rd_i['from_row']}_{rd_i['from_col']}_{ri}",
                "endRoad": f"road_{rd_j['from_row']}_{rd_j['from_col']}_{rj}",
                "type": t,
                "viaLaneLinkId": None,
            })
            link_idx += 1

    cf_its[it_idx]["trafficLight"]["roadLinkIndices"] = list(range(link_idx))

# 生成 flow
flows = []
for rd in roads:
    flows.append({
        "vehicle": {
            "length": 5.0, "width": 2.0, "maxPosAcc": 2.0, "maxNegAcc": 4.5,
            "usualPosAcc": 2.0, "usualNegAcc": 4.5, "minGap": 2.5,
            "maxSpeed": 16.67, "headwayTime": 1.5,
        },
        "route": [f"road_{rd['from_row']}_{rd['from_col']}_{road_id_to_idx[rd['id']]}"],
        "interval": 3.0,
        "startTime": 0,
        "endTime": -1,
    })

roadnet = {"intersections": cf_its, "roads": cf_roads}
with open(OUT_DIR / "roadnet.json", "w", encoding="utf-8") as f:
    json.dump(roadnet, f, ensure_ascii=False, indent=2)
with open(OUT_DIR / "flow.json", "w", encoding="utf-8") as f:
    json.dump(flows, f, ensure_ascii=False, indent=2)

progress(f"CityFlow roadnet 已生成: {OUT_DIR}")
progress(f"路口: {len(cf_its)} (real: {sum(1 for i in cf_its if not i['virtual'])}), 道路: {len(cf_roads)}, roadLinks: {len(cf_road_links)}")
