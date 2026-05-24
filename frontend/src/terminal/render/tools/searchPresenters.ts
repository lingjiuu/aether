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
    const matchCount = numberField(details, 'matchCount');
    const fileCount = numberField(details, 'fileCount');
    if (matchCount != null && fileCount != null) {
      return {
        lines: [
          {
            text: `Found ${matchCount} ${plural(matchCount, 'match', 'matches')} across ${fileCount} ${plural(fileCount, 'file', 'files')}`,
            tone: 'dim',
          },
        ],
      };
    }
    if (matchCount != null) {
      return { lines: [{ text: `Found ${matchCount} ${plural(matchCount, 'match', 'matches')}`, tone: 'dim' }] };
    }
    return { lines: [{ text: 'Search completed', tone: 'dim' }] };
  },
};

export const findPresenter: ToolPresenter = {
  userFacingName: () => 'Search',
  useSummary: (args, toolCall) => searchSummary(args, clean(toolCall?.displaySummary)),
  resultView: details => countResultView(details, 'resultCount', 'Found', 'file', 'files', 'Found results'),
};

export const searchToolPresentations: ToolPresentationDefinition[] = [
  { names: ['ls'], presenter: lsPresenter },
  { names: ['grep'], presenter: grepPresenter },
  { names: ['find'], presenter: findPresenter },
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
