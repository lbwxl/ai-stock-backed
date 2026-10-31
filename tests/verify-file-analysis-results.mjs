// Local API + MySQL + MinIO integration. Only uniquely named fixtures are created/removed.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { readMinioConfig } from './fixtures/minio-config.mjs'
import { digitImage } from './fixtures/digit-image.mjs'
assert.ok(process.argv.includes('--live'), 'Pass --live to create disposable local fixtures')
assert.ok(process.env.MYSQL_PWD, 'Set MYSQL_PWD to the local application database password')
const base = process.env.BASE_URL || 'http://127.0.0.1:18080'
const config = readMinioConfig(fileURLToPath(new URL('..', import.meta.url)))
const marker = 'result_' + randomBytes(5).toString('hex'), tokens = [], files = []
let admin, adminId, owner, userId, taskId
const sql = statement => execFileSync('/opt/homebrew/opt/mysql@8.4/bin/mysql', ['--protocol=TCP', '-h127.0.0.1', '-P3306', '-u' + (process.env.TEST_DB_USER || 'ruoyi_local'), '--batch', '--skip-column-names', 'ry-vue', '-e', statement], { encoding: 'utf8' }).trim()
async function call(path, token, init = {}) {
  const response = await fetch(base + path, { ...init, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...init.headers }, signal: AbortSignal.timeout(120000) })
  return response
}
async function json(path, token, init) {
  const response = await call(path, token, init), body = await response.json()
  assert.equal(response.status, 200, path + ': HTTP failure'); assert.equal(body.code, 200, path + ': ' + (body.msg || body.message)); return body
}
const put = body => ({ method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
async function login(username, password) {
  const captcha = await json('/captchaImage'); let code = ''
  if (captcha.captchaEnabled !== false) {
    const raw = execFileSync('/opt/homebrew/opt/redis/bin/redis-cli', ['--raw', 'GET', 'captcha_codes:' + captcha.uuid], { encoding: 'utf8' }).trim()
    try { code = String(JSON.parse(raw)) } catch { code = raw }
  }
  const data = await json('/login', null, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password, code, uuid: captcha.uuid }) })
  tokens.push(data.token); return data.token
}
function removeObject(file) {
  const path = '/' + config.bucket + '/' + config.prefix.replace(/\/+$/, '') + '/' + adminId + '/' + file.id
  const credential = 'user = ' + JSON.stringify(config.accessKey + ':' + config.secretKey) + '\n'
  const args = ['-sS', '--noproxy', '*', '--max-time', '20', '--aws-sigv4', 'aws:amz:' + config.region + ':s3', '--config', '-']
  const head = execFileSync('/usr/bin/curl', [...args, '--head', config.endpoint + path], { input: credential, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] })
  const version = /^x-amz-version-id:\s*(\S+)/im.exec(head)?.[1]
  execFileSync('/usr/bin/curl', [...args, '-X', 'DELETE', '--fail', config.endpoint + path + (version ? '?versionId=' + encodeURIComponent(version) : '')], { input: credential, stdio: ['pipe', 'pipe', 'pipe'] })
}
try {
  admin = await login(process.env.TEST_USERNAME || 'admin', process.env.TEST_PASSWORD || 'admin123')
  adminId = (await json('/getInfo', admin)).user.userId
  const password = 'aA9!' + randomBytes(16).toString('hex')
  await json('/system/user', admin, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userName: marker, nickName: marker, password, deptId: 100, status: '0', roleIds: [], postIds: [] }) })
  const users = (await json('/system/user/list?userName=' + marker, admin)).rows.filter(user => user.userName === marker)
  assert.equal(users.length, 1); userId = users[0].userId; owner = await login(marker, password)
  // Insert only a disposable manual task, so the real model is never called.
  taskId = Number(sql(`INSERT INTO file_analysis_task(user_id,file_id,file_name,mime_type,file_size,file_url,processing_requirement,analysis_requirement,status,processing_mode,request_id,version,create_time,update_time,create_by) VALUES(${userId},'${randomUUID()}','${marker}.pdf','application/pdf',1,'/fixture','integration fixture','integration fixture','PROCESSING','MANUAL','${randomUUID()}',0,NOW(),NOW(),'${marker}'); SELECT LAST_INSERT_ID();`))
  assert.ok(taskId > 0)
  const document = Buffer.from(execFileSync('python3', ['-c', 'import io,zipfile,base64; b=io.BytesIO(); z=zipfile.ZipFile(b,"w"); z.writestr("[Content_Types].xml",\'<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>\'); z.writestr("_rels/.rels",\'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>\'); z.writestr("word/document.xml",\'<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>测试报告</w:t></w:r></w:p></w:body></w:document>\'); z.close(); print(base64.b64encode(b.getvalue()).decode())'], { encoding: 'utf8' }).trim(), 'base64')
  const samples = [{ name: '自然资源巡查影像分析报告.docx', type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', bytes: document }, { name: '自然资源巡查问题标注图.png', type: 'image/png', bytes: digitImage('123456') }]
  const form = new FormData(); samples.forEach(sample => form.append('files', new Blob([sample.bytes], { type: sample.type }), sample.name))
  const forbidden = await call(`/webui/file-analysis/records/${taskId}/result-files`, owner, { method: 'POST', body: form })
  assert.equal((await forbidden.json()).code, 403)
  files.push(...(await json(`/webui/file-analysis/records/${taskId}/result-files`, admin, { method: 'POST', body: form })).data)
  assert.equal(files.length, 2); assert.equal(files[0].fileName, samples[0].name)
  assert.equal((await json('/webui/file-analysis/tasks/' + taskId, owner)).data.resultFiles.length, 0)
  const downloadPath = file => `/webui/file-analysis/tasks/${taskId}/result-files/${file.id}/download`
  assert.equal((await call(downloadPath(files[0]), owner)).status, 404)
  const draft = { id: taskId, version: 0, resultFileIds: files.map(file => file.id) }
  await json('/webui/file-analysis/records', admin, put(draft))
  const detail = (await json('/webui/file-analysis/tasks/' + taskId, owner)).data
  assert.equal(detail.status, 'SUCCEEDED'); assert.equal(detail.processingMode, 'MANUAL'); assert.equal(detail.resultFiles.length, 2); assert.equal(detail.fileAvailable, false)
  assert.equal((await call('/webui/file-analysis/records', admin, put(draft))).status, 409)
  for (const [index, file] of files.entries()) {
    const downloaded = await call(downloadPath(file), owner)
    assert.equal(downloaded.status, 200); assert.equal(downloaded.headers.get('content-type'), 'application/octet-stream')
    assert.equal(decodeURIComponent(downloaded.headers.get('download-filename')), samples[index].name)
    assert.equal(downloaded.headers.get('x-content-type-options'), 'nosniff'); assert.equal(downloaded.headers.get('cache-control'), 'private, no-store')
    assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), samples[index].bytes)
    assert.equal((await call(downloadPath(file), admin)).status, 404)
    const adminDownload = await call(`/webui/file-analysis/records/${taskId}/result-files/${file.id}/download`, admin)
    assert.equal(adminDownload.status, 200); await adminDownload.arrayBuffer()
  }
  const anonymous = await call(downloadPath(files[0])); assert.equal((await anonymous.json()).code, 401)
  await json('/webui/file-analysis/records', admin, put({ id: taskId, version: detail.version, resultFileIds: [files[1].id] }))
  assert.equal((await call(downloadPath(files[0]), owner)).status, 404)
  assert.equal((await json('/webui/file-analysis/tasks/' + taskId, owner)).data.resultFiles.length, 1)
  console.log('PASS: mixed Word/PNG upload, draft privacy, publish, original bytes/names, owner isolation, real permissions, version conflict and removal.')
} finally {
  const cleanupErrors = []
  for (const file of files) { try { removeObject(file) } catch { cleanupErrors.push('uploaded object cleanup failed') } }
  if (taskId) try { sql(`DELETE FROM file_analysis_result_file WHERE task_id=${taskId}; DELETE FROM file_analysis_task WHERE id=${taskId} AND user_id=${Number(userId)};`) } catch { cleanupErrors.push('task cleanup failed') }
  if (userId) try { sql(`DELETE FROM sys_user_post WHERE user_id=${userId}; DELETE FROM sys_user_role WHERE user_id=${userId}; DELETE FROM sys_user WHERE user_id=${userId} AND user_name='${marker}';`) } catch { cleanupErrors.push('user cleanup failed') }
  for (const token of tokens) await call('/logout', token, { method: 'POST' }).catch(() => {})
  assert.equal(cleanupErrors.length, 0, cleanupErrors.join('; '))
}
