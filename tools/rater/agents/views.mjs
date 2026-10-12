// Turns a rater plan into map specs (for render.mjs) and a text brief for an agent.
import { drivesOnTheLeft } from "./rater.mjs";

export const COLORS = ["#1f77b4", "#17becf", "#2ca02c", "#9a9a00", "#ff7f0e", "#d62728", "#9467bd"];
const FOCUS = "#e6007e";

function hav(a, b) {
  const R = 6371000, toR = Math.PI / 180;
  const dLat = (b[0] - a[0]) * toR, dLon = (b[1] - a[1]) * toR;
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * toR) * Math.cos(b[0] * toR) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}
export function cumulative(coords) {
  const out = [0];
  for (let i = 1; i < coords.length; i++) out.push(out[i - 1] + hav(coords[i - 1], coords[i]));
  return out;
}
function bounds(coordLists) {
  let b = [90, 180, -90, -180];
  for (const cs of coordLists) for (const [lat, lon] of cs) b = [Math.min(b[0], lat), Math.min(b[1], lon), Math.max(b[2], lat), Math.max(b[3], lon)];
  return b;
}
function niceStep(totalKm) {
  const raw = totalKm / 10;
  for (const s of [0.1, 0.2, 0.25, 0.5, 1, 2, 5, 10, 20, 25, 50]) if (s >= raw) return s;
  return 100;
}
function ticks(coords, fromKm = 0, toKm = Infinity) {
  const cum = cumulative(coords), total = cum[cum.length - 1] / 1000;
  const span = Math.min(toKm, total) - fromKm;
  const step = niceStep(span);
  const out = [];
  let next = Math.ceil(fromKm / step) * step, i = 1;
  while (next <= Math.min(toKm, total) + 1e-9) {
    while (i < cum.length && cum[i] / 1000 < next) i++;
    if (i >= cum.length) break;
    const a = coords[i - 1], b = coords[i], d0 = cum[i - 1] / 1000, d1 = cum[i] / 1000;
    const t = d1 > d0 ? (next - d0) / (d1 - d0) : 0;
    out.push({ lat: a[0] + t * (b[0] - a[0]), lon: a[1] + t * (b[1] - a[1]), label: `${+next.toFixed(2)} km` });
    next += step;
  }
  return out;
}
function slice(coords, fromKm, toKm) {
  const cum = cumulative(coords);
  return coords.filter((_, i) => cum[i] / 1000 >= fromKm && cum[i] / 1000 <= toKm);
}
const pct = v => v == null ? "–" : Math.round(v * 100) + "%";
const f1 = v => v == null ? "–" : (+v).toFixed(1);

function endpoints(plan) {
  const q = plan.request;
  return [{ lat: q.from.lat, lon: q.from.lon, label: "A", color: "#0a0" }, { lat: q.to.lat, lon: q.to.lon, label: "B", color: "#c00" }];
}
const geom = (plan, s) => plan.routes[s.routeKey].geometry;
export const CORRIDOR = "#000";
/** Thin black dashed lines: the curviest named roads the graph has in the corridor (plan.corridorRoads with geometry). */
function corridorLines(plan) {
  return (plan.corridorRoads || []).flatMap(r => (r.lines || []).map(coords => ({ coords, color: CORRIDOR, width: 2.6, opacity: 0.9, dash: true })));
}
const CORRIDOR_LEGEND = `<br><span class=sw style="background:repeating-linear-gradient(90deg,#000 0 8px,transparent 8px 13px)"></span>curviest roads in the corridor (from the graph)`;

export function overviewSpec(plan, title) {
  const stops = [...plan.stops].reverse();
  return {
    routes: [...corridorLines(plan), ...stops.map(s => ({ coords: geom(plan, s), color: COLORS[s.level], width: 3 + s.level * 1.7, opacity: 0.95 }))],
    markers: endpoints(plan),
    bounds: bounds(plan.stops.map(s => geom(plan, s))),
    legend: `<b>${title}</b> — all seven stops (wider line = higher stop, drawn underneath)<br>` + plan.stops.map(s => {
      const f = plan.routes[s.routeKey].features;
      return `<span class=sw style="background:${COLORS[s.level]}"></span>${s.level + 1} ${s.style}: ${f1(f.timeMin)} min, ${f1(f.distanceKm)} km, ${pct(f.curvyShare)} curvy`;
    }).join("<br>") + CORRIDOR_LEGEND,
    padTop: 240,
  };
}

export function stopSpec(plan, level, title, fromKm = null, toKm = null) {
  const stop = plan.stops[level];
  const coords = geom(plan, stop);
  const others = plan.stops.filter(s => s.level !== level && s.routeKey !== stop.routeKey);
  const zoomed = fromKm != null && toKm != null;
  const focusBounds = zoomed ? slice(coords, fromKm, toKm) : coords;
  const f = plan.routes[stop.routeKey].features;
  const markers = endpoints(plan);
  if (stop.via) markers.push({ lat: stop.via[0], lon: stop.via[1], label: "v", color: "#fc0" });
  return {
    routes: [
      ...corridorLines(plan),
      ...others.map(s => ({ coords: geom(plan, s), color: "#444", width: 2.5, opacity: 0.45 })),
      { coords, color: FOCUS, width: 6, casing: true },
    ],
    ticks: ticks(coords, zoomed ? fromKm : 0, zoomed ? toKm : Infinity),
    markers,
    bounds: bounds([focusBounds.length > 1 ? focusBounds : coords]),
    legend: `<b>${title}</b><br><span class=sw style="background:${FOCUS}"></span><b>Stop ${level + 1} ${stop.style}</b>: ${f1(f.timeMin)} min, ${f1(f.distanceKm)} km, ${pct(f.curvyShare)} curvy`
      + (zoomed ? `<br>zoomed to km ${fromKm}–${toKm}` : "")
      + `<br><span class=sw style="background:#444;opacity:.5"></span>other stops · yellow labels = km along this ride from A` + (stop.via ? " · v = look-around via point" : "") + CORRIDOR_LEGEND,
  };
}

export function areaSpec(plan, lat, lon, radiusKm, levels, title) {
  const dLat = radiusKm / 111.2, dLon = radiusKm / (111.2 * Math.cos(lat * Math.PI / 180));
  const chosen = plan.stops.filter(s => levels.includes(s.level + 1));
  return {
    routes: [...chosen].reverse().map(s => ({ coords: geom(plan, s), color: COLORS[s.level], width: 3 + s.level * 1.7, opacity: 0.95 })),
    markers: endpoints(plan),
    ticks: chosen.length === 1 ? ticks(geom(plan, chosen[0])) : [],
    bounds: [lat - dLat, lon - dLon, lat + dLat, lon + dLon],
    maxZoom: 18,
    legend: `<b>${title}</b> — area ${radiusKm} km around ${lat.toFixed(4)}, ${lon.toFixed(4)}<br>` +
      chosen.map(s => `<span class=sw style="background:${COLORS[s.level]}"></span>${s.level + 1} ${s.style}`).join("<br>"),
  };
}

const STYLE_INTENT = [
  "Fastest: quickest route; ignores curves.",
  "Mostly fast: a little curvier than Fastest if it costs little time.",
  "Balanced: trades some time for curvier roads.",
  "Curvy: clearly prefers curvy roads; may take noticeably longer.",
  "Twisty: strongly prefers twisty roads; long detours acceptable.",
  "Very twisty: halfway between Twisty and Max twisty.",
  "Max twisty: the twistiest sensible ride; may take up to ~2x the time of its own optimum.",
];

/** The text an agent gets for one corridor. */
export function brief(plan, corridor) {
  const q = plan.request;
  const beeline = hav([q.from.lat, q.from.lon], [q.to.lat, q.to.lon]) / 1000;
  const lines = [];
  lines.push(`Corridor: ${q.from.name} (${q.from.lat.toFixed(5)}, ${q.from.lon.toFixed(5)}) → ${q.to.name} (${q.to.lat.toFixed(5)}, ${q.to.lon.toFixed(5)})`);
  lines.push(`Straight-line distance A→B: ${beeline.toFixed(1)} km. Map: ${plan.region.name}, OSM data ${plan.region.dataDate}; traffic drives on the ${drivesOnTheLeft(plan.region) ? "left" : "right"}.`);
  lines.push(`Options: hills factor ${q.elevation ? "on" : "off"}, avoid stops & towns ${q.stopsAndTowns ? "on" : "off"}, avoid motorways ${q.avoidMotorways ? "on" : "off"}, block unpaved ${q.blockUnpaved ? "on" : "off"}.`);
  lines.push("");
  lines.push("What each stop of the ride-style slider is meant to be (each stop must be a different ride from every lower stop):");
  STYLE_INTENT.forEach((t, i) => lines.push(`  ${i + 1}. ${t}`));
  lines.push("");
  for (const s of plan.stops) {
    const r = plan.routes[s.routeKey], f = r.features;
    lines.push(`=== Stop ${s.level + 1}: ${s.style} ===`);
    lines.push(`time ${f1(f.timeMin)} min, distance ${f1(f.distanceKm)} km, avg ${f.avgKmh} km/h, ×Fastest time ${f.timeVsFastest} / distance ${f.distanceVsFastest}`);
    lines.push(`curvy share ${pct(f.curvyShare)} (${f.curvyKm} km), longest sustained curvy stretch ${f.longestCurvyRunKm} km, curvy stretches ≥2 km: ${f.curvyRunsOver2Km}, ≥5 km: ${f.curvyRunsOver5Km}, bends/km ${f.bendsPerKm}, tight bends/km ${f.tightBendsPerKm}`);
    lines.push(`climb ${f.climbM} m, turns per 10 km ${f.turnsPer10Km}, traffic stops ${f.stops ?? "n/a"}, motorway/trunk ${pct(f.motorwayTrunkShare)}, urban ${pct(f.urbanShare)}, residential/service/track roads ${pct(f.minorRoadShare)}, unpaved ${pct(f.unpavedShare)}, unknown surface ${pct(f.unknownSurfaceShare)}`);
    lines.push(`road classes km: ${Object.entries(f.roadClassKm).map(([k, v]) => `${k} ${v}`).join(", ")}`);
    lines.push(`how the planner found it: ${s.stage}${s.round ? ` (search round ${s.round})` : ""}, curve weight ${s.curveWeight}${s.roadPenalty ? `, penalty on lower stops' roads ${s.roadPenalty}x` : ""}${s.via ? `, routed through look-around via point ${s.via[0]}, ${s.via[1]}` : ""}, budget class ${s.budget}${s.repeats ? `, REPEATS ${s.repeats} (no other road found)` : ""}; time vs this stop's own unconstrained optimum ${s.timeVsStyleOptimum}`);
    lines.push(`shared roads with other stops: ${Object.entries(s.overlapWith).map(([k, v]) => `stop ${+k + 1} ${Math.round(v * 100)}%`).join(", ")}`);
    if (r.places?.length) lines.push(`towns/places passed (km from A): ${r.places.map(([k, t]) => `${k} ${t}`).join(" → ")}`);
    if (r.segments?.length) {
      lines.push("road by road (km from–to | manoeuvre | road | class, surface | curvy share | climb | urban share):");
      const rows = r.segments.map(g => `  ${f1(g.fromKm)}–${f1(g.toKm)} | ${g.turn} | ${g.name} | ${g.roadClass}, ${g.surface}${g.unpavedM ? ` (${g.unpavedM} m unpaved)` : ""} | ${pct(g.curvyShare)} | ${g.climbM} m | ${pct(g.urbanShare)}`);
      lines.push(...(rows.length > 70 ? [...rows.slice(0, 35), `  … ${rows.length - 70} short rows omitted …`, ...rows.slice(-35)] : rows));
    } else {
      let km = 0;
      lines.push(`road sequence: ${r.roads.map(([name, d, turn]) => { const at = km; km += d; return `${at.toFixed(1)}km ${turn} ${name} (${d} km)`; }).join(" → ")}`);
    }
    const odd = r.oddities || [];
    lines.push(odd.length ? `automatic geometry/surface checks (${odd.length}):` : "automatic geometry/surface checks: nothing flagged");
    for (const o of odd) lines.push(`  - ${o.flag ? `[${o.flag}] ` : ""}${o.kind}, km ${f1(o.fromKm)}–${f1(o.toKm)} near ${o.lat}, ${o.lon}: ${o.detail}`);
    lines.push("");
  }
  const roads = plan.corridorRoads || [];
  if (roads.length) {
    lines.push("=== Curviest named roads in the corridor (from the routing graph; black dashes on the maps) ===");
    lines.push("Every non-residential named road inside the area any of these rides could reach, ranked by curvy km (length weighted by the planner's own curve measure). 'used by' = km of that road each stop rides. The same name can cover several separate roads in different towns.");
    for (const x of roads) {
      const used = Object.entries(x.usedKmByStop).map(([k, v]) => `${k}:${v}km`).join(" ") || "NO STOP";
      lines.push(`  ${x.name} (${x.roadClass}) — ${x.km} km in corridor, ${x.curvyKm} curvy km${x.unpavedKm ? `, ${x.unpavedKm} km unpaved` : ""}, curviest near ${x.curviestAt[0]}, ${x.curviestAt[1]} — used by ${used}`);
    }
  }
  return lines.join("\n");
}

export const UNFORCED = "#0050ff";
/**
 * A zoom on the stretch a look-around via point changed: the stop's ride
 * (pink) over its unforced optimum (blue dashes), framed on both stretches.
 */
export function viaSpec(plan, level, title) {
  const stop = plan.stops[level], d = stop.viaDetour;
  // A via that moves the whole corridor (long forced stretch) is judged where it bites: 4 km around the via.
  const local = d.detourKm > 15;
  const pad = Math.max(0.5, d.detourKm * 0.2);
  const spec = local
    ? stopSpec(plan, level, title, Math.max(0, +(d.viaKm - 2).toFixed(2)), +(d.viaKm + 2).toFixed(2))
    : stopSpec(plan, level, title, Math.max(0, +(d.fromKm - pad).toFixed(2)), +(d.toKm + pad).toFixed(2));
  const opt = plan.routes[stop.optimumRouteKey]?.geometry;
  if (opt && stop.optimumRouteKey !== stop.routeKey) {
    const optSlice = slice(opt, Math.max(0, d.optimumFromKm - pad), d.optimumToKm + pad);
    spec.routes.splice(spec.routes.length - 1, 0, { coords: opt, color: UNFORCED, width: 4, opacity: 0.95, dash: true });
    if (optSlice.length > 1 && !local) spec.bounds = bounds([slice(geom(plan, stop), d.fromKm - pad, d.toKm + pad), optSlice]);
  }
  if (local) spec.legend += `<br>the forced stretch is ${d.detourKm} km (most of the ride); this view is the 4 km around the via point`;
  spec.legend += `<br><span class=sw style="background:repeating-linear-gradient(90deg,${UNFORCED} 0 8px,transparent 8px 13px)"></span>the same stop without the via point (its unforced optimum)`;
  return spec;
}

/** A zoom on one flagged spot of one stop (km range padded). */
export function flagSpec(plan, level, title, o) {
  const pad = Math.max(0.4, (o.toKm - o.fromKm) * 0.25);
  return stopSpec(plan, level, title, Math.max(0, +(o.fromKm - pad).toFixed(2)), +(o.toKm + pad).toFixed(2));
}
