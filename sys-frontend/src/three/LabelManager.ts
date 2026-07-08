// ================================================================
// LabelManager — Canvas 纹理 Sprite 文字标签工厂
// 用于路口名称、道路拥堵指数、信号倒计时
// ================================================================
import { Sprite, SpriteMaterial, CanvasTexture, LinearFilter } from 'three'

export interface LabelOptions {
  fontSize?: number
  color?: string
  bg?: string
  padding?: number
  /** 世界坐标下的高度缩放 */
  scale?: number
}

/** 一个可更新文字的 Sprite 标签 */
export class TextLabel {
  readonly sprite: Sprite
  private canvas: HTMLCanvasElement
  private ctx: CanvasRenderingContext2D
  private texture: CanvasTexture
  private opts: Required<LabelOptions>
  private currentText = ''

  constructor(text: string, options: LabelOptions = {}) {
    this.opts = {
      fontSize: options.fontSize ?? 36,
      color: options.color ?? '#e8f4ff',
      bg: options.bg ?? 'rgba(4,21,39,0.7)',
      padding: options.padding ?? 12,
      scale: options.scale ?? 1,
    }
    this.canvas = document.createElement('canvas')
    const ctx = this.canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas 2D context unavailable')
    this.ctx = ctx

    this.texture = new CanvasTexture(this.canvas)
    this.texture.minFilter = LinearFilter
    const material = new SpriteMaterial({
      map: this.texture,
      transparent: true,
      depthTest: false,
      depthWrite: false,
    })
    this.sprite = new Sprite(material)
    this.setText(text)
  }

  /** 更新文字内容（自动重绘纹理） */
  setText(text: string): void {
    if (text === this.currentText) return
    this.currentText = text

    const { fontSize, color, bg, padding, scale } = this.opts
    const font = `700 ${fontSize}px "Rajdhani","PingFang SC",sans-serif`
    this.ctx.font = font
    const metrics = this.ctx.measureText(text)
    const w = Math.ceil(metrics.width) + padding * 2
    const h = fontSize + padding * 2

    this.canvas.width = w
    this.canvas.height = h

    // 重绘（尺寸变化会重置 context）
    this.ctx.font = font
    this.ctx.textAlign = 'center'
    this.ctx.textBaseline = 'middle'

    // 背景圆角
    this.ctx.fillStyle = bg
    this.roundRect(0, 0, w, h, 8)
    this.ctx.fill()

    // 文字
    this.ctx.fillStyle = color
    this.ctx.fillText(text, w / 2, h / 2)

    this.texture.needsUpdate = true

    // Sprite 世界尺寸按画布比例缩放
    const worldH = 22 * scale
    const worldW = (w / h) * worldH
    this.sprite.scale.set(worldW, worldH, 1)
  }

  private roundRect(x: number, y: number, w: number, h: number, r: number): void {
    this.ctx.beginPath()
    this.ctx.moveTo(x + r, y)
    this.ctx.arcTo(x + w, y, x + w, y + h, r)
    this.ctx.arcTo(x + w, y + h, x, y + h, r)
    this.ctx.arcTo(x, y + h, x, y, r)
    this.ctx.arcTo(x, y, x + w, y, r)
    this.ctx.closePath()
  }

  dispose(): void {
    this.texture.dispose()
    ;(this.sprite.material as SpriteMaterial).dispose()
  }
}

export class LabelManager {
  private labels: TextLabel[] = []

  create(text: string, options?: LabelOptions): TextLabel {
    const label = new TextLabel(text, options)
    this.labels.push(label)
    return label
  }

  dispose(): void {
    for (const l of this.labels) l.dispose()
    this.labels = []
  }
}
