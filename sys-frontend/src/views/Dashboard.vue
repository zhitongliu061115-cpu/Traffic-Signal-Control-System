<script setup lang="ts">
// ================================================================
// Dashboard — AI 自适应信号控制与应急绿波数字孪生系统 主大屏
//
// 定时刷新策略（在 onMounted 中启动，onUnmounted 中清除）：
//   - 200ms：车辆位置（高频轻量）
//   - 2s：   交通统计 + 道路指数 + 信号灯（中频）
//   - 5s：   拥堵趋势 + 随机告警（低频）
// ================================================================
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useTrafficStore } from '@/stores/traffic'
import { useSimulationWebSocket } from '@/composables/useSimulationWebSocket'
import type { SimFrameData } from '@/types/traffic'

import SystemWorkbenchHeader from '@/components/SystemWorkbenchHeader.vue'
import TrafficStats from '@/components/TrafficStats.vue'
import MapRoadNetwork from '@/components/MapRoadNetwork.vue'
import SignalControlPanel from '@/components/SignalControlPanel.vue'
import EmergencyPanel from '@/components/EmergencyPanel.vue'
import CompareCharts from '@/components/CompareCharts.vue'
import AiAssistant from '@/components/AiAssistant.vue'

const store = useTrafficStore()
const { status: wsStatus, lastFrameData, lastControlDecision, connect: wsConnect, disconnect: wsDisconnect } = useSimulationWebSocket()

defineOptions({
  name: 'DashboardView',
})

// ---- 定时器句柄 ----
let vehicleTimer: ReturnType<typeof setInterval> | null = null
let statsTimer: ReturnType<typeof setInterval> | null = null
let trendTimer: ReturnType<typeof setInterval> | null = null
let dataRetryTimer: ReturnType<typeof setInterval> | null = null
const simulationOperationPending = ref(false)
let simulationHealthTimer: ReturnType<typeof setInterval> | null = null
const simulationStarting = ref(false)
let dashboardStartingSimulation = false

async function syncDashboardData(): Promise<void> {
  const loaded = await store.loadDashboardData()
  if (loaded && dataRetryTimer) {
    clearInterval(dataRetryTimer)
    dataRetryTimer = null
  }
}

function waitForWsConnected(): Promise<boolean> {
  if (wsStatus.value === 'connected') {
    return Promise.resolve(true)
  }

  return new Promise((resolve) => {
    let stopWatch: (() => void) | null = null
    const cleanup = (): void => {
      stopWatch?.()
      stopWatch = null
    }
    const timeout = window.setTimeout(() => {
      cleanup()
      resolve(false)
    }, 5000)

    stopWatch = watch(wsStatus, (s) => {
      if (s === 'connected') {
        window.clearTimeout(timeout)
        cleanup()
        resolve(true)
      } else if (s === 'error') {
        window.clearTimeout(timeout)
        cleanup()
        resolve(false)
      }
    })
  })
}

async function startSimulationFromDashboard(): Promise<void> {
  if (simulationOperationPending.value || store.simulationStatus === 'running') return
  simulationOperationPending.value = true
  try {
    let sid = store.simulationSid
    if (!sid || store.simulationStatus === 'finished' || store.simulationStatus === 'booting') {
      const result = await store.initSimulationSession()
      sid = result?.sid ?? null
    }
    if (!sid) {
      return
    }

    wsConnect(sid)
    const connected = await waitForWsConnected()
    if (connected) {
      await store.resumeSimulation()
    }
  } catch {
    // 具体错误已由 store 记录到 simulationErrorMessage
  } finally {
    simulationOperationPending.value = false
  }
}

async function pauseSimulationFromDashboard(): Promise<void> {
  if (simulationOperationPending.value || store.simulationStatus !== 'running') return
  simulationOperationPending.value = true
  try {
    await store.pauseSimulationSession()
  } finally {
    simulationOperationPending.value = false
  }
}

async function stopSimulationFromDashboard(): Promise<void> {
  if (
    simulationOperationPending.value ||
    !store.simulationSid ||
    store.simulationStatus === 'finished'
  ) {
    return
  }
  simulationOperationPending.value = true
  try {
    await store.stopSimulationSession()
  } finally {
    wsDisconnect()
    simulationOperationPending.value = false
  }
}

onMounted(() => {
  void syncDashboardData()

  simulationHealthTimer = setInterval(() => {
    store.checkSimulationFrameTimeout()
  }, 1000)

  dataRetryTimer = setInterval(() => {
    if (store.dataSourceStatus === 'database') {
      if (dataRetryTimer) {
        clearInterval(dataRetryTimer)
        dataRetryTimer = null
      }
      return
    }

    void syncDashboardData()
  }, 3000)

  function startMockTimers(): void {
    if (vehicleTimer) return // 已启动
    vehicleTimer = setInterval(() => {
      store.updateVehiclePositions(200)
    }, 200)
    statsTimer = setInterval(() => {
      store.updateTrafficIndicators(2000)
    }, 2000)
    trendTimer = setInterval(() => {
      store.addCongestionTrendPoint()
    }, 5000)
    console.log('[Dashboard] mock 定时器已启动')
  }

  function stopMockTimers(): void {
    if (vehicleTimer) { clearInterval(vehicleTimer); vehicleTimer = null }
    if (statsTimer) { clearInterval(statsTimer); statsTimer = null }
    if (trendTimer) { clearInterval(trendTimer); trendTimer = null }
    console.log('[Dashboard] mock 定时器已停止，使用仿真数据')
  }

  // 初始启动 mock 定时器
  startMockTimers()

  watch(
    () => store.simulationStatus,
    (status) => {
      if (status === 'running') {
        stopMockTimers()
      } else if (vehicleTimer === null) {
        startMockTimers()
      }
    },
  )

})

// ---- 仿真帧数据 → Store ----
watch(
  lastFrameData,
  (frame) => {
    if (frame) {
      store.handleSimFrame(frame as SimFrameData)
    }
  },
)

// ---- AI 控制决策 → Store ----
watch(lastControlDecision, (decision) => {
  if (decision) store.handleControlDecision(decision)
})

onUnmounted(() => {
  if (vehicleTimer) clearInterval(vehicleTimer)
  if (statsTimer) clearInterval(statsTimer)
  if (trendTimer) clearInterval(trendTimer)
  if (dataRetryTimer) clearInterval(dataRetryTimer)
  if (simulationHealthTimer) clearInterval(simulationHealthTimer)
  wsDisconnect()
  store.resetSimulationState()
  console.log('[Dashboard] 定时刷新已停止，WebSocket 已断开')
})
</script>

<template>
  <div class="dashboard-shell ts-dashboard">
    <!-- ============ 视频背景层 ============ -->
    <div class="video-bg">
      <video autoplay muted loop playsinline preload="auto">
        <source src="@/assets/images/bg/bg-video.mp4" type="video/mp4" />
      </video>
      <div class="video-bg-overlay" />
    </div>
    <div class="cockpit-atmosphere" />

    <!-- ============ 顶部：宿主导航栏 (8%) ============ -->
    <SystemWorkbenchHeader active-page="network" class="ts-topbar" />

    <!-- ============ 主体：左-中-右三栏 (65%) ============ -->
    <main class="ts-body">
      <!-- 左侧列 (22%)：交通统计 + AI 控制效果 -->
      <div class="ts-col ts-col--left">
        <TrafficStats />
        <CompareCharts />
      </div>

      <!-- 中央列 (56%)：MapLibre 地图路网（含离线降级到 Three.js 抽象路网） -->
      <div class="ts-col ts-col--center">
        <MapRoadNetwork />
      </div>

      <!-- 右侧列 (22%)：AI 信号控制 + 应急绿波控制 -->
      <div class="ts-col ts-col--right">
        <SignalControlPanel
          :busy="simulationOperationPending"
          @start-simulation="void startSimulationFromDashboard()"
          @pause-simulation="void pauseSimulationFromDashboard()"
          @stop-simulation="void stopSimulationFromDashboard()"
        />
        <EmergencyPanel />
      </div>
    </main>

    <AiAssistant />
  </div>
</template>

<style scoped>
/* 大屏根容器：铺满视口、纵向分区 */
.ts-dashboard {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px 14px;
  overflow: hidden;
}

/* 顶部状态栏：约 5% */
.ts-topbar {
  flex: 5 1 0;
  min-height: 44px;
  max-height: 68px;
}

.ts-body {
  flex: 1 1 0;
  display: grid;
  grid-template-columns: minmax(0, 22fr) minmax(0, 56fr) minmax(0, 22fr);
  gap: 12px;
  min-height: 0;
}

/* 列容器：纵向排列，子模块等分高度 */
.ts-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.ts-col > :deep(*) {
  flex: 1 1 0;
  min-height: 0;
}

/* 中央列仅一个模块，占满整列 */
.ts-col--center > :deep(*) {
  flex: 1 1 100%;
}

/* 网格/弹性子项防溢出 */
.ts-body > .ts-col {
  height: 100%;
  min-width: 0;
}
</style>
