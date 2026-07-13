import { Vector3 } from 'three'
import type { SimRoadnetResponse } from '@/types/traffic'
import { IntersectionCoordinateSystem } from './CoordinateSystem'
import { getConnectedRoads } from './IntersectionGeometry'
import { getRoadCrossSection } from './RoadLayout'

export interface LocalRoadSurfaceSegment {
  roadId: string
  start: Vector3
  end: Vector3
  center: Vector3
  length: number
  width: number
  rotationY: number
  usesRoadnetLaneWidths: boolean
}

export interface LocalLaneLinkPath {
  roadLinkIndex: number
  type: string
  startLaneIndex: number
  endLaneIndex: number
  points: Vector3[]
}

function clipSegmentToRadius(start: Vector3, end: Vector3, radius: number): [Vector3, Vector3] | null {
  const dx = end.x - start.x
  const dz = end.z - start.z
  const a = dx * dx + dz * dz
  if (a === 0) return null

  const b = 2 * (start.x * dx + start.z * dz)
  const c = start.x * start.x + start.z * start.z - radius * radius
  const discriminant = b * b - 4 * a * c
  if (discriminant < 0) return null

  const root = Math.sqrt(discriminant)
  const lower = Math.max(0, (-b - root) / (2 * a))
  const upper = Math.min(1, (-b + root) / (2 * a))
  if (lower >= upper) return null

  return [
    start.clone().lerp(end, lower),
    start.clone().lerp(end, upper),
  ]
}

export function createLocalRoadSurfaceSegments(
  roadnet: SimRoadnetResponse,
  intersectionId: string,
  visibleRadius = 120,
  height = 0.12,
): LocalRoadSurfaceSegment[] {
  const intersection = roadnet.intersections.find((item) => item.id === intersectionId)
  if (!intersection || visibleRadius <= 0) return []

  const coordinates = new IntersectionCoordinateSystem(intersection)
  const segments: LocalRoadSurfaceSegment[] = []

  for (const road of getConnectedRoads(roadnet, intersectionId)) {
    const crossSection = getRoadCrossSection(road)
    const points = road.points.map((point) => coordinates.cityFlowPointToThree(point, height))

    for (let index = 0; index < points.length - 1; index += 1) {
      const rawStart = points[index]
      const rawEnd = points[index + 1]
      if (!rawStart || !rawEnd) continue
      const clipped = clipSegmentToRadius(rawStart, rawEnd, visibleRadius)
      if (!clipped) continue

      const [centerlineStart, centerlineEnd] = clipped
      const direction = centerlineEnd.clone().sub(centerlineStart).normalize()
      const side = new Vector3(-direction.z, 0, direction.x)
      const start = centerlineStart.clone().addScaledVector(side, crossSection.centerOffset)
      const end = centerlineEnd.clone().addScaledVector(side, crossSection.centerOffset)
      const center = start.clone().add(end).multiplyScalar(0.5)

      segments.push({
        roadId: road.id,
        start,
        end,
        center,
        length: start.distanceTo(end),
        width: crossSection.totalWidth,
        rotationY: Math.atan2(direction.x, direction.z),
        usesRoadnetLaneWidths: crossSection.usesRoadnetLaneWidths,
      })
    }
  }

  return segments
}

export function createLocalLaneLinkPaths(
  roadnet: SimRoadnetResponse,
  intersectionId: string,
  height = 0.4,
): LocalLaneLinkPath[] {
  const intersection = roadnet.intersections.find((item) => item.id === intersectionId)
  if (!intersection) return []

  const coordinates = new IntersectionCoordinateSystem(intersection)
  return roadnet.roadLinks
    .filter((roadLink) => roadLink.intersectionId === intersectionId)
    .flatMap((roadLink) => (roadLink.laneLinks ?? []).map((laneLink) => ({
      roadLinkIndex: roadLink.index,
      type: roadLink.type,
      startLaneIndex: laneLink.startLaneIndex,
      endLaneIndex: laneLink.endLaneIndex,
      points: laneLink.points.map((point) => coordinates.cityFlowPointToThree(point, height)),
    })))
    .filter((laneLink) => laneLink.points.length >= 2)
}
