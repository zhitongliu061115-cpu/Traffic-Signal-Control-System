<script setup lang="ts">
// ================================================================
// TrafficLightMarker — MapLibre DOM Marker（红绿灯图标 + 倒计时 + 设备点）
// 每个路口一个实例，由 trafficLightLayer.ts 创建并挂载到地图
// ================================================================
import { computed } from 'vue'
import type { Intersection } from '@/types/traffic'
import { signalStatus, signalDirection, signalColorHex } from '@/map/signalDerive'
import { DEVICE_STATUS_LABELS } from '@/types/traffic'

const props = defineProps<{ intersection: Intersection }>()
const emit = defineEmits<{ select: [] }>()

const status = computed(() => signalStatus(props.intersection))
const dir = computed(() => signalDirection(props.intersection))
const color = computed(() => signalColorHex(status.value))
const deviceColor = computed(() =>
  props.intersection.deviceStatus === 'online' ? '#22D3A0'
    : props.intersection.deviceStatus === 'fault' ? '#FF4D6D'
    : '#5A7595',
)
const deviceClass = computed(() =>
  props.intersection.deviceStatus === 'fault' ? 'tl-device--fault' : '',
)
</script>

<template>
  <div class="tl-marker" @click="emit('select')" @dblclick="emit('select')">
    <!-- 信号灯圆 -->
    <div class="tl-light" :style="{ background: color, boxShadow: `0 0 14px ${color}` }">
      <span class="tl-time">{{ Math.round(intersection.greenRemain) }}</span>
    </div>
    <!-- 方向指示 -->
    <div class="tl-dir">{{ dir }}</div>
    <!-- 设备状态点 -->
    <div
      class="tl-device"
      :class="deviceClass"
      :style="{ background: deviceColor }"
      :title="DEVICE_STATUS_LABELS[intersection.deviceStatus]"
    />
    <!-- 路口名称 -->
    <div class="tl-name">{{ intersection.name.split('-')[0] }}</div>
  </div>
</template>

<style scoped>
.tl-marker {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  user-select: none;
  min-width: 52px;
}
.tl-light {
  width: 28px; height: 28px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  border: 2px solid rgba(255,255,255,0.3);
}
.tl-time {
  font-family: 'Rajdhani', sans-serif; font-size: 13px; font-weight: 700;
  color: #fff; text-shadow: 0 0 4px rgba(0,0,0,0.6);
}
.tl-dir {
  font-family: 'Rajdhani', sans-serif; font-size: 10px; font-weight: 600;
  color: #e8f4ff; background: rgba(4,21,39,0.75);
  padding: 1px 5px; border-radius: 2px;
}
.tl-device {
  width: 7px; height: 7px; border-radius: 50%;
  box-shadow: 0 0 5px currentColor;
}
.tl-device--fault {
  animation: tl-fault-blink 0.7s steps(2, jump-none) infinite;
}
@keyframes tl-fault-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.2; }
}
.tl-name {
  font-size: 9px; color: #8da8c5; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; max-width: 60px;
}
</style>
