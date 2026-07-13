// ================================================================
// IntersectionVehicleAnimator — GLB 小车 4 动画 + 红绿灯联动
// 4 个独立 GLB（straight/turn_left/turn_right/stop）按车道选择
// ================================================================
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { Vehicle, Road, Intersection, SignalPhase, SimVehicleState, SimRoadnetResponse } from '@/types/traffic'
import { laneType, LaneType, canPassIntersection, isNearStopLine, STOP_PROGRESS } from './TrafficRules'
import { createLocalVehiclePoses, type VehicleMovement } from './intersection/VehiclePose'

const LANE_W = 6
const CAR_SCALE = 1.5

const ROOT_MOTION_SUFFIXES = ['.position', '.quaternion']
const DEFAULT_LIVE_FRAME_INTERVAL_MS = 500
const MAX_TRACKED_LIVE_FRAME_GAP_MS = 2000
const LIVE_INTERPOLATION_HEADROOM = 0.9
const MAX_INSTANCED_LIVE_CARS = 512

/**
 * Vehicle GLBs contain baked path translation/rotation on the car node.
 * Runtime positioning owns those transforms, so retaining the baked first frame
 * offsets the mesh away from its Three.js group and rotates it sideways.
 */
export function removeBakedVehicleRootMotion(
  scene: THREE.Group,
  clip: THREE.AnimationClip,
): THREE.AnimationClip {
  const rootMotionTracks = clip.tracks.filter((track) =>
    ROOT_MOTION_SUFFIXES.some((suffix) => track.name.endsWith(suffix)),
  )

  for (const track of rootMotionTracks) {
    const separator = track.name.lastIndexOf('.')
    if (separator <= 0) continue
    const target = scene.getObjectByName(track.name.slice(0, separator))
    if (!target) continue
    if (track.name.endsWith('.position')) target.position.set(0, 0, 0)
    if (track.name.endsWith('.quaternion')) target.quaternion.identity()
  }

  return new THREE.AnimationClip(
    clip.name,
    clip.duration,
    clip.tracks.filter((track) => !rootMotionTracks.includes(track)),
  )
}

// ---- 仿真驱动模式常量 ----
const STOP_LINE = 24        // 停止线距路口中心
const CAR_GAP = 11          // 排队车距
const APPROACH_SPEED = 26   // 绿灯通行速度（单位/秒）
const EXIT_DIST = 2 * STOP_LINE + 34 // 越过此距离视为驶出路口，回收到队尾
const MAX_QUEUE = 10        // 每方向最大展示车辆数

type Dir = 'north' | 'south' | 'east' | 'west'
const DIRS: Dir[] = ['north', 'south', 'east', 'west']

/** 选中路口的仿真派生状态 */
export interface IntersectionSimState {
  northCount: number
  southCount: number
  eastCount: number
  westCount: number
  currentPhase: SignalPhase
  greenRemain: number | null
}

/** 单个方向进场道上的一辆本地车 */
interface SimCar {
  group: THREE.Group
  clip: 'straight' | 'stop'
  /** 沿进场道到停止线的距离：>=0 排队中，<0 正在通过路口 */
  p: number
}

/** 某方向在当前相位下是否放行 */
function dirMoves(dir: Dir, phase: SignalPhase): boolean {
  if (phase === 'all_red') return false
  const ew = phase.startsWith('eastwest')
  return ew ? dir === 'east' || dir === 'west' : dir === 'north' || dir === 'south'
}

interface CarInstance {
  group: THREE.Group
  mixer: THREE.AnimationMixer
  action: THREE.AnimationAction | null
  clipName: string
  duration: number
  stopped: boolean
}

type LiveVehicleClip = VehicleMovement | 'stop'

interface LiveCarInstance {
  group: THREE.Group
  clip: LiveVehicleClip
  from: THREE.Vector3
  to: THREE.Vector3
  fromRotation: number
  toRotation: number
  startAt: number
  lerpMs: number
}

interface LiveCarInstanceLayer {
  mesh: THREE.InstancedMesh
  modelMatrix: THREE.Matrix4
}

type VisualMode = 'mock' | 'aggregate' | 'cityflow'

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
  // ---- 仿真驱动模式：四方向进场车队 ----
  private simCars = new Map<Dir, SimCar[]>(DIRS.map((d) => [d, []]))
  private liveCars = new Map<string, LiveCarInstance>()
  private liveCarInstanceLayers: LiveCarInstanceLayer[] = []
  private liveCarMatrix = new THREE.Matrix4()
  private visualMode: VisualMode | null = null
  private lastLiveVehicles: SimVehicleState[] | null = null
  private lastLiveRoadnet: SimRoadnetResponse | null = null
  private lastLiveIntersectionId = ''
  private lastLiveFrameAt = 0
  private liveFrameIntervalEstimateMs: number | null = null

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
            const runtimeClip = removeBakedVehicleRootMotion(gltf.scene, clip)
            gltf.scene.scale.setScalar(CAR_SCALE)
            resolve([name, { scene: gltf.scene, clip: runtimeClip }])
          }, undefined, () => resolve([name, null]))
        })
      }),
    )
    for (const [name, data] of entries) {
      if (data) { this.templates.set(name, data); console.log('[Vehicle]', name, clipToName(data.clip), (data.clip.duration*30).toFixed(0)+'f') }
    }
    this.initializeLiveCarInstances()
    this.loaded = true
  }

  private initializeLiveCarInstances(): void {
    const template = this.templates.get('straight')
    if (!template || this.liveCarInstanceLayers.length > 0) return

    template.scene.updateMatrixWorld(true)
    template.scene.traverse((object) => {
      if (!(object instanceof THREE.Mesh)) return
      const mesh = new THREE.InstancedMesh(object.geometry, object.material, MAX_INSTANCED_LIVE_CARS)
      mesh.count = 0
      mesh.frustumCulled = false
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      this.liveCarInstanceLayers.push({ mesh, modelMatrix: object.matrixWorld.clone() })
      this.group.add(mesh)
    })
  }

  setIntersection(it: Intersection): void { this.intersection = it }

  private activateMode(mode: VisualMode): void {
    if (this.visualMode === mode) return
    if (this.visualMode === 'mock') this.clearMockCars()
    if (this.visualMode === 'aggregate') this.clearSimCars()
    if (this.visualMode === 'cityflow') this.clearLiveCars()
    this.visualMode = mode
  }

  private clearMockCars(): void {
    for (const inst of this.instances.values()) this.group.remove(inst.group)
    this.instances.clear()
  }

  private clearLiveCars(): void {
    for (const inst of this.liveCars.values()) this.group.remove(inst.group)
    this.liveCars.clear()
    for (const layer of this.liveCarInstanceLayers) layer.mesh.count = 0
    this.lastLiveVehicles = null
    this.lastLiveRoadnet = null
    this.lastLiveIntersectionId = ''
    this.lastLiveFrameAt = 0
    this.liveFrameIntervalEstimateMs = null
  }

  private makeLiveCar(
    position: THREE.Vector3,
    rotationY: number,
    clip: LiveVehicleClip,
    now: number,
  ): LiveCarInstance {
    const group = new THREE.Group()
    const requestedTemplate = this.templates.get(clip)
    group.userData.vehicleClip = requestedTemplate ? clip : 'straight'
    group.position.copy(position)
    group.rotation.y = rotationY
    this.group.add(group)
    return {
      group,
      clip: requestedTemplate ? clip : 'straight',
      from: position.clone(),
      to: position.clone(),
      fromRotation: rotationY,
      toRotation: rotationY,
      startAt: now,
      lerpMs: 200,
    }
  }


  private setLiveClip(instance: LiveCarInstance, clip: LiveVehicleClip): void {
    if (instance.clip === clip) return
    const template = this.templates.get(clip)
    if (!template) return
    instance.clip = clip
    instance.group.userData.vehicleClip = clip
  }

  private updateLiveCarInstances(): void {
    let instanceIndex = 0
    for (const instance of this.liveCars.values()) {
      if (instanceIndex >= MAX_INSTANCED_LIVE_CARS) break
      instance.group.updateMatrix()
      for (const layer of this.liveCarInstanceLayers) {
        this.liveCarMatrix.multiplyMatrices(instance.group.matrix, layer.modelMatrix)
        layer.mesh.setMatrixAt(instanceIndex, this.liveCarMatrix)
      }
      instanceIndex += 1
    }

    for (const layer of this.liveCarInstanceLayers) {
      layer.mesh.count = instanceIndex
      if (instanceIndex > 0) layer.mesh.instanceMatrix.needsUpdate = true
    }
  }

  updateFromCityFlow(
    vehicles: SimVehicleState[],
    roadnet: SimRoadnetResponse,
    intersectionId: string,
    now = performance.now(),
  ): void {
    if (!this.loaded) return
    this.activateMode('cityflow')

    const hasNewFrame = vehicles !== this.lastLiveVehicles
      || roadnet !== this.lastLiveRoadnet
      || intersectionId !== this.lastLiveIntersectionId

    if (hasNewFrame) {
      if (this.lastLiveFrameAt > 0) {
        const observedInterval = now - this.lastLiveFrameAt
        if (observedInterval <= MAX_TRACKED_LIVE_FRAME_GAP_MS) {
          const normalizedInterval = THREE.MathUtils.clamp(observedInterval, 120, 1200)
          this.liveFrameIntervalEstimateMs = this.liveFrameIntervalEstimateMs === null
            ? normalizedInterval
            : THREE.MathUtils.lerp(this.liveFrameIntervalEstimateMs, normalizedInterval, 0.35)
        }
      }
      const lerpMs = THREE.MathUtils.clamp(
        (this.liveFrameIntervalEstimateMs ?? DEFAULT_LIVE_FRAME_INTERVAL_MS) * LIVE_INTERPOLATION_HEADROOM,
        160,
        1600,
      )
      const poses = createLocalVehiclePoses(vehicles, roadnet, intersectionId)
      const activeIds = new Set<string>()

      for (const pose of poses) {
        activeIds.add(pose.id)
        let instance = this.liveCars.get(pose.id)
        if (!instance) {
          const clip: LiveVehicleClip = pose.speed < 0.1 && !pose.onLaneLink ? 'stop' : pose.movement
          instance = this.makeLiveCar(pose.position, pose.rotationY, clip, now)
          this.liveCars.set(pose.id, instance)
        } else {
          const clip: LiveVehicleClip = pose.speed < 0.1 && !pose.onLaneLink ? 'stop' : pose.movement
          this.setLiveClip(instance, clip)
          const jumpDistance = instance.group.position.distanceTo(pose.position)
          instance.from.copy(jumpDistance > 80 ? pose.position : instance.group.position)
          instance.to.copy(pose.position)
          instance.fromRotation = instance.group.rotation.y
          instance.toRotation = pose.rotationY
          instance.startAt = now
          instance.lerpMs = lerpMs
          if (jumpDistance > 80) instance.group.position.copy(pose.position)
        }
      }

      for (const [id, instance] of this.liveCars) {
        // CityFlow frames are complete snapshots. Keeping a missing vehicle at
        // its last pose leaves a frozen car in the road or intersection.
        if (!activeIds.has(id)) {
          this.group.remove(instance.group)
          this.liveCars.delete(id)
        }
      }

      this.lastLiveVehicles = vehicles
      this.lastLiveRoadnet = roadnet
      this.lastLiveIntersectionId = intersectionId
      this.lastLiveFrameAt = now
    }

    for (const instance of this.liveCars.values()) {
      const progress = THREE.MathUtils.clamp((now - instance.startAt) / instance.lerpMs, 0, 1)
      instance.group.position.lerpVectors(instance.from, instance.to, progress)
      const rotationDelta = Math.atan2(
        Math.sin(instance.toRotation - instance.fromRotation),
        Math.cos(instance.toRotation - instance.fromRotation),
      )
      instance.group.rotation.y = instance.fromRotation + rotationDelta * progress
    }
    this.updateLiveCarInstances()
  }

  update(vehicles: Vehicle[], roads: Road[]): void {
    if (!this.loaded) return
    this.activateMode('mock')
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
      // 车头朝向：优先用后端 angle，降级用道路方向
      const heading = v.angle !== undefined
        ? THREE.MathUtils.degToRad(90 - v.angle)
        : this.roadHeading(road)

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

  // ================================================================
  // 仿真驱动模式：按四方向车辆数 + 相位程序化生成排队/通行车流
  // ================================================================

  /** 用仿真派生状态更新场景（每帧调用，deltaMs 为帧间隔） */
  updateFromSim(state: IntersectionSimState, deltaMs: number): void {
    if (!this.loaded) return
    this.activateMode('aggregate')
    const dt = Math.min(0.05, deltaMs / 1000) // 限幅，避免卡顿后跳变
    const counts: Record<Dir, number> = {
      north: Math.min(MAX_QUEUE, state.northCount),
      south: Math.min(MAX_QUEUE, state.southCount),
      east: Math.min(MAX_QUEUE, state.eastCount),
      west: Math.min(MAX_QUEUE, state.westCount),
    }

    for (const dir of DIRS) {
      const moving = dirMoves(dir, state.currentPhase)
      this.updateApproach(dir, counts[dir], moving, dt)
    }
  }

  /** 更新单个方向的进场车队 */
  private updateApproach(dir: Dir, count: number, moving: boolean, dt: number): void {
    const cars = this.simCars.get(dir)!

    // ---- 调整车辆数量 ----
    while (cars.length < count) {
      const backP = cars.length > 0
        ? Math.max(...cars.map((c) => c.p)) + CAR_GAP
        : 0
      const car = this.makeSimCar(moving ? 'straight' : 'stop')
      car.p = backP
      cars.push(car)
    }
    while (cars.length > count) {
      const removed = cars.pop()!
      this.group.remove(removed.group)
    }

    if (cars.length === 0) return

    if (moving) {
      // ---- 绿灯：整队前移，越过路口的车回收到队尾 ----
      for (const car of cars) car.p -= APPROACH_SPEED * dt
      for (const car of cars) {
        if (car.p < -EXIT_DIST) {
          const backP = Math.max(...cars.map((c) => c.p)) + CAR_GAP
          car.p = backP
        }
        this.setSimClip(car, 'straight')
      }
    } else {
      // ---- 红灯：车辆缓动到排队槽位（rank * CAR_GAP） ----
      const sorted = [...cars].sort((a, b) => a.p - b.p)
      sorted.forEach((car, rank) => {
        const target = rank * CAR_GAP
        car.p += (target - car.p) * Math.min(1, dt * 5)
        this.setSimClip(car, 'stop')
      })
    }

    for (const car of cars) this.placeSimCar(car, dir)
  }

  /** 创建一辆本地车（指定初始 GLB clip） */
  private makeSimCar(clip: 'straight' | 'stop'): SimCar {
    const group = new THREE.Group()
    const tpl = this.templates.get(clip === 'straight' ? 'straight' : 'stop')
    if (tpl) {
      const model = tpl.scene.clone(true)
      // 冻结 GLB 内置动画在首帧（位移完全由 group.position 控制）
      group.add(model)
    }
    this.group.add(group)
    return { group, clip, p: 0 }
  }

  /** 切换车辆 GLB 模型（straight ↔ stop），仅在变化时替换 */
  private setSimClip(car: SimCar, clip: 'straight' | 'stop'): void {
    if (car.clip === clip) return
    const tpl = this.templates.get(clip)
    if (!tpl) return
    car.group.clear()
    car.group.add(tpl.scene.clone(true))
    car.clip = clip
  }

  /** 将车辆按方向 + 沿路距离 p 摆放到世界坐标 */
  private placeSimCar(car: SimCar, dir: Dir): void {
    const y = 0.8
    // p = 到停止线距离；停止线在 STOP_LINE，p 增大 = 更远离路口
    switch (dir) {
      case 'north': // 北臂：车在 +Z，车头朝 -Z（朝路口中心）
        car.group.position.set(LANE_W, y, STOP_LINE + car.p)
        car.group.rotation.set(0, Math.PI / 2, 0)
        break
      case 'south': // 南臂：车在 -Z，车头朝 +Z
        car.group.position.set(-LANE_W, y, -(STOP_LINE + car.p))
        car.group.rotation.set(0, -Math.PI / 2, 0)
        break
      case 'east': // 东臂：车在 +X，车头朝 -X
        car.group.position.set(STOP_LINE + car.p, y, -LANE_W)
        car.group.rotation.set(0, Math.PI, 0)
        break
      case 'west': // 西臂：车在 -X，车头朝 +X
        car.group.position.set(-(STOP_LINE + car.p), y, LANE_W)
        car.group.rotation.set(0, 0, 0)
        break
    }
  }

  /** 清空仿真车队（切换路口或关闭弹窗时调用） */
  clearSimCars(): void {
    for (const cars of this.simCars.values()) {
      for (const car of cars) this.group.remove(car.group)
      cars.length = 0
    }
  }

  /** 降级方案：根据道路名判断朝向（用于无后端 angle 的 mock 数据） */
  private roadHeading(road: Road): number {
    // 横向道路（东西向）
    if (this.isHorizontal(road)) {
      return road.name.includes('西') ? Math.PI : 0
    }
    // 纵向道路（南北向）
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
    this.clearMockCars()
    this.clearSimCars()
    this.clearLiveCars()
    this.visualMode = null
  }
}

function clipToName(c: THREE.AnimationClip): string { return c.name || 'unnamed' }
