// ================================================================
// amapPathGen.ts — 通过高德路径规划 API 生成贴合真实道路的 path
// mock 阶段用，正式环境由后端提供道路 LineString
// ================================================================

/** 高德 Web 服务 API Key（用于路径规划等 REST API，可能需要跟 JS API Key 不同） */
const AMAP_WEB_KEY = (import.meta.env.VITE_AMAP_WEB_KEY as string) || (import.meta.env.VITE_AMAP_KEY as string)

/** 缓存：避免重复请求同一对起点/终点 */
const pathCache = new Map<string, [number, number][]>()

function cacheKey(from: [number, number], to: [number, number]): string {
  return `${from[0].toFixed(6)},${from[1].toFixed(6)}-${to[0].toFixed(6)},${to[1].toFixed(6)}`
}

/**
 * 调用高德驾车路径规划 API，返回沿真实道路的折线坐标。
 * 成功返回 path，失败返回 null（调用方降级用手写 mock 数据）
 */
/** 请求间延迟（毫秒），避免 QPS 限流。AMap 免费额度约 1 QPS */
const REQUEST_DELAY_MS = 1200

let lastRequestTime = 0

async function throttle(): Promise<void> {
  const now = Date.now()
  const wait = Math.max(0, REQUEST_DELAY_MS - (now - lastRequestTime))
  if (wait > 0) await new Promise((r) => setTimeout(r, wait))
  lastRequestTime = Date.now()
}

export async function fetchDrivingPath(
  origin: [number, number],
  destination: [number, number],
): Promise<[number, number][] | null> {
  const ck = cacheKey(origin, destination)
  if (pathCache.has(ck)) return pathCache.get(ck)!

  await throttle()

  try {
    const url =
      `https://restapi.amap.com/v3/direction/driving` +
      `?origin=${origin[0]},${origin[1]}` +
      `&destination=${destination[0]},${destination[1]}` +
      `&extensions=all&key=${AMAP_WEB_KEY}`

    const res = await fetch(url)
    const data = await res.json()

    if (data.status !== '1' || !data.route?.paths?.[0]?.steps) {
      console.warn('[AMapPath] 路径规划失败:', data.info || data)
      return null
    }

    const path: [number, number][] = []
    for (const step of data.route.paths[0].steps) {
      if (!step.polyline) continue
      const pts = step.polyline.split(';').map((p: string) => {
        const [lng, lat] = p.split(',').map(Number)
        return [lng, lat] as [number, number]
      })
      path.push(...pts)
    }

    // 校验：过滤 NaN 和异常坐标
    const valid = path.filter(([lng, lat]) => isFinite(lng) && isFinite(lat) && Math.abs(lng) > 1 && Math.abs(lat) > 1)
    if (valid.length < 2) {
      console.warn('[AMapPath] 返回路径无效')
      return null
    }

    pathCache.set(ck, valid)
    console.log(`[AMapPath] ${origin} → ${destination}: ${valid.length} pts`)
    return valid
  } catch (err) {
    console.warn('[AMapPath] 请求失败:', err)
    return null
  }
}
