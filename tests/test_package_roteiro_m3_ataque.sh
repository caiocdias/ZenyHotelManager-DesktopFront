#!/usr/bin/env bash
# Testes adversariais da M3 E05 (testador de etapa): o pacote exige e leva docs/demo/roteiro-m3.md, e o LEIA-ME
# do pacote cita só roteiros presentes, com o do M3 como o atual. Fronts e servidores falsos; nenhum Gradle.
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

new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$FAKE_C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m pin
  make_fake_jars "$1"
}
pkg_sh() { timeout 60 bash "$1/tools/server-env/package.sh"; }
same_file() { cmp -s -- "$1" "$2" && echo 1 || echo 0; }
exists() { [[ -e "$1" || -L "$1" ]] && echo 1 || echo 0; }
tree_sum() { (cd "$1" && find . -print | sort && find . -type f -print0 | sort -z | xargs -0 sha256sum) 2>&1; }
fake_m3() { printf '# Roteiro falso da M3\n\nTexto de teste.\n' >"$1"; }

# --- 1. roteiro-m3 ausente: falha clara, sem pacote pela metade, e só o M3 é culpado ---
f1="$tmp/f1"
new_front "$f1"
rm -f -- "$f1/docs/demo/roteiro-m3.md"
assert_exit 1 "sem roteiro-m3 e sem pacote anterior: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "o roteiro docs/demo/roteiro-m3.md é versionado no front" "sem roteiro-m3: a mensagem cita o arquivo"
assert_not_contains "$ASSERT_OUT" "docs/demo/roteiro-m2.md" "sem roteiro-m3: não culpa o M2"
assert_eq "0" "$(exists "$f1/build/demo-package")" "sem roteiro-m3: nenhum build/demo-package criado pela metade"
fake_m3 "$f1/docs/demo/roteiro-m3.md"
assert_exit 0 "pacote bom (f1)" -- pkg_sh "$f1"
antes="$(tree_sum "$f1/build/demo-package")"
rm -f -- "$f1/docs/demo/roteiro-m3.md"
assert_exit 1 "roteiro-m3 sumiu depois de um pacote bom: sai com 1" -- pkg_sh "$f1"
assert_eq "$antes" "$(tree_sum "$f1/build/demo-package")" "roteiro-m3 ausente: o pacote anterior fica byte a byte intacto"
# Com o M2 ausente e o M3 presente, a culpa é do M2 (os três são exigidos, cada um com o seu nome).
fake_m3 "$f1/docs/demo/roteiro-m3.md"
rm -f -- "$f1/docs/demo/roteiro-m2.md"
assert_exit 1 "só roteiro-m2 ausente: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m2.md" "só roteiro-m2 ausente: cita o M2"
assert_not_contains "$ASSERT_OUT" "docs/demo/roteiro-m3.md" "só roteiro-m2 ausente: não culpa o M3"

# --- 2. formas hostis do roteiro-m3 (M1 e M2 válidos); a mensagem cita o M3 e o pacote anterior fica intacto ---
f2="$tmp/f2"
new_front "$f2"
assert_exit 0 "pacote anterior bom (f2)" -- pkg_sh "$f2"
antes2="$(tree_sum "$f2/build/demo-package")"
r3="$f2/docs/demo/roteiro-m3.md"
segredo="$tmp/segredo.env"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$segredo"
hostil() {
  assert_exit 1 "roteiro-m3 $1: package.sh sai com 1" -- pkg_sh "$f2"
  assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m3.md" "roteiro-m3 $1: cita o arquivo"
  assert_eq "$antes2" "$(tree_sum "$f2/build/demo-package")" "roteiro-m3 $1: pacote anterior intacto"
  assert_eq "0" "$(grep -rl 'nao-pode-vazar-xyz123' "$f2/build/demo-package" 2>/dev/null | wc -l | tr -d ' ')" "roteiro-m3 $1: segredo fora do pacote"
  chmod -R u+rwX "$r3" 2>/dev/null
  rm -rf -- "$r3"
  fake_m3 "$r3"
}
rm -f -- "$r3"; ln -s "$segredo" "$r3"; hostil "symlink para um .env"
rm -f -- "$r3"; ln -s "$f2/docs/demo/roteiro-m2.md" "$r3"; hostil "symlink para o roteiro-m2"
rm -f -- "$r3"; ln -s "$tmp/nao-existe.md" "$r3"; hostil "symlink quebrado"
rm -f -- "$r3"; : >"$r3"; hostil "vazio"
rm -f -- "$r3"; mkdir "$r3"; printf 'x\n' >"$r3/dentro.md"; hostil "diretório"
rm -f -- "$r3"; mkfifo "$r3"; hostil "FIFO"
if [[ "$(id -u)" == 0 ]]; then
  skip_case "roteiro-m3 ilegível: o usuário é root, chmod 000 não restringe"
else
  chmod 000 "$r3"; hostil "ilegível"
fi

# --- 3. cópia fiel, byte a byte, e os três roteiros distintos ---
f3="$tmp/f3"
new_front "$f3"
r3="$f3/docs/demo/roteiro-m3.md"
printf '# Roteiro do M3\r\n\r\nReservas: ação, cotação, ✓\r\nsem quebra final' >"$r3"
assert_exit 0 "roteiro-m3 CRLF sem newline final: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r3" "$f3/build/demo-package/roteiro-m3.md")" "CRLF/UTF-8/sem newline final: cópia idêntica"
head -c 300000 /dev/urandom >"$r3"
printf '# t\n' | cat - "$r3" >"$r3.tmp" && mv -- "$r3.tmp" "$r3"
assert_exit 0 "roteiro-m3 com bytes arbitrários: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r3" "$f3/build/demo-package/roteiro-m3.md")" "bytes arbitrários: cópia idêntica"
printf '# M1 distinto\n' >"$f3/docs/demo/roteiro-m1.md"
printf '# M2 distinto\n' >"$f3/docs/demo/roteiro-m2.md"
printf '# M3 distinto\n' >"$r3"
assert_exit 0 "roteiros distintos: sai com 0" -- pkg_sh "$f3"
for m in 1 2 3; do
  assert_eq "# M$m distinto" "$(<"$f3/build/demo-package/roteiro-m$m.md")" "roteiro-m$m do pacote é o M$m (nenhum sobrescreve outro)"
done
printf '# M3 novo\n' >"$r3"
assert_exit 0 "segunda execução: sai com 0" -- pkg_sh "$f3"
assert_eq "# M3 novo" "$(<"$f3/build/demo-package/roteiro-m3.md")" "segunda execução: roteiro-m3 atualizado"

# --- 4. worktree x checkout principal ---
f4="$tmp/f4"
new_front "$f4"
printf '# M3 do principal\n' >"$f4/docs/demo/roteiro-m3.md"
wt="$tmp/f4-wt"
_fgit "$f4" worktree add -q "$wt" -b wt-m3 >/dev/null 2>&1
make_fake_jars "$wt"
printf '# M3 do worktree\n' >"$wt/docs/demo/roteiro-m3.md"
assert_exit 0 "worktree: package.sh sai com 0" -- pkg_sh "$wt"
assert_eq "# M3 do worktree" "$(<"$f4/build/demo-package/roteiro-m3.md")" "worktree: pacote leva o roteiro-m3 do worktree"
rm -f -- "$wt/docs/demo/roteiro-m3.md"
assert_exit 1 "worktree sem roteiro-m3 (o principal tem): sai com 1" -- pkg_sh "$wt"
assert_contains "$ASSERT_OUT" "$wt/docs/demo/roteiro-m3.md" "worktree sem roteiro-m3: cita o caminho do worktree"
assert_eq "# M3 do worktree" "$(<"$f4/build/demo-package/roteiro-m3.md")" "worktree sem roteiro-m3: não recorre ao do principal"

# --- 5. o pacote real: o conteúdo é o que os três roteiros listam, e o LEIA-ME cita só roteiros presentes ---
f5="$tmp/f5"
new_front "$f5"
for m in 1 2 3; do cp -- "$ROOT/docs/demo/roteiro-m$m.md" "$f5/docs/demo/roteiro-m$m.md"; done
cp -- "$ROOT/tools/server-env/LEIA-ME.md" "$f5/tools/server-env/LEIA-ME.md"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$f5/docs/demo/roteiro-m3.env"
assert_exit 0 "pacote com os documentos reais" -- pkg_sh "$f5"
pk5="$f5/build/demo-package"
assert_eq "1" "$(same_file "$ROOT/docs/demo/roteiro-m3.md" "$pk5/roteiro-m3.md")" "o roteiro-m3 real vai ao pacote byte a byte"
assert_eq "" "$(find "$pk5" -name '*.env' -print)" "o roteiro-m3.env solto em docs/demo não entra"
real="$(cd "$pk5" && ls -A | sort)"
listed() { grep -F "$2" "$1" | sed "s/ $3.*//" | grep -oE '`[^`]+`' | tr -d '`' | grep -vxE 'demo-package|\.env' | sort -u; }
for m in 1 2 3; do
  r="$ROOT/docs/demo/roteiro-m$m.md"
  assert_eq "$real" "$(listed "$r" 'Ela tem as pastas' 'Você não precisa')" "passo 0 do roteiro-m$m lista exatamente o que o package.sh gera"
  assert_eq "$real" "$(listed "$r" 'a lista tem `app`' 'Não pode ter')" "passo 1 do roteiro-m$m lista exatamente o que o package.sh gera"
done
# leia_problems LEIA PACOTE : uma linha por problema do LEIA-ME do pacote (vazio se nenhum).
leia_problems() {
  local leia="$1" pk="$2" n latest top
  [[ -s "$leia" ]] || { echo "LEIA-ME ausente ou vazio"; return; }
  for n in $(grep -oE 'roteiro-m[0-9]+\.md' "$leia" | sort -u); do
    [[ -f "$pk/$n" ]] || echo "cita $n, que não está no pacote"
  done
  latest="$(cd "$pk" && ls roteiro-m*.md 2>/dev/null | sort -V | tail -1)"
  top="$(awk '/^## /{exit} {print}' "$leia")"
  [[ "$top" == *"siga \`$latest\` do início ao fim"* ]] || echo "o topo não manda seguir o roteiro mais novo do pacote ($latest)"
  for n in $(cd "$pk" && ls roteiro-m*.md 2>/dev/null); do
    grep -qF "\`$n\`" "$leia" || echo "não cita $n, que está no pacote"
  done
}
assert_eq "" "$(leia_problems "$pk5/LEIA-ME.md" "$pk5")" "LEIA-ME do pacote: cita só os roteiros presentes, todos eles, e manda seguir o do M3"
# A conferência reprova um LEIA-ME que cita roteiro ausente, que esquece um presente ou que aponta um roteiro velho.
# Desde a M4 E05 o pacote leva o roteiro-m4.md: o roteiro ausente do mutante passa a ser o roteiro-m5.md.
sed 's/`roteiro-m1\.md`\./`roteiro-m1.md` e `roteiro-m5.md`./' "$pk5/LEIA-ME.md" >"$tmp/leia-m5.md"
assert_contains "$(leia_problems "$tmp/leia-m5.md" "$pk5")" "cita roteiro-m5.md, que não está no pacote" "mutante: o LEIA-ME cita um roteiro ausente"
sed 's/`roteiro-m2\.md` e `roteiro-m1\.md`/`roteiro-m1.md`/' "$pk5/LEIA-ME.md" >"$tmp/leia-sem-m2.md"
assert_contains "$(leia_problems "$tmp/leia-sem-m2.md" "$pk5")" "não cita roteiro-m2.md" "mutante: o LEIA-ME esquece um roteiro do pacote"
sed 's/siga `roteiro-m3\.md` do início ao fim/siga `roteiro-m2.md` do início ao fim/' "$pk5/LEIA-ME.md" >"$tmp/leia-velho.md"
assert_contains "$(leia_problems "$tmp/leia-velho.md" "$pk5")" "o topo não manda seguir o roteiro mais novo" "mutante: o topo aponta o roteiro do M2"
for m in leia-m5 leia-sem-m2 leia-velho; do
  cmp -s "$pk5/LEIA-ME.md" "$tmp/$m.md" && _assert_fail "mutante $m difere do LEIA-ME (a expressão não casou)"
done
# README do repositório: cita só roteiros que o pacote leva e aponta o do M3 como o gate atual.
for n in $(grep -oE 'roteiro-m[0-9]+\.md' "$ROOT/README.md" | sort -u); do
  assert_eq "1" "$(exists "$pk5/$n")" "README cita $n e ele está no pacote"
done
assert_eq "1" "$(grep -cF '(gate atual)' "$ROOT/README.md")" "README: um só roteiro marcado como gate atual"
# Desde a M4 E05, o gate atual é o roteiro mais novo do pacote (era o do M3, o mais novo quando a trava nasceu).
latest_pkg="$(cd "$pk5" && ls roteiro-m*.md 2>/dev/null | sort -V | tail -1)"
assert_contains "$(grep -F '(gate atual)' "$ROOT/README.md")" "$latest_pkg" "README: o gate atual é o roteiro mais novo do pacote ($latest_pkg)"
# O roteiro-m3.md real é versionado, regular, não vazio e começa por um título.
assert_eq "docs/demo/roteiro-m3.md" "$(git -C "$ROOT" ls-files -- docs/demo/roteiro-m3.md)" "o roteiro-m3.md real é versionado"
assert_eq "1" "$([[ -s "$ROOT/docs/demo/roteiro-m3.md" && ! -L "$ROOT/docs/demo/roteiro-m3.md" ]] && echo 1 || echo 0)" "o roteiro-m3.md real é arquivo regular não vazio"
assert_eq "# " "$(head -c 2 "$ROOT/docs/demo/roteiro-m3.md")" "o roteiro-m3.md real começa por um título"
assert_eq "0" "$(grep -c 'Em escrita\.' "$ROOT/docs/demo/roteiro-m3.md")" "o roteiro-m3.md real não é mais o provisório da Task 4"

finish
