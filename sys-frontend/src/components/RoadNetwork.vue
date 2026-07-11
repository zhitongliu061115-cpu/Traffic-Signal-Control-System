<script setup lang="ts">
// ================================================================
// RoadNetwork — 城市路网数字孪生（Three.js 三维实现）
// 场景 / 路口 / 道路热力 / 车辆流动 / 信号灯 / 设备状态 / 应急绿波
// 点击路口 → store.selectIntersection → 打开 Intersection3DViewer
// ================================================================
import { ref, watch, onMounted, onBeforeUnmount, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { Raycaster, Vector2 } from 'three'
import { useTrafficStore } from '@/stores/traffic'
import { SceneManager } from '@/three/SceneManager'
import { LabelManager } from '@/three/LabelManager'
import { IntersectionManager } from '@/three/IntersectionManager'
import { RoadManager } from '@/three/RoadManager'
import { VehicleManager } from '@/three/VehicleManager'
import { EmergencyManager } from '@/three/EmergencyManager'
import Intersection3DViewer from '@/components/Intersection3DViewer.vue'

const store = useTrafficStore()
const {
  intersections,
  roads,
  vehicles,
  systemMode,
  emergencyRoute,
  activeGreenWaveIndex,
  selectedIntersectionId,
  statistics,
} = storeToRefs(store)

// ---- 容器 & 管理器 ----
const canvasBox = ref<HTMLDivElement | null>(null)
let sceneMgr: SceneManager | null = null
let labelMgr: LabelManager | null = null
let intersectionMgr: IntersectionManager | null = null
let roadMgr: RoadManager | null = null
let vehicleMgr: VehicleManager | null = null
let emergencyMgr: EmergencyManager | null = null

const raycaster = new Raycaster()
const pointer = new Vector2()

const emergencyActive = computed(() => systemMode.value === 'emergency')
const viewerOpen = ref(false)

// ---- 派生集合 ----
function computeEmergencySets() {
  const active = emergencyActive.value
  const roadIds = emergencyMgr?.emergencyRoadIds(emergencyRoute.value, active) ?? new Set<string>()
  const nodeIds = emergencyMgr?.greenWaveIds(emergencyRoute.value, activeGreenWaveIndex.value, active) ?? new Set<string>()
  return { roadIds, nodeIds }
}

/** 全量同步 store → 三维对象 */
function syncAll(): void {
  if (!intersectionMgr || !roadMgr || !vehicleMgr) return
  const { roadIds, nodeIds } = computeEmergencySets()
  roadMgr.update(roads.value, roadIds)
  intersectionMgr.update(intersections.value, selectedIntersectionId.value, nodeIds)
  vehicleMgr.update(vehicles.value)
}

// ---- 点击路口（Raycaster）----
function onPointerDown(ev: PointerEvent): void {
  if (!sceneMgr || !intersectionMgr || !canvasBox.value) return
  const rect = canvasBox.value.getBoundingClientRect()
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1

  raycaster.setFromCamera(pointer, sceneMgr.camera)
  const hits = raycaster.intersectObjects(intersectionMgr.raycastTargets, false)
  if (hits.length > 0) {
    const id = intersectionMgr.resolveId(hits[0]!.object)
    if (id) {
      store.selectIntersection(selectedIntersectionId.value === id ? null : id)
    }
  }
}

// ---- 双击路口打开实景视图 ----
function onDblClick(): void {
  if (selectedIntersectionId.value) {
    viewerOpen.value = true
  }
}

function closeViewer(): void {
  viewerOpen.value = false
}

// ---- 初始化 Three.js ----
onMounted(() => {
  if (!canvasBox.value) return

  sceneMgr = new SceneManager(canvasBox.value)
  labelMgr = new LabelManager()
  intersectionMgr = new IntersectionManager(labelMgr)
  roadMgr = new RoadManager(labelMgr)
  vehicleMgr = new VehicleManager(roadMgr)
  emergencyMgr = new EmergencyManager()

  // 构建静态几何
  roadMgr.build(intersections.value, roads.value)
  intersectionMgr.build(intersections.value)
  emergencyMgr.setRoads(roads.value)

  sceneMgr.add(roadMgr.group)
  sceneMgr.add(intersectionMgr.group)
  sceneMgr.add(vehicleMgr.group)

  // 首次同步
  syncAll()

  // 每帧动画
  sceneMgr.onUpdate((deltaMs) => {
    intersectionMgr?.animate(deltaMs, intersections.value)
    vehicleMgr?.animate(deltaMs)
  })

  sceneMgr.start()

  const el = sceneMgr.renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('dblclick', onDblClick)
})

// ---- 响应 store 数据变化 ----
watch([intersections, roads, vehicles, selectedIntersectionId, systemMode, activeGreenWaveIndex], syncAll, { deep: true })

// 选中路口时相机平滑飞近
watch(selectedIntersectionId, (id) => {
  if (id && sceneMgr && intersectionMgr) {
    const pos = intersectionMgr.worldPositionOf(id)
    if (pos) sceneMgr.flyTo(pos, 340)
  }
})

onBeforeUnmount(() => {
  const el = sceneMgr?.renderer.domElement
  if (el) {
    el.removeEventListener('pointerdown', onPointerDown)
    el.removeEventListener('dblclick', onDblClick)
  }
  vehicleMgr?.dispose()
  roadMgr?.dispose()
  intersectionMgr?.dispose()
  labelMgr?.dispose()
  sceneMgr?.dispose()
  sceneMgr = null
  labelMgr = null
  intersectionMgr = null
  roadMgr = null
  vehicleMgr = null
  emergencyMgr = null
})

// ---- 顶部概览指标 ----
const overview = computed(() => [
  { label: '路口总数', value: String(intersections.value.length), unit: '个' },
  { label: '设备在线率', value: statistics.value.deviceOnlineRate.toFixed(1), unit: '%' },
  { label: '监测车辆', value: String(vehicles.value.length), unit: '辆' },
  { label: '拥堵路段', value: String(statistics.value.congestedRoadCount), unit: '条' },
])

// ---- 图例 ----
const legend = [
  { name: '畅通', color: '#22D3A0', shape: 'road' },
  { name: '缓行', color: '#FFB800', shape: 'road' },
  { name: '拥堵', color: '#FF7A45', shape: 'road' },
  { name: '严重拥堵', color: '#FF4D6D', shape: 'road' },
  { name: '应急绿波', color: '#00E5FF', shape: 'route' },
  { name: '设备在线', color: '#22D3A0', shape: 'dot' },
  { name: '设备故障', color: '#FF4D6D', shape: 'dot' },
] as const

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
        <span
          v-if="emergencyActive"
          class="hud-pill hud-pill--rose rn-emergency-badge"
        >
          🚨 应急绿波运行中
        </span>
        <span class="titlebar-deco"><i /><i /><i /></span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- 顶部概览指标 -->
      <div class="rn-overview">
        <div v-for="o in overview" :key="o.label" class="rn-metric">
          <span class="rn-metric__value">{{ o.value }}</span>
          <span class="rn-metric__unit">{{ o.unit }}</span>
          <div class="rn-metric__label">{{ o.label }}</div>
        </div>
      </div>

      <!-- Three.js 视口 -->
      <div class="rn-viewport">
        <div ref="canvasBox" class="rn-canvas" />

        <!-- 操作提示 -->
        <div class="rn-hint">
          <span>🖱 拖拽旋转 · 滚轮缩放 · 单击选中 · 双击进入实景</span>
        </div>

        <!-- 选中路口浮层 -->
        <div v-if="selectedName" class="rn-selected-info">
          <div class="rn-selected-info__name">{{ selectedName }}</div>
          <button class="rn-enter-btn" @click="viewerOpen = true">进入三维实景 ⛶</button>
        </div>

        <!-- 图例 -->
        <div class="rn-legend">
          <span v-for="l in legend" :key="l.name" class="rn-legend__item">
            <span
              v-if="l.shape === 'road'"
              class="rn-legend__road"
              :style="{ background: l.color }"
            />
            <span
              v-else-if="l.shape === 'route'"
              class="rn-legend__route"
              :style="{ background: l.color }"
            />
            <span
              v-else
              class="rn-legend__dot"
              :style="{ background: l.color }"
            />
            {{ l.name }}
          </span>
        </div>
      </div>
    </div>

    <!-- 路口三维实景弹窗 -->
    <Intersection3DViewer
      v-if="viewerOpen"
      :intersection-id="selectedIntersectionId"
      @close="closeViewer"
    />
  </section>
</template>

<style scoped>
.comp-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.comp-card__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

/* 应急徽章 */
.rn-emergency-badge {
  margin-left: 14px;
  font-size: 11px;
  padding: 3px 10px;
  animation: rn-badge-pulse 1.4s ease-in-out infinite;
}

@keyframes rn-badge-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 77, 109, 0); }
  50% { box-shadow: 0 0 14px rgba(255, 77, 109, 0.5); }
}

/* 概览指标 */
.rn-overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  flex: 0 0 auto;
}

.rn-metric {
  padding: 7px 14px;
  text-align: center;
  background: rgba(0, 212, 255, 0.05);
  border: 1px solid rgba(0, 212, 255, 0.18);
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
}

.rn-metric__value {
  font-family: 'DINPro', 'Rajdhani', sans-serif;
  font-size: clamp(18px, 1.6vw, 26px);
  font-weight: 700;
  color: #00d4ff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rn-metric__unit {
  margin-left: 4px;
  font-size: 12px;
  color: #8da8c5;
  white-space: nowrap;
}

.rn-metric__label {
  margin-top: 1px;
  font-size: 12px;
  color: #5a7595;
  letter-spacing: 0.04em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 视口 */
.rn-viewport {
  flex: 1;
  min-height: 0;
  position: relative;
  border: 1.5px solid rgba(0, 212, 255, 0.42);
  overflow: hidden;
  box-shadow:
    inset 0 0 40px rgba(0, 212, 255, 0.06),
    0 0 24px rgba(0, 212, 255, 0.12);
}

.rn-canvas {
  width: 100%;
  height: 100%;
}

/* 操作提示 */
.rn-hint {
  position: absolute;
  top: 10px;
  right: 12px;
  z-index: 3;
  font-size: 11px;
  color: #8da8c5;
  padding: 4px 10px;
  background: rgba(4, 21, 39, 0.72);
  border: 1px solid rgba(0, 212, 255, 0.2);
  backdrop-filter: blur(4px);
  pointer-events: none;
}

/* 选中信息浮层 */
.rn-selected-info {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 3;
  min-width: 160px;
  padding: 10px 12px;
  background: rgba(4, 21, 39, 0.9);
  border: 1px solid rgba(0, 212, 255, 0.4);
  backdrop-filter: blur(8px);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  box-shadow: 0 0 20px rgba(0, 212, 255, 0.2);
}

.rn-selected-info__name {
  font-size: 14px;
  font-weight: 700;
  color: #7af7ff;
  margin-bottom: 8px;
  text-shadow: 0 0 10px rgba(0, 212, 255, 0.5);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rn-enter-btn {
  width: 100%;
  padding: 5px 8px;
  font-size: 12px;
  color: #00d4ff;
  background: rgba(0, 212, 255, 0.08);
  border: 1px solid rgba(0, 212, 255, 0.4);
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.rn-enter-btn:hover {
  background: rgba(0, 212, 255, 0.18);
  box-shadow: 0 0 14px rgba(0, 212, 255, 0.3);
}

/* 图例 */
.rn-legend {
  position: absolute;
  right: 12px;
  bottom: 10px;
  z-index: 3;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  max-width: calc(100% - 24px);
  justify-content: flex-end;
  padding: 7px 14px;
  background: rgba(4, 21, 39, 0.75);
  border: 1px solid rgba(0, 212, 255, 0.24);
  backdrop-filter: blur(6px);
}

.rn-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #8da8c5;
  white-space: nowrap;
}

.rn-legend__road {
  width: 16px;
  height: 3.5px;
  border-radius: 2px;
  box-shadow: 0 0 6px currentColor;
}

.rn-legend__route {
  width: 16px;
  height: 3.5px;
  border-radius: 2px;
  box-shadow: 0 0 8px currentColor;
  opacity: 0.95;
}

.rn-legend__dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  box-shadow: 0 0 6px currentColor;
}
</style>
