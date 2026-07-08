// ================================================================
// initMap.ts — MapLibre GL JS 地图初始化
// ================================================================
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { MAP_CENTER, DEFAULT_ZOOM, DARK_STYLE_URL, FALLBACK_STYLE_URL } from './mapConfig'

export interface MapInstance {
  map: maplibregl.Map
  destroy: () => void
}

/**
 * 初始化 MapLibre 地图。
 * @param container DOM 容器
 * @param onZoomChange 缩放级别变化回调（通常写入 store.mapZoom）
 * @param onError 加载失败回调（触发降级）
 */
export function initMap(
  container: HTMLElement,
  onZoomChange: (zoom: number) => void,
  onError: () => void,
): MapInstance {
  const map = new maplibregl.Map({
    container,
    style: DARK_STYLE_URL,
    center: MAP_CENTER,
    zoom: DEFAULT_ZOOM,
    pitch: 45,
    bearing: 0,
    maxZoom: 19,
    minZoom: 3,
  })

  // 缩放同步
  map.on('zoom', () => onZoomChange(map.getZoom()))

  // 降级处理
  map.on('style.error', () => {
    // 尝试备用样式
    try {
      map.setStyle(FALLBACK_STYLE_URL)
    } catch {
      onError()
    }
  })

  // 如果主样式 8 秒内未加载完成，触发降级
  const timeout = setTimeout(() => {
    // 检查地图是否已经加载完成
    try {
      if (!map.isStyleLoaded()) {
        onError()
      }
    } catch {
      onError()
    }
  }, 8000)

  return {
    map,
    destroy: () => {
      clearTimeout(timeout)
      map.remove()
    },
  }
}
