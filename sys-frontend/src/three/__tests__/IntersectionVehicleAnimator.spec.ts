import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import type { SimRoadnetResponse, SimVehicleState } from '@/types/traffic'
import { IntersectionVehicleAnimator, removeBakedVehicleRootMotion } from '../IntersectionVehicleAnimator'

const roadnet: SimRoadnetResponse = {
  sceneId: 'test-scene',
  intersections: [
    { id: 'center', x: 100, y: 200, virtual: false },
    { id: 'east', x: 300, y: 200, virtual: false },
  ],
  roads: [
    { id: 'eastbound', from: 'center', to: 'east', points: [{ x: 100, y: 200 }, { x: 300, y: 200 }], laneCount: 3 },
  ],
  roadLinks: [],
  phases: [],
}

function vehicle(x: number): SimVehicleState {
  return {
    id: 'vehicle-1',
    roadId: 'eastbound',
    lane: 1,
    x,
    y: 200,
    angle: 0,
    speed: 8,
  }
}

describe('IntersectionVehicleAnimator CityFlow mode', () => {
  it('removes baked GLB translation and rotation before runtime placement', () => {
    const scene = new THREE.Group()
    const car = new THREE.Group()
    car.name = 'red car'
    car.position.set(-30, 0, 0)
    car.rotation.y = Math.PI / 2
    scene.add(car)
    const clip = new THREE.AnimationClip('drive', 1, [
      new THREE.VectorKeyframeTrack('red car.position', [0, 1], [-30, 0, 0, 30, 0, 0]),
      new THREE.QuaternionKeyframeTrack('red car.quaternion', [0, 1], [0, 0.707, 0, 0.707, 0, 0, 0, 1]),
      new THREE.VectorKeyframeTrack('red car.scale', [0, 1], [1, 1, 1, 1, 1, 1]),
    ])

    const runtimeClip = removeBakedVehicleRootMotion(scene, clip)

    expect(car.position.toArray()).toEqual([0, 0, 0])
    expect(car.quaternion.toArray()).toEqual([0, 0, 0, 1])
    expect(runtimeClip.tracks.map((track) => track.name)).toEqual(['red car.scale'])
  })

  it('reuses a vehicle object by ID and interpolates between simulation frames', () => {
    const animator = new IntersectionVehicleAnimator('center')
    ;(animator as unknown as { loaded: boolean }).loaded = true

    const firstFrame = [vehicle(120)]
    animator.updateFromCityFlow(firstFrame, roadnet, 'center', 1000)
    const car = animator.group.children[0]

    expect(car?.position.x).toBe(20)

    const secondFrame = [vehicle(140)]
    animator.updateFromCityFlow(secondFrame, roadnet, 'center', 1200)
    expect(animator.group.children[0]).toBe(car)
    expect(car?.position.x).toBe(20)

    animator.updateFromCityFlow(secondFrame, roadnet, 'center', 1290)
    expect(car?.position.x).toBeCloseTo(30)

    animator.updateFromCityFlow(secondFrame, roadnet, 'center', 1380)
    expect(car?.position.x).toBeCloseTo(40)
  })

  it('recovers from a long frame gap using the stable cadence instead of the gap length', () => {
    const animator = new IntersectionVehicleAnimator('center')
    ;(animator as unknown as { loaded: boolean }).loaded = true

    const firstFrame = [vehicle(120)]
    const secondFrame = [vehicle(140)]
    const recoveredFrame = [vehicle(160)]
    animator.updateFromCityFlow(firstFrame, roadnet, 'center', 1000)
    animator.updateFromCityFlow(secondFrame, roadnet, 'center', 1500)
    animator.updateFromCityFlow(secondFrame, roadnet, 'center', 6500)
    animator.updateFromCityFlow(recoveredFrame, roadnet, 'center', 6500)
    const car = animator.group.children[0]

    expect(car?.position.x).toBeCloseTo(40)
    animator.updateFromCityFlow(recoveredFrame, roadnet, 'center', 6725)
    expect(car?.position.x).toBeCloseTo(50)
    animator.updateFromCityFlow(recoveredFrame, roadnet, 'center', 6950)
    expect(car?.position.x).toBeCloseTo(60)
  })

  it('uses the approach-lane movement and keeps it through the laneLink', () => {
    const animator = new IntersectionVehicleAnimator('center')
    const straight = new THREE.Group(); straight.name = 'straight-model'
    const left = new THREE.Group(); left.name = 'left-model'
    const clip = new THREE.AnimationClip('test', 1, [])
    ;(animator as unknown as {
      loaded: boolean
      templates: Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>
    }).loaded = true
    ;(animator as unknown as {
      templates: Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>
    }).templates = new Map([
      ['straight', { scene: straight, clip }],
      ['left_turn', { scene: left, clip }],
    ])

    const turningRoadnet: SimRoadnetResponse = {
      ...roadnet,
      roadLinks: [{
        intersectionId: 'center',
        index: 0,
        fromRoadId: 'eastbound',
        toRoadId: 'eastbound',
        type: 'turn_left',
        laneLinks: [{
          id: 'eastbound_1_TO_eastbound_1',
          startLaneIndex: 1,
          endLaneIndex: 1,
          points: [{ x: 120, y: 200 }, { x: 130, y: 200 }],
        }],
      }],
    }

    const firstFrame = [vehicle(120)]
    animator.updateFromCityFlow(firstFrame, turningRoadnet, 'center', 1000)
    const car = animator.group.children[0] as THREE.Group
    expect(car.userData.vehicleClip).toBe('left_turn')

    const turningFrame = [{
      ...vehicle(0),
      drivableType: 'lane_link' as const,
      drivableId: 'eastbound_1_TO_eastbound_1',
      distance: 5,
    }]
    animator.updateFromCityFlow(turningFrame, turningRoadnet, 'center', 1200)

    expect(animator.group.children[0]).toBe(car)
    expect(car.userData.vehicleClip).toBe('left_turn')
  })

  it('uses the stop model for a stopped vehicle on an approach lane', () => {
    const animator = new IntersectionVehicleAnimator('center')
    const straight = new THREE.Group(); straight.name = 'straight-model'
    const stop = new THREE.Group(); stop.name = 'stop-model'
    const clip = new THREE.AnimationClip('test', 1, [])
    ;(animator as unknown as {
      loaded: boolean
      templates: Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>
    }).loaded = true
    ;(animator as unknown as {
      templates: Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>
    }).templates = new Map([
      ['straight', { scene: straight, clip }],
      ['stop', { scene: stop, clip }],
    ])

    animator.updateFromCityFlow([{ ...vehicle(120), speed: 0 }], roadnet, 'center', 1000)

    const car = animator.group.children[0] as THREE.Group
    expect(car.userData.vehicleClip).toBe('stop')
  })

  it('batches dense live traffic into a fixed number of instanced draw layers', () => {
    const animator = new IntersectionVehicleAnimator('center')
    const template = new THREE.Group()
    template.add(
      new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial()),
      new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial()),
    )
    const clip = new THREE.AnimationClip('test', 1, [])
    ;(animator as unknown as {
      loaded: boolean
      templates: Map<string, { scene: THREE.Group; clip: THREE.AnimationClip }>
      initializeLiveCarInstances: () => void
    }).templates = new Map([['straight', { scene: template, clip }]])
    ;(animator as unknown as { initializeLiveCarInstances: () => void }).initializeLiveCarInstances()
    ;(animator as unknown as { loaded: boolean }).loaded = true

    const vehicles = Array.from({ length: 40 }, (_, index) => ({
      ...vehicle(120 + index),
      id: `vehicle-${index}`,
    }))
    animator.updateFromCityFlow(vehicles, roadnet, 'center', 1000)

    const layers = animator.group.children.filter((child) => child instanceof THREE.InstancedMesh)
    expect(layers).toHaveLength(2)
    expect(layers.every((layer) => layer.count === 40)).toBe(true)
  })

  it('removes vehicles missing from the next complete CityFlow frame', () => {
    const animator = new IntersectionVehicleAnimator('center')
    ;(animator as unknown as { loaded: boolean }).loaded = true

    animator.updateFromCityFlow([vehicle(120)], roadnet, 'center', 1000)
    animator.updateFromCityFlow([], roadnet, 'center', 1500)

    expect(animator.group.children).toHaveLength(0)
  })

  it('removes a vehicle immediately after it leaves this intersection view', () => {
    const animator = new IntersectionVehicleAnimator('center')
    ;(animator as unknown as { loaded: boolean }).loaded = true

    animator.updateFromCityFlow([vehicle(120)], roadnet, 'center', 1000)
    animator.updateFromCityFlow([vehicle(400)], roadnet, 'center', 1500)

    expect(animator.group.children).toHaveLength(0)
  })
})
