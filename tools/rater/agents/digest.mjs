// What a whole review found, in one page (digest.html) and one compact file for an agent to read (digest.json).
//   node digest.mjs REVIEW_DIR [--sweep SWEEP_JSON] [--out FILE.html]
// A sweep's review (sweep-…/review) finds its sweep.json in the folder above and writes the digest there; the
// digest then also says whether the suspicion score's top picks held more nonsense than the random controls.
import fs from "node:fs";
import path from "node:path";
import { STYLES } from "./sweep-lib.mjs";

const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const args = process.argv.slice(2);
const opt = name => { const i = args.indexOf(name); return i < 0 ? undefined : args.splice(i, 2)[1]; };
const sweepArg = opt("--sweep"), outArg = opt("--out");
if (args.length !== 1) { console.error("usage: node digest.mjs REVIEW_DIR [--sweep SWEEP_JSON] [--out FILE.html]"); process.exit(2); }

const reviewDir = path.resolve(args[0]);
const logs = fs.readdirSync(reviewDir).filter(f => f.endsWith(".json")).map(f => JSON.parse(fs.readFileSync(path.join(reviewDir, f), "utf8")))
  .filter(l => l.corridor && "review" in l);
if (!logs.length) { console.error(`${reviewDir} holds no review results`); process.exit(1); }
const sweepFile = sweepArg ? path.resolve(sweepArg) : [path.join(reviewDir, "sweep.json"), path.join(reviewDir, "..", "sweep.json")].find(f => fs.existsSync(f));
const sweep = sweepFile ? JSON.parse(fs.readFileSync(sweepFile, "utf8")) : null;
const trip = id => sweep?.trips.find(t => t.id === id);
const out = path.resolve(outArg || path.join(sweepFile ? path.dirname(sweepFile) : reviewDir, "digest.html"));
const rel = file => path.relative(path.dirname(out), file);

const reviewed = logs.filter(l => l.review);
const CONF = { high: 3, medium: 2, low: 1 };

// Issues by kind, with every instance; heaviest (most high-confidence) first.
const issues = new Map();
for (const l of reviewed) for (const st of l.review.stops) for (const i of st.issues) {
  if (!issues.has(i.kind)) issues.set(i.kind, { kind: i.kind, count: 0, high: 0, corridors: new Set(), byStop: Array(7).fill(0), examples: [] });
  const k = issues.get(i.kind);
  k.count++; k.byStop[st.stop - 1]++; k.corridors.add(l.corridor); if (i.confidence === "high") k.high++;
  k.examples.push({ corridor: l.corridor, stop: st.stop, km: i.km_from != null ? `${i.km_from}–${i.km_to ?? ""}` : null, roads: i.roads || null, confidence: i.confidence, description: i.description });
}
const issueList = [...issues.values()].map(k => ({ ...k, corridors: k.corridors.size, examples: k.examples.sort((a, b) => CONF[b.confidence] - CONF[a.confidence]) }))
  .sort((a, b) => b.high - a.high || b.count - a.count);

// How often the agents agreed with each automatic check.
const flags = new Map();
for (const l of reviewed) for (const f of l.flags || []) {
  if (!flags.has(f.kind)) flags.set(f.kind, { kind: f.kind, "real problem": 0, harmless: 0, unsure: 0, "not ruled on": 0 });
  flags.get(f.kind)[l.review.auto_flags?.find(a => a.flag === f.id)?.verdict || "not ruled on"]++;
}

const stops = STYLES.map((style, i) => {
  const s = reviewed.map(l => l.review.stops[i]).filter(Boolean);
  const n = v => s.filter(x => x.verdict === v).length;
  return { stop: i + 1, style, ok: n("ok"), questionable: n("questionable"), nonsense: n("nonsense"), meanRating: s.length ? Math.round(10 * s.reduce((a, x) => a + x.ride_rating, 0) / s.length) / 10 : null };
});

const corridors = logs.map(l => {
  const t = trip(l.corridor);
  const nonsense = l.review ? l.review.stops.filter(s => s.verdict === "nonsense") : [];
  const overview = path.join(reviewDir, "img", `${l.corridor}-overview.jpg`);
  return {
    id: l.corridor, pick: t?.pick ?? null, score: t?.score ?? null, planId: l.planId, error: l.review ? null : (l.error || "no review"),
    summary: l.review?.corridor_summary ?? null, verdicts: l.review ? l.review.stops.map(s => s.verdict[0]).join("") : null,
    nonsenseStops: nonsense.map(s => ({ stop: s.stop, style: STYLES[s.stop - 1], comment: s.comment })),
    questionableStops: l.review ? l.review.stops.filter(s => s.verdict === "questionable").length : 0,
    issues: l.review ? l.review.stops.reduce((n, s) => n + s.issues.length, 0) : 0,
    betterRoads: l.review?.better_roads ?? [], overview: fs.existsSync(overview) ? rel(overview) : null,
  };
}).sort((a, b) => b.nonsenseStops.length - a.nonsenseStops.length || b.issues - a.issues);

/** Did the suspicion score point the agents at the problems? Nonsense stops and issues per corridor, top vs controls. */
function group(isMember) {
  const c = corridors.filter(x => !x.error && isMember(x));
  const per = f => (c.length ? Math.round(100 * c.reduce((n, x) => n + f(x), 0) / c.length) / 100 : null);
  return { corridors: c.length, nonsenseStopsPerCorridor: per(x => x.nonsenseStops.length), issuesPerCorridor: per(x => x.issues) };
}
const triage = sweep ? { top: group(x => x.pick?.startsWith("top")), controls: group(x => x.pick === "control") } : null;

const totals = {
  corridors: logs.length, reviewed: reviewed.length, failed: logs.filter(l => !l.review).map(l => l.corridor),
  cost: Math.round(1000 * logs.reduce((n, l) => n + (l.usage?.cost || 0), 0)) / 1000,
  tokensIn: logs.reduce((n, l) => n + (l.usage?.input || 0), 0), tokensOut: logs.reduce((n, l) => n + (l.usage?.output || 0), 0),
  slowestSeconds: Math.max(0, ...logs.map(l => l.seconds || 0)), models: [...new Set(logs.map(l => `${l.model} ${l.thinking}`))],
};
const digest = {
  built: new Date().toISOString(), reviewDir, sweep: sweep ? { file: sweepFile, region: sweep.region.name, params: sweep.params, planned: sweep.planned, failed: sweep.failed, signals: sweep.signals } : null,
  totals, triage, stops, issues: issueList, flags: [...flags.values()],
  setIssues: reviewed.flatMap(l => l.review.set_issues.map(i => ({ corridor: l.corridor, kind: i.kind, stops: i.stops, description: i.description }))),
  betterRoads: reviewed.flatMap(l => l.review.better_roads.map(text => ({ corridor: l.corridor, text }))),
  corridors,
};
fs.writeFileSync(out.replace(/\.html$/, "") + ".json", JSON.stringify(digest, null, 1));

const th = cols => `<tr>${cols.map(c => `<th>${c}</th>`).join("")}</tr>`;
const h = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Review digest</title><style>
body{font:13px/1.45 system-ui,sans-serif;margin:16px;color:#1d1d1f;background:#fff;max-width:1400px}
table{border-collapse:collapse;margin:6px 0 14px}td,th{border:1px solid #c9ccd1;padding:3px 6px;vertical-align:top;text-align:left}th{background:#eef0f3}
.num{text-align:right;white-space:nowrap}.muted{color:#5f6368}details{margin:2px 0}summary{cursor:pointer}img{width:220px;border:1px solid #c9ccd1}
</style></head><body><h1>Review digest</h1>
<p class="muted">${esc(totals.models.join(", "))} · ${totals.reviewed} of ${totals.corridors} corridors reviewed${totals.failed.length ? ` (no review: ${esc(totals.failed.join(", "))})` : ""} · $${totals.cost.toFixed(3)} · slowest ${totals.slowestSeconds} s${sweep ? ` · sweep of ${esc(sweep.region.name)}, ${sweep.planned} plans triaged (<a href="${esc(rel(path.join(path.dirname(sweepFile), "triage.html")))}">triage</a>)` : ""} · built ${esc(digest.built.slice(0, 16).replace("T", " "))} UTC</p>
${triage ? `<h2>Did the triage point the right way?</h2><table>${th(["group", "corridors", "nonsense stops per corridor", "issues per corridor"])}
<tr><td>top suspicion scores</td><td class="num">${triage.top.corridors}</td><td class="num">${triage.top.nonsenseStopsPerCorridor ?? "–"}</td><td class="num">${triage.top.issuesPerCorridor ?? "–"}</td></tr>
<tr><td>random controls</td><td class="num">${triage.controls.corridors}</td><td class="num">${triage.controls.nonsenseStopsPerCorridor ?? "–"}</td><td class="num">${triage.controls.issuesPerCorridor ?? "–"}</td></tr></table>
<p class="muted">If the controls hold about as much nonsense as the top picks, the score is not finding it: widen the review or retune <code>suspicion()</code> in sweep-lib.mjs.</p>` : ""}
<h2>Verdicts by stop</h2><table>${th(["stop", "ok", "questionable", "nonsense", "mean rating"])}${stops.map(s => `<tr><td>${s.stop} ${esc(s.style)}</td><td class="num">${s.ok}</td><td class="num">${s.questionable}</td><td class="num">${s.nonsense}</td><td class="num">${s.meanRating ?? "–"}</td></tr>`).join("")}</table>
<h2>Issues by kind</h2><table>${th(["kind", "issues", "high confidence", "corridors", ...STYLES.map((s, i) => `${i + 1}`)])}
${issueList.map(k => `<tr><td><details><summary>${esc(k.kind)}</summary>${k.examples.map(e => `<div><b>${esc(e.corridor)}</b> stop ${e.stop}${e.km ? `, km ${esc(e.km)}` : ""} [${esc(e.confidence)}]${e.roads ? ` ${esc(e.roads)}:` : ""} ${esc(e.description)}</div>`).join("")}</details></td><td class="num">${k.count}</td><td class="num">${k.high}</td><td class="num">${k.corridors}</td>${k.byStop.map(n => `<td class="num">${n || ""}</td>`).join("")}</tr>`).join("\n")}</table>
<h2>Automatic checks as the agents judged them</h2><table>${th(["check", "real problem", "harmless", "unsure", "not ruled on"])}${[...flags.values()].map(f => `<tr><td>${esc(f.kind)}</td><td class="num">${f["real problem"]}</td><td class="num">${f.harmless}</td><td class="num">${f.unsure}</td><td class="num">${f["not ruled on"]}</td></tr>`).join("")}</table>
<h2>Corridors, most nonsense first</h2><table>${th(["corridor", ...(sweep ? ["pick", "score"] : []), "stops 1–7", "summary", "overview"])}
${corridors.map(c => `<tr><td>${esc(c.id)}${c.planId && sweep ? `<br><a class="muted" href="${esc(sweep.rater)}/#plan=${esc(c.planId)}">open plan</a>` : ""}</td>${sweep ? `<td>${esc(c.pick || "")}</td><td class="num">${c.score ?? ""}</td>` : ""}<td><code>${esc(c.verdicts || "")}</code></td><td>${c.error ? `<b>${esc(c.error.split("\n")[0])}</b>` : `${esc(c.summary)}${c.nonsenseStops.length ? `<ul>${c.nonsenseStops.map(s => `<li><b>${esc(s.style)}:</b> ${esc(s.comment)}</li>`).join("")}</ul>` : ""}${c.betterRoads.length ? `<div class="muted">better roads: ${esc(c.betterRoads.join(" | "))}</div>` : ""}`}</td><td>${c.overview ? `<a href="${esc(c.overview)}"><img src="${esc(c.overview)}" alt="${esc(c.id)} overview"></a>` : ""}</td></tr>`).join("\n")}</table>
<p class="muted">Verdicts: o ok, q questionable, n nonsense, stop 1 (Fastest) to 7 (Max twisty). Every finding is a pointer: check it on the map in the rater before acting on it.</p></body></html>
`;
fs.writeFileSync(out, h);
console.log(`Digest: ${out} (+ .json): ${totals.reviewed} corridors, ${issueList.reduce((n, k) => n + k.count, 0)} issues${triage ? `; nonsense stops per corridor ${triage.top.nonsenseStopsPerCorridor} top vs ${triage.controls.nonsenseStopsPerCorridor} controls` : ""}`);
