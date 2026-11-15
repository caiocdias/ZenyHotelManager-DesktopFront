#!/usr/bin/env bash
# Ataque à Task 4 da M2 E07: variações que um desenvolvedor poderia usar para levar a ficha ao disco ou a um programa
# externo sem acionar o guarda estático tests/test_pdf_disk_apis_ataque.sh. Cada variação é posta numa cópia do
# código de produção; o guarda tem de sair com 1. Os controles (nomes já proibidos, por alias e qualificados)
# têm de falhar também. Lê só código-fonte; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

copia="$(mktemp -d)"
trap 'rm -rf "$copia"' EXIT

nova_copia() {
  rm -rf "${copia:?}"/*
  mkdir -p "$copia/tests" "$copia/design/src/main/kotlin" "$copia/app/src/main/kotlin"
  cp -r "$ROOT/tests/lib" "$copia/tests/"
  cp "$ROOT/tests/test_pdf_disk_apis_ataque.sh" "$copia/tests/"
}
roda() { bash "$copia/tests/test_pdf_disk_apis_ataque.sh" >/dev/null 2>&1; }
# pega MÓDULO 'código': põe o código num arquivo de produção novo e exige que o guarda falhe (exit 1).
pega() {
  nova_copia
  printf '%s\n' "$2" >"$copia/$1/src/main/kotlin/Variacao.kt"
  roda
  local code=$?
  [[ "$code" == 1 ]] || _assert_fail "guarda pega: $3" "o guarda saiu com $code sobre: $2"
}

nova_copia
roda || _assert_fail "controle: a cópia limpa passa" "o guarda falhou sobre a cópia limpa"

# Controles: variações de nome que o guarda deve pegar hoje.
pega design 'val f = kotlin.io.path.createTempFile("x", ".pdf")' "kotlin.io.path.createTempFile"
pega app 'import java.io.File.createTempFile as tmp' "createTempFile por import com alias"
pega design 'java.awt.Desktop.getDesktop().open(f)' "java.awt.Desktop qualificado"

# Lacunas: formas que levam a ficha ao disco ou a um programa externo sem usar os nomes proibidos.
pega design 'Files.newOutputStream(Path.of(System.getProperty("java.io.tmpdir"), "ficha.pdf")).use { it.write(b) }' \
  "Files.newOutputStream sob java.io.tmpdir"
pega app 'val dir = System.getProperty("java.io.tmpdir")' "leitura de java.io.tmpdir"
pega design 'import java.awt.Desktop.*
fun f(a: java.io.File) = getDesktop().open(a)' "import estrela de Desktop com getDesktop() sem qualificador"
pega app 'ProcessBuilder("xdg-open", path).start()' "xdg-open por ProcessBuilder"
pega design 'Runtime.getRuntime().exec(arrayOf("open", path))' "Runtime.exec abrindo um programa externo"

finish
