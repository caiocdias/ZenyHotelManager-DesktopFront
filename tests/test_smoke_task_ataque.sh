#!/usr/bin/env bash
# Ataque da Task 12 (E05): isolamento do smokeTest, lockfile e varredura de segredo. Estático: não chama o Gradle
# nem fala com servidor. O isolamento por --dry-run foi conferido à mão e consta do relatório do testador.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

sem_comentario() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1"; }

# --- 1. smokeTest isolado: nenhum script liga a tarefa a check/build/test/assemble ----------------------------
for f in $(cd "$ROOT" && git ls-files '*.kts'); do
  ativo="$(sem_comentario "$ROOT/$f")"
  # Qualquer referência a smokeTest como tarefa (não como source set/classpath/detekt) em dependsOn/finalizedBy.
  achado="$(grep -E '(dependsOn|finalizedBy|mustRunAfter|shouldRunAfter)\(' <<<"$ativo" | grep -E '"smokeTest"|[(, ]smokeTest[),]|tasks\.smokeTest|named\("smokeTest"\)')"
  assert_eq "" "$achado" "$f: nenhuma dependência de/para a tarefa smokeTest"
  assert_eq "" "$(grep -E 'tasks\.(check|build|test|assemble)[ .{(].*smokeTest' <<<"$ativo")" "$f: check/build/test/assemble não citam smokeTest"
done
# O padrão do check no kts raiz só pega o detekt (nome exato), nunca um prefixo que alcance a tarefa Test.
raiz="$(sem_comentario "$ROOT/build.gradle.kts")"
assert_eq "" "$(grep -E 'startsWith|contains\(|Regex|matching *\{ *it\.name *\.' <<<"$raiz" | grep -i 'smoke')" "o kts raiz não casa smokeTest por padrão"
assert_eq "1" "$(grep -c 'detektSmokeTest' <<<"$raiz")" "o kts raiz cita só detektSmokeTest, uma vez"

# A tarefa nunca é considerada atualizada (rodar duas vezes de fato fala com o servidor) e lê a propriedade sem default.
kts="$(sem_comentario "$ROOT/demo-seed/build.gradle.kts")"
assert_contains "$kts" 'outputs.upToDateWhen { false }' "smokeTest nunca fica up-to-date"
assert_eq "" "$(grep -E 'gradleProperty\("serverUrl"\)\.(getOrElse|orElse)|serverUrl.*\?:\s*"http' <<<"$kts")" "-PserverUrl não tem valor padrão"
# O endereço do ambiente do dev (127.0.0.1:8088, .server) não é embutido no script nem no teste.
assert_eq "" "$(grep -E '127\.0\.0\.1|localhost|:8088' <<<"$kts")" "o kts do demo-seed não embute endereço de servidor"
assert_eq "" "$(grep -E '"https?://' "$ROOT/demo-seed/src/smokeTest/kotlin/com/zeny/desktop/seed/smoke/SmokeTest.kt")" "SmokeTest.kt não embute URL"

# --- 2. lockfile: invariantes permanentes (sem commit fixo) ----------------------------------------------------
# (a) o smokeTest não traz coordenada nova: toda coordenada group:artifact:versão do lockfile do demo-seed já está
#     travada no lockfile de outro módulo (o demo-seed só consome o que o core e os demais já resolvem).
lf_seed="$ROOT/demo-seed/gradle.lockfile"
outros=("$ROOT/app/gradle.lockfile" "$ROOT/core/gradle.lockfile" "$ROOT/design/gradle.lockfile" "$ROOT/sdk/gradle.lockfile")
sem_dono=""
while IFS= read -r coord; do
  grep -qF -- "$coord=" "${outros[@]}" || sem_dono+="$coord "
done < <(grep -E '^[^#].*=' "$lf_seed" | cut -d= -f1 | sort -u)
assert_eq "" "$sem_dono" "toda coordenada do lockfile do demo-seed está travada em outro módulo (nenhuma nova)"
# (b) as configurações do smokeTest estão travadas no lockfile do demo-seed, inclusive as vazias.
assert_contains "$(grep -E '^[^#].*=.*smokeTest' "$lf_seed")" "smokeTestRuntimeClasspath" "smokeTestRuntimeClasspath travado"
assert_contains "$(grep -E '^[^#].*=.*smokeTest' "$lf_seed")" "smokeTestCompileClasspath" "smokeTestCompileClasspath travado"
assert_contains "$(grep -E '^empty=' "$lf_seed")" "smokeTest" "configurações vazias do smokeTest travadas"
# Nenhuma coordenada do lockfile perdeu as configurações de produção e de teste ao ganhar as do smokeTest.
assert_contains "$(grep -E '^de\.quati\.ogen:core:' "$lf_seed")" "testRuntimeClasspath" "as configurações de teste existentes continuam travadas"

# --- 3. varredura de segredo: o mesmo teste, rodado contra um repositório descartável com iscas ----------------
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/tests/lib"
cp "$ROOT/tests/test_smoke_task.sh" "$tmp/tests/"
cp "$ROOT/tests/lib/assert.sh" "$tmp/tests/lib/"
git -C "$tmp" init -q
git -C "$tmp" config user.email t@t && git -C "$tmp" config user.name t
varre() { # varre NOME CONTEUDO TRACKED(sim|nao) ESPERA(pega|passa)
  local nome="$1" conteudo="$2" tracked="$3" espera="$4" f="$tmp/isca.txt" saida
  printf '%s\n' "$conteudo" >"$f"
  if [[ "$tracked" == sim ]]; then git -C "$tmp" add isca.txt; fi
  saida="$(bash "$tmp/tests/test_smoke_task.sh" 2>&1 | grep -A3 'nenhum arquivo atribui' )"
  if [[ "$espera" == pega && -z "$saida" ]]; then _assert_fail "varredura não pegou: $nome"; fi
  if [[ "$espera" == passa && -n "$saida" ]]; then _assert_fail "varredura deu falso positivo: $nome" "$saida"; fi
  if [[ "$tracked" == sim ]]; then git -C "$tmp" rm -q -f --cached isca.txt; fi
  rm -f "$f"
}
for modo in sim nao; do
  varre "literal puro ($modo)" 'ZENY_SEED_ADMIN_PASSWORD=Hunter2Hunter2' $modo pega
  varre "literal export ($modo)" 'export ZENY_SEED_MANAGER_PIN=123456' $modo pega
  varre "literal entre aspas ($modo)" 'ZENY_SEED_RECEPTIONIST_PASSWORD="Hunter2Hunter2"' $modo pega
  varre "literal aspas simples ($modo)" "ZENY_SEED_HOUSEKEEPER_PASSWORD='Hunter2Hunter2'" $modo pega
  varre "literal admin-new ($modo)" 'ZENY_SEED_ADMIN_NEW_PASSWORD=Hunter2Hunter2' $modo pega
  varre "placeholder <senha> ($modo)" 'ZENY_SEED_ADMIN_PASSWORD=<senha>' $modo passa
  varre "placeholder entre aspas ($modo)" 'ZENY_SEED_ADMIN_PASSWORD="<senha>"' $modo passa
  varre "expansão de variável ($modo)" 'ZENY_SEED_MANAGER_PASSWORD="${GERADA}"' $modo passa
  varre "substituição de comando ($modo)" 'ZENY_SEED_MANAGER_PIN=$(gera_pin)' $modo passa
  varre "só o nome da variável ($modo)" 'defina ZENY_SEED_ADMIN_PASSWORD no ambiente' $modo passa
  varre "variável não secreta ($modo)" 'ZENY_SEED_SERVER=http://127.0.0.1:8088' $modo passa
done
# Atribuições literais com espaço ou dois-pontos também são segredo em arquivo versionado.
varre "literal com espaços em volta do =" 'ZENY_SEED_ADMIN_PASSWORD = Hunter2Hunter2' sim pega
varre "literal em YAML (chave: valor)" 'ZENY_SEED_MANAGER_PIN: 123456' sim pega

finish
