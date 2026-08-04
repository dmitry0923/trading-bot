import React, { useEffect, useState, useCallback } from 'react';

const BASE = '';

const AUTH_HEADER: Record<string, string> = {};
const authUser = process.env.REACT_APP_AUTH_USER;
const authPass = process.env.REACT_APP_AUTH_PASSWORD;
if (authUser && authPass) {
  AUTH_HEADER.Authorization = 'Basic ' + btoa(`${authUser}:${authPass}`);
}

function headers(extra?: Record<string, string>): Record<string, string> {
  return { ...AUTH_HEADER, ...(extra || {}) };
}

export async function get<T = unknown>(path: string): Promise<T> {
  const r = await fetch(BASE + path, { headers: headers() });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

export async function post<T = unknown>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/json' }),
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
      .then(d => { setData(d); setError(null); })
      .catch((e: Error) => setError(e.message));
  }, [path]);

  useEffect(() => {
    let active = true;
    const doLoad = () =>
      get<T>(path)
        .then(d => { if (active) { setData(d); setError(null); } })
        .catch((e: Error) => { if (active) setError(e.message); });
    doLoad();
    if (intervalMs > 0) {
      const id = setInterval(doLoad, intervalMs);
      return () => { active = false; clearInterval(id); };
    }
    return () => { active = false; };
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
  const r = await fetch(BASE + path, { headers: headers({ Accept: 'text/event-stream' }), signal });
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
        try { onEvent(data); } catch { /* ignore malformed frame */ }
      }
    }
  }
}
