#!/usr/bin/env node

import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';

const TEXT_SMOKE = 'native smoke ok';
const TOOL_SMOKE = 'tool smoke ok';
const COMPACT_SMOKE = 'compact summary ok';
const READ_FILE_CONTENT = 'native smoke file content';
const RESPONSE_ID = 'resp_native_smoke';
const REASONING_ID = 'rsn_native_smoke';
const MESSAGE_ID = 'msg_native_smoke';
const TOOL_ITEM_ID = 'fc_native_smoke';
const TOOL_CALL_ID = 'call_native_smoke';

let child;
let providerServer;
let smokeHome;
let sessionCwd;
let secondaryCwd;
let readFilePath;
let timeout;

const args = parseArgs(process.argv.slice(2));
const backend = requireArg(args, 'backend');
const expectedVersion = args.version;
const runTurnSmoke = truthy(args['ensure-test-config']);
const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));

sessionCwd = mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-cwd-'));
secondaryCwd = mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-secondary-cwd-'));
readFilePath = resolve(sessionCwd, 'read-me.txt');
writeFileSync(readFilePath, `${READ_FILE_CONTENT}\n`, 'utf8');
smokeHome = runTurnSmoke ? mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-home-')) : null;

if (runTurnSmoke) {
  const fakeProvider = await startFakeProvider();
  ensureTestConfig(fakeProvider.baseUrl);
}

let stdout = '';
let stdoutBuffer = '';
let stderr = '';
let finished = false;
let nextRequestId = 1;
let activeScenario = null;
let providerRequestCount = 0;
let sawResumedCompactSummaryInModelRequest = false;
const pendingResponses = new Map();
const eventWaiters = [];
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
}, runTurnSmoke ? 90000 : 15000);

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

try {
  await runSmoke();
} catch (error) {
  fail(`${error?.stack || error?.message || error}\n${recentEventText()}\nstderr:\n${stderr}`);
}

async function runSmoke() {
  const initialized = await request('initialize');
  validateInitialize(initialized);
  if (!runTurnSmoke) {
    pass(`Native backend smoke passed: ${initialized.protocolVersion} ${initialized.session?.appVersion ?? ''}`.trim());
    return;
  }

  await runTextTurn();
  await runToolTurn();
  const originalSession = await request('session/current');
  const originalSessionId = originalSession.sessionId;
  await runCompact();
  await runResume(originalSessionId);
  await runInterrupt();
  await closeSessionForTraceFlush();
  await validateTraceDb();
  pass('Native backend smoke passed: initialize + text/tool/compact/resume/interrupt/trace');
}

function validateInitialize(result) {
  if (result?.protocolVersion !== 'aether.stdio.v1') {
    throw new Error(`Unexpected protocol version: ${JSON.stringify(result?.protocolVersion)}`);
  }
  const actualVersion = result?.session?.appVersion;
  if (expectedVersion && actualVersion !== expectedVersion) {
    throw new Error(`Unexpected app version: expected ${expectedVersion}, got ${JSON.stringify(actualVersion)}`);
  }
}

async function runTextTurn() {
  const scenario = beginScenario('text');
  const completed = waitForEvent('text turn completed', event => event.type === 'TURN_COMPLETED', 30000);
  const ack = await request('turn/submit', {
    items: [{ type: 'text', text: 'basic native smoke' }],
  });
  assertAccepted(ack, 'text turn');
  await completed;
  if (!scenario.assistantCompletedText.includes(TEXT_SMOKE)) {
    throw new Error(`Text smoke missed completed assistant text. Got ${JSON.stringify(scenario.assistantCompletedText)}`);
  }
  if (!scenario.assistantDeltaText.includes(TEXT_SMOKE)) {
    throw new Error(`Text smoke missed assistant delta text. Got ${JSON.stringify(scenario.assistantDeltaText)}`);
  }
  endScenario();
}

async function runToolTurn() {
  const scenario = beginScenario('tool');
  const completed = waitForEvent('tool turn completed', event => event.type === 'TURN_COMPLETED', 45000);
  const ack = await request('turn/submit', {
    items: [{ type: 'text', text: 'tool native smoke: read the smoke file' }],
  });
  assertAccepted(ack, 'tool turn');
  await completed;
  if (!scenario.sawToolCall) {
    throw new Error('Tool smoke did not emit a completed tool call.');
  }
  if (!scenario.sawToolExecutionEnd || !scenario.sawToolResult) {
    throw new Error('Tool smoke did not execute and record the tool result.');
  }
  if (!scenario.assistantCompletedText.includes(TOOL_SMOKE)) {
    throw new Error(`Tool smoke missed final assistant text. Got ${JSON.stringify(scenario.assistantCompletedText)}`);
  }
  endScenario();
}

async function runCompact() {
  const scenario = beginScenario('compact');
  const compactFinished = waitForEvent('compact finished', event => event.type === 'COMPACT_FINISHED', 30000);
  const turnCompleted = waitForEvent('compact turn completed', event => event.type === 'TURN_COMPLETED', 30000);
  const ack = await request('compact/run');
  assertAccepted(ack, 'compact');
  await compactFinished;
  await turnCompleted;
  if (!scenario.compactText.includes(COMPACT_SMOKE)) {
    throw new Error(`Compact smoke missed compact summary. Got ${JSON.stringify(scenario.compactText)}`);
  }
  endScenario();
}

async function runResume(originalSessionId) {
  if (!originalSessionId) {
    throw new Error('Resume smoke has no original session id.');
  }
  const newAck = await request('session/new', { cwd: secondaryCwd });
  assertAccepted(newAck, 'session/new');
  if (newAck.sessionId === originalSessionId) {
    throw new Error('session/new did not switch to a fresh session.');
  }

  const resumeAck = await request('session/resume', { sessionId: originalSessionId });
  assertAccepted(resumeAck, 'session/resume');
  if (resumeAck.sessionId !== originalSessionId) {
    throw new Error(`session/resume returned wrong session id: ${resumeAck.sessionId}`);
  }
  if (!resumeAck.history || !Array.isArray(resumeAck.history.turns) || resumeAck.history.turns.length === 0) {
    throw new Error(`session/resume returned no replay history: ${JSON.stringify(resumeAck.history)}`);
  }

  const current = await request('session/current');
  if (current.sessionId !== originalSessionId) {
    throw new Error(`session/current after resume returned ${current.sessionId}, expected ${originalSessionId}`);
  }
  const history = await request('history/read');
  const historyJson = JSON.stringify(history);
  if (!historyJson.includes('Context compacted')) {
    throw new Error(`Resumed history did not replay the compacted boundary: ${historyJson}`);
  }
  if (historyJson.includes(COMPACT_SMOKE)) {
    throw new Error('Resumed visible history exposed the raw compact summary.');
  }
}

async function runInterrupt() {
  const scenario = beginScenario('interrupt');
  const ended = waitForEvent(
    'interrupt turn aborted',
    event => event.type === 'TURN_ABORTED' || event.type === 'TURN_COMPLETED',
    20000,
  );
  const ack = await request('turn/submit', {
    items: [{ type: 'text', text: 'interrupt native smoke: keep streaming' }],
  });
  assertAccepted(ack, 'interrupt turn');
  await delay(500);
  const cancelAck = await request('turn/cancel');
  assertAccepted(cancelAck, 'turn/cancel');
  const endEvent = await ended;
  if (endEvent.type !== 'TURN_ABORTED') {
    throw new Error('Interrupt smoke completed instead of aborting.');
  }
  if (!scenario.sawAbort) {
    throw new Error('Interrupt smoke did not observe TURN_ABORTED.');
  }
  if (!sawResumedCompactSummaryInModelRequest) {
    throw new Error('Interrupt request after resume did not include the compacted summary in model context.');
  }
  endScenario();
}

async function closeSessionForTraceFlush() {
  const ack = await request('session/close');
  assertAccepted(ack, 'session/close');
}

async function validateTraceDb() {
  if (!smokeHome) {
    return;
  }
  const traceDb = resolve(smokeHome, 'traces', 'aether-trace.sqlite');
  if (!existsSync(traceDb)) {
    throw new Error(`Trace database was not created: ${traceDb}`);
  }

  const probe = spawnSync('sqlite3', ['-version'], { encoding: 'utf8' });
  if (probe.error) {
    if (process.platform === 'win32') {
      console.warn('Skipping native trace DB validation because sqlite3 is not available on Windows.');
      return;
    }
    throw new Error(`sqlite3 is required for native trace validation: ${probe.error.message}`);
  }

  let counts = '';
  for (let attempt = 0; attempt < 20; attempt++) {
    counts = runSqlite(traceDb, `
SELECT
  (SELECT COUNT(*) FROM agent_runs WHERE status = 'RUNNING') || '|' ||
  (SELECT COUNT(*) FROM agent_spans WHERE status = 'RUNNING');
`);
    if (counts === '0|0') {
      return;
    }
    await delay(250);
  }
  if (counts !== '0|0') {
    const details = runSqlite(traceDb, `
SELECT 'run' AS kind, run_id AS id, status, COALESCE(task_kind, '') AS detail, COALESCE(error, '') AS error
FROM agent_runs
WHERE status = 'RUNNING'
UNION ALL
SELECT 'span' AS kind, span_id AS id, status, COALESCE(name, '') AS detail, COALESCE(error, '') AS error
FROM agent_spans
WHERE status = 'RUNNING';
`);
    throw new Error(`Trace DB still has RUNNING records (${counts}).\n${details}`);
  }
}

function runSqlite(databasePath, sql) {
  const result = spawnSync('sqlite3', ['-readonly', databasePath, sql], { encoding: 'utf8' });
  if (result.error) {
    throw new Error(`sqlite3 failed: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`sqlite3 exited with ${result.status}: ${result.stderr}`);
  }
  return result.stdout.trim();
}

function beginScenario(name) {
  activeScenario = {
    name,
    assistantDeltaText: '',
    assistantCompletedText: '',
    compactText: '',
    sawToolCall: false,
    sawToolExecutionEnd: false,
    sawToolResult: false,
    sawAbort: false,
  };
  return activeScenario;
}

function endScenario() {
  activeScenario = null;
}

function assertAccepted(ack, label) {
  if (ack?.accepted !== true) {
    throw new Error(`${label} was not accepted: ${JSON.stringify(ack)}`);
  }
}

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
  const pending = pendingResponses.get(id);
  if (!pending) {
    return;
  }
  pendingResponses.delete(id);
  pending.resolve(message);
}

function handleUiEvent(event) {
  if (!event || !event.type) {
    return;
  }
  rememberEvent(event);
  updateActiveScenario(event);
  if (event.type === 'ERROR') {
    fail(`Native backend emitted ERROR during smoke: ${event.payload?.message ?? JSON.stringify(event.payload)}\n${recentEventText()}\nstderr:\n${stderr}`);
  }
  for (const waiter of [...eventWaiters]) {
    if (waiter.predicate(event)) {
      eventWaiters.splice(eventWaiters.indexOf(waiter), 1);
      clearTimeout(waiter.timeout);
      waiter.resolve(event);
    }
  }
}

function updateActiveScenario(event) {
  if (!activeScenario) {
    return;
  }
  switch (event.type) {
    case 'ASSISTANT_TEXT_DELTA':
      activeScenario.assistantDeltaText += event.payload?.delta ?? '';
      break;
    case 'ITEM_COMPLETED':
      if (event.payload?.item?.kind === 'ASSISTANT_TEXT') {
        activeScenario.assistantCompletedText += event.payload.item.body?.text ?? '';
      } else if (event.payload?.item?.kind === 'TOOL_CALL') {
        activeScenario.sawToolCall = true;
      }
      break;
    case 'TOOL_CALL_ARGUMENTS_DONE':
      activeScenario.sawToolCall = true;
      break;
    case 'TOOL_EXECUTION_END':
      activeScenario.sawToolExecutionEnd = true;
      break;
    case 'TOOL_RESULT':
      activeScenario.sawToolResult = true;
      break;
    case 'COMPACT_FINISHED':
      activeScenario.compactText += event.payload?.text ?? '';
      break;
    case 'TURN_ABORTED':
      activeScenario.sawAbort = true;
      break;
    default:
      break;
  }
}

function rememberEvent(event) {
  recentEvents.push({
    type: event.type,
    payload: event.payload,
  });
  if (recentEvents.length > 50) {
    recentEvents.shift();
  }
}

function recentEventText() {
  return `Recent events:\n${JSON.stringify(recentEvents, null, 2)}`;
}

function request(method, params, timeoutMs = 15000) {
  const id = String(nextRequestId++);
  const message = params === undefined ? { id, method } : { id, method, params };
  const response = new Promise((resolve, reject) => {
    const responseTimeout = setTimeout(() => {
      pendingResponses.delete(id);
      reject(new Error(`${method} response timed out.`));
    }, timeoutMs);
    pendingResponses.set(id, {
      resolve: value => {
        clearTimeout(responseTimeout);
        resolve(value);
      },
      reject,
    });
  });
  send(message);
  return response.then(message => {
    if (message.error) {
      throw new Error(`${method} returned an error: ${JSON.stringify(message.error)}`);
    }
    return message.result;
  });
}

function waitForEvent(label, predicate, timeoutMs) {
  return new Promise((resolve, reject) => {
    const eventTimeout = setTimeout(() => {
      const index = eventWaiters.indexOf(waiter);
      if (index >= 0) {
        eventWaiters.splice(index, 1);
      }
      reject(new Error(`${label} timed out.`));
    }, timeoutMs);
    const waiter = { predicate, resolve, timeout: eventTimeout };
    eventWaiters.push(waiter);
  });
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

      const kind = classifyProviderRequest(body);
      if (kind === 'interrupt') {
        writeHangingStream(response);
        return;
      }

      const events = switchProviderEvents(kind);
      writeSseEvents(response, events);
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

function classifyProviderRequest(body) {
  providerRequestCount++;
  if (body.includes('interrupt native smoke')) {
    if (body.includes(COMPACT_SMOKE)) {
      sawResumedCompactSummaryInModelRequest = true;
    }
    return 'interrupt';
  }
  if (body.includes('CONTEXT CHECKPOINT COMPACTION')) {
    return 'compact';
  }
  if (body.includes('function_call_output') || body.includes(READ_FILE_CONTENT)) {
    return 'tool-final';
  }
  if (body.includes('tool native smoke')) {
    return 'tool-call';
  }
  return 'text';
}

function switchProviderEvents(kind) {
  switch (kind) {
    case 'compact':
      return textResponseEvents(COMPACT_SMOKE, `compact_${providerRequestCount}`);
    case 'tool-call':
      return toolCallEvents();
    case 'tool-final':
      return textResponseEvents(TOOL_SMOKE, `tool_final_${providerRequestCount}`);
    default:
      return textResponseEvents(TEXT_SMOKE, `text_${providerRequestCount}`);
  }
}

function writeSseEvents(response, events) {
  response.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
  });
  for (const event of events) {
    response.write(`event: ${event.type}\n`);
    response.write(`data: ${JSON.stringify(event)}\n\n`);
  }
  response.write('data: [DONE]\n\n');
  response.end();
}

function writeHangingStream(response) {
  response.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
  });
  for (const event of [
    {
      type: 'response.created',
      sequence_number: 0,
      response: responseObject('interrupt', 'in_progress', []),
    },
    {
      type: 'response.output_item.added',
      sequence_number: 1,
      output_index: 0,
      item: messageItem('interrupt', 'in_progress', ''),
    },
  ]) {
    response.write(`event: ${event.type}\n`);
    response.write(`data: ${JSON.stringify(event)}\n\n`);
  }
  const fallback = setTimeout(() => {
    if (!response.destroyed) {
      response.end();
    }
  }, 60000);
  response.on('close', () => clearTimeout(fallback));
}

function textResponseEvents(text, suffix) {
  const completedMessage = messageItem(suffix, 'completed', text);
  return [
    {
      type: 'response.created',
      sequence_number: 0,
      response: responseObject(suffix, 'in_progress', []),
    },
    {
      type: 'response.output_item.added',
      sequence_number: 1,
      output_index: 0,
      item: reasoningItem(suffix, 'in_progress'),
    },
    {
      type: 'response.output_item.done',
      sequence_number: 2,
      output_index: 0,
      item: reasoningItem(suffix, 'completed'),
    },
    {
      type: 'response.output_item.added',
      sequence_number: 3,
      output_index: 1,
      item: messageItem(suffix, 'in_progress', ''),
    },
    {
      type: 'response.content_part.added',
      sequence_number: 4,
      output_index: 1,
      item_id: `${MESSAGE_ID}_${suffix}`,
      content_index: 0,
      part: {
        type: 'output_text',
        text: '',
        annotations: [],
        logprobs: [],
      },
    },
    {
      type: 'response.output_text.delta',
      sequence_number: 5,
      output_index: 1,
      item_id: `${MESSAGE_ID}_${suffix}`,
      content_index: 0,
      delta: text,
      logprobs: [],
      obfuscation: 'native-smoke-extra-field',
    },
    {
      type: 'response.output_text.done',
      sequence_number: 6,
      output_index: 1,
      item_id: `${MESSAGE_ID}_${suffix}`,
      content_index: 0,
      text,
      logprobs: [],
    },
    {
      type: 'response.content_part.done',
      sequence_number: 7,
      output_index: 1,
      item_id: `${MESSAGE_ID}_${suffix}`,
      content_index: 0,
      part: {
        type: 'output_text',
        text,
        annotations: [],
        logprobs: [],
      },
    },
    {
      type: 'response.completed',
      sequence_number: 8,
      response: responseObject(suffix, 'completed'),
    },
  ];
}

function toolCallEvents() {
  const argumentsJson = JSON.stringify({ file_path: readFilePath });
  const completedToolCall = functionCallItem('completed', argumentsJson);
  return [
    {
      type: 'response.created',
      sequence_number: 0,
      response: responseObject('tool_call', 'in_progress', []),
    },
    {
      type: 'response.output_item.added',
      sequence_number: 1,
      output_index: 0,
      item: functionCallItem('in_progress', ''),
    },
    {
      type: 'response.function_call_arguments.delta',
      sequence_number: 2,
      output_index: 0,
      item_id: TOOL_ITEM_ID,
      delta: argumentsJson,
    },
    {
      type: 'response.function_call_arguments.done',
      sequence_number: 3,
      output_index: 0,
      item_id: TOOL_ITEM_ID,
      arguments: argumentsJson,
    },
    {
      type: 'response.output_item.done',
      sequence_number: 4,
      output_index: 0,
      item: completedToolCall,
    },
    {
      type: 'response.completed',
      sequence_number: 5,
      response: responseObject('tool_call', 'completed'),
    },
  ];
}

function responseObject(suffix, status, output) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const response = {
    id: `${RESPONSE_ID}_${suffix}`,
    object: 'response',
    created_at: nowSeconds,
    error: null,
    incomplete_details: null,
    instructions: null,
    metadata: {},
    model: 'smoke-model',
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
  if (output !== undefined) {
    response.output = output;
  }
  return response;
}

function messageItem(suffix, status, text) {
  return {
    id: `${MESSAGE_ID}_${suffix}`,
    type: 'message',
    status,
    role: 'assistant',
    content: status === 'completed'
      ? [
          {
            type: 'output_text',
            text,
            annotations: [],
            logprobs: [],
          },
        ]
      : [],
  };
}

function reasoningItem(suffix, status) {
  return {
    id: `${REASONING_ID}_${suffix}`,
    type: 'reasoning',
    status,
    summary: [],
  };
}

function functionCallItem(status, argumentsJson) {
  return {
    id: TOOL_ITEM_ID,
    type: 'function_call',
    status,
    call_id: TOOL_CALL_ID,
    name: 'Read',
    arguments: argumentsJson,
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

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
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
  for (const pending of pendingResponses.values()) {
    pending.reject(new Error('Native smoke stopped.'));
  }
  pendingResponses.clear();
  for (const waiter of eventWaiters.splice(0)) {
    clearTimeout(waiter.timeout);
  }
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
  if (secondaryCwd) {
    rmSync(secondaryCwd, { recursive: true, force: true });
    secondaryCwd = null;
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
