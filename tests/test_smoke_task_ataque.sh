#!/usr/bin/env bash
# Ataque da Task 12 (E05): isolamento do smokeTest, lockfile e varredura de segredo. Estático: não chama o Gradle
# nem fala com servidor. O isolamento por --dry-run foi conferido à mão e consta do relatório do testador.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
BASE=384d18c

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

# --- 2. lockfile: só configurações novas; nenhuma coordenada ou versão nova ----------------------------------
if ! git -C "$ROOT" cat-file -e "$BASE^{commit}" 2>/dev/null; then
  skip_case "commit base $BASE ausente: parte do lockfile pulada"
else
  for lf in app core design sdk; do
    if ! git -C "$ROOT" diff --quiet "$BASE" -- "$lf/gradle.lockfile"; then
      _assert_fail "$lf/gradle.lockfile mudou desde $BASE"
    fi
  done
  for lf in buildscript-gradle.lockfile settings-gradle.lockfile; do
    git -C "$ROOT" diff --quiet "$BASE" -- "$lf" || _assert_fail "$lf mudou desde $BASE"
  done
  antes="$(git -C "$ROOT" show "$BASE:demo-seed/gradle.lockfile" | grep -E '^[^#].*=' | cut -d= -f1 | sort)"
  depois="$(grep -E '^[^#].*=' "$ROOT/demo-seed/gradle.lockfile" | cut -d= -f1 | sort)"
  assert_eq "$antes" "$depois" "o lockfile do demo-seed tem exatamente as mesmas coordenadas (sem versão nova)"
  # Toda configuração antiga continua, intacta, em cada coordenada; as novas são só smokeTest*.
  while IFS= read -r linha; do
    coord="${linha%%=*}"
    velhas="$(git -C "$ROOT" show "$BASE:demo-seed/gradle.lockfile" | grep -F -- "$coord=" | head -n1 | cut -d= -f2)"
    novas="$(grep -F -- "$coord=" "$ROOT/demo-seed/gradle.lockfile" | head -n1 | cut -d= -f2)"
    extra="$(tr ',' '\n' <<<"$novas" | grep -vxF -f <(tr ',' '\n' <<<"$velhas") | grep -vi 'smokeTest')"
    perdida="$(tr ',' '\n' <<<"$velhas" | grep -vxF -f <(tr ',' '\n' <<<"$novas"))"
    [[ -z "$extra" ]] || _assert_fail "$coord ganhou configuração que não é do smokeTest" "$extra"
    [[ -z "$perdida" ]] || _assert_fail "$coord perdeu configuração" "$perdida"
  done < <(git -C "$ROOT" show "$BASE:demo-seed/gradle.lockfile" | grep -E '^[^#].*=')
  linha_empty="$(grep -E '^empty=' "$ROOT/demo-seed/gradle.lockfile")"
  assert_contains "$linha_empty" "smokeTest" "configurações vazias do smokeTest travadas"
fi

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
