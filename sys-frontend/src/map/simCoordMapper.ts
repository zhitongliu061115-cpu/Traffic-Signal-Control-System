// ================================================================
// simCoordMapper — CityFlow 仿真坐标 → 高德经纬度 仿射映射
// CityFlow 网格与上海路网转置：intersection_R_C 中 R=col, C=row
// 用 12 个配对路口做逐轴线性最小二乘拟合，自动处理轴翻转
// ================================================================
import type { Intersection, SimRoadnetResponse } from '@/types/traffic'

export interface SimCoordMapper {
  toLngLat: (x: number, y: number) => [number, number]
}

/** 单变量线性最小二乘：返回 { a, b } 使 v ≈ a*u + b */
function linfit(pairs: [number, number][]): { a: number; b: number } {
  const n = pairs.length
  if (n === 0) return { a: 0, b: 0 }
  let su = 0, sv = 0, suu = 0, suv = 0
  for (const [u, v] of pairs) {
    su += u; sv += v; suu += u * u; suv += u * v
  }
  const denom = n * suu - su * su
  if (Math.abs(denom) < 1e-9) return { a: 0, b: sv / n }
  const a = (n * suv - su * sv) / denom
  const b = (sv - a * su) / n
  return { a, b }
}

/**
 * 构建映射器。
 * @param simRoadnet CityFlow 静态路网（含真实路口 x/y）
 * @param intersections 上海路口（含 lng/lat + row/col）
 */
export function buildSimCoordMapper(
  simRoadnet: SimRoadnetResponse,
  intersections: Intersection[],
): SimCoordMapper | null {
  // 上海路口按 "col_row" 建索引（= CityFlow 的 R_C）
  const shByKey = new Map<string, Intersection>()
  for (const it of intersections) shByKey.set(`${it.col}_${it.row}`, it)

  // 收集配对点：CityFlow (x,y) ↔ 上海 (lng,lat)
  const xLng: [number, number][] = []
  const yLat: [number, number][] = []
  for (const si of simRoadnet.intersections) {
    if (si.virtual) continue
    const m = si.id.match(/^intersection_(\d+)_(\d+)$/)
    if (!m) continue
    const sh = shByKey.get(`${m[1]}_${m[2]}`)
    if (!sh) continue
    xLng.push([si.x, sh.lng])
    yLat.push([si.y, sh.lat])
  }

  if (xLng.length < 2) return null

  const fx = linfit(xLng) // lng ≈ fx.a * x + fx.b
  const fy = linfit(yLat) // lat ≈ fy.a * y + fy.b

  return {
    toLngLat(x: number, y: number): [number, number] {
      return [fx.a * x + fx.b, fy.a * y + fy.b]
    },
  }
}
