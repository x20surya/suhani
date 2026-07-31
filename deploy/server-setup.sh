#!/usr/bin/env bash
# One-time server provisioning for Suhani (Ubuntu 22.04/24.04).
#
# Usage, from the repo directory on the server:
#   bash deploy/server-setup.sh yourdomain.example.in
#
# Idempotent: safe to re-run. It only edits .env, which is untracked - the old
# version sed-ed the tracked Caddyfile, which dirtied the tree and reverted on
# the next git pull.
set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "Usage: bash deploy/server-setup.sh <domain>"
  echo "  The domain's A record must ALREADY point at this server's public IP,"
  echo "  or Caddy cannot obtain a certificate."
  exit 1
fi

say() { printf '\n==> %s\n' "$1"; }

say "Installing Docker + compose plugin"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi

say "Opening firewall ports 80/443"
if command -v ufw >/dev/null 2>&1; then
  ufw allow 80/tcp  || true
  ufw allow 443/tcp || true
fi
# Oracle Cloud images ship iptables rules that DROP 80/443 even when ufw allows
# them. This is the most common "Caddy cannot get a certificate" cause there.
if command -v iptables >/dev/null 2>&1; then
  iptables -I INPUT -p tcp --dport 80  -j ACCEPT 2>/dev/null || true
  iptables -I INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || true
  command -v netfilter-persistent >/dev/null 2>&1 && netfilter-persistent save || true
fi

if [ ! -f .env ]; then
  say "Creating .env from template"
  cp .env.example .env
fi

# The Caddyfile reads {$SUHANI_DOMAIN}; compose passes it through from .env.
say "Setting SUHANI_DOMAIN=$DOMAIN in .env"
if grep -q '^SUHANI_DOMAIN=' .env; then
  sed -i "s|^SUHANI_DOMAIN=.*|SUHANI_DOMAIN=$DOMAIN|" .env
else
  printf '\n# Domain Caddy serves HTTPS on (used by deploy/Caddyfile).\nSUHANI_DOMAIN=%s\n' "$DOMAIN" >> .env
fi

# APP_KEY guards every /api/ route. Without it the server deliberately REFUSES
# all non-localhost requests with 503, so a public deploy fails closed instead
# of serving her ledger to anyone who finds the hostname.
if ! grep -qE '^APP_KEY=.+' .env; then
  say "Generating APP_KEY (required for public access)"
  KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  if grep -q '^APP_KEY=' .env; then
    sed -i "s|^APP_KEY=.*|APP_KEY=$KEY|" .env
  else
    printf '\nAPP_KEY=%s\n' "$KEY" >> .env
  fi
fi

MISSING=""
for k in SARVAM_API_KEY LIVEKIT_URL LIVEKIT_API_KEY LIVEKIT_API_SECRET; do
  v="$(grep -E "^$k=" .env | cut -d= -f2- || true)"
  case "$v" in ""|TODO*) MISSING="$MISSING $k" ;; esac
done
if [ -n "$MISSING" ]; then
  say "STOP: fill these into .env before continuing:$MISSING"
  echo "    nano .env"
  echo "    then re-run: bash deploy/server-setup.sh $DOMAIN"
  exit 1
fi

say "Building and starting services (first build downloads ~461 MB of model weights)"
docker compose -f deploy/docker-compose.yml up -d --build

APP_KEY_VALUE="$(grep -E '^APP_KEY=' .env | cut -d= -f2-)"
say "Done"
echo "    App:     https://$DOMAIN"
echo "    Health:  https://$DOMAIN/healthz"
echo "    Logs:    docker compose -f deploy/docker-compose.yml logs -f"
echo "    Status:  docker compose -f deploy/docker-compose.yml ps"
echo
echo "    Open this ONCE on her phone, then 'Add to Home screen':"
echo "      https://$DOMAIN/?k=$APP_KEY_VALUE"
echo
echo "    Treat that link like a password."
