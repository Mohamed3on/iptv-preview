// Guide channels Jev matched to ours where none of fetchMyepgGuide's name rules reach (untagged
// feeds, other spellings): myepg channel id -> our tvg-ids. Kept only when Jev picked the
// candidate (≥0.8) AND confirmed it in a separate yes/no question (≥0.8) — wrong EPG is worse
// than none. Written by scripts/match-epg-channels.ts.
export const EPG_MATCHES: Record<string, string[]> = {
 "Sky.Sport.Bundesliga.10.de": [
  "SkyBundesliga10.de"
 ],
 "Sky.Sport.Bundesliga.3.de": [
  "SkyBundesliga3.de"
 ],
 "Sky.Sport.Bundesliga.4.de": [
  "SkyBundesliga4.de"
 ],
 "Sky.Sport.Bundesliga.9.de": [
  "SkyBundesliga9.de"
 ],
 "alkassfive.qa": [
  "sx.1027206"
 ],
 "alkassone.qa": [
  "sx.1302081"
 ],
 "alkasstwo.qa": [
  "sx.1302080"
 ],
 "dazn2.de": [
  "sx.1467815"
 ],
 "skysportaustria3.at": [
  "sx.835809"
 ],
 "tennischannel.us": [
  "sx.686562"
 ]
}
