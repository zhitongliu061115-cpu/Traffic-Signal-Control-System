import type { Intersection, Road } from '@/types/traffic'
import { mockIntersections, mockRoads } from '@/mock/trafficMock'

type DisplayRoadnet = { intersections: Intersection[]; roads: Road[] }

function displayX(lng: number, minLng: number, maxLng: number): number {
  return (lng - minLng) / (maxLng - minLng)
}

function displayY(lat: number, minLat: number, maxLat: number): number {
  return (maxLat - lat) / (maxLat - minLat)
}

function extraIntersection(
  id: string,
  name: string,
  row: number,
  col: number,
  lng: number,
  lat: number,
  phase: Intersection['currentPhase'],
  congestionIndex: number,
  bounds: { minLng: number; maxLng: number; minLat: number; maxLat: number },
): Intersection {
  return {
    id,
    name,
    x: displayX(lng, bounds.minLng, bounds.maxLng),
    y: displayY(lat, bounds.minLat, bounds.maxLat),
    lng,
    lat,
    row,
    col,
    roadIds: [],
    currentPhase: phase,
    greenRemain: 25 + ((row + col) % 4) * 6,
    queueLength: Math.max(2, Math.round(congestionIndex / 7)),
    averageDelay: 12 + Math.round(congestionIndex * 0.45),
    congestionIndex,
    deviceStatus: 'online',
  }
}

function extraRoad(
  id: string,
  from: string,
  to: string,
  name: string,
  flow: number,
  speed: number,
  queueLength: number,
  congestionIndex: number,
  points: [number, number][],
): Road {
  return {
    id,
    from,
    to,
    name,
    flow,
    speed,
    queueLength,
    congestionIndex,
    laneCount: 3,
    direction: 'two-way',
    path: points,
  }
}

function linkRoadIds(intersections: Intersection[], roads: Road[]): void {
  const intersectionsById = new Map(intersections.map((item) => [item.id, item]))
  for (const road of roads) {
    const from = intersectionsById.get(road.from)
    const to = intersectionsById.get(road.to)
    if (from && !from.roadIds.includes(road.id)) from.roadIds.push(road.id)
    if (to && !to.roadIds.includes(road.id)) to.roadIds.push(road.id)
  }
}

export function buildShanghaiDisplayRoadnet(): DisplayRoadnet {
  const bounds = { minLng: 121.450, maxLng: 121.485, minLat: 31.210, maxLat: 31.240 }
  const intersections = structuredClone(mockIntersections)
  const roads = structuredClone(mockRoads)

  intersections.push(
    extraIntersection('VX01', '华山路-北京西路', 0, 1, 121.4508, 31.2388, 'eastwest_straight', 34, bounds),
    extraIntersection('VX02', '陕西北路-北京西路', 0, 2, 121.4606, 31.2395, 'northsouth_straight', 46, bounds),
    extraIntersection('VX03', '成都北路-北京西路', 0, 3, 121.4702, 31.2397, 'eastwest_left', 58, bounds),
    extraIntersection('VX04', '西藏中路-北京东路', 0, 4, 121.4770, 31.2392, 'northsouth_left', 52, bounds),
    extraIntersection('VX05', '肇嘉浜路-襄阳南路', 4, 1, 121.4542, 31.2120, 'eastwest_straight', 38, bounds),
    extraIntersection('VX06', '肇嘉浜路-瑞金二路', 4, 2, 121.4647, 31.2122, 'northsouth_straight', 44, bounds),
    extraIntersection('VX07', '徐家汇路-黄陂南路', 4, 3, 121.4738, 31.2140, 'eastwest_left', 57, bounds),
    extraIntersection('VX08', '复兴东路-西藏南路', 4, 4, 121.4790, 31.2168, 'northsouth_left', 49, bounds),
  )

  roads.push(
    extraRoad('RX01', 'VX04', 'VX03', '北京东路', 980, 48, 45, 36, [[121.4770, 31.2392], [121.4740, 31.2397], [121.4702, 31.2397]]),
    extraRoad('RX02', 'VX03', 'VX02', '北京西路', 1180, 42, 88, 52, [[121.4702, 31.2397], [121.4662, 31.2398], [121.4606, 31.2395]]),
    extraRoad('RX03', 'VX02', 'VX01', '北京西路', 900, 50, 34, 30, [[121.4606, 31.2395], [121.4562, 31.2392], [121.4508, 31.2388]]),
    extraRoad('RX04', 'VX04', 'A01', '西藏中路北延', 1040, 44, 68, 46, [[121.4770, 31.2392], [121.4764, 31.2370], [121.4756, 31.2356]]),
    extraRoad('RX05', 'VX03', 'A02', '成都北路-黄陂北路联络', 1320, 39, 95, 60, [[121.4702, 31.2397], [121.4708, 31.2367], [121.4710, 31.2335]]),
    extraRoad('RX06', 'VX02', 'A03', '陕西北路-茂名北路联络', 1120, 45, 72, 48, [[121.4606, 31.2395], [121.4610, 31.2356], [121.4612, 31.2318]]),
    extraRoad('RX07', 'VX01', 'A04', '华山路-常德路联络', 760, 53, 28, 24, [[121.4508, 31.2388], [121.4512, 31.2345], [121.4518, 31.2305]]),
    extraRoad('RX08', 'VX08', 'VX07', '复兴东路', 1020, 47, 62, 42, [[121.4790, 31.2168], [121.4764, 31.2155], [121.4738, 31.2140]]),
    extraRoad('RX09', 'VX07', 'VX06', '徐家汇路', 1260, 41, 105, 64, [[121.4738, 31.2140], [121.4690, 31.2128], [121.4647, 31.2122]]),
    extraRoad('RX10', 'VX06', 'VX05', '肇嘉浜路', 940, 49, 58, 40, [[121.4647, 31.2122], [121.4590, 31.2118], [121.4542, 31.2120]]),
    extraRoad('RX11', 'A09', 'VX08', '西藏南路南延', 1150, 43, 84, 55, [[121.4778, 31.2208], [121.4784, 31.2188], [121.4790, 31.2168]]),
    extraRoad('RX12', 'A10', 'VX07', '黄陂南路南延', 1210, 38, 118, 68, [[121.4730, 31.2190], [121.4734, 31.2164], [121.4738, 31.2140]]),
    extraRoad('RX13', 'A11', 'VX06', '瑞金二路南延', 1080, 46, 76, 50, [[121.4640, 31.2160], [121.4644, 31.2138], [121.4647, 31.2122]]),
    extraRoad('RX14', 'A12', 'VX05', '襄阳南路南延', 860, 52, 42, 32, [[121.4538, 31.2145], [121.4540, 31.2130], [121.4542, 31.2120]]),
  )

  linkRoadIds(intersections, roads)
  return { intersections, roads }
}

export function buildHangzhouDisplayRoadnet(base: DisplayRoadnet): DisplayRoadnet {
  const bounds = { minLng: 120.105, maxLng: 120.230, minLat: 30.220, maxLat: 30.305 }
  const intersections = structuredClone(base.intersections)
  const roads = structuredClone(base.roads)

  intersections.push(
    extraIntersection('HZVX01', '紫金港路-文一路', 0, 1, 120.1130, 30.3020, 'eastwest_straight', 42, bounds),
    extraIntersection('HZVX02', '莫干山路-文一路', 0, 2, 120.1450, 30.3040, 'northsouth_straight', 50, bounds),
    extraIntersection('HZVX03', '上塘路-文一路', 0, 3, 120.1810, 30.3030, 'eastwest_left', 56, bounds),
    extraIntersection('HZVX04', '机场路-文一路', 0, 4, 120.2200, 30.2980, 'northsouth_left', 48, bounds),
    extraIntersection('HZVX05', '之江路-复兴路', 5, 1, 120.1130, 30.2240, 'eastwest_left', 44, bounds),
    extraIntersection('HZVX06', '湖滨路-复兴路', 5, 2, 120.1500, 30.2220, 'northsouth_straight', 52, bounds),
    extraIntersection('HZVX07', '建国南路-复兴路', 5, 3, 120.1840, 30.2240, 'eastwest_straight', 60, bounds),
    extraIntersection('HZVX08', '秋涛路-复兴路', 5, 4, 120.2220, 30.2260, 'northsouth_left', 54, bounds),
  )

  roads.push(
    extraRoad('HZRX01', 'HZVX01', 'HZVX02', '文一路西延', 960, 45, 58, 40, [[120.1130, 30.3020], [120.1290, 30.3030], [120.1450, 30.3040]]),
    extraRoad('HZRX02', 'HZVX02', 'HZVX03', '文一路中段', 1160, 40, 96, 55, [[120.1450, 30.3040], [120.1630, 30.3042], [120.1810, 30.3030]]),
    extraRoad('HZRX03', 'HZVX03', 'HZVX04', '文一路东延', 1040, 43, 72, 46, [[120.1810, 30.3030], [120.2020, 30.3010], [120.2200, 30.2980]]),
    extraRoad('HZRX04', 'HZVX01', 'intersection_1_1', '紫金港路南接', 820, 48, 42, 34, [[120.1130, 30.3020], [120.1190, 30.2970], [120.1250, 30.2920]]),
    extraRoad('HZRX05', 'HZVX02', 'intersection_2_1', '莫干山路南接', 980, 44, 66, 45, [[120.1450, 30.3040], [120.1500, 30.2985], [120.1550, 30.2920]]),
    extraRoad('HZRX06', 'HZVX03', 'intersection_3_1', '上塘路南接', 1250, 38, 110, 65, [[120.1810, 30.3030], [120.1815, 30.2970], [120.1820, 30.2920]]),
    extraRoad('HZRX07', 'HZVX04', 'intersection_4_1', '机场路南接', 900, 46, 54, 38, [[120.2200, 30.2980], [120.2150, 30.2950], [120.2100, 30.2920]]),
    extraRoad('HZRX08', 'intersection_1_4', 'HZVX05', '之江路南接', 840, 47, 48, 36, [[120.1250, 30.2350], [120.1190, 30.2295], [120.1130, 30.2240]]),
    extraRoad('HZRX09', 'intersection_2_4', 'HZVX06', '湖滨路南接', 1060, 42, 92, 58, [[120.1550, 30.2350], [120.1525, 30.2280], [120.1500, 30.2220]]),
    extraRoad('HZRX10', 'intersection_3_4', 'HZVX07', '建国南路南接', 1220, 39, 104, 63, [[120.1820, 30.2350], [120.1830, 30.2290], [120.1840, 30.2240]]),
    extraRoad('HZRX11', 'intersection_4_4', 'HZVX08', '秋涛路南接', 970, 43, 70, 48, [[120.2100, 30.2350], [120.2160, 30.2300], [120.2220, 30.2260]]),
    extraRoad('HZRX12', 'HZVX05', 'HZVX06', '复兴路西段', 900, 45, 56, 42, [[120.1130, 30.2240], [120.1320, 30.2225], [120.1500, 30.2220]]),
    extraRoad('HZRX13', 'HZVX06', 'HZVX07', '复兴路中段', 1180, 40, 98, 60, [[120.1500, 30.2220], [120.1670, 30.2228], [120.1840, 30.2240]]),
    extraRoad('HZRX14', 'HZVX07', 'HZVX08', '复兴路东段', 1030, 44, 74, 50, [[120.1840, 30.2240], [120.2040, 30.2250], [120.2220, 30.2260]]),
  )

  linkRoadIds(intersections, roads)
  return { intersections, roads }
}
