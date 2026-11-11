#!/usr/bin/env bash
# Testes estáticos dos lockfiles (Task 10): formato, coerência com o catálogo e constraints de segurança.
# Não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

catalogo="$ROOT/gradle/libs.versions.toml"
modulos=(sdk core design app demo-seed)
todos=("$ROOT/buildscript-gradle.lockfile")
for m in "${modulos[@]}"; do todos+=("$ROOT/$m/gradle.lockfile"); done

versao_catalogo() { sed -nE "s/^$1 *= *\"([^\"]+)\".*/\1/p" "$catalogo" | head -n1; }
# Versões travadas de uma coordenada g:a no lockfile $1 (uma por linha, sem repetição).
travadas() { grep -E "^$2:" "$1" 2>/dev/null | cut -d= -f1 | awk -F: '{print $3}' | sort -u; }
# Versões da coordenada $2 travadas na configuração $3 (nome exato) do lockfile $1.
travadas_em() {
  awk -F= -v c="$2" -v cfg="$3" '
    index($1, c ":") == 1 { n = split($2, a, ","); for (i = 1; i <= n; i++) if (a[i] == cfg) { split($1, p, ":"); print p[3] } }
  ' "$1" | sort -u
}

for lf in "${todos[@]}"; do
  nome="${lf#"$ROOT"/}"
  assert_file "$lf" "$nome existe"
  # Só comentário, linha de dependência (g:a:v=cfg,cfg) ou empty=cfg,cfg.
  fora="$(grep -vE '^(#.*|[^:=[:space:]#]+:[^:=[:space:]]+:[^:=[:space:]]+=[A-Za-z0-9_]+(,[A-Za-z0-9_]+)*|empty=([A-Za-z0-9_]+(,[A-Za-z0-9_]+)*)?)$' "$lf")"
  assert_eq "" "$fora" "$nome: nenhuma linha fora do formato do Gradle"
  assert_eq "1" "$(grep -c '^empty=' "$lf")" "$nome: exatamente uma linha empty="
  assert_eq "empty=" "$(tail -n1 "$lf" | cut -c1-6)" "$nome: empty= é a última linha"
  assert_eq "0" "$(grep -cE '^[^#].*(SNAPSHOT|\+|latest)' "$lf")" "$nome: sem versão dinâmica"
done

# Ktor e kotlin: versões fixadas direto pelo catálogo.
ktor="$(versao_catalogo ktor)"
kotlin="$(versao_catalogo kotlin)"
if [[ -z "$ktor" || -z "$kotlin" ]]; then _assert_fail "catálogo fixa ktor e kotlin" "ktor='$ktor' kotlin='$kotlin'"; fi
for m in sdk app; do
  assert_eq "$ktor" "$(travadas "$ROOT/$m/gradle.lockfile" io.ktor:ktor-client-core)" "$m: ktor-client-core travado na versão do catálogo"
  assert_eq "$ktor" "$(travadas "$ROOT/$m/gradle.lockfile" io.ktor:ktor-client-content-negotiation)" "$m: ktor-client-content-negotiation na versão do catálogo"
done
for m in "${modulos[@]}"; do
  lf="$ROOT/$m/gradle.lockfile"
  assert_eq "" "$(grep -E '^io\.ktor:.*=(.*,)?(compileClasspath|runtimeClasspath)(,|$)' "$lf" | grep -v ":$ktor=")" "$m: todo io.ktor do compile e do runtime travado em $ktor"
  assert_eq "$kotlin" "$(travadas_em "$lf" org.jetbrains.kotlin:kotlin-stdlib compileClasspath)" "$m: kotlin-stdlib do compileClasspath na versão do catálogo"
done
assert_eq "$kotlin" "$(travadas "$ROOT/buildscript-gradle.lockfile" org.jetbrains.kotlin:kotlin-gradle-plugin)" "buildscript: kotlin-gradle-plugin na versão do catálogo"

# Constraints de segurança do catálogo, travadas no classpath dos plugins.
bs="$ROOT/buildscript-gradle.lockfile"
jackson="$(versao_catalogo jackson2)"
for c in com.fasterxml.jackson:jackson-bom com.fasterxml.jackson.core:jackson-core com.fasterxml.jackson.core:jackson-databind \
  com.fasterxml.jackson.dataformat:jackson-dataformat-yaml; do
  assert_eq "$jackson" "$(travadas "$bs" "$c")" "buildscript: $c travado em $jackson (catálogo)"
done
assert_eq "$(versao_catalogo guava)" "$(travadas "$bs" com.google.guava:guava)" "buildscript: guava na versão do catálogo"
assert_eq "$(versao_catalogo handlebars)" "$(travadas "$bs" com.github.jknack:handlebars)" "buildscript: handlebars na versão do catálogo"
assert_eq "" "$(grep -E '^com\.fasterxml\.jackson\.(core:jackson-(core|databind)|dataformat:[^:]+|datatype:[^:]+):' "$bs" | grep -v ":$jackson=")" "buildscript: nenhum jackson 2.x abaixo da plataforma"

# Logback na configuração ktlint de cada módulo.
logback="$(versao_catalogo logback)"
for m in "${modulos[@]}"; do
  lf="$ROOT/$m/gradle.lockfile"
  for c in ch.qos.logback:logback-classic ch.qos.logback:logback-core; do
    assert_eq "$logback" "$(travadas "$lf" "$c")" "$m: $c travado em $logback (catálogo), só uma versão"
  done
  assert_contains "$(grep '^ch.qos.logback:logback-core:' "$lf")" '=ktlint' "$m: logback-core travado na configuração ktlint"
done

# Sem versão vulnerável conhecida de volta.
assert_eq "" "$(grep -hE '^(ch\.qos\.logback:[^:]+:1\.3\.|com\.github\.jknack:handlebars:4\.3\.|com\.google\.guava:guava:32\.)' "${todos[@]}")" "nenhum lockfile volta a travar logback 1.3, handlebars 4.3 ou guava 32"

finish
