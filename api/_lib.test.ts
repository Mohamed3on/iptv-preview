import { describe, expect, test } from 'bun:test'
import { KEEP, autoIncluded, bucketForCategory, qualityScore } from './_lib.js'

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
  })
})
