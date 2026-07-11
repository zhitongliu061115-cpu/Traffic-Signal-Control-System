// ================================================================
// VehicleManager — 车辆流动（普通车 InstancedMesh + 应急车特殊标记）
// 预留 InstancedMesh 支持 1000+ 车辆
// ================================================================
import {
  Group,
  InstancedMesh,
  SphereGeometry,
  BoxGeometry,
  MeshStandardMaterial,
  Mesh,
  Object3D,
  Color,
  Vector3,
} from 'three'
import type { Vehicle } from '@/types/traffic'
import { WORLD, THEME } from './config'
import type { RoadManager } from './RoadManager'

export class VehicleManager {
  readonly group = new Group()

  private instanced: InstancedMesh
  private instanceGeom: SphereGeometry
  private instanceMat: MeshStandardMaterial
  private dummy = new Object3D()
  private roads: RoadManager

  // 应急车辆特殊标记
  private emergencyMesh: Mesh
  private emergencyMat: MeshStandardMaterial
  private emergencyGeom: BoxGeometry
  private elapsed = 0

  constructor(roads: RoadManager) {
    this.roads = roads

    // ---- 普通车辆：InstancedMesh ----
    this.instanceGeom = new SphereGeometry(WORLD.VEHICLE_SIZE / 2, 12, 12)
    this.instanceMat = new MeshStandardMaterial({
      color: new Color('#7AF7FF'),
      emissive: new Color('#7AF7FF'),
      emissiveIntensity: 0.6,
      metalness: 0.4,
      roughness: 0.3,
    })
    this.instanced = new InstancedMesh(this.instanceGeom, this.instanceMat, WORLD.MAX_VEHICLES)
    this.instanced.count = 0
    this.group.add(this.instanced)

    // ---- 应急车辆：更大的发光盒 ----
    this.emergencyGeom = new BoxGeometry(WORLD.VEHICLE_SIZE * 1.6, WORLD.VEHICLE_SIZE, WORLD.VEHICLE_SIZE * 1.1)
    this.emergencyMat = new MeshStandardMaterial({
      color: new Color(THEME.emergency),
      emissive: new Color(THEME.emergency),
      emissiveIntensity: 1.2,
      metalness: 0.5,
      roughness: 0.2,
    })
    this.emergencyMesh = new Mesh(this.emergencyGeom, this.emergencyMat)
    this.emergencyMesh.visible = false
    this.group.add(this.emergencyMesh)
  }

  /** 根据车辆数据刷新所有实例位置 */
  update(vehicles: Vehicle[]): void {
    let count = 0
    const start = new Vector3()
    const end = new Vector3()

    for (const v of vehicles) {
      const ep = this.roads.endpointsOf(v.roadId)
      if (!ep) continue
      start.copy(ep.start)
      end.copy(ep.end)

      // progress 0-1 线性插值
      const p = Math.min(1, Math.max(0, v.progress))
      const px = start.x + (end.x - start.x) * p
      const pz = start.z + (end.z - start.z) * p

      if (v.type !== 'normal') {
        // 应急车辆单独渲染
        this.emergencyMesh.visible = true
        this.emergencyMesh.position.set(px, WORLD.VEHICLE_SIZE, pz)
        this.emergencyMesh.rotation.y = -Math.atan2(end.z - start.z, end.x - start.x)
        continue
      }

      if (count >= WORLD.MAX_VEHICLES) continue
      this.dummy.position.set(px, WORLD.VEHICLE_SIZE / 2 + WORLD.ROAD_HEIGHT, pz)
      this.dummy.updateMatrix()
      this.instanced.setMatrixAt(count, this.dummy.matrix)
      count++
    }

    this.instanced.count = count
    this.instanced.instanceMatrix.needsUpdate = true

    // 无应急车辆时隐藏
    if (!vehicles.some((v) => v.type !== 'normal')) {
      this.emergencyMesh.visible = false
    }
  }

  /** 应急车辆闪烁动画 */
  animate(deltaMs: number): void {
    this.elapsed += deltaMs
    if (this.emergencyMesh.visible) {
      const blink = 0.6 + 0.6 * Math.abs(Math.sin(this.elapsed / 250))
      this.emergencyMat.emissiveIntensity = blink
      const s = 1 + 0.15 * Math.sin(this.elapsed / 300)
      this.emergencyMesh.scale.setScalar(s)
    }
  }

  dispose(): void {
    this.instanceGeom.dispose()
    this.instanceMat.dispose()
    this.instanced.dispose()
    this.emergencyGeom.dispose()
    this.emergencyMat.dispose()
  }
}
