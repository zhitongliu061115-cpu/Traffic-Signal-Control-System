<script setup lang="ts">
// ================================================================
// Intersection3DViewer — 路口三维实景视图（全屏弹窗）
//
// 当前：程序化几何占位（道路/斑马线/信号灯杆/摄像头/建筑）
// 预留：GLTFLoader 加载真实路口模型 modelUrl = `/models/{id}.glb`
//       接口已就绪，放入 .glb 即可自动加载（见 loadModel 注释）
// ================================================================
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { storeToRefs } from 'pinia'
import {
  Scene,
  PerspectiveCamera,
  WebGLRenderer,
  Color,
  AmbientLight,
  DirectionalLight,
  Fog,
  Group,
  Mesh,
  InstancedMesh,
  Matrix4,
  BoxGeometry,
  Sprite,
  SpriteMaterial,
  CanvasTexture,
  LinearFilter,
  CylinderGeometry,
  PlaneGeometry,
  SphereGeometry,
  MeshStandardMaterial,
  GridHelper,
  BufferGeometry,
  Line,
  LineBasicMaterial,
} from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js'
import { useTrafficStore } from '@/stores/traffic'
import { IntersectionVehicleAnimator, type IntersectionSimState } from '@/three/IntersectionVehicleAnimator'
import { createLocalRoadCenterlines } from '@/three/intersection/IntersectionGeometry'
import { createLocalLaneLinkPaths, createLocalRoadSurfaceSegments } from '@/three/intersection/RoadSurfaceGeometry'
import { PHASE_LABELS, DEVICE_STATUS_LABELS } from '@/types/traffic'
import { signalRemainingSec, toSignalPhase } from '@/simulation/signalState'
import type { SignalPhase, SimVehicleState, SimSignalState, SimRoadnetResponse, Intersection } from '@/types/traffic'

const props = defineProps<{ intersectionId: string | null }>()
const emit = defineEmits<{ close: [] }>()
const TARGET_RENDER_INTERVAL_MS = 1000 / 45

const store = useTrafficStore()
const { intersections, roads, vehicles, simulationVehicles, simulationSignals, simRoadnet, simulationStatus, simulationErrorMessage } = storeToRefs(store)

const viewerBox = ref<HTMLDivElement | null>(null)
const loading = ref(true)
const modelFound = ref(false)
const showRoadnetDebug = ref(true)
const showRoadnetSurface = ref(true)

const intersection = computed(() =>
  intersections.value.find((it) => it.id === props.intersectionId) ?? null,
)

// ================================================================
// 从仿真帧派生选中路口的四方向车辆数 + 相位（Plan B 数据源）
// ================================================================

/** 上海路口 → CityFlow 转置键 "R_C"（R=col, C=row） */
function simKeyOf(it: Intersection): string {
  return `${it.col}_${it.row}`
}

function deriveIntersectionState(
  shIt: Intersection | null,
  simVehicles: SimVehicleState[],
  simSignals: SimSignalState[],
  roadnet: SimRoadnetResponse | null,
): IntersectionSimState | null {
  if (!shIt || !roadnet) return null
  const key = simKeyOf(shIt)
  const cfId = `intersection_${key}`

  // 找 CityFlow 路口中心坐标
  const cfIt = roadnet.intersections.find((i) => i.id === cfId && !i.virtual)
  if (!cfIt) return null
  const cx = cfIt.x
  const cy = cfIt.y

  // 统计进场车辆：距中心一定范围内，按相对位置分四方向
  // CityFlow: x 轴道路 = 东西向(EW)，y 轴道路 = 南北向(NS)
  const RADIUS_X = 220 // x 轴路口间距 400，取半略小
  const RADIUS_Y = 420 // y 轴路口间距 800，取半略小
  let north = 0, south = 0, east = 0, west = 0
  for (const v of simVehicles) {
    const dx = v.x - cx
    const dy = v.y - cy
    if (Math.abs(dx) > Math.abs(dy)) {
      // 东西向道路
      if (Math.abs(dx) > RADIUS_X || Math.abs(dy) > 30) continue
      if (dx > 0) east++
      else west++
    } else {
      // 南北向道路
      if (Math.abs(dy) > RADIUS_Y || Math.abs(dx) > 30) continue
      if (dy > 0) north++
      else south++
    }
  }

  // 当前相位
  const sig = simSignals.find((s) => s.intersectionId === cfId)
  const currentPhase = toSignalPhase(sig?.phaseCode)
  const greenRemain = signalRemainingSec(sig)

  return { northCount: north, southCount: south, eastCount: east, westCount: west, currentPhase, greenRemain }
}

/** 当前选中路口的仿真派生状态（仿真运行时有值，否则 null → 走 mock 降级） */
const simState = computed<IntersectionSimState | null>(() => {
  if (simulationStatus.value !== 'running') return null
  return deriveIntersectionState(
    intersection.value,
    simulationVehicles.value as SimVehicleState[],
    simulationSignals.value as SimSignalState[],
    simRoadnet.value as SimRoadnetResponse | null,
  )
})

const displayedRemainingSec = computed<number | null>(() => {
  if (simulationStatus.value === 'running') return simState.value?.greenRemain ?? null
  return intersection.value?.greenRemain ?? null
})

const dataModeLabel = computed(() => {
  if (simulationStatus.value === 'running') {
    return simulationErrorMessage.value ? '仿真数据延迟' : 'CityFlow 逐车映射'
  }
  return '演示数据'
})


// ---- Three.js 局部实例 ----
let scene: Scene | null = null
let camera: PerspectiveCamera | null = null
let renderer: WebGLRenderer | null = null
let controls: OrbitControls | null = null
let rafId: number | null = null
let resizeObserver: ResizeObserver | null = null
let lastFrameTime = 0
let lastRenderTime = 0
const tlSpriteUpdaters: Array<() => void> = []
let vehicleAnimator: IntersectionVehicleAnimator | null = null

/** 搭建路口全景实景：三车道 × 四方向 + 3D 灯柱 + 设备 */
function buildProceduralIntersection(): Group {
  const g = new Group()
  const asphalt = new MeshStandardMaterial({ color: 0x1a2433, roughness: 0.9, metalness: 0.1 })
  const curbMat = new MeshStandardMaterial({ color: 0x4a5568, roughness: 0.6 })
  const line = new MeshStandardMaterial({ color: 0xdfe8f0, emissive: 0x223040, emissiveIntensity: 0.3 })

  // 三车道参数：每车道宽 6 单位，双向 6 车道 = 36 单位 + 中央隔离 4 单位 = 40 单位宽
  const LANE_W = 6
  const ROAD_W = LANE_W * 6 + 4
  const ROAD_LEN = 180

  // 十字路面
  const roadH = new Mesh(new BoxGeometry(ROAD_LEN, 0.8, ROAD_W), asphalt)
  g.add(roadH)
  const roadV = new Mesh(new BoxGeometry(ROAD_W, 0.8, ROAD_LEN), asphalt)
  g.add(roadV)

  // 路沿（四边）
  const curbGeom = new BoxGeometry(ROAD_LEN, 1.2, 1)
  const curbs = [
    [0, 0.2, ROAD_W / 2], [0, 0.2, -ROAD_W / 2],
    [ROAD_W / 2, 0.2, 0], [-ROAD_W / 2, 0.2, 0],
  ] as const
  for (const [x, y, z] of curbs) {
    const c = new Mesh(x !== 0 ? new BoxGeometry(1, 1.2, ROAD_LEN) : curbGeom, curbMat)
    c.position.set(x, y, z)
    if (x === 0) c.rotation.y = Math.PI / 2
    g.add(c)
  }

  // 车道线（三车道：左右各 3 条虚线）→ InstancedMesh 合批
  const dummy = new Matrix4()
  for (const dir of ['H', 'V'] as const) {
    const isH = dir === 'H'
    const laneGeom = isH ? new BoxGeometry(8, 1.15, 0.3) : new BoxGeometry(0.3, 1.15, 8)
    const dashPositions: Array<[number, number, number]> = []
    for (let pos = -60; pos <= 60; pos += 16) {
      for (let laneOff of [-LANE_W, 0, LANE_W]) {
        if (isH) dashPositions.push([pos, 0.4, laneOff])
        else     dashPositions.push([laneOff, 0.4, pos])
      }
    }
    const dashIM = new InstancedMesh(laneGeom, line, dashPositions.length)
    dashPositions.forEach(([x, y, z], i) => {
      dummy.identity().setPosition(x, y, z)
      dashIM.setMatrixAt(i, dummy)
    })
    dashIM.instanceMatrix.needsUpdate = true
    g.add(dashIM)

    // 中央双黄线
    const yellow = new MeshStandardMaterial({ color: 0xf5c842, emissive: 0x332800, emissiveIntensity: 0.3 })
    const yellowLine = new Mesh(isH ? new BoxGeometry(ROAD_LEN, 1.15, 0.4) : new BoxGeometry(0.4, 1.15, ROAD_LEN), yellow)
    g.add(yellowLine)
  }

  // 斑马线（仅四角人行横道）→ InstancedMesh 合批
  const zebraMat = new MeshStandardMaterial({ color: 0xffffff, roughness: 0.4 })
  const zebraPositions: [number, number, boolean][] = [
    [-(ROAD_W / 2 + 10), 0, true],  // 西侧
    [(ROAD_W / 2 + 10), 0, true],   // 东侧
    [0, -(ROAD_W / 2 + 10), false], // 南侧
    [0, (ROAD_W / 2 + 10), false],  // 北侧
  ]
  const zebraH: Array<[number, number, number]> = []
  const zebraV: Array<[number, number, number]> = []
  for (const [cx, cz, isH] of zebraPositions) {
    for (let i = -12; i <= 12; i += 4) {
      if (isH) zebraH.push([i, 0.55, cz])
      else     zebraV.push([cx, 0.55, i])
    }
  }
  if (zebraH.length > 0) {
    const zH = new InstancedMesh(new BoxGeometry(3, 0.3, ROAD_W + 6), zebraMat, zebraH.length)
    zebraH.forEach(([x, y, z], i) => { dummy.identity().setPosition(x, y, z); zH.setMatrixAt(i, dummy) })
    zH.instanceMatrix.needsUpdate = true
    g.add(zH)
  }
  if (zebraV.length > 0) {
    const zV = new InstancedMesh(new BoxGeometry(ROAD_W + 6, 0.3, 3), zebraMat, zebraV.length)
    zebraV.forEach(([x, y, z], i) => { dummy.identity().setPosition(x, y, z); zV.setMatrixAt(i, dummy) })
    zV.instanceMatrix.needsUpdate = true
    g.add(zV)
  }

  // 3D 红绿灯柱（四角，每个朝向对应道路）
  const tlConfigs: [number, number, number][] = [
    [-ROAD_W / 2 - 6, 0, -ROAD_W / 2 - 6], // 西南角
    [ROAD_W / 2 + 6, 0, -ROAD_W / 2 - 6],  // 东南角
    [-ROAD_W / 2 - 6, 0, ROAD_W / 2 + 6],  // 西北角
    [ROAD_W / 2 + 6, 0, ROAD_W / 2 + 6],   // 东北角
  ]
  const tlRotations: number[] = [
    0,            // 西南 → 面向东
    Math.PI,      // 东南 → 面向西
    -Math.PI / 2, // 西北 → 面向南
    Math.PI / 2,  // 东北 → 面向北
  ]
  const loader = new GLTFLoader()
  tlConfigs.forEach(([x, y, z], idx) => {
    loader.load(
      '/models/traffic-light.glb',
      (gltf) => {
        const model = gltf.scene
        model.position.set(x, y, z)
        model.scale.setScalar(12)
        model.rotation.set(0, tlRotations[idx]!, 0)
        g.add(model)
        console.log('[3D] traffic-light loaded at', x, z, 'rotation', tlRotations[idx])
      },
      undefined,
      (err) => console.error('[3D] GLB load error:', err),
    )
  })

  // 雷达（四角各一个）
  const radarGeom = new SphereGeometry(3, 16, 16, 0, Math.PI * 2, 0, Math.PI / 2)
  const radarMat = new MeshStandardMaterial({ color: 0x00d4ff, emissive: 0x005566, emissiveIntensity: 0.6, metalness: 0.5 })
  for (const [rx, rz] of [[-ROAD_W / 2, -ROAD_W / 2], [ROAD_W / 2, -ROAD_W / 2], [-ROAD_W / 2, ROAD_W / 2], [ROAD_W / 2, ROAD_W / 2]] as const) {
    const radar = new Mesh(radarGeom, radarMat)
    radar.position.set(rx, 22, rz)
    g.add(radar)
  }

  // 城市绿化树（沿路两侧各 4 棵）→ InstancedMesh 合批
  const trunkMat = new MeshStandardMaterial({ color: 0x5c4033, roughness: 0.8 })
  const leafMat = new MeshStandardMaterial({ color: 0x225533, roughness: 0.5 })
  const treeOffsets = [-65, -30, 30, 65]
  const trunkPositions: Array<[number, number, number]> = []
  const crownPositions: Array<[number, number, number]> = []
  for (const off of treeOffsets) {
    for (const side of [-1, 1]) {
      for (const axis of ['H', 'V'] as const) {
        const isH = axis === 'H'
        const px = isH ? off : side * (ROAD_W / 2 + 14)
        const pz = isH ? side * (ROAD_W / 2 + 14) : off
        trunkPositions.push([px, 3, pz])
        crownPositions.push([px, 9, pz])
      }
    }
  }
  const trunkIM = new InstancedMesh(new CylinderGeometry(0.8, 1.2, 6, 6), trunkMat, trunkPositions.length)
  trunkPositions.forEach(([x, y, z], i) => { dummy.identity().setPosition(x, y, z); trunkIM.setMatrixAt(i, dummy) })
  trunkIM.instanceMatrix.needsUpdate = true
  g.add(trunkIM)
  const crownIM = new InstancedMesh(new SphereGeometry(5, 8, 6), leafMat, crownPositions.length)
  crownPositions.forEach(([x, y, z], i) => { dummy.identity().setPosition(x, y, z); crownIM.setMatrixAt(i, dummy) })
  crownIM.instanceMatrix.needsUpdate = true
  g.add(crownIM)

  // 周边建筑（6 栋）
  const buildMat = new MeshStandardMaterial({ color: 0x14202e, emissive: 0x0a1520, emissiveIntensity: 0.4 })
  const buildings: [number, number, number, number][] = [
    [-85, 85, 36, 70], [85, 85, 44, 95], [-85, -85, 40, 55], [85, -85, 36, 80],
    [-85, -20, 30, 48], [85, 20, 32, 60],
  ]
  for (const [x, z, w, h] of buildings) {
    const b = new Mesh(new BoxGeometry(w, h, w), buildMat)
    b.position.set(x, h / 2, z)
    g.add(b)
  }

  return g
}

/**
 * 预留：加载真实 GLTF 模型。
 * 放入 public/models/{id}.glb 后，取消注释即可自动加载真实路口。
 */
function loadModel(id: string): void {
  // const loader = new GLTFLoader()
  // loader.load(
  //   `/models/${id}.glb`,
  //   (gltf) => { scene?.add(gltf.scene); modelFound.value = true; loading.value = false },
  //   undefined,
  //   () => { buildFallback(); loading.value = false },  // 加载失败 → 占位
  // )
  void id
  buildFallback()
}

let rootGroup: Group | null = null
let roadnetDebugGroup: Group | null = null
let roadnetSurfaceGroup: Group | null = null

function clearRoadnetDebugGeometry(): void {
  if (!roadnetDebugGroup) return
  roadnetDebugGroup.removeFromParent()
  roadnetDebugGroup.traverse((object) => {
    const line = object as Line
    line.geometry?.dispose()
    if (Array.isArray(line.material)) line.material.forEach((material) => material.dispose())
    else line.material?.dispose()
  })
  roadnetDebugGroup = null
}

function updateRoadnetDebugGeometry(): void {
  clearRoadnetDebugGeometry()
  if (!scene || !showRoadnetDebug.value || !intersection.value || !simRoadnet.value) return

  const intersectionId = `intersection_${simKeyOf(intersection.value)}`
  const centerlines = createLocalRoadCenterlines(simRoadnet.value, intersectionId)
  if (centerlines.length === 0) return

  const group = new Group()
  group.name = 'roadnet-debug-centerlines'
  for (const centerline of centerlines) {
    if (centerline.points.length < 2) continue
    const geometry = new BufferGeometry().setFromPoints(centerline.points)
    const material = new LineBasicMaterial({
      color: 0x00e5ff,
      transparent: true,
      opacity: 0.9,
      depthTest: false,
    })
    const line = new Line(geometry, material)
    line.name = `roadnet-${centerline.id}`
    line.renderOrder = 20
    group.add(line)
  }

  if (group.children.length === 0) return
  roadnetDebugGroup = group
  scene.add(group)
}

function toggleRoadnetDebug(): void {
  showRoadnetDebug.value = !showRoadnetDebug.value
}


function clearRoadnetSurfaceGeometry(): void {
  if (!roadnetSurfaceGroup) return
  roadnetSurfaceGroup.removeFromParent()
  roadnetSurfaceGroup.traverse((object) => {
    const drawable = object as Mesh | Line
    drawable.geometry?.dispose()
    if (Array.isArray(drawable.material)) drawable.material.forEach((material) => material.dispose())
    else drawable.material?.dispose()
  })
  roadnetSurfaceGroup = null
}

function updateRoadnetSurfaceGeometry(): void {
  clearRoadnetSurfaceGeometry()
  if (!scene || !showRoadnetSurface.value || !intersection.value || !simRoadnet.value) return

  const intersectionId = `intersection_${simKeyOf(intersection.value)}`
  const segments = createLocalRoadSurfaceSegments(simRoadnet.value, intersectionId, 120, 0.7)
  const laneLinks = createLocalLaneLinkPaths(simRoadnet.value, intersectionId, 0.9)
  if (segments.length === 0 && laneLinks.length === 0) return

  const group = new Group()
  group.name = 'roadnet-surface-overlay'
  for (const segment of segments) {
    const material = new MeshStandardMaterial({
      color: segment.usesRoadnetLaneWidths ? 0x0284c7 : 0xd97706,
      transparent: true,
      opacity: 0.32,
      roughness: 0.85,
      metalness: 0,
      depthWrite: false,
    })
    const mesh = new Mesh(new BoxGeometry(segment.width, 0.18, segment.length), material)
    mesh.name = `roadnet-surface-${segment.roadId}`
    mesh.position.copy(segment.center)
    mesh.rotation.y = segment.rotationY
    mesh.renderOrder = 10
    group.add(mesh)
  }

  const laneLinkColors: Record<string, number> = {
    go_straight: 0x22d3ee,
    turn_left: 0xf59e0b,
    turn_right: 0xa78bfa,
  }
  for (const laneLink of laneLinks) {
    const geometry = new BufferGeometry().setFromPoints(laneLink.points)
    const material = new LineBasicMaterial({
      color: laneLinkColors[laneLink.type] ?? 0xffffff,
      transparent: true,
      opacity: 0.85,
      depthTest: false,
    })
    const line = new Line(geometry, material)
    line.name = `lane-link-${laneLink.roadLinkIndex}-${laneLink.startLaneIndex}-${laneLink.endLaneIndex}`
    line.renderOrder = 21
    group.add(line)
  }

  roadnetSurfaceGroup = group
  scene.add(group)
}

function toggleRoadnetSurface(): void {
  showRoadnetSurface.value = !showRoadnetSurface.value
}

watch([intersection, simRoadnet, showRoadnetDebug], updateRoadnetDebugGeometry)
watch([intersection, simRoadnet, showRoadnetSurface], updateRoadnetSurfaceGeometry)

function buildFallback(): void {
  if (!scene) return
  rootGroup = new Group()
  scene.add(rootGroup)
  modelFound.value = false

  // 加载用户编辑的场景 GLB
  const sceneLoader = new GLTFLoader()
  sceneLoader.load(
    '/models/scene.glb',
    (gltf) => {
      gltf.scene.position.set(0, 0, 0)
      rootGroup!.add(gltf.scene)
      modelFound.value = true
      console.log("[3D] scene.glb loaded")
      // ---- remove ALL baked-in lane dashes from scene.glb ----
      const toRemove: any[] = []
      gltf.scene.traverse((child) => {
        const name: string = (child as any).name || ""
        // Exact: mesh_5 / mesh_5_instance_*  (NOT mesh_50-59)
        if (name === "mesh_5" || name.startsWith("mesh_5_instance")) toRemove.push(child)
        // Exact: mesh_7 / mesh_7_instance_*  (NOT mesh_70-75)
        if (name === "mesh_7" || name.startsWith("mesh_7_instance")) toRemove.push(child)
        // Yellow center lines baked in model
        if (name === "mesh_6" || name === "mesh_8") toRemove.push(child)
      })
      toRemove.forEach((c) => { c.parent.remove(c) })
      console.log("[3D] removed", toRemove.length, "baked-in lane dashes")
      // ---- end ----
      loading.value = false

      // 在 Blender 标记的 tl_label_* 空物体上挂倒计时 Sprite
      const tlSprites: Sprite[] = []
      const tlLabels = ['tl_label_0', 'tl_label_1', 'tl_label_2', 'tl_label_3']
      gltf.scene.traverse((child) => {
        const name = (child as any).name || ''
        const idx = tlLabels.findIndex(l => name.includes(l))
        if (idx >= 0) {
          const canvas = document.createElement('canvas')
          canvas.width = 64; canvas.height = 48
          const ctx = canvas.getContext('2d')!
          const tex = new CanvasTexture(canvas)
          tex.minFilter = LinearFilter
          const sprite = new Sprite(new SpriteMaterial({ map: tex, transparent: true, depthTest: false, depthWrite: false }))
          sprite.scale.set(6, 4, 1)
          sprite.userData = { canvas, ctx, idx: tlLabels.findIndex(l => name.includes(l)) }
          child.add(sprite)
          tlSprites.push(sprite)
        }
      })

      let lastTLSpriteState = ''
      function updateTLSprite() {
        const it = intersection.value
        if (!it) return
        const isEW = it.currentPhase.startsWith('eastwest')
        const remaining = simulationStatus.value === 'running'
          ? simState.value?.greenRemain ?? null
          : it.greenRemain
        const allRed = it.currentPhase === 'all_red' || it.deviceStatus !== 'online'
        const roundedRemaining = remaining === null ? null : Math.round(remaining)
        const spriteState = `${it.currentPhase}|${it.deviceStatus}|${roundedRemaining ?? 'unknown'}`
        if (spriteState === lastTLSpriteState) return
        lastTLSpriteState = spriteState
        // SW/SE=东西向, NW/NE=南北向
        const ewSet = new Set([0, 1])
        for (const s of tlSprites) {
          const idx = s.userData.idx as number
          const isActiveDir = ewSet.has(idx) ? isEW : !isEW
          const color = allRed ? '#FF4D6D' : isActiveDir ? '#22D3A0' : '#FF4D6D'
          const c = s.userData.canvas as HTMLCanvasElement
          const ctx = c.getContext('2d')!
          ctx.clearRect(0, 0, c.width, c.height)
          ctx.fillStyle = color
          ctx.beginPath(); ctx.arc(32, 24, 16, 0, Math.PI * 2); ctx.fill()
          ctx.fillStyle = '#fff'
          ctx.font = 'bold 16px Rajdhani, sans-serif'
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
          if (roundedRemaining !== null) ctx.fillText(String(roundedRemaining), 32, 22)
          ;(s.material as SpriteMaterial).map!.needsUpdate = true
        }
      }
      updateTLSprite()
      tlSpriteUpdaters.push(updateTLSprite)
      // ---- ??????????????? ----
      const dashMat = new MeshStandardMaterial({ color: 0xffffff, emissive: 0x222222, emissiveIntensity: 0.3 })
      const m4 = new Matrix4()
      const STOP_AT = 46  // ?????????
      for (const dir of ["H", "V"] as const) {
        const isH = dir === "H"
        const dGeom = isH ? new BoxGeometry(12, 1.15, 0.4) : new BoxGeometry(0.4, 1.15, 12)
        const dPos: Array<[number, number, number]> = []
        for (let pos = -70; pos <= -(STOP_AT + 8); pos += 18) {
          for (const offset of [-12, -6, 6, 12]) {
            if (isH) dPos.push([pos, 0.5, offset])
            else     dPos.push([offset, 0.5, pos])
          }
        }
        for (let pos = STOP_AT + 8; pos <= 70; pos += 18) {
          for (const offset of [-12, -6, 6, 12]) {
            if (isH) dPos.push([pos, 0.5, offset])
            else     dPos.push([offset, 0.5, pos])
          }
        }
        const dIM = new InstancedMesh(dGeom, dashMat, dPos.length)
        dPos.forEach(([x, y, z], i) => { m4.identity().setPosition(x, y, z); dIM.setMatrixAt(i, m4) })
        dIM.instanceMatrix.needsUpdate = true
        rootGroup!.add(dIM)
        // Extra shorter dashes between stop line (22) and main dashes (52/54)
        const shortGeom = isH ? new BoxGeometry(8, 1.15, 0.4) : new BoxGeometry(0.4, 1.15, 8)
        const extraPos: Array<[number, number, number]> = []
        for (const pos of [-40, -28, 28, 40]) {
          for (const offset of [-12, -6, 6, 12]) {
            if (isH) extraPos.push([pos, 0.5, offset])
            else     extraPos.push([offset, 0.5, pos])
          }
        }
        const extraIM = new InstancedMesh(shortGeom, dashMat, extraPos.length)
        extraPos.forEach(([x, y, z], i) => { m4.identity().setPosition(x, y, z); extraIM.setMatrixAt(i, m4) })
        extraIM.instanceMatrix.needsUpdate = true
        rootGroup!.add(extraIM)
      }

      // ---- ?????????????? ----
      // ??????????????????????
      const stopLineMat = new MeshStandardMaterial({ color: 0xffffff, emissive: 0x333333, emissiveIntensity: 0.4, roughness: 0.4 })
      const STOP_LINE = 22
      const HALF_W = 20
      // [x, z, ??????, ???? z ????????]
      // ????????????? x=-22???? z<0????
      //               ???? x=+22???? z>0????
      // ????????????? z=-22???? x>0????
      //               ???? z=+22???? x<0????
      const stopPos: Array<[number, number, number, number, number]> = [
        [-STOP_LINE, 0.4, 0, 1, HALF_W],       // ?????????z=+1 ~ +20   // ????z ? -20 ? -1??????
        [STOP_LINE, 0.4, 0, -HALF_W, -1],      // ?????????z=-20 ~ -1       // ????z ? 1 ? 20??????
        [0, 0.4, -STOP_LINE, -HALF_W, -1],     // ?????????x=-20 ~ -1      // ????x ? 1 ? 20??????
        [0, 0.4, STOP_LINE, 1, HALF_W],        // ?????????x=+1 ~ +20     // ????x ? -20 ? -1??????
      ]
      for (const [sx, sy, sz, zStart, zEnd] of stopPos) {
        const isH = sz === 0
        const span = Math.abs(zEnd - zStart)
        const mid = (zStart + zEnd) / 2
        const geom = isH ? new BoxGeometry(0.6, 1.2, span) : new BoxGeometry(span, 1.2, 0.6)
        const sl = new Mesh(geom, stopLineMat)
        if (isH) sl.position.set(sx, sy, mid)
        else sl.position.set(mid, sy, sz)
        rootGroup!.add(sl)
      }

      // ---- ???????????????????----
      const yellowMat = new MeshStandardMaterial({ color: 0xf5c842, emissive: 0x8a6d00, emissiveIntensity: 0.2, roughness: 0.5 })
      const Y_LEN = 48      // ????(22)??????(70)
      const Y_FROM = 47     // ?????????? 23 ~ 71
      for (const xSign of [1, -1]) {
        const yl = new Mesh(new BoxGeometry(Y_LEN, 1.2, 0.7), yellowMat)
        yl.position.set(xSign * Y_FROM, 0.4, 0)
        rootGroup!.add(yl)
      }
      for (const zSign of [1, -1]) {
        const yl = new Mesh(new BoxGeometry(0.7, 1.2, Y_LEN), yellowMat)
        yl.position.set(0, 0.4, zSign * Y_FROM)
        rootGroup!.add(yl)
      }
      // ---- lane direction arrows (on exit side of each approach) ----
      function drawArrow(type: string): CanvasTexture {
        const c = document.createElement("canvas")
        c.width = 128; c.height = 192
        const ctx = c.getContext("2d")!
        ctx.fillStyle = "#ffffff"
        // Canvas 128x192, shaft centered at x=64
        const sx = 52, sw = 24        // shaft: x=52..76, width 24
        const sh_y = 96, sh_h = 88    // shaft: y=96..184
        if (type === "straight") {
          // shaft (shifted right 6px)
          ctx.fillRect(sx + 34, sh_y, sw, sh_h)
          // triangle head: base at y=96, tip at top
          ctx.beginPath(); ctx.moveTo(68, 96); ctx.lineTo(128, 96); ctx.lineTo(98, 30); ctx.fill()
        } else if (type === "left") {
          // shaft
          ctx.fillRect(sx, sh_y, sw, sh_h)
          // horizontal arm extending LEFT from shaft top
          ctx.fillRect(10, 66, 66, 30)
          // triangle pointing left
          ctx.beginPath(); ctx.moveTo(4, 81); ctx.lineTo(28, 48); ctx.lineTo(28, 114); ctx.fill()
        } else {
          // shaft
          ctx.fillRect(sx, sh_y, sw, sh_h)
          // horizontal arm extending RIGHT from shaft top
          ctx.fillRect(52, 66, 66, 30)
          // triangle pointing right
          ctx.beginPath(); ctx.moveTo(124, 81); ctx.lineTo(100, 48); ctx.lineTo(100, 114); ctx.fill()
        }
        const tex = new CanvasTexture(c)
        tex.flipY = false
        tex.minFilter = LinearFilter
        tex.magFilter = LinearFilter
        tex.needsUpdate = true
        return tex
      }
      
      const arrowDefs: Array<{x: number, z: number, rot: number, type: string}> = []
      const AD = 28
      const LC = [4, 11, 17]
      const getType = (lc: number) => lc === 4 ? "right" : lc === 17 ? "left" : "straight"
      // West->East: arrows on EAST exit side, south half of road (z negative)
      for (const lc of LC) arrowDefs.push({x: AD, z: -lc, rot: -Math.PI/2, type: getType(lc)})
      // East->West: arrows on WEST exit side, north half (z positive)
      for (const lc of LC) arrowDefs.push({x: -AD, z: lc, rot: Math.PI/2, type: getType(lc)})
      // South->North: arrows on NORTH exit side, east half (x positive)
      for (const lc of LC) arrowDefs.push({x: lc, z: AD, rot: Math.PI, type: getType(lc)})
      // North->South: arrows on SOUTH exit side, west half (x negative)
      for (const lc of LC) arrowDefs.push({x: -lc, z: -AD, rot: 0, type: getType(lc)})
      
      for (const ad of arrowDefs) {
        const tex = drawArrow(ad.type)
        const ap = new Mesh(
          new PlaneGeometry(4, 6),
          new MeshStandardMaterial({ map: tex, transparent: true, depthWrite: false, roughness: 0.5 })
        )
        ap.rotation.x = -Math.PI / 2
        ap.rotation.z = ad.rot
        ap.position.set(ad.x, 0.55, ad.z)
        rootGroup!.add(ap)
      }
      // ---- end arrows ----
    },
    undefined,
    (err) => {
      console.error('[3D] scene.glb load error:', err)
      modelFound.value = false
      rootGroup?.add(buildProceduralIntersection())
      loading.value = false
    },
  )
}

function exportToGLB(): void {
  if (!rootGroup) return
  const exporter = new GLTFExporter()
  exporter.parse(
    rootGroup,
    (result) => {
      const blob = new Blob([result as ArrayBuffer], { type: 'application/octet-stream' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'intersection.glb'
      a.click()
      URL.revokeObjectURL(url)
    },
    (err) => console.error('[3D] export error:', err),
    { binary: true },
  )
}

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape') handleClose()
}

onMounted(async () => {
  window.addEventListener('keydown', onKeyDown)
  vehicleAnimator = new IntersectionVehicleAnimator(props.intersectionId ?? '')
  await vehicleAnimator.preload()
  console.log('[Vehicle] preload done, templates:', vehicleAnimator['templates']?.size ?? 0)
  if (!viewerBox.value) return
  const w = viewerBox.value.clientWidth
  const h = viewerBox.value.clientHeight

  scene = new Scene()
  scene.background = new Color('#020817')
  scene.fog = new Fog('#020817', 300, 700)

  camera = new PerspectiveCamera(55, w / h, 0.5, 3000)
  camera.position.set(0, 190, 210)
  camera.lookAt(0, 0, 0)

  renderer = new WebGLRenderer({ antialias: false, powerPreference: 'high-performance' })
  renderer.setPixelRatio(1)
  renderer.setSize(w, h)
  viewerBox.value.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)

  if (vehicleAnimator) scene!.add(vehicleAnimator.group)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.minDistance = 60
  controls.maxDistance = 500
  controls.maxPolarAngle = Math.PI / 2.1

  scene.add(new AmbientLight(0x8fadc4, 1.2))
  const dir = new DirectionalLight(0xffffff, 1.3)
  dir.position.set(120, 200, 160)
  scene.add(dir)

  const grid = new GridHelper(600, 30, new Color('#0a2540'), new Color('#0a2540'))
  const gm = grid.material as { opacity: number; transparent: boolean }
  gm.opacity = 0.3
  gm.transparent = true
  grid.position.y = -1
  scene.add(grid)

  // 加载模型（当前走占位）
  if (props.intersectionId) loadModel(props.intersectionId)
  else { buildFallback(); loading.value = false }
  updateRoadnetDebugGeometry()
  updateRoadnetSurfaceGeometry()

  // 渲染循环
  const loop = (now: number) => {
    rafId = requestAnimationFrame(loop)
    if (now - lastRenderTime < TARGET_RENDER_INTERVAL_MS) return
    lastRenderTime = now
    const deltaMs = lastFrameTime ? now - lastFrameTime : 16
    lastFrameTime = now
    controls?.update()
    tlSpriteUpdaters.forEach((fn) => fn())
    if (vehicleAnimator && intersection.value) {
      vehicleAnimator.setIntersection(intersection.value)
      if (simulationStatus.value === 'running' && simRoadnet.value) {
        const cityFlowIntersectionId = `intersection_${simKeyOf(intersection.value)}`
        vehicleAnimator.updateFromCityFlow(
          simulationVehicles.value,
          simRoadnet.value,
          cityFlowIntersectionId,
          now,
        )
      } else if (simState.value) {
        // roadnet 尚未就绪时保留按方向数量驱动的降级模式
        vehicleAnimator.updateFromSim(simState.value, deltaMs)
      } else {
        // 非实时仿真：使用演示车辆数据
        vehicleAnimator.update(vehicles.value, roads.value)
      }
    }
    if (renderer && scene && camera) renderer.render(scene, camera)
  }
  rafId = requestAnimationFrame(loop)

  resizeObserver = new ResizeObserver(() => {
    if (!viewerBox.value || !camera || !renderer) return
    const nw = viewerBox.value.clientWidth
    const nh = viewerBox.value.clientHeight
    if (nw === 0 || nh === 0) return
    camera.aspect = nw / nh
    camera.updateProjectionMatrix()
    renderer.setSize(nw, nh)
  })
  resizeObserver.observe(viewerBox.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  if (rafId !== null) cancelAnimationFrame(rafId)
  clearRoadnetDebugGeometry()
  clearRoadnetSurfaceGeometry()
  vehicleAnimator?.dispose()
  resizeObserver?.disconnect()
  controls?.dispose()
  scene?.traverse((o) => {
    const m = o as Mesh
    if (m.geometry) m.geometry.dispose()
    if (m.material) {
      const mat = m.material
      if (Array.isArray(mat)) mat.forEach((x) => x.dispose())
      else mat.dispose()
    }
  })
  renderer?.dispose()
  if (renderer && viewerBox.value?.contains(renderer.domElement)) {
    viewerBox.value.removeChild(renderer.domElement)
  }
  scene = null
  camera = null
  renderer = null
  controls = null
})

function handleClose(): void {
  emit('close')
}

function onOverlayClick(ev: MouseEvent): void {
  if (ev.target === ev.currentTarget) handleClose()
}
</script>

<template>
  <Teleport to="body">
    <div class="i3d-overlay" @click="onOverlayClick">
    <div class="i3d-modal">
      <!-- 标题栏 -->
      <div class="i3d-header">
        <div class="i3d-header__left">
          <span class="i3d-header__mark" />
          <div>
            <div class="i3d-header__title">
              {{ intersection?.name ?? '路口' }} · 三维全景
            </div>
            <div class="i3d-header__sub">
              {{ modelFound ? '通用 GLTF 路口场景' : '程序化通用路口场景' }}
            </div>
          </div>
          <span class="i3d-mode" :class="{ 'i3d-mode--warning': simulationErrorMessage }">{{ dataModeLabel }}</span>
        </div>
        <div class="i3d-header__actions">
<button
  class="i3d-export"
  :class="{ 'i3d-export--inactive': !showRoadnetDebug }"
  title="叠加显示 CityFlow 路网中心线"
  @click="toggleRoadnetDebug"
>🧭 路网线</button>
<button
  class="i3d-export"
  :class="{ 'i3d-export--inactive': !showRoadnetSurface }"
  title="叠加显示 CityFlow 真实车道宽度与 laneLink 轨迹"
  @click="toggleRoadnetSurface"
>&#128739; 路网路面</button>
<button class="i3d-export" @click="exportToGLB">📦 导出 GLB</button>
<button class="i3d-close" @click="handleClose">✕</button>
      </div>

      <!-- 三维视口 -->
      </div>
      <div class="i3d-body">
        <div ref="viewerBox" class="i3d-canvas" />
        <div v-if="loading" class="i3d-loading">加载三维实景中…</div>

        <!-- 右下角路口信息 -->
        <div v-if="intersection" class="i3d-info">
          <span class="i3d-info__item">相位 <b class="text-cyan">{{ PHASE_LABELS[intersection.currentPhase] }}</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">相位剩余 <b class="text-emerald">{{ displayedRemainingSec === null ? '—' : `${Math.round(displayedRemainingSec)}s` }}</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">排队 <b class="text-amber">{{ intersection.queueLength }}辆</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">设备 <b>{{ DEVICE_STATUS_LABELS[intersection.deviceStatus] }}</b></span>
        </div>

        <div class="i3d-hint">🖱 拖拽旋转 · 滚轮缩放 · 点击空白关闭</div>
      </div>
    </div>
    </div>
  </Teleport>
</template>

<style scoped>
.i3d-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(2, 8, 23, 0.96);
  animation: i3d-fade 0.2s ease;
}

@keyframes i3d-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

.i3d-modal {
  width: 88vw;
  height: 86vh;
  display: flex;
  flex-direction: column;
  background: rgba(4, 21, 39, 0.96);
  border: 1.5px solid rgba(0, 212, 255, 0.5);
  box-shadow: 0 0 60px rgba(0, 212, 255, 0.25);
  clip-path: polygon(14px 0, 100% 0, 100% calc(100% - 14px), calc(100% - 14px) 100%, 0 100%, 0 14px);
}

.i3d-header {
  position: relative;
  z-index: 20;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(0, 212, 255, 0.28);
}

.i3d-header__left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.i3d-header__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
}

.i3d-header__mark {
  width: 6px;
  height: 34px;
  transform: skewX(-18deg);
  background: linear-gradient(180deg, #7af7ff, #00d4ff 50%, #034d7a);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.9);
}

.i3d-header__title {
  font-size: 20px;
  font-weight: 700;
  color: #e8f4ff;
  text-shadow: 0 0 14px rgba(0, 212, 255, 0.4);
}

.i3d-header__sub {
  margin-top: 2px;
  font-size: 12px;
  color: #5a7595;
}

.i3d-export {
  height: 34px;
  padding: 0 10px;
  font-size: 12px;
  color: #22d3a0;
  background: transparent;
  border: 1px solid rgba(34, 211, 160, 0.4);
  cursor: pointer;
  transition: all 0.2s ease;
  margin-right: 8px;
}

.i3d-export:hover {
  background: rgba(34, 211, 160, 0.12);
  border-color: rgba(34, 211, 160, 0.7);
}

.i3d-export--inactive {
  color: #64748b;
  border-color: rgba(100, 116, 139, 0.35);
  opacity: 0.72;
}

.i3d-mode {
  margin-left: 12px;
  padding: 3px 8px;
  border: 1px solid rgba(34, 211, 160, 0.45);
  border-radius: 999px;
  color: #22d3a0;
  font-size: 11px;
}

.i3d-mode--warning {
  border-color: rgba(255, 184, 0, 0.5);
  color: #ffb800;
}

.i3d-close {
  position: relative;
  z-index: 10;
  width: 34px;
  height: 34px;
  font-size: 16px;
  color: #8da8c5;
  background: transparent;
  border: 1px solid rgba(0, 212, 255, 0.3);
  cursor: pointer;
  transition: all 0.2s ease;
}

.i3d-close:hover {
  color: #ff4d6d;
  border-color: rgba(255, 77, 109, 0.5);
  background: rgba(255, 77, 109, 0.1);
}

.i3d-body {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: visible;
  position: relative;
}

.i3d-canvas {
  width: 100%;
  height: 100%;
}

.i3d-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 14px;
  color: #00d4ff;
  letter-spacing: 0.1em;
}

.i3d-info {
  position: absolute;
  left: 12px;
  top: 52px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: rgba(4, 21, 39, 0.85);
  border: 1px solid rgba(0, 212, 255, 0.35);
  backdrop-filter: blur(6px);
  font-size: 11px;
  color: #8da8c5;
  font-family: 'Rajdhani', sans-serif;
  white-space: nowrap;
}

.i3d-info b { font-size: 12px; }

.i3d-info__sep { color: rgba(0,212,255,0.25); }

.i3d-hint {
  position: absolute;
  left: 16px;
  bottom: 16px;
  font-size: 11px;
  color: #5a7595;
  padding: 4px 10px;
  background: rgba(4, 21, 39, 0.7);
  border: 1px solid rgba(0, 212, 255, 0.18);
  pointer-events: none;
}

@media (max-width: 768px) {
  .i3d-modal {
    width: calc(100vw - 16px);
    height: calc(100vh - 16px);
  }

  .i3d-header {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
    padding: 10px;
  }

  .i3d-header__mark,
  .i3d-header__sub {
    display: none;
  }

  .i3d-header__left {
    gap: 6px;
  }

  .i3d-header__title {
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .i3d-mode {
    flex: 0 0 auto;
    margin-left: auto;
    padding: 2px 6px;
    font-size: 10px;
  }

  .i3d-header__actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr)) 32px;
    gap: 6px;
  }

  .i3d-export {
    width: 100%;
    height: 32px;
    margin-right: 0;
    overflow: hidden;
    padding: 0 4px;
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .i3d-close {
    width: 32px;
    height: 32px;
  }

  .i3d-info {
    top: 8px;
    right: 8px;
    left: 8px;
    flex-wrap: wrap;
    padding: 4px 6px;
    font-size: 10px;
    white-space: normal;
  }

  .i3d-hint {
    right: 8px;
    bottom: 8px;
    left: 8px;
    overflow: hidden;
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
