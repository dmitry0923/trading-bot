// Диагностика подписки Alor WS QuotesSubscribe: печатает сырые ответы сервера.
const fs = require('fs');

function envVal(name) {
  const line = fs.readFileSync('.env', 'utf8').split(/\r?\n/).find((l) => l.startsWith(name + '='));
  return line ? line.slice(name.length + 1).trim().replace(/^"|"$/g, '') : '';
}

const REFRESH = process.env.ALOR_REFRESH_TOKEN || envVal('ALOR_REFRESH_TOKEN');
const SECS = parseInt(process.argv[2] || '15', 10);

(async () => {
  const r = await fetch('https://oauth.alor.ru/refresh?token=' + encodeURIComponent(REFRESH), { method: 'POST' });
  const j = await r.json();
  const token = j.AccessToken;
  if (!token) { console.log('NO TOKEN', JSON.stringify(j)); process.exit(1); }
  console.log('token ok, len=' + token.length);

  const ws = new WebSocket('wss://api.alor.ru/ws?token=' + encodeURIComponent(token));
  let n = 0;
  ws.onopen = () => {
    console.log('WS OPEN');
    ws.send(JSON.stringify({
      opcode: 'QuotesSubscribe',
      guid: 'diag-1',
      token: token,
      exchange: 'MOEX',
      format: 'Simple',
      guids: [{ guid: 'q-SBER', symbol: 'SBER' }, { guid: 'q-CNYRUBF', symbol: 'CNYRUBF' }],
    }));
    console.log('subscribe sent');
  };
  ws.onmessage = (e) => {
    n++;
    if (n <= 6) console.log('MSG#' + n + ': ' + String(e.data).slice(0, 400));
  };
  ws.onerror = (e) => console.log('WS ERROR', e.message || e);
  ws.onclose = (e) => console.log('WS CLOSE code=' + e.code + ' reason=' + e.reason);
  setTimeout(() => { console.log('total messages=' + n); try { ws.close(); } catch (_) {} process.exit(0); }, SECS * 1000);
})();
