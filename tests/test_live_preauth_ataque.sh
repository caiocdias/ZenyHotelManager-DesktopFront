#!/usr/bin/env bash
# Ataque à M4 E09: a verificação ao vivo das cauções (LivePreAuthorizationCheck) fica pulada sem o opt-in e nunca
# imprime valor de variável de ambiente, senha, URL nem as respostas de caução do sdk (o toString gerado de
# CardPreAuthorizationResponse e de PreAuthorizationCaptureResponse mostra bandeira, últimos dígitos, NSU e
# autorização), inclusive em mensagem de falha. A trava de test_live_payment_ataque.sh só alcança o arquivo dela; esta
# alcança o arquivo novo, mantém "authorization" na regra sem exceção ampla, mantém a lista fechada de templates e
# fecha os furos que a da E03 não pegava: concatenação com não literal, error(...) com não literal, throw com não
# literal, mensagem de check/require por lambda não literal e o objeto inteiro (ou um campo de cartão) num assert*.
# Estático: não chama o Gradle nem o servidor. Só formas portáveis (grep -E, sem -P). Cada trava é provada por mutação:
# a cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/folio/LivePreAuthorizationCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo das cauções existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_payment_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# conta TEXTO PADRAO_ERE: quantas ocorrências do padrão há no texto.
conta() { grep -oE "$2" <<<"$1" | wc -l | tr -d ' '; }

# Os literais (com as continuações `" + "`) passados a println, fail e error: o texto que pode chegar ao log.
mensagens() { grep -oE '(println|fail|error)\(("([^"\\]|\\.)*"( \+ )?)+' <<<"$1"; }

# O que não pode aparecer em texto que vai ao log (ERE, sem diferenciar maiúsculas). "authorization" fica na regra,
# sem exceção: o check não precisa da palavra em nenhum literal.
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

# O que um assert* pode receber além do literal da mensagem: número, constante, a lista fechada de valores inteiros
# (strings de saldo), FolioText.money(...), as perguntas can*(row) do holder e um caminho que termina numa propriedade
# da lista fechada (nunca a resposta inteira do sdk, nunca um campo de cartão). Um objeto novo não entra sem mudar aqui.
VALORES_ASSERT=' expected '
PROPRIEDADES_ASSERT=' canRegister register unconfirmed key size status capture message offersOpenShift shortcuts '
PROPRIEDADES_ASSERT+='release value '

argumento_permitido() {
  local a="$1" prop
  [[ "$a" == \"* ]] && return 0
  [[ "$a" =~ ^-?[0-9]+L?$ ]] && return 0
  [[ "$a" =~ ^[A-Z][A-Z0-9_]*$ ]] && return 0
  [[ "$VALORES_ASSERT" == *" $a "* ]] && return 0
  [[ "$a" =~ ^FolioText\.money\(.*\)$ ]] && return 0
  [[ "$a" =~ ^holder\.can[A-Z][A-Za-z]*\(row\)$ ]] && return 0
  if [[ "$a" =~ ^[a-z][A-Za-z0-9_]*(\[[0-9]+\])?(\.[a-z][A-Za-z0-9_]*(\[[0-9]+\])?)*\.([a-z][A-Za-z0-9_]*)$ ]]; then
    prop="${BASH_REMATCH[4]}"
    [[ "$PROPRIEDADES_ASSERT" == *" $prop "* ]] && return 0
  fi
  return 1
}

# violacoes APP GRADLE: uma linha por trava quebrada (nada = tudo certo). Os dois são código ativo.
violacoes() {
  local app="$1" gradle="$2" p v nome arg
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. Opt-in no build (entrada e upToDateWhen) e no teste.
  tem "$gradle" 'inputs.property("zenyLivePreauth", providers.environmentVariable("ZENY_LIVE_PREAUTH").orElse(""))' \
    "ZENY_LIVE_PREAUTH é entrada do tasks.test"
  tem "$gradle" 'val livePreauth = providers.environmentVariable("ZENY_LIVE_PREAUTH")' \
    "o provider de ZENY_LIVE_PREAUTH"
  tem "$gradle" 'livePreauth.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_PREAUTH"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_PREAUTH") == "1"' "sem ZENY_LIVE_PREAUTH=1 o check pula"
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
  for p in 'writeBytes' 'writeText' 'FileOutputStream' 'Files.write' 'outputStream(' 'appendText' 'printWriter'; do
    nao_tem "$app" "$p" "gravação em disco ($p)"
  done

  # 4. Nenhuma saída ou interpolação de variável de ambiente, senha, URL, das respostas de caução nem de cartão.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$url' '${url' '${core' \
    '$connection' '${connection' 'printStackTrace' 'System.out' 'System.err' ' print(' 'getenv()}' \
    '$registered' '${registered' '$captured' '${captured' '$released' '${released' '$added' '${added' \
    '$input' '${input' '$intents' '${intents' '${screen.payments' '.nsu}' '.authorizationCode}' '.cardLast4}' \
    '.cardBrand}' '.plus(' 'Log.' 'logger'; do
    nao_tem "$app" "$p" "saída ou interpolação de $p"
  done
  # Todo println, fail e error: só texto literal e templates de dados da demo.
  if mensagens "$app" | grep -qiE "$SEGREDO"; then
    echo "proibido: println/fail/error com credencial ou dado de cartão"
  fi
  # As mensagens dos assert* também chegam ao log: nenhum literal do arquivo, fora os nomes das variáveis de ambiente,
  # fala de credencial ou cartão.
  if grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -vE '^"ZENY_[A-Z_]*"$' | grep -qiE "$SEGREDO"; then
    echo "proibido: literal com credencial ou dado de cartão"
  fi
  # Todo template de todo literal está nesta lista fechada (dados da demo e da tela, o nome da variável no env()).
  local permitidos=' $SCREEN_AUTHORIZED $CAPTURED $SCREEN_LEFT $expected $name ${form.message} ${shift.number} '
  while IFS= read -r p; do
    [[ -z "$p" || "$permitidos" == *" $p "* ]] || echo "proibido: template fora da lista ($p)"
  done < <(grep -oE '"([^"\\]|\\.)*"' <<<"$app" | grep -oE '\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')

  # 5. Os furos da trava da E03. (a) println/fail/error com argumento não literal (variável, chamada, toString):
  # todo println, fail e error começa por um literal ou por uma constante em maiúsculas.
  [[ "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(')" == \
    "$(conta "$app" '(^|[^A-Za-z_.])(println|fail|error)\(("|[A-Z][A-Z_]*\))')" ]] ||
    echo "proibido: println/fail/error com argumento que não é literal nem constante"
  # (b) Concatenação de literal com não literal, nos dois sentidos (o literal continuado " + " é permitido).
  if grep -qE '" \+ [^"]|[^" ] \+ "' <<<"$app"; then
    echo "proibido: concatenação de texto com não literal"
  fi
  # (c) throw: só exceção construída a partir de um literal (o desvio da resposta perdida), nunca o que veio de fora.
  [[ "$(conta "$app" '(^|[^A-Za-z_])throw ')" == \
    "$(conta "$app" '(^|[^A-Za-z_])throw [A-Za-z.]+\(([A-Za-z.]+\()*"')" ]] ||
    echo "proibido: throw com argumento que não é literal"
  # (d) A mensagem preguiçosa de check/require/checkNotNull/requireNotNull é um literal.
  if grep -qE '(check|require|checkNotNull|requireNotNull)\([^()]*(\([^()]*\))*[^()]*\) \{ [^"]' <<<"$app"; then
    echo "proibido: mensagem de check/require que não é literal"
  fi
  # (e) Nenhum assert* recebe o objeto inteiro (a resposta do sdk imprime o cartão no toString da falha) nem um campo
  # de cartão: só as formas de argumento_permitido.
  while IFS=$'\t' read -r nome arg; do
    [[ -z "$nome" ]] && continue
    argumento_permitido "$arg" || echo "proibido: argumento de $nome fora da lista ($arg)"
  done < <(argumentos_assert "$app")
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$gradle")"
assert_eq "" "$achado" "LivePreAuthorizationCheck não vaza ambiente, senha, URL nem dado de cartão"

# Os extratores acham o que vigiam (as travas não passam por não ver nada).
assert_eq "true" "$( (($(mensagens "$app" | wc -l) >= 4)) && echo true || echo false)" \
  "o extrator acha os println/fail do LivePreAuthorizationCheck"
assert_eq "true" "$( (($(argumentos_assert "$app" | wc -l) >= 40)) && echo true || echo false)" \
  "o extrator acha os argumentos dos assert* do LivePreAuthorizationCheck"
assert_eq "true" "$( (($(conta "$app" '(^|[^A-Za-z_])throw ') >= 1)) && echo true || echo false)" \
  "o extrator acha o throw do LivePreAuthorizationCheck"
# A regra mantém "authorization" (sem exceção ampla) e acha a palavra em qualquer caixa.
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

# O opt-in e as credenciais (as travas da E03, no arquivo novo).
muta "opt-in removido" 'System.getenv("ZENY_LIVE_PREAUTH") == "1"' 'true' app
muta "opt-in de outra verificação" 'System.getenv("ZENY_LIVE_PREAUTH") == "1"' \
  'System.getenv("ZENY_LIVE_PAYMENT") == "1"' app
muta "opt-in fora das entradas" 'inputs.property("zenyLivePreauth"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'livePreauth.orNull != "1"' 'true' gradle
muta "env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "senha em variável" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "resposta gravada em disco" 'val file = File("build/live/$name.png")' \
  'File("build/live/caucao.txt").writeText("x"); val file = File("build/live/$name.png")' app
muta "stack trace impresso" 'runCatching { runBlocking { connection.logout() } }' \
  'runCatching { runBlocking { connection.logout() } }.onFailure { it.printStackTrace() }' app

# A resposta da caução e o cartão em texto (interpolação e a lista fechada de templates).
muta "caução registrada impressa" '"a caução nova não está ativa"' '"a caução nova não está ativa: $registered"' app
muta "caução capturada numa falha" '"a caução não ficou capturada em parte"' \
  '"a caução não ficou capturada em parte: ${captured}"' app
muta "lista de cauções numa falha" '"a repetição com a mesma chave não deu uma caução só"' \
  '"a repetição com a mesma chave não deu uma caução só: ${added.size} ${added.first()}"' app
muta "autorização da caução numa falha" '"a caução nova não está ativa"' \
  '"a caução nova não está ativa (${registered.authorizationCode})"' app
muta "últimos dígitos por outro caminho" '"a caução não ficou liberada"' \
  '"a caução não ficou liberada (${preAuthorizationOf(screen, id).cardLast4})"' app
muta "template novo fora da lista" '"o valor autorizado não é R$ 100,00"' \
  '"o valor autorizado não é R$ 100,00 (${registered.id})"' app
muta "a palavra authorization num literal" '"a caução nova não está ativa"' \
  '"a caução nova não está ativa (authorization)"' app
muta "a autorização de teste em texto" '"a caução nova não está ativa"' \
  '"a caução nova não está ativa (nsu do teste)"' app

# Os furos da E03: concatenação, error(), throw, mensagem preguiçosa e o objeto inteiro num assert*.
muta "concatenação com a caução" '"a caução nova não está ativa"' '"a caução nova não está ativa: " + registered' app
muta "concatenação com o cartão" '"a caução não ficou liberada"' \
  '"a caução não ficou liberada: " + released.cardLast4' app
muta "concatenação ao contrário" '"a caução não ficou liberada"' 'released.toString() + " a caução não ficou liberada"' app
muta "concatenação com plus" '"a caução não ficou liberada"' '"a caução não ficou liberada".plus(released)' app
muta "error com toString" 'return registered.id.toString()' \
  'if (registered.amount.isEmpty()) error(registered.toString()); return registered.id.toString()' app
muta "error com variável" 'return registered.id.toString()' \
  'if (registered.amount.isEmpty()) error(registered); return registered.id.toString()' app
muta "throw com a resposta" 'return registered.id.toString()' \
  'if (registered.amount.isEmpty()) throw IllegalStateException(registered.toString()); return registered.id.toString()' \
  app
muta "throw da exceção de fora" 'throw ApiError.Network(IOException("resposta perdida pela verificação"))' \
  'throw ApiError.Network(IOException(registered.toString()))' app
muta "throw de variável" 'throw ApiError.Network(IOException("resposta perdida pela verificação"))' \
  'throw failure' app
muta "check com mensagem não literal" 'checkNotNull(shown) { "o painel da conta não foi montado" }' \
  'checkNotNull(shown) { screen.payments.intents.toString() }' app
muta "assertEquals com a caução inteira" 'assertEquals("ACTIVE", registered.status' \
  'assertEquals(registered, added.single()' app
muta "assertNotNull com a resposta" 'assertEquals("ACTIVE", registered.status' \
  'assertNotNull(registered); assertEquals("ACTIVE", registered.status' app
muta "assertEquals com a lista" 'assertEquals(1, added.size' 'assertEquals(listOf(), added' app
muta "assertEquals com o cartão" 'assertEquals("RELEASED", released.status' \
  'assertEquals("RELEASED", released.cardLast4' app
muta "assertEquals com a autorização" 'assertEquals("ACTIVE", registered.status' \
  'assertEquals(TEST_CODE, registered.authorizationCode' app
muta "assert com a resposta da porta" 'assertEquals("PARTIALLY_CAPTURED", captured.status' \
  'assertEquals("PARTIALLY_CAPTURED", preAuthorizationOf(screen, id)' app
muta "assertContains com a lista" 'assertEquals(1, added.size' 'assertContains(added, registered' app

finish
