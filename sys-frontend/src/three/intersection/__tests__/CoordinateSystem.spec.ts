import { describe, expect, it } from 'vitest'
import { IntersectionCoordinateSystem } from '../CoordinateSystem'

describe('IntersectionCoordinateSystem', () => {
  const coordinates = new IntersectionCoordinateSystem({ x: 100, y: 200 })

  it('maps the selected intersection center to the Three.js origin', () => {
    expect(coordinates.cityFlowPointToThree({ x: 100, y: 200 }).toArray()).toEqual([0, 0, 0])
  })

  it('maps CityFlow east to +X and increasing Y to -Z', () => {
    expect(coordinates.cityFlowPointToThree({ x: 125, y: 240 }).toArray()).toEqual([25, 0, -40])
  })

  it('preserves height and applies scale', () => {
    const scaled = new IntersectionCoordinateSystem({ x: 100, y: 200 }, 0.5)

    expect(scaled.cityFlowPointToThree({ x: 80, y: 160 }, 3).toArray()).toEqual([-10, 3, 20])
  })
})
