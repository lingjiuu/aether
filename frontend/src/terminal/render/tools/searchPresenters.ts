import type { ToolPresentationDefinition, ToolPresenter, ToolResultView } from './types.js';
import { clean, numberField, plural, searchSummary, stringField } from './utils.js';

export const lsPresenter: ToolPresenter = {
  userFacingName: () => 'ls',
  useSummary: (args, toolCall) => stringField(args, 'path') ?? clean(toolCall?.displaySummary) ?? '.',
  resultView: details => countResultView(details, 'entryCount', 'Listed', 'entry', 'entries', 'Listed results'),
};

export const grepPresenter: ToolPresenter = {
  userFacingName: () => 'Search',
  useSummary: (args, toolCall) => searchSummary(args, clean(toolCall?.displaySummary)),
  resultView(details): ToolResultView {
    const mode = stringField(details, 'mode');
    if (mode === 'content') {
      const lineCount = numberField(details, 'numLines');
      if (lineCount != null) {
        return { lines: [{ text: `Found ${lineCount} ${plural(lineCount, 'line', 'lines')}`, tone: 'dim' }] };
      }
    }
    if (mode === 'count') {
      const matchCount = numberField(details, 'numMatches');
      const fileCount = numberField(details, 'numFiles');
      if (matchCount != null && fileCount != null) {
        return {
          lines: [
            {
              text: `Found ${matchCount} ${plural(matchCount, 'occurrence', 'occurrences')} across ${fileCount} ${plural(fileCount, 'file', 'files')}`,
              tone: 'dim',
            },
          ],
        };
      }
    }
    const fileCount = numberField(details, 'numFiles');
    if (fileCount != null) {
      return {
        lines: [{ text: `Found ${fileCount} ${plural(fileCount, 'file', 'files')}`, tone: 'dim' }],
      };
    }
    return { lines: [{ text: 'Search completed', tone: 'dim' }] };
  },
};

export const globPresenter: ToolPresenter = {
  userFacingName: () => 'Search',
  useSummary: (args, toolCall) => searchSummary(args, clean(toolCall?.displaySummary)),
  resultView: details => countResultView(details, 'numFiles', 'Found', 'file', 'files', 'Found results'),
};

export const searchToolPresentations: ToolPresentationDefinition[] = [
  { names: ['ls'], presenter: lsPresenter },
  { names: ['Grep', 'grep'], presenter: grepPresenter },
  { names: ['Glob', 'glob'], presenter: globPresenter },
];

function countResultView(
  details: Record<string, unknown>,
  field: string,
  verb: string,
  singular: string,
  pluralText: string,
  fallback: string,
): ToolResultView {
  const count = numberField(details, field);
  if (count == null) {
    return { lines: [{ text: fallback, tone: 'dim' }] };
  }
  return { lines: [{ text: `${verb} ${count} ${plural(count, singular, pluralText)}`, tone: 'dim' }] };
}
