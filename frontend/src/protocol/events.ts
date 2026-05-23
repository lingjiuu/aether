import type { UiEvent, UiEventPayload } from './wire.js';

export function payload<T extends UiEventPayload['payloadType']>(
  event: UiEvent,
  payloadType: T,
): Extract<UiEventPayload, { payloadType: T }> | undefined {
  return event.payload?.payloadType === payloadType
    ? (event.payload as Extract<UiEventPayload, { payloadType: T }>)
    : undefined;
}

export function eventTurnKey(event: UiEvent): string {
  return event.turnId ?? `turn-${event.turn ?? 0}`;
}
