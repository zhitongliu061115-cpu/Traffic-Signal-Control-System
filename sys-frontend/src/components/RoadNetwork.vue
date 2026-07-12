<script setup lang="ts">
// 直接搬运 temp-three-frontend/src/main.js 核心渲染逻辑
import { ref, watch, onMounted, onBeforeUnmount, computed } from 'vue'
import { storeToRefs } from 'pinia'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useTrafficStore } from '@/stores/traffic'
import type { SimRoadnetResponse, SimVehicleState } from '@/types/traffic'

defineProps<{ compact?: boolean }>()

const store = useTrafficStore()
const { simulationStatus, simRoadnet, simulationVehicles, simulationRoads, simulationSignals } = storeToRefs(store)

// ---- 常量（与 temp-three-frontend 一致）----
const LANE_WIDTH = 10; const ROAD_SHOULDER = 4
const VISUAL_LANES_PER_DIRECTION = 3; const MEDIAN_GAP = 7
function roadWidth(lanes: number) { return lanes * LANE_WIDTH + ROAD_SHOULDER * 2 }
function visualLaneCount() { return VISUAL_LANES_PER_DIRECTION }
function visualRoadWidth() { return roadWidth(visualLaneCount()) }
function laneOffset(laneIndex: number, laneCount: number) {
  const lane = Math.max(0, Math.min(Number(laneIndex) || 0, Math.max(0, laneCount - 1)))
  return (lane - (Math.max(1, laneCount) - 1) / 2) * LANE_WIDTH
}
function visualRoadOffset(road: any) {
  if (!road || !road.points || road.points.length < 2) return new THREE.Vector3()
  const pts = road.points.map(worldPoint)
  const d = new THREE.Vector3().subVectors(pts[pts.length - 1], pts[0]).setY(0)
  if (d.lengthSq() < 0.001) return new THREE.Vector3()
  d.normalize()
  return new THREE.Vector3(-d.z, 0, d.x).normalize().multiplyScalar(visualRoadWidth() / 2 + MEDIAN_GAP / 2)
}
function visualRoadPoints(road: any) {
  const offset = visualRoadOffset(road)
  return road.points.map((p: any) => worldPoint(p).add(offset))
}

const levelColors: Record<string, number> = {
  free: 0x33d17a, slow: 0xffb020, jammed: 0xff4d5a, unknown: 0x7f8c9a,
}
const unknownLevelColor = levelColors.unknown!

// ---- 坐标 ----
function worldPoint(p: { x: number; y: number }) {
  return new THREE.Vector3(p.x, 0, -p.y)
}

// ---- DOM 引用 ----
const canvasBox = ref<HTMLDivElement | null>(null)
const simActive = computed(() => simulationStatus.value === 'running' && simRoadnet.value !== null)

// ---- Three.js 实例 ----
let renderer: THREE.WebGLRenderer
let scene: THREE.Scene
let camera: THREE.OrthographicCamera
let controls: OrbitControls
let rafId = 0

let root: THREE.Group
let roadGroup: THREE.Group
let intersectionGroup: THREE.Group
let signalGroup: THREE.Group
let vehicleGroup: THREE.Group

let roadnet: SimRoadnetResponse | null = null
const roadMeshesById = new Map<string, THREE.Mesh[]>()
const roadById = new Map<string, any>()
const intersectionById = new Map<string, any>()
const vehiclesById = new Map<string, { mesh: THREE.Group; from: THREE.Vector3; to: THREE.Vector3; startAt: number; roadId: string }>()
let phaseByIntersectionAndIndex = new Map<string, any>()
let roadLinkByIntersectionAndIndex = new Map<string, any>()
let signalApproachesByIntersection = new Map<string, any[]>()
let bounds = { minX: 0, maxX: 1, minZ: 0, maxZ: 1 }

// ---- 构建 ----
function computeBounds() {
  if (!roadnet) return
  const pts = roadnet.roads.flatMap((r) => (r.points || []).map(worldPoint))
  bounds = pts.reduce((acc, p) => ({
    minX: Math.min(acc.minX, p.x), maxX: Math.max(acc.maxX, p.x),
    minZ: Math.min(acc.minZ, p.z), maxZ: Math.max(acc.maxZ, p.z),
  }), { minX: Infinity, maxX: -Infinity, minZ: Infinity, maxZ: -Infinity })
}

function fitCamera() {
  if (!camera || !canvasBox.value) return
  const w = Math.max(1, bounds.maxX - bounds.minX)
  const h = Math.max(1, bounds.maxZ - bounds.minZ)
  const cw = canvasBox.value.clientWidth || 400
  const ch = canvasBox.value.clientHeight || 300
  const aspect = cw / Math.max(1, ch)
  const ph = Math.max(h * 1.18, (w * 1.18) / aspect)
  const pw = ph * aspect
  camera.left = -pw / 2; camera.right = pw / 2
  camera.top = ph / 2; camera.bottom = -ph / 2
  const cx = (bounds.minX + bounds.maxX) / 2
  const cz = (bounds.minZ + bounds.maxZ) / 2
  camera.position.set(cx, 1800, cz + 0.01)
  camera.lookAt(cx, 0, cz)
  controls.target.set(cx, 0, cz)
  controls.update()
  camera.updateProjectionMatrix()
}

function makeRoadSegmentMesh(start: THREE.Vector3, end: THREE.Vector3, lanes: number, color: number, y = 0) {
  const d = new THREE.Vector3().subVectors(end, start)
  const len = Math.max(d.length(), 0.001)
  const w = roadWidth(lanes)
  const geo = new THREE.BoxGeometry(w, 4, len)
  const mat = new THREE.MeshStandardMaterial({ color, roughness: 0.78, metalness: 0.08 })
  const mesh = new THREE.Mesh(geo, mat)
  mesh.position.copy(start).add(end).multiplyScalar(0.5)
  mesh.position.y = y
  mesh.rotation.y = Math.atan2(d.x, d.z)
  return mesh
}

function drawIntersections() {
  if (!roadnet) return
  for (const si of roadnet.intersections) {
    const pt = worldPoint(si)
    const r = si.virtual ? 8 : 22
    const h = si.virtual ? 3 : 8
    const geo = new THREE.CylinderGeometry(r, r, h, 24)
    const mat = new THREE.MeshStandardMaterial({
      color: si.virtual ? 0x50606d : 0xd9e4ef, roughness: 0.55, metalness: 0.12,
    })
    const mesh = new THREE.Mesh(geo, mat)
    mesh.position.set(pt.x, si.virtual ? 5 : 8, pt.z)
    intersectionGroup.add(mesh)
    intersectionById.set(si.id, { ...si, point: pt, mesh })
  }
}

function makeLaneMark(start: THREE.Vector3, end: THREE.Vector3, lanes: number) {
  if (lanes < 1) return [] as THREE.Mesh[]
  const d = new THREE.Vector3().subVectors(end, start)
  const len = Math.max(d.length(), 0.001)
  const side = new THREE.Vector3(-d.z, 0, d.x).normalize()
  const marks: THREE.Mesh[] = []
  for (let lane = 0; lane < lanes; lane++) {
    const off = laneOffset(lane, lanes)
    const center = start.clone().add(end).multiplyScalar(0.5).addScaledVector(side, off)
    const geo = new THREE.BoxGeometry(1.4, 1, len * 0.8)
    const mat = new THREE.MeshBasicMaterial({ color: lane === 0 ? 0xd7e1ea : 0x1f272e, transparent: true, opacity: lane === 0 ? 0.45 : 0.62 })
    const mesh = new THREE.Mesh(geo, mat)
    mesh.position.copy(center); mesh.position.y = 4.5
    mesh.rotation.y = Math.atan2(d.x, d.z)
    marks.push(mesh)
  }
  // lane boundaries
  for (let b = 1; b < lanes; b++) {
    const off = (b - lanes / 2) * LANE_WIDTH
    const center = start.clone().add(end).multiplyScalar(0.5).addScaledVector(side, off)
    const geo = new THREE.BoxGeometry(0.8, 1, len * 0.9)
    const mat = new THREE.MeshBasicMaterial({ color: 0x9aa8b3, transparent: true, opacity: 0.28 })
    const mesh = new THREE.Mesh(geo, mat)
    mesh.position.copy(center); mesh.position.y = 5
    mesh.rotation.y = Math.atan2(d.x, d.z)
    marks.push(mesh)
  }
  return marks
}

function drawRoads() {
  if (!roadnet) return
  for (const sr of roadnet.roads) {
    roadById.set(sr.id, sr)
    const pts = visualRoadPoints(sr)
    const meshes: THREE.Mesh[] = []
    for (let i = 0; i < pts.length - 1; i++) {
      const m = makeRoadSegmentMesh(pts[i]!, pts[i + 1]!, visualLaneCount(), unknownLevelColor)
      m.userData.roadId = sr.id
      roadGroup.add(m)
      meshes.push(m)
      const marks = makeLaneMark(pts[i]!, pts[i + 1]!, visualLaneCount())
      for (const mk of marks) { roadGroup.add(mk) }
    }
    roadMeshesById.set(sr.id, meshes)
  }
}

function buildSignalApproaches() {
  if (!roadnet) return
  const map = new Map<string, any[]>()
  const seen = new Set<string>()
  for (const rl of roadnet.roadLinks) {
    const si = intersectionById.get(rl.intersectionId)
    const rd = roadById.get(rl.fromRoadId)
    if (!si || !rd || !rd.points || rd.points.length < 2) continue
    const key = `${rl.intersectionId}:${rl.fromRoadId}`
    if (seen.has(key)) continue; seen.add(key)
    const pts = visualRoadPoints(rd)
    const first = pts[0]!, last = pts[pts.length - 1]!
    const near = first.distanceTo(si.point) <= last.distanceTo(si.point) ? first : last
    const far = near === first ? pts[1]! : pts[pts.length - 2]!
    const direction = new THREE.Vector3().subVectors(si.point, far).setY(0)
    if (direction.lengthSq() < 0.001) continue
    direction.normalize()
    const side = new THREE.Vector3(-direction.z, 0, direction.x)
    const stopDist = 42 + Math.max(0, visualLaneCount() - 1) * 1.5
    const sideOff = visualRoadWidth() / 2 + 12
    const pos = near.clone().addScaledVector(direction, -stopDist).addScaledVector(side, sideOff)
    if (!map.has(rl.intersectionId)) map.set(rl.intersectionId, [])
    map.get(rl.intersectionId)!.push({ roadId: rl.fromRoadId, position: pos, direction, laneCount: visualLaneCount() })
  }
  signalApproachesByIntersection = map
}

function updateRoadStates(roads: any[]) {
  for (const r of roads) {
    const color = levelColors[r.level] ?? unknownLevelColor
    const meshes = roadMeshesById.get(r.id) || []
    for (const m of meshes) {
      const material = m.material as THREE.MeshStandardMaterial
      material.color.setHex(color)
      material.emissive?.setHex(r.level === 'jammed' ? 0x2c0508 : 0x000000)
    }
  }
}

function movementStatesForActiveLinks(activeLinks: any[]) {
  const states = new Map<string, { straight: boolean; left: boolean }>()
  for (const rl of activeLinks) {
    if (rl.type === 'turn_right') continue
    if (!states.has(rl.fromRoadId)) states.set(rl.fromRoadId, { straight: false, left: false })
    const s = states.get(rl.fromRoadId)!
    if (rl.type === 'turn_left') s.left = true; else s.straight = true
  }
  return states
}

function drawMovementLamp(g: THREE.Group, x: number, isGreen: boolean, movement: string) {
  const color = isGreen ? 0x26ff7a : 0xff3b4a
  const housing = new THREE.Mesh(new THREE.BoxGeometry(19, 21, 8), new THREE.MeshStandardMaterial({ color: isGreen ? 0x0c3d25 : 0x42151a, emissive: isGreen ? 0x16ff7a : 0xff3347, emissiveIntensity: 0.42, roughness: 0.42 }))
  housing.position.set(x, 52, -8); g.add(housing)
  const lamp = new THREE.Mesh(new THREE.SphereGeometry(7.2, 24, 24), new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: isGreen ? 1.9 : 1.35, roughness: 0.28 }))
  lamp.position.set(x, 52, -13); lamp.userData.signalLamp = true; g.add(lamp)
  // arrow
  const mat = new THREE.MeshBasicMaterial({ color })
  const shaft = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.4, 10), mat); shaft.position.set(x, 52, -20.5); shaft.rotation.x = Math.PI / 2
  if (movement === 'left') { shaft.rotation.z = Math.PI / 4; shaft.position.x -= 1.8 }
  g.add(shaft)
  const head = new THREE.Mesh(new THREE.ConeGeometry(4.2, 7.5, 3), mat); head.position.set(x, 52, -25.5); head.rotation.x = Math.PI / 2
  if (movement === 'left') { head.rotation.z = Math.PI / 4; head.position.x -= 5.6; head.position.z += 2.8 }
  g.add(head)
}

function drawSignalHead(approach: any, moveState: { straight: boolean; left: boolean }) {
  const g = new THREE.Group()
  g.position.copy(approach.position)
  g.rotation.y = Math.atan2(approach.direction.x, approach.direction.z)
  const hasGreen = moveState.straight || moveState.left
  const arm = new THREE.Mesh(new THREE.BoxGeometry(visualRoadWidth() + 26, 2.6, 2.6), new THREE.MeshStandardMaterial({ color: 0x2d3942, roughness: 0.6 }))
  arm.position.set(0, 54, 0); g.add(arm)
  drawMovementLamp(g, -11, moveState.left, 'left')
  drawMovementLamp(g, 11, moveState.straight, 'straight')
  const stopLine = new THREE.Mesh(new THREE.BoxGeometry(visualRoadWidth(), 2.5, 5), new THREE.MeshBasicMaterial({ color: hasGreen ? 0x62ff99 : 0xff4d5a, transparent: true, opacity: 0.88 }))
  stopLine.position.set(0, 7, 10); g.add(stopLine)
  signalGroup.add(g)
}

function highlightRoadDirection(roadId: string, color: number) {
  const rd = roadById.get(roadId); if (!rd) return
  const pts = visualRoadPoints(rd)
  for (let i = 0; i < pts.length - 1; i++) {
    const m = makeRoadSegmentMesh(pts[i]!, pts[i + 1]!, visualLaneCount(), color, 9)
    m.scale.x = 0.48; m.scale.z = 0.7
    ;(m.material as any).transparent = true; (m.material as any).opacity = 0.82
    ;(m.material as any).emissive = new THREE.Color(0x19a95c); (m.material as any).emissiveIntensity = 0.7
    signalGroup.add(m)
  }
}

function drawTurnHint(roadLink: any) {
  const fromRoad = roadById.get(roadLink.fromRoadId)
  const si = intersectionById.get(roadLink.intersectionId)
  if (!fromRoad || !si || !fromRoad.points || fromRoad.points.length < 2) return
  const approach = (signalApproachesByIntersection.get(roadLink.intersectionId) || []).find((a: any) => a.roadId === roadLink.fromRoadId)
  if (!approach) return
  const g = new THREE.Group()
  const base = si.point.clone().addScaledVector(approach.direction, -24)
  g.position.set(base.x, 13, base.z); g.rotation.y = Math.atan2(approach.direction.x, approach.direction.z)
  const mat = new THREE.MeshBasicMaterial({ color: 0x8dffb2, transparent: true, opacity: 0.92 })
  g.add(new THREE.Mesh(new THREE.BoxGeometry(4, 2, 26), mat))
  const hd = new THREE.Mesh(new THREE.ConeGeometry(8, 16, 3), mat); hd.position.set(0, 0, -18); hd.rotation.x = Math.PI / 2; g.add(hd)
  if (roadLink.type === 'turn_left' || roadLink.type === 'turn_right') {
    const s = roadLink.type === 'turn_left' ? -1 : 1
    const wing = new THREE.Mesh(new THREE.BoxGeometry(4, 2, 18), mat); wing.position.set(s * 9, 0, -12); wing.rotation.y = s * Math.PI / 4; g.add(wing)
  }
  signalGroup.add(g)
}

function drawPhaseRing(point: THREE.Vector3, hasActive: boolean) {
  const ring = new THREE.Mesh(new THREE.TorusGeometry(22, 2.4, 8, 28), new THREE.MeshBasicMaterial({ color: hasActive ? 0x69ffa3 : 0xff4d5a, transparent: true, opacity: 0.72 }))
  ring.position.set(point.x, 35, point.z); ring.rotation.x = Math.PI / 2
  signalGroup.add(ring)
}

function updateSignals(signals: any[]) {
  while (signalGroup.children.length > 0) {
    const c = signalGroup.children[0]!
    signalGroup.remove(c)
    c.traverse((o: any) => { o.geometry?.dispose(); o.material?.dispose() })
  }
  for (const sig of signals) {
    const si = intersectionById.get(sig.intersectionId); if (!si) continue
    const phase = phaseByIntersectionAndIndex.get(`${sig.intersectionId}:${sig.phaseIndex}`); if (!phase) continue
    const activeLinks: any[] = []
    for (const idx of (phase.roadLinkIndexes || [])) {
      const rl = roadLinkByIntersectionAndIndex.get(`${sig.intersectionId}:${idx}`)
      if (rl) { activeLinks.push(rl); highlightRoadDirection(rl.fromRoadId, 0x8dffb2); highlightRoadDirection(rl.toRoadId, 0x8dffb2); drawTurnHint(rl) }
    }
    const approaches = signalApproachesByIntersection.get(sig.intersectionId) || []
    const moveStates = movementStatesForActiveLinks(activeLinks)
    for (const ap of approaches) { drawSignalHead(ap, moveStates.get(ap.roadId) || { straight: false, left: false }) }
    drawPhaseRing(si.point, activeLinks.length > 0)
  }
}

// ---- 车辆 ----
function vehiclePosition(v: SimVehicleState) {
  const base = new THREE.Vector3(v.x, 18, -v.y)
  const rd = roadById.get(v.roadId)
  if (!rd || !rd.points || rd.points.length < 2) return base
  const offset = visualRoadOffset(rd)
  const side = new THREE.Vector3(-visualRoadOffset(rd).z, 0, visualRoadOffset(rd).x).normalize()
  const laneOff = laneOffset(v.lane, visualLaneCount())
  return base.add(offset).addScaledVector(side, laneOff)
}

function createVehicleMesh(v: SimVehicleState) {
  const g = new THREE.Group()
  const body = new THREE.Mesh(
    new THREE.BoxGeometry(6.2, 5.2, 13.5),
    new THREE.MeshStandardMaterial({ color: 0x4cc9ff, roughness: 0.42, metalness: 0.18, emissive: 0x0b4c62, emissiveIntensity: 0.85 }),
  )
  body.position.y = 2
  g.add(body)
  const pos = vehiclePosition(v)
  g.position.copy(pos)
  return g
}

function updateVehicles(vehicles: SimVehicleState[]) {
  const now = performance.now()
  const activeIds = new Set<string>()
  for (const v of vehicles) {
    activeIds.add(v.id)
    const target = vehiclePosition(v)
    let entry = vehiclesById.get(v.id)
    if (!entry) {
      const mesh = createVehicleMesh(v)
      vehicleGroup.add(mesh)
      entry = { mesh, from: target.clone(), to: target.clone(), startAt: now, roadId: v.roadId }
      vehiclesById.set(v.id, entry)
    }
    entry.mesh.position.copy(target)
    entry.roadId = v.roadId
  }
  for (const [id, entry] of vehiclesById) {
    if (!activeIds.has(id)) {
      vehicleGroup.remove(entry.mesh)
      entry.mesh.traverse((c: any) => { c.geometry?.dispose(); c.material?.dispose() })
      vehiclesById.delete(id)
    }
  }
}

// ---- 清空 + 重建 ----
function disposeAll(g: THREE.Group) {
  while (g.children.length > 0) {
    const c = g.children[0]!
    g.remove(c)
    c.traverse((o: any) => { o.geometry?.dispose(); o.material?.dispose() })
  }
}

function rebuildAll() {
  if (!roadnet || !root) return
  disposeAll(roadGroup); disposeAll(intersectionGroup)
  disposeAll(signalGroup); disposeAll(vehicleGroup)
  roadMeshesById.clear(); roadById.clear(); intersectionById.clear()
  vehiclesById.clear()
  computeBounds(); drawIntersections(); drawRoads()
  phaseByIntersectionAndIndex.clear(); roadLinkByIntersectionAndIndex.clear()
  for (const ph of roadnet!.phases) phaseByIntersectionAndIndex.set(`${ph.intersectionId}:${ph.phaseIndex}`, ph)
  for (const rl of roadnet!.roadLinks) roadLinkByIntersectionAndIndex.set(`${rl.intersectionId}:${rl.index}`, rl)
  buildSignalApproaches()
  fitCamera()
}

// ---- 初始化 ----
let resizeObs: ResizeObserver | null = null

onMounted(() => {
  if (!canvasBox.value) return
  const w = canvasBox.value.clientWidth || 400
  const h = canvasBox.value.clientHeight || 300

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(w, h)
  renderer.setClearColor(0x101418, 1)
  canvasBox.value.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.fog = new THREE.Fog(0x101418, 2200, 5200)
  scene.add(new THREE.AmbientLight(0xffffff, 0.72))
  const dir = new THREE.DirectionalLight(0xffffff, 1.2)
  dir.position.set(500, 900, 300)
  scene.add(dir)

  camera = new THREE.OrthographicCamera(-10, 10, 10, -10, 0.1, 6000)
  camera.position.set(0, 1700, 900)

  root = new THREE.Group(); scene.add(root)
  roadGroup = new THREE.Group(); intersectionGroup = new THREE.Group()
  signalGroup = new THREE.Group(); vehicleGroup = new THREE.Group()
  root.add(roadGroup, intersectionGroup, signalGroup, vehicleGroup)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true; controls.dampingFactor = 0.08
  controls.mouseButtons = { LEFT: 0, MIDDLE: 2, RIGHT: 1 } as any

  resizeObs = new ResizeObserver(() => {
    if (!canvasBox.value) return
    const nw = canvasBox.value.clientWidth || 400
    const nh = canvasBox.value.clientHeight || 300
    renderer.setSize(nw, nh)
    if (roadnet) fitCamera()
  })
  resizeObs.observe(canvasBox.value)

  const loop = () => {
    rafId = requestAnimationFrame(loop)
    controls.update()
    renderer.render(scene, camera)
  }
  rafId = requestAnimationFrame(loop)
})

// ---- 响应仿真 ----
watch(simActive, (active) => {
  if (active && simRoadnet.value) {
    roadnet = simRoadnet.value
    console.log('[RoadNetwork] roadnet loaded:', roadnet.intersections.length, 'intersections,', roadnet.roads.length, 'roads')
    rebuildAll()
  }
})

watch(simulationRoads, (roads) => { if (simActive.value) updateRoadStates(roads) }, { deep: false })
watch(simulationSignals, (signals) => { if (simActive.value) updateSignals(signals) }, { deep: false })
watch(simulationVehicles, (vehicles) => { if (simActive.value) updateVehicles(vehicles as SimVehicleState[]) }, { deep: false })

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  resizeObs?.disconnect()
  disposeAll(roadGroup); disposeAll(intersectionGroup)
  disposeAll(signalGroup); disposeAll(vehicleGroup)
  controls?.dispose(); renderer?.dispose()
})
</script>

<template>
  <section class="hud-card data-panel-card comp-card">
    <div class="hud-card__content comp-card__body" style="padding:0">
      <div class="rn-viewport">
        <div ref="canvasBox" class="rn-canvas" />
        <div v-if="!simActive" class="rn-placeholder">仿真未运行 — 启动后显示 CityFlow 3D 路网</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.comp-card { height: 100%; display: flex; flex-direction: column; }
.comp-card__body { flex: 1; min-height: 0; overflow: hidden; }
.rn-viewport { width: 100%; height: 100%; position: relative; border: 1.5px solid rgba(0,212,255,0.42); overflow: hidden; }
.rn-canvas { width: 100%; height: 100%; }
.rn-placeholder { position: absolute; inset: 0; z-index: 2; display: flex; align-items: center; justify-content: center; font-size: 13px; color: #5a7595; pointer-events: none; }
</style>
