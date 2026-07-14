<script setup lang="ts">
// ================================================================
// SignalControlPanel — AI 信号控制面板
// 展示选中路口信号状态、AI 决策建议、控制按钮
// ================================================================
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'
import { PHASE_LABELS, DEVICE_STATUS_LABELS } from '@/types/traffic'
import type { SignalPhase, Alert } from '@/types/traffic'

const store = useTrafficStore()
const {
  intersections,
  selectedIntersectionId,
  selectedIntersection,
  alerts,
  simulationControllerType,
  simulationStatus,
  simulationSid,
  simulationSceneId,
  simulationSpeed,
  latestControlDecision,
} = storeToRefs(store)

const props = defineProps<{
  busy?: boolean
}>()

const emit = defineEmits<{
  startSimulation: []
  pauseSimulation: []
  stopSimulation: []
  simulationRecreated: [sid: string]
}>()

// ---- 策略切换 ----
const showControllerDialog = ref(false)
const pendingController = ref('')
const switching = ref(false)
const dialogX = ref(0)
const dialogY = ref(0)
const showSpeedMenu = ref(false)

const controllerOptions = [
  { value: 'fixed-time', label: 'Fixed-Time', desc: '固定配时，按预设周期循环' },
  { value: 'max-pressure', label: 'Max-Pressure', desc: '最大压力算法，按排队择优' },
  { value: 'traffic-r', label: 'Traffic-R', desc: '强化学习模型，需云端服务可用' },
]

const sceneOptions = [
  { value: 'jinan_3x4', label: 'Jinan 3x4' },
  { value: 'jinan_3x4_stress', label: 'Jinan 3x4 Stress' },
  { value: 'hangzhou_4_4', label: 'Hangzhou 4x4' },
]

function openControllerDialog(e: MouseEvent): void {
  pendingController.value = simulationControllerType.value
  // 边界检测：280px 宽 × ~240px 高，不超出视口
  const pw = 280, ph = 240, margin = 12
  let x = e.clientX + 10, y = e.clientY - 10
  if (x + pw > window.innerWidth - margin) x = window.innerWidth - pw - margin
  if (y + ph > window.innerHeight - margin) y = e.clientY - ph - 10
  if (x < margin) x = margin
  if (y < margin) y = margin
  dialogX.value = x
  dialogY.value = y
  showControllerDialog.value = true
}
function selectController(value: string): void {
  pendingController.value = value
}
async function confirmSwitch(): Promise<void> {
  if (switching.value || pendingController.value === simulationControllerType.value) {
    showControllerDialog.value = false
    return
  }
  switching.value = true
  simulationControllerType.value = pendingController.value
  showControllerDialog.value = false
  try {
    const sid = await store.recreateSimulation(pendingController.value)
    if (sid) emit('simulationRecreated', sid)
  } catch {
    // 错误已在 store 内处理
  } finally {
    switching.value = false
  }
}

// ---- 仿真控制 ----
const speedOptions = [1, 3, 5, 8]

const operationBusy = computed(() => props.busy || switching.value)
const hasSimulationSession = computed(() => Boolean(simulationSid.value))
const canStartSimulation = computed(() => !operationBusy.value && simulationStatus.value !== 'running')
const canPauseSimulation = computed(() => !operationBusy.value && simulationStatus.value === 'running')
const canStopSimulation = computed(
  () => !operationBusy.value && hasSimulationSession.value && simulationStatus.value !== 'finished',
)
const canSetSpeed = computed(
  () => !operationBusy.value && (!hasSimulationSession.value || simulationStatus.value === 'finished'),
)

const simulationStatusText = computed(() => {
  if (operationBusy.value) return '处理中'
  if (simulationStatus.value === 'running') return '仿真运行中'
  if (simulationStatus.value === 'paused') return '仿真已暂停'
  if (simulationStatus.value === 'booting') return '等待启动'
  return '仿真未启动'
})

const statusBadgeClass = computed(() => {
  if (operationBusy.value) return 'sc-status-badge--pending'
  if (simulationStatus.value === 'running') return 'sc-status-badge--live'
  if (simulationStatus.value === 'paused') return 'sc-status-badge--paused'
  return 'sc-status-badge--idle'
})

function handleStartSimulation(): void {
  if (!canStartSimulation.value) return
  emit('startSimulation')
}

function handlePauseSimulation(): void {
  if (!canPauseSimulation.value) return
  emit('pauseSimulation')
}

function handleStopSimulation(): void {
  if (!canStopSimulation.value) return
  emit('stopSimulation')
}

function toggleSpeedMenu(): void {
  if (!canSetSpeed.value) return
  showSpeedMenu.value = !showSpeedMenu.value
}

function selectSpeed(speed: number): void {
  if (!canSetSpeed.value) return
  simulationSpeed.value = speed
  showSpeedMenu.value = false
}

async function selectScene(event: Event): Promise<void> {
  if (!canSetSpeed.value) return
  simulationSceneId.value = (event.target as HTMLSelectElement).value
  await store.loadSceneRoadnet(simulationSceneId.value)
}
// 点击外部关闭
function onOverlayClick(e: MouseEvent): void {
  if ((e.target as HTMLElement).classList.contains('sc-dialog-overlay')) {
    showControllerDialog.value = false
  }
}

// ---- 相位循环（与 store 保持一致） ----
const PHASE_ORDER: SignalPhase[] = [
  'eastwest_straight',
  'eastwest_left',
  'northsouth_straight',
  'northsouth_left',
]

const PHASE_DURATION: Record<SignalPhase, number> = {
  eastwest_straight: 60,
  eastwest_left: 30,
  northsouth_straight: 50,
  northsouth_left: 25,
  all_red: 5,
}

// ---- 当前活跃路口（未选中时默认取第一个） ----
const activeIntersection = computed(() =>
  selectedIntersection.value ?? intersections.value[0] ?? null,
)

const phaseName = computed(() => {
  if (!activeIntersection.value) return '—'
  return PHASE_LABELS[activeIntersection.value.currentPhase] ?? activeIntersection.value.currentPhase
})

const deviceLabel = computed(() => {
  if (!activeIntersection.value) return '—'
  return DEVICE_STATUS_LABELS[activeIntersection.value.deviceStatus]
})

const deviceOk = computed(
  () => activeIntersection.value?.deviceStatus === 'online',
)

// ---- AI 决策建议（根据路口实时状态动态生成） ----
const aiPhaseIndex = computed(() => {
  if (!activeIntersection.value) return 0
  const idx = PHASE_ORDER.indexOf(activeIntersection.value.currentPhase as SignalPhase)
  return idx >= 0 ? idx : 0
})

const nextPhaseName = computed(() => {
  const next = PHASE_ORDER[(aiPhaseIndex.value + 1) % PHASE_ORDER.length]!
  return PHASE_LABELS[next]
})

const suggestDuration = computed(() => {
  if (!activeIntersection.value) return 0
  const phase = activeIntersection.value.currentPhase as SignalPhase
  const base = PHASE_DURATION[phase] ?? 30
  const ci = activeIntersection.value.congestionIndex
  // 拥堵越严重，建议延长越多
  if (ci >= 80) return base + 15
  if (ci >= 60) return base + 8
  if (ci >= 40) return base
  return base - 5 // 轻度拥堵可适当缩短
})

const aiSuggestion = computed(() => {
  const it = activeIntersection.value
  if (!it) return { title: '—', body: '', tone: 'default' as const, source: 'none' as const }

  // ---- 仿真运行时：显示后端 AI 真实决策 ----
  const decision = latestControlDecision.value
  if (simulationStatus.value === 'running') {
    if (decision) {
      const phaseLabel = PHASE_LABELS[decision.phaseCode as SignalPhase] ?? decision.phaseCode
      const confPct = Math.round(decision.confidence * 100)
      const tone = decision.confidence >= 0.7 ? 'emerald' as const
        : decision.confidence >= 0.4 ? 'amber' as const
        : 'rose' as const
      return {
        title: `${decision.controllerType} → ${phaseLabel} (置信度 ${confPct}%)`,
        body: decision.reason || `AI 决策：切换至 ${phaseLabel}，持续 ${decision.durationSec}s`,
        tone,
        source: 'ai' as const,
      }
    }
    // 仿真在跑但还没收到 AI 决策（fixed-time 或等待第一个决策周期）
    return {
      title: '等待模型决策',
      body: `仿真运行中，${simulationControllerType.value === 'fixed-time' ? '当前为 Fixed-Time 固定配时模式，不产生 AI 决策。切换至 Max-Pressure 或 Traffic-R 可看到策略决策。' : '决策周期为 10s，正在等待第一个决策推送。'}`,
      tone: 'default' as const,
      source: 'waiting' as const,
    }
  }

  // ---- 仿真未运行：本地启发式规则（降级方案）----
  // 故障/离线
  if (it.deviceStatus === 'fault') {
    return { title: '设备故障 — 需人工介入', body: '当前路口信号控制器故障，倒计时已停止，控制策略自动降级。建议：立即派单巡检，周边路口扩大放行窗口以疏导积压车辆，维修完成前考虑启用临时移动信号灯。', tone: 'rose' as const, source: 'local' as const }
  }
  if (it.deviceStatus === 'offline') {
    return { title: '设备离线', body: '信号控制器离线，无法获取实时车流数据。策略决策已暂停对该路口的控制，请在设备恢复后重新启动仿真验证。', tone: 'amber' as const, source: 'local' as const }
  }
  if (it.congestionIndex >= 80) {
    return { title: `严重拥堵 — 建议延长 ${phaseName.value} 至 ${suggestDuration.value}s`, body: `当前${phaseName.value}相位，路口拥堵指数 ${Math.round(it.congestionIndex)}，排队 ${it.queueLength} 辆，平均延误 ${Math.round(it.averageDelay)}s。`, tone: 'rose' as const, source: 'local' as const }
  }
  if (it.congestionIndex >= 60) {
    return { title: `中度拥堵 — 维持配时，监控排队变化`, body: `当前${phaseName.value}相位，拥堵指数 ${Math.round(it.congestionIndex)}。车流密度偏高但仍在可控范围，建议维持标准配时 ${suggestDuration.value}s。`, tone: 'amber' as const, source: 'local' as const }
  }
  if (it.congestionIndex >= 30) {
    return { title: `轻度拥堵 — 标准配时运行`, body: `当前${phaseName.value}相位，路口整体畅通。按标准配时方案运行。`, tone: 'emerald' as const, source: 'local' as const }
  }
  return { title: `路口畅通 — 标准配时运行`, body: `当前${phaseName.value}相位，路口车流顺畅，拥堵指数仅 ${Math.round(it.congestionIndex)}。`, tone: 'emerald' as const, source: 'local' as const }
})

// ---- 操作日志（最近 6 条 info 级别告警） ----
const operationLog = computed<Pick<Alert, 'id' | 'title' | 'time'>[]>(() =>
  alerts.value
    .filter((a) => a.level === 'info')
    .slice(0, 6)
    .map((a) => ({ id: a.id, title: a.title, time: a.time })),
)

// ---- 相位进度环 ----
const phaseProgress = computed(() => {
  if (!activeIntersection.value) return 0
  const phase = activeIntersection.value.currentPhase as SignalPhase
  const total = PHASE_DURATION[phase] ?? 60
  if (total <= 0) return 0
  return (activeIntersection.value.greenRemain / total) * 100
})

const phaseProgressColor = computed(() => {
  if (!activeIntersection.value) return '#5A7595'
  if (activeIntersection.value.deviceStatus !== 'online') return '#5A7595'
  const r = activeIntersection.value.greenRemain
  if (r <= 3) return '#FF4D6D'
  if (r <= 8) return '#FFB800'
  return '#22D3A0'
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">AI 信号控制</span>
        <span
          class="sc-ai-badge"
          :class="statusBadgeClass"
        >
          <span class="status-dot" :class="simulationStatus === 'running' ? 'status-dot--live' : ''" />
          {{ simulationStatusText }}
        </span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- ===== 控制策略选择 ===== -->
      <div class="sc-section sc-section--compact">
        <div class="sc-section__head">
          <span class="sc-section__label">控制策略</span>
          <span class="sc-status-badge" :class="simulationStatus === 'running' ? 'sc-status-badge--live' : 'sc-status-badge--idle'">
            <span class="status-dot" :class="simulationStatus === 'running' ? 'status-dot--live' : ''" />
            {{ simulationStatus === 'running' ? '仿真中' : simulationStatus === 'paused' ? '已暂停' : '未启动' }}
          </span>
        </div>
        <div class="sc-controller-row">
          <span class="sc-current-strategy">{{ controllerOptions.find(o => o.value === simulationControllerType)?.label ?? simulationControllerType }}</span>
          <button class="cyber-btn sc-controller-trigger" :disabled="switching" @click="openControllerDialog">
            {{ switching ? '切换中' : '切换策略' }}
          </button>
        </div>
      </div>

      <!-- ===== 策略选择弹窗 ===== -->
      <div class="sc-section sc-section--compact">
        <div class="sc-section__head">
          <span class="sc-section__label">仿真路网</span>
          <span class="sc-select-hint">{{ canSetSpeed ? '可切换' : '仿真中锁定' }}</span>
        </div>
        <select
          class="sc-scene-select"
          :value="simulationSceneId"
          :disabled="!canSetSpeed"
          @change="selectScene"
        >
          <option v-for="scene in sceneOptions" :key="scene.value" :value="scene.value">
            {{ scene.label }}
          </option>
        </select>
      </div>

      <Teleport to="body">
        <div v-if="showControllerDialog" class="sc-dialog-overlay" @click="onOverlayClick">
          <aside
            class="hud-card data-panel-card sc-dialog-panel"
            :style="{ left: dialogX + 'px', top: dialogY + 'px' }"
          >
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="titlebar-mark" />
                <span class="titlebar-text">选择控制策略</span>
                <div class="titlebar-deco"><i /><i /><i /></div>
              </div>
            </header>
            <div class="sc-dialog-body">
              <div
                v-for="opt in controllerOptions"
                :key="opt.value"
                class="sc-strategy-card"
                :class="{ 'sc-strategy-card--selected': pendingController === opt.value }"
                @click="selectController(opt.value)"
              >
                <div class="sc-strategy-card__name">{{ opt.label }}</div>
                <div class="sc-strategy-card__desc">{{ opt.desc }}</div>
              </div>
            </div>
            <div class="sc-dialog-footer">
              <button class="cyber-btn sc-dialog-btn sc-dialog-btn--cancel" @click="showControllerDialog = false">取消</button>
              <button class="cyber-btn sc-dialog-btn sc-dialog-btn--confirm" :disabled="switching" @click="confirmSwitch">
                {{ switching ? '切换中' : '确认切换' }}
              </button>
            </div>
          </aside>
        </div>
      </Teleport>

      <!-- ===== 故障/离线醒目提示 ===== -->
      <div v-if="!deviceOk" class="sc-fault-banner" :class="activeIntersection?.deviceStatus === 'fault' ? 'sc-fault-banner--fault' : 'sc-fault-banner--offline'">
        <span class="sc-fault-banner__icon" aria-hidden="true" />
        <div class="sc-fault-banner__text">
          <div class="sc-fault-banner__title">
            {{ activeIntersection?.deviceStatus === 'fault' ? '设备故障' : '设备离线' }}
          </div>
          <div class="sc-fault-banner__desc">AI 决策已降级 · 请派单巡检</div>
        </div>
      </div>

      <!-- ===== 当前路口信息 ===== -->
      <div class="sc-section">
        <div class="sc-section__head">
          <span class="sc-section__label">当前路口</span>
          <span class="sc-select-hint" v-if="!selectedIntersectionId">默认</span>
        </div>
        <div class="sc-int-name">{{ activeIntersection?.name ?? '—' }}</div>

        <div class="sc-info-grid">
          <!-- 相位 -->
          <div class="sc-info-cell">
            <div class="sc-info-cell__label">当前相位</div>
            <div class="sc-info-cell__value text-cyan">{{ phaseName }}</div>
          </div>
          <!-- 绿灯剩余 -->
          <div class="sc-info-cell">
            <div class="sc-info-cell__label">绿灯剩余</div>
            <div class="sc-info-cell__value" :class="activeIntersection?.greenRemain && activeIntersection.greenRemain <= 8 ? 'text-amber' : 'text-emerald'">
              {{ activeIntersection ? `${Math.round(activeIntersection.greenRemain)}s` : '—' }}
            </div>
          </div>
          <!-- 排队车辆 -->
          <div class="sc-info-cell">
            <div class="sc-info-cell__label">排队车辆</div>
            <div class="sc-info-cell__value text-amber">{{ activeIntersection?.queueLength ?? '—' }} 辆</div>
          </div>
          <!-- 设备状态 -->
          <div class="sc-info-cell">
            <div class="sc-info-cell__label">设备状态</div>
            <div class="sc-info-cell__value" :class="deviceOk ? 'text-emerald' : 'text-rose'">
              {{ deviceLabel }}
            </div>
          </div>
        </div>
      </div>

      <!-- ===== 交通指标 ===== -->
      <div class="sc-section sc-section--compact">
        <div class="sc-metrics">
          <div class="sc-metric">
            <span class="sc-metric__label">平均延误</span>
            <span class="sc-metric__value text-amber">
              {{ activeIntersection ? `${Math.round(activeIntersection.averageDelay)}s` : '—' }}
            </span>
          </div>
          <div class="sc-metric">
            <span class="sc-metric__label">拥堵指数</span>
            <span class="sc-metric__value" :class="(activeIntersection?.congestionIndex ?? 0) >= 60 ? 'text-rose' : 'text-cyan'">
              {{ activeIntersection ? Math.round(activeIntersection.congestionIndex) : '—' }} / 100
            </span>
          </div>
        </div>
        <!-- 相位进度条 -->
        <div class="sc-phase-bar">
          <div class="sc-phase-bar__label">相位剩余</div>
          <div class="health-bar" style="flex:1;">
            <div
              class="health-bar-fill"
              :style="{ width: `${phaseProgress}%`, background: phaseProgressColor }"
            />
          </div>
        </div>
      </div>

      <!-- ===== AI 决策建议（突出区域） ===== -->
      <div class="sc-ai-section" :class="`sc-ai-section--${aiSuggestion.tone}`">
        <div class="sc-ai-section__head">
          <span class="sc-ai-section__label">{{ aiSuggestion.source === 'ai' ? 'AI 模型决策' : aiSuggestion.source === 'waiting' ? '等待 AI 决策' : '本地规则建议' }}</span>
          <span class="sc-ai-section__pulse" :class="{ 'sc-ai-section__pulse--live': aiSuggestion.source === 'ai', 'sc-ai-section__pulse--waiting': aiSuggestion.source === 'waiting' }" />
        </div>
        <div class="sc-ai-section__title" :class="aiSuggestion.tone !== 'default' ? `text-${aiSuggestion.tone}` : ''">
          {{ aiSuggestion.title }}
        </div>
        <div class="sc-ai-section__body">{{ aiSuggestion.body }}</div>
      </div>

      <!-- ===== 控制按钮 ===== -->
      <div class="sc-actions">
        <button
          class="sc-action-btn sc-action-btn--start"
          :disabled="!canStartSimulation"
          type="button"
          @click="handleStartSimulation"
        >
          <span class="sc-action-btn__mark" aria-hidden="true" />
          <span>{{ operationBusy ? '处理中' : simulationStatus === 'paused' ? '继续仿真' : '启动仿真' }}</span>
        </button>
        <button
          class="sc-action-btn sc-action-btn--pause"
          :disabled="!canPauseSimulation"
          type="button"
          @click="handlePauseSimulation"
        >
          <span class="sc-action-btn__mark" aria-hidden="true" />
          <span>暂停仿真</span>
        </button>
        <button
          class="sc-action-btn sc-action-btn--stop"
          :disabled="!canStopSimulation"
          type="button"
          @click="handleStopSimulation"
        >
          <span class="sc-action-btn__mark" aria-hidden="true" />
          <span>停止仿真</span>
        </button>
        <div class="sc-speed">
          <button
            class="sc-action-btn sc-action-btn--speed"
            :disabled="!canSetSpeed"
            type="button"
            @click="toggleSpeedMenu"
          >
            <span class="sc-action-btn__mark" aria-hidden="true" />
            <span>仿真倍速 {{ simulationSpeed }}x</span>
          </button>
          <div v-if="showSpeedMenu" class="sc-speed-menu">
            <button
              v-for="speed in speedOptions"
              :key="speed"
              type="button"
              class="sc-speed-menu__item"
              :class="{ 'sc-speed-menu__item--active': simulationSpeed === speed }"
              @click="selectSpeed(speed)"
            >
              {{ speed }}x
            </button>
          </div>
        </div>
      </div>

      <!-- ===== 操作日志 ===== -->
      <div v-if="operationLog.length > 0" class="sc-log">
        <div class="sc-log__title">操作日志</div>
        <div class="sc-log__list">
          <div v-for="log in operationLog" :key="log.id" class="sc-log__item">
            <span class="sc-log__item-icon" aria-hidden="true" />
            <span class="sc-log__item-text">{{ log.title }}</span>
            <span class="sc-log__item-time">{{ log.time }}</span>
          </div>
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

/* AI 状态徽章 */
.sc-ai-badge {
  margin-left: auto;
  margin-right: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
}

.sc-status-badge--live {
  border: 1px solid rgba(34, 211, 160, 0.5);
  color: #22d3a0;
  background: rgba(34, 211, 160, 0.08);
}

.sc-status-badge--paused,
.sc-status-badge--pending {
  border: 1px solid rgba(255, 184, 0, 0.5);
  color: #ffb800;
  background: rgba(255, 184, 0, 0.08);
}

.sc-status-badge--idle {
  border: 1px solid rgba(90, 117, 149, 0.45);
  color: #8da8c5;
  background: rgba(90, 117, 149, 0.08);
}

/* 故障/离线横幅 */
.sc-fault-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid;
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  flex: 0 0 auto;
}

.sc-fault-banner--fault {
  border-color: rgba(255, 77, 109, 0.5);
  background: rgba(255, 77, 109, 0.1);
  animation: sc-fault-pulse 1.8s ease-in-out infinite;
}

.sc-fault-banner--offline {
  border-color: rgba(90, 117, 149, 0.5);
  background: rgba(90, 117, 149, 0.1);
}

.sc-fault-banner__icon {
  flex: 0 0 auto;
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: currentColor;
  box-shadow: 0 0 10px currentColor;
}

.sc-fault-banner__title {
  font-size: 13px;
  font-weight: 700;
  color: #ff4d6d;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sc-fault-banner__desc {
  margin-top: 2px;
  font-size: 11px;
  color: #8da8c5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@keyframes sc-fault-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 77, 109, 0); }
  50% { box-shadow: 0 0 12px rgba(255, 77, 109, 0.3); }
}

/* 区块 */
.sc-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sc-section__label {
  font-size: 11px;
  color: #5a7595;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.sc-select-hint {
  font-size: 10px;
  color: #5a7595;
  padding: 1px 6px;
  border: 1px solid rgba(90, 117, 149, 0.35);
}

/* 路口名称 */
.sc-int-name {
  font-size: 15px;
  font-weight: 700;
  color: #7af7ff;
  text-shadow: 0 0 10px rgba(0, 212, 255, 0.35);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 信息网格 2x2 */
.sc-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
}

.sc-info-cell {
  padding: 7px 9px;
  background: rgba(0, 212, 255, 0.05);
  border: 1px solid rgba(0, 212, 255, 0.12);
}

.sc-info-cell__label {
  font-size: 10px;
  color: #5a7595;
  letter-spacing: 0.04em;
}

.sc-info-cell__value {
  margin-top: 3px;
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 17px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 交通指标行 */
.sc-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.sc-metric {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 9px;
  background: rgba(4, 21, 39, 0.5);
  border: 1px solid rgba(0, 212, 255, 0.1);
}

.sc-metric__label {
  font-size: 11px;
  color: #8da8c5;
}

.sc-metric__value {
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 16px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 相位进度条 */
.sc-phase-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 2px;
}

.sc-phase-bar__label {
  flex: 0 0 auto;
  font-size: 10px;
  color: #5a7595;
}

/* AI 建议区域（突出） */
.sc-ai-section {
  padding: 12px 14px;
  border: 1px solid;
  border-radius: 2px;
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  background: rgba(4, 21, 39, 0.6);
  flex: 0 0 auto;
}

.sc-ai-section--rose {
  border-color: rgba(255, 77, 109, 0.45);
  border-left: 3px solid #ff4d6d;
  box-shadow: inset 0 0 20px rgba(255, 77, 109, 0.06), 0 0 10px rgba(255, 77, 109, 0.08);
}

.sc-ai-section--amber {
  border-color: rgba(255, 184, 0, 0.4);
  border-left: 3px solid #ffb800;
  box-shadow: inset 0 0 20px rgba(255, 184, 0, 0.06), 0 0 10px rgba(255, 184, 0, 0.08);
}

.sc-ai-section--emerald {
  border-color: rgba(34, 211, 160, 0.35);
  border-left: 3px solid #22d3a0;
  box-shadow: inset 0 0 20px rgba(34, 211, 160, 0.05), 0 0 10px rgba(34, 211, 160, 0.06);
}

.sc-ai-section__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.sc-ai-section__label {
  font-size: 13px;
  font-weight: 700;
  color: #7af7ff;
  letter-spacing: 0.04em;
}

.sc-ai-section__pulse {
  margin-left: auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #00d4ff;
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.8);
  animation: status-dot-breathe 1.2s ease-in-out infinite;
}
.sc-ai-section__pulse--live {
  background: #22d3a0;
  box-shadow: 0 0 12px rgba(34, 211, 160, 0.9);
  animation: status-dot-breathe 0.8s ease-in-out infinite;
}
.sc-ai-section__pulse--waiting {
  background: #f5a623;
  box-shadow: 0 0 10px rgba(245, 166, 35, 0.6);
  animation: status-dot-breathe 2s ease-in-out infinite;
}

.sc-ai-section__title {
  font-size: 13px;
  font-weight: 700;
  line-height: 1.5;
  color: #e8f4ff;
  margin-bottom: 6px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.sc-ai-section__body {
  font-size: 12px;
  line-height: 1.6;
  color: #8da8c5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
}

/* 控制按钮 */
.sc-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 0 0 auto;
}

.sc-action-btn {
  width: 100%;
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-start;
  border: 1px solid rgba(0, 212, 255, 0.28);
  color: #dff9ff;
  background:
    linear-gradient(90deg, rgba(0, 212, 255, 0.1), rgba(2, 18, 33, 0.28)),
    rgba(4, 21, 39, 0.58);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  cursor: pointer;
  font-family: 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0;
  line-height: 1.2;
  padding: 0 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    color 160ms ease,
    box-shadow 160ms ease,
    opacity 160ms ease;
}

.sc-action-btn span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sc-action-btn__mark {
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
  box-shadow: 0 0 12px currentColor;
}

.sc-action-btn--start {
  border-color: rgba(34, 211, 160, 0.5);
  color: #22d3a0;
}

.sc-action-btn--start:hover:not(:disabled) {
  background: rgba(34, 211, 160, 0.12);
  box-shadow: 0 0 16px rgba(34, 211, 160, 0.35);
}

.sc-action-btn--pause {
  border-color: rgba(255, 184, 0, 0.5);
  color: #ffb800;
}

.sc-action-btn--pause:hover:not(:disabled) {
  background: rgba(255, 184, 0, 0.12);
  box-shadow: 0 0 16px rgba(255, 184, 0, 0.35);
}

.sc-action-btn--stop {
  border-color: rgba(255, 77, 109, 0.5);
  color: #ff6b86;
}

.sc-action-btn--stop:hover:not(:disabled) {
  background: rgba(255, 77, 109, 0.12);
  box-shadow: 0 0 16px rgba(255, 77, 109, 0.32);
}

.sc-action-btn--speed {
  border-color: rgba(0, 212, 255, 0.48);
  color: #7af7ff;
}

.sc-action-btn--speed:hover:not(:disabled) {
  background: rgba(0, 212, 255, 0.12);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.28);
}

.sc-action-btn:disabled {
  cursor: not-allowed;
  opacity: 0.38;
  box-shadow: none;
}

.sc-speed {
  position: relative;
}

.sc-speed-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 8px);
  z-index: 20;
  display: grid;
  grid-template-columns: repeat(4, minmax(44px, 1fr));
  gap: 6px;
  width: min(100%, 260px);
  padding: 8px;
  border: 1px solid rgba(0, 212, 255, 0.28);
  background: rgba(5, 19, 35, 0.96);
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.32), 0 0 18px rgba(0, 212, 255, 0.14);
}

.sc-speed-menu__item {
  min-height: 30px;
  border: 1px solid rgba(0, 212, 255, 0.24);
  color: #8da8c5;
  background: rgba(4, 21, 39, 0.66);
  cursor: pointer;
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 13px;
  font-weight: 800;
}

.sc-speed-menu__item:hover,
.sc-speed-menu__item--active {
  border-color: rgba(122, 247, 255, 0.72);
  color: #7af7ff;
  background: rgba(0, 212, 255, 0.12);
}

/* 策略选择 */
.sc-controller-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.sc-current-strategy {
  font-size: 11px;
  font-family: 'Rajdhani', 'PingFang SC', sans-serif;
  color: #7af7ff;
  font-weight: 600;
  letter-spacing: 0.05em;
}
.sc-controller-trigger {
  margin-left: auto;
  padding: 5px 14px;
  font-size: 11px;
  flex-shrink: 0;
}
.sc-scene-select {
  width: 100%;
  height: 30px;
  border: 1px solid rgba(0, 212, 255, 0.26);
  border-radius: 8px;
  background: rgba(4, 12, 24, 0.78);
  color: #dffbff;
  padding: 0 10px;
  font-size: 12px;
}
.sc-scene-select:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.sc-status-badge {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  padding: 2px 8px;
}

/* 策略选择弹窗 */
.sc-dialog-overlay {
  position: fixed; inset: 0; z-index: 300;
}
.sc-dialog-panel {
  position: absolute;
  width: 280px; display: flex; flex-direction: column; gap: 0;
  --hud-fill: rgba(5, 19, 35, 0.96);
}
.sc-dialog-body {
  padding: 14px 16px; display: flex; flex-direction: column; gap: 8px;
}
.sc-strategy-card {
  padding: 10px 14px;
  border: 1px solid rgba(0, 212, 255, 0.22);
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.sc-strategy-card:hover {
  border-color: rgba(0, 212, 255, 0.45);
  background: rgba(0, 212, 255, 0.06);
}
.sc-strategy-card--selected {
  border-color: rgba(0, 212, 255, 0.7);
  background: rgba(0, 212, 255, 0.12);
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.15);
}
.sc-strategy-card__name {
  font-size: 13px; font-weight: 700; color: #e8f4ff;
  font-family: 'Rajdhani', 'PingFang SC', sans-serif;
}
.sc-strategy-card__desc {
  margin-top: 3px; font-size: 10px; color: #8da8c5;
}
.sc-dialog-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 10px 16px; border-top: 1px solid rgba(0, 212, 255, 0.12);
}
.sc-dialog-btn { padding: 6px 18px; font-size: 11px; }
.sc-dialog-btn--cancel { border-color: rgba(90,117,149,0.45); color: #8da8c5; }
.sc-dialog-btn--confirm { border-color: #00d4ff; color: #00d4ff; }

/* 操作日志 */
.sc-log {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sc-log__title {
  font-size: 11px;
  color: #5a7595;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.sc-log__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 100px;
  overflow-y: auto;
  padding-right: 4px;
}

.sc-log__item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 7px;
  background: rgba(4, 21, 39, 0.5);
  border: 1px solid rgba(0, 212, 255, 0.08);
  font-size: 11px;
}

.sc-log__item-icon {
  flex: 0 0 auto;
  font-size: 12px;
}

.sc-log__item-text {
  flex: 1;
  color: #8da8c5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sc-log__item-time {
  flex: 0 0 auto;
  font-family: 'Rajdhani', sans-serif;
  font-size: 10px;
  color: #5a7595;
}
</style>
