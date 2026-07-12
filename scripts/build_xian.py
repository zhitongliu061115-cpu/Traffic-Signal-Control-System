"""西安城墙内4x5=20路口：棋盘格路网"""
import json, time, urllib.request, urllib.parse, os

AMAP_KEY = "4ab584658b1cdc916345e3c20bc15add"

# 西安城墙内棋盘格
# 横(南北) 4条, 纵(东西) 5条
H_ROADS = [
    ["莲湖路", "西五路"],   # 北侧  (莲湖路向西, 西五路向东)
    ["西大街", "东大街"],   # 中心  (钟楼十字)
    ["湘子庙街", "书院门"], # 南中
    ["环城南路", "环城南路"], # 南墙外
]
V_ROADS = [
    "甜水井街",    # 西1
    "北大街",      # 西2 (北大街+南大街)
    "尚德路",      # 中
    "解放路",      # 东1
    "太乙路",      # 东2
]

# 纵向路名拼接(南北向路名可能分北段/南段)
def v_name(col, row):
    v = V_ROADS[col]
    if v == "北大街":
        return "北大街" if row <= 1 else "南大街"
    if v == "尚德路":
        return "尚德路" if row <= 1 else "建国路"
    if v == "解放路":
        return "解放路" if row <= 1 else "和平路"
    return v

def h_name(col, row):
    h = H_ROADS[row]
    if col <= 2: return h[0]  # 西半段
    return h[1]                # 东半段

def geocode(addr):
    q = urllib.parse.urlencode({"address": "西安市{}与{}交叉口".format(addr[0],addr[1]), "city": "西安", "key": AMAP_KEY})
    try:
        with urllib.request.urlopen("https://restapi.amap.com/v3/geocode/geo?" + q, timeout=10) as r:
            d = json.loads(r.read().decode())
        if d.get("status")=="1" and d.get("geocodes"):
            lng, lat = d["geocodes"][0]["location"].split(",")
            return float(lng), float(lat)
    except Exception as e:
        print("  err:", e)
    return None

print("Geocoding Xi'an intersections...")
its = []
for row in range(4):
    for col in range(5):
        vn = v_name(col, row)
        hn = h_name(col, row)
        name = "{}-{}".format(hn, vn)
        addr = (hn, vn)
        result = geocode(addr)
        if result:
            lng, lat = result
            its.append({"row": row, "col": col, "name": name, "lng": lng, "lat": lat})
            print("  [{},{}] {} -> {:.5f}, {:.5f}".format(row, col, name, lng, lat))
        else:
            its.append({"row": row, "col": col, "name": name, "lng": 0, "lat": 0})
            print("  [{},{}] {} -> NOT FOUND".format(row, col, name))
        time.sleep(0.6)

print("Geocoded {} intersections".format(len(its)))

# save raw for user to adjust
out = {"intersections": its, "roads": [], "hRoads": H_ROADS, "vRoads": V_ROADS}
os.makedirs("sys-frontend/public/network", exist_ok=True)
with open("sys-frontend/public/network/raw_network.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("Saved to sys-frontend/public/network/raw_network.json")
print("Now adjust markers on the map, export the JSON, and I'll run path planning")
