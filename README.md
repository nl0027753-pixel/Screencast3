# ScreenCast

Salas privadas onde todos assistem e transmitem a tela ao mesmo tempo (celular ↔ computador).

| Pasta | O que é |
|---|---|
| `screencast_mobile/` | App Android (Kotlin + WebRTC) |
| `screencast_mobile/server/` | Servidor WebSocket de sinalização (Node.js) |
| `site/` | Site (HTML único) — publicado no Netlify |
| `.github/workflows/android.yml` | Gera o APK automaticamente |

## Protocolo (igual no app, no site e no servidor)
- `join {room, peerId, platform}` → servidor responde `peers`, `joined`; avisa os outros com `peer-joined` / `peer-left`.
- `signal {to, payload}` é encaminhado ao destino com `from`.
- `payload.kind`: `offer | answer | ice | stop` (`ice.dir`: `b` = transmissor, `v` = espectador).
- Cada transmissor abre uma conexão só de envio para cada participante; cada espectador responde só recebendo → várias transmissões simultâneas, sem conflito (malha, até 8 pessoas).

## Servidor
No Codespace: `bash iniciar-servidor.sh` (instala, liga, deixa a porta pública e grava o endereço no app e no site).
O endereço do Codespace só funciona enquanto ele estiver ligado.

## APK
Ao dar push na `main`, o GitHub Actions compila e publica o APK em **Releases → latest → ScreenCast.apk** (download direto, já é o .apk, não é ZIP).

## Rede
Só há STUN. Em 4G/5G ou redes muito restritas pode ser preciso um servidor TURN (`iceServers` no `MainActivity.kt` e `ICE` em `site/index.html`).
