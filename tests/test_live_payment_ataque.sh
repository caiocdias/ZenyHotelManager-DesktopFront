#!/usr/bin/env bash
# Ataque à M4 E03: a verificação ao vivo do recebimento (LivePaymentCheck) fica pulada sem o opt-in e nunca imprime
# valor de variável de ambiente, senha, URL nem o PaymentResponse (o toString gerado mostra bandeira, últimos dígitos,
# NSU e autorização), inclusive em mensagem de falha. A trava de test_live_folio_ataque.sh só alcança o arquivo dela;
# esta alcança o arquivo novo e fecha o furo do argumento não literal (println(texto) com variável). Estático: não
# chama o Gradle nem o servidor. Só formas portáveis (grep -E, sem -P). Cada trava é provada por mutação: a cópia
# limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/folio/LivePaymentCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo do recebimento existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_folio_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# conta TEXTO PADRAO_ERE: quantas ocorrências do padrão há no texto.
conta() { grep -oE "$2" <<<"$1" | wc -l | tr -d ' '; }

# Os literais (com as continuações `" + "`) passados a println e fail: o texto que pode chegar ao log.
mensagens() { grep -oE '(println|fail)\(("([^"\\]|\\.)*"( \+ )?)+' <<<"$1"; }

# O que não pode aparecer em texto que vai ao log (ERE, sem diferenciar maiúsculas).
SEGREDO='env|password|(^|[^a-z])senha|secret|login|getenv|(^|[^a-z])nsu([^a-z]|$)|authorization|last4|brand|(^|[^a-z])pin([^a-z]|$)'

# violacoes APP GRADLE: uma linha por trava quebrada (nada = tudo certo). Os dois são código ativo.
violacoes() {
  local app="$1" gradle="$2" p v
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. Opt-in no build (entrada e upToDateWhen) e no teste.
  tem "$gradle" 'inputs.property("zenyLivePayment", providers.environmentVariable("ZENY_LIVE_PAYMENT").orElse(""))' \
    "ZENY_LIVE_PAYMENT é entrada do tasks.test"
  tem "$gradle" 'val livePayment = providers.environmentVariable("ZENY_LIVE_PAYMENT")' \
    "o provider de ZENY_LIVE_PAYMENT"
  tem "$gradle" 'livePayment.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_PAYMENT"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_PAYMENT") == "1"' "sem ZENY_LIVE_PAYMENT=1 o check pula"
  tem "$app" '@BeforeTest fun requireOptIn()' "o opt-in roda antes de cada teste"

  # 2. Credenciais só pelo env(), que falha citando só o NOME; cada uma lida uma vez.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
    [[ "$(conta "$app" "env\\(\"$v\"\\)")" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem "$app" '?: fail("defina $name para a verificação real")' "env() falha citando só o nome da variável"
  [[ "$(conta "$app" 'System\.getenv\(')" == 2 ]] || echo "proibido: System.getenv fora do opt-in e do env()"
  tem "$app" 'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
    "login e senha vão direto ao login, sem variável que outro trecho imprima"
  tem "$app" 'ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")' \
    "a URL inválida falha sem o valor"

  # 3. Nada gravado em disco além do print da tela.
  [[ "$(conta "$app" 'ImageIO\.write\(')" == 1 ]] || echo "proibido: outra imagem gravada"
  for p in 'writeBytes' 'writeText' 'FileOutputStream' 'Files.write' 'outputStream(' 'appendText'; do
    nao_tem "$app" "$p" "gravação em disco ($p)"
  done

  # 4. Nenhuma saída ou interpolação de variável de ambiente, senha, URL, do PaymentResponse nem de dado de cartão.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$url' '${url' '${core' \
    '$connection' '${connection' 'printStackTrace' 'System.out' 'System.err' ' print(' 'getenv()}' \
    '$payment ' '$payment"' '$payment)' '${payment}' '$payment,' '$added' '${added' '$received' '${received' \
    '.nsu}' '.authorizationCode}' '.cardLast4}' '.cardBrand}' '$intents' '${intents' '${screen.payments.intents'; do
    nao_tem "$app" "$p" "saída ou interpolação de $p"
  done
  # Todo println e todo fail: só texto literal e templates de dados da demo (número, saldo, mensagem da tela).
  # "nsu", "senha" e "pin" só como palavra: "consumo", "desenhado" e "pinta" os contêm.
  if mensagens "$app" | grep -qiE "$SEGREDO"; then
    echo "proibido: println/fail com credencial ou dado de cartão"
  fi
  # As mensagens dos assert* (o terceiro argumento de assertEquals, o de assertIs...) também chegam ao log e escapam
  # de mensagens(): nenhum literal do arquivo, fora os nomes das variáveis de ambiente, fala de credencial ou cartão.
  if grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -vE '^"ZENY_[A-Z_]*"$' | grep -qiE "$SEGREDO"; then
    echo "proibido: literal com credencial ou dado de cartão"
  fi
  # O furo da trava da E02: println/fail com argumento não literal (variável, chamada) escapa de mensagens(). Aqui,
  # todo println e todo fail começa por um literal ou por uma constante NEED_SEED/ZENY em maiúsculas.
  [[ "$(conta "$app" '(println|fail)\(')" == "$(conta "$app" '(println|fail)\(("|[A-Z][A-Z_]*\))')" ]] ||
    echo "proibido: println/fail com argumento que não é literal nem constante"
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$gradle")"
assert_eq "" "$achado" "LivePaymentCheck não vaza ambiente, senha, URL nem dado de cartão"

# As mensagens de println/fail do arquivo real são encontradas (a trava 4 não passa por não ver nada).
assert_eq "true" "$( (($(mensagens "$app" | wc -l) >= 4)) && echo true || echo false)" \
  "o extrator acha os println/fail do LivePaymentCheck"

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

muta "opt-in removido" 'System.getenv("ZENY_LIVE_PAYMENT") == "1"' 'true' app
muta "opt-in de outra verificação" 'System.getenv("ZENY_LIVE_PAYMENT") == "1"' \
  'System.getenv("ZENY_LIVE_FOLIO") == "1"' app
muta "opt-in fora das entradas" 'inputs.property("zenyLivePayment"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'livePayment.orNull != "1"' 'true' gradle
muta "env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "senha em variável" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "PaymentResponse impresso" 'return payment }' 'println("LivePaymentCheck: $payment"); return payment }' app
muta "PaymentResponse numa falha" '"o pagamento não foi em dinheiro"' '"o pagamento ${payment} não foi em dinheiro"' app
muta "NSU numa mensagem" '"o pagamento não foi em dinheiro"' '"o pagamento não foi em dinheiro (${payment.nsu})"' app
muta "lista de pagamentos impressa" '"a repetição com a mesma chave não deu um pagamento só"' \
  '"a repetição com a mesma chave não deu um pagamento só: $added"' app
muta "println com argumento não literal" 'return payment }' 'println(payment.toString()); return payment }' app
muta "fail com argumento não literal" 'fail("ZENY_LIVE_URL inválida")' 'fail(url.toString())' app
muta "senha numa mensagem continuada" '"a sessão da Recepcionista caiu"' \
  '"a sessão da Recepcionista caiu; " + "senha: "' app
muta "stack trace impresso" 'runCatching { runBlocking { connection.logout() } }' \
  'runCatching { runBlocking { connection.logout() } }.onFailure { it.printStackTrace() }' app
muta "resposta gravada em disco" 'val file = File("build/live/$name.png")' \
  'File("build/live/pagamento.txt").writeText("x"); val file = File("build/live/$name.png")' app

finish
