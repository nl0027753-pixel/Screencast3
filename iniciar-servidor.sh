#!/usr/bin/env bash
# Roda DENTRO do GitHub Codespaces. Instala, liga o servidor, descobre o endereço wss://
# e grava esse endereço no app (MainActivity.kt) e no site (site/index.html).
set -e
cd "$(dirname "$0")"

if [ -z "$CODESPACE_NAME" ]; then
  echo "ERRO: este script precisa rodar dentro do GitHub Codespaces."; exit 1
fi

echo "1/4 Instalando o servidor..."
(cd screencast_mobile/server && npm install --silent)

echo "2/4 Ligando o servidor (porta 8080)..."
pkill -f "node server.js" 2>/dev/null || true
(cd screencast_mobile/server && nohup node server.js > ../../servidor.log 2>&1 &)
sleep 2
curl -fsS http://localhost:8080/ >/dev/null && echo "    servidor no ar."

echo "3/4 Tornando a porta 8080 pública..."
gh codespace ports visibility 8080:public -c "$CODESPACE_NAME" 2>/dev/null \
  && echo "    porta pública." \
  || echo "    (não deu automático: faça manualmente — aba PORTAS > clique direito na 8080 > Visibilidade da porta > Pública)"

URL="wss://${CODESPACE_NAME}-8080.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}/"
echo "4/4 Gravando o endereço no app e no site: $URL"
sed -i "s|const val WSS_URL = \".*\"|const val WSS_URL = \"$URL\"|" screencast_mobile/app/src/main/java/com/screencast/mobile/MainActivity.kt
sed -i "s|const WSS_URL='[^']*'|const WSS_URL='$URL'|" site/index.html
grep -n "WSS_URL" screencast_mobile/app/src/main/java/com/screencast/mobile/MainActivity.kt site/index.html | grep -v "url(WSS_URL)" | grep -v "url=WSS_URL"

echo
echo "PRONTO. Agora envie as mudanças ao GitHub (ver o guia):"
echo "  git add -A && git commit -m 'Atualiza endereco do servidor' && git push"
