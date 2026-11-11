#!/usr/bin/env bash
# Testes estáticos do esqueleto Gradle: projetos, raiz sem plugin aplicado (sem `clean`),
# sem versão literal nos *.gradle.kts e propriedades de memória fixadas.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

settings="$(cat "$ROOT/settings.gradle.kts" 2>/dev/null)"
includes="$(grep -oE '^include\("[^"]+"\)' <<<"$settings" | sort | tr '\n' ' ')"
assert_eq 'include(":app") include(":core") include(":demo-seed") include(":design") include(":sdk") ' "$includes" "settings inclui exatamente os cinco projetos"
assert_contains "$settings" 'rootProject.name = "zeny-desktop"' "nome do projeto raiz"

root="$ROOT/build.gradle.kts"
raiz="$(cat "$root" 2>/dev/null)"
aliases="$(grep -c 'alias(' <<<"$raiz")"
sem_apply="$(grep 'alias(' <<<"$raiz" | grep -vc 'apply false')"
assert_eq "0" "$sem_apply" "todo alias(...) da raiz termina em apply false"
if [[ "$aliases" -eq 0 ]]; then _assert_fail "raiz declara plugins" "nenhum alias(...) encontrado"; fi
outros="$(grep -vE '^\s*(alias\(.*\) apply false|plugins *\{|\}|//.*)?\s*$' <<<"$raiz")"
assert_eq "" "$outros" "raiz só tem alias(...) apply false"

sem_alias="$(grep -vE '^\s*alias\(' <<<"$raiz")"
for proibido in 'base' 'java' 'application' 'kotlin' 'clean' 'buildDirectory'; do
  assert_not_contains "$sem_alias" "$proibido" "raiz não usa $proibido"
done

mapfile -t kts < <(find "$ROOT" -name '*.gradle.kts' -type f -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.claude/*' -not -path '*/.git/*' | sort)
literais="$(grep -nE '^[^/]*("[^"]*:[0-9]+\.[0-9]+[^"]*"|\<version[[:space:]]*(=|\()?[[:space:]]*"[^"]*[0-9])' "${kts[@]}" 2>/dev/null)"
assert_eq "" "$literais" "nenhum *.gradle.kts com versão literal (coordenada ou plugin)"

props="$(cat "$ROOT/gradle.properties" 2>/dev/null)"
for linha in \
  'org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
  'kotlin.compiler.execution.strategy=in-process' \
  'org.gradle.workers.max=2' \
  'org.gradle.parallel=false' \
  'org.gradle.daemon.idletimeout=180000'; do
  if ! grep -qxF "$linha" <<<"$props"; then _assert_fail "gradle.properties tem $linha"; fi
done

for m in sdk core design app demo-seed; do
  assert_file "$ROOT/$m/build.gradle.kts" "build.gradle.kts de $m"
done

finish
