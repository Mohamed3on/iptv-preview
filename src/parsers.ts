import type { Channel, EPGData, Program } from "./types";

export function parseM3U(text: string): Channel[] {
  const lines = text.split("\n");
  const channels: Channel[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith("#EXTINF:")) continue;

    const url = lines[i + 1]?.trim();
    if (!url || url.startsWith("#")) continue;

    const name = line.split(",").pop()?.trim() || "Unknown";
    const logo = attr(line, "tvg-logo") || attr(line, "logo") || undefined;
    const group = attr(line, "group-title") || undefined;
    const tvgId = attr(line, "tvg-id") || undefined;
    const tvgName = attr(line, "tvg-name") || undefined;

    channels.push({ name, url, logo, group, tvgId, tvgName });
  }

  return channels;
}

function attr(line: string, key: string): string | null {
  const re = new RegExp(`${key}="([^"]*)"`, "i");
  const m = line.match(re);
  return m ? m[1] : null;
}

export function parseEPGInWorker(xml: string): Promise<EPGData> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(
      new URL("./epg-worker.ts", import.meta.url),
      { type: "module" }
    );
    worker.onmessage = (e: MessageEvent<EPGData>) => {
      resolve(e.data);
      worker.terminate();
    };
    worker.onerror = (e) => {
      reject(new Error(e.message));
      worker.terminate();
    };
    worker.postMessage(xml);
  });
}

/** Look up EPG programs for a channel, trying tvg-id, tvg-name, and channel name */
export function findEPGForChannel(
  ch: Channel,
  epg: EPGData
): Program[] | undefined {
  const p = epg.programs;
  if (!p) return undefined;

  for (const key of [ch.tvgId, ch.tvgName, ch.name]) {
    if (!key) continue;
    if (p[key]) return p[key];
    const canonical = epg.aliases[key.toLowerCase()];
    if (canonical && p[canonical]) return p[canonical];
  }
  return undefined;
}

export function getCurrentProgram(
  programs: Program[] | undefined,
  nowMs: number
): Program | undefined {
  if (!programs) return undefined;
  // binary search since programs are sorted
  let lo = 0, hi = programs.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const p = programs[mid];
    if (p.stop <= nowMs) lo = mid + 1;
    else if (p.start > nowMs) hi = mid - 1;
    else return p;
  }
  return undefined;
}

export function getNextProgram(
  programs: Program[] | undefined,
  nowMs: number
): Program | undefined {
  if (!programs) return undefined;
  const current = getCurrentProgram(programs, nowMs);
  if (!current) {
    // find first future program
    for (const p of programs) {
      if (p.start > nowMs) return p;
    }
    return undefined;
  }
  // find first program starting at or after current.stop
  for (const p of programs) {
    if (p.start >= current.stop) return p;
  }
  return undefined;
}

export function programProgress(p: Program, nowMs: number): number {
  const total = p.stop - p.start;
  const elapsed = nowMs - p.start;
  return Math.min(100, Math.max(0, (elapsed / total) * 100));
}
