import { describe, expect, it } from 'vitest'
import { cameraCompassRotationDeg, movementSignalState } from '../intersection/PanoramaSignal'

describe('panorama signal presentation', () => {
  it('separates straight and left-turn phases by approach axis', () => {
    expect(movementSignalState('eastwest_straight', 'east', true)).toEqual({
      left: false,
      straight: true,
      right: true,
    })
    expect(movementSignalState('eastwest_left', 'north', true)).toEqual({
      left: false,
      straight: false,
      right: true,
    })
    expect(movementSignalState('northsouth_left', 'south', true)).toEqual({
      left: true,
      straight: false,
      right: true,
    })
  })

  it('shows the CityFlow right-turn-only phase and fails offline signals closed', () => {
    expect(movementSignalState('all_red', 'west', true)).toEqual({
      left: false,
      straight: false,
      right: true,
    })
    expect(movementSignalState('eastwest_straight', 'west', false)).toEqual({
      left: false,
      straight: false,
      right: false,
    })
  })

  it('keeps north at the top for the initial south-side camera view', () => {
    expect(cameraCompassRotationDeg(0, -1)).toBeCloseTo(0)
    expect(cameraCompassRotationDeg(-1, 0)).toBeCloseTo(90)
  })
})
