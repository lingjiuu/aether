export function clampIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return clampNumber(index, 0, count - 1);
}

export function moveIndex(current: number, delta: -1 | 1, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return (current + delta + count) % count;
}

export function clampNumber(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
