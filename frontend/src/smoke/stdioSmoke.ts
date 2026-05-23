import { AetherClient } from '../backend/AetherClient.js';
import { backendOptions } from '../app/bootstrap.js';

const client = new AetherClient(backendOptions());

try {
  client.start();
  const initialized = await client.initialize();
  const current = await client.currentSession();
  const history = await client.history();
  await client.initialized();
  console.log(
    JSON.stringify({
      protocolVersion: initialized.protocolVersion,
      sessionId: current.sessionId,
      turnCount: history.turns?.length ?? 0,
    }),
  );
} finally {
  client.close();
}
