import {
  CanvasTexture,
  Group,
  LinearFilter,
  Mesh,
  MeshStandardMaterial,
  PlaneGeometry,
  Sprite,
  SpriteMaterial,
} from 'three'
import type { SignalPhase } from '@/types/traffic'
import {
  APPROACH_LABELS,
  movementSignalState,
  type ApproachDirection,
  type SignalMovement,
} from './PanoramaSignal'

export interface PanoramaSignalSnapshot {
  phase: SignalPhase
  online: boolean
  remaining: number | null
}

const SIGNAL_POSITIONS: Readonly<Record<ApproachDirection, readonly [number, number, number]>> = {
  north: [-27, 17, -27],
  east: [27, 17, -27],
  south: [27, 17, 27],
  west: [-27, 17, 27],
}

const MARKER_POSITIONS: Readonly<Record<ApproachDirection, readonly [number, number, number]>> = {
  north: [0, 0.72, -66],
  east: [66, 0.72, 0],
  south: [0, 0.72, 66],
  west: [-66, 0.72, 0],
}

const MOVEMENTS: readonly SignalMovement[] = ['left', 'straight', 'right']

function drawArrow(
  ctx: CanvasRenderingContext2D,
  movement: SignalMovement,
  centerX: number,
  centerY: number,
): void {
  ctx.save()
  ctx.strokeStyle = '#ffffff'
  ctx.fillStyle = '#ffffff'
  ctx.lineWidth = 7
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.beginPath()

  if (movement === 'straight') {
    ctx.moveTo(centerX, centerY + 18)
    ctx.lineTo(centerX, centerY - 17)
    ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(centerX, centerY - 27)
    ctx.lineTo(centerX - 11, centerY - 12)
    ctx.lineTo(centerX + 11, centerY - 12)
  } else {
    const direction = movement === 'left' ? -1 : 1
    ctx.moveTo(centerX, centerY + 19)
    ctx.lineTo(centerX, centerY - 6)
    ctx.quadraticCurveTo(centerX, centerY - 19, centerX + direction * 16, centerY - 19)
    ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(centerX + direction * 27, centerY - 19)
    ctx.lineTo(centerX + direction * 12, centerY - 30)
    ctx.lineTo(centerX + direction * 12, centerY - 8)
  }

  ctx.closePath()
  ctx.fill()
  ctx.restore()
}

function drawSignalHead(
  canvas: HTMLCanvasElement,
  direction: ApproachDirection,
  snapshot: PanoramaSignalSnapshot,
): void {
  const ctx = canvas.getContext('2d')!
  const state = movementSignalState(snapshot.phase, direction, snapshot.online)
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = 'rgba(3, 12, 20, 0.94)'
  ctx.strokeStyle = snapshot.online ? '#4d7185' : '#64748b'
  ctx.lineWidth = 4
  ctx.beginPath()
  ctx.roundRect(3, 3, canvas.width - 6, canvas.height - 6, 14)
  ctx.fill()
  ctx.stroke()

  ctx.fillStyle = '#d9f4ff'
  ctx.font = 'bold 23px sans-serif'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText(APPROACH_LABELS[direction], 18, 24)

  const centers = [105, 205, 305]
  MOVEMENTS.forEach((movement, index) => {
    const active = state[movement]
    const centerX = centers[index]!
    const centerY = 79
    ctx.fillStyle = active ? '#16c784' : '#e5484d'
    ctx.shadowColor = active ? '#16c784' : '#e5484d'
    ctx.shadowBlur = active ? 18 : 8
    ctx.beginPath()
    ctx.arc(centerX, centerY, 34, 0, Math.PI * 2)
    ctx.fill()
    ctx.shadowBlur = 0
    drawArrow(ctx, movement, centerX, centerY)
  })

  const controlledMovement = snapshot.phase.endsWith('left') ? 'left' : 'straight'
  if (snapshot.remaining !== null && state[controlledMovement]) {
    const centerX = centers[controlledMovement === 'left' ? 0 : 1]!
    ctx.fillStyle = '#ffffff'
    ctx.font = 'bold 18px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(String(Math.round(snapshot.remaining)), centerX, 113)
  }
}

function createDirectionMarker(direction: ApproachDirection): Mesh {
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 128
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = 'rgba(2, 12, 20, 0.82)'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = '#55d8ff'
  ctx.lineWidth = 8
  ctx.strokeRect(5, 5, canvas.width - 10, canvas.height - 10)
  ctx.fillStyle = '#ffffff'
  ctx.font = 'bold 64px sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(APPROACH_LABELS[direction].slice(0, 1), 128, 64)

  const texture = new CanvasTexture(canvas)
  texture.minFilter = LinearFilter
  const marker = new Mesh(
    new PlaneGeometry(18, 9),
    new MeshStandardMaterial({ map: texture, transparent: true, depthWrite: false }),
  )
  marker.rotation.x = -Math.PI / 2
  marker.position.set(...MARKER_POSITIONS[direction])
  return marker
}

export function installPanoramaGuidance(
  parent: Group,
  getSnapshot: () => PanoramaSignalSnapshot | null,
): () => void {
  const sprites = new Map<ApproachDirection, Sprite>()

  for (const direction of Object.keys(SIGNAL_POSITIONS) as ApproachDirection[]) {
    parent.add(createDirectionMarker(direction))
    const canvas = document.createElement('canvas')
    canvas.width = 410
    canvas.height = 130
    const texture = new CanvasTexture(canvas)
    texture.minFilter = LinearFilter
    const sprite = new Sprite(new SpriteMaterial({
      map: texture,
      transparent: true,
      depthTest: false,
      depthWrite: false,
    }))
    sprite.scale.set(24.5, 7.8, 1)
    sprite.position.set(...SIGNAL_POSITIONS[direction])
    sprite.renderOrder = 20
    sprite.userData.canvas = canvas
    parent.add(sprite)
    sprites.set(direction, sprite)
  }

  let lastState = ''
  return () => {
    const snapshot = getSnapshot()
    if (!snapshot) return
    const roundedRemaining = snapshot.remaining === null ? null : Math.round(snapshot.remaining)
    const stateKey = `${snapshot.phase}|${snapshot.online}|${roundedRemaining ?? 'unknown'}`
    if (stateKey === lastState) return
    lastState = stateKey

    for (const [direction, sprite] of sprites) {
      drawSignalHead(sprite.userData.canvas as HTMLCanvasElement, direction, snapshot)
      ;(sprite.material as SpriteMaterial).map!.needsUpdate = true
    }
  }
}
