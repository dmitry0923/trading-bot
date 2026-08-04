const BASE = '';

async function get(path) {
  const r = await fetch(BASE + path);
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json();
}

async function post(path, body) {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json();
}

export function useFetch(path, intervalMs = 0) {
  const [data, setData] = React.useState(null);
  const [error, setError] = React.useState(null);

  React.useEffect(() => {
    let active = true;
    const load = () =>
      get(path)
        .then(d => { if (active) { setData(d); setError(null); } })
        .catch(e => { if (active) setError(e.message); });
    load();
    if (intervalMs > 0) {
      const id = setInterval(load, intervalMs);
      return () => { active = false; clearInterval(id); };
    }
    return () => { active = false; };
  }, [path, intervalMs]);

  return { data, error, reload: () => get(path).then(setData) };
}

export { get, post };
