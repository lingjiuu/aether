import { dim, success, warning } from '../../shared/ansi.js';
import { fitPlain, visualWidth } from '../../shared/text.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { line } from '../renderPrimitives.js';
import type { RenderedLine } from '../viewModel.js';

export function renderFooter(presentation: TerminalPresentation, width: number): RenderedLine[] {
  if (
    presentation.commandPanel
    || presentation.pendingApproval
    || presentation.composer.commandPaletteOpen
    || presentation.composer.popup
  ) {
    return [];
  }
  const right = rightStatus(presentation);
  const notice = presentation.notices.at(-1);
  const rawRight = notice ? [right, notice].filter(Boolean).join('  ') : right;
  const left = fitLeftStatus(sessionStatus(presentation), rawRight, width);
  return [line(statusText(left, rawRight, width), statusRaw(left, rawRight, width), width)];
}

function sessionStatus(presentation: TerminalPresentation): string {
  return [modelLabel(presentation), cwdLabel(presentation)].filter(Boolean).join(' · ');
}

function rightStatus(presentation: TerminalPresentation): string {
  if (presentation.isRunning) {
    return 'esc to interrupt';
  }
  return '';
}

function statusRaw(left: string, right: string, width: number): string {
  if (!right) {
    return fitPlain(left, width).trimEnd();
  }
  const gap = Math.max(2, width - visualWidth(left) - visualWidth(right));
  return `${left}${' '.repeat(gap)}${right}`;
}

function statusText(left: string, right: string, width: number): string {
  const leftText = coloredSessionStatus(left);
  if (!right) {
    return leftText;
  }
  const gap = Math.max(2, width - visualWidth(left) - visualWidth(right));
  return `${leftText}${' '.repeat(gap)}${dim(fitPlain(right, Math.max(0, width - visualWidth(left) - gap)))}`;
}

function fitLeftStatus(left: string, right: string, width: number): string {
  const reserved = right ? visualWidth(right) + 2 : 0;
  return fitPlain(left, Math.max(0, width - reserved)).trimEnd();
}

function coloredSessionStatus(status: string): string {
  const [model, cwd] = status.split(' · ');
  if (!model || !cwd) {
    return warning(status);
  }
  return `${warning(model)}${dim(' · ')}${success(cwd)}`;
}

function modelLabel(presentation: TerminalPresentation): string {
  const model = shortModelName(presentation.session.model);
  const effort = reasoningEffortLabel(presentation.session.reasoningEffort);
  return [model, effort].filter(Boolean).join(' ');
}

function shortModelName(model?: string): string {
  const trimmed = model?.trim();
  if (!trimmed) {
    return '';
  }
  return trimmed.split('/').filter(Boolean).at(-1) ?? trimmed;
}

function reasoningEffortLabel(effort?: string): string {
  switch (effort?.toUpperCase()) {
    case 'XHIGH':
      return 'xhigh';
    case 'HIGH':
      return 'high';
    case 'MEDIUM':
      return 'medium';
    case 'LOW':
      return 'low';
    case 'MINIMAL':
      return 'minimal';
    default:
      return '';
  }
}

function cwdLabel(presentation: TerminalPresentation): string {
  const cwd = presentation.session.cwd?.trim();
  if (!cwd) {
    return '';
  }
  const home = process.env.HOME;
  if (home && cwd === home) {
    return '~';
  }
  if (home && cwd.startsWith(`${home}/`)) {
    return `~/${cwd.slice(home.length + 1)}`;
  }
  return cwd;
}
