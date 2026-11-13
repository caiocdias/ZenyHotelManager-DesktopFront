#!/usr/bin/env bash
# Testes adversariais da M1 E07 Task 1: package.sh com JARs, roteiro e VERSAO.txt, e o helper make_fake_jars.
# Fronts e servidores falsos; nenhum Gradle. Cada execução do package.sh tem timeout (um FIFO não pode travar).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwX "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT \
  COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES
C1="$FAKE_C1"

# new_front DIR : front falso com pin, JARs e roteiro válidos.
new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m pin
  make_fake_jars "$1"
}
# pkg_sh DIR : roda o package.sh de DIR com timeout.
pkg_sh() { timeout 60 bash "$1/tools/server-env/package.sh"; }
# jar DIR NOME : caminho do JAR NOME (zeny-desktop|demo-seed) no checkout DIR.
jar() {
  case "$2" in
    zeny-desktop) printf '%s/app/build/libs/zeny-desktop.jar' "$1" ;;
    demo-seed) printf '%s/demo-seed/build/libs/demo-seed.jar' "$1" ;;
  esac
}
# versao PKG KEY : valor de "KEY=" no VERSAO.txt do pacote PKG.
versao() { grep -m1 "^$2=" "$1/VERSAO.txt" | cut -d= -f2-; }
# tree_sum DIR : lista e sha256 de todos os arquivos de DIR (para provar que o pacote anterior ficou intacto).
tree_sum() { (cd "$1" && find . -print | sort && find . -type f -print0 | sort -z | xargs -0 sha256sum) 2>&1; }
same_file() { cmp -s -- "$1" "$2" && echo 1 || echo 0; }
sha() { sha256sum <"$1" | cut -d' ' -f1; }
exists() { [[ -e "$1" || -L "$1" ]] && echo 1 || echo 0; }
# commit_at DIR EPOCH PATH : cria PATH em DIR e o commita com data de commit EPOCH.
commit_at() {
  mkdir -p "$(dirname "$1/$3")"
  echo "conteúdo de $3" >"$1/$3"
  _fgit "$1" add -- "$3" >/dev/null
  GIT_COMMITTER_DATE="@$2" GIT_AUTHOR_DATE="@$2" _fgit "$1" commit -q -m "commit em $3"
}

T_JAN=1767225600 # 2026-01-01
T_MAR=1772323200 # 2026-03-01

# --- 1. ordem das defesas: caminho antes dos artefatos; artefatos antes do rm -rf ---
# 1a. build/demo-package symlink para fora e JAR ausente: a recusa é pela validação do caminho (exit 2)
fa="$tmp/fa"
new_front "$fa"
mkdir -p "$fa/build" "$tmp/alvo-a"
echo sentinela >"$tmp/alvo-a/sentinela.txt"
ln -s "$tmp/alvo-a" "$fa/build/demo-package"
rm -f -- "$(jar "$fa" zeny-desktop)"
assert_exit 2 "caminho inseguro e JAR ausente: sai com 2 (validação do caminho primeiro)" -- pkg_sh "$fa"
assert_contains "$ASSERT_OUT" "caminho do pacote inseguro" "caminho inseguro e JAR ausente: causa é o caminho"
assert_not_contains "$ASSERT_OUT" "artefato" "caminho inseguro e JAR ausente: não fala em artefato"
assert_file "$tmp/alvo-a/sentinela.txt" "caminho inseguro e JAR ausente: alvo do symlink intacto"

# 1b. build symlink para fora e roteiro ausente: idem
fb="$tmp/fb"
new_front "$fb"
mkdir -p "$tmp/alvo-b/demo-package"
echo sentinela >"$tmp/alvo-b/demo-package/sentinela.txt"
ln -s "$tmp/alvo-b" "$fb/build"
rm -f -- "$fb/docs/demo/roteiro-m1.md"
assert_exit 2 "build symlink e roteiro ausente: sai com 2" -- pkg_sh "$fb"
assert_contains "$ASSERT_OUT" "caminho do pacote inseguro" "build symlink e roteiro ausente: causa é o caminho"
assert_not_contains "$ASSERT_OUT" "roteiro" "build symlink e roteiro ausente: não fala do roteiro"
assert_file "$tmp/alvo-b/demo-package/sentinela.txt" "build symlink e roteiro ausente: demo-package de fora intacto"

# 1c. sem pacote anterior e com JAR ausente: nenhum pacote parcial é criado
fc="$tmp/fc"
new_front "$fc"
rm -f -- "$(jar "$fc" demo-seed)"
assert_exit 1 "primeira execução sem demo-seed.jar: sai com 1" -- pkg_sh "$fc"
assert_eq "0" "$(exists "$fc/build/demo-package")" "primeira execução sem demo-seed.jar: nenhum pacote parcial"

# 1d. pacote anterior inteiro (todos os arquivos e bytes) preservado em cada falha de artefato
fd="$tmp/fd"
new_front "$fd"
assert_exit 0 "pacote anterior (fd)" -- pkg_sh "$fd"
antes="$(tree_sum "$fd/build/demo-package")"
# hostil NOME ALVO TIPO DICA : torna ALVO hostil (fifo, ilegivel ou symlink quebrado), roda e restaura.
hostil() {
  local nome="$1" alvo="$2" tipo="$3" dica="$4"
  rm -rf -- "$alvo"
  case "$tipo" in
    fifo) mkfifo "$alvo" ;;
    ilegivel) cp "$tmp/valido.bin" "$alvo" && chmod 000 "$alvo" ;;
    quebrado) ln -s "$tmp/nao-existe" "$alvo" ;;
  esac
  assert_exit 1 "$nome $tipo: sai com 1 (sem travar)" -- pkg_sh "$fd"
  assert_contains "$ASSERT_OUT" "$dica" "$nome $tipo: mensagem cita $dica"
  assert_eq "$antes" "$(tree_sum "$fd/build/demo-package")" "$nome $tipo: pacote anterior intacto, byte a byte"
  chmod u+rw "$alvo" 2>/dev/null
  rm -rf -- "$alvo"
  make_fake_jars "$fd"
}
cp "$(jar "$fd" zeny-desktop)" "$tmp/valido.bin"
for tipo in fifo ilegivel quebrado; do
  hostil zeny-desktop.jar "$(jar "$fd" zeny-desktop)" "$tipo" "./gradlew :app:desktopJar"
  hostil demo-seed.jar "$(jar "$fd" demo-seed)" "$tipo" "./gradlew :demo-seed:seedJar"
done
for tipo in fifo quebrado; do
  hostil roteiro "$fd/docs/demo/roteiro-m1.md" "$tipo" "docs/demo/roteiro-m1.md"
done
assert_exit 0 "fd restaurado: package.sh volta a sair com 0" -- pkg_sh "$fd"

# --- 2. checkout e worktree com espaço e colchetes no caminho ---
fs="$tmp/front [x] com espaço"
new_front "$fs"
assert_exit 0 "checkout com espaço e colchetes: sai com 0" -- pkg_sh "$fs"
pk="$fs/build/demo-package"
assert_eq "1" "$(same_file "$(jar "$fs" zeny-desktop)" "$pk/app/zeny-desktop.jar")" "espaço: zeny-desktop.jar copiado"
assert_eq "1" "$(same_file "$(jar "$fs" demo-seed)" "$pk/app/demo-seed.jar")" "espaço: demo-seed.jar copiado"
assert_eq "1" "$(same_file "$fs/docs/demo/roteiro-m1.md" "$pk/roteiro-m1.md")" "espaço: roteiro copiado"
assert_eq "$(printf 'demo-seed.jar\nzeny-desktop.jar')" "$(ls -A "$pk/app")" "espaço: app/ só com os dois JARs"
assert_not_contains "$(cat "$pk/VERSAO.txt")" "/" "espaço: VERSAO.txt sem nenhuma barra (nenhum caminho)"
assert_eq "$(git -C "$fs" rev-parse HEAD)" "$(versao "$pk" front_commit)" "espaço: front_commit"

wts="$tmp/wt [y] com espaço"
_fgit "$fs" worktree add -q "$wts" -b wt-espaco >/dev/null 2>&1
make_fake_jars "$wts"
_fgit "$wts" commit -q --allow-empty -m "commit do worktree com espaço"
assert_exit 0 "worktree com espaço: sai com 0" -- pkg_sh "$wts"
assert_eq "1" "$(same_file "$(jar "$wts" zeny-desktop)" "$pk/app/zeny-desktop.jar")" "worktree com espaço: zeny-desktop.jar do worktree"
assert_eq "1" "$(same_file "$(jar "$wts" demo-seed)" "$pk/app/demo-seed.jar")" "worktree com espaço: demo-seed.jar do worktree"
assert_eq "$(git -C "$wts" rev-parse HEAD)" "$(versao "$pk" front_commit)" "worktree com espaço: front_commit do worktree"
assert_eq "0" "$(exists "$wts/build")" "worktree com espaço: sem build/ no worktree"

# --- 3. worktree sem JARs e principal com JARs: falha citando o worktree, sem recorrer ao principal ---
fm="$tmp/fm"
new_front "$fm"
assert_exit 0 "pacote anterior (fm)" -- pkg_sh "$fm"
antes_fm="$(tree_sum "$fm/build/demo-package")"
_fgit "$fm" worktree add -q "$tmp/fm-wt" -b wt-sem-jar >/dev/null 2>&1
mkdir -p "$tmp/fm-wt/docs/demo"
cp "$fm/docs/demo/roteiro-m1.md" "$tmp/fm-wt/docs/demo/roteiro-m1.md"
assert_exit 1 "worktree sem JARs: sai com 1" -- pkg_sh "$tmp/fm-wt"
assert_contains "$ASSERT_OUT" "$tmp/fm-wt/app/build/libs/zeny-desktop.jar" "worktree sem JARs: cita o JAR do worktree"
assert_contains "$ASSERT_OUT" "./gradlew :app:desktopJar" "worktree sem JARs: cita o comando do Gradle"
assert_eq "$antes_fm" "$(tree_sum "$fm/build/demo-package")" "worktree sem JARs: pacote do principal intacto"

# --- 4. VERSAO.txt ---
# 4a. formato exato, linha a linha, com \n final e sem \r
fe="$tmp/fe"
new_front "$fe"
assert_exit 0 "VERSAO.txt (fe): sai com 0" -- pkg_sh "$fe"
pk="$fe/build/demo-package"
esperado="$(printf '%s\n' \
  "front_commit=$(git -C "$fe" rev-parse HEAD)" \
  "front_dirty=false" \
  "server_commit=$C1" \
  "zeny-desktop.jar sha256=$(sha "$(jar "$fe" zeny-desktop)")" \
  "demo-seed.jar sha256=$(sha "$(jar "$fe" demo-seed)")")"
assert_eq "$esperado" "$(cat "$pk/VERSAO.txt")" "VERSAO.txt: as cinco linhas, nesta ordem e neste formato"
assert_eq "5" "$(wc -l <"$pk/VERSAO.txt" | tr -d ' ')" "VERSAO.txt: exatamente 5 linhas terminadas em \\n"
assert_eq "0" "$(grep -c $'\r' "$pk/VERSAO.txt")" "VERSAO.txt: sem \\r"
assert_eq "1" "$(grep -cxE 'front_commit=[0-9a-f]{40}' "$pk/VERSAO.txt")" "VERSAO.txt: front_commit com sha de 40 hex"
assert_eq "2" "$(grep -cxE '(zeny-desktop|demo-seed)\.jar sha256=[0-9a-f]{64}' "$pk/VERSAO.txt")" "VERSAO.txt: sha256 com 64 hex"

# 4b. front_dirty: só não rastreados → false; mudança só no index → true; arquivo novo só no index → true;
# arquivo versionado apagado → true
commit_at "$fe" "$T_JAN" notas.txt
echo novo >"$fe/nao-rastreado.txt"
assert_exit 0 "só não rastreados: sai com 0" -- pkg_sh "$fe"
assert_eq "false" "$(versao "$pk" front_dirty)" "front_dirty=false com só arquivos não rastreados"
rm -f "$fe/nao-rastreado.txt"

echo mudou >>"$fe/notas.txt"
_fgit "$fe" add notas.txt >/dev/null
assert_eq "" "$(git -C "$fe" diff --name-only)" "pré-condição: árvore igual ao index"
assert_exit 0 "mudança só no index: sai com 0" -- pkg_sh "$fe"
assert_eq "true" "$(versao "$pk" front_dirty)" "front_dirty=true com mudança só no index"
_fgit "$fe" reset -q --hard

echo novo >"$fe/novo-no-index.txt"
_fgit "$fe" add novo-no-index.txt >/dev/null
assert_exit 0 "arquivo novo só no index: sai com 0" -- pkg_sh "$fe"
assert_eq "true" "$(versao "$pk" front_dirty)" "front_dirty=true com arquivo novo só no index"
_fgit "$fe" reset -q --hard
rm -f "$fe/novo-no-index.txt"

rm -f "$fe/notas.txt"
assert_exit 0 "arquivo versionado apagado: sai com 0" -- pkg_sh "$fe"
assert_eq "true" "$(versao "$pk" front_dirty)" "front_dirty=true com arquivo versionado apagado"
_fgit "$fe" checkout -q -- notas.txt

make_fake_jars "$fe"
assert_exit 0 "JARs regerados (caminhos excluídos): sai com 0" -- pkg_sh "$fe"
assert_eq "false" "$(versao "$pk" front_dirty)" "front_dirty=false quando só os JARs (excluídos) mudam"

# 4c. o sha256 é do JAR copiado: o original muda logo depois do cp (cp do PATH trocado por um que copia e altera a origem)
fr="$tmp/fr"
new_front "$fr"
realcp="$(command -v cp)"
mkdir -p "$tmp/cpstub"
{
  printf '#!/usr/bin/env bash\n'
  printf '%q "$@" || exit $?\n' "$realcp"
  cat <<'STUB'
for a in "$@"; do
  case "$a" in */build/libs/*.jar) printf 'ALTERADO-DEPOIS-DA-COPIA' >>"$a" ;; esac
done
STUB
} >"$tmp/cpstub/cp"
chmod +x "$tmp/cpstub/cp"
cp "$(jar "$fr" zeny-desktop)" "$tmp/fr-app-antes.jar"
cp "$(jar "$fr" demo-seed)" "$tmp/fr-seed-antes.jar"
assert_exit 0 "origem muda depois do cp: sai com 0" -- env PATH="$tmp/cpstub:$PATH" timeout 60 bash "$fr/tools/server-env/package.sh"
pk="$fr/build/demo-package"
assert_eq "0" "$(same_file "$tmp/fr-app-antes.jar" "$(jar "$fr" zeny-desktop)")" "pré-condição: a origem do zeny-desktop.jar mudou"
assert_eq "1" "$(same_file "$tmp/fr-app-antes.jar" "$pk/app/zeny-desktop.jar")" "o pacote tem o zeny-desktop.jar de antes da mudança"
assert_eq "$(sha "$pk/app/zeny-desktop.jar")" "$(versao "$pk" 'zeny-desktop.jar sha256')" "sha256 do zeny-desktop.jar é o da cópia no pacote"
assert_eq "$(sha "$pk/app/demo-seed.jar")" "$(versao "$pk" 'demo-seed.jar sha256')" "sha256 do demo-seed.jar é o da cópia no pacote"
assert_eq "1" "$(same_file "$tmp/fr-seed-antes.jar" "$pk/app/demo-seed.jar")" "o pacote tem o demo-seed.jar de antes da mudança"

# 4d. de um worktree: estado sujo e pin são os do worktree, não os do principal
fw="$tmp/fw"
new_front "$fw"
commit_at "$fw" "$T_JAN" notas.txt
_fgit "$fw" worktree add -q "$tmp/fw-wt" -b wt-versao >/dev/null 2>&1
wt="$tmp/fw-wt"
make_fake_jars "$wt"
pk="$fw/build/demo-package"
echo sujo >>"$fw/notas.txt"
assert_exit 0 "principal sujo, worktree limpo: sai com 0" -- pkg_sh "$wt"
assert_eq "false" "$(versao "$pk" front_dirty)" "worktree limpo e principal sujo: front_dirty=false"
_fgit "$fw" checkout -q -- notas.txt
echo sujo >>"$wt/notas.txt"
assert_exit 0 "worktree sujo, principal limpo: sai com 0" -- pkg_sh "$wt"
assert_eq "true" "$(versao "$pk" front_dirty)" "worktree sujo e principal limpo: front_dirty=true"
_fgit "$wt" checkout -q -- notas.txt
write_fake_pin "$wt" "$srv" "$FAKE_C2"
_fgit "$wt" add api >/dev/null
_fgit "$wt" commit -q -m "pin C2 no worktree"
assert_exit 0 "worktree com outro pin: sai com 0" -- pkg_sh "$wt"
assert_eq "$FAKE_C2" "$(versao "$pk" server_commit)" "worktree com pin C2: server_commit é o pin do worktree"
assert_eq "$(git -C "$wt" rev-parse HEAD)" "$(versao "$pk" front_commit)" "worktree com pin C2: front_commit do worktree"
assert_not_contains "$(cat "$pk/VERSAO.txt")" "/" "worktree: VERSAO.txt sem caminho"

# --- 5. JAR com mtime antigo, futuro ou igual ao último commit: nenhum aviso, JAR copiado como está ---
# O aviso de JAR velho por data saiu do package.sh (plano a795595, código 3b1ee93): um commit depois do build,
# um rebase ou um merge deixavam o JAR certo "mais velho" que o commit. O frescor fica com o Gradle e com o
# sha256 do VERSAO.txt comparado ao clone limpo (Task 4). Em nenhuma destas situações o package.sh avisa.
# sem_aviso DESCRIÇÃO FRONT [OPÇÕES DO BASH...] : sai com 0, stderr vazio (sem "aviso:"), stdout sem aviso,
# e os dois JARs do pacote iguais aos do checkout.
sem_aviso() {
  local desc="$1" f="$2" err out n
  shift 2
  err="$(timeout 60 bash "$@" "$f/tools/server-env/package.sh" 2>&1 >"$tmp/sem-aviso.out")"
  assert_eq "0" "$?" "$desc: sai com 0"
  out="$(cat "$tmp/sem-aviso.out")"
  assert_not_contains "$err" "aviso:" "$desc: sem \"aviso:\" no stderr"
  assert_eq "" "$err" "$desc: stderr vazio"
  assert_not_contains "$out" "aviso" "$desc: sem aviso no stdout"
  for n in zeny-desktop demo-seed; do
    assert_eq "1" "$(same_file "$(jar "$f" "$n")" "$f/build/demo-package/app/$n.jar")" "$desc: $n.jar copiado como está"
  done
}
# stale_case DESCRIÇÃO CAMINHO : commit em CAMINHO (2026-03-01) com os JARs de 2026-01-01 (mais antigos).
stale_case() {
  local f="$tmp/stale-$RANDOM$RANDOM"
  new_front "$f"
  commit_at "$f" "$T_MAR" "$2"
  touch -d "@$T_JAN" "$(jar "$f" zeny-desktop)" "$(jar "$f" demo-seed)"
  sem_aviso "$1" "$f"
}
stale_case "commit só em docs/, JARs mais antigos" docs/notas.md
stale_case "commit só em tools/, JARs mais antigos" tools/extra.txt
stale_case "commit em apps/ (parece app/), JARs mais antigos" apps/x.txt
stale_case "commit em application.md (parece app), JARs mais antigos" application.md
stale_case "commit em core-notas.md (parece core), JARs mais antigos" core-notas.md
stale_case "commit em app/src, JARs mais antigos" app/src/Main.kt
stale_case "commit em design/, JARs mais antigos" design/src/Tema.kt
stale_case "commit em demo-seed/src, JARs mais antigos" demo-seed/src/Seed.kt
stale_case "commit em gradle/libs.versions.toml, JARs mais antigos" gradle/libs.versions.toml
stale_case "commit em settings.gradle.kts na raiz, JARs mais antigos" settings.gradle.kts

# sem commit no código e JARs de 2001, com bash -u
fn="$tmp/fn"
new_front "$fn"
commit_at "$fn" "$T_MAR" docs/so-docs.md
touch -d @1000000000 "$(jar "$fn" zeny-desktop)" "$(jar "$fn" demo-seed)"
sem_aviso "sem commit no código e JARs de 2001 (bash -u)" "$fn" -u

# commit em app/src com mtime no futuro, igual ao commit e 1 s mais antigo que ele
ff="$tmp/ff"
new_front "$ff"
commit_at "$ff" "$T_MAR" app/src/Main.kt
touch -d @4102444800 "$(jar "$ff" zeny-desktop)" "$(jar "$ff" demo-seed)" # 2100-01-01
sem_aviso "JARs com mtime no futuro" "$ff"
touch -d "@$T_MAR" "$(jar "$ff" zeny-desktop)" "$(jar "$ff" demo-seed)"
sem_aviso "JARs com mtime igual ao commit" "$ff"
touch -d "@$((T_MAR - 1))" "$(jar "$ff" zeny-desktop)"
sem_aviso "zeny-desktop.jar 1 s mais antigo que o commit" "$ff"
sem_aviso "JAR 1 s mais antigo que o commit, com bash -u" "$ff" -u

# --- 6. segredos de um worktree não entram no pacote ---
# O valor falso vem de variável: nenhum arquivo versionado atribui literal a ZENY_SEED_*PASSWORD (test_smoke_task.sh).
segredo_seed=senha-seed-teste
fx="$tmp/fx"
new_front "$fx"
_fgit "$fx" worktree add -q "$tmp/fx-wt" -b wt-segredo >/dev/null 2>&1
wx="$tmp/fx-wt"
make_fake_jars "$wx"
mkdir -p "$wx/.server"
write_test_env "$wx/.server/seed-secrets.env" ZENY_SEED_ADMIN_PASSWORD="$segredo_seed"
write_test_env "$wx/.env" ZENY_SEED_ADMIN_PASSWORD="$segredo_seed"
write_test_env "$wx/docs/demo/seed-secrets.env" ZENY_SEED_ADMIN_PASSWORD="$segredo_seed"
write_test_env "$wx/app/build/libs/.env"
write_test_env "$wx/demo-seed/build/libs/seed-secrets.env" ZENY_SEED_ADMIN_PASSWORD="$segredo_seed"
mkdir -p "$fx/.server"
write_test_env "$fx/.server/seed-secrets.env" ZENY_SEED_ADMIN_PASSWORD="$segredo_seed"
assert_exit 0 "worktree com segredos ao lado dos artefatos: sai com 0" -- pkg_sh "$wx"
pk="$fx/build/demo-package"
assert_eq "" "$(find "$pk" \( -name '.env' -o -name '*.env' -o -name '.git' -o -name '.git*' \) -print)" "worktree: pacote sem .env, *.env nem .git*"
assert_eq "" "$(grep -rl -e 'senha-seed-teste' -e 'senha-db-teste' -e 'chave-jwt-fake' "$pk" 2>/dev/null)" "worktree: nenhum valor de segredo no pacote"
saida="$(pkg_sh "$wx" 2>&1)"
assert_not_contains "$saida" "senha-seed-teste" "worktree: nenhum segredo do seed na saída do package.sh"
assert_not_contains "$saida" "senha-db-teste" "worktree: nenhum segredo do banco na saída do package.sh"

# --- 7. make_fake_jars ---
fj="$tmp/fj"
make_fake_front "$fj"
assert_eq "" "$(_fgit "$fj" status --porcelain)" "pré-condição: front falso limpo"
make_fake_jars "$fj"
assert_eq "" "$(_fgit "$fj" status --porcelain --untracked-files=all)" "make_fake_jars: git status vazio (inclusive não rastreados)"
assert_eq "" "$(_fgit "$fj" ls-files --others --exclude-standard)" "make_fake_jars: nenhum arquivo não rastreado visível"
assert_eq "" "$(find "$fj" -name .gitignore -not -path '*/.git/*' -not -path "$fj/build/*")" "make_fake_jars: nenhum .gitignore criado"
for j in "$(jar "$fj" zeny-desktop)" "$(jar "$fj" demo-seed)"; do
  assert_exit 0 "make_fake_jars: unzip -t aceita ${j##*/}" -- unzip -tq "$j"
  assert_exit 0 "make_fake_jars: zipfile aceita ${j##*/}" -- python3 -I -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None
    assert "META-INF/MANIFEST.MF" in z.namelist()
' "$j"
done
assert_eq "0" "$(same_file "$(jar "$fj" zeny-desktop)" "$(jar "$fj" demo-seed)")" "make_fake_jars: os dois JARs diferem"
assert_file "$fj/docs/demo/roteiro-m1.md" "make_fake_jars: roteiro criado"

# idempotente
make_fake_jars "$fj"
for p in /app/build/ /demo-seed/build/ /docs/demo/; do
  assert_eq "1" "$(grep -cxF -- "$p" "$fj/.git/info/exclude")" "make_fake_jars duas vezes: $p uma só vez no exclude"
done
assert_eq "" "$(_fgit "$fj" status --porcelain --untracked-files=all)" "make_fake_jars duas vezes: git status vazio"
assert_exit 0 "make_fake_jars duas vezes: unzip -t aceita o JAR" -- unzip -tq "$(jar "$fj" zeny-desktop)"

# no worktree: o exclude é o do common-dir e vale no worktree
_fgit "$fj" worktree add -q "$tmp/fj-wt" -b wt-jars >/dev/null 2>&1
wj="$tmp/fj-wt"
make_fake_jars "$wj"
assert_eq "" "$(_fgit "$wj" status --porcelain --untracked-files=all)" "make_fake_jars no worktree: git status do worktree vazio"
assert_eq "" "$(_fgit "$fj" status --porcelain --untracked-files=all)" "make_fake_jars no worktree: git status do principal vazio"
wgit="$(git -C "$wj" rev-parse --path-format=absolute --git-dir)"
assert_eq "0" "$(exists "$wgit/info/exclude")" "make_fake_jars no worktree: não grava exclude privado do worktree"
for p in /app/build/ /demo-seed/build/ /docs/demo/; do
  assert_eq "1" "$(grep -cxF -- "$p" "$fj/.git/info/exclude")" "make_fake_jars no worktree: $p uma só vez no exclude do common-dir"
done
assert_eq "0" "$(same_file "$(jar "$fj" zeny-desktop)" "$(jar "$wj" zeny-desktop)")" "make_fake_jars: JAR do worktree difere do principal"

finish
