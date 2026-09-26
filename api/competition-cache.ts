// Channels seen airing LIVE matches of each competition in the EPG:
//   tvg-id -> { competition key (see COMPS in _lib.ts) -> last seen, YYYY-MM-DD }
// Written by scripts/learn-competitions.ts (daily GitHub Action); the playlist
// trusts an entry for 60 days, so a channel stays listed between cup rounds.
export const LEARNED: Record<string, Record<string, string>> = {
 "AbuDhabiSports1PremiumAr.ae": {
  "seriea": "2026-09-25"
 },
 "BeinSports1.fr": {
  "ligue1": "2026-09-25"
 },
 "BeinSports2.fr": {
  "laliga": "2026-09-22",
  "pl": "2026-09-22"
 },
 "DAZN1.de": {
  "bundes": "2026-09-25"
 },
 "DAZN1.es": {
  "bundes": "2026-09-25",
  "laliga": "2026-09-23",
  "seriea": "2026-09-20"
 },
 "DAZN2.de": {
  "bundes": "2026-09-25",
  "ligue1": "2026-09-20"
 },
 "DAZN2.es": {
  "bundes": "2026-09-25",
  "laliga": "2026-09-26",
  "ligue1": "2026-09-24"
 },
 "DAZN3.es": {
  "pl": "2026-09-20"
 },
 "MUTV.uk": {
  "ucl": "2026-09-24"
 },
 "PremierSports1.ie": {
  "laliga": "2026-09-20"
 },
 "PremierSports1.uk": {
  "laliga": "2026-09-21"
 },
 "PremierSports2.uk": {
  "laliga": "2026-09-25"
 },
 "SkyBundesligaUHD.de": {
  "bundes": "2026-09-26"
 },
 "SkySport1.de": {
  "uel": "2026-09-17"
 },
 "SkySport2.de": {
  "uel": "2026-09-17"
 },
 "SkySport3.de": {
  "uel": "2026-09-17"
 },
 "SkySport4.de": {
  "uel": "2026-09-17"
 },
 "SkySport6.de": {
  "pl": "2026-09-20"
 },
 "SkySport7.de": {
  "pl": "2026-09-20"
 },
 "SkySport8.de": {
  "pl": "2026-09-19"
 },
 "SkySportUHD.de": {
  "pokal": "2026-09-27"
 },
 "SkySportsMix.uk": {
  "pokal": "2026-09-26",
  "uel": "2026-09-17"
 },
 "TNTSport1.uk": {
  "seriea": "2026-09-22",
  "uel": "2026-09-17"
 },
 "VSportUltraHD.no": {
  "pl": "2026-09-25"
 },
 "Vamos.es": {
  "pl": "2026-09-27"
 },
 "beINSports1En.qa": {
  "laliga": "2026-09-24",
  "ligue1": "2026-09-23",
  "pl": "2026-09-26",
  "uel": "2026-09-18"
 },
 "beINSports1Fr.qa": {
  "ligue1": "2026-09-25"
 },
 "beINSports2.qa": {
  "laliga": "2026-09-22",
  "pl": "2026-09-22"
 },
 "beINSports2En.qa": {
  "eflcup": "2026-09-17",
  "laliga": "2026-09-21",
  "pl": "2026-09-21"
 },
 "beINSports3En.qa": {
  "laliga": "2026-09-20",
  "ligue1": "2026-09-20",
  "pl": "2026-09-20",
  "uel": "2026-09-18"
 },
 "beINSports4En.qa": {
  "eflcup": "2026-09-17",
  "laliga": "2026-09-20",
  "ligue1": "2026-09-20",
  "pl": "2026-09-20",
  "uel": "2026-09-18"
 },
 "beINSports5En.qa": {
  "eflcup": "2026-09-16",
  "laliga": "2026-09-21",
  "ligue1": "2026-09-20",
  "pl": "2026-09-20",
  "uel": "2026-09-18"
 },
 "beINSports6En.qa": {
  "eflcup": "2026-09-17",
  "laliga": "2026-09-18",
  "ligue1": "2026-09-23",
  "pl": "2026-09-23"
 },
 "beINSports7En.qa": {
  "laliga": "2026-09-17",
  "uel": "2026-09-19"
 },
 "beINSports8En.qa": {
  "laliga": "2026-09-19",
  "uel": "2026-09-18"
 },
 "beINSports9En.qa": {
  "laliga": "2026-09-20",
  "uel": "2026-09-17"
 },
 "beINSportsXtra1.qa": {
  "laliga": "2026-09-16",
  "uel": "2026-09-17"
 },
 "beINSportsXtra2.qa": {
  "uel": "2026-09-16"
 },
 "beINSportsXtra3.qa": {
  "uel": "2026-09-16"
 },
 "dazn1.de": {
  "bundes": "2026-09-25"
 },
 "dazn2.de": {
  "bundes": "2026-09-25",
  "ligue1": "2026-09-20"
 },
 "daznlaliga.es": {
  "laliga": "2026-09-21"
 },
 "mligadecampeones.es": {
  "uel": "2026-09-24"
 },
 "mligadecampeones1.es": {
  "pl": "2026-09-21",
  "seriea": "2026-09-20",
  "ucl": "2026-09-26",
  "uel": "2026-09-27"
 },
 "skysport6.de": {
  "pl": "2026-09-20"
 },
 "skysport7.de": {
  "pl": "2026-09-20"
 },
 "skysportmix.de": {
  "pokal": "2026-09-21"
 },
 "skysports1uhd.uk": {
  "champ": "2026-09-19",
  "pl": "2026-09-20"
 },
 "skysportsfootball.uk": {
  "eflcup": "2026-09-21"
 },
 "skysportsmix.uk": {
  "eflcup": "2026-09-17"
 },
 "skysportsnews.uk": {
  "pl": "2026-09-19"
 },
 "sportdigital.de": {
  "eflcup": "2026-09-27"
 },
 "sx.1290226": {
  "laliga": "2026-09-27"
 },
 "sx.1467810": {
  "bundes": "2026-09-25",
  "ligue1": "2026-09-20"
 },
 "sx.1467811": {
  "bundes": "2026-09-25"
 },
 "sx.1467814": {
  "bundes": "2026-09-25",
  "ligue1": "2026-09-20"
 },
 "sx.1467816": {
  "bundes": "2026-09-20",
  "ligue1": "2026-09-20"
 },
 "sx.1467817": {
  "bundes": "2026-09-25"
 },
 "sx.1544251": {
  "laliga": "2026-09-23",
  "seriea": "2026-09-24"
 },
 "sx.1544301": {
  "ucl": "2026-09-23"
 },
 "sx.1556138": {
  "ligue1": "2026-09-27",
  "pl": "2026-09-27"
 },
 "sx.1683720": {
  "seriea": "2026-09-20"
 },
 "sx.285259": {
  "ucl": "2026-09-21"
 },
 "sx.351704": {
  "uel": "2026-09-27"
 },
 "sx.351705": {
  "uel": "2026-09-27"
 },
 "sx.351708": {
  "seriea": "2026-09-27"
 },
 "sx.351709": {
  "seriea": "2026-09-27"
 },
 "sx.351711": {
  "uel": "2026-09-27"
 },
 "sx.351713": {
  "seriea": "2026-09-21"
 },
 "sx.479814": {
  "bundes": "2026-09-26"
 },
 "sx.479818": {
  "eflcup": "2026-09-18"
 },
 "sx.479820": {
  "eflcup": "2026-09-18",
  "pl": "2026-09-26"
 },
 "sx.479821": {
  "pokal": "2026-09-21"
 },
 "sx.479823": {
  "pokal": "2026-09-26"
 },
 "sx.486675": {
  "eflcup": "2026-09-18"
 },
 "sx.486676": {
  "eflcup": "2026-09-18"
 },
 "sx.588045": {
  "pl": "2026-09-27"
 },
 "sx.621642": {
  "laliga": "2026-09-20"
 },
 "sx.785949": {
  "eflcup": "2026-09-24",
  "ligue1": "2026-09-23"
 },
 "sx.839971": {
  "laliga": "2026-09-20"
 },
 "sx.839972": {
  "laliga": "2026-09-25"
 },
 "sx.985090": {
  "eflcup": "2026-09-17",
  "laliga": "2026-09-19",
  "ligue1": "2026-09-20",
  "pl": "2026-09-21"
 },
 "sx.985104": {
  "ligue1": "2026-09-24"
 },
 "tntsports1.uk": {
  "seriea": "2026-09-22",
  "uel": "2026-09-17"
 },
 "tntsports4.uk": {
  "seriea": "2026-09-23",
  "uel": "2026-09-18"
 }
}
