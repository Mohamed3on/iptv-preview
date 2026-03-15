import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import Hls from "hls.js";
import mpegts from "mpegts.js";
import type { Channel, EPGData } from "./types";
import {
  parseM3U,
  parseEPGInWorker,
  findEPGForChannel,
  getCurrentProgram,
  getNextProgram,
  programProgress,
} from "./parsers";
import "./App.css";

const PROXY_BASE = "https://vercel-cors-proxy-nine.vercel.app/api?url=";

function rewriteGithubUrl(url: string): string {
  const m = url.match(/^https?:\/\/github\.com\/([^/]+)\/([^/]+)\/raw\/(.+)$/);
  return m ? `https://raw.githubusercontent.com/${m[1]}/${m[2]}/${m[3]}` : url;
}

async function fetchWithProxy(url: string): Promise<Response> {
  const resolved = rewriteGithubUrl(url);
  const res = await fetch(`${PROXY_BASE}${encodeURIComponent(resolved)}`);
  if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
  return res;
}

async function fetchText(url: string): Promise<string> {
  const res = await fetchWithProxy(url);
  if (url.endsWith(".gz")) {
    const decompressed = res.body!.pipeThrough(new DecompressionStream("gzip"));
    return new Response(decompressed).text();
  }
  return res.text();
}

function readFileAsText(file: File): Promise<string> {
  if (file.name.endsWith(".gz")) {
    const decompressed = file.stream().pipeThrough(new DecompressionStream("gzip"));
    return new Response(decompressed).text();
  }
  return file.text();
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function loadSaved(key: string, fallback: string) {
  try { return localStorage.getItem(key) ?? fallback; } catch { return fallback; }
}

const EMPTY_EPG: EPGData = { programs: {}, aliases: {} };

export default function App() {
  const [m3uUrl, setM3uUrl] = useState(() => loadSaved("iptv_m3u", ""));
  const [epgUrl, setEpgUrl] = useState(() => loadSaved("iptv_epg", ""));
  const [channels, setChannels] = useState<Channel[]>([]);
  const [epg, setEpg] = useState<EPGData>(EMPTY_EPG);
  const [loading, setLoading] = useState(false);
  const [epgLoading, setEpgLoading] = useState(false);
  const [error, setError] = useState("");
  const [epgStatus, setEpgStatus] = useState("");
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [activeChannel, setActiveChannel] = useState<Channel | null>(null);
  const [nowMs, setNowMs] = useState(Date.now());

  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const mpegtsRef = useRef<mpegts.Player | null>(null);
  const m3uFileRef = useRef<HTMLInputElement>(null);
  const epgFileRef = useRef<HTMLInputElement>(null);
  const listParentRef = useRef<HTMLDivElement>(null);

  // persist URLs
  useEffect(() => { try { localStorage.setItem("iptv_m3u", m3uUrl); } catch {} }, [m3uUrl]);
  useEffect(() => { try { localStorage.setItem("iptv_epg", epgUrl); } catch {} }, [epgUrl]);

  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  const applyEPG = useCallback(async (text: string) => {
    const parsed = await parseEPGInWorker(text);
    const count = Object.keys(parsed.programs).length;
    if (count === 0) throw new Error("No EPG data found");
    setEpg(parsed);
    setEpgStatus(`${count} channels loaded`);
  }, []);

  const loadPlaylist = useCallback(async () => {
    if (!m3uUrl.trim()) return;
    setLoading(true);
    setError("");
    try {
      const text = await fetchText(m3uUrl.trim());
      const parsed = parseM3U(text);
      if (parsed.length === 0) throw new Error("No channels found in playlist");
      setChannels(parsed);
      setSelectedGroup(null);
      setActiveChannel(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load playlist");
    } finally {
      setLoading(false);
    }
  }, [m3uUrl]);

  const loadEPG = useCallback(async () => {
    if (!epgUrl.trim()) return;
    setEpgLoading(true);
    setError("");
    try {
      const text = await fetchText(epgUrl.trim());
      await applyEPG(text);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load EPG");
      setEpgStatus("");
    } finally {
      setEpgLoading(false);
    }
  }, [epgUrl, applyEPG]);

  const handleM3uFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setLoading(true);
    setError("");
    try {
      const text = await readFileAsText(file);
      const parsed = parseM3U(text);
      if (parsed.length === 0) throw new Error("No channels found in file");
      setChannels(parsed);
      setSelectedGroup(null);
      setActiveChannel(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to read file");
    } finally {
      setLoading(false);
      e.target.value = "";
    }
  };

  const handleEpgFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setEpgLoading(true);
    setError("");
    try {
      const text = await readFileAsText(file);
      await applyEPG(text);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load EPG file");
      setEpgStatus("");
    } finally {
      setEpgLoading(false);
      e.target.value = "";
    }
  };

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !activeChannel) return;

    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    if (mpegtsRef.current) {
      mpegtsRef.current.destroy();
      mpegtsRef.current = null;
    }

    const url = activeChannel.url;
    const isHLS = url.includes(".m3u8");
    const isTS = url.endsWith(".ts");

    if (isHLS && Hls.isSupported()) {
      const hls = new Hls();
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
      hlsRef.current = hls;
    } else if (isHLS && video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      video.play().catch(() => {});
    } else if (isTS && mpegts.isSupported()) {
      const player = mpegts.createPlayer({
        type: "mpegts",
        isLive: true,
        url,
      });
      player.attachMediaElement(video);
      player.load();
      player.play();
      mpegtsRef.current = player;
    } else {
      video.src = url;
      video.play().catch(() => {});
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
      if (mpegtsRef.current) {
        mpegtsRef.current.destroy();
        mpegtsRef.current = null;
      }
    };
  }, [activeChannel]);

  const groups = useMemo(
    () => [...new Set(channels.map((c) => c.group).filter(Boolean))].sort() as string[],
    [channels]
  );

  const groupCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const c of channels) {
      if (c.group) counts[c.group] = (counts[c.group] || 0) + 1;
    }
    return counts;
  }, [channels]);

  const filtered = useMemo(() => {
    const s = search.toLowerCase();
    return channels.filter((c) => {
      if (selectedGroup && c.group !== selectedGroup) return false;
      if (s && !c.name.toLowerCase().includes(s)) return false;
      return true;
    });
  }, [channels, selectedGroup, search]);

  const virtualizer = useVirtualizer({
    count: filtered.length,
    getScrollElement: () => listParentRef.current,
    estimateSize: () => 52,
    overscan: 20,
  });

  return (
    <div className="app">
      <aside className="sidebar">
        <h1 className="logo">IPTV Preview</h1>

        <div className="input-group">
          <label>M3U Playlist URL or File</label>
          <div className="input-row">
            <input
              type="url"
              placeholder="https://example.com/playlist.m3u"
              value={m3uUrl}
              onChange={(e) => setM3uUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && loadPlaylist()}
            />
            <button onClick={loadPlaylist} disabled={loading}>
              {loading ? "..." : "Load"}
            </button>
            <button
              className="file-btn"
              onClick={() => m3uFileRef.current?.click()}
              disabled={loading}
            >
              File
            </button>
            <input ref={m3uFileRef} type="file" accept=".m3u,.m3u8" onChange={handleM3uFile} hidden />
          </div>
        </div>

        <div className="input-group">
          <label>EPG URL or File (optional)</label>
          <div className="input-row">
            <input
              type="url"
              placeholder="https://example.com/epg.xml"
              value={epgUrl}
              onChange={(e) => setEpgUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && loadEPG()}
            />
            <button onClick={loadEPG} disabled={epgLoading}>
              {epgLoading ? "..." : "Load"}
            </button>
            <button
              className="file-btn"
              onClick={() => epgFileRef.current?.click()}
              disabled={epgLoading}
            >
              File
            </button>
            <input ref={epgFileRef} type="file" accept=".xml,.xml.gz,.gz" onChange={handleEpgFile} hidden />
          </div>
          {epgStatus && <div className="epg-status">{epgStatus}</div>}
        </div>

        {error && <div className="error">{error}</div>}

        {channels.length > 0 && (
          <>
            <input
              type="text"
              placeholder={`Search ${channels.length} channels...`}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="search"
            />

            {groups.length > 0 && (
              <div className="groups">
                <button
                  className={`group-tag ${selectedGroup === null ? "active" : ""}`}
                  onClick={() => setSelectedGroup(null)}
                >
                  All ({channels.length})
                </button>
                {groups.map((g) => (
                  <button
                    key={g}
                    className={`group-tag ${selectedGroup === g ? "active" : ""}`}
                    onClick={() => setSelectedGroup(selectedGroup === g ? null : g)}
                  >
                    {g} ({groupCounts[g]})
                  </button>
                ))}
              </div>
            )}

            <div className="channel-list" ref={listParentRef}>
              <div style={{ height: virtualizer.getTotalSize(), position: "relative" }}>
                {virtualizer.getVirtualItems().map((vItem) => {
                  const ch = filtered[vItem.index];
                  const programs = findEPGForChannel(ch, epg);
                  const current = getCurrentProgram(programs, nowMs);
                  const isActive = activeChannel === ch;

                  return (
                    <button
                      key={vItem.key}
                      data-index={vItem.index}
                      ref={virtualizer.measureElement}
                      className={`channel-item ${isActive ? "active" : ""}`}
                      onClick={() => setActiveChannel(ch)}
                      style={{
                        position: "absolute",
                        top: 0,
                        left: 0,
                        width: "100%",
                        transform: `translateY(${vItem.start}px)`,
                      }}
                    >
                      <div className="ch-logo-wrap">
                        {ch.logo ? (
                          <img
                            src={ch.logo}
                            alt=""
                            className="ch-logo"
                            loading="lazy"
                            onError={(e) => {
                              (e.target as HTMLImageElement).style.display = "none";
                            }}
                          />
                        ) : (
                          <div className="ch-logo-placeholder">TV</div>
                        )}
                      </div>
                      <div className="ch-info">
                        <span className="ch-name">{ch.name}</span>
                        {current && <span className="ch-program">{current.title}</span>}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          </>
        )}
      </aside>

      <main className="main">
        {activeChannel ? (
          <>
            <div className="player-wrap">
              <video ref={videoRef} controls autoPlay muted className="player" />
            </div>
            <div className="now-playing">
              <div className="np-channel">
                {activeChannel.logo && (
                  <img src={activeChannel.logo} alt="" className="np-logo" />
                )}
                <h2>{activeChannel.name}</h2>
                {activeChannel.group && (
                  <span className="np-group">{activeChannel.group}</span>
                )}
              </div>
              {(() => {
                const programs = findEPGForChannel(activeChannel, epg);
                const current = getCurrentProgram(programs, nowMs);
                const next = getNextProgram(programs, nowMs);
                if (!current) return null;
                return (
                  <div className="np-epg">
                    <div className="np-current">
                      <div className="np-label">Now</div>
                      <div className="np-title">{current.title}</div>
                      <div className="np-time">
                        {formatTime(current.start)} - {formatTime(current.stop)}
                      </div>
                      <div className="np-progress-bar">
                        <div
                          className="np-progress"
                          style={{ width: `${programProgress(current, nowMs)}%` }}
                        />
                      </div>
                      {current.description && (
                        <div className="np-desc">{current.description}</div>
                      )}
                    </div>
                    {next && (
                      <div className="np-next">
                        <div className="np-label">Next</div>
                        <div className="np-title">{next.title}</div>
                        <div className="np-time">
                          {formatTime(next.start)} - {formatTime(next.stop)}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })()}
            </div>
          </>
        ) : (
          <div className="empty-state">
            <div className="empty-icon">📡</div>
            <h2>Load a playlist to get started</h2>
            <p>
              Paste an M3U URL in the sidebar and click Load.
              <br />
              Optionally add an EPG URL for program guide info.
            </p>
          </div>
        )}
      </main>
    </div>
  );
}
