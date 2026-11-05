import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

const writes = new Map<string, Promise<void>>()

/**
 * Writes [data] (a string as is, anything else as JSON) to [file] atomically (temp + rename), one write at a
 * time per file, so a crash or a second writer never leaves a half-written file. [mode] makes it owner-only (0o600).
 */
export function writeAtomic(file: string, data: unknown, mode?: number): Promise<void> {
  const next = (writes.get(file) ?? Promise.resolve()).catch(() => {}).then(async () => {
    await mkdir(dirname(file), { recursive: true, ...(mode && { mode: 0o700 }) })
    if (mode) await rm(file + '.tmp', { force: true }) // a new file takes the mode
    await writeFile(file + '.tmp', typeof data === 'string' ? data : JSON.stringify(data), { mode })
    await rename(file + '.tmp', file)
  })
  writes.set(file, next)
  const done = () => void (writes.get(file) === next && writes.delete(file))
  next.then(done, done)
  return next
}
