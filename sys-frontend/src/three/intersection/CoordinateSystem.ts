import { Vector3 } from 'three'

export interface Point2D {
  x: number
  y: number
}

/**
 * Converts CityFlow world coordinates into a Three.js coordinate system
 * centered on one intersection.
 */
export class IntersectionCoordinateSystem {
  constructor(
    private readonly center: Point2D,
    private readonly scale = 1,
  ) {}

  cityFlowPointToThree(point: Point2D, height = 0): Vector3 {
    const localY = point.y - this.center.y
    return new Vector3(
      (point.x - this.center.x) * this.scale,
      height,
      localY === 0 ? 0 : -localY * this.scale,
    )
  }
}

