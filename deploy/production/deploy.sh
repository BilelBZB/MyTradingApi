#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${APPLY_PRODUCTION_DEPLOY:-}" != "YES" ]]; then
  echo "Refusing WAR deployment without APPLY_PRODUCTION_DEPLOY=YES."
  exit 2
fi
: "${TRADING_WAR:?Set TRADING_WAR to the reviewed WAR path}"
: "${TRADING_WAR_SHA256:?Set TRADING_WAR_SHA256 to its approved SHA-256}"
[[ -r "$TRADING_WAR" ]] || { echo "WAR is not readable"; exit 2; }
echo "$TRADING_WAR_SHA256  $TRADING_WAR" | sha256sum -c -
[[ -f /etc/trading-api/trading-prod.env ]] || { echo "Missing protected production environment"; exit 2; }
grep -qF '[À ' /etc/trading-api/trading-prod.env && { echo "Production environment still contains a placeholder"; exit 2; }
grep -qx 'SPRING_PROFILES_ACTIVE=prod' /etc/trading-api/trading-prod.env || { echo "Production profile is required"; exit 2; }
grep -Eq '^TRADING_DEMO_ENABLED=(true|false)$' /etc/trading-api/trading-prod.env || { echo "Explicit DEMO runtime setting required"; exit 2; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/trading-api/$stamp"
umask 077
install -d -m 0700 "$backup_dir"
backup_files=(trading-prod.env)
if [[ -f /opt/trading-api/trading-api.war ]]; then
  install -m 0600 /opt/trading-api/trading-api.war "$backup_dir/trading-api.war"
  backup_files+=(trading-api.war)
fi
install -m 0600 /etc/trading-api/trading-prod.env "$backup_dir/trading-prod.env"
if sudo -u postgres psql -Atqc "SELECT 1 FROM pg_database WHERE datname='trading'" | grep -qx 1; then
  sudo -u postgres pg_dump -Fc trading > "$backup_dir/trading.dump"
  chmod 0600 "$backup_dir/trading.dump"
  pg_restore --list "$backup_dir/trading.dump" >/dev/null
  pg_restore --file=/dev/null "$backup_dir/trading.dump"
  backup_files+=(trading.dump)
else
  echo "Existing trading database required; refusing release without database backup"
  exit 2
fi
( cd "$backup_dir" && sha256sum "${backup_files[@]}" > SHA256SUMS )
if [[ -n "${TRADING_RELEASE_RESERVATION_TTL:-}" ]]; then
  [[ "$TRADING_RELEASE_RESERVATION_TTL" == "10s" ]] || { echo "Unsupported release TTL"; exit 2; }
fi
systemctl stop trading-api.service
[[ "$(systemctl show trading-api.service -p ActiveState --value)" == "inactive" ]] || { echo "Service did not stop"; exit 2; }
if [[ "${TRADING_RELEASE_RESERVATION_TTL:-}" == "10s" ]]; then
  # Seule cette variable non secrete est modifiee, apres sauvegarde du fichier complet.
  python3 - <<'PY'
from pathlib import Path
p = Path('/etc/trading-api/trading-prod.env')
lines = p.read_text().splitlines()
lines = [line for line in lines if not line.startswith('TRADING_RESERVATION_TTL=')]
lines.append('TRADING_RESERVATION_TTL=10s')
p.write_text('\n'.join(lines) + '\n')
PY
fi
install -o trading -g trading -m 0644 "$TRADING_WAR" /opt/trading-api/trading-api.war
sha256sum /opt/trading-api/trading-api.war | grep -q "^$TRADING_WAR_SHA256 " || { echo "Installed WAR checksum mismatch"; exit 2; }
systemctl start trading-api.service
echo "$backup_dir"
