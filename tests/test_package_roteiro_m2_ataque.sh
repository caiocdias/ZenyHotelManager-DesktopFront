#!/usr/bin/env bash
# Testes adversariais da M2 E06 Task 3: o pacote exige e leva docs/demo/roteiro-m2.md.
# Fronts e servidores falsos; nenhum Gradle.
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

# --- 1. roteiro-m2 ausente: falha clara, sem pacote pela metade ---
f1="$tmp/f1"
new_front "$f1"
rm -f -- "$f1/docs/demo/roteiro-m2.md"
assert_exit 1 "sem roteiro-m2 e sem pacote anterior: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m2.md" "sem roteiro-m2: a mensagem cita o arquivo"
assert_eq "0" "$(exists "$f1/build/demo-package")" "sem roteiro-m2: nenhum build/demo-package criado pela metade"

# Com pacote anterior bom, a falha o deixa intacto (e o roteiro-m2 antigo continua lá).
make_fake_jars "$f1"
assert_exit 0 "pacote bom (f1)" -- pkg_sh "$f1"
antes="$(tree_sum "$f1/build/demo-package")"
rm -f -- "$f1/docs/demo/roteiro-m2.md"
assert_exit 1 "roteiro-m2 sumiu depois de um pacote bom: sai com 1" -- pkg_sh "$f1"
assert_eq "$antes" "$(tree_sum "$f1/build/demo-package")" "roteiro-m2 ausente: o pacote anterior fica byte a byte intacto"

# Só o M2 ausente é culpa do M2: com o M1 ausente a mensagem cita o M1, e com os dois ausentes falha também.
make_fake_jars "$f1"
rm -f -- "$f1/docs/demo/roteiro-m1.md"
assert_exit 1 "só roteiro-m1 ausente: sai com 1" -- pkg_sh "$f1"
assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m1.md" "só roteiro-m1 ausente: cita o M1"
rm -f -- "$f1/docs/demo/roteiro-m2.md"
assert_exit 1 "os dois roteiros ausentes: sai com 1" -- pkg_sh "$f1"
make_fake_jars "$f1"

# --- 2. formas hostis do roteiro-m2 (o M1 está válido); a mensagem cita o M2 e o pacote anterior fica intacto ---
f2="$tmp/f2"
new_front "$f2"
assert_exit 0 "pacote anterior bom (f2)" -- pkg_sh "$f2"
antes2="$(tree_sum "$f2/build/demo-package")"
r2="$f2/docs/demo/roteiro-m2.md"
segredo="$tmp/segredo.env"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$segredo"
hostil() {
  assert_exit 1 "roteiro-m2 $1: package.sh sai com 1" -- pkg_sh "$f2"
  assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m2.md" "roteiro-m2 $1: cita o arquivo"
  assert_eq "$antes2" "$(tree_sum "$f2/build/demo-package")" "roteiro-m2 $1: pacote anterior intacto"
  assert_eq "0" "$(grep -rl 'nao-pode-vazar-xyz123' "$f2/build/demo-package" 2>/dev/null | wc -l | tr -d ' ')" "roteiro-m2 $1: segredo fora do pacote"
  rm -rf -- "$r2"
  printf '# Roteiro falso da M2\n\nTexto de teste.\n' >"$r2"
}
rm -f -- "$r2"; ln -s "$segredo" "$r2"; hostil "symlink para um .env"
rm -f -- "$r2"; ln -s "$f2/docs/demo/roteiro-m1.md" "$r2"; hostil "symlink para o roteiro-m1"
rm -f -- "$r2"; ln -s "$tmp/nao-existe.md" "$r2"; hostil "symlink quebrado"
rm -f -- "$r2"; : >"$r2"; hostil "vazio"
rm -f -- "$r2"; mkdir "$r2"; printf 'x\n' >"$r2/dentro.md"; hostil "diretório"
rm -f -- "$r2"; mkfifo "$r2"; hostil "FIFO"
if [[ "$(id -u)" == 0 ]]; then
  skip_case "roteiro-m2 ilegível: o usuário é root, chmod 000 não restringe"
else
  chmod 000 "$r2"; hostil "ilegível"
fi

# --- 3. cópia fiel, byte a byte ---
f3="$tmp/f3"
new_front "$f3"
r3="$f3/docs/demo/roteiro-m2.md"
# CRLF, UTF-8, sem quebra de linha final.
printf '# Roteiro do M2\r\n\r\nRecepção do dia: ação, coração, ✓\r\nsem quebra final' >"$r3"
assert_exit 0 "roteiro-m2 CRLF sem newline final: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r3" "$f3/build/demo-package/roteiro-m2.md")" "CRLF/UTF-8/sem newline final: cópia idêntica"
assert_eq "$(sha256sum <"$r3")" "$(sha256sum <"$f3/build/demo-package/roteiro-m2.md")" "CRLF: sha256 igual"
# Bytes quaisquer (NUL, 0xFF) e arquivo grande.
head -c 300000 /dev/urandom >"$r3"
printf '# t\n' | cat - "$r3" >"$r3.tmp" && mv -- "$r3.tmp" "$r3"
assert_exit 0 "roteiro-m2 com bytes arbitrários: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r3" "$f3/build/demo-package/roteiro-m2.md")" "bytes arbitrários: cópia idêntica"
printf '# so o titulo\n' >"$r3"
assert_exit 0 "roteiro-m2 só com título: sai com 0" -- pkg_sh "$f3"
assert_eq "1" "$(same_file "$r3" "$f3/build/demo-package/roteiro-m2.md")" "só título: cópia idêntica"
assert_eq "# so o titulo" "$(<"$f3/build/demo-package/roteiro-m2.md")" "só título: conteúdo lido de volta"

# roteiro-m1 e roteiro-m2 distintos no pacote: um não sobrescreve o outro.
printf '# M1 distinto\n' >"$f3/docs/demo/roteiro-m1.md"
printf '# M2 distinto\n' >"$r3"
assert_exit 0 "roteiros distintos: sai com 0" -- pkg_sh "$f3"
assert_eq "# M1 distinto" "$(<"$f3/build/demo-package/roteiro-m1.md")" "roteiro-m1 do pacote é o M1"
assert_eq "# M2 distinto" "$(<"$f3/build/demo-package/roteiro-m2.md")" "roteiro-m2 do pacote é o M2"
# Rodar de novo é idempotente e atualiza o roteiro-m2.
printf '# M2 novo\n' >"$r3"
assert_exit 0 "segunda execução: sai com 0" -- pkg_sh "$f3"
assert_eq "# M2 novo" "$(<"$f3/build/demo-package/roteiro-m2.md")" "segunda execução: roteiro-m2 atualizado"

# --- 4. worktree x checkout principal ---
f4="$tmp/f4"
new_front "$f4"
printf '# M2 do principal\n' >"$f4/docs/demo/roteiro-m2.md"
wt="$tmp/f4-wt"
_fgit "$f4" worktree add -q "$wt" -b wt-m2 >/dev/null 2>&1
make_fake_jars "$wt"
printf '# M2 do worktree\n' >"$wt/docs/demo/roteiro-m2.md"
assert_exit 0 "worktree: package.sh sai com 0" -- pkg_sh "$wt"
assert_eq "# M2 do worktree" "$(<"$f4/build/demo-package/roteiro-m2.md")" "worktree: pacote leva o roteiro-m2 do worktree"
assert_eq "0" "$(exists "$wt/build")" "worktree: nada de build/ no worktree"
rm -f -- "$wt/docs/demo/roteiro-m2.md"
assert_exit 1 "worktree sem roteiro-m2 (o principal tem): sai com 1" -- pkg_sh "$wt"
assert_contains "$ASSERT_OUT" "$wt/docs/demo/roteiro-m2.md" "worktree sem roteiro-m2: cita o caminho do worktree"
assert_eq "# M2 do worktree" "$(<"$f4/build/demo-package/roteiro-m2.md")" "worktree sem roteiro-m2: não recorre ao do principal nem apaga o pacote"

# --- 5. nenhum segredo nem .env entra por causa do roteiro-m2 ---
f5="$tmp/f5"
new_front "$f5"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$f5/docs/demo/.env"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$f5/docs/demo/roteiro-m2.env"
printf 'ZENY_SEGREDO=nao-pode-vazar-xyz123\n' >"$f5/.env"
assert_exit 0 "com .env soltos em docs/demo e na raiz: sai com 0" -- pkg_sh "$f5"
assert_eq "" "$(find "$f5/build/demo-package" \( -name '.env' -o -name '*.env' \) -print)" "nenhum .env no pacote"
assert_eq "0" "$(grep -rl 'nao-pode-vazar-xyz123' "$f5/build/demo-package" | wc -l | tr -d ' ')" "o segredo não aparece em nenhum arquivo do pacote"
assert_eq "1" "$(same_file "$f5/docs/demo/roteiro-m2.md" "$f5/build/demo-package/roteiro-m2.md")" "com .env soltos: roteiro-m2 ainda copiado"

# --- 6. conteúdo do pacote x o que a documentação lista ---
f6="$tmp/f6"
new_front "$f6"
assert_exit 0 "pacote para conferir listas" -- pkg_sh "$f6"
pk6="$f6/build/demo-package"
real="$(cd "$pk6" && ls -A | sort)"
rot="$ROOT/docs/demo/roteiro-m1.md"
fix() { grep -v '^$'; }
l0="$(grep -F 'Ela tem as pastas' "$rot" | sed 's/ Você não precisa.*//' | grep -oE '`[^`]+`' | tr -d '`' | grep -vx 'demo-package' | sort -u)"
l1="$(grep -F 'a lista tem `app`' "$rot" | sed 's/ Não pode ter.*//' | grep -oE '`[^`]+`' | tr -d '`' | grep -vx 'demo-package' | sort -u)"
assert_eq "$real" "$(printf '%s\n' "$l0" | grep -vxE '\.env' | fix)" "passo 0 do roteiro-m1 lista exatamente o que o package.sh gera"
assert_eq "$real" "$(printf '%s\n' "$l1" | grep -vxE '\.env' | fix)" "passo 1 do roteiro-m1 lista exatamente o que o package.sh gera"
# LEIA-ME do pacote: todo roteiro que cita existe no pacote, e cita o M2.
leia="$pk6/LEIA-ME.md"
assert_contains "$(<"$leia")" 'roteiro-m2.md' "LEIA-ME do pacote cita o roteiro-m2.md"
for n in $(grep -oE 'roteiro-m[0-9]+\.md' "$leia" | sort -u); do
  assert_eq "1" "$(exists "$pk6/$n")" "LEIA-ME cita $n e ele está no pacote"
done
# README e roteiro-m1 do repositório citam só roteiros que o pacote leva.
for n in $(grep -oE 'roteiro-m[0-9]+\.md' "$ROOT/README.md" "$rot" | cut -d: -f2 | sort -u); do
  assert_eq "1" "$(exists "$pk6/$n")" "README/roteiro-m1 citam $n e ele está no pacote"
done
# O roteiro-m2 real do repositório existe, é versionado, não vazio e começa por um título.
assert_eq "docs/demo/roteiro-m2.md" "$(git -C "$ROOT" ls-files -- docs/demo/roteiro-m2.md)" "o roteiro-m2.md real é versionado"
assert_eq "1" "$([[ -s "$ROOT/docs/demo/roteiro-m2.md" && ! -L "$ROOT/docs/demo/roteiro-m2.md" ]] && echo 1 || echo 0)" "o roteiro-m2.md real é arquivo regular não vazio"
assert_eq "# " "$(head -c 2 "$ROOT/docs/demo/roteiro-m2.md")" "o roteiro-m2.md real começa por um título"

finish
