#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
: "${STUBLINE_TLS_PASSWORD:?Set STUBLINE_TLS_PASSWORD in .env or the environment}"
out=certs/dev
mkdir -p "$out"
chmod 755 "$out"
force=false
if [[ "${1:-}" == --force ]]; then force=true; elif [[ $# -gt 0 ]]; then
  echo 'Usage: dev-ca.sh [--force]' >&2; exit 2
fi
needs_renewal() {
  [[ "$force" == true || ! -s "$1" ]] || ! openssl x509 -checkend 604800 -noout -in "$1" >/dev/null 2>&1
}
make_ca() {
  local name=$1
  openssl req -x509 -newkey rsa:3072 -sha256 -days 30 -nodes -subj "/CN=Stubline $name development CA" -keyout "$out/$name-ca.key" -out "$out/$name-ca.crt" >/dev/null 2>&1
}
make_cert() {
  local ca=$1 name=$2 sans=$3
  openssl req -newkey rsa:3072 -nodes -subj "/CN=$name" -keyout "$out/$name.key" -out "$out/$name.csr" >/dev/null 2>&1
  printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth,clientAuth\n' "$sans" > "$out/$name.ext"
  openssl x509 -req -in "$out/$name.csr" -CA "$out/$ca-ca.crt" -CAkey "$out/$ca-ca.key" -CAcreateserial -out "$out/$name.crt" -days 30 -sha256 -extfile "$out/$name.ext" >/dev/null 2>&1
  openssl pkcs12 -export -inkey "$out/$name.key" -in "$out/$name.crt" -certfile "$out/$ca-ca.crt" -out "$out/$name.p12" -passout "pass:$STUBLINE_TLS_PASSWORD" >/dev/null 2>&1
  rm "$out/$name.csr" "$out/$name.ext"
}
for ca in public internal; do
  renew_ca=false
  if needs_renewal "$out/$ca-ca.crt"; then renew_ca=true; fi
  if [[ ! -s "$out/$ca-ca.key" ]]; then
    if [[ "$ca" == public ]] && { needs_renewal "$out/ticketing-api.crt" || needs_renewal "$out/dev-token-issuer.crt"; }; then renew_ca=true; fi
    if [[ "$ca" == internal ]] && { needs_renewal "$out/inventory-service.crt" || needs_renewal "$out/ticketing-client.crt" || needs_renewal "$out/inventory-health.crt"; }; then renew_ca=true; fi
  fi
  if [[ "$renew_ca" == true ]]; then
    # Older runs removed the CA private key. Reissue its dependent certificates together.
    make_ca "$ca"
    if [[ "$ca" == public ]]; then rm -f "$out/ticketing-api.crt" "$out/dev-token-issuer.crt"; else
      rm -f "$out/inventory-service.crt" "$out/ticketing-client.crt" "$out/inventory-health.crt"
    fi
  fi
done
if needs_renewal "$out/ticketing-api.crt" || [[ ! -s "$out/ticketing-api.p12" ]]; then make_cert public ticketing-api 'DNS:ticketing-api,DNS:localhost,DNS:host.docker.internal,IP:127.0.0.1'; fi
if needs_renewal "$out/dev-token-issuer.crt" || [[ ! -s "$out/dev-token-issuer.p12" ]]; then make_cert public dev-token-issuer 'DNS:dev-token-issuer,DNS:localhost,IP:127.0.0.1'; fi
if needs_renewal "$out/inventory-service.crt"; then make_cert internal inventory-service 'DNS:inventory-service,DNS:localhost,IP:127.0.0.1'; fi
if needs_renewal "$out/ticketing-client.crt"; then make_cert internal ticketing-client 'DNS:ticketing-api'; fi
if needs_renewal "$out/inventory-health.crt"; then make_cert internal inventory-health 'DNS:inventory-health'; fi
chmod 600 "$out"/*.key "$out"/*.p12
mkdir -p "$out/api" "$out/inventory" "$out/issuer"
docker run --rm -v "$PWD/$out:/certs" --user 0 eclipse-temurin:21.0.12.1_1-jre-noble \
  chown -R "$(id -u):$(id -g)" /certs/api /certs/inventory /certs/issuer
cp "$out/public-ca.crt" "$out/internal-ca.crt" "$out/ticketing-api.p12" "$out/ticketing-client.crt" "$out/ticketing-client.key" "$out/api/"
cp "$out/public-ca.crt" "$out/dev-token-issuer.p12" "$out/issuer/"
cp "$out/internal-ca.crt" "$out/inventory-service.crt" "$out/inventory-service.key" "$out/inventory-health.crt" "$out/inventory-health.key" "$out/inventory/"
chmod 755 "$out/api" "$out/inventory" "$out/issuer"
find "$out/api" "$out/inventory" "$out/issuer" -type f -name '*.crt' -exec chmod 644 {} +
find "$out/api" "$out/inventory" "$out/issuer" -type f \( -name '*.key' -o -name '*.p12' \) -exec chmod 600 {} +
docker run --rm -v "$PWD/$out:/certs" --user 0 eclipse-temurin:21.0.12.1_1-jre-noble \
  chown -R 10001:10001 /certs/api /certs/inventory /certs/issuer
printf 'Development certificates ready in %s\n' "$out"
