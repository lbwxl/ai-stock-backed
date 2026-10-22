import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { readMinioConfig } from './fixtures/minio-config.mjs'

test('exported MinIO environment overrides ignored dotenv defaults without executing shell', () => {
  const directory = mkdtempSync(join(tmpdir(), 'minio-env-'))
  try {
    mkdirSync(join(directory, '.local'))
    writeFileSync(join(directory, '.local/backend.env'), "# defaults\nMINIO_ENDPOINT=http://local:9000\nMINIO_ACCESS_KEY=local-key\nMINIO_SECRET_KEY='literal-$HOME-value'\n")
    const config = readMinioConfig(directory, { MINIO_ENDPOINT: 'https://external.example.test' })
    assert.equal(config.endpoint, 'https://external.example.test')
    assert.equal(config.accessKey, 'local-key')
    assert.equal(config.secretKey, 'literal-$HOME-value')
    assert.equal(config.bucket, 'ai-file')
  } finally { rmSync(directory, { recursive: true, force: true }) }
})
