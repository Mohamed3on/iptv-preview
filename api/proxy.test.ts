import { expect, test } from 'bun:test'
import proxy from './proxy.js'

test('a spoofed X-Forwarded-Host does not allowlist an arbitrary destination', async () => {
  const req = new Request('http://localhost/api/proxy?url=' + encodeURIComponent('https://example.com/'), {
    headers: { host: 'localhost', 'x-forwarded-host': 'example.com', 'sec-fetch-site': 'same-origin' },
  })
  const res = await proxy(req)
  expect(res.status).toBe(403)
  expect(await res.text()).toBe('Destination not allowed')
})
