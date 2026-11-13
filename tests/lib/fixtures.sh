#!/usr/bin/env bash
# Fixtures dos testes: servidor falso, front falso, pin, env e stubs de docker/curl.
# Para `source`; não executa nada ao ser carregado.
# Congelado após a Task 1: tasks seguintes só consomem.
# Exceção: a M1 E07 (Task 1) acrescentou make_fake_jars, sem mudar os helpers existentes.

_FIXTURES_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

_fgit() {
  local dir="$1"
  shift
  git -C "$dir" -c user.name=Teste -c user.email=teste@example.com \
    -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"
}

# $1 = com things (0|1), $2 = com meta/version (0|1)
_fake_contract() {
  cat <<'YAML'
openapi: 3.0.1
info:
  title: Zeny falso
  version: 1.0.0
paths:
YAML
  if [[ "$2" == 1 ]]; then
    cat <<'YAML'
  /api/v1/meta/version:
    get:
      operationId: getVersion
      responses:
        '200':
          description: Versão
          content:
            application/json:
              schema:
                type: object
                properties:
                  version:
                    type: string
YAML
  fi
  if [[ "$1" == 1 ]]; then
    cat <<'YAML'
  /api/v1/things:
    get:
      operationId: listThings
      responses:
        '200':
          description: Coisas
          content:
            application/json:
              schema:
                type: array
                items:
                  type: string
YAML
  fi
}

make_fake_server() {
  local dir="$1"
  mkdir -p "$dir/api" "$dir/docker"
  git init -q -b main "$dir"
  _fake_contract 0 1 >"$dir/api/openapi.yaml"
  cat >"$dir/docker/compose.yaml" <<'YAML'
name: zeny
services:
  api:
    build:
      context: ..
      dockerfile: docker/Dockerfile
    image: zeny-hotel-manager:dev
    environment:
      ZENY_STORAGE_PUBLIC_ENDPOINT: ${ZENY_STORAGE_PUBLIC_ENDPOINT:-http://localhost}
      ZENY_JWT_KEYS: ${ZENY_JWT_KEYS:?defina}
  db:
    image: postgres:18
  storage:
    image: pgsty/minio:RELEASE.2026-08-04T00-00-00Z
  storage-init:
    image: pgsty/minio:RELEASE.2026-08-04T00-00-00Z
  proxy:
    image: caddy:2.11-alpine
    ports:
      - "${ZENY_HTTP_ADDRESS:-127.0.0.1}:${ZENY_HTTP_PORT:-80}:80"
YAML
  printf 'ZENY_JWT_KEYS=\n' >"$dir/docker/.env.example"
  printf 'servidor falso v1\n' >"$dir/README.md"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q -m "C1: contrato base"
  FAKE_C1="$(_fgit "$dir" rev-parse HEAD)"

  printf 'servidor falso v2\n' >"$dir/README.md"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q -m "C2: só README"
  FAKE_C2="$(_fgit "$dir" rev-parse HEAD)"

  _fake_contract 1 1 >"$dir/api/openapi.yaml"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q -m "C3: acrescenta things (aditivo)"
  FAKE_C3="$(_fgit "$dir" rev-parse HEAD)"

  _fake_contract 1 0 >"$dir/api/openapi.yaml"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q -m "C4: remove meta/version (breaking)"
  FAKE_C4="$(_fgit "$dir" rev-parse HEAD)"

  export FAKE_C1 FAKE_C2 FAKE_C3 FAKE_C4
}

make_fake_front() {
  local dir="$1"
  mkdir -p "$dir"
  git init -q -b main "$dir"
  cp -a "$_FIXTURES_ROOT/tools" "$dir/tools"
  [[ -d "$_FIXTURES_ROOT/api" ]] && cp -a "$_FIXTURES_ROOT/api" "$dir/api"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q --allow-empty -m "front falso"
}

# make_fake_jars DIR : em DIR (checkout ou worktree do front falso) cria os JARs (ZIPs mínimos válidos,
# com o caminho de cada um numa entrada, para distinguir JARs e checkouts) e o roteiro falsos, escondidos
# do git status só pelo info/exclude do common-dir.
make_fake_jars() {
  local dir="$1" common pattern
  mkdir -p "$dir/app/build/libs" "$dir/demo-seed/build/libs" "$dir/docs/demo" || return 1
  python3 -I -c '
import sys, zipfile
for path in sys.argv[1:]:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        z.writestr("origem.txt", path + "\n")
' "$dir/app/build/libs/zeny-desktop.jar" "$dir/demo-seed/build/libs/demo-seed.jar" || return 1
  printf '# Roteiro falso da M1\n\nTexto de teste.\n' >"$dir/docs/demo/roteiro-m1.md"
  common="$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir)" || return 1
  mkdir -p "$common/info"
  for pattern in /app/build/ /demo-seed/build/ /docs/demo/; do
    grep -qxF -- "$pattern" "$common/info/exclude" 2>/dev/null || printf '%s\n' "$pattern" >>"$common/info/exclude"
  done
}

# Formato da Task 2: commit, contract_sha256, synced_at (nesta ordem).
write_fake_pin() {
  local front="$1" server="$2" commit="$3" sha
  sha="$(_fgit "$server" rev-parse --verify "$commit^{commit}")" || return 1
  mkdir -p "$front/api"
  _fgit "$server" show "$sha:api/openapi.yaml" >"$front/api/openapi.yaml" || return 1
  {
    printf 'commit=%s\n' "$sha"
    printf 'contract_sha256=%s\n' "$(sha256sum "$front/api/openapi.yaml" | cut -d' ' -f1)"
    printf 'synced_at=2026-11-10T00:00:00Z\n'
  } >"$front/api/server-pin.properties"
}

write_test_env() {
  local file="$1"
  shift
  {
    printf '%s\n' \
      ZENY_DB_USERNAME=zeny_test \
      ZENY_DB_PASSWORD=senha-db-teste \
      ZENY_DB_MIGRATION_USERNAME=zeny_migration_test \
      ZENY_DB_MIGRATION_PASSWORD=senha-migracao-teste \
      ZENY_ENCRYPTION_KEYS=k1:chave-fake-de-teste \
      ZENY_ENCRYPTION_ACTIVE_KEY_ID=k1 \
      ZENY_JWT_KEYS=k1:chave-jwt-fake-de-teste \
      ZENY_JWT_ACTIVE_KEY_ID=k1 \
      ZENY_STORAGE_ROOT_USER=root_test \
      ZENY_STORAGE_ROOT_PASSWORD=senha-storage-teste \
      ZENY_STORAGE_ACCESS_KEY=acesso-teste \
      ZENY_STORAGE_SECRET_KEY=segredo-teste
    if (($# > 0)); then printf '%s\n' "$@"; fi
  } >"$file"
}

# Imprime o PATH a usar (DIR/bin na frente).
# STUB_IMAGE_PRESENT (padrão 0), STUB_DOCKER_EXIT, STUB_CURL_BODY, STUB_CURL_EXIT.
install_docker_stub() {
  local dir="$1"
  mkdir -p "$dir/bin"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'printf "cwd=%%s %%s\\n" "$(pwd)" "$*" >>%q\n' "$dir/docker.log"
    cat <<'STUB'
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  [[ "${STUB_IMAGE_PRESENT:-0}" == 1 ]] && exit 0
  exit 1
fi
exit "${STUB_DOCKER_EXIT:-0}"
STUB
  } >"$dir/bin/docker"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'printf "cwd=%%s %%s\\n" "$(pwd)" "$*" >>%q\n' "$dir/curl.log"
    cat <<'STUB'
[[ -n "${STUB_CURL_BODY:-}" ]] && printf '%s' "$STUB_CURL_BODY"
exit "${STUB_CURL_EXIT:-0}"
STUB
  } >"$dir/bin/curl"
  chmod +x "$dir/bin/docker" "$dir/bin/curl"
  printf '%s\n' "$dir/bin:$PATH"
}
