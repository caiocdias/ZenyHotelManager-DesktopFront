#!/usr/bin/env bash
# Ataque à M4 E08: a verificação ao vivo das correções da conta (LiveFolioCorrectionCheck) fica pulada sem o opt-in e
# nunca imprime valor de variável de ambiente, senha, PIN do aprovador, URL nem as respostas do sdk, inclusive em
# mensagem de falha. No padrão de test_live_preauth_ataque.sh, com o que esta verificação acrescenta: o login e o PIN
# do Gerente (lidos uma vez, guardados num Approver cujo toString não mostra nada e usados só no coordenador de
# aprovação), a lista fechada dos templates dos literais e a lista fechada, argumento por argumento, do que um assert*
# recebe (um argumento novo não entra sem mudar aqui).
#
# Decisão do testador sobre os campos do servidor que aparecem numa falha: item.amount, original.amount,
# item.description, original.description, o reason do servidor (FolioText.serviceChargeRemoved(reason)) e os ids das
# taxas (chargeId, charge?.id) ficam aceitos explicitamente. São o valor e a descrição do consumo que a própria
# verificação lançou na demo, a frase que o servidor registra na remoção da taxa e ids de lançamentos da demo: nenhum
# é credencial, PIN, URL nem a resposta inteira do sdk.
#
# Estático: não chama o Gradle nem o servidor. Só formas portáveis (grep -E, sem -P). Cada trava é provada por mutação:
# a cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/folio/LiveFolioCorrectionCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo das correções existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_preauth_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# conta TEXTO PADRAO_ERE: quantas ocorrências do padrão há no texto.
conta() { grep -oE "$2" <<<"$1" | wc -l | tr -d ' '; }

# Os literais (com as continuações `" + "`) passados a println, fail e error: o texto que pode chegar ao log.
mensagens() { grep -oE '(println|fail|error)\(("([^"\\]|\\.)*"( \+ )?)+' <<<"$1"; }

# O que não pode aparecer em texto que vai ao log (ERE, sem diferenciar maiúsculas).
SEGREDO='env|password|(^|[^a-z])senha|secret|login|getenv|(^|[^a-z])pin([^a-z]|$)|token'

# Os argumentos dos assert*, um por linha, com a forma "assertX<TAB>argumento" (parênteses e literais balanceados).
argumentos_assert() {
  perl -e '
    my $s = do { local $/; <STDIN> };
    while ($s =~ /\b(assert\w*)(?:<[^>()]*>)?\(/g) {
      my ($name, $i, $depth, $arg, @args, $q) = ($1, pos($s), 1, "", ());
      while ($i < length($s) && $depth > 0) {
        my $c = substr($s, $i, 1);
        if ($q) { $arg .= $c; if ($c eq "\\") { $arg .= substr($s, ++$i, 1) } elsif ($c eq "\"") { $q = 0 } }
        elsif ($c eq "\"") { $q = 1; $arg .= $c }
        elsif ($c eq "(" || $c eq "{" || $c eq "[") { $depth++; $arg .= $c }
        elsif ($c eq ")" || $c eq "}" || $c eq "]") { $depth--; $arg .= $c if $depth > 0 }
        elsif ($c eq "," && $depth == 1) { push @args, $arg; $arg = "" }
        else { $arg .= $c }
        $i++;
      }
      push @args, $arg;
      for my $a (@args) { $a =~ s/^\s+|\s+$//g; print "$name\t$a\n" if length $a }
    }
  ' <<<"$1"
}

# A lista fechada do que um assert* recebe além de um literal: cada argumento exato, separado por "|".
ARGUMENTOS_ASSERT='|FolioText.ADJUSTMENT_DONE|FolioText.money(adjustment.amount)|FolioText.serviceChargeRemoved(reason)'
ARGUMENTOS_ASSERT+='|FolioText.splitAmong(2)|FolioText.transferredTo(target.number)|POSTINGS_ADJUST|SCREEN_ADJUSTMENT'
ARGUMENTOS_ASSERT+='|charge?.id|charge?.serviceCharge == true|chargeId|chargeIn.id in there|chargeOut.id in here'
ARGUMENTOS_ASSERT+='|connection.state.value|form.checked|form.remaining|holder.adjustment|holder.canAdjust(row)'
ARGUMENTOS_ASSERT+='|holder.canRemoveServiceCharge(row)|holder.canTransfer(row)|holder.removal|holder.split'
ARGUMENTOS_ASSERT+='|holder.transfer|item >= 0|item.amount|item.description|moved.incoming.id in there'
ARGUMENTOS_ASSERT+='|moved.outgoing.id in here|original.amount|original.description|required.permission'
ARGUMENTOS_ASSERT+='|response.transfers.isNotEmpty()|screen.approvals.dialog?.canConfirm == true'
ARGUMENTOS_ASSERT+='|screen.approvals.dialog?.message|screen.folio.notice|setOf(itemId)|'

# A lista fechada dos templates dos literais (dados da demo e da tela, constantes, o nome da variável no env()).
TEMPLATES=' $ADJUSTED $MAX_TARGET_PAGES $POSTINGS_ADJUST $POSTINGS_TRANSFER $SERVICE_CHARGE_REMOVE $name '
TEMPLATES+='${FolioText.money(item.amount)} ${UUID.randomUUID().toString().take(TAG_LENGTH)} ${form.message} '
TEMPLATES+='${screen.approvals.dialog?.message} ${targets.error} '

# violacoes APP GRADLE: uma linha por trava quebrada (nada = tudo certo). Os dois são código ativo.
violacoes() {
  local app="$1" gradle="$2" p v nome arg
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. Opt-in no build (entrada e upToDateWhen) e no teste.
  tem "$gradle" \
    'inputs.property("zenyLiveCorrection", providers.environmentVariable("ZENY_LIVE_CORRECTION").orElse(""))' \
    "ZENY_LIVE_CORRECTION é entrada do tasks.test"
  tem "$gradle" 'val liveCorrection = providers.environmentVariable("ZENY_LIVE_CORRECTION")' \
    "o provider de ZENY_LIVE_CORRECTION"
  tem "$gradle" 'liveCorrection.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_CORRECTION"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_CORRECTION") == "1"' "sem ZENY_LIVE_CORRECTION=1 o check pula"
  tem "$app" '@BeforeTest fun requireOptIn()' "o opt-in roda antes de cada teste"

  # 2. Credenciais e PIN só pelo env(), que falha citando só o NOME; cada uma lida uma vez.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD ZENY_LIVE_APPROVER_LOGIN \
    ZENY_LIVE_APPROVER_PIN; do
    [[ "$(conta "$app" "env\\(\"$v\"\\)")" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem "$app" '?: fail("defina $name para a verificação real")' "env() falha citando só o nome da variável"
  [[ "$(conta "$app" 'System\.getenv\(')" == 2 ]] || echo "proibido: System.getenv fora do opt-in e do env()"
  tem "$app" 'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
    "login e senha vão direto ao login, sem variável que outro trecho imprima"
  tem "$app" 'ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")' \
    "a URL inválida falha sem o valor"

  # 3. O aprovador: login e PIN direto do env() para o Approver, que não se mostra, e dele só para o coordenador.
  tem "$app" 'Approver(env("ZENY_LIVE_APPROVER_LOGIN"), env("ZENY_LIVE_APPROVER_PIN"))' \
    "login e PIN do Gerente vão direto ao Approver"
  tem "$app" 'override fun toString(): String = "Approver(***)"' "o Approver não mostra o login nem o PIN"
  [[ "$(conta "$app" '\.pin([^A-Za-z0-9_]|$)')" == 2 ]] || echo "proibido: o PIN usado fora do coordenador"
  [[ "$(conta "$app" 'approver\.pin')" == 1 ]] || echo "proibido: o PIN do Approver lido mais de uma vez"
  tem "$app" 'screen.approvals.pin = approver.pin' "o PIN vai só ao coordenador de aprovação"
  [[ "$(conta "$app" 'approver\.login')" == 1 ]] || echo "proibido: o login do Approver lido mais de uma vez"
  tem "$app" 'screen.approvals.login = approver.login' "o login do Gerente vai só ao coordenador de aprovação"

  # 4. Nada gravado em disco além do print da tela.
  [[ "$(conta "$app" 'ImageIO\.write\(')" == 1 ]] || echo "proibido: outra imagem gravada"
  for p in 'writeBytes' 'writeText' 'FileOutputStream' 'Files.write' 'outputStream(' 'appendText' 'printWriter'; do
    nao_tem "$app" "$p" "gravação em disco ($p)"
  done

  # 5. Nenhuma saída ou interpolação de ambiente, senha, PIN, URL nem das respostas do sdk.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$url' '${url' '${core' \
    '$connection' '${connection' '$approver' '${approver' '.pin}' '.login}' 'printStackTrace' 'System.out' \
    'System.err' ' print(' 'getenv()}' '$response' '${response' '$moved' '${moved' '$removal' '${removal' \
    '$adjustment' '${adjustment' '$original' '${original' '$posted' '${posted' '$required' '${required' \
    '${screen.corrections' '.last}' '$after' '${after' '.plus(' 'Log.' 'logger'; do
    nao_tem "$app" "$p" "saída ou interpolação de $p"
  done
  # Todo println, fail e error: só texto literal e templates da lista fechada.
  if mensagens "$app" | grep -qiE "$SEGREDO"; then
    echo "proibido: println/fail/error com credencial ou PIN"
  fi
  # As mensagens dos assert* também chegam ao log: nenhum literal, fora os nomes das variáveis de ambiente, fala de
  # credencial, PIN ou token.
  if grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -vE '^"ZENY_[A-Z_]*"$' | grep -qiE "$SEGREDO"; then
    echo "proibido: literal com credencial, PIN ou token"
  fi
  while IFS= read -r p; do
    [[ -z "$p" || "$TEMPLATES" == *" $p "* ]] || echo "proibido: template fora da lista ($p)"
  done < <(grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -oE '\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')

  # 6. (a) println/fail/error começa por um literal ou por uma constante em maiúsculas.
  [[ "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(')" == \
    "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(("|[A-Z][A-Z_]*\))')" ]] ||
    echo "proibido: println/fail/error com argumento que não é literal nem constante"
  # (b) Concatenação de literal com não literal, nos dois sentidos.
  if grep -qE '" \+ [^"]|[^" ] \+ "' <<<"$app"; then
    echo "proibido: concatenação de texto com não literal"
  fi
  # (c) throw: só exceção construída a partir de um literal.
  [[ "$(conta "$app" '(^|[^A-Za-z_])throw ')" == \
    "$(conta "$app" '(^|[^A-Za-z_])throw [A-Za-z.]+\(([A-Za-z.]+\()*"')" ]] ||
    echo "proibido: throw com argumento que não é literal"
  # (d) A mensagem preguiçosa de check/require/checkNotNull/requireNotNull é um literal.
  if grep -qE '(check|require|checkNotNull|requireNotNull)\([^()]*(\([^()]*\))*[^()]*\) \{ [^"]' <<<"$app"; then
    echo "proibido: mensagem de check/require que não é literal"
  fi
  # (e) Todo argumento de assert* é um literal ou está na lista fechada.
  while IFS=$'\t' read -r nome arg; do
    [[ -z "$nome" || "$arg" == \"* ]] && continue
    [[ "$ARGUMENTOS_ASSERT" == *"|$arg|"* ]] || echo "proibido: argumento de $nome fora da lista ($arg)"
  done < <(argumentos_assert "$app")
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$gradle")"
assert_eq "" "$achado" "LiveFolioCorrectionCheck não vaza ambiente, senha, PIN, URL nem resposta do sdk"

# Os extratores acham o que vigiam (as travas não passam por não ver nada).
assert_eq "true" "$( (($(mensagens "$app" | wc -l) >= 4)) && echo true || echo false)" \
  "o extrator acha os println/fail do LiveFolioCorrectionCheck"
assert_eq "true" "$( (($(argumentos_assert "$app" | wc -l) >= 60)) && echo true || echo false)" \
  "o extrator acha os argumentos dos assert* do LiveFolioCorrectionCheck"
assert_eq "true" "$(grep -qiE "$SEGREDO" <<<'"o PIN do Gerente"' && echo true || echo false)" \
  "a regra de segredo pega o PIN"

# muta DESCRIÇÃO ANTIGO NOVO app|gradle: o mutante (primeira ocorrência trocada) tem de quebrar alguma trava.
muta() {
  local m_app="$app" m_gradle="$gradle"
  case "$4" in
    app)
      [[ "$m_app" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
      m_app="${m_app/"$2"/"$3"}" ;;
    gradle)
      [[ "$m_gradle" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
      m_gradle="${m_gradle/"$2"/"$3"}" ;;
  esac
  [[ -n "$(violacoes "$m_app" "$m_gradle")" ]] || _assert_fail "mutante não detectado: $1"
}

# O opt-in.
muta "opt-in removido" 'System.getenv("ZENY_LIVE_CORRECTION") == "1"' 'true' app
muta "opt-in de outra verificação" 'System.getenv("ZENY_LIVE_CORRECTION") == "1"' \
  'System.getenv("ZENY_LIVE_PREAUTH") == "1"' app
muta "opt-in fora das entradas" 'inputs.property("zenyLiveCorrection"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'liveCorrection.orNull != "1"' 'true' gradle

# As credenciais e o PIN do aprovador.
muta "env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "PIN lido fora do env()" 'env("ZENY_LIVE_APPROVER_PIN")' 'System.getenv("ZENY_LIVE_APPROVER_PIN")' app
muta "PIN lido duas vezes" 'val core = ZenyCore(' 'env("ZENY_LIVE_APPROVER_PIN"); val core = ZenyCore(' app
muta "senha em variável" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "Approver se mostra" 'override fun toString(): String = "Approver(***)"' \
  'override fun toString(): String = "Approver($login)"' app
muta "Approver sem toString próprio" 'override fun toString(): String = "Approver(***)"' '' app
muta "PIN numa falha" '"o diálogo de aprovação não aceita o Gerente"' \
  '"o diálogo de aprovação não aceita o Gerente (${approver.pin})"' app
muta "Approver inteiro numa falha" '"o diálogo de aprovação não aceita o Gerente"' \
  '"o diálogo de aprovação não aceita o Gerente ($approver)"' app
muta "PIN impresso" 'runOnUiThread { screen.approvals.confirm() }' \
  'println(approver.pin); runOnUiThread { screen.approvals.confirm() }' app
muta "PIN em outro lugar" 'runOnUiThread { screen.approvals.confirm() }' \
  'val typed = screen.approvals.pin; runOnUiThread { screen.approvals.confirm() }' app
muta "PIN do coordenador num assert" 'assertEquals(POSTINGS_ADJUST, required.permission' \
  'assertEquals(POSTINGS_ADJUST, screen.approvals.pin' app
muta "a palavra PIN num literal" '"o diálogo de aprovação não aceita o Gerente"' \
  '"o diálogo de aprovação não aceita o PIN do Gerente"' app
muta "stack trace impresso" 'runCatching { runBlocking { connection.logout() } }' \
  'runCatching { runBlocking { connection.logout() } }.onFailure { it.printStackTrace() }' app
muta "resposta gravada em disco" 'val file = File("build/live/$name.png")' \
  'File("build/live/correction.txt").writeText("x"); val file = File("build/live/$name.png")' app

# As respostas do sdk em texto (interpolação e a lista fechada de templates).
muta "resposta da divisão numa falha" '"a divisão não devolveu a resposta"' \
  '"a divisão não devolveu a resposta: $response"' app
muta "transferência numa falha" '"a resposta não traz o consumo transferido"' \
  '"a resposta não traz o consumo transferido: ${moved}"' app
muta "ajuste inteiro numa falha" '"o ajuste não é de R$ 1,00"' '"o ajuste não é de R$ 1,00 (${adjustment})"' app
muta "lançamentos relidos numa falha" '"o valor do consumo mudou"' '"o valor do consumo mudou: $after"' app
muta "última resposta da porta" '"a transferência não devolveu a resposta"' \
  '"a transferência não devolveu a resposta (${screen.corrections.last})"' app
muta "template novo fora da lista" '"o aviso da divisão"' '"o aviso da divisão (${screen.secondId})"' app
muta "pedido de aprovação inteiro" '"a permissão pedida no APPROVAL_REQUIRED"' \
  '"a permissão pedida no APPROVAL_REQUIRED (${required})"' app

# Concatenação, error(), throw, mensagem preguiçosa e o objeto inteiro num assert*.
muta "concatenação com a resposta" '"a divisão não devolveu a resposta"' \
  '"a divisão não devolveu a resposta: " + screen.corrections.last' app
muta "concatenação ao contrário" '"a remoção falhou: ${form.message}"' \
  'removal.toString() + "a remoção falhou: ${form.message}"' app
muta "concatenação com plus" '"o aviso da transferência"' '"o aviso da transferência".plus(response)' app
muta "error com toString" 'return itemId' 'if (itemId.isEmpty()) error(response.toString()); return itemId' app
muta "throw com a resposta" 'return itemId' \
  'if (itemId.isEmpty()) throw IllegalStateException(response.toString()); return itemId' app
muta "check com mensagem não literal" 'checkNotNull(shown) { "o painel da conta não foi montado" }' \
  'checkNotNull(shown) { screen.corrections.last.toString() }' app
muta "assertEquals com o lançamento inteiro" 'assertEquals(item.amount, original.amount' \
  'assertEquals(item.amount, original' app
muta "assertNotNull com a resposta" 'assertTrue(response.transfers.isNotEmpty()' \
  'assertNotNull(response); assertTrue(response.transfers.isNotEmpty()' app
muta "assertEquals com a lista relida" 'assertEquals(item.description, original.description' \
  'assertEquals(item.description, after' app
muta "assertEquals com a transferência" 'assertEquals(chargeId, charge?.id' 'assertEquals(chargeId, moved' app
muta "assertContains com os ids" 'assertTrue(moved.outgoing.id in here' 'assertContains(here, moved.outgoing.id' app
muta "assertEquals com o ajuste" 'assertEquals(SCREEN_ADJUSTMENT, FolioText.money(adjustment.amount)' \
  'assertEquals(SCREEN_ADJUSTMENT, adjustment' app

finish
