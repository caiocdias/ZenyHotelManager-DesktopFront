#!/usr/bin/env bash
# Testes de tools/server-env/package.sh e save-images.sh. Servidor e front falsos, docker stub.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT

stubpath="$(install_docker_stub "$tmp/stub")"
dlog="$tmp/stub/docker.log"
tag="${FAKE_C1:0:12}"

front="$tmp/front"
make_fake_front "$front"
write_fake_pin "$front" "$srv" "$FAKE_C1"
_fgit "$front" add -A >/dev/null
_fgit "$front" commit -q --allow-empty -m "pin"
make_fake_jars "$front"

# Segredos de máquina que jamais podem entrar no pacote.
mkdir -p "$front/.server"
write_test_env "$front/.server/zeny-front.env"
write_test_env "$front/tools/server-env/.env"
write_test_env "$front/.server/seed-secrets.env"
write_test_env "$front/docs/demo/segredo.env"
write_test_env "$front/app/build/libs/seed-secrets.env"
mkdir -p "$front/build/outro"
echo preservar >"$front/build/outro/arquivo.txt"

pkg="$front/build/demo-package"
assert_exit 0 "package.sh sai com 0" -- bash "$front/tools/server-env/package.sh"
assert_contains "$ASSERT_OUT" "$pkg" "imprime o caminho do pacote"

# --- conteúdo ---
assert_file "$pkg/server/docker/compose.yaml" "server/docker/compose.yaml"
assert_eq "$(git -C "$srv" show "$FAKE_C1:docker/compose.yaml")" "$(cat "$pkg/server/docker/compose.yaml")" "compose.yaml igual ao do commit"
assert_file "$pkg/compose.override.yaml" "compose.override.yaml"
assert_file "$pkg/.env.example" ".env.example"
assert_file "$pkg/gen-env.sh" "gen-env.sh"
assert_file "$pkg/server-pin.properties" "server-pin.properties"
assert_eq "$(cat "$front/api/server-pin.properties")" "$(cat "$pkg/server-pin.properties")" "pin copiado"
assert_file "$pkg/LEIA-ME.md" "LEIA-ME.md"
assert_file "$pkg/app/zeny-desktop.jar" "app/zeny-desktop.jar"
assert_file "$pkg/app/demo-seed.jar" "app/demo-seed.jar"
assert_file "$pkg/images/LEIA-ME.txt" "images/LEIA-ME.txt"
assert_contains "$(cat "$pkg/images/LEIA-ME.txt")" "save-images.sh" "images/LEIA-ME.txt cita save-images.sh"
assert_eq "1" "$([[ -x "$pkg/gen-env.sh" ]] && echo 1 || echo 0)" "gen-env.sh executável"

leia="$(cat "$pkg/LEIA-ME.md")"
if grep -qE '@[A-Z_]+@' "$pkg/LEIA-ME.md"; then
  _assert_fail "LEIA-ME.md sem marcador @...@"
fi
assert_contains "$leia" "$FAKE_C1" "LEIA-ME.md contém o SHA do commit"
assert_contains "$leia" "zeny-hotel-manager:$tag" "LEIA-ME.md contém a imagem com a tag"

ov="$(cat "$pkg/compose.override.yaml")"
assert_contains "$ov" "zeny-hotel-manager:$tag" "override com a imagem do commit"
assert_contains "$ov" "ZENY_HTTP_ADDRESS:-0.0.0.0}" "override com endereço padrão 0.0.0.0"
assert_not_contains "$ov" "@" "override sem marcador"

# --- nenhum .env ---
assert_eq "" "$(find "$pkg" \( -name '.env' -o -name '*.env' \))" "pacote sem .env nem *.env"
assert_eq "" "$(grep -rl -e 'senha-db-teste' -e 'chave-jwt-fake' "$pkg" 2>/dev/null)" "nenhum valor de segredo no pacote"

# --- git status limpo sem .gitignore ---
assert_file "$front/build/.gitignore" "build autoignorado"
assert_eq "" "$(git -C "$front" status --porcelain -- build .server)" "git status do front limpo (build e .server)"

# --- segunda execução ---
echo lixo >"$pkg/estranho.txt"
assert_exit 0 "segunda execução" -- bash "$front/tools/server-env/package.sh"
assert_eq "0" "$([[ -e "$pkg/estranho.txt" ]] && echo 1 || echo 0)" "arquivo estranho apagado"
assert_file "$front/build/outro/arquivo.txt" "build/outro intacto"
assert_file "$pkg/LEIA-ME.md" "pacote recriado"

# --- JARs, roteiro e VERSAO.txt ---
# same_file A B : 1 se A e B existem e têm o mesmo conteúdo.
same_file() { cmp -s -- "$1" "$2" && echo 1 || echo 0; }
# versao KEY : valor de "KEY=" no VERSAO.txt do pacote $pkg.
versao() { grep -m1 "^$1=" "$pkg/VERSAO.txt" | cut -d= -f2-; }
# jar_front DIR : front falso com pin, JARs e roteiro.
jar_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$FAKE_C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m pin
  make_fake_jars "$1"
}

assert_eq "1" "$(same_file "$front/app/build/libs/zeny-desktop.jar" "$pkg/app/zeny-desktop.jar")" "app/zeny-desktop.jar igual ao do checkout"
assert_eq "1" "$(same_file "$front/demo-seed/build/libs/demo-seed.jar" "$pkg/app/demo-seed.jar")" "app/demo-seed.jar igual ao do checkout"
assert_eq "1" "$(same_file "$front/docs/demo/roteiro-m1.md" "$pkg/roteiro-m1.md")" "roteiro-m1.md igual ao docs/demo/roteiro-m1.md"
assert_eq "0" "$([[ -e "$pkg/app/LEIA-ME.txt" ]] && echo 1 || echo 0)" "sem app/LEIA-ME.txt"

assert_file "$pkg/VERSAO.txt" "VERSAO.txt"
assert_eq "$(git -C "$front" rev-parse HEAD)" "$(versao front_commit)" "VERSAO.txt: front_commit é o HEAD do checkout"
assert_eq "false" "$(versao front_dirty)" "VERSAO.txt: front_dirty=false com a árvore limpa"
assert_eq "$FAKE_C1" "$(versao server_commit)" "VERSAO.txt: server_commit é o do pin"
assert_eq "$(sha256sum "$front/app/build/libs/zeny-desktop.jar" | cut -d' ' -f1)" "$(versao 'zeny-desktop.jar sha256')" "VERSAO.txt: sha256 do zeny-desktop.jar"
assert_eq "$(sha256sum "$front/demo-seed/build/libs/demo-seed.jar" | cut -d' ' -f1)" "$(versao 'demo-seed.jar sha256')" "VERSAO.txt: sha256 do demo-seed.jar"
versao_txt="$(cat "$pkg/VERSAO.txt")"
assert_not_contains "$versao_txt" "/home/" "VERSAO.txt sem /home/"
assert_not_contains "$versao_txt" "$front" "VERSAO.txt sem o caminho do checkout"
assert_not_contains "$versao_txt" "$tmp" "VERSAO.txt sem o caminho temporário"

# front padrão (nenhum commit no código dos JARs): sem aviso e sem erro no stderr
err="$(bash "$front/tools/server-env/package.sh" 2>&1 >/dev/null)"
assert_eq "" "$err" "front sem commit no código dos JARs: stderr vazio (sem aviso nem erro)"

# árvore suja: arquivo versionado modificado
fd="$tmp/front-sujo"
jar_front "$fd"
echo nota >"$fd/notas.txt"
_fgit "$fd" add notas.txt >/dev/null
_fgit "$fd" commit -q -m notas
echo mudou >>"$fd/notas.txt"
assert_exit 0 "package.sh com árvore suja" -- bash "$fd/tools/server-env/package.sh"
pkg="$fd/build/demo-package"
assert_eq "true" "$(versao front_dirty)" "VERSAO.txt: front_dirty=true com arquivo versionado modificado"
assert_eq "$(git -C "$fd" rev-parse HEAD)" "$(versao front_commit)" "VERSAO.txt: front_commit da árvore suja"

# --- artefatos ausentes ou inválidos: falha antes de apagar o pacote anterior ---
fa="$tmp/front-artefatos"
jar_front "$fa"
assert_exit 0 "package.sh inicial (artefatos)" -- bash "$fa/tools/server-env/package.sh"
echo marcador >"$fa/build/demo-package/marcador.txt"
app_jar="$fa/app/build/libs/zeny-desktop.jar"
seed_jar="$fa/demo-seed/build/libs/demo-seed.jar"
roteiro="$fa/docs/demo/roteiro-m1.md"
# falha_preserva DESCRIÇÃO TEXTO : package.sh sai com 1, cita TEXTO e deixa o pacote anterior intacto;
# depois restaura os três artefatos válidos.
falha_preserva() {
  assert_exit 1 "$1: package.sh sai com 1" -- bash "$fa/tools/server-env/package.sh"
  assert_contains "$ASSERT_OUT" "$2" "$1: mensagem cita $2"
  assert_file "$fa/build/demo-package/marcador.txt" "$1: pacote anterior intacto"
  rm -rf -- "$app_jar" "$seed_jar" "$roteiro"
  make_fake_jars "$fa"
}
# troca ARQUIVO TIPO : substitui ARQUIVO por ausente, symlink (para um ZIP válido), vazio, não-ZIP, diretório
# ou arquivo sem permissão de leitura.
cp "$app_jar" "$tmp/valido.jar"
echo 'roteiro de fora' >"$tmp/outro-roteiro.md"
troca() {
  rm -f -- "$1"
  case "$2" in
    symlink) ln -s "$3" "$1" ;;
    vazio) : >"$1" ;;
    nao-zip) printf 'PK\x05\x06 nao sou um zip local\n' >"$1" ;;
    diretorio) mkdir "$1" ;;
    ilegivel) printf 'roteiro sem leitura\n' >"$1" && chmod 000 "$1" ;;
    truncado) head -c 30 -- "$3" >"$1" ;; # começa com PK\x03\x04, mas o ZIP está cortado
    crc) # ZIP com diretório central íntegro e um byte de conteúdo trocado (CRC não confere)
      python3 -I -c 'import sys; d = open(sys.argv[1], "rb").read(); open(sys.argv[2], "wb").write(d.replace(b"Manifest-Version", b"Manifest-Versiom", 1))' "$3" "$1" ;;
  esac
}

for tipo in ausente symlink vazio nao-zip diretorio truncado crc; do
  troca "$app_jar" "$tipo" "$tmp/valido.jar"
  falha_preserva "zeny-desktop.jar $tipo" "./gradlew :app:desktopJar"
  troca "$seed_jar" "$tipo" "$tmp/valido.jar"
  falha_preserva "demo-seed.jar $tipo" "./gradlew :demo-seed:seedJar"
done
tipos_roteiro=(ausente symlink vazio diretorio)
# Como root, chmod 000 não impede a leitura: o caso ilegivel não provaria nada.
if (($(id -u) == 0)); then
  skip_case "roteiro ilegível (rodando como root)"
else
  tipos_roteiro+=(ilegivel)
fi
for tipo in "${tipos_roteiro[@]}"; do
  troca "$roteiro" "$tipo" "$tmp/outro-roteiro.md"
  falha_preserva "roteiro $tipo" "docs/demo/roteiro-m1.md"
done
# Arquivos de origem do pacote (LEIA-ME, .env.example, gen-env.sh e compose.override.yaml):
# ilegível ou symlink (por exemplo, para um arquivo de segredo) falha antes do rm -rf.
origens=(LEIA-ME.md .env.example gen-env.sh compose.override.yaml)
printf 'ZENY_SEGREDO=nao-pode-sair\n' >"$tmp/segredo.env"
for origem in "${origens[@]}"; do
  arq="$fa/tools/server-env/$origem"
  mv -- "$arq" "$arq.orig"
  ln -s -- "$tmp/segredo.env" "$arq"
  falha_preserva "$origem symlink" "$origem"
  rm -f -- "$arq"
  mv -- "$arq.orig" "$arq"
done
if (($(id -u) == 0)); then
  skip_case "arquivo de origem ilegível (rodando como root)"
else
  for origem in "${origens[@]}"; do
    arq="$fa/tools/server-env/$origem"
    modo="$(stat -c %a -- "$arq")"
    chmod 000 -- "$arq"
    falha_preserva "$origem ilegível" "$origem"
    chmod "$modo" -- "$arq"
  done
fi
assert_exit 0 "artefatos restaurados: package.sh volta a sair com 0" -- bash "$fa/tools/server-env/package.sh"

# sem python3 no PATH: falha clara antes de apagar o pacote anterior (a validação do ZIP usa python3)
sempy="$tmp/sem-python3"
mkdir -p "$sempy"
IFS=: read -ra path_dirs <<<"$PATH"
for d in "${path_dirs[@]}"; do
  for exe in "$d"/*; do
    nome="${exe##*/}"
    [[ -x "$exe" && ! -d "$exe" && "$nome" != python3* && ! -e "$sempy/$nome" ]] && ln -s "$exe" "$sempy/$nome"
  done
done
echo marcador >"$fa/build/demo-package/marcador.txt"
assert_exit 1 "sem python3: package.sh sai com 1" -- env PATH="$sempy" bash "$fa/tools/server-env/package.sh"
assert_contains "$ASSERT_OUT" "python3" "sem python3: mensagem cita python3"
assert_file "$fa/build/demo-package/marcador.txt" "sem python3: pacote anterior intacto"

# --- make_fake_jars: info/exclude sem quebra de linha final não cola o padrão na última linha ---
fx="$tmp/front-exclude"
git init -q -b main "$fx"
mkdir -p "$fx/.git/info"
printf '/ja-existia' >"$fx/.git/info/exclude"
make_fake_jars "$fx"
assert_eq "1" "$(grep -cx '/ja-existia' "$fx/.git/info/exclude")" "make_fake_jars: linha anterior preservada inteira"
for padrao in /app/build/ /demo-seed/build/ /docs/demo/; do
  assert_eq "1" "$(grep -cxF -- "$padrao" "$fx/.git/info/exclude")" "make_fake_jars: $padrao numa linha própria"
done
assert_eq "" "$(git -C "$fx" status --porcelain --untracked-files=all)" "make_fake_jars: git status vazio"

# --- JAR velho: aviso, sem falhar ---
fo="$tmp/front-velho"
jar_front "$fo"
mkdir -p "$fo/app/src"
echo 'fun main() {}' >"$fo/app/src/Main.kt"
_fgit "$fo" add app/src >/dev/null
GIT_COMMITTER_DATE=@1767225600 GIT_AUTHOR_DATE=@1767225600 _fgit "$fo" commit -q -m "código do app" # 2026-01-01
touch -d @1764547200 "$fo/app/build/libs/zeny-desktop.jar"                                          # 2025-12-01
touch -d @1769904000 "$fo/demo-seed/build/libs/demo-seed.jar"                                       # 2026-02-01
assert_exit 0 "JAR velho: package.sh sai com 0" -- bash "$fo/tools/server-env/package.sh"
assert_contains "$ASSERT_OUT" "aviso:" "JAR velho: aviso no stderr"
assert_contains "$(grep 'aviso:' <<<"$ASSERT_OUT")" "zeny-desktop.jar" "JAR velho: aviso cita zeny-desktop.jar"
assert_not_contains "$(grep 'aviso:' <<<"$ASSERT_OUT")" "demo-seed.jar" "JAR novo: sem aviso para demo-seed.jar"
assert_file "$fo/build/demo-package/app/zeny-desktop.jar" "JAR velho: copiado mesmo assim"

touch -d @1769904000 "$fo/app/build/libs/zeny-desktop.jar" # 2026-02-01
mkdir -p "$fo/docs"
echo doc >"$fo/docs/notas.md"
_fgit "$fo" add docs/notas.md >/dev/null
GIT_COMMITTER_DATE=@1772323200 GIT_AUTHOR_DATE=@1772323200 _fgit "$fo" commit -q -m "só docs" # 2026-03-01
err="$(bash "$fo/tools/server-env/package.sh" 2>&1 >/dev/null)"
assert_not_contains "$err" "aviso:" "JAR mais novo que o código (commit posterior só em docs/): sem aviso"

mkdir -p "$fo/core"
echo x >"$fo/core/build.gradle.kts"
_fgit "$fo" add core >/dev/null
GIT_COMMITTER_DATE=@1772323200 GIT_AUTHOR_DATE=@1772323200 _fgit "$fo" commit -q -m "core" # 2026-03-01
err="$(bash "$fo/tools/server-env/package.sh" 2>&1 >/dev/null)"
assert_contains "$err" "zeny-desktop.jar" "commit em core/ depois do JAR: aviso cita zeny-desktop.jar"
assert_contains "$err" "demo-seed.jar" "commit em core/ depois do JAR: aviso cita demo-seed.jar"

# commits só em testes (*/src/test/) não entram nos JARs: JAR mais antigo que eles não gera aviso
ft="$tmp/front-testes"
jar_front "$ft"
for f in app/src/test/kotlin/AppTest.kt core/src/test/kotlin/CoreTest.kt demo-seed/src/test/kotlin/SeedTest.kt; do
  mkdir -p "$ft/${f%/*}"
  echo "// $f" >"$ft/$f"
  _fgit "$ft" add -- "$f" >/dev/null
  GIT_COMMITTER_DATE=@1772323200 GIT_AUTHOR_DATE=@1772323200 _fgit "$ft" commit -q -m "teste $f" # 2026-03-01
done
touch -d @1769904000 "$ft/app/build/libs/zeny-desktop.jar" "$ft/demo-seed/build/libs/demo-seed.jar" # 2026-02-01
err="$(bash "$ft/tools/server-env/package.sh" 2>&1 >/dev/null)"
assert_eq "" "$err" "commit só em */src/test/ depois dos JARs: sem aviso"
mkdir -p "$ft/app/src/main/kotlin"
echo 'fun main() {}' >"$ft/app/src/main/kotlin/Main.kt"
_fgit "$ft" add app/src/main >/dev/null
GIT_COMMITTER_DATE=@1775001600 GIT_AUTHOR_DATE=@1775001600 _fgit "$ft" commit -q -m "código do app" # 2026-04-01
err="$(bash "$ft/tools/server-env/package.sh" 2>&1 >/dev/null)"
assert_contains "$err" "aviso: zeny-desktop.jar" "commit em app/src/main/ depois dos JARs: aviso para zeny-desktop.jar"
assert_contains "$err" "aviso: demo-seed.jar" "commit em app/src/main/ depois dos JARs: aviso para demo-seed.jar"

# --- de um worktree: JARs e commit do worktree, pacote no checkout principal ---
fw="$tmp/front-wt"
jar_front "$fw"
_fgit "$fw" worktree add -q "$tmp/front-wt-arvore" -b wt >/dev/null 2>&1
wt="$tmp/front-wt-arvore"
make_fake_jars "$wt"
printf '# Roteiro do worktree\n' >"$wt/docs/demo/roteiro-m1.md"
_fgit "$wt" commit -q --allow-empty -m "commit do worktree"
assert_exit 0 "package.sh de um worktree" -- bash "$wt/tools/server-env/package.sh"
pkg="$fw/build/demo-package"
assert_eq "1" "$(same_file "$wt/app/build/libs/zeny-desktop.jar" "$pkg/app/zeny-desktop.jar")" "worktree: zeny-desktop.jar do worktree"
assert_eq "1" "$(same_file "$wt/demo-seed/build/libs/demo-seed.jar" "$pkg/app/demo-seed.jar")" "worktree: demo-seed.jar do worktree"
assert_eq "0" "$(same_file "$fw/app/build/libs/zeny-desktop.jar" "$pkg/app/zeny-desktop.jar")" "worktree: não usa o JAR do checkout principal"
assert_eq "1" "$(same_file "$wt/docs/demo/roteiro-m1.md" "$pkg/roteiro-m1.md")" "worktree: roteiro do worktree"
assert_eq "$(git -C "$wt" rev-parse HEAD)" "$(versao front_commit)" "worktree: front_commit do worktree"
assert_eq "false" "$(versao front_dirty)" "worktree: front_dirty=false"
assert_eq "0" "$([[ -e "$wt/build" ]] && echo 1 || echo 0)" "worktree sem build/"
assert_eq "" "$(git -C "$wt" status --porcelain)" "worktree: git status vazio"
pkg="$front/build/demo-package"

# --- gen-env do pacote + compose real ---
if ! docker compose version >/dev/null 2>&1; then
  skip_case "docker compose indisponível: pacote com compose config"
else
  work="$(mktemp -d)"
  (cd "$work" && assert_exit 0 "gen-env do pacote fora de git" -- bash "$pkg/gen-env.sh" "$work/.env")
  cfg="$(cd "$work" && docker compose -p zeny-front \
    -f "$pkg/server/docker/compose.yaml" -f "$pkg/compose.override.yaml" \
    --env-file "$work/.env" config --format json 2>&1)"
  if command -v python3 >/dev/null 2>&1; then
    hostip="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["proxy"]["ports"][0]["host_ip"])' <<<"$cfg" 2>&1)"
    endpoint="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["api"]["environment"]["ZENY_STORAGE_PUBLIC_ENDPOINT"])' <<<"$cfg" 2>&1)"
    assert_eq "0.0.0.0" "$hostip" "host_ip do pacote é 0.0.0.0"
    assert_eq "http://localhost:8088" "$endpoint" "endpoint público padrão"
  else
    assert_contains "$cfg" '"host_ip": "0.0.0.0"' "host_ip do pacote é 0.0.0.0"
    assert_contains "$cfg" '"ZENY_STORAGE_PUBLIC_ENDPOINT": "http://localhost:8088"' "endpoint público padrão"
  fi
  rm -rf "$work"
fi

# --- save-images.sh ---
: >"$dlog"
out="$tmp/imagens.tar"
STUB_IMAGE_PRESENT=1 assert_exit 0 "save-images com imagens presentes" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=1 bash "$front/tools/server-env/save-images.sh" "$out"
log="$(cat "$dlog")"
assert_eq "1" "$(grep -c ' save -o ' "$dlog")" "um único docker save"
saveline="$(grep ' save -o ' "$dlog")"
assert_contains "$saveline" "save -o $out" "save grava em OUT"
for img in "zeny-hotel-manager:$tag" postgres:18 pgsty/minio:RELEASE.2026-08-04T00-00-00Z caddy:2.11-alpine; do
  assert_contains "$saveline" "$img" "save inclui $img"
done
assert_not_contains "$saveline" ":dev" "save sem :dev"
assert_eq "1" "$(grep -o 'pgsty/minio' <<<"$saveline" | wc -l)" "imagens duplicadas removidas"

: >"$dlog"
assert_exit 2 "save-images com imagem ausente sai com 2" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=0 bash "$front/tools/server-env/save-images.sh" "$out"
assert_contains "$ASSERT_OUT" "up.sh --build" "dica de up.sh --build"
assert_eq "0" "$(grep -c ' save ' "$dlog")" "nenhum save quando falta imagem"

finish
