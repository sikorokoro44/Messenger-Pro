import { readFileSync } from 'fs';

const registry = JSON.parse(readFileSync(new URL('../feature-registry.json', import.meta.url), 'utf8'));
const failures = [];
const fail = (msg) => failures.push(msg);

if (registry.total !== 600) fail(`total is ${registry.total}, expected 600`);
if (!Array.isArray(registry.fixtures) || registry.fixtures.length !== 600) {
  fail(`fixtures length is ${registry.fixtures?.length}, expected 600`);
}
if (registry.categories !== 25) fail(`categories is ${registry.categories}, expected 25`);

const ids = new Set();
const categories = new Set();
for (const [i, f] of registry.fixtures.entries()) {
  const expectedId = `MP-${String(i + 1).padStart(3, '0')}`;
  if (f.id !== expectedId) fail(`fixture ${i} has id ${f.id}, expected ${expectedId}`);
  if (ids.has(f.id)) fail(`duplicate id ${f.id}`);
  ids.add(f.id);
  categories.add(f.category);
  if (!f.name || !f.name.trim()) fail(`${f.id} has no name`);
  if (!f.category) fail(`${f.id} has no category`);

  const impl = f.implementationStatus;
  const test = f.testStatus;
  if (impl === 'COMPLETE') {
    if (test !== 'COMPLETE') fail(`${f.id} claims COMPLETE implementation but test is ${test}`);
    if (!Array.isArray(f.evidence?.implementation) || f.evidence.implementation.length === 0) {
      fail(`${f.id} claims COMPLETE implementation without evidence`);
    }
    if (!Array.isArray(f.evidence?.tests) || f.evidence.tests.length === 0) {
      fail(`${f.id} claims COMPLETE implementation without test evidence`);
    }
  }
  if (test === 'COMPLETE' && impl !== 'COMPLETE' && impl !== 'IN_PROGRESS') {
    fail(`${f.id} claims COMPLETE tests without an implementation`);
  }
  if (impl === 'BLOCKED' && !f.note) fail(`${f.id} is BLOCKED without an explanation`);
}

if (categories.size !== 25) fail(`${categories.size} categories found, expected 25`);

const statuses = ['COMPLETE', 'IN_PROGRESS', 'NOT_STARTED', 'BLOCKED'];
const counts = Object.fromEntries(statuses.map(s => [s, registry.fixtures.filter(f => f.implementationStatus === s).length]));
console.log(`Fixtures: ${registry.fixtures.length} across ${categories.size} categories`);
console.log(`Implementation: ${statuses.map(s => `${s}=${counts[s]}`).join(' ')}`);
console.log('Test: ' + statuses.map(s => `${s}=${registry.fixtures.filter(f => f.testStatus === s).length}`).join(' '));

if (failures.length) {
  console.error('\nRegistry verification FAILED:');
  for (const f of failures) console.error(` - ${f}`);
  process.exit(1);
}
console.log('Registry verification passed.');
