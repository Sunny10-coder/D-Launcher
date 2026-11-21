import type { DurableObjectState } from "@cloudflare/workers-types";

/**
 * CallRoom - a Durable Object that relays signalling messages between exactly two
 * authenticated clients (one parent, one child).
 *
 * The Worker authorises the WebSocket upgrade and passes the verified role in a
 * header; the Durable Object is never reachable from the internet directly. Media
 * never passes through here: only SDP offers/answers and ICE candidates.
 */
export class CallRoom {
  private sockets: { ws: WebSocket; role: string }[] = [];

  // The state object keeps the isolate alive for the duration of a call.
  constructor(private state: DurableObjectState) {
    void this.state;
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected a WebSocket upgrade", { status: 426 });
    }
    const role = request.headers.get("x-mlw-role") ?? "unknown";
    if (role !== "parent" && role !== "device") {
      return new Response("Unknown role", { status: 403 });
    }
    if (this.sockets.some((entry) => entry.role === role)) {
      return new Response("Role already connected", { status: 409 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    const peers = this.sockets.length;
    this.sockets.push({ ws: server, role });
    server.send(JSON.stringify({ type: "welcome", peers }));
    this.broadcast(server, { type: "peer-joined", role });

    server.addEventListener("message", (event) => {
      const data = typeof event.data === "string" ? event.data : "";
      if (data.length === 0 || data.length > 60_000) return;
      let parsed: unknown;
      try {
        parsed = JSON.parse(data);
      } catch {
        return;
      }
      if (typeof parsed !== "object" || parsed === null) return;
      const type = (parsed as { type?: unknown }).type;
      if (typeof type !== "string") return;
      this.broadcast(server, parsed);
    });

    const cleanup = (reason: string) => {
      this.sockets = this.sockets.filter((entry) => entry.ws !== server);
      this.broadcast(server, { type: "peer-left", reason });
    };
    server.addEventListener("close", () => cleanup("closed"));
    server.addEventListener("error", () => cleanup("error"));

    return new Response(null, { status: 101, webSocket: client });
  }

  private broadcast(sender: WebSocket, message: unknown): void {
    for (const entry of this.sockets) {
      if (entry.ws === sender) continue;
      try {
        entry.ws.send(JSON.stringify(message));
      } catch {
        // A dead socket is cleaned up by its own close handler.
      }
    }
  }
}
