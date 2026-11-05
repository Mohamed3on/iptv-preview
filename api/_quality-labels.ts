// Ready-to-show quality labels for films and episodes ("4K", "Dolby Vision", "HEVC", "20 Mbps",
// "Atmos", "E-AC3 5.1"), best first, unknowns left out. Episodes carry the provider's probe of the
// stream (get_series_info); films carry only a bitrate, so theirs come from the category's own tags
// ("NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ", "TOP MOVIES BLURAY").

/** The provider's ffprobe-style stream description, as far as labels need it. */
export interface ProbedStream {
  video?: {
    codec_name?: string
    codec_tag_string?: string
    width?: number | string
    height?: number | string
    color_transfer?: string
    r_frame_rate?: string
    avg_frame_rate?: string
    side_data_list?: { side_data_type?: string }[]
  }
  audio?: { codec_name?: string; codec_long_name?: string; profile?: string; channels?: number | string }
  /** kbps */
  bitrate?: number | string
}

const VIDEO_CODEC: Record<string, string> = { hevc: 'HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', mpeg2video: 'MPEG-2' }
const AUDIO_CODEC: Record<string, string> = { eac3: 'E-AC3', ac3: 'AC3', truehd: 'TrueHD', aac: 'AAC', dts: 'DTS', opus: 'Opus', mp3: 'MP3', flac: 'FLAC', mp2: 'MP2' }
const LAYOUT: Record<number, string> = { 1: 'Mono', 2: 'Stereo', 6: '5.1', 8: '7.1' }

function resolution(width: number, height: number): string | null {
  if (height >= 2160 || width >= 3800) return '4K'
  if (height >= 1080 || width >= 1900) return '1080p'
  if (height >= 720 || width >= 1260) return '720p'
  return height > 0 ? `${height}p` : null
}

/** "20 Mbps", "7.4 Mbps"; null when unknown. */
export function bitrateLabel(kbps: number | string | undefined): string | null {
  const mbps = Number(kbps) / 1000
  if (!(mbps > 0)) return null
  return `${mbps < 10 ? mbps.toFixed(1) : Math.round(mbps)} Mbps`
}

/** Labels from a probed stream (an episode's). */
export function streamLabels(s: ProbedStream | undefined): string[] {
  if (!s) return []
  const v = s.video ?? {}
  const a = s.audio ?? {}
  const out: (string | null)[] = []
  out.push(resolution(Number(v.width) || 0, Number(v.height) || 0))
  const dv = /dvhe|dvh1|dvav|dva1/i.test(`${v.codec_tag_string ?? ''} ${v.codec_name ?? ''}`) || (v.side_data_list ?? []).some((d) => /dovi|dolby vision/i.test(d.side_data_type ?? ''))
  out.push(dv ? 'Dolby Vision' : v.color_transfer === 'smpte2084' ? 'HDR10' : v.color_transfer === 'arib-std-b67' ? 'HLG' : null)
  const [num, den] = String(v.avg_frame_rate || v.r_frame_rate || '').split('/').map(Number)
  const fps = den ? num / den : num
  out.push(fps >= 48 ? `${Math.round(fps)} fps` : null)
  out.push(VIDEO_CODEC[String(v.codec_name ?? '').toLowerCase()] ?? null)
  out.push(bitrateLabel(s.bitrate))
  const audioText = `${a.profile ?? ''} ${a.codec_long_name ?? ''}`
  if (/atmos|joc/i.test(audioText)) out.push('Atmos')
  const codec = /dts-hd ma/i.test(audioText) ? 'DTS-HD MA' : AUDIO_CODEC[String(a.codec_name ?? '').toLowerCase()]
  const layout = LAYOUT[Number(a.channels)] ?? null
  if (codec || layout) out.push([codec, layout].filter(Boolean).join(' '))
  return out.filter((l): l is string => !!l)
}

/** Labels for a film from its category's tags and its bitrate (kbps). Tags read through their superscript spelling. */
export function categoryLabels(category: string, kbps?: number | string): string[] {
  const c = category.normalize('NFKC').replace(/ɪ/g, 'i')
  const out: (string | null)[] = []
  out.push(/\b4K\b|3840|2160|\bUHD\b|فائقة الوضوح/i.test(c) ? '4K' : /blu-?ray/i.test(c) ? '1080p' : null)
  out.push(/dolby\s*vis|\bDV\b/i.test(c) ? 'Dolby Vision' : /\bHDR/i.test(c) ? 'HDR' : null)
  out.push(bitrateLabel(kbps))
  if (/dolby\s*audio/i.test(c)) out.push('Dolby Audio')
  return out.filter((l): l is string => !!l)
}
