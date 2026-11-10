#!/usr/bin/env bash
# Testes adversariais de tools/server-env/lib.sh e do override (isolamento do ambiente do servidor).
# Só usam servidores e fronts falsos em diretório temporário; `docker compose config` não sobe nada.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwx "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

make_fake_server "$tmp/server"
srv="$tmp/server"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT ZENY_STORAGE_PUBLIC_ENDPOINT \
  COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES
real_tar="$(command -v tar)"

# fe FRONT FUNC ARGS... : roda FUNC de tools/server-env/lib.sh do front dado, com cwd neutro.
fe() {
  local front="$1"
  shift
  (cd / && bash -c 'source "$1/tools/server-env/lib.sh" && shift && "$@"' _ "$front" "$@")
}

# new_front DIR [COMMIT] [SERVER] : front falso, sem .gitignore, com o pin commitado.
new_front() {
  local dir="$1" commit="${2:-$FAKE_C1}" server="${3:-$srv}"
  make_fake_front "$dir"
  write_fake_pin "$dir" "$server" "$commit"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q --allow-empty -m "pin"
}

# snap DIR : retrato da árvore (caminho, mtime, inode, modo, conteúdo).
snap() {
  (cd "$1" && find . -printf '%p %T@ %i %m\n' | sort && find . -type f -print0 | sort -z | xargs -0 sha256sum)
}

tag="${FAKE_C1:0:12}"
docker_ok=0
docker compose version >/dev/null 2>&1 && docker_ok=1

# =====================================================================
# Extração atômica
# =====================================================================

# tar que extrai tudo e depois falha: sem atomicidade sobraria uma árvore completa e "válida".
mkdir -p "$tmp/faketar/bin"
printf '#!/usr/bin/env bash\n%q "$@"\nexit 1\n' "$real_tar" >"$tmp/faketar/bin/tar"
chmod +x "$tmp/faketar/bin/tar"

fa="$tmp/fa"
new_front "$fa"
assert_exit 2 "tar falhando no fim da extração -> 2" -- env PATH="$tmp/faketar/bin:$PATH" bash -c \
  'source "$1/tools/server-env/lib.sh" && ensure_server_tree' _ "$fa"
assert_eq "" "$(find "$fa/.server" -mindepth 1 -maxdepth 1 ! -name .gitignore)" \
  "falha no tar não deixa árvore parcial nem temporário em .server/"
# a próxima chamada, já com tar bom, extrai normalmente (nada envenenado)
assert_eq "$fa/.server/$FAKE_C1" "$(fe "$fa" ensure_server_tree)" "depois da falha, a extração funciona"
assert_file "$fa/.server/$FAKE_C1/docker/compose.yaml" "árvore completa depois da recuperação"

# tar que morre por sinal no meio (SIGKILL de si mesmo depois de extrair)
fb="$tmp/fb"
new_front "$fb"
mkdir -p "$tmp/killtar/bin"
printf '#!/usr/bin/env bash\n%q "$@"\nkill -9 $$\n' "$real_tar" >"$tmp/killtar/bin/tar"
chmod +x "$tmp/killtar/bin/tar"
assert_exit 2 "tar morto por sinal -> 2" -- env PATH="$tmp/killtar/bin:$PATH" bash -c \
  'source "$1/tools/server-env/lib.sh" && ensure_server_tree' _ "$fb"
assert_eq "" "$(find "$fb/.server" -mindepth 1 -maxdepth 1 ! -name .gitignore)" "tar morto não deixa árvore parcial"

# commit do pin que sumiu do servidor
fc="$tmp/fc"
new_front "$fc"
sed -i "s/^commit=.*/commit=$(printf 'a%.0s' {1..40})/" "$fc/api/server-pin.properties"
assert_exit 2 "commit do pin ausente no servidor -> 2" -- fe "$fc" ensure_server_tree
assert_eq "" "$(find "$fc/.server" -mindepth 1 -maxdepth 1 ! -name .gitignore)" \
  "commit ausente não deixa árvore parcial nem temporário"
assert_eq "*" "$(cat "$fc/.server/.gitignore")" "mesmo na falha, .server/ já se autoignora"

# servidor inexistente
fd="$tmp/fd"
new_front "$fd"
assert_exit 2 "servidor inexistente -> 2" -- env ZENY_SERVER_REPO="$tmp/nao-existe" bash -c \
  'source "$1/tools/server-env/lib.sh" && ensure_server_tree' _ "$fd"
assert_eq "" "$(find "$fd/.server" -mindepth 1 -maxdepth 1 ! -name .gitignore)" "servidor ausente não deixa árvore"

# =====================================================================
# Árvore intocada em chamadas seguintes; override fora da árvore
# =====================================================================
fe_=$tmp/fe_front
new_front "$fe_"
fe "$fe_" ensure_server_tree >/dev/null
tree="$fe_/.server/$FAKE_C1"
printf 'edição local\n' >>"$tree/README.md"
printf 'extra\n' >"$tree/SENTINELA"
sleep 1.1
before="$(snap "$tree")"
fe "$fe_" ensure_server_tree >/dev/null
fe "$fe_" ensure_server_tree >/dev/null
assert_eq "$before" "$(snap "$tree")" "ensure_server_tree repetido não reescreve nada da árvore"
if ((docker_ok)); then
  write_test_env "$fe_/.server/zeny-front.env"
  fe "$fe_" compose config >/dev/null 2>&1
  fe "$fe_" compose config >/dev/null 2>&1
  assert_eq "$before" "$(snap "$tree")" "compose não reescreve a árvore (override fica fora)"
  assert_file "$fe_/.server/$FAKE_C1.override.yaml" "override renderizado ao lado da árvore"
  assert_eq "" "$(find "$tree" -iname '*override*')" "nenhum override dentro da árvore"
else
  skip_case "docker compose indisponível: árvore intacta depois de compose"
fi

# árvore pré-existente porém vazia/parcial: não é reextraída silenciosamente, o contrato acusa
fe2="$tmp/fe2"
new_front "$fe2"
mkdir -p "$fe2/.server/$FAKE_C1"
assert_exit 3 "diretório da árvore vazio -> contrato não confere (3)" -- fe "$fe2" ensure_server_tree
assert_eq "" "$(ls -A "$fe2/.server/$FAKE_C1")" "árvore vazia pré-existente não é preenchida por baixo dos panos"
# openapi apagado
fe3="$tmp/fe3"
new_front "$fe3"
fe "$fe3" ensure_server_tree >/dev/null
rm "$fe3/.server/$FAKE_C1/api/openapi.yaml"
assert_exit 3 "openapi.yaml ausente na árvore -> 3" -- fe "$fe3" ensure_server_tree
# pin com contract_sha256 errado: nunca imprime caminho
fe4="$tmp/fe4"
new_front "$fe4"
sed -i 's/^contract_sha256=.*/contract_sha256=0000/' "$fe4/api/server-pin.properties"
assert_exit 3 "contract_sha256 do pin errado -> 3" -- fe "$fe4" ensure_server_tree
assert_exit 3 "contract_sha256 errado segue falhando na segunda chamada" -- fe "$fe4" ensure_server_tree
out="$(fe "$fe4" ensure_server_tree 2>/dev/null)"
assert_eq "" "$out" "com contrato inválido nada é impresso no stdout"

# permissão da árvore: mktemp -d cria 0700; a árvore extraída deveria ter o modo de um diretório comum
assert_eq "$(printf '%o' $((0777 & ~$(umask))))" "$(stat -c %a "$tree")" "árvore extraída com modo padrão do umask (não 700 do mktemp)"

# extração concorrente: nenhuma chamada falha e nada vaza para dentro da árvore ou de .server/
fcc="$tmp/fcc"
new_front "$fcc"
for i in 1 2 3 4 5 6 7 8; do
  (fe "$fcc" ensure_server_tree >"$tmp/cc.$i.out" 2>&1; echo $? >"$tmp/cc.$i.rc") &
done
wait
rcs="$(cat "$tmp"/cc.*.rc | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
assert_eq " 8 0;" "$rcs" "8 extrações concorrentes terminam todas com 0"
assert_eq "" "$(find "$fcc/.server" -mindepth 1 -maxdepth 1 ! -name .gitignore ! -name "$FAKE_C1")" \
  "concorrência: sem temporários em .server/"
assert_eq "" "$(find "$fcc/.server/$FAKE_C1" -maxdepth 1 -name '.extract*')" \
  "concorrência: o mv não aninha temporário dentro da árvore já criada"

# =====================================================================
# Estado de máquina: checkout principal, autoignore
# =====================================================================
fw="$tmp/fw"
new_front "$fw"
git -C "$fw" worktree add -q "$tmp/wt" -b wt >/dev/null 2>&1
fe "$tmp/wt" ensure_server_tree >/dev/null
fe "$tmp/wt" render_override "$(fe "$tmp/wt" override_file)" 127.0.0.1
fe "$tmp/wt" ensure_state_dir "$(fe "$tmp/wt" env_file | xargs dirname)"
assert_file "$fw/.server/$FAKE_C1/docker/compose.yaml" "worktree: árvore no checkout principal"
assert_file "$fw/.server/$FAKE_C1.override.yaml" "worktree: override no checkout principal"
assert_eq "no" "$([[ -e "$tmp/wt/.server" ]] && echo yes || echo no)" "worktree: nada de .server/ no worktree"
assert_eq "" "$(git -C "$tmp/wt" status --porcelain)" "worktree: git status limpo"
assert_eq "" "$(git -C "$fw" status --porcelain)" "principal: git status limpo (sem .gitignore versionado)"

# pin do worktree vence o do principal: outro commit -> outra árvore, no principal
write_fake_pin "$tmp/wt" "$srv" "$FAKE_C3"
assert_eq "$fw/.server/$FAKE_C3" "$(fe "$tmp/wt" ensure_server_tree)" "worktree com pin C3 extrai C3 no principal"
assert_eq "$FAKE_C1" "$(fe "$fw" server_tree_dir | xargs basename)" "principal segue no pin dele (C1)"
assert_eq "${FAKE_C3:0:12}" "$(fe "$tmp/wt" server_tag)" "server_tag do worktree vem do pin do worktree"
git -C "$fw" worktree remove --force "$tmp/wt"
assert_file "$fw/.server/$FAKE_C3/docker/compose.yaml" "árvore C3 sobrevive à remoção do worktree"

# .gitignore existente nunca é sobrescrito (build/ e .server/), inclusive vazio
fg="$tmp/fg"
new_front "$fg"
mkdir -p "$fg/.server" "$fg/build"
printf 'conteudo-do-usuario\nmais\n' >"$fg/.server/.gitignore"
: >"$fg/build/.gitignore"
fe "$fg" ensure_server_tree >/dev/null
fe "$fg" ensure_state_dir "$fg/build"
fe "$fg" ensure_state_dir "$fg/.server"
assert_eq "conteudo-do-usuario
mais" "$(cat "$fg/.server/.gitignore")" ".server/.gitignore alheio intacto depois de 3 chamadas"
assert_eq "0" "$(stat -c %s "$fg/build/.gitignore")" "build/.gitignore vazio existente não é preenchido"
# idempotência do autoignore
fh="$tmp/fh"
new_front "$fh"
fe "$fh" ensure_state_dir "$fh/build"
fe "$fh" ensure_state_dir "$fh/build"
assert_eq "*" "$(cat "$fh/build/.gitignore")" "ensure_state_dir repetido mantém um único *"
# diretório com espaço e criação de pai inexistente
fe "$fh" ensure_state_dir "$fh/build/sub dir/x"
assert_file "$fh/build/sub dir/x/.gitignore" "ensure_state_dir cria diretórios com espaço"

# front em caminho com espaços inteiro funciona
fs="$tmp/front com espaço"
new_front "$fs"
assert_eq "$fs/.server/$FAKE_C1" "$(fe "$fs" ensure_server_tree)" "front em caminho com espaços"
assert_eq "" "$(git -C "$fs" status --porcelain)" "front com espaços: git status limpo"

# =====================================================================
# render_override com caracteres especiais
# =====================================================================
fr="$tmp/fr"
new_front "$fr"
check_addr() { # ENDEREÇO DESCRIÇÃO
  local addr="$1" desc="$2" line
  fe "$fr" render_override "$tmp/r.yaml" "$addr"
  line="$(grep -F 'ZENY_HTTP_ADDRESS' "$tmp/r.yaml")"
  assert_eq "      - \"\${ZENY_HTTP_ADDRESS:-$addr}:\${ZENY_HTTP_PORT:-8088}:80\"" "$line" "render_override literal: $desc"
}
check_addr 'a&b' "e comercial (patsub_replacement do bash 5.2)"
check_addr '&' "só &"
check_addr 'a\b' "barra invertida"
check_addr '\&' "barra invertida e &"
check_addr 'a/b' "barra"
check_addr 'a|b' "pipe"
check_addr 'a,b;c' "vírgula e ponto e vírgula"
check_addr '$(touch '"$tmp"'/pwned-ro)' "substituição de comando"
check_addr '`touch '"$tmp"'/pwned-ro`' "crase"
check_addr '$HOME' "variável"
check_addr '@SERVER_TAG@' "marcador dentro do endereço não é reexpandido"
check_addr '0.0.0.0' "endereço normal"
check_addr '::1' "IPv6"
assert_eq "no" "$([[ -e "$tmp/pwned-ro" ]] && echo yes || echo no)" "render_override não executa o endereço"
# marcadores restantes e demais linhas intactas
fe "$fr" render_override "$tmp/r.yaml" 0.0.0.0
assert_not_contains "$(cat "$tmp/r.yaml")" "@" "nenhum marcador restante"
assert_contains "$(cat "$tmp/r.yaml")" "ports: !override" "!override preservado"
assert_eq "$(sed -n 's/^.*image: //p' "$tmp/r.yaml")" "zeny-hotel-manager:$tag" "imagem renderizada"
# DEST maior pré-existente é totalmente substituído
head -c 5000 /dev/zero | tr '\0' 'x' >"$tmp/r2.yaml"
fe "$fr" render_override "$tmp/r2.yaml" 127.0.0.1
assert_eq "no" "$(grep -q xxxx "$tmp/r2.yaml" && echo yes || echo no)" "DEST é truncado, sem sobra"
# modelo termina em uma única quebra de linha
assert_eq "10" "$(tail -c1 "$tmp/r2.yaml" | od -An -tu1 | tr -d ' ')" "override termina em newline"
assert_eq "$(wc -l <"$ROOT/tools/server-env/compose.override.yaml")" "$(wc -l <"$tmp/r2.yaml")" "mesmo número de linhas do modelo"

# =====================================================================
# env_get
# =====================================================================
ev() { ZENY_FRONT_ENV_FILE="$tmp/ev.env" fe "$fr" env_get "$@"; }
printf '%s\n' \
  'ZENY_HTTP_PORT_EXTRA=111' \
  'ZENY_HTTP=222' \
  'XZENY_HTTP_PORT=333' \
  'ZENY_HTTP_PORT=9099' \
  '# COMENTADA=1' \
  '#COMENTADA2=1' \
  'export EXPORTADA=exp' \
  '  ESPACADA=sp' \
  'VAZIA=' \
  'IGUAL=a=b=c' \
  'COM_ASPAS="x y"' \
  'COM_HASH=abc#def' \
  'BARRA=a\nb\\c' \
  'CMD=$(touch '"$tmp"'/pwned-env)' \
  'CRASE=`touch '"$tmp"'/pwned-env`' \
  'PONTO;touch '"$tmp"'/pwned-env' \
  '$(touch '"$tmp"'/pwned-env)=1' \
  'ZENY.OUTRA=pt' >"$tmp/ev.env"
printf 'CRLF=valor\r\nULTIMA=semnewline' >>"$tmp/ev.env"
assert_eq "9099" "$(ev ZENY_HTTP_PORT 8088)" "env_get: chave que é sufixo/prefixo de outras não confunde"
assert_eq "222" "$(ev ZENY_HTTP)" "env_get: chave curta, prefixo de outra"
assert_eq "111" "$(ev ZENY_HTTP_PORT_EXTRA)" "env_get: chave longa"
assert_eq "dflt" "$(ev ZENY dflt)" "env_get: prefixo puro de chaves existentes não casa"
assert_eq "dflt" "$(ev COMENTADA dflt)" "env_get: linha comentada com espaço não casa"
assert_eq "dflt" "$(ev COMENTADA2 dflt)" "env_get: linha comentada sem espaço não casa"
assert_eq "dflt" "$(ev EXPORTADA dflt)" "env_get: 'export KEY=' não é lido como KEY (D: contrato é ^KEY=)"
assert_eq "dflt" "$(ev ESPACADA dflt)" "env_get: linha indentada não casa"
assert_eq "" "$(ev VAZIA dflt)" "env_get: KEY= vazio vence o padrão"
assert_eq "a=b=c" "$(ev IGUAL)" "env_get: valor com vários ="
assert_eq '"x y"' "$(ev COM_ASPAS)" "env_get: valor devolvido cru, com aspas"
assert_eq "abc#def" "$(ev COM_HASH)" "env_get: # dentro do valor"
assert_eq 'a\nb\\c' "$(ev BARRA)" "env_get: barras invertidas literais (sem escapes do awk)"
assert_eq '$(touch '"$tmp"'/pwned-env)' "$(ev CMD)" "env_get devolve substituição de comando como texto"
assert_eq '`touch '"$tmp"'/pwned-env`' "$(ev CRASE)" "env_get devolve crase como texto"
assert_eq "valor" "$(ev CRLF)" "env_get: CRLF removido"
assert_eq "semnewline" "$(ev ULTIMA)" "env_get: última linha sem newline"
assert_eq "dflt" "$(ev 'ZENY.HTTP_PORT' dflt)" "env_get: ponto na chave não é regex"
assert_eq "pt" "$(ev 'ZENY.OUTRA')" "env_get: chave com ponto literal"
assert_eq "dflt" "$(ev 'ZEN.' dflt)" "env_get: chave não é padrão"
assert_eq "no" "$([[ -e "$tmp/pwned-env" ]] && echo yes || echo no)" "env_get nunca executa o conteúdo"
assert_eq "" "$(ev AUSENTE)" "env_get sem padrão e ausente devolve vazio"
assert_exit 0 "env_get ausente sai com 0" -- ev AUSENTE
# chave duplicada: o compose usa o último valor; env_get precisa concordar
printf 'DUP=1\nDUP=2\n' >"$tmp/dup.env"
if ((docker_ok)); then
  fdup="$tmp/fdup"
  new_front "$fdup"
  write_test_env "$tmp/dup2.env" ZENY_HTTP_PORT=1111 ZENY_HTTP_PORT=2222
  dup_compose="$(ZENY_FRONT_ENV_FILE="$tmp/dup2.env" fe "$fdup" compose config --format json | jq -r '.services.proxy.ports[0].published')"
  dup_env="$(ZENY_FRONT_ENV_FILE="$tmp/dup2.env" fe "$fdup" env_get ZENY_HTTP_PORT)"
  assert_eq "$dup_compose" "$dup_env" "chave duplicada: env_get e docker compose leem o mesmo valor"
fi
# env file com espaço no caminho e env_file padrão
mkdir -p "$tmp/dir com espaço"
printf 'ZENY_HTTP_PORT=7\n' >"$tmp/dir com espaço/a b.env"
assert_eq "7" "$(ZENY_FRONT_ENV_FILE="$tmp/dir com espaço/a b.env" fe "$fr" env_get ZENY_HTTP_PORT)" "env_get com caminho com espaço"
assert_eq "$fr/.server/zeny-front.env" "$(fe "$fr" env_file)" "env_file padrão no principal"
assert_eq "dflt" "$(env -u ZENY_FRONT_ENV_FILE bash -c 'source "$1/tools/server-env/lib.sh" && env_get QQ dflt' _ "$fr")" "env_get sem env file padrão usa DEFAULT"
# diretório no lugar do arquivo não derruba nem executa
mkdir -p "$tmp/envdir.env"
assert_eq "dflt" "$(ZENY_FRONT_ENV_FILE="$tmp/envdir.env" fe "$fr" env_get X dflt 2>/dev/null)" "env_get com diretório no lugar do arquivo usa o padrão"

# =====================================================================
# Projeto, argv do docker (stub) e isolamento do compose
# =====================================================================
fk="$tmp/fk"
new_front "$fk"
mkdir -p "$fk/.server"
write_test_env "$fk/.server/zeny-front.env"
stubpath="$(install_docker_stub "$tmp/stub")"
(cd / && PATH="$stubpath" ZENY_FRONT_PROJECT=zeny COMPOSE_PROJECT_NAME=zeny bash -c \
  'source "$1/tools/server-env/lib.sh" && compose up -d "um arg" --x' _ "$fk")
argv="$(cut -d' ' -f2- "$tmp/stub/docker.log")"
assert_eq "compose -p zeny-front -f $fk/.server/$FAKE_C1/docker/compose.yaml -f $fk/.server/$FAKE_C1.override.yaml --env-file $fk/.server/zeny-front.env up -d um arg --x" \
  "$argv" "argv exato do docker: projeto zeny-front, dois -f, --env-file, argumentos repassados"
assert_eq "1" "$(grep -c . "$tmp/stub/docker.log")" "uma única chamada ao docker"
assert_not_contains " $argv " " -p zeny " "nunca -p zeny"
assert_exit 1 "ZENY_FRONT_PROJECT é readonly dentro do processo" -- bash -c \
  'source "$1/tools/server-env/lib.sh"; ZENY_FRONT_PROJECT=zeny; [[ "$ZENY_FRONT_PROJECT" == zeny-front ]] && exit 0; exit 1' _ "$fk"
# status do docker é propagado
(cd / && PATH="$stubpath" STUB_DOCKER_EXIT=7 bash -c 'source "$1/tools/server-env/lib.sh" && compose ps' _ "$fk")
assert_eq "7" "$?" "compose propaga o código de saída do docker"
# env file inexistente é problema do docker (stub não o checa); ensure_server_tree falhando impede o docker
: >"$tmp/stub/docker.log"
assert_exit 3 "compose com contrato adulterado não chama o docker" -- bash -c \
  'printf "#x\n" >> "$2/.server/$3/api/openapi.yaml"; PATH="$4" bash -c "source \"$1/tools/server-env/lib.sh\" && compose ps" _ "$1"' _ "$fk" "$fk" "$FAKE_C1" "$stubpath"
assert_eq "0" "$(grep -c . "$tmp/stub/docker.log")" "docker não é chamado se a árvore não confere"

if ((docker_ok == 0)); then
  skip_case "docker compose indisponível: casos de compose config"
else
  # servidor falso mais rico: volume nomeado, rede, container_name ausente, profile
  srv2="$tmp/server2"
  mkdir -p "$srv2/api" "$srv2/docker"
  git init -q -b main "$srv2"
  _fake_contract 0 1 >"$srv2/api/openapi.yaml"
  cat >"$srv2/docker/compose.yaml" <<'YAML'
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
    networks: [back]
  db:
    image: postgres:18
    volumes:
      - pgdata:/var/lib/postgresql
    networks: [back]
  proxy:
    image: caddy:2.11-alpine
    ports:
      - "${ZENY_HTTP_ADDRESS:-127.0.0.1}:${ZENY_HTTP_PORT:-80}:80"
    networks: [back]
volumes:
  pgdata: {}
networks:
  back: {}
YAML
  _fgit "$srv2" add -A >/dev/null
  _fgit "$srv2" commit -q -m "S2"
  s2="$(_fgit "$srv2" rev-parse HEAD)"
  fi_="$tmp/fiso"
  new_front "$fi_" "$s2" "$srv2"
  mkdir -p "$fi_/.server"
  write_test_env "$fi_/.server/zeny-front.env"
  cfgi() { ZENY_SERVER_REPO="$srv2" fe "$fi_" compose config --format json; }

  j="$(COMPOSE_PROJECT_NAME=zeny COMPOSE_FILE=/nonexistent.yaml COMPOSE_PROFILES=x ZENY_FRONT_PROJECT=zeny cfgi 2>&1)"
  if ! jq -e . >/dev/null 2>&1 <<<"$j"; then
    _assert_fail "compose config do servidor falso rico" "$j"
  else
    assert_eq "zeny-front" "$(jq -r .name <<<"$j")" "COMPOSE_PROJECT_NAME/ZENY_FRONT_PROJECT=zeny no ambiente não vencem o -p"
    assert_eq "zeny-front_pgdata" "$(jq -r .volumes.pgdata.name <<<"$j")" "volume do banco pertence ao projeto zeny-front"
    assert_eq "zeny-front_back" "$(jq -r .networks.back.name <<<"$j")" "rede pertence ao projeto zeny-front"
    # nenhuma string estrutural (fora de environment) cita o projeto zeny ou :dev
    leaks="$(jq -r 'del(.services[].environment) | [.. | strings
      | select(test("^zeny($|[^-]|-(?!front|hotel-manager))") or test(":dev$") or test("^zeny_"))] | unique | .[]' <<<"$j")"
    assert_eq "" "$leaks" "nada na config cita o projeto zeny nem a imagem :dev"
    assert_eq "0" "$(jq -r '[.services[].image // empty | select(endswith(":dev"))] | length' <<<"$j")" "nenhuma imagem :dev"
    assert_eq "zeny-hotel-manager:${s2:0:12}" "$(jq -r .services.api.image <<<"$j")" "imagem do api é a do commit"
    assert_eq "$(jq -r '.services | keys | join(",")' <<<"$j")" "api,db,proxy" "serviços do servidor preservados"
    assert_eq "1" "$(jq '.services.proxy.ports | length' <<<"$j")" "porta do servidor (80) substituída, não somada"
    assert_eq "80" "$(jq -r '.services.proxy.ports[0].target' <<<"$j")" "alvo do proxy continua 80"
  fi
  # o servidor falso rico usa a tag do próprio commit
  assert_eq "${s2:0:12}" "$(ZENY_SERVER_REPO="$srv2" fe "$fi_" server_tag)" "server_tag do servidor rico"

  # --- interpolação aninhada do endpoint ---
  fn="$tmp/fn"
  new_front "$fn"
  mkdir -p "$fn/.server"
  ep() { # LINHAS... : endpoint resultante com o env file padrão + LINHAS
    write_test_env "$fn/.server/zeny-front.env" "$@"
    fe "$fn" compose config --format json | jq -r .services.api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT
  }
  pt() {
    write_test_env "$fn/.server/zeny-front.env" "$@"
    fe "$fn" compose config --format json | jq -r '.services.proxy.ports[0] | "\(.host_ip):\(.published)"'
  }
  assert_eq "http://localhost:8088" "$(ep)" "endpoint: sem nada"
  assert_eq "http://localhost:9099" "$(ep ZENY_HTTP_PORT=9099)" "endpoint: segue a porta"
  assert_eq "http://localhost:8088" "$(ep ZENY_HTTP_PORT=)" "endpoint: porta vazia cai em 8088"
  assert_eq "http://localhost:8088" "$(ep ZENY_STORAGE_PUBLIC_ENDPOINT=)" "endpoint: endpoint vazio cai no padrão"
  assert_eq "https://zeny.example:9443" "$(ep ZENY_HTTP_PORT=9099 ZENY_STORAGE_PUBLIC_ENDPOINT=https://zeny.example:9443)" "endpoint explícito vence a porta"
  assert_eq "http://192.168.0.5:8088" "$(ep ZENY_STORAGE_PUBLIC_ENDPOINT=http://192.168.0.5:8088)" "endpoint explícito com IP"
  assert_eq "http://localhost:80" "$(ep ZENY_HTTP_PORT=80)" "endpoint: porta 80 não é omitida"
  assert_eq "127.0.0.1:8088" "$(pt)" "porta/host padrão"
  assert_eq "127.0.0.1:8088" "$(pt ZENY_HTTP_ADDRESS= ZENY_HTTP_PORT=)" "host e porta vazios caem no padrão"
  assert_eq "192.168.0.5:9099" "$(pt ZENY_HTTP_ADDRESS=192.168.0.5 ZENY_HTTP_PORT=9099)" "host e porta do env"
  # porta com $ ou espaço no env file não executa nada
  ep 'ZENY_HTTP_PORT=$(touch '"$tmp"'/pwned-compose)' >/dev/null 2>&1
  assert_eq "no" "$([[ -e "$tmp/pwned-compose" ]] && echo yes || echo no)" "porta com substituição de comando não executa"

  # --- estado limpo depois do compose, front sem .gitignore ---
  assert_eq "" "$(git -C "$fn" status --porcelain)" "git status do front limpo depois de compose"

  # --- override do pacote (0.0.0.0) e o do notebook não se misturam ---
  write_test_env "$fn/.server/zeny-front.env"
  fe "$fn" render_override "$tmp/pac.yaml" 0.0.0.0
  jp="$(cd / && docker compose -p zeny-front -f "$fn/.server/$FAKE_C1/docker/compose.yaml" -f "$tmp/pac.yaml" --env-file "$fn/.server/zeny-front.env" config --format json)"
  assert_eq "0.0.0.0" "$(jq -r '.services.proxy.ports[0].host_ip' <<<"$jp")" "override do pacote: 0.0.0.0"
  assert_eq "127.0.0.1:8088" "$(pt)" "override do notebook continua 127.0.0.1 depois"
  # env file ausente: o docker reclama, o código de saída não é 0
  assert_exit 1 "env file inexistente -> falha do docker" -- env ZENY_FRONT_ENV_FILE="$tmp/nao-existe.env" bash -c \
    'source "$1/tools/server-env/lib.sh" && compose config >/dev/null' _ "$fn"
fi

finish
