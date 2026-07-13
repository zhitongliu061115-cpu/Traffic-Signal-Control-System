import type { SignalPhase, SimSignalState } from '@/types/traffic'

const SIGNAL_PHASE_MAP: Readonly<Record<string, SignalPhase>> = {
  ETWT: 'eastwest_straight',
  ew_straight: 'eastwest_straight',
  NTST: 'northsouth_straight',
  ns_straight: 'northsouth_straight',
  ELWL: 'eastwest_left',
  ew_left: 'eastwest_left',
  NLSL: 'northsouth_left',
  ns_left: 'northsouth_left',
  all_red: 'all_red',
}

/** Unknown or missing phases fail closed instead of accidentally showing a green movement. */
export function toSignalPhase(phaseCode: string | null | undefined): SignalPhase {
  if (!phaseCode) return 'all_red'
  return SIGNAL_PHASE_MAP[phaseCode] ?? 'all_red'
}

/** Remaining time is only displayable when the simulation explicitly supplies it. */
export function signalRemainingSec(signal: SimSignalState | null | undefined): number | null {
  const remaining = signal?.remainingSec
  if (typeof remaining !== 'number' || !Number.isFinite(remaining)) return null
  return Math.max(0, remaining)
}
