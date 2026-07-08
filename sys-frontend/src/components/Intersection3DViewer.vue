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
  BoxGeometry,
  CylinderGeometry,
  PlaneGeometry,
  SphereGeometry,
  MeshStandardMaterial,
  GridHelper,
} from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
// 预留：真实模型加载
// import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { useTrafficStore } from '@/stores/traffic'
import { PHASE_LABELS, DEVICE_STATUS_LABELS } from '@/types/traffic'

const props = defineProps<{ intersectionId: string | null }>()
const emit = defineEmits<{ close: [] }>()

const store = useTrafficStore()
const { intersections } = storeToRefs(store)

const viewerBox = ref<HTMLDivElement | null>(null)
const loading = ref(true)
const modelFound = ref(false)

const intersection = computed(() =>
  intersections.value.find((it) => it.id === props.intersectionId) ?? null,
)

// ---- Three.js 局部实例 ----
let scene: Scene | null = null
let camera: PerspectiveCamera | null = null
let renderer: WebGLRenderer | null = null
let controls: OrbitControls | null = null
let rafId: number | null = null
let resizeObserver: ResizeObserver | null = null
let elapsed = 0
const signalLamps: Mesh[] = []

/** 程序化搭建一个简化路口实景（无 .glb 时的占位） */
function buildProceduralIntersection(): Group {
  const g = new Group()
  const asphalt = new MeshStandardMaterial({ color: 0x1a2433, roughness: 0.9, metalness: 0.1 })
  const line = new MeshStandardMaterial({ color: 0xdfe8f0, emissive: 0x223040, emissiveIntensity: 0.3 })

  // 十字路面
  const roadH = new Mesh(new BoxGeometry(160, 1, 44), asphalt)
  g.add(roadH)
  const roadV = new Mesh(new BoxGeometry(44, 1, 160), asphalt)
  g.add(roadV)

  // 车道中线
  for (let i = -60; i <= 60; i += 20) {
    const dashH = new Mesh(new BoxGeometry(10, 1.2, 1.5), line)
    dashH.position.set(i, 0.5, 0)
    g.add(dashH)
    const dashV = new Mesh(new BoxGeometry(1.5, 1.2, 10), line)
    dashV.position.set(0, 0.5, i)
    g.add(dashV)
  }

  // 斑马线（四方向）
  for (let i = -18; i <= 18; i += 6) {
    const cw1 = new Mesh(new PlaneGeometry(4, 20), line)
    cw1.rotation.x = -Math.PI / 2
    cw1.position.set(i, 0.6, -32)
    g.add(cw1)
    const cw2 = cw1.clone()
    cw2.position.set(i, 0.6, 32)
    g.add(cw2)
  }

  // 信号灯杆（四角）+ 灯头
  const poleMat = new MeshStandardMaterial({ color: 0x33475c, metalness: 0.6, roughness: 0.4 })
  const corners = [
    [-30, -30], [30, -30], [-30, 30], [30, 30],
  ] as const
  for (const [x, z] of corners) {
    const pole = new Mesh(new CylinderGeometry(1.2, 1.2, 28, 12), poleMat)
    pole.position.set(x, 14, z)
    g.add(pole)
    // 灯头（随相位变色）
    const lamp = new Mesh(
      new SphereGeometry(3, 16, 16),
      new MeshStandardMaterial({ color: 0x22d3a0, emissive: 0x22d3a0, emissiveIntensity: 1 }),
    )
    lamp.position.set(x, 28, z)
    g.add(lamp)
    signalLamps.push(lamp)
  }

  // 摄像头（一角横臂 + 盒）
  const camArm = new Mesh(new BoxGeometry(16, 1.5, 1.5), poleMat)
  camArm.position.set(-38, 26, -30)
  g.add(camArm)
  const camBox = new Mesh(
    new BoxGeometry(5, 4, 6),
    new MeshStandardMaterial({ color: 0x2a3a4a, metalness: 0.7, roughness: 0.3 }),
  )
  camBox.position.set(-46, 25, -30)
  g.add(camBox)

  // 雷达（半球）
  const radar = new Mesh(
    new SphereGeometry(4, 20, 20, 0, Math.PI * 2, 0, Math.PI / 2),
    new MeshStandardMaterial({ color: 0x00d4ff, emissive: 0x0088b3, emissiveIntensity: 0.5, metalness: 0.5 }),
  )
  radar.position.set(30, 29, 30)
  g.add(radar)

  // 周边建筑（简化盒）
  const buildMat = new MeshStandardMaterial({ color: 0x14202e, emissive: 0x0a1520, emissiveIntensity: 0.4 })
  const buildings = [
    [-70, 70, 40, 60], [70, 70, 50, 90], [-70, -70, 44, 50], [72, -70, 40, 70],
  ] as const
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

function buildFallback(): void {
  if (!scene) return
  scene.add(buildProceduralIntersection())
  modelFound.value = false
}

onMounted(() => {
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
  const loop = () => {
    elapsed += 16
    // 信号灯呼吸
    const glow = 0.7 + 0.5 * Math.abs(Math.sin(elapsed / 400))
    for (const lamp of signalLamps) {
      ;(lamp.material as MeshStandardMaterial).emissiveIntensity = glow
    }
    controls?.update()
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
  if (rafId !== null) cancelAnimationFrame(rafId)
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
          <div class="i3d-info__row">
            <span>相位</span><span class="text-cyan">{{ PHASE_LABELS[intersection.currentPhase] }}</span>
          </div>
          <div class="i3d-info__row">
            <span>绿灯剩余</span><span class="text-emerald">{{ Math.round(intersection.greenRemain) }}s</span>
          </div>
          <div class="i3d-info__row">
            <span>排队</span><span class="text-amber">{{ intersection.queueLength }} 辆</span>
          </div>
          <div class="i3d-info__row">
            <span>设备</span><span>{{ DEVICE_STATUS_LABELS[intersection.deviceStatus] }}</span>
          </div>
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
  z-index: 100;
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

.i3d-close {
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
  right: 16px;
  bottom: 16px;
  min-width: 180px;
  padding: 12px 14px;
  background: rgba(4, 21, 39, 0.9);
  border: 1px solid rgba(0, 212, 255, 0.4);
  backdrop-filter: blur(8px);
}

.i3d-info__row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
  color: #8da8c5;
  margin-top: 5px;
  font-family: 'Rajdhani', sans-serif;
}

.i3d-info__row:first-child {
  margin-top: 0;
}

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
