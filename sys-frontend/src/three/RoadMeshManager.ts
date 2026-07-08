// ================================================================
// RoadMeshManager — Three.js 多段道路 Mesh（CustomLayer 内）
// 使用 path 字段中的真实中心线经纬度，分段生成 BoxGeometry，
// 每段贴合道路弯曲走向，与 MapLibre 底图道路对齐
// ================================================================
import * as THREE from 'three'
import type { Intersection, Road } from '@/types/traffic'
import { getRoadPathTransform, clearRoadCache } from '@/map/CoordinateHelper'
import { congestionColorHex, THEME } from './config'

const LANE_WIDTH = 0.00006

interface RoadMeshEntry {
  id: string
  group: THREE.Group
  material: THREE.MeshStandardMaterial
}

export class RoadMeshManager {
  readonly group = new THREE.Group()
  private entries = new Map<string, RoadMeshEntry>()

  /** 全量构建所有道路 Mesh（多段，沿真实中心线） */
  build(intersections: Intersection[], roads: Road[]): void {
    clearRoadCache()
    for (const r of roads) {
      const pt = getRoadPathTransform(r, intersections)
      if (!pt || pt.segments.length === 0) continue

      const hex = congestionColorHex(r.congestionIndex)
      const mat = new THREE.MeshStandardMaterial({
        color: new THREE.Color(hex),
        emissive: new THREE.Color(hex),
        emissiveIntensity: 0.9,
        metalness: 0.15,
        roughness: 0.55,
      })

      const roadGroup = new THREE.Group()

      for (const seg of pt.segments) {
        const width = r.laneCount * LANE_WIDTH
        const geom = new THREE.BoxGeometry(width, 0.8, seg.length)
        const mesh = new THREE.Mesh(geom, mat)
        mesh.position.copy(seg.mid)
        mesh.rotation.order = 'YXZ'
        mesh.rotation.z = -seg.angle
        mesh.userData = { roadId: r.id }
        roadGroup.add(mesh)
      }

      this.group.add(roadGroup)
      this.entries.set(r.id, { id: r.id, group: roadGroup, material: mat })
    }
  }

  /** 动态更新道路颜色（拥堵变化、应急路线激活） */
  update(roads: Road[], emergencyRoadIds: Set<string> | null): void {
    for (const r of roads) {
      const e = this.entries.get(r.id)
      if (!e) continue

      if (emergencyRoadIds?.has(r.id)) {
        e.material.color.set(THEME.emergency)
        e.material.emissive.set(THEME.emergency)
        e.material.emissiveIntensity = 1.4
      } else {
        const hex = congestionColorHex(r.congestionIndex)
        e.material.color.set(hex)
        e.material.emissive.set(hex)
        e.material.emissiveIntensity = 0.9
      }
    }
  }

  dispose(): void {
    this.entries.forEach((e) => {
      e.group.traverse((o) => {
        const m = o as THREE.Mesh
        if (m.geometry) m.geometry.dispose()
      })
      e.material.dispose()
    })
    this.entries.clear()
    while (this.group.children.length) this.group.remove(this.group.children[0]!)
  }
}
