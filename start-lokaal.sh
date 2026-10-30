#!/bin/sh
set -eu
cd "$(dirname "$0")"
random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
  fi
}
if [ ! -e .env ]; then
  # Noclobber voorkomt ook overschrijven wanneer twee starts tegelijk plaatsvinden.
  (umask 077; set -C; cat > .env <<EOF
POSTGRES_PASSWORD=$(random_secret)
ACCOUNT_TOKEN_SECRET=$(random_secret)
APP_ALLOW_INSECURE_LOCAL_DEFAULTS=true
APP_FLYWAY_LOCATIONS=db/migration,db/demo
EOF
  )
fi
if grep -Eq '^APP_ALLOW_INSECURE_LOCAL_DEFAULTS=true[[:space:]]*$' .env; then
  echo 'Lokale modus met demo-accounts: alleen voor lokaal gebruik, niet voor een gedeelde omgeving.'
else
  echo 'Bestaande .env wordt gebruikt en blijft ongewijzigd. Controleer de instellingen voor deze omgeving.'
fi
docker compose up --build -d
echo 'Open de app op http://localhost:8080'
