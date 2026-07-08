<script setup lang="ts">
// ================================================================
// EmergencyPanel — 应急绿波控制面板
// 驱动应急车辆模拟、绿波激活、路线高亮（与 RoadNetwork 联动）
// ================================================================
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'

const store = useTrafficStore()
const {
  systemMode,
  emergencyVehicle,
  emergencyRoute,
  activeGreenWaveIndex,
  intersections,
  vehicles,
  compareMetrics,
} = storeToRefs(store)

// ---- 本地状态：是否曾经触发过（区分"未触发"与"已完成"） ----
const wasEverTriggered = ref(false)

// ---- 应急车辆类型查找 ----
const emergencyVehOnRoad = computed(() =>
  vehicles.value.find((v) => v.id === 'E001' && v.type !== 'normal'),
)

const vehicleTypeLabel = computed(() => {
  if (!emergencyVehOnRoad.value && !emergencyVehicle.value.greenWaveActive) return null
  const t = emergencyVehicle.value.type
  return t === 'ambulance' ? '救护车 🚑' : t === 'firetruck' ? '消防车 🚒' : '应急车辆'
})

const vehicleTypeIcon = computed(() => {
  const t = emergencyVehicle.value.type
  return t === 'ambulance' ? '🚑' : '🚒'
})

// ---- 起点 / 终点名称（通过 intersection ID 查找） ----
const startNodeName = computed(() => {
  const id = emergencyRoute.value[0]
  if (!id) return '—'
  return intersections.value.find((it) => it.id === id)?.name ?? id
})

const endNodeName = computed(() => {
  const id = emergencyRoute.value[emergencyRoute.value.length - 1]
  if (!id) return '—'
  return intersections.value.find((it) => it.id === id)?.name ?? id
})

// ---- 应急车辆实时速度 ----
const currentSpeed = computed(() => {
  return emergencyVehOnRoad.value?.speed ?? emergencyVehicle.value.greenWaveActive ? 62 : 0
})

// ---- 预计到达时间（分钟） ----
const estimatedEta = computed(() => {
  return emergencyVehicle.value.eta
})

// ---- 已激活绿波路口数 ----
const activatedNodeCount = computed(() => {
  if (!emergencyVehicle.value.greenWaveActive || activeGreenWaveIndex.value < 0) return 0
  return Math.min(activeGreenWaveIndex.value + 1, emergencyRoute.value.length)
})

const totalRouteNodes = computed(() => emergencyRoute.value.length)

// ---- 预计节省时间 ----
const timeSaved = computed(() => {
  const m = compareMetrics.value.emergencyPassTime
  return +(m.traditional - m.ai).toFixed(1)
})

// ---- 绿波路线节点列表（用于内联展示） ----
const routeNodeLabels = computed(() =>
  emergencyRoute.value.map((id) => {
    const it = intersections.value.find((i) => i.id === id)
    return { id, name: it?.name ?? id }
  }),
)

// ---- 应急阶段状态 ----
type EmergencyPhase = 'idle' | 'planning' | 'executing' | 'completed'

const emergencyPhase = computed<EmergencyPhase>(() => {
  if (!wasEverTriggered.value) return 'idle'
  if (systemMode.value === 'emergency' && emergencyVehicle.value.greenWaveActive) return 'executing'
  if (wasEverTriggered.value && systemMode.value === 'normal') return 'completed'
  // 绿波未激活但车辆在路上
  if (wasEverTriggered.value && emergencyVehOnRoad.value) return 'planning'
  return 'completed'
})

const phaseMeta = computed(() => {
  const map: Record<EmergencyPhase, { label: string; tone: string; icon: string; cls: string }> = {
    idle:      { label: '未触发',   tone: 'muted',   icon: '⏸️', cls: 'ep-phase--idle' },
    planning:  { label: '绿波规划中', tone: 'amber',  icon: '📋', cls: 'ep-phase--planning' },
    executing: { label: '绿波执行中', tone: 'rose',   icon: '🚨', cls: 'ep-phase--executing' },
    completed: { label: '已完成',    tone: 'emerald', icon: '✅', cls: 'ep-phase--completed' },
  }
  return map[emergencyPhase.value]
})

// ---- 按钮禁用状态 ----
const canSimulate = computed(() => emergencyPhase.value === 'idle' || emergencyPhase.value === 'completed')
const canStartWave = computed(() =>
  // 车辆存在但绿波未激活（planning 阶段）或已完成想重新激活
  (emergencyPhase.value === 'planning' || emergencyPhase.value === 'completed') &&
  emergencyVehOnRoad.value != null,
)
const canRestore = computed(() => systemMode.value === 'emergency')

// ================================================================
// 按钮处理
// ================================================================

function handleSimulateVehicle(): void {
  store.simulateEmergencyVehicle()
  wasEverTriggered.value = true
}

function handleStartGreenWave(): void {
  store.startEmergencyGreenWave()
  wasEverTriggered.value = true
  store.generateMockAlert(
    'green_wave_start',
    'emergency',
    `应急绿波启动成功 — ${vehicleTypeLabel.value ?? '应急车辆'}优先通行`,
    `${startNodeName.value} → ${endNodeName.value}`,
    emergencyRoute.value[0],
  )
}

function handleRestoreNormal(): void {
  store.restoreNormalMode()
  store.generateMockAlert(
    'green_wave_restore',
    'info',
    '已恢复普通控制模式，应急绿波通道关闭',
    '系统操作 · 应急控制面板',
  )
}

// ---- 绿波进度百分比 ----
const waveProgress = computed(() => {
  if (totalRouteNodes.value <= 1) return 0
  return (activatedNodeCount.value / totalRouteNodes.value) * 100
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">应急绿波控制</span>
        <span
          v-if="emergencyPhase !== 'idle'"
          class="ep-phase-badge"
          :class="phaseMeta.cls"
        >
          <span>{{ phaseMeta.icon }}</span>
          {{ phaseMeta.label }}
        </span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- ===== 应急车辆信息 ===== -->
      <div v-if="emergencyPhase !== 'idle'" class="ep-vehicle-card">
        <div class="ep-vehicle-card__header">
          <span class="ep-vehicle-card__icon">{{ vehicleTypeIcon }}</span>
          <div>
            <div class="ep-vehicle-card__type">{{ vehicleTypeLabel }}</div>
            <div class="ep-vehicle-card__id">ID: {{ emergencyVehicle.id }}</div>
          </div>
          <span class="status-dot" :class="emergencyPhase === 'executing' ? 'status-dot--danger' : 'status-dot--live'" />
        </div>

        <div class="ep-vehicle-card__route">
          <div class="ep-route-row">
            <span class="ep-route-label">起点</span>
            <span class="ep-route-value text-cyan">{{ startNodeName }}</span>
          </div>
          <div class="ep-route-arrow">→</div>
          <div class="ep-route-row">
            <span class="ep-route-label">终点</span>
            <span class="ep-route-value text-cyan">{{ endNodeName }}</span>
          </div>
          <div class="ep-route-dest">{{ emergencyVehicle.destination }}</div>
        </div>

        <div class="ep-vehicle-card__metrics">
          <div class="ep-vm">
            <span class="ep-vm__label">当前速度</span>
            <span class="ep-vm__value text-emerald">{{ currentSpeed }} km/h</span>
          </div>
          <div class="ep-vm">
            <span class="ep-vm__label">预计到达</span>
            <span class="ep-vm__value text-cyan">{{ estimatedEta }} min</span>
          </div>
          <div class="ep-vm">
            <span class="ep-vm__label">预计节省</span>
            <span class="ep-vm__value text-emerald">~{{ timeSaved }} min</span>
          </div>
        </div>
      </div>

      <!-- ===== 绿波路线进度 ===== -->
      <div v-if="emergencyPhase !== 'idle'" class="ep-wave-section">
        <div class="ep-wave-section__head">
          <span class="ep-wave-section__label">绿波路线</span>
          <span class="ep-wave-section__count">
            {{ activatedNodeCount }} / {{ totalRouteNodes }} 路口已放行
          </span>
        </div>

        <!-- 节点链 -->
        <div class="ep-node-chain">
          <template v-for="(n, idx) in routeNodeLabels" :key="n.id">
            <div
              class="ep-node-chip"
              :class="{
                'ep-node-chip--active': idx <= activeGreenWaveIndex && emergencyPhase === 'executing',
                'ep-node-chip--current': idx === activeGreenWaveIndex && emergencyPhase === 'executing',
              }"
            >
              <span class="ep-node-chip__dot" />
              <span class="ep-node-chip__id">{{ n.id }}</span>
            </div>
            <span v-if="idx < routeNodeLabels.length - 1" class="ep-node-chip__arrow">→</span>
          </template>
        </div>

        <!-- 进度条 -->
        <div class="ep-wave-bar">
          <div class="health-bar" style="flex:1;">
            <div
              class="health-bar-fill"
              :style="{ width: `${waveProgress}%`, background: 'linear-gradient(90deg, #00E5FF, #22D3A0)' }"
            />
          </div>
          <span class="ep-wave-bar__pct">{{ Math.round(waveProgress) }}%</span>
        </div>
      </div>

      <!-- ===== 当前状态（醒目展示） ===== -->
      <div class="ep-status-banner" :class="phaseMeta.cls">
        <div class="ep-status-banner__inner">
          <span class="ep-status-banner__icon">{{ phaseMeta.icon }}</span>
          <div>
            <div class="ep-status-banner__label">当前状态</div>
            <div class="ep-status-banner__value" :class="`text-${phaseMeta.tone}`">
              {{ phaseMeta.label }}
            </div>
          </div>
        </div>
      </div>

      <!-- ===== 一键激活大按钮（仅 idle 时醒目） ===== -->
      <button
        v-if="emergencyPhase === 'idle'"
        class="cyber-btn ep-activate-big"
        @click="handleSimulateVehicle"
      >
        <span class="ep-activate-big__icon">🚨</span>
        <div class="ep-activate-big__text">
          <div>模拟救护车进入路网</div>
          <div class="ep-activate-big__sub">激活应急绿波通道 · 优先放行沿线信号</div>
        </div>
      </button>

      <!-- ===== 控制按钮组 ===== -->
      <div v-else class="ep-actions">
        <button
          class="cyber-btn ep-action-btn ep-action-btn--sim"
          :disabled="!canSimulate"
          @click="handleSimulateVehicle"
        >
          <span>🚑</span> 新增应急车辆
        </button>
        <button
          class="cyber-btn ep-action-btn ep-action-btn--go"
          :disabled="!canStartWave"
          @click="handleStartGreenWave"
        >
          <span>🚨</span> 启动应急绿波
        </button>
        <button
          class="cyber-btn ep-action-btn ep-action-btn--stop"
          :disabled="!canRestore"
          @click="handleRestoreNormal"
        >
          <span>🔙</span> 恢复普通控制
        </button>
      </div>

      <!-- ===== 空状态占位 ===== -->
      <div v-if="emergencyPhase === 'idle'" class="ep-idle-placeholder">
        <div class="ep-idle-placeholder__icon">🟢</div>
        <div class="ep-idle-placeholder__text">路网运行正常，无应急事件</div>
        <div class="ep-idle-placeholder__desc">
          点击上方按钮模拟应急车辆进入路网<br>
          系统将自动规划绿波路线
        </div>
      </div>
    </div>
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
  gap: 10px;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 阶段徽章 */
.ep-phase-badge {
  margin-left: auto;
  margin-right: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
}

.ep-phase--idle      { border: 1px solid rgba(90, 117, 149, 0.4); color: #5a7595; background: rgba(90, 117, 149, 0.08); }
.ep-phase--planning  { border: 1px solid rgba(255, 184, 0, 0.5); color: #ffb800; background: rgba(255, 184, 0, 0.08); }
.ep-phase--executing { border: 1px solid rgba(255, 77, 109, 0.6); color: #ff4d6d; background: rgba(255, 77, 109, 0.1); animation: ep-phase-pulse 1s ease-in-out infinite; }
.ep-phase--completed { border: 1px solid rgba(34, 211, 160, 0.5); color: #22d3a0; background: rgba(34, 211, 160, 0.08); }

@keyframes ep-phase-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 77, 109, 0); }
  50% { box-shadow: 0 0 12px rgba(255, 77, 109, 0.45); }
}

/* 应急车辆卡片 */
.ep-vehicle-card {
  padding: 10px 12px;
  background: rgba(0, 212, 255, 0.05);
  border: 1px solid rgba(0, 212, 255, 0.2);
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.ep-vehicle-card__header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ep-vehicle-card__icon {
  font-size: 26px;
}

.ep-vehicle-card__type {
  font-size: 14px;
  font-weight: 700;
  color: #e8f4ff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ep-vehicle-card__id {
  margin-top: 1px;
  font-size: 10px;
  color: #5a7595;
  font-family: 'Rajdhani', sans-serif;
}

.ep-vehicle-card__route {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: rgba(4, 21, 39, 0.5);
  border: 1px solid rgba(0, 212, 255, 0.1);
  overflow: hidden;
}

.ep-route-row {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.ep-route-label {
  font-size: 10px;
  color: #5a7595;
  flex: 0 0 auto;
}

.ep-route-value {
  font-family: 'Rajdhani', sans-serif;
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ep-route-arrow {
  color: #5a7595;
  font-size: 12px;
  flex: 0 0 auto;
}

.ep-route-dest {
  width: 100%;
  margin-top: 2px;
  font-size: 10px;
  color: #8da8c5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 应急指标 3 列 */
.ep-vehicle-card__metrics {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 7px;
}

.ep-vm {
  text-align: center;
  padding: 6px 4px;
  background: rgba(4, 21, 39, 0.6);
  border: 1px solid rgba(0, 212, 255, 0.1);
  min-width: 0;
}

.ep-vm__label {
  font-size: 10px;
  color: #5a7595;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ep-vm__value {
  margin-top: 3px;
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 16px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 绿波路线 */
.ep-wave-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ep-wave-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ep-wave-section__label {
  font-size: 10px;
  color: #5a7595;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.ep-wave-section__count {
  font-size: 11px;
  color: #22d3a0;
  font-family: 'Rajdhani', sans-serif;
  font-weight: 600;
}

/* 节点链 */
.ep-node-chain {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
}

.ep-node-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  background: rgba(4, 21, 39, 0.6);
  border: 1px solid rgba(0, 212, 255, 0.15);
  font-size: 10px;
  color: #5a7595;
  transition: all 300ms ease;
}

.ep-node-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #5a7595;
  transition: all 300ms ease;
}

.ep-node-chip--active {
  border-color: rgba(34, 211, 160, 0.55);
  background: rgba(34, 211, 160, 0.08);
}

.ep-node-chip--active .ep-node-chip__dot {
  background: #22d3a0;
  box-shadow: 0 0 8px rgba(34, 211, 160, 0.8);
}

.ep-node-chip--current {
  border-color: #00e5ff;
  background: rgba(0, 229, 255, 0.12);
  color: #7af7ff;
  font-weight: 700;
  animation: ep-node-curr-pulse 0.8s ease-in-out infinite;
}

@keyframes ep-node-curr-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(0, 229, 255, 0); }
  50% { box-shadow: 0 0 12px rgba(0, 229, 255, 0.45); }
}

.ep-node-chip__arrow {
  color: #5a7595;
  font-size: 10px;
}

/* 绿波进度条 */
.ep-wave-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ep-wave-bar__pct {
  flex: 0 0 auto;
  font-family: 'Rajdhani', sans-serif;
  font-size: 12px;
  font-weight: 700;
  color: #00e5ff;
}

/* 状态横幅 */
.ep-status-banner {
  padding: 4px;
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
}

.ep-status-banner--idle      { background: rgba(90, 117, 149, 0.1); border: 1px solid rgba(90, 117, 149, 0.25); }
.ep-status-banner--planning  { background: rgba(255, 184, 0, 0.08); border: 1px solid rgba(255, 184, 0, 0.3); }
.ep-status-banner--executing { background: rgba(255, 77, 109, 0.12); border: 1px solid rgba(255, 77, 109, 0.5); animation: ep-banner-pulse 1.4s ease-in-out infinite; }
.ep-status-banner--completed { background: rgba(34, 211, 160, 0.08); border: 1px solid rgba(34, 211, 160, 0.3); }

@keyframes ep-banner-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 77, 109, 0); }
  50% { box-shadow: 0 0 18px rgba(255, 77, 109, 0.4); }
}

.ep-status-banner__inner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
}

.ep-status-banner__icon {
  font-size: 24px;
}

.ep-status-banner__label {
  font-size: 10px;
  color: #5a7595;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.ep-status-banner__value {
  margin-top: 2px;
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 20px;
  font-weight: 700;
}

/* 一键激活大按钮 */
.ep-activate-big {
  width: 100%;
  padding: 14px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-transform: none;
  letter-spacing: 0.04em;
  color: #ff4d6d;
  border-color: rgba(255, 77, 109, 0.6);
  border-width: 1.5px;
}

.ep-activate-big:hover {
  background: rgba(255, 77, 109, 0.14);
  box-shadow: 0 0 24px rgba(255, 77, 109, 0.4), inset 0 0 14px rgba(255, 77, 109, 0.1);
}

.ep-activate-big__icon {
  font-size: 28px;
}

.ep-activate-big__text {
  text-align: center;
  font-size: 14px;
  font-weight: 700;
}

.ep-activate-big__sub {
  margin-top: 2px;
  font-size: 10px;
  font-weight: 400;
  color: rgba(255, 77, 109, 0.6);
  letter-spacing: 0.04em;
}

/* 按钮组 */
.ep-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ep-action-btn {
  width: 100%;
  justify-content: center;
  text-transform: none;
  letter-spacing: 0.04em;
  font-size: 12px;
  padding: 9px 12px;
}

.ep-action-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
  filter: none;
}

.ep-action-btn--sim {
  border-color: rgba(0, 212, 255, 0.45);
  color: #00d4ff;
}

.ep-action-btn--sim:hover:not(:disabled) {
  background: rgba(0, 212, 255, 0.12);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.3);
}

.ep-action-btn--go {
  border-color: rgba(255, 77, 109, 0.5);
  color: #ff4d6d;
}

.ep-action-btn--go:hover:not(:disabled) {
  background: rgba(255, 77, 109, 0.14);
  box-shadow: 0 0 20px rgba(255, 77, 109, 0.4);
}

.ep-action-btn--stop {
  border-color: rgba(255, 184, 0, 0.45);
  color: #ffb800;
}

.ep-action-btn--stop:hover:not(:disabled) {
  background: rgba(255, 184, 0, 0.12);
  box-shadow: 0 0 16px rgba(255, 184, 0, 0.3);
}

/* 空状态 */
.ep-idle-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px 0;
}

.ep-idle-placeholder__icon {
  font-size: 32px;
  opacity: 0.4;
}

.ep-idle-placeholder__text {
  font-size: 13px;
  color: #8da8c5;
}

.ep-idle-placeholder__desc {
  font-size: 11px;
  color: #5a7595;
  text-align: center;
  line-height: 1.6;
}
</style>
