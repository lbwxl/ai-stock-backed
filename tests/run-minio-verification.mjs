// Explicit live check. Credentials are read from ignored local files and never printed.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, chmodSync, rmSync } from 'node:fs'
import { execFileSync, spawn } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { temporaryMinioConfig } from './fixtures/minio-config.mjs'

assert.ok(process.argv.includes('--live'), 'Pass --live to run real MinIO operations')
const project = fileURLToPath(new URL('..', import.meta.url))
const temporaryConfig = temporaryMinioConfig(project)
const config = temporaryConfig.path
assert.ok(readFileSync(config, 'utf8').trim(), 'Provide a private MinIO config file')
const primaryPath = resolve(project, '.local/minio-primary-test.json')
const env = { ...process.env, MINIO_LIVE_TEST: 'true', MINIO_TEST_CONFIG: config,
  MINIO_TEST_RESULT: resolve(project, '.local/minio-live-result.json'),
  JAVA_HOME: process.env.RUOYI_JAVA_HOME || '/Applications/PyCharm.app/Contents/jbr/Contents/Home' }
try {
  if (process.argv.includes('--model')) {
    // Uses the existing user 1 configuration, without changing it. This option calls a paid model API.
    const mysql = process.env.RUOYI_MYSQL || '/opt/homebrew/opt/mysql@8.4/bin/mysql'
    const args = ['--no-defaults', '--socket=' + resolve(project, '.local/mysql.sock'), '-u', 'root', '--batch', '--raw', '--skip-column-names']
    const databases = execFileSync(mysql, [...args, '-e', 'SHOW DATABASES'], { encoding: 'utf8' }).trim().split('\n').filter(x => !['information_schema', 'mysql', 'sys', 'performance_schema'].includes(x))
    assert.equal(databases.length, 1, 'Expected one local application database')
    const sql = "SELECT JSON_OBJECT('serviceName',s.service_name,'baseUrl',s.service_url,'apiKey',c.ai_service_key,'model',m.model_name) FROM user_webui_config c LEFT JOIN ai_service s ON s.id=c.ai_service_id LEFT JOIN ai_model m ON m.id=c.model_id WHERE c.user_id=1 ORDER BY c.id LIMIT 1"
    const primary = JSON.parse(execFileSync(mysql, [...args, databases[0], '-e', sql], { encoding: 'utf8' }).trim())
    assert.ok(primary.apiKey && primary.model && primary.baseUrl, 'Primary user model is not configured')
    writeFileSync(primaryPath, JSON.stringify(primary), { mode: 0o600 }); chmodSync(primaryPath, 0o600)
    env.MINIO_TEST_PRIMARY_CONFIG = primaryPath
  } else delete env.MINIO_TEST_PRIMARY_CONFIG
  const maven = process.env.RUOYI_MAVEN || resolve(process.env.HOME, '.local/share/aigupiao-tools/apache-maven-3.9.9/bin/mvn')
  const child = spawn(maven, ['-B', '-ntp', '-s', 'local-maven-settings.xml', '-pl', 'ruoyi-admin', '-am',
    '-Dtest=MinioFileAnalysisLiveTest', '-Dsurefire.failIfNoSpecifiedTests=false',
    '-DargLine=-Djava.awt.headless=true -Djava.net.preferIPv4Stack=true', 'test'], { cwd: project, env, stdio: 'inherit' })
  const code = await new Promise((resolve, reject) => child.once('error', reject).once('exit', resolve))
  process.exitCode = code || 0
} finally { rmSync(primaryPath, { force: true }); temporaryConfig.cleanup() }
