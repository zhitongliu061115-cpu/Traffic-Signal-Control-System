<script setup lang="ts">
// ================================================================
// Intersection3DViewer — 路口三维实景视图（全屏弹窗）
//
// 当前：程序化几何占位（道路/斑马线/信号灯杆/摄像头/建筑）
// 预留：GLTFLoader 加载真实路口模型 modelUrl = `/models/{id}.glb`
//       接口已就绪，放入 .glb 即可自动加载（见 loadModel 注释）
// ================================================================
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
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
} from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js'
import { useTrafficStore } from '@/stores/traffic'
import { IntersectionVehicleAnimator, type IntersectionSimState } from '@/three/IntersectionVehicleAnimator'
import { PHASE_LABELS, DEVICE_STATUS_LABELS } from '@/types/traffic'
import type { SignalPhase, SimVehicleState, SimSignalState, SimRoadnetResponse, Intersection } from '@/types/traffic'

const props = defineProps<{ intersectionId: string | null }>()
const emit = defineEmits<{ close: [] }>()

const store = useTrafficStore()
const { intersections, roads, vehicles, simulationVehicles, simulationSignals, simulationSimTime, simRoadnet, simulationStatus } = storeToRefs(store)

const viewerBox = ref<HTMLDivElement | null>(null)
const loading = ref(true)
const modelFound = ref(false)

const intersection = computed(() =>
  intersections.value.find((it) => it.id === props.intersectionId) ?? null,
)

// ================================================================
// 从仿真帧派生选中路口的四方向车辆数 + 相位（Plan B 数据源）
// ================================================================
const SIGNAL_PHASE_MAP: Record<string, SignalPhase> = {
  ETWT: 'eastwest_straight', ew_straight: 'eastwest_straight',
  NTST: 'northsouth_straight', ns_straight: 'northsouth_straight',
  ELWL: 'eastwest_left', ew_left: 'eastwest_left',
  NLSL: 'northsouth_left', ns_left: 'northsouth_left',
  all_red: 'all_red',
}

/** 上海路口 → CityFlow 转置键 "R_C"（R=col, C=row） */
function simKeyOf(it: Intersection): string {
  return `${it.col}_${it.row}`
}

function deriveIntersectionState(
  shIt: Intersection | null,
  simVehicles: SimVehicleState[],
  simSignals: SimSignalState[],
  roadnet: SimRoadnetResponse | null,
  simTime: number,
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
  const currentPhase = sig ? (SIGNAL_PHASE_MAP[sig.phaseCode] ?? 'eastwest_straight') : 'eastwest_straight'
  const greenRemain = 10 - (simTime % 10)

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
    simulationSimTime.value,
  )
})

// ---- Three.js 局部实例 ----
let scene: Scene | null = null
let camera: PerspectiveCamera | null = null
let renderer: WebGLRenderer | null = null
let controls: OrbitControls | null = null
let rafId: number | null = null
let resizeObserver: ResizeObserver | null = null
let lastFrameTime = 0
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
  loading.value = false
}

let rootGroup: Group | null = null

function buildFallback(): void {
  if (!scene) return
  rootGroup = new Group()
  scene.add(rootGroup)
  modelFound.value = true

  // 加载用户编辑的场景 GLB
  const sceneLoader = new GLTFLoader()
  sceneLoader.load(
    '/models/scene.glb',
    (gltf) => {
      gltf.scene.position.set(0, 0, 0)
      rootGroup!.add(gltf.scene)
      console.log("[3D] scene.glb loaded")
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

      function updateTLSprite() {
        const it = intersection.value
        if (!it) return
        const isEW = it.currentPhase.startsWith('eastwest')
        const rem = Math.round(it.greenRemain)
        const allRed = it.currentPhase === 'all_red' || it.deviceStatus !== 'online'
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
          ctx.fillText(String(rem), 32, 22)
          ;(s.material as SpriteMaterial).map!.needsUpdate = true
        }
      }
      updateTLSprite()
      tlSpriteUpdaters.push(updateTLSprite)
      const dashMat = new MeshStandardMaterial({ color: 0xffffff, emissive: 0x222222, emissiveIntensity: 0.3 })
      const m4 = new Matrix4()
      for (const dir of ["H", "V"] as const) {
        const isH = dir === "H"
        const dGeom = isH ? new BoxGeometry(12, 1.15, 0.4) : new BoxGeometry(0.4, 1.15, 12)
        const dPos: Array<[number, number, number]> = []
        for (let pos = -70; pos <= 70; pos += 18) {
          for (const offset of [-12, -6, 6, 12]) {
            if (isH) dPos.push([pos, 0.5, offset])
            else     dPos.push([offset, 0.5, pos])
          }
        }
        const dIM = new InstancedMesh(dGeom, dashMat, dPos.length)
        dPos.forEach(([x, y, z], i) => { m4.identity().setPosition(x, y, z); dIM.setMatrixAt(i, m4) })
        dIM.instanceMatrix.needsUpdate = true
        rootGroup!.add(dIM)
      }
    },
    undefined,
    (err) => { console.error('[3D] scene.glb load error:', err); modelFound!.value = false },
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
  camera.position.set(120, 130, 150)
  camera.lookAt(0, 0, 0)

  renderer = new WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
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

  // 渲染循环
  const loop = (now: number) => {
    const deltaMs = lastFrameTime ? now - lastFrameTime : 16
    lastFrameTime = now
    controls?.update()
    tlSpriteUpdaters.forEach((fn) => fn())
    if (vehicleAnimator && intersection.value) {
      vehicleAnimator.setIntersection(intersection.value)
      if (simState.value) {
        // 仿真运行中：按四方向车辆数 + 相位驱动车流
        vehicleAnimator.updateFromSim(simState.value, deltaMs)
      } else {
        // 降级：mock 车辆数据
        vehicleAnimator.update(vehicles.value, roads.value)
      }
    }
    if (renderer && scene && camera) renderer.render(scene, camera)
    rafId = requestAnimationFrame(loop)
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
  <div class="i3d-overlay" @click="onOverlayClick">
    <div class="i3d-modal">
      <!-- 标题栏 -->
      <div class="i3d-header">
        <div class="i3d-header__left">
          <span class="i3d-header__mark" />
          <div>
            <div class="i3d-header__title">
              {{ intersection?.name ?? '路口' }} · 三维实景
            </div>
            <div class="i3d-header__sub">
              {{ modelFound ? 'GLTF 真实路口模型' : '程序化实景占位（放入 .glb 自动加载）' }}
            </div>
          </div>
        </div>
<button class="i3d-close" @click="handleClose">✕</button>
      </div>

      <!-- 三维视口 -->
      <div class="i3d-body">
        <div ref="viewerBox" class="i3d-canvas" />
        <div v-if="loading" class="i3d-loading">加载三维实景中…</div>

        <!-- 右下角路口信息 -->
        <div v-if="intersection" class="i3d-info">
          <span class="i3d-info__item">相位 <b class="text-cyan">{{ PHASE_LABELS[intersection.currentPhase] }}</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">绿灯 <b class="text-emerald">{{ Math.round(intersection.greenRemain) }}s</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">排队 <b class="text-amber">{{ intersection.queueLength }}辆</b></span>
          <span class="i3d-info__sep">|</span>
          <span class="i3d-info__item">设备 <b>{{ DEVICE_STATUS_LABELS[intersection.deviceStatus] }}</b></span>
        </div>

        <div class="i3d-hint">🖱 拖拽旋转 · 滚轮缩放 · 点击空白关闭</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.i3d-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(2, 8, 23, 0.82);
  backdrop-filter: blur(6px);
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
</style>
