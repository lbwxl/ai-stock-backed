#!/usr/bin/env node
// Local integration checks. Requires the restored backend, local Redis and Node >= 18.
// BASE_URL=http://localhost:1024/dev-api node tests/verify-webui.mjs
// Never prints tokens, business rows, exports or real API keys. Only fixture rows are changed.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { promisify } from 'node:util';
import { randomBytes } from 'node:crypto';

const execFileAsync = promisify(execFile);
const base = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const origin = new URL(base);
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname), 'Local server required');
const prefix = `webui_it_${Date.now().toString(36)}_${randomBytes(3).toString('hex')}`;
const cleanup = [];
let token;
let failed = false;

async function request(path, { method = 'GET', body, anonymous = false, raw = false } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(token && !anonymous ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(20000)
  });
  if (raw) return response;
  // Do not include response bodies in assertion errors: they may contain credentials.
  assert.ok(response.status >= 200 && response.status < 500, 'Unexpected HTTP status');
  return response.json();
}

async function ok(path, options) {
  const data = await request(path, options);
  assert.equal(data.code, 200, 'Application request failed');
  return data;
}

function query(values) {
  return new URLSearchParams(Object.entries(values).filter(([, value]) => value != null)).toString();
}

async function list(group, filter = {}, pageNum = 1, pageSize = 100) {
  // Disable RuoYi's default out-of-range page clamping for explicit offset checks.
  const result = await ok(`/webui/${group}/list?${query({ ...filter, pageNum, pageSize, reasonable: false })}`);
  assert.ok(Array.isArray(result.rows), 'Missing rows');
  assert.ok(Number.isInteger(result.total) && result.total >= 0, 'Invalid total');
  return result;
}

async function check(name, fn) {
  try {
    await fn();
    console.log(`PASS ${name}`);
  } catch {
    console.error(`FAIL ${name}`);
    failed = true;
    throw new Error('Check failed');
  }
}

// Register the exact unique ownership filter BEFORE inserting, so cleanup can recover
// an ID even if the response is interrupted after the database has committed.
async function create(group, body, filter, identity) {
  const owned = { group, filter, identity, id: undefined, removed: false };
  cleanup.push(owned);
  await ok(`/webui/${group}`, { method: 'POST', body });
  const found = (await list(group, filter)).rows.filter(identity);
  assert.equal(found.length, 1, 'Fixture lookup was not unique');
  owned.id = found[0].id;
  assert.ok(Number.isSafeInteger(owned.id) && owned.id > 0, 'Invalid fixture ID');
  return owned;
}

async function detail(fixture) {
  const result = await ok(`/webui/${fixture.group}/${fixture.id}`);
  assert.ok(result.data && result.data.id === fixture.id, 'Detail did not match fixture');
  return result.data;
}

async function verifyPagination(group, filter, expected) {
  const first = await list(group, filter, 1, 1);
  assert.equal(first.total, expected, 'Filtered total incorrect');
  assert.equal(first.rows.length, Math.min(expected, 1), 'Page size ignored');
  const next = await list(group, filter, 2, 1);
  assert.equal(next.total, expected, 'Total changed between pages');
  assert.equal(next.rows.length, expected > 1 ? 1 : 0, 'Page offset ignored');
  if (next.rows.length) assert.notEqual(first.rows[0].id, next.rows[0].id, 'Repeated page');
}

async function remove(fixture) {
  // Require ownership immediately before deletion; never delete by a broad name search.
  const current = await detail(fixture);
  assert.ok(fixture.identity(current), 'Fixture ownership lost');
  await ok(`/webui/${fixture.group}/${fixture.id}`, { method: 'DELETE' });
  fixture.removed = true;
  const after = await ok(`/webui/${fixture.group}/${fixture.id}`);
  assert.ok(after.data == null, 'Deleted fixture still exists');
}

try {
  await check('admin login with this request captcha from local Redis', async () => {
    const captcha = await ok('/captchaImage', { anonymous: true });
    let code = '';
    if (captcha.captchaEnabled !== false) {
      assert.ok(typeof captcha.uuid === 'string' && /^[a-zA-Z0-9-]+$/.test(captcha.uuid), 'Invalid captcha ID');
      const cli = process.env.REDIS_CLI || [
        '/opt/homebrew/opt/redis/bin/redis-cli', '/usr/local/opt/redis/bin/redis-cli'
      ].find(existsSync) || 'redis-cli';
      const { stdout } = await execFileAsync(cli, [
        '--raw', '-h', process.env.REDIS_HOST || '127.0.0.1',
        '-p', process.env.REDIS_PORT || '6379', '-n', process.env.REDIS_DB || '0',
        'GET', `captcha_codes:${captcha.uuid}`
      ], { timeout: 10000, maxBuffer: 8192 });
      const cached = stdout.trim();
      try { code = String(JSON.parse(cached)); } catch { code = cached; }
      assert.ok(code.length > 0 && code.length <= 20, 'Captcha cache missing');
    }
    const login = await ok('/login', {
      method: 'POST', anonymous: true,
      body: { username: 'admin', password: 'admin123', uuid: captcha.uuid, code }
    });
    assert.ok(typeof login.token === 'string' && login.token.length > 20, 'Missing login token');
    token = login.token;
  });

  await check('anonymous management endpoints require authentication', async () => {
    for (const group of ['service', 'model', 'search', 'config']) {
      for (const path of [`/webui/${group}/list`, `/webui/${group}/1`]) {
        const result = await request(path, { anonymous: true });
        assert.ok([401, 403].includes(result.code), 'Anonymous management request allowed');
      }
    }
  });

  // Run before fixture config creation: no existing user configuration is changed.
  await check('webui auth success and validation failures', async () => {
    const success = await ok('/webui/auth/in', {
      method: 'POST', anonymous: true, body: { username: 'admin', password: 'admin123' }
    });
    assert.ok(success.data && typeof success.data === 'object', 'Missing auth payload');
    const account = (await ok('/getInfo')).user;
    const configured = (await list('config', { userId: account.userId })).rows;
    if (configured.length) {
      assert.equal(success.data.userId, account.userId, 'Auth user mapping missing');
      assert.equal(success.data.username, account.userName, 'Auth username mapping missing');
      assert.ok(configured.some(row =>
        row.aiServiceId === success.data.aiService?.aiServiceId &&
        row.aiServiceKey === success.data.aiService?.aiServiceKey &&
        row.modelName === success.data.aiService?.modelName &&
        row.serviceName === success.data.aiService?.serviceName &&
        row.searchServiceId === success.data.searchService?.searchServiceId &&
        row.searchServiceKey === success.data.searchService?.searchServiceKey &&
        row.searchServiceName === success.data.searchService?.searchServiceName
      ), 'Auth joined configuration mapping missing');
    }
    for (const body of [
      { username: '', password: '' }, { username: 'admin', password: '1234' },
      { username: 'a', password: 'admin123' },
      { username: 'admin', password: 'wrong_local_password' }
    ]) {
      const rejected = await request('/webui/auth/in', { method: 'POST', anonymous: true, body });
      assert.equal(rejected.code, 400, 'Invalid credentials accepted');
    }
    // Reset any local failed-password counter by authenticating correctly again.
    await ok('/webui/auth/in', {
      method: 'POST', anonymous: true, body: { username: 'admin', password: 'admin123' }
    });
  });

  const profile = await ok('/getInfo');
  assert.ok(Number.isSafeInteger(profile.user?.userId), 'Missing admin identity');
  const userId = profile.user.userId;
  const serviceUrl = `https://example.invalid/${prefix}`;
  let service, otherService, search, model, otherModel, config;
  await check('AI service create detail filter pagination update', async () => {
    const identity = row => row.serviceName?.startsWith(prefix) && row.serviceUrl === serviceUrl;
    service = await create('service', { serviceName: `${prefix}_a`, serviceUrl }, { serviceUrl }, identity);
    otherService = await create('service', { serviceName: `${prefix}_b`, serviceUrl: `${serviceUrl}/other` },
      { serviceUrl: `${serviceUrl}/other` }, row => row.serviceName?.startsWith(prefix) && row.serviceUrl === `${serviceUrl}/other`);
    await detail(service);
    await verifyPagination('service', { serviceName: prefix }, 2);
    await ok('/webui/service', { method: 'PUT', body: { id: service.id, serviceName: `${prefix}_updated` } });
    assert.equal((await detail(service)).serviceName, `${prefix}_updated`, 'Update ignored');
  });

  await check('search service create detail filters pagination update', async () => {
    search = await create('search', {
      serviceName: `${prefix}_search`, serviceUrl, searchEngineCx: `${prefix}_cx`
    }, { serviceUrl }, row => row.serviceName?.startsWith(prefix) && row.serviceUrl === serviceUrl);
    assert.equal((await detail(search)).searchEngineCx, `${prefix}_cx`, 'Search cx lost');
    await verifyPagination('search', { serviceName: prefix, serviceUrl, searchEngineCx: `${prefix}_cx` }, 1);
    await ok('/webui/search', { method: 'PUT', body: { id: search.id, searchEngineCx: `${prefix}_new` } });
    assert.equal((await detail(search)).searchEngineCx, `${prefix}_new`, 'Search cx update ignored');
  });

  await check('AI model create detail filters pagination join update', async () => {
    model = await create('model', { modelName: `${prefix}_model`, aiServiceId: service.id },
      { modelName: `${prefix}_model`, aiServiceId: service.id }, row => row.modelName?.startsWith(prefix) && row.aiServiceId === service.id);
    otherModel = await create('model', { modelName: `${prefix}_other`, aiServiceId: otherService.id },
      { modelName: `${prefix}_other`, aiServiceId: otherService.id }, row => row.modelName?.startsWith(prefix) && row.aiServiceId === otherService.id);
    await detail(model);
    await verifyPagination('model', { modelName: prefix }, 2);
    await verifyPagination('model', { aiServiceId: service.id }, 1);
    const joined = (await list('model', { aiServiceId: service.id })).rows[0];
    assert.equal(joined.serviceName, `${prefix}_updated`, 'Model service join missing');
    await ok('/webui/model', { method: 'PUT', body: { id: model.id, modelName: `${prefix}_model_new` } });
    assert.equal((await detail(model)).modelName, `${prefix}_model_new`, 'Model update ignored');
  });

  await check('service search model and user dropdowns including service filter', async () => {
    for (const [path, id] of [['/webui/service/select/list', service.id], ['/webui/search/select/list', search.id]]) {
      const result = await ok(path);
      assert.ok(Array.isArray(result.data) && result.data.some(row => row.id === id), 'Dropdown fixture missing');
    }
    for (const [selectedService, selectedModel, excludedModel] of [
      [service, model, otherModel], [otherService, otherModel, model]
    ]) {
      const result = await ok(`/webui/model/select/list/${selectedService.id}`);
      assert.ok(Array.isArray(result.data), 'Missing models');
      assert.ok(result.data.some(row => row.id === selectedModel.id), 'Selected model missing');
      assert.ok(result.data.every(row => row.aiServiceId === selectedService.id), 'Other service models included');
      assert.ok(!result.data.some(row => row.id === excludedModel.id), 'Other fixture model included');
    }
    const users = await ok('/system/user/select/list');
    assert.ok(Array.isArray(users.data) && users.data.some(row => row.userId === userId), 'User dropdown missing admin');
  });

  await check('user config create detail filters pagination joins update', async () => {
    // Only insert a fresh row linked to fixture services. Never update an existing
    // configuration, and do not call webui auth while the extra row exists.
    config = await create('config', {
      userId, aiServiceId: service.id, modelId: model.id, searchServiceId: search.id,
      aiServiceKey: `${prefix}_dummy_ai`, searchServiceKey: `${prefix}_dummy_search`
    }, { aiServiceId: service.id }, row => row.aiServiceId === service.id && row.userId === userId && row.aiServiceKey?.startsWith(prefix));
    await detail(config);
    await verifyPagination('config', {
      userId, aiServiceId: service.id, modelId: model.id, searchServiceId: search.id,
      aiServiceKey: `${prefix}_dummy_ai`, searchServiceKey: `${prefix}_dummy_search`
    }, 1);
    const joined = (await list('config', { aiServiceId: service.id })).rows[0];
    for (const [key, expected] of Object.entries({
      userName: profile.user.userName, serviceName: `${prefix}_updated`,
      modelName: `${prefix}_model_new`, searchServiceName: `${prefix}_search`
    })) assert.equal(joined[key], expected, 'Config join missing');
    await ok('/webui/config', { method: 'PUT', body: { id: config.id, searchServiceKey: `${prefix}_dummy_updated` } });
    assert.equal((await detail(config)).searchServiceKey, `${prefix}_dummy_updated`, 'Config update ignored');
  });

  await check('four Excel exports produce XLSX ZIP payloads', async () => {
    for (const group of ['service', 'model', 'search', 'config']) {
      // Filter each export to this run's fixture data; keep response bytes in memory.
      const filter = group === 'config' ? { aiServiceId: service.id }
        : group === 'model' ? { modelName: prefix } : { serviceName: prefix };
      const response = await request(`/webui/${group}/export?${query(filter)}`, { method: 'POST', raw: true });
      assert.equal(response.status, 200, 'Export HTTP error');
      const bytes = new Uint8Array(await response.arrayBuffer());
      assert.ok(bytes.length > 100 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 3 && bytes[3] === 4, 'Export was not XLSX ZIP');
    }
  });

  await check('four resource delete endpoints remove owned fixtures', async () => {
    for (const fixture of [config, model, otherModel, search, service, otherService]) await remove(fixture);
  });
} catch {
  failed = true;
} finally {
  // Reverse dependency order, and attempt every cleanup even if one fails.
  let clean = true;
  for (const fixture of [...cleanup].reverse()) {
    if (fixture.removed) continue;
    try {
      if (!fixture.id) {
        const candidates = (await list(fixture.group, fixture.filter)).rows.filter(fixture.identity);
        assert.ok(candidates.length <= 1, 'Ambiguous cleanup ownership');
        if (!candidates.length) continue;
        fixture.id = candidates[0].id;
      }
      await remove(fixture);
    } catch { clean = false; }
  }
  console.log(`${clean ? 'PASS' : 'FAIL'} cleanup only this run fixtures`);
  if (!clean) failed = true;
  // Release this run's management login without deleting unrelated Redis state.
  if (token) {
    try { await ok('/logout', { method: 'POST' }); } catch { failed = true; console.error('FAIL logout'); }
  }
  process.exitCode = failed ? 1 : 0;
}
