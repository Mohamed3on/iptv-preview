// Sofa logo generator: render(config) → banner + icon SVG. Pure; all randomness lives in roll(seed).
// Geometry is kept to M/L/A commands and fill/stroke paths so it converts 1:1 to an Android VectorDrawable.

export type Mode = 'tube' | 'line' | 'stripes' | 'solid' | 'word'
export type Config = {
  seed: number
  mode: Mode
  ratio: number // sofa width / height
  armW: number // arm width / sofa width
  armH: number // arm top / sofa height
  seatH: number // seat line / sofa height
  hump: number // camelback hump / sofa height
  backFull: boolean // back spans over the arms
  roll: number // arm-top roundness, 1 = rolled scroll
  radius: number // corner radius / sofa height
  weight: number // line weight / sofa height
  gap: number // clear gap between parts / sofa height
  bands: number // stripes
  bandGap: number // space between stripes / line weight
  cushions: number // solid: seat cushions
  legs: number // solid: leg height / sofa height
  play: boolean // tube: play mark on the screen
  dot: 'off' | 'mark' | 'word'
  dotSize: number // dot radius / sofa height
  xh: number // wordmark x-height / banner height
  ink: number // wordmark stroke / x-height
  track: number // tracking / x-height
  asc: number // f height / x-height
  faJoin: boolean // f crossbar runs into the a
  markScale: number // mark height / f height (banner)
  layout: 'row' | 'stack'
  iconFill: number // icon mark width / 108
  iconWord: 'fa' | 's' | 'sofa'
  ground: 'ink' | 'navy' | 'glow'
}

const WHITE = '#F4F6F8'
const RED = '#FF5147'
const GROUNDS = { ink: '#12161C', navy: '#0E1A33', glow: '#12161C' }

type Cmd =
  | { c: 'M' | 'L'; x: number; y: number }
  | { c: 'A'; rx: number; ry: number; large: 0 | 1; sweep: 0 | 1; x: number; y: number }
type Box = [number, number, number, number]
export type Shape = {
  cmds: Cmd[]
  close?: boolean
  fill?: string
  stroke?: string
  sw?: number
  cap?: 'round' | 'butt'
  box: Box // geometry bounds, before stroke width
}
type Pt = [number, number]

// ---------- seeded randomness ----------
function mulberry32(a: number) {
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function roll(seed: number, mode: Mode): Config {
  const r = mulberry32(seed)
  const between = (a: number, b: number) => +(a + (b - a) * r()).toFixed(3)
  const pick = <T,>(xs: readonly T[]) => xs[Math.floor(r() * xs.length)]
  // three silhouettes that survive 48 px: camelback hump, chesterfield level arms, lawson step
  const arch = pick(['camelback', 'chesterfield', 'lawson'] as const)
  const shape =
    arch === 'camelback'
      ? { hump: between(0.12, 0.24), armH: between(0.42, 0.58), backFull: r() < 0.6, roll: between(0.5, 1) }
      : arch === 'chesterfield'
        ? { hump: 0, armH: between(0.9, 1), backFull: false, roll: between(0.85, 1) }
        : { hump: between(0, 0.05), armH: between(0.5, 0.66), backFull: r() < 0.4, roll: between(0.4, 1) }
  return {
    seed,
    mode,
    ratio: between(2, 2.8),
    armW: between(0.11, 0.2),
    seatH: between(0.2, 0.36),
    radius: between(0.04, 0.16),
    weight: between(0.1, 0.17),
    gap: between(0.05, 0.12),
    bands: pick([2, 3, 3, 4]),
    bandGap: between(0.45, 0.9),
    cushions: pick([1, 2, 2, 3]),
    legs: between(0, 0.1),
    play: r() < 0.6,
    dot: pick(['mark', 'mark', 'word', 'off'] as const),
    dotSize: between(0.07, 0.11),
    xh: mode === 'word' ? between(0.26, 0.34) : between(0.18, 0.24),
    ink: between(0.16, 0.24),
    track: between(0.02, 0.12),
    asc: between(1.35, 1.6),
    faJoin: r() < 0.5,
    markScale: between(0.9, 1.4),
    layout: r() < 0.7 ? 'row' : 'stack',
    iconFill: between(0.7, 0.8),
    iconWord: pick(['fa', 's', 'sofa'] as const),
    ground: pick(['ink', 'ink', 'navy', 'glow'] as const),
    ...shape,
  }
}

// ---------- geometry helpers ----------
const sub = (a: Pt, b: Pt): Pt => [a[0] - b[0], a[1] - b[1]]
const len = (v: Pt) => Math.hypot(v[0], v[1])
const norm = (v: Pt): Pt => [v[0] / len(v), v[1] / len(v)]
const cross = (a: Pt, b: Pt) => a[0] * b[1] - a[1] * b[0]
const left = (v: Pt): Pt => [-v[1], v[0]]

function hull(pts: Pt[]): Box {
  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1])
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)]
}

// polyline with circular corners; works for convex and concave turns
function rpoly(pts: Pt[], radii: number[], closed: boolean): { cmds: Cmd[]; box: Box } {
  const n = pts.length
  const cmds: Cmd[] = []
  const corner = (a: Pt, b: Pt, c: Pt, r: number) => {
    const din = norm(sub(b, a)), dout = norm(sub(c, b))
    const x = cross(din, dout)
    if (Math.abs(x) < 1e-6 || r <= 0) return cmds.push({ c: 'L', x: b[0], y: b[1] })
    const phi = Math.acos(Math.max(-1, Math.min(1, din[0] * dout[0] + din[1] * dout[1])))
    let t = r * Math.tan(phi / 2)
    const tmax = Math.min(len(sub(b, a)), len(sub(c, b))) / 2
    if (t > tmax) { t = tmax; r = t / Math.tan(phi / 2) }
    cmds.push({ c: 'L', x: b[0] - din[0] * t, y: b[1] - din[1] * t })
    cmds.push({ c: 'A', rx: r, ry: r, large: 0, sweep: x > 0 ? 1 : 0, x: b[0] + dout[0] * t, y: b[1] + dout[1] * t })
  }
  if (closed) {
    const s: Pt = [(pts[n - 1][0] + pts[0][0]) / 2, (pts[n - 1][1] + pts[0][1]) / 2]
    cmds.push({ c: 'M', x: s[0], y: s[1] })
    for (let i = 0; i < n; i++) corner(pts[(i - 1 + n) % n], pts[i], pts[(i + 1) % n], radii[i])
  } else {
    cmds.push({ c: 'M', x: pts[0][0], y: pts[0][1] })
    for (let i = 1; i < n - 1; i++) corner(pts[i - 1], pts[i], pts[i + 1], radii[i])
    cmds.push({ c: 'L', x: pts[n - 1][0], y: pts[n - 1][1] })
  }
  return { cmds, box: hull(pts) }
}

// parallel copy of an open polyline, d to the left of travel; corner radii follow
function offset(pts: Pt[], radii: number[], d: number): { pts: Pt[]; radii: number[] } {
  const n = pts.length
  const out: Pt[] = [], rs: number[] = []
  for (let i = 0; i < n; i++) {
    if (i === 0 || i === n - 1) {
      const seg = i === 0 ? norm(sub(pts[1], pts[0])) : norm(sub(pts[n - 1], pts[n - 2]))
      const nl = left(seg)
      out.push([pts[i][0] + nl[0] * d, pts[i][1] + nl[1] * d]); rs.push(0)
      continue
    }
    const din = norm(sub(pts[i], pts[i - 1])), dout = norm(sub(pts[i + 1], pts[i]))
    const n1 = left(din), n2 = left(dout)
    const q1: Pt = [pts[i][0] + n1[0] * d, pts[i][1] + n1[1] * d]
    const q2: Pt = [pts[i][0] + n2[0] * d, pts[i][1] + n2[1] * d]
    const x = cross(din, dout)
    if (Math.abs(x) < 1e-6) { out.push(q1); rs.push(radii[i]); continue }
    const s = cross(sub(q2, q1), dout) / x
    out.push([q1[0] + din[0] * s, q1[1] + din[1] * s])
    rs.push(Math.max(0, radii[i] - d * Math.sign(x)))
  }
  return { pts: out, radii: rs }
}

function circle(cx: number, cy: number, r: number, fill: string): Shape {
  return {
    cmds: [
      { c: 'M', x: cx + r, y: cy },
      { c: 'A', rx: r, ry: r, large: 1, sweep: 0, x: cx - r, y: cy },
      { c: 'A', rx: r, ry: r, large: 1, sweep: 0, x: cx + r, y: cy },
    ],
    close: true,
    fill,
    box: [cx - r, cy - r, cx + r, cy + r],
  }
}

// ---------- the sofa mark (units: sofa height H = 100, floor at y = 0) ----------
function sofa(k: Config): Shape[] {
  const H = 100, W = k.ratio * H
  const g = k.gap * H, sw = k.weight * H
  const dr = k.dotSize * H
  const peak = -H, top = -(1 - k.hump) * H
  const out: Shape[] = []

  if (k.mode === 'solid') {
    const lh = k.legs * H, bottom = -lh
    const aw = Math.max(k.armW * W, 0.1 * W)
    const armTop = Math.min(-k.armH * H, bottom - 0.3 * H)
    const seatY = Math.max(Math.min(-k.seatH * H, bottom - 0.12 * H), armTop + 0.12 * H)
    const rc = k.radius * H, rr = k.roll * aw / 2
    const add = (pts: Pt[], radii: number[]) => out.push({ ...rpoly(pts, radii, true), close: true, fill: WHITE })
    for (const [x0, x1] of [[0, aw], [W - aw, W]]) {
      add([[x0, bottom], [x0, armTop], [x1, armTop], [x1, bottom]], [rc / 2, rr, rr, rc / 2])
      if (lh > 0) {
        const lw = aw * 0.5, lx = (x0 + x1) / 2 - lw / 2
        add([[lx, 0], [lx, bottom], [lx + lw, bottom], [lx + lw, 0]], [lw / 3, 0, 0, lw / 3])
      }
    }
    const ix0 = aw + g, ix1 = W - aw - g
    const n = k.cushions, cw = (ix1 - ix0 - (n - 1) * g) / n
    for (let i = 0; i < n; i++) {
      const x = ix0 + i * (cw + g)
      add([[x, bottom], [x, seatY], [x + cw, seatY], [x + cw, bottom]], [rc / 2, rc, rc, rc / 2])
    }
    const backBottom = seatY - g
    let backTop = Math.min(top, backBottom - 0.2 * H)
    const humpPts = (xa: number, xb: number): Pt[] =>
      k.hump > 0.01 ? [[xa, backTop], [(xa + xb) / 2, backTop - (top - peak)], [xb, backTop]] : [[xa, backTop], [xb, backTop]]
    const humpR = (rb: number) => (k.hump > 0.01 ? [rb, W / 3, rb] : [rb, rb])
    const rb = rc * 1.5
    if (k.backFull && armTop - g - backTop > 0.08 * H) {
      const ab = armTop - g
      add(
        [[0, ab], ...humpPts(0, W), [W, ab], [ix1, ab], [ix1, backBottom], [ix0, backBottom], [ix0, ab]],
        [rc / 2, ...humpR(rb), rc / 2, rc / 2, rc / 2, rc / 2, rc / 2],
      )
      if (k.dot === 'mark') out.push(circle(W - g - dr * 1.6, backTop + g + dr * 1.6, dr, RED))
    } else {
      backTop = Math.min(backTop, armTop)
      add([[ix0, backBottom], ...humpPts(ix0, ix1), [ix1, backBottom]], [rc / 2, ...humpR(rb), rc / 2])
      if (k.dot === 'mark') out.push(circle(ix1 - g - dr * 1.4, backTop + g + dr * 1.4, dr, RED))
    }
    return out
  }

  // line, stripes, tube: one stroked U (arm, seat, arm) plus a back above it
  const stripes = k.mode === 'stripes'
  // stripes share the line's total mass (2 × weight) between the bands
  const T = stripes ? 2 * sw : sw
  const bw = stripes ? T / (k.bands + (k.bands - 1) * k.bandGap) : sw, bg = k.bandGap * bw
  const aw = Math.max(k.armW * W, T + 0.6 * sw)
  const armTop = -k.armH * H
  const seatY = Math.min(Math.max(-k.seatH * H, armTop + T + 0.1 * H), -T)
  const rr = k.roll * aw / 2, rs = k.radius * H + T / 2
  const U: Pt[] = [[0, 0], [0, armTop], [aw, armTop], [aw, seatY], [W - aw, seatY], [W - aw, armTop], [W, armTop], [W, 0]]
  const Ur = [0, rr, rr, rs, rs, rr, rr, 0]

  const full = k.backFull || stripes // a stripe back between the arms collapses to a stem
  const xL = full ? 0 : aw + g + T, xR = W - xL
  const yB = full ? armTop - g - T : seatY - g - T
  const yTop = Math.min(top, yB - 1.5 * T - 0.12 * H)
  const lift = top - peak
  const back: Pt[] = k.hump > 0.01
    ? [[xL, yB], [xL, yTop], [W / 2, yTop - lift], [xR, yTop], [xR, yB]]
    : [[xL, yB], [xL, yTop], [xR, yTop], [xR, yB]]
  const rb = k.radius * H * 1.5 + T / 2
  const backR = k.hump > 0.01 ? [0, rb, W / 3, rb, 0] : [0, rb, rb, 0]

  const stroke = (pts: Pt[], radii: number[], w: number, cap: 'round' | 'butt'): Shape =>
    ({ ...rpoly(pts, radii, false), stroke: WHITE, sw: w, cap })

  if (stripes) {
    for (let i = 0; i < k.bands; i++) {
      const d = (i - (k.bands - 1) / 2) * (bw + bg)
      const u = offset(U, Ur, d), b = offset(back, backR, d)
      out.push(stroke(u.pts, u.radii, bw, 'butt'), stroke(b.pts, b.radii, bw, 'butt'))
    }
  } else {
    out.push(stroke(U, Ur, sw, 'round'))
    if (k.mode === 'tube') {
      const scr = back.slice(0, -1).concat([[xR, yB]])
      out.push({ ...rpoly(scr, backR.map(r => r || rb), true), close: true, stroke: WHITE, sw, cap: 'round' })
      if (k.play) {
        const h = Math.min(yB - yTop, xR - xL) * 0.36, cx = (xL + xR) / 2 + h * 0.06, cy = (yTop + yB) / 2
        out.push({
          ...rpoly([[cx - h * 0.42, cy - h / 2], [cx + h * 0.5, cy], [cx - h * 0.42, cy + h / 2]], [h * 0.12, h * 0.12, h * 0.12], true),
          close: true,
          fill: WHITE,
        })
      }
    } else out.push(stroke(back, backR, sw, 'round'))
  }
  if (k.dot === 'mark') {
    const inset = T / 2 + g + dr
    out.push(circle(xR - inset, yTop + inset, dr, RED))
  }
  return out
}

// ---------- monoline wordmark (units: x-height = 100, baseline y = 0) ----------
function letters(k: Config, word: string, withDot: boolean): Shape[] {
  const c = (k.ink * 100) / 2, os = 0.03
  const asc = k.asc * 100, trackU = k.track * 100
  const out: Shape[] = []
  const st = (cmds: Cmd[], box: Box): Shape => ({ cmds, stroke: WHITE, sw: c * 2, cap: 'round', box })
  const bowl = (x0: number) => {
    const R = 50 * (1 + os) - c, cx = x0 + 50 * (1 + os), cy = -50
    return { ...circle(cx, cy, R, ''), fill: undefined, stroke: WHITE, sw: c * 2, cap: 'round' as const }
  }
  let x = 0
  const chars = word.split('')
  chars.forEach((ch, i) => {
    if (ch === 'o') { out.push(bowl(x)); x += 100 * (1 + os) }
    else if (ch === 'a') {
      out.push(bowl(x))
      const sx = x + 100 * (1 + os) - c
      out.push(st([{ c: 'M', x: sx, y: -(100 - c) }, { c: 'L', x: sx, y: -c }], [sx, -(100 - c), sx, -c]))
      x += 100 * (1 + os)
    } else if (ch === 's') {
      const ws = 78, cx = x + ws / 2, rx = ws / 2 - c, ry = (50 - c) / 2
      const cyT = -(150 - c) / 2, cyB = -(50 + c) / 2
      const pt = (cy: number, th: number) => [cx + rx * Math.cos(th), cy - ry * Math.sin(th)] as const
      const d = Math.PI / 180
      const [x0, y0] = pt(cyT, 25 * d), [x1, y1] = pt(cyT, 270 * d), [x2, y2] = pt(cyB, -155 * d)
      out.push(st([
        { c: 'M', x: x0, y: y0 },
        { c: 'A', rx, ry, large: 1, sweep: 0, x: x1, y: y1 },
        { c: 'A', rx, ry, large: 1, sweep: 1, x: x2, y: y2 },
      ], [x + c, -(100 - c), x + ws - c, -c]))
      x += ws
    } else if (ch === 'f') {
      const cbL = 22, rh = 26, hookExt = 14
      const sx = x + c + cbL
      out.push(st([
        { c: 'M', x: sx, y: -c },
        { c: 'L', x: sx, y: -(asc - c - rh) },
        { c: 'A', rx: rh, ry: rh, large: 0, sweep: 1, x: sx + rh, y: -(asc - c) },
        { c: 'L', x: sx + rh + hookExt, y: -(asc - c) },
      ], [sx, -(asc - c), sx + rh + hookExt, -c]))
      const joins = k.faJoin && chars[i + 1] === 'a'
      const end = joins ? x + c + cbL + rh + hookExt + c + trackU + 50 * (1 + os) : sx + 30
      out.push(st([{ c: 'M', x: sx - cbL, y: -(100 - c) }, { c: 'L', x: end, y: -(100 - c) }], [sx - cbL, -(100 - c), end, -(100 - c)]))
      x = sx + rh + hookExt + c
    }
    if (i < chars.length - 1) x += trackU
  })
  if (withDot) out.push(circle(x + trackU + c * 1.3, -c * 1.3, c * 1.3, RED))
  return out
}

// ---------- layout ----------
function bounds(shapes: Shape[]): Box {
  const b: Box = [Infinity, Infinity, -Infinity, -Infinity]
  for (const s of shapes) {
    const h = (s.sw ?? 0) / 2
    b[0] = Math.min(b[0], s.box[0] - h); b[1] = Math.min(b[1], s.box[1] - h)
    b[2] = Math.max(b[2], s.box[2] + h); b[3] = Math.max(b[3], s.box[3] + h)
  }
  return b
}

function move(shapes: Shape[], s: number, tx: number, ty: number): Shape[] {
  const p = (x: number, y: number) => [x * s + tx, y * s + ty] as const
  return shapes.map(sh => ({
    ...sh,
    sw: sh.sw && sh.sw * s,
    box: [...p(sh.box[0], sh.box[1]), ...p(sh.box[2], sh.box[3])] as Box,
    cmds: sh.cmds.map(c => {
      const [x, y] = p(c.x, c.y)
      return c.c === 'A' ? { ...c, rx: c.rx * s, ry: c.ry * s, x, y } : { ...c, x, y }
    }),
  }))
}

// scale + place a group so its bounds fill box (x, y, w, h), centred
function fit(shapes: Shape[], x: number, y: number, w: number, h: number): Shape[] {
  const b = bounds(shapes)
  const s = Math.min(w / (b[2] - b[0]), h / (b[3] - b[1]))
  return move(shapes, s, x + (w - (b[2] - b[0]) * s) / 2 - b[0] * s, y + (h - (b[3] - b[1]) * s) / 2 - b[1] * s)
}

export function bannerShapes(k: Config): Shape[] {
  const BH = 180, SAFE = { x: 20, y: 20, w: 280, h: 140 }
  const xhPx = k.xh * BH
  const word = letters(k, 'sofa', k.dot === 'word')
  if (k.mode === 'word') {
    const b = bounds(word), s = xhPx / 100
    const w = (b[2] - b[0]) * s, h = (b[3] - b[1]) * s
    const f = Math.min(1, SAFE.w / w, SAFE.h / h)
    return fit(word, 160 - (w * f) / 2, 90 - (h * f) / 2, w * f, h * f)
  }
  const markFor = (kk: Config) => { const b = bounds(sofa(kk)); return (k.markScale * k.asc * xhPx) / (b[3] - b[1]) }
  let km = k
  if (k.mode !== 'solid') for (let i = 0; i < 3; i++) km = { ...k, weight: (k.ink * xhPx) / markFor(km) / 100 }
  const mark = sofa(km)
  const ws = xhPx / 100
  const wordPx = move(word, ws, 0, 0)
  const mb = bounds(mark)
  const ms = (k.markScale * k.asc * xhPx) / (mb[3] - mb[1])
  let group: Shape[]
  if (k.layout === 'row') {
    const markPx = move(mark, ms, -mb[0] * ms, 0) // floor at y = 0 = baseline
    const gapPx = xhPx * 0.45
    group = [...markPx, ...move(wordPx, 1, bounds(markPx)[2] + gapPx - bounds(wordPx)[0], 0)]
  } else {
    const markPx = move(mark, ms * 0.8, 0, 0)
    const m = bounds(markPx), wb = bounds(wordPx)
    const dy = wb[1] - xhPx * 0.35 - m[3]
    group = [...move(markPx, 1, (wb[0] + wb[2]) / 2 - (m[0] + m[2]) / 2, dy), ...wordPx]
  }
  const g = bounds(group)
  const f = Math.min(1, SAFE.w / (g[2] - g[0]), SAFE.h / (g[3] - g[1]))
  const w = (g[2] - g[0]) * f, h = (g[3] - g[1]) * f
  return fit(group, 160 - w / 2, 90 - h / 2, w, h)
}

export function iconShapes(k: Config): Shape[] {
  const glyph = k.mode === 'word' ? letters(k, k.iconWord, false) : sofa(k).filter(s => k.dot === 'mark' || s.fill !== RED)
  const w = k.iconFill * 108
  return fit(glyph, 54 - w / 2, 54 - w / 2, w, w)
}

// ---------- SVG out ----------
const n = (v: number) => +v.toFixed(2)
export function pathData(s: Shape): string {
  return s.cmds.map(c =>
    c.c === 'A' ? `A${n(c.rx)} ${n(c.ry)} 0 ${c.large} ${c.sweep} ${n(c.x)} ${n(c.y)}` : `${c.c}${n(c.x)} ${n(c.y)}`,
  ).join(' ') + (s.close ? ' Z' : '')
}

function svg(shapes: Shape[], w: number, h: number, k: Config, px?: number): string {
  const glow = k.ground === 'glow'
    ? `<defs><radialGradient id="g" cx="0.3" cy="0.15" r="0.9"><stop offset="0" stop-color="#3D9BFF" stop-opacity="0.28"/><stop offset="1" stop-color="#3D9BFF" stop-opacity="0"/></radialGradient></defs><rect width="${w}" height="${h}" fill="url(#g)"/>`
    : ''
  const body = shapes.map(s =>
    s.fill
      ? `<path d="${pathData(s)}" fill="${s.fill}"/>`
      : `<path d="${pathData(s)}" fill="none" stroke="${s.stroke}" stroke-width="${n(s.sw!)}" stroke-linecap="${s.cap}" stroke-linejoin="round"/>`,
  ).join('')
  const size = px ? `width="${px}" height="${(px * h) / w}"` : ''
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" ${size}><rect width="${w}" height="${h}" fill="${GROUNDS[k.ground]}"/>${glow}${body}</svg>`
}

export const render = (k: Config, bannerPx?: number, iconPx?: number) => ({
  banner: svg(bannerShapes(k), 320, 180, k, bannerPx),
  icon: svg(iconShapes(k), 108, 108, k, iconPx),
})
