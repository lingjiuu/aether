import { AetherClient } from './backend/AetherClient.js';
import { backendOptions } from './app/bootstrap.js';
import { TerminalApp } from './terminal/TerminalApp.js';

const client = new AetherClient(backendOptions());
const app = new TerminalApp(client);

app.start().catch(error => {
  app.stop();
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
