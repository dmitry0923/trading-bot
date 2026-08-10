import React, { useEffect, useState, useCallback } from 'react';
import { getAccessToken, refreshAccessToken } from './auth';

const BASE = '';

function authHeaders(extra?: HeadersInit): Record<string, string> {
  const token = getAccessToken();
  const base: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};
  if (!extra) return base;
  if (extra instanceof Headers) {
    const headers: Record<string, string> = {};
    extra.forEach((value, key) => {
      headers[key] = value;
    });
    return { ...base, ...headers };
  }
  if (Array.isArray(extra)) {
    const headers: Record<string, string> = {};
    for (const [key, value] of extra) headers[key] = value;
    return { ...base, ...headers };
  }
  return { ...base, ...extra };
}

let refreshPromise: Promise<boolean> | null = null;

/**
 * fetch с Bearer-токеном. При 401 с валидным access-токеном один раз
 * (single-flight) обновляем токен по httpOnly cookie и повторяем запрос.
 */
async function doFetch(path: string, init?: RequestInit): Promise<Response> {
  const first = await fetch(BASE + path, { ...(init || {}), headers: authHeaders(init?.headers) });
  if (first.status !== 401 || !getAccessToken()) return first;
  if (!refreshPromise) {
    refreshPromise = refreshAccessToken().finally(() => {
      refreshPromise = null;
    });
  }
  const ok = await refreshPromise;
  if (!ok) return first;
  return fetch(BASE + path, { ...(init || {}), headers: authHeaders(init?.headers) });
}

export async function get<T = unknown>(path: string): Promise<T> {
  const r = await doFetch(path);
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

export async function post<T = unknown>(path: string, body?: unknown): Promise<T> {
  const r = await doFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

interface FetchResult<T> {
  data: T | null;
  error: string | null;
  reload: () => void;
}

export function useFetch<T = unknown>(path: string, intervalMs = 0): FetchResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    get<T>(path)
      .then(d => {
        setData(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [path]);

  useEffect(() => {
    let active = true;
    const doLoad = () =>
      get<T>(path)
        .then(d => {
          if (active) {
            setData(d);
            setError(null);
          }
        })
        .catch((e: Error) => {
          if (active) setError(e.message);
        });
    doLoad();
    if (intervalMs > 0) {
      const id = setInterval(doLoad, intervalMs);
      return () => {
        active = false;
        clearInterval(id);
      };
    }
    return () => {
      active = false;
    };
  }, [path, intervalMs]);

  return { data, error, reload: load };
}

/**
 * SSE-поток через fetch: EventSource не умеет отправлять Authorization-заголовок,
 * поэтому читаем `text/event-stream` вручную через ReadableStream.
 */
export async function subscribeSse(
  path: string,
  eventName: string,
  onEvent: (data: string) => void,
  signal: AbortSignal
): Promise<void> {
  const r = await fetch(BASE + path, { headers: authHeaders({ Accept: 'text/event-stream' }), signal });
  if (!r.ok || !r.body) throw new Error(`${path}: HTTP ${r.status}`);

  const reader = r.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let idx: number;
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      let currentEvent = 'message';
      let data = '';
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) currentEvent = line.slice(6).trim();
        if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (currentEvent === eventName && data) {
        try {
          onEvent(data);
        } catch {
          /* ignore malformed frame */
        }
      }
    }
  }
}
