import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import Hls from "hls.js";
import mpegts from "mpegts.js";
import {
  Check,
  ChevronRight,
  Copy,
  Loader2,
  Monitor,
  Search,
  Upload,
} from "lucide-react";
import type { Channel, EPGData } from "./types";
import {
  parseM3U,
  parseTvgUrls,
  extractCredentials,
  parseEPGInWorker,
  findEPGForChannel,
  getCurrentProgram,
  getNextProgram,
  programProgress,
} from "./parsers";
import { getCachedEpg, setCachedEpg, epgSourceKey } from "./epgCache";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Separator } from "@/components/ui/separator";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupButton,
} from "@/components/ui/input-group";
import { Toggle } from "@/components/ui/toggle";

const PROXY_BASE = import.meta.env.DEV
  ? "/proxy?url="
  : "/api/proxy?url=";

function rewriteGithubUrl(url: string): string {
  const m = url.match(
    /^https?:\/\/github\.com\/([^/]+)\/([^/]+)\/raw\/(.+)$/
  );
  return m
    ? `https://raw.githubusercontent.com/${m[1]}/${m[2]}/${m[3]}`
    : url;
}

async function fetchWithProxy(url: string): Promise<Response> {
  const resolved = rewriteGithubUrl(url);
  const res = await fetch(`${PROXY_BASE}${encodeURIComponent(resolved)}`);
  if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
  return res;
}

async function fetchText(url: string): Promise<string> {
  const res = await fetchWithProxy(url);
  const buf = new Uint8Array(await res.arrayBuffer());
  // Detect gzip by magic bytes (1f 8b), since EPG URLs often serve gzip
  // without a .gz extension (e.g. myepg.top download links).
  const isGzip = buf[0] === 0x1f && buf[1] === 0x8b;
  if (isGzip) {
    const stream = new Response(buf).body!.pipeThrough(
      new DecompressionStream("gzip")
    );
    return new Response(stream).text();
  }
  return new TextDecoder().decode(buf);
}

function readFileAsText(file: File): Promise<string> {
  if (file.name.endsWith(".gz")) {
    const decompressed = file
      .stream()
      .pipeThrough(new DecompressionStream("gzip"));
    return new Response(decompressed).text();
  }
  return file.text();
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function loadSaved(key: string, fallback: string) {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

const EMPTY_EPG: EPGData = { programs: {}, aliases: {} };
const EPG_TTL_MS = 6 * 60 * 60 * 1000; // refresh the cached guide every 6h

function CredRow({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);

  function copy() {
    navigator.clipboard
      .writeText(value)
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1200);
      })
      .catch(() => {});
  }

  return (
    <div className="flex items-center gap-2 border-b border-border px-2.5 py-1.5 last:border-b-0">
      <span className="w-10 shrink-0 text-[9px] font-semibold uppercase tracking-widest text-muted-foreground">
        {label}
      </span>
      <span className="min-w-0 flex-1 break-all font-mono text-[11px] text-foreground">
        {value}
      </span>
      <button
        type="button"
        onClick={copy}
        title={`Copy ${label}`}
        className="shrink-0 text-muted-foreground transition-colors hover:text-amber"
      >
        {copied ? (
          <Check className="size-3 text-amber" />
        ) : (
          <Copy className="size-3" />
        )}
      </button>
    </div>
  );
}

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
  const [configOpen, setConfigOpen] = useState(true);

  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const mpegtsRef = useRef<mpegts.Player | null>(null);
  const m3uFileRef = useRef<HTMLInputElement>(null);
  const epgFileRef = useRef<HTMLInputElement>(null);
  const listParentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    try {
      localStorage.setItem("iptv_m3u", m3uUrl);
    } catch {}
  }, [m3uUrl]);
  useEffect(() => {
    try {
      localStorage.setItem("iptv_epg", epgUrl);
    } catch {}
  }, [epgUrl]);

  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  // Auto-load saved sources on mount
  const didAutoLoad = useRef(false);
  useEffect(() => {
    if (didAutoLoad.current) return;
    didAutoLoad.current = true;
    if (m3uUrl.trim() || epgUrl.trim()) {
      loadAll();
      setConfigOpen(false);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const applyEPG = useCallback(async (text: string) => {
    const parsed = await parseEPGInWorker(text);
    const count = Object.keys(parsed.programs).length;
    if (count === 0) throw new Error("No EPG data found");
    setEpg(parsed);
    setEpgStatus(`${count} EPG channels`);
  }, []);

  // Load one or more XMLTV URLs and merge them into a single EPG, backed by an
  // IndexedDB cache so subsequent loads are instant (TiviMate-style).
  const loadEpgFrom = useCallback(
    async (urls: string[]) => {
      // Drop only exact-duplicate URLs. Distinct myepg links (same
      // download_file, different key) are DIFFERENT guides — keep them all.
      const seen = new Set<string>();
      const unique = urls.filter((u) => {
        const key = epgSourceKey(u);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
      const cacheKey = unique.map(epgSourceKey).sort().join("|");

      // Serve fresh cache instantly.
      const cached = await getCachedEpg(cacheKey);
      const age = cached ? Date.now() - cached.at : Infinity;
      if (cached && age < EPG_TTL_MS) {
        setEpg(cached.epg);
        setEpgStatus(
          `${Object.keys(cached.epg.programs).length} EPG channels (cached)`
        );
        return;
      }

      // Parse one guide at a time and merge, so peak memory stays bounded even
      // with several large (100MB+) files.
      const merged: EPGData = { programs: {}, aliases: {} };
      for (let i = 0; i < unique.length; i++) {
        setEpgStatus(
          unique.length > 1
            ? `Loading guide… (${i + 1}/${unique.length})`
            : "Loading guide…"
        );
        try {
          const d = await parseEPGInWorker(await fetchText(unique[i]));
          Object.assign(merged.programs, d.programs);
          Object.assign(merged.aliases, d.aliases);
        } catch {
          /* skip a source that fails to fetch/parse */
        }
      }
      const count = Object.keys(merged.programs).length;
      if (count === 0) {
        // Fall back to a stale cache rather than showing nothing.
        if (cached) {
          setEpg(cached.epg);
          setEpgStatus(
            `${Object.keys(cached.epg.programs).length} EPG channels (cached)`
          );
          return;
        }
        throw new Error("No EPG data found");
      }
      setEpg(merged);
      setEpgStatus(`${count} EPG channels`);
      void setCachedEpg(cacheKey, { at: Date.now(), epg: merged });
    },
    []
  );

  const loadPlaylist = useCallback(async () => {
    if (!m3uUrl.trim()) return;
    setLoading(true);
    setError("");
    try {
      const text = await fetchText(m3uUrl.trim());
      const parsed = parseM3U(text);
      if (parsed.length === 0)
        throw new Error("No channels found in playlist");
      setChannels(parsed);
      setSelectedGroup(null);
      setActiveChannel(null);
      // Auto-load EPG baked into the playlist header unless a manual EPG is set.
      if (!epgUrl.trim()) {
        const tvgUrls = parseTvgUrls(text);
        if (tvgUrls.length > 0) {
          setEpgLoading(true);
          loadEpgFrom(tvgUrls)
            .catch(() => setEpgStatus(""))
            .finally(() => setEpgLoading(false));
        }
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load playlist");
    } finally {
      setLoading(false);
    }
  }, [m3uUrl, epgUrl, loadEpgFrom]);

  const loadEPG = useCallback(async () => {
    if (!epgUrl.trim()) return;
    setEpgLoading(true);
    setError("");
    try {
      await loadEpgFrom([epgUrl.trim()]);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load EPG");
      setEpgStatus("");
    } finally {
      setEpgLoading(false);
    }
  }, [epgUrl, loadEpgFrom]);

  const loadAll = useCallback(() => {
    loadPlaylist();
    loadEPG();
  }, [loadPlaylist, loadEPG]);

  const handleM3uFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setLoading(true);
    setError("");
    try {
      const text = await readFileAsText(file);
      const parsed = parseM3U(text);
      if (parsed.length === 0)
        throw new Error("No channels found in file");
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
      setError(
        e instanceof Error ? e.message : "Failed to load EPG file"
      );
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
    } else if (
      isHLS &&
      video.canPlayType("application/vnd.apple.mpegurl")
    ) {
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

  const credentials = useMemo(
    () => extractCredentials(m3uUrl, channels),
    [m3uUrl, channels]
  );

  const groups = useMemo(
    () =>
      [
        ...new Set(channels.map((c) => c.group).filter(Boolean)),
      ].sort() as string[],
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
      if (!s) return true;
      if (c.name.toLowerCase().includes(s)) return true;
      if (c.group?.toLowerCase().includes(s)) return true;
      // Also match what's on now / up next in the guide.
      const programs = findEPGForChannel(c, epg);
      if (programs) {
        for (const p of [
          getCurrentProgram(programs, nowMs),
          getNextProgram(programs, nowMs),
        ]) {
          if (!p) continue;
          if (p.title.toLowerCase().includes(s)) return true;
          if (p.description?.toLowerCase().includes(s)) return true;
        }
      }
      return false;
    });
  }, [channels, selectedGroup, search, epg, nowMs]);

  const virtualizer = useVirtualizer({
    count: filtered.length,
    getScrollElement: () => listParentRef.current,
    estimateSize: () => 56,
    overscan: 20,
  });

  const isLoading = loading || epgLoading;

  return (
    <div className="flex h-dvh overflow-hidden">
      {/* ── Sidebar ── */}
      <aside className="flex w-[360px] min-w-[360px] flex-col overflow-hidden border-r border-border bg-surface-1 max-md:w-full max-md:min-w-0 max-md:max-h-[45dvh] max-md:border-r-0 max-md:border-b">
        {/* Header */}
        <div className="flex items-baseline gap-3 px-5 pt-5 pb-0">
          <h1 className="text-[15px] font-bold tracking-tight">
            <span className="text-amber">IPTV</span>{" "}
            <span className="text-foreground">Preview</span>
          </h1>
          {channels.length > 0 && (
            <Badge variant="secondary" className="font-mono text-[10px] text-muted-foreground">
              {channels.length.toLocaleString()} ch
            </Badge>
          )}
        </div>

        {/* Sources config */}
        <Collapsible
          open={configOpen}
          onOpenChange={setConfigOpen}
          className="border-b border-border px-5 py-3"
        >
          <CollapsibleTrigger className="flex w-full items-center gap-2 text-xs font-medium tracking-wide text-muted-foreground uppercase hover:text-foreground transition-colors">
            <ChevronRight
              className={cn(
                "size-3 transition-transform duration-200",
                configOpen && "rotate-90"
              )}
            />
            Sources
          </CollapsibleTrigger>
          <CollapsibleContent className="mt-3 flex flex-col gap-3">
            {/* Playlist field */}
            <div className="space-y-1.5">
              <label className="text-[10px] font-semibold tracking-widest text-muted-foreground uppercase">
                Playlist
              </label>
              <InputGroup>
                <InputGroupInput
                  type="url"
                  placeholder="M3U URL"
                  value={m3uUrl}
                  onChange={(e) => setM3uUrl(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && loadAll()}
                  className="font-mono text-xs"
                />
                <InputGroupAddon align="inline-end">
                  <InputGroupButton
                    onClick={() => m3uFileRef.current?.click()}
                    disabled={loading}
                  >
                    <Upload className="size-3" />
                  </InputGroupButton>
                </InputGroupAddon>
              </InputGroup>
              <input
                ref={m3uFileRef}
                type="file"
                accept=".m3u,.m3u8"
                onChange={handleM3uFile}
                hidden
              />
            </div>

            {/* Connection (debug) */}
            {credentials && (
              <div className="space-y-1.5">
                <label className="text-[10px] font-semibold tracking-widest text-muted-foreground uppercase">
                  Connection
                </label>
                <div className="overflow-hidden rounded-md border border-border bg-surface-2">
                  <CredRow label="Host" value={credentials.host} />
                  <CredRow label="User" value={credentials.username} />
                  <CredRow label="Pass" value={credentials.password} />
                </div>
              </div>
            )}

            {/* EPG field */}
            <div className="space-y-1.5">
              <label className="text-[10px] font-semibold tracking-widest text-muted-foreground uppercase">
                EPG Guide
              </label>
              <InputGroup>
                <InputGroupInput
                  type="url"
                  placeholder="XMLTV URL (.xml, .xml.gz)"
                  value={epgUrl}
                  onChange={(e) => setEpgUrl(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && loadAll()}
                  className="font-mono text-xs"
                />
                <InputGroupAddon align="inline-end">
                  <InputGroupButton
                    onClick={() => epgFileRef.current?.click()}
                    disabled={epgLoading}
                  >
                    <Upload className="size-3" />
                  </InputGroupButton>
                </InputGroupAddon>
              </InputGroup>
              <input
                ref={epgFileRef}
                type="file"
                accept=".xml,.xml.gz,.gz"
                onChange={handleEpgFile}
                hidden
              />
              {epgStatus && (
                <p className="font-mono text-[10px] tracking-wide text-amber-dim">
                  {epgStatus}
                </p>
              )}
            </div>

            <Button
              onClick={loadAll}
              disabled={isLoading}
              className="w-full"
            >
              {isLoading && <Loader2 className="size-3.5 animate-spin" />}
              {isLoading ? "Loading..." : "Load Sources"}
            </Button>
          </CollapsibleContent>
        </Collapsible>

        {/* Error */}
        {error && (
          <div className="mx-5 mt-2 rounded-md border-l-2 border-live bg-live-glow px-3 py-2 text-[11px] text-live">
            {error}
          </div>
        )}

        {/* Search + Filters + Channel List */}
        {channels.length > 0 && (
          <>
            {/* Search */}
            <div className="px-5 pt-3">
              <InputGroup>
                <InputGroupAddon align="inline-start">
                  <Search className="size-3.5" />
                </InputGroupAddon>
                <InputGroupInput
                  type="text"
                  placeholder={
                    Object.keys(epg.programs).length > 0
                      ? `Search ${filtered.length.toLocaleString()} channels or what's on…`
                      : `Search ${filtered.length.toLocaleString()} channels...`
                  }
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  className="text-[13px]"
                />
              </InputGroup>
              {(epgLoading || epgStatus) && (
                <p className="mt-1.5 flex items-center gap-1.5 font-mono text-[10px] tracking-wide text-amber-dim">
                  {epgLoading && <Loader2 className="size-3 animate-spin" />}
                  {epgLoading ? "Loading guide…" : epgStatus}
                </p>
              )}
            </div>

            {/* Group tags */}
            {groups.length > 0 && (
              <div className="flex shrink-0 gap-1 overflow-x-auto px-5 py-2 [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]">
                <Toggle
                  size="sm"
                  pressed={selectedGroup === null}
                  onPressedChange={() => setSelectedGroup(null)}
                  className="shrink-0 text-[11px] data-[state=on]:bg-amber-glow data-[state=on]:text-amber"
                >
                  All
                </Toggle>
                {groups.map((g) => (
                  <Toggle
                    key={g}
                    size="sm"
                    pressed={selectedGroup === g}
                    onPressedChange={() =>
                      setSelectedGroup(selectedGroup === g ? null : g)
                    }
                    className="shrink-0 text-[11px] whitespace-nowrap data-[state=on]:bg-amber-glow data-[state=on]:text-amber"
                  >
                    {g} ({groupCounts[g]})
                  </Toggle>
                ))}
              </div>
            )}

            {/* Channel list (virtualized) */}
            <div
              ref={listParentRef}
              className="flex-1 overflow-y-auto"
              style={{
                scrollbarWidth: "thin",
                scrollbarColor: "var(--surface-4) transparent",
              }}
            >
              <div
                style={{
                  height: virtualizer.getTotalSize(),
                  position: "relative",
                }}
              >
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
                      onClick={() => setActiveChannel(ch)}
                      className={cn(
                        "absolute top-0 left-0 flex w-full items-center gap-3 border-l-2 border-transparent px-5 py-2.5 text-left transition-colors",
                        isActive
                          ? "border-l-amber bg-amber-glow"
                          : "hover:bg-surface-2"
                      )}
                      style={{
                        transform: `translateY(${vItem.start}px)`,
                      }}
                    >
                      <div className="flex size-8 shrink-0 items-center justify-center overflow-hidden rounded-md bg-surface-3">
                        {ch.logo ? (
                          <img
                            src={ch.logo}
                            alt=""
                            className="size-full object-contain"
                            loading="lazy"
                            onError={(e) => {
                              (
                                e.target as HTMLImageElement
                              ).style.display = "none";
                            }}
                          />
                        ) : (
                          <span className="text-[9px] font-bold text-muted-foreground tracking-wide">
                            TV
                          </span>
                        )}
                      </div>
                      <div className="flex min-w-0 flex-col gap-px">
                        <span className="truncate text-[13px] font-medium text-foreground">
                          {ch.name}
                        </span>
                        {current && (
                          <span
                            className={cn(
                              "truncate text-[11px]",
                              isActive
                                ? "text-amber-dim"
                                : "text-muted-foreground"
                            )}
                          >
                            {current.title}
                          </span>
                        )}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          </>
        )}
      </aside>

      {/* ── Main content ── */}
      <main className="flex flex-1 flex-col overflow-hidden bg-surface-0 max-md:min-h-0">
        {activeChannel ? (
          <>
            {/* Video player */}
            <div className="flex flex-1 items-center justify-center bg-[oklch(0.06_0_0)] min-h-0">
              <video
                ref={videoRef}
                controls
                autoPlay
                muted
                className="size-full outline-none bg-[oklch(0.06_0_0)]"
              />
            </div>

            {/* Now playing bar */}
            <div className="border-t border-border bg-surface-1 px-6 py-4">
              {/* Channel info */}
              <div className="flex items-center gap-3">
                {activeChannel.logo && (
                  <img
                    src={activeChannel.logo}
                    alt=""
                    className="size-6 rounded object-contain"
                  />
                )}
                <span className="text-sm font-semibold text-foreground">
                  {activeChannel.name}
                </span>
                {activeChannel.group && (
                  <Badge
                    variant="secondary"
                    className="text-[10px] uppercase tracking-wide"
                  >
                    {activeChannel.group}
                  </Badge>
                )}
                <span className="ml-auto flex items-center gap-1.5 font-mono text-[9px] font-bold uppercase tracking-widest text-live">
                  <span
                    className="inline-block size-1.5 rounded-full bg-live"
                    style={{ animation: "pulse-live 2s ease-in-out infinite" }}
                  />
                  Live
                </span>
              </div>

              {/* EPG info */}
              {(() => {
                const programs = findEPGForChannel(activeChannel, epg);
                const current = getCurrentProgram(programs, nowMs);
                const next = getNextProgram(programs, nowMs);
                if (!current) return null;
                return (
                  <>
                    <Separator className="my-3 bg-border" />
                    <div className="flex gap-8 max-md:flex-col max-md:gap-3">
                      {/* Current program */}
                      <div className="flex-1 min-w-0 space-y-1.5">
                        <p className="font-mono text-[9px] font-medium uppercase tracking-[1.5px] text-amber">
                          Now
                        </p>
                        <p className="truncate text-sm font-semibold text-foreground">
                          {current.title}
                        </p>
                        <p className="font-mono text-[11px] text-muted-foreground">
                          {formatTime(current.start)} —{" "}
                          {formatTime(current.stop)}
                        </p>
                        <Progress
                          value={programProgress(current, nowMs)}
                          className="h-0.5 bg-surface-3 [&>[data-slot=progress-indicator]]:bg-amber"
                        />
                        {current.description && (
                          <p className="line-clamp-2 text-xs leading-relaxed text-muted-foreground">
                            {current.description}
                          </p>
                        )}
                      </div>

                      {/* Next program */}
                      {next && (
                        <div className="flex-1 min-w-0 space-y-1.5">
                          <p className="font-mono text-[9px] font-medium uppercase tracking-[1.5px] text-muted-foreground">
                            Next
                          </p>
                          <p className="truncate text-sm text-secondary-foreground">
                            {next.title}
                          </p>
                          <p className="font-mono text-[11px] text-muted-foreground">
                            {formatTime(next.start)} —{" "}
                            {formatTime(next.stop)}
                          </p>
                        </div>
                      )}
                    </div>
                  </>
                );
              })()}
            </div>
          </>
        ) : (
          /* Empty state */
          <div className="flex flex-1 flex-col items-center justify-center gap-4 p-10">
            <div className="flex size-16 items-center justify-center rounded-full border border-border">
              <Monitor className="size-6 text-muted-foreground opacity-40" />
            </div>
            <h2 className="text-base font-medium text-secondary-foreground">
              No channel selected
            </h2>
            <p className="max-w-[320px] text-center text-[13px] leading-relaxed text-muted-foreground">
              Load a playlist from the sidebar, then select a channel to
              start watching.
            </p>
            <Badge
              variant="secondary"
              className="mt-2 font-mono text-[11px] text-muted-foreground"
            >
              M3U / XMLTV EPG / HLS / MPEG-TS
            </Badge>
          </div>
        )}
      </main>
    </div>
  );
}
