<script setup lang="ts">
// ================================================================
// MapRoadNetwork — 高德地图 城市级数字孪生路网
// 加载流程：地图 → POI修正路口 → 路径规划 → 一次性渲染
// 离线降级 → RoadNetwork.vue（Three.js 抽象路网）
// ================================================================
import { ref, watch, onBeforeUnmount, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'
import { initAMap, type AMapInstance } from '@/map/amapInit'
import { addAMapRoadLayer } from '@/map/amapRoads'
import { createTLMarkers, type TLMarker } from '@/map/amapMarkers'
import { fetchDrivingPathsBatch, snapIntersectionsToAMap } from '@/map/amapPathGen'
import RoadNetwork from '@/components/RoadNetwork.vue'
import MapLegend from '@/components/MapLegend.vue'
import Intersection3DViewer from '@/components/Intersection3DViewer.vue'
import { createVehicleLayer, type VehicleLayer } from '@/map/amapVehicleLayer'
import type { SimVehicleState, SimRoadnetResponse } from '@/types/traffic'

const store = useTrafficStore()
const {
  intersections, roads, vehicles,
  systemMode, emergencyRoute, activeGreenWaveIndex, selectedIntersectionId,
  simulationVehicles, simulationStatus, simRoadnet, emergencyCfVehicleId,
} = storeToRefs(store)

const mapBox = ref<HTMLDivElement | null>(null)
const mapReady = ref(false)
const mapFailed = ref(false)
const dataLoading = ref(false)
const viewerOpen = ref(false)
const selectedRoadId = ref<string | null>(null)

type SourceCollectorMode = 'idle' | 'real' | 'virtual' | 'edge'
interface SourceIntersectionDraft {
  id: string
  name: string
  lng: number
  lat: number
  signalized?: boolean
  virtual?: boolean
}
interface SourceEdgeDraft {
  id: string
  from: string
  to: string
  name: string
  bidirectional: boolean
  laneCount: number
}

const SOURCE_COLLECTOR_KEY = 'custom_real_source_draft'
const SHOW_DEFAULT_ROADNET = import.meta.env.VITE_SHOW_DEFAULT_ROADNET !== 'false'
const sourceCollectorMode = ref<SourceCollectorMode>('idle')
const sourceIntersections = ref<SourceIntersectionDraft[]>([])
const sourceEdges = ref<SourceEdgeDraft[]>([])
const sourceSelectedNodeId = ref<string | null>(null)
const edgeBidirectional = ref(true)
const edgeLaneCount = ref(3)

const sourceNodeCount = computed(() => sourceIntersections.value.length)
const sourceEdgeCount = computed(() => sourceEdges.value.length)
const sourceModeText = computed(() => {
  if (sourceCollectorMode.value === 'real') return '点击地图添加真实路口'
  if (sourceCollectorMode.value === 'virtual') return '点击地图添加边界入口/出口'
  if (sourceCollectorMode.value === 'edge') return '依次点击两个采集点添加道路连接'
  return '选择采集模式'
})

let amapInstance: AMapInstance | null = null
let roadLayer: ReturnType<typeof addAMapRoadLayer> | null = null
let tlMarkers: ReturnType<typeof createTLMarkers> | null = null
let emergencyLine: AMap.Polyline | null = null
let vehicleLayer: VehicleLayer | null = null
let vehicleUpdateTimer: ReturnType<typeof setTimeout> | null = null
const sourceMarkers = new Map<string, AMap.Marker>()
const sourceMarkerContents = new Map<string, HTMLElement>()
const sourceEdgeLines: AMap.Polyline[] = []

async function bootstrapMap(): Promise<void> {
  if (!mapBox.value) return
  try {
    amapInstance = await initAMap(mapBox.value, () => { mapFailed.value = true })
    const map = amapInstance!.map

    emergencyLine = new AMap.Polyline({
      path: [], strokeColor: '#00E5FF', strokeWeight: 8, strokeOpacity: 0.9, zIndex: 50,
    })
    emergencyLine.setMap(map)

    // 双击地图 → 若有选中路口则打开全景 3D 视图
    map.on('dblclick', () => {
      if (selectedIntersectionId.value) viewerOpen.value = true
    })
    map.on('click', (event: any) => {
      handleSourceMapClick(event)
    })

    mapReady.value = true
    dataLoading.value = true

    // 后台加载真实数据，完成后一次性渲染
    await loadRealData()
    restoreSourceDraft()
    dataLoading.value = false
  } catch {
    mapFailed.value = true
  }
}

function addEndpoint(map: Map<string, Array<[number, number]>>, itId: string, pt: [number, number]): void {
  const arr = map.get(itId)
  if (arr) arr.push(pt)
  else map.set(itId, [pt])
}

async function loadRealData(): Promise<void> {
  const map = amapInstance!.map

  if (!SHOW_DEFAULT_ROADNET) return

  // ---- 第 1 步：从后端加载数据（失败自动降级 mock）----
  await store.loadDashboardData()

  // ---- 第 1.5 步：高德 POI 地理编码修正路口坐标 ----
  // 把手写的 mock 坐标替换为高德认定的真实交叉口位置
  const snapItems = intersections.value.map((it) => ({ id: it.id, name: it.name, lng: it.lng, lat: it.lat }))
  const snapped = await snapIntersectionsToAMap(snapItems, 3)
  for (const it of intersections.value) {
    const pt = snapped.get(it.id)
    if (pt) {
      it.lng = pt[0]
      it.lat = pt[1]
      it.x = (it.lng - 121.450) / 0.035
      it.y = (31.240 - it.lat) / 0.027
    }
  }

  // ---- 第 2 步：路径规划（用修正后的坐标）----
  const pairs: Array<{ origin: [number, number]; destination: [number, number] }> = []
  for (const r of roads.value) {
    const from = intersections.value.find((i) => i.id === r.from)
    const to = intersections.value.find((i) => i.id === r.to)
    if (!from || !to) continue
    pairs.push({ origin: [from.lng, from.lat], destination: [to.lng, to.lat] })
  }
  const paths = await fetchDrivingPathsBatch(pairs, 3)
  let pi = 0
  for (const r of roads.value) {
    const from = intersections.value.find((i) => i.id === r.from)
    const to = intersections.value.find((i) => i.id === r.to)
    if (!from || !to) continue
    const realPath = paths[pi++]
    if (realPath) r.path = realPath
  }

  // ---- 第 2.5 步：用真实路径端点修正路口坐标 ----
  // 高德驾车路径端点 = 道路实际交叉口位置，比 mock 手写坐标更准确
  const itEndpoints = new Map<string, Array<[number, number]>>()
  for (const r of roads.value) {
    if (!r.path || r.path.length < 2) continue
    const first = r.path[0]!
    const last = r.path[r.path.length - 1]!
    addEndpoint(itEndpoints, r.from, first)
    addEndpoint(itEndpoints, r.to, last)
  }
  for (const it of intersections.value) {
    const pts = itEndpoints.get(it.id)
    if (!pts || pts.length === 0) continue
    const sumLng = pts.reduce((s, p) => s + p[0], 0)
    const sumLat = pts.reduce((s, p) => s + p[1], 0)
    it.lng = sumLng / pts.length
    it.lat = sumLat / pts.length
    // 同步修正归一化坐标（Three.js 备用）
    it.x = (it.lng - 121.450) / 0.035
    it.y = (31.240 - it.lat) / 0.027
  }

  // ---- 第 3 步：一次性创建 ----
  roadLayer = addAMapRoadLayer(map, intersections.value, roads.value, (id) => { selectedRoadId.value = id })
  tlMarkers = createTLMarkers(map, intersections.value, (id) => store.selectIntersection(id))

  // ---- 第 4 步：加载扩展路网预览图层 ----
  void loadExpandedNetwork(map)
}

// ---- 扩展路网预览：显示 6×8 完整路网（半透明叠加） ----
const expandedLines: AMap.Polyline[] = []
const expandedMarkers: AMap.Marker[] = []
let expandedData: any = null

function exportAdjustedNetwork(): void {
  if (!expandedData) return
  const json = JSON.stringify(expandedData, null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = 'shanghai_adjusted.json'
  a.click()
  URL.revokeObjectURL(url)
}

// ---- 新增路口模式 ----
const addingIntersection = ref(false)
function toggleAddMode(): void {
  addingIntersection.value = !addingIntersection.value
  if (addingIntersection.value) {
    console.log('[ExpandedNetwork] 点击地图任意位置添加新路口')
  }
}

function setSourceMode(mode: SourceCollectorMode): void {
  sourceCollectorMode.value = sourceCollectorMode.value === mode ? 'idle' : mode
  if (sourceCollectorMode.value !== 'edge') sourceSelectedNodeId.value = null
  syncSourceMarkerStyles()
}

function nextSourceId(prefix: string): string {
  const used = new Set(sourceIntersections.value.map((item) => item.id))
  let index = 1
  let id = `${prefix}${String(index).padStart(3, '0')}`
  while (used.has(id)) {
    index += 1
    id = `${prefix}${String(index).padStart(3, '0')}`
  }
  return id
}

function edgeId(from: string, to: string): string {
  return `E_${from}_${to}`.replace(/[^A-Za-z0-9_]/g, '_')
}

function sourceNodeLabel(node: SourceIntersectionDraft): string {
  return node.virtual ? 'V' : 'I'
}

function sourceNodeColor(node: SourceIntersectionDraft): string {
  return node.virtual ? '#f5a623' : '#22d3a0'
}

function createSourceMarker(node: SourceIntersectionDraft): void {
  if (!amapInstance) return
  sourceMarkers.get(node.id)?.setMap(null)
  const content = document.createElement('button')
  content.type = 'button'
  content.className = 'source-node-marker'
  content.textContent = sourceNodeLabel(node)
  content.title = `${node.name}\nlng=${node.lng.toFixed(6)} lat=${node.lat.toFixed(6)}`
  content.style.borderColor = sourceNodeColor(node)
  content.style.color = sourceNodeColor(node)

  const marker = new AMap.Marker({
    position: [node.lng, node.lat],
    map: amapInstance.map,
    content,
    offset: new AMap.Pixel(-10, -10),
    zIndex: 120,
    draggable: true,
  } as any)
  marker.on('click', (event: any) => {
    event?.originEvent?.stopPropagation?.()
    handleSourceMarkerClick(node.id)
  })
  marker.on('dragging', (event: any) => {
    const pos = event.lnglat
    node.lng = pos.lng
    node.lat = pos.lat
    content.title = `${node.name}\nlng=${node.lng.toFixed(6)} lat=${node.lat.toFixed(6)}`
    redrawSourceEdges()
  })
  marker.on('dragend', () => {
    saveSourceDraft()
    redrawSourceEdges()
  })
  sourceMarkers.set(node.id, marker)
  sourceMarkerContents.set(node.id, content)
  syncSourceMarkerStyles()
}

function syncSourceMarkerStyles(): void {
  for (const node of sourceIntersections.value) {
    const content = sourceMarkerContents.get(node.id)
    if (!content) continue
    const selected = sourceSelectedNodeId.value === node.id
    content.style.background = selected ? 'rgba(34,211,160,0.95)' : 'rgba(4,21,39,0.94)'
    content.style.color = selected ? '#041527' : sourceNodeColor(node)
    content.style.boxShadow = selected ? '0 0 14px rgba(34,211,160,0.65)' : `0 0 10px ${sourceNodeColor(node)}66`
  }
}

function handleSourceMapClick(event: any): void {
  if (sourceCollectorMode.value !== 'real' && sourceCollectorMode.value !== 'virtual') return
  const lng = event.lnglat.lng
  const lat = event.lnglat.lat
  const isVirtual = sourceCollectorMode.value === 'virtual'
  const id = nextSourceId(isVirtual ? 'V' : 'I')
  const node: SourceIntersectionDraft = {
    id,
    name: isVirtual ? `边界点 ${id}` : `路口 ${id}`,
    lng,
    lat,
    ...(isVirtual ? { virtual: true } : { signalized: true }),
  }
  sourceIntersections.value.push(node)
  createSourceMarker(node)
  saveSourceDraft()
}

function handleSourceMarkerClick(nodeId: string): void {
  if (sourceCollectorMode.value !== 'edge') {
    sourceSelectedNodeId.value = nodeId
    syncSourceMarkerStyles()
    return
  }
  if (!sourceSelectedNodeId.value) {
    sourceSelectedNodeId.value = nodeId
    syncSourceMarkerStyles()
    return
  }
  if (sourceSelectedNodeId.value === nodeId) {
    sourceSelectedNodeId.value = null
    syncSourceMarkerStyles()
    return
  }
  addSourceEdge(sourceSelectedNodeId.value, nodeId)
  sourceSelectedNodeId.value = null
  syncSourceMarkerStyles()
}

function addSourceEdge(from: string, to: string): void {
  if (sourceEdges.value.some((edge) => edge.from === from && edge.to === to)) return
  const fromNode = sourceIntersections.value.find((item) => item.id === from)
  const toNode = sourceIntersections.value.find((item) => item.id === to)
  if (!fromNode || !toNode) return
  sourceEdges.value.push({
    id: edgeId(from, to),
    from,
    to,
    name: `${fromNode.name}-${toNode.name}`,
    bidirectional: edgeBidirectional.value,
    laneCount: Math.max(1, Math.min(6, edgeLaneCount.value)),
  })
  redrawSourceEdges()
  saveSourceDraft()
}

function redrawSourceEdges(): void {
  if (!amapInstance) return
  sourceEdgeLines.forEach((line) => line.setMap(null))
  sourceEdgeLines.length = 0
  for (const edge of sourceEdges.value) {
    const from = sourceIntersections.value.find((item) => item.id === edge.from)
    const to = sourceIntersections.value.find((item) => item.id === edge.to)
    if (!from || !to) continue
    const line = new AMap.Polyline({
      map: amapInstance.map,
      path: [[from.lng, from.lat], [to.lng, to.lat]],
      strokeColor: edge.bidirectional ? '#7af7ff' : '#f5a623',
      strokeWeight: 3,
      strokeOpacity: 0.82,
      lineJoin: 'round',
      lineCap: 'round',
      zIndex: 115,
    } as any)
    sourceEdgeLines.push(line)
  }
}

function buildSourcePayload(): any {
  return {
    sceneId: 'custom_real',
    coordinateSystem: 'lnglat',
    intersections: sourceIntersections.value.map((item) => ({
      id: item.id,
      name: item.name,
      lng: +item.lng.toFixed(6),
      lat: +item.lat.toFixed(6),
      ...(item.virtual ? { virtual: true } : { signalized: true }),
    })),
    edges: sourceEdges.value.map((edge) => ({
      id: edge.id,
      from: edge.from,
      to: edge.to,
      name: edge.name,
      bidirectional: edge.bidirectional,
      laneCount: edge.laneCount,
    })),
  }
}

function saveSourceDraft(): void {
  try {
    localStorage.setItem(SOURCE_COLLECTOR_KEY, JSON.stringify(buildSourcePayload()))
  } catch { /* ignore */ }
}

function restoreSourceDraft(): void {
  try {
    const raw = localStorage.getItem(SOURCE_COLLECTOR_KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    sourceIntersections.value = Array.isArray(data.intersections) ? data.intersections : []
    sourceEdges.value = Array.isArray(data.edges) ? data.edges : []
    sourceMarkers.forEach((marker) => marker.setMap(null))
    sourceMarkers.clear()
    sourceMarkerContents.clear()
    for (const node of sourceIntersections.value) createSourceMarker(node)
    redrawSourceEdges()
  } catch { /* ignore */ }
}

function exportCustomSourceNetwork(): void {
  const json = JSON.stringify(buildSourcePayload(), null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'custom_real_source.json'
  a.click()
  URL.revokeObjectURL(url)
}

function clearSourceDraft(): void {
  if (!window.confirm('清空当前采集的路口和道路连接？')) return
  sourceIntersections.value = []
  sourceEdges.value = []
  sourceSelectedNodeId.value = null
  sourceMarkers.forEach((marker) => marker.setMap(null))
  sourceMarkers.clear()
  sourceMarkerContents.clear()
  sourceEdgeLines.forEach((line) => line.setMap(null))
  sourceEdgeLines.length = 0
  localStorage.removeItem(SOURCE_COLLECTOR_KEY)
}

async function loadExpandedNetwork(map: AMap.Map): Promise<void> {
  try {
    // 清除旧的图层
    expandedLines.forEach((l) => l.setMap(null))
    expandedMarkers.forEach((m) => m.setMap(null))
    expandedLines.length = 0
    expandedMarkers.length = 0

    const resp = await fetch('/network/raw_network.json')
    const data = await resp.json()
    expandedData = data

    // 道路（实线，拥堵配色按车辆数推算）
    const CI_COLORS = ['#5ebf49', '#5ebf49', '#f2b23d', '#f2b23d', '#e65c4c', '#cc0000']
    for (const r of data.roads) {
      const pts = r.points || r.pts
      const path = pts.map((p: any) => [p.x, p.y] as [number, number])
      const ci = Math.min(5, Math.floor(Math.random() * 3)) // 默认畅通，后续跟仿真数据
      const poly = new AMap.Polyline({
        path, map,
        strokeColor: CI_COLORS[ci]!,
        strokeWeight: 5, strokeOpacity: 0.7,
        lineJoin: 'round', lineCap: 'round',
        zIndex: 50,
      } as any)
      expandedLines.push(poly)
    }

    // 路口标记（已有路口用 TL marker 已覆盖，只画新增的蓝点）
    const existingNames = new Set(['南京路-西藏路','南京路-黄陂路','南京路-瑞金路','南京路-常熟路','淮海路-西藏路','淮海路-黄陂路','淮海路-瑞金路','淮海路-常熟路','建国路-西藏路','建国路-黄陂路','建国路-瑞金路','建国路-常熟路'])
    const newIts = data.intersections.filter((i: any) => !existingNames.has(i.name))
    for (const it of newIts) {
      const isEx = false
      const markerContent = document.createElement('div')
      markerContent.style.cssText = `width:${isEx ? 22 : 18}px;height:${isEx ? 22 : 18}px;border-radius:50%;background:rgba(4,21,39,0.9);border:2px solid ${isEx ? '#22d3a0' : '#00d4ff'};box-shadow:0 0 8px ${isEx ? '#22d3a0' : '#00d4ff'};display:flex;align-items:center;justify-content:center;font-size:${isEx ? 13 : 10}px;cursor:grab`
      markerContent.innerText = isEx ? '🚥' : '+'
      markerContent.title = `${it.name} — 可拖拽修正坐标`

      const m = new AMap.Marker({
        position: [it.lng, it.lat], map,
        content: markerContent,
        offset: new AMap.Pixel(isEx ? -11 : -9, isEx ? -11 : -9),
        zIndex: 90,
        draggable: true,
      } as any)

      // 拖拽时显示坐标
      m.on('dragging', (e: any) => {
        const pos = e.lnglat
        markerContent.style.cursor = 'grabbing'
        markerContent.title = `${it.name}\nlng=${pos.lng.toFixed(6)} lat=${pos.lat.toFixed(6)}`
      })
      m.on('dragend', (e: any) => {
        const pos = e.lnglat
        it.lng = pos.lng
        it.lat = pos.lat
        markerContent.style.cursor = 'grab'
        markerContent.style.borderColor = '#f5a623'
        markerContent.style.boxShadow = '0 0 12px #f5a623'
        console.log(`[ExpandedNetwork] ${it.name} moved to`, pos.lng.toFixed(6), pos.lat.toFixed(6))
      })

      expandedMarkers.push(m)
    }
    console.log('[ExpandedNetwork] loaded', data.intersections.length, 'intersections +', data.roads.length, 'roads')
    console.log('[ExpandedNetwork] drawing', expandedLines.length, 'lines +', expandedMarkers.length, 'markers (new only)')

    // 地图点击新增路口
    map.on('click', (e: any) => {
      if (!addingIntersection.value) return
      const lng = e.lnglat.lng, lat = e.lnglat.lat
      const row = parseInt(prompt(`新路口行号 (0-5)?\n坐标: ${lng.toFixed(4)}, ${lat.toFixed(4)}`) || '')
      if (isNaN(row) || row < 0 || row > 5) return
      const col = parseInt(prompt('新路口列号 (0-7)?') || '')
      if (isNaN(col) || col < 0 || col > 7) return
      const name = prompt('路口名称 (如 北京路-常熟路)?') || `row${row}col${col}`
      // 添加到数据
      const newIt = { row, col, lng, lat, name }
      expandedData.intersections.push(newIt)
      // 创建 marker
      const markerContent = document.createElement('div')
      markerContent.style.cssText = 'width:18px;height:18px;border-radius:50%;background:rgba(4,21,39,0.9);border:2px solid #f5a623;box-shadow:0 0 12px #f5a623;display:flex;align-items:center;justify-content:center;font-size:10px;cursor:grab'
      markerContent.innerText = '●'
      markerContent.title = `${name} (新增)`
      const m = new AMap.Marker({
        position: [lng, lat], map,
        content: markerContent,
        offset: new AMap.Pixel(-9, -9),
        zIndex: 91,
        draggable: true,
      } as any)
      m.on('dragging', (ev: any) => {
        const p = ev.lnglat
        newIt.lng = p.lng; newIt.lat = p.lat
        markerContent.title = `${name}\nlng=${p.lng.toFixed(6)} lat=${p.lat.toFixed(6)}`
      })
      m.on('dragend', (ev: any) => {
        const p = ev.lnglat
        newIt.lng = p.lng; newIt.lat = p.lat
        markerContent.style.borderColor = '#f5a623'
        markerContent.style.boxShadow = '0 0 12px #f5a623'
        console.log(`[ExpandedNetwork] new ${name} moved to`, p.lng.toFixed(6), p.lat.toFixed(6))
      })
      expandedMarkers.push(m)
      console.log(`[ExpandedNetwork] added ${name} at row=${row} col=${col}`, lng.toFixed(6), lat.toFixed(6))
    })
  } catch (e) {
    console.warn('[ExpandedNetwork] load failed:', e)
  }
}

function syncEmergency(): void {
  if (!emergencyLine) return
  if (systemMode.value === 'emergency') {
    const pts: [number, number][] = []
    for (const id of emergencyRoute.value) {
      // 优先直接匹配 mock ID，否则尝试 CityFlow intersection_{col}_{row} 格式反向映射
      let it = intersections.value.find((i) => i.id === id)
      if (!it) {
        const m = id.match(/^intersection_(\d+)_(\d+)$/)
        if (m) {
          const col = +m[1]!, row = +m[2]!
          it = intersections.value.find((i) => i.col === col && i.row === row)
        }
      }
      if (it) pts.push([it.lng, it.lat])
    }
    emergencyLine.setPath(pts)
    emergencyLine.show()
  } else {
    emergencyLine.hide()
  }
}

// 防抖更新：避免 200ms 高频属性变更触发 AMap 全量重绘风暴
let updateTimer: ReturnType<typeof setTimeout> | null = null
watch([roads, intersections], () => {
  if (updateTimer !== null) return // 已有待执行更新，合并跳过
  updateTimer = setTimeout(() => {
    updateTimer = null
    roadLayer?.update(intersections.value, roads.value)
    tlMarkers?.updateAll(intersections.value)
    syncEmergency()
  }, 300)
}, { deep: true })
watch(systemMode, syncEmergency)

// 单击路口 marker → 镜头拉近放大到该路口
watch(selectedIntersectionId, (id) => {
  if (!id || !amapInstance) return
  const it = intersections.value.find((i) => i.id === id)
  if (it) {
  amapInstance.map.setZoomAndCenter(15, [it.lng, it.lat])
  }
})

// ---- 仿真车辆图层：路网就绪后按帧刷新 ----
watch([simulationVehicles, simRoadnet, simulationStatus], () => {
  if (simulationStatus.value !== 'running' || !amapInstance || !simRoadnet.value) {
    if (vehicleUpdateTimer) {
      clearTimeout(vehicleUpdateTimer)
      vehicleUpdateTimer = null
    }
    vehicleLayer?.dispose()
    vehicleLayer = null
    return
  }
   if (vehicleUpdateTimer !== null) return
  vehicleUpdateTimer = setTimeout(() => {
    vehicleUpdateTimer = null
    if (simulationStatus.value !== 'running' || !amapInstance || !simRoadnet.value) return
    if (!vehicleLayer) {
      vehicleLayer = createVehicleLayer(
        amapInstance.map,
        simRoadnet.value as SimRoadnetResponse,
        roads.value,
        intersections.value,
      )
    }
    const evSet = emergencyCfVehicleId.value ? new Set([emergencyCfVehicleId.value]) : undefined
    vehicleLayer.update(simulationVehicles.value as SimVehicleState[], evSet)
  }, 500)
})

setTimeout(bootstrapMap, 100)

onBeforeUnmount(() => {
  if (vehicleUpdateTimer) clearTimeout(vehicleUpdateTimer)
  roadLayer?.dispose()
  tlMarkers?.dispose()
  vehicleLayer?.dispose()
  emergencyLine?.setMap(null)
  expandedLines.forEach((l) => l.setMap(null))
  expandedMarkers.forEach((m) => m.setMap(null))
  sourceMarkers.forEach((m) => m.setMap(null))
  sourceEdgeLines.forEach((l) => l.setMap(null))
  amapInstance?.destroy()
})

const overview = computed(() => [
  { label: '路口总数', value: String(intersections.value.length), unit: '个' },
  { label: '设备在线率', value: store.statistics.deviceOnlineRate.toFixed(1), unit: '%' },
  { label: '监测车辆', value: String(vehicles.value.length), unit: '辆' },
  { label: '拥堵路段', value: String(store.statistics.congestedRoadCount), unit: '条' },
])
const selectedName = computed(
  () => intersections.value.find((it) => it.id === selectedIntersectionId.value)?.name ?? '',
)
const selectedRoadName = computed(
  () => roads.value.find((r) => r.id === selectedRoadId.value)?.name ?? '',
)
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">城市路网数字孪生</span>
        <span v-if="dataLoading" class="mrn-badge mrn-badge--loading">
          <span class="status-dot status-dot--live" /> 加载中…
        </span>
        <span v-else-if="mapReady" class="mrn-badge mrn-badge--live">
          <span class="status-dot status-dot--live" /> 高德地图
        </span>
        <span v-else-if="mapFailed" class="mrn-badge mrn-badge--fallback">
          <span class="status-dot status-dot--warning" /> 离线/降级
        </span>
        <span class="titlebar-deco"><i /><i /><i /></span>
        <div v-if="mapReady" class="source-toolbar">
          <button
            class="source-tool-btn"
            :class="{ active: sourceCollectorMode === 'real' }"
            @click="setSourceMode('real')"
          >真实路口</button>
          <button
            class="source-tool-btn"
            :class="{ active: sourceCollectorMode === 'virtual' }"
            @click="setSourceMode('virtual')"
          >边界点</button>
          <button
            class="source-tool-btn"
            :class="{ active: sourceCollectorMode === 'edge' }"
            @click="setSourceMode('edge')"
          >道路连接</button>
          <button class="source-tool-btn" @click="exportCustomSourceNetwork">导出配置</button>
          <button class="source-tool-btn subtle" @click="clearSourceDraft">清空</button>
        </div>
        <button
          v-if="mapReady"
          class="cyber-btn mrn-export-btn"
          :style="{ marginLeft:'8px', padding:'3px 10px', fontSize:'10px', borderColor: addingIntersection ? '#f5a623' : '' }"
          @click="toggleAddMode"
        >{{ addingIntersection ? '📌 点击地图添加…' : '📌 新增路口' }}</button>
        <button
          v-if="mapReady"
          class="cyber-btn mrn-export-btn"
          style="margin-left:4px;padding:3px 10px;font-size:10px"
          @click="exportAdjustedNetwork"
        >📥 导出坐标</button>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <div class="mrn-viewport">
        <RoadNetwork v-if="mapFailed" class="mrn-fallback" />
        <div v-show="!mapFailed" ref="mapBox" class="mrn-map" />

        <!-- 概览指标：左上角浮层 -->
        <div class="mrn-overview-float">
          <div v-for="o in overview" :key="o.label" class="mrn-metric-float">
            <span class="mrn-metric__value">{{ o.value }}</span>
            <span class="mrn-metric__unit">{{ o.unit }}</span>
            <div class="mrn-metric__label">{{ o.label }}</div>
          </div>
        </div>

        <div v-if="dataLoading" class="mrn-loading-overlay">
          <div class="mrn-loading-box">
            <span class="mrn-loading-icon">⏳</span>
            <div class="mrn-loading-text">正在加载路口数据与道路规划…</div>
          </div>
        </div>

        <MapLegend :visible="SHOW_DEFAULT_ROADNET && !mapFailed && !dataLoading" />

        <div v-if="!mapFailed && !dataLoading" class="source-collector-panel">
          <div class="source-collector-panel__title">路网采集</div>
          <div class="source-collector-panel__mode">{{ sourceModeText }}</div>
          <div class="source-collector-panel__stats">
            <span>路口 {{ sourceNodeCount }}</span>
            <span>道路 {{ sourceEdgeCount }}</span>
          </div>
          <div class="source-collector-panel__controls">
            <label>
              <input v-model="edgeBidirectional" type="checkbox">
              双向道路
            </label>
            <label>
              车道
              <input v-model.number="edgeLaneCount" type="number" min="1" max="6">
            </label>
          </div>
          <div v-if="sourceSelectedNodeId" class="source-collector-panel__selected">
            已选 {{ sourceSelectedNodeId }}
          </div>
        </div>


        <div class="mrn-hint" v-if="!mapFailed && !dataLoading">
          <span>🖱 拖拽/滚轮 · 单击路口 · 双击进全景 | 高德地图</span>
        </div>
      </div>
    </div>

    <Intersection3DViewer
      v-if="viewerOpen"
      :intersection-id="selectedIntersectionId"
      @close="viewerOpen = false"
    />
  </section>
</template>

<style scoped>
.comp-card { height: 100%; display: flex; flex-direction: column; }
.comp-card__body { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 0; overflow: hidden; }
.mrn-badge { margin-left: 10px; font-size: 10px; padding: 3px 8px; display: inline-flex; align-items: center; gap: 4px; }
.mrn-badge--live { border: 1px solid rgba(34,211,160,0.5); color: #22d3a0; background: rgba(34,211,160,0.08); }
.mrn-badge--loading { border: 1px solid rgba(0,212,255,0.5); color: #00d4ff; background: rgba(0,212,255,0.08); }
.mrn-badge--fallback { border: 1px solid rgba(255,184,0,0.5); color: #ffb800; background: rgba(255,184,0,0.08); }
.mrn-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; flex: 0 0 auto; position: relative; z-index: 5; }
.mrn-metric { padding: 5px 10px; text-align: center; background: rgba(0,212,255,0.05); border: 1px solid rgba(0,212,255,0.18); }
.mrn-metric__value { font-family: 'DINPro','Rajdhani',sans-serif; font-size: clamp(15px, 1.3vw, 23px); font-weight: 700; color: #00d4ff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mrn-metric__unit { margin-left: 3px; font-size: 11px; color: #8da8c5; white-space: nowrap; }
.mrn-metric__label { margin-top: 1px; font-size: 11px; color: #5a7595; letter-spacing: 0.04em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mrn-viewport { flex: 1; min-height: 0; position: relative; border: 1.5px solid rgba(0,212,255,0.42); overflow: hidden; }
.mrn-map { width: 100%; height: 100%; }
.mrn-fallback { width: 100%; height: 100%; }

.mrn-overview-float { position: absolute; top: 6px; left: 6px; z-index: 5; display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.mrn-metric-float { padding: 4px 10px; text-align: center; background: rgba(4,21,39,0.85); border: 1px solid rgba(0,212,255,0.25); white-space: nowrap; }
.mrn-loading-overlay { position: absolute; inset: 0; z-index: 10; display: flex; align-items: center; justify-content: center; background: rgba(2,8,23,0.6); }
.mrn-loading-box { text-align: center; }
.mrn-loading-icon { font-size: 36px; display: block; margin-bottom: 10px; }
.mrn-loading-text { font-size: 14px; color: #8da8c5; letter-spacing: 0.06em; }
.mrn-selected-info { position: absolute; top: 6px; left: 50%; transform: translateX(-50%); z-index: 5; padding: 4px 10px; background: rgba(4,21,39,0.88); border: 1px solid rgba(0,212,255,0.35); backdrop-filter: blur(4px); display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.mrn-selected-info__name { font-size: 12px; font-weight: 600; color: #7af7ff; text-shadow: 0 0 6px rgba(0,212,255,0.3); overflow: hidden; text-overflow: ellipsis; }
.mrn-enter-btn { padding: 3px 7px; font-size: 10px; color: #00d4ff; background: rgba(0,212,255,0.08); border: 1px solid rgba(0,212,255,0.35); cursor: pointer; transition: all 0.2s; white-space: nowrap; }
.mrn-enter-btn:hover { background: rgba(0,212,255,0.18); box-shadow: 0 0 8px rgba(0,212,255,0.25); }
.mrn-hint { position: absolute; top: 8px; right: 10px; z-index: 4; font-size: 10px; color: #8da8c5; padding: 3px 8px; background: rgba(4,21,39,0.7); border: 1px solid rgba(0,212,255,0.15); pointer-events: none; }
.mrn-export-btn { display: none; }
.source-toolbar { margin-left: 8px; display: inline-flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.source-tool-btn { min-height: 24px; padding: 3px 9px; font-size: 11px; color: #9db8d6; background: rgba(4,21,39,0.72); border: 1px solid rgba(0,212,255,0.25); cursor: pointer; }
.source-tool-btn:hover { color: #d9f7ff; border-color: rgba(0,212,255,0.55); background: rgba(0,212,255,0.1); }
.source-tool-btn.active { color: #041527; border-color: #22d3a0; background: #22d3a0; }
.source-tool-btn.subtle { color: #f2b23d; border-color: rgba(245,166,35,0.45); }
.source-collector-panel { position: absolute; left: 8px; bottom: 8px; z-index: 6; width: 230px; padding: 9px 10px; background: rgba(4,21,39,0.9); border: 1px solid rgba(0,212,255,0.28); color: #9db8d6; backdrop-filter: blur(4px); }
.source-collector-panel__title { font-size: 12px; font-weight: 700; color: #d9f7ff; }
.source-collector-panel__mode { margin-top: 4px; font-size: 11px; color: #7af7ff; line-height: 1.35; }
.source-collector-panel__stats { margin-top: 7px; display: flex; gap: 8px; font-size: 11px; color: #f2b23d; }
.source-collector-panel__controls { margin-top: 8px; display: flex; align-items: center; gap: 10px; font-size: 11px; }
.source-collector-panel__controls label { display: inline-flex; align-items: center; gap: 4px; }
.source-collector-panel__controls input[type="number"] { width: 42px; color: #d9f7ff; background: rgba(2,8,23,0.75); border: 1px solid rgba(0,212,255,0.25); padding: 2px 4px; }
.source-collector-panel__selected { margin-top: 6px; font-size: 11px; color: #22d3a0; }
:deep(.source-node-marker) { width: 20px; height: 20px; border-radius: 50%; border: 2px solid currentColor; background: rgba(4,21,39,0.94); font-size: 10px; font-weight: 700; line-height: 16px; text-align: center; cursor: grab; padding: 0; font-family: 'DINPro','Rajdhani',sans-serif; }
:deep(.source-node-marker:active) { cursor: grabbing; }
</style>
