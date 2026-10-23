import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { openDb } from './db.mjs';
import { Store } from './store.mjs';
import { createApp } from './app.mjs';

const port = Number(process.env.PORT ?? 8080);
const databasePath = process.env.DATABASE_PATH ?? './data/messenger.db';
const secret = process.env.AUTH_SECRET ?? 'dev-insecure-secret-change-me';

if (secret === 'dev-insecure-secret-change-me') {
  console.warn('WARNING: AUTH_SECRET is not set; using an insecure development secret.');
}

mkdirSync(dirname(databasePath), { recursive: true });
const db = openDb(databasePath);
const store = new Store(db);
const { server } = createApp({ store, secret });

server.listen(port, '0.0.0.0', () => {
  console.log(`Messenger Pro backend listening on :${port}`);
});

function shutdown(signal) {
  console.log(`Received ${signal}; shutting down`);
  server.close(() => {
    try {
      db.close();
    } finally {
      process.exit(0);
    }
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
