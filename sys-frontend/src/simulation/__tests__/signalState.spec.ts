import { describe, expect, it } from 'vitest'
import { signalRemainingSec, toSignalPhase } from '@/simulation/signalState'

describe('simulation signal presentation', () => {
  it('maps supported CityFlow phase codes', () => {
    expect(toSignalPhase('ETWT')).toBe('eastwest_straight')
    expect(toSignalPhase('NTST')).toBe('northsouth_straight')
    expect(toSignalPhase('ELWL')).toBe('eastwest_left')
    expect(toSignalPhase('NLSL')).toBe('northsouth_left')
  })

  it('fails closed for missing or unknown phases', () => {
    expect(toSignalPhase(undefined)).toBe('all_red')
    expect(toSignalPhase('yellow_transition')).toBe('all_red')
  })

  it('does not invent a countdown when the backend omits it', () => {
    expect(signalRemainingSec(undefined)).toBeNull()
    expect(signalRemainingSec({ intersectionId: 'i1', phaseIndex: 2, phaseCode: 'ETWT' })).toBeNull()
  })

  it('normalizes a supplied remaining time', () => {
    expect(signalRemainingSec({ intersectionId: 'i1', phaseIndex: 2, phaseCode: 'ETWT', remainingSec: 12.5 })).toBe(12.5)
    expect(signalRemainingSec({ intersectionId: 'i1', phaseIndex: 2, phaseCode: 'ETWT', remainingSec: -1 })).toBe(0)
  })
})
