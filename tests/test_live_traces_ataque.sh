#!/usr/bin/env bash
# Ataque à Task 9 da M2 E05: a verificação ao vivo dos recados (LiveTracesCheck) escreve no servidor; sem o opt-in
# fica pulada e com ele não pode sair verde por omissão nem vazar segredo. Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

codigo_ativo() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1" 2>/dev/null | tr '\n' ' ' | tr -s ' '; }

KT="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reception/LiveTracesCheck.kt"
GRADLE="$ROOT/app/build.gradle.kts"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"
gradle="$(codigo_ativo "$GRADLE")"

# --- 1. Opt-in no build, igual ao dos outros ----------------------------------------------------------------
assert_contains "$gradle" 'inputs.property("zenyLiveTraces", providers.environmentVariable("ZENY_LIVE_TRACES").orElse(""))' \
  "app/build.gradle.kts: ZENY_LIVE_TRACES é entrada do tasks.test"
assert_contains "$gradle" 'val liveTraces = providers.environmentVariable("ZENY_LIVE_TRACES")' \
  "app/build.gradle.kts: liveTraces lê ZENY_LIVE_TRACES"
bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
assert_contains "$bloco" 'liveTraces.orNull != "1"' "app/build.gradle.kts: o upToDateWhen leva ZENY_LIVE_TRACES"
assert_contains "$bloco" 'liveStay.orNull != "1"' "app/build.gradle.kts: o upToDateWhen manteve o ZENY_LIVE_STAY"

# --- 2. Gating: sem opt-in pula (antes de cada teste); com opt-in exige as três variáveis -------------------
assert_contains "$kt" '@BeforeTest fun requireOptIn() { assumeTrue(System.getenv("ZENY_LIVE_TRACES") == "1"' \
  "sem ZENY_LIVE_TRACES=1 cada teste pula"
for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
  assert_contains "$kt" "env(\"$v\")" "a verificação exige $v (falha se faltar)"
done
assert_contains "$kt" '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
n="$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "System.getenv só no opt-in e no env()"
n="$(grep -oE '@Test' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "os três cenários do brief"

# --- 3. Higiene de segredo -----------------------------------------------------------------------------------
vazamento="$(grep -oE '(println|fail|checkNotNull|assert[A-Za-z]*)\([^;]*"[^"]*\$\{?(env|VIEWER_PASSWORD|password)[^"]*"' <<<"$kt" | wc -l)"
assert_eq "0" "$vazamento" "nenhuma saída interpola senha ou valor do ambiente"
assert_not_contains "$kt" 'e.printStackTrace' "sem printStackTrace"

# --- 4. Os cenários afirmam o que o brief pede ---------------------------------------------------------------
assert_contains "$kt" 'private const val DEPARTMENT = "Governança"' "setor Governança"
assert_contains "$kt" 'assertTrue(list.table.rows.none { it.id == id }' "resolver tira o recado dos abertos"
assert_contains "$kt" 'assertFalse(resolved.open' "resolvido aparece como não aberto"
assert_contains "$kt" 'assertFalse(second.saved, "o segundo salvou sem o 412")' "o segundo formulário não salva sem o 412"
assert_contains "$kt" '"Alguém alterou este recado. Confira e salve de novo."' "o aviso do 412 é conferido por inteiro"
assert_contains "$kt" 'assertEquals(firstText, second.text' "o segundo relê o texto do primeiro"
assert_contains "$kt" 'assertTrue(second.saved, "o segundo não salvou na segunda confirmação")' "a segunda confirmação salva"
assert_contains "$kt" 'capture("traces")' "captura traces.png"
assert_contains "$kt" 'resolveQuietly(screen, id)' "cada recado criado é resolvido no fim"

finish
