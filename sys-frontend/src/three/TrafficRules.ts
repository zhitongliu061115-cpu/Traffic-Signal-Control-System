// ================================================================
// TrafficRules.ts — 路口交通约束规则（车辆动画用）
// ================================================================
import type { Intersection, Vehicle } from '@/types/traffic'

// ---- 1. 车道定义 ----
// 三车道，从黄线（中心）往外排序
export const LANE_COUNT = 3

export enum LaneType {
  LEFT_TURN = 'left',    // 最靠黄线：只允许左转
  STRAIGHT  = 'straight', // 中间：只允许直行
  RIGHT_TURN = 'right',  // 最远离黄线：只允许右转
}

/** laneIndex(0/1/2) → 车道类型（0=最靠黄线=左转） */
export function laneType(laneIndex: number): LaneType {
  if (laneIndex === 0) return LaneType.LEFT_TURN
  if (laneIndex === 1) return LaneType.STRAIGHT
  return LaneType.RIGHT_TURN
}

// ---- 2. 车辆当前所处车道 ----
export function getVehicleLane(v: Vehicle): LaneType {
  return laneType(v.laneIndex)
}

// ---- 3. 红绿灯约束 ----
/**
 * 判断车辆在当前路口是否允许通行。
 * @param v 车辆
 * @param intersection 当前路口信号状态
 * @param approachingIntersection 车辆是否正在接近路口（progress 在停止线范围内）
 */
export function canPassIntersection(
  v: Vehicle,
  intersection: Intersection,
  isApproaching: boolean,
): boolean {
  // 不是接近路口 → 不限制
  if (!isApproaching) return true

  // 设备故障/离线 → 四向停车
  if (intersection.deviceStatus !== 'online') return false

  // 全红 → 停车
  if (intersection.currentPhase === 'all_red') return false

  const lane = getVehicleLane(v)
  const isEW = intersection.currentPhase.startsWith('eastwest')

  // 直行车道：只在当前相位放行时通过
  if (lane === LaneType.STRAIGHT) {
    // 东西放行 ≠ 直行（东西左转/直行都能走这条道）
    // 实际规则：东西相位放行时，东西向直行车道通过
    return isEW // 简化：直行随当前方向走
  }

  // 左转车道：只在对应方向允许左转时通过
  if (lane === LaneType.LEFT_TURN) {
    // 左转需要专用相位（eastwest_left / northsouth_left）
    return intersection.currentPhase.endsWith('left')
  }

  // 右转车道：通常允许右转（不受红绿灯限制）
  if (lane === LaneType.RIGHT_TURN) {
    return true // 右转不受红灯限制
  }

  return false
}

// ---- 4. 车辆行进方向约束 ----
export enum VehicleAction {
  GO_STRAIGHT = 'straight',
  TURN_LEFT   = 'left',
  TURN_RIGHT  = 'right',
  STOP        = 'stop',
}

/**
 * 根据车辆所在车道和当前信号状态，决定车辆在路口的动作。
 * 只应用于 progress 在停止线范围内的车辆（如 0.4~0.6）。
 */
export function vehicleAction(
  v: Vehicle,
  intersection: Intersection,
): VehicleAction {
  const lane = getVehicleLane(v)

  // 红灯 / 设备异常 → 停车
  if (
    intersection.deviceStatus !== 'online' ||
    intersection.currentPhase === 'all_red' ||
    (lane !== LaneType.RIGHT_TURN && intersection.greenRemain <= 0)
  ) {
    // 右转不受红灯限制
    if (lane === LaneType.RIGHT_TURN) return VehicleAction.TURN_RIGHT
    return VehicleAction.STOP
  }

  switch (lane) {
    case LaneType.LEFT_TURN:  return VehicleAction.TURN_LEFT
    case LaneType.STRAIGHT:   return VehicleAction.GO_STRAIGHT
    case LaneType.RIGHT_TURN: return VehicleAction.TURN_RIGHT
    default:                  return VehicleAction.STOP
  }
}

// ---- 5. 停止线范围 ----
/** progress 在此范围内视为"接近路口"，需要检查红绿灯 */
export function isNearStopLine(progress: number): boolean {
  return progress > 0.35 && progress < 0.65
}

/** 停车时 progress 冻结在此值 */
export const STOP_PROGRESS = 0.45
