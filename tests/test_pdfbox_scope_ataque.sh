#!/usr/bin/env bash
# Ataque à Task 3 da M2 E07: o PDFBox é `implementation` só do design (ADR 0008). Fica fora do core e do
# compileClasspath de produção do app, entra no runtimeClasspath do app (o JAR precisa dele) e, no
# app/build.gradle.kts, só como testImplementation. Lê lockfiles e build files; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

catalogo="$ROOT/gradle/libs.versions.toml"
core_lock="$ROOT/core/gradle.lockfile"
design_lock="$ROOT/design/gradle.lockfile"
app_lock="$ROOT/app/gradle.lockfile"
for lf in "$core_lock" "$design_lock" "$app_lock"; do assert_file "$lf" "${lf#"$ROOT"/} existe"; done

# Configurações onde uma coordenada (g:a, ou só o grupo g) aparece no lockfile $1, uma por linha.
configs_de() { grep -E "^$2:" "$1" | cut -d= -f2 | tr ',' '\n' | sort -u; }

# O catálogo fixa a versão, e o lockfile do design trava exatamente ela.
versao="$(sed -nE 's/^pdfbox *= *"([^"]+)".*/\1/p' "$catalogo" | head -n1)"
if [[ ! "$versao" =~ ^3\.0\.[0-9]+$ ]]; then _assert_fail "catálogo fixa o pdfbox numa 3.0.x" "versão: '$versao'"; fi
assert_eq "$versao" "$(grep -E '^org\.apache\.pdfbox:pdfbox:' "$design_lock" | cut -d= -f1 | cut -d: -f3 | sort -u)" \
  "design: pdfbox travado na versão do catálogo"

# core: nenhum artefato do PDFBox em nenhuma configuração.
assert_eq "0" "$(grep -cE '^org\.apache\.pdfbox:' "$core_lock")" "core: lockfile sem org.apache.pdfbox"

# design: o PDFBox compila e roda com o design.
for cfg in compileClasspath runtimeClasspath; do
  assert_contains "$(configs_de "$design_lock" 'org\.apache\.pdfbox:pdfbox')" "$cfg" "design: pdfbox no $cfg"
done

# Conjunto de runtime do PDFBox: sem qualquer um deles, o JAR do app cai em NoClassDefFoundError na primeira ficha.
# Cada um está no runtimeClasspath do design (de onde vem) e no do app (que o JAR empacota).
for c in 'org\.apache\.pdfbox:pdfbox' 'org\.apache\.pdfbox:fontbox' 'org\.apache\.pdfbox:pdfbox-io' \
  'commons-logging:commons-logging'; do
  assert_contains "$(configs_de "$design_lock" "$c")" "runtimeClasspath" "design: ${c//\\/} no runtimeClasspath"
  assert_contains "$(configs_de "$app_lock" "$c")" "runtimeClasspath" "app: ${c//\\/} no runtimeClasspath"
done

# app: fora do compileClasspath e de toda configuração que não seja de runtime ou de teste, para cada artefato do
# PDFBox (pdfbox, fontbox, pdfbox-io).
for a in pdfbox fontbox pdfbox-io; do
  cfgs="$(configs_de "$app_lock" "org\\.apache\\.pdfbox:$a")"
  assert_eq "" "$(grep -xE 'compileClasspath' <<<"$cfgs")" "app: $a fora do compileClasspath"
  assert_eq "" "$(grep -vE '^((composeHotReloadDev)?[Tt]est[A-Za-z]*|[A-Za-z]*[Rr]untimeClasspath)$' <<<"$cfgs")" \
    "app: $a só em classpaths de runtime ou de teste"
done

# Linhas do build file $1 que citam o PDFBox (comentários não contam).
cita_pdfbox() { grep -vE '^\s*//' "$1" | grep -iE 'pdfbox'; }

# design/build.gradle.kts: implementation, nunca api (vazaria para o compileClasspath do app).
dk="$(cita_pdfbox "$ROOT/design/build.gradle.kts")"
assert_contains "$dk" "implementation(libs.pdfbox)" "design: pdfbox declarado como implementation"
assert_eq "" "$(grep -E '^\s*api\(' <<<"$dk")" "design: pdfbox nunca como api"

# app/build.gradle.kts: ausente ou só testImplementation; qualquer outra linha que cite o PDFBox é recusada.
ak="$(cita_pdfbox "$ROOT/app/build.gradle.kts")"
assert_eq "" "$(grep -vE '^\s*testImplementation\(' <<<"$ak" | grep -v '^$')" "app: pdfbox ausente ou só testImplementation"

# core/build.gradle.kts não cita o PDFBox.
assert_eq "" "$(cita_pdfbox "$ROOT/core/build.gradle.kts")" "core: build sem pdfbox"

finish
