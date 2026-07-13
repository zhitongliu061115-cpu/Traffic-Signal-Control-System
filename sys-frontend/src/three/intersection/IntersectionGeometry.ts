import type { SimRoadnetResponse, SimRoadnetRoad } from '@/types/traffic'
import { IntersectionCoordinateSystem } from './CoordinateSystem'
import type { Vector3 } from 'three'

export interface LocalRoadCenterline {
  id: string
  laneCount: number
  points: Vector3[]
}

export function getConnectedRoads(
  roadnet: SimRoadnetResponse,
  intersectionId: string,
): SimRoadnetRoad[] {
  return roadnet.roads.filter(
    (road) => road.from === intersectionId || road.to === intersectionId,
  )
}

export function createLocalRoadCenterlines(
  roadnet: SimRoadnetResponse,
  intersectionId: string,
  scale = 1,
  height = 1.5,
): LocalRoadCenterline[] {
  const intersection = roadnet.intersections.find((item) => item.id === intersectionId)
  if (!intersection) return []

  const coordinates = new IntersectionCoordinateSystem(intersection, scale)
  return getConnectedRoads(roadnet, intersectionId).map((road) => ({
    id: road.id,
    laneCount: road.laneCount,
    points: road.points.map((point) => coordinates.cityFlowPointToThree(point, height)),
  }))
}
