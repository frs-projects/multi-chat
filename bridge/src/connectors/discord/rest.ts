// Outbound Discord delivery over plain fetch: webhook posts (preferred - per-message
// name/avatar) and bot channel messages as fallback. Sends to the same destination are
// serialized through a per-key promise chain and 429s are honored via retry_after;
// that is all the rate limiting a chat relay needs.

const API = "https://discord.com/api/v10";
const MAX_ATTEMPTS = 3;

export interface WebhookPayload {
  content: string;
  username?: string;
  avatar_url?: string;
  allowed_mentions: { parse: string[] };
}

export class DiscordRest {
  private readonly queues = new Map<string, Promise<void>>();

  constructor(private readonly log: (...args: unknown[]) => void) {}

  sendWebhook(webhookUrl: string, payload: WebhookPayload): Promise<void> {
    return this.enqueue(webhookUrl, () =>
      this.post(`${webhookUrl}?wait=true`, { "Content-Type": "application/json" }, payload));
  }

  sendChannelMessage(token: string, channelId: string, content: string): Promise<void> {
    return this.enqueue(`channel:${channelId}`, () =>
      this.post(`${API}/channels/${channelId}/messages`, {
        "Content-Type": "application/json",
        Authorization: `Bot ${token}`,
      }, { content, allowed_mentions: { parse: [] } }));
  }

  /** Serializes tasks per destination so retry_after sleeps hold back the whole queue. */
  private enqueue(key: string, task: () => Promise<void>): Promise<void> {
    const next = (this.queues.get(key) ?? Promise.resolve()).then(task, task);
    this.queues.set(key, next);
    return next;
  }

  private async post(url: string, headers: Record<string, string>, body: unknown): Promise<void> {
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      const resp = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
      if (resp.ok) return;
      if (resp.status === 429 && attempt < MAX_ATTEMPTS) {
        const data = (await resp.json().catch(() => ({}))) as { retry_after?: number };
        const waitMs = Math.ceil((data.retry_after ?? 1) * 1000);
        this.log(`discord rate limited, waiting ${waitMs}ms`);
        await Bun.sleep(waitMs);
        continue;
      }
      this.log(`discord send failed (${resp.status}): ${await resp.text().catch(() => "")}`);
      return; // don't retry non-429 failures; dropping one relay message is fine
    }
  }
}
