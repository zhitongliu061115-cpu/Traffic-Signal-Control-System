// ================================================================
// SceneManager — Three.js 场景 / 相机 / 控制器 / 渲染循环
// ================================================================
import {
  Scene,
  PerspectiveCamera,
  WebGLRenderer,
  Color,
  AmbientLight,
  DirectionalLight,
  GridHelper,
  Fog,
  Vector3,
  type Object3D,
} from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { THEME, WORLD } from './config'

export class SceneManager {
  readonly scene: Scene
  readonly camera: PerspectiveCamera
  readonly renderer: WebGLRenderer
  readonly controls: OrbitControls

  private container: HTMLElement
  private rafId: number | null = null
  private updateCallbacks: Array<(deltaMs: number) => void> = []
  private lastTime = 0
  private resizeObserver: ResizeObserver

  constructor(container: HTMLElement) {
    this.container = container

    // ---- 场景（深色背景 + 雾）----
    this.scene = new Scene()
    this.scene.background = new Color(THEME.bg)
    this.scene.fog = new Fog(THEME.bg, 900, 1800)

    // ---- 相机（俯视透视）----
    const w = container.clientWidth || 800
    const h = container.clientHeight || 500
    this.camera = new PerspectiveCamera(50, w / h, 1, 5000)
    this.camera.position.set(0, 620, 460)
    this.camera.lookAt(0, 0, 0)

    // ---- 渲染器 ----
    this.renderer = new WebGLRenderer({ antialias: true, alpha: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    this.renderer.setSize(w, h)
    container.appendChild(this.renderer.domElement)

    // ---- 控制器（旋转/缩放/平移）----
    this.controls = new OrbitControls(this.camera, this.renderer.domElement)
    this.controls.enableDamping = true
    this.controls.dampingFactor = 0.08
    this.controls.minDistance = 200
    this.controls.maxDistance = 1400
    this.controls.maxPolarAngle = Math.PI / 2.15 // 限制不低于地平线
    this.controls.target.set(0, 0, 0)

    // ---- 灯光 ----
    this.scene.add(new AmbientLight(0x8fadc4, 1.1))
    const dir = new DirectionalLight(0xffffff, 1.2)
    dir.position.set(300, 600, 400)
    this.scene.add(dir)
    const dir2 = new DirectionalLight(0x00d4ff, 0.4)
    dir2.position.set(-400, 300, -300)
    this.scene.add(dir2)

    // ---- 地面网格 ----
    const grid = new GridHelper(
      Math.max(WORLD.SIZE_X, WORLD.SIZE_Z) * 1.4,
      40,
      new Color(THEME.grid),
      new Color(THEME.grid),
    )
    const gridMat = grid.material as { opacity: number; transparent: boolean }
    gridMat.opacity = 0.35
    gridMat.transparent = true
    grid.position.y = -1
    this.scene.add(grid)

    // ---- 自适应尺寸 ----
    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(container)
  }

  /** 添加对象到场景 */
  add(obj: Object3D): void {
    this.scene.add(obj)
  }

  /** 注册每帧更新回调 */
  onUpdate(cb: (deltaMs: number) => void): void {
    this.updateCallbacks.push(cb)
  }

  /** 相机平滑飞向目标点 */
  flyTo(target: Vector3, distance = 320): void {
    const dir = new Vector3(0.2, 0.9, 0.6).normalize().multiplyScalar(distance)
    const camTarget = target.clone().add(dir)
    const startPos = this.camera.position.clone()
    const startTarget = this.controls.target.clone()
    const t0 = performance.now()
    const dur = 700
    const step = (now: number) => {
      const k = Math.min(1, (now - t0) / dur)
      const ease = 1 - Math.pow(1 - k, 3)
      this.camera.position.lerpVectors(startPos, camTarget, ease)
      this.controls.target.lerpVectors(startTarget, target, ease)
      this.controls.update()
      if (k < 1) requestAnimationFrame(step)
    }
    requestAnimationFrame(step)
  }

  /** 启动渲染循环 */
  start(): void {
    this.lastTime = performance.now()
    const loop = (now: number) => {
      const deltaMs = now - this.lastTime
      this.lastTime = now
      this.controls.update()
      for (const cb of this.updateCallbacks) cb(deltaMs)
      this.renderer.render(this.scene, this.camera)
      this.rafId = requestAnimationFrame(loop)
    }
    this.rafId = requestAnimationFrame(loop)
  }

  private resize(): void {
    const w = this.container.clientWidth
    const h = this.container.clientHeight
    if (w === 0 || h === 0) return
    this.camera.aspect = w / h
    this.camera.updateProjectionMatrix()
    this.renderer.setSize(w, h)
  }

  /** 销毁：停止循环、移除监听、释放 WebGL 资源 */
  dispose(): void {
    if (this.rafId !== null) cancelAnimationFrame(this.rafId)
    this.resizeObserver.disconnect()
    this.controls.dispose()
    this.renderer.dispose()
    if (this.renderer.domElement.parentElement === this.container) {
      this.container.removeChild(this.renderer.domElement)
    }
    this.updateCallbacks = []
  }
}
