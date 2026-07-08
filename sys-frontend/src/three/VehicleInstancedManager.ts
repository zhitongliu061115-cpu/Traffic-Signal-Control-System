// ================================================================
// VehicleInstancedManager — InstancedMesh 车辆（CustomLayer 内）
// 根据 roadId + progress + laneIndex 计算 Mercator 位置，沿道路流动
// ================================================================
import * as THREE from 'three'
import type { Intersection, Road, Vehicle } from '@/types/traffic'
import { vehiclePathPosition } from '@/map/CoordinateHelper'
import { WORLD } from './config'

export class VehicleInstancedManager {
  readonly group = new THREE.Group()
  private instanced: THREE.InstancedMesh
  private geom: THREE.SphereGeometry
  private mat: THREE.MeshStandardMaterial
  private dummy = new THREE.Object3D()
  private emergencyMesh: THREE.Mesh | null = null
  private emergencyMat: THREE.MeshStandardMaterial | null = null
  private elapsed = 0

  constructor() {
    this.geom = new THREE.SphereGeometry(1.2, 10, 10)
    this.mat = new THREE.MeshStandardMaterial({
      color: new THREE.Color('#7AF7FF'),
      emissive: new THREE.Color('#7AF7FF'),
      emissiveIntensity: 0.7,
      metalness: 0.3,
      roughness: 0.3,
    })
    this.instanced = new THREE.InstancedMesh(this.geom, this.mat, WORLD.MAX_VEHICLES)
    this.instanced.count = 0
    this.group.add(this.instanced)
  }

  update(
    vehicles: Vehicle[],
    roads: Road[],
    intersections: Intersection[],
  ): void {
    let count = 0
    const rMap = new Map(roads.map((r) => [r.id, r]))

    for (const v of vehicles) {
      const r = rMap.get(v.roadId)
      if (!r) continue

      const pos = vehiclePathPosition(r, intersections, v.progress, v.laneIndex)
      if (!pos) continue

      if (v.type !== 'normal') {
        // 应急车辆单独 Mesh
        if (!this.emergencyMesh) {
          const emGeom = new THREE.BoxGeometry(3.5, 2.5, 2)
          this.emergencyMat = new THREE.MeshStandardMaterial({
            color: new THREE.Color('#00E5FF'),
            emissive: new THREE.Color('#00E5FF'),
            emissiveIntensity: 1.2,
            metalness: 0.5,
            roughness: 0.2,
          })
          this.emergencyMesh = new THREE.Mesh(emGeom, this.emergencyMat)
          this.group.add(this.emergencyMesh)
        }
        this.emergencyMesh.position.copy(pos)
        continue
      }

      if (count >= WORLD.MAX_VEHICLES) continue
      this.dummy.position.copy(pos)
      this.dummy.updateMatrix()
      this.instanced.setMatrixAt(count, this.dummy.matrix)
      count++
    }

    this.instanced.count = count
    this.instanced.instanceMatrix.needsUpdate = true

    // 无应急车辆时隐藏
    if (this.emergencyMesh) {
      this.emergencyMesh.visible = vehicles.some((v) => v.type !== 'normal')
    }
  }

  animate(deltaMs: number): void {
    this.elapsed += deltaMs
    if (this.emergencyMesh?.visible && this.emergencyMat) {
      const blink = 0.7 + 0.6 * Math.abs(Math.sin(this.elapsed / 250))
      this.emergencyMat.emissiveIntensity = blink
    }
  }

  dispose(): void {
    this.geom.dispose()
    this.mat.dispose()
    this.instanced.dispose()
    if (this.emergencyMesh) {
      this.emergencyMesh.geometry.dispose()
      this.emergencyMat?.dispose()
    }
  }
}
