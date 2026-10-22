// Starts only a loopback test fixture; never exposed through the file tunnel.
import { readFileSync, writeFileSync } from 'node:fs'
import { createThirdPartyService } from './fixtures/third-party-file-service.mjs'
const configPath = process.env.FILE_ANALYSIS_SIMULATION_CONFIG
if (!configPath) throw new Error('Set FILE_ANALYSIS_SIMULATION_CONFIG to an ignored private test config')
const config = JSON.parse(readFileSync(configPath, 'utf8'))
const trace = []
const server = createThirdPartyService({
  settings: config.thirdParty, fixtureKey: config.fixture.apiKey,
  record(event) { trace.push(event); writeFileSync(config.traceFile, JSON.stringify(trace, null, 2), { mode: 0o600 }) },
})
const address = new URL(config.fixture.baseUrl)
if (address.hostname !== '127.0.0.1') throw new Error('Simulation must bind to loopback')
server.listen(Number(address.port), address.hostname, () => console.log('Test-only third-party file service ready on loopback'))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)))
