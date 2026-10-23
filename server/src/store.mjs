import { ApiError, assert } from './errors.mjs';
import { createHmac, randomBytes } from 'node:crypto';

export function threadId(a, b) {
  return [a, b].sort().join(':');
}

export function publicAccount(row) {
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    createdAt: row.created_at,
    status: row.status,
    phone: row.phone,
    email: row.email,
    bio: row.bio
  };
}

export class Store {
  constructor(db, { now = Date.now } = {}) {
    this.db = db;
    this.now = now;
  }

  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  createAccount({ username, displayName, passwordHash, salt, phone = null, email = null, bio = null }) {
    const now = this.now();
    const id = 'acct-' + randomBytes(8).toString('hex');
    this.db.prepare(`
      INSERT INTO accounts (id, username, display_name, salt, password_hash, created_at, status, phone, email, bio)
      VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
    `).run(id, username, displayName, salt, passwordHash, now, phone, email, bio);
    return this.findAccountById(id);
  }

  findAccountByUsername(username) {
    return this.db.prepare('SELECT * FROM accounts WHERE username = ?').get(username) ?? null;
  }

  findAccountById(id) {
    return this.db.prepare('SELECT * FROM accounts WHERE id = ?').get(id) ?? null;
  }

  createSession(accountId, jti) {
    const now = this.now();
    this.db.prepare('INSERT INTO sessions (jti, account_id, created_at, revoked) VALUES (?, ?, ?, 0)')
      .run(jti, accountId, now);
    return { jti, accountId, createdAt: now };
  }

  findSession(jti) {
    return this.db.prepare('SELECT * FROM sessions WHERE jti = ? AND revoked = 0').get(jti) ?? null;
  }

  revokeSession(jti) {
    this.db.prepare('UPDATE sessions SET revoked = 1 WHERE jti = ?').run(jti);
  }

  revokeAllSessions(accountId) {
    this.db.prepare('UPDATE sessions SET revoked = 1 WHERE account_id = ?').run(accountId);
  }

  upsertContact(ownerId, contactId, patch = {}) {
    assert(ownerId !== contactId, 400, 'invalid_contact', 'Cannot add yourself as a contact');
    assert(this.findAccountById(contactId), 404, 'no_such_account', 'No account with that id');
    const now = this.now();
    const existing = this.getContact(ownerId, contactId);
    const value = {
      owner_id: ownerId,
      contact_id: contactId,
      nickname: patch.nickname ?? existing?.nickname ?? null,
      note: patch.note ?? existing?.note ?? null,
      favourite: patch.favourite ?? Boolean(existing?.favourite),
      blocked: patch.blocked ?? Boolean(existing?.blocked),
      hidden: patch.hidden ?? Boolean(existing?.hidden),
      ringtone: patch.ringtone ?? existing?.ringtone ?? null,
      updated_at: now
    };
    this.db.prepare(`
      INSERT INTO contacts (owner_id, contact_id, nickname, note, favourite, blocked, hidden, ringtone, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(owner_id, contact_id) DO UPDATE SET
        nickname = excluded.nickname, note = excluded.note, favourite = excluded.favourite,
        blocked = excluded.blocked, hidden = excluded.hidden, ringtone = excluded.ringtone,
        updated_at = excluded.updated_at
    `).run(value.owner_id, value.contact_id, value.nickname, value.note,
      value.favourite ? 1 : 0, value.blocked ? 1 : 0, value.hidden ? 1 : 0, value.ringtone, value.updated_at);
    if (value.blocked) {
      this.appendEvent(contactId, 'contact.blocked', { by: ownerId, at: now }, now);
    }
    return this.getContact(ownerId, contactId);
  }

  getContact(ownerId, contactId) {
    return this.db.prepare('SELECT * FROM contacts WHERE owner_id = ? AND contact_id = ?')
      .get(ownerId, contactId) ?? null;
  }

  isBlocked(ownerId, targetId) {
    const row = this.getContact(ownerId, targetId);
    return Boolean(row?.blocked);
  }

  listContacts(ownerId) {
    return this.db.prepare('SELECT * FROM contacts WHERE owner_id = ? ORDER BY favourite DESC, contact_id')
      .all(ownerId).map(row => ({
        id: row.owner_id + '->' + row.contact_id,
        ownerId: row.owner_id,
        contactAccountId: row.contact_id,
        nickname: row.nickname,
        note: row.note,
        favourite: Boolean(row.favourite),
        blocked: Boolean(row.blocked),
        hidden: Boolean(row.hidden),
        ringtone: row.ringtone,
        updatedAt: row.updated_at
      }));
  }

  deleteContact(ownerId, contactId) {
    this.db.prepare('DELETE FROM contacts WHERE owner_id = ? AND contact_id = ?').run(ownerId, contactId);
  }

  sendMessage({ senderId, recipientId, body, clientId, replyToId = null, expiresAt = null, transcript = null }) {
    assert(senderId !== recipientId, 400, 'invalid_recipient', 'Cannot send to yourself');
    assert(this.findAccountById(recipientId), 404, 'no_such_account', 'No account with that id');
    assert(!this.isBlocked(recipientId, senderId), 403, 'blocked', 'This account is not accepting your messages');

    const existing = this.db.prepare('SELECT * FROM messages WHERE sender_id = ? AND client_id = ?')
      .get(senderId, clientId);
    if (existing) return this.messageFor(senderId, existing);

    const now = this.now();
    const id = 'msg-' + randomBytes(10).toString('hex');
    let seq;
    this.tx(() => {
      const info = this.db.prepare(`
        INSERT INTO messages (id, client_id, sender_id, recipient_id, body, created_at, edited_at, deleted, reply_to, expires_at, transcript)
        VALUES (?, ?, ?, ?, ?, ?, NULL, 0, ?, ?, ?)
      `).run(id, clientId, senderId, recipientId, body, now, replyToId, expiresAt, transcript);
      seq = Number(info.lastInsertRowid);
      this.db.prepare('INSERT INTO receipts (message_id, account_id, state, at) VALUES (?, ?, ?, ?)')
        .run(id, senderId, 'SENT', now);
      const recipientView = this.messageFor(recipientId, this.db.prepare('SELECT * FROM messages WHERE id = ?').get(id));
      this.appendEvent(recipientId, 'message.created', recipientView, now);
      const senderView = this.messageFor(senderId, this.db.prepare('SELECT * FROM messages WHERE id = ?').get(id));
      this.appendEvent(senderId, 'message.created', senderView, now);
    });
    return this.messageFor(senderId, this.db.prepare('SELECT * FROM messages WHERE seq = ?').get(seq));
  }

  messageFor(viewerId, row) {
    if (!row) return null;
    const reactions = this.db.prepare('SELECT account_id, emoji, at FROM reactions WHERE message_id = ? ORDER BY at')
      .all(row.id).map(r => ({ emoji: r.emoji, senderId: r.account_id, at: r.at }));
    const receipts = this.db.prepare('SELECT account_id, state FROM receipts WHERE message_id = ?')
      .all(row.id);
    const otherId = row.sender_id === viewerId ? row.recipient_id : row.sender_id;
    const otherReceipt = receipts.find(r => r.account_id === otherId);
    const ownReceipt = receipts.find(r => r.account_id === viewerId);
    let state;
    if (row.sender_id === viewerId) {
      state = otherReceipt?.state === 'READ' ? 'READ' : otherReceipt?.state === 'DELIVERED' ? 'DELIVERED' : 'SENT';
    } else {
      state = ownReceipt?.state === 'READ' ? 'READ' : 'DELIVERED';
    }
    return {
      id: row.id,
      clientId: row.client_id,
      conversationId: threadId(row.sender_id, row.recipient_id),
      senderId: row.sender_id,
      recipientId: row.recipient_id,
      body: row.deleted ? '' : row.body,
      createdAt: row.created_at,
      state,
      replyToId: row.reply_to,
      editedAt: row.edited_at,
      reactions,
      deleted: Boolean(row.deleted),
      expiresAt: row.expires_at,
      transcript: row.transcript,
      seq: row.seq
    };
  }

  listMessages(accountId, peerId, { sinceSeq = 0, limit = 200 } = {}) {
    const now = this.now();
    const rows = this.db.prepare(`
      SELECT * FROM messages
      WHERE seq > ? AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
        AND (expires_at IS NULL OR expires_at > ?)
      ORDER BY seq ASC LIMIT ?
    `).all(sinceSeq, accountId, peerId, peerId, accountId, now, limit);
    return rows.map(row => this.messageFor(accountId, row));
  }

  markDelivered(viewerId, rows) {
    const now = this.now();
    const insert = this.db.prepare(`
      INSERT INTO receipts (message_id, account_id, state, at) VALUES (?, ?, 'DELIVERED', ?)
      ON CONFLICT(message_id, account_id) DO NOTHING
    `);
    for (const row of rows) {
      if (row.recipient_id !== viewerId) continue;
      const existing = this.db.prepare('SELECT state FROM receipts WHERE message_id = ? AND account_id = ?')
        .get(row.id, viewerId);
      if (existing) continue;
      insert.run(row.id, viewerId, now);
      this.appendEvent(row.sender_id, 'message.delivered', {
        messageId: row.id, by: viewerId, at: now
      }, now);
    }
  }

  markRead(accountId, messageId) {
    const row = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    assert(row, 404, 'no_such_message', 'No such message');
    assert(row.recipient_id === accountId, 403, 'not_recipient', 'Only the recipient can mark a message read');
    const now = this.now();
    this.db.prepare(`
      INSERT INTO receipts (message_id, account_id, state, at) VALUES (?, ?, 'READ', ?)
      ON CONFLICT(message_id, account_id) DO UPDATE SET state = 'READ', at = excluded.at
    `).run(messageId, accountId, now);
    this.appendEvent(row.sender_id, 'message.read', { messageId, by: accountId, at: now }, now);
    return this.messageFor(accountId, row);
  }

  editMessage(accountId, messageId, body) {
    const row = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    assert(row, 404, 'no_such_message', 'No such message');
    assert(row.sender_id === accountId, 403, 'not_sender', 'Only the sender can edit this message');
    assert(!row.deleted, 409, 'deleted', 'Deleted messages cannot be edited');
    const now = this.now();
    this.db.prepare('UPDATE messages SET body = ?, edited_at = ? WHERE id = ?').run(body, now, messageId);
    const updated = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    this.appendEvent(row.recipient_id, 'message.updated', this.messageFor(row.recipient_id, updated), now);
    return this.messageFor(accountId, updated);
  }

  deleteMessage(accountId, messageId, forEveryone) {
    const row = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    assert(row, 404, 'no_such_message', 'No such message');
    assert(row.sender_id === accountId, 403, 'not_sender', 'Only the sender can delete for everyone');
    const now = this.now();
    this.db.prepare('UPDATE messages SET deleted = 1, body = \'\' WHERE id = ?').run(messageId);
    if (forEveryone) {
      const updated = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
      this.appendEvent(row.recipient_id, 'message.deleted', this.messageFor(row.recipient_id, updated), now);
    }
    return true;
  }

  addReaction(accountId, messageId, emoji) {
    const row = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    assert(row, 404, 'no_such_message', 'No such message');
    assert(row.sender_id === accountId || row.recipient_id === accountId, 403, 'not_participant', 'Not your conversation');
    const now = this.now();
    this.db.prepare(`
      INSERT INTO reactions (message_id, account_id, emoji, at) VALUES (?, ?, ?, ?)
      ON CONFLICT(message_id, account_id) DO UPDATE SET emoji = excluded.emoji, at = excluded.at
    `).run(messageId, accountId, emoji, now);
    this.notifyOther(row, accountId, 'message.reactions', now);
    return this.messageFor(accountId, row);
  }

  removeReaction(accountId, messageId) {
    const row = this.db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
    assert(row, 404, 'no_such_message', 'No such message');
    const now = this.now();
    this.db.prepare('DELETE FROM reactions WHERE message_id = ? AND account_id = ?').run(messageId, accountId);
    this.notifyOther(row, accountId, 'message.reactions', now);
    return this.messageFor(accountId, row);
  }

  notifyOther(row, actorId, type, at) {
    const other = row.sender_id === actorId ? row.recipient_id : row.sender_id;
    this.appendEvent(other, type, this.messageFor(other, row), at);
    this.appendEvent(actorId, type, this.messageFor(actorId, row), at);
  }

  listThreads(accountId) {
    const rows = this.db.prepare(`
      SELECT m.*,
        (SELECT COUNT(*) FROM messages x WHERE x.sender_id = m.recipient_id AND x.recipient_id = m.sender_id) AS ignored
      FROM messages m
      WHERE (m.sender_id = ? OR m.recipient_id = ?) AND m.seq IN (
        SELECT MAX(seq) FROM messages WHERE sender_id = ? OR recipient_id = ? GROUP BY
          CASE WHEN sender_id = ? THEN recipient_id ELSE sender_id END
      )
      ORDER BY m.seq DESC
    `).all(accountId, accountId, accountId, accountId, accountId);
    return rows.map(row => {
      const peerId = row.sender_id === accountId ? row.recipient_id : row.sender_id;
      const last = this.messageFor(accountId, row);
      const unread = this.db.prepare(`
        SELECT COUNT(*) AS n FROM messages m
        WHERE m.sender_id = ? AND m.recipient_id = ? AND NOT EXISTS (
          SELECT 1 FROM receipts r WHERE r.message_id = m.id AND r.account_id = ? AND r.state = 'READ'
        ) AND (m.expires_at IS NULL OR m.expires_at > ?)
      `).get(peerId, accountId, accountId, this.now()).n;
      const contact = this.getContact(accountId, peerId);
      return {
        peerId,
        threadId: threadId(accountId, peerId),
        lastMessage: last,
        unread: Number(unread),
        favourite: Boolean(contact?.favourite),
        blocked: Boolean(contact?.blocked),
        muted: false,
        updatedAt: row.created_at
      };
    });
  }

  setPresence(accountId, state, availability = 'Available') {
    const now = this.now();
    this.db.prepare(`
      INSERT INTO presence (account_id, state, last_seen, availability) VALUES (?, ?, ?, ?)
      ON CONFLICT(account_id) DO UPDATE SET state = excluded.state, last_seen = excluded.last_seen,
        availability = excluded.availability
    `).run(accountId, state, now, availability);
    const watchers = this.db.prepare('SELECT owner_id FROM contacts WHERE contact_id = ?').all(accountId);
    for (const watcher of watchers) {
      this.appendEvent(watcher.owner_id, 'presence.changed', { accountId, state, lastSeen: now, availability }, now);
    }
    return this.getPresence(accountId);
  }

  getPresence(accountId) {
    const row = this.db.prepare('SELECT * FROM presence WHERE account_id = ?').get(accountId);
    if (!row) return null;
    return {
      accountId: row.account_id,
      state: row.state,
      lastSeen: row.last_seen,
      availability: row.availability
    };
  }

  appendEvent(accountId, type, payload, at = this.now()) {
    this.db.prepare('INSERT INTO events (account_id, type, payload, at) VALUES (?, ?, ?, ?)')
      .run(accountId, type, JSON.stringify(payload), at);
  }

  eventsSince(accountId, since = 0, limit = 200) {
    const rows = this.db.prepare('SELECT * FROM events WHERE account_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?')
      .all(accountId, since, limit);
    const events = rows.map(row => ({
      seq: row.seq,
      type: row.type,
      payload: JSON.parse(row.payload),
      at: row.at
    }));
    return {
      events,
      cursor: events.length ? events[events.length - 1].seq : since
    };
  }

  pruneExpired(now = this.now()) {
    const expired = this.db.prepare('SELECT * FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?').all(now);
    for (const row of expired) {
      this.appendEvent(row.recipient_id, 'message.deleted', { messageId: row.id, at: now }, now);
      this.appendEvent(row.sender_id, 'message.deleted', { messageId: row.id, at: now }, now);
    }
    this.db.prepare('DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?').run(now);
    return expired.length;
  }
}
