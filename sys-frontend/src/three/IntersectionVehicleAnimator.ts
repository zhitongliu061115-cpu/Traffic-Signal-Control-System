// ================================================================
// IntersectionVehicleAnimator — GLB 小车 4 动画 + 红绿灯联动
// 4 个独立 GLB（straight/turn_left/turn_right/stop）按车道选择
// ================================================================
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { Vehicle, Road, Intersection } from '@/types/traffic'
import { laneType, LaneType, canPassIntersection, isNearStopLine, STOP_PROGRESS } from './TrafficRules'

const LANE_W = 6
const CAR_SCALE = 1.5

interface CarInstance {
  group: THREE.Group
  mixer: THREE.AnimationMixer
  action: THREE.AnimationAction | null
  clipName: string
  duration: number
  stopped: boolean
}

const GLB_MAP: Record<string, string> = {
  straight: '/models/straight.glb',
  left_turn: '/models/turn_left.glb',
  right_turn: '/models/turn_right.glb',
  stop: '/models/stop.glb',
}

export class IntersectionVehicleAnimator {
  readonly group = new THREE.Group()
  private templates = new Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>()
  private loaded = false
  private instances = new Map<string, CarInstance>()
  private intersection: Intersection | null = null

  constructor(private intersectionId: string, private roadLength = 90) {}

  async preload(): Promise<void> {
    if (this.loaded) return
    const loader = new GLTFLoader()
    const entries = await Promise.all(
      Object.entries(GLB_MAP).map(async ([name, url]) => {
        return new Promise<[string, { scene: THREE.Group; clip: THREE.AnimationClip } | null]>((resolve) => {
          loader.load(url, (gltf) => {
            const clip = gltf.animations[0]
            if (!clip) { console.warn('[Vehicle]', name, 'no animation'); resolve([name, null]); return }
            gltf.scene.scale.setScalar(CAR_SCALE)
            resolve([name, { scene: gltf.scene, clip }])
          }, undefined, () => resolve([name, null]))
        })
      }),
    )
    for (const [name, data] of entries) {
      if (data) { this.templates.set(name, data); console.log('[Vehicle]', name, clipToName(data.clip), (data.clip.duration*30).toFixed(0)+'f') }
    }
    this.loaded = true
  }

  setIntersection(it: Intersection): void { this.intersection = it }

  update(vehicles: Vehicle[], roads: Road[]): void {
    if (!this.loaded) return
    const relevant = new Map<string, Road>()
    for (const r of roads) {
      if (r.from === this.intersectionId || r.to === this.intersectionId) relevant.set(r.id, r)
    }
    const activeIds = new Set<string>()

    for (const v of vehicles) {
      const road = relevant.get(v.roadId)
      if (!road) continue
      activeIds.add(v.id)

      let inst = this.instances.get(v.id)
      if (!inst) {
        const lt = laneType(v.laneIndex)
        let clipName = 'straight'
        if (lt === LaneType.LEFT_TURN) clipName = 'left_turn'
        else if (lt === LaneType.RIGHT_TURN) clipName = 'right_turn'

        const tpl = this.templates.get(clipName)
        const group = new THREE.Group()
        const mixer = new THREE.AnimationMixer(group)
        let action: THREE.AnimationAction | null = null
        let duration = 2

        if (tpl) {
          group.add(tpl.scene.clone(true))
          action = mixer.clipAction(tpl.clip)
          action.setLoop(THREE.LoopOnce, 0)
          action.clampWhenFinished = true
          duration = tpl.clip.duration
        }

        this.group.add(group)
        inst = { group, mixer, action: action!, clipName, duration, stopped: false }
        this.instances.set(v.id, inst)
        action?.play()
      }

      // 红绿灯控制
      let p = Math.max(0, Math.min(1, v.progress))
      if (isNearStopLine(p) && this.intersection && !canPassIntersection(v, this.intersection, true)) {
        p = STOP_PROGRESS
        if (!inst.stopped) {
          inst.stopped = true
          // 切到停止动画
          const stopTpl = this.templates.get('stop')
          if (stopTpl) {
            inst.action?.stop()
            inst.group.clear()
            inst.group.add(stopTpl.scene.clone(true))
            const stopAction = inst.mixer.clipAction(stopTpl.clip)
            stopAction.setLoop(THREE.LoopOnce, 0); stopAction.clampWhenFinished = true
            inst.action = stopAction; inst.duration = stopTpl.clip.duration
            inst.action.play()
          }
        }
      } else if (inst.stopped && canPassIntersection(v, this.intersection!, true)) {
        inst.stopped = false
        // 恢复行驶动画
        const lt = laneType(v.laneIndex)
        let cn = 'straight'; if (lt === LaneType.LEFT_TURN) cn = 'left_turn'; else if (lt === LaneType.RIGHT_TURN) cn = 'right_turn'
        const tpl = this.templates.get(cn)
        if (tpl) {
          inst.action?.stop(); inst.group.clear(); inst.group.add(tpl.scene.clone(true))
          inst.action = inst.mixer.clipAction(tpl.clip)
          inst.action.setLoop(THREE.LoopOnce, 0); inst.action.clampWhenFinished = true
          inst.duration = tpl.clip.duration; inst.action.play()
        }
      }

      // 动画冻结在 time=0（车模朝前），位置完全由 group.position 控制
      // 这样可以避免：Blender 动画内置位移 + 代码位移 = 双倍位置
      if (inst.action) {
        inst.action.time = 0
        inst.mixer.setTime(0)
      }

      // 定位
      const fromIntersection = (road.from === this.intersectionId)
      const laneOffset = (v.laneIndex - (road.laneCount - 1) / 2) * LANE_W
      const along = (p - 0.5) * this.roadLength * 2
      const isH = this.isHorizontal(road)
      // 位置方向：离开路口时反转坐标映射（progress 0→1 变成 远→近）
      const posDir = fromIntersection ? -1 : 1
      // 车头朝向：始终跟 from→to 方向一致
      const heading = this.headingAngle(road)

      if (isH) {
        inst.group.position.set(posDir * along, 0.8, laneOffset)
        inst.group.rotation.set(0, heading, 0)
      } else {
        inst.group.position.set(laneOffset, 0.8, posDir * along)
        inst.group.rotation.set(0, heading, 0)
      }
    }

    for (const [id, inst] of this.instances) {
      if (!activeIds.has(id)) { this.group.remove(inst.group); this.instances.delete(id) }
    }
  }

  /** 根据道路走向返回车头 Y 轴旋转角 */
  private headingAngle(road: Road): number {
    // 横向（东西向）
    if (road.name.includes('南京') || road.name.includes('淮海') || road.name.includes('建国')) {
      // 路名里"东段"→朝东(0)，"西段"→朝西(π)
      return road.name.includes('西') ? Math.PI : 0
    }
    // 纵向（南北向）
    // "南段"→朝南(-π/2)，"北段"→朝北(π/2)
    if (road.name.includes('南')) return -Math.PI / 2
    if (road.name.includes('北')) return Math.PI / 2
    return 0
  }

  private isHorizontal(road: Road): boolean {
    // 横向（东西向）：南京路 / 淮海路 / 建国路
    if (road.name.includes('南京') || road.name.includes('淮海') || road.name.includes('建国')) return true
    // 纵向（南北向）：西藏路 / 黄陂路 / 瑞金路 / 常熟路 / 襄阳路
    if (road.name.includes('西藏') || road.name.includes('黄陂') || road.name.includes('瑞金') || road.name.includes('常熟') || road.name.includes('襄阳')) return false
    // 兜底：按 from/to 路口 lng 判断（lng 相近 = 纵向）
    return false
  }

  dispose(): void {
    for (const inst of this.instances.values()) this.group.remove(inst.group)
    this.instances.clear()
  }
}

function clipToName(c: THREE.AnimationClip): string { return c.name || 'unnamed' }
