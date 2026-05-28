import { StdioJsonRpcClient } from './StdioJsonRpcClient.mjs';

export class AetherEvalClient {
  constructor(options) {
    this.transport = new StdioJsonRpcClient(options);
  }

  start() {
    this.transport.start();
  }

  onEvent(handler) {
    return this.transport.onNotification(notification => {
      if (notification.method === 'event') {
        handler(notification.params);
      }
    });
  }

  onStderr(handler) {
    return this.transport.onStderr(handler);
  }

  initialize() {
    return this.transport.request('initialize');
  }

  initialized() {
    return this.transport.request('initialized');
  }

  currentSession() {
    return this.transport.request('session/current');
  }

  history() {
    return this.transport.request('history/read');
  }

  eventsAfter(afterSequence, limit = 200) {
    return this.transport.request('events/list', { afterSequence, limit });
  }

  setPermissionMode(permissionMode) {
    return this.transport.request('permission/set', { permissionMode });
  }

  submitText(text) {
    return this.transport.request('turn/submit', {
      items: [{ type: 'text', text }],
    });
  }

  cancelTurn() {
    return this.transport.request('turn/cancel');
  }

  close() {
    this.transport.close();
  }
}
