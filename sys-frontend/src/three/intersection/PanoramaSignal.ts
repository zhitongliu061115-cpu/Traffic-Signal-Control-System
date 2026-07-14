import type { SignalPhase } from '@/types/traffic'

export type ApproachDirection = 'north' | 'east' | 'south' | 'west'
export type SignalMovement = 'left' | 'straight' | 'right'

export interface MovementSignalState {
  left: boolean
  straight: boolean
  right: boolean
}

export const APPROACH_LABELS: Readonly<Record<ApproachDirection, string>> = {
  north: '北进口',
  east: '东进口',
  south: '南进口',
  west: '西进口',
}

export function movementSignalState(
  phase: SignalPhase,
  direction: ApproachDirection,
  online: boolean,
): MovementSignalState {
  if (!online) {
    return { left: false, straight: false, right: false }
  }

  if (phase === 'all_red') {
    // In the bundled CityFlow roadnet, phase 1 blocks straight/left links but keeps all right turns open.
    return { left: false, straight: false, right: true }
  }

  const isEastWest = direction === 'east' || direction === 'west'
  const phaseMatchesDirection = phase.startsWith(isEastWest ? 'eastwest' : 'northsouth')

  return {
    left: phaseMatchesDirection && phase.endsWith('left'),
    straight: phaseMatchesDirection && phase.endsWith('straight'),
    // The current CityFlow roadnet allows all four right-turn links in every business phase.
    right: true,
  }
}

export function cameraCompassRotationDeg(viewX: number, viewZ: number): number {
  const headingDeg = (Math.atan2(viewX, -viewZ) * 180) / Math.PI
  return -headingDeg
}
