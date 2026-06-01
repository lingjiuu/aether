#!/usr/bin/env node

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const EXPECTED_TEXT = 'native smoke ok';
const RESPONSE_ID = 'resp_native_smoke';
const REASONING_ID = 'rsn_native_smoke';
const MESSAGE_ID = 'msg_native_smoke';

let child;
let providerServer;
let smokeHome;
let sessionCwd;
let timeout;

const args = parseArgs(process.argv.slice(2));
const backend = requireArg(args, 'backend');
const expectedVersion = args.version;
const runTurnSmoke = truthy(args['ensure-test-config']);
const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));

sessionCwd = mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-cwd-'));
smokeHome = runTurnSmoke ? mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-home-')) : null;

if (runTurnSmoke) {
  const fakeProvider = await startFakeProvider();
  ensureTestConfig(fakeProvider.baseUrl);
}

let stdout = '';
let stdoutBuffer = '';
let stderr = '';
let initialized = false;
let turnAccepted = false;
let assistantDeltaText = '';
let assistantCompletedText = '';
let finished = false;
const recentEvents = [];

child = spawn(backend, ['--stdio'], {
  cwd: repoRoot,
  env: {
    ...process.env,
    ...(smokeHome ? { AETHER_HOME: smokeHome, HOME: smokeHome, USERPROFILE: smokeHome } : {}),
    AETHER_SESSION_CWD: sessionCwd,
  },
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

timeout = setTimeout(() => {
  fail(`Native backend smoke timed out.\nstdout:\n${stdout}\nstderr:\n${stderr}`);
}, runTurnSmoke ? 30000 : 15000);

child.stdout.setEncoding('utf8');
child.stderr.setEncoding('utf8');
child.stdout.on('data', chunk => {
  stdout += chunk;
  stdoutBuffer += chunk;
  drainStdoutLines();
});
child.stderr.on('data', chunk => {
  stderr += chunk;
});
child.on('error', error => {
  fail(`Failed to start native backend: ${error.message}`);
});
child.on('exit', (code, signal) => {
  if (!finished) {
    fail(`Native backend exited before smoke completed: code=${code} signal=${signal}\nstdout:\n${stdout}\nstderr:\n${stderr}`);
  }
});

send({ id: '1', method: 'initialize' });

function drainStdoutLines() {
  while (true) {
    const newlineIndex = stdoutBuffer.indexOf('\n');
    if (newlineIndex === -1) {
      return;
    }
    const line = stdoutBuffer.slice(0, newlineIndex).trim();
    stdoutBuffer = stdoutBuffer.slice(newlineIndex + 1);
    if (line) {
      handleMessageLine(line);
    }
  }
}

function handleMessageLine(line) {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    return;
  }

  if (message.method === 'event') {
    handleUiEvent(message.params);
    return;
  }

  const id = String(message.id ?? '');
  if (id === '1') {
    handleInitializeResponse(message);
  } else if (id === '2') {
    handleTurnSubmitResponse(message);
  }
}

function handleInitializeResponse(message) {
  if (initialized) {
    return;
  }
  if (message.error) {
    fail(`Native backend initialize returned an error: ${JSON.stringify(message.error)}\nstderr:\n${stderr}`);
  }

  const result = message.result;
  if (result?.protocolVersion !== 'aether.stdio.v1') {
    fail(`Unexpected protocol version: ${JSON.stringify(result?.protocolVersion)}`);
  }
  const actualVersion = result?.session?.appVersion;
  if (expectedVersion && actualVersion !== expectedVersion) {
    fail(`Unexpected app version: expected ${expectedVersion}, got ${JSON.stringify(actualVersion)}`);
  }

  initialized = true;
  if (!runTurnSmoke) {
    pass(`Native backend smoke passed: ${result.protocolVersion} ${actualVersion ?? ''}`.trim());
    return;
  }

  send({
    id: '2',
    method: 'turn/submit',
    params: {
      items: [
        {
          type: 'text',
          text: 'hello from native smoke',
        },
      ],
    },
  });
}

function handleTurnSubmitResponse(message) {
  if (message.error) {
    fail(`turn/submit returned an error: ${JSON.stringify(message.error)}\nstderr:\n${stderr}`);
  }
  if (message.result?.accepted !== true) {
    fail(`turn/submit was not accepted: ${JSON.stringify(message.result)}\nstderr:\n${stderr}`);
  }
  turnAccepted = true;
}

function handleUiEvent(event) {
  if (!event || !event.type) {
    return;
  }
  rememberEvent(event);

  switch (event.type) {
    case 'ASSISTANT_TEXT_DELTA':
      assistantDeltaText += event.payload?.delta ?? '';
      break;
    case 'ITEM_COMPLETED':
      if (event.payload?.item?.kind === 'ASSISTANT_TEXT') {
        assistantCompletedText += event.payload.item.body?.text ?? '';
      }
      break;
    case 'ERROR':
      fail(`Native backend emitted ERROR during smoke: ${event.payload?.message ?? JSON.stringify(event.payload)}\n${recentEventText()}\nstderr:\n${stderr}`);
      break;
    case 'TURN_COMPLETED':
      completeTurnSmoke();
      break;
    default:
      break;
  }
}

function completeTurnSmoke() {
  const missing = [];
  if (!turnAccepted) {
    missing.push('turn/submit ack');
  }
  if (!assistantCompletedText.includes(EXPECTED_TEXT)) {
    missing.push('completed assistant text item');
  }
  if (missing.length > 0) {
    fail(`Native backend turn completed without ${missing.join(', ')}.\nDelta text: ${JSON.stringify(assistantDeltaText)}\nCompleted text: ${JSON.stringify(assistantCompletedText)}\n${recentEventText()}\nstderr:\n${stderr}`);
  }
  pass('Native backend smoke passed: initialize + completed model turn');
}

function rememberEvent(event) {
  recentEvents.push({
    type: event.type,
    payload: event.payload,
  });
  if (recentEvents.length > 20) {
    recentEvents.shift();
  }
}

function recentEventText() {
  return `Recent events:\n${JSON.stringify(recentEvents, null, 2)}`;
}

function send(message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

async function startFakeProvider() {
  const server = createServer((request, response) => {
    if (request.method !== 'POST' || !request.url?.endsWith('/responses')) {
      response.writeHead(404, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ error: { message: 'not found' } }));
      return;
    }

    let body = '';
    request.setEncoding('utf8');
    request.on('data', chunk => {
      body += chunk;
    });
    request.on('end', () => {
      try {
        JSON.parse(body || '{}');
      } catch {
        response.writeHead(400, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ error: { message: 'invalid json' } }));
        return;
      }

      response.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      for (const event of responseEvents()) {
        response.write(`event: ${event.type}\n`);
        response.write(`data: ${JSON.stringify(event)}\n\n`);
      }
      response.write('data: [DONE]\n\n');
      response.end();
    });
  });

  providerServer = server;
  await new Promise((resolvePromise, rejectPromise) => {
    server.once('error', rejectPromise);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', rejectPromise);
      resolvePromise();
    });
  });
  const address = server.address();
  return { baseUrl: `http://127.0.0.1:${address.port}/v1` };
}

function responseEvents() {
  const completedReasoning = reasoningItem('completed');
  const completedMessage = messageItem('completed');
  return [
    {
      type: 'response.created',
      sequence_number: 0,
      response: responseObject('in_progress', []),
    },
    {
      type: 'response.output_item.added',
      sequence_number: 1,
      output_index: 0,
      item: reasoningItem('in_progress'),
    },
    {
      type: 'response.output_item.done',
      sequence_number: 2,
      output_index: 0,
      item: completedReasoning,
    },
    {
      type: 'response.output_item.added',
      sequence_number: 3,
      output_index: 1,
      item: messageItem('in_progress'),
    },
    {
      type: 'response.output_text.done',
      sequence_number: 4,
      output_index: 1,
      item_id: MESSAGE_ID,
      content_index: 0,
      text: EXPECTED_TEXT,
      logprobs: [],
    },
    {
      type: 'response.completed',
      sequence_number: 5,
      response: responseObject('completed', [completedReasoning, completedMessage]),
    },
  ];
}

function responseObject(status, output) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  return {
    id: RESPONSE_ID,
    object: 'response',
    created_at: nowSeconds,
    error: null,
    incomplete_details: null,
    instructions: null,
    metadata: {},
    model: 'smoke-model',
    output,
    parallel_tool_calls: true,
    temperature: null,
    tool_choice: 'auto',
    tools: [],
    top_p: null,
    background: null,
    completed_at: status === 'completed' ? nowSeconds : null,
    conversation: null,
    max_output_tokens: null,
    max_tool_calls: null,
    previous_response_id: null,
    prompt: null,
    prompt_cache_key: null,
    prompt_cache_retention: null,
    reasoning: null,
    safety_identifier: null,
    service_tier: null,
    status,
    text: null,
    top_logprobs: null,
    truncation: 'disabled',
    usage: status === 'completed'
      ? {
          input_tokens: 4,
          output_tokens: 3,
          total_tokens: 7,
        }
      : null,
    user: null,
  };
}

function messageItem(status) {
  return {
    id: MESSAGE_ID,
    type: 'message',
    status,
    role: 'assistant',
    content: status === 'completed'
      ? [
          {
            type: 'output_text',
            text: EXPECTED_TEXT,
            annotations: [],
            logprobs: [],
          },
        ]
      : [],
  };
}

function reasoningItem(status) {
  return {
    id: REASONING_ID,
    type: 'reasoning',
    status,
    summary: [],
  };
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      fail(`Unexpected argument: ${arg}`);
    }
    const eq = arg.indexOf('=');
    if (eq !== -1) {
      parsed[arg.slice(2, eq)] = arg.slice(eq + 1);
      continue;
    }
    const key = arg.slice(2);
    const value = argv[++i];
    if (!value || value.startsWith('--')) {
      fail(`--${key} requires a value`);
    }
    parsed[key] = value;
  }
  return parsed;
}

function requireArg(values, name) {
  const value = values[name];
  if (!value) {
    fail(`--${name} is required`);
  }
  return value;
}

function truthy(value) {
  return value === true || value === 'true' || value === '1' || value === 'yes';
}

function pass(message) {
  finished = true;
  cleanup();
  console.log(message);
  process.exit(0);
}

function fail(message) {
  finished = true;
  cleanup();
  console.error(message);
  process.exit(1);
}

function cleanup() {
  clearTimeout(timeout);
  if (child) {
    child.kill();
  }
  if (providerServer) {
    providerServer.close();
    providerServer = null;
  }
  if (sessionCwd) {
    rmSync(sessionCwd, { recursive: true, force: true });
    sessionCwd = null;
  }
  if (smokeHome) {
    rmSync(smokeHome, { recursive: true, force: true });
    smokeHome = null;
  }
}

function ensureTestConfig(baseUrl) {
  const configPath = resolve(smokeHome, 'config.toml');
  mkdirSync(dirname(configPath), { recursive: true });
  writeFileSync(configPath, `default_provider = "smoke"
default_model = "smoke-model"
default_thinking_level = "medium"

[model_providers.smoke]
name = "Smoke"
api = "openai"
base_url = "${baseUrl}"
api_key = "smoke-key"
request_max_retries = 0
stream_max_retries = 0

[[model_providers.smoke.models]]
id = "smoke-model"
name = "Smoke Model"
api = "openai"
base_url = "${baseUrl}"
context_window = 100000
input = ["text"]
`, 'utf8');
}
