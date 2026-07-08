<script setup lang="ts">
// ================================================================
// SystemStatusBar — 系统状态（顶部状态栏）
// 读取 Pinia store，展示实时系统运行状态
// ================================================================
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'

const store = useTrafficStore()
const {
  systemMode,
  aiEnabled,
  systemLatency,
  statistics,
  onlineIntersections,
  intersections,
  alerts,
} = storeToRefs(store)

// ---- 实时时钟 ----
const currentTime = ref('')
let clockTimer: ReturnType<typeof setInterval> | null = null

function updateClock() {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  currentTime.value =
    `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ` +
    `${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`
}

onMounted(() => {
  updateClock()
  clockTimer = setInterval(updateClock, 1000)
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})

// ---- 派生状态 ----
const modeLabel = computed(() =>
  systemMode.value === 'emergency' ? '应急绿波模式' : '普通自适应控制',
)

const aiStatusLabel = computed(() =>
  aiEnabled.value ? 'AI 已启用' : 'AI 未启用',
)

const errorCount = computed(() =>
  alerts.value.filter((a) => (a.level === 'emergency' || a.level === 'error') && !a.acknowledged).length,
)

const systemStatus = computed(() => {
  if (systemMode.value === 'emergency') return { label: '应急模式', tone: 'rose', dot: 'danger' as const }
  return errorCount.value > 0
    ? { label: `告警中 (${errorCount.value})`, tone: 'rose', dot: 'danger' as const }
    : { label: '运行中', tone: 'emerald', dot: 'live' as const }
})

const latencyColor = computed(() => {
  if (systemLatency.value < 40) return 'text-emerald'
  if (systemLatency.value < 60) return 'text-cyan'
  return 'text-amber'
})

// ---- 动态状态指标 ----
const dynamicMetrics = computed(() => [
  {
    label: '系统状态',
    value: systemStatus.value.label,
    tone: systemStatus.value.tone,
    dot: systemStatus.value.dot,
  },
  {
    label: '信号机在线',
    value: `${onlineIntersections.value.length} / ${intersections.value.length}`,
    tone: 'cyan',
    dot: 'live',
  },
  {
    label: '实时车流',
    value: `${statistics.value.totalFlow.toLocaleString()} 辆/h`,
    tone: 'cyan',
    dot: 'live',
  },
  {
    label: '运行模式',
    value: modeLabel.value,
    tone: systemMode.value === 'emergency' ? 'rose' : 'emerald',
    dot: systemMode.value === 'emergency' ? 'danger' : 'live',
  },
  {
    label: aiStatusLabel.value,
    value: aiEnabled.value ? '活跃' : '暂停',
    tone: aiEnabled.value ? 'cyan' : 'amber',
    dot: aiEnabled.value ? 'live' : 'warning',
  },
])
</script>

<template>
  <section class="hud-card status-bar">
    <span class="hud-corner hud-corner--tl" />
    <span class="hud-corner hud-corner--tr" />
    <span class="hud-corner hud-corner--br" />
    <span class="hud-corner hud-corner--bl" />

    <!-- 装饰粒子 -->
    <span class="data-particle" style="top: 12%; left: 8%; animation-delay: 0s;" />
    <span class="data-particle" style="top: 30%; left: 92%; animation-delay: 2s;" />
    <span class="data-particle" style="top: 65%; left: 3%; animation-delay: 4s;" />

    <div class="hud-card__content status-bar__content">
      <!-- 品牌标题 -->
      <div class="status-bar__brand">
        <span class="status-bar__mark" />
        <div class="status-bar__titles">
          <h1 class="status-bar__title">AI 自适应信号控制与应急绿波数字孪生系统</h1>
          <div class="status-bar__subtitle">
            城市交通信号智能调度 · 数字孪生指挥中心 ·
            <span :class="latencyColor" class="status-bar__latency">
              延迟 {{ Math.round(systemLatency) }}ms
            </span>
          </div>
        </div>
      </div>

      <!-- 核心状态指标 -->
      <div class="status-bar__metrics">
        <div
          v-for="m in dynamicMetrics"
          :key="m.label"
          class="status-metric"
        >
          <span class="status-dot" :class="`status-dot--${m.dot}`" />
          <div class="status-metric__text">
            <div class="status-metric__label">{{ m.label }}</div>
            <div class="status-metric__value" :class="`text-${m.tone}`">
              {{ m.value }}
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：刷新频率 + 实时时钟 -->
      <div class="status-bar__right">
        <!-- 刷新频率指示 -->
        <div class="status-bar__refresh">
          <div class="refresh-row">
            <span class="refresh-dot refresh-dot--fast" />
            <span class="refresh-label">车辆 200ms</span>
          </div>
          <div class="refresh-row">
            <span class="refresh-dot refresh-dot--mid" />
            <span class="refresh-label">指标 2s</span>
          </div>
          <div class="refresh-row">
            <span class="refresh-dot refresh-dot--slow" />
            <span class="refresh-label">图表 5s</span>
          </div>
        </div>

        <!-- 实时时钟 -->
        <div class="sync-widget status-bar__clock">
          <span class="sync-radar" />
          <div>
            <div class="sync-label">实时同步中</div>
            <div class="sync-time">{{ currentTime }}</div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.status-bar {
  height: 100%;
}

.status-bar__content {
  height: 100%;
  display: flex;
  align-items: center;
  gap: clamp(12px, 2vw, 28px);
  padding: 8px 20px;
}

.status-bar__brand {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 0 0 auto;
}

.status-bar__mark {
  width: 6px;
  height: 40px;
  transform: skewX(-18deg);
  background: linear-gradient(180deg, #7af7ff, #00d4ff 50%, #034d7a);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.9);
}

.status-bar__title {
  font-family: 'DOUYUFont', 'AlimamaShuHeiTi', 'PingFang SC', sans-serif;
  font-size: clamp(18px, 1.5vw, 28px);
  font-weight: 700;
  color: #e8f4ff;
  letter-spacing: 0.04em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 0 18px rgba(0, 212, 255, 0.55), 0 0 36px rgba(0, 212, 255, 0.28);
}

.status-bar__subtitle {
  margin-top: 3px;
  font-size: 11px;
  letter-spacing: 0.14em;
  color: #5a7595;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-bar__latency {
  font-family: 'Rajdhani', sans-serif;
  font-weight: 700;
}

.status-bar__metrics {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(14px, 2vw, 38px);
  min-width: 0;
  overflow: hidden;
}

.status-metric {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  min-width: 0;
}

.status-metric__text {
  overflow: hidden;
}

.status-metric__label {
  font-size: 11px;
  color: #8da8c5;
  letter-spacing: 0.06em;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-metric__value {
  margin-top: 2px;
  font-family: 'Rajdhani', 'DINPro', sans-serif;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-bar__right {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 0 0 auto;
}

/* 刷新频率指示器 */
.status-bar__refresh {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 5px 10px;
  border: 1px solid rgba(0, 212, 255, 0.18);
  background: rgba(4, 21, 39, 0.5);
}

.refresh-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.refresh-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.refresh-dot--fast {
  background: #22d3a0;
  box-shadow: 0 0 6px rgba(34, 211, 160, 0.8);
  animation: status-dot-breathe 0.4s ease-in-out infinite;
}

.refresh-dot--mid {
  background: #00d4ff;
  box-shadow: 0 0 6px rgba(0, 212, 255, 0.8);
  animation: status-dot-breathe 0.8s ease-in-out infinite;
}

.refresh-dot--slow {
  background: #7c5cff;
  box-shadow: 0 0 6px rgba(124, 92, 255, 0.8);
  animation: status-dot-breathe 1.2s ease-in-out infinite;
}

.refresh-label {
  font-size: 10px;
  color: #5a7595;
  letter-spacing: 0.06em;
  font-family: 'Rajdhani', sans-serif;
}

.status-bar__clock {
  flex: 0 0 auto;
}
</style>
