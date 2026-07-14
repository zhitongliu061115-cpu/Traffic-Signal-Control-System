<script setup lang="ts">
// ================================================================
// EmergencyPanel — 应急绿波控制面板
// 驱动应急车辆模拟、绿波激活、路线高亮（与 RoadNetwork 联动）
// ================================================================
import { computed, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'
import type { EmergencyEvType } from '@/types/traffic'

const store = useTrafficStore()
const {
  systemMode,
  emergencyVehicle,
  emergencyCfVehicleId,
  emergencyRoute,
  activeGreenWaveIndex,
  intersections,
  vehicles,
  simulationVehicles,
  compareMetrics,
  latestEvEvents,
  latestEvStatus,
} = storeToRefs(store)

// ---- 本地状态：是否曾经触发过（区分"未触发"与"已完成"） ----
const wasEverTriggered = ref(false)

// ---- Dialog 状态 ----
const showDispatchDialog = ref(false)
const dispatchDialogX = ref(0)
const dispatchDialogY = ref(0)
const dispatching = ref(false)
const dispatchResult = ref<{
  evId: string
  route: string[]
  routeRoads: string[]
  estimatedTravelTime: number
  startName: string
  endName: string
} | null>(null)

const dispatchForm = reactive({
  startId: 'A01' as string,
  endId: 'A10' as string,
  vehicleType: 'ambulance' as EmergencyEvType,
  priority: 3 as number,
})


/** 确认按钮是否可用 */
const canDispatch = computed(
  () =>
    dispatchForm.startId !== dispatchForm.endId &&
    dispatchForm.startId !== '' &&
    dispatchForm.endId !== '' &&
    !dispatching.value,
)

// ---- 应急车辆类型查找（通过 CityFlow 分配的 cfVehicleId 匹配）----
const emergencyVehOnRoad = computed(() => {
  const cfId = emergencyCfVehicleId.value
  if (!cfId) return undefined
  return simulationVehicles.value.find((v) => v.id === cfId)
    ?? vehicles.value.find((v) => v.id === cfId)
})

const vehicleTypeLabel = computed(() => {
  if (!emergencyVehOnRoad.value && !emergencyVehicle.value.greenWaveActive) return null
  const t = emergencyVehicle.value.type
  return t === 'ambulance' ? '救护车 🚑' : t === 'fire_truck' ? '消防车 🚒' : '应急车辆'
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
  const ev = emergencyVehOnRoad.value
  if (ev && ev.speed != null) return ev.speed
  return 0
})

// ---- 已激活绿波路口数 ----
const activatedNodeCount = computed(() => {
  if (!emergencyVehicle.value.greenWaveActive || activeGreenWaveIndex.value < 0) return 0
  return Math.min(activeGreenWaveIndex.value + 1, emergencyRoute.value.length)
})

const totalRouteNodes = computed(() => emergencyRoute.value.length)

// ---- 已运行时间（秒）----
const elapsedRunningTime = computed(() => {
  const status = latestEvStatus.value
  if (status && status.length > 0) return status[0].elapsedTime ?? 0
  return 0
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
  // Backend evStatus.completed takes priority over map vehicle presence.
  // EV may still appear in vehicle list (speed=0) after reaching destination.
  if (latestEvStatus.value?.some((s: any) => s.completed)) return 'completed'
  if (systemMode.value === 'emergency' && emergencyVehicle.value.greenWaveActive) return 'executing'
  // 车辆在路上但绿波未激活
  if (emergencyVehOnRoad.value) return 'planning'
  if (systemMode.value === 'normal') return 'completed'
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

let completedTimer: ReturnType<typeof setTimeout> | null = null
watch(emergencyPhase, (phase) => {
  if (phase === 'completed') {
    if (completedTimer) clearTimeout(completedTimer)
    completedTimer = setTimeout(() => {
      wasEverTriggered.value = false
      completedTimer = null
    }, 4000)
  }
})

// ================================================================
// 按钮处理
// ================================================================

function openDispatchDialog(e: MouseEvent): void {
  dispatchResult.value = null
  dispatchForm.startId = emergencyRoute.value[0] ?? 'A01'
  dispatchForm.endId = emergencyRoute.value[emergencyRoute.value.length - 1] ?? 'A10'
  dispatchForm.vehicleType = 'ambulance'
  dispatchForm.priority = 3
  // 边界检测
  const pw = 420, ph = 400, margin = 12
  let x = e.clientX + 10, y = e.clientY - 10
  if (x + pw > window.innerWidth - margin) x = window.innerWidth - pw - margin
  if (y + ph > window.innerHeight - margin) y = e.clientY - ph - 10
  if (x < margin) x = margin
  if (y < margin) y = margin
  dispatchDialogX.value = x
  dispatchDialogY.value = y
  showDispatchDialog.value = true
}

function closeDispatchDialog(): void {
  showDispatchDialog.value = false
}

async function handleConfirmDispatch(): Promise<void> {
  if (!canDispatch.value) return
  dispatching.value = true
  dispatchResult.value = null

  try {
    const result = await store.dispatchEmergencyVehicle({
      startIntersection: dispatchForm.startId,
      endIntersection: dispatchForm.endId,
      evType: dispatchForm.vehicleType,
      priority: dispatchForm.priority,
    })

    if (result) {
      dispatchResult.value = result
      wasEverTriggered.value = true
    }
  } catch (err) {
    console.error('[EmergencyPanel] dispatch failed', err)
  } finally {
    dispatching.value = false
  }
}

function handleSimulateVehicle(): void {
  openDispatchDialog(new MouseEvent('click'))
}

function handleStartGreenWave(): void {
  store.startEmergencyGreenWave()
  if (completedTimer) { clearTimeout(completedTimer); completedTimer = null }
  wasEverTriggered.value = true
  store.generateMockAlert(
    'green_wave_start',
    'emergency',
    '应急绿波已启动，沿线信号进入优先放行策略',
    '系统操作 · 应急控制面板',
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

// ---- 应急车辆事件 ----
const latestEvDecision = computed(() => {
  const events = latestEvEvents.value
  if (!events || events.length === 0) return null
  const evId = emergencyVehicle.value.id
  if (!evId) return null
  const evEvents = events.filter(e => e.evId === evId)
  if (evEvents.length === 0) return null
  const latest = evEvents[evEvents.length - 1]
  const label = intersections.value.find((it) => it.id === latest.intersectionId)?.name ?? latest.intersectionId
  return { ...latest, label }
})

const processedIntersections = computed(() => {
  const events = latestEvEvents.value
  if (!events || events.length === 0) return []
  const evId = emergencyVehicle.value.id
  if (!evId) return []
  const seen = new Set()
  const result = []
  for (const e of events) {
    if (e.evId !== evId) continue
    if (seen.has(e.intersectionId)) continue
    seen.add(e.intersectionId)
    const label = intersections.value.find((it) => it.id === e.intersectionId)?.name ?? e.intersectionId
    result.push({ id: e.intersectionId, label, decision: e.decision })
  }
  return result
})

// ---- 信号调度列表（路线所有路口 + 当前决策） ----
const signalDispatchList = computed(() => {
  const events = latestEvEvents.value
  const evId = emergencyVehicle.value.id
  return routeNodeLabels.value.map(node => {
    const nodeEvents = events.filter(e => e.evId === evId && e.intersectionId === node.id)
    const latest = nodeEvents.length > 0 ? nodeEvents[nodeEvents.length - 1] : null
    const active = latest !== null
    return {
      id: node.id,
      label: node.name,
      decision: latest ? latest.decision : '暂无',
      active,
    }
  })
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
            <span class="ep-vm__value text-emerald">{{ (currentSpeed * 3.6).toFixed(1) }} km/h</span>
          </div>
          <div class="ep-vm ep-vm--stacked">
            <div class="ep-vm__label">已运行时间</div>
            <div class="ep-vm__value text-emerald">{{ elapsedRunningTime }} s</div>
          </div>
        </div>
      </div>

      <!-- ===== 调度路线 & 信号调度 ===== -->
      <div v-if="emergencyPhase !== 'idle'" class="ep-wave-section">
        <!-- 调度路线 -->
        <div class="ep-route-box">
          <div class="ep-route-box__title">调度路线</div>
          <div class="ep-route-box__nodes">
            <template v-for="(n, idx) in routeNodeLabels" :key="n.id">
              <span class="ep-route-box__node">
                {{ n.name || n.id }}
              </span>
              <span v-if="idx < routeNodeLabels.length - 1" class="ep-route-box__arrow">→</span>
            </template>
          </div>
        </div>

        <!-- 信号调度 -->
        <div class="ep-signal-table">
          <div class="ep-signal-table__title">信号调度</div>
          <div class="ep-signal-table__rows">
            <div v-for="item in signalDispatchList" :key="item.id" class="ep-signal-row">
              <span class="ep-signal-row__name">{{ item.label }}</span>
              <span class="ep-signal-row__decision" :class="{ 'ep-signal-row__decision--active': item.active }">
                {{ item.decision }}
              </span>
            </div>
          </div>
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

      <!-- ===== 大按钮（仅 idle 时醒目） ===== -->
      <button
        v-if="emergencyPhase === 'idle'"
        class="cyber-btn ep-dispatch-trigger"
        @click="openDispatchDialog($event)"
      >
        应急调度
      </button>

      <!-- ===== 控制按钮组 ===== -->
      <div v-else class="ep-actions">
        <button
          class="cyber-btn ep-action-btn ep-action-btn--sim"
          :disabled="!canSimulate"
          @click="openDispatchDialog($event)"
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
          点击上方按钮配置应急车辆调度<br>
          系统将自动规划绿波路线
        </div>
      </div>
    </div>

    <!-- ===== 调度浮动面板 ===== -->
    <Teleport to="body">
      <div v-if="showDispatchDialog" class="ep-dispatch-float" @click.self="closeDispatchDialog">
        <aside
          class="hud-card data-panel-card ep-dispatch-panel"
          :style="{ left: dispatchDialogX + 'px', top: dispatchDialogY + 'px' }"
          aria-label="应急车辆调度"
        >
          <!-- 标题栏 — 复用全局 .hud-panel-titlebar -->
          <header class="hud-panel-titlebar">
            <div class="titlebar-inner">
              <span class="titlebar-mark" />
              <span class="titlebar-text">应急车辆调度</span>
              <div class="titlebar-deco">
                <i /><i /><i />
              </div>
            </div>
          </header>

          <!-- 表单 -->
          <div class="ep-dispatch-body">
            <div v-if="!dispatchResult">
              <div class="ep-field">
                <label class="ep-label">起点路口</label>
                <el-select
                  v-model="dispatchForm.startId"
                  placeholder="选择起点路口"
                  filterable
                  class="ep-select"
                >
                  <el-option
                    v-for="it in intersections"
                    :key="it.id"
                    :label="`${it.name} [${it.id}]`"
                    :value="it.id"
                  />
                </el-select>
              </div>

              <div class="ep-field">
                <label class="ep-label">终点路口</label>
                <el-select
                  v-model="dispatchForm.endId"
                  placeholder="选择终点路口"
                  filterable
                  class="ep-select"
                >
                  <el-option
                    v-for="it in intersections"
                    :key="it.id"
                    :label="`${it.name} [${it.id}]`"
                    :value="it.id"
                  />
                </el-select>
              </div>

              <div class="ep-form-row">
                <div class="ep-field ep-field--half">
                  <label class="ep-label">车辆类型</label>
                  <el-select v-model="dispatchForm.vehicleType" class="ep-select">
                    <el-option label="🚑 救护车" value="ambulance" />
                    <el-option label="🚒 消防车" value="fire_truck" />
                  </el-select>
                </div>
                <div class="ep-field ep-field--half">
                  <label class="ep-label">优先级别</label>
                  <el-select v-model="dispatchForm.priority" class="ep-select">
                    <el-option label="5 · 最高" :value="5" />
                    <el-option label="4 · 高" :value="4" />
                    <el-option label="3 · 中" :value="3" />
                    <el-option label="2 · 低" :value="2" />
                    <el-option label="1 · 最低" :value="1" />
                  </el-select>
                </div>
              </div>
            </div>

            <!-- 结果 -->
            <div v-else class="ep-result">
              <div class="ep-result__icon">🚨</div>
              <div class="ep-result__title">调度成功</div>
              <div class="ep-result__meta">
                <div class="ep-result__row">
                  <span class="ep-result__key">车辆 ID</span>
                  <span class="ep-result__val text-cyan">{{ dispatchResult.evId }}</span>
                </div>
                <div class="ep-result__row">
                  <span class="ep-result__key">路线</span>
                  <span class="ep-result__val text-cyan">{{ dispatchResult.route.join(' → ') }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 底部 -->
          <div class="ep-dispatch-footer">
            <button v-if="!dispatchResult" class="cyber-btn ep-footer-btn ep-footer-btn--cancel" @click="closeDispatchDialog">
              取消
            </button>
            <button
              v-if="!dispatchResult"
              class="cyber-btn ep-footer-btn ep-footer-btn--go"
              :disabled="!canDispatch"
              @click="handleConfirmDispatch"
            >
              <span v-if="dispatching">⏳ 调度中…</span>
              <span v-else>🚨 确认调度</span>
            </button>
            <button v-else class="cyber-btn ep-footer-btn" @click="closeDispatchDialog">
              关闭
            </button>
          </div>
        </aside>
      </div>
    </Teleport>
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
  grid-template-columns: 1fr 1fr;
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

.ep-vm--stacked .ep-vm__label {
  font-size: 10px;
  color: #5a7595;
  text-align: center;
}
.ep-vm--stacked .ep-vm__value {
  display: block;
  text-align: center;
  margin-top: 4px;
  font-size: 18px;
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

/* 应急调度大按钮 */
.ep-dispatch-trigger {
  width: 100%;
  padding: 22px 16px;
  text-transform: none;
  font-family: 'AlimamaShuHeiTi', 'PingFang SC', sans-serif;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.12em;
  color: #ff4d6d;
  border-color: rgba(255, 77, 109, 0.55);
  border-width: 2px;
  background: linear-gradient(180deg, rgba(255, 77, 109, 0.06), rgba(255, 77, 109, 0.02));
  transition: all 0.25s ease;
}

.ep-dispatch-trigger:hover {
  color: #ff6b85;
  border-color: rgba(255, 107, 133, 0.7);
  background: rgba(255, 77, 109, 0.14);
  box-shadow:
    0 0 36px rgba(255, 77, 109, 0.45),
    inset 0 0 22px rgba(255, 77, 109, 0.12);
  transform: translateY(-2px);
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

/* ================================================================
   Floating Panel — 复用全局 HUD 样式，el-select 依赖 dashboard-shell 暗色覆盖
   ================================================================ */

.ep-dispatch-float {
  position: fixed;
  inset: 0;
  z-index: 180;
}
.ep-dispatch-panel {
  position: absolute;
  width: 380px;
  max-height: 76vh;
  pointer-events: auto;
  display: flex;
  --hud-fill: rgba(5, 19, 35, 0.96);
  flex-direction: column;
}

.ep-dispatch-body {
  flex: 1 1 auto;
  min-height: 0;
  padding: 14px 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ep-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ep-field--half {
  flex: 1;
  min-width: 0;
}

.ep-form-row {
  display: flex;
  gap: 10px;
}

.ep-label {
  font-family: 'Rajdhani', 'PingFang SC', sans-serif;
  font-size: 12px;
  font-weight: 600;
  color: #8da8c5;
  letter-spacing: 0.04em;
}

.ep-select {
  width: 100%;
}

.ep-dispatch-footer {
  flex: 0 0 auto;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 16px 12px;
  border-top: 1px solid rgba(0, 212, 255, 0.1);
}

.ep-footer-btn {
  font-size: 12px;
  padding: 7px 16px;
  letter-spacing: 0.04em;
}

.ep-footer-btn--cancel {
  color: #8da8c5;
  border-color: rgba(143, 172, 197, 0.35);
}

.ep-footer-btn--go {
  color: #ff4d6d;
  border-color: rgba(255, 77, 109, 0.45);
}

.ep-result {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 20px 0;
}

.ep-result__icon {
  font-size: 36px;
}

.ep-result__title {
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 22px;
  font-weight: 700;
  color: #22d3a0;
  text-shadow: 0 0 14px rgba(34, 211, 160, 0.4);
}

.ep-result__meta {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 10px 12px;
  background: rgba(4, 21, 39, 0.4);
  border: 1px solid rgba(0, 212, 255, 0.1);
}

.ep-result__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.ep-result__key {
  font-size: 11px;
  color: #5a7595;
}

.ep-result__val {
  font-family: 'Rajdhani', sans-serif;
  font-size: 13px;
  font-weight: 700;
  text-align: right;
}

/* 调度路线 & 信号调度 */
.ep-route-box {
  padding: 10px 12px;
  background: rgba(0, 212, 255, 0.05);
  border: 1px solid rgba(0, 212, 255, 0.15);
  border-radius: 6px;
}
.ep-route-box__title {
  font-size: 11px;
  font-weight: 600;
  color: #5a7595;
  letter-spacing: 0.06em;
  margin-bottom: 6px;
}
.ep-route-box__nodes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}
.ep-route-box__node {
  padding: 2px 8px;
  border-radius: 3px;
  background: rgba(4, 21, 39, 0.4);
  color: #8da8c5;
  font-family: 'Rajdhani', sans-serif;
  font-weight: 600;
}
.ep-route-box__node--active {
  background: rgba(0, 212, 255, 0.15);
  color: #00d4ff;
}
.ep-route-box__arrow { color: #3a5575; font-weight: 700; }

.ep-signal-table {
  margin-top: 10px;
  border: 1px solid rgba(0, 212, 255, 0.12);
  border-radius: 6px;
  overflow: hidden;
}
.ep-signal-table__title {
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 700;
  color: #00d4ff;
  letter-spacing: 0.06em;
  background: rgba(0, 212, 255, 0.06);
  border-bottom: 1px solid rgba(0, 212, 255, 0.1);
}
.ep-signal-table__rows {
  display: flex;
  flex-direction: column;
}
.ep-signal-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px;
  border-bottom: 1px solid rgba(0, 212, 255, 0.06);
  transition: background 0.2s;
}
.ep-signal-row:last-child { border-bottom: none; }
.ep-signal-row:hover { background: rgba(0, 212, 255, 0.04); }
.ep-signal-row__name {
  font-size: 12px;
  color: #c0d8f0;
  font-family: 'Rajdhani', sans-serif;
  font-weight: 600;
}
.ep-signal-row__decision {
  font-size: 11px;
  color: #5a7595;
  padding: 2px 8px;
  border-radius: 3px;
  background: rgba(4, 21, 39, 0.3);
}
.ep-signal-row__decision--active {
  color: #22d3a0;
  background: rgba(34, 211, 160, 0.12);
}

</style>
