// ================================================================
// lodController.ts — LOD 缩放规则控制器
// ================================================================
import type maplibregl from 'maplibre-gl'

export type LODLevel = 'LOD1' | 'LOD2' | 'LOD3'

/** 根据 zoom 返回当前 LOD */
export function lodLevel(zoom: number): LODLevel {
  if (zoom >= 16) return 'LOD3'
  if (zoom >= 13) return 'LOD2'
  return 'LOD1'
}

/**
 * 监听 map zoom 变化，按 LOD 阈值控制 Three 细节层的挂载/卸载回调。
 * @returns 当前 LOD 的清理函数工厂
 */
export function createLODController(
  map: maplibregl.Map,
  onEnterLOD3: () => void,
  onExitLOD3: () => void,
): { currentLevel: () => LODLevel; dispose: () => void } {
  let prevLevel: LODLevel = lodLevel(map.getZoom())

  const handler = () => {
    const cur = lodLevel(map.getZoom())
    if (cur === 'LOD3' && prevLevel !== 'LOD3') {
      onEnterLOD3()
    } else if (cur !== 'LOD3' && prevLevel === 'LOD3') {
      onExitLOD3()
    }
    prevLevel = cur
  }

  map.on('zoom', handler)
  handler() // 初始检查

  return {
    currentLevel: () => lodLevel(map.getZoom()),
    dispose: () => map.off('zoom', handler),
  }
}
