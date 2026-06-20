// Tiny IndexedDB cache for parsed EPG data, so reloads are instant instead of
// re-fetching + re-parsing the (large) XMLTV guide every time.
import type { EPGData } from "./types";

const DB_NAME = "iptv-preview";
const STORE = "epg";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export interface CachedEpg {
  at: number;
  epg: EPGData;
}

/** Stable cache key for a set of EPG sources (ignores rotating auth params). */
export function epgSourceKey(url: string): string {
  try {
    const u = new URL(url);
    // Keep ALL params (incl. `key`): myepg links sharing a download_file but
    // differing by key are DIFFERENT guides, so they must not be deduped.
    u.searchParams.sort();
    return u.origin + u.pathname + "?" + u.searchParams.toString();
  } catch {
    return url;
  }
}

export async function getCachedEpg(key: string): Promise<CachedEpg | null> {
  try {
    const db = await openDb();
    return await new Promise((resolve) => {
      const req = db.transaction(STORE, "readonly").objectStore(STORE).get(key);
      req.onsuccess = () => resolve((req.result as CachedEpg) ?? null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function setCachedEpg(key: string, value: CachedEpg): Promise<void> {
  try {
    const db = await openDb();
    await new Promise<void>((resolve) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  } catch {
    /* cache is best-effort */
  }
}
