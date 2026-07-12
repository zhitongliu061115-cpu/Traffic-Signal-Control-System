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
const simulationStarting = ref(false)
let dashboardStartingSimulation = false

async function syncDashboardData(): Promise<void> {
  const loaded = await store.loadDashboardData()
  if (loaded && dataRetryTimer) {
    clearInterval(dataRetryTimer)
    dataRetryTimer = null
  }
}

async function startSimulationFromDashboard(): Promise<void> {
  if (simulationStarting.value || store.simulationStatus === 'running') return
  simulationStarting.value = true
  dashboardStartingSimulation = true
  try {
    const result = await store.initSimulationSession()
    if (!result?.sid) {
      simulationStarting.value = false
      return
    }
    wsConnect(result.sid)
    const stopWatch = watch(wsStatus, (s) => {
      if (s === 'connected') {
        stopWatch()
        void store.resumeSimulation()
        simulationStarting.value = false
      } else if (s === 'error' || s === 'disconnected') {
        stopWatch()
        simulationStarting.value = false
      }
    })
  } catch {
    simulationStarting.value = false
  } finally {
    dashboardStartingSimulation = false
  }
}

onMounted(() => {
  void syncDashboardData()
  void startSimulationFromDashboard()

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

  if (store.simulationSid && store.simulationStatus !== 'finished') {
    stopMockTimers()
    wsConnect(store.simulationSid)
  } else {
    startMockTimers()
  }

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

  // ---- 策略切换：监听 simulationSid 变化自动重连 WebSocket ----
  watch(
    () => store.simulationSid,
    (newSid) => {
      if (dashboardStartingSimulation) return // Dashboard 首次启动流程会自行连接，避免重复连接
      if (!newSid) return // resetSimulationState 置空，跳过
      // recreate 触发的 sid 变化 → 断开旧连接 + 接新连接
      wsDisconnect()
      wsConnect(newSid)
      const stop = watch(wsStatus, (s) => {
        if (s === 'connected') { stop(); store.resumeSimulation() }
      })
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
  wsDisconnect()
  console.log('[Dashboard] 定时刷新已停止，仿真会话缓存保留供其他页面使用')
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

    <button
      class="ts-sim-start"
      type="button"
      :disabled="simulationStarting || store.simulationStatus === 'running'"
      @click="void startSimulationFromDashboard()"
    >
      {{ store.simulationStatus === 'running' ? '仿真运行中' : simulationStarting ? '启动中' : '启动仿真' }}
    </button>

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
        <SignalControlPanel />
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

/* 主体三栏区：占满顶部栏之外的全部空间 */
.ts-sim-start {
  position: absolute;
  top: 18px;
  right: 24px;
  z-index: 20;
  min-width: 96px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid rgba(0, 212, 255, 0.45);
  background: rgba(4, 21, 39, 0.82);
  color: #7af7ff;
  font-size: 13px;
  cursor: pointer;
}

.ts-sim-start:disabled {
  cursor: default;
  opacity: 0.62;
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
