#!/usr/bin/env bash
# Ataque à Task 3 da M2 E07, por mutação: aplica cada defeito numa cópia dos lockfiles e build files e exige que
# tests/test_pdfbox_scope_ataque.sh falhe. A cópia sem defeito tem de passar (controle). Não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

copia="$(mktemp -d)"
trap 'rm -rf "$copia"' EXIT

# Cópia limpa dos arquivos que o script lê, com o próprio script e a lib de asserções.
nova_copia() {
  rm -rf "$copia"/*
  mkdir -p "$copia/gradle" "$copia/core" "$copia/app" "$copia/design" "$copia/tests"
  cp -r "$ROOT/tests/lib" "$copia/tests/"
  cp "$ROOT/tests/test_pdfbox_scope_ataque.sh" "$copia/tests/"
  cp "$ROOT/gradle/libs.versions.toml" "$copia/gradle/"
  local m
  for m in core app design; do cp "$ROOT/$m/gradle.lockfile" "$ROOT/$m/build.gradle.kts" "$copia/$m/"; done
}
roda() { bash "$copia/tests/test_pdfbox_scope_ataque.sh" >/dev/null 2>&1; }
# exige_falha DESCRIÇÃO: o script, sobre a cópia já mutada, tem de sair com 1.
exige_falha() {
  roda
  local code=$?
  [[ "$code" == 1 ]] || _assert_fail "$1" "o script deveria falhar (exit 1) e saiu com $code"
}
# Reescreve a coluna de configurações da coordenada $2 no lockfile $1.
configs() { sed -i -E "\|^$2=|s|=.*|=$3|" "$copia/$1/gradle.lockfile"; }

nova_copia
roda || _assert_fail "controle: a cópia sem defeito passa" "o script falhou sobre a cópia limpa"

# O JAR do app precisa das quatro coordenadas no runtimeClasspath, não só do pdfbox: sem fontbox, pdfbox-io ou
# commons-logging o app sobe e cai em NoClassDefFoundError na primeira ficha.
for coord in 'org\.apache\.pdfbox:fontbox' 'org\.apache\.pdfbox:pdfbox-io' 'commons-logging:commons-logging'; do
  nova_copia
  configs app "$coord:[0-9.]+" "testRuntimeClasspath"
  exige_falha "app: ${coord//\\/} fora do runtimeClasspath derruba o script"
  nova_copia
  sed -i -E "\|^$coord:|d" "$copia/app/gradle.lockfile"
  exige_falha "app: ${coord//\\/} ausente do lockfile derruba o script"
done

# Os mesmos defeitos já cobertos (controle da própria sonda): têm de continuar derrubando.
nova_copia
echo 'org.apache.pdfbox:pdfbox:3.0.8=runtimeClasspath' >>"$copia/core/gradle.lockfile"
exige_falha "core: pdfbox no lockfile"
nova_copia
configs app 'org\.apache\.pdfbox:pdfbox:[0-9.]+' "compileClasspath,runtimeClasspath,testRuntimeClasspath"
exige_falha "app: pdfbox no compileClasspath"
nova_copia
echo 'implementation(libs.pdfbox)' >>"$copia/app/build.gradle.kts"
exige_falha "app: implementation(libs.pdfbox)"

finish
