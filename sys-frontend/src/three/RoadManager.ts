// ================================================================
// RoadManager — 三维道路（BoxGeometry）+ 拥堵热力 emissive + 标签
// ================================================================
import {
  Group,
  Mesh,
  BoxGeometry,
  MeshStandardMaterial,
  Vector3,
  Color,
} from 'three'
import type { Intersection, Road } from '@/types/traffic'
import { WORLD, toWorldX, toWorldZ, congestionColorHex, cachedColor, THEME } from './config'
import { LabelManager, type TextLabel } from './LabelManager'

interface RoadSegment {
  id: string
  mesh: Mesh
  material: MeshStandardMaterial
  label: TextLabel
  start: Vector3
  end: Vector3
  isEmergency: boolean
}

export class RoadManager {
  readonly group = new Group()
  private segments = new Map<string, RoadSegment>()
  private labels: LabelManager
  private posCache = new Map<string, Vector3>()

  constructor(labels: LabelManager) {
    this.labels = labels
  }

  /** 道路起点/终点世界坐标（供车辆定位） */
  endpointsOf(roadId: string): { start: Vector3; end: Vector3 } | null {
    const seg = this.segments.get(roadId)
    return seg ? { start: seg.start, end: seg.end } : null
  }

  build(intersections: Intersection[], roads: Road[]): void {
    for (const it of intersections) {
      this.posCache.set(it.id, new Vector3(toWorldX(it.x), 0, toWorldZ(it.y)))
    }

    for (const r of roads) {
      const start = this.posCache.get(r.from)
      const end = this.posCache.get(r.to)
      if (!start || !end) continue
      this.segments.set(r.id, this.createSegment(r, start, end))
    }
  }

  private createSegment(r: Road, start: Vector3, end: Vector3): RoadSegment {
    const length = start.distanceTo(end)
    const geom = new BoxGeometry(length, WORLD.ROAD_HEIGHT, WORLD.ROAD_WIDTH)

    const hex = congestionColorHex(r.congestionIndex)
    const material = new MeshStandardMaterial({
      color: new Color(hex),
      emissive: new Color(hex),
      emissiveIntensity: 0.5,
      metalness: 0.2,
      roughness: 0.6,
    })

    const mesh = new Mesh(geom, material)
    // 位于两点中心
    const mid = start.clone().add(end).multiplyScalar(0.5)
    mesh.position.set(mid.x, WORLD.ROAD_HEIGHT / 2, mid.z)
    // 朝向：绕 Y 轴旋转对齐 start→end
    const angle = Math.atan2(end.z - start.z, end.x - start.x)
    mesh.rotation.y = -angle

    this.group.add(mesh)

    // 道路标签（名称 + 拥堵指数）
    const label = this.labels.create(`${r.name} ${Math.round(r.congestionIndex)}`, {
      fontSize: 26,
      color: '#8da8c5',
      scale: 0.7,
    })
    label.sprite.position.set(mid.x, 16, mid.z)
    this.group.add(label.sprite)

    return { id: r.id, mesh, material, label, start, end, isEmergency: false }
  }

  /** 动态更新道路拥堵颜色与标签 */
  update(roads: Road[], emergencyRoadIds: Set<string>): void {
    for (const r of roads) {
      const seg = this.segments.get(r.id)
      if (!seg) continue

      const isEmergency = emergencyRoadIds.has(r.id)
      seg.isEmergency = isEmergency

      if (isEmergency) {
        // 应急路线：青色高亮 + 高 emissive
        seg.material.color.copy(cachedColor(THEME.emergency))
        seg.material.emissive.copy(cachedColor(THEME.emergency))
        seg.material.emissiveIntensity = 1.1
      } else {
        const hex = congestionColorHex(r.congestionIndex)
        seg.material.color.copy(cachedColor(hex))
        seg.material.emissive.copy(cachedColor(hex))
        seg.material.emissiveIntensity = 0.5
      }

      // 仅拥堵路段显示指数，避免刷屏
      seg.label.setText(
        r.congestionIndex >= 60 ? `${r.name} ${Math.round(r.congestionIndex)}` : r.name,
      )
    }
  }

  dispose(): void {
    this.segments.forEach((s) => {
      s.mesh.geometry.dispose()
      s.material.dispose()
    })
    this.segments.clear()
  }
}
