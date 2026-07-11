// ================================================================
// IntersectionManager — 路口节点 / 选中环 / 设备点 / 信号灯 / 名称标签
// ================================================================
import {
  Group,
  Mesh,
  CircleGeometry,
  RingGeometry,
  SphereGeometry,
  MeshBasicMaterial,
  MeshStandardMaterial,
  Color,
  Vector3,
  DoubleSide,
  type Object3D,
} from 'three'
import type { Intersection, DeviceStatus, SignalPhase } from '@/types/traffic'
import { THEME, WORLD, toWorldX, toWorldZ, cachedColor } from './config'
import { LabelManager, type TextLabel } from './LabelManager'
import { PHASE_LABELS } from '@/types/traffic'

/** 单个路口的 3D 对象集合 */
interface IntersectionNode {
  id: string
  group: Group
  /** 用于 raycast 点击的核心 mesh */
  hitMesh: Mesh
  coreMat: MeshStandardMaterial
  selectRing: Mesh
  signalRing: Mesh
  signalRingMat: MeshBasicMaterial
  deviceDot: Mesh
  deviceDotMat: MeshBasicMaterial
  nameLabel: TextLabel
  signalLabel: TextLabel
  worldPos: Vector3
}

/** 相位 → 放行方向文字 */
function phaseDirection(phase: SignalPhase): string {
  if (phase.startsWith('eastwest')) return 'EW'
  if (phase.startsWith('northsouth')) return 'NS'
  return '—'
}

/** 相位 → 信号颜色（绿波强制放行由外部覆盖） */
function signalColorHex(it: Intersection): string {
  if (it.deviceStatus !== 'online') return THEME.deviceOffline
  if (it.currentPhase === 'all_red') return THEME.signalRed
  if (it.greenRemain <= 3) return THEME.signalRed
  if (it.greenRemain <= 8) return THEME.signalYellow
  return THEME.signalGreen
}

function deviceColorHex(status: DeviceStatus): string {
  if (status === 'online') return THEME.deviceOnline
  if (status === 'fault') return THEME.deviceFault
  return THEME.deviceOffline
}

export class IntersectionManager {
  readonly group = new Group()
  private nodes = new Map<string, IntersectionNode>()
  private labels: LabelManager
  private selectedId: string | null = null
  private greenWaveIds = new Set<string>()
  private elapsed = 0

  constructor(labels: LabelManager) {
    this.labels = labels
  }

  /** raycast 目标列表 */
  get raycastTargets(): Object3D[] {
    return Array.from(this.nodes.values()).map((n) => n.hitMesh)
  }

  /** 通过 hitMesh 反查路口 id */
  resolveId(obj: Object3D): string | null {
    for (const n of this.nodes.values()) {
      if (n.hitMesh === obj) return n.id
    }
    return null
  }

  /** 初始化所有路口节点 */
  build(intersections: Intersection[]): void {
    for (const it of intersections) {
      const node = this.createNode(it)
      this.nodes.set(it.id, node)
      this.group.add(node.group)
    }
  }

  private createNode(it: Intersection): IntersectionNode {
    const g = new Group()
    const wx = toWorldX(it.x)
    const wz = toWorldZ(it.y)
    g.position.set(wx, 0, wz)
    const worldPos = new Vector3(wx, 0, wz)

    // 发光核心圆盘（水平）
    const coreMat = new MeshStandardMaterial({
      color: new Color(THEME.nodeNormal),
      emissive: new Color(THEME.nodeNormal),
      emissiveIntensity: 0.7,
      metalness: 0.3,
      roughness: 0.4,
    })
    const core = new Mesh(new CircleGeometry(WORLD.NODE_RADIUS, 40), coreMat)
    core.rotation.x = -Math.PI / 2
    core.position.y = 1
    g.add(core)

    // 点击命中体（略大不可见）
    const hitMesh = new Mesh(
      new CircleGeometry(WORLD.NODE_RADIUS + 8, 24),
      new MeshBasicMaterial({ visible: false }),
    )
    hitMesh.rotation.x = -Math.PI / 2
    hitMesh.position.y = 2
    g.add(hitMesh)

    // 选中金色高亮环
    const selectRing = new Mesh(
      new RingGeometry(WORLD.NODE_RADIUS + 6, WORLD.NODE_RADIUS + 11, 48),
      new MeshBasicMaterial({ color: new Color(THEME.nodeSelected), side: DoubleSide, transparent: true, opacity: 0.95 }),
    )
    selectRing.rotation.x = -Math.PI / 2
    selectRing.position.y = 2.5
    selectRing.visible = false
    g.add(selectRing)

    // 信号灯光圈
    const signalRingMat = new MeshBasicMaterial({
      color: new Color(signalColorHex(it)),
      side: DoubleSide,
      transparent: true,
      opacity: 0.85,
    })
    const signalRing = new Mesh(
      new RingGeometry(WORLD.NODE_RADIUS - 3, WORLD.NODE_RADIUS, 40),
      signalRingMat,
    )
    signalRing.rotation.x = -Math.PI / 2
    signalRing.position.y = 3
    g.add(signalRing)

    // 设备状态点（球体，浮于节点上方）
    const deviceDotMat = new MeshBasicMaterial({ color: new Color(deviceColorHex(it.deviceStatus)) })
    const deviceDot = new Mesh(new SphereGeometry(3.5, 16, 16), deviceDotMat)
    deviceDot.position.set(WORLD.NODE_RADIUS + 2, 8, -(WORLD.NODE_RADIUS + 2))
    g.add(deviceDot)

    // 名称标签
    const nameLabel = this.labels.create(it.name, { fontSize: 34, color: '#e8f4ff', scale: 1 })
    nameLabel.sprite.position.set(0, 34, 0)
    g.add(nameLabel.sprite)

    // 信号倒计时标签
    const signalLabel = this.labels.create(
      `${phaseDirection(it.currentPhase)} ${Math.round(it.greenRemain)}s`,
      { fontSize: 30, color: signalColorHex(it), scale: 0.85 },
    )
    signalLabel.sprite.position.set(0, 20, 0)
    g.add(signalLabel.sprite)

    return {
      id: it.id,
      group: g,
      hitMesh,
      coreMat,
      selectRing,
      signalRing,
      signalRingMat,
      deviceDot,
      deviceDotMat,
      nameLabel,
      signalLabel,
      worldPos,
    }
  }

  /** 每帧 / 数据更新时同步路口状态 */
  update(intersections: Intersection[], selectedId: string | null, greenWaveIds: Set<string>): void {
    this.selectedId = selectedId
    this.greenWaveIds = greenWaveIds

    for (const it of intersections) {
      const node = this.nodes.get(it.id)
      if (!node) continue

      const isSelected = it.id === selectedId
      node.selectRing.visible = isSelected

      // 核心颜色：选中金色，否则蓝
      const coreHex = isSelected ? THEME.nodeSelected : THEME.nodeNormal
      node.coreMat.color.copy(cachedColor(coreHex))
      node.coreMat.emissive.copy(cachedColor(coreHex))

      // 信号颜色（绿波路口强制绿）
      const sigHex = greenWaveIds.has(it.id) ? THEME.signalGreen : signalColorHex(it)
      node.signalRingMat.color.copy(cachedColor(sigHex))

      // 设备点颜色
      node.deviceDotMat.color.copy(cachedColor(deviceColorHex(it.deviceStatus)))

      // 信号标签文字
      const label = it.deviceStatus === 'online'
        ? `${phaseDirection(it.currentPhase)} ${Math.round(it.greenRemain)}s`
        : (it.deviceStatus === 'fault' ? '故障' : '离线')
      node.signalLabel.setText(label)
    }
  }

  /** 每帧动画（选中环旋转、绿波脉冲、故障闪烁） */
  animate(deltaMs: number, intersections: Intersection[]): void {
    this.elapsed += deltaMs
    const pulse = 0.6 + 0.4 * Math.sin(this.elapsed / 300)
    const blink = Math.sin(this.elapsed / 200) > 0 ? 1 : 0.25

    for (const it of intersections) {
      const node = this.nodes.get(it.id)
      if (!node) continue

      if (node.selectRing.visible) {
        node.selectRing.rotation.z += deltaMs * 0.002
      }
      // 绿波脉冲
      if (this.greenWaveIds.has(it.id)) {
        node.signalRingMat.opacity = 0.5 + 0.5 * pulse
      } else {
        node.signalRingMat.opacity = 0.85
      }
      // 故障设备点闪烁
      if (it.deviceStatus === 'fault') {
        node.deviceDotMat.opacity = blink
        node.deviceDotMat.transparent = true
      } else {
        node.deviceDotMat.opacity = 1
        node.deviceDotMat.transparent = false
      }
    }
  }

  /** 获取路口世界坐标（供相机飞行） */
  worldPositionOf(id: string): Vector3 | null {
    return this.nodes.get(id)?.worldPos ?? null
  }

  dispose(): void {
    this.nodes.forEach((n) => {
      n.coreMat.dispose()
      n.signalRingMat.dispose()
      n.deviceDotMat.dispose()
      n.group.traverse((o) => {
        const m = o as Mesh
        if (m.geometry) m.geometry.dispose()
      })
    })
    this.nodes.clear()
  }
}

export { PHASE_LABELS }
