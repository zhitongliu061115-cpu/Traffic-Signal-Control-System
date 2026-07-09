// ================================================================
// amapMarkers.ts — 高德红绿灯 DOM Marker
// ================================================================
import type { Intersection } from '@/types/traffic'
import { signalStatus, signalDirection, signalColorHex } from './signalDerive'

export interface TLMarker {
  id: string
  marker: AMap.Marker
  update: (it: Intersection) => void
  remove: () => void
}

function buildContent(it: Intersection): string {
  const s = signalStatus(it)
  const dir = signalDirection(it)
  const color = signalColorHex(s)
  const dColor = it.deviceStatus === 'online' ? '#22D3A0' : it.deviceStatus === 'fault' ? '#FF4D6D' : '#5A7595'
  return `<div style="display:flex;flex-direction:column;align-items:center;gap:2px;cursor:pointer;user-select:none;transform:translate(-50%,-50%)">
    <div style="width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid rgba(255,255,255,0.3);background:${color};box-shadow:0 0 14px ${color}">
      <span style="font-family:Rajdhani,sans-serif;font-size:13px;font-weight:700;color:#fff;text-shadow:0 0 4px rgba(0,0,0,0.6)">${Math.round(it.greenRemain)}</span>
    </div>
    <div style="font-family:Rajdhani,sans-serif;font-size:10px;font-weight:600;color:#e8f4ff;background:rgba(4,21,39,0.75);padding:1px 5px;border-radius:2px">${dir}</div>
    <div style="width:7px;height:7px;border-radius:50%;background:${dColor};box-shadow:0 0 5px ${dColor}"></div>
    <div style="font-size:9px;color:#8da8c5;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:60px">${it.name.split('-')[0]}</div>
  </div>`
}

export function createTLMarkers(
  map: AMap.Map,
  intersections: Intersection[],
  onSelect: (id: string) => void,
): { markers: TLMarker[]; updateAll: (its: Intersection[]) => void; dispose: () => void } {
  const markers: TLMarker[] = []

  for (const it of intersections) {
    const content = buildContent(it)
    const marker = new AMap.Marker({
      position: [it.lng, it.lat],
      content,
      offset: new AMap.Pixel(-20, -20),  // 图标 ~40px 圆心对齐
      zIndex: 100,
    })
    marker.setMap(map)
    marker.on('click', () => onSelect(it.id))

    markers.push({
      id: it.id,
      marker,
      update: (updatedIt: Intersection) => {
        marker.setContent(buildContent(updatedIt))
      },
      remove: () => marker.setMap(null),
    })
  }

  return {
    markers,
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
