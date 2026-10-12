// The running rater (tools/rater/run.sh) as the agents and the sweep use it: its info, its readiness, and plans
// cached on disk so a sweep's plans are reused by the review that follows.
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

export const RATER = process.env.RATER || "http://127.0.0.1:8765";

// Every rater request is safe to repeat; a dropped connection or a server failure (5xx) is retried after these waits
// (`retries: []` turns that off, for a quick status check).
const RETRY_MS = [3000, 10000];

export async function api(pathAndQuery, { retries = RETRY_MS, ...init } = {}) {
  for (let attempt = 0; ; attempt++) {
    let res, body;
    try {
      res = await fetch(`${RATER}${pathAndQuery}`, { signal: AbortSignal.timeout(init.timeout ?? 120_000), ...init });
      body = await res.json();
    } catch (e) {
      if (attempt < retries.length) { await new Promise(r => setTimeout(r, retries[attempt])); continue; }
      throw new Error(`rater request ${pathAndQuery.split("?")[0]} failed: ${e.cause?.code || e.message}`, { cause: e.cause ?? e });
    }
    if (res.status >= 500 && attempt < retries.length) { await new Promise(r => setTimeout(r, retries[attempt])); continue; }
    if (body.error) throw new Error(body.error);
    return body;
  }
}

/** The rater's /api/info, plus what keeps it from serving plans now (each with its fix). */
export async function raterStatus() {
  try {
    const info = await api("/api/info", { timeout: 5000, retries: [] });
    const problems = [];
    if (!info.region) problems.push(`the rater at ${RATER} has no map open: open it in a browser and pick one`);
    if (!info.algo?.context) problems.push(`the rater at ${RATER} is older than these scripts: restart tools/rater/run.sh`);
    else if (info.codeChanged) problems.push("rater or routing code changed since the rater started: restart tools/rater/run.sh so plans use it");
    return { info, problems };
  } catch (e) {
    const why = e.cause?.code || e.cause?.errors?.[0]?.code || e.message;
    return { info: null, problems: [`the rater is not reachable at ${RATER} (${why}): start it with tools/rater/run.sh, or set RATER`] };
  }
}

/**
 * The corridor's plan. The rater answers from its saved plans when the routing code, rater code and graph match,
 * and plans again otherwise (REFETCH=1 always plans again). With [cache] the plan is also kept in [plansDir] by
 * corridor and request, so a review keeps exactly what its agents saw; a sweep's triage skips that (one plan is
 * ~1 MB) and saves only the plans it picks for review.
 * `corridor.options` go into the request: the plan switches, and `tag`, which keeps sweep plans out of History.
 */
export async function loadPlan(corridor, info, plansDir, { cache = true } = {}) {
  const body = JSON.stringify({ from: corridor.from, to: corridor.to, fresh: !!process.env.REFETCH, ...corridor.options });
  const file = path.join(plansDir, `${corridor.id}-${createHash("sha256").update(JSON.stringify([corridor.from, corridor.to, corridor.options ?? {}])).digest("hex").slice(0, 8)}.json`);
  if (cache && fs.existsSync(file) && !process.env.REFETCH) {
    const cached = JSON.parse(fs.readFileSync(file, "utf8"));
    if (cached.algo?.hash === info.algo.hash && cached.algo?.context === info.algo.context
      && cached.region?.graphId === info.region.graphId) return cached;
  }
  const plan = await api("/api/plan", { method: "POST", headers: { "Content-Type": "application/json" }, body });
  if (cache) {
    fs.mkdirSync(plansDir, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(plan));
  }
  return plan;
}

/** Whether traffic keeps left on this map, from its name (catalog regions are named after countries and states). */
export function drivesOnTheLeft(region) {
  return /australia|queensland|new south wales|victoria|tasmania|northern territory|capital territory|new zealand|united kingdom|great britain|england|scotland|wales|ireland|isle of man|japan|south africa|malaysia|singapore|thailand|india|indonesia/i
    .test(`${region?.name ?? ""} ${region?.id ?? ""}`);
}
