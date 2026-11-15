#!/usr/bin/env bash
# Ataque ao roteiro do M2 (M2 E06, Task 5): docs/demo/roteiro-m2.md, "um dia na recepção", do zero e acumulando o
# roteiro do M1. Aplica as travas genéricas de tests/lib/roteiro_checks.sh (segredos, PowerShell 5.1, folha de
# senhas, linhas idênticas ao LEIA-ME) e acrescenta o que é do M2: a saída do seed com os recados, as contagens do
# painel, a chegada sem UH do check-in e do desfazer (A3/A4), os rótulos de tela que existem no código, a segunda
# janela do app sem repetir o caminho do JAR, a aprovação contra o item certo da folha e o passo "No fim".
# Estático: sem pwsh, Docker nem Gradle. O catálogo do seed e o código do app são a fonte da verdade.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

rot="$ROOT/docs/demo/roteiro-m2.md"
rot1="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
readme_f="$ROOT/README.md"
docs_test="$ROOT/tests/test_docs.sh"
seed_src="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed"
catalog_kt="$seed_src/data/DemoCatalog.kt"
report_kt="$seed_src/data/SeedReport.kt"
traces_kt="$seed_src/frontdesk/TraceSeeder.kt"
users_kt="$seed_src/people/UserSeeder.kt"
catalog_err_kt="$ROOT/core/src/main/kotlin/com/zeny/desktop/core/error/ErrorCatalog.kt"

missing=0
for f in "$rot" "$rot1" "$leia_f" "$readme_f" "$docs_test" "$catalog_kt" "$report_kt" "$traces_kt" "$users_kt" \
  "$catalog_err_kt"; do
  if [[ ! -f "$f" ]]; then
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

# =============================================================================================================
# 1. Travas genéricas de todo roteiro (tests/lib/roteiro_checks.sh)
# =============================================================================================================
roteiro_check_powershell_51 "$rot" "$leia_f"
roteiro_check_seed_secrets "$rot" "$leia_f" "$readme_f"
roteiro_check_leia_app_and_vars "$rot" "$leia_f"
roteiro_check_password_sheet "$rot"
roteiro_check_identical_to_leia "$rot" "$leia_f" "$docs_test"
roteiro_check_folha_before_secrets "$rot"
# As linhas comuns são as mesmas do roteiro do M1 (a lista do test_docs.sh): o M2 acumula o M1.
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

# =============================================================================================================
# 2. Estrutura: passos em sequência, resultado esperado e a ordem do dia na recepção
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

n_seed="$(step_with 'demo-seed\.jar --server')"
n_app="$(step_with 'app\\zeny-desktop\.jar')"
n_map="$(step_with 'Ficam só as UHs sujas')"
n_live="$(step_with 'auth/login')"
n_panel="$(step_with '"Chegadas \([0-9]+\)"')"
n_checkin="$(step_with '\*\*Fazer check-in\*\*')"
n_card="$(step_with 'Microsoft Print to PDF')"
n_walkin="$(step_with '\*\*Concluir walk-in\*\*')"
n_move="$(step_with 'Troca feita para a UH')"
n_undo="$(step_with '\*\*Login de quem aprova\*\*')"
n_traces="$(step_with '\*\*Novo recado\*\*')"
n_concurrent="$(step_with 'Alguém alterou este recado')"
order_ok=1
prev=-1
for pair in "seed|$n_seed" "abrir o app|$n_app" "mapa|$n_map" "mudança ao vivo|$n_live" "painel do dia|$n_panel" \
  "check-in|$n_checkin" "ficha de registro|$n_card" "walk-in|$n_walkin" "troca de UH|$n_move" \
  "desfazer check-in|$n_undo" "recados|$n_traces" "edição concorrente|$n_concurrent" "No fim|$last_step"; do
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
((order_ok)) || _assert_fail "roteiro em ordem: seed < app < mapa < ao vivo < painel < check-in < ficha < walk-in < troca < desfazer < recados < edição concorrente < No fim" \
  "passos: $n_seed $n_app $n_map $n_live $n_panel $n_checkin $n_card $n_walkin $n_move $n_undo $n_traces $n_concurrent $last_step"
# Os passos novos dizem o que conta como reprovação do gate.
for n in "$n_panel" "$n_checkin" "$n_card" "$n_walkin" "$n_move" "$n_undo" "$n_traces" "$n_concurrent"; do
  [[ -n "$n" ]] || continue
  assert_contains "$(step "$n")" "**Reprovação" "passo $n: diz o que conta como reprovação"
done
# Remissões a passos e blocos existem; Erros comuns no último passo.
for n in $(grep -oE 'passos? [0-9]+((, | e | a )[0-9]+)*' "$rot" | grep -oE '[0-9]+' | sort -un); do
  ((n <= last_step)) || _assert_fail "remissão ao passo $n existe (último: $last_step)"
done
labels="$(grep -oE '^\*\*[0-9]+\.[0-9]+\. ' "$rot" | tr -d '* ' | sed 's/\.$//')"
for b in $(sed -E 's/PowerShell 5\.1//g' "$rot" | grep -oE '(^|[^0-9.v:$])[0-9]{1,2}\.[0-9]{1,2}([^0-9.%]|\.[^0-9]|$)' |
  grep -oE '[0-9]{1,2}\.[0-9]{1,2}' | sort -u); do
  grep -qxF -- "$b" <<<"$labels" || _assert_fail "remissão ao bloco $b existe (**$b.)"
done
assert_contains "$(step "$last_step")" "### Erros comuns" "os Erros comuns ficam no último passo ($last_step)"
assert_contains "$rot_text" "\"Erros comuns\", no passo $last_step" "a remissão a Erros comuns aponta o passo $last_step"
assert_eq "" "$(grep -noE '[A-Za-z0-9_./-]+\.md' "$rot" | grep -vE ':(roteiro-m[12]|LEIA-ME)\.md$')" "roteiro não remete a outro .md"
assert_eq "" "$(grep -nE 'README' "$rot")" "roteiro não remete ao README"
# O conteúdo do pacote nos passos 0 e 1 é o mesmo do roteiro do M1 (que o teste do pacote confere com o package.sh).
names_of() { grep -oE '`[^`]+`' <<<"$1" | tr -d '`' | grep -vE '^(demo-package|\.env|\.example)$' | sort -u; }
list_of() { names_of "$(grep -F "$2" "$1" | sed "s/ $3.*//")"; }
m1_l0="$(list_of "$rot1" 'Ela tem as pastas' 'Você não precisa')"
m1_l1="$(list_of "$rot1" 'a lista tem `app`' 'Não pode ter')"
[[ -n "$m1_l0" ]] || _assert_fail "roteiro-m1: passo 0 lista o conteúdo do pacote"
assert_eq "$m1_l0" "$(list_of "$rot" 'Ela tem as pastas' 'Você não precisa')" "passo 0: o conteúdo do pacote é o do roteiro-m1"
assert_eq "$m1_l1" "$(list_of "$rot" 'a lista tem `app`' 'Não pode ter')" "passo 1: o Get-ChildItem espera o conteúdo do roteiro-m1"
assert_contains "$m1_l0" "roteiro-m2.md" "o pacote leva o roteiro-m2.md"

# =============================================================================================================
# 3. Comandos: só os dois java do pacote, cada um uma vez; nenhum comando novo fora da lista; seed num bloco só
# =============================================================================================================
app_line='java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar'
seed_line="$(rc_ps_code "$leia_f" | grep -F 'java -jar app\demo-seed.jar' | head -1)"
assert_eq "$(printf '%s\n%s' "$seed_line" "$app_line" | sort)" "$(grep -E '^java ' <<<"$rot_ps" | grep -vxF 'java -version' | sort)" \
  "blocos PowerShell: além do java -version, só o java do seed e o do app, cada um uma vez (nenhum java -jar sem o modo da demo)"
assert_eq "" "$(grep -E 'zeny-desktop\.jar' "$rot" | grep -vxF "$app_line")" \
  "o caminho do JAR do app só aparece no comando do passo de abrir o app"
# Os passos do M2 (depois da mudança ao vivo) não trazem comando novo para o PowerShell além de entrar na pasta.
new_ps="$(for ((n = ${n_live:-$last_step} + 1; n <= last_step; n++)); do rc_ps_code <<<"$(step "$n")"; done |
  grep -vxF 'Set-Location C:\zeny-demo' | grep -vxF "$(grep -E '^docker compose .* down$' <<<"$rot_ps" | head -1)" |
  grep -vxF 'docker compose -p zeny-front down -v')"
assert_eq "" "$new_ps" "passos do M2: nenhum comando PowerShell novo (os do 5.1 ficam conferidos só no passo da mudança ao vivo)"

# =============================================================================================================
# 4. Seed: a saída com recados, as contagens contra o catálogo e o seed repetido
# =============================================================================================================
seed_step="$(step "${n_seed:-x}")"
seed_labels="$(grep -oE '^    [A-Z_]+\("[^"]+"\)' "$report_kt" | sed -E 's/.*\("([^"]+)"\)/\1/')"
seed_out="$(grep -E '^  [^ ].*: [0-9]+ criados, [0-9]+ já existiam$' <<<"$seed_step" | sed -E 's/^  //')"
assert_eq "$seed_labels" "$(sed -E 's/: [0-9]+ criados.*//' <<<"$seed_out")" "saída do seed com os rótulos e a ordem do SeedItem"
assert_eq "recados" "$(grep -A1 -xF 'check-ins' <<<"$seed_labels" | tail -1)" "SeedItem: recados logo depois de check-ins"
seed_total() { grep -E "^$1: " <<<"$seed_out" | sed -E 's/.*: ([0-9]+) criados, ([0-9]+) já existiam/\1 \2/' | awk '{print $1 + $2}'; }
cat_txt="$(cat "$catalog_kt")"
n_rooms="$(grep -cE '"[0-9]{3}" to "[A-Z]{3}"' <<<"$cat_txt")"
# Reservas do catálogo: slot, hóspede, tipo, noites, adultos, UH (- sem UH) e check-in.
res="$(grep -E '^ +r\("[A-Z][0-9]+",' <<<"$cat_txt" | sed -E 's/, null, /, "-", /' |
  sed -E 's/^ +r\("([A-Z][0-9]+)", ([0-9]+), "([A-Z]+)", ([0-9]+), ([0-9]+), "([0-9]{3}|-)", (true|false)\),?$/\1 \2 \3 \4 \5 \6 \7/')"
n_res="$(grep -c . <<<"$res")"
n_alloc="$(awk '$6 != "-"' <<<"$res" | grep -c .)"
n_ci_seed="$(awk '$7 == "true"' <<<"$res" | grep -c .)"
n_alloc_rerun="$(awk '$6 != "-" && $7 == "false"' <<<"$res" | grep -c .)"
n_traces_seed="$(grep -c 'TraceSpec("' "$traces_kt")"
assert_contains "$cat_txt" 'ReservationSpec(slot, "G$guest", type, d, d.plusDays(nights)' "catálogo: toda reserva chega na data operacional"
[[ "$n_res" -ge 11 && "$(awk 'NF != 7' <<<"$res" | grep -c .)" == 0 ]] || _assert_fail "li as reservas do DemoCatalog" "$res"
assert_eq "$n_rooms" "$(seed_total "UHs")" "seed: UHs = DemoCatalog"
assert_eq "$n_rooms" "$(seed_total "status de UH")" "seed: status de UH soma o número de UHs"
assert_eq "$n_res" "$(seed_total "reservas")" "seed: reservas = DemoCatalog"
assert_eq "$n_alloc" "$(seed_total "alocações")" "seed: alocações = DemoCatalog"
assert_eq "$n_ci_seed" "$(seed_total "check-ins")" "seed: check-ins = DemoCatalog"
assert_contains "$seed_out" "recados: $n_traces_seed criados, 0 já existiam" "seed: recados de exemplo = TraceSeeder ($n_traces_seed)"
assert_contains "$seed_step" "Concluído em " "seed: linha final Concluído em N s."
assert_contains "$seed_step" "a senha do administrador é o item 1 da folha" "seed: depois dele, a senha do admin é o item 1"
rerun="$(grep -F 'Rodar o seed de novo' <<<"$seed_step")"
assert_contains "$rerun" "\`alocações: 0 criados, $n_alloc_rerun já existiam\`" "seed repetido antes da demo: alocações das chegadas com UH"
assert_contains "$rerun" 'a linha `check-ins` não aparece' "seed repetido antes da demo: a linha check-ins some"
assert_contains "$rerun" "\`recados: 0 criados, $n_traces_seed já existiam\`" "seed repetido antes da demo: recados já existiam"
assert_contains "$rerun" "passo $last_step" "seed repetido: depois da demo, remete ao passo No fim ($last_step)"

# =============================================================================================================
# 5. Painel do dia, check-in, ficha, walk-in, troca e desfazer contra o catálogo
# =============================================================================================================
name_prefix="$(grep -oE 'fullName = "[^$"]*' "$catalog_kt" | sed 's/^fullName = "//')"
guest_words="$(awk '/val names =/ {on = 1; next} on && /^ +\)$/ {exit} on' "$catalog_kt" | grep -oE '"[^"]+"' | tr -d '"')"
[[ -n "$name_prefix" && "$(grep -c . <<<"$guest_words")" -ge 12 ]] || _assert_fail "li os nomes dos hóspedes do DemoCatalog"
guest_name() { printf '%s%s' "$name_prefix" "$(sed -n "${1}p" <<<"$guest_words")"; }
slot_holder() { guest_name "$(awk -v s="$1" '$1 == s {print $2}' <<<"$res")"; }
# Os nomes de hóspede citados num texto, um por linha.
names_in() { grep -oE "${name_prefix}[^[:space:],.;:()\"\`*]+" <<<"$1" | sort -u; }
slot_of_name() { for s in $(awk '{print $1}' <<<"$res"); do [[ "$(slot_holder "$s")" == "$1" ]] && echo "$s"; done; }

panel="$(step "${n_panel:-x}")"
n_arr="$n_res"
n_dep="$(awk '$4 == 0' <<<"$res" | grep -c .)"
assert_contains "$panel" "\"Chegadas ($n_arr)\"" "painel: chegadas = reservas do seed na data operacional ($n_arr)"
assert_contains "$panel" "\"Saídas ($n_dep)\"" "painel: saídas = day use do seed ($n_dep)"
assert_contains "$panel" "\"Hospedados ($n_ci_seed)\"" "painel: hospedados = check-ins do seed ($n_ci_seed)"
assert_contains "$panel" "digite \`recepcao\`" "painel: o dia na recepção é da Recepcionista"

checkin="$(step "${n_checkin:-x}")"
ci_names="$(names_in "$checkin")"
[[ -n "$ci_names" ]] || _assert_fail "check-in: cita o titular da chegada"
for nm in $(tr ' ' '_' <<<"$ci_names"); do
  s="$(slot_of_name "${nm//_/ }")"
  [[ "$s" == "A3" || "$s" == "A4" ]] || _assert_fail "check-in: só a chegada sem UH A3 ou A4 (citou ${nm//_/ }, slot ${s:-?})"
  assert_eq "-" "$(awk -v s="$s" '$1 == s {print $6}' <<<"$res")" "check-in: a chegada $s não tem UH no seed"
done
assert_contains "$checkin" "**Alocar**" "check-in: aloca a UH no diálogo"
assert_contains "$checkin" "Check-in feito na UH " "check-in: o resultado do diálogo"

card="$(step "${n_card:-x}")"
for word in "**Salvar como…**" "**Imprimir**" "Microsoft Print to PDF" "Cancelar" "Arquivo salvo." \
  "Enviado para a impressora." "substituir" "Alt+Tab" "125" "150%" "100%" "não é reprovação" "mascarado"; do
  assert_contains "$card" "$word" "ficha: cita $word"
done
assert_eq "" "$(names_in "$card" | grep -vxF -f <(printf '%s\n' "$ci_names"))" "ficha: é a da reserva do check-in"

walkin="$(step "${n_walkin:-x}")"
wk_names="$(names_in "$walkin")"
[[ -n "$wk_names" ]] || _assert_fail "walk-in: cita o hóspede escolhido"
while IFS= read -r nm; do
  [[ -n "$nm" ]] || continue
  [[ -z "$(slot_of_name "$nm")" ]] || _assert_fail "walk-in: o hóspede ($nm) não é titular de reserva do seed"
done <<<"$wk_names"

move="$(step "${n_move:-x}")"
mv_names="$(names_in "$move")"
[[ "$(grep -c . <<<"$mv_names")" == 1 ]] || _assert_fail "troca: cita um hospedado" "nomes: $mv_names"
mv_slot="$(slot_of_name "$mv_names")"
assert_eq "true" "$(awk -v s="$mv_slot" '$1 == s && $4 > 0 {print $7}' <<<"$res")" "troca: o hóspede ($mv_slot) está hospedado com noite restante"
mv_room="$(awk -v s="$mv_slot" '$1 == s {print $6}' <<<"$res")"
assert_contains "$move" "UH $mv_room" "troca: a UH de origem é a do seed ($mv_room)"
assert_contains "$move" "\"Suja\"" "troca: a UH de origem fica suja no mapa"

undo="$(step "${n_undo:-x}")"
un_names="$(names_in "$undo")"
[[ -n "$un_names" ]] || _assert_fail "desfazer: cita o titular"
assert_eq "" "$(grep -vxF -f <(printf '%s\n' "$ci_names") <<<"$un_names")" "desfazer: só o check-in feito no roteiro (A3/A4)"
assert_contains "$undo" "passo $n_checkin" "desfazer: remete ao check-in do passo $n_checkin"
# A aprovação: login gerente e o PIN do item certo da folha; a tentativa errada sem dígitos, gastando uma tentativa.
rows="$(rc_folha_rows "$rot")"
i_ger="$(rc_folha_item "$rows" 'login `gerente`')"
i_pin="$(rc_folha_item "$rows" 'PIN do Gerente')"
appr_lines="$(grep -E 'Em \*\*Login de quem aprova\*\*, digite' <<<"$undo")"
assert_eq "2" "$(grep -c . <<<"$appr_lines")" "desfazer: duas aprovações (PIN errado e PIN certo)"
while IFS= read -r l; do
  [[ -n "$l" ]] || continue
  assert_contains "$l" 'digite `gerente`' "desfazer: quem aprova é o gerente"
  assert_eq "$i_pin" "$(grep -oE 'item [0-9]+ da folha' <<<"$l" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "desfazer: a linha da aprovação aponta o item do PIN ($i_pin), e só ele"
done <<<"$appr_lines"
assert_contains "$(head -1 <<<"$appr_lines")" "um PIN diferente do item $i_pin da folha" "desfazer: primeiro, um PIN errado sem dígitos"
assert_contains "$(tail -1 <<<"$appr_lines")" "o PIN do Gerente (item $i_pin da folha)" "desfazer: depois, o PIN do Gerente"
wrong_msg="$(grep -E '"APPROVAL_CREDENTIALS_INVALID" to "' "$catalog_err_kt" | sed -E 's/.* to "([^"]+)".*/\1/')"
[[ -n "$wrong_msg" ]] || _assert_fail "ErrorCatalog tem a mensagem de APPROVAL_CREDENTIALS_INVALID"
assert_contains "$undo" "\"$wrong_msg\"" "desfazer: a mensagem do PIN errado é a do ErrorCatalog"
assert_contains "$undo" "gasta uma tentativa" "desfazer: o PIN errado gasta uma tentativa do gerente"

# =============================================================================================================
# 6. Recados e a edição concorrente com dois apps
# =============================================================================================================
traces="$(step "${n_traces:-x}")"
concurrent="$(step "${n_concurrent:-x}")"
seed_texts="$(grep -oE 'TraceSpec\("[A-Z_]+", "[^"]+"' "$traces_kt" | sed -E 's/.*, "([^"]+)"$/\1/')"
assert_eq "$n_traces_seed" "$(grep -c . <<<"$seed_texts")" "li os textos dos recados do TraceSeeder"
while IFS= read -r t; do
  [[ -n "$t" ]] || continue
  assert_contains "$traces" "\"$t\"" "recados: a aba mostra o recado do seed [$t]"
  # Resolver e editar só os recados criados no roteiro (o seed os conta pelo vínculo e setor).
  assert_eq "" "$(grep -E '\*\*(Resolver|Editar)\*\*' <<<"$traces$concurrent" | grep -F -- "$t")" "recados: não resolve nem edita o do seed [$t]"
done <<<"$seed_texts"
role_name="$(grep -oE 'DEPARTMENTS_ROLE_NAME = "[^"]+"' "$users_kt" | sed -E 's/.*"([^"]+)"/\1/')"
assert_contains "$traces" "\"$role_name\"" "recados: o aviso cita a role da demo ($role_name)"
assert_contains "$traces" "Sem permissão para ver os setores." "recados: o aviso cita a mensagem de permissão"
assert_contains "$concurrent" "Alguém alterou este recado. Confira e salve de novo." "edição concorrente: a mensagem do 412"
assert_contains "$concurrent" "passo $n_app" "edição concorrente: a segunda janela remete ao comando do passo $n_app"
assert_contains "$concurrent" "Em **Login**, digite \`gerente\`" "edição concorrente: a segunda janela entra com outro usuário (gerente)"
assert_not_contains "$concurrent" "Em **Login**, digite \`recepcao\`" "edição concorrente: o mesmo usuário encerraria a sessão da primeira janela"

# =============================================================================================================
# 7. Rótulos e textos de tela citados existem no código do app, do design ou do core
# =============================================================================================================
code_text="$(find "$ROOT/app/src/main" "$ROOT/design/src/main" "$ROOT/core/src/main" -name '*.kt' -print0 | xargs -0 cat)"
for lbl in '"Recados"' '"Novo recado"' '"Editar recado"' '"Ficha de registro"' '"Salvar como…"' '"Imprimir"' \
  '"Trocar UH"' '"Desfazer check-in"' '"Walk-in"' '"Alguém alterou este recado. Confira e salve de novo."' \
  '"Recepção"' '"Atualizar"' '"Chegadas"' '"Saídas"' '"Hospedados"' '"Fazer check-in"' '"Alocar"' '"Pronta"' \
  '"Check-in feito na UH ' '"Abrir ficha de registro"' '"Confirmada"' '"Hospedada"' '"Cobrar early check-in"' \
  '"Ficha de registro — ' '"Arquivo salvo."' '"Enviado para a impressora."' '"Salvar ficha de registro"' \
  '"Cotar"' '"Escolher"' '"Escolher hóspede"' '"Por nome"' '"Buscar"' '"Concluir walk-in"' '"Walk-in concluído: reserva ' \
  '"Saída (dd/mm/aaaa)"' '"Pedido do hóspede"' '"Manter a tarifa"' '"Troca feita para a UH ' '"Desfazer"' \
  '"Aprovação de superior"' '"Login de quem aprova"' '"Motivo"' '"Aprovar"' '"Check-in desfeito."' '"Resolver"' \
  '"Editar"' '"Salvar"' '"Abertos"' '"Resolvidos"' '"Troca de UH"' '"Hóspedes da estadia"' '"Histórico"' \
  '"Sem permissão para ver os setores. ' '"Esta ação precisa da aprovação de um superior."' \
  '"Sua sessão foi encerrada. Entre novamente."' "\"$wrong_msg\"" '"Data operacional: ' '"Sem UH"' \
  '"Sem early check-in agora."' '"Não escreva dados pessoais do hóspede no recado."' \
  '"Com aprovação de superior"'; do
  probe="${lbl//\"/}"
  probe="${probe% }"
  assert_contains "$rot_text" "$probe" "roteiro cita o rótulo [$probe]"
  assert_contains "$code_text" "$lbl" "o código tem o rótulo [$lbl]"
done

# =============================================================================================================
# 8. No fim: o seed rodado de novo depois da demo, sem repetir o bloco do seed
# =============================================================================================================
fim="$(step "$last_step")"
fim_rerun="$(awk '/^### Rodar o seed de novo depois da demo$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
[[ -n "$fim_rerun" ]] || _assert_fail "No fim: tem \"### Rodar o seed de novo depois da demo\""
assert_contains "$fim_rerun" "passo $n_seed" "No fim: o seed repetido remete ao passo do seed ($n_seed)"
assert_contains "$fim_rerun" "\`recados: 0 criados, $n_traces_seed já existiam\`" "No fim: os recados do seed já existiam"
assert_contains "$fim_rerun" "\`alocações: 0 criados, $n_alloc_rerun já existiam\`" "No fim: alocações das chegadas com UH"
assert_contains "$fim_rerun" 'a linha `check-ins` não aparece' "No fim: nenhum check-in refeito (só o do roteiro foi desfeito)"
assert_contains "$fim_rerun" "\`status de UH: 1 criados, $((n_rooms - 1)) já existiam\`" "No fim: a UH de origem da troca volta ao status do seed"
assert_eq "" "$(rc_ps_code <<<"$fim" | grep -E 'demo-seed|SetEnvironmentVariable|Read-Host|Get-Credential')" \
  "No fim: sem bloco do seed nem de segredo (remete ao passo do seed)"
# Retomar: os passos do app e do ao vivo se repetem; os do dia na recepção gastam o estado do seed (que o seed
# repetido não restaura), então rodam uma vez por servidor e se repetem só depois de "Recomeçar do zero".
n_build="$(step_with 'docker compose .* build$')"
resume="os passos ${n_app:-?} a ${n_live:-?} podem ser repetidos; os passos ${n_panel:-?} a ${n_concurrent:-?} rodam uma vez por servidor: para repeti-los, ou se você parou no meio deles, faça o \"Recomeçar do zero\""
parar="$(awk '/^### Parar sem apagar$/ {on = 1; next} on && /^###? / {exit} on' <<<"$fim")"
assert_contains "$parar" "siga do passo ${n_app:-?}" "Parar sem apagar: volta pelo passo de abrir o app"
assert_contains "$parar" "$resume" "Parar sem apagar: os passos do dia na recepção rodam uma vez por servidor"
assert_contains "$parar" "e siga do passo ${n_build:-?}." "Parar sem apagar: para repetir o dia, Recomeçar do zero e seguir do build"
intro="$(awk '/^## / {exit} {print}' "$rot")"
assert_contains "$intro" "$resume" "introdução: os passos do dia na recepção rodam uma vez por servidor"
assert_contains "$intro" "no passo $last_step, e siga do passo ${n_build:-?}." "introdução: Recomeçar do zero no último passo, seguindo do build"

finish
