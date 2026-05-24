export type RenderedLine = {
  kind: 'line';
  key: string;
  text: string;
  raw: string;
  role?: string;
};

export type RenderBlock = {
  kind: 'block';
  key: string;
  children: RenderNode[];
  cursor?: { x: number; y: number };
};

export type RenderNode = RenderBlock | RenderedLine;

export type TerminalScrollInfo = {
  scrollTop: number;
  maxScrollTop: number;
  viewportRows: number;
  transcriptLineCount: number;
  isAtBottom: boolean;
  isSticky: boolean;
  pendingDeltaRows: number;
};

export type TerminalView = {
  resetKey: string;
  frame: RenderBlock;
  transcript: RenderBlock;
  bottom: RenderBlock;
  scroll: TerminalScrollInfo;
  cursor?: { x: number; y: number };
};
