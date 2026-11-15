#!/usr/bin/env bash
# Guarda estática da M2 E07 (ADR 0009): a ficha de registro só vai ao disco por "Salvar como…". Nenhuma API que
# crie arquivo temporário, abra o PDF num programa externo ou ligue um cache de disco do PDFBox pode aparecer no
# código de produção do design e do app. Lê só o código-fonte; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

dirs=("$ROOT/design/src/main" "$ROOT/app/src/main")
for d in "${dirs[@]}"; do [[ -d "$d" ]] || _assert_fail "diretório de produção existe: ${d#"$ROOT"/}"; done

# Nomes proibidos, como texto literal.
proibidos=(
  'createTempFile'
  'createTempDirectory'
  'Files.createTemp'
  'deleteOnExit'
  'pdfbox.fontcache'
  'Desktop.getDesktop'
  'browse('
  'createTempFileOnlyStreamCache'
  'setupTempFileOnly'
  'MemoryUsageSetting'
  'ScratchFile'
)
for nome in "${proibidos[@]}"; do
  achados="$(grep -rnF --include='*.kt' --include='*.java' -- "$nome" "${dirs[@]}" 2>/dev/null)"
  assert_eq "" "$achados" "produção sem '$nome'"
done

# `.open(` num Desktop (`Desktop.getDesktop().open(...)`, ou uma variável do tipo Desktop).
achados="$(grep -rnE --include='*.kt' --include='*.java' 'Desktop.*\.open\(' "${dirs[@]}" 2>/dev/null)"
assert_eq "" "$achados" "produção sem .open( num Desktop"

finish
