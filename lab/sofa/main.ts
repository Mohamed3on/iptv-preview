import { render, roll, type Config, type Mode } from './render'

type Knob =
  | { key: keyof Config; label: string; min: number; max: number; step: number }
  | { key: keyof Config; label: string; options: readonly (string | boolean | number)[] }

const KNOBS: [string, Knob[]][] = [
  ['Sofa', [
    { key: 'ratio', label: 'width', min: 1.8, max: 3.2, step: 0.01 },
    { key: 'armW', label: 'arm width', min: 0.06, max: 0.26, step: 0.005 },
    { key: 'armH', label: 'arm height', min: 0.3, max: 1, step: 0.01 },
    { key: 'roll', label: 'arm roll', min: 0, max: 1, step: 0.01 },
    { key: 'seatH', label: 'seat height', min: 0.12, max: 0.5, step: 0.01 },
    { key: 'hump', label: 'camel hump', min: 0, max: 0.3, step: 0.005 },
    { key: 'backFull', label: 'back over arms', options: [false, true] },
    { key: 'radius', label: 'corners', min: 0, max: 0.25, step: 0.005 },
    { key: 'weight', label: 'line weight', min: 0.05, max: 0.22, step: 0.005 },
    { key: 'gap', label: 'gap', min: 0.02, max: 0.18, step: 0.005 },
  ]],
  ['Mode extras', [
    { key: 'bands', label: 'stripes', options: [2, 3, 4] },
    { key: 'bandGap', label: 'stripe gap', min: 0.25, max: 1.4, step: 0.01 },
    { key: 'cushions', label: 'cushions', options: [1, 2, 3] },
    { key: 'legs', label: 'legs', min: 0, max: 0.16, step: 0.005 },
    { key: 'play', label: 'play mark', options: [true, false] },
  ]],
  ['Live dot', [
    { key: 'dot', label: 'dot', options: ['mark', 'word', 'off'] },
    { key: 'dotSize', label: 'dot size', min: 0.04, max: 0.16, step: 0.005 },
  ]],
  ['Wordmark', [
    { key: 'xh', label: 'size', min: 0.14, max: 0.4, step: 0.005 },
    { key: 'ink', label: 'weight', min: 0.1, max: 0.3, step: 0.005 },
    { key: 'track', label: 'tracking', min: -0.06, max: 0.25, step: 0.005 },
    { key: 'asc', label: 'f height', min: 1.2, max: 1.8, step: 0.01 },
    { key: 'faJoin', label: 'fa joined', options: [false, true] },
  ]],
  ['Layout', [
    { key: 'layout', label: 'banner', options: ['row', 'stack'] },
    { key: 'markScale', label: 'mark size', min: 0.6, max: 2, step: 0.01 },
    { key: 'iconFill', label: 'icon fill', min: 0.45, max: 0.92, step: 0.005 },
    { key: 'iconWord', label: 'word icon', options: ['fa', 's', 'sofa'] },
    { key: 'ground', label: 'ground', options: ['ink', 'navy', 'glow'] },
  ]],
]

const MODES: [Mode, string][] = [
  ['tube', 'A · Tube sofa'],
  ['line', 'B · One line'],
  ['stripes', 'C · Stripes'],
  ['solid', 'D · Solid'],
  ['word', 'E · Wordmark'],
]

const $ = (id: string) => document.getElementById(id)!
const fresh = (mode: Mode) => roll(Math.floor(Math.random() * 2 ** 31), mode)

let cfg: Config = (() => {
  try { return JSON.parse(decodeURIComponent(location.hash.slice(1))) } catch { return fresh('tube') }
})()

function draw() {
  const big = render(cfg, 960, 324)
  $('banner').innerHTML = big.banner
  $('icon').innerHTML = big.icon
  const real = render(cfg, 320, 108)
  const dummy = (c: string) => `<div class="card" style="width:320px;height:180px;background:${c}"></div>`
  $('real').innerHTML =
    `<figure>${dummy('#1d2530')}<figcaption>Projectivy row, real size</figcaption></figure>` +
    `<figure><div class="card focused">${real.banner}</div><figcaption>focused</figcaption></figure>` +
    `<figure><div class="firetile"><div class="icon">${real.icon}</div></div><figcaption>Fire OS app library</figcaption></figure>` +
    `<figure><div class="icon" style="width:48px;height:48px">${render(cfg, 320, 48).icon}</div><figcaption>48 dp</figcaption></figure>`
  $('seed').textContent = String(cfg.seed)
  for (const b of $('tabs').querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.mode === cfg.mode))
  for (const [, knobs] of KNOBS) for (const k of knobs) {
    const el = document.querySelector<HTMLInputElement | HTMLSelectElement>(`[name="${k.key}"]`)!
    el.value = String(cfg[k.key])
    const out = el.parentElement!.querySelector('output')
    if (out) out.textContent = String(cfg[k.key])
  }
  history.replaceState(null, '', '#' + encodeURIComponent(JSON.stringify(cfg)))
}

$('tabs').innerHTML = MODES.map(([m, l]) => `<button data-mode="${m}">${l}</button>`).join('')
$('tabs').addEventListener('click', e => {
  const m = (e.target as HTMLElement).dataset.mode as Mode | undefined
  if (m) { cfg = fresh(m); draw() }
})

$('panel').innerHTML = KNOBS.map(([title, knobs]) =>
  `<h3>${title}</h3>` + knobs.map(k =>
    'options' in k
      ? `<label>${k.label}<select name="${k.key}">${k.options.map(o => `<option>${o}</option>`).join('')}</select><span></span></label>`
      : `<label>${k.label}<input type="range" name="${k.key}" min="${k.min}" max="${k.max}" step="${k.step}"><output></output></label>`,
  ).join(''),
).join('')
$('panel').addEventListener('input', e => {
  const el = e.target as HTMLInputElement
  const v = el.value
  cfg = { ...cfg, [el.name]: v === 'true' ? true : v === 'false' ? false : isNaN(+v) ? v : +v }
  draw()
})

for (const id of ['banner', 'icon']) $(id).addEventListener('click', () => { cfg = fresh(cfg.mode); draw() })

async function rail() {
  const list: { name: string; config: Config }[] = await fetch('/__lab/sofa').then(r => r.json())
  $('rail').innerHTML = list.map((s, i) => `<div data-i="${i}" title="${s.name}">${render(s.config, 213).banner}</div>`).join('')
  $('rail').onclick = e => {
    const i = (e.target as HTMLElement).closest<HTMLElement>('[data-i]')?.dataset.i
    if (i !== undefined) { cfg = list[+i].config; draw() }
  }
}

$('save').addEventListener('click', async () => {
  const json = JSON.stringify(cfg, null, 2)
  await navigator.clipboard.writeText(json).catch(() => {})
  const { path } = await fetch('/__lab/sofa', { method: 'POST', body: json }).then(r => r.json())
  $('saved').textContent = `saved ${path} (and copied)`
  rail()
})

draw()
rail()
