import { DatabaseSync } from 'node:sqlite';

export function openDb(path) {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(`
    CREATE TABLE IF NOT EXISTS accounts (
      id TEXT PRIMARY KEY,
      username TEXT NOT NULL UNIQUE COLLATE NOCASE,
      display_name TEXT NOT NULL,
      salt TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'ACTIVE',
      phone TEXT,
      email TEXT,
      bio TEXT
    );
    CREATE TABLE IF NOT EXISTS sessions (
      jti TEXT PRIMARY KEY,
      account_id TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      revoked INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS contacts (
      owner_id TEXT NOT NULL,
      contact_id TEXT NOT NULL,
      nickname TEXT,
      note TEXT,
      favourite INTEGER NOT NULL DEFAULT 0,
      blocked INTEGER NOT NULL DEFAULT 0,
      hidden INTEGER NOT NULL DEFAULT 0,
      ringtone TEXT,
      updated_at INTEGER NOT NULL,
      PRIMARY KEY (owner_id, contact_id)
    );
    CREATE TABLE IF NOT EXISTS messages (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      id TEXT NOT NULL UNIQUE,
      client_id TEXT NOT NULL,
      sender_id TEXT NOT NULL,
      recipient_id TEXT NOT NULL,
      body TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      edited_at INTEGER,
      deleted INTEGER NOT NULL DEFAULT 0,
      reply_to TEXT,
      expires_at INTEGER,
      transcript TEXT,
      UNIQUE (sender_id, client_id)
    );
    CREATE TABLE IF NOT EXISTS receipts (
      message_id TEXT NOT NULL,
      account_id TEXT NOT NULL,
      state TEXT NOT NULL,
      at INTEGER NOT NULL,
      PRIMARY KEY (message_id, account_id)
    );
    CREATE TABLE IF NOT EXISTS reactions (
      message_id TEXT NOT NULL,
      account_id TEXT NOT NULL,
      emoji TEXT NOT NULL,
      at INTEGER NOT NULL,
      PRIMARY KEY (message_id, account_id)
    );
    CREATE TABLE IF NOT EXISTS presence (
      account_id TEXT PRIMARY KEY,
      state TEXT NOT NULL,
      last_seen INTEGER NOT NULL,
      availability TEXT NOT NULL DEFAULT 'Available'
    );
    CREATE TABLE IF NOT EXISTS events (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      account_id TEXT NOT NULL,
      type TEXT NOT NULL,
      payload TEXT NOT NULL,
      at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages (sender_id, recipient_id, seq);
    CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages (recipient_id, seq);
    CREATE INDEX IF NOT EXISTS idx_events_account ON events (account_id, seq);
  `);
  return db;
}
