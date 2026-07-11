// ================================================================
// signalDerive.ts — 从现有 currentPhase + greenRemain 派生 MapLibre 信号显示
// 不新增枚举，复用现有类型
// ================================================================
import type { Intersection } from '@/types/traffic'

export type SignalLight = 'green' | 'yellow' | 'red'

/** 从路口当前相位 + 剩余时间推导信号灯颜色 */
export function signalStatus(it: Intersection): SignalLight {
  if (it.currentPhase === 'all_red') return 'red'
  if (it.deviceStatus !== 'online') return 'red'
  if (it.greenRemain <= 3) return 'red'
  if (it.greenRemain <= 8) return 'yellow'
  return 'green'
}

/** 放行方向简写 */
export function signalDirection(it: Intersection): 'NS' | 'EW' | '—' {
  if (it.deviceStatus !== 'online') return '—'
  if (it.currentPhase === 'all_red') return '—'
  if (it.currentPhase.startsWith('eastwest')) return 'EW'
  if (it.currentPhase.startsWith('northsouth')) return 'NS'
  return '—'
}

/** 信号颜色 → hex */
export function signalColorHex(status: SignalLight): string {
  return status === 'green' ? '#22D3A0' : status === 'yellow' ? '#FFB800' : '#FF4D6D'
}
