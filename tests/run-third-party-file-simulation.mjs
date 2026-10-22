// Explicit opt-in live simulation. Reads saved primary configuration without modifying it.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, chmodSync, rmSync } from 'node:fs'
import { execFileSync, spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
import net from 'node:net'
import { temporaryMinioConfig } from './fixtures/minio-config.mjs'

assert.ok(process.argv.includes('--live'), 'Pass --live to authorize external API calls for this test')
const project = resolve(new URL('..', import.meta.url).pathname)
const configPath = resolve(project, '.local/file-analysis-simulation.json')
const useMinio = process.argv.includes('--minio')
for (const name of ['file-analysis-simulation-result.json', 'file-analysis-simulation-trace.json']) {
  rmSync(resolve(project, '.local', name), { force: true })
}
console.log(useMinio ? 'Using MinIO signed file URLs; no temporary tunnel needed' : 'Waiting for the temporary file URL to become publicly reachable')
let tunnelReady = useMinio
for (let attempt = 0; !tunnelReady && attempt < 90; attempt++) {
  try {
    const state = JSON.parse(readFileSync(resolve(project, '.local/file-tunnel-status.json'), 'utf8'))
    if (state.state === 'ready') {
      const response = await fetch(state.url + '/__file_gateway_probe', { signal: AbortSignal.timeout(5000) })
      if (response.status === 404 && (await response.text()).includes('Error response')) { tunnelReady = true; break }
    }
  } catch {}
  await new Promise(resolve => setTimeout(resolve, 1000))
}
assert.ok(tunnelReady, 'Temporary file tunnel is not publicly reachable; no model calls were made')
const mysql = '/opt/homebrew/opt/mysql@8.4/bin/mysql'
const args = ['--no-defaults', '--socket=' + resolve(project, '.local/mysql.sock'), '-u', 'root', '--batch', '--raw', '--skip-column-names']
const databases = execFileSync(mysql, [...args, '-e', 'SHOW DATABASES'], { encoding: 'utf8' }).trim().split('\n').filter(x => !['information_schema', 'mysql', 'sys', 'performance_schema'].includes(x))
assert.equal(databases.length, 1, 'Expected one local application database')
const sql = "SELECT JSON_OBJECT('serviceName',s.service_name,'baseUrl',s.service_url,'apiKey',c.ai_service_key,'model',m.model_name) FROM user_webui_config c LEFT JOIN ai_service s ON s.id=c.ai_service_id LEFT JOIN ai_model m ON m.id=c.model_id WHERE c.user_id=1 ORDER BY c.id LIMIT 1"
const primary = JSON.parse(execFileSync(mysql, [...args, databases[0], '-e', sql], { encoding: 'utf8' }).trim())
const keyFile = resolve(project, '.local/file-analysis-third-party.key')
assert.ok(readFileSync(keyFile, 'utf8').trim(), 'Provide dedicated third-party key in ignored .local file')
const reservation = net.createServer()
await new Promise((resolve, reject) => reservation.once('error', reject).listen(0, '127.0.0.1', resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const config = {
  userId: 1, primary,
  storageType: useMinio ? 'minio' : 'local',
  minioConfigFile: '',
  storagePath: resolve(project, '.local/ai-files'), publicBaseUrlFile: resolve(project, '.local/file-tunnel-url'),
  thirdParty: { baseUrl: 'https://api1.aisz.mom/v1', model: 'gpt-6-astra', reasoningEffort: 'high', apiKeyFile: keyFile },
  fixture: { baseUrl: `http://127.0.0.1:${port}`, apiKey: randomBytes(32).toString('hex') },
  resultFile: resolve(project, '.local/file-analysis-simulation-result.json'),
  traceFile: resolve(project, '.local/file-analysis-simulation-trace.json'),
}
const minioConfig = useMinio ? temporaryMinioConfig(project) : null
if (minioConfig) config.minioConfigFile = minioConfig.path
writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 }); chmodSync(configPath, 0o600)
const env = { ...process.env, FILE_ANALYSIS_SIMULATION_CONFIG: configPath, FILE_ANALYSIS_SIMULATION_LIVE: 'true', JAVA_HOME: process.env.RUOYI_JAVA_HOME || '/Applications/PyCharm.app/Contents/jbr/Contents/Home' }
const fixture = spawn(process.execPath, [resolve(project, 'tests/mock-file-analysis-service.mjs')], { cwd: project, env, stdio: ['ignore', 'inherit', 'inherit'] })
try {
  let ready = false
  for (let i = 0; i < 40; i++) {
    await new Promise(resolve => setTimeout(resolve, 100))
    if (fixture.exitCode !== null) throw new Error('Local third-party fixture exited')
    try { ready = (await fetch(config.fixture.baseUrl + '/health')).ok; if (ready) break } catch {}
  }
  assert.ok(ready, 'Local third-party fixture not ready')
  const maven = process.env.RUOYI_MAVEN || resolve(process.env.HOME, '.local/share/aigupiao-tools/apache-maven-3.9.9/bin/mvn')
  const child = spawn(maven, ['-B', '-ntp', '-s', 'local-maven-settings.xml', '-pl', 'ruoyi-admin', '-am', '-Dtest=ThirdPartyFileAnalysisSimulationTest', '-Dsurefire.failIfNoSpecifiedTests=false', '-DargLine=-Djava.net.preferIPv4Stack=true', 'test'], { cwd: project, env, stdio: 'inherit' })
  const code = await new Promise((resolve, reject) => child.once('error', reject).once('exit', resolve))
  if (code !== 0) process.exitCode = code || 1
  else console.log('Live simulation passed. Result: .local/file-analysis-simulation-result.json')
} finally {
  fixture.kill('SIGTERM')
  await new Promise(resolve => { if (fixture.exitCode !== null) resolve(); else fixture.once('exit', resolve) })
  // Do not retain a second copy of the user's primary provider key after the test.
  rmSync(configPath, { force: true })
  minioConfig?.cleanup()
}
