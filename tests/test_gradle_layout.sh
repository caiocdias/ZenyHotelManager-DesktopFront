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
# Blocos permitidos além dos aliases: `subprojects { ... }` (lint, Task 9), `buildscript { ... }` e
# `allprojects { ... }` (travamento de dependências, Task 10). Nenhum outro.
bloco_de() { awk -v nome="$1" '$0 ~ "^"nome" *\\{"{d=1} d{print} d&&/^\}/{d=0}' <<<"$raiz"; }
bloco="$(bloco_de subprojects)"
bloco_buildscript="$(bloco_de buildscript)"
bloco_allprojects="$(bloco_de allprojects)"
raiz_sem_blocos="$(awk '/^(subprojects|buildscript|allprojects) *\{/{d=1} !d{print} d&&/^\}/{d=0}' <<<"$raiz")"
outros="$(grep -vE '^\s*(alias\(.*\) apply false|plugins *\{|\}|//.*)?\s*$' <<<"$raiz_sem_blocos")"
assert_eq "" "$outros" "raiz só tem alias(...) apply false e os blocos subprojects, buildscript e allprojects"

sem_alias="$(grep -vE '^\s*alias\(' <<<"$raiz")"
# Sem diferença entre maiúsculas e minúsculas (`BasePlugin`, `Delete`), só neste trecho.
shopt -s nocasematch
for proibido in 'base' 'java' 'application' 'kotlin' 'clean' 'buildDirectory'; do
  assert_not_contains "$sem_alias" "$proibido" "raiz não usa $proibido"
done
# Dentro de subprojects, nada que alcance a raiz: plugin nela (cria `clean`) ou tarefa Delete (atinge build/).
for proibido in 'rootProject.apply' 'rootProject.plugins' 'rootProject.pluginManager' 'rootProject.tasks' 'allprojects' 'Delete'; do
  assert_not_contains "$bloco" "$proibido" "bloco subprojects não usa $proibido"
done
# O allprojects só trava dependências e registra resolveAndLockAll: nada de plugin nem `clean` na raiz.
for proibido in 'apply(' 'plugins' 'pluginManager' 'Delete' '"clean"' 'clean('; do
  assert_not_contains "$bloco_allprojects" "$proibido" "bloco allprojects não usa $proibido"
done
shopt -u nocasematch

# Só os *.gradle.kts versionados: pastas ignoradas pelo git (.server/<commit> com a árvore do servidor,
# build/, .gradle/, .kotlin/) e outros worktrees são estado da máquina, não parte do front. Caminhos
# relativos à raiz, como o ls-files devolve.
mapfile -t kts < <(git -C "$ROOT" ls-files -- '*.gradle.kts' | sort)
if [[ "${#kts[@]}" -lt 7 ]]; then _assert_fail "acha ao menos 7 *.gradle.kts (raiz, settings e cinco módulos)" "achou ${#kts[@]}: ${kts[*]}"; fi
# /dev/null evita que o grep leia o stdin se a lista vier vazia.
literais="$(cd "$ROOT" && grep -nE '^[^/]*("[^"]*:[0-9]+\.[0-9]+[^"]*"|\<version[[:space:]]*(=|\()?[[:space:]]*"[^"]*[0-9])' /dev/null "${kts[@]}" 2>/dev/null)"
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

# Fronteira com o sdk (ADR 0008): o core expõe o sdk por api(); app e design só chegam a ele pelo core.
assert_contains "$(cat "$ROOT/core/build.gradle.kts" 2>/dev/null)" 'api(project(":sdk"))' "core expõe o sdk por api(project(\":sdk\"))"
for m in app design; do
  assert_not_contains "$(cat "$ROOT/$m/build.gradle.kts" 2>/dev/null)" 'project(":sdk")' "$m não depende do sdk direto"
done

# Task 7: o app embarca os nativos de Linux e Windows (a Task 8 empacota os dois), nunca currentOs.
app_kts="$(cat "$ROOT/app/build.gradle.kts" 2>/dev/null)"
assert_contains "$app_kts" 'linux_x64' "app depende dos nativos linux_x64"
assert_contains "$app_kts" 'windows_x64' "app depende dos nativos windows_x64"
assert_not_contains "$app_kts" 'currentOs' "app não usa currentOs"

# Task 9: lint oficial. A raiz aplica detekt e ktlint só nos subprojetos (nunca em si mesma).
assert_contains "$bloco" 'dev.detekt' "subprojects aplica o detekt"
assert_contains "$bloco" 'org.jlleitschuh.gradle.ktlint' "subprojects aplica o ktlint"
assert_contains "$bloco" 'libs.versions.ktlint.cli' "ktlint CLI vem do catálogo"
assert_contains "$bloco" 'detektMain' "check depende de detektMain"
assert_contains "$bloco" 'detektTest' "check depende de detektTest"
assert_file "$ROOT/.editorconfig" ".editorconfig"
assert_contains "$(cat "$ROOT/.editorconfig" 2>/dev/null)" 'ktlint_function_naming_ignore_when_annotated_with = Composable' "ktlint dispensa @Composable do nome de função"
detekt_yml="$(cat "$ROOT/config/detekt/detekt.yml" 2>/dev/null)"
assert_contains "$detekt_yml" 'ForbiddenImport' "detekt.yml tem ForbiddenImport"
assert_contains "$detekt_yml" 'ignoreAnnotated' "detekt.yml dispensa @Composable do nome de função"
for pkg in 'com.zeny.desktop.sdk.client' 'com.zeny.desktop.sdk.util'; do
  assert_contains "$detekt_yml" "$pkg" "ForbiddenImport barra $pkg"
done
# Cliente HTTP só pelo core (ADR 0008, decisão D1): o detekt barra o pacote e seus subpacotes.
for pkg in 'io.ktor.client.*' 'de.quati.ogen.client.*'; do
  assert_contains "$detekt_yml" "value: '$pkg'" "ForbiddenImport barra $pkg (cliente HTTP só pelo core)"
done
# O ForbiddenImport só olha `import`: nada de nome totalmente qualificado do sdk de operações em app/design.
proibidos="$(cd "$ROOT" && grep -rnE 'com\.zeny\.desktop\.sdk\.(client|util)|io\.ktor\.client|de\.quati\.ogen\.client' $(ls -d app/src design/src 2>/dev/null) 2>/dev/null)"
assert_eq "" "$proibidos" "app e design não citam com.zeny.desktop.sdk.client, .util, io.ktor.client nem de.quati.ogen.client"

# Task 10 da E02: o acesso cru às operações do sdk (`@RawSdkAccess`, ADR 0008) é só do core e do demo-seed.
# app e design não citam a anotação, nem por @OptIn, nem por optIn/-opt-in no build.gradle.kts.
raw_usos="$(cd "$ROOT" && grep -rnE 'RawSdkAccess' /dev/null $(ls -d app/src design/src app/build.gradle.kts design/build.gradle.kts 2>/dev/null) 2>/dev/null)"
assert_eq "" "$raw_usos" "app e design não citam RawSdkAccess (nem @OptIn, nem optIn/-opt-in no build)"
raw_kt="$ROOT/core/src/main/kotlin/com/zeny/desktop/core/RawSdkAccess.kt"
assert_file "$raw_kt" "o core declara a anotação RawSdkAccess"
raw_ativo="$(sed -E 's@^\s*//.*$@@' "$raw_kt" 2>/dev/null)"
assert_contains "$raw_ativo" 'annotation class RawSdkAccess' "RawSdkAccess é uma annotation class"
assert_contains "$raw_ativo" '@RequiresOptIn' "RawSdkAccess é @RequiresOptIn"
assert_contains "$raw_ativo" 'RequiresOptIn.Level.ERROR' "RawSdkAccess exige opt-in no nível ERROR (não WARNING)"

# Task 10: travamento estrito. Cada módulo tem gradle.lockfile (a raiz não tem configuração resolvível, e o
# Gradle não grava lockfile vazio); o classpath dos plugins e o das settings também têm o seu.
for d in sdk core design app demo-seed; do
  assert_file "$ROOT/$d/gradle.lockfile" "gradle.lockfile de $d"
done
assert_file "$ROOT/buildscript-gradle.lockfile" "buildscript-gradle.lockfile da raiz"
assert_file "$ROOT/settings-gradle.lockfile" "settings-gradle.lockfile da raiz"
assert_contains "$bloco_buildscript" 'LockMode.STRICT' "buildscript trava o classpath dos plugins em modo estrito"
assert_contains "$bloco_allprojects" 'LockMode.STRICT' "allprojects trava em modo estrito"
assert_contains "$bloco_allprojects" 'resolveAndLockAll' "allprojects registra resolveAndLockAll"

finish
