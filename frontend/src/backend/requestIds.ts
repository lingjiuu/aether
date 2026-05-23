let nextRequestId = 1;

export function newRequestId(): string {
  return String(nextRequestId++);
}
