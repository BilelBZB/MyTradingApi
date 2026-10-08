#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_PREFLIGHT:-}" != "YES" ]]; then
  echo "Refusing preflight without APPLY_PRODUCTION_PREFLIGHT=YES."
  exit 2
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$root_dir/trading-prod.env.template" ]] || { echo "Missing environment template"; exit 2; }
[[ -f "$root_dir/trading-api.service" ]] || { echo "Missing systemd template"; exit 2; }
[[ -f "$root_dir/nginx-trading.saamp.com.conf" ]] || { echo "Missing nginx template"; exit 2; }
if grep -q '__FRONT_ROOT_A_COMPLETER__' "$root_dir/nginx-trading.saamp.com.conf"; then
  # Une mise a jour backend conserve le vhost deja installe, sans copier le template front.
  systemctl is-active --quiet trading-api.service || { echo "Existing backend required with unresolved front template"; exit 2; }
  nginx -t
  installed_nginx="$(nginx -T 2>/dev/null)"
  grep -Eq 'server_name[[:space:]]+trading\.saamp\.com[[:space:];]' <<< "$installed_nginx" || {
    echo "Existing trading vhost not found"; exit 2;
  }
fi
: "${TRADING_WAR:?Set TRADING_WAR to the reviewed local WAR path}"
: "${TRADING_WAR_SHA256:?Set TRADING_WAR_SHA256 to its approved SHA-256}"
[[ -r "$TRADING_WAR" ]] || { echo "WAR is not readable"; exit 2; }
echo "$TRADING_WAR_SHA256  $TRADING_WAR" | sha256sum -c -
getent hosts trading.saamp.com || { echo "DNS resolution unavailable"; exit 2; }
echo "Preflight passed; no host modification was performed."
