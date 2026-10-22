import assert from 'node:assert/strict'
import { existsSync, readFileSync, writeFileSync, rmSync, mkdtempSync } from 'node:fs'
import { resolve } from 'node:path'

// Mirrors start-local.sh: real environment variables override the ignored local dotenv defaults.
export function readMinioConfig(project, env = process.env) {
  if (env.MINIO_TEST_CONFIG) return JSON.parse(readFileSync(env.MINIO_TEST_CONFIG, 'utf8'))
  const defaults = {}
  const file = resolve(project, '.local/backend.env')
  if (existsSync(file)) for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const match = /^(?:export\s+)?([A-Z][A-Z0-9_]*)=(.*)$/.exec(trimmed)
    assert.ok(match, 'Invalid local dotenv assignment')
    let value = match[2]
    if (value.startsWith('"')) value = JSON.parse(value)
    else if (value.startsWith("'") && value.endsWith("'")) value = value.slice(1, -1).replaceAll("'\"'\"'", "'")
    defaults[match[1]] = value
  }
  const value = (key, fallback = '') => env[key] ?? defaults[key] ?? fallback
  const config = { endpoint: value('MINIO_ENDPOINT'), publicEndpoint: value('MINIO_PUBLIC_ENDPOINT'),
    accessKey: value('MINIO_ACCESS_KEY'), secretKey: value('MINIO_SECRET_KEY'), bucket: value('MINIO_BUCKET', 'ai-file'),
    region: value('MINIO_REGION', 'us-east-1'), prefix: value('MINIO_PREFIX', 'file-analysis/') }
  assert.ok(config.endpoint && config.accessKey && config.secretKey, 'Set MinIO environment variables or .local/backend.env')
  return config
}

export function temporaryMinioConfig(project) {
  const directory = mkdtempSync(resolve(project, '.local/minio-test-'))
  const path = resolve(directory, 'config.json')
  try { writeFileSync(path, JSON.stringify(readMinioConfig(project)), { mode: 0o600 }) }
  catch (error) { rmSync(directory, { recursive: true, force: true }); throw error }
  return { path, cleanup: () => rmSync(directory, { recursive: true, force: true }) }
}
