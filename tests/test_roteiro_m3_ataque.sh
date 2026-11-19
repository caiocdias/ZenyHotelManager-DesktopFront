#!/usr/bin/env bash
# Ataque ao roteiro do M3 (M3 E05, testador de etapa): docs/demo/roteiro-m3.md, "reservas do começo ao fim", do
# zero e acumulando os roteiros do M2 e do M1. Aplica as travas genéricas de tests/lib/roteiro_checks.sh e acrescenta
# o que é do M3: os passos 0 a 18 iguais aos do M2 salvo a lista fechada de diferenças (7 itens), a estrutura dos
# passos 19 a 28, a saída do seed igual ao SeedItem, os fatos do seed lidos do PendingSeeder e do FutureSeeder
# (titular, tipos, datas, situação), o CPF do hóspede citado recalculado pelo FictitiousDocuments, a cotação contra o
# catálogo, as ressalvas das listas de pendências, as aprovações contra o item certo da folha, os rótulos citados
# existindo no código e o "No fim" com as reservas do M3. Estático: sem pwsh, Docker nem Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

rot="$ROOT/docs/demo/roteiro-m3.md"
rot2="$ROOT/docs/demo/roteiro-m2.md"
rot1="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
readme_f="$ROOT/README.md"
docs_test="$ROOT/tests/test_docs.sh"
seed_src="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed"
catalog_kt="$seed_src/data/DemoCatalog.kt"
report_kt="$seed_src/data/SeedReport.kt"
docs_kt="$seed_src/data/FictitiousDocuments.kt"
traces_kt="$seed_src/frontdesk/TraceSeeder.kt"
pending_kt="$seed_src/pending/PendingSeeder.kt"
future_kt="$seed_src/pending/FutureSeeder.kt"
demoseed_kt="$seed_src/DemoSeed.kt"
adapter_kt="$seed_src/sdk/SdkPendingGateway.kt"
app_res="$ROOT/app/src/main/kotlin/com/zeny/desktop/app/reservations"
app_rec="$ROOT/app/src/main/kotlin/com/zeny/desktop/app/reception"
facade_kt="$ROOT/core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt"
cancel_test="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reservations/ReservationCancelStateTest.kt"

missing=0
for f in "$rot" "$rot2" "$rot1" "$leia_f" "$readme_f" "$docs_test" "$catalog_kt" "$report_kt" "$docs_kt" "$traces_kt" \
  "$pending_kt" "$future_kt" "$demoseed_kt" "$adapter_kt" "$facade_kt" "$cancel_test" "$app_res/ReservationSearchText.kt" \
  "$app_res/ReservationSearchState.kt" "$app_res/AvailabilityState.kt" "$app_res/AvailabilityText.kt" \
  "$app_res/QuotePanel.kt" "$app_res/PendingListsState.kt" "$app_res/PendingText.kt" "$app_res/BookingText.kt" \
  "$app_rec/ReceptionText.kt"; do
  if [[ ! -s "$f" ]]; then
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    missing=1
  fi
done
((missing == 0)) || finish

step() { rc_step "$rot" "$1"; }
step_with() { rc_step_with "$rot" "$1"; }
rot_text="$(cat "$rot")"
rot_ps="$(rc_ps_code "$rot")"
last_step="$(grep -oE '^## [0-9]+\.' "$rot" | tail -1 | grep -oE '[0-9]+')"
last_step="${last_step:-0}"
last2="$(grep -oE '^## [0-9]+\.' "$rot2" | tail -1 | grep -oE '[0-9]+')"
# fem N : o numeral por extenso, no feminino ("duas linhas", "seis reservas").
fem() {
  local w=(zero uma duas três quatro cinco seis sete oito nove dez)
  echo "${w[$1]:-$1}"
}
# day N : como o roteiro escreve o dia N contado da data operacional.
day() { if (($1 == 0)); then echo "a data operacional"; else echo "mais $1 dias"; fi; }

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
# 2. Estrutura: passos em sequência, títulos do M3, resultado esperado, reprovação e remissões
# =============================================================================================================
rot_nums="$(grep -E '^## ' "$rot" | grep -oE '^## [0-9]+\.' | grep -oE '[0-9]+' | tr '\n' ' ')"
assert_eq "$(seq 0 $(($(grep -cE '^## ' "$rot") - 1)) | tr '\n' ' ')" "$rot_nums" "roteiro: todo título ## é um passo numerado em sequência"
while IFS= read -r h; do
  [[ -n "$h" ]] || continue
  assert_contains "$(rc_section "$rot" "$h")" "**Resultado esperado" "${h#\#\# } tem resultado esperado"
done < <(grep -E '^## ' "$rot")
missing_res="$(awk '
  /^## / { if (pending) print pending_ln; pending = 0 }
  /^```powershell/ { if (pending) print pending_ln; pending = 1; pending_ln = NR; inb = 1; next }
  /^```/ { inb = 0; next }
  inb { next }
  pending && (/\*\*Resultado esperado/ || /^\| Código \|/) { pending = 0 }
  END { if (pending) print pending_ln }' "$rot")"
assert_eq "" "$missing_res" "todo bloco PowerShell do roteiro tem resultado esperado (linhas sem: $missing_res)"
# Todo subpasso **N.M.** traz o seu "Resultado esperado" antes do próximo subpasso ou passo.
assert_eq "" "$(awk '
  function flush() { if (cur != "" && !ok) print cur; cur = ""; ok = 0 }
  /^## / || /^\*\*[0-9]+\.[0-9]+\. / { flush() }
  /^\*\*[0-9]+\.[0-9]+\. / { cur = substr($0, 1, 40); ok = 0 }
  /\*\*Resultado esperado/ { ok = 1 }
  END { flush() }' "$rot")" "todo subpasso N.M tem resultado esperado"
# Os títulos do M3, nesta ordem, depois dos 19 passos herdados do M2 (do 0 ao 18).
m3_titles="$(grep -E '^## (19|2[0-9])\. ' "$rot")"
assert_eq "## 19. Buscar reservas
## 20. Disponibilidade e cotação
## 21. Nova reserva
## 22. Desconto com a aprovação do Gerente
## 23. Alterar uma reserva e estender um hospedado
## 24. Cancelar com a multa
## 25. Reativar com a aprovação do Gerente
## 26. Pendências
## 27. Sair
## 28. No fim" "$m3_titles" "os passos do M3 (19 a 26), Sair (27) e No fim (28), nesta ordem"

n_seed="$(step_with 'demo-seed\.jar --server')"
n_app="$(step_with 'app\\zeny-desktop\.jar')"
n_live="$(step_with 'auth/login')"
n_panel="$(step_with '"Chegadas \([0-9]+\)"')"
n_checkin="$(step_with '\*\*Fazer check-in\*\*')"
n_walkin="$(step_with '\*\*Concluir walk-in\*\*')"
n_undo="$(step_with 'Check-in desfeito\.')"
n_concurrent="$(step_with 'Alguém alterou este recado')"
n_search="$(step_with 'Buscar por documento')"
n_quote="$(step_with 'Cotar a partir deste dia')"
n_new="$(step_with 'Confirmação da reserva — R-')"
n_discount="$(step_with 'Diária negociada com o hóspede')"
n_change="$(step_with 'Salvar alteração')"
n_cancel="$(step_with 'Multa lançada na conta')"
n_react="$(step_with 'reativada\.')"
n_pend="$(step_with 'Realocação necessária')"
n_sair="$(grep -E '^## [0-9]+\. Sair$' "$rot" | grep -oE '[0-9]+' | head -1)"
order_ok=1
prev=-1
for pair in "seed|$n_seed" "abrir o app|$n_app" "mudança ao vivo|$n_live" "painel do dia|$n_panel" \
  "check-in|$n_checkin" "walk-in|$n_walkin" "desfazer check-in|$n_undo" "edição concorrente|$n_concurrent" \
  "busca|$n_search" "cotação|$n_quote" "nova reserva|$n_new" "desconto|$n_discount" "alteração|$n_change" \
  "cancelamento|$n_cancel" "reativação|$n_react" "pendências|$n_pend" "Sair|$n_sair" "No fim|$last_step"; do
  v="${pair#*|}"
  if [[ -z "$v" ]]; then
    _assert_fail "roteiro tem um único passo de ${pair%%|*}"
    order_ok=0
  elif ((v <= prev)); then
    order_ok=0
  else
    prev="$v"
  fi
done
((order_ok)) || _assert_fail "roteiro em ordem: do seed à edição concorrente (M2), da busca às pendências (M3), Sair e No fim" \
  "passos: $n_seed $n_app $n_live $n_panel $n_checkin $n_walkin $n_undo $n_concurrent $n_search $n_quote $n_new $n_discount $n_change $n_cancel $n_react $n_pend $n_sair $last_step"
# Do painel (11) ao último antes de Sair, todo passo diz o que conta como reprovação; o cabeçalho diz o intervalo.
for ((n = ${n_panel:-11}; n < ${n_sair:-0}; n++)); do
  assert_contains "$(step "$n")" "**Reprovação" "passo $n: diz o que conta como reprovação"
done
intro="$(awk '/^## / {exit} {print}' "$rot")"
assert_contains "$intro" "Do passo ${n_panel:-?} ao $((${n_sair:-1} - 1)), cada passo diz também o que conta como **Reprovação**" \
  "introdução: o intervalo dos passos com reprovação"
# Remissões a passos e blocos existem; Erros comuns no último passo.
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
assert_eq "" "$(grep -nE 'README' "$rot")" "roteiro não remete ao README"
# O conteúdo do pacote nos passos 0 e 1 é o mesmo do roteiro do M1 (que o teste do pacote confere com o package.sh).
names_of() { grep -oE '`[^`]+`' <<<"$1" | tr -d '`' | grep -vE '^(demo-package|\.env|\.example)$' | sort -u; }
list_of() { names_of "$(grep -F "$2" "$1" | sed "s/ $3.*//")"; }
m1_l0="$(list_of "$rot1" 'Ela tem as pastas' 'Você não precisa')"
m1_l1="$(list_of "$rot1" 'a lista tem `app`' 'Não pode ter')"
[[ -n "$m1_l0" ]] || _assert_fail "roteiro-m1: passo 0 lista o conteúdo do pacote"
assert_eq "$m1_l0" "$(list_of "$rot" 'Ela tem as pastas' 'Você não precisa')" "passo 0: o conteúdo do pacote é o do roteiro-m1"
assert_eq "$m1_l1" "$(list_of "$rot" 'a lista tem `app`' 'Não pode ter')" "passo 1: o Get-ChildItem espera o conteúdo do roteiro-m1"
assert_contains "$m1_l0" "roteiro-m3.md" "o pacote leva o roteiro-m3.md"
assert_contains "$(grep -F 'Ela tem as pastas' "$rot")" '`roteiro-m3.md` (este)' "passo 0: \"(este)\" ao lado do roteiro-m3.md"
assert_eq "1" "$(grep -oF '(este)' "$rot" | grep -c .)" "passo 0: um só \"(este)\""

# =============================================================================================================
# 3. Comandos: só os dois java do pacote; os passos depois da mudança ao vivo não trazem comando novo
# =============================================================================================================
app_line='java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar'
seed_line="$(rc_ps_code "$leia_f" | grep -F 'java -jar app\demo-seed.jar' | head -1)"
assert_eq "$(printf '%s\n%s' "$seed_line" "$app_line" | sort)" "$(grep -E '^java ' <<<"$rot_ps" | grep -vxF 'java -version' | sort)" \
  "blocos PowerShell: além do java -version, só o java do seed e o do app, cada um uma vez"
new_ps="$(for ((n = ${n_live:-$last_step} + 1; n <= last_step; n++)); do rc_ps_code <<<"$(step "$n")"; done |
  grep -vxF 'Set-Location C:\zeny-demo' | grep -vxF "$(grep -E '^docker compose .* down$' <<<"$rot_ps" | head -1)" |
  grep -vxF 'docker compose -p zeny-front down -v')"
assert_eq "" "$new_ps" "passos do M2 e do M3: nenhum comando PowerShell novo"

# =============================================================================================================
# 4. Os passos 0 a 18 são os do M2, salvo a lista fechada de diferenças (7 itens)
# =============================================================================================================
# Itens mecânicos, aplicados ao texto do M2 para dar o esperado:
#   (2) a lista do passo 0: o "(este)" passa do roteiro-m2.md para o roteiro-m3.md (a do passo 1 não muda);
#   (3) a folha: os passos que pedem senha ou PIN ganham os do M3 que citam um item da folha;
#   (4) as remissões ao passo "No fim" ("passo 20" -> "passo 28").
# Itens de texto, cada um numa linha só e no passo certo:
#   (5) passo 5: uma linha a mais, antes do "Rodar o seed de novo";
#   (6) passo 14: a frase do Hóspede Doze deixa de ser "sem reserva";
#   (7) passo 11 (ruling do orquestrador): a frase do Gerente cita também as aprovações dos passos 22 e 25.
# O item (1), título e abertura, é do cabeçalho (seção 5). Qualquer outra linha diferente reprova.
body() { awk '/^## 0\. /{on = 1} /^## 19\. /{exit} on' "$1"; }
m2_folha_said="$(grep -oE 'precisar deles nos passos [0-9]+((, | e )[0-9]+)*' "$rot2" | head -1)"
m3_extra_steps="$(for ((n = 19; n < ${n_sair:-19}; n++)); do step "$n" | grep -qE 'item [0-9]+ da folha' && echo "$n"; done)"
mapfile -t said_nums < <(grep -oE '[0-9]+' <<<"$m2_folha_said"; grep . <<<"$m3_extra_steps")
# "5, 7, 8, ..., 22 e 25": todos com vírgula, menos o último, com "e".
said_list="$(IFS=,; echo "${said_nums[*]:0:${#said_nums[@]}-1}" | sed 's/,/, /g') e ${said_nums[-1]}"
m3_folha_said="precisar deles nos passos $said_list"
assert_contains "$(rc_folha "$rot")" "$m3_folha_said," "folha: o M2 com os passos do M3 que pedem senha ou PIN ($m3_extra_steps)"
[[ -n "$m3_extra_steps" ]] || _assert_fail "algum passo do M3 pede um item da folha (as aprovações)"
expected_body="$(body "$rot2" | sed -E \
  -e 's/`roteiro-m2\.md` \(este\), `roteiro-m3\.md`/`roteiro-m2.md`, `roteiro-m3.md` (este)/' \
  -e "s/$m2_folha_said,/$m3_folha_said,/" \
  -e "s/passo $last2([^0-9])/passo $last_step\\1/g; s/passo $last2\$/passo $last_step/")"
m3_body="$(body "$rot")"
[[ "$(grep -c . <<<"$expected_body")" -gt 300 ]] || _assert_fail "li os passos 0 a 18 do roteiro-m2.md"
changes="$(diff --unchanged-line-format= --old-line-format='-%L' --new-line-format='+%L' \
  <(printf '%s\n' "$expected_body") <(printf '%s\n' "$m3_body"))"
removed="$(grep -E '^-' <<<"$changes" | sed 's/^-//')"
added="$(grep -E '^\+' <<<"$changes" | sed 's/^+//')"
walk_m2="$(grep -F 'Esse hóspede do seed não tem reserva.' <<<"$expected_body")"
ger_m2="$(grep -E '^Do passo 11 ao [0-9]+, quem trabalha é a Recepcionista\.' <<<"$expected_body")"
assert_eq "$(printf '%s\n%s' "$ger_m2" "$walk_m2")" "$removed" \
  "passos 0 a 18: só saem do M2 a frase do Gerente (passo 11) e a do Hóspede Doze (passo 14)"
seed_m3="- As reservas futuras, a cancelada e as pendentes que o seed cria para o M3 não aparecem nesta saída: elas são vistas na área Reservas, nos passos ${n_search:-?} e ${n_pend:-?}."
ger_m3="Do passo ${n_panel:-?} ao ${n_sair:-?}, quem trabalha é a Recepcionista. O Gerente só aprova o desfazer do check-in, no passo ${n_undo:-?}, a diária negociada, no passo ${n_discount:-?}, e a reativação, no passo ${n_react:-?}, e entra numa segunda janela no passo ${n_concurrent:-?}."
walk_m3="${walk_m2% Esse hóspede do seed não tem reserva.} Esse hóspede do seed é o titular das reservas futuras e pendentes do seed, nenhuma delas de hoje: o walk-in com ele vale do mesmo jeito."
assert_eq "$(printf '%s\n' "$ger_m3" "$seed_m3" "$walk_m3" | sort)" "$(sort <<<"$added")" \
  "passos 0 a 18: só entram as três linhas da lista fechada (Gerente, seed e Hóspede Doze)"
ln_of() { grep -nxF -- "$1" "$rot" | head -1 | cut -d: -f1; }
assert_eq "${n_panel:-x}" "$(rc_step_of_line "$rot" "$(ln_of "$ger_m3")")" "a frase do Gerente fica no passo $n_panel"
assert_eq "${n_seed:-x}" "$(rc_step_of_line "$rot" "$(ln_of "$seed_m3")")" "a linha das reservas do M3 fica no passo do seed ($n_seed)"
assert_eq "${n_walkin:-x}" "$(rc_step_of_line "$rot" "$(ln_of "$walk_m3")")" "a frase do Hóspede Doze fica no passo do walk-in ($n_walkin)"
seed_ln="$(ln_of "$seed_m3")"
assert_contains "$(sed -n "$((${seed_ln:-1} + 1))p" "$rot")" "- Rodar o seed de novo é seguro." "a linha das reservas do M3 vem logo antes do seed repetido"
# Os passos em que o Gerente aprova são os que citam "Login de quem aprova"; a segunda janela, a do Login gerente.
appr_steps="$(for ((n = 0; n <= last_step; n++)); do step "$n" | grep -qF 'Em **Login de quem aprova**, digite `gerente`' && echo "$n"; done | tr '\n' ' ')"
assert_eq "$n_undo $n_discount $n_react " "$appr_steps" "o Gerente aprova nos passos do desfazer, da diária negociada e da reativação"

# =============================================================================================================
# 5. Cabeçalho: título, abertura, intervalos renumerados e o aviso da regra 5
# =============================================================================================================
head2="$(awk '/^## / {exit} {print}' "$rot2")"
assert_eq "# Roteiro de demonstração do M3 — reservas do começo ao fim" "$(head -1 "$rot")" "título do roteiro do M3"
opening="$(sed -n 3p "$rot")"
assert_contains "$opening" "Este roteiro mostra o que o M3 entrega" "abertura: o que o M3 entrega"
assert_contains "$opening" "Ele acumula o roteiro do M2" "abertura: acumula o roteiro do M2"
assert_contains "$opening" "e o do M1" "abertura: e o do M1"
# Fora o título, a abertura e o parágrafo do M3, o cabeçalho é o do M2 com os números trocados; o parêntese do que
# os passos mudam cresce com o M3 (comparado sem ele).
norm() { sed -E '1d; 3d; s/ \([^)]*\), e rodar o seed/ (…), e rodar o seed/' | cat -s; }
exp_head="$(norm <<<"$head2" | sed -E "s/passo 11 ao 18/passo 11 ao $((${n_sair:-1} - 1))/; s/passos 11 a 18/passos 11 a $((${n_sair:-1} - 1))/g; s/passo $last2([^0-9])/passo $last_step\\1/g")"
got_head="$(norm <<<"$intro" | grep -vF 'Os passos do M3 (do' | cat -s)"
assert_eq "$exp_head" "$got_head" "cabeçalho: o do M2, com os intervalos e o No fim renumerados"
m3_par="$(grep -F 'Os passos do M3 (do' <<<"$intro")"
assert_contains "$m3_par" "(do $n_search ao $n_pend)" "cabeçalho: os passos do M3 são do $n_search ao $n_pend"
assert_contains "$m3_par" "como diz o aviso das 03:00 no passo 0" "cabeçalho: remete ao aviso da regra 5 no passo 0"
assert_contains "$m3_par" '"Não compareceu"' "cabeçalho: o efeito do fechamento nas chegadas"
assert_contains "$(grep -F 'Os passos 11 a' <<<"$intro")" "reativam reservas e confirmam a pré-reserva do seed" \
  "cabeçalho: o que os passos do M3 mudam nos dados"

# =============================================================================================================
# 6. Seed: a saída igual ao SeedItem; as reservas do M3 fora do relatório
# =============================================================================================================
seed_step="$(step "${n_seed:-x}")"
seed_labels="$(grep -oE '^    [A-Z_]+\("[^"]+"\)' "$report_kt" | sed -E 's/.*\("([^"]+)"\)/\1/')"
seed_out="$(grep -E '^  [^ ].*: [0-9]+ criados, [0-9]+ já existiam$' <<<"$seed_step" | sed -E 's/^  //')"
assert_eq "$seed_labels" "$(sed -E 's/: [0-9]+ criados.*//' <<<"$seed_out")" "saída do seed com os rótulos e a ordem do SeedItem"
cat_txt="$(cat "$catalog_kt")"
n_res="$(grep -cE '^ +r\("[A-Z][0-9]+",' <<<"$cat_txt")"
assert_contains "$seed_out" "reservas: $n_res criados, 0 já existiam" "seed: reservas = só as do DemoCatalog ($n_res)"
assert_eq "" "$(grep -E 'SeedReport|report' "$pending_kt" "$future_kt")" "PendingSeeder e FutureSeeder não escrevem no relatório"
assert_contains "$(cat "$demoseed_kt")" 'FutureSeeder(it).seed(data, seeded, admin.businessDate())' \
  "DemoSeed: o FutureSeeder roda sem o relatório"
assert_contains "$seed_step" "Concluído em " "seed: linha final Concluído em N s."

# =============================================================================================================
# 7. Fatos do seed: titular, tipos, datas e situação das reservas do M3 (PendingSeeder e FutureSeeder)
# =============================================================================================================
name_prefix="$(grep -oE 'fullName = "[^$"]*' "$catalog_kt" | sed 's/^fullName = "//')"
guest_words="$(awk '/val names =/ {on = 1; next} on && /^ +\)$/ {exit} on' "$catalog_kt" | grep -oE '"[^"]+"' | tr -d '"')"
[[ -n "$name_prefix" && "$(grep -c . <<<"$guest_words")" -ge 12 ]] || _assert_fail "li os nomes dos hóspedes do DemoCatalog"
guest_name() { printf '%s%s' "$name_prefix" "$(sed -n "${1}p" <<<"$guest_words")"; }
type_name() { grep -oE "RoomTypeSpec\\(\"$1\", \"[^\"]+\"" "$catalog_kt" | sed -E 's/.*, "([^"]+)"$/\1/'; }
max_adults() { grep -oE "RoomTypeSpec\\(\"$1\", \"[^\"]+\", [0-9]+, [0-9]+, [0-9]+, [0-9]+" "$catalog_kt" | grep -oE '[0-9]+$'; }
status_label() { grep -oE "\"$1\" to \"[^\"]+\"" "$app_rec/ReceptionText.kt" | head -1 | sed -E 's/.* to "([^"]+)"/\1/'; }
const_of() { grep -oE "const val $2 = \"?[A-Za-z0-9_ ()-]+\"?L?" "$1" | sed -E 's/.* = "?//; s/"?L?$//'; }

holder_p="$(const_of "$pending_kt" HOLDER_KEY)"
holder_f="$(const_of "$future_kt" HOLDER_KEY)"
assert_eq "$holder_p" "$holder_f" "PendingSeeder e FutureSeeder: o mesmo titular"
holder_n="${holder_f#G}"
holder="$(guest_name "$holder_n")"
assert_eq "${name_prefix}Doze" "$holder" "o titular das reservas do M3 é o $holder_f, \"Hóspede Demonstração Doze\""
# As reservas do seed do M3: externalId situação tipo chegada saída (dias contados da data operacional).
plans="$(grep -oE 'Plan\("DEMO-FUT-[A-Z0-9]+", "[A-Z]+", [0-9]+, [0-9]+, "[A-Z_]+", cancelAfterCreate = (true|false)\)' "$future_kt" |
  sed -E 's/Plan\("([^"]+)", "([A-Z]+)", ([0-9]+), ([0-9]+), "[A-Z_]+", cancelAfterCreate = (true|false)\)/\1 \5 \2 \3 \4/' |
  awk '{print $1, ($2 == "true" ? "CANCELLED" : "CONFIRMED"), $3, $4, $5}')"
assert_eq "3" "$(grep -c . <<<"$plans")" "li as três reservas do FutureSeeder"
fn_body() { awk -v f="$2" 'index($0, "private suspend fun " f "(") {on = 1} on {print} on && /^    }$/ {exit}' "$1"; }
type_in() { if grep -qF 'roomTypeId = resolved.standardTypeId' <<<"$1"; then const_of "$pending_kt" STANDARD; elif grep -qF 'roomTypeId = resolved.luxuryTypeId' <<<"$1"; then const_of "$pending_kt" LUXURY; fi; }
pre_body="$(fn_body "$pending_kt" seedPreBooking)"
res_body="$(fn_body "$pending_kt" seedReservation)"
room208="$(const_of "$pending_kt" ROOM_NUMBER)"
seed_res="$(printf '%s\n' "$plans"
  echo "$(const_of "$pending_kt" PRE_BOOKING_ID) PROVISIONAL $(type_in "$pre_body") $(const_of "$pending_kt" PRE_BOOKING_ARRIVAL_DAY) $(const_of "$pending_kt" PRE_BOOKING_DEPARTURE_DAY)"
  echo "$(const_of "$pending_kt" RESERVATION_ID) CONFIRMED $(type_in "$res_body") $(const_of "$pending_kt" RESERVATION_ARRIVAL_DAY) $(const_of "$pending_kt" RESERVATION_DEPARTURE_DAY)")"
[[ "$(awk 'NF != 5' <<<"$seed_res" | grep -c .)" == 0 && "$(grep -c . <<<"$seed_res")" == 5 ]] ||
  _assert_fail "li as cinco reservas do seed do M3" "$seed_res"
# A pré-reserva é a do plano com garantia por cartão, vendido só no site.
assert_contains "$pre_body" "ratePlanId = planId" "a P1 é criada no plano do PendingSeeder"
assert_contains "$(cat "$adapter_kt")" "GuaranteePolicyRequest.Type.CARD" "a garantia do plano é por cartão"
assert_contains "$(cat "$adapter_kt")" "channels = listOf(RatePlanCreateRequest.ChannelsItem.WEBSITE)" "o plano é vendido só no site"
# Nenhuma reserva do seed do M3 chega hoje: o walk-in do passo 14 com o titular delas vale.
assert_eq "" "$(awk '$4 == 0' <<<"$seed_res")" "nenhuma reserva do seed do M3 chega na data operacional"

# 7.1 O walk-in do passo 14 é com o titular das reservas do M3, que não é titular de nenhuma do DemoCatalog.
walkin="$(step "${n_walkin:-x}")"
assert_contains "$walkin" "aparece \"$holder\"" "walk-in: o hóspede escolhido é o titular das reservas do M3"
assert_eq "" "$(grep -E "^ +r\(\"[A-Z][0-9]+\", $holder_n, " "$catalog_kt")" "walk-in: o $holder_f não é titular de reserva do DemoCatalog"
wk_type="$(grep -oE 'na oferta [[:alpha:]]+' <<<"$walkin" | head -1 | awk '{print $3}')"
wk_nights="$(grep -oE 'data operacional mais [0-9]+ dias' <<<"$walkin" | head -1 | grep -oE '[0-9]+')"
[[ -n "$wk_type" && -n "$wk_nights" ]] || _assert_fail "walk-in: li o tipo e as noites" "tipo: $wk_type; noites: $wk_nights"

# 7.2 A tabela do 19.2: as reservas do titular, em ordem de chegada, com a situação, o tipo e as datas do código.
search="$(step "${n_search:-x}")"
want_rows="$( {
  echo "0 $(status_label IN_HOUSE)|$wk_type|$(day 0)|$(day "${wk_nights:-0}")"
  while read -r id st ty a d; do
    [[ -n "$id" ]] || continue
    echo "$a $(status_label "$st")|$(type_name "$ty")|$(day "$a")|$(day "$d")"
  done <<<"$seed_res"
} | sort -n | cut -d' ' -f2-)"
got_rows="$(grep -E '^\| [^|]+ \| [^|]+ \| [^|]+ \| [^|]+ \| [^|]+ \|$' <<<"$search" | grep -vE '^\| (Situação|---)' |
  awk -F' \\| ' '{sub(/^\| /, "", $1); print $1 "|" $2 "|" $3 "|" $4}')"
assert_eq "$want_rows" "$got_rows" "19.2: situação, tipo, chegada e saída de cada reserva do titular, em ordem de chegada"
n_rows="$(grep -c . <<<"$want_rows")"
assert_contains "$search" "a tabela mostra $(fem "$n_rows") reservas, todas de \"$holder\"" "19.2: o número de reservas e o titular"
row_of() { grep -E "^\| [^|]+ \| [^|]+ \| $(day "$1") \|" <<<"$search"; }
assert_contains "$(row_of 0)" "walk-in do passo $n_walkin" "19.2: a linha de hoje é o walk-in do passo $n_walkin"
r1_a="$(awk -v r="$(const_of "$pending_kt" RESERVATION_ID)" '$1 == r {print $4}' <<<"$seed_res")"
c1_line="$(grep -E '^DEMO-FUT-[A-Z0-9]+ CANCELLED ' <<<"$seed_res")"
read -r _ _ c1_type c1_a c1_d <<<"$c1_line"
assert_contains "$(row_of "${r1_a:-x}")" "UH $room208" "19.2: a reserva do seed na UH $room208"
assert_contains "$(row_of "${c1_a:-x}")" "cancelada sem multa" "19.2: a cancelada do seed, sem multa"
# Sem multa: a política cobra a partir de deadlineHours antes do check-in, e a C1 é cancelada dias antes.
deadline_h="$(grep -oE 'deadlineHours = [0-9]+' "$catalog_kt" | grep -oE '[0-9]+')"
((${c1_a:-0} * 24 > ${deadline_h:-0})) || _assert_fail "a C1 é cancelada fora do prazo da multa (chegada em d+$c1_a, prazo $deadline_h h)"
assert_contains "$search" 'A linha "Pré-reserva" só aparece numa demo recriada no dia' "19.2: a pré-reserva só na demo do dia"
assert_contains "$search" "vence 48 h depois de criada" "19.2: a pré-reserva vence em 48 h"
assert_contains "$search" "A busca por documento não usa o período" "19.2: a busca por documento ignora o período"
assert_contains "$(cat "$app_res/ReservationSearchState.kt")" \
  'runQuery(Query.ByDocument(query, knownStatuses()))' "código: a busca por documento leva só as situações, sem o período"
assert_contains "$search" "a busca por documento também filtra pelas situações marcadas" "19.1: desmarcar a situação antes do 19.2"

# 7.3 O CPF do titular, recalculado como o FictitiousDocuments o calcula, e a máscara da tela.
docs_txt="$(cat "$docs_kt")"
for frag in 'val d1 = check(base9, 10)' 'val d2 = check(base9 + d1, 11)' '(firstWeight - i)' 'MODULUS = 11' \
  'MIN_WEIGHTED_REMAINDER = 2' 'if (it < MIN_WEIGHTED_REMAINDER) 0 else MODULUS - it'; do
  assert_contains "$docs_txt" "$frag" "FictitiousDocuments: o algoritmo recalculado aqui ($frag)"
done
cpf_prefix="$(grep -oE 'cpf = FictitiousDocuments\.cpf\("[0-9]+\$\{n\.toString\(\)\.padStart\(2, .0.\)\}"\)' "$catalog_kt" |
  grep -oE '"[0-9]+' | tr -d '"')"
[[ "${#cpf_prefix}" == 7 ]] || _assert_fail "li a base do CPF dos hóspedes no DemoCatalog" "achei: $cpf_prefix"
cpf_of() {
  local b="$1" s=0 i r d1 d2
  for ((i = 0; i < 9; i++)); do s=$((s + ${b:i:1} * (10 - i))); done
  r=$((s % 11)); if ((r < 2)); then d1=0; else d1=$((11 - r)); fi
  b="$b$d1"; s=0
  for ((i = 0; i < 10; i++)); do s=$((s + ${b:i:1} * (11 - i))); done
  r=$((s % 11)); if ((r < 2)); then d2=0; else d2=$((11 - r)); fi
  echo "$b$d2"
}
cpf="$(cpf_of "$cpf_prefix$(printf '%02d' "$holder_n")")"
assert_contains "$search" "\"$holder\", criado pelo seed: \`$cpf\`" "19.2: o CPF digitado é o do $holder_f, recalculado ($cpf)"
hidden="$(grep -oE 'HIDDEN = "[^"]+"' "$app_res/ReservationSearchText.kt" | sed -E 's/.*"([^"]+)"/\1/')"
visible="$(grep -oE 'VISIBLE_DOCUMENT_CHARS = [0-9]+' "$app_res/ReservationSearchText.kt" | grep -oE '[0-9]+$')"
cpf_label="$(grep -oE '"CPF" to "[^"]+"' "$app_rec/ReceptionText.kt" | head -1 | sed -E 's/.* to "([^"]+)"/\1/')"
masked="$cpf_label $hidden${cpf: -${visible:-4}}"
assert_contains "$search" "\"Reservas do documento $masked\"" "19.2: a linha do documento mascarado como a tela a monta ($masked)"
assert_contains "$(cat "$app_res/ReservationsScreen.kt")" 'Text("Reservas do documento $it"' "código: a linha do documento buscado"
# Só esse número de 11 dígitos aparece no roteiro, uma vez: nenhum outro documento por extenso.
assert_eq "$cpf" "$(grep -oE '(^|[^0-9])[0-9]{11}([^0-9]|$)' "$rot" | grep -oE '[0-9]{11}' | sort -u)" "o roteiro só traz o CPF fictício do titular"
assert_eq "1" "$(grep -oF "$cpf" "$rot" | grep -c .)" "o CPF fictício aparece uma vez, no 19.2"
assert_eq "${n_search:-x}" "$(rc_step_of_line "$rot" "$(grep -nF "$cpf" "$rot" | head -1 | cut -d: -f1)")" "o CPF fictício só no passo da busca"

# 7.4 O detalhe da cancelada (19.3), a reativação (25) e o seed interrompido (No fim): a C1 do código.
detail="$(awk '/^\*\*19\.3\. /{on = 1} /^\*\*Reprovação/{exit} on' <<<"$search")"
assert_contains "$detail" "chegada na data operacional mais $c1_a dias" "19.3: a cancelada chega em d+$c1_a"
assert_contains "$detail" "\"$((c1_d - c1_a)) noites\"" "19.3: a cancelada tem $((c1_d - c1_a)) noites"
assert_contains "$detail" "\"Tipo\", $(type_name "$c1_type")" "19.3: o tipo da cancelada"
assert_contains "$detail" "\"Titular\", \"$holder\"" "19.3: o titular da cancelada"
react="$(step "${n_react:-x}")"
assert_contains "$react" "em **De** a data operacional mais $c1_a dias" "25: o filtro começa na chegada da C1"
assert_contains "$react" "em **Até** mais $c1_d dias" "25: o filtro termina na saída da C1"
assert_contains "$react" "clique na situação **Cancelada**" "25: o filtro Cancelada"
assert_contains "$react" "de \"$holder\", com a chegada na data operacional mais $c1_a dias" "25: a linha achada é a C1"
assert_contains "$react" "Clique de novo em **Cancelada**, para desmarcar." "25: desmarca a situação no fim"
fim="$(step "$last_step")"
interrupted="$(grep -F '**Se o seed foi interrompido no meio**' <<<"$fim")"
assert_contains "$interrupted" "nenhuma reserva \"Cancelada\" do \"$holder\" com a chegada na data operacional mais $c1_a dias" \
  "No fim: o sintoma do seed interrompido é a C1 (d+$c1_a) sem cancelar"
assert_contains "$interrupted" "faça o \"Recomeçar do zero\"" "No fim: o seed interrompido manda recomeçar do zero"
assert_contains "$interrupted" "no 19.2" "No fim: o sintoma é visto no 19.2"
assert_contains "$(cat "$future_kt")" 'if (plan.cancelAfterCreate) gateway.cancelReservation(ref.id, CANCEL_REASON)' \
  "código: só a reserva criada nesta execução é cancelada (o sintoma descrito)"

# 7.5 Pendências (26): a pré-reserva nas garantias e a reserva da UH 208 na realocação, com as datas do código.
pend="$(step "${n_pend:-x}")"
p1_a="$(awk -v r="$(const_of "$pending_kt" PRE_BOOKING_ID)" '$1 == r {print $4}' <<<"$seed_res")"
r1_d="$(awk -v r="$(const_of "$pending_kt" RESERVATION_ID)" '$1 == r {print $5}' <<<"$seed_res")"
guar="$(grep -F -- '- **Garantias pendentes:**' <<<"$pend")"
dep="$(grep -F -- '- **Depósitos em falta:**' <<<"$pend")"
nos="$(grep -F -- '- **No-shows previstos:**' <<<"$pend")"
realloc="$(grep -F -- '- **Realocação necessária:**' <<<"$pend")"
assert_contains "$guar" "de \"$holder\" (chegada na data operacional mais $p1_a dias)" "26: a pré-reserva do titular, chegada d+$p1_a"
assert_contains "$guar" '"Pré-reserva · Cartão"' "26: a situação da garantia pendente"
assert_contains "$(cat "$app_res/PendingText.kt")" '"PROVISIONAL" to "Pré-reserva"' "código: Pré-reserva na lista de garantias"
assert_contains "$(cat "$app_res/BookingText.kt")" '"CARD" to "Cartão"' "código: Cartão como rótulo da garantia"
assert_contains "$guar" "48 h depois de criada" "26: a pré-reserva vence em 48 h"
assert_contains "$guar" "numa demo recriada no dia" "26: a pré-reserva só na demo do dia"
assert_contains "$dep" 'vazia, com "Nenhum depósito em falta."' "26: depósitos em falta vazia"
assert_contains "$dep" "só chega no M4" "26: o depósito na conta é do M4"
assert_contains "$nos" "às 03:00" "26: os no-shows previstos somem às 03:00"
assert_contains "$nos" "\"Não compareceu\"" "26: viram Não compareceu"
assert_contains "$nos" "voltou a confirmada no passo $n_undo" "26: a chegada do check-in desfeito ($n_undo)"
assert_contains "$nos" "A reserva cancelada no passo $n_cancel não entra" "26: a cancelada do passo $n_cancel não entra"
assert_contains "$realloc" "UH $room208" "26: a reserva da UH $room208"
assert_contains "$realloc" "da data operacional mais $r1_a dias à data operacional mais $r1_d dias, com \"$((r1_d - r1_a)) noites\"" \
  "26: a realocação achada pelo período da R1 (d+$r1_a a d+$r1_d)"
assert_contains "$realloc" "\"UH $room208 fora de ordem na estadia\"" "26: a situação da realocação"
assert_contains "$(cat "$app_res/PendingText.kt")" 'val detail = "UH ${number ?: "alocada"} fora de ordem na estadia"' \
  "código: o texto da realocação"
assert_contains "$realloc" 'a coluna "Titular" mostra "—"' "26: a realocação sem titular"
assert_contains "$(fn_body "$app_res/PendingListsState.kt" reallocations)" 'withHolders(source.reallocationRequired(cursor, limit), { null })' \
  "código: a lista de realocação não lê o titular"
assert_contains "$(cat "$app_res/PendingListsState.kt")" 'private const val UNKNOWN = "—"' "código: sem titular, \"—\""
assert_contains "$realloc" "clique em **Atualizar**" "26: a realocação pode precisar de Atualizar"
assert_contains "$pend" '"Nenhuma garantia pendente."' "26: depois de confirmar, a lista de garantias vazia"
assert_contains "$pend" '"Cartão" marcado' "26: a confirmação com Cartão"
# A busca da tela pagina e o roteiro não promete a página seguinte.
for p in 'Carregar mais' 'página seguinte' 'próxima página' 'Próxima página' 'mais resultados'; do
  assert_not_contains "$rot_text" "$p" "o roteiro não promete a página seguinte ($p)"
done

# 7.6 O filtro Hospedada (19.1): a situação começa pelo rótulo e pode trazer alertas depois de " · ", como a tabela
# a monta (ReservationRows.statusText); o exemplo é um alerta que o seed de fato produz hoje (day use hospedado).
rows_kt="$app_rec/ReservationRows.kt"
status_fn="$(awk '/fun ReservationListItemResponse\.statusText\(\)/{on = 1} on {print} on && /joinToString/ {exit}' "$rows_kt")"
status_sep="$(grep -oE 'joinToString\("[^"]+"\)' <<<"$status_fn" | sed -E 's/joinToString\("([^"]+)"\)/\1/')"
alert_labels="$(grep -oE '^ +"[^"]+"\.takeIf' <<<"$status_fn" | sed -E 's/^ +"([^"]+)".*/\1/')"
assert_contains "$status_fn" 'ReceptionText.status(status).label,' "código: a situação vem primeiro no rótulo da linha"
[[ -n "$status_sep" && "$(grep -c . <<<"$alert_labels")" -ge 1 ]] || _assert_fail "li o separador e os alertas do statusText" "$status_fn"
in_house="$(status_label IN_HOUSE)"
s191="$(awk '/^\*\*19\.1\. /{on = 1} /^\*\*19\.2\. /{exit} on' <<<"$search")"
assert_contains "$s191" "a situação de toda linha começa por \"$in_house\"" "19.1: a situação começa pelo rótulo"
assert_contains "$s191" "separado por \"$status_sep\"" "19.1: o separador do alerta é o do statusText ($status_sep)"
example="$(grep -oE "\\(por exemplo, \"$in_house$status_sep[^\"]+\"\\)" <<<"$s191" | sed -E "s/.*\"$in_house$status_sep([^\"]+)\".*/\\1/")"
[[ -n "$example" ]] || _assert_fail "19.1: o exemplo de alerta é \"$in_house$status_sep<alerta>\""
grep -qxF -- "$example" <<<"$alert_labels" || _assert_fail "19.1: o alerta do exemplo ($example) é um dos do statusText" "$alert_labels"
if [[ "$example" == "Day use" ]]; then
  assert_contains "$status_fn" '"Day use".takeIf { dayUse },' "código: Day use é o alerta da reserva de day use"
  [[ -n "$(grep -E '^ +r\("[A-Z][0-9]+", [0-9]+, "[A-Z]+", 0, [0-9]+, "[0-9]{3}", true\)' "$catalog_kt")" ]] ||
    _assert_fail "19.1: o seed tem um day use hospedado hoje (o exemplo aparece na demo)"
fi
assert_contains "$s191" "isso não é reprovação" "19.1: o alerta não é reprovação"
assert_contains "$search" "o filtro \"$in_house\" mostra linha cuja situação não começa por \"$in_house\" (um alerta depois de \"$status_sep\" é normal)" \
  "19: a reprovação do filtro admite o alerta"

# =============================================================================================================
# 8. Disponibilidade e cotação (20) contra o catálogo, o PendingSeeder e o código
# =============================================================================================================
quote="$(step "${n_quote:-x}")"
q1="$(awk '/^\*\*20\.1\. /{on = 1} /^\*\*20\.2\. /{exit} on' <<<"$quote")"
q2="$(awk '/^\*\*20\.2\. /{on = 1} /^\*\*Reprovação/{exit} on' <<<"$quote")"
q_adults="$(grep -oE 'troque o 2 por `[0-9]+`' <<<"$q1" | grep -oE '[0-9]+`$' | tr -d '`')"
plan_types="$(grep -oE 'roomTypeCodes = listOf\([^)]*\)' "$catalog_kt" | grep -oE '"[A-Z]+"' | tr -d '"')"
plan_name="$(awk '/ratePlan =/{on = 1} on' "$catalog_kt" | grep -oE 'name = "[^"]+"' | head -1 | sed -E 's/name = "([^"]+)"/\1/')"
meal="$(awk '/ratePlan =/{on = 1} on' "$catalog_kt" | grep -oE 'mealPlan = "[A-Z_]+"' | head -1 | grep -oE '"[A-Z_]+"' | tr -d '"')"
meal_label="$(grep -oE "\"$meal\" to \"[^\"]+\"" "$app_res/AvailabilityText.kt" | sed -E 's/.* to "([^"]+)"/\1/')"
garantida="$(const_of "$pending_kt" PLAN_CODE)"
garantida_type="$(type_in "$(fn_body "$pending_kt" seedPlan)")"
assert_eq "$(const_of "$pending_kt" STANDARD)" "$garantida_type" "o plano $garantida do seed é só da Standard"
# Linhas indisponíveis com q_adults: uma por plano que vende o tipo, quando o tipo não recebe q_adults.
for t in $(printf '%s\n' "$plan_types" | sort -u); do
  n_lines=0
  grep -qxF "$t" <<<"$plan_types" && n_lines=$((n_lines + 1))
  [[ "$t" == "$garantida_type" ]] && n_lines=$((n_lines + 1))
  name="$(type_name "$t")"
  if ((q_adults > $(max_adults "$t"))); then
    assert_contains "$q1" "$(fem "$n_lines") $( ((n_lines == 1)) && echo "que começa" || echo "linhas que começam") por \"$name ·\"" \
      "20.1: $(fem "$n_lines") linha(s) $name em Indisponíveis ($q_adults adultos > $(max_adults "$t"))"
    assert_contains "$q1" "o tipo de UH recebe até $(max_adults "$t") adultos" "20.1: o motivo da $name"
  else
    assert_contains "$q1" "A $name pode aparecer como oferta" "20.1: a $name recebe $q_adults adultos e pode ser oferta"
  fi
done
for q in "$q1" "$q2"; do
  assert_contains "$q" "\"$(type_name "$garantida_type") · $garantida\"" "20: a linha do plano $garantida, mostrado pelo código"
  assert_contains "$q" '"O plano não é vendido neste canal."' "20: o motivo do canal"
  assert_contains "$q" "isso é o esperado, não é reprovação" "20: a linha $garantida não é reprovação"
done
assert_contains "$(cat "$app_res/QuotePanel.kt")" '?.ratePlanName ?: item.ratePlanCode' "código: sem oferta do plano, a tela mostra o código"
assert_contains "$(cat "$app_res/AvailabilityText.kt")" '"O plano não é vendido neste canal."' "código: o motivo do canal em português"
assert_contains "$(cat "$facade_kt")" 'channel = QuoteStayParamChannel.DIRECT' "código: a cotação é do canal DIRECT, fora do $garantida (só WEBSITE)"
offer="$(type_name STD) · $plan_name · $meal_label"
assert_contains "$q2" "\"$offer\"" "20.2: a oferta Standard com o plano e a refeição do catálogo"
assert_contains "$q2" "uma linha por noite, três ao todo" "20.2: as três noites"
for n in "$n_new" "$n_discount" "$n_cancel"; do
  assert_contains "$(step "${n:-x}")" "**Reservar** na oferta \"$offer\"" "passo $n: reserva na oferta do catálogo"
done
# O calendário de 30 dias e o período padrão da busca, do código.
assert_contains "$(cat "$app_res/AvailabilityState.kt")" 'private const val DEFAULT_DAYS = 30' "código: o calendário tem 30 dias"
assert_contains "$quote" "e 30 colunas, da data operacional em diante" "20: o calendário de 30 colunas"
assert_contains "$(cat "$app_res/ReservationSearchState.kt")" 'private const val DEFAULT_PERIOD_DAYS = 30L' "código: o período padrão da busca"
assert_contains "$search" '"Até" (já com a data operacional mais 30 dias)' "19: o Até padrão"
assert_contains "$quote" "uma linha por tipo de UH (\"$(type_name LUX)\", \"$(type_name STD)\" e \"$(type_name SUI)\")" "20: os três tipos do catálogo"

# =============================================================================================================
# 9. Datas: os exemplos batem com a data operacional de exemplo e cabem nos preços do seed
# =============================================================================================================
base="$(grep -oE 'Os exemplos usam a data operacional [0-9]{2}/[0-9]{2}/[0-9]{4}' "$rot" | grep -oE '[0-9/]{10}')"
[[ -n "$base" ]] || _assert_fail "o roteiro declara a data operacional dos exemplos"
iso_base="${base:6:4}-${base:3:2}-${base:0:2}"
pairs="$(grep -oE 'mais [0-9]+ dias?(; no exemplo, | \(no exemplo, `| \(`)[0-9]{2}/[0-9]{2}/[0-9]{4}' "$rot")"
(($(grep -c . <<<"$pairs") >= 9)) || _assert_fail "li os exemplos de data do M3" "$pairs"
while IFS= read -r p; do
  [[ -n "$p" ]] || continue
  n="$(grep -oE '^mais [0-9]+' <<<"$p" | grep -oE '[0-9]+')"
  got="$(grep -oE '[0-9]{2}/[0-9]{2}/[0-9]{4}$' <<<"$p")"
  assert_eq "$(date -d "$iso_base + $n days" +%d/%m/%Y)" "$got" "exemplo de data: $p"
done <<<"$pairs"
wd=(x seg ter qua qui sex sáb dom)
assert_contains "$quote" "(como \"${wd[$(date -d "$iso_base" +%u)]} ${base:0:5}\")" "20: o cabeçalho do dia de exemplo (dayHeader, pt-BR)"
assert_contains "$q2" "(como \"$(date -d "$iso_base + 30 days" +%d/%m) R\$ …\")" "20.2: a primeira noite da cotação de exemplo"
price_days="$(grep -oE 'HIGH_END = [0-9]+' "$catalog_kt" | grep -oE '[0-9]+')"
for ((n = ${n_search:-19}; n <= ${n_pend:-0}; n++)); do
  for d in $(step "$n" | grep -oE 'mais [0-9]+ dias?' | grep -oE '[0-9]+' | sort -un); do
    ((d <= price_days)) || _assert_fail "passo $n: o dia d+$d cabe nos preços do seed (até d+$price_days)"
  done
done

# =============================================================================================================
# 10. Nova reserva, desconto, alteração e cancelamento: titular por nome, noites e o hospedado estendido
# =============================================================================================================
# Todo termo de busca por nome digitado acha um só hóspede do catálogo, o que o roteiro diz que aparece.
all_names="$(for ((i = 1; i <= $(grep -c . <<<"$guest_words"); i++)); do guest_name "$i"; echo; done)"
terms="$(grep -oE '(no campo \*\*Nome\*\*, digite|em \*\*Nome do titular ou hóspede\*\* digite) `[^`]+`|\(`[A-Za-z]+`, \*\*Buscar\*\*' "$rot" |
  grep -oE '`[^`]+`' | tr -d '`' | sort -u)"
[[ -n "$terms" ]] || _assert_fail "li os termos de busca por nome"
for t in $terms; do
  hits="$(grep -iF -- "$t" <<<"$all_names")"
  assert_eq "1" "$(grep -c . <<<"$hits")" "a busca por nome [$t] acha um só hóspede do catálogo ($hits)"
  assert_contains "$rot_text" "\"$hits\"" "o roteiro diz que a busca [$t] acha \"$hits\""
done
new="$(step "${n_new:-x}")"
assert_contains "$new" '"3 noites"' "21: a reserva de 3 noites"
assert_contains "$new" "\"$(guest_name 1)\"" "21: o titular é o Hóspede Um"
cancel_terms="Cancelamento sem multa até $deadline_h h antes do check-in; depois, multa de $(grep -oE 'penaltyNights = [0-9]+' "$catalog_kt" | grep -oE '[0-9]+') diária(s)."
assert_contains "$new" "\"$cancel_terms\"" "21: os termos da política do catálogo"
assert_contains "$(step "${n_cancel:-x}")" "\"$cancel_terms\"" "24: os mesmos termos antes de cancelar"
disc="$(step "${n_discount:-x}")"
assert_contains "$disc" 'o período de "1 noite"' "22: uma noite (d+35 a d+36)"
assert_contains "$disc" "o PIN do Gerente (item" "22: o PIN do Gerente vem da folha"
assert_contains "$disc" "o mesmo do passo $n_undo" "22: o mesmo PIN do desfazer"
assert_contains "$disc" "**Resultado esperado:** uma linha só" "22: conferida uma vez só na busca"
chg="$(step "${n_change:-x}")"
c231="$(awk '/^\*\*23\.1\. /{on = 1} /^\*\*23\.2\. /{exit} on' <<<"$chg")"
c232="$(awk '/^\*\*23\.2\. /{on = 1} /^\*\*Reprovação/{exit} on' <<<"$chg")"
assert_contains "$c231" "o código anotado no passo $n_new" "23.1: altera a reserva do passo $n_new"
assert_contains "$c231" "data operacional mais 34 dias" "23.1: a saída passa a d+34 (um dia depois de d+33)"
assert_contains "$c231" '"4 noites"' "23.1: quatro noites depois da alteração"
assert_contains "$c231" "maior que o do passo $n_new" "23.1: o total novo maior"
# O hospedado estendido: um do DemoCatalog com check-in e noite restante, que os passos 11 a 18 não tocam.
res="$(grep -E '^ +r\("[A-Z][0-9]+",' "$catalog_kt" | sed -E 's/, null, /, "-", /' |
  sed -E 's/^ +r\("([A-Z][0-9]+)", ([0-9]+), "([A-Z]+)", ([0-9]+), ([0-9]+), "([0-9]{3}|-)", (true|false)\),?$/\1 \2 \3 \4 \5 \6 \7/')"
ext_room="$(grep -oE 'O hóspede da UH [0-9]{3}' <<<"$c232" | grep -oE '[0-9]{3}')"
ext="$(awk -v r="$ext_room" '$6 == r' <<<"$res")"
read -r _ ext_g ext_t ext_nights _ _ ext_ci <<<"$ext"
assert_eq "true" "${ext_ci:-}" "23.2: o hóspede da UH $ext_room está hospedado no seed"
assert_contains "$c232" "\"$(guest_name "${ext_g:-0}")\" (UH $ext_room)" "23.2: o nome é o do titular da UH $ext_room no catálogo"
assert_contains "$c232" "a \"Saída\" na data operacional mais $ext_nights dias" "23.2: a saída do seed (d+$ext_nights)"
assert_contains "$c232" "data operacional mais $((ext_nights + 1)) dias" "23.2: a saída nova, um dia depois"
untouched="$(for ((n = n_panel; n < n_search; n++)); do step "$n"; done)"
assert_not_contains "$untouched" "$(guest_name "${ext_g:-0}")" "23.2: os passos 11 a 18 não tocam o hóspede estendido"
assert_not_contains "$untouched" "UH $ext_room" "23.2: os passos 11 a 18 não tocam a UH $ext_room"
assert_contains "$(step "${n_cancel:-x}")" "em **Chegada**, digite a data operacional (no exemplo," "24: a reserva cancelada chega hoje"
# O que o roteiro diz que vem marcado é o padrão do código: a primeira origem, o primeiro segmento e o primeiro
# motivo de cancelamento; os motivos na ordem da tela.
bt="$(cat "$app_res/BookingText.kt")"
label_in() { grep -oE "\"$2\" to \"[^\"]+\"" <<<"$1" | head -1 | sed -E 's/.* to "([^"]+)"/\1/'; }
first_of() { grep -oE "val $1: List<String> = listOf\(\"[A-Z_]+\"" <<<"$bt" | grep -oE '"[A-Z_]+"$' | tr -d '"'; }
assert_contains "$new" "\"Origem\" (\"$(label_in "$bt" "$(first_of ORIGINS)")\" marcado)" "21: a origem marcada é a padrão do código"
assert_contains "$new" "\"Segmento\" (\"$(label_in "$bt" "$(first_of SEGMENTS)")\" marcado)" "21: o segmento marcado é o padrão do código"
reasons="$(grep -oE '^ +"[A-Z_]+" to "[^"]+",' "$app_res/ReservationCancelState.kt" | sed -E 's/.* to "([^"]+)",/\1/')"
want_reasons="(\"$(head -1 <<<"$reasons")\" marcado, $(tail -n +2 <<<"$reasons" | sed 's/.*/"&"/' | paste -sd, - | sed 's/,/, /g; s/, \([^,]*\)$/ e \1/'))"
assert_contains "$(step "${n_cancel:-x}")" "o \"Motivo\" $want_reasons" "24: os motivos na ordem da tela, o primeiro marcado"
assert_contains "$(cat "$app_res/ReservationCancelState.kt")" 'private var reasonValue by mutableStateOf(CANCEL_REASONS.first().first)' \
  "código: o primeiro motivo vem marcado"
assert_contains "$(cat "$app_res/NewReservationState.kt")" 'mutableStateOf(BookingText.ORIGINS.first())' "código: a primeira origem vem marcada"
assert_contains "$(cat "$app_res/NewReservationState.kt")" 'mutableStateOf(BookingText.SEGMENTS.first())' "código: o primeiro segmento vem marcado"

# =============================================================================================================
# 11. Aprovações: login gerente e o PIN do item certo da folha, nos passos 16, 22 e 25
# =============================================================================================================
rows="$(rc_folha_rows "$rot")"
i_pin="$(rc_folha_item "$rows" 'PIN do Gerente')"
for n in "$n_discount" "$n_react"; do
  s="$(step "${n:-x}")"
  l="$(grep -E 'Em \*\*Login de quem aprova\*\*, digite' <<<"$s")"
  assert_eq "1" "$(grep -c . <<<"$l")" "passo $n: uma aprovação"
  assert_contains "$l" 'digite `gerente`' "passo $n: quem aprova é o gerente"
  assert_eq "$i_pin" "$(grep -oE 'item [0-9]+ da folha' <<<"$l" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "passo $n: a aprovação aponta o item do PIN ($i_pin), e só ele"
  assert_contains "$s" '"Aprovação de superior"' "passo $n: o diálogo de aprovação"
  assert_contains "$s" "o PIN aparece na tela ou numa mensagem" "passo $n: o PIN na tela é reprovação"
done
assert_contains "$(step "${n_react:-x}")" "a Recepcionista não reativa sozinha" "25: a reativação pede a aprovação"

# =============================================================================================================
# 12. Rótulos e textos citados nos passos do M3 existem no código do app, do design ou do core
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
m3_text="$(for ((n = ${n_search:-19}; n <= ${n_pend:-0}; n++)); do step "$n"; done)"
type_names="$(grep -oE 'RoomTypeSpec\("[A-Z]+", "[^"]+"' "$catalog_kt" | sed -E 's/.*, "([^"]+)"$/\1/')"
bad_quotes=""
while IFS= read -r q; do
  [[ -n "$q" ]] || continue
  p="${q:1:${#q}-2}"
  if grep -qF -- "\"$p\"" "$code_f" || tpl_in_code "$p"; then continue; fi
  # Um pedaço de mensagem citado na prosa ("Alterou:", "cancelada", "Disponíveis"): está dentro de um literal.
  if [[ "$p" != *[[:space:]]* ]] && grep -qE -- "\"([^\"]*[ (])?$p[ .$][^\"]*\"" "$code_f"; then
    continue
  fi
  case "$p" in
    "$name_prefix"*) grep -qxF -- "$p" <<<"$all_names" && continue ;;
    "$offer" | "$(type_name "$garantida_type") · $garantida") continue ;;
    "Standard ·" | "Luxo ·") grep -qxF -- "${p% ·}" <<<"$type_names" && continue ;;
    "Luxo" | "Standard" | "Suíte") grep -qxF -- "$p" <<<"$type_names" && continue ;;
    "1 noite" | [0-9]" noites") grep -qF 'val noun = if (nights == 1L) "noite" else "noites"' "$code_f" && continue ;;
    "Página 1 de N") grep -qF 'Text("Página ${document.page + 1} de ${document.pageCount}")' "$code_f" && continue ;;
    "${wd[$(date -d "$iso_base" +%u)]} ${base:0:5}") grep -qF 'return "$weekday $day/$month"' "$code_f" && continue ;;
    *" R\$ …") grep -qF 'return "$day ${ReceptionText.money(night.total)}"' "$code_f" && continue ;;
    "Confirmação da reserva — R-…") grep -qF '"Confirmação da reserva — ",' "$code_f" && continue ;;
    "Reservas do documento $masked") continue ;; # conferido na seção 7.3
    "$in_house$status_sep$example") continue ;;  # conferido na seção 7.6
    "Reserva R-… cancelada. Multa lançada na conta: R\$ ….")
      # A frase composta é saída do ReservationCancelState: confere-se a saída, que o teste do estado fixa inteira
      # (o resumo é exatamente "Reserva R1 cancelada. Multa lançada na conta: R$ 320,00."), e não a linha que a monta.
      grep -qE '^[[:space:]]*assertEquals\("Reserva R1 cancelada\. Multa lançada na conta: R\$ [0-9.]+,[0-9]{2}\.", state\.done\)$' \
        "$cancel_test" && tpl_in_code "Reserva R-… cancelada." && tpl_in_code "Multa lançada na conta: R\$ …." && continue ;;
    "mais 30 dias" | "reserva do passo $n_new" | "Rodar o seed de novo depois da demo") continue ;; # texto do próprio roteiro
  esac
  bad_quotes+="$p"$'\n'
done < <(grep -oE '"[^"]+"' <<<"$m3_text" | sort -u)
assert_eq "" "$bad_quotes" "todo texto entre aspas dos passos do M3 existe no código (ou é nome, tipo ou plano do seed)"
# Os rótulos em negrito (botões, campos, abas e situações) são literais do código.
bad_bold=""
while IFS= read -r b; do
  [[ -n "$b" ]] || continue
  case "$b" in
    [0-9]*. | "Resultado esperado:" | "Reprovação:" | "Anote o código" | "Anote o código da reserva") continue ;;
  esac
  b="${b%:}"
  grep -qF -- "\"$b\"" "$code_f" && continue
  grep -qxF -- "$b" <<<"$type_names" && continue
  bad_bold+="$b"$'\n'
done < <(grep -oE '\*\*[^*]+\*\*' <<<"$m3_text" | tr -d '*' | sed -E 's/^([0-9]+\.[0-9]+\.).*/\1/' | sort -u)
assert_eq "" "$bad_bold" "todo rótulo em negrito dos passos do M3 é um literal do código"
(($(grep -oE '"[^"]+"' <<<"$m3_text" | sort -u | grep -c .) >= 100)) || _assert_fail "li os textos entre aspas dos passos do M3"

# =============================================================================================================
# 13. No fim: o seed repetido, as reservas do M3 e o retomar
# =============================================================================================================
n_traces_seed="$(grep -c 'TraceSpec("' "$traces_kt")"
fim_rerun="$(awk '/^### Rodar o seed de novo depois da demo$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
[[ -n "$fim_rerun" ]] || _assert_fail "No fim: tem \"### Rodar o seed de novo depois da demo\""
assert_contains "$fim_rerun" "passo $n_seed" "No fim: o seed repetido remete ao passo do seed ($n_seed)"
assert_contains "$fim_rerun" "\`reservas: 0 criados, $n_res já existiam\`" "No fim: as reservas do DemoCatalog, sem as do M3"
assert_contains "$fim_rerun" "\`recados: 0 criados, $n_traces_seed já existiam\`" "No fim: os recados do seed já existiam"
m3_rerun="$(grep -F -- '- As reservas do M3 não aparecem na saída' <<<"$fim_rerun")"
assert_contains "$m3_rerun" "o seed não toca nas que já existem" "No fim: o seed não toca nas reservas do M3 que existem"
assert_contains "$m3_rerun" "reativada no passo $n_react continua \"Confirmada\"" "No fim: a reativada continua confirmada"
assert_contains "$m3_rerun" "pré-reserva confirmada no passo $n_pend" "No fim: a pré-reserva confirmada"
assert_contains "$m3_rerun" "estendido no passo $n_change" "No fim: o hospedado estendido"
assert_contains "$m3_rerun" "nos passos $n_new a $n_cancel" "No fim: as reservas criadas, alterada e cancelada"
assert_eq "" "$(rc_ps_code <<<"$fim" | grep -E 'demo-seed|SetEnvironmentVariable|Read-Host|Get-Credential')" \
  "No fim: sem bloco do seed nem de segredo (remete ao passo do seed)"
n_build="$(step_with 'docker compose .* build$')"
resume="os passos ${n_app:-?} a ${n_live:-?} podem ser repetidos; os passos ${n_panel:-?} a ${n_pend:-?} rodam uma vez por servidor: para repeti-los, ou se você parou no meio deles, faça o \"Recomeçar do zero\""
parar="$(awk '/^### Parar sem apagar$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
assert_contains "$parar" "siga do passo ${n_app:-?}" "Parar sem apagar: volta pelo passo de abrir o app"
assert_contains "$parar" "$resume" "Parar sem apagar: os passos 11 a $n_pend rodam uma vez por servidor"
assert_contains "$parar" "e siga do passo ${n_build:-?}." "Parar sem apagar: Recomeçar do zero e seguir do build"
assert_contains "$intro" "$resume" "introdução: os passos 11 a $n_pend rodam uma vez por servidor"
assert_contains "$intro" "no passo $last_step, e siga do passo ${n_build:-?}." "introdução: Recomeçar do zero no último passo"
assert_contains "$(step "${n_sair:-x}")" "o passo $last_step diz como parar o servidor" "Sair remete ao No fim"

finish
