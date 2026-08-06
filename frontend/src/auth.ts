import { useState, useEffect, useCallback } from 'react';
import type { CurrentUser } from './types';

/**
 * JWT-аутентификация на стороне фронтенда.
 *
 * Access-токен держим ТОЛЬКО в памяти (модульная переменная) — не в
 * localStorage/sessionStorage: при XSS он не будет украден.
 * Refresh-токен хранится в httpOnly cookie (Path=/api/v1/auth), поэтому
 * обновление происходит прозрачно без участия JS.
 */

let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export async function login(username: string, password: string): Promise<CurrentUser> {
  const r = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!r.ok) {
    throw new Error('Неверные учётные данные');
  }
  const data = await r.json();
  accessToken = data.accessToken as string;
  return { username: data.username, roles: data.roles as string[] };
}

/**
 * Прозрачное обновление access-токена через httpOnly cookie.
 * Возвращает true, если удалось обновить (или токен уже есть).
 */
export async function refreshAccessToken(): Promise<boolean> {
  if (accessToken) return true;
  const r = await fetch('/api/v1/auth/refresh', { method: 'POST' });
  if (!r.ok) {
    clearAuth();
    return false;
  }
  const data = await r.json();
  accessToken = data.accessToken as string;
  return true;
}

export async function logout(): Promise<void> {
  try {
    await fetch('/api/v1/auth/logout', { method: 'POST' });
  } catch {
    // сеть недоступна — всё равно сбрасываем локальное состояние
  }
  clearAuth();
}

export function clearAuth(): void {
  accessToken = null;
}

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

export function useAuth() {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<CurrentUser | null>(null);

  useEffect(() => {
    let active = true;
    fetch('/api/v1/me', {
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}
    })
      .then(r => {
        if (!r.ok) throw new Error(String(r.status));
        return r.json() as Promise<CurrentUser>;
      })
      .then(u => {
        if (active) {
          setUser(u);
          setStatus('authenticated');
        }
      })
      .catch(() => {
        // Если /me вернул 401 — пытаемся обновить токен по cookie.
        refreshAccessToken()
          .then(ok => {
            if (!ok) {
              if (active) setStatus('unauthenticated');
              return;
            }
            return fetch('/api/v1/me', {
              headers: { Authorization: `Bearer ${getAccessToken()}` }
            }).then(r => {
              if (!r.ok) throw new Error(String(r.status));
              return r.json() as Promise<CurrentUser>;
            });
          })
          .then(u => {
            if (u && active) {
              setUser(u);
              setStatus('authenticated');
            }
          })
          .catch(() => {
            if (active) setStatus('unauthenticated');
          });
      });
    return () => {
      active = false;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    const u = await login(username, password);
    setUser(u);
    setStatus('authenticated');
  }, []);

  const signOut = useCallback(async () => {
    await logout();
    setUser(null);
    setStatus('unauthenticated');
  }, []);

  return { status, user, signIn, signOut };
}
