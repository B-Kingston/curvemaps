# Route rater (desktop rating tool)

Usage and data format: `tools/rater/README.md`. This is the summary agents
need when touching routing.

- `tools/rater/run.sh` serves `tools/rater/index.html` at
  `http://127.0.0.1:8765`. It is an opt-in JVM "test"
  (`app/src/test/java/com/curvemaps/app/rater/RouteRaterServer.kt`, `RATER=1`,
  blocks until Ctrl-C) running the planner's real seven-stop search on the
  desktop (`DistinctRideStyles.resolve` + `buildGhRequest` +
  `loadMotoGraphHopper`), so the stops match the app.
- **Rating:** good/bad with reasons and a note, for each stop's ride and every
  candidate the search found (shown with the resolver's verdict). Each rating
  stores whole-route features (`RaterFeatures.kt`), the stop's search meta, a
  routing-code hash and the graph id, in `data/rater/ratings.jsonl`
  (append-only, latest per plan/stop/route wins) and
  `data/rater/plans/<id>.json`.
- **Maps:** the bundled graph, catalog regions from the map service, or a local
  `.motomap`; installs are verified and keep only graph and geocoder.
- The unit-test compile classpath is android.jar (no `com.sun.net.httpserver`,
  no `java.net.http`), hence `MiniHttpServer.kt` on a `ServerSocket` and
  `HttpURLConnection`.
- **Replay** (`/api/replay`, History → Replay) re-plans every rated request with
  the current code and reports how many stops now show rides rated good or
  bad. Use it with the probes as evidence when retuning `RideStyle`,
  `ComplexityWeighting` or `DistinctRideStyles`.
- **Code hashes:** plans carry `algo.hash` (routing sources, part of the plan
  id) and `algo.context` (`RaterPlanner`/`RaterContext`/`RaterFeatures`, not in
  the id, so ratings survive it). A non-fresh `/api/plan` reuses a saved plan
  only when both hashes and the graph match. The server runs the code it
  started with; `/api/info` reports `codeChanged` until you restart it.
- **Reviewer context** (`RaterContext.kt`): rides carry `segments`, `oddities`,
  `places`; stops `optimumRouteKey`, `viaDetour`; plans `corridorRoads`.
- **Agent review (experimental):** `tools/rater/agents/` runs one pi agent per
  corridor over rendered maps (default `opencode-go/muse-spark-1.3-contributor`,
  xhigh thinking), or a lean headless Claude Code session with
  `PROVIDER=claude-code` (default `claude-sonnet-5-5`, low effort; Claude Code's
  own login, the review prompt as its whole system prompt, no built-in tools or
  config, review tools served over MCP by `mcp.mjs`). `npm run check` verifies
  setup. Results and plan caches go to `data/rater/agent-review/` (gitignored),
  never `tools/`; cached plans are replanned when the routing hash, rater hash
  or graph changes. Credentials stay in `~/.pi/agent/auth.json`. The output is
  advice: nothing writes ratings.
- **Sweeps** (`npm run sweep -- --bbox S,W,N,E`), for when nobody knows where
  to look: one place per grid cell (`/api/places`), a seeded balanced sample of
  trips with random rider settings unless `--options` sets them (the triage
  compares each setting on against off; the `corridors.json` benchmark has a
  fixed mix, `OPTIONS`/`SEED` override). Every trip is triaged for free
  (`suspicion()` in `sweep-lib.mjs`, `npm test` / `sweep.test.mjs`); agents
  review the top scores plus random controls; `digest.mjs` summarises. Sweep
  requests carry `tag`, keeping their plans out of History
  (`/api/plans?all=1` lists them). `#plan=<id>` opens a saved plan.
- Tests: JVM `RaterPureTest` (curvy runs, geometry oddities, road re-entries,
  street tangles, the ratings store, History tags, `placesIn`, package installs).
