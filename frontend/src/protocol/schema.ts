import { z } from 'zod';
import type { UiEvent } from './wire.js';

export const UiEventSchema = z
  .object({
    type: z.string().nullable().optional(),
    sequence: z.number().nullable().optional(),
    timestampMs: z.number().nullable().optional(),
    sessionId: z.string().nullable().optional(),
    commandId: z.string().nullable().optional(),
    turnId: z.string().nullable().optional(),
    turn: z.number().nullable().optional(),
    payload: z.unknown().nullable().optional(),
  })
  .passthrough();

export function parseUiEvent(value: unknown): UiEvent | undefined {
  const parsed = UiEventSchema.safeParse(value);
  return parsed.success ? (parsed.data as UiEvent) : undefined;
}
