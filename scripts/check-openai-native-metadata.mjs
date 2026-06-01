#!/usr/bin/env node

import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';

const repoRoot = resolve(new URL('..', import.meta.url).pathname);
const targetClassesPath = resolve(repoRoot, 'target/classes');
const reflectConfigPath = resolve(
  repoRoot,
  'src/main/resources/META-INF/native-image/io.github.lingjiuu/aether/reflect-config.json',
);

const reflectConfig = JSON.parse(readFileSync(reflectConfigPath, 'utf8'));
const byName = new Map(reflectConfig.map(entry => [entry.name, entry]));

const aetherCount = await checkAetherJsonMetadata(byName);
const openAiCount = await checkOpenAiAnySetterMetadata(byName);

console.log(`Native metadata covers ${aetherCount} Aether JSON classes and ${openAiCount} OpenAI any-setter classes.`);

async function checkAetherJsonMetadata(byName) {
  if (!existsSync(targetClassesPath)) {
    fail(`Missing ${targetClassesPath}. Run mvn compile first.`);
  }

  const compiledClasses = await classNamesUnder(resolve(targetClassesPath, 'io/github/lingjiuu'));
  const required = new Set(compiledClasses.filter(isCriticalAetherJsonClass));

  for (const subtype of await jacksonSubtypes()) {
    required.add(subtype);
  }
  for (const root of await jacksonTypeRoots()) {
    required.add(root);
  }

  const missing = [];
  const weak = [];
  for (const className of [...required].sort()) {
    const entry = byName.get(className);
    if (!entry) {
      missing.push(className);
      continue;
    }
    if (entry.allDeclaredMethods !== true && entry.allPublicMethods !== true) {
      weak.push(className);
    }
  }

  if (missing.length > 0) {
    fail(`Aether native metadata is missing JSON classes:\n${missing.join('\n')}`);
  }
  if (weak.length > 0) {
    fail(`Aether native metadata entries need reflected methods:\n${weak.join('\n')}`);
  }

  return required.size;
}

async function checkOpenAiAnySetterMetadata(byName) {
const openAiVersion = readFileSync(resolve(repoRoot, 'pom.xml'), 'utf8')
  .match(/<artifactId>openai-java<\/artifactId>\s*<version>([^<]+)<\/version>/s)?.[1];
if (!openAiVersion) {
  fail('Could not find openai-java version in pom.xml.');
}

const openAiCoreJar = resolve(
  homedir(),
  '.m2/repository/com/openai/openai-java-core',
  openAiVersion,
  `openai-java-core-${openAiVersion}.jar`,
);
if (!existsSync(openAiCoreJar)) {
  fail(`Missing ${openAiCoreJar}. Run mvn -DskipTests compile first.`);
}

const tempDir = mkdtempSync(resolve(tmpdir(), 'aether-openai-native-metadata-'));
try {
  runJarExtract(tempDir, openAiCoreJar, [
    'com/openai/models/responses',
    'com/openai/models/Reasoning.class',
    'com/openai/models/Reasoning$Builder.class',
  ]);

  const required = new Set([
    ...(await classesWithAnySetter(resolve(tempDir, 'com/openai/models/responses'))),
    ...(await rootAnySetterClasses(tempDir)),
  ]);

  const missing = [];
  for (const className of [...required].sort()) {
    const entry = byName.get(className);
    if (!entry
        || entry.allDeclaredConstructors !== true
        || entry.allDeclaredFields !== true
        || entry.allDeclaredMethods !== true) {
      missing.push(className);
    }
  }

  if (missing.length > 0) {
    fail(`OpenAI native metadata is missing declared constructors, fields, and methods for:\n${missing.join('\n')}`);
  }

  return required.size;
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}
}

function isCriticalAetherJsonClass(className) {
  if (className.includes('$') && /\$(?:[A-Za-z0-9_]+Builder|\d+)$/.test(className)) {
    return false;
  }

  if (className.startsWith('io.github.lingjiuu.protocol.')) {
    return ![
      'io.github.lingjiuu.protocol.UiCommandPayloads',
      'io.github.lingjiuu.protocol.UiEventPayloads',
      'io.github.lingjiuu.protocol.UiItemBodies',
    ].includes(className);
  }

  if (className.startsWith('io.github.lingjiuu.message.content.')) {
    return true;
  }
  if (className.startsWith('io.github.lingjiuu.message.')) {
    return !className.endsWith('.MessageContents');
  }

  if (className === 'io.github.lingjiuu.context.EnvironmentContext'
      || className === 'io.github.lingjiuu.context.EnvironmentContext$Field') {
    return true;
  }

  if (className.startsWith('io.github.lingjiuu.provider.')) {
    return true;
  }

  if (/^io\.github\.lingjiuu\.model\.(ModelInfo|ModelOption|ModelSelection|ReasoningOptions(?:\$ReasoningEffort|\$ReasoningSummaryEffort)?|TokenUsage|TokenUsageInfo)$/.test(className)) {
    return true;
  }
  if (/^io\.github\.lingjiuu\.model\.client\.(AssistantStreamEvent(?:\$Type)?|ModelCallOptions|ModelErrorCode|ModelErrorInfo|ModelRequest|ModelRetryOptions)$/.test(className)) {
    return true;
  }

  if (/^io\.github\.lingjiuu\.trace\.Trace(ArtifactRecord|Context|EventRecord|RunDetail|RunRecord|SpanRecord)$/.test(className)) {
    return true;
  }

  if (/^io\.github\.lingjiuu\.tool\.(ToolCallResult|ToolFailure|ValidationResult)$/.test(className)) {
    return true;
  }
  if (/^io\.github\.lingjiuu\.tool\.permission\.(ApprovalId|ApprovalRequest|ApprovalResponse)$/.test(className)) {
    return true;
  }
  if (/^io\.github\.lingjiuu\.tool\.result\.(ModelToolResult|PersistedToolOutput|ProcessedToolResult|ToolDisplayResult|ToolResultArtifactRef|ToolResultContext|ToolResultInput|ToolResultPolicy|ToolResultReplacement)$/.test(className)) {
    return true;
  }
  if (/^io\.github\.lingjiuu\.tool\.builtin\..+\$(Input|Output)$/.test(className)) {
    return true;
  }
  if (className === 'io.github.lingjiuu.tool.builtin.diff.StructuredDiff$Hunk'
      || className === 'io.github.lingjiuu.tool.file.ReadFileState$Snapshot') {
    return true;
  }
  if (/^io\.github\.lingjiuu\.tool\.builtin\.shell\.ShellOutputCapture\$(Snapshot|StreamSnapshot|StreamTruncation)$/.test(className)) {
    return true;
  }

  if (/^io\.github\.lingjiuu\.transcript\.(TranscriptModelSelection|TranscriptReconstruction|TranscriptRecord)$/.test(className)) {
    return true;
  }
  if (className.startsWith('io.github.lingjiuu.transcript.item.')) {
    return true;
  }

  if (/^io\.github\.lingjiuu\.transport\.JsonRpc/.test(className)) {
    return true;
  }
  if (className === 'io.github.lingjiuu.transport.stdio.StdioAetherServer$SkillInfo') {
    return true;
  }

  if (className === 'io.github.lingjiuu.wire.WireReplayData') {
    return true;
  }
  if (/^io\.github\.lingjiuu\.wire\.openai\.OpenAiReplayData(?:\$ReplayItem|\$Type)?$/.test(className)) {
    return true;
  }

  return false;
}

async function jacksonSubtypes() {
  const sourceFiles = await javaFiles(resolve(repoRoot, 'src/main/java/io/github/lingjiuu'));
  const subtypes = new Set();
  for (const filePath of sourceFiles) {
    const content = await readFile(filePath, 'utf8');
    const packageName = content.match(/package\s+([A-Za-z0-9_.]+);/)?.[1];
    if (!packageName) {
      continue;
    }
    const imports = importedTypes(content);
    const matcher = content.matchAll(/@JsonSubTypes\.Type\(value\s*=\s*([A-Za-z0-9_.$]+)\.class/g);
    for (const match of matcher) {
      subtypes.add(resolveJavaTypeToken(packageName, imports, match[1]));
    }
  }
  return subtypes;
}

async function jacksonTypeRoots() {
  const sourceFiles = await javaFiles(resolve(repoRoot, 'src/main/java/io/github/lingjiuu'));
  const roots = new Set();
  for (const filePath of sourceFiles) {
    const content = await readFile(filePath, 'utf8');
    if (!content.includes('@JsonTypeInfo')) {
      continue;
    }
    const packageName = content.match(/package\s+([A-Za-z0-9_.]+);/)?.[1];
    const root = content.match(/public\s+(?:sealed\s+)?(?:interface|class|record)\s+([A-Za-z0-9_]+)/)?.[1];
    if (packageName && root) {
      roots.add(`${packageName}.${root}`);
    }
  }
  return roots;
}

function importedTypes(content) {
  const imports = new Map();
  for (const match of content.matchAll(/import\s+([A-Za-z0-9_.]+);/g)) {
    const qualifiedName = match[1];
    imports.set(qualifiedName.slice(qualifiedName.lastIndexOf('.') + 1), qualifiedName);
  }
  return imports;
}

function resolveJavaTypeToken(packageName, imports, token) {
  if (token.startsWith('io.github.lingjiuu.')) {
    return token;
  }
  const parts = token.split('.');
  if (imports.has(parts[0])) {
    const importedName = imports.get(parts[0]);
    if (parts.length === 1) {
      return importedName;
    }
    return `${importedName}$${parts.slice(1).join('$')}`;
  }
  if (parts.length === 1) {
    return `${packageName}.${token}`;
  }
  return `${packageName}.${parts[0]}$${parts.slice(1).join('$')}`;
}

async function rootAnySetterClasses(tempDir) {
  const names = [];
  for (const relativePath of [
    'com/openai/models/Reasoning.class',
    'com/openai/models/Reasoning$Builder.class',
  ]) {
    const filePath = resolve(tempDir, relativePath);
    if (!existsSync(filePath)) {
      continue;
    }
    const content = await readFile(filePath);
    if (content.includes('putAdditionalProperty')) {
      names.push(className(tempDir, filePath));
    }
  }
  return names;
}

async function classesWithAnySetter(root) {
  const matches = [];
  await walk(root, async filePath => {
    if (!filePath.endsWith('.class')) {
      return;
    }
    const content = await readFile(filePath);
    if (content.includes('putAdditionalProperty')) {
      matches.push(className(resolve(root, '../../../..'), filePath));
    }
  });
  return matches;
}

async function classNamesUnder(root) {
  const matches = [];
  await walk(root, async filePath => {
    if (filePath.endsWith('.class')) {
      matches.push(className(targetClassesPath, filePath));
    }
  });
  return matches;
}

async function javaFiles(root) {
  const matches = [];
  await walk(root, async filePath => {
    if (filePath.endsWith('.java')) {
      matches.push(filePath);
    }
  });
  return matches;
}

async function walk(root, visitor) {
  if (!existsSync(root)) {
    return;
  }
  const entries = await readdir(root, { withFileTypes: true });
  for (const entry of entries) {
    const path = resolve(root, entry.name);
    if (entry.isDirectory()) {
      await walk(path, visitor);
    } else if (entry.isFile()) {
      await visitor(path);
    }
  }
}

function className(root, filePath) {
  return filePath
    .slice(root.length + 1)
    .replaceAll(sep, '.')
    .replace(/\.class$/, '');
}

function runJarExtract(cwd, jarPath, entries) {
  const result = spawnSync('jar', ['xf', jarPath, ...entries], {
    cwd,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    fail(`Failed to extract ${jarPath}.`);
  }
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
