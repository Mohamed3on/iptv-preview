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

/** EPG URLs baked into the `#EXTM3U` header (`url-tvg` / `x-tvg-url`). */
export function parseTvgUrls(text: string): string[] {
  const header = text.split("\n", 1)[0] ?? "";
  const raw = attr(header, "url-tvg") || attr(header, "x-tvg-url");
  if (!raw) return [];
  return raw
    .split(",")
    .map((u) => u.trim())
    .filter(Boolean);
}

export interface M3UCredentials {
  host: string;
  username: string;
  password: string;
}

/** Extract Xtream-Codes host/username/password from an M3U URL or its stream URLs. */
export function extractCredentials(
  m3uUrl: string,
  channels: Channel[]
): M3UCredentials | undefined {
  const fromQuery = credsFromQuery(m3uUrl);
  if (fromQuery) return fromQuery;

  // Fallback for uploaded files: trust path-derived creds only when several
  // channels agree — every Xtream entry shares the same username/password.
  const tally = new Map<string, { creds: M3UCredentials; count: number }>();
  for (const ch of channels.slice(0, 50)) {
    const creds = credsFromPath(ch.url);
    if (!creds) continue;
    const key = `${creds.host}/${creds.username}/${creds.password}`;
    const seen = tally.get(key);
    if (seen) seen.count++;
    else tally.set(key, { creds, count: 1 });
  }
  for (const { creds, count } of tally.values()) {
    if (count >= 3) return creds;
  }
  return undefined;
}

/** get.php-style URL with ?username=…&password=… query params */
function credsFromQuery(raw: string): M3UCredentials | undefined {
  try {
    const u = new URL(raw);
    const username = u.searchParams.get("username");
    const password = u.searchParams.get("password");
    if (username && password) return { host: u.host, username, password };
  } catch {
    /* not a valid URL */
  }
  return undefined;
}

/** Xtream stream URL: …/[live|movie|series/]USER/PASS/STREAM_ID[.ext] */
function credsFromPath(raw: string): M3UCredentials | undefined {
  try {
    const u = new URL(raw);
    const segs = u.pathname.split("/").filter(Boolean);
    if (segs.length < 3) return undefined;
    const streamId = segs[segs.length - 1].replace(/\.[a-z0-9]+$/i, "");
    if (!/^\d+$/.test(streamId)) return undefined; // not an Xtream stream id
    return {
      host: u.host,
      username: segs[segs.length - 3],
      password: segs[segs.length - 2],
    };
  } catch {
    /* not a valid URL */
  }
  return undefined;
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
