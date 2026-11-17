#!/usr/bin/env bash
# Travas genéricas dos roteiros da demo (docs/demo/roteiro-m*.md): segredos, regras do Windows PowerShell 5.1,
# folha de senhas por item e linhas idênticas ao LEIA-ME do pacote. Cada função recebe o caminho do roteiro (e,
# onde precisa, o do LEIA-ME ou do README); as asserções só de um marco (os passos dele, a legenda contra o seed,
# o passo de sair...) ficam no teste do marco. Estático: sem pwsh, Docker nem Gradle.
# Para `source` depois de tests/lib/assert.sh, com ROOT (a raiz do repositório) definido; não executa nada ao ser
# carregado.

# --- utilitários ---------------------------------------------------------------------------------------------
# Linhas dos blocos ```powershell: "ROTULO:LINHA<TAB>texto"; "---" separa blocos.
rc_ps_lines() {
  awk -v lbl="$2" '/^```powershell[[:space:]]*$/{inb=1; print "---"; next} /^```/{inb=0; next} inb {print lbl ":" FNR "\t" $0}' "$1"
}
# Junta as linhas de um comando que abre { ou ( e fecha depois: "ROTULO:LINHA<TAB>comando inteiro".
rc_statements() {
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
rc_strip_strings() { sed -E "s/\"[^\"]*\"/\"\"/g; s/'[^']*'/''/g"; }
# rc_section ARQUIVO TÍTULO : seção "## N. ..." de um Markdown, do título até antes do próximo "## ".
rc_section() { awk -v h="$2" 'index($0, h) == 1 {on = 1; print; next} on && /^## / {exit} on' "$1"; }
rc_in_list() { grep -qxF -- "$1" <<<"$2"; }
# rc_step_heading ARQUIVO PADRÃO : título da ÚNICA seção "## ..." cujo texto casa com o padrão (ERE); vazio se
# nenhuma, "AMBIGUO" se mais de uma.
rc_step_heading() {
  PAT="$2" awk '
    function flush() { if (h != "" && hit) { n++; out = h } }
    /^## / { flush(); h = $0; hit = 0; next }
    h != "" && $0 ~ ENVIRON["PAT"] { hit = 1 }
    END { flush(); if (n == 1) print out; else if (n > 1) print "AMBIGUO" }' "$1"
}
rc_step_num() { grep -oE '^## [0-9]+\.' <<<"$1" | grep -oE '[0-9]+'; }
# rc_step_text ARQUIVO TÍTULO : texto do passo achado por rc_step_heading (vazio se não achou).
rc_step_text() { [[ "$2" == "## "* ]] && rc_section "$1" "$2"; }
# Só as linhas dentro de blocos ```powershell (de um arquivo ou de um texto na entrada).
rc_ps_code() { awk '/^```powershell[[:space:]]*$/{inb=1; next} /^```/{inb=0; next} inb' "$@"; }
# O mesmo, aceitando texto depois de ```powershell na abertura (a leitura do test_docs.sh).
rc_ps_code_any() { awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' "$1" 2>/dev/null; }
# Mesmo que rc_ps_code, com o número da linha no arquivo: "N<TAB>linha".
rc_ps_code_n() { awk '/^```powershell[[:space:]]*$/{inb=1; next} /^```/{inb=0; next} inb {print NR "\t" $0}' "$1"; }
# rc_step ARQUIVO N : seção "## N. ..." pelo número, do título até antes do próximo "## ".
rc_step() { awk -v h="## $2. " 'index($0, h) == 1 {on = 1; print; next} on && /^## / {exit} on' "$1"; }
# rc_step_of_line ARQUIVO N : número do passo que contém a linha N.
rc_step_of_line() { awk -v n="$2" 'NR > n {exit} /^## [0-9]+\. / {s = $2} END {sub(/\.$/, "", s); print s}' "$1"; }
# rc_step_with ARQUIVO PADRÃO : número do único passo cujo texto casa com o padrão (ERE); vazio se nenhum ou mais de um.
rc_step_with() {
  PAT="$2" awk '
    function flush() { if (h != "" && hit) { n++; out = h } }
    /^## [0-9]+\. / { flush(); h = $2; hit = 0; next }
    h != "" && $0 ~ ENVIRON["PAT"] { hit = 1 }
    END { flush(); if (n == 1) { sub(/\.$/, "", out); print out } }' "$1"
}
# rc_para_before ARQUIVO N : parágrafo logo antes da linha N (o texto que diz como rodar o bloco).
rc_para_before() { awk -v s="$2" 'NR < s' "$1" | awk 'BEGIN{RS=""} {p=$0} END{print p}'; }
# rc_first_ln ARQUIVO PADRÃO : número da primeira linha que casa com o padrão (ERE).
rc_first_ln() { grep -nE -- "$2" "$1" | head -1 | cut -d: -f1; }
# rc_folha ARQUIVO : a subseção "### Folha de senhas"; rc_folha_rows ARQUIVO : as linhas "| N | ..." da tabela dela.
rc_folha() { awk '/^### Folha de senhas$/ {on = 1; next} on && /^##/ {exit} on' "$1"; }
rc_folha_rows() { grep -E '^\| [0-9]+ \|' <<<"$(rc_folha "$1")"; }
# rc_folha_item LINHAS PADRÃO : número do item da folha cuja linha casa com o padrão (ERE).
rc_folha_item() { grep -E -- "$2" <<<"$1" | awk -F'|' '{gsub(/ /, "", $2); print $2}'; }
# rc_docs_shared_lines TEST_DOCS : as linhas comuns ao roteiro e ao LEIA-ME da lista do test_docs.sh (compose='...'
# e shared_lines=( ... )), uma por linha. Só as linhas da definição são avaliadas (compose='...' e itens '...' ou
# "$compose <subcomando>", sem substituição de comando); com qualquer outra forma, nada é avaliado: imprime a
# definição achada (para a mensagem de falha) e devolve 1.
rc_docs_shared_lines() {
  local docs_test="$1" compose_def arr_def arr_items
  compose_def="$(grep -E "^compose='[^']*'\$" "$docs_test")"
  arr_def="$(awk '/^shared_lines=\(/ {on = 1; first = NR} on {print} on && ((NR == first && /\)$/) || /^\)$/) {exit}' "$docs_test")"
  arr_items="$(sed '1d;$d' <<<"$arr_def")"
  if [[ -z "$compose_def" || "$(head -1 <<<"$arr_def")" != "shared_lines=(" || "$(tail -1 <<<"$arr_def")" != ")" ]] ||
    grep -qvE "^  ('[^']*'|\"\\\$compose [a-z -]+\")\$" <<<"$arr_items"; then
    printf '%s\n' "$arr_def"
    return 1
  fi
  (
    eval "$compose_def"
    eval "items=($arr_items)"
    printf '%s\n' "${items[@]}"
  )
}
# rc_login_body_var COMANDOS : nome da variável do -Body do login (comandos de rc_statements).
rc_login_body_var() {
  grep -E 'auth/login' <<<"$1" | grep -oE -- '-Body[[:space:]]+\$[A-Za-z_][A-Za-z0-9_]*' | grep -oE '[A-Za-z_][A-Za-z0-9_]*$' | head -1
}

# =============================================================================================================
# Regras do PowerShell 5.1 (vindas do test_roteiro_m1_ataque.sh, seção 1)
# =============================================================================================================
# roteiro_check_powershell_51 ROTEIRO LEIA-ME : blocos do roteiro e do LEIA-ME; o passo da mudança ao vivo é achado
# pelo conteúdo (auth/login).
roteiro_check_powershell_51() {
  local rot="$1" leia_f="$2"
  local approot_kt="$ROOT/app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt"
  local rot_ps leia_ps all_ps rot_stmts all_stmts h_live n_live live live_ps live_stmts P
  local n_iwr where st bad_alias ps7_params ps_code_nostr n_body lhs_var var assign depth unquoted_keys
  local etag_vars ev assigns a src src_assign pw_stmts vals v cvar cassign login_body_var login_ln rm_ln prop_name
  local defined used lhs u rv
  local -A last_assign=()
  rot_ps="$(rc_ps_lines "$rot" roteiro)"
  leia_ps="$(rc_ps_lines "$leia_f" LEIA-ME)"
  all_ps="$(printf '%s\n%s\n' "$rot_ps" "$leia_ps")"
  rot_stmts="$(rc_statements <<<"$rot_ps")"
  all_stmts="$(rc_statements <<<"$all_ps")"
  h_live="$(rc_step_heading "$rot" 'auth/login')"
  n_live="$(rc_step_num "$h_live")"
  live="$(rc_step_text "$rot" "$h_live")"
  live_ps="$(awk '/^```powershell/{inb=1; print "---"; next} /^```/{inb=0; next} inb {print "ao-vivo:" NR "\t" $0}' <<<"$live")"
  live_stmts="$(rc_statements <<<"$live_ps")"
  P="passo ${n_live:-?}"

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
  ps_code_nostr="$(cut -f2- <<<"$all_ps" | rc_strip_strings)"
  assert_eq "" "$(grep -niE -- "$ps7_params" <<<"$ps_code_nostr")" "sem parâmetro que só existe no PowerShell 6+"
  assert_eq "" "$(grep -nE '&&|\|\||\?\?|\?\.' <<<"$ps_code_nostr")" "sem operador && || ?? ?. (PowerShell 7) fora de aspas"
  assert_eq "" "$(grep -niE 'Get-Credential.*-Title' <<<"$ps_code_nostr")" "Get-Credential sem -Title (PowerShell 6+)"

  # 1.4 Corpo JSON: -Body sempre de uma variável montada com ConvertTo-Json (hashtable direta vira formulário)
  #     e -ContentType com charset=utf-8 (sem charset, o 5.1 codifica a string do corpo em ISO-8859-1).
  n_body=0
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
    depth="$(rc_strip_strings <<<"$st" | awk '{n = split($0, c, ""); d = 0; m = 0; for (i = 1; i <= n; i++) { if (c[i] == "{") { d++; if (d > m) m = d } else if (c[i] == "}") d-- } print m}')"
    ((depth <= 2)) || _assert_fail "ConvertTo-Json sem -Depth com hashtable de até 2 níveis ($where: $depth)" "comando: $st"
  done <<<"$all_stmts"

  # 1.6 Chave de hashtable com hífen sem aspas (If-Match = ...) é erro de sintaxe no PowerShell.
  unquoted_keys="$(cut -f2- <<<"$all_ps" | rc_strip_strings | grep -nE '(@\{|;)[[:space:]]*[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9-]+[[:space:]]*=|^[[:space:]]*[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9-]+[[:space:]]*=')"
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
  login_body_var="$(rc_login_body_var "$rot_stmts")"
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

  # 1.12 Variáveis do passo da mudança ao vivo definidas antes do uso, na ordem dos blocos (a segunda janela começa
  #      vazia). Sem os comandos do passo, a asserção passaria no vazio.
  (($(grep -c . <<<"$live_stmts") >= 15)) ||
    _assert_fail "$P (mudança ao vivo) tem os comandos do login ao logout (achados: $(grep -c . <<<"$live_stmts"))"
  defined=" "
  while IFS=$'\t' read -r where st; do
    [[ -n "$where" ]] || continue
    used="$(rc_strip_strings <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*' | sort -u)"
    # Variáveis dentro de strings expansíveis também contam.
    used="$(printf '%s\n%s\n' "$used" "$(grep -oE '"[^"]*"' <<<"$st" | grep -oE '\$\(?\$?[A-Za-z_][A-Za-z0-9_]*' | tr -d '($' | sed 's/^/$/')" | sort -u)"
    lhs="$(grep -oE '^[[:space:]]*\$[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=' <<<"$st" | grep -oE '\$[A-Za-z_][A-Za-z0-9_]*')"
    for u in $used; do
      case "$u" in
        '$_' | '$Error' | '$true' | '$false' | '$null' | '$env' | '$PWD' | '$LASTEXITCODE' | "$lhs") continue ;;
      esac
      [[ "$defined" == *" $u "* ]] || _assert_fail "$P: $u usada antes de ser definida ($where)" "comando: $st"
    done
    [[ -n "$lhs" ]] && defined="$defined$lhs "
    # Remove-Variable tira a variável da sessão: usá-la depois manda $null.
    for rv in $(grep -oE 'Remove-Variable[[:space:]]+(-Name[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*' <<<"$st" | awk '{print $NF}'); do
      defined="${defined// \$$rv / }"
    done
  done <<<"$live_stmts"
}

# =============================================================================================================
# Segredos (vindos do test_roteiro_m1_ataque.sh, seção 3)
# =============================================================================================================
# rc_check_seed_secrets RÓTULO TEXTO SEGREDOS : TEXTO são as linhas dos blocos PowerShell do arquivo, na ordem;
# SEGREDOS, os nomes das variáveis do seed separados por espaço.
rc_check_seed_secrets() {
  local lbl="$1" text="$2" seed_secrets="$3" java_ln v lines ln line value sv sv_ln rm_ln
  local gc_value='^\(Get-Credential( -UserName [A-Za-z0-9_.-]+)?( -Message "[^"$`]*")?\)\.GetNetworkCredential\(\)\.Password$'
  local pin_value='^\[Runtime\.InteropServices\.Marshal\]::PtrToStringBSTR\(\[Runtime\.InteropServices\.Marshal\]::SecureStringToBSTR\(\$[A-Za-z_][A-Za-z0-9_]*\)\)$'
  java_ln="$(grep -nF 'java -jar app\demo-seed.jar --server' <<<"$text" | head -1 | cut -d: -f1)"
  [[ -n "$java_ln" ]] || _assert_fail "$lbl: bloco PowerShell roda o demo-seed.jar"
  for v in $seed_secrets; do
    lines="$(grep -nE "^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\"," <<<"$text")"
    if [[ "$(grep -c . <<<"$lines")" != 1 ]]; then
      _assert_fail "$lbl define $v uma vez por [Environment]::SetEnvironmentVariable num bloco PowerShell" "linhas: $lines"
      continue
    fi
    ln="${lines%%:*}"
    line="${lines#*:}"
    value="$(sed -nE "s/^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\", (.*), \"Process\"\\)$/\\1/p" <<<"$line")"
    if [[ -z "$value" ]]; then
      _assert_fail "$lbl: $v definida no escopo \"Process\"" "linha: $line"
      continue
    fi
    [[ -n "$java_ln" ]] && ((ln < java_ln)) || _assert_fail "$lbl: $v definida antes do java -jar do seed"
    if [[ "$v" == *_PIN ]]; then
      if ! grep -qE "$pin_value" <<<"$value"; then
        _assert_fail "$lbl: $v vem de um SecureString convertido pelo Marshal" "valor: $value"
        continue
      fi
      sv="$(grep -oE '\$[A-Za-z_][A-Za-z0-9_]*\)\)$' <<<"$value" | tr -d ')$')"
      sv_ln="$(grep -nE "^\\\$${sv}[[:space:]]*=[[:space:]]*Read-Host[[:space:]]+-AsSecureString([[:space:]]|$)" <<<"$text" | tail -1 | cut -d: -f1)"
      [[ -n "$sv_ln" ]] && ((sv_ln < ln)) || _assert_fail "$lbl: \$$sv do $v vem de Read-Host -AsSecureString antes do uso"
      rm_ln="$(grep -nE "^Remove-Variable[[:space:]]+(-Name[[:space:]]+)?${sv}([[:space:]]|$)" <<<"$text" | head -1 | cut -d: -f1)"
      [[ -n "$rm_ln" ]] && ((rm_ln > ln)) || _assert_fail "$lbl: Remove-Variable $sv depois de definir $v"
      [[ -n "$rm_ln" && -n "$java_ln" ]] && ((rm_ln < java_ln)) ||
        _assert_fail "$lbl: Remove-Variable $sv antes do java -jar do seed"
    else
      grep -qE "$gc_value" <<<"$value" ||
        _assert_fail "$lbl: $v vem de (Get-Credential ...).GetNetworkCredential().Password, sem valor literal" "valor: $value"
    fi
  done
}

# roteiro_check_seed_secrets ROTEIRO LEIA-ME README : os segredos do seed no roteiro e no LEIA-ME; nenhum segredo
# literal no roteiro, no LEIA-ME nem no README.
roteiro_check_seed_secrets() {
  local rot="$1" leia_f="$2" readme_f="$3"
  local seedargs_kt="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/cli/SeedArgs.kt"
  local rot_ps leia_ps all_ps f bad seed_secrets leia_ps_text rot_ps_text
  rot_ps="$(rc_ps_lines "$rot" roteiro)"
  leia_ps="$(rc_ps_lines "$leia_f" LEIA-ME)"
  all_ps="$(printf '%s\n%s\n' "$rot_ps" "$leia_ps")"

  # 3.1 ZENY_SEED_* só com valor de exemplo entre <...>, no roteiro e no LEIA-ME.
  for f in "$rot" "$leia_f"; do
    bad="$(grep -nE 'ZENY_SEED_[A-Z_]*[[:space:]]*=' "$f" | grep -vE 'ZENY_SEED_[A-Z_]*[[:space:]]*=[[:space:]]*"<[^>"]+>"[[:space:]]*$')"
    assert_eq "" "$bad" "${f#"$ROOT"/}: ZENY_SEED_* só com \"<...>\""
  done
  # 3.1b Os 6 segredos do seed: definidos por SetEnvironmentVariable(..., "Process"), num bloco PowerShell, antes do
  #      java -jar; o valor vem da janela do Get-Credential (senhas) ou de um Read-Host -AsSecureString (PIN), nunca de
  #      texto digitado na linha (o 5.1 grava cada linha no histórico); o $pin sai da sessão antes do java -jar.
  #      Vale para o LEIA-ME e para o roteiro (que tem o seu próprio bloco do seed, passo a passo).
  seed_secrets="ZENY_SEED_ADMIN_PASSWORD ZENY_SEED_ADMIN_NEW_PASSWORD ZENY_SEED_MANAGER_PASSWORD
ZENY_SEED_RECEPTIONIST_PASSWORD ZENY_SEED_HOUSEKEEPER_PASSWORD ZENY_SEED_MANAGER_PIN"
  # A lista acima é a dos segredos que o seed lê (senhas e PIN do SeedArgs.kt): nenhum a mais, nenhum a menos.
  assert_eq "$(tr ' ' '\n' <<<"$seed_secrets" | grep . | sort)" \
    "$(grep -oE 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)' "$seedargs_kt" | sort -u)" "os 6 segredos são os que o seed lê"
  leia_ps_text="$(cut -f2- <<<"$leia_ps")"
  rot_ps_text="$(cut -f2- <<<"$rot_ps")"
  rc_check_seed_secrets LEIA-ME "$leia_ps_text" "$seed_secrets"
  rc_check_seed_secrets roteiro "$rot_ps_text" "$seed_secrets"
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
}

# =============================================================================================================
# Coerência com o LEIA-ME (vinda do test_roteiro_m1_ataque.sh, seção 4)
# =============================================================================================================
# roteiro_check_leia_app_and_vars ROTEIRO LEIA-ME : o comando de abrir o app é o do LEIA-ME; as variáveis de senha
# citadas são as do LEIA-ME e as que o seed lê.
roteiro_check_leia_app_and_vars() {
  local rot="$1" leia_f="$2"
  local seedargs_kt="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/cli/SeedArgs.kt"
  local h_app n_app ln_app app_cmd leia_app leia_ps_text v
  h_app="$(rc_step_heading "$rot" 'app\\zeny-desktop\.jar')"
  n_app="$(rc_step_num "$h_app")"
  ln_app="$(grep -nE '^## [0-9]+\. Abrir o app$' "$leia_f" | cut -d: -f1)"
  leia_ps_text="$(cut -f2- <<<"$(rc_ps_lines "$leia_f" LEIA-ME)")"

  # 4.4 O comando do passo de abrir o app é o mesmo da seção "Abrir o app" do LEIA-ME.
  app_cmd="$(rc_step_text "$rot" "$h_app" | awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' | head -1)"
  leia_app="$(rc_section "$leia_f" "## ${ln_app:+$(sed -n "${ln_app}p" "$leia_f" | grep -oE '[0-9]+')}." | awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb')"
  [[ -n "$app_cmd" ]] || _assert_fail "passo ${n_app:-?} tem o comando de abrir o app"
  rc_in_list "$app_cmd" "$leia_app" ||
    _assert_fail "comando do passo ${n_app:-?} (abrir o app) igual ao do LEIA-ME" "roteiro: $app_cmd" "LEIA-ME: $leia_app"

  # 4.5 As variáveis de senha citadas pelo roteiro são as do LEIA-ME e as que o seed lê.
  for v in $(grep -oE 'ZENY_SEED_[A-Z_]+' "$rot" | sort -u); do
    grep -qE "^\\[Environment\\]::SetEnvironmentVariable\\(\"${v}\", .*, \"Process\"\\)$" <<<"$leia_ps_text" ||
      _assert_fail "$v citada pelo roteiro é definida no LEIA-ME por SetEnvironmentVariable(..., \"Process\")"
    grep -qF -- "$v" "$seedargs_kt" || _assert_fail "$v citada pelo roteiro é lida pelo seed"
  done
  for v in ZENY_SEED_RECEPTIONIST_PASSWORD ZENY_SEED_MANAGER_PASSWORD ZENY_SEED_HOUSEKEEPER_PASSWORD; do
    assert_contains "$(cat "$rot")" "$v" "roteiro diz de onde vem a senha ($v)"
  done
}

# =============================================================================================================
# Folha de senhas por item (vinda do test_roteiro_m1_zero_ataque.sh, seção 1)
# =============================================================================================================
# roteiro_check_password_sheet ROTEIRO : a folha no passo 0, os 5 itens com as regras do SeedArgs.kt e cada janela
# de credencial, login no app e remissão apontando o item certo.
roteiro_check_password_sheet() {
  local rot="$1"
  local seedargs_kt="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/cli/SeedArgs.kt"
  local rot_ps_n n_admin n_seed last_step folha_ln step1_ln first_secret_ln first_login_ln folha rows
  local i_admin i_ger i_rec i_gov i_pin pair pw_rule pin_rule i n_prompts start prompt_ln line prev want login
  local got_items got_user l user n said used
  rot_ps_n="$(rc_ps_code_n "$rot")"
  n_admin="$(rc_step_with "$rot" 'Select-String .*ZENY_ADMIN_')"
  n_seed="$(rc_step_with "$rot" 'demo-seed\.jar --server')"
  last_step="$(grep -oE '^## [0-9]+\.' "$rot" | tail -1 | grep -oE '[0-9]+')"

  # 1.1 A folha fica no passo 0, antes do passo 1: antes de qualquer linha que peça segredo e antes de o .env existir.
  folha_ln="$(rc_first_ln "$rot" '^### Folha de senhas$')"
  step1_ln="$(rc_first_ln "$rot" '^## 1\. ')"
  first_secret_ln="$(awk -F'\t' '$2 ~ /Get-Credential|Read-Host|SetEnvironmentVariable/ {print $1; exit}' <<<"$rot_ps_n")"
  first_login_ln="$(rc_first_ln "$rot" '\*\*Senha\*\*')"
  if [[ -z "$folha_ln" || -z "$step1_ln" || -z "$first_secret_ln" || -z "$first_login_ln" ]] ||
    ! ((folha_ln < step1_ln && step1_ln < first_secret_ln && folha_ln < first_login_ln)); then
    _assert_fail "folha de senhas no passo 0, antes do passo 1, da primeira linha de segredo e do primeiro login no app" \
      "folha: ${folha_ln:-ausente}; passo 1: ${step1_ln:-ausente}; segredo: ${first_secret_ln:-ausente}; login: ${first_login_ln:-ausente}"
  fi
  assert_eq "0" "$(rc_step_of_line "$rot" "${folha_ln:-1}")" "a folha de senhas está no passo 0"

  # 1.2 A tabela: 5 itens numerados de 1 a 5, um por segredo do seed, com as regras do SeedArgs.kt.
  folha="$(rc_folha "$rot")"
  rows="$(rc_folha_rows "$rot")"
  assert_eq "1 2 3 4 5" "$(awk -F'|' '{gsub(/ /, "", $2); print $2}' <<<"$rows" | tr '\n' ' ' | sed 's/ $//')" \
    "folha: itens 1 a 5, em ordem"
  i_admin="$(rc_folha_item "$rows" 'senha nova do administrador')"
  i_ger="$(rc_folha_item "$rows" 'login `gerente`')"
  i_rec="$(rc_folha_item "$rows" 'login `recepcao`')"
  i_gov="$(rc_folha_item "$rows" 'login `governanta`')"
  i_pin="$(rc_folha_item "$rows" 'PIN do Gerente')"
  for pair in "senha nova do administrador|$i_admin" "gerente|$i_ger" "recepcao|$i_rec" "governanta|$i_gov" "PIN|$i_pin"; do
    [[ "${pair#*|}" =~ ^[1-5]$ ]] || _assert_fail "folha: um só item para ${pair%%|*}" "achado: ${pair#*|}"
  done
  pw_rule="$(grep -oE 'PASSWORD_LENGTH = [0-9]+\.\.[0-9]+' "$seedargs_kt" | grep -oE '[0-9]+\.\.[0-9]+' | sed 's/\.\./ a /')"
  pin_rule="$(grep -oE 'PIN_LENGTH = [0-9]+\.\.[0-9]+' "$seedargs_kt" | grep -oE '[0-9]+\.\.[0-9]+' | sed 's/\.\./ a /')"
  [[ -n "$pw_rule" && -n "$pin_rule" ]] || _assert_fail "li as regras de senha e PIN do SeedArgs.kt"
  for i in "$i_admin" "$i_ger" "$i_rec" "$i_gov"; do
    assert_contains "$(grep -E "^\\| $i \\|" <<<"$rows")" "de $pw_rule caracteres" "folha, item $i: senha de $pw_rule caracteres (SeedArgs)"
  done
  assert_contains "$(grep -E "^\\| ${i_pin:-x} \\|" <<<"$rows")" "de $pin_rule dígitos" "folha, item do PIN: de $pin_rule dígitos (SeedArgs)"
  assert_contains "$folha" "anote" "folha: manda anotar"
  assert_contains "$folha" "fora da pasta" "folha: anotar fora da pasta do pacote"

  # 1.3 Cada janela de senha aponta o item certo da folha (e só ele); a senha atual do admin aponta o passo do .env.
  #     O nome do usuário na janela é o login do item.
  n_prompts=0
  while IFS=: read -r start prompt_ln; do
    [[ -n "$start" ]] || continue
    n_prompts=$((n_prompts + 1))
    line="$(sed -n "${prompt_ln}p" "$rot")"
    prev="$(rc_para_before "$rot" "$start")"
    want="" login=""
    case "$line" in
      *'"ZENY_SEED_ADMIN_PASSWORD"'*) want="passo" ;;
      *'"ZENY_SEED_ADMIN_NEW_PASSWORD"'*) want="$i_admin" login="administrador" ;;
      *'"ZENY_SEED_MANAGER_PASSWORD"'*) want="$i_ger" login="gerente" ;;
      *'"ZENY_SEED_RECEPTIONIST_PASSWORD"'*) want="$i_rec" login="recepcao" ;;
      *'"ZENY_SEED_HOUSEKEEPER_PASSWORD"'*) want="$i_gov" login="governanta" ;;
      *Read-Host*) want="$i_pin" ;;
      *'Get-Credential governanta'*) want="$i_gov" login="governanta" ;;
      *) _assert_fail "janela de credencial conhecida (linha $prompt_ln)" "linha: $line" ;;
    esac
    got_items="$(grep -oE 'item [0-9]+ da folha' <<<"$prev" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ $//')"
    if [[ "$want" == "passo" ]]; then
      assert_eq "" "$got_items" "janela da senha atual do admin (linha $prompt_ln) não aponta item da folha"
      assert_contains "$prev" "passo $n_admin" "janela da senha atual do admin (linha $prompt_ln) aponta o passo $n_admin (.env)"
    elif [[ -n "$want" ]]; then
      assert_eq "$want" "$got_items" "janela da linha $prompt_ln aponta o item $want da folha, e só ele"
    fi
    if [[ -n "$login" ]]; then
      got_user="$(grep -oE 'Get-Credential (-UserName )?[A-Za-z0-9_.-]+' <<<"$line" | awk '{print $NF}')"
      assert_eq "$login" "$got_user" "janela da linha $prompt_ln com o usuário do item ($login)"
    fi
  done < <(awk '
    /^```powershell/ { s = NR; p = ""; next }
    /^```/ && s { if (p != "") print s ":" p; s = 0; next }
    s && /Get-Credential|Read-Host/ && p == "" { p = NR }' "$rot")
  assert_eq "7" "$n_prompts" "roteiro: 7 janelas de credencial (6 do seed e a da Governanta)"

  # 1.4 Os logins no app apontam o item do usuário digitado.
  while IFS= read -r l; do
    [[ -n "$l" ]] || continue
    user="$(grep -oE 'digite `[a-z]+`' <<<"$l" | grep -oE '[a-z]+`$' | tr -d '`')"
    case "$user" in
      gerente) want="$i_ger" ;; recepcao) want="$i_rec" ;; governanta) want="$i_gov" ;; *) want="?" ;;
    esac
    assert_eq "$want" "$(grep -oE 'item [0-9]+ da folha' <<<"$l" | grep -oE '[0-9]+' | tr '\n' ' ' | sed 's/ $//')" \
      "login de $user no app aponta o item $want da folha"
  done < <(grep -E 'Em \*\*Login\*\*, digite' "$rot")
  (($(grep -cE 'Em \*\*Login\*\*, digite' "$rot") >= 2)) || _assert_fail "roteiro: logins da Recepcionista e do Gerente no app"

  # 1.5 Todo "item N da folha" existe; depois do seed, a senha do admin é o item da senha nova.
  for n in $(grep -oE 'item [0-9]+ da folha' "$rot" | grep -oE '[0-9]+' | sort -u); do
    ((n >= 1 && n <= 5)) || _assert_fail "item $n citado existe na folha"
  done
  assert_contains "$(rc_step "$rot" "$n_seed")" "a senha do administrador é o item $i_admin da folha" \
    "depois do seed, a senha do admin é o item $i_admin (senha nova)"
  # 1.6 "Você vai precisar deles nos passos ..." = os passos (antes do passo final) que citam um item da folha.
  said="$(grep -oE 'precisar deles nos passos [0-9]+((, | e )[0-9]+)*' <<<"$folha" | grep -oE '[0-9]+' | sort -n | tr '\n' ' ')"
  used="$(for ((n = 1; n < last_step; n++)); do rc_step "$rot" "$n" | grep -qE 'item [0-9]+ da folha' && echo "$n"; done | tr '\n' ' ')"
  [[ -n "$said" ]] || _assert_fail "folha diz em que passos os valores são usados"
  assert_eq "$used" "$said" "folha: os passos citados são os que usam um item da folha"
}

# =============================================================================================================
# Linhas idênticas ao LEIA-ME (vindas do test_roteiro_m1_zero_ataque.sh, seção 5)
# =============================================================================================================
# roteiro_check_identical_to_leia ROTEIRO LEIA-ME TEST_DOCS : toda linha de servidor, seed e app do roteiro está
# igual num bloco PowerShell do LEIA-ME, e a lista de linhas comuns do test_docs.sh (só lida) cobre todas.
roteiro_check_identical_to_leia() {
  local rot="$1" leia_f="$2" docs_test="$3"
  local rot_ps leia_ps kinds rot_shared l docs_list
  rot_ps="$(rc_ps_code "$rot")"
  leia_ps="$(rc_ps_code "$leia_f")"
  kinds='^(docker |curl\.exe |\[Environment\]::SetEnvironmentVariable|\$pin |Remove-Variable pin$|java -jar app\\demo-seed\.jar |java "-Dzeny)'
  rot_shared="$(grep -E "$kinds" <<<"$rot_ps" | grep -vxF 'docker compose version' | sort -u)"
  (($(grep -c . <<<"$rot_shared") >= 18)) ||
    _assert_fail "roteiro: ao menos 18 linhas de servidor, seed e app em comum com o LEIA-ME" "achadas: $(grep -c . <<<"$rot_shared")"
  while IFS= read -r l; do
    [[ -n "$l" ]] || continue
    grep -qxF -- "$l" <<<"$leia_ps" || _assert_fail "linha do roteiro idêntica num bloco PowerShell do LEIA-ME" "$l"
  done <<<"$rot_shared"
  # A lista do test_docs.sh: não vazia e com toda linha comum do roteiro (só lida, nunca alterada aqui).
  # rc_docs_shared_lines só avalia as linhas da definição; qualquer outra forma é falha, sem avaliar nada.
  if ! docs_list="$(rc_docs_shared_lines "$docs_test")"; then
    _assert_fail "test_docs.sh: compose='...' e shared_lines=( ... ) com um item entre aspas por linha" "definição: $docs_list"
    docs_list=""
  fi
  (($(grep -c . <<<"$docs_list") >= 17)) ||
    _assert_fail "test_docs.sh: a lista de linhas comuns tem ao menos 17 linhas" "achadas: $(grep -c . <<<"$docs_list")"
  while IFS= read -r l; do
    [[ -n "$l" && "$l" != java\ \"-Dzeny* ]] || continue
    grep -qxF -- "$l" <<<"$docs_list" || _assert_fail "test_docs.sh confere a linha comum do roteiro" "$l"
  done <<<"$rot_shared"
}

# =============================================================================================================
# Travas do roteiro vindas do test_docs.sh
# =============================================================================================================
# roteiro_check_folha_before_secrets ROTEIRO : a folha de senhas vem antes da primeira linha que pede segredo.
roteiro_check_folha_before_secrets() {
  local roteiro="$1" folha_ln secret_ln
  folha_ln="$(grep -nF 'Folha de senhas' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  secret_ln="$(grep -nE 'Get-Credential|Read-Host|SetEnvironmentVariable' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  if [[ -z "$folha_ln" || -z "$secret_ln" ]] || ((folha_ln > secret_ln)); then
    _assert_fail "roteiro: a folha de senhas vem antes da primeira linha que pede segredo" \
      "folha: ${folha_ln:-ausente}; segredo: ${secret_ln:-ausente}"
  fi
}

# roteiro_check_shared_lines ROTEIRO LEIA-ME LINHA... : cada LINHA comum está, igual, num bloco PowerShell do
# roteiro e do LEIA-ME; toda linha de servidor ou seed do roteiro é uma delas.
roteiro_check_shared_lines() {
  local roteiro="$1" leia_file="$2"
  local -a shared_lines=("${@:3}")
  local rot_ps_code leia_ps_code l s found
  rot_ps_code="$(rc_ps_code_any "$roteiro")"
  leia_ps_code="$(rc_ps_code_any "$leia_file")"
  for l in "${shared_lines[@]}"; do
    grep -qxF -- "$l" <<<"$rot_ps_code" || _assert_fail "roteiro: bloco PowerShell com a linha comum" "$l"
    grep -qxF -- "$l" <<<"$leia_ps_code" || _assert_fail "LEIA-ME: bloco PowerShell com a linha comum" "$l"
  done
  # Toda linha de roteiro com docker, curl.exe, SetEnvironmentVariable, $pin ou o java do seed é uma das comuns.
  while IFS= read -r l; do
    [[ -n "$l" ]] || continue
    found=0
    for s in "${shared_lines[@]}"; do [[ "$l" == "$s" ]] && found=1 && break; done
    ((found)) || _assert_fail "roteiro: linha de servidor ou seed fora da lista comum com o LEIA-ME" "$l"
  done < <(grep -E '^(docker (run|compose -p|compose ls|volume) |curl\.exe |\[Environment\]::SetEnvironmentVariable|\$pin |Remove-Variable pin|java -jar app\\demo-seed)' <<<"$rot_ps_code")
}

# roteiro_check_seed_order ROTEIRO : o seed roda depois dos 6 segredos e o $pin sai da sessão depois de usado.
roteiro_check_seed_order() {
  local roteiro="$1" seed_ln last_set_ln pin_set_ln pin_rm_ln
  seed_ln="$(grep -nF 'java -jar app\demo-seed.jar' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  last_set_ln="$(grep -nF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$roteiro" 2>/dev/null | tail -1 | cut -d: -f1)"
  assert_eq "6" "$(grep -cF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$roteiro" 2>/dev/null)" "roteiro: 6 segredos do seed"
  if [[ -z "$seed_ln" || -z "$last_set_ln" ]] || ((seed_ln < last_set_ln)); then
    _assert_fail "roteiro: o java -jar do seed vem depois dos 6 SetEnvironmentVariable"
  fi
  pin_set_ln="$(grep -nF 'SetEnvironmentVariable("ZENY_SEED_MANAGER_PIN"' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  pin_rm_ln="$(grep -nxF 'Remove-Variable pin' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  if [[ -z "$pin_set_ln" || -z "$pin_rm_ln" ]] || ((pin_rm_ln < pin_set_ln)); then
    _assert_fail "roteiro: Remove-Variable pin depois do SetEnvironmentVariable do PIN"
  fi
}

# roteiro_check_ps51_lines ROTEIRO : o app no modo da demo, -Dzeny.* entre aspas, -UseBasicParsing, charset=utf-8 e
# o ETag com [string].
roteiro_check_ps51_lines() {
  local roteiro="$1" rot_ps_code
  rot_ps_code="$(rc_ps_code_any "$roteiro")"
  assert_contains "$rot_ps_code" 'java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar' "roteiro abre o app no modo da demo"
  assert_eq "" "$(grep -nE '(^|[^"])-Dzeny\.' <<<"$rot_ps_code")" "roteiro: -Dzeny.* sempre entre aspas"
  assert_eq "" "$(grep -E 'Invoke-WebRequest' <<<"$rot_ps_code" | grep -vF -- '-UseBasicParsing')" \
    "roteiro: todo Invoke-WebRequest com -UseBasicParsing"
  assert_eq "" "$(grep -E -- '-Body ' <<<"$rot_ps_code" | grep -vF 'charset=utf-8')" "roteiro: todo -Body com charset=utf-8"
  assert_eq "" "$(grep -E 'Headers\["ETag"\]' <<<"$rot_ps_code" | grep -vF '[string]$status.Headers["ETag"]')" \
    "roteiro: o ETag sempre com [string], intacto"
}

# roteiro_check_prompt_blocks ROTEIRO : bloco com Get-Credential ou Read-Host tem só essa linha e o parágrafo antes
# diz como rodá-lo e de onde vem o valor; o bloco do corpo do login explica o >>.
roteiro_check_prompt_blocks() {
  local roteiro="$1" n_prompt_blocks start end prompt_ln prev body_start body_prev
  # Bloco com Get-Credential ou Read-Host: a linha que pede a credencial é a única do bloco (colada junto com
  # outras, ela leria a linha seguinte como resposta), e o parágrafo logo antes diz como rodá-la. No passo do
  # seed, o parágrafo liga a janela a um item da folha (ou à senha inicial do passo 4).
  n_prompt_blocks=0
  while IFS=: read -r start end prompt_ln; do
    n_prompt_blocks=$((n_prompt_blocks + 1))
    assert_eq "1" "$((end - start - 1))" "roteiro: o bloco da linha $start (com credencial) tem só a linha da credencial"
    prev="$(awk -v s="$start" 'NR < s' "$roteiro" | awk 'BEGIN{RS=""} {p=$0} END{print p}')"
    assert_contains "$prev" "(cole, Enter, responda a janela)" \
      "roteiro: o bloco da linha $start (com credencial) diz como rodá-lo"
    if sed -n "${prompt_ln}p" "$roteiro" | grep -qE 'SetEnvironmentVariable|Read-Host'; then
      [[ "$prev" == *folha* || "$prev" == *"passo 4"* ]] ||
        _assert_fail "roteiro: o bloco da linha $start diz de onde vem o valor (folha ou passo 4)" "parágrafo: ${prev:0:200}"
    fi
  done < <(awk '
    /^```powershell/ { s = NR; p = ""; next }
    /^```/ && s { if (p != "") print s ":" NR ":" p; s = 0; next }
    s && /Get-Credential|Read-Host/ && p == "" { p = NR }
  ' "$roteiro")
  assert_eq "7" "$n_prompt_blocks" "roteiro: 7 blocos com credencial (5 senhas e o PIN no passo 5; a Governanta no 10.1)"
  # O bloco do corpo do login (hashtable em várias linhas) avisa que o >> do PowerShell é esperado.
  body_start="$(awk '/^```powershell/ { s = NR; next } /^```/ { s = 0; next } s && /\$loginBody = @\{/ { print s; exit }' "$roteiro")"
  body_prev="$(awk -v s="${body_start:-1}" 'NR < s' "$roteiro" | awk 'BEGIN{RS=""} {p=$0} END{print p}')"
  assert_contains "$body_prev" '`>>`' "roteiro: o parágrafo antes do corpo do login explica o >>"
}

# roteiro_check_no_secret_text ROTEIRO : sem segredo nem comando perigoso; HTTP pelo Invoke-RestMethod e curl.exe.
roteiro_check_no_secret_text() {
  local roteiro="$1" rot_ps_code
  rot_ps_code="$(rc_ps_code_any "$roteiro")"
  assert_eq "" "$(grep -nE -e '-Password\b' "$roteiro" 2>/dev/null)" "roteiro sem -Password"
  assert_eq "" "$(grep -niE 'password[[:space:]]*=[[:space:]]*["'"'"']?[^$<"'"'"'[:space:]]' "$roteiro" 2>/dev/null)" \
    "roteiro sem password= com valor literal"
  assert_eq "" "$(grep -niE '"password"[[:space:]]*:[[:space:]]*"[^<$]' "$roteiro" 2>/dev/null)" \
    "roteiro sem \"password\":\"valor\""
  assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*[[:space:]]*=[[:space:]]*"?[^<"[:space:]]' "$roteiro" 2>/dev/null)" \
    "roteiro sem valor atribuído a ZENY_SEED_*"
  assert_eq "" "$(grep -nE -e '--[a-z-]*(password|pin)([=[:space:]]|$)' "$roteiro" 2>/dev/null)" \
    "roteiro sem senha ou PIN em argumento"
  assert_eq "" "$(grep -nE -e '-p zeny( |$|`)' "$roteiro" 2>/dev/null)" "roteiro sem -p zeny"
  assert_contains "$rot_ps_code" "Invoke-RestMethod" "roteiro tem blocos PowerShell com os comandos HTTP"
  assert_eq "" "$(grep -nE '(^|[^.A-Za-z0-9_-])curl([[:space:]]|$)' <<<"$rot_ps_code")" "bloco PowerShell sem curl (alias), só curl.exe"
}

# roteiro_check_md_refs ROTEIRO : o roteiro só remete a LEIA-ME.md e a roteiro-mN.md; cada roteiro-mN.md citado é
# arquivo regular não vazio em docs/demo e o package.sh o exige (um nome inventado ou fora do pacote reprova). Era a
# lista m[123] repetida nos testes dos roteiros do M1 e do M2 (M3 E05, testador de etapa).
roteiro_check_md_refs() {
  local rot="$1" n f label="${1##*/}"
  if [[ ! -s "$rot" ]]; then
    _assert_fail "${label:-<vazio>}: o roteiro existe e não está vazio (sem ele, as remissões passariam no vazio)"
    return
  fi
  assert_eq "" "$(grep -noE '[A-Za-z0-9_./-]+\.md' "$rot" | grep -vE ':(roteiro-m[0-9]+|LEIA-ME)\.md$')" \
    "$label: não remete a outro .md"
  for n in $(grep -oE 'roteiro-m[0-9]+\.md' "$rot" | sort -u); do
    f="$ROOT/docs/demo/$n"
    assert_eq "1" "$([[ -f "$f" && ! -L "$f" && -s "$f" ]] && echo 1 || echo 0)" \
      "$label: cita $n e ele existe (arquivo regular, não vazio) em docs/demo"
    grep -qF "\"\$root/docs/demo/$n\"" "$ROOT/tools/server-env/package.sh" &&
      grep -E '^require_artifact ' "$ROOT/tools/server-env/package.sh" | grep -qF "docs/demo/$n" ||
      _assert_fail "$label: cita $n e o package.sh o exige (\"\$root/docs/demo/$n\" e require_artifact)"
  done
}

# roteiro_check_seed_secret_values ARQUIVO : nenhum valor de segredo do seed digitado numa linha (o PSReadLine do
# 5.1 grava o histórico em texto puro). Vale para o roteiro e para o LEIA-ME. As asserções são só negativas:
# sem o arquivo (caminho errado, vazio ou ""), elas passariam no vazio, então o arquivo tem de existir com texto.
roteiro_check_seed_secret_values() {
  local f="$1" n
  n="${f#"$ROOT"/}"
  if [[ ! -s "$f" ]]; then
    _assert_fail "${n:-<vazio>}: arquivo existe e não está vazio (sem ele, as travas de segredo passariam no vazio)"
    return
  fi
  assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[A-Z_]*["'"'"']?[[:space:]]*=' "$f" 2>/dev/null)" \
    "$n: nenhuma atribuição a ZENY_SEED_*PASSWORD ou *_PIN"
  assert_eq "" "$(grep -nE 'SetEnvironmentVariable\([^,]*ZENY_SEED_[^,]*,[[:space:]]*["'"'"']' "$f" 2>/dev/null)" \
    "$n: SetEnvironmentVariable de ZENY_SEED_* sem valor literal"
}
