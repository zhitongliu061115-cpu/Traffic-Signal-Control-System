// ================================================================
// IntersectionNodeManager — Three.js 路口节点（CustomLayer 内）
// 发光圆盘 + 信号灯环 + 设备状态球 + raycaster 命中体
// ================================================================
import * as THREE from 'three'
import type { Intersection } from '@/types/traffic'
import { signalStatus, signalColorHex } from '@/map/signalDerive'
import { toMercator } from '@/map/CoordinateHelper'
import { THEME } from './config'

interface NodeEntry {
  id: string
  group: THREE.Group
  hitMesh: THREE.Mesh
  ringMat: THREE.MeshBasicMaterial
  dotMat: THREE.MeshBasicMaterial
}

export class IntersectionNodeManager {
  readonly group = new THREE.Group()
  private entries = new Map<string, NodeEntry>()

  get raycastTargets(): THREE.Object3D[] {
    return Array.from(this.entries.values()).map((e) => e.hitMesh)
  }

  resolveId(obj: THREE.Object3D): string | null {
    for (const e of this.entries.values()) {
      if (e.hitMesh === obj) return e.id
    }
    return null
  }

  build(intersections: Intersection[]): void {
    for (const it of intersections) {
      const m = toMercator(it.lng, it.lat, 0)
      const g = new THREE.Group()
      g.position.set(m.x, m.y, m.z)

      // 发光核心圆盘
      const coreMat = new THREE.MeshStandardMaterial({
        color: new THREE.Color(THEME.nodeNormal),
        emissive: new THREE.Color(THEME.nodeNormal),
        emissiveIntensity: 0.8,
        metalness: 0.3,
        roughness: 0.4,
      })
      const core = new THREE.Mesh(new THREE.CircleGeometry(4, 40), coreMat)
      core.rotation.x = -Math.PI / 2
      core.position.y = 0.1
      g.add(core)

      // 信号灯环
      const ringHex = signalColorHex(signalStatus(it))
      const ringMat = new THREE.MeshBasicMaterial({
        color: new THREE.Color(ringHex),
        side: THREE.DoubleSide,
        transparent: true,
        opacity: 0.9,
      })
      const ring = new THREE.Mesh(new THREE.RingGeometry(3.0, 4.4, 48), ringMat)
      ring.rotation.x = -Math.PI / 2
      ring.position.y = 0.2
      g.add(ring)

      // 设备状态球
      const dHex =
        it.deviceStatus === 'online' ? THEME.deviceOnline :
        it.deviceStatus === 'fault' ? THEME.deviceFault :
        THEME.deviceOffline
      const dotMat = new THREE.MeshBasicMaterial({ color: new THREE.Color(dHex) })
      const dot = new THREE.Mesh(new THREE.SphereGeometry(0.7, 12, 12), dotMat)
      dot.position.set(2.8, 2.5, -2.8)
      g.add(dot)

      // 不可见命中体（raycaster）
      const hit = new THREE.Mesh(
        new THREE.CircleGeometry(3.5, 20),
        new THREE.MeshBasicMaterial({ visible: false }),
      )
      hit.rotation.x = -Math.PI / 2
      hit.position.y = 0.3
      hit.userData = { intersectionId: it.id }
      g.add(hit)

      this.group.add(g)
      this.entries.set(it.id, { id: it.id, group: g, hitMesh: hit, ringMat, dotMat })
    }
  }

  update(intersections: Intersection[], selectedId: string | null, greenWaveIds: Set<string>): void {
    for (const it of intersections) {
      const e = this.entries.get(it.id)
      if (!e) continue

      // 信号环颜色
      const sigHex = greenWaveIds.has(it.id) ? THEME.signalGreen : signalColorHex(signalStatus(it))
      e.ringMat.color.set(sigHex)

      // 设备点颜色
      const dHex =
        it.deviceStatus === 'online' ? THEME.deviceOnline :
        it.deviceStatus === 'fault' ? THEME.deviceFault :
        THEME.deviceOffline
      e.dotMat.color.set(dHex)
    }
  }

  animate(deltaMs: number, intersections: Intersection[]): void {
    const t = deltaMs * 0.001
    const blink = Math.sin(t * 7) > 0 ? 1 : 0.25
    for (const it of intersections) {
      if (it.deviceStatus !== 'fault') continue
      const e = this.entries.get(it.id)
      if (e) e.dotMat.opacity = blink
    }
  }

  dispose(): void {
    this.entries.forEach((e) => {
      e.group.traverse((o) => {
        const m = o as THREE.Mesh
        if (m.geometry) m.geometry.dispose()
        const mat = m.material
        if (mat) {
          if (Array.isArray(mat)) mat.forEach((x) => x.dispose())
          else mat.dispose()
        }
      })
    })
    this.entries.clear()
  }
}
