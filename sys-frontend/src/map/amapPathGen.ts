// ================================================================
// amapPathGen.ts — 通过高德路径规划 API 生成贴合真实道路的 path
// mock 阶段用，正式环境由后端提供道路 LineString
// ================================================================

/** 高德 Web 服务 API Key（用于路径规划等 REST API，可能需要跟 JS API Key 不同） */
const AMAP_WEB_KEY = '4ab584658b1cdc916345e3c20bc15add'

/** 缓存：避免重复请求同一对起点/终点 */
const pathCache = new Map<string, [number, number][]>()

function cacheKey(from: [number, number], to: [number, number]): string {
  return `${from[0].toFixed(6)},${from[1].toFixed(6)}-${to[0].toFixed(6)},${to[1].toFixed(6)}`
}

// ================================================================
// 速率限制器 + localStorage 持久化
// ================================================================

/** 请求间隔（毫秒），避免 QPS 限流。并发模式下每条仍间隔 600ms */
const REQUEST_DELAY_MS = 600

let nextAllowedTime = 0

function acquireSlot(): Promise<void> {
  const now = Date.now()
  const wait = Math.max(0, nextAllowedTime - now)
  nextAllowedTime = Math.max(nextAllowedTime, now) + REQUEST_DELAY_MS
  if (wait > 0) return new Promise((r) => setTimeout(r, wait))
  return Promise.resolve()
}

// ---- localStorage 持久化 ----
const CACHE_STORAGE_KEY = 'amap_path_cache'

function loadCacheFromStorage(): void {
  try {
    const raw = localStorage.getItem(CACHE_STORAGE_KEY)
    if (!raw) return
    const data = JSON.parse(raw) as Array<{ key: string; path: [number, number][] }>
    for (const { key, path } of data) {
      if (!pathCache.has(key)) pathCache.set(key, path)
    }
    console.log(`[AMapPath] 从本地缓存恢复 ${data.length} 条路径`)
  } catch { /* ignore */ }
}

function saveCacheToStorage(): void {
  try {
    const data = Array.from(pathCache.entries()).map(([key, path]) => ({ key, path }))
    localStorage.setItem(CACHE_STORAGE_KEY, JSON.stringify(data))
  } catch { /* ignore */ }
}

// 启动时加载缓存
loadCacheFromStorage()

// ================================================================
// 核心路径请求逻辑
// ================================================================

async function requestPath(
  origin: [number, number],
  destination: [number, number],
): Promise<[number, number][] | null> {
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
    const valid = path.filter(
      ([lng, lat]) => isFinite(lng) && isFinite(lat) && Math.abs(lng) > 1 && Math.abs(lat) > 1,
    )
    if (valid.length < 2) {
      console.warn('[AMapPath] 返回路径无效')
      return null
    }

    return valid
  } catch (err) {
    console.warn('[AMapPath] 请求失败:', err)
    return null
  }
}

// ================================================================
// 公开 API
// ================================================================

// ---- 地理编码缓存 ----
const geocodeCache = new Map<string, [number, number] | null>()
const GEO_CACHE_STORAGE_KEY = 'amap_geocode_cache'

function loadGeocodeCache(): void {
  try {
    const raw = localStorage.getItem(GEO_CACHE_STORAGE_KEY)
    if (!raw) return
    const data = JSON.parse(raw) as Array<{ key: string; lng: number; lat: number }>
    for (const { key, lng, lat } of data) {
      if (!geocodeCache.has(key)) geocodeCache.set(key, [lng, lat])
    }
  } catch { /* ignore */ }
}
function saveGeocodeCache(): void {
  try {
    const data = Array.from(geocodeCache.entries())
      .filter(([, v]) => v !== null)
      .map(([key, pt]) => ({ key, lng: pt![0], lat: pt![1] }))
    localStorage.setItem(GEO_CACHE_STORAGE_KEY, JSON.stringify(data))
  } catch { /* ignore */ }
}
loadGeocodeCache()

/**
 * 用高德地理编码 API 将路口名转为精确经纬度。
 * 搜索关键词 = "上海市{路口名}路口"
 */
async function geocodeOne(address: string, city = '上海'): Promise<[number, number] | null> {
  try {
    const url =
      `https://restapi.amap.com/v3/geocode/geo` +
      `?address=${encodeURIComponent(address)}` +
      `&city=${encodeURIComponent(city)}` +
      `&key=${AMAP_WEB_KEY}`

    const res = await fetch(url)
    const data = await res.json()

    if (data.status !== '1' || !data.geocodes?.length) return null

    const [lng, lat] = data.geocodes[0].location.split(',').map(Number) as [number, number]
    if (!isFinite(lng) || !isFinite(lat)) return null

    return [lng, lat]
  } catch {
    return null
  }
}

/**
 * 批量地理编码：把路口名修正为高德认定的真实交叉口坐标。
 * 12 个路口 × 600ms / 3 并发 ≈ 2.4s
 */
export async function snapIntersectionsToAMap(
  items: Array<{ id: string; name: string; lng: number; lat: number }>,
  concurrency = 3,
  options: { city?: string } = {},
): Promise<Map<string, [number, number]>> {
  const results = new Map<string, [number, number]>()
  let idx = 0
  let changed = false
  const city = options.city ?? '上海'

  async function worker() {
    while (idx < items.length) {
      const i = idx++
      const it = items[i]!
      const roadNames = it.name
        .split(/[×xX\-—–]/)
        .map((part) => part.trim())
        .filter(Boolean)
        .join('')
      const address = `${city}市${roadNames || it.name}路口`

      // 缓存命中
      if (geocodeCache.has(address)) {
        const pt = geocodeCache.get(address)
        if (pt) results.set(it.id, pt)
        continue
      }

      await acquireSlot()

      const pt = await geocodeOne(address, city)
      geocodeCache.set(address, pt)
      changed = true
      if (pt) {
        results.set(it.id, pt)
        console.log(`[AMapGeo] ${it.name} → ${pt[0].toFixed(6)}, ${pt[1].toFixed(6)}`)
      } else {
        console.warn(`[AMapGeo] ${it.name} 未找到，保留原坐标`)
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, () => worker()))
  if (changed) saveGeocodeCache()
  return results
}

/**
 * 并发批量路径规划（推荐）
 *
 * 使用 concurrency 个并发 worker + 速率限制器，大幅缩短加载时间。
 * 17 条路 × 600ms / 3 并发 ≈ 3.4s（vs 原来 20.4s）
 *
 * 首次加载后自动缓存到 localStorage，二次打开秒加载。
 */
export async function fetchDrivingPathsBatch(
  pairs: Array<{ origin: [number, number]; destination: [number, number] }>,
  concurrency = 3,
): Promise<Array<[number, number][] | null>> {
  const results: Array<[number, number][] | null> = new Array(pairs.length).fill(null)
  let idx = 0
  let cacheChanged = false

  async function worker() {
    while (idx < pairs.length) {
      const i = idx++
      const pair = pairs[i]!
      const ck = cacheKey(pair.origin, pair.destination)

      // 缓存命中 → 跳过请求
      if (pathCache.has(ck)) {
        results[i] = pathCache.get(ck)!
        continue
      }

      // 获取速率限制槽位
      await acquireSlot()

      const valid = await requestPath(pair.origin, pair.destination)
      if (valid) {
        pathCache.set(ck, valid)
        cacheChanged = true
        results[i] = valid
        console.log(`[AMapPath] ${pair.origin} → ${pair.destination}: ${valid.length} pts`)
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, () => worker()))

  // 批量请求完成后持久化缓存
  if (cacheChanged) saveCacheToStorage()

  return results
}

/**
 * 单条路径规划（保持向后兼容）
 * 内部也使用速率限制器
 */
export async function fetchDrivingPath(
  origin: [number, number],
  destination: [number, number],
): Promise<[number, number][] | null> {
  const ck = cacheKey(origin, destination)
  if (pathCache.has(ck)) return pathCache.get(ck)!

  await acquireSlot()

  const valid = await requestPath(origin, destination)
  if (valid) {
    pathCache.set(ck, valid)
    saveCacheToStorage()
    console.log(`[AMapPath] ${origin} → ${destination}: ${valid.length} pts`)
  }
  return valid
}
