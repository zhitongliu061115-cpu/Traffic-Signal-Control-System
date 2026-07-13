import type { SimRoadnetRoad } from '@/types/traffic'

export const FALLBACK_LANE_WIDTH = 6
export const FALLBACK_MEDIAN_GAP = 4

export interface RoadCrossSection {
  laneWidths: number[]
  totalWidth: number
  medianGap: number
  centerOffset: number
  usesRoadnetLaneWidths: boolean
}

export function getRoadCrossSection(road: SimRoadnetRoad): RoadCrossSection {
  const laneCount = Math.max(1, Math.trunc(road.laneCount))
  const widthsByIndex = new Map(
    (road.lanes ?? [])
      .filter((lane) => Number.isFinite(lane.width) && lane.width > 0)
      .map((lane) => [lane.index, lane.width]),
  )
  const usesRoadnetLaneWidths = Array.from({ length: laneCount }, (_, index) =>
    widthsByIndex.has(index),
  ).every(Boolean)
  const laneWidths = usesRoadnetLaneWidths
    ? Array.from({ length: laneCount }, (_, index) => widthsByIndex.get(index)!)
    : Array.from({ length: laneCount }, () => FALLBACK_LANE_WIDTH)
  const totalWidth = laneWidths.reduce((sum, width) => sum + width, 0)
  const medianGap = usesRoadnetLaneWidths ? 0 : FALLBACK_MEDIAN_GAP

  return {
    laneWidths,
    totalWidth,
    medianGap,
    centerOffset: medianGap / 2 + totalWidth / 2,
    usesRoadnetLaneWidths,
  }
}

export function getLaneCenterOffset(road: SimRoadnetRoad, laneIndex: number): number {
  const crossSection = getRoadCrossSection(road)
  const index = Math.min(Math.max(Math.trunc(laneIndex), 0), crossSection.laneWidths.length - 1)
  const precedingWidth = crossSection.laneWidths
    .slice(0, index)
    .reduce((sum, width) => sum + width, 0)
  return crossSection.medianGap / 2 + precedingWidth + crossSection.laneWidths[index]! / 2
}
