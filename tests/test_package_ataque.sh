#!/usr/bin/env bash
# Testes adversariais de tools/server-env/package.sh, save-images.sh e LEIA-ME.md (Task 7).
# Fronts e servidores falsos; docker stub (só o `docker compose config` roda o docker real, sem daemon).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT \
  COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES
stubpath="$(install_docker_stub "$tmp/stub")"
dlog="$tmp/stub/docker.log"
C1="$FAKE_C1"
tag="${C1:0:12}"

# new_front DIR [COMMIT] [SERVIDOR] : front falso com pin.
new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "${3:-$srv}" "${2:-$C1}"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m pin
}

# --- 1. segredos: .env em vários lugares, .git, .env dentro da árvore do servidor ---
f1="$tmp/f1"
new_front "$f1"
mkdir -p "$f1/.server" "$f1/build"
write_test_env "$f1/.server/zeny-front.env"
write_test_env "$f1/tools/server-env/.env"
write_test_env "$f1/.env"
write_test_env "$f1/build/zeny.env"
assert_exit 0 "package.sh com .env espalhados" -- bash "$f1/tools/server-env/package.sh"
pkg="$f1/build/demo-package"
assert_eq "" "$(find "$pkg" \( -name '.env' -o -name '*.env' -o -name '.git' -o -name '.git*' \) -print)" "pacote sem .env, *.env nem .git"
assert_eq "" "$(grep -rl -e 'senha-db-teste' -e 'chave-fake-de-teste' -e 'chave-jwt-fake' "$pkg" 2>/dev/null)" "nenhum valor de segredo no pacote"

# servidor que versiona um .env: o pacote não pode sair com ele
srvenv="$tmp/srv-env"
make_fake_server "$srvenv"
printf 'ZENY_DB_PASSWORD=segredo-no-servidor\n' >"$srvenv/docker/.env"
_fgit "$srvenv" add -A >/dev/null
_fgit "$srvenv" commit -q -m "servidor com .env"
c_env="$(_fgit "$srvenv" rev-parse HEAD)"
f1b="$tmp/f1b"
new_front "$f1b" "$c_env" "$srvenv"
code=0
ZENY_SERVER_REPO="$srvenv" bash "$f1b/tools/server-env/package.sh" >/dev/null 2>&1 || code=$?
assert_eq "1" "$([[ "$code" != 0 ]] && echo 1 || echo 0)" "servidor com .env versionado: package.sh falha"
assert_eq "" "$(grep -rl 'segredo-no-servidor' "$f1b/build/demo-package" 2>/dev/null)" "segredo do servidor não sobra no pacote"

# --- 2. rm -rf seguro ---
# 2a. caminho com espaço
f2="$tmp/front com espaço"
new_front "$f2"
assert_exit 0 "package.sh em checkout com espaço" -- bash "$f2/tools/server-env/package.sh"
assert_file "$f2/build/demo-package/LEIA-ME.md" "pacote criado em caminho com espaço"
mkdir -p "$f2/build/outro"
echo x >"$f2/build/outro/a"
echo y >"$f2/build/demo-package/estranho"
assert_exit 0 "segunda execução com espaço" -- bash "$f2/tools/server-env/package.sh"
assert_file "$f2/build/outro/a" "build/outro intacto (espaço)"
assert_eq "0" "$([[ -e "$f2/build/demo-package/estranho" ]] && echo 1 || echo 0)" "estranho apagado (espaço)"

# 2b. build/demo-package symlink para fora: o alvo não pode ser apagado
f3="$tmp/f3"
new_front "$f3"
mkdir -p "$f3/build" "$tmp/alvo3"
echo sentinela >"$tmp/alvo3/sentinela.txt"
ln -s "$tmp/alvo3" "$f3/build/demo-package"
code=0
bash "$f3/tools/server-env/package.sh" >/dev/null 2>&1 || code=$?
assert_file "$tmp/alvo3/sentinela.txt" "symlink em build/demo-package: alvo preservado"
assert_eq "1" "$([[ -L "$f3/build/demo-package" ]] && echo 1 || echo 0)" "symlink build/demo-package não foi substituído"
assert_eq "1" "$([[ "$code" != 0 ]] && echo 1 || echo 0)" "symlink em build/demo-package: recusa com código != 0"

# 2c. build como symlink para fora: nada fora do checkout pode ser apagado
f4="$tmp/f4"
new_front "$f4"
mkdir -p "$tmp/alvo4/demo-package"
echo sentinela >"$tmp/alvo4/demo-package/sentinela.txt"
echo outro >"$tmp/alvo4/irmao.txt"
ln -s "$tmp/alvo4" "$f4/build"
code=0
bash "$f4/tools/server-env/package.sh" >/dev/null 2>&1 || code=$?
assert_file "$tmp/alvo4/demo-package/sentinela.txt" "build symlink: demo-package de fora preservado"
assert_file "$tmp/alvo4/irmao.txt" "build symlink: irmão preservado"
assert_eq "1" "$([[ "$code" != 0 ]] && echo 1 || echo 0)" "build symlink para fora: recusa com código != 0"

# 2d. falha antes de apagar (pin com contrato adulterado): pacote anterior intacto
f5="$tmp/f5"
new_front "$f5"
bash "$f5/tools/server-env/package.sh" >/dev/null 2>&1
sed -i 's/^contract_sha256=.*/contract_sha256=0000/' "$f5/api/server-pin.properties"
rm -rf "$f5/.server"
assert_exit 3 "pin adulterado: package.sh sai com 3" -- bash "$f5/tools/server-env/package.sh"
assert_file "$f5/build/demo-package/LEIA-ME.md" "falha antes do rm: pacote anterior preservado"

# 2e. argumento sobrando
assert_exit 2 "package.sh com argumento sai com 2" -- bash "$f1/tools/server-env/package.sh" extra

# 2f. rodando de um worktree do front: pacote vai para o checkout principal, não para o worktree
f6="$tmp/f6"
new_front "$f6"
_fgit "$f6" worktree add -q "$tmp/f6wt" -b wt >/dev/null 2>&1
assert_exit 0 "package.sh num worktree" -- bash "$tmp/f6wt/tools/server-env/package.sh"
assert_file "$f6/build/demo-package/LEIA-ME.md" "pacote no checkout principal"
assert_eq "0" "$([[ -e "$tmp/f6wt/build" ]] && echo 1 || echo 0)" "worktree sem build/"

# --- 3. autoignore: git status do front falso vazio ---
f7="$tmp/f7"
new_front "$f7"
assert_exit 0 "package.sh em front limpo" -- bash "$f7/tools/server-env/package.sh"
assert_eq "" "$(_fgit "$f7" status --porcelain)" "git status --porcelain vazio depois do pacote"

# --- 4. pacote autossuficiente ---
if ! docker compose version >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  skip_case "docker compose ou python3 indisponível: pacote autossuficiente"
else
  pk7="$f7/build/demo-package"
  iso="$(mktemp -d)"
  cp -a "$pk7" "$iso/pacote"
  assert_eq "" "$(git -C "$iso/pacote" rev-parse --show-toplevel 2>/dev/null)" "pacote copiado fora de git"
  (cd "$iso" && assert_exit 0 "gen-env da cópia do pacote, cwd fora de git" -- env -u ZENY_SERVER_REPO bash "$iso/pacote/gen-env.sh" "$iso/.env")
  assert_eq "1" "$([[ -s "$iso/.env" ]] && echo 1 || echo 0)" ".env gerado"
  assert_eq "600" "$(stat -c %a "$iso/.env")" ".env gerado com modo 600"
  cmp_json() { python3 -c "import json,sys; d=json.load(sys.stdin)['services']; print($1)" 2>&1; }
  cfg="$(cd "$iso" && docker compose -p zeny-front -f "$iso/pacote/server/docker/compose.yaml" \
    -f "$iso/pacote/compose.override.yaml" --env-file "$iso/.env" config --format json 2>&1)"
  assert_eq "0.0.0.0" "$(cmp_json "d['proxy']['ports'][0]['host_ip']" <<<"$cfg")" "cópia do pacote: host_ip 0.0.0.0"
  assert_eq "1" "$(cmp_json "len(d['proxy']['ports'])" <<<"$cfg")" "cópia do pacote: uma única publicação de porta"
  assert_eq "http://localhost:8088" "$(cmp_json "d['api']['environment']['ZENY_STORAGE_PUBLIC_ENDPOINT']" <<<"$cfg")" "cópia do pacote: endpoint padrão"
  assert_eq "zeny-hotel-manager:$tag" "$(cmp_json "d['api']['image']" <<<"$cfg")" "cópia do pacote: imagem com a tag do commit"
  cfg2="$(cd "$iso" && ZENY_HTTP_ADDRESS=127.0.0.1 docker compose -p zeny-front -f "$iso/pacote/server/docker/compose.yaml" \
    -f "$iso/pacote/compose.override.yaml" --env-file "$iso/.env" config --format json 2>&1)"
  assert_eq "127.0.0.1" "$(cmp_json "d['proxy']['ports'][0]['host_ip']" <<<"$cfg2")" "ZENY_HTTP_ADDRESS sobrescreve o padrão do pacote"
  (cd "$iso" && bash "$iso/pacote/gen-env.sh" --public-endpoint http://192.168.0.50:8088 "$iso/.env2" >/dev/null 2>&1)
  cfg3="$(cd "$iso" && docker compose -p zeny-front -f "$iso/pacote/server/docker/compose.yaml" \
    -f "$iso/pacote/compose.override.yaml" --env-file "$iso/.env2" config --format json 2>&1)"
  assert_eq "http://192.168.0.50:8088" "$(cmp_json "d['api']['environment']['ZENY_STORAGE_PUBLIC_ENDPOINT']" <<<"$cfg3")" "public-endpoint do Windows respeitado"
  rm -rf "$iso"
fi

# --- 5. LEIA-ME renderizado ---
lm="$f7/build/demo-package/LEIA-ME.md"
leia="$(cat "$lm")"
assert_eq "" "$(grep -nE '@[A-Za-z_]+@' "$lm")" "LEIA-ME sem marcador @...@"
assert_contains "$leia" "$C1" "LEIA-ME com o commit completo (40 chars)"
assert_contains "$leia" "zeny-hotel-manager:$tag" "LEIA-ME com a imagem"
assert_contains "$leia" "New-NetFirewallRule" "LEIA-ME: regra de firewall"
assert_contains "$leia" "-Profile Private -RemoteAddress LocalSubnet" "LEIA-ME: firewall só rede privada e sub-rede local"
assert_contains "$leia" 'Remove-NetFirewallRule -DisplayName "Zeny front 8088"' "LEIA-ME: remoção da regra"
assert_contains "$leia" "2.24.4" "LEIA-ME: Compose >= 2.24.4"
assert_contains "$leia" "curl.exe -fsS http://localhost:8088/actuator/health/readiness" "LEIA-ME: readiness com curl.exe"
assert_contains "$leia" "docker load -i images" "LEIA-ME: fluxo sem internet"
assert_contains "$leia" "down -v" "LEIA-ME: recriar a demo"
assert_contains "$leia" "ZENY_ADMIN_LOGIN" "LEIA-ME: login do admin"
assert_contains "$leia" "--public-endpoint" "LEIA-ME: gen-env com --public-endpoint"
assert_contains "$leia" "alpine:3.22" "LEIA-ME: geração do .env em alpine:3.22"
for h in "## 0." "## 1." "## 2." "## 3." "## 4."; do
  assert_contains "$leia" "$h" "LEIA-ME: seção $h"
done
assert_eq "" "$(grep -nE -e '-p zeny( |$|`)' "$lm" | grep -viE 'nunca')" "LEIA-ME: nenhum comando usa -p zeny sem -front"
bad="$(grep -E '^docker compose ' "$lm" | grep -v -e '-p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env ')"
assert_eq "" "$bad" "LEIA-ME: todo docker compose com -p zeny-front, os dois -f e --env-file"
assert_eq "" "$(grep -oE '@[A-Z_]+@' "$ROOT/tools/server-env/LEIA-ME.md" | sort -u | grep -vE '^@(SERVER_COMMIT|SERVER_TAG)@$')" "modelo só usa os marcadores conhecidos"
assert_contains "$(cat "$f7/build/demo-package/images/LEIA-ME.txt")" "docker load" "images/LEIA-ME.txt ensina docker load"

# --- 6. save-images.sh ---
f8="$tmp/f8"
new_front "$f8"
run_save() { # run_save PRESENT ARGS...
  local present="$1"
  shift
  : >"$dlog"
  env PATH="$stubpath" STUB_IMAGE_PRESENT="$present" bash "$f8/tools/server-env/save-images.sh" "$@"
}
# 6a. OUT em diretório inexistente (aninhado) e com espaço; exatamente as 4 imagens
out="$tmp/dir novo/sub dir/img.tar"
assert_exit 0 "save-images em OUT com espaço e diretório inexistente" -- run_save 1 "$out"
assert_eq "1" "$([[ -d "$tmp/dir novo/sub dir" ]] && echo 1 || echo 0)" "diretório de OUT criado"
assert_eq "1" "$(grep -c ' save -o ' "$dlog")" "um único save"
saveline="$(grep ' save -o ' "$dlog")"
assert_contains "$saveline" "save -o $out " "OUT com espaço passado inteiro ao docker save"
imgs="${saveline#*"save -o $out "}"
assert_eq "caddy:2.11-alpine pgsty/minio:RELEASE.2026-08-04T00-00-00Z postgres:18 zeny-hotel-manager:$tag" \
  "$(tr ' ' '\n' <<<"$imgs" | sort | tr '\n' ' ' | sed 's/ $//')" "exatamente as 4 imagens, sem :dev e sem duplicata"

# 6b. faltando só uma imagem: exit 2, lista o que falta, sem save
cat >"$tmp/stub/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'cwd=%s %s\n' "$(pwd)" "$*" >>"$(dirname "$0")/../docker.log"
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  [[ "${3:-}" == caddy:2.11-alpine ]] && exit 1
  exit 0
fi
exit 0
STUB
: >"$dlog"
out2="$tmp/o2.tar"
assert_exit 2 "uma imagem ausente: exit 2" -- env PATH="$stubpath" bash "$f8/tools/server-env/save-images.sh" "$out2"
assert_contains "$ASSERT_OUT" "caddy:2.11-alpine" "lista a imagem ausente"
assert_not_contains "$ASSERT_OUT" "postgres:18" "não lista imagem presente como ausente"
assert_eq "0" "$(grep -c ' save ' "$dlog")" "nenhum save com imagem ausente"
assert_eq "0" "$([[ -e "$out2" ]] && echo 1 || echo 0)" "nenhum OUT criado quando falta imagem"

# 6c. docker save falha: exit != 0 e sem mensagem de sucesso
cat >"$tmp/stub/bin/docker" <<'STUB'
#!/usr/bin/env bash
[[ "${1:-}" == save ]] && exit 7
exit 0
STUB
assert_exit 1 "docker save falhando: save-images sai com 1" -- env PATH="$stubpath" bash "$f8/tools/server-env/save-images.sh" "$tmp/o3.tar"
assert_not_contains "$ASSERT_OUT" "imagens gravadas" "sem mensagem de sucesso se o save falhou"
install_docker_stub "$tmp/stub" >/dev/null

# 6d. image: com comentário no fim da linha e aspas
srvc="$tmp/srv-com"
make_fake_server "$srvc"
cat >"$srvc/docker/compose.yaml" <<'YAML'
name: zeny
services:
  api:
    image: zeny-hotel-manager:dev   # imagem local
  db:
    image: "postgres:18"  # banco
  # image: comentado:1
  proxy:
    image: caddy:2.11-alpine
YAML
_fgit "$srvc" add -A >/dev/null
_fgit "$srvc" commit -q -m "compose com comentarios"
c_com="$(_fgit "$srvc" rev-parse HEAD)"
f9="$tmp/f9"
new_front "$f9" "$c_com" "$srvc"
: >"$dlog"
code=0
ZENY_SERVER_REPO="$srvc" PATH="$stubpath" STUB_IMAGE_PRESENT=1 bash "$f9/tools/server-env/save-images.sh" "$tmp/o4.tar" >/dev/null 2>&1 || code=$?
saveline="$(grep ' save -o ' "$dlog")"
assert_eq "0" "$code" "image: com comentário no fim da linha: exit 0"
assert_not_contains "$saveline" "#" "comentário de fim de linha não vira parte do nome da imagem"
assert_not_contains "$saveline" "comentado" "linha comentada ignorada"
assert_contains "$saveline" "zeny-hotel-manager:${c_com:0:12} postgres:18 caddy:2.11-alpine" "imagens limpas e na ordem"

# 6e. OUT padrão não deixa sujeira no git do front (autoignore de build/)
f10="$tmp/f10"
new_front "$f10"
: >"$dlog"
env PATH="$stubpath" STUB_IMAGE_PRESENT=1 bash "$f10/tools/server-env/save-images.sh" >/dev/null 2>&1
assert_contains "$(grep ' save -o ' "$dlog")" "$f10/build/demo-package/images/zeny-server-images.tar" "OUT padrão no checkout principal"
assert_eq "" "$(_fgit "$f10" status --porcelain)" "save-images com OUT padrão: git status vazio (build/ autoignorado)"

# 6f. argumentos demais
assert_exit 2 "save-images com dois argumentos sai com 2" -- env PATH="$stubpath" bash "$f8/tools/server-env/save-images.sh" a b

finish
