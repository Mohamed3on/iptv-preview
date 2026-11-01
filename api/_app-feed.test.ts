import { afterEach, beforeEach, describe, expect, spyOn, test } from 'bun:test'
import { buildAppFeed } from './_app-feed.js'
import { buildM3U, VPN_HOST, withCompetitionGroups, type Channel } from './_lib.js'
import { QUALITY } from './quality-cache.js'
import { channels as channelsRoute } from './app.js'
import { handle } from '../server.js'

const cfg = { host: 'http://plain.example', user: 'u', pass: 'p' }
const UK = '🏴 Sky Sports & UK Sports'
const ch = (streamId: number, name: string, tvgId: string, extra: Partial<Channel> = {}): Channel =>
  ({ streamId, name, logo: '', tvgId, group: UK, isEventSlot: false, q: 4, ...extra })
const byId = (feed: ReturnType<typeof buildAppFeed>, id: number) => feed.channels.filter((c) => c.id === id)

describe('app channel feed', () => {
  test('a 🏆 copy shares its streamId and points home to the original group', () => {
    const grouped = withCompetitionGroups([ch(900001, 'UK: SKY SPORTS PREMIER LEAGUE HD', 'test.home', { comps: ['pl'] })])
    const feed = buildAppFeed(cfg, grouped)
    const rows = byId(feed, 900001)
    expect(rows.map((r) => r.group)).toEqual(['🏆 Premier League', UK])
    expect(rows.every((r) => r.home === UK)).toBe(true)
    expect(rows[0].family).toBe(rows[1].family)
    expect(feed.groups).toEqual(['🏆 Premier League', UK])
  })

  test('a Premier League abroad channel appears only in its 🏆 group, with no home group', () => {
    const abroad = ch(900002, 'NZ: SKY SPORT PREMIER LEAGUE', 'test.nz', { group: '🌏 Premier League abroad', comps: ['pl'] })
    const feed = buildAppFeed(cfg, withCompetitionGroups([abroad]))
    const rows = byId(feed, 900002)
    expect(rows.map((r) => r.group)).toEqual(['🏆 Premier League'])
    expect(rows[0].home).toBeUndefined()
  })

  test('feeds of one channel share a family, ranked in server order; a mislabelled id stays out', () => {
    const feed = buildAppFeed(cfg, [
      ch(1, 'VIP: TNT SPORTS 1 RAW', 'TNTSport1.uk'),
      ch(2, 'NOW: TNT SPORT 1 HD', 'tntsports1.uk', { q: 3 }),
      ch(3, 'UK: SKY SPORTS NEWS HD', 'SkySportsNews.uk'),
      ch(4, 'UK: SKY SPORTS MAIN EVENT HD', 'SkySportsNews.uk'),
    ])
    const [tnt1, tnt2, news, mainEvent] = [1, 2, 3, 4].map((id) => byId(feed, id)[0])
    expect(tnt1.family).toBe(tnt2.family)
    expect([tnt1.rank, tnt2.rank]).toEqual([0, 1])
    expect(mainEvent.family).not.toBe(news.family)
    expect(tnt2.q).toBe(3)
  })

  test('only event slots whose current name reads as a fixture are flagged', () => {
    const slot = (id: number, name: string) => ch(id, name, `ppv.${id}`, { isEventSlot: true, group: '⚽ Football — Match PPV' })
    const feed = buildAppFeed(cfg, [slot(5, 'Arsenal v Chelsea'), slot(6, 'DAZN PPV 3'), ch(7, 'Arsenal v Chelsea', 'x.uk')])
    expect([5, 6, 7].map((id) => byId(feed, id)[0].fixture)).toEqual([true, undefined, undefined])
    expect(byId(feed, 5)[0].slot).toBe(true)
  })

  test('badges and probed height come from measured quality only', () => {
    const ids = { uhd: 910001, fhd50: 910002, atmos: 910003, unknown: 910004 }
    Object.assign(QUALITY, {
      [ids.uhd]: { w: 3840, h: 2160, fps: 25, codec: 'hevc', name: '' },
      [ids.fhd50]: { w: 1920, h: 1080, fps: 50, codec: 'h264', name: '' },
      [ids.atmos]: { w: 1920, h: 1080, fps: 25, codec: 'h264', name: '', atmos: true },
    })
    try {
      const feed = buildAppFeed(cfg, Object.values(ids).map((id) => ch(id, `UK: CHANNEL ${id} 4K`, `t.${id}`, { q: 5 })))
      const at = (id: number) => byId(feed, id)[0]
      expect(at(ids.uhd).badges).toEqual(['4K'])
      expect(at(ids.fhd50).badges).toEqual(['FHD', '50fps'])
      expect(at(ids.atmos).badges).toEqual(['FHD', 'Atmos'])
      expect(at(ids.unknown).badges).toEqual([])
      expect([at(ids.uhd).h, at(ids.unknown).h]).toEqual([2160, null])
    } finally {
      for (const id of Object.values(ids)) delete QUALITY[id]
    }
  })

  test('route info carries both provider fronts once', () => {
    expect(buildAppFeed(cfg, []).route).toEqual({ plain: cfg.host, vpn: VPN_HOST, user: 'u', pass: 'p' })
  })

  test('buildM3U output for the same channels is unchanged by the VPN_HOST move', () => {
    const m3u = buildM3U({ ...cfg, host: VPN_HOST }, [ch(1, 'UK: A', 'a.uk')], ['http://e'])
    expect(m3u).toBe(
      '#EXTM3U url-tvg="http://e"\n#EXTINF:-1 tvg-id="a.uk" tvg-name="UK: A" tvg-logo="" group-title="🏴 Sky Sports & UK Sports",UK: A\n' +
        'http://apps.g1ming-hub.sbs/live/u/p/1.ts\n',
    )
  })
})

describe('/api/app/channels token gate', () => {
  const env = { ...process.env }
  // Provider API stub: one category with one stream.
  beforeEach(() => {
    Object.assign(process.env, { XTREAM_HOST: 'http://plain.example', XTREAM_USER: 'u', XTREAM_PASS: 'p', PLAYLIST_TOKEN: 'tv' })
    spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
      const action = new URL(url).searchParams.get('action')
      if (action === 'get_live_categories') return Response.json([{ category_id: '9001', category_name: 'UK| PREMIER LEAGUE' }])
      if (action === 'get_live_streams')
        return Response.json([{ stream_id: 1, name: 'UK: SKY SPORTS MAIN EVENT FHD', epg_channel_id: 'a.uk', category_id: '9001' }])
      return Response.json([])
    }) as typeof fetch)
  })
  afterEach(() => {
    process.env = { ...env }
  })
  const get = (t?: string) => channelsRoute(new Request(`http://localhost/api/app/channels${t === undefined ? '' : `?t=${t}`}`))

  test('no token, the TiviMate token, an empty token, or no APP_TOKENS are refused', async () => {
    process.env.APP_TOKENS = 'new,'
    expect((await get()).status).toBe(403)
    expect((await get('tv')).status).toBe(403)
    expect((await get('')).status).toBe(403)
    delete process.env.APP_TOKENS
    expect((await get('new')).status).toBe(403)
  })

  test('either of two app tokens returns the private feed', async () => {
    process.env.APP_TOKENS = 'old, new'
    for (const t of ['old', 'new']) {
      const res = await get(t)
      expect(res.status).toBe(200)
      expect(res.headers.get('cache-control')).toBe('private, s-maxage=1800, stale-while-revalidate=3600')
      expect(res.headers.get('access-control-allow-origin')).toBeNull()
      expect((await res.json()).channels.length).toBeGreaterThan(0)
    }
  })

  test('through server.ts, a warmed cache never serves the tokenless path', async () => {
    process.env.APP_TOKENS = 'new'
    expect((await handle(new Request('http://localhost/api/app/channels?t=new'))).status).toBe(200)
    expect((await handle(new Request('http://localhost/api/app/channels'))).status).toBe(403)
    expect((await handle(new Request('http://localhost/api/app/channels?t='))).status).toBe(403)
  })
})
