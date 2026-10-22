import test from 'node:test'
import assert from 'node:assert/strict'
import { buildResponsesRequest, readResponsesResult, createThirdPartyService } from './fixtures/third-party-file-service.mjs'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
const file = { fileUrl: 'https://files.example.test/signed/sample.pdf', fileName: 'sample.pdf', mimeType: 'application/pdf', processingRequirement: '保持原文件', analysisRequirement: '读取验证数字', task: '分析 PDF' }
const settings = { model: 'gpt-6-astra', reasoningEffort: 'high' }
const completed = { status: 'completed', output: [{ type: 'reasoning', summary: [] }, { type: 'message', content: [{ type: 'output_text', text: '第三方读取的结果' }] }] }

test('department request forwards both user requirements, model task and original file URL only', () => {
  const body = buildResponsesRequest(file, settings)
  assert.equal(body.model, 'gpt-6-astra'); assert.equal(body.store, false); assert.equal(body.stream, true)
  assert.equal(body.reasoning.effort, 'high')
  assert.deepEqual(body.input[0].content[1], { type: 'input_file', file_url: file.fileUrl })
  for (const value of [file.processingRequirement, file.analysisRequirement, file.task]) assert.ok(body.input[0].content[0].text.includes(value))
  assert.equal(JSON.stringify(body).includes('base64'), false)
  assert.equal(JSON.stringify(body).includes('Authorization'), false)
})
test('image uses Responses input_image URL', () => {
  assert.deepEqual(buildResponsesRequest({ ...file, mimeType: 'image/png' }, settings).input[0].content[1], { type: 'input_image', image_url: file.fileUrl, detail: 'auto' })
})
test('parses the completed Responses output, not reasoning items', async () => {
  assert.equal(await readResponsesResult(new Response(JSON.stringify(completed), { headers: { 'Content-Type': 'application/json' } })), '第三方读取的结果')
})
test('SSE parser waits for completed result and tolerates chunked lines', async () => {
  const text = 'event: response.output_text.delta\r\ndata: {"type":"response.output_text.delta","delta":"partial"}\r\n\r\ndata: ' + JSON.stringify({ type: 'response.completed', response: completed }) + '\n\n'
  const bytes = new TextEncoder().encode(text)
  const stream = new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i += 7) controller.enqueue(bytes.slice(i, i + 7)); controller.close() } })
  assert.equal(await readResponsesResult(new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })), '第三方读取的结果')
})
test('interrupted/incomplete/error responses never become successful analysis', async () => {
  await assert.rejects(readResponsesResult(new Response('data: {"type":"response.output_text.delta","delta":"partial"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })), /STREAM_INTERRUPTED/)
  await assert.rejects(readResponsesResult(new Response(JSON.stringify({ ...completed, status: 'incomplete' }), { headers: { 'Content-Type': 'application/json' } })), /INCOMPLETE/)
  await assert.rejects(readResponsesResult(new Response('secret provider response', { status: 401 })), /THIRD_PARTY_AUTH_FAILED/)
})

// HTTP contract simulations use fixed output. They do not claim a real model read a file.
async function withService(fetcher, run) {
  const directory = mkdtempSync(join(tmpdir(), 'file-service-simulation-'))
  const apiKeyFile = join(directory, 'key')
  writeFileSync(apiKeyFile, 'fixture-upstream-key', { mode: 0o600 })
  const events = []
  const server = createThirdPartyService({ settings: { ...settings, baseUrl: 'https://provider.example.test/v1', apiKeyFile }, fixtureKey: 'fixture-local-key', fetcher, record: event => events.push(event) })
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    await run(`http://127.0.0.1:${server.address().port}/analyze`, events)
  } finally {
    await new Promise(resolve => server.close(resolve))
    rmSync(directory, { recursive: true, force: true })
  }
}
const departmentRequest = { ...file, requestId: 'simulation-request' }
function post(body = departmentRequest, key = 'fixture-local-key') {
  return { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` }, body: JSON.stringify(body) }
}
test('HTTP department service returns upstream analysis with request ID and URL-only input', async () => {
  let calls = 0
  await withService(async (url, options) => {
    calls++
    assert.equal(url, 'https://provider.example.test/v1/responses')
    assert.equal(options.headers.Authorization, 'Bearer fixture-upstream-key')
    assert.deepEqual(JSON.parse(options.body), buildResponsesRequest(departmentRequest, settings))
    return new Response(JSON.stringify(completed), { headers: { 'Content-Type': 'application/json' } })
  }, async (url, events) => {
    const response = await fetch(url, post())
    assert.equal(response.status, 200)
    assert.deepEqual(await response.json(), { requestId: departmentRequest.requestId, analysisText: '第三方读取的结果', previewFile: null })
    assert.deepEqual(events.map(event => event.event), ['third_party_requested', 'third_party_completed'])
    assert.equal(JSON.stringify(events).includes(file.fileUrl), false)
  })
  assert.equal(calls, 1)
})
test('HTTP department service reports upstream unavailability without inventing analysis', async () => {
  const errorBody = JSON.stringify({ error: { type: 'api_error', message: `private provider error fixture-upstream-key ${file.fileUrl}` } })
  await withService(async () => new Response(errorBody, { status: 503 }), async (url, events) => {
    const response = await fetch(url, post())
    assert.equal(response.status, 502)
    const result = await response.json()
    assert.equal(result.code, 'THIRD_PARTY_HTTP_503')
    assert.equal(result.analysisText, undefined)
    assert.equal(JSON.stringify(result).includes('private provider error'), false)
    assert.equal(events.at(-1).code, 'THIRD_PARTY_HTTP_503')
    const diagnostic = events.find(event => event.event === 'third_party_error_detail')
    assert.equal(diagnostic.httpStatus, 503)
    assert.equal(diagnostic.message.includes('fixture-upstream-key'), false)
    assert.equal(diagnostic.message.includes(file.fileUrl), false)
  })
})
test('HTTP department service rejects unauthorized calls before contacting upstream', async () => {
  await withService(async () => { assert.fail('Upstream must not be called') }, async (url, events) => {
    const response = await fetch(url, post(departmentRequest, 'wrong-key'))
    assert.equal(response.status, 401)
    assert.deepEqual(events, [])
  })
})
