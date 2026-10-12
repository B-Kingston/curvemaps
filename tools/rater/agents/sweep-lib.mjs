// The pure parts of the agent sweep (sweep.mjs): one place per grid cell, a balanced sample of trips between them,
// a suspicion score from the checks the rater already ran, and which plans go to the agents. Tested by sweep.test.mjs.

export const STYLES = ["Fastest", "Mostly fast", "Balanced", "Curvy", "Twisty", "Very twisty", "Max twisty"];

/** The rater's plan switches (rider settings), how people call them, and the ones a random setting varies. */
export const SWITCHES = ["avoidMotorways", "elevation", "stopsAndTowns", "blockUnpaved"];
export const RANDOM_SWITCHES = ["avoidMotorways", "elevation", "stopsAndTowns"];
export const SETTING_NAMES = { avoidMotorways: "avoid motorways", elevation: "hills", stopsAndTowns: "stops & towns", blockUnpaved: "block unpaved" };

/**
 * The switches a setting spec turns on: "random" draws each of RANDOM_SWITCHES with [rand], "none" is none, and a
 * comma-separated list is those switches. Throws on an unknown switch.
 */
export function settingsFrom(spec, rand) {
  if (spec === "random") return RANDOM_SWITCHES.filter(() => rand() < 0.5);
  if (spec === "none") return [];
  const on = spec.split(",").map(s => s.trim()).filter(Boolean);
  const unknown = on.filter(s => !SWITCHES.includes(s));
  if (unknown.length) throw new Error(`unknown setting ${unknown.join(", ")}: use random, none, or ${SWITCHES.join(", ")}`);
  return on;
}

/** A random stream fixed by a text key (FNV-1a), e.g. a seed and a corridor id. */
export function rngFor(key) {
  let h = 0x811c9dc5;
  for (const ch of key) h = Math.imul(h ^ ch.codePointAt(0), 0x01000193) >>> 0;
  return rng(h);
}

/** A seeded random number generator (mulberry32): the same seed picks the same places, trips and controls. */
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function shuffle(list, rand) {
  const a = [...list];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export function haversineKm(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371.0088 * Math.asin(Math.sqrt(h));
}

/** The n×n cells of [south, west, north, east]; row 0 is the northern row. */
export function gridCells([south, west, north, east], n) {
  const dLat = (north - south) / n, dLon = (east - west) / n;
  const cells = [];
  for (let row = 0; row < n; row++) for (let col = 0; col < n; col++) {
    const top = north - row * dLat, left = west + col * dLon;
    cells.push({ row, col, south: top - dLat, west: left, north: top, east: left + dLon, lat: top - dLat / 2, lon: left + dLon / 2 });
  }
  return cells;
}

/**
 * Per cell, the place a rider would most plausibly start from: the highest geocoder rank (cities, then towns,
 * villages, suburbs, hamlets), then the one nearest the cell's centre. Cells with no place of at least [minRank]
 * (sea, forest) are left out.
 */
export function pickPlaces(places, bbox, n, minRank = 160) {
  return gridCells(bbox, n).flatMap(c => {
    const inside = places.filter(p => p.rank >= minRank
      && p.lat >= c.south && (p.lat < c.north || (c.row === 0 && p.lat <= c.north))
      && p.lon >= c.west && (p.lon < c.east || (c.col === n - 1 && p.lon <= c.east)));
    if (!inside.length) return [];
    const best = inside.reduce((a, b) => b.rank > a.rank || (b.rank === a.rank && haversineKm(b, c) < haversineKm(a, c)) ? b : a);
    return [{ ...best, cell: `${c.row},${c.col}` }];
  });
}

/** Every pair of places [minKm, maxKm] apart as the crow flies, once, in a random direction. */
export function candidatePairs(places, minKm, maxKm, rand) {
  const pairs = [];
  for (let i = 0; i < places.length; i++) for (let j = i + 1; j < places.length; j++) {
    const km = haversineKm(places[i], places[j]);
    if (km < minKm || km > maxKm) continue;
    const [from, to] = rand() < 0.5 ? [places[i], places[j]] : [places[j], places[i]];
    pairs.push({ from, to, km: Math.round(km * 10) / 10 });
  }
  return pairs;
}

/**
 * At most [max] pairs, split evenly over short, medium and long trips (three equal log-distance bands), each band
 * spreading its trips over as many places as it can: the next pick always has the least-used endpoints.
 */
export function samplePairs(pairs, max, rand) {
  if (pairs.length <= max) return shuffle(pairs, rand);
  const logs = pairs.map(p => Math.log(p.km));
  const lo = Math.min(...logs), span = (Math.max(...logs) - lo) || 1;
  const bands = [[], [], []];
  pairs.forEach((p, i) => bands[Math.min(2, Math.floor(((logs[i] - lo) / span) * 3))].push(p));
  // Fair quotas: the smallest band first, each taking at most its share of what is left.
  const quota = new Map();
  let left = max;
  [...bands].sort((a, b) => a.length - b.length).forEach((band, i, sorted) => {
    const share = Math.min(band.length, Math.floor(left / (sorted.length - i)));
    quota.set(band, share);
    left -= share;
  });
  const used = new Map();
  const uses = place => used.get(place.cell) || 0;
  const out = [];
  for (const band of bands) {
    const pool = shuffle(band, rand);
    for (let n = quota.get(band); n > 0 && pool.length; n--) {
      let best = 0, bestCost = Infinity;
      pool.forEach((p, i) => {
        const cost = Math.max(uses(p.from), uses(p.to)) * 1000 + uses(p.from) + uses(p.to);
        if (cost < bestCost) { bestCost = cost; best = i; }
      });
      const [p] = pool.splice(best, 1);
      used.set(p.from.cell, uses(p.from) + 1);
      used.set(p.to.cell, uses(p.to) + 1);
      out.push(p);
    }
  }
  return out;
}

/** The suspicion kinds besides the geometry checks, in report order. */
export const SIGNALS = ["forced via", "less curvy", "near copy", "repeat", "over budget", "unused curvy road"];

const ODDITY_WEIGHT = {
  "returns to an earlier point": 2, "doubles back": 2, "street tangle": 2, "U-turn instruction": 2,
  "leaves and rejoins a road": 1.5, unpaved: 1.5,
};
const CURVY_STOPS = ["4", "5", "6", "7"]; // usedKmByStop keys: Curvy … Max twisty
const pct = x => `${Math.round(x * 100)}%`;

/**
 * How likely a plan is to hold nonsense, from what the rater measured: forced-via detours, the geometry checks
 * (one count per spot, however many stops share it; unpaved weighs most on the lower stops), stops over budget,
 * repeats and near-copies (93% of their roads shared with a lower stop), stops less curvy than a lower one, and
 * curvy roads on the way (their curviest point within 1.3× the beeline + 5 km of A and B together) with more curvy
 * km than the curviest stop's whole ride that no curvy stop rides. Returns the score and its reasons,
 * heaviest first; each reason's `kind` is one of SIGNALS or a geometry check's name.
 */
export function suspicion(plan) {
  const reasons = [];
  const add = (weight, text, kind) => reasons.push({ weight: Math.round(weight * 10) / 10, text, kind });
  const routes = plan.stops.map(s => plan.routes[s.routeKey]);
  const spots = [];
  plan.stops.forEach((stop, level) => {
    const style = STYLES[level];
    for (const o of routes[level].oddities || []) {
      let weight = ODDITY_WEIGHT[o.kind];
      if (!weight) continue;
      // Gravel is common in the country and fair on a twisty ride; a long stretch on the fast stops is not.
      if (o.kind === "unpaved") weight = o.toKm - o.fromKm >= 1 && level <= 2 ? 1.5 : 0.5;
      const spot = spots.find(s => s.kind === o.kind && Math.abs(s.lat - o.lat) < 0.004 && Math.abs(s.lon - o.lon) < 0.004);
      if (spot) { spot.stops.push(style); continue; }
      spots.push({ kind: o.kind, lat: o.lat, lon: o.lon, km: `${o.fromKm}–${o.toKm}`, weight, stops: [style] });
    }
    const vd = stop.viaDetour;
    if (vd) {
      if (vd.replacesKm < 0.5) add(3, `${style} forced through a via: a ${vd.detourKm} km loop`, "forced via");
      else add(vd.detourKm / vd.replacesKm >= 2 ? 3 : 1.5, `${style} forced through a via: ${vd.detourKm} km replace ${vd.replacesKm} km`, "forced via");
    }
    if (stop.budget === "RELAXED") add(1, `${style} is only within the relaxed budget`, "over budget");
    if (stop.budget === "NONE") add(2.5, `${style} is outside any budget`, "over budget");
    if (stop.repeats != null) add(4, `${style} repeats a lower stop`, "repeat");
    if (level === 0) return;
    const overlaps = Object.entries(stop.overlapWith || {}).filter(([l]) => +l < level);
    const [closest, share] = overlaps.reduce((a, b) => (b[1] > a[1] ? b : a), ["", 0]);
    if (share >= 0.93 && stop.repeats == null) add(share >= 0.98 ? 2.5 : 1, `${style} shares ${pct(share)} of its roads with ${STYLES[+closest]}`, "near copy");
    const curvy = routes[level].features.curvyShare;
    const lower = routes.slice(0, level).map((r, l) => [l, r.features.curvyShare]).reduce((a, b) => (b[1] > a[1] ? b : a));
    if (curvy < lower[1] - 0.03) add(1 + Math.min(2, (lower[1] - curvy) * 20), `${style} is ${pct(curvy)} curvy, less than ${STYLES[lower[0]]} at ${pct(lower[1])}`, "less curvy");
  });
  let unpaved = 0;
  for (const s of spots) {
    const weight = s.kind === "unpaved" ? Math.min(s.weight, 3 - unpaved) : s.weight;
    if (s.kind === "unpaved") unpaved += weight;
    if (weight > 0) add(weight, `${s.kind} on ${s.stops.join(", ")} (km ${s.km} on ${s.stops[0]})`, s.kind);
  }
  const a = plan.request?.from, b = plan.request?.to;
  const reach = a && b ? 1.3 * haversineKm(a, b) + 5 : Infinity;
  const onTheWay = road => !road.curviestAt || !a || !b
    || haversineKm(a, { lat: road.curviestAt[0], lon: road.curviestAt[1] }) + haversineKm({ lat: road.curviestAt[0], lon: road.curviestAt[1] }, b) <= reach;
  // Nearly every corridor has some curvy road nobody rides; one with more curvy km than the curviest stop's whole
  // ride is the sign that the curvy stops missed it. The best two count, the second at half weight.
  const curviest = Math.max(1, ...routes.slice(3).map(r => r.features.curvyKm || 0));
  (plan.corridorRoads || [])
    .filter(road => road.curvyKm >= 6 && road.curvyKm > curviest && road.roadClass !== "motorway" && onTheWay(road)
      && Math.max(0, ...CURVY_STOPS.map(k => road.usedKmByStop?.[k] || 0)) < 1)
    .sort((x, y) => y.curvyKm - x.curvyKm).slice(0, 2)
    .forEach((road, i) => add(Math.min(2.5, road.curvyKm / curviest) / (i + 1),
      `${road.name}: ${road.curvyKm} curvy km on the way, ${(road.curvyKm / curviest).toFixed(1)}× the curviest stop's, ridden by no curvy stop`, "unused curvy road"));
  reasons.sort((a, b) => b.weight - a.weight);
  return { score: Math.round(reasons.reduce((n, r) => n + r.weight, 0) * 10) / 10, reasons };
}

/**
 * The plans the agents review: the [review] highest scores, each place in at most [perPlace] of them (so one bad
 * start cannot fill the list; skipped plans come back only if too few are left), plus [controls] random others to
 * show what the score misses.
 */
export function selectForReview(scored, { review, controls, perPlace, rand }) {
  const ranked = [...scored].sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
  const top = [], skipped = [], used = new Map();
  const uses = p => used.get(p.cell) || 0;
  for (const s of ranked) {
    if (top.length >= review) break;
    if (uses(s.from) >= perPlace || uses(s.to) >= perPlace) { skipped.push(s); continue; }
    top.push(s);
    used.set(s.from.cell, uses(s.from) + 1);
    used.set(s.to.cell, uses(s.to) + 1);
  }
  top.push(...skipped.slice(0, review - top.length));
  const chosen = new Set(top);
  return { top, controls: shuffle(ranked.filter(s => !chosen.has(s)), rand).slice(0, controls) };
}

/** A readable, unique corridor id such as "esk--kilcoy". */
export function corridorId(from, to, taken) {
  const slug = s => s.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "place";
  const base = `${slug(from.name)}--${slug(to.name)}`;
  let id = base;
  for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
  taken.add(id);
  return id;
}
