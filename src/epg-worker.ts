// Web Worker: regex-based XMLTV parser — no DOM, no main-thread blocking

interface SerializedProgram {
  channel: string;
  title: string;
  description?: string;
  start: number; // epoch ms
  stop: number;
}

interface WorkerResult {
  programs: Record<string, SerializedProgram[]>;
  aliases: Record<string, string>;
}

function parseXMLTVDate(s: string): number {
  if (!s || s.length < 14) return 0;
  const year = +s.slice(0, 4);
  const month = +s.slice(4, 6) - 1;
  const day = +s.slice(6, 8);
  const hour = +s.slice(8, 10);
  const min = +s.slice(10, 12);
  const sec = +s.slice(12, 14);

  const tzPart = s.slice(14).trim();
  if (tzPart) {
    const sign = tzPart[0] === "-" ? -1 : 1;
    const tzH = +tzPart.slice(1, 3) || 0;
    const tzM = +tzPart.slice(3, 5) || 0;
    const utcMs = Date.UTC(year, month, day, hour, min, sec);
    return utcMs - sign * (tzH * 3600000 + tzM * 60000);
  }
  return Date.UTC(year, month, day, hour, min, sec);
}

function decodeXMLEntities(s: string): string {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'");
}

self.onmessage = (e: MessageEvent<string>) => {
  const xml = e.data;
  const programs: Record<string, SerializedProgram[]> = {};
  const aliases: Record<string, string> = {};

  // parse <channel> elements for alias map
  const channelRe = /<channel\s+id="([^"]*)"[^>]*>([\s\S]*?)<\/channel>/g;
  const displayNameRe = /<display-name[^>]*>([^<]*)<\/display-name>/g;
  let cm;
  while ((cm = channelRe.exec(xml)) !== null) {
    const id = decodeXMLEntities(cm[1]);
    aliases[id.toLowerCase()] = id;
    let nm;
    displayNameRe.lastIndex = 0;
    while ((nm = displayNameRe.exec(cm[2])) !== null) {
      const name = decodeXMLEntities(nm[1]).trim();
      if (name) aliases[name.toLowerCase()] = id;
    }
  }

  // parse <programme> elements
  const progRe =
    /<programme\s+start="([^"]*)"\s+stop="([^"]*)"\s+channel="([^"]*)"[^>]*>([\s\S]*?)<\/programme>/g;
  const titleRe = /<title[^>]*>([^<]*)<\/title>/;
  const descRe = /<desc[^>]*>([^<]*)<\/desc>/;
  let pm;
  while ((pm = progRe.exec(xml)) !== null) {
    const start = parseXMLTVDate(pm[1]);
    const stop = parseXMLTVDate(pm[2]);
    if (!start || !stop) continue;

    const channelId = decodeXMLEntities(pm[3]);
    const body = pm[4];
    const titleMatch = titleRe.exec(body);
    const title = titleMatch ? decodeXMLEntities(titleMatch[1]) : "";
    const descMatch = descRe.exec(body);
    const desc = descMatch ? decodeXMLEntities(descMatch[1]) : undefined;

    if (!programs[channelId]) programs[channelId] = [];
    programs[channelId].push({ channel: channelId, title, description: desc, start, stop });
  }

  // sort each channel's programs
  for (const id of Object.keys(programs)) {
    programs[id].sort((a, b) => a.start - b.start);
  }

  self.postMessage({ programs, aliases } satisfies WorkerResult);
};
