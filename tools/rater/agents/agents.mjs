// Agents review rater plans, one corridor each: 7 ride-style stops, text data + rendered maps,
// with a zoom tool, ending in a structured submit_review call. Each agent runs through pi, or with
// PROVIDER=claude-code as a headless Claude Code session. Usage: tools/rater/README.md.
// `node agents.mjs --check` checks the setup and exits.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, execFileSync } from "node:child_process";
import {
  createAgentSession, createExtensionRuntime, ModelRuntime, SessionManager, SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { initRenderer, renderImage, closeRenderer, rendererProblems, ROOT } from "./render.mjs";
import { overviewSpec, stopSpec, areaSpec, flagSpec, viaSpec, brief } from "./views.mjs";
import { RATER, raterStatus, loadPlan } from "./rater.mjs";
import { SWITCHES, SETTING_NAMES, settingsFrom, rngFor } from "./sweep-lib.mjs";
import { startMcp, closeMcp, serveTools } from "./mcp.mjs";

// Muse Spark 1.3 Contributor at xhigh came closest to Opus 5.5 high per dollar in the 2026-10-07 comparisons
// (data/rater/agent-review/compare-opus.html).
const PROVIDER = process.env.PROVIDER || "opencode-go";
// PROVIDER=claude-code runs each agent as `claude -p` with Claude Code's own login (a Claude subscription or an API
// key), THINKING as its --effort, and the review tools served over MCP from this process.
const CLAUDE = PROVIDER === "claude-code";
const CLAUDE_BIN = process.env.CLAUDE_BIN || "claude";
const CLAUDE_EFFORTS = ["low", "medium", "high", "xhigh", "max"];
// A folder with no CLAUDE.md or project memory for the sessions to pick up.
const CLAUDE_CWD = path.join(os.tmpdir(), "curvemaps-rater-agent");
const MODEL = process.env.MODEL || (CLAUDE ? "claude-sonnet-5-5" : "muse-spark-1.3-contributor");
const THINKING = process.env.THINKING || (CLAUDE ? "low" : "xhigh");
const MAX_VIEWS = 10;
// A review submitted after fewer zooms is sent back once (the second submit is always kept): the overview images are
// too coarse to judge loops, jogs and spurs, and cheap models otherwise barely zoom. MIN_VIEWS=0 turns it off.
const MIN_VIEWS = +(process.env.MIN_VIEWS ?? 4);
const MAX_FLAGS = 8;
// A provider call can hang; abort an agent after AGENT_TIMEOUT seconds and retry it once with a fresh session.
const AGENT_TIMEOUT_MS = +(process.env.AGENT_TIMEOUT || 600) * 1000;
const REVIEWS = path.join(ROOT, "data/rater/agent-review");
const PLANS = process.env.PLANS || path.join(REVIEWS, "plans");
// The default results folder gets -2, -3… rather than overwrite an earlier full run of the same model that day.
const OUT = process.env.OUT || (() => {
  const base = path.join(REVIEWS, `${new Date().toLocaleDateString("en-CA")}-${MODEL}-${THINKING}`);
  const used = dir => !process.env.ONLY && fs.existsSync(dir) && fs.readdirSync(dir).some(f => f.endsWith(".json"));
  let dir = base;
  for (let n = 2; used(dir); n++) dir = `${base}-${n}`;
  return dir;
})();
const CORRIDORS = process.env.CORRIDORS || path.join(path.dirname(new URL(import.meta.url).pathname), "corridors.json");
const ONLY = (process.env.ONLY || "").split(",").map(s => s.trim()).filter(Boolean);
// OPTIONS overrides the corridors' rider settings: random (fixed per corridor by SEED, so every model gets the same
// plans), none, or the switches to turn on. Unset, each corridor keeps the settings corridors.json gives it.
const OPTIONS = process.env.OPTIONS, SEED = process.env.SEED || "1";
function withSettings(corridor) {
  if (!OPTIONS) return corridor;
  const on = settingsFrom(OPTIONS, rngFor(`${SEED}:${corridor.id}`));
  const kept = Object.fromEntries(Object.entries(corridor.options || {}).filter(([k]) => !SWITCHES.includes(k)));
  return { ...corridor, options: { ...kept, ...Object.fromEntries(on.map(s => [s, true])) } };
}
const settingsOf = c => SWITCHES.filter(s => c.options?.[s]).map(s => SETTING_NAMES[s]).join(", ") || "no settings";

const SYSTEM = `You review route suggestions from a motorcycle route planner. The brief names the map region and the side traffic keeps to.
For one A→B trip the planner offers seven "ride style" stops, from Fastest (1) to Max twisty (7). Each stop must be a different ride from every lower stop.

Your job is quality control, mainly finding NONSENSE a human rider would laugh at, and secondarily judging whether each stop is a sensible ride for its style.

Your review goes to another agent that acts on it, not to a person. Write terse notes: fragments, no preamble, no recap, no politeness, no filler. Quote a figure from the brief only when it is the evidence.
Text outside tool calls costs tokens and nobody reads it. Write it only to record a finding or to reason through a decision, in one or two terse lines (e.g. "F3: roundabout, harmless." or "stop 6 = stop 4 to km 80; check the Esk end."). Never announce or narrate a step ("Let me zoom…", "Now I'll check…", "Zooming showed…" with nothing new), never restate the brief, and never summarise the review: once submit_review has recorded it, your whole reply is the single word "done".

Nonsense to look for (use these kinds):
- "pointless loop": the ride leaves a road and comes back to it, circles a block, town or suburb, or makes a loop that serves no purpose.
- "out-and-back spur": drives into a road and comes back the same way.
- "street zig-zag": weaves through residential/grid streets when a through road would do.
- "detour is the whole ride": a big excursion (e.g. a square detour into farm roads and straight back) that does not serve the trip and is not a known good riding road.
- "same as another stop": looks the same as another stop, or differs only trivially.
- "not curvier than lower stop": a higher stop is clearly less curvy or less interesting than a lower one.
- "too long for its style" / "too timid for its style".
- "urban slog": a twisty stop spends lots of its time in towns, suburbs or traffic lights.
- "misses obvious better road": a well-known riding road nearby that a curvy stop should have used. Name it and say how sure you are; never invent roads.
- "surface risk": unpaved/track/unknown surface on a stop that should be a sealed ride.
- "other".

How to work:
- You get a text brief and map images: one overview with all stops, one image per stop (pink = that stop, grey = other stops, yellow labels = km from A along the pink ride, A green = start, B red = end, v = a "look-around" via point the planner routed through, thin black dashes = the curviest roads in the corridor), then zooms of flagged spots.
- The brief gives each stop road by road (km range, manoeuvre, road class, surface, curvy share, climb, urban share), the towns passed, and automatic checks that code ran on the geometry: "returns to an earlier point" (loop, spur or road driven twice), "doubles back" (a U-shaped detour, even on a parallel road), "leaves and rejoins a road" (a short jog off a road and back onto it), U-turn instructions and unpaved stretches.
- "forced via detour": to make a stop differ from the stops below it, the planner sometimes forces it through a look-around via point (v). Its flag image shows the stop's ride (pink) over where the same stop goes WITHOUT the via (blue dashes). Ask: would a rider choose the pink stretch over the blue one for this style? A forced stretch that climbs a road and comes straight back down a neighbouring one, pokes into a side area and returns, or adds distance without better road is nonsense even when each road on it is fine; a forced stretch that takes a genuinely better road is fine. The checks are exact about WHERE but not about whether it matters: a return can be a harmless roundabout or dual-carriageway crossing, or real nonsense. Flags with an id ([F1]…) come with a zoomed image; rule on every one of those in auto_flags.
- The brief's Options line gives the rider's settings the plan used. Avoid motorways on: motorways and trunk roads carry a heavy penalty (9x extra travel time) at every stop, so long motorway or trunk stretches need a reason (no other way across). Hills factor on: hilly roads count as fun on the curvy stops, so a hilly but less curvy road can be a fair choice there. Avoid stops & towns on: traffic lights, stop signs and town streets are penalised at every stop, so a ride through town centres or past many lights is a problem. Judge each stop with these settings in mind.
- The brief ends with the curviest named roads in the corridor, measured from the routing graph, with how many km each stop rides of each. This is grounded local knowledge: a long, very curvy, sealed road that no curvy stop uses is the first place to look for "misses obvious better road". Combine it with what you know about the area (famous riding roads, closures, gravel) and check on the map that the road actually goes the right way before claiming it. Prefer roads from this list over roads from memory, and say which you used.
- The km labels and the road sequence let you say WHERE a problem is. Always give km ranges and road names.
- Zooming is the core of the job, not an extra: the overview and stop images are too coarse to tell a pointless loop, jog or spur from a harmless junction. Before submitting, zoom with render_view on every flag you would otherwise rule "unsure", around A and B, around via points, wherever a ride doubles back, leaves and rejoins a road, or wiggles, and along the most promising unused corridor road (to check it really connects before you name it). Expect to use 4 to ${MAX_VIEWS} views (${MAX_VIEWS} at most).
- Be concrete and honest. If something only looks odd at overview scale but is fine when zoomed, mark it fine. No generic advice.
- Finish by calling submit_review exactly once. Do not write the review as plain text, before or after.`;

const ISSUE_KINDS = ["pointless loop", "out-and-back spur", "street zig-zag", "detour is the whole ride", "same as another stop",
  "not curvier than lower stop", "too long for its style", "too timid for its style", "urban slog", "misses obvious better road", "surface risk", "other"];

const reviewSchema = Type.Object({
  corridor_summary: Type.String({ description: "At most 25 words: the main problem first, or that the set is fine." }),
  stops: Type.Array(Type.Object({
    stop: Type.Integer({ minimum: 1, maximum: 7 }),
    verdict: Type.Union([Type.Literal("ok"), Type.Literal("questionable"), Type.Literal("nonsense")]),
    ride_rating: Type.Integer({ minimum: 1, maximum: 5, description: "How good this looks as a ride for its style (1 bad, 5 great)." }),
    issues: Type.Array(Type.Object({
      kind: Type.Union(ISSUE_KINDS.map(k => Type.Literal(k))),
      km_from: Type.Optional(Type.Number()),
      km_to: Type.Optional(Type.Number()),
      roads: Type.Optional(Type.String({ description: "Road names involved." })),
      description: Type.String({ description: "At most 25 words: what is wrong and why. km and roads go in their own fields." }),
      confidence: Type.Union([Type.Literal("low"), Type.Literal("medium"), Type.Literal("high")]),
    })),
    comment: Type.String({ description: "At most 12 words, or empty when the verdict and issues say it all." }),
  }), { minItems: 7, maxItems: 7 }),
  set_issues: Type.Array(Type.Object({
    kind: Type.Union(ISSUE_KINDS.map(k => Type.Literal(k))),
    stops: Type.Array(Type.Integer({ minimum: 1, maximum: 7 })),
    description: Type.String({ description: "At most 25 words." }),
  }), { description: "Problems across stops: repeats, curviness order, etc." }),
  better_roads: Type.Array(Type.String(), { description: "Riding roads a curvy stop should use here, each as \"<road>; <confidence>; listed|memory\" (listed = in the corridor list). Empty if none." }),
  auto_flags: Type.Array(Type.Object({
    flag: Type.String({ description: "The flag id, e.g. F1." }),
    verdict: Type.Union([Type.Literal("real problem"), Type.Literal("harmless"), Type.Literal("unsure")]),
    comment: Type.String({ description: "At most 15 words: why." }),
  }), { description: "One entry per flag id [F…] in the brief." }),
});

const img = buf => ({ type: "image", data: buf.toString("base64"), mimeType: "image/jpeg" });

async function review(corridor, modelRuntime, model, outDir, info) {
  const plan = await loadPlan(corridor, info, PLANS);
  const title = `${plan.request.from.name} → ${plan.request.to.name}`;
  const log = { corridor: corridor.id, planId: plan.planId, algo: plan.algo, model: `${PROVIDER}/${MODEL}`, thinking: THINKING, views: [], prose: [], started: new Date().toISOString() };
  const save = (name, buf) => fs.writeFileSync(`${outDir}/img/${corridor.id}-${name}.jpg`, buf);

  const images = [];
  const overview = await renderImage(overviewSpec(plan, title));
  save("overview", overview);
  images.push(img(overview));
  for (let level = 0; level < 7; level++) {
    const buf = await renderImage(stopSpec(plan, level, title));
    save(`stop${level + 1}`, buf);
    images.push(img(buf));
  }

  // Flag the distinct oddity spots (several stops often share one); zoom on each.
  const flags = [];
  const KIND_ORDER = ["forced via detour", "U-turn instruction", "returns to an earlier point", "doubles back", "leaves and rejoins a road", "unpaved"];
  // Every stop the planner forced through a look-around via point is a flag of its own, drawn over its unforced route.
  const viaFlags = plan.stops.filter(s => s.viaDetour).map(s => ({ level: s.level, o: {
    kind: "forced via detour", fromKm: s.viaDetour.fromKm, toKm: s.viaDetour.toKm, lat: s.via[0], lon: s.via[1], via: true,
    detail: `the planner forced this stop through a look-around via point (km ${s.viaDetour.viaKm}) to make it differ from lower stops; km ${s.viaDetour.fromKm}–${s.viaDetour.toKm} (${s.viaDetour.detourKm} km) replaces ${s.viaDetour.replacesKm} km of where this stop goes without the via`,
  } }));
  for (const v of viaFlags) (plan.routes[plan.stops[v.level].routeKey].oddities ||= []).unshift(v.o);
  const all = [...viaFlags, ...plan.stops.flatMap(s => (plan.routes[s.routeKey].oddities || []).filter(o => !o.via).map(o => ({ o, level: s.level })))];
  all.sort((a, b) => KIND_ORDER.indexOf(a.o.kind) - KIND_ORDER.indexOf(b.o.kind) || (b.o.toKm - b.o.fromKm) - (a.o.toKm - a.o.fromKm));
  for (const { o, level } of all) {
    const near = !o.via && flags.find(f => f.o.kind === o.kind && Math.abs(f.o.lat - o.lat) < 0.004 && Math.abs(f.o.lon - o.lon) < 0.004);
    if (near) { near.levels.push(level); o.flag = near.id; continue; }
    if (flags.length >= MAX_FLAGS) continue;
    const id = `F${flags.length + 1}`;
    o.flag = id;
    flags.push({ id, o, level, levels: [level] });
  }
  for (const f of flags) {
    const buf = await renderImage(f.o.via ? viaSpec(plan, f.level, title) : flagSpec(plan, f.level, title, f.o));
    save(`flag-${f.id}`, buf);
    images.push(img(buf));
  }
  log.flags = flags.map(f => ({ id: f.id, kind: f.o.kind, stops: f.levels.map(l => l + 1), fromKm: f.o.fromKm, toKm: f.o.toKm }));
  const flagText = flags.map((f, i) => `${9 + i}) [${f.id}] ${f.o.kind} on stop ${f.level + 1}, km ${f.o.fromKm}–${f.o.toKm}${f.levels.length > 1 ? ` (same spot on stops ${[...new Set(f.levels)].map(l => l + 1).join(", ")})` : ""}`).join("; ");

  let submitted = null;
  const renderView = {
    name: "render_view",
    label: "Render map view",
    description: "Render a zoomed map image. Either (a) stop + from_km + to_km: that stop's ride between two km marks (km from A), or (b) lat + lon + radius_km: an area, with the given stops (default all) drawn in their overview colours.",
    parameters: Type.Object({
      stop: Type.Optional(Type.Integer({ minimum: 1, maximum: 7 })),
      from_km: Type.Optional(Type.Number()),
      to_km: Type.Optional(Type.Number()),
      lat: Type.Optional(Type.Number()),
      lon: Type.Optional(Type.Number()),
      radius_km: Type.Optional(Type.Number({ minimum: 0.1, maximum: 50 })),
      stops: Type.Optional(Type.Array(Type.Integer({ minimum: 1, maximum: 7 }))),
      why: Type.String({ description: "A few words: what you are checking." }),
    }),
    execute: async (_id, p) => {
      if (log.views.length >= MAX_VIEWS) return { content: [{ type: "text", text: `View budget (${MAX_VIEWS}) used up. Submit your review.` }], details: {} };
      const slot = { ...p };
      log.views.push(slot); // reserve before rendering, so parallel calls cannot overrun the budget
      let spec, caption;
      if (p.lat != null && p.lon != null) {
        const r = p.radius_km ?? 2;
        spec = areaSpec(plan, p.lat, p.lon, r, p.stops?.length ? p.stops : [1, 2, 3, 4, 5, 6, 7], title);
        caption = `Area ${r} km around ${p.lat}, ${p.lon}`;
      } else if (p.stop != null) {
        const from = p.from_km ?? 0, to = p.to_km ?? from + 5;
        spec = stopSpec(plan, p.stop - 1, title, from, to);
        caption = `Stop ${p.stop}, km ${from}–${to}`;
      } else {
        log.views.splice(log.views.indexOf(slot), 1);
        return { content: [{ type: "text", text: "Give stop+from_km+to_km or lat+lon(+radius_km)." }], details: {} };
      }
      const buf = await renderImage(spec);
      const n = log.views.indexOf(slot) + 1;
      slot.n = n;
      save(`view${n}`, buf);
      return { content: [{ type: "text", text: caption }, img(buf)], details: {} };
    },
  };
  const submit = {
    name: "submit_review",
    label: "Submit review",
    description: "Submit the final structured review. Call exactly once, at the end.",
    parameters: reviewSchema,
    execute: async (_id, p) => {
      if (log.views.length < MIN_VIEWS && log.sentBack == null) {
        log.sentBack = log.views.length;
        return { content: [{ type: "text", text: `Not recorded: you zoomed ${log.views.length} times. Zoom on at least ${MIN_VIEWS - log.views.length} more suspicious spots with render_view (flags you are unsure of, A, B, via points, an unused corridor road), revise the review, then call submit_review again.` }], details: {} };
      }
      submitted = p;
      return { content: [{ type: "text", text: "Recorded. Reply with exactly: done" }], details: {} };
    },
  };

  const text = `${brief(plan, corridor)}\nImages attached, in order: 1) overview of all seven stops; 2-8) stop 1 … stop 7, each on its own${flags.length ? `; then the flagged spots: ${flagText}` : ""}.\nReview this corridor now. Zoom first: call render_view on the suspicious spots (4 to ${MAX_VIEWS} views; parallel calls are fine), and only then call submit_review.`;
  log.briefChars = text.length;
  const usage = { input: 0, output: 0, cacheRead: 0, reasoning: 0, cost: 0, turns: 0, proseChars: 0 };
  const t0 = Date.now();
  await (CLAUDE ? runClaude : runPi)({ text, images, tools: [renderView, submit], log, usage, isSubmitted: () => !!submitted, model, modelRuntime, outDir });
  if (log.timedOut && !submitted) log.error = `timed out after ${AGENT_TIMEOUT_MS / 1000} s`;
  log.usage = usage;
  log.seconds = Math.round((Date.now() - t0) / 1000);
  log.review = submitted;
  if (!submitted && !log.error && log.providerErrors) log.error = `provider: ${log.providerErrors.at(-1)}`;
  fs.writeFileSync(`${outDir}/${corridor.id}.json`, JSON.stringify(log, null, 1));
  const retried = submitted && log.providerErrors ? `, ${log.providerErrors.length} provider error(s) retried` : "";
  console.log(`${corridor.id}: ${submitted ? "submitted" : "NO REVIEW"} in ${log.seconds}s, ${log.views.length} views, ${usageText(log)}${retried}${log.error ? " ERROR " + log.error.split("\n")[0] : ""}`);
  return log;
}
const usageText = l => `${l.usage.input} in / ${l.usage.output} out tokens, ${l.usage.turns} turns`;
const NUDGE = "Call submit_review now.";

/** One pi agent session. Fills [log] (finalText, prose, error, timedOut) and [usage]. */
async function runPi({ text, images, tools, log, usage, isSubmitted, model, modelRuntime, outDir }) {
  const resourceLoader = {
    getExtensions: () => ({ extensions: [], errors: [], runtime: createExtensionRuntime() }),
    getSkills: () => ({ skills: [], diagnostics: [] }),
    getPrompts: () => ({ prompts: [], diagnostics: [] }),
    getThemes: () => ({ themes: [], diagnostics: [] }),
    getAgentsFiles: () => ({ agentsFiles: [] }),
    getSystemPrompt: () => SYSTEM,
    getSystemPromptSource: () => undefined,
    getAppendSystemPrompt: () => [],
    getAppendSystemPromptSources: () => [],
    extendResources: () => {},
    reload: async () => {},
  };
  const { session } = await createAgentSession({
    cwd: outDir,
    model,
    thinkingLevel: THINKING,
    modelRuntime,
    resourceLoader,
    noTools: "builtin",
    customTools: tools,
    sessionManager: SessionManager.inMemory(outDir),
    settingsManager: SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: true, maxRetries: 3 } }),
  });
  const timer = setTimeout(() => { log.timedOut = true; session.abort().catch(() => {}); }, AGENT_TIMEOUT_MS);
  try {
    await session.prompt(text, { images });
    if (!isSubmitted() && !log.timedOut) await session.prompt(NUDGE);
    log.finalText = session.getLastAssistantText();
  } catch (e) {
    log.error = String(e?.stack || e);
  } finally {
    clearTimeout(timer);
    for (const m of session.messages) if (m.role === "assistant" && m.usage) {
      usage.turns++;
      // pi retries a failed provider call; keep each failure, and report it as the error only without a review.
      if (m.stopReason === "error" && m.errorMessage) (log.providerErrors ||= []).push(m.errorMessage);
      for (const c of Array.isArray(m.content) ? m.content : []) if (c.type === "text" && c.text.trim()) { usage.proseChars += c.text.length; log.prose.push(c.text); }
      usage.input += m.usage.input; usage.output += m.usage.output; usage.cacheRead += m.usage.cacheRead;
      usage.reasoning += m.usage.reasoning || 0; usage.cost += m.usage.cost?.total || 0;
    }
    session.dispose();
  }
}

/**
 * One agent as a lean headless Claude Code session: SYSTEM replaces Claude Code's whole system prompt, and there are
 * no built-in tools, settings files, hooks, plugins, skills, other MCP servers, CLAUDE.md or saved session; the
 * review tools come from this process over MCP (as mcp__rater__<name>). What remains is a fixed ~550-token preamble.
 * The parent's Claude Code session variables are dropped so a run from inside Claude Code starts clean.
 * `cost` is Claude Code's figure at API prices; on a subscription it counts against the plan's limits instead.
 */
function runClaude({ text, images, tools, log, usage, isSubmitted }) {
  const mcp = serveTools(tools);
  fs.mkdirSync(CLAUDE_CWD, { recursive: true });
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(CLAUDECODE|CLAUDE_CODE_|CLAUDE_PID$|CLAUDE_EFFORT$)/.test(k)));
  env.CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = "1"; // no update checks, telemetry or error reports per session
  const child = spawn(CLAUDE_BIN, [
    "-p", "--model", MODEL, "--effort", THINKING,
    "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
    "--system-prompt", `${SYSTEM}\nThe tools are named mcp__rater__render_view and mcp__rater__submit_review.`,
    "--tools", "",
    "--mcp-config", JSON.stringify({ mcpServers: { rater: { type: "http", url: mcp.url } } }), "--strict-mcp-config",
    "--allowedTools", tools.map(t => `mcp__rater__${t.name}`).join(","),
    "--setting-sources", "", "--no-session-persistence", "--disable-slash-commands",
  ], { cwd: CLAUDE_CWD, env, stdio: ["pipe", "pipe", "pipe"] });
  const send = content => child.stdin.write(`${JSON.stringify({ type: "user", message: { role: "user", content } })}\n`);
  child.stdin.on("error", () => {}); // a session that already exited
  return new Promise(resolve => {
    let buf = "", stderr = "", nudged = false;
    const prose = new Set(); // stream events can repeat a message's blocks
    const timer = setTimeout(() => {
      log.timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 5000).unref();
    }, AGENT_TIMEOUT_MS);
    child.stdout.on("data", d => {
      buf += d;
      for (let i; (i = buf.indexOf("\n")) >= 0;) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        let e;
        try { e = JSON.parse(line); } catch { continue; }
        if (e.type === "system" && e.subtype === "init") {
          log.auth = e.apiKeySource === "none" ? "login" : e.apiKeySource; // "none": a claude.ai login, no API key
          const rater = e.mcp_servers?.find(m => m.name === "rater");
          if (rater?.status !== "connected") { log.error = `the review tools did not connect to Claude Code (${rater?.status ?? "missing"})`; child.kill(); }
        } else if (e.type === "assistant") {
          for (const c of e.message?.content ?? []) if (c.type === "text") prose.add(`${e.message.id}\u0000${c.text}`);
        } else if (e.type === "result") {
          const u = e.usage ?? {};
          usage.input += (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0);
          usage.output += u.output_tokens ?? 0;
          usage.cacheRead += u.cache_read_input_tokens ?? 0;
          usage.reasoning += u.output_tokens_details?.thinking_tokens ?? 0;
          usage.cost = e.total_cost_usd ?? usage.cost; // already the session total
          usage.turns += e.num_turns ?? 0;
          log.finalText = e.result ?? "";
          if (e.is_error) log.error = `${e.subtype}: ${e.result ?? ""}`.trim();
          if (!isSubmitted() && !nudged && !e.is_error && !log.timedOut) {
            nudged = true;
            send([{ type: "text", text: NUDGE }]);
          } else child.stdin.end();
        }
      }
    });
    child.stderr.on("data", d => { stderr += d; });
    child.on("error", e => { log.error ||= `cannot run ${CLAUDE_BIN}: ${e.message}`; });
    child.on("close", code => {
      clearTimeout(timer);
      mcp.close();
      for (const p of prose) {
        const text = p.slice(p.indexOf("\u0000") + 1);
        if (text.trim()) { usage.proseChars += text.length; log.prose.push(text); }
      }
      if (code && !log.error && !log.timedOut) log.error = `${CLAUDE_BIN} exited with ${code}: ${stderr.trim().split("\n").slice(-3).join(" ")}`;
      resolve();
    });
    send([...images.map(i => ({ type: "image", source: { type: "base64", media_type: i.mimeType, data: i.data } })), { type: "text", text }]);
  });
}

/**
 * Checks everything a run needs (Node, renderer inputs, the rater, the model and its credentials, the corridors)
 * and returns what it found; `problems` lists each failure with how to fix it.
 */
async function preflight() {
  const problems = [];
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major < 22 || (major === 22 && minor < 19)) problems.push(`Node ${process.versions.node} is too old: pi needs 22.19 or newer`);
  problems.push(...rendererProblems());

  const { info, problems: raterProblems } = await raterStatus();
  problems.push(...raterProblems);

  let modelRuntime = null, model = null, claudeAuth = null;
  if (CLAUDE) {
    let status = null;
    try {
      status = JSON.parse(execFileSync(CLAUDE_BIN, ["auth", "status"], { encoding: "utf8", timeout: 30_000 }));
    } catch (e) {
      if (e.code === "ENOENT") problems.push(`Claude Code (${CLAUDE_BIN}) is not installed: install it, or set CLAUDE_BIN`);
      else try { status = JSON.parse(e.stdout); } catch { problems.push(`${CLAUDE_BIN} auth status failed: ${e.message.split("\n")[0]}`); }
    }
    if (status && !status.loggedIn) problems.push(`Claude Code is not logged in: run ${CLAUDE_BIN}, then /login`);
    else if (status) claudeAuth = process.env.ANTHROPIC_API_KEY ? "ANTHROPIC_API_KEY (billed per token)" : `${status.authMethod} login`;
    if (!CLAUDE_EFFORTS.includes(THINKING)) problems.push(`Claude Code has no effort "${THINKING}"; use one of ${CLAUDE_EFFORTS.join(", ")}`);
  } else if (!(modelRuntime = await ModelRuntime.create()).getProvider(PROVIDER)) problems.push(`pi has no provider "${PROVIDER}"`);
  else if (!(model = modelRuntime.getModel(PROVIDER, MODEL))) problems.push(`pi has no model ${PROVIDER}/${MODEL}; it knows ${modelRuntime.getModels(PROVIDER).map(m => m.id).join(", ")}`);
  else {
    const levels = Object.entries(model.thinkingLevelMap || {}).filter(([, v]) => v != null).map(([k]) => k);
    if (model.thinkingLevelMap && !levels.includes(THINKING)) problems.push(`${MODEL} has no thinking level "${THINKING}"; use one of ${levels.join(", ")}`);
    if (!model.input?.includes("image")) problems.push(`${MODEL} does not take images, and the review is mostly maps`);
    if (!modelRuntime.hasConfiguredAuth(PROVIDER)) problems.push(`no ${PROVIDER} credentials: run pi, then /login and pick ${PROVIDER} (or set its API key variable; see pi's docs/providers.md)`);
  }

  let corridors = [];
  try {
    const all = JSON.parse(fs.readFileSync(CORRIDORS, "utf8"));
    corridors = all.filter(c => !ONLY.length || ONLY.includes(c.id));
    try { corridors = corridors.map(withSettings); } catch (e) { problems.push(`OPTIONS=${OPTIONS}: ${e.message}`); corridors = []; }
    const unknown = ONLY.filter(id => !all.some(c => c.id === id));
    if (unknown.length) problems.push(`ONLY names unknown corridors (${unknown.join(", ")}); ${CORRIDORS} has ${all.map(c => c.id).join(", ")}`);
    if (!corridors.length) problems.push(`no corridors to review in ${CORRIDORS}`);
  } catch (e) {
    problems.push(`cannot read ${CORRIDORS}: ${e.message}`);
  }
  return { problems, info, modelRuntime, model, claudeAuth, corridors };
}

const { problems, info, modelRuntime, model, claudeAuth, corridors } = await preflight();
console.log(`model ${PROVIDER}/${MODEL}, ${CLAUDE ? "effort" : "thinking"} ${THINKING}${claudeAuth ? ` (${claudeAuth})` : ""}; rater ${RATER}${info?.region ? ` (${info.region.name}, data ${info.region.dataDate?.slice(0, 10)})` : ""}`);
console.log(`${corridors.length} corridor(s)${OPTIONS ? ` (OPTIONS=${OPTIONS}${OPTIONS === "random" ? `, SEED=${SEED}` : ""})` : ""}:\n${corridors.map(c => `  ${c.id}: ${settingsOf(c)}`).join("\n")}`);
console.log(`results ${OUT}\nplans ${PLANS}`);
if (problems.length) {
  console.error(`\nNot ready:\n${problems.map(p => `- ${p}`).join("\n")}`);
  process.exit(1);
}
if (process.argv.includes("--check")) {
  console.log("\nReady.");
  process.exit(0);
}

fs.mkdirSync(`${OUT}/img`, { recursive: true });
const t0 = Date.now();
const logs = [];
await initRenderer(3);
if (CLAUDE) await startMcp();
try {
  // PARALLEL agents at a time (default 5).
  const queue = [...corridors];
  const parallel = +(process.env.PARALLEL || 5);
  await Promise.all(Array.from({ length: parallel }, async () => {
    for (let c; (c = queue.shift());) {
      try {
        let log = await review(c, modelRuntime, model, OUT, info);
        if (!log.review && log.timedOut) { console.log(`${c.id}: retrying after timeout`); log = await review(c, modelRuntime, model, OUT, info); }
        logs.push(log);
      } catch (e) { console.error(`${c.id}: ${e.message}`); }
    }
  }));
} finally {
  await closeRenderer();
  closeMcp();
}
const reviewed = logs.filter(l => l.review).length;
const cost = logs.reduce((sum, l) => sum + l.usage.cost, 0);
const onPlan = CLAUDE && !process.env.ANTHROPIC_API_KEY && /claude\.ai/.test(claudeAuth) ? " (not charged: it counts against your Claude plan's usage limits)" : "";
console.log(`\n${reviewed} of ${corridors.length} corridors reviewed in ${Math.round((Date.now() - t0) / 1000)} s, $${cost.toFixed(3)} at catalog prices${onPlan}.`);
const rel = path.relative(process.cwd(), OUT);
console.log(`Results: ${OUT}\nCompare runs: npm run compare -- ${rel.startsWith("../..") ? OUT : rel} <other run dir>`);
if (reviewed < corridors.length) process.exitCode = 1;
