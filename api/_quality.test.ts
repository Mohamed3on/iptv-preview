import { afterEach, beforeEach, describe, expect, test } from 'bun:test'
import { rm } from 'node:fs/promises'
import { mergeAppReading } from './_quality.js'
import { events as eventsRoute, quality as qualityRoute } from './app.js'
import { QUALITY } from './quality-cache.js'

const today = '2026-11-01'
const fresh = { w: 1920, h: 1080, fps: 25, codec: 'h264', name: 'A', at: '2026-10-30' }
const reading = (over: object = {}) => ({ w: 1920, h: 1080, fps: 50, codec: 'h264', name: 'A', ...over })

describe('app quality readings merge like the probe', () => {
  test('a missing or dead reading is filled', () => {
    expect(mergeAppReading(undefined, reading(), today)).toEqual({ ...reading(), at: today })
    expect(mergeAppReading(null, reading(), today)).toEqual({ ...reading(), at: today })
  })
  test('a reading older than 14 days is replaced, even by a lower one', () => {
    const old = { ...fresh, h: 2160, w: 3840, at: '2026-10-01' }
    expect(mergeAppReading(old, reading({ fps: 25 }), today)).toEqual({ ...reading({ fps: 25 }), at: today })
  })
  test('a fresh reading is only upgraded by a taller picture or newly found Atmos', () => {
    expect(mergeAppReading(fresh, reading({ w: 3840, h: 2160 }), today)?.h).toBe(2160)
    expect(mergeAppReading(fresh, reading({ fps: 25, atmos: true }), today)?.atmos).toBe(true)
    expect(mergeAppReading(fresh, reading({ h: 720, w: 1280 }), today)).toBeNull()
  })
  test('rendered fps never raises a same-height reading (interlaced feeds render 50 from 25)', () => {
    expect(mergeAppReading(fresh, reading({ fps: 50 }), today)).toBeNull()
  })
})

describe('/api/app/quality', () => {
  const env = { ...process.env }
  const dir = `${import.meta.dir}/../.cache/test-quality`
  const post = (body: unknown, t = 'app') =>
    qualityRoute(new Request(`http://localhost/api/app/quality?t=${t}`, { method: 'POST', body: JSON.stringify(body) }))
  beforeEach(async () => {
    Object.assign(process.env, { APP_TOKENS: 'app', QUALITY_CACHE_FILE: `${dir}/quality-cache.ts` })
    await rm(dir, { recursive: true, force: true })
  })
  afterEach(async () => {
    process.env = { ...env }
    delete QUALITY['990001']
    await rm(dir, { recursive: true, force: true })
  })

  test('refuses without an app token', async () => {
    expect((await post({ id: 990001, ...reading() }, 'tv')).status).toBe(403)
  })
  test('refuses a malformed reading', async () => {
    for (const bad of [{ id: 'x', ...reading() }, { id: 990001, ...reading({ h: 99999 }) }, { id: 990001, ...reading({ codec: '<b>' }) }])
      expect((await post(bad)).status).toBe(400)
  })
  test('stores a new reading in memory and in the cache file', async () => {
    const res = await post({ id: 990001, ...reading() })
    expect(await res.json()).toEqual({ stored: true })
    expect(QUALITY['990001']).toMatchObject({ h: 1080, fps: 50 })
    const file = await Bun.file(`${dir}/quality-cache.ts`).text()
    expect(file).toStartWith('// Auto-generated')
    expect(file).toContain('"990001":{"w":1920,"h":1080,"fps":50')
    expect(await (await post({ id: 990001, ...reading({ h: 720, w: 1280 }) })).json()).toEqual({ stored: false })
  })
})

describe('/api/app/events', () => {
  const env = { ...process.env }
  const file = `${import.meta.dir}/../.cache/test-events/app-events.jsonl`
  const post = (body: unknown, t = 'app') =>
    eventsRoute(new Request(`http://localhost/api/app/events?t=${t}`, { method: 'POST', body: JSON.stringify(body) }))
  beforeEach(async () => {
    Object.assign(process.env, { APP_TOKENS: 'app', APP_EVENTS_FILE: file })
    await rm(`${import.meta.dir}/../.cache/test-events`, { recursive: true, force: true })
  })
  afterEach(() => { process.env = { ...env } })

  test('appends a valid batch with the time received, and refuses the rest', async () => {
    expect((await post([{ type: 'tune', id: 1, ms: 900 }], 'tv')).status).toBe(403)
    expect((await post([{ type: 'nope' }])).status).toBe(400)
    expect((await post([{ type: 'tune', id: 1, ms: 900 }, { type: 'stall', id: 1 }, { type: 'rebuffer', id: 1, netMbps: 4.1, streamMbps: 6.2, cushionMs: 3000 }])).status).toBe(204)
    const lines = (await Bun.file(file).text()).trim().split('\n').map((l) => JSON.parse(l))
    expect(lines.map((l) => [l.type, typeof l.at])).toEqual([['tune', 'string'], ['stall', 'string'], ['rebuffer', 'string']])
  })
})
