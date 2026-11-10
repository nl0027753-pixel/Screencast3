// ScreenCast — servidor de sinalização (salas + encaminhamento de mensagens).
// O vídeo NÃO passa por aqui: ele vai direto entre os participantes (WebRTC).
const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const MAX_PEERS_PER_ROOM = 8;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('ScreenCast signaling OK\n');
});
const wss = new WebSocket.Server({ server, maxPayload: 64 * 1024 });
const rooms = new Map(); // código -> Map(peerId, socket)

const send = (ws, data) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data)); };

function leave(ws) {
  if (!ws.room || !ws.peerId) return;
  const members = rooms.get(ws.room);
  if (members && members.get(ws.peerId) === ws) {
    members.delete(ws.peerId);
    for (const m of members.values()) send(m, { type: 'peer-left', peerId: ws.peerId });
    if (!members.size) rooms.delete(ws.room);
  }
  ws.room = null; ws.peerId = null;
}

wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', raw => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (!msg || typeof msg !== 'object') return;

    if (msg.type === 'join') {
      const room = String(msg.room || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 24);
      const peerId = String(msg.peerId || '').slice(0, 80);
      if (!room || !peerId) return send(ws, { type: 'error', message: 'room e peerId são obrigatórios' });
      leave(ws);
      if (!rooms.has(room)) rooms.set(room, new Map());
      const members = rooms.get(room);
      if (members.has(peerId)) return send(ws, { type: 'error', message: 'peerId já está em uso' });
      if (members.size >= MAX_PEERS_PER_ROOM) return send(ws, { type: 'error', message: 'sala cheia' });
      const existing = [...members.keys()];
      members.set(peerId, ws);
      ws.room = room; ws.peerId = peerId;
      send(ws, { type: 'peers', peers: existing });
      for (const [id, m] of members) if (id !== peerId) send(m, { type: 'peer-joined', peerId });
      send(ws, { type: 'joined', room, count: members.size });
      return;
    }
    if (msg.type === 'signal' && ws.room && ws.peerId) {
      const target = rooms.get(ws.room)?.get(String(msg.to));
      if (target) send(target, { type: 'signal', from: ws.peerId, payload: msg.payload });
    }
  });
  ws.on('close', () => leave(ws));
  ws.on('error', () => leave(ws));
});

// Mantém conexões vivas atrás de proxies e remove conexões mortas.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false; ws.ping();
  }
}, 25000);

server.listen(PORT, () => console.log(`ScreenCast signaling listening on ${PORT}`));
