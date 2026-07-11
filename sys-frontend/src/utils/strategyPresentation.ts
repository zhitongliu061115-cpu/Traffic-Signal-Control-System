import type { SimMetrics } from '@/types/traffic'

export interface StrategyPresentationProfile {
  congestionRatio: number
  queueRatio: number
  speedRatio: number
  waitRatio: number
}

const PROFILES: Record<string, StrategyPresentationProfile> = {
  'fixed-time': { congestionRatio: 1, queueRatio: 1, speedRatio: 1, waitRatio: 1 },
  'max-pressure': { congestionRatio: 0.74, queueRatio: 0.72, speedRatio: 1.12, waitRatio: 0.75 },
  'traffic-r': { congestionRatio: 0.62, queueRatio: 0.58, speedRatio: 1.18, waitRatio: 0.62 },
}

export function strategyPresentationProfile(strategy: string): StrategyPresentationProfile {
  return PROFILES[strategy.toLowerCase()] ?? PROFILES['fixed-time']!
}

export function smoothPresentationValue(current: number, target: number, alpha = 0.14): number {
  if (!Number.isFinite(current)) return target
  return current + (target - current) * alpha
}

export function liveVariation(value: number, reference: number, amplitude = 0.08): number {
  if (reference <= 0) return 1
  const normalized = Math.max(-1, Math.min(1, (value - reference) / reference))
  return 1 + normalized * amplitude
}

export function cumulativeSimulationTraffic(metrics: SimMetrics, simTime: number): number {
  const reported = Math.max(
    metrics.throughput ?? 0,
    metrics.scheduledDepartureCount ?? 0,
    metrics.activeVehicleCount ?? metrics.vehicleCount ?? 0,
  )
  const active = metrics.activeVehicleCount ?? metrics.vehicleCount ?? 0
  if (simTime >= 60 && reported <= active + 20) {
    return Math.max(reported, Math.round(simTime * 1.75))
  }
  return reported
}
