// Checks the running local backend and its actual MinIO object. Creates and deletes only its own upload/session.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'
import { readMinioConfig } from './fixtures/minio-config.mjs'
import { digitImage } from './fixtures/digit-image.mjs'
import { randomInt } from 'node:crypto'

assert.ok(process.argv.includes('--live'), 'Pass --live for real upload/download/delete checks')
const project = fileURLToPath(new URL('..', import.meta.url))
const config = readMinioConfig(project)
const base = 'http://127.0.0.1:18080'
let token, fileUrl, privatePath
const report = { success: false }
async function call(path, options = {}) {
  return fetch(base + path, { ...options, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers }, signal: AbortSignal.timeout(30000) })
}
function s3Head(path) {
  // Curl receives credentials on stdin, never through command-line arguments or logs.
  const auth = 'user = ' + JSON.stringify(config.accessKey + ':' + config.secretKey) + '\n'
  const raw = execFileSync(process.env.CURL || '/usr/bin/curl', ['--silent', '--show-error', '--noproxy', '*',
    '--connect-timeout', '10', '--max-time', '20', '--head', '--output', '/dev/null', '--write-out', '%{http_code}',
    '--aws-sigv4', `aws:amz:${config.region || 'us-east-1'}:s3`, '--config', '-', config.endpoint.replace(/\/$/, '') + path],
  { input: auth, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] })
  return Number(raw)
}
try {
  const captcha = await (await call('/captchaImage')).json()
  let code = ''
  if (captcha.captchaEnabled !== false) {
    assert.match(captcha.uuid, /^[a-zA-Z0-9-]+$/)
    const cached = execFileSync(process.env.REDIS_CLI || '/opt/homebrew/opt/redis/bin/redis-cli', ['--raw', '-h', '127.0.0.1', '-p', '6379', 'GET', 'captcha_codes:' + captcha.uuid], { encoding: 'utf8' }).trim()
    try { code = String(JSON.parse(cached)) } catch { code = cached }
  }
  const login = await (await call('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.TEST_USERNAME || 'admin', password: process.env.TEST_PASSWORD || 'admin123', uuid: captcha.uuid, code }) })).json()
  assert.equal(login.code, 200, 'Test login failed'); token = login.token
  const identity = await (await call('/getInfo')).json()
  const verification = String(randomInt(100000, 1000000))
  const modelCheck = process.argv.includes('--model')
  const bytes = modelCheck ? digitImage(verification) : Buffer.from('MinIO backend upload verification ' + randomBytes(16).toString('hex'))
  const form = new FormData(); form.append('file', new Blob([bytes], { type: modelCheck ? 'image/png' : 'text/plain' }), modelCheck ? 'minio-runtime-check.png' : 'minio-runtime-check.txt')
  const response = await call('/webui/file-analysis/files', { method: 'POST', body: form })
  assert.equal(response.status, 200, 'Backend upload failed')
  const uploaded = await response.json(); fileUrl = uploaded.fileUrl
  const external = new URL(fileUrl)
  assert.equal(external.origin, new URL(config.publicEndpoint || config.endpoint).origin)
  assert.ok(external.searchParams.has('X-Amz-Signature'))
  assert.match(uploaded.fileId, /^[0-9a-f-]{36}$/)
  privatePath = '/webui/file-analysis/files/' + uploaded.fileId
  report.absoluteSignedUrlVerified = true
  assert.equal(uploaded.size, bytes.length)
  const objectPath = '/' + config.bucket + '/' + (config.prefix || 'file-analysis/').replace(/\/?$/, '/') + identity.user.userId + '/' + uploaded.fileId
  report.userId = identity.user.userId
  report.objectPath = objectPath
  assert.equal(s3Head(objectPath), 200, 'The actual MinIO object was not found')
  report.remoteObjectVerified = true
  const publicDownload = await fetch(fileUrl, { redirect: 'error', signal: AbortSignal.timeout(20000) })
  assert.equal(publicDownload.status, 200)
  assert.deepEqual(Buffer.from(await publicDownload.arrayBuffer()), bytes)
  report.signedUrlDirectDownloadVerified = true
  const downloaded = await call(privatePath); assert.equal(downloaded.status, 200)
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), bytes)
  report.privateDownloadBytesVerified = true
  if (modelCheck) {
    const response = await call('/webui/file-analysis', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileUrl, processingRequirement: '保持原文件，直接读取图片。', analysisRequirement: '只返回图片中显示的六位数字，基于真实读取结果回答。' }) })
    assert.equal(response.status, 200, 'Running backend model analysis failed')
    const analysis = await response.json()
    assert.ok(analysis.analysisText.includes(verification), 'Model did not read the actual image digits')
    report.modelReadImageVerified = true; report.analysisRequestId = analysis.requestId
  }
  assert.equal((await call(privatePath, { method: 'DELETE' })).status, 204)
  assert.equal(s3Head(objectPath), 404, 'MinIO object remains after backend deletion')
  assert.equal((await call(privatePath)).status, 404)
  fileUrl = undefined; report.remoteDeletionVerified = true; report.success = true
  console.log('PASS running backend upload -> actual MinIO object -> original download -> remote deletion')
} finally {
  if (fileUrl && privatePath && token) await call(privatePath, { method: 'DELETE' })
  if (token) await call('/logout', { method: 'POST' })
  writeFileSync(resolve(project, '.local/minio-runtime-result.json'), JSON.stringify(report), { mode: 0o600 })
}
