import { describe, expect, it } from 'vitest'
import type { SimRoadnetRoad } from '@/types/traffic'
import { getLaneCenterOffset, getRoadCrossSection } from '../RoadLayout'

function road(overrides: Partial<SimRoadnetRoad> = {}): SimRoadnetRoad {
  return {
    id: 'road',
    from: 'center',
    to: 'east',
    points: [{ x: 0, y: 0 }, { x: 100, y: 0 }],
    laneCount: 3,
    ...overrides,
  }
}

describe('road cross section', () => {
  it('uses real CityFlow lane widths without inventing a median gap', () => {
    const item = road({ lanes: [
      { index: 0, width: 4 },
      { index: 1, width: 4 },
      { index: 2, width: 4 },
    ] })

    expect(getRoadCrossSection(item)).toMatchObject({
      totalWidth: 12,
      medianGap: 0,
      centerOffset: 6,
      usesRoadnetLaneWidths: true,
    })
    expect([0, 1, 2].map((index) => getLaneCenterOffset(item, index))).toEqual([2, 6, 10])
  })

  it('keeps the existing procedural dimensions as a compatibility fallback', () => {
    const item = road()

    expect(getRoadCrossSection(item)).toMatchObject({
      totalWidth: 18,
      medianGap: 4,
      centerOffset: 11,
      usesRoadnetLaneWidths: false,
    })
    expect([0, 1, 2].map((index) => getLaneCenterOffset(item, index))).toEqual([5, 11, 17])
  })
})
