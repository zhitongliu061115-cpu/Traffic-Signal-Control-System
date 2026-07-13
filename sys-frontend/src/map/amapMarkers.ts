// ================================================================
// amapMarkers.ts — 高德红绿灯标记
// 地图上显示小 🚥 图标，hover 弹出详情卡片（大屏 cyber 风格）
// ================================================================
import type { Intersection, SignalPhase } from '@/types/traffic'

export interface TLMarker {
  id: string
  marker: AMap.Marker
  update: (it: Intersection) => void
  remove: () => void
}

// ---- 相位 → 中文名 ----
const PHASE_NAME: Record<string, string> = {
  eastwest_straight: '东西直行',
  eastwest_left: '东西左转',
  northsouth_straight: '南北直行',
  northsouth_left: '南北左转',
  all_red: '全向红灯',
}

interface DirFlag { e: boolean; w: boolean; n: boolean; s: boolean; sub: string }
function dirFlags(phase: SignalPhase): DirFlag {
  if (phase === 'all_red') return { e: false, w: false, n: false, s: false, sub: '' }
  const ew = phase.startsWith('eastwest')
  const sub = phase.endsWith('straight') ? '直' : phase.endsWith('left') ? '左' : ''
  return { e: ew, w: ew, n: !ew, s: !ew, sub }
}

// ---- 共享悬浮卡片（全局单例，所有路口复用） ----
let popupEl: HTMLDivElement | null = null
let popupVisible = false

function ensurePopup(): HTMLDivElement {
  if (popupEl) return popupEl
  popupEl = document.createElement('div')
  popupEl.className = 'tl-popup'
  popupEl.style.cssText =
    'position:absolute;z-index:999;pointer-events:none;display:none;' +
    'font-family:Rajdhani,\'PingFang SC\',sans-serif'
  document.body.appendChild(popupEl)
  return popupEl
}

function buildPopup(it: Intersection): string {
  const phase = it.currentPhase as SignalPhase
  const online = it.deviceStatus === 'online'
  const allRed = phase === 'all_red' || !online
  const rem = Math.round(it.greenRemain)

  // 倒计时颜色
  let cdColor = '#e8f4ff', cdShadow = '0 0 6px rgba(232,244,255,0.2)', cdAnim = ''
  if (!online) { cdColor = '#5a7595'; cdShadow = 'none' }
  else if (rem <= 3) { cdColor = '#ff4d6d'; cdShadow = '0 0 6px rgba(255,77,109,0.35)'; cdAnim = 'animation:tl-blink .5s step-end infinite alternate' }
  else if (rem <= 8) { cdColor = '#f5a623'; cdShadow = '0 0 6px rgba(245,166,35,0.35)' }

  function dotStyle(active: boolean): string {
    if (!online) return 'background:#2a3540;box-shadow:none;color:rgba(255,255,255,.12)'
    if (allRed) return 'background:#3a1020;box-shadow:0 0 4px rgba(255,77,109,0.3);color:rgba(255,255,255,.25)'
    return active
      ? 'background:#22d3a0;box-shadow:0 0 7px rgba(34,211,160,0.65),inset 0 1px 2px rgba(255,255,255,.25)'
      : 'background:#3a1020;box-shadow:0 0 4px rgba(255,77,109,0.3);color:rgba(255,255,255,.25)'
  }
  function light(active: boolean, arrow: string, label: string): string {
    const ds = dotStyle(active)
    return `<div style="display:flex;align-items:center;gap:4px;font-size:9px;color:#8da8c5"><span style="width:13px;height:13px;border-radius:50%;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:7px;font-weight:700;color:#fff;${ds}">${arrow}</span><span>${label}</span></div>`
  }

  const flags = dirFlags(phase)
  const stColor = online ? '#22d3a0' : it.deviceStatus === 'fault' ? '#ff4d6d' : '#5a7595'
  const stShadow = online ? '0 0 4px #22d3a0' : it.deviceStatus === 'fault' ? '0 0 4px #ff4d6d' : 'none'

  return `<div style="width:150px;background:linear-gradient(135deg,rgba(122,247,255,0.1) 0 1px,transparent 1px 42%),radial-gradient(circle at 18% 0%,rgba(0,212,255,0.16),transparent 34%),radial-gradient(circle at 100% 100%,rgba(58,143,255,0.12),transparent 38%),linear-gradient(180deg,rgba(10,37,64,0.54),rgba(2,8,23,0.7));clip-path:polygon(6px 0,calc(100% - 6px) 0,100% 6px,100% calc(100% - 6px),calc(100% - 6px) 100%,6px 100%,0 calc(100% - 6px),0 6px);display:flex;flex-direction:column;box-shadow:inset 0 0 16px rgba(0,212,255,0.06),inset 0 1px 0 rgba(221,251,255,0.08),0 0 20px rgba(0,212,255,0.16);font-family:Rajdhani,'DINPro','PingFang SC',monospace;overflow:hidden">
    <div style="display:flex;align-items:center;gap:8px;padding:4px 8px;border-bottom:1px solid rgba(0,212,255,0.15)">
      <div style="width:6px;height:20px;transform:skewX(-18deg);background:linear-gradient(180deg,#7af7ff,#00d4ff 48%,#034d7a);box-shadow:0 0 10px rgba(0,212,255,0.7);flex-shrink:0"></div>
      <span style="font-size:10px;font-weight:900;color:#7af7ff;letter-spacing:.03em;text-shadow:0 0 8px rgba(0,212,255,0.4)">${PHASE_NAME[phase] ?? phase}</span>
      <div style="margin-left:auto;display:flex;gap:3px;padding-right:3px"><i style="display:block;width:4px;height:8px;transform:skewX(-22deg);background:rgba(122,247,255,0.7)"></i><i style="display:block;width:4px;height:8px;transform:skewX(-22deg);background:rgba(0,212,255,0.45)"></i><i style="display:block;width:4px;height:8px;transform:skewX(-22deg);background:rgba(0,136,179,0.25)"></i></div>
    </div>
    <div style="display:flex;flex-direction:column;align-items:center;gap:3px;padding:5px 8px 6px">
    <span style="font-size:18px;font-weight:700;line-height:1;color:${cdColor};text-shadow:${cdShadow};${cdAnim}">${online ? rem : '--'}</span>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:1px 6px;width:100%">
      ${light(flags.e, '→', flags.sub ? '东' + flags.sub : '东')}
      ${light(flags.w, '←', flags.sub ? '西' + flags.sub : '西')}
      ${light(flags.s, '↓', flags.sub ? '南' + flags.sub : '南')}
      ${light(flags.n, '↑', flags.sub ? '北' + flags.sub : '北')}
    </div>
    </div>
    <div style="display:flex;align-items:center;gap:4px;padding:4px 8px;border-top:1px solid rgba(0,212,255,0.10);font-size:8px;color:#5a7595;white-space:nowrap;overflow:hidden;text-overflow:ellipsis"><span style="width:4px;height:4px;border-radius:50%;flex-shrink:0;background:${stColor};box-shadow:${stShadow}"></span>${it.name.split('-')[0]}</div>
  </div>`
}

// ---- 图标 marker 内容 ----
function buildIcon(it: Intersection): string {
  const online = it.deviceStatus === 'online'
  const allRed = it.currentPhase === 'all_red'
  const glow = online && !allRed ? '#22d3a0' : online ? '#ff4d6d' : '#5a7595'
  return `<div style="
    width:22px;height:22px;border-radius:50%;
    background:rgba(5,19,35,0.96);border:2px solid ${glow};
    box-shadow:0 0 8px ${glow};
    display:flex;align-items:center;justify-content:center;
    font-size:13px;cursor:pointer;
  ">🚥</div>`
}

// ---- 全局注入 @keyframes（内联 style 不支持动画，需独立 style 标签） ----
let styleInjected = false
function injectStyles(): void {
  if (styleInjected) return
  styleInjected = true
  const el = document.createElement('style')
  el.textContent = '@keyframes tl-blink{50%{opacity:.2}}'
  document.head.appendChild(el)
}

// ================================================================
export function createTLMarkers(
  map: AMap.Map,
  intersections: Intersection[],
  onSelect: (id: string) => void,
): { markers: TLMarker[]; updateAll: (its: Intersection[]) => void; dispose: () => void } {
  injectStyles()
  ensurePopup()
  const markers: TLMarker[] = []
  const markerLookup = new Map<string, TLMarker>()
  let hoveredId: string | null = null
  let mouseX = 0, mouseY = 0

  function showPopup(it: Intersection): void {
    const el = popupEl!
    el.innerHTML = buildPopup(it)
    el.style.display = 'block'
    el.style.left = (mouseX + 16) + 'px'
    el.style.top = (mouseY - 10) + 'px'
    popupVisible = true
  }
  function movePopup(): void {
    if (!popupVisible || !popupEl) return
    popupEl.style.left = (mouseX + 16) + 'px'
    popupEl.style.top = (mouseY - 10) + 'px'
  }
  function hidePopup(): void {
    if (popupEl) { popupEl.style.display = 'none'; popupEl.innerHTML = '' }
    popupVisible = false
  }

  // 全局鼠标跟踪
  document.addEventListener('mousemove', (e) => { mouseX = e.clientX; mouseY = e.clientY; if (popupVisible) movePopup() })

  // 存储每个 marker 对应的最新路口数据引用
  const latestData = new Map<string, Intersection>()

  for (const it of intersections) {
    latestData.set(it.id, it)
    const content = buildIcon(it)
    const marker = new AMap.Marker({
      position: [it.lng, it.lat],
      content,
      offset: new AMap.Pixel(-11, -11),
      zIndex: 100,
    })
    marker.setMap(map)

    marker.on('mouseover', () => {
      hoveredId = it.id
      const data = latestData.get(it.id)
      if (data) showPopup(data)
    })
    marker.on('mouseout', () => { hoveredId = null; hidePopup() })
    marker.on('click', () => onSelect(it.id))

    let lastIconState = `${it.currentPhase}|${it.deviceStatus}`
    const tlMarker: TLMarker = {
      id: it.id, marker,
      update: (updatedIt: Intersection) => {
        latestData.set(updatedIt.id, updatedIt)
        const iconState = `${updatedIt.currentPhase}|${updatedIt.deviceStatus}`
        if (iconState !== lastIconState) {
          lastIconState = iconState
          marker.setContent(buildIcon(updatedIt))
        }
        if (hoveredId === updatedIt.id && popupVisible) {
          showPopup(updatedIt)
        }
      },
      remove: () => { marker.setMap(null); latestData.delete(it.id) },
    }
    markers.push(tlMarker)
    markerLookup.set(it.id, tlMarker)
  }

  return {
    markers,
    updateAll(its: Intersection[]) {
      for (const it of its) {
        const m = markerLookup.get(it.id)
        if (m) m.update(it)
      }
    },
    dispose() {
      markers.forEach((m) => m.remove())
      hidePopup()
    },
  }
}
