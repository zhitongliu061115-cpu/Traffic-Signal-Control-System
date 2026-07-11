// ================================================================
// AMap 全局类型声明（@amap/amap-jsapi-loader 不包含类型定义）
// ================================================================

declare namespace AMap {
  interface MapOptions {
    center?: [number, number]
    zoom?: number
    pitch?: number
    viewMode?: '2D' | '3D'
    mapStyle?: string
    resizeEnable?: boolean
  }

  class Map {
    constructor(container: HTMLElement | string, opts?: MapOptions)
    destroy(): void
    add(overlay: unknown): void
    remove(overlay: unknown): void
    on(event: string, handler: (...args: any[]) => void): void
    getContainer(): HTMLElement
    getZoom(): number
    lngLatToContainer(lngLat: [number, number]): { x: number; y: number }
  }

  class Pixel {
    constructor(x: number, y: number)
  }

  interface PolylineOptions {
    path?: [number, number][]
    strokeColor?: string
    strokeWeight?: number
    strokeOpacity?: number
    lineJoin?: string
    lineCap?: string
    zIndex?: number
  }

  class Polyline {
    constructor(opts?: PolylineOptions)
    setMap(map: Map | null): void
    setPath(path: [number, number][]): void
    setOptions(opts: Partial<PolylineOptions>): void
    show(): void
    hide(): void
    on(event: string, handler: (...args: any[]) => void): void
  }

  interface MarkerOptions {
    position?: [number, number]
    content?: string
    offset?: Pixel
    zIndex?: number
  }

  class Marker {
    constructor(opts?: MarkerOptions)
    setMap(map: Map | null): void
    setContent(content: string): void
    setPosition(position: [number, number]): void
    on(event: string, handler: (...args: any[]) => void): void
  }
}
