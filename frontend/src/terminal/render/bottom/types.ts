import type { TerminalPresentation } from '../presentationModel.js';

export type BottomRenderOptions = {
  presentation: TerminalPresentation;
  width: number;
  rows: number;
  composerCursorOffset: number;
  approvalSelectedIndex: number;
};
