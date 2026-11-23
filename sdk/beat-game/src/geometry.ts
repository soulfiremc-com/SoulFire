import type {
  BeatGameBlockPosition,
  BeatGameEyeSample,
  BeatGamePosition,
  BeatGameRotation,
  EyeTriangulation,
} from "./model.js";

const RADIANS_PER_DEGREE = Math.PI / 180;
const DEGREES_PER_RADIAN = 180 / Math.PI;
const MINIMUM_BASELINE = 8;
const MINIMUM_INTERSECTION_SINE = 0.03;
// Overworld strongholds start 1280 blocks from the origin. Two throws 17
// blocks apart and 2.5 degrees off crossed 550 blocks out in a live run,
// and the bot searched there for hours. A crossing that close is noise.
const MINIMUM_STRONGHOLD_ORIGIN_DISTANCE = 1100;
export const NETHER_PORTAL_FRAME_OBSIDIAN_COUNT = 10 as const;

export interface PortalFrame {
  readonly origin: BeatGameBlockPosition;
  readonly axis: "x" | "z";
  readonly blocks: readonly BeatGameBlockPosition[];
  readonly interior: readonly BeatGameBlockPosition[];
}

export interface NetherPortalFrameMatch {
  readonly frame: PortalFrame;
  readonly matchingBlocks: number;
}

export function rotationToward(
  origin: BeatGamePosition,
  target: Pick<BeatGamePosition, "x" | "y" | "z">,
): BeatGameRotation {
  const dx = target.x - origin.x;
  const dy = target.y - origin.y;
  const dz = target.z - origin.z;
  const horizontalDistance = Math.hypot(dx, dz);
  return {
    yaw: normalizeDegrees(Math.atan2(-dx, dz) * DEGREES_PER_RADIAN),
    pitch: clamp(-Math.atan2(dy, horizontalDistance) * DEGREES_PER_RADIAN, -90, 90),
  };
}

export function horizontalDirection(
  from: Pick<BeatGamePosition, "x" | "z">,
  to: Pick<BeatGamePosition, "x" | "z">,
): Readonly<{ x: number; z: number }> | undefined {
  const x = to.x - from.x;
  const z = to.z - from.z;
  const length = Math.hypot(x, z);
  if (length <= Number.EPSILON) {
    return undefined;
  }
  return { x: x / length, z: z / length };
}

export function directionFromRotation(
  rotation: BeatGameRotation,
): Readonly<{ x: number; z: number }> {
  const yaw = rotation.yaw * RADIANS_PER_DEGREE;
  return {
    x: -Math.sin(yaw),
    z: Math.cos(yaw),
  };
}

export function triangulateStronghold(
  samples: readonly BeatGameEyeSample[],
): EyeTriangulation | undefined {
  let best: EyeTriangulation | undefined;
  for (let leftIndex = 0; leftIndex < samples.length; leftIndex += 1) {
    const left = samples[leftIndex];
    if (left === undefined) {
      continue;
    }
    for (let rightIndex = leftIndex + 1; rightIndex < samples.length; rightIndex += 1) {
      const right = samples[rightIndex];
      if (right === undefined || left.origin.dimension !== right.origin.dimension) {
        continue;
      }
      const candidate = intersectSamples(left, right);
      if (
        candidate !== undefined &&
        Math.hypot(candidate.position.x, candidate.position.z) >=
          MINIMUM_STRONGHOLD_ORIGIN_DISTANCE &&
        (best === undefined || candidate.confidence > best.confidence)
      ) {
        best = candidate;
      }
    }
  }
  return best;
}

export function createNetherPortalFrame(
  origin: BeatGameBlockPosition,
  axis: "x" | "z" = "x",
): PortalFrame {
  const position = (horizontal: number, vertical: number): BeatGameBlockPosition => ({
    x: origin.x + (axis === "x" ? horizontal : 0),
    y: origin.y + vertical,
    z: origin.z + (axis === "z" ? horizontal : 0),
    dimension: origin.dimension,
  });
  const blocks: BeatGameBlockPosition[] = [];
  // Vanilla permits the four frame corners to be empty. Keeping them out of
  // the blueprint matches the ten-block resource budget and avoids requiring
  // four extra lava sources when casting a frame.
  for (let horizontal = 1; horizontal < 3; horizontal += 1) {
    blocks.push(position(horizontal, 0), position(horizontal, 4));
  }
  for (let vertical = 1; vertical < 4; vertical += 1) {
    blocks.push(position(0, vertical), position(3, vertical));
  }
  const interior: BeatGameBlockPosition[] = [];
  for (let horizontal = 1; horizontal < 3; horizontal += 1) {
    for (let vertical = 1; vertical < 4; vertical += 1) {
      interior.push(position(horizontal, vertical));
    }
  }
  return {
    origin,
    axis,
    blocks,
    interior,
  };
}

export function inferNetherPortalFrames(
  obsidian: readonly BeatGameBlockPosition[],
  reference: BeatGamePosition,
): readonly NetherPortalFrameMatch[] {
  const observedKeys = new Set(
    obsidian
      .filter(({ dimension }) => dimension === reference.dimension)
      .map((position) => positionKey(position)),
  );
  const candidates = new Map<string, PortalFrame>();
  for (const block of obsidian) {
    if (block.dimension !== reference.dimension) {
      continue;
    }
    for (const axis of ["x", "z"] as const) {
      const template = createNetherPortalFrame(
        {
          x: 0,
          y: 0,
          z: 0,
          dimension: block.dimension,
        },
        axis,
      );
      for (const templateBlock of template.blocks) {
        const origin = {
          x: block.x - templateBlock.x,
          y: block.y - templateBlock.y,
          z: block.z - templateBlock.z,
          dimension: block.dimension,
        };
        const frame = createNetherPortalFrame(origin, axis);
        candidates.set(`${axis}:${positionKey(origin)}`, frame);
      }
    }
  }

  return [...candidates.values()]
    .map((frame) => ({
      frame,
      matchingBlocks: frame.blocks.filter((position) => observedKeys.has(positionKey(position)))
        .length,
    }))
    .filter(({ matchingBlocks }) => matchingBlocks > 0)
    .toSorted(
      (left, right) =>
        right.matchingBlocks - left.matchingBlocks ||
        distanceSquared(left.frame.origin, reference) -
          distanceSquared(right.frame.origin, reference) ||
        left.frame.origin.y - right.frame.origin.y ||
        left.frame.origin.x - right.frame.origin.x ||
        left.frame.origin.z - right.frame.origin.z ||
        left.frame.axis.localeCompare(right.frame.axis),
    );
}

function positionKey(position: BeatGameBlockPosition): string {
  return `${position.dimension}:${position.x}:${position.y}:${position.z}`;
}

export function distanceSquared(
  left: Pick<BeatGamePosition, "x" | "y" | "z">,
  right: Pick<BeatGamePosition, "x" | "y" | "z">,
): number {
  return (left.x - right.x) ** 2 + (left.y - right.y) ** 2 + (left.z - right.z) ** 2;
}

function intersectSamples(
  left: BeatGameEyeSample,
  right: BeatGameEyeSample,
): EyeTriangulation | undefined {
  const deltaX = right.origin.x - left.origin.x;
  const deltaZ = right.origin.z - left.origin.z;
  const baseline = Math.hypot(deltaX, deltaZ);
  if (baseline < MINIMUM_BASELINE) {
    return undefined;
  }
  const cross = cross2(left.direction, right.direction);
  if (Math.abs(cross) < MINIMUM_INTERSECTION_SINE) {
    return undefined;
  }
  const leftDistance = cross2({ x: deltaX, z: deltaZ }, right.direction) / cross;
  const rightDistance = cross2({ x: deltaX, z: deltaZ }, left.direction) / cross;
  if (leftDistance <= 0 || rightDistance <= 0) {
    return undefined;
  }
  const angleRadians = Math.asin(Math.min(1, Math.abs(cross)));
  const angleDegrees = angleRadians * DEGREES_PER_RADIAN;
  const geometryConfidence = Math.min(1, baseline / 256) * Math.min(1, Math.abs(cross) / 0.35);
  return {
    position: {
      x: left.origin.x + left.direction.x * leftDistance,
      y: left.origin.y,
      z: left.origin.z + left.direction.z * leftDistance,
      dimension: left.origin.dimension,
    },
    confidence: clamp(geometryConfidence * Math.min(left.confidence, right.confidence), 0, 1),
    baseline,
    angleDegrees,
  };
}

function cross2(
  left: Readonly<{ x: number; z: number }>,
  right: Readonly<{ x: number; z: number }>,
): number {
  return left.x * right.z - left.z * right.x;
}

function normalizeDegrees(value: number): number {
  const normalized = ((((value + 180) % 360) + 360) % 360) - 180;
  return Object.is(normalized, -0) ? 0 : normalized;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}
