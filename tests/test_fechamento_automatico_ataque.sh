#!/usr/bin/env bash
# Ataque ao aviso do fechamento automático das 03:00 (M0 E03, Task 3): posição fixada, unicidade por documento
# e chegada ao pacote (package.sh com Docker, servidor e JARs falsos; sem Gradle). Desde a M3 E05, os laços
# percorrem também o roteiro-m3.md (e, desde a M4 E05, o roteiro-m4.md), e todo roteiro de docs/demo tem de estar neles.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

aviso='**A recriação vale só para o dia em que é feita.** O servidor fecha o dia sozinho às 03:00 do dia seguinte: as chegadas confirmadas sem check-in viram "Não compareceu", com multa, as diárias são lançadas e a data operacional passa ao dia seguinte. Recrie a demo (`down -v`, `up -d` e o seed) no mesmo dia do roteiro ou da demo, depois das 03:00, e nunca use no dia seguinte um ambiente recriado na véspera.'
ficha_velha='podem sair como "(código FNRH)"'

conta() { grep -cxF -- "$aviso" "$1"; }

# linha_de ARQUIVO TEXTO : número da primeira linha igual a TEXTO (linha inteira).
linha_de() { grep -nxF -- "$1" "$2" | head -1 | cut -d: -f1; }

# --- 1. Unicidade e posição nas fontes ---
leia="$ROOT/tools/server-env/LEIA-ME.md"
roteiros=(roteiro-m1 roteiro-m2 roteiro-m3 roteiro-m4)
# Um roteiro novo em docs/demo sem entrar nos laços passaria sem o aviso: a lista é a de docs/demo.
assert_eq "$(printf '%s\n' "${roteiros[@]}")" "$(find "$ROOT/docs/demo" -maxdepth 1 -name 'roteiro-m*.md' -printf '%f\n' | sed 's/\.md$//' | sort -V)" \
  "os laços percorrem todos os roteiros de docs/demo"
for f in "$leia" "${roteiros[@]/%/.md}"; do
  [[ "$f" == "$leia" ]] || f="$ROOT/docs/demo/$f"
  assert_eq "1" "$(conta "$f")" "o aviso aparece uma vez em ${f#"$ROOT"/}"
done

# Roteiros: o aviso é o parágrafo imediatamente antes de "### Folha de senhas" (uma linha em branco entre eles).
for r in "${roteiros[@]}"; do
  f="$ROOT/docs/demo/$r.md"
  n_aviso="$(linha_de "$aviso" "$f")"
  n_folha="$(linha_de '### Folha de senhas' "$f")"
  assert_eq "$((n_aviso + 2))" "$n_folha" "$r: '### Folha de senhas' vem logo depois do aviso"
  assert_eq "" "$(sed -n "$((n_aviso + 1))p" "$f")" "$r: linha em branco entre o aviso e a folha"
done

# LEIA-ME: logo depois do fecho do bloco que contém down -v / up -d, e antes de "Depois de recriar".
n_aviso="$(linha_de "$aviso" "$leia")"
n_up="$(grep -n 'env-file .env up -d$' "$leia" | tail -1 | cut -d: -f1)"
assert_eq "" "$(sed -n "$((n_aviso - 1))p" "$leia")" "LEIA-ME: linha em branco antes do aviso"
assert_eq '```' "$(sed -n "$((n_aviso - 2))p" "$leia")" "LEIA-ME: o aviso vem logo depois do fecho do bloco"
assert_eq "$((n_up + 1))" "$((n_aviso - 2))" "LEIA-ME: o fecho é o do bloco down -v/up -d"
assert_contains "$(sed -n "$((n_aviso + 2))p" "$leia")" "Depois de recriar, popule" "LEIA-ME: 'Depois de recriar' segue o aviso"
assert_eq "1" "$(rc_section "$leia" '## 3. Parar e recriar a demo' | grep -c 'down -v$')" "LEIA-ME: um só comando down -v na seção 3"

# Passo 13 do M2 (herdado pelo M3): a frase velha não sobrou em lugar nenhum dos roteiros.
for r in roteiro-m2 roteiro-m3 roteiro-m4; do
  assert_eq "0" "$(grep -cF -- "$ficha_velha" "$ROOT/docs/demo/$r.md")" "$r: sem a frase velha dos rótulos FNRH"
done
# O roteiro do M3 remete ao aviso no cabeçalho e no passo das pendências (no-shows previstos somem às 03:00).
assert_contains "$(awk '/^## /{exit} {print}' "$ROOT/docs/demo/roteiro-m3.md")" "como diz o aviso das 03:00 no passo 0" \
  "roteiro-m3: o cabeçalho remete ao aviso das 03:00"
assert_contains "$(rc_section "$ROOT/docs/demo/roteiro-m3.md" '## 26. Pendências')" "às 03:00 o servidor as marca como \"Não compareceu\" (o aviso do passo 0)" \
  "roteiro-m3: os no-shows previstos remetem ao aviso"
# O roteiro do M4 herda as duas remissões do M3 (o cabeçalho e o passo das pendências).
assert_contains "$(awk '/^## /{exit} {print}' "$ROOT/docs/demo/roteiro-m4.md")" "como diz o aviso das 03:00 no passo 0" \
  "roteiro-m4: o cabeçalho remete ao aviso das 03:00"
assert_contains "$(rc_section "$ROOT/docs/demo/roteiro-m4.md" '## 26. Pendências')" "às 03:00 o servidor as marca como \"Não compareceu\" (o aviso do passo 0)" \
  "roteiro-m4: os no-shows previstos remetem ao aviso"

# --- 2. O pacote leva o aviso, uma vez por documento ---
tmp="$(mktemp -d)"
trap 'chmod -R u+rwX "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT
make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT \
  COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES
front="$tmp/front"
make_fake_front "$front"
write_fake_pin "$front" "$srv" "$FAKE_C1"
_fgit "$front" add -A >/dev/null
_fgit "$front" commit -q --allow-empty -m pin
make_fake_jars "$front"
cp -- "$ROOT/docs/demo/roteiro-m1.md" "$front/docs/demo/roteiro-m1.md"
cp -- "$ROOT/docs/demo/roteiro-m2.md" "$front/docs/demo/roteiro-m2.md"
cp -- "$ROOT/docs/demo/roteiro-m3.md" "$front/docs/demo/roteiro-m3.md"
cp -- "$ROOT/docs/demo/roteiro-m4.md" "$front/docs/demo/roteiro-m4.md"
cp -- "$ROOT/tools/server-env/LEIA-ME.md" "$front/tools/server-env/LEIA-ME.md"
assert_exit 0 "pacote com os documentos reais" -- timeout 60 bash "$front/tools/server-env/package.sh"
pkg="$front/build/demo-package"
for p in LEIA-ME.md roteiro-m1.md roteiro-m2.md roteiro-m3.md roteiro-m4.md; do
  assert_eq "1" "$(conta "$pkg/$p")" "pacote: $p traz o aviso uma vez"
done
assert_eq "0" "$(grep -cF -- "$ficha_velha" "$pkg/roteiro-m2.md")" "pacote: roteiro-m2 sem a frase velha"
assert_eq "0" "$(grep -cF -- "$ficha_velha" "$pkg/roteiro-m3.md")" "pacote: roteiro-m3 sem a frase velha"
assert_eq "1" "$(cmp -s "$ROOT/docs/demo/roteiro-m3.md" "$pkg/roteiro-m3.md" && echo 1 || echo 0)" "pacote: o roteiro-m3 real, byte a byte"
assert_eq "0" "$(grep -cF -- "$ficha_velha" "$pkg/roteiro-m4.md")" "pacote: roteiro-m4 sem a frase velha"
assert_eq "1" "$(cmp -s "$ROOT/docs/demo/roteiro-m4.md" "$pkg/roteiro-m4.md" && echo 1 || echo 0)" "pacote: o roteiro-m4 real, byte a byte"

finish
