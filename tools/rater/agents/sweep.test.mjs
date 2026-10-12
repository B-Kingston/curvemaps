// node --test (npm test): the pure sweep logic.
import test from "node:test";
import assert from "node:assert/strict";
import { rng, rngFor, settingsFrom, gridCells, pickPlaces, candidatePairs, samplePairs, suspicion, selectForReview, corridorId, haversineKm } from "./sweep-lib.mjs";

const BOX = [-28, 152, -27, 153];

test("the same seed gives the same numbers", () => {
  const a = rng(7), b = rng(7), c = rng(8);
  const draw = r => Array.from({ length: 5 }, r);
  assert.deepEqual(draw(a), draw(b));
  assert.notDeepEqual(draw(rng(7)), draw(c));
});

test("settings: random is fixed by its key, none is none, a list is that list, unknown names fail", () => {
  const draws = Array.from({ length: 40 }, (_, i) => settingsFrom("random", rngFor(`1:corridor-${i}`)).join(","));
  assert.deepEqual(draws, Array.from({ length: 40 }, (_, i) => settingsFrom("random", rngFor(`1:corridor-${i}`)).join(",")));
  assert.ok(new Set(draws).size >= 6, `only ${new Set(draws).size} combinations in 40 draws`);
  assert.deepEqual(settingsFrom("none"), []);
  assert.deepEqual(settingsFrom("avoidMotorways, elevation"), ["avoidMotorways", "elevation"]);
  assert.throws(() => settingsFrom("hills"), /unknown setting hills/);
});

test("the grid tiles the box, north row first", () => {
  const cells = gridCells(BOX, 4);
  assert.equal(cells.length, 16);
  assert.deepEqual([cells[0].north, cells[0].west], [-27, 152]);
  assert.deepEqual([cells[15].south, cells[15].east], [-28, 153]);
  const area = cells.reduce((n, c) => n + (c.north - c.south) * (c.east - c.west), 0);
  assert.ok(Math.abs(area - 1) < 1e-9);
});

test("each cell keeps its highest-ranked place, nearest the centre on a tie, and empty cells drop out", () => {
  const places = [
    { name: "Village", rank: 195, lat: -27.3, lon: 152.2 },
    { name: "Town", rank: 220, lat: -27.4, lon: 152.4 },     // same north-west cell, higher rank
    { name: "Edge", rank: 220, lat: -27.05, lon: 152.05 },   // same rank, further from the centre
    { name: "Farm", rank: 130, lat: -27.7, lon: 152.7 },     // below minRank: its cell stays empty
    { name: "Suburb", rank: 185, lat: -27.75, lon: 152.25 },
  ];
  const picked = pickPlaces(places, BOX, 2);
  assert.deepEqual(picked.map(p => [p.name, p.cell]), [["Town", "0,0"], ["Suburb", "1,0"]]);
});

test("candidate pairs keep the distance band and each pair once", () => {
  const places = [{ name: "A", lat: -27, lon: 152, cell: "a" }, { name: "B", lat: -27.1, lon: 152, cell: "b" }, { name: "C", lat: -28, lon: 153, cell: "c" }];
  const pairs = candidatePairs(places, 5, 50, rng(1));
  assert.equal(pairs.length, 1);
  assert.deepEqual(new Set([pairs[0].from.name, pairs[0].to.name]), new Set(["A", "B"]));
  assert.ok(Math.abs(pairs[0].km - haversineKm(places[0], places[1])) < 0.1);
});

test("sampling keeps the size, mixes distances and spreads endpoints, the same way every time", () => {
  const places = gridCells([-28.3, 151.9, -26.4, 153.6], 6).map((c, i) => ({ name: `P${i}`, lat: c.lat, lon: c.lon, rank: 200, cell: `${c.row},${c.col}` }));
  const pairs = candidatePairs(places, 15, 150, rng(3));
  const sample = samplePairs(pairs, 60, rng(3));
  assert.equal(sample.length, 60);
  assert.deepEqual(sample.map(p => p.from.name + p.to.name), samplePairs(pairs, 60, rng(3)).map(p => p.from.name + p.to.name));
  const kms = sample.map(p => p.km).sort((a, b) => a - b);
  assert.ok(kms[0] < 50 && kms.at(-1) > 100, `distances ${kms[0]}–${kms.at(-1)}`);
  const uses = new Map();
  for (const p of sample) for (const e of [p.from, p.to]) uses.set(e.cell, (uses.get(e.cell) || 0) + 1);
  assert.equal(uses.size, places.length, "every place starts or ends a trip");
  assert.ok(Math.max(...uses.values()) - Math.min(...uses.values()) <= 3, `uses ${[...uses.values()]}`);
});

/** A plan with seven stops whose curvy shares rise unless [curvy] says otherwise. */
function plan({ curvy = [0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4], stop = () => ({}), oddities = () => [], corridorRoads = [] } = {}) {
  const request = { from: { lat: -27.99, lon: 153.34 }, to: { lat: -28.03, lon: 153.06 } }; // Nerang → Canungra, 28 km
  const routes = {}, stops = [];
  curvy.forEach((c, level) => {
    routes[`r${level}`] = { features: { curvyShare: c, curvyKm: c * 20 }, oddities: oddities(level) }; // Max twisty: 8 curvy km
    stops.push({ level, routeKey: `r${level}`, budget: "STYLE", repeats: null, viaDetour: null, overlapWith: {}, ...stop(level) });
  });
  return { request, stops, routes, corridorRoads };
}

test("a clean plan scores zero", () => {
  const ridden = { name: "Mount Glorious Road", curvyKm: 12, roadClass: "secondary", usedKmByStop: { 6: 10 } };
  assert.deepEqual(suspicion(plan({ corridorRoads: [ridden] })), { score: 0, reasons: [] });
});

test("the score names each suspicious thing once, heaviest first", () => {
  const spot = { kind: "doubles back", fromKm: 17, toKm: 19, lat: -27.95, lon: 153.22 };
  const p = plan({
    curvy: [0.1, 0.15, 0.2, 0.25, 0.12, 0.35, 0.4], // Twisty below Curvy
    stop: level => level === 4 ? { viaDetour: { detourKm: 8, replacesKm: 0.2 }, budget: "NONE" }
      : level === 6 ? { overlapWith: { 5: 0.95, 3: 0.4 } } : {},
    oddities: level => (level === 4 || level === 5 ? [spot] : []), // one spot on two stops
    corridorRoads: [
      { name: "Beechmont Road", curvyKm: 15, roadClass: "secondary", usedKmByStop: {}, curviestAt: [-28.08, 153.19] },
      { name: "Mount Glorious Road", curvyKm: 20, roadClass: "secondary", usedKmByStop: {}, curviestAt: [-27.33, 152.77] }, // 80 km off the way
      { name: "Pacific Motorway", curvyKm: 9, roadClass: "motorway", usedKmByStop: {} },
      { name: "Short Lane", curvyKm: 2, roadClass: "tertiary", usedKmByStop: {} },
    ],
  });
  const { score, reasons } = suspicion(p);
  const texts = reasons.map(r => r.text);
  assert.ok(texts.includes("Twisty forced through a via: a 8 km loop"));
  assert.ok(texts.includes("Twisty is outside any budget"));
  assert.ok(texts.includes("Twisty is 12% curvy, less than Curvy at 25%"));
  assert.ok(texts.includes("Max twisty shares 95% of its roads with Very twisty"));
  assert.ok(texts.includes("doubles back on Twisty, Very twisty (km 17–19 on Twisty)"));
  assert.ok(texts.includes("Beechmont Road: 15 curvy km on the way, 1.9× the curviest stop's, ridden by no curvy stop"));
  assert.equal(texts.filter(t => t.startsWith("doubles back")).length, 1);
  assert.deepEqual(new Set(reasons.map(r => r.kind)), new Set(["forced via", "over budget", "less curvy", "near copy", "doubles back", "unused curvy road"]));
  assert.ok(!texts.some(t => /Pacific Motorway|Short Lane|Mount Glorious/.test(t)));
  assert.deepEqual(reasons.map(r => r.weight), [...reasons.map(r => r.weight)].sort((a, b) => b - a));
  assert.equal(score, Math.round(reasons.reduce((n, r) => n + r.weight, 0) * 10) / 10);
});

test("unpaved weighs most as a long stretch on a fast stop, and at most 3 in all", () => {
  const gravel = (lat, km) => ({ kind: "unpaved", fromKm: 10, toKm: 10 + km, lat, lon: 152 });
  const weights = p => suspicion(p).reasons.filter(r => r.kind === "unpaved").map(r => r.weight);
  assert.deepEqual(weights(plan({ oddities: level => (level === 1 ? [gravel(-27.1, 4)] : level === 5 ? [gravel(-27.2, 4), gravel(-27.3, 0.4)] : []) })), [1.5, 0.5, 0.5]);
  assert.equal(weights(plan({ oddities: level => (level === 0 ? [-27.1, -27.2, -27.3].map(lat => gravel(lat, 5)) : []) })).reduce((a, b) => a + b), 3);
});

test("the review takes the top scores without letting one place fill it, plus random controls", () => {
  const place = cell => ({ name: cell, cell });
  const scored = [
    { id: "a1", from: place("A"), to: place("B"), score: 9 },
    { id: "a2", from: place("A"), to: place("C"), score: 8 },
    { id: "a3", from: place("A"), to: place("D"), score: 7 }, // A's third: skipped while others remain
    { id: "e1", from: place("E"), to: place("F"), score: 3 },
    { id: "g1", from: place("G"), to: place("H"), score: 1 },
    { id: "i1", from: place("I"), to: place("J"), score: 0 },
    { id: "k1", from: place("K"), to: place("L"), score: 0 },
  ];
  const { top, controls } = selectForReview(scored, { review: 3, controls: 2, perPlace: 2, rand: rng(5) });
  assert.deepEqual(top.map(s => s.id), ["a1", "a2", "e1"]);
  assert.equal(controls.length, 2);
  assert.ok(controls.every(c => !top.includes(c)));
  assert.deepEqual(controls.map(c => c.id), selectForReview(scored, { review: 3, controls: 2, perPlace: 2, rand: rng(5) }).controls.map(c => c.id));
  // Too few plans left: the skipped ones come back in score order.
  assert.deepEqual(selectForReview(scored.slice(0, 3), { review: 3, controls: 0, perPlace: 2, rand: rng(5) }).top.map(s => s.id), ["a1", "a2", "a3"]);
});

test("corridor ids are readable and unique", () => {
  const taken = new Set();
  assert.equal(corridorId({ name: "Mount Tamborine" }, { name: "Canungra" }, taken), "mount-tamborine--canungra");
  assert.equal(corridorId({ name: "Mount Tamborine" }, { name: "Canungra" }, taken), "mount-tamborine--canungra-2");
  assert.equal(corridorId({ name: "Mapleton" }, { name: "Nambour (Café)" }, taken), "mapleton--nambour-cafe");
});
