// ================================================================
// amapInit.ts — 高德 JS API v2 地图初始化
// ================================================================
import AMapLoader from '@amap/amap-jsapi-loader'

const AMAP_KEY = '177a0670648f7f7b711e935b0b4bddbd'
const LOAD_TIMEOUT_MS = 12000 // 12 秒超时

const CENTER: [number, number] = [108.948, 34.260]

export interface AMapInstance {
  map: AMap.Map
  destroy: () => void
}

export async function initAMap(
  container: HTMLElement,
  onError: () => void,
): Promise<AMapInstance> {
  if (!AMAP_KEY || AMAP_KEY.startsWith('your_')) {
    console.warn('[AMap] 缺少有效 Key，请检查 .env 中的 VITE_AMAP_KEY')
    onError()
    throw new Error('Missing AMap key')
  }

  try {
    const AMap = await Promise.race([
      AMapLoader.load({ key: AMAP_KEY, version: '2.0' }),
      new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('AMap CDN timeout')), LOAD_TIMEOUT_MS),
      ),
    ])

    console.log('[AMap] container size:', container.clientWidth, 'x', container.clientHeight)
    const map = new AMap.Map(container, {
      center: CENTER,
      zoom: 14,
      pitch: 45,
      viewMode: '3D',
      mapStyle: 'amap://styles/dark',
      resizeEnable: true,
      doubleClickZoom: false,
    })

    console.log('[AMap] 地图初始化成功')
    return { map: map as AMap.Map, destroy: () => map.destroy() }
  } catch (err) {
    console.error('[AMap] 初始化失败:', err)
    onError()
    throw err
  }
}
