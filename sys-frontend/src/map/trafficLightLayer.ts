// ================================================================
// trafficLightLayer.ts — 红绿灯 DOM Markers（LOD2/LOD3 显示）
// 直接用 HTML 字符串创建 MapLibre Marker，绕过 Vue 异步渲染时序问题
// ================================================================
import maplibregl from 'maplibre-gl'
import type { Intersection } from '@/types/traffic'
import { signalStatus, signalDirection, signalColorHex } from './signalDerive'

export interface TrafficMarker {
  marker: maplibregl.Marker
  id: string
  show: () => void
  hide: () => void
  remove: () => void
  /** 更新灯色和倒计时 */
  update: (it: Intersection) => void
  /** 重新定位（地理吸附完成后，把 Marker 挪到真实路口交叉点） */
  setPosition: (lngLat: [number, number]) => void
}

/** 生成单个路口的 HTML（纯字符串，不依赖 Vue） */
function buildMarkerHTML(it: Intersection): string {
  const s = signalStatus(it)
  const dir = signalDirection(it)
  const color = signalColorHex(s)
  const dColor =
    it.deviceStatus === 'online' ? '#22D3A0'
      : it.deviceStatus === 'fault' ? '#FF4D6D'
      : '#5A7595'
  return `
<div class="tl-marker" style="display:flex;flex-direction:column;align-items:center;gap:2px;cursor:pointer;user-select:none;">
  <div class="tl-light" style="width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid rgba(255,255,255,0.3);background:${color};box-shadow:0 0 14px ${color};">
    <span class="tl-time" style="font-family:Rajdhani,sans-serif;font-size:13px;font-weight:700;color:#fff;text-shadow:0 0 4px rgba(0,0,0,0.6);">${it.greenRemainKnown === false ? '—' : Math.round(it.greenRemain)}</span>
  </div>
  <div class="tl-dir" style="font-family:Rajdhani,sans-serif;font-size:10px;font-weight:600;color:#e8f4ff;background:rgba(4,21,39,0.75);padding:1px 5px;border-radius:2px;">${dir}</div>
  <div class="tl-device" style="width:7px;height:7px;border-radius:50%;background:${dColor};box-shadow:0 0 5px ${dColor};"></div>
  <div class="tl-name" style="font-size:9px;color:#8da8c5;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:60px;">${it.name.split('-')[0]}</div>
</div>`
}

/**
 * 为所有路口创建红绿灯 DOM Markers。
 * 纯 HTML 方案，不依赖 Vue 异步渲染，Marker 创建立即可见。
 */
export function createTrafficLightMarkers(
  map: maplibregl.Map,
  intersections: Intersection[],
  onSelect: (id: string) => void,
): { markers: TrafficMarker[]; syncByZoom: (zoom: number) => void; updateAll: (its: Intersection[]) => void; dispose: () => void } {
  const markers: TrafficMarker[] = []

  for (const it of intersections) {
    const el = document.createElement('div')
    el.innerHTML = buildMarkerHTML(it)
    el.addEventListener('click', () => onSelect(it.id))
    el.addEventListener('dblclick', () => onSelect(it.id))

    const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
      .setLngLat([it.lng, it.lat])
      .addTo(map)

    markers.push({
      marker, id: it.id,
      show: () => { el.style.display = '' },
      hide: () => { el.style.display = 'none' },
      remove: () => { marker.remove() },
      update: (updatedIt: Intersection) => {
        el.innerHTML = buildMarkerHTML(updatedIt)
      },
      setPosition: (lngLat: [number, number]) => {
        marker.setLngLat(lngLat)
      },
    })
  }

  return {
    markers,
    syncByZoom(zoom: number) {
      const visible = zoom >= 13
      markers.forEach((m) => (visible ? m.show() : m.hide()))
    },
    updateAll(its: Intersection[]) {
      for (const it of its) {
        const m = markers.find((mk) => mk.id === it.id)
        if (m) m.update(it)
      }
    },
    dispose() {
      markers.forEach((m) => m.remove())
    },
  }
}

