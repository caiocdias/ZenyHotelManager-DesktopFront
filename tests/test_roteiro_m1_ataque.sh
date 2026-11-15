#!/usr/bin/env bash
# Ataque ao roteiro da demo do M1 (E07, Task 2): docs/demo/roteiro-m1.md, o LEIA-ME do pacote e o README.
# O roteiro roda no Windows PowerShell 5.1 do jeito que está escrito; aqui não há pwsh, então os comandos são
# julgados por leitura (estático). O contrato (api/openapi.yaml), o app (rótulos da tela e áreas) e o catálogo
# do demo-seed são a fonte da verdade. Não chama o Gradle nem containers.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

rot="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
readme_f="$ROOT/README.md"
api="$ROOT/api/openapi.yaml"
app_src="$ROOT/app/src/main/kotlin/com/zeny/desktop/app"
areas_kt="$app_src/shell/Areas.kt"
display_kt="$app_src/rooms/RoomDisplay.kt"
approot_kt="$app_src/AppRoot.kt"
seed_src="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed"
report_kt="$seed_src/data/SeedReport.kt"
catalog_kt="$seed_src/data/DemoCatalog.kt"
seedargs_kt="$seed_src/cli/SeedArgs.kt"

missing=0
for f in "$rot" "$leia_f" "$readme_f" "$api" "$areas_kt" "$display_kt" "$approot_kt" "$report_kt" "$catalog_kt" \
  "$seedargs_kt"; do
  if [[ ! -f "$f" ]]; then
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    missing=1
  fi
done
((missing == 0)) || finish

# --- utilitários (os genéricos vêm de tests/lib/roteiro_checks.sh) ---------------------------------------
ps_lines() { rc_ps_lines "$@"; }
statements() { rc_statements; }
strip_strings() { rc_strip_strings; }
section() { rc_section "$@"; }
# Bloco de um caminho do contrato (até o próximo caminho ou chave de 2 espaços).
api_path_block() { awk -v p="  $1:" '$0 == p {on = 1; next} on && /^  [^ ]/ {exit} on' "$api"; }
schema_block() { awk -v s="    $1:" '$0 == s {on = 1; next} on && /^    [^ ]/ {exit} on' "$api"; }
schema_props() {
  schema_block "$1" | awk '/^      properties:/ {on = 1; next} on && /^      [^ ]/ {on = 0} on && /^        [A-Za-z]+:$/ {gsub(/[ :]/, ""); print}'
}
schema_required() {
  schema_block "$1" | awk '/^      required:/ {on = 1; next} on && /^      - / {sub(/^      - /, ""); print; next} on {on = 0}'
}
in_list() { rc_in_list "$@"; }

rot_ps="$(ps_lines "$rot" roteiro)"
rot_stmts="$(statements <<<"$rot_ps")"

# Passos do roteiro achados pelo conteúdo, não pelo número: a Task 5a da E07 reescreveu o roteiro do zero (passos
# 0 a 12) e a numeração antiga (seed no 1, app no 2, demo do 4 ao 8) mudou de lugar. Cada passo é a ÚNICA seção
# "## N. ..." cujo texto casa com o padrão (ERE); nenhuma ou mais de uma é falha. A ordem entre eles é afirmada
# logo abaixo, então trocar dois passos de lugar continua vermelho.
step_heading() { rc_step_heading "$rot" "$1"; }
step_num() { rc_step_num "$1"; }
step_text() { rc_step_text "$rot" "$1"; }
h_seed="$(step_heading 'demo-seed\.jar --server')"
h_app="$(step_heading 'app\\zeny-desktop\.jar')"
h_recep="$(step_heading 'digite `recepcao`')"
h_ger="$(step_heading 'digite `gerente`')"
h_map="$(step_heading 'Ficam só as UHs sujas')"
h_live="$(step_heading 'auth/login')"
for pair in "seed (demo-seed.jar --server)|$h_seed" "abrir o app (app\\zeny-desktop.jar)|$h_app" \
  "Recepcionista (digite \`recepcao\`)|$h_recep" "Gerente (digite \`gerente\`)|$h_ger" \
  "mapa (Ficam só as UHs sujas)|$h_map" "mudança ao vivo (auth/login)|$h_live"; do
  [[ "${pair#*|}" == "## "[0-9]* ]] || _assert_fail "roteiro tem um único passo de ${pair%%|*}" "achado: ${pair#*|}"
done
n_seed="$(step_num "$h_seed")"
n_app="$(step_num "$h_app")"
n_recep="$(step_num "$h_recep")"
n_ger="$(step_num "$h_ger")"
n_map="$(step_num "$h_map")"
n_live="$(step_num "$h_live")"
# O passo depois da mudança ao vivo é o de sair do app (antigo passo 8).
h_exit="$([[ -n "$n_live" ]] && grep -m1 -E "^## $((n_live + 1))\\. " "$rot")"
n_exit="$(step_num "$h_exit")"
if [[ -z "$n_seed" || -z "$n_app" || -z "$n_recep" || -z "$n_ger" || -z "$n_map" || -z "$n_live" || -z "$n_exit" ]] ||
  ! ((n_seed < n_app && n_app < n_recep && n_recep < n_ger && n_ger < n_map && n_map < n_live && n_live < n_exit)); then
  _assert_fail "roteiro em ordem: seed < abrir o app < Recepcionista < Gerente < mapa < mudança ao vivo < sair" \
    "passos: $n_seed $n_app $n_recep $n_ger $n_map $n_live $n_exit"
fi
seed_step="$(step_text "$h_seed")"
recep_step="$(step_text "$h_recep")"
ger_step="$(step_text "$h_ger")"
map_step="$(step_text "$h_map")"
live="$(step_text "$h_live")"
live_ps="$(awk '/^```powershell/{inb=1; print "---"; next} /^```/{inb=0; next} inb {print "ao-vivo:" NR "\t" $0}' <<<"$live")"
live_stmts="$(statements <<<"$live_ps")"
P="passo ${n_live:-?}"

# =============================================================================================================
# 1. PowerShell 5.1
# =============================================================================================================
# Travas genéricas de todo roteiro (tests/lib/roteiro_checks.sh): 1.1 a 1.12, no roteiro e no LEIA-ME.
roteiro_check_powershell_51 "$rot" "$leia_f"

# =============================================================================================================
# 2. Fidelidade ao contrato (api/openapi.yaml)
# =============================================================================================================

base_val="$(cut -f2- <<<"$live_stmts" | grep -oE '^\$base[[:space:]]*=[[:space:]]*"[^"]*"' | sed -E 's/.*"([^"]*)"/\1/')"
assert_eq "http://localhost:8088" "$base_val" "\$base do $P (mudança ao vivo) é o servidor da demo"

# 2.1 Cada URI chamada existe no contrato com o método usado.
n_calls=0
seen_paths=""
while IFS=$'\t' read -r where st; do
  grep -qiE 'Invoke-(RestMethod|WebRequest)' <<<"$st" || continue
  uri="$(grep -oE -- '-Uri[[:space:]]+"[^"]+"' <<<"$st" | sed -E 's/^-Uri[[:space:]]+"//; s/"$//')"
  [[ -n "$uri" ]] || {
    _assert_fail "chamada HTTP com -Uri \"...\" ($where)" "comando: $st"
    continue
  }
  n_calls=$((n_calls + 1))
  method="$(grep -oiE -- '-Method[[:space:]]+[A-Za-z]+' <<<"$st" | awk '{print tolower($2)}')"
  method="${method:-get}"
  query=""
  [[ "$uri" == *"?"* ]] && query="${uri#*\?}"
  path="${uri%%\?*}"
  path="${path#\$base}"
  path="$(sed -E 's/\$\([^)]*\)/{id}/g; s/\$[A-Za-z_][A-Za-z0-9_]*/{id}/g' <<<"$path")"
  seen_paths="$seen_paths$method $path"$'\n'
  block="$(api_path_block "$path")"
  if [[ -z "$block" ]]; then
    _assert_fail "caminho do roteiro existe no contrato: $path ($where)"
    continue
  fi
  grep -qE "^    ${method}:" <<<"$block" || _assert_fail "contrato tem $method $path ($where)"
  IFS='&' read -ra pairs <<<"$query"
  for kv in "${pairs[@]}"; do
    [[ -n "$kv" ]] || continue
    k="${kv%%=*}"
    v="${kv#*=}"
    grep -qE "^ +name: ${k}$" <<<"$block" || _assert_fail "parâmetro $k de $path existe no contrato"
    if [[ "$k" == "limit" ]]; then
      max="$(awk '/name: limit$/ {on = 1} on && /maximum:/ {print $2; exit}' <<<"$block")"
      [[ -n "$max" && "$v" -le "$max" ]] || _assert_fail "limit=$v dentro do máximo do contrato ($max)"
    fi
  done
done <<<"$rot_stmts"
for want in "post /api/v1/auth/login" "get /api/v1/rooms/status" "get /api/v1/rooms/{id}/status" \
  "post /api/v1/rooms/{id}/housekeeping-status" "post /api/v1/auth/logout"; do
  in_list "$want" "$seen_paths" || _assert_fail "$P chama $want"
done

# Variável do corpo do login (a mesma que a 1.9 confere).
login_body_var="$(rc_login_body_var "$rot_stmts")"
# 2.2 Corpo do login: as chaves do LoginRequest e do DeviceRequest, nenhuma a mais.
login_props="$(schema_props LoginRequest)"
device_props="$(schema_props DeviceRequest)"
[[ -n "$login_props" && -n "$device_props" ]] || _assert_fail "contrato tem LoginRequest e DeviceRequest"
login_stmt="$(cut -f2- <<<"$rot_stmts" | grep -E "^[[:space:]]*\\\$${login_body_var:-loginBody}[[:space:]]*=" | head -1)"
login_nostr="$(strip_strings <<<"$login_stmt")"
device_part="$(grep -oE 'device[[:space:]]*=[[:space:]]*@\{[^}]*\}' <<<"$login_nostr")"
[[ -n "$device_part" ]] || _assert_fail "corpo do login tem device = @{ ... }"
top_part="$(sed -E 's/device[[:space:]]*=[[:space:]]*@\{[^}]*\}/device = X/' <<<"$login_nostr")"
for r in $(schema_required LoginRequest); do
  grep -qE "(^|[^A-Za-z_])${r}[[:space:]]*=" <<<"$top_part" || _assert_fail "corpo do login tem $r (obrigatório no contrato)"
done
for r in $(schema_required DeviceRequest); do
  grep -qE "(^|[^A-Za-z_])${r}[[:space:]]*=" <<<"$device_part" || _assert_fail "device tem $r (obrigatório no contrato)"
done
for k in $(grep -oE '(@\{|;|[[:space:]])[A-Za-z_]+[[:space:]]*=' <<<"$top_part" | grep -oE '[A-Za-z_]+'); do
  in_list "$k" "$login_props" || _assert_fail "chave $k do login existe no LoginRequest"
done
for k in $(grep -oE '(@\{|;|[[:space:]])[A-Za-z_]+[[:space:]]*=' <<<"${device_part#*@\{}" | grep -oE '[A-Za-z_]+'); do
  in_list "$k" "$device_props" || _assert_fail "chave $k do device existe no DeviceRequest"
done
assert_contains "$login_stmt" 'id = [guid]::NewGuid().ToString()' "device.id é um UUID novo em texto"

# 2.3 Transições: as do enum do HousekeepingStatusRequest, e o corpo só com transition.
transitions="$(schema_block HousekeepingStatusRequest | awk '/^        transition:/ {on = 1; next} on && /^        [^ ]/ {on = 0} on && /^          - / {print $2}')"
[[ -n "$transitions" ]] || _assert_fail "contrato tem o enum de transition"
used_tr="$(grep -oE 'transition[[:space:]]*=[[:space:]]*"[A-Z_]+"' <<<"$rot_stmts" | grep -oE '[A-Z_]+' | sort -u)"
assert_eq $'MARK_CLEAN\nMARK_DIRTY' "$used_tr" "$P usa MARK_CLEAN e MARK_DIRTY"
for t in $used_tr; do
  in_list "$t" "$transitions" || _assert_fail "transição $t existe no contrato"
done

# 2.4 Campos lidos das respostas existem nos esquemas do contrato.
token_props="$(schema_props TokenResponse)"
page_props="$(schema_props CursorPageRoomStatusResponse)"
room_props="$(schema_props RoomStatusResponse)"
for f in $(grep -oE '\$session\.[A-Za-z]+' <<<"$rot_stmts" | cut -d. -f2 | sort -u); do
  in_list "$f" "$token_props" || _assert_fail "\$session.$f existe no TokenResponse"
done
for f in $(grep -oE '\$rooms\.[A-Za-z]+' <<<"$rot_stmts" | cut -d. -f2 | sort -u); do
  in_list "$f" "$page_props" || _assert_fail "\$rooms.$f existe no CursorPageRoomStatusResponse"
done
room_fields="$(
  {
    grep -oE '\$_\.[A-Za-z]+' <<<"$rot_stmts" | cut -d. -f2
    grep -oE '\)\.[a-z][A-Za-z]+' <<<"$rot_stmts" | cut -d. -f2
    grep -oE 'Select-Object[[:space:]]+[A-Za-z, ]+' <<<"$rot_stmts" | sed -E 's/^Select-Object[[:space:]]+//' | tr ',' '\n' | tr -d ' '
  } | sort -u
)"
[[ -n "$room_fields" ]] || _assert_fail "$P lê campos da UH"
for f in $room_fields; do
  in_list "$f" "$room_props" || _assert_fail "campo $f da UH existe no RoomStatusResponse"
done

# 2.5 Token no cabeçalho Authorization: Bearer, em toda chamada além do login.
grep -qE '^      scheme: bearer$' <<<"$(awk '/^  securitySchemes:/ {on = 1} on' "$api")" || _assert_fail "contrato usa bearer"
assert_contains "$rot_stmts" 'Authorization = "Bearer $($session.accessToken)"' "cabeçalho Authorization: Bearer <accessToken>"
while IFS=$'\t' read -r where st; do
  grep -qiE 'Invoke-(RestMethod|WebRequest)' <<<"$st" || continue
  [[ "$st" == *"auth/login"* ]] && continue
  grep -qE -- '-Headers[[:space:]]+(\$auth|@\{[^}]*Authorization)' <<<"$st" ||
    _assert_fail "chamada autenticada leva o Authorization ($where)" "comando: $st"
done <<<"$rot_stmts"

# 2.6 Logout responde 204 no contrato e no roteiro; 412, 428 e 422 são os do contrato.
assert_contains "$(api_path_block /api/v1/auth/logout)" '"204":' "contrato: logout 204"
logout_after="$(awk '/auth\/logout/ {on = 1} on' <<<"$live" | grep -m1 -E '\*\*Resultado esperado')"
assert_contains "$logout_after" '`204`' "roteiro espera 204 no logout"
hk_desc="$(api_path_block '/api/v1/rooms/{id}/housekeeping-status' | tr -d '\n\\' | tr -s ' ')"
for c in 412 428 422; do
  assert_contains "$hk_desc" "$c" "contrato da mudança de governança cita $c"
done

# 2.7 Códigos de erro e enums citados entre crases existem no contrato.
for code in $(grep -oE '`[A-Z][A-Z_]*_[A-Z_]*[A-Z]`' "$rot" | tr -d '`' | grep -v '^ZENY_' | sort -u); do
  grep -qF -- "$code" "$api" || _assert_fail "código $code citado pelo roteiro existe no contrato"
done

# =============================================================================================================
# 3. Segredos
# =============================================================================================================

# Travas genéricas de todo roteiro (tests/lib/roteiro_checks.sh): 3.1 a 3.3, no roteiro, no LEIA-ME e no README.
roteiro_check_seed_secrets "$rot" "$leia_f" "$readme_f"

# =============================================================================================================
# 4. Coerência com o LEIA-ME
# =============================================================================================================

# 4.1 Seções numeradas em sequência, sem buraco nem repetição; "Abrir o app" depois do seed e antes do firewall.
nums="$(grep -oE '^## [0-9]+\.' "$leia_f" | grep -oE '[0-9]+' | tr '\n' ' ')"
expected_nums="$(seq 0 $(($(grep -cE '^## [0-9]+\.' "$leia_f") - 1)) | tr '\n' ' ')"
assert_eq "$expected_nums" "$nums" "LEIA-ME: seções numeradas em sequência"
ln_seed="$(grep -nE '^## [0-9]+\. Parar e recriar a demo$' "$leia_f" | cut -d: -f1)"
ln_app="$(grep -nE '^## [0-9]+\. Abrir o app$' "$leia_f" | cut -d: -f1)"
ln_fw="$(grep -nE '^## [0-9]+\. Firewall$' "$leia_f" | cut -d: -f1)"
if [[ -z "$ln_seed" || -z "$ln_app" || -z "$ln_fw" ]] || ! ((ln_seed < ln_app && ln_app < ln_fw)); then
  _assert_fail "LEIA-ME: \"Abrir o app\" entre \"Parar e recriar a demo\" e \"Firewall\"" "linhas: $ln_seed $ln_app $ln_fw"
fi

# 4.2 Remissões só por título: nada de "seções 1 e 3", "§3" ou "item 3 do LEIA-ME".
assert_eq "" "$(grep -nE '[Ss]e[cç](ão|ões|ao|oes)[^"]{0,6}[0-9]|§[[:space:]]*[0-9]|(item|passo)[[:space:]]+[0-9]+[^.]{0,20}LEIA-ME' "$rot")" \
  "roteiro remete ao LEIA-ME só pelo título"

# 4.3 Nada manda copiar JAR à mão.
for f in "$rot" "$leia_f"; do
  assert_eq "" "$(grep -niE '(copi[ea]|copiar|Copy-Item|xcopy|robocopy|(^|[[:space:]])(cp|copy)[[:space:]]).*\.jar' "$f")" \
    "${f#"$ROOT"/}: não manda copiar JAR"
done

# 4.4 e 4.5: travas genéricas de todo roteiro (tests/lib/roteiro_checks.sh): o comando de abrir o app é o do
#     LEIA-ME e as variáveis de senha citadas são as do LEIA-ME e as que o seed lê.
roteiro_check_leia_app_and_vars "$rot" "$leia_f"

# =============================================================================================================
# 5. Ordem e resultados esperados
# =============================================================================================================

# 5.1 Passos numerados em sequência, sem buraco nem repetição, e cada um tem resultado esperado.
rot_nums="$(grep -E '^## ' "$rot" | grep -oE '^## [0-9]+\.' | grep -oE '[0-9]+' | tr '\n' ' ')"
assert_eq "$(seq 0 $(($(grep -cE '^## ' "$rot") - 1)) | tr '\n' ' ')" "$rot_nums" "roteiro: todo título ## é um passo numerado em sequência"
(($(grep -cE '^## ' "$rot") >= 8)) || _assert_fail "roteiro tem ao menos os 8 passos da demo"
while IFS= read -r h; do
  [[ -n "$h" ]] || continue
  assert_contains "$(section "$rot" "$h")" "**Resultado esperado" "${h#\#\# } tem resultado esperado"
done < <(grep -E '^## ' "$rot")

# 5.2 Cada bloco PowerShell é seguido do resultado esperado (ou da tabela de códigos) antes do próximo bloco.
missing_res="$(awk '
  /^## / { if (pending) print pending_ln; pending = 0 }
  /^```powershell/ { if (pending) print pending_ln; pending = 1; pending_ln = NR; inb = 1; next }
  /^```/ { inb = 0; next }
  inb { next }
  pending && (/\*\*Resultado esperado/ || /^\| Código \|/) { pending = 0 }
  END { if (pending) print pending_ln }' "$rot")"
assert_eq "" "$missing_res" "todo bloco PowerShell do roteiro tem resultado esperado (linhas sem: $missing_res)"

# 5.3 Passo da mudança ao vivo na ordem: login, lista, ETag, MARK_CLEAN, ETag de novo, MARK_DIRTY, logout.
ln_of() { grep -nE "$1" <<<"$live_ps" | head -1 | cut -d: -f1; }
ln_last() { grep -nE "$1" <<<"$live_ps" | tail -1 | cut -d: -f1; }
l_login="$(ln_of 'auth/login')"
l_list="$(ln_of 'rooms/status\?')"
l_get1="$(ln_of 'rooms/\$[A-Za-z_]+/status"')"
l_clean="$(ln_of 'MARK_CLEAN')"
l_dirty="$(ln_of 'MARK_DIRTY')"
l_get2="$(ln_last 'rooms/\$[A-Za-z_]+/status"')"
l_logout="$(ln_of 'auth/logout')"
order_ok=1
for v in "$l_login" "$l_list" "$l_get1" "$l_clean" "$l_get2" "$l_dirty" "$l_logout"; do [[ -n "$v" ]] || order_ok=0; done
if ((order_ok)); then
  ((l_login < l_list && l_list < l_get1 && l_get1 < l_clean && l_clean < l_get2 && l_get2 < l_dirty && l_dirty < l_logout)) || order_ok=0
fi
((order_ok)) || _assert_fail "$P em ordem: login < lista < ETag < MARK_CLEAN < ETag relido < MARK_DIRTY < logout" \
  "linhas: $l_login $l_list $l_get1 $l_clean $l_get2 $l_dirty $l_logout"

# 5.4 412 e 422 com o que fazer; o código HTTP do erro do 5.1 vem de Exception.Response.StatusCode.
for c in 412 422; do
  row="$(grep -E "^\\| \`$c\` \\|" <<<"$live")"
  cells="$(awk -F'|' '{n = 0; for (i = 2; i < NF; i++) if ($i ~ /[^[:space:]]/) n++; print n}' <<<"$row")"
  [[ "$cells" == "3" ]] || _assert_fail "$P: linha do $c com o que quer dizer e o que fazer" "linha: $row"
done
assert_contains "$live" "Exception.Response.StatusCode" "$P: como ver o código HTTP do erro"
assert_contains "$live" "lançam exceção" "$P: diz que o cmdlet lança exceção no erro"

# 5.5 Resultado ao vivo: Suja -> Limpa em até 2 s, sem recarregar.
assert_contains "$live" 'de "Suja" para "Limpa"' "$P: ladrilho muda de Suja para Limpa"
assert_contains "$live" "2 segundos" "$P: em até 2 segundos"
assert_contains "$live" "sem recarregar" "$P: sem recarregar"
assert_contains "$recep_step" "Clique em **Sair**" "passo ${n_recep:-?} (Recepcionista) termina em Sair"
assert_contains "$(step_text "$h_exit")" "Sair" "passo ${n_exit:-?} (depois da mudança ao vivo) sai"

# 5.6 Menus da Recepcionista e do Gerente: rótulos e ordem do enum Area; "(em breve)" só nas áreas sem tela; menus diferentes.
labels_in_order="$(awk '
  /^    [A-Z_]+\("[a-z]+", "[^"]+"/ { match($0, /", "[^"]+"/); print substr($0, RSTART + 4, RLENGTH - 5); next }
  /^    [A-Z_]+\($/ { want = 2; next }
  want > 0 && /^        "[^"]+",$/ { want--; if (want == 0) { gsub(/^ +"|",$/, ""); print } }' "$areas_kt")"
impl_names="$(grep -oE 'IMPLEMENTED_AREAS[^=]*= *setOf\([^)]*\)' "$areas_kt" | grep -oE 'Area\.[A-Z_]+' | cut -d. -f2)"
impl_labels="$(for nm in $impl_names; do
  awk -v n="$nm" '$0 ~ "^    " n "\\(" { if (match($0, /", "[^"]+"/)) { print substr($0, RSTART + 4, RLENGTH - 5); exit } want = 2; next }
    want > 0 && /^        "[^"]+",$/ { want--; if (want == 0) { gsub(/^ +"|",$/, ""); print; exit } }' "$areas_kt"
done)"
[[ "$(wc -l <<<"$labels_in_order")" -ge 6 && -n "$impl_labels" ]] || _assert_fail "li as áreas do Areas.kt" "$labels_in_order"
menu_of() { step_text "$1" | grep -E '^  [0-9]+\. ' | sed -E 's/^  [0-9]+\. //'; }
for step in "$h_recep" "$h_ger"; do
  menu="$(menu_of "$step")"
  [[ -n "$menu" ]] || _assert_fail "$step lista as áreas do menu"
  prev=-1
  while IFS= read -r item; do
    [[ -n "$item" ]] || continue
    base="${item% (em breve)}"
    idx="$(grep -nxF -- "$base" <<<"$labels_in_order" | cut -d: -f1)"
    if [[ -z "$idx" ]]; then
      _assert_fail "$step: área \"$base\" existe no enum Area"
      continue
    fi
    ((idx > prev)) || _assert_fail "$step: \"$base\" na ordem do enum Area"
    prev="$idx"
    if in_list "$base" "$impl_labels"; then
      [[ "$item" == "$base" ]] || _assert_fail "$step: \"$base\" tem tela, sem \"(em breve)\""
    else
      [[ "$item" == "$base (em breve)" ]] || _assert_fail "$step: \"$base\" sem tela vem com \"(em breve)\""
    fi
  done <<<"$menu"
done
m4="$(menu_of "$h_recep")"
m5="$(menu_of "$h_ger")"
[[ -n "$m4" && "$m4" != "$m5" ]] || _assert_fail "menus da Recepcionista e do Gerente diferem"
for item in $(comm -13 <(sort <<<"$m4") <(sort <<<"$m5") | sed 's/ (em breve)//' | tr ' ' '_'); do
  assert_contains "$ger_step" "\"${item//_/ }\"" "passo ${n_ger:-?} (Gerente) nomeia a diferença ($item)"
done

# 5.7 Rótulos de tela citados existem no app (um rótulo trocado no app deixaria o roteiro mentindo).
app_text="$(cat "$app_src"/*.kt "$app_src"/*/*.kt)"
for lbl in "Entrar no Zeny Hotel Manager" '"Testar"' '"Entrar"' "Servidor encontrado (versão " "Data operacional: " \
  '"Sair"' 'Selecione uma área no menu.' '"Mapa de UHs"' '"Ao vivo"' '"Conectando…"' '"Andar $' '"UH $' '"Andar: ' \
  '"Tipo: ' '"Ocupação: ' '"Governança: ' '"Sinalização: ' '"Nenhuma"' '"Vaga"' '"Fechar"' ' · ' 'em breve)'; do
  probe="${lbl//\"/}"
  probe="${probe%\$}"
  assert_contains "$(cat "$rot")" "$probe" "roteiro cita o rótulo [$probe]"
  assert_contains "$app_text" "$lbl" "app tem o rótulo [$lbl]"
done

# 5.8 Seed: as linhas na ordem e com os rótulos do SeedItem; contagens coerentes com o DemoCatalog.
seed_labels="$(grep -oE '^    [A-Z_]+\("[^"]+"\)' "$report_kt" | sed -E 's/.*\("([^"]+)"\)/\1/')"
seed_out="$(grep -E '^  [^ ].*: [0-9]+ criados, [0-9]+ já existiam$' <<<"$seed_step" | sed -E 's/^  //')"
assert_eq "$seed_labels" "$(sed -E 's/: [0-9]+ criados.*//' <<<"$seed_out")" "saída do seed com os rótulos e a ordem do SeedItem"
seed_total() { grep -E "^$1: " <<<"$seed_out" | sed -E 's/.*: ([0-9]+) criados, ([0-9]+) já existiam/\1 \2/' | awk '{print $1 + $2}'; }
cat_txt="$(cat "$catalog_kt")"
rooms="$(grep -oE '"[0-9]{3}" to "[A-Z]{3}"' <<<"$cat_txt" | tr -d '"' | awk '{print $1, $3}')"
n_rooms="$(grep -c . <<<"$rooms")"
assert_eq "$n_rooms" "$(seed_total "UHs")" "seed: UHs = UHs do DemoCatalog"
# O seed conta cada UH uma vez em "status de UH" (criada se mudou, já existia se não): o total é o de UHs.
assert_eq "$n_rooms" "$(seed_total "status de UH")" "seed: \"status de UH\" soma criados + já existiam = número de UHs"
assert_eq "$(grep -c 'RoomTypeSpec("' <<<"$cat_txt")" "$(seed_total "tipos de UH")" "seed: tipos de UH = DemoCatalog"
assert_eq "$(grep -c 'UserSpec(DemoRole' <<<"$cat_txt")" "$(seed_total "usuários")" "seed: usuários = DemoCatalog"
res_lines="$(grep -E '^ +r\("[A-Z][0-9]+",' <<<"$cat_txt")"
assert_eq "$(grep -c . <<<"$res_lines")" "$(seed_total "reservas")" "seed: reservas = DemoCatalog"
assert_eq "$(grep -cE '"[0-9]{3}", (true|false)\)' <<<"$res_lines")" "$(seed_total "alocações")" "seed: alocações = DemoCatalog"
assert_eq "$(grep -cE ', true\)' <<<"$res_lines")" "$(seed_total "check-ins")" "seed: check-ins = DemoCatalog"
assert_contains "$seed_step" "Concluído em " "seed: linha final Concluído em N s."

# 5.9 Mapa e mudança ao vivo: legenda, UH da mudança e filtros coerentes com o DemoCatalog e o RoomDisplay.
set_of() { grep -oE "val $1 = setOf\\([^)]*\\)" <<<"$cat_txt" | grep -oE '[0-9]{3}'; }
dirty="$(set_of dirty)"
pending="$(set_of pending)"
clean="$(set_of clean)"
dirty_extra="$(grep -E 'in dirty,.*HousekeepingTarget\.DIRTY' <<<"$cat_txt" | grep -oE '"[0-9]{3}"' | tr -d '"')"
ooo="$(grep -oE 'outOfOrder = number == "[0-9]{3}"' <<<"$cat_txt" | grep -oE '[0-9]{3}')"
occupied="$(grep -E ', true\)' <<<"$res_lines" | grep -oE '"[0-9]{3}", true' | grep -oE '[0-9]{3}')"
if [[ -z "$rooms" || -z "$dirty" || -z "$ooo" || -z "$occupied" ]]; then
  _assert_fail "li UHs, sujas, OOO e ocupadas do DemoCatalog"
fi
category() { # categoria do RoomDisplayStatus de uma UH num servidor recém-populado
  local n="$1"
  if in_list "$n" "$ooo"; then echo OUT_OF_ORDER
  elif in_list "$n" "$occupied"; then echo OCCUPIED
  elif in_list "$n" "$dirty" || in_list "$n" "$dirty_extra"; then echo DIRTY
  elif in_list "$n" "$pending"; then echo CLEAN_PENDING_INSPECTION
  elif in_list "$n" "$clean"; then echo CLEAN
  else echo INSPECTED; fi
}
cats="$(while read -r n _; do echo "$n $(category "$n")"; done <<<"$rooms")"
display_pairs="$(grep -oE '^    [A-Z_]+\("[^"]+",' "$display_kt" | sed -E 's/^    ([A-Z_]+)\("([^"]+)",/\1|\2/')"
expected_legend="$(while IFS='|' read -r nm lbl; do
  printf '%s: %s\n' "$lbl" "$(awk -v c="$nm" '$2 == c' <<<"$cats" | grep -c .)"
done <<<"$display_pairs")"
legend_line="$(grep -m1 'Fora de ordem: ' <<<"$map_step")"
got_legend="$(grep -oE '"[^"]+: [0-9]+"' <<<"$legend_line" | tr -d '"')"
assert_eq "$expected_legend" "$got_legend" "passo ${n_map:-?}: legenda (rótulos, ordem e contadores) = RoomDisplay + DemoCatalog"
uh="$(grep -oE 'marca a UH [0-9]{3}' <<<"$live" | grep -oE '[0-9]{3}')"
assert_eq "DIRTY" "$(category "${uh:-x}")" "$P: a UH $uh é vaga, fora de OOO e suja no seed"
assert_contains "$(cut -f2- <<<"$live_ps")" "-eq \"$uh\"" "$P: os comandos pegam a mesma UH $uh"
for n in $(grep -E '^\| `422`' <<<"$live" | grep -oE '[0-9]{3}' | grep -v 422); do
  assert_eq "DIRTY" "$(category "$n")" "tabela do 422: a UH $n sugerida é vaga e suja"
done
hk_dirty="$(printf '%s\n%s\n' "$dirty" "$dirty_extra" | grep . | sort -u)"
got_dirty="$(grep -m1 'Ficam só as UHs sujas' <<<"$map_step" | grep -oE '[0-9]{3}' | sort -u)"
assert_eq "$hk_dirty" "$got_dirty" "passo ${n_map:-?}: o filtro Suja mostra as UHs sujas do seed"
suites="$(awk '$2 == "SUI" {print $1}' <<<"$rooms" | sort -u)"
got_suites="$(grep -m1 'Suíte\*\*\. Ficam' <<<"$map_step" | grep -oE '[0-9]{3}' | sort -u)"
assert_eq "$suites" "$got_suites" "passo ${n_map:-?}: o filtro Suíte mostra as suítes do seed"
n_dirty_tile="$(awk '$2 == "DIRTY"' <<<"$cats" | grep -c .)"
n_clean_tile="$(awk '$2 == "CLEAN"' <<<"$cats" | grep -c .)"
assert_contains "$live" "\"Suja: $((n_dirty_tile - 1))\" e \"Limpa: $((n_clean_tile + 1))\"" "$P: legenda depois do MARK_CLEAN"
assert_contains "$live" "\"Suja: $n_dirty_tile\" e \"Limpa: $n_clean_tile\"" "$P: legenda depois da volta com MARK_DIRTY"

finish
