<script setup lang="ts">
// ================================================================
// AlertPanel — 实时告警面板
// 从 Pinia store 读取告警列表，支持分级着色、确认、滚动
// ================================================================
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useTrafficStore } from '@/stores/traffic'
import { ALERT_TYPE_LABELS } from '@/types/traffic'
import type { AlertLevel, AlertType } from '@/types/traffic'

const store = useTrafficStore()
const { alerts, highAlerts, unacknowledgedAlertCount } = storeToRefs(store)

// ---- 告警等级 → 视觉配置 ----
const LEVEL_CONFIG: Record<AlertLevel, { color: string; icon: string; cls: string; glow: string }> = {
  emergency: { color: '#FF4D6D', icon: '🚨', cls: 'alert-item--emergency', glow: 'alert-item--glow' },
  error:     { color: '#FF4D6D', icon: '⚠',  cls: 'alert-item--error',     glow: 'alert-item--glow' },
  warning:   { color: '#FFB800', icon: '⚡',  cls: 'alert-item--warning',   glow: '' },
  info:      { color: '#00D4FF', icon: 'ℹ',  cls: 'alert-item--info',      glow: '' },
}

const TYPE_ICON: Record<AlertType, string> = {
  abnormal_congestion: '🚦',
  device_offline:      '🔌',
  device_fault:        '⚠️',
  control_failure:     '❌',
  emergency_event:     '🚨',
  emergency_vehicle_enter: '🚑',
  green_wave_start:    '🌊',
  green_wave_restore:  '🔙',
  ai_control_start:    '🟢',
  ai_control_pause:    '⏸️',
}

// ---- 最新 6 条未确认告警优先 ----
const visibleAlerts = computed(() => {
  const all = [...alerts.value]
  // 未确认的排前面，已确认排后面
  all.sort((a, b) => {
    if (a.acknowledged !== b.acknowledged) return a.acknowledged ? 1 : -1
    return b.time.localeCompare(a.time)
  })
  return all.slice(0, 6)
})

const emergencyCount = computed(() =>
  alerts.value.filter((a) => a.level === 'emergency' && !a.acknowledged).length,
)
const errorCount = computed(() =>
  alerts.value.filter((a) => a.level === 'error' && !a.acknowledged).length,
)
const unackCount = computed(() => unacknowledgedAlertCount.value)

/** 点击告警：确认并选中关联路口 */
function handleAlertClick(alertId: string, intersectionId?: string) {
  store.acknowledgeAlert(alertId)
  if (intersectionId) {
    store.selectIntersection(intersectionId)
  }
}

/** 一键确认全部 */
function acknowledgeAll() {
  alerts.value.forEach((a) => {
    if (!a.acknowledged) store.acknowledgeAlert(a.id)
  })
}
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-panel-titlebar">
      <div class="titlebar-inner">
        <span class="titlebar-mark" />
        <span class="titlebar-text">实时告警</span>
        <span class="alert-counts">
          <span v-if="emergencyCount > 0" class="hud-pill hud-pill--rose">
            🚨 应急 {{ emergencyCount }}
          </span>
          <span v-if="errorCount > 0" class="hud-pill hud-pill--rose">
            严重 {{ errorCount }}
          </span>
          <span v-if="unackCount > 0" class="hud-pill hud-pill--amber">
            未处理 {{ unackCount }}
          </span>
          <button
            v-if="unackCount > 0"
            class="ack-all-btn"
            title="一键确认全部"
            @click.stop="acknowledgeAll"
          >
            全部确认
          </button>
        </span>
      </div>
    </div>

    <div class="hud-card__content comp-card__body">
      <!-- 当没有告警时显示空状态 -->
      <div v-if="visibleAlerts.length === 0" class="alert-empty">
        <div class="alert-empty__icon">✅</div>
        <div class="alert-empty__text">当前无活跃告警</div>
      </div>

      <!-- 告警列表 -->
      <div v-else class="alert-list">
        <div
          v-for="a in visibleAlerts"
          :key="a.id"
          class="alert-item"
          :class="[
            LEVEL_CONFIG[a.level]?.cls ?? '',
            LEVEL_CONFIG[a.level]?.glow ?? '',
            { 'alert-item--acked': a.acknowledged },
          ]"
          @click="handleAlertClick(a.id, a.intersectionId)"
        >
          <!-- 关联图标 -->
          <div class="alert-icon" :style="{ color: LEVEL_CONFIG[a.level]?.color ?? '#8DA8C5' }">
            {{ TYPE_ICON[a.type] ?? LEVEL_CONFIG[a.level]?.icon }}
          </div>

          <div class="alert-content">
            <div class="alert-header-row">
              <span class="alert-type-badge" :style="{ color: LEVEL_CONFIG[a.level]?.color }">
                {{ ALERT_TYPE_LABELS[a.type] ?? a.type }}
              </span>
              <span v-if="a.acknowledged" class="alert-acked-tag">已确认</span>
            </div>
            <div class="alert-title">{{ a.title }}</div>
            <div class="alert-location">{{ a.location }}</div>
            <div class="alert-time">{{ a.time }}</div>
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
  overflow: hidden;
  padding-top: 4px;
}

.alert-counts {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 5px;
}

.alert-counts .hud-pill {
  font-size: 10px;
  padding: 3px 9px;
}

.ack-all-btn {
  font-size: 10px;
  padding: 2px 8px;
  background: transparent;
  border: 1px solid rgba(255, 184, 0, 0.5);
  color: #ffb800;
  cursor: pointer;
  font-family: inherit;
  letter-spacing: 0.04em;
  transition: background 200ms ease, box-shadow 200ms ease;
}

.ack-all-btn:hover {
  background: rgba(255, 184, 0, 0.12);
  box-shadow: 0 0 8px rgba(255, 184, 0, 0.25);
}

/* 空状态 */
.alert-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.alert-empty__icon {
  font-size: 28px;
  opacity: 0.5;
}

.alert-empty__text {
  font-size: 13px;
  color: #5a7595;
}

/* 列表 */
.alert-list {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  padding-right: 4px;
}

/* 告警条目增强 */
.alert-item {
  cursor: pointer;
  transition: all 200ms ease;
}

.alert-item--acked {
  opacity: 0.45;
}

.alert-item--acked:hover {
  opacity: 0.7;
}

/* 紧急告警呼吸光效 */
.alert-item--glow {
  animation: alert-glow-pulse 2s ease-in-out infinite;
}

@keyframes alert-glow-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 77, 109, 0); }
  50% { box-shadow: 0 0 14px rgba(255, 77, 109, 0.25), inset 0 0 8px rgba(255, 77, 109, 0.08); }
}

.alert-header-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}

.alert-type-badge {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  padding: 1px 5px;
  border: 1px solid currentColor;
  opacity: 0.85;
}

.alert-acked-tag {
  font-size: 10px;
  color: #22d3a0;
  margin-left: auto;
}
</style>
