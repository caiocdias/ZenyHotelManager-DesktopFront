#!/usr/bin/env bash
# Ataque à M4 E02: a verificação ao vivo da conta da hospedagem (LiveFolioCheck) fica pulada sem o opt-in e nunca
# imprime valor de variável de ambiente, senha, PIN do Gerente nem o texto do PDF, inclusive em mensagem de falha. A
# trava de test_live_cashier_ataque.sh só alcança os arquivos do caixa; esta alcança o arquivo novo. Estático: não
# chama o Gradle nem o servidor. Só formas portáveis (grep -E, sem -P). Cada trava é provada por mutação: a cópia
# limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/folio/LiveFolioCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo da conta existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_cashier_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# conta TEXTO PADRAO_ERE: quantas ocorrências do padrão há no texto.
conta() { grep -oE "$2" <<<"$1" | wc -l | tr -d ' '; }

# Os literais (com as continuações `" + "`) passados a println e fail: o texto que pode chegar ao log.
mensagens() { grep -oE '(println|fail)\(("([^"\\]|\\.)*"( \+ )?)+' <<<"$1"; }

# violacoes APP GRADLE: uma linha por trava quebrada (nada = tudo certo). Os dois são código ativo.
violacoes() {
  local app="$1" gradle="$2" p v
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. Opt-in no build (entrada e upToDateWhen) e no teste.
  tem "$gradle" 'inputs.property("zenyLiveFolio", providers.environmentVariable("ZENY_LIVE_FOLIO").orElse(""))' \
    "ZENY_LIVE_FOLIO é entrada do tasks.test"
  tem "$gradle" 'val liveFolio = providers.environmentVariable("ZENY_LIVE_FOLIO")' "o provider de ZENY_LIVE_FOLIO"
  tem "$gradle" 'liveFolio.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_FOLIO"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_FOLIO") == "1"' "sem ZENY_LIVE_FOLIO=1 o check pula"
  tem "$app" '@BeforeTest fun requireOptIn()' "o opt-in roda antes de cada teste"

  # 2. Credenciais só pelo env(), que falha citando só o NOME; cada uma lida uma vez.
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

  # 3. O Gerente: classe comum (não data class) com toString mascarado; login e PIN só vão ao coordenador.
  tem "$app" 'private class Approver(val login: String, val pin: String) { override fun toString(): String = "Approver(***)" }' \
    "o Approver esconde login e PIN no toString"
  nao_tem "$app" 'data class Approver' "data class Approver (o toString mostraria o PIN)"
  tem "$app" 'Approver(env("ZENY_LIVE_APPROVER_LOGIN"), env("ZENY_LIVE_APPROVER_PIN"))' \
    "login e PIN do Gerente vão direto ao Approver"
  [[ "$(conta "$app" 'approver\.pin')" == 1 ]] || echo "proibido: o PIN usado fora do diálogo de aprovação"
  tem "$app" 'screen.approvals.pin = approver.pin' "o PIN vai só ao diálogo de aprovação"
  [[ "$(conta "$app" 'approver\.login')" == 1 ]] || echo "proibido: o login do Gerente usado fora do diálogo"
  tem "$app" 'screen.approvals.login = approver.login' "o login do Gerente vai só ao diálogo de aprovação"

  # 4. O PDF fica em memória: nada gravado em disco além do print da tela, e o texto nunca impresso.
  [[ "$(conta "$app" 'ImageIO\.write\(')" == 1 ]] || echo "proibido: outra imagem gravada"
  for p in 'writeBytes' 'writeText' 'FileOutputStream' 'Files.write' 'pdf.save(' '.save(' 'outputStream('; do
    nao_tem "$app" "$p" "gravação em disco ($p)"
  done
  for p in '$text' '${text' '$joined' '${joined' '${pdf' '$pdf' '$digits' '${digits'; do
    nao_tem "$app" "$p" "o texto do PDF em template ($p)"
  done

  # 5. Nenhuma saída ou interpolação de variável de ambiente, senha, PIN, Approver ou do que o coordenador guardou.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$pin' '${pin' '$approver' \
    '${approver' '$url' '${url' 'approvals.pin}' 'approvals.login}' '.pin}' '.login}' 'printStackTrace' \
    'System.out' 'System.err' ' print(' 'getenv()}'; do
    nao_tem "$app" "$p" "saída ou interpolação de $p"
  done
  # Todo println e todo fail: só texto literal e templates de dados da demo (número da conta, saldo, mensagem da tela).
  if mensagens "$app" | grep -qiE 'env|password|senha|secret|pin|login|getenv|approver'; then
    echo "proibido: println/fail com credencial"
  fi
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$gradle")"
assert_eq "" "$achado" "LiveFolioCheck não vaza ambiente, senha, PIN nem o PDF"

# As mensagens de println/fail do arquivo real são encontradas (a trava 5 não passa por não ver nada).
assert_eq "true" "$( (($(mensagens "$app" | wc -l) >= 4)) && echo true || echo false)" \
  "o extrator acha os println/fail do LiveFolioCheck"

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

muta "opt-in removido" 'System.getenv("ZENY_LIVE_FOLIO") == "1"' 'true' app
muta "opt-in fora das entradas" 'inputs.property("zenyLiveFolio"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'liveFolio.orNull != "1"' 'true' gradle
muta "env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "PIN lido fora do env()" 'env("ZENY_LIVE_APPROVER_PIN")' 'System.getenv("ZENY_LIVE_APPROVER_PIN")' app
muta "senha em variável" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "Approver vira data class" 'private class Approver(' 'private data class Approver(' app
muta "Approver sem toString mascarado" 'override fun toString(): String = "Approver(***)"' \
  'override fun toString(): String = "Approver($login)"' app
muta "PIN impresso" 'screen.approvals.pin = approver.pin' \
  'screen.approvals.pin = approver.pin; println("PIN ${approver.pin}")' app
muta "PIN numa mensagem de falha" '"o Gerente foi recusado' '"o Gerente ${approver.pin} foi recusado' app
muta "PIN relido do coordenador" '"o diálogo de aprovação não aceita o Gerente"' \
  '"o diálogo de aprovação não aceita ${screen.approvals.pin}"' app
muta "Approver interpolado" '"o diálogo de aprovação não aceita o Gerente"' \
  '"o diálogo de aprovação não aceita $approver"' app
muta "texto do PDF impresso" 'val joined = text.replace(BETWEEN_DIGITS, "")' \
  'val joined = text.replace(BETWEEN_DIGITS, ""); println("PDF: $text")' app
muta "PDF gravado em disco" 'val text = Loader.loadPDF(pdf.bytes)' \
  'File("build/live/extrato.pdf").writeBytes(pdf.bytes); val text = Loader.loadPDF(pdf.bytes)' app
muta "login na mensagem de println" 'println("LiveFolioCheck: estorno aprovado pelo Gerente' \
  'println("LiveFolioCheck: login ZENY_LIVE_APPROVER_LOGIN estorno aprovado pelo Gerente' app
muta "senha numa mensagem de fail continuada" '"o estorno não pediu aprovação (${form.message}); ' \
  '"o estorno não pediu aprovação (${form.message}); " + "senha da Recepcionista: ' app
muta "stack trace impresso" 'runCatching { runBlocking { connection.logout() } }' \
  'runCatching { runBlocking { connection.logout() } }.onFailure { it.printStackTrace() }' app

finish
