"""20路口：逐对路径规划，严格按用户坐标，不走立交，不私自改变定位"""
import json, time, urllib.request, urllib.parse, os

AMAP_KEY = "4ab584658b1cdc916345e3c20bc15add"
SRC = r"c:\Users\Cxaiorui\Downloads\shanghai_adjusted (3).json"

with open(SRC, encoding="utf-8") as f:
    src = json.load(f)

# 严格按用户筛选的20个路口
keep = set()
keep.add((0,4)); keep.add((0,5))
for row in [1,2,4]:
    for col in range(1,7):
        keep.add((row,col))

its = [i for i in src["intersections"] if (i["row"],i["col"]) in keep]
grid = {(i["row"],i["col"]): i for i in its}
print("intersections:", len(its))
for it in its:
    print("  [{},{}] {} lng={:.5f} lat={:.5f}".format(it["row"],it["col"],it["name"],it["lng"],it["lat"]))

def driving_path(o, d):
    q = urllib.parse.urlencode({
        "origin": "{},{}".format(o[0],o[1]),
        "destination": "{},{}".format(d[0],d[1]),
        "extensions": "all", "key": AMAP_KEY,
        "strategy": "3",
    })
    try:
        with urllib.request.urlopen("https://restapi.amap.com/v3/direction/driving?" + q, timeout=15) as r:
            d = json.loads(r.read().decode())
        if d.get("status")=="1" and d.get("route",{}).get("paths"):
            path=[]
            for s in d["route"]["paths"][0]["steps"]:
                if not s.get("polyline"): continue
                for p in s["polyline"].split(";"):
                    lng, lat = p.split(",")
                    path.append({"x":float(lng),"y":float(lat)})
            if len(path)>=2: return path
    except Exception as e:
        print("  err:", e)
    return None

rows = sorted(set(i["row"] for i in its))
cols = sorted(set(i["col"] for i in its))
roads = []

# 横向相邻对
for row in rows:
    row_its = sorted([i for i in its if i["row"]==row], key=lambda i:i["col"])
    for k in range(len(row_its)-1):
        a, b = row_its[k], row_its[k+1]
        p = driving_path((a["lng"],a["lat"]), (b["lng"],b["lat"]))
        if p:
            roads.append({"id":"r{}c{}_h".format(row,a["col"]),"fc":a["col"],"fr":row,"tc":b["col"],"tr":row,"pts":p})
            print("  H {} -> {}: {}pts".format(a["name"],b["name"],len(p)))
        else:
            print("  H {} -> {}: FAILED".format(a["name"],b["name"]))
        time.sleep(0.6)

# 纵向相邻对
for col in cols:
    col_its = sorted([i for i in its if i["col"]==col], key=lambda i:i["row"])
    for k in range(len(col_its)-1):
        a, b = col_its[k], col_its[k+1]
        p = driving_path((a["lng"],a["lat"]), (b["lng"],b["lat"]))
        if p:
            roads.append({"id":"r{}c{}_v".format(a["row"],col),"fc":col,"fr":a["row"],"tc":col,"tr":b["row"],"pts":p})
            print("  V {} -> {}: {}pts".format(a["name"],b["name"],len(p)))
        else:
            print("  V {} -> {}: FAILED".format(a["name"],b["name"]))
        time.sleep(0.6)

conn={}
for r in roads:
    conn[(r["fr"],r["fc"])]=conn.get((r["fr"],r["fc"]),0)+1
    conn[(r["tr"],r["tc"])]=conn.get((r["tr"],r["tc"]),0)+1
for it in its:
    n=conn.get((it["row"],it["col"]),0)
    print("  [{},{}] {} roads={}".format(it["row"],it["col"],it["name"],n))

out={"intersections":its,"roads":roads}
os.makedirs("sys-frontend/public/network", exist_ok=True)
with open("sys-frontend/public/network/raw_network.json","w",encoding="utf-8") as f:
    json.dump(out,f,ensure_ascii=False,indent=2)

# CityFlow roadnet
cf_its, cf_roads, rid_map = [], [], {}
for it in its:
    r,c = it["row"], it["col"]
    cn = sum(1 for rd in roads if (rd["fc"]==c and rd["fr"]==r) or (rd["tc"]==c and rd["tr"]==r))
    cf_its.append({"id":"intersection_{}_{}".format(c+1,r+1),"point":{"x":it["lng"]*10000,"y":it["lat"]*10000},"width":0,"roads":[],"roadLinks":[],"trafficLight":{"roadLinkIndices":[],"lightphases":[{"time":5 if i==0 else 30,"availableRoadLinks":[]} for i in range(9)]},"virtual":cn<2})

for idx,rd in enumerate(roads):
    cf_id = "road_{}_{}_{}".format(rd["fr"],rd["fc"],idx)
    rid_map[rd["id"]] = cf_id
    cf_roads.append({"id":cf_id,"startIntersection":"intersection_{}_{}".format(rd["fc"]+1,rd["fr"]+1),"endIntersection":"intersection_{}_{}".format(rd["tc"]+1,rd["tr"]+1),"points":rd["pts"],"lanes":[{"width":3.2,"maxSpeed":16.67}]*3})

for it_idx, it in enumerate(its):
    r,c = it["row"], it["col"]
    conn_list = [i for i,rd in enumerate(roads) if (rd["fc"]==c and rd["fr"]==r) or (rd["tc"]==c and rd["tr"]==r)]
    cf_its[it_idx]["roads"] = [rid_map[roads[i]["id"]] for i in conn_list]
    if cf_its[it_idx]["virtual"]: continue
    li=0
    for i in conn_list:
        for j in conn_list:
            if i==j: continue
            rdi,rdj=roads[i],roads[j]
            idc=rdi["tc"]-rdi["fc"] if (rdi["tc"]==c and rdi["tr"]==r) else rdi["fc"]-rdi["tc"]
            idr=rdi["tr"]-rdi["fr"] if (rdi["tc"]==c and rdi["tr"]==r) else rdi["fr"]-rdi["tr"]
            odc=rdj["tc"]-rdj["fc"] if (rdj["fc"]==c and rdj["fr"]==r) else rdj["fc"]-rdj["tc"]
            odr=rdj["tr"]-rdj["fr"] if (rdj["fc"]==c and rdj["fr"]==r) else rdj["fr"]-rdj["tr"]
            cross=idc*odr-idr*odc; dot=idc*odc+idr*odr
            t="go_straight" if dot>0 and abs(cross)<0.5 else ("turn_left" if cross>0 else "turn_right")
            cf_its[it_idx]["roadLinks"].append({"index":li,"startRoad":rid_map[rdi["id"]],"endRoad":rid_map[rdj["id"]],"type":t,"viaLaneLinkId":None}); li+=1
    cf_its[it_idx]["trafficLight"]["roadLinkIndices"]=list(range(li))

rdn={"intersections":cf_its,"roads":cf_roads}
with open("sim-python/data/shanghai_final/roadnet.json","w",encoding="utf-8") as f: json.dump(rdn,f,ensure_ascii=False,indent=2)
with open("sim-python/data/shanghai_final/flow.json","w",encoding="utf-8") as f: json.dump([{"vehicle":{"length":5,"width":2,"maxPosAcc":2,"maxNegAcc":4.5,"usualPosAcc":2,"usualNegAcc":4.5,"minGap":2.5,"maxSpeed":16.67,"headwayTime":1.5},"route":[rid_map[rd["id"]]],"interval":3.0,"startTime":0,"endTime":-1} for rd in roads],f,ensure_ascii=False,indent=2)

real = sum(1 for i in cf_its if not i["virtual"])
print("Done: {} its ({} real), {} roads".format(len(cf_its),real,len(cf_roads)))
