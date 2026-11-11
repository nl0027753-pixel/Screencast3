#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
IN="$1"
if [ -z "$IN" ]; then echo "Uso: bash usar-render.sh https://NOME.onrender.com"; exit 1; fi
HOST=$(echo "$IN" | sed -E 's#^(https?|wss?)://##; s#/.*$##')
if ! echo "$HOST" | grep -qE '^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'; then echo "Endereço inválido: $IN"; exit 1; fi
URL="wss://$HOST/"
sed -i "s|const val WSS_URL = \".*\"|const val WSS_URL = \"$URL\"|" screencast_mobile/app/src/main/java/com/screencast/mobile/MainActivity.kt
sed -i "s|const WSS_URL='[^']*'|const WSS_URL='$URL'|" site/index.html
echo "Endereço gravado: $URL"
grep -n "WSS_URL = \|WSS_URL='" screencast_mobile/app/src/main/java/com/screencast/mobile/MainActivity.kt site/index.html
