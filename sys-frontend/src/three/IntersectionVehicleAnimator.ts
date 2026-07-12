// ================================================================
// IntersectionVehicleAnimator — GLB 小车 4 动画 + 红绿灯联动
// 4 个独立 GLB（straight/turn_left/turn_right/stop）按车道选择
// ================================================================
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { Vehicle, Road, Intersection, SignalPhase, SimVehicleState, SimRoadnetResponse } from '@/types/traffic'
import { laneType, LaneType, canPassIntersection, isNearStopLine, STOP_PROGRESS } from './TrafficRules'

const LANE_W = 6
const CAR_SCALE = 1.5

/** 递归释放 Three.js Object3D 及其子节点的几何体和材质 */
function disposeTree(obj: THREE.Object3D): void {
  obj.traverse((child) => {
    const m = child as THREE.Mesh
    if (m.geometry) m.geometry.dispose()
    if (m.material) {
      if (Array.isArray(m.material)) m.material.forEach((x) => x.dispose())
      else m.material.dispose()
    }
  })
}

// ---- 仿真驱动模式常量 ----
const STOP_LINE = 24        // 停止线距路口中心
const CAR_GAP = 11          // 排队车距
const APPROACH_SPEED = 26   // 绿灯通行速度（单位/秒）
const EXIT_DIST = 2 * STOP_LINE + 34 // 越过此距离视为驶出路口，回收到队尾
const MAX_QUEUE = 10        // 每方向最大展示车辆数

// ---- CityFlow → 3D 坐标映射常量（已验证） ----
const RADIUS_X = 220        // X 轴检测半径（CityFlow 单位）
const RADIUS_Y = 420        // Y 轴检测半径（CityFlow 单位）
const SCALE_X = 0.409       // CityFlow X → 3D X (90/220)
const SCALE_Z = 0.214       // CityFlow Y → 3D Z (90/420)
const ROAD_Y = 0.8          // 路面高度

type Dir = 'north' | 'south' | 'east' | 'west'
const DIRS: Dir[] = ['north', 'south', 'east', 'west']

/** 选中路口的仿真派生状态 */
export interface IntersectionSimState {
  northCount: number
  southCount: number
  eastCount: number
  westCount: number
  currentPhase: SignalPhase
  greenRemain: number
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
  // ---- 真实仿真车辆池：key=vehicle.id, value=3D car group ----
  private realCarPool = new Map<string, THREE.Group>()

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
      // 车头朝向：优先用后端 angle（CityFlow 0°=正东 CCW），降级用道路方向
      const heading = v.angle !== undefined
        ? -THREE.MathUtils.degToRad(v.angle)
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

  // ================================================================
  // 真实仿真车辆渲染：直接映射 SimVehicleState → 3D 场景
  // 替代旧的 "统计→伪造" 管线，让 3D 全景真实反映 CityFlow 仿真
  // ================================================================

  /**
   * 用 CityFlow 仿真车辆直接更新 3D 场景。
   * @param vehicles 仿真帧中的车辆列表
   * @param roadnet CityFlow 静态路网（用于获取路口中心坐标）
   * @param shIt 当前选中的上海路口
   */
  updateFromSimVehicles(
    vehicles: SimVehicleState[],
    roadnet: SimRoadnetResponse,
    shIt: Intersection,
  ): void {
    if (!this.loaded) return

    // 找 CityFlow 路口中心
    const cfKey = `${shIt.col}_${shIt.row}`
    const cfId = `intersection_${cfKey}`
    const cfIt = roadnet.intersections.find((i) => i.id === cfId && !i.virtual)
    if (!cfIt) return
    const cx = cfIt.x
    const cy = cfIt.y

    const activeIds = new Set<string>()

    for (const v of vehicles) {
      const dx = v.x - cx
      const dy = v.y - cy

      // 过滤：超出检测半径的车辆不渲染
      if (Math.abs(dx) > RADIUS_X && Math.abs(dy) > RADIUS_Y) continue
      // 过滤：既不在东西向道路也不在南北向道路上的跳过
      const onEW = Math.abs(dx) > Math.abs(dy)
      if (onEW && (Math.abs(dx) > RADIUS_X || Math.abs(dy) > 30)) continue
      if (!onEW && (Math.abs(dy) > RADIUS_Y || Math.abs(dx) > 30)) continue

      activeIds.add(v.id)

      // 对象池：复用或创建
      let group = this.realCarPool.get(v.id)
      if (!group) {
        group = this.makeRealCar(v.speed < 0.5 ? 'stop' : 'straight')
        this.realCarPool.set(v.id, group)
      }

      // 车道基础偏移：lane 0(左/近黄线)→ -6, lane 1(中)→ 0, lane 2(右/近路沿)→ +6
      const laneBase = (v.lane - 1) * LANE_W

      // 朝向：CityFlow angle 0°=正东(CCW)，转为 Three.js Y 旋转
      // GLB 小车 forward=+X，Y=0→朝东, Y=π/2→朝南, Y=π→朝西, Y=-π/2→朝北
      const heading = -THREE.MathUtils.degToRad(v.angle)

      if (onEW) {
        // 东西向道路：沿 X 轴
        // 东臂(dx>0): 车头朝西，左=南(-Z)，车道偏移 z负=近黄线 ← 与 placeSimCar 一致
        // 西臂(dx<0): 车头朝东，左=北(+Z)，车道偏移 z正=近黄线 ← 需翻转符号
        const zSign = dx >= 0 ? 1 : -1
        group.position.set(dx * SCALE_X, ROAD_Y, zSign * laneBase)
      } else {
        // 南北向道路：沿 Z 轴
        // 北臂(dy>0): 车头朝南，左=东(+X)，车道偏移 x正=近黄线
        // 南臂(dy<0): 车头朝北，左=西(-X)，车道偏移 x负=近黄线
        const xSign = dy >= 0 ? -1 : 1
        group.position.set(xSign * laneBase, ROAD_Y, dy * SCALE_Z)
      }
      group.rotation.set(0, heading, 0)
      group.visible = true
    }

    // 隐藏不在本帧中的车辆
    for (const [id, group] of this.realCarPool) {
      if (!activeIds.has(id)) group.visible = false
    }
  }

  /** 为真实仿真车辆创建 3D 模型（从模板克隆） */
  private makeRealCar(clip: 'straight' | 'stop'): THREE.Group {
    const group = new THREE.Group()
    const tplName = clip === 'straight' ? 'straight' : 'stop'
    const tpl = this.templates.get(tplName)
    if (tpl) {
      group.add(tpl.scene.clone(true))
    }
    this.group.add(group)
    return group
  }

  /** 隐藏所有真实仿真车辆（切换到 mock 模式时调用） */
  hideAllRealCars(): void {
    for (const [, group] of this.realCarPool) group.visible = false
  }

  /** 更新车辆的 stop/straight 模型（根据速度切换） */
  private setRealCarClip(group: THREE.Group, speed: number): void {
    const clip: 'straight' | 'stop' = speed < 0.5 ? 'stop' : 'straight'
    const tplName = clip === 'straight' ? 'straight' : 'stop'
    const tpl = this.templates.get(tplName)
    if (!tpl) return
    // 只在模型真的需要切换时才重建
    // 简单方案：总是用 straight 模型，通过 visible 区分
    group.clear()
    group.add(tpl.scene.clone(true))
  }
  clearSimCars(): void {
    for (const cars of this.simCars.values()) {
      for (const car of cars) {
        disposeTree(car.group)
        this.group.remove(car.group)
      }
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
    // 先递归释放所有车辆实例的 GPU 资源，再从场景图移除
    for (const inst of this.instances.values()) {
      disposeTree(inst.group)
      this.group.remove(inst.group)
    }
    this.instances.clear()
    // 释放真实仿真车辆池
    for (const [, group] of this.realCarPool) {
      disposeTree(group)
      this.group.remove(group)
    }
    this.realCarPool.clear()
    this.clearSimCars()
    // 释放模板克隆缓存（如果有的话）
    for (const tpl of this.templates.values()) {
      disposeTree(tpl.scene)
    }
    this.templates.clear()
    // 释放 animator 自身的 group
    disposeTree(this.group)
  }
}

function clipToName(c: THREE.AnimationClip): string { return c.name || 'unnamed' }
