// ================================================================
// IntersectionVehicleAnimator — 全景路口车辆（GLB 动画 + 红绿灯联动）
// preload car.glb → AnimationMixer 控制直行/左转/右转/停止 + 进度同步
// ================================================================
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { Vehicle, Road, Intersection } from '@/types/traffic'
import { laneType, LaneType, canPassIntersection, isNearStopLine, STOP_PROGRESS } from './TrafficRules'

const LANE_W = 6
const CAR_SCALE = 20

interface CarInstance {
  group: THREE.Group
  mixer: THREE.AnimationMixer
  clips: Map<string, THREE.AnimationAction>
  currentClip: string
  stopped: boolean
  laneIndex: number
  roadId: string
}

export class IntersectionVehicleAnimator {
  readonly group = new THREE.Group()
  private carTemplate: THREE.Group | null = null
  private templateClips: Map<string, THREE.AnimationClip> | null = null
  private loaded = false
  private instances = new Map<string, CarInstance>()
  private intersection: Intersection | null = null

  constructor(
    private intersectionId: string,
    private roadLength = 90,
  ) {}

  async preload(): Promise<void> {
    if (this.loaded) return
    return new Promise((resolve) => {
      new GLTFLoader().load('/models/car.glb', (gltf) => {
        console.log('[Vehicle] car.glb loaded, animations:', gltf.animations.length, 'scene children:', gltf.scene.children.length)
        this.carTemplate = gltf.scene
        this.carTemplate.scale.setScalar(CAR_SCALE)
        this.templateClips = new Map()
        for (const clip of gltf.animations) {
          this.templateClips.set(clip.name, clip)
          console.log('[Vehicle] clip:', clip.name, clip.duration.toFixed(1) + 's')
        }
        this.loaded = true
        // 放测试车验证
        const test = this.carTemplate.clone(true)
        test.position.set(0, 2, 0)
        this.group.add(test)
        console.log('[Vehicle] 测试车已放置')
        resolve()
      }, undefined, (err) => { console.error('[Vehicle] car.glb load failed:', err); this.loaded = true; resolve() })
    })
  }

  setIntersection(it: Intersection): void { this.intersection = it }

  update(vehicles: Vehicle[], roads: Road[]): void {
    const relevant = new Map<string, Road>()
    for (const r of roads) {
      if (r.from === this.intersectionId || r.to === this.intersectionId) {
        relevant.set(r.id, r)
      }
    }

    const activeIds = new Set<string>()

    for (const v of vehicles) {
      const road = relevant.get(v.roadId)
      if (!road) continue
      activeIds.add(v.id)

      let inst = this.instances.get(v.id)
      if (!inst) {
        const group = new THREE.Group()
        const mixer = new THREE.AnimationMixer(group)

        // 克隆车模或降级胶囊体
        if (this.carTemplate && this.templateClips) {
          const clone = this.carTemplate.clone(true)
          group.add(clone)
        }

        // 注册动画 clips
        const clips = new Map<string, THREE.AnimationAction>()
        if (this.templateClips) {
          for (const [name, clip] of this.templateClips) {
            const action = mixer.clipAction(clip)
            action.setLoop(THREE.LoopOnce, 0)
            action.clampWhenFinished = true
            clips.set(name, action)
          }
        }

        this.group.add(group)
        inst = {
          group, mixer, clips,
          currentClip: 'straight',
          stopped: false,
          laneIndex: v.laneIndex,
          roadId: v.roadId,
        }
        this.instances.set(v.id, inst)

        // 启动默认动画
        inst.clips.get('straight')?.play()
      }

      // 选择对应动画 clip
      const lt = laneType(v.laneIndex)
      let clipName = 'straight'
      if (lt === LaneType.LEFT_TURN) clipName = 'left_turn'
      else if (lt === LaneType.RIGHT_TURN) clipName = 'right_turn'

      if (clipName !== inst.currentClip) {
        inst.clips.get(inst.currentClip)?.stop()
        inst.clips.get(clipName)?.reset().play()
        inst.currentClip = clipName
      }

      // 红绿灯控制
      let p = Math.max(0, Math.min(1, v.progress))
      const nearStop = isNearStopLine(p)

      if (nearStop && this.intersection) {
        if (!canPassIntersection(v, this.intersection, true)) {
          p = STOP_PROGRESS
          if (!inst.stopped) {
            inst.stopped = true
            // 切到 stop 动画
            if (inst.currentClip !== 'stop') {
              inst.clips.get(inst.currentClip)?.stop()
              inst.clips.get('stop')?.reset().play()
              inst.currentClip = 'stop'
            }
          }
        } else {
          inst.stopped = false
        }
      }

      if (!inst.stopped && inst.currentClip === 'stop' && clipName !== 'stop') {
        inst.clips.get('stop')?.stop()
        inst.clips.get(clipName)?.reset().play()
        inst.currentClip = clipName
      }

      // 动画时间 = progress 映射
      const action = inst.clips.get(inst.currentClip)
      if (action) {
        const duration = action.getClip().duration || 2
        action.time = Math.max(0, p * duration)
        inst.mixer.setTime(action.time)
      }

      // 定位
      const fromIntersection = (road.from === this.intersectionId)
      const laneOffset = (v.laneIndex - (road.laneCount - 1) / 2) * LANE_W
      const along = (p - 0.5) * this.roadLength * 2
      const isH = this.isHorizontal(road)
      const dir = fromIntersection ? -1 : 1

      if (isH) {
        inst.group.position.set(dir * along, 1.5, laneOffset)
        inst.group.rotation.set(0, dir < 0 ? Math.PI : 0, 0)
      } else {
        inst.group.position.set(laneOffset, 1.5, dir * along)
        inst.group.rotation.set(0, dir < 0 ? -Math.PI / 2 : Math.PI / 2, 0)
      }
    }

    // 清理离开路口的车
    for (const [id, inst] of this.instances) {
      if (!activeIds.has(id)) {
        this.group.remove(inst.group)
        this.instances.delete(id)
      }
    }
  }

  private isHorizontal(road: Road): boolean {
    return road.name.includes('南京') || road.name.includes('淮海') || road.name.includes('建国')
  }

  dispose(): void {
    for (const inst of this.instances.values()) {
      this.group.remove(inst.group)
    }
    this.instances.clear()
  }
}
