// ================================================================
// CustomLayerManager — MapLibre CustomLayerInterface 工厂
// 让 Three.js 场景与 MapLibre 共享 WebGL 上下文和 VP 矩阵
// ================================================================
import * as THREE from 'three'
import type maplibregl from 'maplibre-gl'

export interface CustomLayerHandle {
  layer: maplibregl.CustomLayerInterface
  /** 供外部 dispose 时调用 */
  dispose: () => void
}

export function createCustomLayer(
  scene: THREE.Scene,
  map: maplibregl.Map,
): CustomLayerHandle {
  let renderer: THREE.WebGLRenderer | null = null
  let camera: THREE.Camera | null = null

  const layer: maplibregl.CustomLayerInterface = {
    id: 'three-custom-layer',
    type: 'custom',
    renderingMode: '2d' as const,

    onAdd(_map, gl) {
      renderer = new THREE.WebGLRenderer({
        canvas: _map.getCanvas(),
        context: gl,
        antialias: true,
      })
      renderer.autoClear = false

      camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.01, 1000)
    },

    render(_gl, matrix) {
      if (!renderer || !camera) return
      // matrix 是 Float32Array(16)，TS 类型是 CustomRenderMethodInput，需要 as 转
      const m = new THREE.Matrix4().fromArray(matrix as unknown as number[])
      camera.projectionMatrix = m
      renderer.resetState()
      renderer.render(scene, camera)
      map.triggerRepaint()
    },

    onRemove() {
      renderer?.dispose()
      renderer = null
      camera = null
    },
  }

  return {
    layer,
    dispose: () => {
      renderer?.dispose()
      renderer = null
      camera = null
    },
  }
}
