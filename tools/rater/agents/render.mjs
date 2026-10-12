// Route image renderer: the repo's offline basemap (PMTiles + app style) in headless Chromium.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { chromium } from "playwright-core";

const HERE = path.dirname(new URL(import.meta.url).pathname);
export const ROOT = path.resolve(HERE, "../../..");
// Any free port by default, so several runs (a review and a sweep, two models) can render at once.
let port = +(process.env.RENDER_PORT || 0);
const TILES = process.env.TILES || path.join(ROOT, "data/tiles/queensland.pmtiles");
const ASSETS = path.join(ROOT, "app/src/main/assets");
// The Chromium build that matches the pinned playwright-core (`npm run setup` downloads it).
const CHROME = process.env.CHROME || chromium.executablePath();

/** What the renderer is missing, each with the command that fixes it; empty when it can start. */
export function rendererProblems() {
  const problems = [];
  if (!fs.existsSync(TILES)) problems.push(`basemap ${TILES} is missing: build it with tools/tiles/build-tiles.sh, or set TILES`);
  if (!["glyphs/Noto Sans Regular/0-255.pbf", "sprites/sprite.json"].every(f => fs.existsSync(path.join(ASSETS, f))))
    problems.push("style glyphs/sprites are missing from app/src/main/assets: run tools/style/fetch-style-assets.sh");
  if (!fs.existsSync(CHROME)) problems.push(`Chromium ${CHROME} is missing: run npm run setup, or set CHROME`);
  return problems;
}

function style() {
  const s = JSON.parse(fs.readFileSync(path.join(ASSETS, "style.json"), "utf8"));
  s.sources.omt.url = `pmtiles://http://127.0.0.1:${port}/tiles.pmtiles`;
  s.glyphs = `http://127.0.0.1:${port}/glyphs/{fontstack}/{range}.pbf`;
  s.sprite = `http://127.0.0.1:${port}/sprites/sprite`;
  // The app style has `match` expressions without a fallback (place-labels-major text-size),
  // which MapLibre GL JS rejects outright; give them their last output as the fallback.
  const fix = v => {
    if (!Array.isArray(v)) return v;
    v = v.map(fix);
    if (v[0] === "match" && v.length % 2 === 0) v.push(v[v.length - 1]);
    return v;
  };
  for (const layer of s.layers) for (const k of ["layout", "paint"]) if (layer[k]) for (const p in layer[k]) layer[k][p] = fix(layer[k][p]);
  return JSON.stringify(s);
}

const FILES = {
  "/render.html": [path.join(HERE, "static/render.html"), "text/html"],
  "/maplibre-gl.js": [path.join(HERE, "node_modules/maplibre-gl/dist/maplibre-gl.js"), "text/javascript"],
  "/maplibre-gl.css": [path.join(HERE, "node_modules/maplibre-gl/dist/maplibre-gl.css"), "text/css"],
  "/pmtiles.js": [path.join(HERE, "node_modules/pmtiles/dist/pmtiles.js"), "text/javascript"],
};

let server;
export async function startStatic() {
  if (server) return;
  let styleJson;
  server = http.createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, "http://x").pathname);
    try {
      if (url === "/style.json") { res.writeHead(200, { "Content-Type": "application/json" }); return res.end(styleJson); }
      if (url === "/tiles.pmtiles") {
        const size = fs.statSync(TILES).size;
        const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
        const start = m ? +m[1] : 0, end = m && m[2] ? +m[2] : size - 1;
        res.writeHead(m ? 206 : 200, { "Content-Type": "application/octet-stream", "Accept-Ranges": "bytes",
          "Content-Length": end - start + 1, ...(m ? { "Content-Range": `bytes ${start}-${end}/${size}` } : {}) });
        return fs.createReadStream(TILES, { start, end }).pipe(res);
      }
      let file, type;
      if (FILES[url]) [file, type] = FILES[url];
      else if (url.startsWith("/glyphs/") || url.startsWith("/sprites/")) {
        file = path.join(ASSETS, url.slice(1));
        if (!file.startsWith(ASSETS)) throw new Error("bad path");
        type = url.endsWith(".png") ? "image/png" : url.endsWith(".json") ? "application/json" : "application/x-protobuf";
      }
      if (!file || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { "Content-Type": type });
      fs.createReadStream(file).pipe(res);
    } catch (e) { res.writeHead(500); res.end(String(e)); }
  });
  await new Promise((resolve, reject) => server.once("error", reject).listen(port, "127.0.0.1", resolve));
  port = server.address().port;
  styleJson = style();
}

let browser;
const pages = [];
const waiters = [];
export async function initRenderer(poolSize = 3) {
  await startStatic();
  browser = await chromium.launch({
    executablePath: CHROME,
    args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
  });
  for (let i = 0; i < poolSize; i++) {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
    page.on("pageerror", e => console.error("render pageerror:", e.message));
    await page.goto(`http://127.0.0.1:${port}/render.html`);
    await page.evaluate(() => window.ready);
    pages.push(page);
  }
}
async function acquire() {
  if (pages.length) return pages.pop();
  return new Promise(r => waiters.push(r));
}
function release(page) { const w = waiters.shift(); if (w) w(page); else pages.push(page); }

/** Renders a spec to a JPEG buffer. */
export async function renderImage(spec) {
  const page = await acquire();
  try {
    await page.evaluate(s => window.render(s), spec);
    return await page.screenshot({ type: "jpeg", quality: 82 });
  } finally { release(page); }
}
export async function closeRenderer() { await browser?.close(); server?.close(); }
