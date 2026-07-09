<script setup lang="ts">
// ================================================================
// Dashboard — AI 自适应信号控制与应急绿波数字孪生系统 主大屏
//
// 定时刷新策略（在 onMounted 中启动，onUnmounted 中清除）：
//   - 200ms：车辆位置（高频轻量）
//   - 2s：   交通统计 + 道路指数 + 信号灯（中频）
//   - 5s：   拥堵趋势 + 随机告警（低频）
// ================================================================
import { onMounted, onUnmounted } from 'vue'
import { useTrafficStore } from '@/stores/traffic'

import SystemWorkbenchHeader from '@/components/SystemWorkbenchHeader.vue'
import TrafficStats from '@/components/TrafficStats.vue'
import AlertPanel from '@/components/AlertPanel.vue'
import MapRoadNetwork from '@/components/MapRoadNetwork.vue'
import SignalControlPanel from '@/components/SignalControlPanel.vue'
import EmergencyPanel from '@/components/EmergencyPanel.vue'
import CompareCharts from '@/components/CompareCharts.vue'
import AiAssistant from '@/components/AiAssistant.vue'

const store = useTrafficStore()

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

onMounted(() => {
  // 200ms — 车辆位置高频更新
  vehicleTimer = setInterval(() => {
    store.updateVehiclePositions(200)
  }, 200)

  // 2s — 交通统计 / 道路指数 / 信号灯 / 系统延迟
  statsTimer = setInterval(() => {
    store.updateTrafficIndicators(2000)
  }, 2000)

  // 5s — 拥堵趋势 + 随机告警
  trendTimer = setInterval(() => {
    store.addCongestionTrendPoint()
    maybeGenerateRandomAlert()
  }, 5000)

  console.log('[Dashboard] 定时刷新已启动 (200ms / 2s / 5s)')
})

onUnmounted(() => {
  if (vehicleTimer) clearInterval(vehicleTimer)
  if (statsTimer) clearInterval(statsTimer)
  if (trendTimer) clearInterval(trendTimer)
  console.log('[Dashboard] 定时刷新已停止')
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

/* 顶部状态栏：约 8% */
.ts-topbar {
  flex: 8 1 0;
  min-height: 58px;
  max-height: 92px;
}

/* 主体三栏区：约 65% */
.ts-body {
  flex: 65 1 0;
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
