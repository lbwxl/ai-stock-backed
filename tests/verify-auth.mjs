// Local auth integration checks. Creates uniquely named fixtures and removes them.
// Never prints JWTs, captcha values or real service keys.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const base = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname));
const userName = `auth_${randomBytes(5).toString('hex')}`;
const password = randomBytes(8).toString('hex');
const fixtures = [];
const sessions = [];
let adminToken;
let fixtureUserId;

async function request(path, { token, method = 'GET', body } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(15000),
  });
  return { status: response.status, data: await response.json() };
}

async function ok(path, options = {}) {
  const result = await request(path, options);
  assert.equal(result.data.code, 200, 'Business request failed');
  return result.data;
}

async function login(username, loginPassword) {
  const captcha = await ok('/captchaImage');
  let code = '';
  if (captcha.captchaEnabled !== false) {
    assert.match(captcha.uuid, /^[a-zA-Z0-9-]+$/);
    const cached = execFileSync(process.env.REDIS_CLI || 'redis-cli', [
      '--raw', '-h', '127.0.0.1', '-p', '6379', 'GET', `captcha_codes:${captcha.uuid}`,
    ], { encoding: 'utf8' }).trim();
    try { code = String(JSON.parse(cached)); } catch { code = cached; }
    assert.ok(code);
  }
  const response = await ok('/login', {
    method: 'POST', body: { username, password: loginPassword, uuid: captcha.uuid, code },
  });
  assert.ok(typeof response.token === 'string' && response.token.length > 20);
  sessions.push(response.token);
  return response.token;
}

async function check(name, action) {
  await action();
  console.log(`PASS ${name}`);
}

async function createFixture(group, body) {
  await ok(`/webui/${group}`, { token: adminToken, method: 'POST', body });
  const filter = group === 'config' ? { userId: fixtureUserId } :
    group === 'model' ? { modelName: userName } : { serviceName: userName };
  const result = await ok(`/webui/${group}/list?${new URLSearchParams(filter)}`, { token: adminToken });
  assert.equal(result.rows.length, 1, 'Fixture was not unique');
  fixtures.push({ group, id: result.rows[0].id });
  return result.rows[0].id;
}

try {
  await check('native login issues JWT with system captcha enabled', async () => {
    adminToken = await login(process.env.TEST_USERNAME || 'admin', process.env.TEST_PASSWORD || 'admin123');
  });
  const admin = (await ok('/getInfo', { token: adminToken })).user;
  await check('missing and invalid JWT are rejected; CORS allows Authorization', async () => {
    for (const token of [undefined, 'invalid-test-token']) {
      const result = await request('/webui/config/current', { token });
      assert.equal(result.data.code, 401);
    }
    const response = await fetch(base + '/webui/config/current', {
      method: 'OPTIONS', headers: {
        Origin: 'http://127.0.0.1:3000',
        'Access-Control-Request-Method': 'GET',
        'Access-Control-Request-Headers': 'authorization',
      },
    });
    assert.equal(response.headers.get('access-control-allow-origin'), 'http://127.0.0.1:3000');
    assert.match(response.headers.get('access-control-allow-headers'), /authorization/i);
  });
  await check('retired login has no anonymous access and no authenticated mapping', async () => {
    const body = { username: admin.userName, password: 'unused' };
    assert.equal((await request('/webui/auth/in', { method: 'POST', body })).data.code, 401);
    assert.equal((await request('/webui/auth/in', { token: adminToken, method: 'POST', body })).status, 404);
  });

  // Create a normal user without management roles; never modify an existing user.
  await ok('/system/user', {
    token: adminToken, method: 'POST',
    body: { userName, nickName: userName, password, status: '0', roleIds: [], postIds: [] },
  });
  const users = await ok(`/system/user/list?userName=${userName}`, { token: adminToken });
  const owned = users.rows.filter(user => user.userName === userName);
  assert.equal(owned.length, 1);
  fixtureUserId = owned[0].userId;
  const userToken = await login(userName, password);

  await check('ordinary user can query own empty config without management permission', async () => {
    const current = await ok('/webui/config/current', { token: userToken });
    assert.equal(current.data.userId, fixtureUserId);
    assert.equal(current.data.username, userName);
    assert.ok(!current.data.aiService && !current.data.searchService);
    assert.equal((await request('/webui/config/list', { token: userToken })).data.code, 403);
  });

  const serviceId = await createFixture('service', { serviceName: userName, serviceUrl: 'https://example.invalid/v1' });
  const modelId = await createFixture('model', { modelName: userName, aiServiceId: serviceId });
  const searchId = await createFixture('search', { serviceName: userName, serviceUrl: 'https://example.invalid' });
  await createFixture('config', {
    userId: fixtureUserId, aiServiceId: serviceId, modelId, searchServiceId: searchId,
    aiServiceKey: 'fixture-ai-key', searchServiceKey: 'fixture-search-key',
  });
  await check('query IDs cannot switch identity; joined config only belongs to JWT user', async () => {
    const current = await ok(`/webui/config/current?userId=${admin.userId}&id=${admin.userId}`, { token: userToken });
    assert.equal(current.data.userId, fixtureUserId);
    assert.equal(current.data.aiService.aiServiceKey, 'fixture-ai-key');
    assert.equal(current.data.aiService.modelName, userName);
    assert.equal(current.data.searchService.searchServiceKey, 'fixture-search-key');
    const other = await ok(`/webui/config/current?userId=${fixtureUserId}`, { token: adminToken });
    assert.equal(other.data.userId, admin.userId);
    assert.notEqual(other.data.aiService?.aiServiceKey, 'fixture-ai-key');
  });
  await check('native logout invalidates the JWT', async () => {
    await ok('/logout', { token: userToken, method: 'POST' });
    assert.equal((await request('/webui/config/current', { token: userToken })).data.code, 401);
  });
} finally {
  let cleanupFailed = false;
  for (const fixture of fixtures.reverse()) {
    try {
      await ok(`/webui/${fixture.group}/${fixture.id}`, { token: adminToken, method: 'DELETE' });
    } catch { cleanupFailed = true; }
  }
  if (adminToken) {
    try {
      // Recover the ID if creation succeeded but the first lookup was interrupted.
      const result = await ok(`/system/user/list?userName=${userName}`, { token: adminToken });
      const owned = result.rows.filter(user => user.userName === userName);
      assert.ok(owned.length <= 1);
      for (const user of owned) {
        await ok(`/system/user/${user.userId}`, { token: adminToken, method: 'DELETE' });
      }
    } catch { cleanupFailed = true; }
  }
  for (const token of sessions.reverse()) {
    try { await request('/logout', { token, method: 'POST' }); } catch { cleanupFailed = true; }
  }
  assert.ok(!cleanupFailed, 'Fixture or session cleanup failed');
  console.log('PASS fixture and test-session cleanup');
}
