import { useCallback, useEffect, useState } from "react";

const AUTH_STORAGE_KEY = "trading-bot.basic-auth";
export const AUTH_REQUIRED_EVENT = "trading-bot:auth-required";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function hasCredentials(): boolean {
  return sessionStorage.getItem(AUTH_STORAGE_KEY) !== null;
}

export function setCredentials(username: string, password: string): void {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  const raw = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
  sessionStorage.setItem(AUTH_STORAGE_KEY, `Basic ${btoa(raw)}`);
}

export function clearCredentials(): void {
  sessionStorage.removeItem(AUTH_STORAGE_KEY);
}

function headers(extra?: Record<string, string>): Record<string, string> {
  const authorization = sessionStorage.getItem(AUTH_STORAGE_KEY);
  return {
    ...(authorization ? { Authorization: authorization } : {}),
    ...(extra ?? {}),
  };
}

async function request(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const response = await fetch(path, {
    ...init,
    headers: headers(init.headers as Record<string, string> | undefined),
  });
  if (!response.ok) {
    if (response.status === 401) {
      clearCredentials();
      window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT));
    }
    throw new ApiError(`${path}: HTTP ${response.status}`, response.status);
  }
  return response;
}

async function parseJson<T>(response: Response): Promise<T> {
  if (
    response.status === 204 ||
    response.headers.get("content-length") === "0"
  ) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export async function get<T = unknown>(path: string): Promise<T> {
  return parseJson<T>(await request(path));
}

export async function post<T = unknown>(
  path: string,
  body?: unknown,
): Promise<T> {
  return parseJson<T>(
    await request(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}

interface FetchResult<T> {
  data: T | null;
  error: string | null;
  reload: () => void;
}

export function useFetch<T = unknown>(
  path: string,
  intervalMs = 0,
): FetchResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!path) return;
    get<T>(path)
      .then((value) => {
        setData(value);
        setError(null);
      })
      .catch((reason: Error) => setError(reason.message));
  }, [path]);

  useEffect(() => {
    let active = true;
    if (!path) {
      setData(null);
      setError(null);
      return () => {
        active = false;
      };
    }

    const doLoad = () => {
      get<T>(path)
        .then((value) => {
          if (active) {
            setData(value);
            setError(null);
          }
        })
        .catch((reason: Error) => {
          if (active) setError(reason.message);
        });
    };

    doLoad();
    const interval =
      intervalMs > 0 ? window.setInterval(doLoad, intervalMs) : null;
    return () => {
      active = false;
      if (interval !== null) window.clearInterval(interval);
    };
  }, [path, intervalMs]);

  return { data, error, reload: load };
}

/** Читает SSE через fetch, поскольку EventSource не поддерживает Authorization header. */
export async function subscribeSse(
  path: string,
  eventName: string,
  onEvent: (data: string) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await request(path, {
    headers: { Accept: "text/event-stream" },
    signal,
  });
  if (!response.body)
    throw new ApiError(`${path}: empty response body`, response.status);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");

    let separator: number;
    while ((separator = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, separator);
      buffer = buffer.slice(separator + 2);
      let currentEvent = "message";
      const dataLines: string[] = [];
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) currentEvent = line.slice(6).trim();
        if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
      }
      if (currentEvent === eventName && dataLines.length > 0) {
        onEvent(dataLines.join("\n"));
      }
    }

    if (done) break;
  }
}
