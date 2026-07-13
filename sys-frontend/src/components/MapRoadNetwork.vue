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

let amapInstance: AMapInstance | null = null
let roadLayer: ReturnType<typeof addAMapRoadLayer> | null = null
let tlMarkers: ReturnType<typeof createTLMarkers> | null = null
let emergencyLine: AMap.Polyline | null = null
let vehicleLayer: VehicleLayer | null = null
let vehicleUpdateTimer: ReturnType<typeof setTimeout> | null = null
let dashboardVideo: HTMLVideoElement | null = null
let resumeDashboardVideo = false
let mapInteracting = false

function scheduleVehicleLayerUpdate(delayMs = 500): void {
  if (vehicleUpdateTimer !== null || viewerOpen.value || mapInteracting) return
  vehicleUpdateTimer = setTimeout(() => {
    vehicleUpdateTimer = null
    if (viewerOpen.value || mapInteracting || simulationStatus.value !== 'running' || !amapInstance || !simRoadnet.value) return
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
  }, delayMs)
}

function handleMapMoveStart(): void {
  mapInteracting = true
  if (vehicleUpdateTimer) {
    clearTimeout(vehicleUpdateTimer)
    vehicleUpdateTimer = null
  }
}

function handleMapMoveEnd(): void {
  mapInteracting = false
  scheduleVehicleLayerUpdate(0)
}

watch([viewerOpen, simulationStatus], ([open, status]) => {
  dashboardVideo ??= document.querySelector<HTMLVideoElement>('.video-bg video')
  if (open || status === 'running') {
    if (dashboardVideo && !dashboardVideo.paused) resumeDashboardVideo = true
    dashboardVideo?.pause()
    if (open && vehicleUpdateTimer) {
      clearTimeout(vehicleUpdateTimer)
      vehicleUpdateTimer = null
    }
    return
  }

  if (resumeDashboardVideo) void dashboardVideo?.play().catch(() => undefined)
  resumeDashboardVideo = false
}, { immediate: true })

async function bootstrapMap(): Promise<void> {
  if (!mapBox.value) return
  try {
    amapInstance = await initAMap(mapBox.value, () => { mapFailed.value = true })
    const map = amapInstance!.map
    map.on('movestart', handleMapMoveStart)
    map.on('moveend', handleMapMoveEnd)

    emergencyLine = new AMap.Polyline({
      path: [], strokeColor: '#00E5FF', strokeWeight: 8, strokeOpacity: 0.9, zIndex: 50,
    })
    emergencyLine.setMap(map)

    mapReady.value = true
    dataLoading.value = true

    // 后台加载真实数据，完成后一次性渲染
    await loadRealData()
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

}

function syncEmergency(): void {
  if (!emergencyLine) return
  if (systemMode.value === 'emergency' && emergencyRoute.value.length > 0) {
    // 将 CityFlow 路口 ID 映射为 mock 路口 ID
    const cfToMock = (cfId: string): string | null => {
      const direct = intersections.value.find((i) => i.id === cfId)
      if (direct) return direct.id
      const m = cfId.match(/^intersection_(\d+)_(\d+)$/)
      if (m) {
        const col = Number(m[1]), row = Number(m[2])
        const it = intersections.value.find((i) => i.col === col && i.row === row)
        return it ? it.id : null
      }
      return null
    }

    // 逐段找路网中的真实道路路径
    const allPts: [number, number][] = []
    for (let i = 0; i < emergencyRoute.value.length - 1; i++) {
      const fromMock = cfToMock(emergencyRoute.value[i])
      const toMock = cfToMock(emergencyRoute.value[i + 1])
      if (!fromMock || !toMock) continue

      // 找连接这两个 mock 路口的路
      const road = roads.value.find(
        (r) => (r.from === fromMock && r.to === toMock) || (r.from === toMock && r.to === fromMock)
      )
      if (road && road.path && road.path.length >= 2) {
        const path = road.from === fromMock ? road.path : [...road.path].reverse()
        for (const pt of path) {
          allPts.push([pt[0], pt[1]])
        }
      } else {
        // 降级：直连路口中心
        const fi = intersections.value.find((it) => it.id === fromMock)
        const ti = intersections.value.find((it) => it.id === toMock)
        if (fi && ti) {
          if (allPts.length === 0) allPts.push([fi.lng, fi.lat])
          allPts.push([ti.lng, ti.lat])
        }
      }
    }
    if (allPts.length >= 2) {
      emergencyLine.setPath(allPts)
      emergencyLine.show()
    }
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
watch(emergencyRoute, () => { if (systemMode.value === 'emergency') syncEmergency() }, { deep: true })

// 单击路口 marker → 镜头拉近放大到该路口
watch(selectedIntersectionId, (id) => {
  if (!id || !amapInstance) return
  const it = intersections.value.find((i) => i.id === id)
  if (it) {
  amapInstance.map.setZoomAndCenter(15, [it.lng, it.lat])
  }
})

// ---- 仿真车辆图层：路网就绪后按帧刷新 ----
watch([simulationVehicles, simRoadnet, simulationStatus, viewerOpen], () => {
  if (viewerOpen.value) return
  if (simulationStatus.value !== 'running' || !amapInstance || !simRoadnet.value) {
    if (vehicleUpdateTimer) {
      clearTimeout(vehicleUpdateTimer)
      vehicleUpdateTimer = null
    }
    vehicleLayer?.dispose()
    vehicleLayer = null
    return
  }
  scheduleVehicleLayerUpdate()
})

function onMapDblClick(): void {
  if (selectedIntersectionId.value) viewerOpen.value = true
}

setTimeout(bootstrapMap, 100)

onBeforeUnmount(() => {
  if (resumeDashboardVideo) void dashboardVideo?.play().catch(() => undefined)
  if (vehicleUpdateTimer) clearTimeout(vehicleUpdateTimer)
  roadLayer?.dispose()
  tlMarkers?.dispose()
  vehicleLayer?.dispose()
  emergencyLine?.setMap(null)
  amapInstance?.destroy()
})

const overview = computed(() => [
  { label: '路口总数', value: String(intersections.value.length), unit: '个' },
  { label: '设备在线率', value: store.statistics.deviceOnlineRate.toFixed(1), unit: '%' },
  { label: '监测车辆', value: String(
    simulationStatus.value === 'running' ? simulationVehicles.value.length : vehicles.value.length,
  ), unit: '辆' },
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
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <div v-show="!viewerOpen" class="mrn-viewport">
        <RoadNetwork v-if="mapFailed" class="mrn-fallback" />
        <div v-show="!mapFailed" ref="mapBox" class="mrn-map" @dblclick="onMapDblClick" />

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

        <MapLegend :visible="!mapFailed && !dataLoading" />


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
</style>
