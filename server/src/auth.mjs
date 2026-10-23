import { createHmac, pbkdf2Sync, randomBytes, timingSafeEqual } from 'node:crypto';

const ITERATIONS = 120_000;
const KEY_BYTES = 32;

export function randomId(bytes = 16) {
  return randomBytes(bytes).toString('hex');
}

export function hashPassword(password, salt = randomId(16)) {
  const hash = pbkdf2Sync(password, salt, ITERATIONS, KEY_BYTES, 'sha256').toString('hex');
  return { salt, hash };
}

export function verifyPassword(password, salt, expectedHex) {
  const candidate = pbkdf2Sync(password, salt, ITERATIONS, KEY_BYTES, 'sha256');
  const expected = Buffer.from(expectedHex, 'hex');
  if (candidate.length !== expected.length) return false;
  return timingSafeEqual(candidate, expected);
}

function b64url(value) {
  return Buffer.from(value).toString('base64url');
}

function hmac(value, secret) {
  return createHmac('sha256', secret).update(value).digest('base64url');
}

export function signToken({ accountId, jti }, secret, issuedAt = Date.now()) {
  const payload = b64url(JSON.stringify({ sub: accountId, jti, iat: issuedAt }));
  return `${payload}.${hmac(payload, secret)}`;
}

export function verifyToken(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [payload, signature] = parts;
  const expected = hmac(payload, secret);
  const a = Buffer.from(signature);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
  try {
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!decoded || typeof decoded.sub !== 'string' || typeof decoded.jti !== 'string') return null;
    return decoded;
  } catch {
    return null;
  }
}

export function bearer(header) {
  if (typeof header !== 'string') return null;
  const match = /^Bearer (.+)$/.exec(header);
  return match ? match[1] : null;
}
