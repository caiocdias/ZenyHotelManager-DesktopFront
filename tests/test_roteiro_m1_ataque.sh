#!/usr/bin/env bash
# Ataque ao roteiro da demo do M1 (E07, Task 2): docs/demo/roteiro-m1.md, o LEIA-ME do pacote e o README.
# O roteiro roda no Windows PowerShell 5.1 do jeito que está escrito; aqui não há pwsh, então os comandos são
# julgados por leitura (estático). O contrato (api/openapi.yaml), o app (rótulos da tela e áreas) e o catálogo
# do demo-seed são a fonte da verdade. Não chama o Gradle nem containers.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

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

# --- utilitários --------------------------------------------------------------------------------------------
# Linhas dos blocos ```powershell: "ROTULO:LINHA<TAB>texto"; "---" separa blocos.
ps_lines() {
  awk -v lbl="$2" '/^```powershell[[:space:]]*$/{inb=1; print "---"; next} /^```/{inb=0; next} inb {print lbl ":" FNR "\t" $0}' "$1"
}
# Junta as linhas de um comando que abre { ou ( e fecha depois: "ROTULO:LINHA<TAB>comando inteiro".
statements() {
  awk -F'\t' '
    function strip(s) { gsub(/"[^"]*"/, "\"\"", s); gsub(/\047[^\047]*\047/, "", s); return s }
    $0 == "---" { depth = 0; next }
    {
      t = $2; s = strip(t)
      if (depth == 0) { start = $1; buf = t } else { buf = buf " " t }
      n = split(s, ch, "")
      for (i = 1; i <= n; i++) {
        if (ch[i] == "{" || ch[i] == "(") depth++
        else if (ch[i] == "}" || ch[i] == ")") depth--
      }
      if (depth <= 0) { depth = 0; print start "\t" buf }
    }'
}
# Texto sem o conteúdo das strings (para procurar operadores e parâmetros fora de aspas).
strip_strings() { sed -E "s/\"[^\"]*\"/\"\"/g; s/'[^']*'/''/g"; }
# Seção "## N. ..." de um Markdown, do título até antes do próximo "## ".
section() { awk -v h="$2" 'index($0, h) == 1 {on = 1; print; next} on && /^## / {exit} on' "$1"; }
# Bloco de um caminho do contrato (até o próximo caminho ou chave de 2 espaços).
api_path_block() { awk -v p="  $1:" '$0 == p {on = 1; next} on && /^  [^ ]/ {exit} on' "$api"; }
schema_block() { awk -v s="    $1:" '$0 == s {on = 1; next} on && /^    [^ ]/ {exit} on' "$api"; }
schema_props() {
  schema_block "$1" | awk '/^      properties:/ {on = 1; next} on && /^      [^ ]/ {on = 0} on && /^        [A-Za-z]+:$/ {gsub(/[ :]/, ""); print}'
}
schema_required() {
  schema_block "$1" | awk '/^      required:/ {on = 1; next} on && /^      - / {sub(/^      - /, ""); print; next} on {on = 0}'
}
in_list() { grep -qxF -- "$1" <<<"$2"; }

rot_ps="$(ps_lines "$rot" roteiro)"
leia_ps="$(ps_lines "$leia_f" LEIA-ME)"
all_ps="$(printf '%s\n%s\n' "$rot_ps" "$leia_ps")"
rot_stmts="$(statements <<<"$rot_ps")"
all_stmts="$(statements <<<"$all_ps")"
step7="$(section "$rot" "## 7.")"
step7_ps="$(awk '/^```powershell/{inb=1; print "---"; next} /^```/{inb=0; next} inb {print "passo7:" NR "\t" $0}' <<<"$step7")"
step7_stmts="$(statements <<<"$step7_ps")"

# =============================================================================================================
# 1. PowerShell 5.1
# =============================================================================================================

# 1.1 Invoke-WebRequest no 5.1 usa o motor do Internet Explorer sem -UseBasicParsing (falha sem o IE configurado).
n_iwr=0
while IFS=$'\t' read -r where st; do
  [[ -n "$where" ]] || continue
  if grep -qiE '(^|[^A-Za-z-])(Invoke-WebRequest|iwr)([[:space:]]|$)' <<<"$st"; then
    n_iwr=$((n_iwr + 1))
    grep -qi -- '-UseBasicParsing' <<<"$st" || _assert_fail "Invoke-WebRequest com -UseBasicParsing ($where)" "comando: $st"
  fi
done <<<"$all_stmts"
((n_iwr >= 3)) || _assert_fail "roteiro usa Invoke-WebRequest para o ETag e o logout (achados: $n_iwr)"

# 1.2 No 5.1, curl e wget são aliases do Invoke-WebRequest: também no LEIA-ME, só curl.exe.
bad_alias="$(cut -f2- <<<"$all_ps" | grep -nE '(^|[^.A-Za-z0-9_-])(curl|wget)([[:space:]]|$)')"
assert_eq "" "$bad_alias" "blocos PowerShell (roteiro e LEIA-ME) sem curl/wget aliases; só curl.exe"

# 1.3 Nada que só exista no PowerShell 6/7: parâmetros novos dos cmdlets web e operadores && || ?? ?.
ps7_params='-(ResponseHeadersVariable|StatusCodeVariable|SkipHttpErrorCheck|SkipHeaderValidation|SkipCertificateCheck|Authentication|Token|Form|AsHashtable|NoProxy|Resume|AllowUnencryptedAuthentication|HttpVersion|ConnectionTimeoutSeconds|OperationTimeoutSeconds|PreserveAuthorizationOnRedirect|AllowInsecureRedirect)([^A-Za-z]|$)'
ps_code_nostr="$(cut -f2- <<<"$all_ps" | strip_strings)"
assert_eq "" "$(grep -niE -- "$ps7_params" <<<"$ps_code_nostr")" "sem parâmetro que só existe no PowerShell 6+"
assert_eq "" "$(grep -nE '&&|\|\||\?\?|\?\.' <<<"$ps_code_nostr")" "sem operador && || ?? ?. (PowerShell 7) fora de aspas"
assert_eq "" "$(grep -niE 'Get-Credential.*-Title' <<<"$ps_code_nostr")" "Get-Credential sem -Title (PowerShell 6+)"

# 1.4 Corpo JSON: -Body sempre de uma variável montada com ConvertTo-Json (hashtable direta vira formulário)
#     e -ContentType com charset=utf-8 (sem charset, o 5.1 codifica a string do corpo em ISO-8859-1).
n_body=0
declare -A last_assign=()
while IFS=$'\t' read -r where st; do
  [[ -n "$where" ]] || continue
  lhs_var="$(grep -oE '^[[:space:]]*\$[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=' <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*')"
  [[ "$st" == *"-Body"* ]] || {
    [[ -n "$lhs_var" ]] && last_assign["$lhs_var"]="$st"
    continue
  }
  n_body=$((n_body + 1))
  var="$(grep -oE -- '-Body[[:space:]]+\$[A-Za-z_][A-Za-z0-9_]*' <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*$')"
  if [[ -z "$var" ]]; then
    _assert_fail "-Body vem de uma variável ($where)" "comando: $st"
    continue
  fi
  # A atribuição que vale é a última antes deste comando.
  assign="${last_assign[$var]:-}"
  [[ "$assign" == *"ConvertTo-Json"* ]] || _assert_fail "$var do -Body é montado com ConvertTo-Json ($where)" "atribuição: $assign"
  grep -qiE -- '-ContentType[[:space:]]+"application/json;[[:space:]]*charset=utf-8"' <<<"$st" ||
    _assert_fail "-Body com -ContentType \"application/json; charset=utf-8\" ($where)" "comando: $st"
  [[ -n "$lhs_var" ]] && last_assign["$lhs_var"]="$st"
done <<<"$all_stmts"
((n_body >= 3)) || _assert_fail "roteiro envia corpo no login, no MARK_CLEAN e no MARK_DIRTY (achados: $n_body)"

# 1.5 ConvertTo-Json sem -Depth corta no nível 2: hashtable aninhada além disso vira "System.Collections.Hashtable".
while IFS=$'\t' read -r where st; do
  [[ "$st" == *"ConvertTo-Json"* ]] || continue
  grep -qi -- '-Depth' <<<"$st" && continue
  depth="$(strip_strings <<<"$st" | awk '{n = split($0, c, ""); d = 0; m = 0; for (i = 1; i <= n; i++) { if (c[i] == "{") { d++; if (d > m) m = d } else if (c[i] == "}") d-- } print m}')"
  ((depth <= 2)) || _assert_fail "ConvertTo-Json sem -Depth com hashtable de até 2 níveis ($where: $depth)" "comando: $st"
done <<<"$all_stmts"

# 1.6 Chave de hashtable com hífen sem aspas (If-Match = ...) é erro de sintaxe no PowerShell.
unquoted_keys="$(cut -f2- <<<"$all_ps" | strip_strings | grep -nE '(@\{|;)[[:space:]]*[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9-]+[[:space:]]*=|^[[:space:]]*[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9-]+[[:space:]]*=')"
assert_eq "" "$unquoted_keys" "chaves com hífen entre aspas nas hashtables (\"If-Match\" = ...)"

# 1.7 ETag: lido do .Headers de um Invoke-WebRequest (o Invoke-RestMethod do 5.1 não expõe cabeçalhos) e
#     repassado intacto, com as aspas, no If-Match.
etag_vars="$(grep -oE '"If-Match"[[:space:]]*=[[:space:]]*\$[A-Za-z_][A-Za-z0-9_]*' <<<"$rot_stmts" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*$' | sort -u)"
[[ -n "$etag_vars" ]] || _assert_fail "o If-Match recebe uma variável com o ETag"
for ev in $etag_vars; do
  assigns="$(cut -f2- <<<"$rot_stmts" | grep -E "^[[:space:]]*\\${ev}[[:space:]]*=")"
  [[ -n "$assigns" ]] || _assert_fail "$ev (If-Match) é atribuída no roteiro"
  while IFS= read -r a; do
    [[ -n "$a" ]] || continue
    src="$(grep -oE '\$[A-Za-z_][A-Za-z0-9_]*\.Headers' <<<"$a" | head -1 | sed 's/\.Headers$//')"
    if [[ -z "$src" ]] || ! grep -qiE "\\.Headers(\\[[\"']ETag[\"']\\]|\\.ETag)" <<<"$a"; then
      _assert_fail "$ev vem de .Headers[\"ETag\"] da resposta" "atribuição: $a"
      continue
    fi
    src_assign="$(cut -f2- <<<"$rot_stmts" | grep -E "^[[:space:]]*\\${src}[[:space:]]*=" | head -1)"
    grep -qiE '(^|[^A-Za-z-])(Invoke-WebRequest|iwr)([[:space:]]|$)' <<<"$src_assign" ||
      _assert_fail "$src (de onde sai o ETag) vem de Invoke-WebRequest, não de Invoke-RestMethod" "atribuição: $src_assign"
    # Nada que tire as aspas ou corte o valor: o servidor compara o ETag inteiro, com as aspas.
    assert_eq "" "$(grep -oiE 'Trim|-replace|\.Replace\(|Substring|-split|\.Headers\[[^]]*\]\[' <<<"$a")" \
      "$ev é o ETag intacto (sem Trim, -replace, -split nem índice; no 5.1 [0] pega só a aspa)"
    # No 5.1 .Headers["ETag"] é string; no PowerShell 7 é string[], e o -Headers manda "System.String[]" no
    # If-Match (412 em laço). [string] ou -join dão a mesma string intacta nos dois.
    grep -qE '\[string\]|-join' <<<"$a" ||
      _assert_fail "$ev normalizado para uma string só ([string] ou -join): .Headers[\"ETag\"] é string[] no PowerShell 7" "atribuição: $a"
  done <<<"$assigns"
done

# 1.8 Senha: só da janela do Get-Credential; nunca em argumento, Read-Host sem -AsSecureString ou -AsPlainText.
pw_stmts="$(cut -f2- <<<"$all_stmts" | grep -iE '(^|[^A-Za-z_])password[[:space:]]*=')"
[[ -n "$pw_stmts" ]] || _assert_fail "o login da Governanta manda a senha no corpo"
while IFS= read -r st; do
  [[ -n "$st" ]] || continue
  vals="$(grep -oiE '(^|[^A-Za-z_])password[[:space:]]*=[[:space:]]*[^[:space:];}]+' <<<"$st" | sed -E 's/.*=[[:space:]]*//')"
  while IFS= read -r v; do
    cvar="$(grep -oE '^\$[A-Za-z_][A-Za-z0-9_]*\.GetNetworkCredential\(\)\.Password$' <<<"$v" | sed 's/\..*//')"
    if [[ -z "$cvar" ]]; then
      _assert_fail "password vem de \$cred.GetNetworkCredential().Password" "valor: $v"
      continue
    fi
    cassign="$(cut -f2- <<<"$all_stmts" | grep -E "^[[:space:]]*\\${cvar}[[:space:]]*=" | head -1)"
    assert_contains "$cassign" "Get-Credential" "$cvar vem do Get-Credential"
  done <<<"$vals"
done <<<"$pw_stmts"
assert_eq "" "$(grep -niE 'AsPlainText|ConvertTo-SecureString' <<<"$ps_code_nostr")" "sem ConvertTo-SecureString -AsPlainText"
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -iE 'Read-Host' | grep -viE -- '-AsSecureString')" "Read-Host só com -AsSecureString"

# 1.9 O corpo com a senha sai da sessão logo depois do login.
login_body_var="$(grep -E 'auth/login' <<<"$rot_stmts" | grep -oE -- '-Body[[:space:]]+\$[A-Za-z_][A-Za-z0-9_]*' | grep -oE '[A-Za-z_][A-Za-z0-9_]*$' | head -1)"
if [[ -z "$login_body_var" ]]; then
  _assert_fail "o login manda o corpo por uma variável"
else
  login_ln="$(grep -nE 'auth/login' <<<"$rot_ps" | head -1 | cut -d: -f1)"
  rm_ln="$(grep -nE "Remove-Variable[[:space:]]+(-Name[[:space:]]+)?${login_body_var}([[:space:]]|$)" <<<"$rot_ps" | head -1 | cut -d: -f1)"
  if [[ -z "$rm_ln" ]] || ((rm_ln <= login_ln)); then
    _assert_fail "Remove-Variable $login_body_var depois do login (a senha não fica na sessão)"
  fi
fi

# 1.10 "-Dzeny.devShell=true" sempre entre aspas (sem elas o PowerShell parte no ponto), com o nome que o app lê.
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -nE '(^|[^"])-Dzeny\.')" "-Dzeny.* entre aspas em todo bloco PowerShell"
prop_name="$(grep -oE 'prop\("[^"]+"\) == "true"' "$approot_kt" | head -1 | sed -E 's/prop\("([^"]+)"\).*/\1/')"
assert_eq "zeny.devShell" "$prop_name" "o app lê a propriedade zeny.devShell (AppRoot.kt)"
assert_contains "$(cut -f2- <<<"$rot_ps")" "java \"-D${prop_name}=true\" -jar app\\zeny-desktop.jar" \
  "roteiro abre o app com a propriedade que o app lê"

# 1.11 Sem crase no fim da linha (continuação que quebra com um espaço depois dela ao copiar).
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -nE '`[[:space:]]*$')" "sem crase de continuação nos blocos PowerShell"

# 1.12 Variáveis do passo 7 definidas antes do uso, na ordem dos blocos (a segunda janela começa vazia).
defined=" "
while IFS=$'\t' read -r where st; do
  [[ -n "$where" ]] || continue
  used="$(strip_strings <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*' | sort -u)"
  # Variáveis dentro de strings expansíveis também contam.
  used="$(printf '%s\n%s\n' "$used" "$(grep -oE '"[^"]*"' <<<"$st" | grep -oE '\$\(?\$?[A-Za-z_][A-Za-z0-9_]*' | tr -d '($' | sed 's/^/$/')" | sort -u)"
  lhs="$(grep -oE '^[[:space:]]*\$[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=' <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*')"
  for u in $used; do
    case "$u" in
      '$_' | '$Error' | '$true' | '$false' | '$null' | '$env' | '$PWD' | '$LASTEXITCODE' | "$lhs") continue ;;
    esac
    [[ "$defined" == *" $u "* ]] || _assert_fail "passo 7: $u usada antes de ser definida ($where)" "comando: $st"
  done
  [[ -n "$lhs" ]] && defined="$defined$lhs "
  # Remove-Variable tira a variável da sessão: usá-la depois manda $null.
  for rv in $(grep -oE 'Remove-Variable[[:space:]]+(-Name[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*' <<<"$st" | awk '{print $NF}'); do
    defined="${defined// \$$rv / }"
  done
done <<<"$step7_stmts"

# =============================================================================================================
# 2. Fidelidade ao contrato (api/openapi.yaml)
# =============================================================================================================

base_val="$(cut -f2- <<<"$step7_stmts" | grep -oE '^\$base[[:space:]]*=[[:space:]]*"[^"]*"' | sed -E 's/.*"([^"]*)"/\1/')"
assert_eq "http://localhost:8088" "$base_val" "\$base do passo 7 é o servidor da demo"

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
  in_list "$want" "$seen_paths" || _assert_fail "passo 7 chama $want"
done

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
assert_eq $'MARK_CLEAN\nMARK_DIRTY' "$used_tr" "passo 7 usa MARK_CLEAN e MARK_DIRTY"
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
[[ -n "$room_fields" ]] || _assert_fail "passo 7 lê campos da UH"
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
logout_after="$(awk '/auth\/logout/ {on = 1} on' <<<"$step7" | grep -m1 -E '\*\*Resultado esperado')"
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

# 3.1 ZENY_SEED_* só com valor de exemplo entre <...>, no roteiro e no LEIA-ME.
for f in "$rot" "$leia_f"; do
  bad="$(grep -nE 'ZENY_SEED_[A-Z_]*[[:space:]]*=' "$f" | grep -vE 'ZENY_SEED_[A-Z_]*[[:space:]]*=[[:space:]]*"<[^>"]+>"[[:space:]]*$')"
  assert_eq "" "$bad" "${f#"$ROOT"/}: ZENY_SEED_* só com \"<...>\""
done
# 3.1b Os 6 segredos do seed: definidos no LEIA-ME por SetEnvironmentVariable(..., "Process"), num bloco
#      PowerShell, antes do java -jar; o valor vem da janela do Get-Credential (senhas) ou de um
#      Read-Host -AsSecureString (PIN), nunca de texto digitado na linha (o 5.1 grava cada linha no histórico).
seed_secrets="ZENY_SEED_ADMIN_PASSWORD ZENY_SEED_ADMIN_NEW_PASSWORD ZENY_SEED_MANAGER_PASSWORD
ZENY_SEED_RECEPTIONIST_PASSWORD ZENY_SEED_HOUSEKEEPER_PASSWORD ZENY_SEED_MANAGER_PIN"
# A lista acima é a dos segredos que o seed lê (senhas e PIN do SeedArgs.kt): nenhum a mais, nenhum a menos.
assert_eq "$(tr ' ' '\n' <<<"$seed_secrets" | grep . | sort)" \
  "$(grep -oE 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)' "$seedargs_kt" | sort -u)" "os 6 segredos são os que o seed lê"
leia_ps_text="$(cut -f2- <<<"$leia_ps")"
seed_java_ln="$(grep -nF 'java -jar app\demo-seed.jar --server' <<<"$leia_ps_text" | head -1 | cut -d: -f1)"
[[ -n "$seed_java_ln" ]] || _assert_fail "LEIA-ME: bloco PowerShell roda o demo-seed.jar"
gc_value='^\(Get-Credential( -UserName [A-Za-z0-9_.-]+)?( -Message "[^"$`]*")?\)\.GetNetworkCredential\(\)\.Password$'
pin_value='^\[Runtime\.InteropServices\.Marshal\]::PtrToStringBSTR\(\[Runtime\.InteropServices\.Marshal\]::SecureStringToBSTR\(\$[A-Za-z_][A-Za-z0-9_]*\)\)$'
for v in $seed_secrets; do
  lines="$(grep -nE "^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\"," <<<"$leia_ps_text")"
  if [[ "$(grep -c . <<<"$lines")" != 1 ]]; then
    _assert_fail "LEIA-ME define $v uma vez por [Environment]::SetEnvironmentVariable num bloco PowerShell" "linhas: $lines"
    continue
  fi
  ln="${lines%%:*}"
  line="${lines#*:}"
  value="$(sed -nE "s/^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\", (.*), \"Process\"\\)$/\\1/p" <<<"$line")"
  if [[ -z "$value" ]]; then
    _assert_fail "$v definida no escopo \"Process\"" "linha: $line"
    continue
  fi
  [[ -n "$seed_java_ln" ]] && ((ln < seed_java_ln)) || _assert_fail "$v definida antes do java -jar do seed"
  if [[ "$v" == *_PIN ]]; then
    if ! grep -qE "$pin_value" <<<"$value"; then
      _assert_fail "$v vem de um SecureString convertido pelo Marshal" "valor: $value"
      continue
    fi
    sv="$(grep -oE '\$[A-Za-z_][A-Za-z0-9_]*\)\)$' <<<"$value" | tr -d ')$')"
    sv_ln="$(grep -nE "^\\\$${sv}[[:space:]]*=[[:space:]]*Read-Host[[:space:]]+-AsSecureString([[:space:]]|$)" <<<"$leia_ps_text" | tail -1 | cut -d: -f1)"
    [[ -n "$sv_ln" ]] && ((sv_ln < ln)) || _assert_fail "\$$sv do $v vem de Read-Host -AsSecureString antes do uso"
    rm_ln="$(grep -nE "^Remove-Variable[[:space:]]+(-Name[[:space:]]+)?${sv}([[:space:]]|$)" <<<"$leia_ps_text" | head -1 | cut -d: -f1)"
    [[ -n "$rm_ln" ]] && ((rm_ln > ln)) || _assert_fail "Remove-Variable $sv depois de definir $v"
  else
    grep -qE "$gc_value" <<<"$value" ||
      _assert_fail "$v vem de (Get-Credential ...).GetNetworkCredential().Password, sem valor literal" "valor: $value"
  fi
done
# 3.1c A volta do padrão antigo: segredo do seed atribuído na linha, com qualquer valor (até "<...>"), nos blocos.
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -nE '\$env:ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[[:space:]]*=')" \
  "blocos PowerShell sem \$env:ZENY_SEED_*PASSWORD/PIN = (o 5.1 grava a linha no histórico)"
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -nE 'SetEnvironmentVariable\("ZENY_SEED_[A-Z_]*(PASSWORD|PIN)",[[:space:]]*["'"'"']')" \
  "SetEnvironmentVariable dos segredos sem valor literal"

# 3.2 Nenhum comando com senha ou PIN em argumento (o seed aceita --*-password e --manager-pin).
assert_eq "" "$(cut -f2- <<<"$all_ps" | grep -nE -- '--[a-z-]*(password|pin)([=[:space:]]|$)')" \
  "blocos PowerShell sem --*-password nem --manager-pin"
assert_eq "" "$(grep -nE -- '--(admin|admin-new|manager|receptionist|housekeeper)-password|--manager-pin' "$rot" "$readme_f")" \
  "roteiro e README não citam opção de senha em argumento"

# 3.3 Nenhum token, senha ou PIN literal.
for f in "$rot" "$leia_f" "$readme_f"; do
  assert_eq "" "$(grep -nE 'eyJ[A-Za-z0-9_-]{8,}' "$f")" "${f#"$ROOT"/}: sem JWT literal"
  assert_eq "" "$(grep -nE 'Bearer[[:space:]]+[^$[:space:]`…<]' "$f" | grep -vE 'Bearer[[:space:]]+(…|\.\.\.)')" \
    "${f#"$ROOT"/}: Bearer só com variável"
  assert_eq "" "$(grep -nE '(^|[^A-Za-z])PIN([^A-Za-z]|$)' "$f" | grep -E '(^|[^0-9A-Za-z-])[0-9]{4,8}([^0-9]|$)')" \
    "${f#"$ROOT"/}: sem PIN literal"
done

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

# 4.4 O comando do passo 2 é o mesmo da seção "Abrir o app" do LEIA-ME.
step2_cmd="$(section "$rot" "## 2." | awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' | head -1)"
leia_app="$(section "$leia_f" "## ${ln_app:+$(sed -n "${ln_app}p" "$leia_f" | grep -oE '[0-9]+')}." | awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb')"
[[ -n "$step2_cmd" ]] || _assert_fail "passo 2 tem o comando de abrir o app"
in_list "$step2_cmd" "$leia_app" || _assert_fail "comando do passo 2 igual ao do LEIA-ME" "passo 2: $step2_cmd" "LEIA-ME: $leia_app"

# 4.5 As variáveis de senha citadas pelo roteiro são as do LEIA-ME e as que o seed lê.
for v in $(grep -oE 'ZENY_SEED_[A-Z_]+' "$rot" | sort -u); do
  grep -qE "^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\", .*, \"Process\"\\)$" <<<"$leia_ps_text" ||
    _assert_fail "$v citada pelo roteiro é definida no LEIA-ME por SetEnvironmentVariable(..., \"Process\")"
  grep -qF -- "$v" "$seedargs_kt" || _assert_fail "$v citada pelo roteiro é lida pelo seed"
done
for v in ZENY_SEED_RECEPTIONIST_PASSWORD ZENY_SEED_MANAGER_PASSWORD ZENY_SEED_HOUSEKEEPER_PASSWORD; do
  assert_contains "$(cat "$rot")" "$v" "roteiro diz de onde vem a senha ($v)"
done

# =============================================================================================================
# 5. Ordem e resultados esperados
# =============================================================================================================

# 5.1 Cada um dos 8 passos tem resultado esperado.
for n in 1 2 3 4 5 6 7 8; do
  s="$(section "$rot" "## $n.")"
  [[ -n "$s" ]] || {
    _assert_fail "passo $n existe"
    continue
  }
  assert_contains "$s" "**Resultado esperado" "passo $n tem resultado esperado"
done

# 5.2 Cada bloco PowerShell é seguido do resultado esperado (ou da tabela de códigos) antes do próximo bloco.
missing_res="$(awk '
  /^## / { if (pending) print pending_ln; pending = 0 }
  /^```powershell/ { if (pending) print pending_ln; pending = 1; pending_ln = NR; inb = 1; next }
  /^```/ { inb = 0; next }
  inb { next }
  pending && (/\*\*Resultado esperado/ || /^\| Código \|/) { pending = 0 }
  END { if (pending) print pending_ln }' "$rot")"
assert_eq "" "$missing_res" "todo bloco PowerShell do roteiro tem resultado esperado (linhas sem: $missing_res)"

# 5.3 Passo 7 na ordem: login, lista, ETag, MARK_CLEAN, ETag de novo, MARK_DIRTY, logout.
ln_of() { grep -nE "$1" <<<"$step7_ps" | head -1 | cut -d: -f1; }
ln_last() { grep -nE "$1" <<<"$step7_ps" | tail -1 | cut -d: -f1; }
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
((order_ok)) || _assert_fail "passo 7 em ordem: login < lista < ETag < MARK_CLEAN < ETag relido < MARK_DIRTY < logout" \
  "linhas: $l_login $l_list $l_get1 $l_clean $l_get2 $l_dirty $l_logout"

# 5.4 412 e 422 com o que fazer; o código HTTP do erro do 5.1 vem de Exception.Response.StatusCode.
for c in 412 422; do
  row="$(grep -E "^\\| \`$c\` \\|" <<<"$step7")"
  cells="$(awk -F'|' '{n = 0; for (i = 2; i < NF; i++) if ($i ~ /[^[:space:]]/) n++; print n}' <<<"$row")"
  [[ "$cells" == "3" ]] || _assert_fail "passo 7: linha do $c com o que quer dizer e o que fazer" "linha: $row"
done
assert_contains "$step7" "Exception.Response.StatusCode" "passo 7: como ver o código HTTP do erro"
assert_contains "$step7" "lançam exceção" "passo 7: diz que o cmdlet lança exceção no erro"

# 5.5 Resultado ao vivo: Suja -> Limpa em até 2 s, sem recarregar.
assert_contains "$step7" 'de "Suja" para "Limpa"' "passo 7: ladrilho muda de Suja para Limpa"
assert_contains "$step7" "2 segundos" "passo 7: em até 2 segundos"
assert_contains "$step7" "sem recarregar" "passo 7: sem recarregar"
assert_contains "$(section "$rot" "## 4.")" "Clique em **Sair**" "passo 4 termina em Sair"
assert_contains "$(section "$rot" "## 8.")" "Sair" "passo 8 sai"

# 5.6 Menus dos passos 4 e 5: rótulos e ordem do enum Area; "(em breve)" só nas áreas sem tela; menus diferentes.
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
menu_of() { section "$rot" "$1" | grep -E '^  [0-9]+\. ' | sed -E 's/^  [0-9]+\. //'; }
for step in "## 4." "## 5."; do
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
m4="$(menu_of "## 4.")"
m5="$(menu_of "## 5.")"
[[ -n "$m4" && "$m4" != "$m5" ]] || _assert_fail "menus da Recepcionista e do Gerente diferem"
for item in $(comm -13 <(sort <<<"$m4") <(sort <<<"$m5") | sed 's/ (em breve)//' | tr ' ' '_'); do
  assert_contains "$(section "$rot" "## 5.")" "\"${item//_/ }\"" "passo 5 nomeia a diferença ($item)"
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
seed_out="$(section "$rot" "## 1." | grep -E '^  [^ ].*: [0-9]+ criados, [0-9]+ já existiam$' | sed -E 's/^  //')"
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
assert_contains "$(section "$rot" "## 1.")" "Concluído em " "seed: linha final Concluído em N s."

# 5.9 Mapa (passos 6 e 7): legenda, UH da mudança e filtros coerentes com o DemoCatalog e o RoomDisplay.
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
legend_line="$(section "$rot" "## 6." | grep -m1 'Fora de ordem: ')"
got_legend="$(grep -oE '"[^"]+: [0-9]+"' <<<"$legend_line" | tr -d '"')"
assert_eq "$expected_legend" "$got_legend" "passo 6: legenda (rótulos, ordem e contadores) = RoomDisplay + DemoCatalog"
uh="$(grep -oE 'marca a UH [0-9]{3}' <<<"$step7" | grep -oE '[0-9]{3}')"
assert_eq "DIRTY" "$(category "${uh:-x}")" "passo 7: a UH $uh é vaga, fora de OOO e suja no seed"
assert_contains "$(cut -f2- <<<"$step7_ps")" "-eq \"$uh\"" "passo 7: os comandos pegam a mesma UH $uh"
for n in $(grep -E '^\| `422`' <<<"$step7" | grep -oE '[0-9]{3}' | grep -v 422); do
  assert_eq "DIRTY" "$(category "$n")" "tabela do 422: a UH $n sugerida é vaga e suja"
done
hk_dirty="$(printf '%s\n%s\n' "$dirty" "$dirty_extra" | grep . | sort -u)"
got_dirty="$(section "$rot" "## 6." | grep -m1 'Ficam só as UHs sujas' | grep -oE '[0-9]{3}' | sort -u)"
assert_eq "$hk_dirty" "$got_dirty" "passo 6: o filtro Suja mostra as UHs sujas do seed"
suites="$(awk '$2 == "SUI" {print $1}' <<<"$rooms" | sort -u)"
got_suites="$(section "$rot" "## 6." | grep -m1 'Suíte\*\*\. Ficam' | grep -oE '[0-9]{3}' | sort -u)"
assert_eq "$suites" "$got_suites" "passo 6: o filtro Suíte mostra as suítes do seed"
n_dirty_tile="$(awk '$2 == "DIRTY"' <<<"$cats" | grep -c .)"
n_clean_tile="$(awk '$2 == "CLEAN"' <<<"$cats" | grep -c .)"
assert_contains "$step7" "\"Suja: $((n_dirty_tile - 1))\" e \"Limpa: $((n_clean_tile + 1))\"" "passo 7: legenda depois do MARK_CLEAN"
assert_contains "$step7" "\"Suja: $n_dirty_tile\" e \"Limpa: $n_clean_tile\"" "passo 7: legenda depois da volta com MARK_DIRTY"

finish
