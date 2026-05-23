import React from 'react';
import { render } from 'ink';
import { AetherClient } from './backend/AetherClient.js';
import { App } from './app/App.js';
import { backendOptions } from './app/bootstrap.js';

const client = new AetherClient(backendOptions());

render(<App client={client} />);
