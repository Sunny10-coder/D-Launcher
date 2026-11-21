/**
 * Optional FCM delivery.
 *
 * Polling is the default, always-available transport. When a Firebase service
 * account is configured (FCM_SERVICE_ACCOUNT_JSON) this module can additionally
 * push a data-only notification so calls wake a backgrounded phone faster.
 *
 * The token exchange below follows FCM HTTP v1 (OAuth2 JWT bearer). It has NOT
 * been exercised against a real Firebase project in this environment; treat it as
 * opt-in and verify it in your own deployment before relying on it.
 */
interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
  project_id: string;
}

function base64Url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function accessToken(account: ServiceAccount): Promise<string | null> {
  try {
    const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const now = Math.floor(Date.now() / 1000);
    const claims = base64Url(
      JSON.stringify({
        iss: account.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: account.token_uri ?? "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      }),
    );
    const key = await crypto.subtle.importKey(
      "pkcs8",
      pemToPkcs8(account.private_key),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const signature = await crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      key,
      new TextEncoder().encode(`${header}.${claims}`),
    );
    const assertion = `${header}.${claims}.${base64Url(new Uint8Array(signature))}`;
    const response = await fetch(account.token_uri ?? "https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    if (!response.ok) return null;
    const body = (await response.json()) as { access_token?: string };
    return body.access_token ?? null;
  } catch {
    return null;
  }
}

export async function sendPush(
  serviceAccountJson: string | undefined,
  deviceToken: string,
  data: Record<string, string>,
): Promise<boolean> {
  if (!serviceAccountJson || !deviceToken) return false;
  let account: ServiceAccount;
  try {
    account = JSON.parse(serviceAccountJson) as ServiceAccount;
  } catch {
    return false;
  }
  const token = await accessToken(account);
  if (!token) return false;
  try {
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: deviceToken,
            data,
            android: { priority: "HIGH" },
          },
        }),
      },
    );
    return response.ok;
  } catch {
    return false;
  }
}
