// Looks for routing nonsense in an area without knowing where to look (usage: tools/rater/README.md):
//   node sweep.mjs --bbox SOUTH,WEST,NORTH,EAST [--cells 6] [--km 15-150] [--max-pairs 300] [--review 25]
//                  [--controls 5] [--per-place 2] [--seed 1] [--min-rank 160] [--jobs 3] [--out DIR] [--plan-only]
//                  [--options random | none | avoidMotorways,elevation,stopsAndTowns,blockUnpaved]
// 1. one place per grid cell (the rater's geocoder); 2. a balanced sample of trips between them; 3. every trip
// planned by the running rater and scored from its own checks (free); 4. the top scores plus random controls
// reviewed by the agents (agents.mjs); 5. one digest of what they found (digest.mjs).
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { api, raterStatus, loadPlan, RATER } from "./rater.mjs";
import {
  STYLES, SIGNALS, SWITCHES, SETTING_NAMES, rng, settingsFrom, gridCells, pickPlaces, candidatePairs, samplePairs, suspicion,
  selectForReview, corridorId,
} from "./sweep-lib.mjs";

const HERE = path.dirname(new URL(import.meta.url).pathname);
const REVIEWS = path.resolve(HERE, "../../../data/rater/agent-review");
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

const argv = process.argv.slice(2);
const flag = name => argv.includes(name);
const opt = (name, fallback) => { const i = argv.indexOf(name); return i < 0 ? fallback : argv[i + 1]; };
const fail = msg => { console.error(msg); process.exit(1); };

const bbox = (opt("--bbox") || "").split(",").map(Number);
if (bbox.length !== 4 || bbox.some(Number.isNaN) || bbox[0] >= bbox[2] || bbox[1] >= bbox[3])
  fail("usage: node sweep.mjs --bbox SOUTH,WEST,NORTH,EAST [--cells 6] [--km 15-150] [--max-pairs 300] [--review 25] [--controls 5] [--per-place 2] [--seed 1] [--min-rank 160] [--jobs 3] [--out DIR] [--plan-only] [--options random|none|SWITCH,…]");
// The rider's settings each trip is planned with (the rater's plan switches). "random" (the default) turns avoid
// motorways, hills and stops & towns on or off per trip, so a sweep covers every combination; a list turns those
// switches on for every trip; "none" plans with all of them off.
const optionsArg = opt("--options", "random");
let switches = [];
try { switches = optionsArg === "random" ? [] : settingsFrom(optionsArg); } catch (e) { fail(`--options: ${e.message}`); }
const [minKm, maxKm] = opt("--km", "15-150").split("-").map(Number);
const params = {
  bbox, cells: +opt("--cells", 6), minKm, maxKm, maxPairs: +opt("--max-pairs", 300), review: +opt("--review", 25),
  controls: +opt("--controls", 5), perPlace: +opt("--per-place", 2), seed: +opt("--seed", 1), minRank: +opt("--min-rank", 160),
  options: optionsArg === "random" ? "random" : switches,
};
const jobs = +opt("--jobs", 3);
// Settings other than random get their own folder: -plain for none, else the switches.
const optionsTag = optionsArg === "random" ? "" : `-${switches.length ? switches.join("-") : "plain"}`;
const OUT = path.resolve(opt("--out", path.join(REVIEWS, `sweep-${new Date().toLocaleDateString("en-CA")}-${params.cells}x${params.cells}-s${params.seed}${optionsTag}`)));
const PLANS = process.env.PLANS || path.join(REVIEWS, "plans");

// 1. Is the rater ready, and does its map cover the box?
const { info, problems } = await raterStatus();
if (problems.length) fail(`Not ready:\n${problems.map(p => `- ${p}`).join("\n")}`);
const [rs, rw, rn, re] = info.region.bounds || [];
if (rs != null && (bbox[2] < rs || bbox[0] > rn || bbox[3] < rw || bbox[1] > re)) fail(`the box is outside the rater's map ${info.region.name} (${info.region.bounds.join(", ")})`);
console.log(`sweep ${OUT}\nmap ${info.region.name} on ${RATER}, ${params.cells}x${params.cells} cells, trips ${minKm}–${maxKm} km, seed ${params.seed}, settings ${optionsArg === "random" ? "random per trip" : switches.join(", ") || "none"}`);

// 2. Places and trips.
const rand = rng(params.seed);
const { places: all } = await api(`/api/places?south=${bbox[0]}&west=${bbox[1]}&north=${bbox[2]}&east=${bbox[3]}`);
const places = pickPlaces(all, bbox, params.cells, params.minRank);
if (places.length < 2) fail(`only ${places.length} cell(s) hold a place of rank ≥ ${params.minRank}: use a bigger box, fewer cells or --min-rank`);
const pairs = samplePairs(candidatePairs(places, minKm, maxKm, rand), params.maxPairs, rand);
if (!pairs.length) fail(`no two places are ${minKm}–${maxKm} km apart: change --km or --cells`);
const taken = new Set();
// Its own random stream, so random settings leave the places, trips and controls as a plain sweep picks them.
const settingsRand = rng(params.seed ^ 0x5e771265);
const trips = pairs.map(p => {
  const on = optionsArg === "random" ? settingsFrom("random", settingsRand) : switches;
  return {
    id: corridorId(p.from, p.to, taken), km: p.km, fromCell: p.from.cell, toCell: p.to.cell, settings: on,
    from: { lat: p.from.lat, lon: p.from.lon, name: p.from.name }, to: { lat: p.to.lat, lon: p.to.lon, name: p.to.name },
    options: { tag: "sweep", ...Object.fromEntries(on.map(s => [s, true])) },
  };
});
console.log(`${places.length} places, ${trips.length} trips`);

// 3. Plan and score every trip. The rater keeps each plan, so the review reuses the ones it picks.
const t0 = Date.now();
let done = 0, nextReport = 0;
const queue = [...trips];
await Promise.all(Array.from({ length: jobs }, async () => {
  for (let trip; (trip = queue.shift());) {
    try {
      const plan = await loadPlan(trip, info, PLANS, { cache: false });
      Object.assign(trip, { planId: plan.planId, ...suspicion(plan) });
    } catch (e) {
      trip.error = e.message;
    }
    if (++done >= nextReport || done === trips.length) {
      console.log(`planned ${done}/${trips.length}${trips.some(t => t.error) ? ` (${trips.filter(t => t.error).length} failed)` : ""} in ${Math.round((Date.now() - t0) / 1000)} s`);
      nextReport = done + Math.max(10, Math.ceil(trips.length / 10));
    }
  }
}));

// 4. Pick the review: top scores plus controls.
const planned = trips.filter(t => !t.error);
const scored = planned.map(t => ({ ...t, from: { ...t.from, cell: t.fromCell }, to: { ...t.to, cell: t.toCell } }));
const { top, controls } = selectForReview(scored, { ...params, rand });
const pick = new Map([...top.map((t, i) => [t.id, `top ${i + 1}`]), ...controls.map(t => [t.id, "control"])]);
for (const t of trips) t.pick = pick.get(t.id) || null;
const corridors = [...top, ...controls].map(t => ({
  id: t.id, from: t.from, to: t.to, options: t.options,
  sweep: { pick: pick.get(t.id), score: t.score, reasons: t.reasons.slice(0, 6).map(r => r.text) },
}));
// For each switch: how the plans with it on compare with those with it off (only telling when settings vary).
const bySetting = SWITCHES.map(sw => {
  const side = on => {
    const group = planned.filter(t => t.settings.includes(sw) === on);
    return { plans: group.length, meanScore: group.length ? Math.round(10 * group.reduce((n, t) => n + t.score, 0) / group.length) / 10 : null,
      signals: Object.fromEntries(SIGNALS.map(k => [k, group.filter(t => t.reasons.some(r => r.kind === k)).length])) };
  };
  return { switch: sw, on: side(true), off: side(false) };
}).filter(s => s.on.plans && s.off.plans);
const signals = [...SIGNALS, ...new Set(planned.flatMap(t => t.reasons.map(r => r.kind)).filter(k => !SIGNALS.includes(k)))]
  .map(kind => ({ kind, plans: planned.filter(t => t.reasons.some(r => r.kind === kind)).length }));

fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, "corridors.json"), JSON.stringify(corridors, null, 1));
const sweep = {
  built: new Date().toISOString(), rater: RATER, region: info.region, algo: info.algo, params, places,
  planned: planned.length, failed: trips.length - planned.length, seconds: Math.round((Date.now() - t0) / 1000), signals, bySetting,
  trips: trips.map(({ options, ...t }) => t),
};
fs.writeFileSync(path.join(OUT, "sweep.json"), JSON.stringify(sweep, null, 1));
fs.writeFileSync(path.join(OUT, "triage.html"), triagePage(sweep));
console.log(`\n${planned.length} planned, ${sweep.failed} failed. Review: ${top.length} top + ${controls.length} controls.`);
console.log(`Triage: ${path.join(OUT, "triage.html")}`);
for (const s of signals.filter(s => s.plans)) console.log(`  ${s.kind}: ${s.plans} of ${planned.length} plans`);

// The picked plans go to the review's plan cache one at a time, so the parallel agents read them from disk instead
// of asking the rater for many large plans at once.
for (const c of corridors) await loadPlan(c, info, PLANS).catch(e => console.log(`${c.id}: plan not cached (${e.message})`));

// 5. The agents, then the digest.
if (flag("--plan-only") || !corridors.length) {
  console.log(`\nReview later: CORRIDORS=${path.join(OUT, "corridors.json")} OUT=${path.join(OUT, "review")} npm run review`);
  process.exit(sweep.failed && !planned.length ? 1 : 0);
}
const run = (script, args, env) => spawnSync(process.execPath, [path.join(HERE, script), ...args], { stdio: "inherit", env: { ...process.env, ...env } }).status;
const reviewDir = path.join(OUT, "review");
const status = run("agents.mjs", [], { CORRIDORS: path.join(OUT, "corridors.json"), OUT: reviewDir, PLANS });
if (fs.existsSync(reviewDir) && fs.readdirSync(reviewDir).some(f => f.endsWith(".json"))) run("digest.mjs", [reviewDir]);
process.exit(status ?? 1);

/** The sweep at a glance: what each signal's share is, where the trips went, and every plan by score. */
function triagePage(s) {
  const pctOf = (n, of) => (of ? `${Math.round(100 * n / of)}%` : "–");
  const [south, west, north, east] = s.params.bbox;
  const k = Math.cos(((south + north) / 2) * Math.PI / 180);
  const W = 760, H = Math.round(W * (north - south) / ((east - west) * k));
  const x = lon => ((lon - west) / (east - west) * W).toFixed(1), y = lat => ((north - lat) / (north - south) * H).toFixed(1);
  const grid = gridCells(s.params.bbox, s.params.cells).map(c => `<rect x="${x(c.west)}" y="${y(c.north)}" width="${(x(c.east) - x(c.west)).toFixed(1)}" height="${(y(c.south) - y(c.north)).toFixed(1)}" class="cell"/>`).join("");
  const line = (t, cls) => `<line x1="${x(t.from.lon)}" y1="${y(t.from.lat)}" x2="${x(t.to.lon)}" y2="${y(t.to.lat)}" class="${cls}"><title>${esc(t.from.name)} → ${esc(t.to.name)}${t.error ? ": failed" : `: ${t.score}`}</title></line>`;
  const lines = [...s.trips.filter(t => !t.pick && !t.error).map(t => line(t, "trip")), ...s.trips.filter(t => t.error).map(t => line(t, "failed")),
    ...s.trips.filter(t => t.pick === "control").map(t => line(t, "control")), ...s.trips.filter(t => t.pick?.startsWith("top")).map(t => line(t, "top"))].join("");
  const dots = s.places.map(p => `<circle cx="${x(p.lon)}" cy="${y(p.lat)}" r="3.5"/><text x="${(+x(p.lon) + 5).toFixed(1)}" y="${(+y(p.lat) + 4).toFixed(1)}">${esc(p.name)}</text>`).join("");
  const ranked = [...s.trips].sort((a, b) => (b.score ?? -1) - (a.score ?? -1));
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sweep triage</title><style>
body{font:13px/1.45 system-ui,sans-serif;margin:16px;color:#1d1d1f;background:#fff;max-width:1400px}
table{border-collapse:collapse;margin:6px 0 14px}td,th{border:1px solid #c9ccd1;padding:3px 6px;vertical-align:top;text-align:left}th{background:#eef0f3}
.num{text-align:right;white-space:nowrap}.muted{color:#5f6368}svg{border:1px solid #c9ccd1;max-width:100%;height:auto;background:#fafbfc}
.cell{fill:none;stroke:#d5d8dc}.trip{stroke:#b9bec6;stroke-width:.8}.failed{stroke:#e8710a;stroke-dasharray:4 3}.control{stroke:#1a73e8;stroke-width:2.2}.top{stroke:#d93025;stroke-width:2.6}
circle{fill:#1d1d1f}text{font-size:10px;fill:#1d1d1f;paint-order:stroke;stroke:#fff;stroke-width:3px}tr.top td{background:#fdecea}tr.control td{background:#e8f0fe}
</style></head><body><h1>Sweep triage</h1>
<p class="muted">${esc(s.region.name)} · routing ${esc(s.algo.hash)} · rater ${esc(s.algo.context)} · box ${s.params.bbox.join(", ")} · ${s.params.cells}×${s.params.cells} cells · trips ${s.params.minKm}–${s.params.maxKm} km · seed ${s.params.seed} · settings ${esc(s.params.options === "random" ? "random per trip" : (s.params.options || []).join(", ") || "none")} · built ${esc(s.built.slice(0, 16).replace("T", " "))} UTC</p>
<p>${s.places.length} places, ${s.trips.length} trips: ${s.planned} planned in ${s.seconds} s, ${s.failed} failed. The agents review the <b style="color:#d93025">top ${s.params.review}</b> by suspicion score (each place in at most ${s.params.perPlace}) and <b style="color:#1a73e8">${s.params.controls} random controls</b>.</p>
<table><tr><th>signal</th><th>plans with it</th></tr>${s.signals.map(g => `<tr><td>${esc(g.kind)}</td><td class="num">${g.plans} (${s.planned ? Math.round(100 * g.plans / s.planned) : 0}%)</td></tr>`).join("")}</table>
${s.bySetting?.length ? `<h2>Settings: on against off</h2><table><tr><th>setting</th><th>plans on / off</th><th>mean score on / off</th>${SIGNALS.map(k => `<th>${esc(k)} on / off</th>`).join("")}</tr>
${s.bySetting.map(b => `<tr><td>${esc(SETTING_NAMES[b.switch])}</td><td class="num">${b.on.plans} / ${b.off.plans}</td><td class="num">${b.on.meanScore} / ${b.off.meanScore}</td>${SIGNALS.map(k => `<td class="num">${pctOf(b.on.signals[k], b.on.plans)} / ${pctOf(b.off.signals[k], b.off.plans)}</td>`).join("")}</tr>`).join("\n")}</table>` : ""}
<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="Grid, places and trips">${grid}${lines}${dots}</svg>
<h2>Every trip by score</h2><table><tr><th>#</th><th>pick</th><th>trip</th><th>km</th><th>settings</th><th>score</th><th>reasons</th><th>plan</th></tr>
${ranked.map((t, i) => `<tr class="${t.pick?.startsWith("top") ? "top" : t.pick || ""}"><td class="num">${i + 1}</td><td>${esc(t.pick || "")}</td><td>${esc(t.from.name)} → ${esc(t.to.name)}</td><td class="num">${t.km}</td><td>${esc((t.settings || []).map(sw => SETTING_NAMES[sw]).join(", "))}</td><td class="num">${t.error ? "" : t.score}</td><td>${t.error ? `<b>plan failed:</b> ${esc(t.error)}` : t.reasons.map(r => `${esc(r.text)} <span class="muted">(${r.weight})</span>`).join("<br>")}</td><td>${t.planId ? `<a href="${esc(s.rater)}/#plan=${esc(t.planId)}">${esc(t.planId)}</a>` : ""}</td></tr>`).join("\n")}
</table><p class="muted">Styles: ${STYLES.map((n, i) => `${i + 1} ${n}`).join(", ")}. A plan id opens that plan in the running rater; sweep plans stay out of its History unless you tick "Show sweep plans".</p></body></html>\n`;
}
