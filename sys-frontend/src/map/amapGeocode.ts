// ================================================================
// amapGeocode.ts — 高德 POI 搜索，获取路口精确坐标
// ================================================================

const AMAP_WEB_KEY = '4ab584658b1cdc916345e3c20bc15add'

/** 请求间延迟（AMap POI API 也有 QPS 限制） */
const DELAY_MS = 300

let lastTime = 0
async function throttle(): Promise<void> {
  const now = Date.now()
  const wait = Math.max(0, DELAY_MS - (now - lastTime))
  if (wait > 0) await new Promise((r) => setTimeout(r, wait))
  lastTime = Date.now()
}

export interface GeoResult {
  name: string
  lng: number
  lat: number
  address: string
}

/**
 * 用关键字搜索路口，返回第一个结果的坐标。
 * 关键词格式：路口名 + "路口"（如 "西藏中路南京东路路口"）
 */
export async function searchIntersection(keyword: string, city = '上海'): Promise<GeoResult | null> {
  await throttle()
  try {
    const url =
      `https://restapi.amap.com/v3/place/text` +
      `?keywords=${encodeURIComponent(keyword)}` +
      `&city=${encodeURIComponent(city)}` +
      `&key=${AMAP_WEB_KEY}`

    const res = await fetch(url)
    const data = await res.json()

    if (data.status !== '1' || !data.pois?.length) {
      console.warn(`[Geo] ${keyword}:`, data.info || '无结果')
      return null
    }

    const poi = data.pois[0]
    const [lng, lat] = poi.location.split(',').map(Number)
    console.log(`[Geo] ${keyword} → ${poi.name} (${lng},${lat})`)
    return { name: poi.name, lng, lat, address: poi.address || '' }
  } catch (err) {
    console.warn(`[Geo] ${keyword} 请求失败:`, err)
    return null
  }
}
