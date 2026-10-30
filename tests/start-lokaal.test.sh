#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
cp "$project_dir/start-lokaal.sh" "$test_dir/start-lokaal.sh"
mkdir "$test_dir/bin"
cat > "$test_dir/bin/docker" <<'DOCKER'
#!/bin/sh
[ "$*" = 'compose up --build -d' ] || exit 1
DOCKER
chmod +x "$test_dir/bin/docker"
PATH="$test_dir/bin:$PATH" sh "$test_dir/start-lokaal.sh" > "$test_dir/output"
grep -q 'Lokale modus met demo-accounts' "$test_dir/output"
grep -q 'http://localhost:8080' "$test_dir/output"
grep -q '^POSTGRES_PASSWORD=[a-f0-9]\{64\}$' "$test_dir/.env"
grep -q '^ACCOUNT_TOKEN_SECRET=[a-f0-9]\{64\}$' "$test_dir/.env"
grep -q '^APP_FLYWAY_LOCATIONS=db/migration,db/demo$' "$test_dir/.env"
[ "$(LC_ALL=C ls -ld "$test_dir/.env" | cut -c 1-10)" = '-rw-------' ]
cp "$test_dir/.env" "$test_dir/original"
PATH="$test_dir/bin:$PATH" sh "$test_dir/start-lokaal.sh" >/dev/null
cmp "$test_dir/.env" "$test_dir/original"
printf '%s\n' 'APP_ALLOW_INSECURE_LOCAL_DEFAULTS=false' 'BEWAAR=dit' > "$test_dir/.env"
cp "$test_dir/.env" "$test_dir/original"
PATH="$test_dir/bin:$PATH" sh "$test_dir/start-lokaal.sh" > "$test_dir/output"
grep -q 'Bestaande .env' "$test_dir/output"
cmp "$test_dir/.env" "$test_dir/original"
# Test de fallback met uitsluitend de benodigde hulpprogramma's in PATH, zonder openssl.
mkdir "$test_dir/fallback"
for tool in dirname cat od tr grep; do
  ln -s "$(command -v "$tool")" "$test_dir/fallback/$tool"
done
ln -s "$test_dir/bin/docker" "$test_dir/fallback/docker"
rm "$test_dir/.env"
PATH="$test_dir/fallback" /bin/sh "$test_dir/start-lokaal.sh" > "$test_dir/output"
grep -q '^POSTGRES_PASSWORD=[a-f0-9]\{64\}$' "$test_dir/.env"
grep -q '^ACCOUNT_TOKEN_SECRET=[a-f0-9]\{64\}$' "$test_dir/.env"
echo '4 opstarttests geslaagd: geheimen genereren, bestaande lokale .env behouden, bestaande gedeelde .env behouden, /dev/urandom-fallback.'
