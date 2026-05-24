export type RenderedLine = {
  text: string;
  raw: string;
};

export type RenderBlock = {
  lines: RenderedLine[];
  cursor?: { x: number; y: number };
};

export type TerminalScrollInfo = {
  scrollTop: number;
  maxScrollTop: number;
  viewportRows: number;
  transcriptLineCount: number;
  isAtBottom: boolean;
};

export type TerminalView = {
  resetKey: string;
  transcriptLines: string[];
  bottomLines: string[];
  scroll: TerminalScrollInfo;
  cursor?: { x: number; y: number };
};
