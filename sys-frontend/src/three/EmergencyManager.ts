// ================================================================
// EmergencyManager — 应急路线计算（路段高亮集合 + 绿波路口集合）
// 纯逻辑管理器：根据 store 状态推导应急道路/路口 id，供 Road/Intersection 渲染
// ================================================================
import type { Road } from '@/types/traffic'

export class EmergencyManager {
  private roads: Road[] = []

  setRoads(roads: Road[]): void {
    this.roads = roads
  }

  /**
   * 根据应急路线节点序列推导需要高亮的道路 id。
   * 匹配 from→to 或 to→from（双向）。
   */
  emergencyRoadIds(route: string[], active: boolean): Set<string> {
    const ids = new Set<string>()
    if (!active || route.length < 2) return ids

    for (let i = 0; i < route.length - 1; i++) {
      const a = route[i]
      const b = route[i + 1]
      const road = this.roads.find(
        (r) => (r.from === a && r.to === b) || (r.from === b && r.to === a),
      )
      if (road) ids.add(road.id)
    }
    return ids
  }

  /**
   * 根据 activeGreenWaveIndex 推导已放行（变绿）的路口集合。
   * 索引 <= activeGreenWaveIndex 的路线节点依次变绿。
   */
  greenWaveIds(route: string[], activeIndex: number, active: boolean): Set<string> {
    const ids = new Set<string>()
    if (!active || activeIndex < 0) return ids
    route.forEach((id, i) => {
      if (i <= activeIndex) ids.add(id)
    })
    return ids
  }
}
