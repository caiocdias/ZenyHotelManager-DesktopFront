#!/usr/bin/env bash
# Ataque à M4 E07: as verificações ao vivo do caixa (LiveCashierCheck no app, LiveCashierNotificationCheck no
# demo-seed) ficam puladas sem o opt-in e nunca imprimem valor de variável de ambiente nem senha, inclusive em
# mensagem de falha. A trava genérica (test_live_checks_ataque.sh) só olha o curl e o opt-in do realtime; esta
# alcança os dois arquivos novos. Estático: não chama o Gradle nem o servidor. Cada trava é provada por mutação: a
# cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

APP_KT="app/src/test/kotlin/com/zeny/desktop/app/cashier/LiveCashierCheck.kt"
SEED_KT="demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/LiveCashierNotificationCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$APP_KT" "a verificação ao vivo do caixa no app existe"
assert_file "$ROOT/$SEED_KT" "a verificação ao vivo da notificação de diferença existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_booking_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# violacoes APP SEED GRADLE: uma linha por trava quebrada (nada = tudo certo). Os três são código ativo.
violacoes() {
  local app="$1" seed="$2" gradle="$3" p v
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. App: opt-in no build e no teste.
  tem "$gradle" 'inputs.property("zenyLiveCashier", providers.environmentVariable("ZENY_LIVE_CASHIER").orElse(""))' \
    "ZENY_LIVE_CASHIER é entrada do tasks.test"
  tem "$gradle" 'liveCashier.orNull != "1"' "o upToDateWhen leva ZENY_LIVE_CASHIER"
  tem "$app" 'assumeTrue(System.getenv("ZENY_LIVE_CASHIER") == "1"' "sem ZENY_LIVE_CASHIER=1 o check pula"

  # 2. App: credenciais só pelo env(), que falha citando só o NOME; a senha vai só para o login.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
    [[ "$(grep -oF "env(\"$v\")" <<<"$app" | wc -l)" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem "$app" '?: fail("defina $name para a verificação real")' "env() falha citando só o nome da variável"
  [[ "$(grep -oE 'System\.getenv\(' <<<"$app" | wc -l)" == 2 ]] ||
    echo "proibido: System.getenv no app fora do opt-in e do env()"
  tem "$app" 'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
    "login e senha vão direto ao login, sem variável que outro trecho imprima"
  tem "$app" 'ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")' \
    "a URL inválida falha sem o valor"

  # 3. Seed: opt-in e senhas só pelo SeedArgs (Secret, toString "***"), nunca pelo valor.
  tem "$seed" 'assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")' \
    "sem ZENY_SEED_LIVE=1 o check pula"
  tem "$seed" 'SeedArgs.parse(emptyList(), System.getenv())' "as variáveis passam só pelo SeedArgs"
  tem "$seed" 'assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")' \
    "o SeedArgs incompleto falha sem mostrar o que veio"
  [[ "$(grep -oE 'System\.getenv\(' <<<"$seed" | wc -l)" == 3 ]] ||
    echo "proibido: System.getenv no seed fora do opt-in e do SeedArgs"
  tem "$seed" 'signedIn(factory, RECEPTIONIST, config.receptionistPassword)' "a senha da Recepcionista vai como Secret"
  tem "$seed" 'signedIn(factory, MANAGER, config.managerPassword)' "a senha do Gerente vai como Secret"
  [[ "$(grep -oE 'Password' <<<"$seed" | wc -l)" == 2 ]] || echo "proibido: senha usada fora do signIn"
  nao_tem "$seed" 'password.value' "o valor cru da senha"
  nao_tem "$seed" 'Password.value' "o valor cru da senha"

  # 4. Nos dois: nenhuma saída ou interpolação de variável de ambiente, senha, config ou do resultado do SeedArgs.
  for p in '${env(' '$env(' 'getenv(name)}' '${System' '$password' '${password' '$config' '${config' \
    '$parsed' '${parsed' '$url' '${url' 'printStackTrace' 'System.out' 'System.err' ' print(' '.value}' \
    'Password}' 'Password)}' 'getenv()}'; do
    nao_tem "$app $seed" "$p" "saída ou interpolação de $p"
  done
  # Todo println e todo fail: só texto literal e templates de dados da demo (número do turno, mensagem da tela).
  for v in "$app" "$seed"; do
    if grep -oP '(println|fail)\("(?:[^"\\]|\\.)*"' <<<"$v" | grep -qiE 'env|password|senha|secret|login|getenv'; then
      echo "proibido: println/fail com credencial"
    fi
  done
}

app="$(codigo_ativo "$ROOT/$APP_KT")"
seed="$(codigo_ativo "$ROOT/$SEED_KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$app" "$seed" "$gradle")"
assert_eq "" "$achado" "LiveCashierCheck e LiveCashierNotificationCheck não vazam ambiente nem senha"

# muta DESCRIÇÃO ANTIGO NOVO app|seed|gradle: o mutante (primeira ocorrência trocada) tem de quebrar alguma trava.
muta() {
  local m_app="$app" m_seed="$seed" m_gradle="$gradle"
  case "$4" in
    app)
      [[ "$m_app" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
      m_app="${m_app/"$2"/"$3"}" ;;
    seed)
      [[ "$m_seed" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
      m_seed="${m_seed/"$2"/"$3"}" ;;
    gradle)
      [[ "$m_gradle" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
      m_gradle="${m_gradle/"$2"/"$3"}" ;;
  esac
  [[ -n "$(violacoes "$m_app" "$m_seed" "$m_gradle")" ]] || _assert_fail "mutante não detectado: $1"
}

muta "app: opt-in removido" 'System.getenv("ZENY_LIVE_CASHIER") == "1"' 'true' app
muta "app: opt-in fora das entradas" 'inputs.property("zenyLiveCashier"' 'inputs.property("zenyLiveOutro"' gradle
muta "app: opt-in fora do upToDateWhen" 'liveCashier.orNull != "1"' 'true' gradle
muta "app: env() mostra o valor" '?: fail("defina $name para a verificação real")' \
  '?: fail("defina $name (${System.getenv(name)}) para a verificação real")' app
muta "app: senha lida fora do env()" 'env("ZENY_LIVE_VIEWER_PASSWORD")' 'System.getenv("ZENY_LIVE_VIEWER_PASSWORD")' app
muta "app: senha impressa" 'block(connection)' 'println("senha ${env("ZENY_LIVE_VIEWER_PASSWORD")}") block(connection)' app
muta "app: senha em variável e em mensagem de falha" \
  'connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))' \
  'val password = env("ZENY_LIVE_VIEWER_PASSWORD"); connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), password)' app
muta "app: URL inválida mostra o valor" 'fail("ZENY_LIVE_URL inválida")' 'fail("ZENY_LIVE_URL inválida: $url")' app
muta "app: login na mensagem de falha" 'fail("defina $name para a verificação real")' \
  'fail("defina $name para a verificação real; login ZENY_LIVE_VIEWER_LOGIN")' app
muta "seed: opt-in removido" 'System.getenv("ZENY_SEED_LIVE") == "1"' 'true' seed
muta "seed: SeedArgs incompleto mostra o resultado" '"variáveis ZENY_SEED_* incompletas")' \
  '"variáveis ZENY_SEED_* incompletas: $parsed")' seed
muta "seed: senha lida direto do ambiente" 'SeedArgs.parse(emptyList(), System.getenv())' \
  'SeedArgs.parse(emptyList(), System.getenv()); System.getenv("ZENY_SEED_MANAGER_PASSWORD")' seed
muta "seed: valor cru da senha na falha" '"login do usuário $login não ficou ativo"' \
  '"login do usuário $login (${password.value}) não ficou ativo"' seed
muta "seed: config impressa" 'println("LiveCashierNotificationCheck: turno $number fechado com diferença")' \
  'println("LiveCashierNotificationCheck: $config")' seed
muta "seed: senha usada fora do signIn" 'signedIn(factory, MANAGER, config.managerPassword)' \
  'signedIn(factory, MANAGER, config.managerPassword.also { System.err.println(it.value) })' seed
muta "seed: senha da Recepcionista trocada por literal" 'config.receptionistPassword' 'Secret("x")' seed

finish
