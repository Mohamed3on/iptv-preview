import { expect, test } from 'bun:test'
import { buildShowList } from './_shows'

test('a show is kept once per language, the 4K copy first, English rows leading', () => {
  const cats = [
    { category_id: '1', category_name: 'FIFA+ SERIES' },
    { category_id: '2', category_name: 'APPLE+ SERIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ' },
    { category_id: '3', category_name: 'APPLE+ SERIES' },
    { category_id: '4', category_name: 'GERMANY APPLE ⁴ᴷ ³⁸⁴⁰ᴾ' },
    { category_id: '5', category_name: 'FRANCE SÉRIES' },
    { category_id: '6', category_name: 'NETFLIX PODCAST' },
  ]
  const show = (series_id: number, category_id: string, name: string) => ({ series_id, name, category_id, tmdb: '95396' })
  const list = buildShowList(cats, [
    show(1, '3', 'A+ - Severance (2022) (US)'),
    show(2, '2', '4K-A+ - Severance (2022) (US)'),
    show(3, '4', '4K-DE - Severance (2022) (US)'),
    show(4, '5', 'FR - Severance (2022)'),
    { series_id: 5, name: 'EN - World Cup Diaries', category_id: '1' },
    { series_id: 6, name: 'EN - Some Podcast', category_id: '6' },
  ])
  expect(list.movies.map((m) => [m.id, m.name, m.group])).toEqual([
    [2, 'Severance (2022) (US)', 'APPLE+ SERIES'],
    [5, 'World Cup Diaries', 'FIFA+ SERIES'],
    [3, 'Severance (2022) (US)', 'GERMANY APPLE'],
  ])
})
