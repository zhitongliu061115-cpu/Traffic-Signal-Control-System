// ================================================================
// mapConfig.ts — 地图全局配置
// ================================================================

/** 城市中心点（12 个真实路口几何中心：人民广场-淮海路-静安核心区） */
export const MAP_CENTER: [number, number] = [121.4644, 31.2240]

/** 默认缩放 */
export const DEFAULT_ZOOM = 13

/** 深色底图样式 URL（CartoDB dark matter，免 token） */
export const DARK_STYLE_URL =
  'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json'

/** 备选样式（同样免 token，供降级） */
export const FALLBACK_STYLE_URL =
  'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json'

/** 是否离线 */
export function isOffline(): boolean {
  return !navigator.onLine
}
