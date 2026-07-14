import type { Intersection, Road } from '@/types/traffic'
import { mockIntersections, mockRoads } from '@/mock/trafficMock'

function displayX(lng: number): number {
  return (lng - 121.450) / 0.035
}

function displayY(lat: number): number {
  return (31.240 - lat) / 0.027
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
): Intersection {
  return {
    id,
    name,
    x: displayX(lng),
    y: displayY(lat),
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

function path(points: [number, number][]): [number, number][] {
  return points
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
    path: path(points),
  }
}

export function buildShanghaiDisplayRoadnet(): { intersections: Intersection[]; roads: Road[] } {
  const intersections = structuredClone(mockIntersections)
  const roads = structuredClone(mockRoads)

  intersections.push(
    extraIntersection('VX01', '华山路-北京西路', 0, 1, 121.4508, 31.2388, 'eastwest_straight', 34),
    extraIntersection('VX02', '陕西北路-北京西路', 0, 2, 121.4606, 31.2395, 'northsouth_straight', 46),
    extraIntersection('VX03', '成都北路-北京西路', 0, 3, 121.4702, 31.2397, 'eastwest_left', 58),
    extraIntersection('VX04', '西藏中路-北京东路', 0, 4, 121.4770, 31.2392, 'northsouth_left', 52),
    extraIntersection('VX05', '肇嘉浜路-襄阳南路', 4, 1, 121.4542, 31.2120, 'eastwest_straight', 38),
    extraIntersection('VX06', '肇嘉浜路-瑞金二路', 4, 2, 121.4647, 31.2122, 'northsouth_straight', 44),
    extraIntersection('VX07', '徐家汇路-黄陂南路', 4, 3, 121.4738, 31.2140, 'eastwest_left', 57),
    extraIntersection('VX08', '复兴东路-西藏南路', 4, 4, 121.4790, 31.2168, 'northsouth_left', 49),
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

  const intersectionsById = new Map(intersections.map((item) => [item.id, item]))
  for (const road of roads) {
    const from = intersectionsById.get(road.from)
    const to = intersectionsById.get(road.to)
    if (from && !from.roadIds.includes(road.id)) from.roadIds.push(road.id)
    if (to && !to.roadIds.includes(road.id)) to.roadIds.push(road.id)
  }

  return { intersections, roads }
}
