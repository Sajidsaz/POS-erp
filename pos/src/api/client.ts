/**
 * The single seam between the POS and the Spring API.
 *
 * Transport: under Tauri the request goes through the Rust HTTP plugin (no browser CORS,
 * no mixed-content rules); in a plain browser it falls back to window.fetch against Vite's
 * dev proxy. Auth: a POS login yields a bearer token pair; a 401 triggers one silent
 * refresh and retry before the session is considered dead.
 */
import { isTauri } from "@tauri-apps/api/core";
import { fetch as tauriFetch } from "@tauri-apps/plugin-http";
import type { ApiError, TokenResponse } from "./types";

export interface Session {
  accessToken: string;
  refreshToken: string;
}

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: string[];

  constructor(status: number, body: ApiError | null, fallback: string) {
    super(body?.message || fallback);
    this.name = "ApiClientError";
    this.status = status;
    this.code = body?.code || "UNKNOWN";
    this.details = body?.details || [];
  }

  /** The login flow returns this code when a valid TOTP/recovery code must still be supplied. */
  get mfaRequired(): boolean {
    return this.code === "MFA_REQUIRED";
  }
}

type SessionListener = (session: Session | null) => void;

const doFetch: typeof fetch = isTauri()
  ? (tauriFetch as unknown as typeof fetch)
  : window.fetch.bind(window);

export class ApiClient {
  private baseUrl: string;
  private session: Session | null = null;
  private listeners = new Set<SessionListener>();
  private refreshing: Promise<boolean> | null = null;

  constructor(apiBaseUrl: string) {
    // In the browser we hit Vite's proxy at a relative path; under Tauri we address the
    // configured backend directly.
    this.baseUrl = isTauri() ? apiBaseUrl.replace(/\/+$/, "") : "";
  }

  setBaseUrl(apiBaseUrl: string) {
    this.baseUrl = isTauri() ? apiBaseUrl.replace(/\/+$/, "") : "";
  }

  setSession(session: Session | null) {
    this.session = session;
    this.listeners.forEach((l) => l(session));
  }

  getSession(): Session | null {
    return this.session;
  }

  onSessionChange(listener: SessionListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  // ---- verbs -------------------------------------------------------------

  get<T>(path: string): Promise<T> {
    return this.request<T>("GET", path);
  }

  post<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    return this.request<T>("POST", path, body, idempotencyKey);
  }

  del<T>(path: string): Promise<T> {
    return this.request<T>("DELETE", path);
  }

  // ---- core --------------------------------------------------------------

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    idempotencyKey?: string,
    isRetry = false,
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (this.session) headers["Authorization"] = `Bearer ${this.session.accessToken}`;
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

    let res: Response;
    try {
      res = await doFetch(this.baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (e) {
      throw new ApiClientError(0, null, `Cannot reach the server. ${(e as Error).message}`);
    }

    // A 401 on a token request means the access token lapsed: refresh once, then retry.
    if (res.status === 401 && this.session && !isRetry && !path.includes("/auth/")) {
      const refreshed = await this.refresh();
      if (refreshed) return this.request<T>(method, path, body, idempotencyKey, true);
    }

    if (res.status === 204 || res.status === 205) {
      return undefined as T;
    }

    const text = await res.text();
    const parsed = text ? safeJson(text) : null;

    if (!res.ok) {
      throw new ApiClientError(res.status, parsed as ApiError | null, `Request failed (${res.status})`);
    }
    return parsed as T;
  }

  private refresh(): Promise<boolean> {
    if (this.refreshing) return this.refreshing;
    const rt = this.session?.refreshToken;
    if (!rt) return Promise.resolve(false);

    this.refreshing = (async () => {
      try {
        const res = await doFetch(this.baseUrl + "/api/v1/auth/refresh", {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ refreshToken: rt }),
        });
        if (!res.ok) {
          this.setSession(null);
          return false;
        }
        const pair = (await res.json()) as TokenResponse;
        this.setSession({ accessToken: pair.accessToken, refreshToken: pair.refreshToken });
        return true;
      } catch {
        this.setSession(null);
        return false;
      } finally {
        this.refreshing = null;
      }
    })();
    return this.refreshing;
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { code: "NON_JSON", message: text, details: [] };
  }
}
