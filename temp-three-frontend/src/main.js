import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import './styles.css';

const DEFAULT_SCENE_ID = 'jinan_3x4_stress';
const DEFAULT_API_BASE = 'http://127.0.0.1:8080';
const API_BASE = new URLSearchParams(window.location.search).get('api') || DEFAULT_API_BASE;
const WS_BASE = API_BASE.replace(/^http/, 'ws');

const levelColors = {
  free: 0x33d17a,
  slow: 0xffb020,
  jammed: 0xff4d5a,
  unknown: 0x7f8c9a,
};

const LANE_WIDTH = 10;
const ROAD_SHOULDER = 4;
const VISUAL_LANES_PER_DIRECTION = 3;
const MEDIAN_GAP = 7;
const VEHICLE_WIDTH = 6.2;
const VEHICLE_LENGTH = 13.5;
const VEHICLE_HEIGHT = 5.2;
const MAX_LINEAR_INTERPOLATION = 90;
const FRAME_INTERVAL_MS = 200;
const VEHICLE_LERP_MS = 180;
const MIN_DYNAMIC_LERP_MS = 120;
const MAX_DYNAMIC_LERP_MS = 2500;

const els = {
  canvas: document.querySelector('#trafficCanvas'),
  statusDot: document.querySelector('#statusDot'),
  statusText: document.querySelector('#statusText'),
  sceneSelect: document.querySelector('#sceneSelect'),
  controllerSelect: document.querySelector('#controllerSelect'),
  speedSelect: document.querySelector('#speedSelect'),
  recreateButton: document.querySelector('#recreateButton'),
  activeController: document.querySelector('#activeController'),
  cityflowApplyState: document.querySelector('#cityflowApplyState'),
  cityflowAppliedPhase: document.querySelector('#cityflowAppliedPhase'),
  scheduledDepartureCount: document.querySelector('#scheduledDepartureCount'),
  vehicleCount: document.querySelector('#vehicleCount'),
  queueCount: document.querySelector('#queueCount'),
  avgSpeed: document.querySelector('#avgSpeed'),
  throughput: document.querySelector('#throughput'),
  toggleRunButton: document.querySelector('#toggleRunButton'),
  stopButton: document.querySelector('#stopButton'),
};

const renderer = new THREE.WebGLRenderer({
  canvas: els.canvas,
  antialias: true,
  alpha: false,
});
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setClearColor(0x101418, 1);

const scene = new THREE.Scene();
scene.fog = new THREE.Fog(0x101418, 2200, 5200);

const camera = new THREE.OrthographicCamera(-100, 100, 100, -100, 0.1, 6000);
camera.position.set(0, 1700, 900);
camera.lookAt(0, 0, 0);

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.enableRotate = true;
controls.enablePan = true;
controls.enableZoom = true;
controls.minZoom = 0.25;
controls.maxZoom = 5;
controls.mouseButtons = {
  LEFT: THREE.MOUSE.PAN,
  MIDDLE: THREE.MOUSE.DOLLY,
  RIGHT: THREE.MOUSE.ROTATE,
};
controls.touches = {
  ONE: THREE.TOUCH.PAN,
  TWO: THREE.TOUCH.DOLLY_ROTATE,
};

const root = new THREE.Group();
const roadGroup = new THREE.Group();
const intersectionGroup = new THREE.Group();
const vehicleGroup = new THREE.Group();
const signalGroup = new THREE.Group();
scene.add(root);
root.add(roadGroup, intersectionGroup, signalGroup, vehicleGroup);

const ambient = new THREE.AmbientLight(0xffffff, 0.72);
const directional = new THREE.DirectionalLight(0xffffff, 1.2);
directional.position.set(500, 900, 300);
scene.add(ambient, directional);

let roadnet = null;
let activeSceneId = DEFAULT_SCENE_ID;
let sid = null;
let ws = null;
let frameCount = 0;
let lastFrameAt = 0;
let lastMessageAt = 0;
let lastVisualSimTime = null;
let observedFrameIntervalMs = FRAME_INTERVAL_MS;
let roadMeshesById = new Map();
let intersectionById = new Map();
let roadById = new Map();
let phaseByIntersectionAndIndex = new Map();
let roadLinkByIntersectionAndIndex = new Map();
let signalApproachesByIntersection = new Map();
let vehiclesById = new Map();
let bounds = { minX: 0, maxX: 1, minZ: 0, maxZ: 1 };
let simulationState = 'booting';
let controlBusy = false;
let activeControllerType = 'traffic-r';
let latestSignals = [];

const controllerLabels = {
  'traffic-r': 'RL',
  'max-pressure': 'Max Pressure',
  'fixed-time': 'Fixed Time',
};

function worldPoint(point) {
  return new THREE.Vector3(point.x, 0, -point.y);
}

function setStatus(text, mode = 'loading') {
  els.statusText.textContent = text;
  els.statusDot.className = `dot ${mode}`;
}

function selectedControllerType() {
  return els.controllerSelect?.value || 'traffic-r';
}

function selectedSceneId() {
  return els.sceneSelect?.value || DEFAULT_SCENE_ID;
}

function selectedSimulationSpeed() {
  const value = Number(els.speedSelect?.value || 20);
  return Number.isFinite(value) && value > 0 ? value : 20;
}

function selectedWarmupSeconds() {
  return 0;
}

function controllerLabel(controllerType) {
  return controllerLabels[controllerType] || controllerType || 'Unknown';
}

function resetDecisionPanel(controllerType = selectedControllerType()) {
  els.activeController.textContent = controllerLabel(controllerType);
  els.cityflowApplyState.textContent = 'Waiting for CityFlow';
  els.cityflowAppliedPhase.textContent = 'Waiting';
}

function setSimulationState(nextState) {
  simulationState = nextState;
  const hasSession = Boolean(sid);
  const isFinished = nextState === 'finished';
  const isRunning = nextState === 'running';

  els.toggleRunButton.disabled = !hasSession || isFinished;
  els.stopButton.disabled = !hasSession || isFinished;
  els.recreateButton.disabled = isRunning || controlBusy;
  els.sceneSelect.disabled = isRunning || controlBusy;
  els.controllerSelect.disabled = isRunning || controlBusy;
  els.speedSelect.disabled = isRunning || controlBusy;
  els.toggleRunButton.textContent = isRunning ? '暂停' : '启动';
  els.toggleRunButton.classList.toggle('running', isRunning);
  els.stopButton.classList.toggle('stopped', isFinished);
}

function unwrapApiResponse(payload) {
  if (payload && typeof payload === 'object' && 'success' in payload && 'data' in payload) {
    if (!payload.success) {
      throw new Error(payload.message || '后端返回失败');
    }
    return payload.data;
  }
  return payload;
}

async function apiFetch(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(json?.message || `${response.status} ${response.statusText}`);
  }
  return unwrapApiResponse(json);
}

function clearGroup(group) {
  while (group.children.length > 0) {
    const child = group.children.pop();
    child.traverse((obj) => {
      obj.geometry?.dispose?.();
      if (Array.isArray(obj.material)) {
        obj.material.forEach((material) => material.dispose?.());
      } else {
        obj.material?.dispose?.();
      }
    });
  }
}

function computeBounds() {
  const points = roadnet.roads.flatMap((road) => road.points.map(worldPoint));
  bounds = points.reduce(
    (acc, point) => ({
      minX: Math.min(acc.minX, point.x),
      maxX: Math.max(acc.maxX, point.x),
      minZ: Math.min(acc.minZ, point.z),
      maxZ: Math.max(acc.maxZ, point.z),
    }),
    { minX: Infinity, maxX: -Infinity, minZ: Infinity, maxZ: -Infinity },
  );
}

function fitCamera() {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxZ - bounds.minZ);
  const centerX = (bounds.minX + bounds.maxX) / 2;
  const centerZ = (bounds.minZ + bounds.maxZ) / 2;
  const aspect = els.canvas.clientWidth / Math.max(1, els.canvas.clientHeight);
  const paddedHeight = Math.max(height * 1.18, (width * 1.18) / aspect);
  const paddedWidth = paddedHeight * aspect;

  camera.left = -paddedWidth / 2;
  camera.right = paddedWidth / 2;
  camera.top = paddedHeight / 2;
  camera.bottom = -paddedHeight / 2;
  camera.position.set(centerX, 1800, centerZ + 0.01);
  camera.lookAt(centerX, 0, centerZ);
  controls.target.set(centerX, 0, centerZ);
  controls.update();
  camera.updateProjectionMatrix();
}

function makeRoadSegmentMesh(start, end, laneCount, color, y = 0) {
  const direction = new THREE.Vector3().subVectors(end, start);
  const length = Math.max(direction.length(), 0.001);
  const width = roadWidth(laneCount);
  const geometry = new THREE.BoxGeometry(width, 4, length);
  const material = new THREE.MeshStandardMaterial({
    color,
    roughness: 0.78,
    metalness: 0.08,
  });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.position.copy(start).add(end).multiplyScalar(0.5);
  mesh.position.y = y;
  mesh.rotation.y = Math.atan2(direction.x, direction.z);
  return mesh;
}

function roadWidth(laneCount) {
  return Math.max(1, laneCount) * LANE_WIDTH + ROAD_SHOULDER * 2;
}

function visualLaneCount() {
  return VISUAL_LANES_PER_DIRECTION;
}

function visualRoadWidth() {
  return roadWidth(visualLaneCount());
}

function visualRoadOffset(road) {
  if (!road || !road.points || road.points.length < 2) return new THREE.Vector3();
  const points = road.points.map(worldPoint);
  const direction = new THREE.Vector3().subVectors(points[points.length - 1], points[0]).setY(0);
  if (direction.lengthSq() < 0.001) return new THREE.Vector3();
  direction.normalize();
  const side = new THREE.Vector3(-direction.z, 0, direction.x).normalize();
  return side.multiplyScalar(visualRoadWidth() / 2 + MEDIAN_GAP / 2);
}

function visualRoadPoints(road) {
  const offset = visualRoadOffset(road);
  return road.points.map((point) => worldPoint(point).add(offset));
}

function drawIntersections() {
  const realIntersections = roadnet.intersections.filter((item) => !item.virtual);
  const virtualIntersections = roadnet.intersections.filter((item) => item.virtual);

  for (const intersection of realIntersections) {
    const point = worldPoint(intersection);
    const geometry = new THREE.CylinderGeometry(22, 22, 8, 24);
    const material = new THREE.MeshStandardMaterial({
      color: 0xd9e4ef,
      roughness: 0.55,
      metalness: 0.12,
    });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(point.x, 8, point.z);
    intersectionGroup.add(mesh);
    intersectionById.set(intersection.id, { ...intersection, point, mesh });
  }

  for (const intersection of virtualIntersections) {
    const point = worldPoint(intersection);
    const geometry = new THREE.CylinderGeometry(8, 8, 3, 16);
    const material = new THREE.MeshBasicMaterial({ color: 0x50606d });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(point.x, 5, point.z);
    intersectionGroup.add(mesh);
    intersectionById.set(intersection.id, { ...intersection, point, mesh });
  }
}

function drawRoads() {
  for (const road of roadnet.roads) {
    roadById.set(road.id, road);
    const meshes = [];
    const points = visualRoadPoints(road);

    for (let i = 0; i < points.length - 1; i += 1) {
      const mesh = makeRoadSegmentMesh(points[i], points[i + 1], visualLaneCount(), levelColors.unknown, 0);
      mesh.userData.roadId = road.id;
      roadGroup.add(mesh);
      meshes.push(mesh);

      const laneMark = makeLaneMark(points[i], points[i + 1], visualLaneCount());
      for (const mark of laneMark) {
        roadGroup.add(mark);
      }
    }

    roadMeshesById.set(road.id, meshes);
  }
}

function makeLaneMark(start, end, laneCount) {
  if (laneCount < 1) return [];
  const direction = new THREE.Vector3().subVectors(end, start);
  const length = Math.max(direction.length(), 0.001);
  const marks = [];
  const side = new THREE.Vector3(-direction.z, 0, direction.x).normalize();

  for (let lane = 0; lane < laneCount; lane += 1) {
    const offset = laneOffset(lane, laneCount);
    const center = start.clone().add(end).multiplyScalar(0.5).addScaledVector(side, offset);
    const geometry = new THREE.BoxGeometry(1.4, 1, length * 0.8);
    const material = new THREE.MeshBasicMaterial({
      color: lane === 0 ? 0xd7e1ea : 0x1f272e,
      transparent: true,
      opacity: lane === 0 ? 0.45 : 0.62,
    });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.copy(center);
    mesh.position.y = 4.5;
    mesh.rotation.y = Math.atan2(direction.x, direction.z);
    marks.push(mesh);
  }

  if (laneCount > 1) {
    for (let boundary = 1; boundary < laneCount; boundary += 1) {
      const offset = (boundary - laneCount / 2) * LANE_WIDTH;
      const center = start.clone().add(end).multiplyScalar(0.5).addScaledVector(side, offset);
      const geometry = new THREE.BoxGeometry(0.8, 1, length * 0.9);
      const material = new THREE.MeshBasicMaterial({
        color: 0x9aa8b3,
        transparent: true,
        opacity: 0.28,
      });
      const mesh = new THREE.Mesh(geometry, material);
      mesh.position.copy(center);
      mesh.position.y = 5;
      mesh.rotation.y = Math.atan2(direction.x, direction.z);
      marks.push(mesh);
    }
  }

  return marks;
}

function laneOffset(laneIndex, laneCount) {
  const lane = Math.max(0, Math.min(Number(laneIndex) || 0, Math.max(0, laneCount - 1)));
  return (lane - (Math.max(1, laneCount) - 1) / 2) * LANE_WIDTH;
}

function indexSignalConfig() {
  for (const phase of roadnet.phases) {
    phaseByIntersectionAndIndex.set(`${phase.intersectionId}:${phase.phaseIndex}`, phase);
  }
  for (const roadLink of roadnet.roadLinks) {
    roadLinkByIntersectionAndIndex.set(`${roadLink.intersectionId}:${roadLink.index}`, roadLink);
  }
  signalApproachesByIntersection = buildSignalApproaches();
}

function buildSignalApproaches() {
  const approachesByIntersection = new Map();
  const seenKeys = new Set();

  for (const roadLink of roadnet.roadLinks) {
    const intersection = intersectionById.get(roadLink.intersectionId);
    const road = roadById.get(roadLink.fromRoadId);
    if (!intersection || !road || !road.points || road.points.length < 2) continue;

    const key = `${roadLink.intersectionId}:${roadLink.fromRoadId}`;
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);

    const points = visualRoadPoints(road);
    const first = points[0];
    const last = points[points.length - 1];
    const near = first.distanceTo(intersection.point) <= last.distanceTo(intersection.point) ? first : last;
    const far = near === first ? points[1] : points[points.length - 2];
    const direction = new THREE.Vector3().subVectors(intersection.point, far).setY(0);
    if (direction.lengthSq() < 0.001) continue;
    direction.normalize();

    const side = new THREE.Vector3(-direction.z, 0, direction.x);
    const stopDistance = 42 + Math.max(0, visualLaneCount() - 1) * 1.5;
    const sideOffset = visualRoadWidth() / 2 + 12;
    const position = near.clone()
      .addScaledVector(direction, -stopDistance)
      .addScaledVector(side, sideOffset);

    if (!approachesByIntersection.has(roadLink.intersectionId)) {
      approachesByIntersection.set(roadLink.intersectionId, []);
    }
    approachesByIntersection.get(roadLink.intersectionId).push({
      roadId: roadLink.fromRoadId,
      position,
      direction,
      laneCount: visualLaneCount(),
    });
  }

  return approachesByIntersection;
}

function updateRoadStates(roads = []) {
  for (const road of roads) {
    const color = levelColors[road.level] ?? levelColors.unknown;
    const meshes = roadMeshesById.get(road.id) || [];
    for (const mesh of meshes) {
      mesh.material.color.setHex(color);
      mesh.material.emissive?.setHex(road.level === 'jammed' ? 0x2c0508 : 0x000000);
    }
  }
}

function updateSignals(signals = []) {
  clearGroup(signalGroup);

  for (const signal of signals) {
    const intersection = intersectionById.get(signal.intersectionId);
    if (!intersection) continue;

    const phase = phaseByIntersectionAndIndex.get(`${signal.intersectionId}:${signal.phaseIndex}`);
    if (!phase) continue;

    const activeLinks = [];
    for (const index of phase.roadLinkIndexes || []) {
      const roadLink = roadLinkByIntersectionAndIndex.get(`${signal.intersectionId}:${index}`);
      if (!roadLink) continue;
      activeLinks.push(roadLink);
      highlightRoadDirection(roadLink.fromRoadId, 0x8dffb2);
      highlightRoadDirection(roadLink.toRoadId, 0x8dffb2);
      drawTurnHint(roadLink);
    }

    const approaches = signalApproachesByIntersection.get(signal.intersectionId) || [];
    const movementStates = movementStatesForActiveLinks(activeLinks);
    const highlightedRoads = new Set(activeLinks.map((roadLink) => roadLink.fromRoadId));
    for (const approach of approaches) {
      drawSignalHead(approach, movementStates.get(approach.roadId) || { straight: false, left: false }, signal);
    }
    drawPhaseRing(intersection.point, signal, highlightedRoads.size > 0);
  }
}

function movementStatesForActiveLinks(activeLinks) {
  const states = new Map();
  for (const roadLink of activeLinks) {
    if (roadLink.type === 'turn_right') continue;
    if (!states.has(roadLink.fromRoadId)) {
      states.set(roadLink.fromRoadId, { straight: false, left: false });
    }
    const state = states.get(roadLink.fromRoadId);
    if (roadLink.type === 'turn_left') {
      state.left = true;
    } else {
      state.straight = true;
    }
  }
  return states;
}

function drawSignalHead(approach, movementState, signal) {
  const group = new THREE.Group();
  group.position.copy(approach.position);
  group.rotation.y = Math.atan2(approach.direction.x, approach.direction.z);
  const hasGreen = movementState.straight || movementState.left;

  const arm = new THREE.Mesh(
    new THREE.BoxGeometry(visualRoadWidth() + 26, 2.6, 2.6),
    new THREE.MeshStandardMaterial({ color: 0x2d3942, roughness: 0.6 }),
  );
  arm.position.set(0, 54, 0);
  group.add(arm);

  drawMovementLamp(group, -11, movementState.left, 'left');
  drawMovementLamp(group, 11, movementState.straight, 'straight');

  const stopLine = new THREE.Mesh(
    new THREE.BoxGeometry(visualRoadWidth(), 2.5, 5),
    new THREE.MeshBasicMaterial({
      color: hasGreen ? 0x62ff99 : 0xff4d5a,
      transparent: true,
      opacity: 0.88,
    }),
  );
  stopLine.position.set(0, 7, 10);
  group.add(stopLine);
  group.userData.signalPhaseIndex = signal.phaseIndex;
  group.userData.roadId = approach.roadId;
  signalGroup.add(group);
}

function drawMovementLamp(group, x, isGreen, movement) {
  const lampColor = isGreen ? 0x26ff7a : 0xff3b4a;
  const housing = new THREE.Mesh(
    new THREE.BoxGeometry(19, 21, 8),
    new THREE.MeshStandardMaterial({
      color: isGreen ? 0x0c3d25 : 0x42151a,
      emissive: isGreen ? 0x16ff7a : 0xff3347,
      emissiveIntensity: 0.42,
      roughness: 0.42,
    }),
  );
  housing.position.set(x, 52, -8);
  group.add(housing);

  const lamp = new THREE.Mesh(
    new THREE.SphereGeometry(7.2, 24, 24),
    new THREE.MeshStandardMaterial({
      color: lampColor,
      emissive: lampColor,
      emissiveIntensity: isGreen ? 1.9 : 1.35,
      roughness: 0.28,
    }),
  );
  lamp.position.set(x, 52, -13);
  lamp.userData.signalLamp = true;
  group.add(lamp);
  drawLampArrow(group, x, lampColor, movement);
}

function drawLampArrow(group, x, color, movement) {
  const material = new THREE.MeshBasicMaterial({ color });
  const shaft = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.4, 10), material);
  shaft.position.set(x, 52, -20.5);
  shaft.rotation.x = Math.PI / 2;
  if (movement === 'left') {
    shaft.rotation.z = Math.PI / 4;
    shaft.position.x -= 1.8;
  }
  group.add(shaft);

  const head = new THREE.Mesh(new THREE.ConeGeometry(4.2, 7.5, 3), material);
  head.position.set(x, 52, -25.5);
  head.rotation.x = Math.PI / 2;
  if (movement === 'left') {
    head.rotation.z = Math.PI / 4;
    head.position.x -= 5.6;
    head.position.z += 2.8;
  }
  group.add(head);
}

function drawPhaseRing(point, signal, hasActivePhase) {
  const ring = new THREE.Mesh(
    new THREE.TorusGeometry(22, 2.4, 8, 28),
    new THREE.MeshBasicMaterial({
      color: hasActivePhase ? 0x69ffa3 : 0xff4d5a,
      transparent: true,
      opacity: 0.72,
    }),
  );
  ring.position.set(point.x, 35, point.z);
  ring.rotation.x = Math.PI / 2;
  ring.userData.signalPhaseIndex = signal.phaseIndex;
  signalGroup.add(ring);
}

function highlightRoadDirection(roadId, color) {
  const road = roadById.get(roadId);
  if (!road) return;
  const points = visualRoadPoints(road);
  for (let i = 0; i < points.length - 1; i += 1) {
    const mesh = makeRoadSegmentMesh(points[i], points[i + 1], visualLaneCount(), color, 9);
    mesh.scale.x = 0.48;
    mesh.scale.z = 0.7;
    mesh.material.transparent = true;
    mesh.material.opacity = 0.82;
    mesh.material.emissive = new THREE.Color(0x19a95c);
    mesh.material.emissiveIntensity = 0.7;
    signalGroup.add(mesh);
  }
}

function drawTurnHint(roadLink) {
  const fromRoad = roadById.get(roadLink.fromRoadId);
  const intersection = intersectionById.get(roadLink.intersectionId);
  if (!fromRoad || !intersection || !fromRoad.points || fromRoad.points.length < 2) return;

  const approach = (signalApproachesByIntersection.get(roadLink.intersectionId) || [])
    .find((item) => item.roadId === roadLink.fromRoadId);
  if (!approach) return;

  const group = new THREE.Group();
  const base = intersection.point.clone().addScaledVector(approach.direction, -24);
  group.position.set(base.x, 13, base.z);
  group.rotation.y = Math.atan2(approach.direction.x, approach.direction.z);

  const material = new THREE.MeshBasicMaterial({
    color: 0x8dffb2,
    transparent: true,
    opacity: 0.92,
  });

  const shaft = new THREE.Mesh(new THREE.BoxGeometry(4, 2, 26), material);
  shaft.position.set(0, 0, 0);
  group.add(shaft);

  const head = new THREE.Mesh(new THREE.ConeGeometry(8, 16, 3), material);
  head.position.set(0, 0, -18);
  head.rotation.x = Math.PI / 2;
  group.add(head);

  if (roadLink.type === 'turn_left' || roadLink.type === 'turn_right') {
    const turnSign = roadLink.type === 'turn_left' ? -1 : 1;
    const wing = new THREE.Mesh(new THREE.BoxGeometry(4, 2, 18), material);
    wing.position.set(turnSign * 9, 0, -12);
    wing.rotation.y = turnSign * Math.PI / 4;
    group.add(wing);
  }

  signalGroup.add(group);
}

function createVehicleMesh(vehicle) {
  const group = new THREE.Group();
  const bodyGeometry = new THREE.BoxGeometry(VEHICLE_WIDTH, VEHICLE_HEIGHT, VEHICLE_LENGTH);
  const material = new THREE.MeshStandardMaterial({
    color: 0x4cc9ff,
    roughness: 0.42,
    metalness: 0.18,
    emissive: 0x0b4c62,
    emissiveIntensity: 0.85,
  });
  const body = new THREE.Mesh(bodyGeometry, material);
  body.position.y = 2;
  body.userData.vehicleBody = true;
  group.add(body);

  const marker = new THREE.Mesh(
    new THREE.ConeGeometry(4.6, 8, 3),
    new THREE.MeshBasicMaterial({
      color: 0xffffff,
      transparent: true,
      opacity: 0.74,
    }),
  );
  marker.position.set(0, 7, -5.5);
  marker.rotation.x = Math.PI / 2;
  group.add(marker);

  group.position.copy(vehiclePosition(vehicle));
  group.rotation.y = vehicleRotation(vehicle);
  vehicleGroup.add(group);
  return group;
}

function vehiclePosition(vehicle) {
  const base = new THREE.Vector3(vehicle.x, 18, -vehicle.y);
  const road = roadById.get(vehicle.roadId);
  if (!road || !road.points || road.points.length < 2) return base;

  const roadDirection = roadDirectionAtPoint(road, base);
  const side = new THREE.Vector3(-roadDirection.z, 0, roadDirection.x).normalize();
  const offset = laneOffset(vehicle.lane, visualLaneCount());
  return base.add(visualRoadOffset(road)).addScaledVector(side, offset);
}

function degToRad(angle) {
  return THREE.MathUtils.degToRad(180 - angle);
}

function roadDirectionAtPoint(road, point) {
  const points = road.points.map(worldPoint);
  let bestStart = points[0];
  let bestEnd = points[1];
  let bestDistance = Infinity;

  for (let i = 0; i < points.length - 1; i += 1) {
    const start = points[i];
    const end = points[i + 1];
    const projection = closestPointOnSegment(point, start, end);
    const distance = projection.distanceToSquared(point);
    if (distance < bestDistance) {
      bestDistance = distance;
      bestStart = start;
      bestEnd = end;
    }
  }

  const direction = new THREE.Vector3().subVectors(bestEnd, bestStart).setY(0);
  if (direction.lengthSq() < 0.001) return new THREE.Vector3(0, 0, 1);
  return direction.normalize();
}

function closestPointOnSegment(point, start, end) {
  const segment = new THREE.Vector3().subVectors(end, start);
  const lengthSq = segment.lengthSq();
  if (lengthSq < 0.001) return start.clone();
  const t = THREE.MathUtils.clamp(new THREE.Vector3().subVectors(point, start).dot(segment) / lengthSq, 0, 1);
  return start.clone().addScaledVector(segment, t);
}

function updateVehicles(vehicles = []) {
  const now = performance.now();
  const activeIds = new Set();
  const lerpMs = THREE.MathUtils.clamp(
    Math.max(VEHICLE_LERP_MS, observedFrameIntervalMs * 1.05),
    MIN_DYNAMIC_LERP_MS,
    MAX_DYNAMIC_LERP_MS,
  );

  for (const vehicle of vehicles) {
    activeIds.add(vehicle.id);
    const target = vehiclePosition(vehicle);
    let entry = vehiclesById.get(vehicle.id);

    if (!entry) {
      const mesh = createVehicleMesh(vehicle);
      entry = {
        mesh,
        from: target.clone(),
        to: target.clone(),
        fromRotation: vehicleRotation(vehicle),
        toRotation: vehicleRotation(vehicle),
        roadId: vehicle.roadId,
        startAt: now,
        lastSeenAt: now,
        lerpMs,
      };
      vehiclesById.set(vehicle.id, entry);
    }

    const jumpDistance = entry.mesh.position.distanceTo(target);
    const changedRoad = entry.roadId && entry.roadId !== vehicle.roadId;
    entry.from = changedRoad || jumpDistance > MAX_LINEAR_INTERPOLATION ? target.clone() : entry.mesh.position.clone();
    entry.to = target;
    entry.fromRotation = entry.mesh.rotation.y;
    entry.toRotation = vehicleRotation(vehicle);
    entry.roadId = vehicle.roadId;
    entry.startAt = now;
    entry.lastSeenAt = now;
    entry.lerpMs = lerpMs;
    const body = entry.mesh.children.find((child) => child.userData.vehicleBody);
    if (body) {
      body.material.color.setHex(vehicle.speed < 1 ? 0xffd166 : 0x4cc9ff);
    }
  }

  for (const [id, entry] of vehiclesById) {
    if (!activeIds.has(id) && now - entry.lastSeenAt > 2500) {
      vehicleGroup.remove(entry.mesh);
      entry.mesh.traverse((obj) => {
        obj.geometry?.dispose?.();
        obj.material?.dispose?.();
      });
      vehiclesById.delete(id);
    }
  }
}

function vehicleRotation(vehicle) {
  const road = roadById.get(vehicle.roadId);
  if (!road || !road.points || road.points.length < 2) return degToRad(vehicle.angle || 0);
  const direction = roadDirectionAtPoint(road, new THREE.Vector3(vehicle.x, 18, -vehicle.y));
  return Math.atan2(direction.x, direction.z);
}

function updateMetrics(metrics = {}) {
  metrics = metrics || {};
  els.scheduledDepartureCount.textContent = numberText(metrics.scheduledDepartureCount);
  els.vehicleCount.textContent = numberText(metrics.activeVehicleCount ?? metrics.vehicleCount);
  els.queueCount.textContent = numberText(metrics.queueCount);
  els.avgSpeed.textContent = fixedText(metrics.avgSpeed);
  els.throughput.textContent = numberText(metrics.throughput);
}

function numberText(value) {
  return Number.isFinite(Number(value)) ? String(Math.round(Number(value))) : '0';
}

function fixedText(value) {
  return Number.isFinite(Number(value)) ? Number(value).toFixed(1) : '0.0';
}

function handleFrame(message) {
  if (message.type !== 'sim.frame') return;
  frameCount = message.seq ?? frameCount + 1;
  const now = performance.now();
  lastMessageAt = now;
  const frameSimTime = Number(message.simTime ?? message.data?.simTime);
  const isNewVisualFrame = !Number.isFinite(lastVisualSimTime)
    || !Number.isFinite(frameSimTime)
    || Math.abs(frameSimTime - lastVisualSimTime) > 0.0001;
  if (isNewVisualFrame && lastFrameAt) {
    observedFrameIntervalMs = THREE.MathUtils.clamp(now - lastFrameAt, MIN_DYNAMIC_LERP_MS, MAX_DYNAMIC_LERP_MS);
  }
  if (isNewVisualFrame) {
    lastFrameAt = now;
    lastVisualSimTime = frameSimTime;
    updateRoadStates(message.data?.roads);
    latestSignals = Array.isArray(message.data?.signals) ? message.data.signals : [];
    updateSignals(latestSignals);
    updateVehicles(message.data?.vehicles);
  }
  updateMetrics(message.data?.metrics);
  const firstSignal = message.data?.signals?.[0];
  const phaseText = firstSignal ? ` | phase ${firstSignal.phaseIndex}${firstSignal.phaseCode ? ` ${firstSignal.phaseCode}` : ''}` : '';
  els.statusText.title = `收到 ${message.data?.vehicles?.length ?? 0} 辆车，${message.data?.signals?.length ?? 0} 个信号灯状态`;
  setStatus(`sid ${message.sid} | frame ${frameCount} | t=${fixedText(message.simTime)}s${phaseText}`, 'ok');
}

function handleDecision(message) {
  if (message.type !== 'control.decision') return;
  const decisions = Array.isArray(message.data) ? message.data : [];
  if (decisions.length === 0) return;
  const selectedDecision = decisions.find((decision) => decision?.controllerType === 'traffic-r') || decisions[0];
  const controllerType = selectedDecision.controllerType || activeControllerType;
  const appliedPhase = selectedDecision.metadata?.cityflowAppliedPhaseIndex
    || selectedDecision.metadata?.cityflowPhaseIndex
    || selectedDecision.phaseIndex;
  const appliedPhaseId = selectedDecision.metadata?.cityflowAppliedPhaseId
    ?? selectedDecision.metadata?.cityflowPhaseId
    ?? (Number(appliedPhase) - 1);
  const phaseCode = selectedDecision.metadata?.businessPhaseCode || selectedDecision.phaseCode || '';
  els.activeController.textContent = controllerLabel(controllerType);
  els.cityflowApplyState.textContent = selectedDecision.metadata?.cityflowApplied
    ? `Applied ${decisions.length} intersections`
    : 'Waiting for CityFlow';
  els.cityflowAppliedPhase.textContent = `phaseIndex ${appliedPhase}${phaseCode ? ` ${phaseCode}` : ''} | phaseId ${appliedPhaseId}`;
}

async function startSimulation() {
  if (!sid || simulationState === 'running' || simulationState === 'finished') return;
  els.toggleRunButton.disabled = true;
  await apiFetch(`/api/v1/simulations/${sid}/start`, { method: 'POST', body: '{}' });
  setSimulationState('running');
  setStatus(`仿真运行中 | sid ${sid}`, 'ok');
}

async function pauseSimulation() {
  if (!sid || simulationState !== 'running') return;
  els.toggleRunButton.disabled = true;
  await apiFetch(`/api/v1/simulations/${sid}/pause`, { method: 'POST', body: '{}' });
  setSimulationState('paused');
  setStatus(`已暂停 | sid ${sid} | frame ${frameCount}`, 'loading');
}

async function stopSimulation() {
  if (!sid || simulationState === 'finished') return;
  els.stopButton.disabled = true;
  await apiFetch(`/api/v1/simulations/${sid}/stop`, { method: 'POST', body: '{}' });
  setSimulationState('finished');
  setStatus(`已停止 | sid ${sid}`, 'error');
}

function disconnectWebSocket() {
  if (!ws) return;
  const currentWs = ws;
  ws = null;
  currentWs.onclose = null;
  currentWs.onerror = null;
  currentWs.close();
}

function resetRuntimeState() {
  frameCount = 0;
  lastFrameAt = 0;
  lastMessageAt = 0;
  lastVisualSimTime = null;
  vehiclesById.forEach((entry) => {
    entry.mesh.geometry?.dispose?.();
    entry.mesh.material?.dispose?.();
    vehicleGroup.remove(entry.mesh);
  });
  vehiclesById.clear();
  updateMetrics(null);
}

function resetSceneState() {
  disconnectWebSocket();
  sid = null;
  resetRuntimeState();
  clearGroup(roadGroup);
  clearGroup(intersectionGroup);
  clearGroup(signalGroup);
  roadMeshesById.clear();
  intersectionById.clear();
  roadById.clear();
  phaseByIntersectionAndIndex.clear();
  roadLinkByIntersectionAndIndex.clear();
  signalApproachesByIntersection.clear();
}

async function recreateSimulation() {
  if (controlBusy || simulationState === 'running') return;
  controlBusy = true;
  els.recreateButton.disabled = true;
  try {
    if (sid && simulationState !== 'finished') {
      await apiFetch(`/api/v1/simulations/${sid}/stop`, { method: 'POST', body: '{}' });
    }
    if (selectedSceneId() !== activeSceneId) {
      resetSceneState();
      await loadRoadnetAndDraw();
    } else {
      disconnectWebSocket();
      sid = null;
      resetRuntimeState();
    }
    setSimulationState('booting');
    await createSimulation();
    await connectWebSocket();
  } catch (error) {
    console.error(error);
    setStatus(error.message || '切换控制方法失败', 'error');
  } finally {
    controlBusy = false;
    setSimulationState(sid ? simulationState : 'booting');
  }
}

async function toggleSimulation() {
  if (controlBusy) return;
  controlBusy = true;
  try {
    if (simulationState === 'running') {
      await pauseSimulation();
    } else {
      await startSimulation();
    }
  } catch (error) {
    console.error(error);
    setSimulationState(simulationState);
    setStatus(error.message || '控制仿真失败', 'error');
  } finally {
    controlBusy = false;
  }
}

async function loadRoadnetAndDraw() {
  setStatus('1/11 获取静态路网');
  activeSceneId = selectedSceneId();
  roadnet = await apiFetch(`/api/v1/scenes/${activeSceneId}/roadnet`);
  computeBounds();
  fitCamera();

  setStatus('2/11 绘制路口节点');
  drawIntersections();

  setStatus('3/11 绘制道路折线');
  setStatus('4/11 按 laneCount 调整道路宽度');
  drawRoads();
  indexSignalConfig();
}

async function createSimulation() {
  setStatus('5/11 创建仿真会话');
  activeControllerType = selectedControllerType();
  resetDecisionPanel(activeControllerType);
  const data = await apiFetch('/api/v1/simulations', {
    method: 'POST',
    body: JSON.stringify({
      sceneId: activeSceneId,
      speed: selectedSimulationSpeed(),
      warmupSeconds: selectedWarmupSeconds(),
      controllerType: activeControllerType,
    }),
  });
  sid = data.sid;
  activeControllerType = data.controllerType || activeControllerType;
  resetDecisionPanel(activeControllerType);
  setSimulationState('paused');
}

async function connectWebSocket() {
  setStatus('6/11 连接 WebSocket');
  ws = new WebSocket(`${WS_BASE}/ws/v1/simulations/${sid}`);

  ws.addEventListener('open', async () => {
    ws.send(
      JSON.stringify({
        v: '1.0',
        type: 'client.subscribe',
        sid,
        seq: 1,
        simTime: 0,
        sentAt: new Date().toISOString(),
        data: {
          topics: ['vehicles', 'roads', 'intersections', 'signals', 'metrics'],
          intervalMs: FRAME_INTERVAL_MS,
        },
      }),
    );
    setStatus(`会话已就绪，点击启动开始仿真 | sid ${sid}`, 'loading');
  });

  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    handleFrame(message);
    handleDecision(message);
  });

  ws.addEventListener('close', () => setStatus('WebSocket 已断开', 'error'));
  ws.addEventListener('error', () => setStatus('WebSocket 连接错误', 'error'));
}

async function bootstrap() {
  try {
    resize();
    await loadRoadnetAndDraw();
    await createSimulation();
    await connectWebSocket();
  } catch (error) {
    console.error(error);
    setStatus(error.message || '启动失败', 'error');
  }
}

function resize() {
  const width = els.canvas.clientWidth;
  const height = els.canvas.clientHeight;
  renderer.setSize(width, height, false);
  if (roadnet) fitCamera();
}

function animate() {
  requestAnimationFrame(animate);
  const now = performance.now();

  for (const entry of vehiclesById.values()) {
    const t = Math.min(1, (now - entry.startAt) / (entry.lerpMs || VEHICLE_LERP_MS));
    const eased = easeOutCubic(t);
    entry.mesh.position.lerpVectors(entry.from, entry.to, eased);
    entry.mesh.rotation.y = lerpAngle(entry.fromRotation ?? entry.mesh.rotation.y, entry.toRotation ?? entry.mesh.rotation.y, eased);
  }

  signalGroup.traverse((child) => {
    if (child.isMesh && child.userData.signalLamp) {
      const pulse = 1 + Math.sin(now / 220 + child.id) * 0.035;
      child.scale.setScalar(pulse);
    }
  });

  if (simulationState === 'running' && lastMessageAt && now - lastMessageAt > 5000 && ws?.readyState === WebSocket.OPEN) {
    setStatus(`等待后端推送 | last frame ${frameCount}`, 'loading');
  }

  controls.update();
  renderer.render(scene, camera);
}

function easeOutCubic(t) {
  return 1 - Math.pow(1 - t, 3);
}

function lerpAngle(from, to, t) {
  const delta = Math.atan2(Math.sin(to - from), Math.cos(to - from));
  return from + delta * t;
}

window.addEventListener('resize', resize);
els.controllerSelect.addEventListener('change', () => {
  resetDecisionPanel(selectedControllerType());
});
els.sceneSelect.addEventListener('change', () => {
  setStatus('Scene changed, click switch to recreate simulation', 'loading');
});
els.recreateButton.addEventListener('click', recreateSimulation);
els.toggleRunButton.addEventListener('click', toggleSimulation);
els.stopButton.addEventListener('click', async () => {
  try {
    await stopSimulation();
  } catch (error) {
    console.error(error);
    setStatus(error.message || '停止仿真失败', 'error');
  }
});
window.addEventListener('keydown', (event) => {
  if (event.key.toLowerCase() === 'r') {
    fitCamera();
  }
  if (event.code === 'Space') {
    event.preventDefault();
    toggleSimulation();
  }
});
setSimulationState('booting');
animate();
bootstrap();
