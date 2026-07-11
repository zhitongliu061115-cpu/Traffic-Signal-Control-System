<script setup lang="ts">
// ================================================================
// Dashboard — AI 自适应信号控制与应急绿波数字孪生系统 主大屏
//
// 定时刷新策略（在 onMounted 中启动，onUnmounted 中清除）：
//   - 200ms：车辆位置（高频轻量）
//   - 2s：   交通统计 + 道路指数 + 信号灯（中频）
//   - 5s：   拥堵趋势 + 随机告警（低频）
// ================================================================
import { onMounted, onUnmounted, watch } from 'vue'
import { useTrafficStore } from '@/stores/traffic'
import { useSimulationWebSocket } from '@/composables/useSimulationWebSocket'
import type { SimFrameData } from '@/types/traffic'

import SystemWorkbenchHeader from '@/components/SystemWorkbenchHeader.vue'
import TrafficStats from '@/components/TrafficStats.vue'
import AlertPanel from '@/components/AlertPanel.vue'
import MapRoadNetwork from '@/components/MapRoadNetwork.vue'
import SignalControlPanel from '@/components/SignalControlPanel.vue'
import EmergencyPanel from '@/components/EmergencyPanel.vue'
import CompareCharts from '@/components/CompareCharts.vue'
import AiAssistant from '@/components/AiAssistant.vue'

const store = useTrafficStore()
const { status: wsStatus, lastFrameData, connect: wsConnect, disconnect: wsDisconnect } = useSimulationWebSocket()

defineOptions({
  name: 'DashboardView',
})

// ---- 随机告警素材 ----
const randomAlertPool = [
  {
    type: 'abnormal_congestion' as const,
    level: 'warning' as const,
    titles: [
      '长江路-北京路 车流量超阈值',
      '建设路东段 排队长度异常增长',
      '人民路南段 拥堵指数突增',
    ],
    locations: ['东城区', '西城区', '中心城区'],
    intersectionIds: ['A03', 'A06', 'A05'],
  },
  {
    type: 'device_offline' as const,
    level: 'error' as const,
    titles: ['解放大道信号机通信超时', '建设路检测器数据延迟'],
    locations: ['南区', '中区'],
    intersectionIds: ['A09', 'A06'],
  },
  {
    type: 'control_failure' as const,
    level: 'warning' as const,
    titles: ['AI 配时方案 B 收敛失败', '绿波同步异常回退'],
    locations: ['中山大道', '长江路'],
    intersectionIds: ['A02', 'A03'],
  },
]

/** 每 5s 有概率生成随机告警，增加大屏动态感 */
function maybeGenerateRandomAlert(): void {
  // ~15% 概率触发（即平均每 30s 一条）
  if (Math.random() > 0.15) return

  const pool = randomAlertPool[Math.floor(Math.random() * randomAlertPool.length)]!
  const title = pool.titles[Math.floor(Math.random() * pool.titles.length)]!
  const location = pool.locations[Math.floor(Math.random() * pool.locations.length)]!
  const intersectionId = pool.intersectionIds[Math.floor(Math.random() * pool.intersectionIds.length)]

  store.generateMockAlert(pool.type, pool.level, title, location, intersectionId)
}

// ---- 定时器句柄 ----
let vehicleTimer: ReturnType<typeof setInterval> | null = null
let statsTimer: ReturnType<typeof setInterval> | null = null
let trendTimer: ReturnType<typeof setInterval> | null = null
let dataRetryTimer: ReturnType<typeof setInterval> | null = null
let simulationHasStarted = false

async function syncDashboardData(): Promise<void> {
  const loaded = await store.loadDashboardData()
  if (loaded && dataRetryTimer) {
    clearInterval(dataRetryTimer)
    dataRetryTimer = null
  }
}

onMounted(() => {
  void syncDashboardData()

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

  // ---- 仿真初始化：创建会话 → 连接 WebSocket ----
  async function initSimulation(): Promise<void> {
    const result = await store.initSimulationSession()
    if (result?.sid) {
      wsConnect(result.sid)
      // WebSocket 连接成功后自动启动仿真
      const stopWatch = watch(wsStatus, (s) => {
        if (s === 'connected') {
          stopWatch()
          store.resumeSimulation()
        }
      })
    }
  }

  // 页面启动自动连接仿真 WebSocket（后端不可用时不影响 mock 模式运行）
  void initSimulation()

  // ---- Mock 定时器控制 ----
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
      maybeGenerateRandomAlert()
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

  // ---- 仿真运行后关闭 mock，仿真停止后恢复 mock ----
  watch(
    () => store.simulationStatus,
    (status) => {
      if (status === 'running') {
        simulationHasStarted = true
        stopMockTimers()
      } else if (!simulationHasStarted && vehicleTimer === null) {
        startMockTimers()
      }
    },
  )

  // ---- 策略切换：监听 simulationSid 变化自动重连 WebSocket ----
  watch(
    () => store.simulationSid,
    (newSid, oldSid) => {
      // 跳过首次赋值（页面初始化走 initSimulation）
      if (oldSid === undefined || oldSid === null) return
      if (!newSid) return
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
  { deep: true },
)

onUnmounted(() => {
  if (vehicleTimer) clearInterval(vehicleTimer)
  if (statsTimer) clearInterval(statsTimer)
  if (trendTimer) clearInterval(trendTimer)
  if (dataRetryTimer) clearInterval(dataRetryTimer)
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
      <!-- 左侧列 (22%)：交通统计 + 实时告警 -->
      <div class="ts-col ts-col--left">
        <TrafficStats />
        <AlertPanel />
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

    <!-- ============ 底部：控制效果对比 (27%) ============ -->
    <footer class="ts-footer">
      <CompareCharts />
    </footer>

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

/* 主体三栏区：约 70% */
.ts-body {
  flex: 70 1 0;
  display: grid;
  grid-template-columns: minmax(0, 22fr) minmax(0, 56fr) minmax(0, 22fr);
  gap: 12px;
  min-height: 0;
}

/* 底部区：约 30%，对比图表横向占满 */
.ts-footer {
  flex: 30 1 0;
  display: block;
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
.ts-body > .ts-col,
.ts-footer > :deep(*) {
  height: 100%;
  min-width: 0;
}
</style>
