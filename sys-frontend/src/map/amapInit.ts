// ================================================================
// amapInit.ts — 高德 JS API v2 地图初始化
// ================================================================
import AMapLoader from '@amap/amap-jsapi-loader'

const AMAP_KEY = import.meta.env.VITE_AMAP_KEY as string

/** 上海中心（12 个路口几何中心） */
const CENTER: [number, number] = [121.4644, 31.2240]

export interface AMapInstance {
  map: AMap.Map
  destroy: () => void
}

declare global { var _AMapSecurityConfig: { securityJsCode?: string } | undefined }

/**
 * 加载并初始化高德地图。失败时回调 onError（触发降级到 RoadNetwork.vue）。
 */
export async function initAMap(
  container: HTMLElement,
  onError: () => void,
): Promise<AMapInstance> {
  try {
    // 安全密钥（高德 2.0 需要）
    window._AMapSecurityConfig = { securityJsCode: AMAP_KEY }
    const AMap = await AMapLoader.load({ key: AMAP_KEY, version: '2.0' })

    const map = new AMap.Map(container, {
      center: CENTER,
      zoom: 14,
      pitch: 45,
      viewMode: '3D',
      mapStyle: 'amap://styles/dark',
      resizeEnable: true,
    })

    return {
      map: map as AMap.Map,
      destroy: () => map.destroy(),
    }
  } catch {
    onError()
    throw new Error('AMap init failed')
  }
}
