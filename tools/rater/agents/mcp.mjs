// A minimal MCP server (streamable HTTP, plain JSON responses, no SSE) that serves each review's tools to a
// Claude Code agent, so it calls the same render_view and submit_review as the pi agents, in this process.
// Each review registers its tools under a random path; only this machine can reach it.
import http from "node:http";
import { randomUUID } from "node:crypto";
import { Value } from "typebox/value";

const sessions = new Map(); // path token -> tools ({ name, description, parameters, execute })
let server, port;

export async function startMcp() {
  if (server) return;
  server = http.createServer(async (req, res) => {
    const tools = sessions.get(req.url.replace(/^\/mcp\//, ""));
    if (!tools) { res.writeHead(404); return res.end(); }
    if (req.method !== "POST") { res.writeHead(req.method === "DELETE" ? 200 : 405); return res.end(); }
    let body = "";
    for await (const chunk of req) body += chunk;
    let messages;
    try { messages = JSON.parse(body); } catch { res.writeHead(400); return res.end(); }
    const replies = (await Promise.all([messages].flat().map(m => answer(tools, m)))).filter(Boolean);
    if (!replies.length) { res.writeHead(202); return res.end(); }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(Array.isArray(messages) ? replies : replies[0]));
  });
  await new Promise((resolve, reject) => server.once("error", reject).listen(0, "127.0.0.1", resolve));
  port = server.address().port;
}

export function closeMcp() {
  server?.close();
  server = null;
}

/** Serves [tools] until the returned `close`; `url` is the MCP endpoint for --mcp-config. */
export function serveTools(tools) {
  const token = randomUUID();
  sessions.set(token, tools);
  return { url: `http://127.0.0.1:${port}/mcp/${token}`, close: () => sessions.delete(token) };
}

async function answer(tools, m) {
  if (m.id === undefined) return null; // a notification
  const ok = result => ({ jsonrpc: "2.0", id: m.id, result });
  switch (m.method) {
    case "initialize":
      return ok({ protocolVersion: m.params?.protocolVersion || "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "curvemaps-rater", version: "1" } });
    case "ping":
      return ok({});
    case "tools/list":
      return ok({ tools: tools.map(t => ({ name: t.name, description: t.description, inputSchema: JSON.parse(JSON.stringify(t.parameters)) })) });
    case "tools/call": {
      const tool = tools.find(t => t.name === m.params?.name);
      if (!tool) return { jsonrpc: "2.0", id: m.id, error: { code: -32602, message: `unknown tool ${m.params?.name}` } };
      const args = m.params.arguments ?? {};
      if (!Value.Check(tool.parameters, args)) {
        const errors = [...Value.Errors(tool.parameters, args)].slice(0, 8).map(e => `${e.instancePath || "/"} ${e.message}`);
        return ok({ isError: true, content: [{ type: "text", text: `Invalid arguments, nothing recorded: ${errors.join("; ")}` }] });
      }
      try {
        return ok({ content: (await tool.execute(String(m.id), args)).content });
      } catch (e) {
        return ok({ isError: true, content: [{ type: "text", text: String(e?.message || e) }] });
      }
    }
    default:
      return { jsonrpc: "2.0", id: m.id, error: { code: -32601, message: `unsupported method ${m.method}` } };
  }
}
