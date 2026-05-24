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

export type TerminalView = {
  resetKey: string;
  frame: RenderBlock;
  history: RenderBlock;
  active: RenderBlock;
  cursor?: { x: number; y: number };
};
