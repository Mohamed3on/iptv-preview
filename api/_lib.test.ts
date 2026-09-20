import { describe, expect, test } from 'bun:test'
import {
  KEEP,
  autoIncluded,
  bucketForCategory,
  buildEventEpg,
  chanKey,
  learnedCompetitions,
  qualityScore,
  saneFps,
  staticCompetitions,
  withCompetitionGroups,
  type Channel,
} from './_lib.js'

describe('sports playlist curation', () => {
  test('does not reintroduce expired 2026 tournament packages', () => {
    const staleIds = [2343, 2361, 2352, 2346, 2353, 2354, 2355, 2362, 1958, 1334, 2334]
    expect(staleIds.some((id) => KEEP.includes(id))).toBe(false)
    expect(autoIncluded('8K| WORLD CUP 2026 8K')).toBe(false)
    expect(autoIncluded('ES| DAZN MUNDIAL PPV')).toBe(false)
    expect(autoIncluded('FR| ROLAND GARROS 2026 RAW')).toBe(false)
  })

  test('keeps current high-quality beIN families in Arabic sports', () => {
    const highQualityBeinIds = [1134, 780, 346, 781, 1133, 349]
    expect(highQualityBeinIds.every((id) => KEEP.includes(id))).toBe(true)
    expect(bucketForCategory('AR| BEIN SPORTS 8K & RAW')).toBe('🇸🇦 beIN & Arabic Sports')
    expect(qualityScore('ᵁᴴᴰ: beIN Sports 1 HD', 'AR| BEIN SPORTS UHD')).toBe(3)
    expect(qualityScore('ᵁᴴᴰ: beIN Sports 1 ˢᵈ', 'AR| BEIN SPORTS UHD')).toBe(0)
    expect(qualityScore('SS: beIN Sports 1 4K', 'AR| BEIN SPORTS SS')).toBe(5)
  })

  test('continues to discover newly added current football categories', () => {
    expect(autoIncluded('UK| UEFA CHAMPIONS LEAGUE PPV')).toBe(true)
    expect(autoIncluded('DE| BUNDESLIGA PPV')).toBe(true)
    // the provider writes the French league's channel without a space
    expect(autoIncluded('FR| LIGUE1+ ᴮᴱ')).toBe(true)
    expect(bucketForCategory('FR| LIGUE1+ ᴮᴱ')).toBe('🇫🇷 French Sports')
  })
})

describe('sibling feeds', () => {
  test('chanKey collapses a channel\'s feed families onto one identity', () => {
    expect(chanKey('VIP: TNT SPORTS 1 ᴿᴬᵂ ⁵⁰ FPS')).toBe('tnt sport 1')
    expect(chanKey('NOW: TNT SPORT 1 ᴴᴰ')).toBe('tnt sport 1')
    expect(chanKey('UK: TNT SPORTS 1 HEVC 4K')).toBe('tnt sport 1')
    expect(chanKey('DE: SKY SPORT BUNDESLIGA 1 HD (SAT)')).toBe('sky sport bundesliga 1')
    expect(chanKey('SKYGO: SKY SPORT BUNDESLIGA 1 4K')).toBe('sky sport bundesliga 1')
    // superscript words survive as words, so Arena stays distinct from Sky Sports+
    expect(chanKey('UK: SKY SPORTS ᴬʳᵉⁿᵃ HD')).toBe('sky sport arena')
    expect(chanKey('NOW: SKY SPORTS+ ᴿᴬᵂ')).toBe('sky sport')
  })

  test('EPG: an id the guides lack borrows its sibling feed\'s programmes', () => {
    const uk = '🏴 Sky Sports & UK Sports'
    const ch = (streamId: number, name: string, tvgId: string): Channel =>
      ({ streamId, name, logo: '', tvgId, group: uk, isEventSlot: false, q: 4 })
    const channels = [
      ch(1, 'UK: TNT SPORTS 1 ᴿᴬᵂ', 'TNTSport1.uk'),
      ch(2, 'NOW: TNT SPORT 1 ᴴᴰ', 'tntsports1.uk'),
      ch(3, 'UK: SKY SPORTS NEWS HD', 'SkySportsNews.uk'),
      ch(4, 'UK: SKY SPORTS MAIN EVENT HD', 'SkySportsNews.uk'), // provider mislabel
      ch(5, 'NOW: SKY SPORTS MAIN EVENT', 'sx.5'),
    ]
    const prog = (id: string, title: string) =>
      `<programme start="20260920180000 +0000" stop="20260920190000 +0000" channel="${id}"><title>${title}</title></programme>`
    const xml = buildEventEpg(channels, '', {
      programmes: [prog('tntsports1.uk', 'Live Serie A: Juventus v Atalanta'), prog('SkySportsNews.uk', 'Sky Sports News')],
      covered: new Set(['tntsports1.uk', 'SkySportsNews.uk']),
    })
    expect(xml).toContain('channel="TNTSport1.uk"><title>Live Serie A: Juventus v Atalanta</title>')
    expect(xml).not.toContain('<title>UK: TNT SPORTS 1 ᴿᴬᵂ</title>')
    // an id the provider also stamps on a differently-named channel never donates
    expect(xml).not.toContain('channel="sx.5"><title>Sky Sports News</title>')
    expect(xml).toContain('channel="sx.5"><title>NOW: SKY SPORTS MAIN EVENT</title>')
  })
})

describe('competition groups', () => {
  test('names and categories place channels; other sports and women\'s leagues do not', () => {
    expect(staticCompetitions('UK: SERIE A - AC MILAN 4K', 'UK| SERIE A TEAM PPV')).toEqual(['seriea'])
    expect(staticCompetitions('IT: ZONA DAZN SERIE A 1 4K', 'IT| SERIE A/B/C')).toEqual(['seriea'])
    expect(staticCompetitions('IT: TIM VISION SERIE A FEMMINILE 1', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: ALLIANZ MILANO -SUPERLEGA SERIE A MASCHILE-', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: HIGHLIGHTS SERIE A HD', 'IT| SPORT HD/4K')).toEqual([])
    expect(staticCompetitions('IT: DAZN DIRETTA GOL SERIE B HD', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: GIUGLIANO GIRONE C', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('UK: SKY SPORTS PL ᴿᴬᵂ', 'UK| SPORT ᴿᴬᵂ')).toEqual(['pl'])
    expect(staticCompetitions('IT: Eleven Sport Pallamano Bundesliga Live + Replica', 'IT| SPORT HD/4K')).toEqual([])
    expect(staticCompetitions('ES: LALIGA TV HYPERMOTION ᴿᴬᵂ', 'ES| M+ LALIGA VIP')).toEqual([])
    expect(staticCompetitions('ES: M+ LIGA DE CAMPEONES 2 ᴿᴬᵂ', 'ES| M+ LIGA DE CAMPEONES VIP')).toEqual(['ucl'])
    expect(staticCompetitions('UK: VIDIO EVENT 1', 'UK| VIDIO EPL PPV')).toEqual(['pl'])
    expect(staticCompetitions('CHAMP: BIRMINGHAM CITY', 'UK| CHAMPIONSHIP PPV')).toEqual(['champ'])
    expect(staticCompetitions('FR: LIGUE1+ 1 ⱽᴵᴾ ᴿᴬᵂ', 'FR| LIGUE1+ ⱽᴵᴾ ᴿᴬᵂ')).toEqual(['ligue1'])
    // a bare UEFA package can't tell the three cups apart, so it joins all of them
    expect(staticCompetitions('UEFA | 03 - Beşiktaş vs Marseille 8:00 pm', 'UK| UEFA PPV')).toEqual(['ucl', 'uel', 'uecl'])
  })

  test('EPG titles count only when they are live matches', () => {
    expect(learnedCompetitions('Live Serie A: Juventus v Atalanta')).toEqual(['seriea'])
    expect(learnedCompetitions('Nottingham Forest vs  Coventry City - English Premier League 2026/2027')).toEqual(['pl'])
    expect(learnedCompetitions('Bundesliga - Bayer 04 Leverkusen - RB Leipzig ᴸᶦᵛᵉ')).toEqual(['bundes'])
    expect(learnedCompetitions('UEFA Europa League: Juventus - NEC')).toEqual(['uel'])
    expect(learnedCompetitions('Serie A Preview Show Magazine:Round 5')).toEqual([])
    expect(learnedCompetitions('Premier League Review')).toEqual([])
    expect(learnedCompetitions('Brasileiro Serie A: Santos v Cruzeiro')).toEqual([])
    expect(learnedCompetitions('Frauen-Bundesliga - FC Bayern München - 1. FC Köln')).toEqual([])
    expect(learnedCompetitions('Es folgt: Live 2. Bundesliga')).toEqual([])
    expect(learnedCompetitions('Bundesliga Highlights - 4. Spieltag')).toEqual([])
    expect(learnedCompetitions('EPL Netbusters - 2026/2027 EP 4')).toEqual([])
    expect(learnedCompetitions('Serie A - Full Impact')).toEqual([])
    // retro replays carry an old season tag
    expect(learnedCompetitions('EFL Cup 07/08: Chelsea v Spurs', new Date('2026-09-20'))).toEqual([])
    expect(learnedCompetitions('Goal Rush: Champions League 2017/18', new Date('2026-09-20'))).toEqual([])
    expect(learnedCompetitions('Manchester City vs Norwich - Carabao Cup 2026 / 2027 - Round 3', new Date('2026-09-20'))).toEqual(['eflcup'])
  })

  test('groups lead the playlist; always-on channels first, then quality, then language', () => {
    const mk = (streamId: number, name: string, group: string, q: number, extra: Partial<Channel> = {}): Channel =>
      ({ streamId, name, logo: '', tvgId: 'x', group, isEventSlot: false, q, comps: staticCompetitions(name, ''), ...extra })
    const channels = [
      mk(900000001, 'UK: SKY SPORTS PREMIER LEAGUE HD', '🏴 Sky Sports & UK Sports', 3),
      mk(900000003, 'AR: BUNDESLIGA 1 ᴿᴬᵂ', '🇸🇦 beIN & Arabic Sports', 4),
      mk(900000004, 'DE: SKY SPORT BUNDESLIGA 1 4K', '🇩🇪 German Sports', 5),
      mk(900000005, 'DE: SKY SPORT BUNDESLIGA 1 HD', '🇩🇪 German Sports', 3),
      mk(900000006, 'DE: SKY SPORT BUNDESLIGA 1 (MOBIL)', '🇩🇪 German Sports', 3),
      mk(900000008, 'UK: EPL 1 PPV ᵁᴴᴰ ³⁸⁴⁰ᴾ', '⚽ Football — Match PPV', 5, { isEventSlot: true }),
      mk(900000002, 'UK: EPL ARSENAL', '⚽ Football — Match PPV', 2, { isEventSlot: true }),
      mk(900000007, 'DE: BUNDESLIGA REPLAY 1 HD', '📼 Football — Replays', 3),
    ]
    const out = withCompetitionGroups(channels)
    const groups = out.map((c) => c.group)
    expect(groups[0]).toBe('🏆 Premier League')
    expect(groups.lastIndexOf('🏆 Bundesliga')).toBeLessThan(groups.indexOf('🏴 Sky Sports & UK Sports'))
    // an idle 4K-marked slot never outranks a channel that is actually on air
    expect(out.filter((c) => c.group === '🏆 Premier League').map((c) => c.name))
      .toEqual(['UK: SKY SPORTS PREMIER LEAGUE HD', 'UK: EPL 1 PPV ᵁᴴᴰ ³⁸⁴⁰ᴾ', 'UK: EPL ARSENAL'])
    // 4K first, then the Arabic RAW at 1080 over the German 720p feeds; best + one spare per channel; replays never
    expect(out.filter((c) => c.group === '🏆 Bundesliga').map((c) => c.name))
      .toEqual(['DE: SKY SPORT BUNDESLIGA 1 4K', 'AR: BUNDESLIGA 1 ᴿᴬᵂ', 'DE: SKY SPORT BUNDESLIGA 1 HD'])
    expect(out.length).toBe(channels.length + 6)
  })

  test('a 100fps reading is the 50p field rate, not a better feed', () => {
    expect(saneFps(100)).toBe(50)
    expect(saneFps(50)).toBe(50)
    expect(saneFps(120)).toBe(0)
  })
})
