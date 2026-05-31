#!/usr/bin/env node

import { AetherClient } from './backend/AetherClient.js';
import { backendOptions } from './app/bootstrap.js';
import { ensureAetherConfig } from './setup/configSetup.js';
import { TerminalApp } from './terminal/app/TerminalApp.js';

let app: TerminalApp | undefined;

async function main(): Promise<void> {
  if (!await ensureAetherConfig()) {
    console.error('Aether setup cancelled.');
    process.exitCode = 1;
    return;
  }
  const client = new AetherClient(backendOptions());
  app = new TerminalApp(client);
  await app.start();
}

main().catch(error => {
  app?.stop();
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
