#!/usr/bin/env bash
# Ataque à M4 E10: a verificação ao vivo das cauções a vencer (LiveExpiringPreAuthorizationCheck) fica pulada sem o
# opt-in ZENY_LIVE_EXPIRING, lê as credenciais uma vez e nunca imprime valor de variável de ambiente, senha, URL nem as
# respostas de caução do sdk (o toString gerado de CardPreAuthorizationResponse mostra bandeira, últimos dígitos, NSU e
# autorização), inclusive em mensagem de falha, concatenação, error/throw e assert* com o objeto inteiro. A verificação
# é só de leitura: nenhuma escrita na demo (só as três portas de leitura saem da conexão e nenhum método de escrita é
# chamado) e nenhum arquivo gravado. No padrão de test_live_preauth_ataque.sh.
# Estático: não chama o Gradle nem o servidor. Só formas portáveis (grep -E, sem -P). Cada trava é provada por mutação:
# a cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/folio/LiveExpiringPreAuthorizationCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo das cauções a vencer existe"
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
SEGREDO='env|password|(^|[^a-z])senha|secret|login|getenv|(^|[^a-z])nsu([^a-z]|$)|authorization|last4|brand|(^|[^a-z])pin([^a-z]|$)'

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

# O que um assert* pode receber além do literal da mensagem: número, constante, a lista fechada abaixo (a espera, as
# validades, o identificador da conta e o texto de erro do estado). Nunca a resposta inteira do sdk, a linha inteira
# nem um campo de cartão. Um argumento novo não entra sem mudar aqui.
ARGUMENTOS_ASSERT=' done true times times.sorted() rows.isNotEmpty() rows.first().folioId folio.id.toString() '
ARGUMENTOS_ASSERT+='state.error state.untilError connection.state.value rows.all { VALIDITY_SHAPE.matches(it.expires) } '

argumento_permitido() {
  local a="$1"
  [[ "$a" == \"* ]] && return 0
  [[ "$a" =~ ^-?[0-9]+L?$ ]] && return 0
  [[ "$a" =~ ^[A-Z][A-Z0-9_]*$ ]] && return 0
  [[ "$ARGUMENTOS_ASSERT" == *" $a "* ]] && return 0
  return 1
}

# violacoes APP GRADLE: uma linha por trava quebrada (nada = tudo certo). Os dois são código ativo.
violacoes() {
  local app="$1" gradle="$2" p v nome arg
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. Opt-in no build (entrada e upToDateWhen) e no teste.
  tem "$gradle" 'inputs.property("zenyLiveExpiring", providers.environmentVariable("ZENY_LIVE_EXPIRING").orElse(""))' \
    "ZENY_LIVE_EXPIRING é entrada do tasks.test"
  tem "$gradle" 'val liveExpiring = providers.environmentVariable("ZENY_LIVE_EXPIRING")' \
    "o provider de ZENY_LIVE_EXPIRING"
  tem "$gradle" 'liveExpiring.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_EXPIRING"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_EXPIRING") == "1"' "sem ZENY_LIVE_EXPIRING=1 o check pula"
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

  # 3. Nada gravado em disco, nem imagem.
  for p in 'ImageIO' 'captureToImage' 'File(' 'writeBytes' 'writeText' 'FileOutputStream' 'Files.write' \
    'outputStream(' 'appendText' 'printWriter'; do
    nao_tem "$app" "$p" "gravação em disco ($p)"
  done

  # 4. Só leitura: só as três portas de leitura saem da conexão e nenhum método de escrita é chamado.
  while IFS= read -r p; do
    [[ -z "$p" || " expiringPreAuthorizationSource paymentSource folioSource " == *" $p "* ]] ||
      echo "proibido: porta fora da lista de leitura ($p)"
  done < <(grep -oE 'connection\.[A-Za-z]+Source\(\)' <<<"$app" | sed -E 's/connection\.//; s/\(\)//')
  for p in '.post(' '.reverse(' '.receive(' '.refund(' 'registerPreAuthorization' 'capturePreAuthorization' \
    'releasePreAuthorization' 'openFolioPage' 'CheckOut' 'cashier' 'Correction'; do
    nao_tem "$app" "$p" "escrita ou porta de escrita ($p)"
  done

  # 5. Nenhuma saída ou interpolação de variável de ambiente, senha, URL, das respostas de caução nem de cartão.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$url' '${url' '${core' \
    '$connection' '${connection' 'printStackTrace' 'System.out' 'System.err' ' print(' 'getenv()}' \
    '$port' '${port' '$payments' '${payments' '$folios' '${folios' '$folio' '${folio' \
    '$state' '${state' '$rows' '${rows.first' '${rows[' '$row' '${row}' '$times' '${times' \
    '.nsu}' '.authorizationCode}' '.cardLast4}' '.cardBrand}' '.plus(' 'Log.' 'logger'; do
    nao_tem "$app" "$p" "saída ou interpolação de $p"
  done
  if mensagens "$app" | grep -qiE "$SEGREDO"; then
    echo "proibido: println/fail/error com credencial ou dado de cartão"
  fi
  # Nenhum literal do arquivo, fora os nomes das variáveis de ambiente, fala de credencial ou cartão.
  if grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -vE '^"ZENY_[A-Z_]*"$' | grep -qiE "$SEGREDO"; then
    echo "proibido: literal com credencial ou dado de cartão"
  fi
  # Todo template de todo literal está nesta lista fechada (a contagem de linhas, o nome da variável no env()).
  local permitidos=' ${rows.size} $name '
  while IFS= read -r p; do
    [[ -z "$p" || "$permitidos" == *" $p "* ]] || echo "proibido: template fora da lista ($p)"
  done < <(grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -oE '\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')

  # 6. println/fail/error começam por literal ou constante; sem concatenação com não literal; throw só de literal;
  # mensagem preguiçosa de check/require é literal; assert* só com argumentos da lista fechada.
  [[ "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(')" == \
    "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(("|[A-Z][A-Z_]*\))')" ]] ||
    echo "proibido: println/fail/error com argumento que não é literal nem constante"
  if grep -qE '" \+ [^"]|[^" ] \+ "' <<<"$app"; then
    echo "proibido: concatenação de texto com não literal"
  fi
  [[ "$(conta "$app" '(^|[^A-Za-z_])throw ')" == \
    "$(conta "$app" '(^|[^A-Za-z_])throw [A-Za-z.]+\(([A-Za-z.]+\()*"')" ]] ||
    echo "proibido: throw com argumento que não é literal"
  if grep -qE '(check|require|checkNotNull|requireNotNull)\([^()]*(\([^()]*\))*[^()]*\) \{ [^"]' <<<"$app"; then
    echo "proibido: mensagem de check/require que não é literal"
  fi
  while IFS=$'\t' read -r nome arg; do
    [[ -z "$nome" ]] && continue
    argumento_permitido "$arg" || echo "proibido: argumento de $nome fora da lista ($arg)"
  done < <(argumentos_assert "$app")
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$gradle")"
assert_eq "" "$achado" "LiveExpiringPreAuthorizationCheck não vaza ambiente, senha, URL nem dado de cartão"

# Os extratores acham o que vigiam (as travas não passam por não ver nada).
assert_eq "true" "$( (($(mensagens "$app" | wc -l) >= 2)) && echo true || echo false)" \
  "o extrator acha os fail do LiveExpiringPreAuthorizationCheck"
assert_eq "true" "$( (($(argumentos_assert "$app" | wc -l) >= 12)) && echo true || echo false)" \
  "o extrator acha os argumentos dos assert* do LiveExpiringPreAuthorizationCheck"
assert_eq "true" "$( (($(conta "$app" 'connection\.[A-Za-z]+Source\(\)') >= 3)) && echo true || echo false)" \
  "o extrator acha as portas de leitura do LiveExpiringPreAuthorizationCheck"
assert_eq "true" "$(grep -qiE "$SEGREDO" <<<'"PreAuthorizationCode"' && echo true || echo false)" \
  "a regra de segredo pega authorization"

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

# O opt-in e as credenciais.
muta "opt-in removido" 'System.getenv("ZENY_LIVE_EXPIRING") == "1"' 'true' app
muta "opt-in de outra verificação" 'System.getenv("ZENY_LIVE_EXPIRING") == "1"' \
  'System.getenv("ZENY_LIVE_PREAUTH") == "1"' app
muta "opt-in fora das entradas" 'inputs.property("zenyLiveExpiring"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'liveExpiring.orNull != "1"' 'true' gradle
muta "env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "login lido duas vezes" 'connection.login(env("ZENY_LIVE_VIEWER_LOGIN")' \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN") + env("ZENY_LIVE_VIEWER_LOGIN")' app
muta "senha em variável" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "stack trace impresso" 'runCatching { runBlocking { connection.logout() } }' \
  'runCatching { runBlocking { connection.logout() } }.onFailure { it.printStackTrace() }' app

# Só leitura: nada gravado, nenhuma escrita na demo.
muta "imagem gravada" 'state.close()' 'ImageIO.write(img, "png", File("x.png")); state.close()' app
muta "texto gravado" 'state.close()' 'java.io.File("x.txt").writeText("x"); state.close()' app
muta "porta de caixa" 'connection.folioSource()' 'connection.cashierSource()' app
muta "porta de correção" 'connection.folioSource()' 'connection.folioCorrectionSource()' app
muta "porta de check-out" 'connection.paymentSource()' 'connection.checkOutSource()' app
muta "lançamento na conta" 'folios.folio(rows.first().folioId)' 'folios.post(rows.first().folioId)' app
muta "caução registrada" 'payments.businessDay()' 'payments.registerPreAuthorization()' app
muta "caução capturada" 'payments.businessDay()' 'payments.capturePreAuthorization()' app
muta "caução liberada" 'payments.businessDay()' 'payments.releasePreAuthorization()' app
muta "pagamento recebido" 'payments.businessDay()' 'payments.receive()' app
muta "estorno" 'payments.businessDay()' 'payments.refund()' app

# As respostas de caução e o cartão em texto, interpolação, concatenação, error/throw e assert*.
muta "linha impressa" '"Cauções a vencer ao vivo: ${rows.size} linha(s) em ordem"' \
  '"Cauções a vencer ao vivo: ${rows.first()} linha(s) em ordem"' app
muta "estado impresso" '"Cauções a vencer ao vivo: a conta da primeira linha existe"' \
  '"Cauções a vencer ao vivo: $state"' app
muta "conta impressa" '"Cauções a vencer ao vivo: a conta da primeira linha existe"' \
  '"Cauções a vencer ao vivo: ${folio}"' app
muta "template novo fora da lista" '"Cauções a vencer ao vivo: ${rows.size} linha(s) em ordem"' \
  '"Cauções a vencer ao vivo: ${rows.size} ${times.size} linha(s) em ordem"' app
muta "a palavra authorization num literal" '"a leitura inicial falhou"' '"a leitura inicial falhou (authorization)"' app
muta "o NSU num literal" '"a leitura inicial falhou"' '"a leitura inicial falhou (nsu)"' app
muta "concatenação com a linha" '"a leitura inicial falhou"' '"a leitura inicial falhou: " + rows' app
muta "concatenação ao contrário" '"a leitura falhou"' 'rows.toString() + " a leitura falhou"' app
muta "concatenação com plus" '"a leitura falhou"' '"a leitura falhou".plus(rows)' app
muta "error com a linha" 'val folio = folios.folio(' \
  'if (rows.isEmpty()) error(rows.toString()); val folio = folios.folio(' app
muta "error com variável" 'val folio = folios.folio(' 'if (rows.isEmpty()) error(rows); val folio = folios.folio(' app
muta "throw com a linha" 'val folio = folios.folio(' \
  'if (rows.isEmpty()) throw IllegalStateException(rows.toString()); val folio = folios.folio(' app
muta "throw de variável" 'val folio = folios.folio(' 'if (rows.isEmpty()) throw failure; val folio = folios.folio(' app
muta "check com mensagem não literal" 'checkNotNull(connection.expiringPreAuthorizationSource()) { "a conexão não tem a lista" }' \
  'checkNotNull(connection.expiringPreAuthorizationSource()) { rows.toString() }' app
muta "assertEquals com a linha inteira" 'assertEquals(rows.first().folioId' 'assertEquals(rows.first()' app
muta "assertEquals com a resposta da conta" 'assertEquals(rows.first().folioId, folio.id.toString()' \
  'assertEquals(rows.first().folioId, folio' app
muta "assertNotNull com a linha" 'assertTrue(rows.isNotEmpty(), NEED_SEED)' \
  'assertNotNull(rows.first()); assertTrue(rows.isNotEmpty(), NEED_SEED)' app
muta "assertEquals com a lista" 'assertEquals(times.sorted(), times' 'assertEquals(rows, times' app
muta "assertContains com a lista" 'assertTrue(rows.isNotEmpty(), NEED_SEED)' 'assertContains(rows, rows.first())' app
muta "assert com o estado" 'assertNull(state.error, "a leitura falhou")' 'assertNull(state, "a leitura falhou")' app

finish
