import { describe, expect, it } from 'vitest';
import { flattenLines } from '../../terminal/render/renderPrimitives.js';
import { stripAnsi } from '../../terminal/shared/text.js';
import { renderConfigSetupView } from '../configSetupRenderer.js';

describe('config setup renderer', () => {
  it('renders a compact setup card without exposing the API key', () => {
    const view = renderConfigSetupView({
      configPath: '/Users/test/.aether/config.toml',
      activeIndex: 2,
      cursorOffset: 11,
      fields: [
        { id: 'providerId', label: 'Provider', value: 'lingsuan', placeholder: '' },
        { id: 'baseUrl', label: 'Base URL', value: 'https://api.openai.com/v1', placeholder: '' },
        { id: 'apiKey', label: 'API key', value: 'sk-secret-key', placeholder: 'sk-...', masked: true },
        { id: 'modelId', label: 'Model', value: 'gpt-5.5', placeholder: 'gpt-5.5' },
        { id: 'contextWindowK', label: 'Context window', value: '', placeholder: '258', suffix: 'K' },
      ],
    }, 90);

    const text = flattenLines(view.frame).map(line => stripAnsi(line.text)).join('\n');
    expect(text).toContain('Aether setup');
    expect(text).toContain('Only for OpenAI Responses API');
    expect(text).toContain('Provider');
    expect(text).toContain('Base URL');
    expect(text).toContain('API key');
    expect(text).toContain('Context window');
    expect(text).toContain('Context window 258 K');
    expect(text).not.toContain('API key is hidden while typing.');
    expect(text).toContain('***********');
    expect(text).not.toContain('sk-secret-key');
    expect(view.cursor).toEqual({ x: 30, y: 7 });
  });
});
