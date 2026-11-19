#!/usr/bin/env bash
# Testes adversariais da M4 E05 (testador escopado): o pacote exige e leva docs/demo/roteiro-m4.md, byte a byte, sem
# confundi-lo com os roteiros anteriores, e os passos 0 e 1 do roteiro do M4 listam exatamente o que o package.sh
# gera. Fronts e servidores falsos; nenhum Gradle. O LEIA-ME e o README do pacote ficam com o
# test_package_roteiro_m3_ataque.sh, que confere o roteiro mais novo do pacote.
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
fake_m4() { printf '# Roteiro falso da M4\n\nTexto de teste.\n' >"$1"; }

# --- 1. roteiro-m4 ausente: falha clara, sem pacote pela metade, e só o M4 é culpado ---
f1="$tmp/f1"
new_front "$f1"
rm -f -- "$f1/docs/demo/roteiro-m4.md"
assert_exit 1 "sem roteiro-m4 e sem pacote anterior: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "o roteiro docs/demo/roteiro-m4.md é versionado no front" "sem roteiro-m4: a mensagem cita o arquivo"
assert_not_contains "$ASSERT_OUT" "docs/demo/roteiro-m3.md" "sem roteiro-m4: não culpa o M3"
assert_eq "0" "$(exists "$f1/build/demo-package")" "sem roteiro-m4: nenhum build/demo-package criado pela metade"
fake_m4 "$f1/docs/demo/roteiro-m4.md"
assert_exit 0 "pacote bom (f1)" -- pkg_sh "$f1"
assert_file "$f1/build/demo-package/roteiro-m4.md" "pacote bom: leva o roteiro-m4.md"
antes="$(tree_sum "$f1/build/demo-package")"
rm -f -- "$f1/docs/demo/roteiro-m4.md"
assert_exit 1 "roteiro-m4 sumiu depois de um pacote bom: sai com 1" -- pkg_sh "$f1"
assert_eq "$antes" "$(tree_sum "$f1/build/demo-package")" "roteiro-m4 ausente: o pacote anterior fica byte a byte intacto"
# Com o M3 ausente e o M4 presente, a culpa é do M3 (os quatro são exigidos, cada um com o seu nome).
fake_m4 "$f1/docs/demo/roteiro-m4.md"
rm -f -- "$f1/docs/demo/roteiro-m3.md"
assert_exit 1 "só roteiro-m3 ausente: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m3.md" "só roteiro-m3 ausente: cita o M3"
assert_not_contains "$ASSERT_OUT" "docs/demo/roteiro-m4.md" "só roteiro-m3 ausente: não culpa o M4"

# --- 2. formas hostis do roteiro-m4 (M1 a M3 válidos); a mensagem cita o M4 e o pacote anterior fica intacto ---
f2="$tmp/f2"
new_front "$f2"
assert_exit 0 "pacote anterior bom (f2)" -- pkg_sh "$f2"
antes2="$(tree_sum "$f2/build/demo-package")"
r4="$f2/docs/demo/roteiro-m4.md"
segredo="$tmp/segredo.env"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$segredo"
hostil() {
  assert_exit 1 "roteiro-m4 $1: package.sh sai com 1" -- pkg_sh "$f2"
  assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m4.md" "roteiro-m4 $1: cita o arquivo"
  assert_eq "$antes2" "$(tree_sum "$f2/build/demo-package")" "roteiro-m4 $1: pacote anterior intacto"
  assert_eq "0" "$(grep -rl 'nao-pode-vazar-xyz123' "$f2/build/demo-package" 2>/dev/null | wc -l | tr -d ' ')" "roteiro-m4 $1: segredo fora do pacote"
  chmod -R u+rwX "$r4" 2>/dev/null
  rm -rf -- "$r4"
  fake_m4 "$r4"
}
rm -f -- "$r4"; ln -s "$segredo" "$r4"; hostil "symlink para um .env"
rm -f -- "$r4"; ln -s "$f2/docs/demo/roteiro-m3.md" "$r4"; hostil "symlink para o roteiro-m3"
rm -f -- "$r4"; ln -s "$tmp/nao-existe.md" "$r4"; hostil "symlink quebrado"
rm -f -- "$r4"; : >"$r4"; hostil "vazio"
rm -f -- "$r4"; mkdir "$r4"; printf 'x\n' >"$r4/dentro.md"; hostil "diretório"
rm -f -- "$r4"; mkfifo "$r4"; hostil "FIFO"
if [[ "$(id -u)" == 0 ]]; then
  skip_case "roteiro-m4 ilegível: o usuário é root, chmod 000 não restringe"
else
  chmod 000 "$r4"; hostil "ilegível"
fi

# --- 3. cópia fiel, byte a byte, e os quatro roteiros distintos ---
f3="$tmp/f3"
new_front "$f3"
r4="$f3/docs/demo/roteiro-m4.md"
printf '# Roteiro do M4\r\n\r\nContas: caução, check-out, ✓\r\nsem quebra final' >"$r4"
assert_exit 0 "roteiro-m4 CRLF sem newline final: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r4" "$f3/build/demo-package/roteiro-m4.md")" "CRLF/UTF-8/sem newline final: cópia idêntica"
head -c 300000 /dev/urandom >"$r4"
printf '# t\n' | cat - "$r4" >"$r4.tmp" && mv -- "$r4.tmp" "$r4"
assert_exit 0 "roteiro-m4 com bytes arbitrários: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r4" "$f3/build/demo-package/roteiro-m4.md")" "bytes arbitrários: cópia idêntica"
for m in 1 2 3 4; do printf '# M%s distinto\n' "$m" >"$f3/docs/demo/roteiro-m$m.md"; done
assert_exit 0 "roteiros distintos: sai com 0" -- pkg_sh "$f3"
for m in 1 2 3 4; do
  assert_eq "# M$m distinto" "$(<"$f3/build/demo-package/roteiro-m$m.md")" "roteiro-m$m do pacote é o M$m (nenhum sobrescreve outro)"
done
printf '# M4 novo\n' >"$r4"
assert_exit 0 "segunda execução: sai com 0" -- pkg_sh "$f3"
assert_eq "# M4 novo" "$(<"$f3/build/demo-package/roteiro-m4.md")" "segunda execução: roteiro-m4 atualizado"

# --- 4. worktree x checkout principal ---
f4="$tmp/f4"
new_front "$f4"
printf '# M4 do principal\n' >"$f4/docs/demo/roteiro-m4.md"
wt="$tmp/f4-wt"
_fgit "$f4" worktree add -q "$wt" -b wt-m4 >/dev/null 2>&1
make_fake_jars "$wt"
printf '# M4 do worktree\n' >"$wt/docs/demo/roteiro-m4.md"
assert_exit 0 "worktree: package.sh sai com 0" -- pkg_sh "$wt"
assert_eq "# M4 do worktree" "$(<"$f4/build/demo-package/roteiro-m4.md")" "worktree: pacote leva o roteiro-m4 do worktree"
rm -f -- "$wt/docs/demo/roteiro-m4.md"
assert_exit 1 "worktree sem roteiro-m4 (o principal tem): sai com 1" -- pkg_sh "$wt"
assert_contains "$ASSERT_OUT" "$wt/docs/demo/roteiro-m4.md" "worktree sem roteiro-m4: cita o caminho do worktree"
assert_eq "# M4 do worktree" "$(<"$f4/build/demo-package/roteiro-m4.md")" "worktree sem roteiro-m4: não recorre ao do principal"

# --- 5. o pacote real: o roteiro-m4 real, byte a byte, e os passos 0 e 1 dele listam o que o package.sh gera ---
f5="$tmp/f5"
new_front "$f5"
for m in 1 2 3 4; do cp -- "$ROOT/docs/demo/roteiro-m$m.md" "$f5/docs/demo/roteiro-m$m.md"; done
cp -- "$ROOT/tools/server-env/LEIA-ME.md" "$f5/tools/server-env/LEIA-ME.md"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$f5/docs/demo/roteiro-m4.env"
assert_exit 0 "pacote com os documentos reais" -- pkg_sh "$f5"
pk5="$f5/build/demo-package"
assert_eq "1" "$(same_file "$ROOT/docs/demo/roteiro-m4.md" "$pk5/roteiro-m4.md")" "o roteiro-m4 real vai ao pacote byte a byte"
assert_eq "" "$(find "$pk5" -name '*.env' -print)" "o roteiro-m4.env solto em docs/demo não entra"
assert_eq "0" "$(grep -rl 'nao-pode-vazar-xyz123' "$pk5" 2>/dev/null | wc -l | tr -d ' ')" "pacote real: segredo fora do pacote"
real="$(cd "$pk5" && ls -A | sort)"
listed() { grep -F "$2" "$1" | sed "s/ $3.*//" | grep -oE '`[^`]+`' | tr -d '`' | grep -vxE 'demo-package|\.env' | sort -u; }
r="$ROOT/docs/demo/roteiro-m4.md"
assert_eq "$real" "$(listed "$r" 'Ela tem as pastas' 'Você não precisa')" "passo 0 do roteiro-m4 lista exatamente o que o package.sh gera"
assert_eq "$real" "$(listed "$r" 'a lista tem `app`' 'Não pode ter')" "passo 1 do roteiro-m4 lista exatamente o que o package.sh gera"
# O roteiro-m4.md real é versionado, regular, não vazio e começa por um título.
assert_eq "docs/demo/roteiro-m4.md" "$(git -C "$ROOT" ls-files -- docs/demo/roteiro-m4.md)" "o roteiro-m4.md real é versionado"
assert_eq "1" "$([[ -s "$ROOT/docs/demo/roteiro-m4.md" && ! -L "$ROOT/docs/demo/roteiro-m4.md" ]] && echo 1 || echo 0)" "o roteiro-m4.md real é arquivo regular não vazio"
assert_eq "# " "$(head -c 2 "$ROOT/docs/demo/roteiro-m4.md")" "o roteiro-m4.md real começa por um título"
# O package.sh exige o M4 antes de apagar o pacote anterior (como o do M3) e o copia com o mesmo nome.
pk_sh="$ROOT/tools/server-env/package.sh"
n_req="$(grep -n 'require_artifact "$roteiro_m4"' "$pk_sh" | head -1 | cut -d: -f1)"
n_rm="$(grep -nE '^[[:space:]]*rm -rf -- "\$pkg"' "$pk_sh" | head -1 | cut -d: -f1)"
((${n_req:-999999} < ${n_rm:-0})) || _assert_fail "package.sh exige o roteiro-m4 antes do rm -rf do pacote" "require: $n_req; rm: $n_rm"
assert_contains "$(cat "$pk_sh")" 'cp -- "$roteiro_m4" "$pkg/roteiro-m4.md"' "package.sh copia o roteiro-m4 com o mesmo nome"

finish
