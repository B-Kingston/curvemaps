// One HTML page comparing agent-review runs side by side. Usage (see tools/rater/README.md):
//   node compare.mjs [--out FILE] [--title TEXT] [--scores FILE] RUN_DIR[=LABEL] RUN_DIR[=LABEL] ...
// Runs that reviewed the same plan share their flags, so their verdicts on each flag line up; disagreements are
// listed first. --scores adds a hand-written scorecard: {"rows": [["problem", "cell per run", ...]], "read": "..."}.
import fs from "node:fs";
import path from "node:path";

const HERE = path.dirname(new URL(import.meta.url).pathname);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

const args = process.argv.slice(2);
const opt = name => { const i = args.indexOf(name); return i < 0 ? undefined : args.splice(i, 2)[1]; };
const outArg = opt("--out"), title = opt("--title") || "Agent review comparison", scoresFile = opt("--scores");
if (!args.length || args.some(a => a.startsWith("--"))) {
  console.error("usage: node compare.mjs [--out FILE] [--title TEXT] [--scores FILE] RUN_DIR[=LABEL] ...");
  process.exit(2);
}

const runs = args.map(arg => {
  const [dir, label] = arg.split("=");
  const logs = new Map();
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith(".json"))) {
    const log = JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
    if (log.corridor && "review" in log) logs.set(log.corridor, log);
  }
  if (!logs.size) throw new Error(`${dir} holds no review results`);
  const first = [...logs.values()][0];
  return { dir: path.resolve(dir), label: label || `${first.model.split("/").pop()} ${first.thinking} (${path.basename(dir)})`, logs };
});
const out = path.resolve(outArg || path.join(path.dirname(runs[0].dir), "compare.html"));
const link = file => esc(path.relative(path.dirname(out), file));
const shown = dir => { const rel = path.relative(path.dirname(out), dir); return esc(rel.startsWith("../..") ? dir : rel); };

// Corridors in corridors.json order, then any others.
let order = [];
try { order = JSON.parse(fs.readFileSync(path.join(HERE, "corridors.json"), "utf8")).map(c => c.id); } catch {}
const seen = new Set(runs.flatMap(r => [...r.logs.keys()]));
const ids = [...order.filter(id => seen.has(id)), ...[...seen].filter(id => !order.includes(id)).sort()];

const issueCount = log => log.review ? log.review.stops.reduce((n, s) => n + s.issues.length, 0) + log.review.set_issues.length : 0;
const totals = runs.map(r => {
  const logs = ids.map(id => r.logs.get(id)).filter(Boolean);
  return {
    reviewed: logs.filter(l => l.review).length,
    failed: ids.length - logs.filter(l => l.review).length,
    slowest: Math.max(0, ...logs.map(l => l.seconds || 0)),
    views: logs.reduce((n, l) => n + l.views.length, 0),
    flags: logs.reduce((n, l) => n + (l.flags || []).length, 0),
    input: logs.reduce((n, l) => n + l.usage.input, 0),
    output: logs.reduce((n, l) => n + l.usage.output, 0),
    cost: logs.reduce((n, l) => n + l.usage.cost, 0),
    issues: logs.reduce((n, l) => n + issueCount(l), 0),
  };
});

/**
 * Every flag of a corridor with each run's verdict. Flags are matched by plan, kind, stops and km, not by id:
 * runs on the same plan with different rater code number their flags differently.
 */
function flagRows(id) {
  const rows = new Map();
  for (const [i, r] of runs.entries()) {
    const log = r.logs.get(id);
    if (!log) continue;
    for (const f of log.flags || []) {
      const key = `${log.planId}|${f.kind}|${f.stops}|${f.fromKm}|${f.toKm}`;
      if (!rows.has(key)) rows.set(key, { flag: f, planId: log.planId, verdicts: runs.map(() => null) });
      const ruling = (log.review?.auto_flags || []).find(a => a.flag === f.id);
      rows.get(key).verdicts[i] = { id: f.id, verdict: ruling?.verdict || "not ruled on", comment: ruling?.comment || "" };
    }
  }
  return [...rows.values()].map(row => {
    const given = row.verdicts.filter(Boolean).map(v => v.verdict);
    return { ...row, disagree: given.length > 1 && new Set(given).size > 1 };
  });
}
const verdictCell = v => `<td>${v ? `<b>${esc(v.id)} ${esc(v.verdict)}</b> ${esc(v.comment)}` : `<span class="muted">not flagged</span>`}</td>`;
const flagLabel = f => `${esc(f.kind)}, stop ${f.stops.join(", ")}, km ${f.fromKm}–${f.toKm}`;

let h = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title><style>
:root{color-scheme:light;--ink:#1d1d1f;--muted:#5f6368;--line:#c9ccd1;--head:#eef0f3;--hot:#fff1d6;--note:#eef4ff}
body{font:13px/1.45 system-ui,sans-serif;margin:16px;color:var(--ink);background:#fff;max-width:1800px}
table{border-collapse:collapse;margin:6px 0 14px}td,th{border:1px solid var(--line);padding:4px 6px;vertical-align:top;text-align:left}
th{background:var(--head)}.num{text-align:right;white-space:nowrap}.hot td{background:var(--hot)}.muted{color:var(--muted)}
ul{margin:2px 0;padding-left:16px}img{max-width:300px;border:1px solid var(--line);margin:2px}.note{background:var(--note);padding:8px;max-width:1100px}
</style></head><body><h1>${esc(title)}</h1>
<p class="muted">Built ${new Date().toISOString().slice(0, 16).replace("T", " ")} UTC by tools/rater/agents/compare.mjs from ${runs.map(r => `<code>${shown(r.dir)}</code>`).join(", ")}.</p>
<table><tr><th>run</th><th>reviewed</th><th>slowest corridor</th><th>zoom views</th><th>flag images</th><th>tokens in / out</th><th>catalog cost</th><th>issues raised (stops + across stops)</th></tr>
${runs.map((r, i) => { const t = totals[i]; return `<tr><td>${esc(r.label)}</td><td class="num">${t.reviewed} of ${ids.length}${t.failed ? ` (${t.failed} missing)` : ""}</td><td class="num">${t.slowest} s</td><td class="num">${t.views}</td><td class="num">${t.flags}</td><td class="num">${t.input.toLocaleString("en")} / ${t.output.toLocaleString("en")}</td><td class="num">$${t.cost.toFixed(3)}</td><td class="num">${t.issues}</td></tr>`; }).join("\n")}
</table>`;

if (scoresFile) {
  const scores = JSON.parse(fs.readFileSync(scoresFile, "utf8"));
  h += `<h2>Scorecard</h2><table><tr><th>known problem</th>${runs.map(r => `<th>${esc(r.label)}</th>`).join("")}</tr>
${scores.rows.map(row => `<tr>${row.map(c => `<td>${esc(c)}</td>`).join("")}</tr>`).join("\n")}</table>
${scores.legend ? `<p class="muted">${esc(scores.legend)}</p>` : ""}${scores.read ? `<div class="note">${esc(scores.read)}</div>` : ""}`;
}

const disputes = ids.flatMap(id => flagRows(id).filter(r => r.disagree).map(r => ({ id, ...r })));
if (runs.length > 1) {
  h += `<h2>Flags the runs disagree on</h2>${disputes.length ? `<table><tr><th>corridor</th><th>flag</th>${runs.map(r => `<th>${esc(r.label)}</th>`).join("")}</tr>
${disputes.map(d => `<tr><td><a href="#${esc(d.id)}">${esc(d.id)}</a></td><td>${flagLabel(d.flag)}</td>${d.verdicts.map(verdictCell).join("")}</tr>`).join("\n")}</table>`
    : `<p>None: every run that got the same flag gave the same verdict.</p>`}`;
}

for (const id of ids) {
  const logs = runs.map(r => r.logs.get(id));
  // The plan id covers routing code only; algo.context (recorded since the rater code hash existed) covers the rest.
  const planOf = l => l.algo?.context ? `${l.planId}/${l.algo.context}` : l.planId;
  const plans = [...new Set(logs.filter(Boolean).map(planOf))];
  h += `<h2 id="${esc(id)}">${esc(id)}</h2><p class="muted">${plans.length === 1 ? `All runs reviewed plan ${esc(plans[0])}.` : `Plans (id/rater code): ${logs.map((l, i) => `${esc(runs[i].label)} ${esc(l ? planOf(l) : "not run")}`).join(", ")}.`}</p>
<table><tr><th></th>${runs.map(r => `<th>${esc(r.label)}</th>`).join("")}</tr>`;
  const cell = (l, body) => `<td>${!l ? `<span class="muted">not run</span>` : !l.review ? `<b>no review</b> ${esc(l.error?.split("\n")[0] || "")}` : body(l.review)}</td>`;
  h += `<tr><td>summary</td>${logs.map(l => cell(l, r => `${esc(r.corridor_summary)}<br><i>better roads:</i> ${esc(r.better_roads.join(" | ") || "none")}`)).join("")}</tr>`;
  for (let s = 0; s < 7; s++) {
    h += `<tr><td>stop ${s + 1}</td>${logs.map(l => cell(l, r => { const st = r.stops[s]; return `<b>${esc(st.verdict)} ${st.ride_rating}/5</b> ${esc(st.comment)}<ul>${st.issues.map(i => `<li>[${esc(i.kind)}, ${esc(i.confidence)}] ${i.km_from != null ? `km ${i.km_from}–${i.km_to ?? ""} ` : ""}${esc(i.description)}</li>`).join("")}</ul>`; })).join("")}</tr>`;
  }
  h += `<tr><td>across stops</td>${logs.map(l => cell(l, r => r.set_issues.length ? `<ul>${r.set_issues.map(i => `<li>[${esc(i.kind)}] stops ${i.stops.join(", ")}: ${esc(i.description)}</li>`).join("")}</ul>` : "none")).join("")}</tr></table>`;

  const rows = flagRows(id);
  if (rows.length) {
    h += `<table><tr><th>flag</th>${runs.map(r => `<th>${esc(r.label)}</th>`).join("")}</tr>
${rows.map(row => `<tr class="${row.disagree ? "hot" : ""}"><td>${flagLabel(row.flag)}${plans.length > 1 ? `<br><span class="muted">plan ${esc(row.planId)}</span>` : ""}</td>${row.verdicts.map(verdictCell).join("")}</tr>`).join("\n")}</table>`;
  }
  const withImages = runs.find(r => fs.existsSync(path.join(r.dir, "img")));
  const images = withImages ? fs.readdirSync(path.join(withImages.dir, "img")).filter(f => f.startsWith(`${id}-flag`)).sort() : [];
  if (images.length) h += `<p class="muted">Flag images from ${esc(withImages.label)}:</p><p>${images.map(f => { const src = link(path.join(withImages.dir, "img", f)); return `<a href="${src}"><img src="${src}" alt="${esc(f)}" title="${esc(f)}"></a>`; }).join("")}</p>`;
}

fs.writeFileSync(out, h + "</body></html>\n");
console.log(`${out}: ${runs.length} runs, ${ids.length} corridors, ${disputes.length} disputed flags`);
