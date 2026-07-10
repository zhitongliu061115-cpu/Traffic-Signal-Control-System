// ================================================================
// amapMarkers.ts — 高德四方向红绿灯 DOM Marker
// 每个路口显示 4 个小灯 (N/S/E/W)，当前相位方向绿，其余红
// ================================================================
import type { Intersection } from '@/types/traffic'
import { signalColorHex } from './signalDerive'

export interface TLMarker {
  id: string
  marker: AMap.Marker
  update: (it: Intersection) => void
  remove: () => void
}

function buildContent(it: Intersection): string {
  const isEW = it.currentPhase.startsWith('eastwest')
  const isNS = it.currentPhase.startsWith('northsouth')
  const allRed = it.currentPhase === 'all_red' || it.deviceStatus !== 'online'
  const rem = Math.round(it.greenRemain)
  const dColor =
    it.deviceStatus === 'online' ? '#22D3A0'
      : it.deviceStatus === 'fault' ? '#FF4D6D'
      : '#5A7595'

  function light(active: boolean, label: string): string {
    const c = active && !allRed ? signalColorHex('green') : signalColorHex('red')
    return `<div style="display:flex;flex-direction:column;align-items:center;gap:1px">
      <div style="width:22px;height:22px;border-radius:50%;background:${c};border:1.5px solid rgba(255,255,255,0.25);display:flex;align-items:center;justify-content:center;box-shadow:0 0 8px ${c}">
        <span style="font-family:Rajdhani,sans-serif;font-size:11px;font-weight:700;color:#fff;text-shadow:0 0 3px rgba(0,0,0,0.5)">${rem}</span>
      </div>
      <span style="font-size:8px;color:#8da8c5">${label}</span>
    </div>`
  }

  return `<div style="display:flex;flex-direction:column;align-items:center;gap:3px;cursor:pointer;user-select:none">
    <div style="display:flex;align-items:center;gap:4px">
      <div style="display:flex;flex-direction:column;align-items:center;gap:2px">
        ${light(isEW, '西')}
        ${light(isNS, '南')}
      </div>
      <div style="width:1px;height:56px;background:rgba(255,255,255,0.1)"></div>
      <div style="display:flex;flex-direction:column;align-items:center;gap:2px">
        ${light(isEW, '东')}
        ${light(isNS, '北')}
      </div>
    </div>
    <div style="display:flex;align-items:center;gap:3px">
      <div style="width:5px;height:5px;border-radius:50%;background:${dColor};box-shadow:0 0 4px ${dColor}"></div>
      <span style="font-size:8px;color:#8da8c5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:55px">${it.name.split('-')[0]}</span>
    </div>
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
      offset: new AMap.Pixel(-30, -35),
      zIndex: 100,
    })
    marker.setMap(map)
    marker.on('click', () => onSelect(it.id))

    markers.push({
      id: it.id, marker,
      update: (updatedIt: Intersection) => marker.setContent(buildContent(updatedIt)),
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
    dispose() { markers.forEach((m) => m.remove()) },
  }
}
