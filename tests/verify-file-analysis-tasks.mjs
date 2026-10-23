// End-to-end checks against the local backend + real MinIO. AI behavior is an isolated HTTP fixture.
// Creates uniquely named users/configuration/records, then removes only those fixtures.
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { writeFileSync } from 'node:fs'
import { readMinioConfig } from './fixtures/minio-config.mjs'
import { digitImage } from './fixtures/digit-image.mjs'
assert.ok(process.argv.includes('--live'), 'Pass --live to create disposable local fixtures and MinIO files')
const project = fileURLToPath(new URL('..', import.meta.url)), base = 'http://127.0.0.1:18080'
const config = readMinioConfig(project), marker = 'task_' + randomBytes(5).toString('hex')
const tokens = [], uploaded = [], records = [], resources = [], requests = []
let admin, user, userId, failure, raceStartedResolve, releaseRace
const raceStarted = new Promise(resolve => { raceStartedResolve = resolve })
const raceGate = new Promise(resolve => { releaseRace = resolve })
const result = { testOnly: true, success: false }
const fixture = createServer(async (request, response) => {
  try {
    assert.equal(request.headers.authorization, 'Bearer fixture-task-key')
    let raw = ''; for await (const part of request) raw += part
    const body = JSON.parse(raw), text = JSON.stringify(body)
    const kind = text.includes('RACE') ? 'RACE' : text.includes('MANUAL') ? 'MANUAL' : text.includes('FAILURE') ? 'FAILURE' : 'SUCCESS'
    requests.push(kind)
    let status = 200, message
    if (kind === 'FAILURE') { status = 401; message = {} }
    else if (kind === 'MANUAL') {
      if (Array.isArray(body.messages.at(-1).content)) { status = 400; message = {} }
      else message = { role: 'assistant', content: null, tool_calls: [{ id: 'task-manual-call', type: 'function', function: { name: 'analyze_uploaded_file', arguments: JSON.stringify({ task: '请人工处理当前文件' }) } }] }
    } else {
      const attachment = body.messages.at(-1).content.find(part => part.type === 'image_url')
      assert.ok(attachment)
      const url = new URL(attachment.image_url.url)
      assert.equal(url.origin, new URL(config.publicEndpoint || config.endpoint).origin)
      const source = uploaded.find(file => new URL(file.fileUrl).pathname === url.pathname)
      assert.ok(source)
      const download = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(10000) })
      assert.equal(download.status, 200); assert.deepEqual(Buffer.from(await download.arrayBuffer()), source.bytes)
      if (kind === 'RACE') { raceStartedResolve(); await raceGate }
      message = { role: 'assistant', content: '隔离测试自动分析结果 ' + kind }
    }
    response.writeHead(status, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify(status === 200 ? { choices: [{ message }] } : {}))
  } catch { failure = true; response.writeHead(500, { 'Content-Type': 'application/json' }); response.end('{}') }
})
await new Promise(resolve => fixture.listen(0, '127.0.0.1', resolve))
async function call(path, { token, method = 'GET', body, form } = {}) {
  const response = await fetch(base + path, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: form || (body ? JSON.stringify(body) : undefined), signal: AbortSignal.timeout(30000) })
  return { status: response.status, data: await response.json().catch(() => null) }
}
async function ok(path, options) { const response = await call(path, options); assert.equal(response.status, 200, 'Backend HTTP request failed at ' + path); assert.equal(response.data.code, 200, 'Business request failed at ' + path); return response.data }
async function login(username, password) {
  const captcha = await ok('/captchaImage'); let code = ''
  if (captcha.captchaEnabled !== false) {
    const raw = execFileSync('/opt/homebrew/opt/redis/bin/redis-cli', ['--raw', 'GET', 'captcha_codes:' + captcha.uuid], { encoding: 'utf8' }).trim()
    try { code = String(JSON.parse(raw)) } catch { code = raw }
  }
  const response = await ok('/login', { method: 'POST', body: { username, password, code, uuid: captcha.uuid } })
  tokens.push(response.token); return response.token
}
async function resource(group, body, filter) {
  await ok('/webui/' + group, { token: admin, method: 'POST', body })
  const rows = (await ok('/webui/' + group + '/list?' + new URLSearchParams(filter), { token: admin })).rows
  assert.equal(rows.length, 1); resources.push({ group, id: rows[0].id }); return rows[0].id
}
async function upload(kind) {
  const bytes = kind === 'MANUAL' ? Buffer.from('%PDF-1.4\nFile task test fixture\n%%EOF') : digitImage('123456')
  const form = new FormData(); form.append('file', new Blob([bytes], { type: kind === 'MANUAL' ? 'application/pdf' : 'image/png' }), marker + '_文件 ' + kind + (kind === 'MANUAL' ? '.pdf' : '.png'))
  const response = await call('/webui/file-analysis/files', { token: user, method: 'POST', form })
  assert.equal(response.status, 200); const file = { ...response.data, bytes }; uploaded.push(file); return file
}
async function create(kind) {
  const file = await upload(kind)
  const task = (await ok('/webui/file-analysis/tasks', { token: user, method: 'POST', body: { fileUrl: file.fileUrl, processingRequirement: marker + ' 保持原文件', analysisRequirement: kind + ' 分析要求' } })).data
  records.push(task.id); assert.equal(task.status, 'NOT_STARTED'); assert.equal(task.userId, userId)
  return task
}
async function waitFor(id, predicate) {
  const limit = Date.now() + 45000
  while (Date.now() < limit) {
    const task = (await ok('/webui/file-analysis/tasks/' + id, { token: user })).data
    if (predicate(task)) return task
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  throw new Error('Task status did not reach the expected state')
}
function update(task, changes) {
  return { id: task.id, version: task.version, analysisText: task.analysisText || '', ...changes }
}
async function verifyAdminDownload(task, file) {
  const response = await fetch(base + '/webui/file-analysis/records/' + task.id + '/download', {
    headers: { Authorization: 'Bearer ' + admin }, signal: AbortSignal.timeout(30000)
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'application/octet-stream')
  assert.match(response.headers.get('content-disposition'), /^attachment;/)
  assert.equal(decodeURIComponent(response.headers.get('download-filename')), file.fileName)
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff')
  assert.ok(response.headers.get('x-request-id'))
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), file.bytes)
}
function sql(statement) {
  return execFileSync('/opt/homebrew/opt/mysql@8.4/bin/mysql', ['--no-defaults', '--socket=' + resolve(project, '.local/mysql.sock'), '-u', 'root', '--batch', '--skip-column-names', 'ry-vue', '-e', statement], { encoding: 'utf8' })
}
function removeObject(file) {
  const path = new URL(file.fileUrl).pathname
  const credential = 'user = ' + JSON.stringify(config.accessKey + ':' + config.secretKey) + '\n'
  const args = ['-sS', '--noproxy', '*', '--max-time', '15', '--aws-sigv4', 'aws:amz:' + config.region + ':s3', '--config', '-']
  const head = execFileSync('/usr/bin/curl', [...args, '--head', config.endpoint + path], { input: credential, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] })
  const version = /^x-amz-version-id:\s*(\S+)/im.exec(head)?.[1]
  execFileSync('/usr/bin/curl', [...args, '-X', 'DELETE', '--fail', config.endpoint + path + (version ? '?versionId=' + encodeURIComponent(version) : '')], { input: credential, stdio: ['pipe', 'pipe', 'pipe'] })
}
try {
  admin = await login(process.env.TEST_USERNAME || 'admin', process.env.TEST_PASSWORD || 'admin123')
  const password = randomBytes(8).toString('hex')
  await ok('/system/user', { token: admin, method: 'POST', body: { userName: marker, nickName: marker, password, status: '0', roleIds: [], postIds: [] } })
  const users = (await ok('/system/user/list?userName=' + marker, { token: admin })).rows.filter(row => row.userName === marker)
  assert.equal(users.length, 1); userId = users[0].userId; user = await login(marker, password)
  const serviceId = await resource('service', { serviceName: marker, serviceUrl: 'http://127.0.0.1:' + fixture.address().port + '/v1' }, { serviceName: marker })
  const modelId = await resource('model', { modelName: marker, aiServiceId: serviceId }, { modelName: marker })
  await resource('config', { userId, aiServiceId: serviceId, modelId, aiServiceKey: 'fixture-task-key' }, { userId })
  const success = await create('SUCCESS'); await verifyAdminDownload(success, uploaded[0])
  const completed = await waitFor(success.id, task => task.status === 'SUCCEEDED')
  assert.equal(completed.analysisText, '隔离测试自动分析结果 SUCCESS'); result.automaticSuccess = true
  const reviewed = (await ok('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(success, { analysisText: '核对自动分析结果' }) })).data
  assert.equal(reviewed.analysisText, '核对自动分析结果'); assert.equal(reviewed.processingMode, 'MANUAL')
  const linked = uploaded[0]
  assert.equal((await call('/webui/file-analysis/files/' + linked.fileId, { token: user, method: 'DELETE' })).status, 409)
  result.retainedFileProtected = true
  const failed = await create('FAILURE'); const failedResult = await waitFor(failed.id, task => task.status === 'FAILED')
  assert.equal(failedResult.errorCode, 'MODEL_AUTH_FAILED'); result.failurePersisted = true
  await verifyAdminDownload(failedResult, uploaded[1])
  const manual = await create('MANUAL'); const waiting = await waitFor(manual.id, task => task.status === 'PROCESSING' && task.processingMode === 'MANUAL')
  assert.equal(waiting.analysisText, null)
  assert.equal(requests.filter(kind => kind === 'MANUAL').length, 2); result.modelRequestedManualMaintenance = true
  await verifyAdminDownload(waiting, uploaded[2])
  const maintained = (await ok('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(manual, { analysisText: '后台人工维护结果' }) })).data
  assert.equal(maintained.status, 'SUCCEEDED'); assert.equal(maintained.processingMode, 'MANUAL')
  const visible = (await ok('/webui/file-analysis/tasks/' + manual.id, { token: user })).data
  assert.equal(visible.analysisText, '后台人工维护结果'); assert.equal(visible.analysisRequirement, waiting.analysisRequirement); assert.equal(visible.processingRequirement, waiting.processingRequirement); assert.equal(visible.fileId, waiting.fileId); result.manualResultVisibleToOwner = true
  assert.equal((await call('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(waiting, { analysisText: '过期版本' }) })).status, 409)
  const competing = await Promise.all(['维护人员甲', '维护人员乙'].map(analysisText => call('/webui/file-analysis/records', {
    token: admin, method: 'PUT', body: update(maintained, { analysisText })
  })))
  assert.equal(competing.filter(response => response.status === 200).length, 1)
  assert.equal(competing.filter(response => response.status === 409 && response.data.code === 'TASK_VERSION_CONFLICT').length, 1)
  const winningResult = competing.find(response => response.status === 200).data.data.analysisText
  assert.equal((await ok('/webui/file-analysis/tasks/' + manual.id, { token: user })).data.analysisText, winningResult)
  result.concurrentHumanEditsProtected = true
  const race = await create('RACE')
  await Promise.race([raceStarted, new Promise((_, reject) => setTimeout(() => reject(new Error('Race model fixture was not reached')), 20000))])
  const current = (await ok('/webui/file-analysis/records/' + race.id, { token: admin })).data
  await ok('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(race, { analysisText: '人工结果优先' }) })
  releaseRace(); await new Promise(resolve => setTimeout(resolve, 1200))
  assert.equal((await ok('/webui/file-analysis/tasks/' + race.id, { token: user })).data.analysisText, '人工结果优先'); result.manualUpdateWinsRace = true
  result.automaticStatusChangesDoNotBlockMaintenance = true
  const latest = (await ok('/webui/file-analysis/records/' + race.id, { token: admin })).data
  for (const field of ['fileUrl', 'processingRequirement', 'analysisRequirement', 'status', 'errorMessage', 'userId']) {
    const denied = await call('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(latest, { [field]: '禁止修改' }) })
    assert.equal(denied.status, 400); assert.equal(denied.data.code, 'INVALID_REQUEST')
  }
  const unchanged = (await ok('/webui/file-analysis/records/' + race.id, { token: admin })).data
  assert.equal(unchanged.version, latest.version); assert.equal(unchanged.fileId, race.fileId)
  assert.equal(unchanged.processingRequirement, race.processingRequirement); assert.equal(unchanged.analysisRequirement, race.analysisRequirement)
  assert.equal(unchanged.analysisText, '人工结果优先'); result.onlyAnalysisResultCanBeMaintained = true
  const form = new FormData(); form.append('file', new Blob(['replacement'], { type: 'text/plain' }), 'replacement.txt')
  const removed = await call('/webui/file-analysis/records/' + race.id + '/file', { token: admin, method: 'POST', form })
  assert.equal(removed.status, 404); result.replacementEndpointRemoved = true
  const corrected = (await ok('/webui/file-analysis/records', { token: admin, method: 'PUT', body: update(failedResult, { analysisText: '人工补充失败任务结果' }) })).data
  assert.equal(corrected.status, 'SUCCEEDED'); assert.equal(corrected.errorMessage, null); assert.equal(corrected.errorCode, null)
  await verifyAdminDownload(unchanged, uploaded.at(-1)); result.adminOriginalFileDownload = true
  assert.equal((await call('/webui/file-analysis/records/' + success.id + '/download', { token: user })).data.code, 403)
  assert.equal((await call('/webui/file-analysis/records/' + success.id + '/download')).data.code, 401)
  assert.equal((await call('/webui/file-analysis/records/9007199254740991/download', { token: admin })).status, 404)
  result.adminDownloadPermissionEnforced = true
  assert.equal((await call('/webui/file-analysis/records/list', { token: user })).data.code, 403)
  assert.equal((await call('/webui/file-analysis/tasks/' + success.id, { token: admin })).status, 404)
  const own = await ok('/webui/file-analysis/tasks?userId=1&pageNum=1&pageSize=10', { token: user })
  assert.equal(own.total, 4); assert.ok(own.rows.every(task => task.userId === userId)); result.ownerIsolation = true
  const adminList = await ok('/webui/file-analysis/records/list?userId=' + userId, { token: admin }); assert.equal(adminList.total, 4)
  assert.equal(failure, undefined); result.success = true
  console.log('PASS result-only maintenance / immutable task inputs / automatic success / concurrent update protection / original-file downloads and permissions')
} finally {
  releaseRace()
  if (records.length && Number.isSafeInteger(userId)) sql('DELETE FROM file_analysis_task WHERE user_id=' + userId + ' AND id IN (' + records.map(Number).join(',') + ')')
  for (const file of uploaded) { try { removeObject(file) } catch { result.cleanupFailed = true } }
  for (const resource of resources.reverse()) { try { await ok('/webui/' + resource.group + '/' + resource.id, { token: admin, method: 'DELETE' }) } catch { result.cleanupFailed = true } }
  if (userId && admin) { try { await ok('/system/user/' + userId, { token: admin, method: 'DELETE' }) } catch { result.cleanupFailed = true } }
  for (const token of tokens.reverse()) await call('/logout', { token, method: 'POST' }).catch(() => {})
  await new Promise(resolve => fixture.close(resolve))
  writeFileSync(resolve(project, '.local/file-analysis-task-test-result.json'), JSON.stringify(result), { mode: 0o600 })
  assert.notEqual(result.cleanupFailed, true, 'Fixture cleanup failed')
}
