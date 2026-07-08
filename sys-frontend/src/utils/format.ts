// ================================================================
// 格式化工具函数 — 数字、时间、百分比等展示辅助
// 迁移自 buildingEnergy 老项目
// ================================================================

/** 大数格式化：3847 → "3,847" */
export function fmtNum(n: number): string {
  return n.toLocaleString('zh-CN')
}

/** 保留一位小数 */
export function fmtDecimal(n: number, decimals = 1): string {
  return n.toFixed(decimals)
}

/** 百分比格式化：0.984 → "98.4%" */
export function fmtPercent(n: number, decimals = 1): string {
  return `${(n * 100).toFixed(decimals)}%`
}

/** 耗时格式化：32.8 → "32.8s" */
export function fmtDuration(n: number): string {
  if (n >= 60) return `${(n / 60).toFixed(1)}min`
  return `${n.toFixed(1)}s`
}

/** 速度格式化：42.6 → "42.6 km/h" */
export function fmtSpeed(n: number): string {
  return `${n.toFixed(1)} km/h`
}

/** 拥堵指数 → 文字描述 */
export function congestionLabel(ci: number): string {
  if (ci < 30) return '畅通'
  if (ci < 60) return '缓行'
  if (ci < 80) return '拥堵'
  return '严重拥堵'
}

/** 拥堵指数 → 配色 */
export function congestionColor(ci: number): string {
  if (ci < 30) return '#22D3A0'
  if (ci < 60) return '#FFB800'
  if (ci < 80) return '#FF7A45'
  return '#FF4D6D'
}

/** 统计变化方向：+5.8 → "↑ 5.8%", -3.2 → "↓ 3.2%" */
export function fmtChange(delta: number): string {
  const arrow = delta >= 0 ? '↑' : '↓'
  return `${arrow} ${Math.abs(delta).toFixed(1)}%`
}

/** 时间戳格式化：Date → "HH:mm:ss" */
export function fmtTime(d: Date = new Date()): string {
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

/** 日期格式化：Date → "YYYY-MM-DD" */
export function fmtDate(d: Date = new Date()): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 日期+时间格式化 */
export function fmtDateTime(d: Date = new Date()): string {
  return `${fmtDate(d)} ${fmtTime(d)}`
}
