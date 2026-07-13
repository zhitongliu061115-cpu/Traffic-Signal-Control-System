import { MathUtils, Vector3 } from 'three'
import type { SimRoadnetResponse, SimRoadnetRoad, SimVehicleState } from '@/types/traffic'
import { IntersectionCoordinateSystem } from './CoordinateSystem'
import { getConnectedRoads } from './IntersectionGeometry'
import { getLaneCenterOffset } from './RoadLayout'

const DEFAULT_VISIBLE_RADIUS = 100

export type VehicleMovement = 'straight' | 'left_turn' | 'right_turn'

export interface LocalVehiclePose {
  id: string
  roadId: string
  speed: number
  position: Vector3
  rotationY: number
  movement: VehicleMovement
  onLaneLink: boolean
}

export interface PolylineSample {
  point: { x: number; y: number }
  direction: { x: number; y: number }
}

export function samplePolylineAtDistance(
  points: Array<{ x: number; y: number }>,
  distance: number,
): PolylineSample | null {
  if (points.length < 2) return null

  let remaining = Math.max(0, distance)
  let lastValid: PolylineSample | null = null

  for (let index = 0; index < points.length - 1; index += 1) {
    const start = points[index]
    const end = points[index + 1]
    if (!start || !end) continue

    const dx = end.x - start.x
    const dy = end.y - start.y
    const length = Math.hypot(dx, dy)
    if (length === 0) continue

    const direction = { x: dx / length, y: dy / length }
    lastValid = { point: { x: end.x, y: end.y }, direction }
    if (remaining <= length) {
      const ratio = remaining / length
      return {
        point: { x: start.x + dx * ratio, y: start.y + dy * ratio },
        direction,
      }
    }
    remaining -= length
  }

  return lastValid
}

function closestSegmentDirection(road: SimRoadnetRoad, point: { x: number; y: number }): Vector3 | null {
  let bestDistanceSq = Number.POSITIVE_INFINITY
  let bestDirection: Vector3 | null = null

  for (let index = 0; index < road.points.length - 1; index += 1) {
    const start = road.points[index]
    const end = road.points[index + 1]
    if (!start || !end) continue

    const dx = end.x - start.x
    const dy = end.y - start.y
    const lengthSq = dx * dx + dy * dy
    if (lengthSq === 0) continue

    const projection = MathUtils.clamp(
      ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSq,
      0,
      1,
    )
    const projectedX = start.x + dx * projection
    const projectedY = start.y + dy * projection
    const offsetX = point.x - projectedX
    const offsetY = point.y - projectedY
    const distanceSq = offsetX * offsetX + offsetY * offsetY

    if (distanceSq < bestDistanceSq) {
      bestDistanceSq = distanceSq
      bestDirection = new Vector3(dx, 0, -dy).normalize()
    }
  }

  return bestDirection
}

export function movementFromRoadLink(type: string): VehicleMovement {
  if (type === 'turn_left') return 'left_turn'
  if (type === 'turn_right') return 'right_turn'
  return 'straight'
}


export function movementForApproachLane(
  roadnet: SimRoadnetResponse,
  intersectionId: string,
  roadId: string,
  laneIndex: number,
): VehicleMovement {
  const movements = roadnet.roadLinks
    .filter((roadLink) => roadLink.intersectionId === intersectionId && roadLink.fromRoadId === roadId)
    .filter((roadLink) => (roadLink.laneLinks ?? []).some((laneLink) => laneLink.startLaneIndex === laneIndex))
    .map((roadLink) => movementFromRoadLink(roadLink.type))

  return movements.length === 1 ? movements[0]! : 'straight'
}

export function createLocalVehiclePoses(
  vehicles: SimVehicleState[],
  roadnet: SimRoadnetResponse,
  intersectionId: string,
  visibleRadius = DEFAULT_VISIBLE_RADIUS,
  height = 0.8,
): LocalVehiclePose[] {
  const intersection = roadnet.intersections.find((item) => item.id === intersectionId)
  if (!intersection) return []

  const coordinates = new IntersectionCoordinateSystem(intersection)
  const connectedRoads = new Map(
    getConnectedRoads(roadnet, intersectionId).map((road) => [road.id, road]),
  )
  const laneLinks = new Map(
    roadnet.roadLinks
      .filter((roadLink) => roadLink.intersectionId === intersectionId)
      .flatMap((roadLink) => (roadLink.laneLinks ?? []).map((laneLink) => [
        laneLink.id,
        { laneLink, movement: movementFromRoadLink(roadLink.type) },
      ] as const)),
  )
  const poses: LocalVehiclePose[] = []

  for (const vehicle of vehicles) {
    if (vehicle.drivableType === 'lane_link' && vehicle.drivableId) {
      const match = laneLinks.get(vehicle.drivableId)
      if (!match || !connectedRoads.has(vehicle.roadId)) continue

      const sample = samplePolylineAtDistance(match.laneLink.points, vehicle.distance ?? 0)
      if (!sample) continue
      const position = coordinates.cityFlowPointToThree(sample.point, height)
      if (Math.hypot(position.x, position.z) > visibleRadius) continue
      const direction = new Vector3(sample.direction.x, 0, -sample.direction.y)

      poses.push({
        id: vehicle.id,
        roadId: vehicle.roadId,
        speed: vehicle.speed,
        position,
        rotationY: Math.atan2(direction.x, direction.z),
        movement: match.movement,
        onLaneLink: true,
      })
      continue
    }

    const road = connectedRoads.get(vehicle.roadId)
    if (!road) continue

    const position = coordinates.cityFlowPointToThree(vehicle, height)
    if (Math.hypot(position.x, position.z) > visibleRadius) continue

    const direction = closestSegmentDirection(road, vehicle)
    if (!direction) continue

    const laneIndex = MathUtils.clamp(Math.trunc(vehicle.lane), 0, Math.max(1, road.laneCount) - 1)
    const side = new Vector3(-direction.z, 0, direction.x)
    position.addScaledVector(side, getLaneCenterOffset(road, laneIndex))

    poses.push({
      id: vehicle.id,
      roadId: vehicle.roadId,
      speed: vehicle.speed,
      position,
      rotationY: Math.atan2(direction.x, direction.z),
      movement: movementForApproachLane(roadnet, intersectionId, vehicle.roadId, laneIndex),
      onLaneLink: false,
    })
  }

  return poses
}
