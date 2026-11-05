import { expect, test } from 'bun:test'
import { introWindow } from './_intros'

test('a timestamp answer maps to a skip window: a null start is the first frame, a too-short span is skipped, IntroDB fills in', () => {
  // TheIntroDB, The Pitt S02E03 as answered on 2026-11-05.
  expect(introWindow({ intro: [{ start_ms: null, end_ms: 15000 }] }, null)).toEqual([0, 15000])
  expect(introWindow({ intro: [{ start_ms: 1000, end_ms: 3000 }, { start_ms: 229500, end_ms: 246500 }] }, null)).toEqual([229500, 246500])
  expect(introWindow({ intro: [{ start_ms: 3030692, end_ms: null }] }, { intro: { start_ms: 596000, end_ms: 651000 } })).toEqual([596000, 651000])
  expect(introWindow({}, { intro: null })).toBeNull()
})
