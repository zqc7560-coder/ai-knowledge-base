#!/usr/bin/env bash
# 生成开发/演示用自签证书（生产应换 CA 证书）。产出 deploy/certs/{key,crt}，已被 .gitignore 挡。
# 用法: gen-selfsigned-cert.sh [域名=localhost] [有效天数=365]
set -euo pipefail
CN="${1:-localhost}"
DAYS="${2:-365}"
OUT="$(cd "$(dirname "$0")" && pwd)/certs"
# openssl 是原生 Windows 程序时不认 MSYS 路径（/d/... 报 No such file）；pwd -W 可得 C:/... 形式
if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]] || command -v uname >/dev/null 2>&1 && uname -o 2>/dev/null | grep -qiE "msys|cygwin"; then
  OUTW="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)/certs"
else
  OUTW="$OUT"
fi
mkdir -p "$OUT"
if command -v openssl >/dev/null 2>&1; then
  openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
    -keyout "$OUTW/kb.key" -out "$OUTW/kb.crt" \
    -subj "/CN=$CN" -addext "subjectAltName=DNS:$CN,DNS:localhost,IP:127.0.0.1"
elif command -v docker >/dev/null 2>&1; then
  # 无 openssl 时用临时镜像生成（现场机器常见）
  docker run --rm -v "$OUT:/c" alpine/openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
    -keyout /c/kb.key -out /c/kb.crt -subj "/CN=$CN" -addext "subjectAltName=DNS:$CN,DNS:localhost,IP:127.0.0.1" \
    >/dev/null 2>&1 || { echo "需 openssl 或可拉取 alpine/openssl"; exit 1; }
else
  echo "既无 openssl 也无 docker，无法生成自签证书"; exit 1
fi
chmod 600 "$OUT/kb.key"
echo "证书生成于 $OUT/kb.{key,crt}（CN=$CN, ${DAYS}天）"
echo "启用 HTTPS：docker compose --profile app --profile tls up -d  且访问 https://$CN:8443"
