import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Box, Text, useBoxMetrics, useCursor, useInput, useStdout, type DOMElement } from 'ink';
import type { AppState } from '../state/reducer.js';
import {
  getSlashCommandInsertText,
  getSlashCommandPlaceholder,
  getSlashCommandSuggestions,
} from '../commands/slashCommands.js';
import { tokens } from '../theme/tokens.js';

type Props = {
  state: AppState;
  disabled: boolean;
  onSubmit: (input: string) => void;
  onChange: (value: string) => void;
  onMoveSuggestion: (delta: -1 | 1, count: number) => void;
  onSelectSuggestion: (index: number) => void;
};

const PROMPT = '❯ ';
const PROMPT_WIDTH = 2;
const FOOTER_HEIGHT = 1;

export function Composer({
  state,
  disabled,
  onSubmit,
  onChange,
  onMoveSuggestion,
  onSelectSuggestion,
}: Props) {
  const value = state.composer.value;
  const [cursorOffset, setCursorOffset] = useState(value.length);
  const composerRef = useRef<DOMElement | null>(null);
  const promptPanelRef = useRef<DOMElement | null>(null);
  const promptLineRef = useRef<DOMElement | null>(null);
  const composerMetrics = useBoxMetrics(composerRef);
  const promptPanelMetrics = useBoxMetrics(promptPanelRef);
  const promptLineMetrics = useBoxMetrics(promptLineRef);
  const { setCursorPosition } = useCursor();
  const { stdout } = useStdout();
  const suggestions = useMemo(
    () => (state.composer.commandPaletteOpen ? getSlashCommandSuggestions(value) : []),
    [state.composer.commandPaletteOpen, value],
  );
  const suggestionUsageWidth = Math.max(0, ...suggestions.map(command => command.usage.length));
  const connected = state.session.connectionStatus === 'connected';
  const disconnected = state.session.connectionStatus === 'disconnected';
  const active = connected && !disabled;
  const borderColor = disconnected ? tokens.warning : tokens.border;
  const promptColor = disconnected ? tokens.warning : active ? tokens.accent : tokens.dim;
  const selectedSuggestionIndex = clampIndex(state.composer.selectedSuggestionIndex, suggestions.length);
  const visibleCursorOffset = clampNumber(cursorOffset, 0, value.length);
  const ghostPlaceholder =
    visibleCursorOffset === value.length ? getSlashCommandPlaceholder(value) : undefined;
  const beforeCursor = value.slice(0, visibleCursorOffset);
  const afterCursor = value.slice(visibleCursorOffset);

  useEffect(() => {
    setCursorOffset(current => clampNumber(current, 0, value.length));
  }, [value]);

  useEffect(() => {
    if (suggestions.length && state.composer.selectedSuggestionIndex !== selectedSuggestionIndex) {
      onSelectSuggestion(selectedSuggestionIndex);
    }
  }, [onSelectSuggestion, selectedSuggestionIndex, state.composer.selectedSuggestionIndex, suggestions.length]);

  const hasCursorMetrics =
    composerMetrics.hasMeasured && promptPanelMetrics.hasMeasured && promptLineMetrics.hasMeasured;
  const promptX = composerMetrics.left + promptPanelMetrics.left + promptLineMetrics.left;
  const promptY = composerMetrics.top + promptPanelMetrics.top + promptLineMetrics.top;
  const outputHeight = composerMetrics.top + composerMetrics.height + FOOTER_HEIGHT;
  const fullscreenCursorOffset = stdout.rows && outputHeight >= stdout.rows - 1 ? 1 : 0;
  const cursorPosition =
    active && hasCursorMetrics
      ? {
          x: promptX + PROMPT_WIDTH + visualWidth(beforeCursor),
          y: promptY + fullscreenCursorOffset,
        }
      : undefined;
  setCursorPosition(cursorPosition);

  useInput(
    (input, key) => {
      if (!active) {
        return;
      }

      if (key.ctrl && input === 'c') {
        return;
      }

      if (suggestions.length && (key.upArrow || key.downArrow)) {
        onMoveSuggestion(key.upArrow ? -1 : 1, suggestions.length);
        return;
      }

      if (suggestions.length && (key.return || key.tab)) {
        const selected = suggestions[selectedSuggestionIndex] ?? suggestions[0];
        if (selected) {
          const nextValue = getSlashCommandInsertText(selected);
          if (key.return) {
            onChange('');
            setCursorOffset(0);
            onSubmit(nextValue);
          } else {
            onChange(nextValue);
            setCursorOffset(nextValue.length);
          }
        }
        return;
      }

      if (key.return) {
        const trimmed = value.trim();
        if (!trimmed) {
          return;
        }
        onChange('');
        setCursorOffset(0);
        onSubmit(trimmed);
        return;
      }

      if (key.leftArrow) {
        setCursorOffset(current => clampNumber(current - 1, 0, value.length));
        return;
      }

      if (key.rightArrow) {
        setCursorOffset(current => clampNumber(current + 1, 0, value.length));
        return;
      }

      if (key.upArrow || key.downArrow) {
        return;
      }

      if (key.ctrl && input === 'a') {
        setCursorOffset(0);
        return;
      }

      if (key.ctrl && input === 'e') {
        setCursorOffset(value.length);
        return;
      }

      if (key.backspace || key.delete) {
        if (visibleCursorOffset <= 0) {
          return;
        }
        const nextValue = value.slice(0, visibleCursorOffset - 1) + value.slice(visibleCursorOffset);
        onChange(nextValue);
        setCursorOffset(visibleCursorOffset - 1);
        return;
      }

      if (!input) {
        return;
      }

      const nextValue = value.slice(0, visibleCursorOffset) + input + value.slice(visibleCursorOffset);
      onChange(nextValue);
      setCursorOffset(visibleCursorOffset + input.length);
    },
    { isActive: active },
  );

  return (
    <Box ref={composerRef} flexDirection="column" width="100%" flexShrink={0}>
      {suggestions.length ? (
        <Box flexDirection="column" width="100%">
          {suggestions.map((command, index) => (
            <Box key={command.usage} flexDirection="row">
              <Box width={suggestionUsageWidth + 3}>
                <Text color={index === selectedSuggestionIndex ? tokens.accent : tokens.dim}>
                  {index === selectedSuggestionIndex ? '› ' : '  '}
                  {command.usage}
                </Text>
              </Box>
              <Text color={tokens.dim} wrap="truncate-end">
                {command.description}
              </Text>
            </Box>
          ))}
        </Box>
      ) : null}
      <Box
        ref={promptPanelRef}
        borderStyle="single"
        borderTop
        borderBottom
        borderLeft={false}
        borderRight={false}
        borderColor={borderColor}
        borderDimColor={!disconnected}
        flexDirection="column"
        width="100%"
        flexShrink={0}
      >
        <Box ref={promptLineRef} width="100%" flexShrink={0} paddingRight={1}>
          <Text color={promptColor} bold>
            {PROMPT}
          </Text>
          <Text>{beforeCursor}</Text>
          {ghostPlaceholder ? <Text color={tokens.dim}>{`[${ghostPlaceholder}]`}</Text> : null}
          <Text>{afterCursor}</Text>
        </Box>
      </Box>
    </Box>
  );
}

function clampIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return clampNumber(index, 0, count - 1);
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function visualWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    width += isWide(char) ? 2 : 1;
  }
  return width;
}

function isWide(char: string): boolean {
  const codePoint = char.codePointAt(0) ?? 0;
  return (
    (codePoint >= 0x1100 && codePoint <= 0x115f) ||
    (codePoint >= 0x2e80 && codePoint <= 0xa4cf) ||
    (codePoint >= 0xac00 && codePoint <= 0xd7a3) ||
    (codePoint >= 0xf900 && codePoint <= 0xfaff) ||
    (codePoint >= 0xfe10 && codePoint <= 0xfe19) ||
    (codePoint >= 0xfe30 && codePoint <= 0xfe6f) ||
    (codePoint >= 0xff00 && codePoint <= 0xff60) ||
    (codePoint >= 0xffe0 && codePoint <= 0xffe6)
  );
}
