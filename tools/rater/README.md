# Route rater

A local web page for rating the planner's rides. It runs the app's own
seven-stop search (`DistinctRideStyles.resolve` through the production
request builder, weighting and graph loader) on the desktop, so you can rate
each stop's ride as good or bad. Every rating is saved together with the data
that describes the route.

```sh
tools/rater/run.sh            # then open http://127.0.0.1:8765
```

Ctrl-C stops it. The script runs the opt-in JVM "test"
`app/src/test/java/com/curvemaps/app/rater/RouteRaterServer.kt` with
`RATER=1` and `--rerun`. Options:
- `RATER_PORT` (default 8765)
- `RATER_DATA` (default `data/rater`, gitignored)
- `RATER_MAP_SERVER` (default `https://maps.curvemaps.app`)

The test JVM gets a 4 GB heap when `RATER=1` (`app/build.gradle.kts`; Gradle's
default 512 MB runs out on a whole state such as New South Wales), and the
server loads or computes at most two plans at once, so a burst of requests
queues instead of running it out of memory.

Restart the script after changing routing or rater code. Gradle recompiles,
and new plans are tagged with the new code hashes (`algo.hash` for routing,
`algo.context` for the rater's features and reviewer context). Until then,
`/api/info` reports `"codeChanged": true`.

## Maps

The rater routes on any of these:
- The **bundled graph**: `app/src/main/assets/graph-cache`, else `data/graph-cache`.
- A region **downloaded from the map service**: open *Map data → Browse downloadable maps*.
- A **local `.motomap`**: enter its path, for example `data/package/queensland.motomap`.

Installing a package verifies the download against the catalog SHA-256. It
extracts only the graph and the geocoder, checking each file against its
manifest digest and refusing unsafe paths and other graph profiles. The result
goes to `data/rater/regions/<packageId>/`. The basemap is OSM, OpenTopoMap or
Esri raster tiles, so the tool needs a network connection, but routing and
place search stay local.

## Rating

1. Set From and To. Either search (Enter; `lat,lon` works too) or click the
   map: the first click sets From, the next sets To, and pins can be dragged.
   The plan runs as soon as both are set. Toggle the ride factors and road
   toggles to match what you want to test.
2. Select a stop: click its row, use `1`–`7`, or `←`/`→`. Then rate it: `G`
   good, `B` bad, `C` clear. Optionally tick reasons and add a note first
   (e.g. "should use Mt Mee Rd"). After a rating, the next stop is selected.
3. `V` (or *Candidates*) draws every distinct ride the search found for this
   stop, with the verdict the resolver gave it: `STYLE`, `RELAXED`,
   `NONE (…)`, *same ride as a lower stop*, *drives a road twice*, and so on.
   Rate a candidate the same way. A good candidate that the planner did not
   show is a **selection** failure. A stop where you know a better road and no
   candidate takes it is a **generation** failure.

A rating belongs to a stop *and* a route. The same road can be right for
Twisty and wrong for Mostly fast.

Plans are deterministic. Each one is saved under an id derived from the
request, the code hash and the graph, and reopens instantly from *History*.
Tick *recompute* to force a fresh search. Plans made by an agent sweep stay out
of History unless you tick *Show sweep plans*, and `#plan=<id>` in the page
address opens any saved plan (the sweep pages link to them this way).

## Replay after a code change

*History → Replay rated requests* (or `GET /api/replay`) re-plans every rated
request on the loaded map with the current code. For each stop it shows
whether the stop now shows a ride rated good (✓), bad (✗) or unrated (·),
plus the totals `goodNoLongerChosen` and `badNoLongerChosen`. Ratings carry
over between code versions whenever a stop produces exactly the same road
sequence.

## Data

The files live in `data/rater/`:

| file | contents |
|---|---|
| `ratings.jsonl` | Append-only, one self-contained line per rating. Each line has the request, map, code hash/git, stop, the stop's search meta (stage, round, curve weight, penalty, via, budget), the candidate verdict, the route's `features` and the shown ride's `chosenFeatures`. The last line per (plan, stop, route) wins; `"rating": null` clears it. |
| `plans/<planId>.json` | The full plan: the 7 stops, every search each stop made (failed ones included), and features and geometry per distinct route. |
| `plans.jsonl` | One summary line per plan (for History). Sweep plans carry a `tag` and are listed only by `/api/plans?all=1`, until someone plans the same request without one. |

*Export JSON* (`/api/export.json`) and *Export CSV* (`/api/export.csv`, flat
feature columns) give you the latest rating per route.

Each shown ride also carries reviewer context (`RaterContext.kt`):
- `segments`: road by road, with km range, manoeuvre, dominant class and
  surface, unpaved metres, curvy share, climb and urban share.
- `oddities`: what the code flags without map knowledge:
  - *returns to an earlier point*: within 60 m of where it was ≥500 m earlier
    (loops, spurs, a road driven twice);
  - *doubles back*: a 2 km stretch that ends under 500 m from its start
    (U-shaped detours, even on a parallel road);
  - *leaves and rejoins a road*: back on the same named road within 1 km,
    counted only when the stretch off it is ≥1.4× and ≥100 m longer than the
    straight line, so a highway renamed through a town is not a jog;
  - *street tangle*: 500 m across ≥3 roads that ends under 225 m from its
    start (back-and-forth through streets; skipped within 250 m of A or B);
  - U-turn instructions and unpaved stretches of 200 m or more.
- `places`: the towns passed, from the offline geocoder.

Each stop also records `optimumRouteKey` (the same stop's unforced optimum)
and, when the planner forced it through a look-around via point, `viaDetour`:
the stretch around the via that the optimum does not drive (`fromKm`–`toKm`,
`detourKm`) and the optimum stretch it replaces (`replacesKm`; 0 means a pure
loop).

The planner chooses the seven rides together (`RideLadder`, see AGENTS.md) from
everything its stop-by-stop searches found. A stop showing a ride another
stop's search found records `foundBy` (that stop's label). The plan's
`selection` keeps the stop-by-stop ladder the choice started from:
`settled` (per stop: `routeKey`, `stage`, `budget`, `repeats`,
`timeVsStyleOptimum`, `overlapWith`), both scores (`settledCost`,
`chosenCost`: −ln of the chance no stop is called nonsense, plus repeats and
budget breaches), `poolSize` (different rides found) and `changed` (stops whose
ride the choice changed), so one sweep compares both ladders. `problem` is the
choice's whole input, for re-scoring a ladder offline: the candidates in order
(`keys`: route keys; `foundBy`: the stop index whose search found each;
`curviness`, `gravel`, `lookAround`, `lasso`), `ownOptimum` (a candidate index
per stop), `budgets` and `timeOverOptimum` (per stop and candidate), `same`
(pairs that are the same ride) and `overlap` (shared-road share, candidate by
candidate).

The plan's `corridorRoads` lists the 25 curviest named non-motorway, non-residential roads
inside the ellipse any shown ride could reach, ranked by curve exposure × km.
For each road it gives the km every stop rides, and the top 15 also carry
geometry. A long curvy road that no curvy stop rides points to a generation
failure.

Features per route (`RaterFeatures.kt`):
- **Speed:** time, distance, average speed, and time/distance vs Fastest and
  vs the stop's own optimum.
- **Curves:** curvy share (the route card's figure), curvy km, longest
  sustained curvy run (straights up to 300 m bridged), runs ≥2 km and ≥5 km,
  bends and tight bends (<60 m radius) per km, and mean edge curve and hill
  exposure.
- **Edge shares:** share of straight edges and very curvy edges.
- **Climb and stops:** climb (with the 10 m hysteresis), stops and stops per
  10 km, turns per 10 km.
- **Road mix:** km by road class, surface, urban density and road environment,
  plus the motorway/trunk, minor-road, urban, unpaved and unknown-surface
  shares.

## Agent review (experimental)

`tools/rater/agents/` runs one AI agent per corridor over rater plans to find
nonsense: loops, spurs, street zig-zags, repeated stops, curviness out of
order, gravel, and riding roads the planner missed. The agents use the pi SDK
(`@earendil-works/pi-coding-agent`), or headless Claude Code with
`PROVIDER=claude-code`. The default model is
`opencode-go/muse-spark-1.3-contributor` at xhigh thinking, which came
closest to Opus 5.5 high per dollar in the 2026-10-07 comparisons. The output
is advice: nothing writes ratings.

Each agent gets:
- each stop's numbers, its road-by-road table and the towns passed;
- the automatic checks (`oddities`);
- the corridor's curviest roads, with what each stop rides of them;
- a map of all stops, and a map per stop (that stop in pink with km labels
  and its numbers, the other stops faint grey), rendered offline from
  `data/tiles/queensland.pmtiles` with the app's style in headless Chromium,
  with the corridor roads as black dashes;
- a zoom of up to 8 distinct flagged spots (`[F1]`…). Every forced-via stop
  is one, drawn over its unforced route (blue dashes); a forced stretch over
  15 km is shown as the 4 km around the via;
- a zoom tool (10 views).

It finishes with one structured `submit_review` call and must rule on every
flag (real problem / harmless / unsure).

### Setup (once)

1. **Map data.** The renderer needs `data/tiles/queensland.pmtiles`
   (`tools/tiles/build-tiles.sh`) and the style's glyphs and sprites
   (`tools/style/fetch-style-assets.sh`). The rater needs a map (see Maps).
2. **Node 22.19 or newer**, then:
   ```sh
   cd tools/rater/agents
   npm ci           # pi SDK, MapLibre GL JS, pmtiles, playwright-core, pinned by package-lock.json
   npm run setup    # downloads the Chromium build that the pinned playwright-core drives
   ```
3. **Model login.** pi keeps credentials in `~/.pi/agent/auth.json`, outside
   the repo; never copy them into it. Run `npx pi`, type `/login` and pick
   OpenCode Go, or export `OPENCODE_API_KEY`. Other providers are listed in
   pi's `docs/providers.md` (in `node_modules/@earendil-works/pi-coding-agent/`).
   For `PROVIDER=claude-code`, log in to Claude Code itself (`claude`, then
   `/login`); a Claude subscription works. Do not use pi's Anthropic
   "Claude Pro/Max" login: it borrows Claude Code's sign-in, which Anthropic's
   terms do not allow outside Claude Code.
4. `npm run check` checks all of the above, plus the running rater, the model
   and its thinking level, and the corridors. It prints how to fix each
   problem.

### Run

```sh
tools/rater/run.sh &                         # from the repo root; plans come from the running rater
cd tools/rater/agents
npm run check
npm run review                               # every corridor in corridors.json, 5 at a time
ONLY=nerang-canungra npm run review          # some corridors (comma-separated ids)
PROVIDER=openai-codex MODEL=gpt-6-luna THINKING=xhigh npm run review   # another model
PROVIDER=claude-code MODEL=claude-opus-5-5 THINKING=high npm run review  # through Claude Code
```

A run checks the setup first and stops if anything is missing. With Muse
xhigh, the five corridors take about 4 minutes and cost about $0.07 (catalog
prices); the slowest corridor sets the time. The run ends with a total and the
command to compare it with another run.

With `PROVIDER=claude-code`, each agent is a lean `claude -p` session: the
review prompt replaces Claude Code's whole system prompt, and it loads no
built-in tools, settings files, hooks, plugins, skills, CLAUDE.md or other MCP
servers, and saves no session. `render_view` and `submit_review` are served
to it over MCP from the run's own process (`mcp.mjs`). Claude Code still adds
a fixed preamble of about 550 tokens, under 1% of a corridor. On a Claude
subscription the reported cost is Claude Code's API-price figure; the run
counts against the plan's usage limits instead.

Environment variables:

| variable | default | meaning |
|---|---|---|
| `PROVIDER`, `MODEL`, `THINKING` | `opencode-go`, `muse-spark-1.3-contributor`, `xhigh` | pi model and thinking level (`check` names a model's valid levels). `PROVIDER=claude-code` runs `claude -p` instead: `MODEL` defaults to `claude-sonnet-5-5` and `THINKING` is its effort (low to max) |
| `CLAUDE_BIN` | `claude` | Claude Code executable for `PROVIDER=claude-code` |
| `ONLY` | all | comma-separated corridor ids |
| `OPTIONS`, `SEED` | unset, 1 | override every corridor's rider settings: `random` (fixed per corridor by `SEED`, so every model gets the same plans), `none`, or the switches to turn on (`avoidMotorways,elevation,stopsAndTowns,blockUnpaved`) |
| `PARALLEL` | 5 | agents at a time |
| `MIN_VIEWS` | 4 | a review submitted after fewer zooms is sent back once to zoom more (the second submit is kept); `0` turns it off |
| `AGENT_TIMEOUT` | 600 | seconds before a stuck agent is aborted and retried once with a fresh session (provider calls can hang) |
| `OUT` | `data/rater/agent-review/<date>-<model>-<thinking>` | results folder |
| `PLANS` | `data/rater/agent-review/plans` | the plans a review's agents saw (a sweep saves only the ones it picks for review: the rater stores every plan) |
| `REFETCH` | unset | `1` replans every corridor even when its cached plan is current |
| `CORRIDORS` | `corridors.json` | corridor list |
| `RATER` | `http://127.0.0.1:8765` | rater URL |
| `TILES`, `CHROME`, `RENDER_PORT` | repo basemap, Playwright's Chromium, any free port | renderer inputs |

`corridors.json` is a list of `{"id", "from": {"lat", "lon", "name"}, "to": {…}, "options": {…}}`.
`options` holds the rider settings, which are the rater's plan switches:
`avoidMotorways`, `elevation` (hills), `stopsAndTowns` and `blockUnpaved`. Each
benchmark corridor has a fixed mix, with avoid motorways, hills and stops &
towns each on for 3 corridors and off for 2, so every model reviews the same
plans. The agents' brief names the settings and the system prompt explains
what each one changes. Every run before the 2026-10-07 `*-settings` runs
planned with all settings off.

### Results

`OUT` gets `<corridor>.json` and `img/` (the overview, stop, flag and zoom
images the agent saw). It is gitignored. A rerun with the same `OUT`
overwrites only the corridors it reviews, so `ONLY=… OUT=<earlier run>`
repairs one corridor of a run. Each JSON holds:
- `corridor`, `planId`, `algo` (the plan's code hashes), `model`, `thinking`
  and `started`;
- `flags` (id, kind, stops, km) and `views` (each zoom the agent asked for);
  `sentBack` holds the zoom count when a review was sent back for too few;
- `usage` (tokens, turns, catalog cost, and `proseChars`: the length of text
  the agent wrote outside tool calls) and `seconds`;
- `prose`: that text itself. The prompt allows it only for a finding or a
  line of reasoning, never narration ("Let me zoom…") or a recap;
- `timedOut`/`error` when something failed, and `providerErrors` (provider calls pi retried);
- `review`, the `submit_review` arguments. The review is written for the agent
  that runs the review, not for a person: terse notes with word limits per
  field; after submitting, the agent replies only "done" (models ignore "write
  nothing" and recap instead).
  - `corridor_summary`;
  - `stops`: verdict ok/questionable/nonsense, a 1–5 rating, issues with kind,
    km, roads and confidence, and a comment;
  - `set_issues`: problems across stops;
  - `better_roads`;
  - `auto_flags`: one verdict per flag.

### Comparing runs

```sh
npm run compare -- ../../../data/rater/agent-review/2026-10-07-ds-low-v3=DeepSeek \
                   ../../../data/rater/agent-review/2026-10-07-luna-xhigh-v3=Luna
```

This writes one HTML page (`--out`, default `compare.html` in the folder that
holds the first run) with:
- totals per run;
- the flags that runs on the same plan judged differently;
- every corridor side by side, with flag images.

Flags are matched by plan, kind, stops and km, not by id. `--scores FILE` adds
a hand-written scorecard of known problems
(`{"rows": [["problem", "✓", "✗"]], "legend": "…", "read": "…"}`, one cell per
run).

### Sweeps: when you don't know where to look

```sh
npm run sweep -- --bbox -28.3,151.9,-26.4,153.6               # South East Queensland: plan, triage, review, digest
npm run sweep -- --bbox -28.3,151.9,-26.4,153.6 --plan-only   # only the free part
```

1. **Places.** The box (`south,west,north,east`) is split into `--cells` ×
   `--cells` (default 6). Each cell gets its most important place from the
   rater's geocoder (`/api/places`): cities, then towns, villages and suburbs,
   of at least `--min-rank` (default 160). Cells without one (sea, forest)
   drop out.
2. **Trips.** Every pair of places `--km` apart (default 15–150 km, straight
   line) gets a random direction. The pairs are sampled down to
   `--max-pairs` (default 300):
   - a third each of short, medium and long trips;
   - spread so that every place starts or ends about as many trips.

   Each trip's rider settings are random: avoid motorways, hills and stops &
   towns each on or off, so a sweep covers every combination. `--options`
   overrides this: `none` turns them all off, and a comma-separated list
   (`avoidMotorways,elevation,stopsAndTowns,blockUnpaved`) turns those on for
   every trip. The random settings come from their own stream, so they never
   change which places, trips or controls are picked. `--seed` (default 1)
   makes every choice repeatable.
3. **Triage (free).** The rater plans every trip, `--jobs` at a time
   (default 3; about 10 minutes for 300). Each plan gets a suspicion score
   from the rater's own checks:
   - forced-via detours;
   - the geometry checks, counted once per spot (unpaved stretches weigh most
     on the fast stops, and at most 3 in all);
   - stops over budget, and repeats;
   - near copies (93% of roads shared with a lower stop);
   - stops less curvy than a lower one;
   - curvy roads on the way with more curvy km than the curviest stop's whole
     ride, which no curvy stop rides.

   `triage.html` shows:
   - how common each signal is;
   - for each setting, the plans with it on against those with it off (mean
     score and signal shares);
   - a map of the grid, places and trips;
   - every trip by score, with its settings, its reasons and a link that
     opens its plan in the rater.
4. **Review.** The picked plans are saved to `PLANS` one at a time, so the
   agents read them from disk instead of asking the rater for many at once.
   The agents (`agents.mjs`, same environment variables) review:
   - the `--review` highest scores (default 25), each place in at most
     `--per-place` of them (default 2);
   - `--controls` random other trips (default 5), which show what the score
     misses.
5. **Digest.** `digest.html` and `digest.json` show:
   - whether the top picks held more nonsense than the controls;
   - verdicts by stop;
   - issues by kind, with every instance;
   - how the agents judged each automatic check;
   - every corridor, most nonsense first.

Everything goes to `data/rater/agent-review/sweep-<date>-<n>x<n>-s<seed>/`,
plus `-plain` or the switches when `--options` is set (`--out` overrides):

| file | contents |
|---|---|
| `sweep.json` | settings, places, every trip with its plan id, score and reasons, and the share of plans per signal |
| `corridors.json` | the review list (`CORRIDORS=… OUT=… npm run review` reruns it) |
| `triage.html` | the triage page |
| `review/` | the agents' results |
| `digest.html`, `digest.json` | the digest (`npm run digest -- <review dir>` rebuilds it) |

**Cost.** The triage costs nothing, but the rater stores every plan in
`data/rater/plans/` (about 1 MB each, so about 330 MB for 300 trips). With
Muse xhigh, the default 30 reviews cost about $0.45 and take about 20 minutes.

**For an agent running a sweep:**
1. Run `npm run check`, then the sweep.
2. Read `digest.json`: totals, triage, issues by kind, corridors.
3. Open the worst corridors' images in `review/img/`.
4. Report patterns, not single verdicts, and check the strongest ones on the
   map.

The score lives in `suspicion()` in `sweep-lib.mjs` (`npm test` covers the
sweep logic). If the controls hold as much nonsense as the top picks, the score
is missing something.

### Plans and caching

Plans are requested without `fresh`. The rater answers from its saved plan
only when the plan's routing hash, rater code hash (`algo.context`) and graph
match, and plans again otherwise (`REFETCH=1` always plans again). A review
also keeps the plans its agents saw in `PLANS`. A dropped connection or a
server error is retried twice (after 3 and 10 s) before a corridor fails, and
every provider error pi retried is kept in its result's `providerErrors`
(the run line says how many a submitted review recovered from; the last one
becomes `error` when the corridor got no review).

The rater runs the code it was started with, so restart `tools/rater/run.sh`
after changing routing or rater code (`check` says when). A plan id covers the
request, the routing code and the graph only, so ratings survive rater
changes. Two runs on the same plan id can still have seen different reviewer
context (see `algo.context` in their results).

### Acting on findings

Treat every finding as a pointer, never as a rating:
1. Open the plan in the rater: its link on the triage or digest page, or
   *History, replay & export*, then click its row.
2. Look at the stop and rate it there.
3. After a routing fix, use Replay and rerun the agents.

### What the comparisons found (2026-10-07)

The runs and their comparison pages are in `data/rater/agent-review/`
(gitignored, on the machine that ran them).
- **Defects the code flags:** with the automatic checks and flag zooms, both
  DeepSeek v4.1 flash (low) and GPT-6 Luna (xhigh) caught every known one,
  including forced-via U-detours and street tangles.
- **Missed riding roads:** DeepSeek named more of them (Mount Mee, Beechmont,
  Tamborine–Oxenford, Mount Nebo/Glorious).
- **Speed and cost:** Luna was faster (305 s against 723 s for the slowest
  corridor), cheaper ($0.075 against $0.106) and raised fewer false alarms.
- **Flag verdicts disagree:** on the same plans, the two models disagreed on
  8 of 30 flags, mostly short interchange loops and block jogs.
- **Runs vary:** two DeepSeek runs on the same Nerang plan split on the
  forced-via U-detour itself (harmless, then real problem).

On the settings plans (fixed rider-settings mix, five corridors), with Opus
5.5 high as the reference (`compare-opus.html`; scored on flag verdicts, stop
verdicts, issue kind per stop and named riding roads):
- **Opus 5.5 high** (through Claude Code, $3.48 at API prices) was the most
  thorough, and the only model besides Sol low to name Beechmont Road.
- **Muse Spark 1.3 Contributor at xhigh** (the default) scored 68% and 61%
  in two runs, for $0.07–0.09 and about 4–6 minutes. Its stop verdicts
  matched Opus best; it names fewer missed riding roads.
- **Sol 6.1 low** scored 71% ($0.64 catalog, ChatGPT login) and **DeepSeek
  low** 65% ($0.10).
- **Muse needs the zoom instruction and xhigh:** before it, Muse zoomed 2
  times in five corridors at low and 15 at high. With it, high zoomed 39
  times but its verdicts did not improve; xhigh zoomed 45 times.

Muse and Opus ran with the terse review prompt, DeepSeek and Sol with the
earlier one, so issue counts are not directly comparable.

Settle a disputed verdict on the map, and run a second model or a second pass
when the verdicts matter.
