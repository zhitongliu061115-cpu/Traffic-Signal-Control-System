<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'

type ActivePage = 'analytics' | 'network'

const props = defineProps<{
  activePage: ActivePage
}>()

const SYSTEM_TITLE = '信号灯配时控制与应急通行信控系统'
const trafficStore = useTrafficStore()
const { dataSourceStatus, dataSourceMessage } = storeToRefs(trafficStore)

const navItems = [
  {
    key: 'network',
    label: '路网大屏页面',
    to: '/',
    description: '城市路网、信号配时、拥堵态势与应急通行监控',
  },
  {
    key: 'analytics',
    label: '数据分析页面',
    to: '/data-analysis',
    description: '能耗、人流、设备状态、风险分层与监测明细分析',
  },
] as const

const now = ref(new Date())
let clockTimer: ReturnType<typeof setInterval> | null = null

const dateText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    day: '2-digit',
    month: '2-digit',
    weekday: 'short',
  }).format(now.value),
)

const timeText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    hour12: false,
    minute: '2-digit',
    second: '2-digit',
  }).format(now.value),
)

const sourceText = computed(() => {
  if (dataSourceStatus.value === 'database') return '数据库数据'
  if (dataSourceStatus.value === 'loading') return '连接中'
  return '本地演示'
})

const sourceToneClass = computed(() => {
  if (dataSourceStatus.value === 'database') return 'text-emerald'
  if (dataSourceStatus.value === 'loading') return 'text-cyan'
  return 'text-amber'
})

const sourceDotClass = computed(() => ({
  'data-status-dot--loading': dataSourceStatus.value === 'loading',
  'data-status-dot--warning': dataSourceStatus.value === 'mock',
}))

onMounted(() => {
  clockTimer = setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <header class="workbench-header">
    <div class="workbench-header__inner">
      <nav class="cyber-nav-shell" aria-label="页面导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.key"
          :aria-current="item.key === props.activePage ? 'page' : undefined"
          class="cyber-tab"
          :class="{ 'cyber-tab-active': item.key === props.activePage }"
          :title="item.description"
          :to="item.to"
        >
          <span class="cyber-tab__icon" aria-hidden="true">
            <svg v-if="item.key === 'network'" viewBox="0 0 24 24">
              <path
                d="M4.5 18.5 8.2 5.2h2.1L8.7 11h6.6l-1.6-5.8h2.1l3.7 13.3h-2.2l-1.4-5.2H8.1l-1.4 5.2H4.5Z"
                fill="currentColor"
              />
              <path
                d="M7.4 15.2h9.2M9.1 8.4h5.8"
                fill="none"
                stroke="currentColor"
                stroke-linecap="round"
                stroke-width="1.4"
              />
            </svg>
            <svg v-else viewBox="0 0 24 24">
              <path
                d="M5 18.25h14v1.5H5Zm1-2.5V9.5h1.75v6.25Zm5 0V5.5h1.75v10.25Zm5 0v-4.5h1.75v4.5Z"
                fill="currentColor"
              />
            </svg>
          </span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="title-plate" aria-label="系统名称">
        <svg aria-hidden="true" preserveAspectRatio="none" viewBox="0 0 620 58">
          <defs>
            <linearGradient id="workbenchTitleStroke" x1="0" x2="1" y1="0" y2="0">
              <stop offset="0%" stop-color="#034d7a" stop-opacity="0" />
              <stop offset="16%" stop-color="#00d4ff" stop-opacity="0.76" />
              <stop offset="50%" stop-color="#7af7ff" stop-opacity="0.95" />
              <stop offset="84%" stop-color="#00d4ff" stop-opacity="0.76" />
              <stop offset="100%" stop-color="#034d7a" stop-opacity="0" />
            </linearGradient>
            <linearGradient id="workbenchTitleFill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stop-color="#0a2540" stop-opacity="0.68" />
              <stop offset="100%" stop-color="#020817" stop-opacity="0.08" />
            </linearGradient>
          </defs>
          <path
            d="M48 6H572L606 29L572 52H48L14 29L48 6Z"
            fill="url(#workbenchTitleFill)"
            stroke="url(#workbenchTitleStroke)"
            stroke-width="1.8"
          />
          <path
            d="M118 14H502M118 44H502"
            stroke="url(#workbenchTitleStroke)"
            stroke-linecap="round"
            stroke-width="1.4"
          />
          <path
            d="M54 14H96M524 14H566M54 44H96M524 44H566"
            stroke="#7af7ff"
            stroke-linecap="round"
            stroke-width="2.2"
          />
          <path
            d="M78 7L52 29L78 51M542 7L568 29L542 51"
            fill="none"
            opacity="0.55"
            stroke="#00d4ff"
            stroke-width="1.2"
          />
        </svg>
        <div class="title-plate-text">{{ SYSTEM_TITLE }}</div>
      </div>

      <div class="data-header-status">
        <div class="data-status-cell data-status-online" :title="dataSourceMessage">
          <span class="data-status-dot" :class="sourceDotClass" />
          <div>
            <div class="data-status-kicker">数据来源</div>
            <div class="data-status-value" :class="sourceToneClass">{{ sourceText }}</div>
          </div>
        </div>
        <span class="data-status-divider" />
        <div class="data-status-cell text-right" title="时间同步状态">
          <div>
            <div class="data-status-kicker">{{ dateText }}</div>
            <div class="status-time">{{ timeText }}</div>
          </div>
        </div>
        <span class="data-status-divider" />
        <div class="data-status-cell data-status-weather" title="室外天气">
          <span class="weather-glyph" aria-hidden="true" />
          <div>
            <div class="data-status-kicker">多云</div>
            <div class="data-status-value text-cyan">26°C</div>
          </div>
        </div>
        <button class="data-status-icon-btn data-status-bell" title="通知中心" type="button">
          <span class="data-status-badge">3</span>
          <svg aria-hidden="true" viewBox="0 0 24 24">
            <path
              d="M12 3.5a5.5 5.5 0 0 0-5.5 5.5v3.4L5 15v1h14v-1l-1.5-2.6V9A5.5 5.5 0 0 0 12 3.5Zm-2.2 14a2.3 2.3 0 0 0 4.4 0h-4.4Z"
              fill="currentColor"
            />
          </svg>
        </button>
        <button class="data-status-icon-btn data-status-gear" title="页面设置" type="button">
          <svg aria-hidden="true" viewBox="0 0 24 24">
            <path
              d="M19.4 13.5a7.8 7.8 0 0 0 0-3l2-1.5-2-3.4-2.4 1a7.3 7.3 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.6A7.3 7.3 0 0 0 7 6.6l-2.4-1-2 3.4 2 1.5a7.8 7.8 0 0 0 0 3l-2 1.5 2 3.4 2.4-1a7.3 7.3 0 0 0 2.6 1.5l.4 2.6h4l.4-2.6a7.3 7.3 0 0 0 2.6-1.5l2.4 1 2-3.4-2-1.5ZM12 8.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7Z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.workbench-header {
  position: sticky;
  top: 0;
  z-index: 35;
  display: flex;
  min-height: 73px;
  align-items: center;
  padding: 8px 16px;
  border-bottom: 1px solid rgba(0, 212, 255, 0.1);
  background: linear-gradient(180deg, rgba(2, 8, 23, 0.18), rgba(2, 8, 23, 0));
  box-shadow: 0 8px 34px rgba(0, 212, 255, 0.08);
  backdrop-filter: blur(12px);
}

.workbench-header__inner {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 16px;
}

.cyber-nav-shell {
  position: relative;
  z-index: 2;
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  padding: 5px;
  border: 1px solid rgba(0, 212, 255, 0.26);
  background:
    linear-gradient(180deg, rgba(7, 30, 54, 0.86), rgba(2, 8, 23, 0.44)),
    linear-gradient(90deg, rgba(0, 212, 255, 0.14), transparent 55%);
  box-shadow: inset 0 0 18px rgba(0, 212, 255, 0.08), 0 0 20px rgba(0, 212, 255, 0.08);
  clip-path: polygon(12px 0, 100% 0, calc(100% - 12px) 100%, 0 100%);
  backdrop-filter: blur(14px);
}

.cyber-tab {
  display: inline-flex;
  min-width: 142px;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  color: rgba(255, 255, 255, 0.58);
  background: transparent;
  font-family: 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
  font-size: 15px;
  font-weight: 800;
  text-decoration: none;
  transition:
    color 180ms ease,
    border-color 180ms ease,
    background-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease,
    filter 180ms ease;
  clip-path: polygon(10px 0, 100% 0, calc(100% - 10px) 100%, 0 100%);
}

.cyber-tab:hover {
  color: #dff9ff;
  border-color: rgba(0, 212, 255, 0.24);
  background: rgba(0, 212, 255, 0.08);
  transform: translateY(-1px);
}

.cyber-tab-active {
  color: #7af7ff;
  border-color: rgba(122, 247, 255, 0.72);
  background:
    linear-gradient(180deg, rgba(0, 212, 255, 0.22), rgba(0, 212, 255, 0.08)),
    rgba(0, 212, 255, 0.08);
  box-shadow: 0 0 18px rgba(0, 212, 255, 0.22), inset 0 0 12px rgba(122, 247, 255, 0.1);
}

.cyber-tab__icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  color: currentColor;
  filter: drop-shadow(0 0 8px rgba(0, 212, 255, 0.28));
}

.cyber-tab__icon svg {
  width: 100%;
  height: 100%;
}

.title-plate {
  pointer-events: none;
  position: absolute;
  left: 50%;
  top: 50%;
  width: min(42vw, 620px);
  height: 58px;
  transform: translate(-50%, -50%);
}

.title-plate svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.title-plate-text {
  position: relative;
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: #f0fbff;
  font-family: 'DOUYUFont', 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
  font-size: 25px;
  font-weight: 800;
  text-align: center;
  text-shadow: 0 0 8px rgba(122, 247, 255, 0.68), 0 0 22px rgba(0, 212, 255, 0.34);
}

.data-header-status {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
  font-family: 'AlimamaShuHeiTi', 'Microsoft YaHei', sans-serif;
}

.data-header-status::before {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  top: -7px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(122, 247, 255, 0.78), transparent);
}

.data-status-cell {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.data-status-online {
  padding-left: 2px;
}

.data-status-kicker {
  color: rgba(184, 230, 255, 0.58);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.1;
}

.data-status-value {
  margin-top: 5px;
  font-size: 14px;
  font-weight: 800;
  line-height: 1;
  white-space: nowrap;
}

.text-emerald {
  color: #22d3a0;
}

.text-amber {
  color: #fbbf24;
}

.text-cyan,
.status-time {
  color: #7af7ff;
}

.status-time {
  margin-top: 4px;
  font-family: 'Rajdhani', 'DINPro', monospace;
  font-size: 20px;
  font-weight: 800;
  line-height: 1;
}

.data-status-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: #22d3a0;
  box-shadow:
    0 0 0 4px rgba(34, 211, 160, 0.1),
    0 0 14px rgba(34, 211, 160, 0.85);
  animation: workbench-status-dot-breathe 1.8s ease-in-out infinite;
}

.data-status-dot--loading {
  background: #7af7ff;
  box-shadow:
    0 0 0 4px rgba(122, 247, 255, 0.1),
    0 0 14px rgba(122, 247, 255, 0.85);
}

.data-status-dot--warning {
  background: #fbbf24;
  box-shadow:
    0 0 0 4px rgba(251, 191, 36, 0.1),
    0 0 14px rgba(251, 191, 36, 0.85);
}

.data-status-divider {
  width: 1px;
  height: 30px;
  background: linear-gradient(180deg, transparent, rgba(0, 212, 255, 0.48), transparent);
}

.weather-glyph {
  position: relative;
  display: inline-flex;
  width: 24px;
  height: 16px;
  border-radius: 999px;
  background: rgba(122, 247, 255, 0.16);
  box-shadow: inset 0 0 0 1px rgba(122, 247, 255, 0.35), 0 0 14px rgba(0, 212, 255, 0.24);
}

.weather-glyph::before {
  content: '';
  position: absolute;
  left: 3px;
  top: -6px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: rgba(255, 184, 0, 0.88);
  box-shadow: 0 0 12px rgba(255, 184, 0, 0.55);
}

.data-status-icon-btn {
  position: relative;
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 212, 255, 0.24);
  color: rgba(207, 250, 254, 0.82);
  background: rgba(8, 47, 73, 0.22);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  cursor: pointer;
  transition:
    color 180ms ease,
    border-color 180ms ease,
    background-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.data-status-icon-btn:hover {
  color: #7af7ff;
  border-color: rgba(122, 247, 255, 0.72);
  background: rgba(0, 212, 255, 0.1);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.2);
  transform: translateY(-1px);
}

.data-status-icon-btn svg {
  width: 18px;
  height: 18px;
}

.data-status-bell:hover svg {
  animation: workbench-status-bell-wiggle 520ms ease;
}

.data-status-gear:hover svg {
  animation: workbench-status-gear-spin 1.4s linear infinite;
}

.data-status-badge {
  position: absolute;
  right: -4px;
  top: -5px;
  display: inline-flex;
  min-width: 17px;
  height: 17px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 77, 141, 0.48);
  border-radius: 999px;
  color: #ffe7f0;
  background: rgba(255, 77, 141, 0.9);
  font-family: 'Rajdhani', 'DINPro', monospace;
  font-size: 12px;
  font-weight: 800;
  box-shadow: 0 0 12px rgba(255, 77, 141, 0.48);
}

@media (max-width: 1380px) {
  .title-plate {
    display: none;
  }
}

@media (max-width: 1080px) {
  .workbench-header {
    position: relative;
  }

  .workbench-header__inner {
    align-items: flex-start;
  }

  .data-header-status {
    display: none;
  }
}

@media (max-width: 720px) {
  .workbench-header__inner {
    display: block;
  }

  .cyber-nav-shell {
    width: 100%;
    overflow-x: auto;
  }

  .cyber-tab {
    min-width: 132px;
    font-size: 14px;
  }
}

@keyframes workbench-status-dot-breathe {
  0%,
  100% {
    box-shadow:
      0 0 0 4px rgba(34, 211, 160, 0.1),
      0 0 14px rgba(34, 211, 160, 0.85);
  }
  50% {
    box-shadow:
      0 0 0 7px rgba(34, 211, 160, 0.05),
      0 0 22px rgba(34, 211, 160, 1);
  }
}

@keyframes workbench-status-bell-wiggle {
  0%,
  100% {
    transform: rotate(0deg);
  }
  25% {
    transform: rotate(-9deg);
  }
  55% {
    transform: rotate(7deg);
  }
  75% {
    transform: rotate(-4deg);
  }
}

@keyframes workbench-status-gear-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
