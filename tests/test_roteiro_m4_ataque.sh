#!/usr/bin/env bash
# Ataque ao roteiro do M4 (M4 E05, testador escopado): docs/demo/roteiro-m4.md, "contas, caixa e check-out", do zero e
# acumulando os roteiros do M3, do M2 e do M1. Aplica as travas genéricas de tests/lib/roteiro_checks.sh e acrescenta o
# que é do M4: os passos 0 a 26 iguais aos do M3 salvo nos passos 0, 1, 5, 6 e 11 (e na folha e nas remissões ao "No
# fim"), a estrutura dos passos 27 a 41, a recriação no dia (regra 5) e o B-M8 no cabeçalho, a saída do seed com a taxa
# e os consumos lida do SeedReport e do DemoCatalog, os hóspedes e valores da conta do catálogo, as aprovações contra o
# item certo da folha, todo texto entre aspas e todo negrito dos passos 27 a 39 existindo no código e o "No fim" com o
# seed repetido. Estático: sem pwsh, Docker nem Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

rot="$ROOT/docs/demo/roteiro-m4.md"
rot3="$ROOT/docs/demo/roteiro-m3.md"
rot1="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
readme_f="$ROOT/README.md"
docs_test="$ROOT/tests/test_docs.sh"
seed_src="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed"
catalog_kt="$seed_src/data/DemoCatalog.kt"
report_kt="$seed_src/data/SeedReport.kt"
pending_kt="$seed_src/pending/PendingSeeder.kt"
folio_seed_kt="$seed_src/folio/FolioSeeder.kt"

missing=0
for f in "$rot" "$rot3" "$rot1" "$leia_f" "$readme_f" "$docs_test" "$catalog_kt" "$report_kt" "$pending_kt" "$folio_seed_kt"; do
  if [[ ! -s "$f" ]]; then
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    missing=1
  fi
done
((missing == 0)) || finish

step() { rc_step "$rot" "$1"; }
step_with() { rc_step_with "$rot" "$1"; }
rot_text="$(cat "$rot")"
last_step="$(grep -oE '^## [0-9]+\.' "$rot" | tail -1 | grep -oE '[0-9]+')"
last_step="${last_step:-0}"
last3="$(grep -oE '^## [0-9]+\.' "$rot3" | tail -1 | grep -oE '[0-9]+')"

# =============================================================================================================
# 1. Travas genéricas de todo roteiro (tests/lib/roteiro_checks.sh)
# =============================================================================================================
roteiro_check_powershell_51 "$rot" "$leia_f"
roteiro_check_seed_secrets "$rot" "$leia_f" "$readme_f"
roteiro_check_leia_app_and_vars "$rot" "$leia_f"
roteiro_check_password_sheet "$rot"
roteiro_check_identical_to_leia "$rot" "$leia_f" "$docs_test"
roteiro_check_folha_before_secrets "$rot"
if shared_txt="$(rc_docs_shared_lines "$docs_test")"; then
  mapfile -t shared_lines <<<"$shared_txt"
  roteiro_check_shared_lines "$rot" "$leia_f" "${shared_lines[@]}"
else
  _assert_fail "test_docs.sh: lista de linhas comuns legível" "definição: $shared_txt"
fi
roteiro_check_seed_order "$rot"
roteiro_check_ps51_lines "$rot"
roteiro_check_prompt_blocks "$rot"
roteiro_check_no_secret_text "$rot"
roteiro_check_seed_secret_values "$rot"
roteiro_check_md_refs "$rot"

# =============================================================================================================
# 2. Estrutura: passos em sequência, títulos do M4, resultado esperado, reprovação e remissões
# =============================================================================================================
rot_nums="$(grep -E '^## ' "$rot" | grep -oE '^## [0-9]+\.' | grep -oE '[0-9]+' | tr '\n' ' ')"
assert_eq "$(seq 0 $(($(grep -cE '^## ' "$rot") - 1)) | tr '\n' ' ')" "$rot_nums" "roteiro: todo título ## é um passo numerado em sequência"
while IFS= read -r h; do
  [[ -n "$h" ]] || continue
  assert_contains "$(rc_section "$rot" "$h")" "**Resultado esperado" "${h#\#\# } tem resultado esperado"
done < <(grep -E '^## ' "$rot")
assert_eq "" "$(awk '
  function flush() { if (cur != "" && !ok) print cur; cur = ""; ok = 0 }
  /^## / || /^\*\*[0-9]+\.[0-9]+\. / { flush() }
  /^\*\*[0-9]+\.[0-9]+\. / { cur = substr($0, 1, 40); ok = 0 }
  /\*\*Resultado esperado/ { ok = 1 }
  END { flush() }' "$rot")" "todo subpasso N.M tem resultado esperado"
# Os passos 0 a 26 têm os títulos do M3; os do M4 vêm depois, nesta ordem.
assert_eq "$(grep -E '^## ([0-9]|1[0-9]|2[0-6])\. ' "$rot3")" "$(grep -E '^## ([0-9]|1[0-9]|2[0-6])\. ' "$rot")" \
  "os passos 0 a 26 têm os títulos do roteiro do M3"
assert_eq "## 27. Abrir o turno de caixa
## 28. A conta com os consumos do seed
## 29. Estornar com a aprovação do Gerente
## 30. Extrato na tela e em PDF
## 31. Ajustar e remover a taxa
## 32. Transferir e dividir
## 33. Pagador e limite
## 34. Receber e reembolsar com a aprovação do Gerente
## 35. Caução e cauções a vencer
## 36. Check-out até o saldo zero
## 37. Reabrir a conta encerrada
## 38. Recancelar com a multa e cancelar com isenção
## 39. Fechar o turno às cegas
## 40. Sair
## 41. No fim" "$(grep -E '^## (2[7-9]|3[0-9]|4[0-9])\. ' "$rot")" "os passos do M4 (27 a 39), Sair (40) e No fim (41), nesta ordem"
n_sair="$(grep -E '^## [0-9]+\. Sair$' "$rot" | grep -oE '[0-9]+' | head -1)"
n_panel="$(step_with '"Chegadas \([0-9]+\)"')"
n_shift="$(step_with 'clique em \*\*Abrir turno\*\*')"
n_close="$(step_with '"O fechamento tem diferença\."')"
assert_eq "27" "${n_shift:-x}" "o turno abre no primeiro passo do M4 (27)"
assert_eq "$((n_sair - 1))" "${n_close:-x}" "o turno fecha no último passo antes de Sair"
for ((n = ${n_panel:-11}; n < ${n_sair:-0}; n++)); do
  assert_contains "$(step "$n")" "**Reprovação" "passo $n: diz o que conta como reprovação"
done
intro="$(awk '/^## / {exit} {print}' "$rot")"
assert_contains "$intro" "Do passo ${n_panel:-?} ao $((${n_sair:-1} - 1)), cada passo diz também o que conta como **Reprovação**" \
  "introdução: o intervalo dos passos com reprovação"
assert_contains "$intro" "os passos ${n_panel:-?} a $((${n_sair:-1} - 1)) rodam uma vez por servidor" "introdução: os passos que rodam uma vez"
assert_contains "$intro" "Os passos 11 a $((${n_sair:-1} - 1)) mudam os dados da demo" "introdução: os passos que mudam os dados"
assert_contains "$intro" "Os passos do M4 (do 27 ao $((${n_sair:-1} - 1)))" "introdução: o intervalo dos passos do M4"
for n in $(grep -oE 'passos? [0-9]+((, | e | a )[0-9]+)*' "$rot" | grep -oE '[0-9]+' | sort -un); do
  ((n <= last_step)) || _assert_fail "remissão ao passo $n existe (último: $last_step)"
done
for n in $(grep -oE '(Do|do) passo [0-9]+ ao [0-9]+|do [0-9]+ ao [0-9]+' "$rot" | grep -oE '[0-9]+' | sort -un); do
  ((n <= last_step)) || _assert_fail "intervalo cita o passo $n, que existe (último: $last_step)"
done
labels="$(grep -oE '^\*\*[0-9]+\.[0-9]+\. ' "$rot" | tr -d '* ' | sed 's/\.$//')"
for b in $(sed -E 's/PowerShell 5\.1//g' "$rot" | grep -oE '(^|[^0-9.v:$])[0-9]{1,2}\.[0-9]{1,2}([^0-9.%]|\.[^0-9]|$)' |
  grep -oE '[0-9]{1,2}\.[0-9]{1,2}' | sort -u); do
  grep -qxF -- "$b" <<<"$labels" || _assert_fail "remissão ao bloco $b existe (**$b.)"
done
assert_contains "$(step "$last_step")" "### Erros comuns" "os Erros comuns ficam no último passo ($last_step)"
assert_contains "$rot_text" "\"Erros comuns\", no passo $last_step" "a remissão a Erros comuns aponta o passo $last_step"
assert_contains "$(step "${n_sair:-x}")" "o passo $last_step diz como parar o servidor" "Sair remete ao No fim"
assert_eq "" "$(grep -nE 'README' "$rot")" "roteiro não remete ao README"

# =============================================================================================================
# 3. Pacote: os passos 0 e 1 listam o roteiro-m4.md, com o "(este)" nele; o conteúdo é o do roteiro do M1 mais ele
# =============================================================================================================
names_of() { grep -oE '`[^`]+`' <<<"$1" | tr -d '`' | grep -vE '^(demo-package|\.env|\.example)$' | sort -u; }
list_of() { names_of "$(grep -F "$2" "$1" | sed "s/ $3.*//")"; }
m4_l0="$(list_of "$rot" 'Ela tem as pastas' 'Você não precisa')"
m4_l1="$(list_of "$rot" 'a lista tem `app`' 'Não pode ter')"
assert_contains "$m4_l0" "roteiro-m4.md" "passo 0: o pacote leva o roteiro-m4.md"
assert_contains "$m4_l1" "roteiro-m4.md" "passo 1: o Get-ChildItem espera o roteiro-m4.md"
assert_eq "$m4_l0" "$m4_l1" "passos 0 e 1: a mesma lista do pacote"
assert_eq "$(printf '%s\nroteiro-m4.md' "$(list_of "$rot1" 'Ela tem as pastas' 'Você não precisa')" | sort -u)" "$m4_l0" \
  "passo 0: o conteúdo do pacote é o do roteiro-m1 mais o roteiro-m4.md"
assert_contains "$(grep -F 'Ela tem as pastas' "$rot")" '`roteiro-m4.md` (este)' "passo 0: \"(este)\" ao lado do roteiro-m4.md"
assert_eq "1" "$(grep -oF '(este)' "$rot" | grep -c .)" "passo 0: um só \"(este)\""

# =============================================================================================================
# 4. Os passos 0 a 26 são os do M3, salvo nos passos 0, 1, 5, 6 e 11, na folha e nas remissões ao "No fim"
# =============================================================================================================
body() { awk '/^## 0\. /{on = 1} /^## 27\. /{exit} on' "$1"; }
m3_folha_said="$(grep -oE 'precisar deles nos passos [0-9]+((, | e )[0-9]+)*' "$rot3" | head -1)"
m4_extra_steps="$(for ((n = 27; n < ${n_sair:-27}; n++)); do step "$n" | grep -qE 'item [0-9]+ da folha' && echo "$n"; done)"
mapfile -t said_nums < <(grep -oE '[0-9]+' <<<"$m3_folha_said"; grep . <<<"$m4_extra_steps")
said_list="$(IFS=,; echo "${said_nums[*]:0:${#said_nums[@]}-1}" | sed 's/,/, /g') e ${said_nums[-1]}"
m4_folha_said="precisar deles nos passos $said_list"
assert_contains "$(rc_folha "$rot")" "$m4_folha_said," "folha: o M3 com os passos do M4 que pedem senha ou PIN ($(tr '\n' ' ' <<<"$m4_extra_steps"))"
[[ -n "$m4_extra_steps" ]] || _assert_fail "algum passo do M4 pede um item da folha (as aprovações)"
expected_body="$(body "$rot3" | sed -E \
  -e "s/$m3_folha_said,/$m4_folha_said,/" \
  -e "s/passo $last3([^0-9])/passo $last_step\\1/g; s/passo $last3\$/passo $last_step/")"
m4_body="$(body "$rot")"
[[ "$(grep -c . <<<"$expected_body")" -gt 400 ]] || _assert_fail "li os passos 0 a 26 do roteiro-m3.md"
# Passo de cada linha mudada: as linhas do M3 (removidas) e as do M4 (acrescentadas).
changed_steps() {
  diff --unchanged-line-format= --old-line-format="$1" --new-line-format="$2" \
    <(printf '%s\n' "$expected_body") <(printf '%s\n' "$m4_body") | grep -v '^$' |
    while IFS= read -r l; do
      T="$l" awk '/^## [0-9]+\. / {s = $2} $0 == ENVIRON["T"] {sub(/\.$/, "", s); print s; exit}' "$3"
    done | sort -un | tr '\n' ' '
}
removed_steps="$(changed_steps '%L' '' <(printf '%s\n' "$expected_body"))"
added_steps="$(changed_steps '' '%L' "$rot")"
for s in $removed_steps $added_steps; do
  case "$s" in
    0 | 1 | 5 | 6 | 11) ;;
    *) _assert_fail "passos 0 a 26: o passo $s mudou em relação ao M3 (só os passos 0, 1, 5, 6 e 11 mudam)" \
      "passos com linha removida: $removed_steps; com linha acrescentada: $added_steps" ;;
  esac
done
[[ -n "$added_steps" ]] || _assert_fail "passos 0 a 26: li as diferenças em relação ao M3"
assert_eq "0 1 5 6 11 " "$added_steps" "passos 0 a 26: as linhas novas ficam nos passos 0, 1, 5, 6 e 11"
ger="$(grep -E '^Do passo 11 ao [0-9]+, quem trabalha é a Recepcionista\.' "$rot")"
assert_eq "11" "$(rc_step_of_line "$rot" "$(grep -nxF -- "$ger" "$rot" | head -1 | cut -d: -f1)")" "a frase do Gerente fica no passo 11"
assert_contains "$ger" "Do passo 11 ao ${n_sair:-?}," "passo 11: a Recepcionista trabalha até Sair"

# =============================================================================================================
# 5. Cabeçalho: título, abertura, a recriação no dia (regra 5) e o B-M8 (a UH 208 depende da data da recriação)
# =============================================================================================================
assert_eq "# Roteiro de demonstração do M4 — contas, caixa e check-out" "$(head -1 "$rot")" "título do roteiro do M4"
opening="$(sed -n 3p "$rot")"
assert_contains "$opening" "Este roteiro mostra o que o M4 entrega" "abertura: o que o M4 entrega"
assert_contains "$opening" "Ele acumula o roteiro do M3" "abertura: acumula o roteiro do M3"
assert_contains "$opening" "o do M2" "abertura: e o do M2"
assert_contains "$opening" "e o do M1" "abertura: e o do M1"
aviso='**A recriação vale só para o dia em que é feita.**'
assert_eq "1" "$(grep -cF -- "$aviso" "$rot")" "regra 5: o aviso da recriação no dia, uma vez"
assert_eq "0" "$(rc_step_of_line "$rot" "$(grep -nF -- "$aviso" "$rot" | head -1 | cut -d: -f1)")" "regra 5: o aviso fica no passo 0"
day_par="$(grep -F 'como diz o aviso das 03:00 no passo 0' <<<"$intro")"
assert_contains "$day_par" "num ambiente recriado na véspera" "regra 5: o cabeçalho remete ao aviso e diz o que dá errado na véspera"
room208="$(grep -oE 'const val ROOM_NUMBER = "[0-9]+"' "$pending_kt" | grep -oE '[0-9]+')"
ooo_a="$(grep -oE 'const val RESERVATION_ARRIVAL_DAY = [0-9]+' "$pending_kt" | grep -oE '[0-9]+$')"
ooo_d="$(grep -oE 'const val RESERVATION_DEPARTURE_DAY = [0-9]+' "$pending_kt" | grep -oE '[0-9]+$')"
assert_contains "$(fn_ooo="$(grep -A4 'gateway.openOutOfOrder(' "$pending_kt")"; echo "$fn_ooo")" 'businessDate.plusDays(RESERVATION_ARRIVAL_DAY)' \
  "código: o fora de ordem da UH $room208 começa na data operacional do seed"
bm8="$(grep -F "O fora de ordem da UH $room208" <<<"$intro")"
[[ -n "$bm8" ]] || _assert_fail "B-M8: o cabeçalho fala do fora de ordem da UH $room208"
assert_contains "$bm8" "depende da data operacional do dia da recriação" "B-M8: o fora de ordem depende da data da recriação"
assert_contains "$bm8" "da data operacional mais $ooo_a dias à mais $ooo_d dias" "B-M8: o período do fora de ordem é o do PendingSeeder (d+$ooo_a a d+$ooo_d)"
assert_contains "$bm8" "uma demo recriada noutro dia o tem noutras datas" "B-M8: noutro dia, noutras datas"
assert_contains "$(grep -F 'Os passos do M4 (do' <<<"$intro")" "o passo 38 reativa e cancela de novo a reserva cancelada no passo 24" \
  "cabeçalho: os passos do M4 dependem da ordem (38 sobre o 24)"

# =============================================================================================================
# 6. Seed: a saída com a taxa de serviço e os consumos, como o SeedReport a monta e o DemoCatalog a conta
# =============================================================================================================
n_seed="$(step_with 'demo-seed\.jar --server')"
seed_step="$(step "${n_seed:-x}")"
seed_labels="$(grep -oE '^    [A-Z_]+\("[^"]+"\)' "$report_kt" | sed -E 's/.*\("([^"]+)"\)/\1/')"
seed_block="$(awk '/^  ```text$/ {on = 1; next} on && /^  ```$/ {exit} on' <<<"$seed_step" | sed -E 's/^  //')"
seed_out="$(grep -E ': [0-9]+ criados, [0-9]+ já existiam$' <<<"$seed_block")"
assert_eq "$(printf '%s\nconsumos' "$seed_labels")" "$(sed -E 's/: [0-9]+ criados.*//' <<<"$seed_out")" \
  "saída do seed: os rótulos e a ordem do SeedItem, com os consumos por último (SeedReport.lines)"
rate="$(grep -oE 'const val SERVICE_CHARGE_RATE = "[0-9]+"' "$catalog_kt" | grep -oE '"[0-9]+"' | tr -d '"')"
assert_contains "$(cat "$report_kt")" 'serviceChargeLine = "taxa de serviço: $rate% (${if (defined) "definida" else "já configurada"})"' \
  "código: a linha da taxa de serviço"
assert_eq "taxa de serviço: $rate% (definida)" "$(sed -n 2p <<<"$seed_block")" "saída do seed: a taxa ($rate%) logo depois do hotel"
assert_eq "hotel" "$(sed -n 1p <<<"$seed_block" | sed -E 's/:.*//')" "saída do seed: o hotel vem primeiro"
n_ci="$(grep -cE '^ +r\("[A-Z][0-9]+", [0-9]+, "[A-Z]+", [0-9]+, [0-9]+, "[0-9]{3}", true\)' "$catalog_kt")"
n_cons="$(awk '/consumptions =/ {on = 1} on' "$catalog_kt" | grep -m1 -oE 'listOf\(.*\)' | grep -oE 'ConsumptionSpec\(' | grep -c .)"
((n_ci > 0 && n_cons > 0)) || _assert_fail "li os hospedados ($n_ci) e os consumos ($n_cons) do DemoCatalog"
assert_contains "$seed_out" "consumos: $((n_ci * n_cons)) criados, 0 já existiam" "seed: consumos = $n_cons por hospedado × $n_ci hospedados"
assert_contains "$seed_step" "lançados na conta de cada um dos $n_ci hospedados" "seed: a linha dos consumos explica a conta ($n_ci hospedados)"
assert_contains "$seed_step" "\`consumos: 0 criados, $((n_ci * n_cons)) já existiam\`" "seed repetido: os consumos já existiam"
assert_contains "$seed_step" "\`taxa de serviço: $rate% (já configurada)\`" "seed repetido: a taxa já configurada"
mark="$(grep -oE 'private const val MARK = "[^"]+"' "$folio_seed_kt" | sed -E 's/.*= "([^"]+)"/\1/')"
cons_desc="$(awk '/consumptions =/ {on = 1} on' "$catalog_kt" | grep -m1 -oE 'listOf\(.*\)' | grep -oE 'ConsumptionSpec\("[^"]+", "[0-9.]+"\)')"
while IFS= read -r c; do
  [[ -n "$c" ]] || continue
  d="$(sed -E 's/ConsumptionSpec\("([^"]+)".*/\1/' <<<"$c")"
  assert_contains "$seed_step" "\`$mark$d\`" "seed: o consumo $d com a marca do FolioSeeder ($mark)"
done <<<"$cons_desc"

# =============================================================================================================
# 7. Os hóspedes e os valores da conta (28 a 36), do DemoCatalog
# =============================================================================================================
name_prefix="$(grep -oE 'fullName = "[^$"]*' "$catalog_kt" | sed 's/^fullName = "//')"
guest_words="$(awk '/val names =/ {on = 1; next} on && /^ +\)$/ {exit} on' "$catalog_kt" | grep -oE '"[^"]+"' | tr -d '"')"
guest_name() { printf '%s%s' "$name_prefix" "$(sed -n "${1}p" <<<"$guest_words")"; }
all_names="$(for ((i = 1; i <= $(grep -c . <<<"$guest_words"); i++)); do guest_name "$i"; echo; done)"
res="$(grep -E '^ +r\("[A-Z][0-9]+",' "$catalog_kt" | sed -E 's/, null, /, "-", /' |
  sed -E 's/^ +r\("([A-Z][0-9]+)", ([0-9]+), "([A-Z]+)", ([0-9]+), ([0-9]+), "([0-9]{3}|-)", (true|false)\),?$/\1 \2 \3 \4 \5 \6 \7/')"
# guest_of_room UH : "nome|noites|hospedado" do titular da reserva do catálogo na UH.
guest_of_room() { awk -v r="$1" '$6 == r {print $2, $4, $7}' <<<"$res"; }
# Cada "Hóspede Demonstração X" (UH NNN) citado nos passos do M4 é o titular hospedado do catálogo nessa UH.
m4_text="$(for ((n = 27; n < ${n_sair:-27}; n++)); do step "$n"; done)"
pairs="$(grep -oE "\"$name_prefix[[:alpha:]]+\" \\(UH [0-9]{3}\\)" <<<"$m4_text" | sort -u)"
(($(grep -c . <<<"$pairs") >= 3)) || _assert_fail "li os hóspedes citados com a UH nos passos do M4" "$pairs"
while IFS= read -r p; do
  [[ -n "$p" ]] || continue
  nm="$(sed -E 's/^"([^"]+)".*/\1/' <<<"$p")"
  uh="$(grep -oE '[0-9]{3}\)$' <<<"$p" | tr -d ')')"
  read -r g nights ci <<<"$(guest_of_room "$uh")"
  assert_eq "$nm" "$(guest_name "${g:-0}")" "$p: o titular da UH $uh no DemoCatalog"
  assert_eq "true" "${ci:-}" "$p: a UH $uh está hospedada no seed"
done <<<"$pairs"
# O check-out (36) é do day use que sai hoje, o hóspede da caução (35).
n_ck="$(step_with 'Clique em \*\*Concluir check-out\*\*')"
ck_room="$(grep -oE "\"$name_prefix[[:alpha:]]+\" \\(UH [0-9]{3}\\)" <<<"$(step "${n_ck:-x}")" | head -1 | grep -oE '[0-9]{3}')"
read -r _ ck_nights _ <<<"$(guest_of_room "${ck_room:-x}")"
assert_eq "0" "${ck_nights:-x}" "36: o check-out é de um day use do seed (sai hoje)"
assert_contains "$(step 35)" "(UH $ck_room), um day use que sai hoje" "35: a caução é na conta do hóspede do check-out"
assert_contains "$(step "${n_ck:-x}")" "que passa a contar $n_ci" "36: os hospedados voltam a $n_ci (o walk-in entra e o check-out sai)"
assert_contains "$(step "${n_ck:-x}")" "o ladrilho da UH $ck_room mostra \"Suja\"" "36: a UH do check-out fica suja"
assert_contains "$(step "${n_ck:-x}")" '"Ocupação: …" (Vaga)' "36: a UH do check-out fica vaga"
# Os consumos do seed na conta do 28, com a taxa calculada pelo catálogo; o saldo do servidor recém-criado.
money() { awk -v v="$1" 'BEGIN { s = sprintf("%.2f", v); sub(/\./, ",", s); print "R$ " s }'; }
s28="$(step 28)"
total=0
while IFS= read -r c; do
  [[ -n "$c" ]] || continue
  d="$(sed -E 's/ConsumptionSpec\("([^"]+)".*/\1/' <<<"$c")"
  v="$(sed -E 's/.*"([0-9.]+)"\)$/\1/' <<<"$c")"
  tax="$(awk -v v="$v" -v r="$rate" 'BEGIN { printf "%.2f", v * r / 100 }')"
  assert_contains "$s28" "\`$mark$d\`, de $(money "$v")" "28: o consumo $d com o valor do catálogo"
  assert_contains "$(grep -F 'a linha da taxa de serviço dele' <<<"$s28")" "$(money "$tax" | sed 's/^R\$ //')" "28: a taxa de $d ($rate%)"
  total="$(awk -v t="$total" -v v="$v" -v x="$tax" 'BEGIN { printf "%.2f", t + v + x }')"
done <<<"$cons_desc"
assert_contains "$s28" "o saldo é $(money "$total"), a soma dos dois consumos e das duas taxas" "28: o saldo inicial (consumos e taxas do catálogo)"

# =============================================================================================================
# 8. Aprovações: login gerente e o PIN do item certo da folha; nenhum PIN nem senha impresso
# =============================================================================================================
rows="$(rc_folha_rows "$rot")"
i_pin="$(rc_folha_item "$rows" 'PIN do Gerente')"
appr_steps="$(for ((n = 0; n <= last_step; n++)); do step "$n" | grep -qF 'Em **Login de quem aprova**, digite `gerente`' && echo "$n"; done | tr '\n' ' ')"
assert_eq "16 22 25 29 31 34 37 38 " "$appr_steps" "o Gerente aprova no desfazer, na diária, na reativação, no estorno, no ajuste, no reembolso, na reabertura e no 38"
for n in 29 31 34 37 38; do
  s="$(step "$n")"
  l="$(grep -E 'Em \*\*Login de quem aprova\*\*, digite' <<<"$s")"
  assert_eq "" "$(grep -vF 'digite `gerente`' <<<"$l")" "passo $n: quem aprova é sempre o gerente"
  assert_eq "$i_pin" "$(grep -oE 'item [0-9]+ da folha' <<<"$l" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "passo $n: a aprovação aponta o item do PIN ($i_pin), e só ele"
  assert_contains "$s" '"Aprovação de superior"' "passo $n: o diálogo de aprovação"
done
assert_eq "2" "$(step 38 | grep -cF 'Em **Login de quem aprova**, digite `gerente`')" "38: duas aprovações (a reativação e a isenção)"
for n in 29 31 34 37 38; do
  assert_contains "$(step "$n")" "o PIN aparece na tela ou numa mensagem" "passo $n: o PIN na tela é reprovação"
done
# Nenhum dígito de PIN ou senha nos passos do M4: o único valor digitado no PIN é o item da folha.
assert_eq "" "$(grep -E 'em \*\*PIN\*\*, (digite|`)' <<<"$m4_text")" "passos do M4: nenhum PIN digitado por extenso"
assert_eq "" "$(grep -iE 'senha[^.]*`[^`]+`' <<<"$m4_text")" "passos do M4: nenhuma senha impressa"

# =============================================================================================================
# 9. Rótulos e textos citados nos passos do M4 existem no código do app, do design ou do core
# =============================================================================================================
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
code_f="$tmp/code.kt"
find "$ROOT/app/src/main" "$ROOT/design/src/main" "$ROOT/core/src/main" -name '*.kt' -print0 | xargs -0 cat |
  perl -0pe 's/"\s*\+\s*\n\s*"//g' >"$code_f"
tpl='\$(\{[^}]*\}|[A-Za-z_][A-Za-z0-9_]*)'
# tpl_in_code TEXTO : o texto, com "…", "R-…", "R$ …" e "dd/mm/aaaa" no lugar de interpolações, casa um literal do código.
tpl_in_code() {
  local re
  re="$(sed -E 's/[][\.*^$+?(){}|]/\\&/g' <<<"$1" | sed -E 's/R-…|R\\\$ …|…|dd\/mm\/aaaa/@T@/g')"
  re="${re//@T@/$tpl}"
  grep -qE -- "\"$re\"" "$code_f"
}
type_names="$(grep -oE 'RoomTypeSpec\("[A-Z]+", "[^"]+"' "$catalog_kt" | sed -E 's/.*, "([^"]+)"$/\1/')"
plan_name="$(awk '/ratePlan =/{on = 1} on' "$catalog_kt" | grep -oE 'name = "[^"]+"' | head -1 | sed -E 's/name = "([^"]+)"/\1/')"
meal="$(awk '/ratePlan =/{on = 1} on' "$catalog_kt" | grep -oE 'mealPlan = "[A-Z_]+"' | head -1 | grep -oE '"[A-Z_]+"' | tr -d '"')"
meal_label="$(grep -rhoE "\"$meal\" to \"[^\"]+\"" "$ROOT/app/src/main" | head -1 | sed -E 's/.* to "([^"]+)"/\1/')"
std_name="$(grep -oE 'RoomTypeSpec\("STD", "[^"]+"' "$catalog_kt" | sed -E 's/.*, "([^"]+)"$/\1/')"
offer="$std_name · $plan_name · $meal_label"
bad_quotes=""
while IFS= read -r q; do
  [[ -n "$q" ]] || continue
  p="${q:1:${#q}-2}"
  if grep -qF -- "\"$p\"" "$code_f" || tpl_in_code "$p"; then continue; fi
  # Um pedaço de mensagem citado na prosa: está dentro de um literal.
  if [[ "$p" != *[[:space:]]* ]] && grep -qE -- "\"([^\"]*[ (])?$p[ .$][^\"]*\"" "$code_f"; then
    continue
  fi
  case "$p" in
    "$name_prefix"*) grep -qxF -- "$p" <<<"$all_names" && continue ;;
    "$offer") continue ;;
    "Página 1 de N") grep -qF 'Text("Página ${document.page + 1} de ${document.pageCount}")' "$code_f" && continue ;;
  esac
  bad_quotes+="$p"$'\n'
done < <(grep -oE '"[^"]+"' <<<"$m4_text" | sort -u)
assert_eq "" "$bad_quotes" "todo texto entre aspas dos passos do M4 existe no código (ou é nome ou oferta do seed)"
bad_bold=""
while IFS= read -r b; do
  [[ -n "$b" ]] || continue
  case "$b" in
    [0-9]*. | "Resultado esperado:" | "Reprovação:" | "Anote o número da conta") continue ;;
  esac
  b="${b%:}"
  grep -qF -- "\"$b\"" "$code_f" && continue
  grep -qxF -- "$b" <<<"$type_names" && continue
  bad_bold+="$b"$'\n'
done < <(grep -oE '\*\*[^*]+\*\*' <<<"$m4_text" | tr -d '*' | sed -E 's/^([0-9]+\.[0-9]+\.).*/\1/' | sort -u)
assert_eq "" "$bad_bold" "todo rótulo em negrito dos passos do M4 é um literal do código"
(($(grep -oE '"[^"]+"' <<<"$m4_text" | sort -u | grep -c .) >= 120)) || _assert_fail "li os textos entre aspas dos passos do M4"
# Valores de dados que não são literais do app ficam entre crases: nenhuma descrição "Seed M4: …" entre aspas.
assert_eq "" "$(grep -oE "\"$mark[^\"]*\"" "$rot")" "as descrições dos consumos do seed vão entre crases, não entre aspas"

# =============================================================================================================
# 10. No fim: o seed repetido com os consumos, a taxa e a UH do check-out; o retomar
# =============================================================================================================
fim="$(step "$last_step")"
fim_rerun="$(awk '/^### Rodar o seed de novo depois da demo$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
[[ -n "$fim_rerun" ]] || _assert_fail "No fim: tem \"### Rodar o seed de novo depois da demo\""
assert_contains "$fim_rerun" "\`consumos: 0 criados, $((n_ci * n_cons)) já existiam\`" "No fim: os consumos já existiam"
assert_contains "$fim_rerun" "\`taxa de serviço: $rate% (já configurada)\`" "No fim: a taxa já configurada"
assert_contains "$fim_rerun" "a UH $ck_room, vaga e suja depois do check-out do passo ${n_ck:-?}, voltam a inspecionadas" \
  "No fim: a UH do check-out volta a inspecionada"
assert_contains "$fim_rerun" "\`status de UH: 2 criados, 18 já existiam\`" "No fim: duas UHs voltam a inspecionadas (103 e a do check-out)"
assert_contains "$fim_rerun" "reaberta no passo 37, continua aberta" "No fim: a conta reaberta continua aberta"
assert_contains "$fim_rerun" "nos passos 28 a 38 fica como está" "No fim: o que se fez nas contas fica"
assert_eq "" "$(rc_ps_code <<<"$fim" | grep -E 'demo-seed|SetEnvironmentVariable|Read-Host|Get-Credential')" \
  "No fim: sem bloco do seed nem de segredo (remete ao passo do seed)"
parar="$(awk '/^### Parar sem apagar$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
assert_contains "$parar" "os passos ${n_panel:-?} a $((${n_sair:-1} - 1)) rodam uma vez por servidor" "Parar sem apagar: os passos que rodam uma vez"

finish
