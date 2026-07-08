<script setup lang="ts">
// ================================================================
// MapRoadNetwork — MapLibre + Three.js CustomLayer 城市级数字孪生路网
// LOD1/LOD2：MapLibre 原生图层（道路线 + DOM Marker + 应急线）
// LOD3 (zoom≥16)：Three.js CustomLayer（3D道路 + 车辆 + 路口节点）
// 离线降级：RoadNetwork.vue（Three.js 抽象路网）
// ================================================================
import { ref, watch, onBeforeUnmount, computed } from 'vue'
import { storeToRefs } from 'pinia'
import * as THREE from 'three'
import { useTrafficStore } from '@/stores/traffic'
import { initMap, type MapInstance } from '@/map/initMap'
import { addRoadLayer } from '@/map/roadLayer'
import { createTrafficLightMarkers, type TrafficMarker } from '@/map/trafficLightLayer'
import { createEmergencyLayer } from '@/map/emergencyLayer'
import { createCustomLayer, type CustomLayerHandle } from '@/three/CustomLayerManager'
import { RoadMeshManager } from '@/three/RoadMeshManager'
import { IntersectionNodeManager } from '@/three/IntersectionNodeManager'
import { VehicleInstancedManager } from '@/three/VehicleInstancedManager'
import RoadNetwork from '@/components/RoadNetwork.vue'
import MapLegend from '@/components/MapLegend.vue'
import Intersection3DViewer from '@/components/Intersection3DViewer.vue'

const store = useTrafficStore()
const {
  intersections, roads, vehicles,
  systemMode, emergencyRoute, activeGreenWaveIndex,
  selectedIntersectionId, mapZoom,
} = storeToRefs(store)

// ---- 状态 ----
const mapBox = ref<HTMLDivElement | null>(null)
const mapReady = ref(false)
const mapFailed = ref(false)
const viewerOpen = ref(false)
const lod = ref<'LOD1' | 'LOD2' | 'LOD3'>('LOD1')
let mapInstance: MapInstance | null = null

// 原生图层
let markers: TrafficMarker[] = []
let markersUpdateAll: ((its: typeof intersections.value) => void) | null = null
let markersDispose: (() => void) | null = null
let updateRoadsFn: { update: (its: typeof intersections.value, rds: typeof roads.value) => void } | null = null
let emergencyCtrl: ReturnType<typeof createEmergencyLayer> | null = null

// Three.js CustomLayer（LOD3）
let lod3Active = false
const lod3Scene = new THREE.Scene()
let customLayerHandle: CustomLayerHandle | null = null
let roadMeshMgr: RoadMeshManager | null = null
let nodeMeshMgr: IntersectionNodeManager | null = null
let vehicleMgr: VehicleInstancedManager | null = null
let raycasterAnimId: number | null = null

// ---- 应急路段集合 ----
function getEmergencyRoadIds(): Set<string> {
  if (systemMode.value !== 'emergency') return new Set()
  const ids = new Set<string>()
  const route = emergencyRoute.value
  for (let i = 0; i < route.length - 1; i++) {
    const r = roads.value.find(
      (x) => (x.from === route[i] && x.to === route[i + 1]) ||
             (x.from === route[i + 1] && x.to === route[i]),
    )
    if (r) ids.add(r.id)
  }
  return ids
}

// ---- 激活/隐藏 LOD3 CustomLayer ----
function toggleLOD3(active: boolean): void {
  if (!mapInstance) return
  const { map } = mapInstance

  if (active && !lod3Active) {
    // 初始化 Three.js 道路 Mesh
    if (!roadMeshMgr) {
      roadMeshMgr = new RoadMeshManager()
      roadMeshMgr.build(intersections.value, roads.value)
      lod3Scene.add(roadMeshMgr.group)
    }
    if (!nodeMeshMgr) {
      nodeMeshMgr = new IntersectionNodeManager()
      nodeMeshMgr.build(intersections.value)
      lod3Scene.add(nodeMeshMgr.group)
    }
    if (!vehicleMgr) {
      vehicleMgr = new VehicleInstancedManager()
      lod3Scene.add(vehicleMgr.group)
    }

    // 添加 CustomLayer
    customLayerHandle = createCustomLayer(lod3Scene, map)
    map.addLayer(customLayerHandle.layer)

    // 隐藏原生道路线层
    if (map.getLayer('roads-layer')) {
      map.setLayoutProperty('roads-layer', 'visibility', 'none')
    }

    lod3Active = true
  } else if (!active && lod3Active) {
    // 移除 CustomLayer
    try { map.removeLayer('three-custom-layer') } catch { /* */ }
    customLayerHandle?.dispose()
    customLayerHandle = null
    // 恢复原生道路线层
    if (map.getLayer('roads-layer')) {
      map.setLayoutProperty('roads-layer', 'visibility', 'visible')
    }
    lod3Active = false
  }
}

// ---- 初始化地图 ----
function bootstrapMap(): void {
  if (!mapBox.value) return
  try {
    mapInstance = initMap(
      mapBox.value,
      (z) => {
        store.updateMapZoom(z)
        const newLod: 'LOD1' | 'LOD2' | 'LOD3' = z >= 14 ? 'LOD3' : z >= 13 ? 'LOD2' : 'LOD1'
        if (newLod !== lod.value) {
          lod.value = newLod
          toggleLOD3(newLod === 'LOD3')
        }
      },
      () => { mapFailed.value = true },
    )
    const { map } = mapInstance

    // 红绿灯 DOM Marker（全 LOD 可用）
    const tl = createTrafficLightMarkers(map, intersections.value, (id) => store.selectIntersection(id))
    markers = tl.markers
    markersUpdateAll = tl.updateAll
    markersDispose = tl.dispose

    const addMapData = () => {
      if (updateRoadsFn) return
      try {
        updateRoadsFn = addRoadLayer(map, intersections.value, roads.value)
        emergencyCtrl = createEmergencyLayer(map)
        tl.syncByZoom(map.getZoom())
        syncEmergency()
        mapReady.value = true
        // 初始化 LOD 状态（默认 zoom 13）
        if (map.getZoom() >= 14) toggleLOD3(true)
      } catch {
        mapFailed.value = true
      }
    }

    if (map.isStyleLoaded()) {
      addMapData()
    } else {
      map.on('load', addMapData)
    }

    map.on('zoom', () => tl.syncByZoom(map.getZoom()))
  } catch {
    mapFailed.value = true
  }
}

// ---- 应急同步 ----
function syncEmergency(): void {
  if (!emergencyCtrl) return
  if (systemMode.value === 'emergency') {
    emergencyCtrl.activate(emergencyRoute.value, roads.value, intersections.value)
  } else {
    emergencyCtrl.deactivate()
  }
  // LOD3 时同步道路 Mesh 颜色
  if (lod3Active && roadMeshMgr) {
    roadMeshMgr.update(roads.value, systemMode.value === 'emergency' ? getEmergencyRoadIds() : null)
  }
}

// ---- store → Three.js CustomLayer ----
watch([roads, intersections], () => {
  if (updateRoadsFn) updateRoadsFn.update(intersections.value, roads.value)
  if (markersUpdateAll) markersUpdateAll(intersections.value)
  if (lod3Active && roadMeshMgr) {
    roadMeshMgr.update(roads.value, systemMode.value === 'emergency' ? getEmergencyRoadIds() : null)
  }
  if (lod3Active && nodeMeshMgr) {
    const gw = new Set<string>()
    if (systemMode.value === 'emergency') {
      emergencyRoute.value.forEach((id, i) => { if (i <= activeGreenWaveIndex.value) gw.add(id) })
    }
    nodeMeshMgr.update(intersections.value, selectedIntersectionId.value, gw)
  }
})

watch(systemMode, () => {
  syncEmergency()
  if (lod3Active && roadMeshMgr) {
    roadMeshMgr.update(roads.value, systemMode.value === 'emergency' ? getEmergencyRoadIds() : null)
  }
})

// LOD3 车辆动画循环
function vehicleLoop(): void {
  if (!lod3Active || !vehicleMgr) return
  vehicleMgr.update(vehicles.value, roads.value, intersections.value)
  vehicleMgr.animate(16)
  raycasterAnimId = requestAnimationFrame(vehicleLoop)
}

watch(lod, (cur) => {
  if (cur === 'LOD3' && lod3Active) {
    raycasterAnimId = requestAnimationFrame(vehicleLoop)
  } else if (cur !== 'LOD3' && raycasterAnimId !== null) {
    cancelAnimationFrame(raycasterAnimId)
    raycasterAnimId = null
  }
})

// ---- 双击路口 → 全景 ----
function onMapDblClick(): void {
  if (selectedIntersectionId.value) viewerOpen.value = true
}

// ---- 生命周期 ----
setTimeout(bootstrapMap, 100)

onBeforeUnmount(() => {
  if (raycasterAnimId !== null) cancelAnimationFrame(raycasterAnimId)
  customLayerHandle?.dispose()
  vehicleMgr?.dispose()
  nodeMeshMgr?.dispose()
  roadMeshMgr?.dispose()
  markersDispose?.()
  emergencyCtrl = null
  mapInstance?.destroy()
})

// ---- 概览指标 ----
const overview = computed(() => [
  { label: '路口总数', value: String(intersections.value.length), unit: '个' },
  { label: '设备在线率', value: store.statistics.deviceOnlineRate.toFixed(1), unit: '%' },
  { label: '监测车辆', value: String(vehicles.value.length), unit: '辆' },
  { label: '拥堵路段', value: String(store.statistics.congestedRoadCount), unit: '条' },
])
const selectedName = computed(
  () => intersections.value.find((it) => it.id === selectedIntersectionId.value)?.name ?? '',
)
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">城市路网数字孪生</span>
        <span v-if="mapReady" class="mrn-badge mrn-badge--live">
          <span class="status-dot status-dot--live" /> MapLibre · LOD{{ lod.slice(-1) }}
        </span>
        <span v-else-if="mapFailed" class="mrn-badge mrn-badge--fallback">
          <span class="status-dot status-dot--warning" /> 离线/降级
        </span>
        <span class="titlebar-deco"><i /><i /><i /></span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <div class="mrn-overview">
        <div v-for="o in overview" :key="o.label" class="mrn-metric">
          <span class="mrn-metric__value">{{ o.value }}</span>
          <span class="mrn-metric__unit">{{ o.unit }}</span>
          <div class="mrn-metric__label">{{ o.label }}</div>
        </div>
      </div>

      <div class="mrn-viewport">
        <RoadNetwork v-if="mapFailed" class="mrn-fallback" />
        <div
          v-show="!mapFailed"
          ref="mapBox"
          class="mrn-map"
          @dblclick="onMapDblClick"
        />
        <MapLegend :visible="!mapFailed" />

        <div v-if="selectedName" class="mrn-selected-info">
          <div class="mrn-selected-info__name">{{ selectedName }}</div>
          <button class="mrn-enter-btn" @click="viewerOpen = true">进入三维实景 ⛶</button>
        </div>

        <div class="mrn-hint" v-if="!mapFailed">
          <span>🖱 拖拽/滚轮 · 单击标记 · 双击进全景 | LOD: {{ lod }}</span>
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
.comp-card__body { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 10px; overflow: hidden; }
.mrn-badge { margin-left: 10px; font-size: 10px; padding: 3px 8px; display: inline-flex; align-items: center; gap: 4px; }
.mrn-badge--live { border: 1px solid rgba(34,211,160,0.5); color: #22d3a0; background: rgba(34,211,160,0.08); }
.mrn-badge--fallback { border: 1px solid rgba(255,184,0,0.5); color: #ffb800; background: rgba(255,184,0,0.08); }
.mrn-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; flex: 0 0 auto; }
.mrn-metric { padding: 6px 12px; text-align: center; background: rgba(0,212,255,0.05); border: 1px solid rgba(0,212,255,0.18); }
.mrn-metric__value { font-family: 'DINPro','Rajdhani',sans-serif; font-size: clamp(16px, 1.4vw, 24px); font-weight: 700; color: #00d4ff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mrn-metric__unit { margin-left: 3px; font-size: 11px; color: #8da8c5; white-space: nowrap; }
.mrn-metric__label { margin-top: 1px; font-size: 11px; color: #5a7595; letter-spacing: 0.04em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mrn-viewport { flex: 1; min-height: 0; position: relative; border: 1.5px solid rgba(0,212,255,0.42); overflow: hidden; }
.mrn-map { width: 100%; height: 100%; }
.mrn-fallback { width: 100%; height: 100%; }
.mrn-selected-info { position: absolute; top: 10px; left: 10px; z-index: 5; min-width: 140px; padding: 8px 10px; background: rgba(4,21,39,0.9); border: 1px solid rgba(0,212,255,0.4); backdrop-filter: blur(6px); }
.mrn-selected-info__name { font-size: 13px; font-weight: 700; color: #7af7ff; text-shadow: 0 0 8px rgba(0,212,255,0.4); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 6px; }
.mrn-enter-btn { width: 100%; padding: 4px 8px; font-size: 11px; color: #00d4ff; background: rgba(0,212,255,0.08); border: 1px solid rgba(0,212,255,0.4); cursor: pointer; transition: all 0.2s; white-space: nowrap; }
.mrn-enter-btn:hover { background: rgba(0,212,255,0.18); box-shadow: 0 0 12px rgba(0,212,255,0.3); }
.mrn-hint { position: absolute; top: 8px; right: 10px; z-index: 4; font-size: 10px; color: #8da8c5; padding: 3px 8px; background: rgba(4,21,39,0.7); border: 1px solid rgba(0,212,255,0.15); pointer-events: none; }
</style>
