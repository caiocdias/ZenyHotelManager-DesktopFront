#!/usr/bin/env bash
# Ataque à M1 E07 Task 3: as correções pequenas dos commits ccd7e97 e 2816021.
# 1. package.sh com artefato ilegível (-r): JAR, roteiro e diretórios sem permissão; o pacote anterior sobrevive.
# 2. Bloco do seed no LEIA-ME e a nota do seed repetido no roteiro, contra o código do demo-seed.
# Fronts e servidores falsos; nenhum Gradle, nenhum container, nenhum servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwX "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

# =============================================================================================================
# 1. package.sh e artefatos ilegíveis
# =============================================================================================================
make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT \
  COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES

# new_front DIR : front falso com pin, JARs e roteiro válidos.
new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$FAKE_C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m pin
  make_fake_jars "$1"
}
pkg_sh() { timeout 60 bash "$1/tools/server-env/package.sh"; }
# tree_sum DIR : lista, modos e sha256 de todos os arquivos de DIR (pacote anterior intacto, byte a byte).
tree_sum() { (cd "$1" && find . -printf '%p %m\n' | sort && find . -type f -print0 | sort -z | xargs -0 sha256sum) 2>&1; }

if (($(id -u) == 0)); then
  # Como root, chmod 000 não impede a leitura: os casos abaixo não provariam nada.
  skip_case "package.sh com artefato ilegível (rodando como root)"
else
  fr="$tmp/fr"
  new_front "$fr"
  pk="$fr/build/demo-package"
  assert_exit 0 "pacote anterior: package.sh sai com 0" -- pkg_sh "$fr"
  echo marcador >"$pk/marcador.txt"
  antes="$(tree_sum "$pk")"
  rot="$fr/docs/demo/roteiro-m1.md"
  app_jar="$fr/app/build/libs/zeny-desktop.jar"
  seed_jar="$fr/demo-seed/build/libs/demo-seed.jar"

  # ilegivel_preserva DESCRIÇÃO DICA : sai com 1, cita DICA, não fala em python3 e deixa o pacote igual, byte a byte.
  ilegivel_preserva() {
    assert_exit 1 "$1: package.sh sai com 1" -- pkg_sh "$fr"
    assert_contains "$ASSERT_OUT" "$2" "$1: mensagem cita $2"
    assert_contains "$ASSERT_OUT" "legível" "$1: mensagem diz que o artefato precisa ser legível"
    assert_not_contains "$ASSERT_OUT" "python3" "$1: recusado antes da validação do ZIP pelo python3"
    assert_not_contains "$ASSERT_OUT" "pacote: " "$1: não anuncia pacote montado"
    assert_eq "$antes" "$(tree_sum "$pk")" "$1: pacote anterior intacto, byte a byte"
  }

  # 1a. Roteiro ilegível depois de um pacote anterior.
  chmod 000 "$rot"
  ilegivel_preserva "roteiro chmod 000" "docs/demo/roteiro-m1.md"
  chmod 644 "$rot"

  # 1b. Roteiro legível só pelo grupo e pelos outros: -r vale para o dono, que não lê.
  chmod 044 "$rot"
  ilegivel_preserva "roteiro chmod 044 (dono sem leitura)" "docs/demo/roteiro-m1.md"
  chmod 644 "$rot"

  # 1c. JAR ilegível (cada um) depois de um pacote anterior: a recusa vem antes do python3.
  chmod 000 "$app_jar"
  ilegivel_preserva "zeny-desktop.jar chmod 000" "./gradlew :app:desktopJar"
  chmod 644 "$app_jar"
  chmod 000 "$seed_jar"
  ilegivel_preserva "demo-seed.jar chmod 000" "./gradlew :demo-seed:seedJar"
  chmod 644 "$seed_jar"

  # 1d. docs/demo sem permissão de execução: o roteiro não é alcançável (nem o -f passa).
  chmod 644 "$fr/docs/demo"
  assert_exit 1 "docs/demo sem x: package.sh sai com 1" -- pkg_sh "$fr"
  assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m1.md" "docs/demo sem x: mensagem cita o roteiro"
  assert_eq "$antes" "$(tree_sum "$pk")" "docs/demo sem x: pacote anterior intacto, byte a byte"
  chmod 755 "$fr/docs/demo"

  # 1e. docs sem permissão de execução (o avô do roteiro): mesmo efeito.
  chmod 644 "$fr/docs"
  assert_exit 1 "docs sem x: package.sh sai com 1" -- pkg_sh "$fr"
  assert_contains "$ASSERT_OUT" "docs/demo/roteiro-m1.md" "docs sem x: mensagem cita o roteiro"
  assert_eq "$antes" "$(tree_sum "$pk")" "docs sem x: pacote anterior intacto, byte a byte"
  chmod 755 "$fr/docs"

  # 1f. app/build/libs sem permissão de execução: o JAR não é alcançável.
  chmod 644 "$fr/app/build/libs"
  assert_exit 1 "app/build/libs sem x: package.sh sai com 1" -- pkg_sh "$fr"
  assert_contains "$ASSERT_OUT" "./gradlew :app:desktopJar" "app/build/libs sem x: mensagem cita o comando do Gradle"
  assert_eq "$antes" "$(tree_sum "$pk")" "app/build/libs sem x: pacote anterior intacto, byte a byte"
  chmod 755 "$fr/app/build/libs"

  # 1g. Falso positivo: docs/demo sem leitura (r) mas com x. O roteiro continua alcançável e legível,
  #     então o pacote sai, com o roteiro igual ao original.
  chmod 311 "$fr/docs/demo"
  assert_exit 0 "docs/demo só com x: package.sh sai com 0" -- pkg_sh "$fr"
  chmod 755 "$fr/docs/demo"
  assert_eq "1" "$(cmp -s -- "$rot" "$pk/roteiro-m1.md" && echo 1 || echo 0)" "docs/demo só com x: roteiro copiado igual"
  assert_eq "0" "$([[ -e "$pk/marcador.txt" ]] && echo 1 || echo 0)" "docs/demo só com x: pacote recriado"

  # 1h. Roteiro ilegível sem pacote anterior: nada de build/demo-package criado pela metade.
  fn="$tmp/fn"
  new_front "$fn"
  chmod 000 "$fn/docs/demo/roteiro-m1.md"
  assert_exit 1 "sem pacote anterior, roteiro ilegível: sai com 1" -- pkg_sh "$fn"
  assert_eq "0" "$([[ -e "$fn/build/demo-package" ]] && echo 1 || echo 0)" \
    "sem pacote anterior, roteiro ilegível: build/demo-package não é criado"
  chmod 644 "$fn/docs/demo/roteiro-m1.md"

  # 1i. Restaurado tudo, o mesmo checkout volta a montar o pacote.
  assert_exit 0 "permissões restauradas: package.sh volta a sair com 0" -- pkg_sh "$fr"
fi

# =============================================================================================================
# 2. LEIA-ME (bloco do seed) e roteiro, contra o código
# =============================================================================================================
leia_file="$ROOT/tools/server-env/LEIA-ME.md"
rot_file="$ROOT/docs/demo/roteiro-m1.md"
seed_src="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed"
leia="$(cat "$leia_file")"

# 2a. "Uma linha por vez" vem antes de qualquer linha que pede segredo no LEIA-ME, e o próximo bloco de código
#     depois da frase é o próprio bloco do seed (nenhum outro bloco no meio).
one_ln="$(grep -niF 'uma linha por vez' "$leia_file" | head -1 | cut -d: -f1)"
# Linha de código que pede segredo (a prosa cita os cmdlets entre crases; o código os chama com parâmetros).
first_secret_ln="$(grep -nE '\(Get-Credential |= Get-Credential|Read-Host -' "$leia_file" | head -1 | cut -d: -f1)"
if [[ -z "$one_ln" || -z "$first_secret_ln" ]] || ((one_ln > first_secret_ln)); then
  _assert_fail "LEIA-ME: 'uma linha por vez' antes da primeira linha com Get-Credential ou Read-Host" \
    "frase: ${one_ln:-ausente}; primeira linha de segredo: ${first_secret_ln:-ausente}"
else
  fence_after="$(awk -v s="$one_ln" 'NR > s && /^```/ { print NR; exit }' "$leia_file")"
  block_end="$(awk -v s="$fence_after" 'NR > s && /^```/ { print NR; exit }' "$leia_file")"
  assert_eq "powershell" "$(sed -n "${fence_after}p" "$leia_file" | sed 's/^```//')" \
    "LEIA-ME: o primeiro bloco depois de 'uma linha por vez' é PowerShell"
  if [[ -z "$block_end" ]] || ((first_secret_ln < fence_after || first_secret_ln > block_end)); then
    _assert_fail "LEIA-ME: a primeira linha de segredo está no bloco logo depois de 'uma linha por vez'" \
      "bloco: $fence_after-$block_end; segredo: $first_secret_ln"
  fi
  assert_contains "$(sed -n "${fence_after},${block_end}p" "$leia_file")" "demo-seed.jar" \
    "LEIA-ME: o bloco logo depois de 'uma linha por vez' é o do seed"
fi

# 2b. A frase do cancelamento cita o valor anterior e pede para rodar de novo só a linha (não o bloco).
cancel="$(grep -F 'cancelar uma janela de credencial' "$leia_file")"
assert_contains "$cancel" "valor da rodada anterior" "LEIA-ME: cancelar a janela mantém o valor da rodada anterior"
assert_contains "$cancel" "rode de novo só a linha dela" "LEIA-ME: depois de cancelar, roda de novo só a linha"
assert_not_contains "$cancel" "bloco inteiro" "LEIA-ME: depois de cancelar, não manda colar o bloco inteiro"

# 2c. Valor vazio coerente com o SetEnvironmentVariable e com o seed: só a senha nova é opcional no SeedArgs,
#     e só ela é a que o LEIA-ME manda omitir ou deixa vazia.
args_kt="$seed_src/cli/SeedArgs.kt"
optional_in_help="$(grep -E '^ +--[a-z-]+ .*ZENY_SEED_[A-Z_]+ \(opcional\)' "$args_kt" | grep -oE 'ZENY_SEED_[A-Z_]+')"
assert_eq "ZENY_SEED_ADMIN_NEW_PASSWORD" "$optional_in_help" "SeedArgs: só ZENY_SEED_ADMIN_NEW_PASSWORD é opcional"
assert_contains "$(cat "$args_kt")" 'lookup("admin-new-password")?.let' \
  "SeedArgs: senha nova ausente é aceita (variável apagada = omitida)"
empty_note="$(grep -F 'valor vazio' "$leia_file")"
assert_eq "1" "$(grep -c . <<<"$empty_note")" "LEIA-ME: uma só nota fala de valor vazio"
assert_contains "$empty_note" "ZENY_SEED_ADMIN_NEW_PASSWORD" "LEIA-ME: a nota do valor vazio é a da senha nova"
assert_contains "$empty_note" "SetEnvironmentVariable" "LEIA-ME: a nota do valor vazio cita o SetEnvironmentVariable"
assert_eq "" "$(grep -niE 'n[ãa]o (a|o) deixe vazi|nunca (deixe )?vazi' "$leia_file" "$rot_file")" \
  "LEIA-ME e roteiro não proíbem valor vazio (o SetEnvironmentVariable apaga a variável)"
omit_vars="$(grep -F '**omita**' "$leia_file" | grep -oE 'ZENY_SEED_[A-Z_]+' | sort -u)"
assert_eq "ZENY_SEED_ADMIN_NEW_PASSWORD" "$omit_vars" "LEIA-ME: só a senha nova pode ser omitida"

# 2d. Roteiro x LEIA-ME: o LEIA-ME diz que, colado inteiro, o Get-Credential lê a linha seguinte do bloco. Então
#     todo bloco do roteiro com Get-Credential (ou Read-Host) seguido de outras linhas tem de dizer "uma linha por
#     vez" antes dele; ou o LEIA-ME deixa de dizer isso do Get-Credential.
leia_rule="$(grep -iF 'uma linha por vez' "$leia_file")"
if [[ "$leia_rule" == *Get-Credential* ]]; then
  # Para cada bloco powershell do roteiro: início, fim e se tem Get-Credential/Read-Host antes da última linha.
  while IFS=: read -r start end prompt_ln; do
    [[ -n "$prompt_ln" ]] || continue
    prev_text="$(awk -v s="$start" 'NR < s' "$rot_file" | awk 'BEGIN{RS=""} {p=$0} END{print p}')"
    # O parágrafo antes do bloco (ou o título do passo) tem de mandar rodar uma linha por vez.
    if [[ "$prev_text" != *"uma linha por vez"* && "$prev_text" != *"Uma linha por vez"* ]]; then
      _assert_fail "roteiro: o bloco das linhas $start-$end tem $(sed -n "${prompt_ln}p" "$rot_file" | grep -oE 'Get-Credential|Read-Host' | head -1) na linha $prompt_ln seguido de outras linhas, sem 'uma linha por vez' (o LEIA-ME diz que, colado inteiro, ele lê a linha seguinte)" \
        "parágrafo antes do bloco: ${prev_text:0:200}"
    fi
  done < <(awk '
    /^```powershell/ { s = NR; p = ""; next }
    /^```/ && s { if (p != "" && p < NR - 1) print s ":" NR ":" p; s = 0; next }
    s && /Get-Credential|Read-Host/ && p == "" { p = NR }
  ' "$rot_file")
fi

# 2e. Seed repetido (roteiro, passo 1) contra o código:
#     - alocações: só as reservas ainda CONFIRMED com UH, ou seja, as do catálogo com UH e sem check-in;
#     - check-ins: o BookingSeeder nunca conta check-in como "já existia" e o SeedReport some com a linha de total 0.
catalog_kt="$seed_src/data/DemoCatalog.kt"
booking_kt="$seed_src/booking/BookingSeeder.kt"
report_kt="$seed_src/data/SeedReport.kt"
res_lines="$(grep -E '^ +r\("[A-Z][0-9]+",' "$catalog_kt")"
n_alloc_no_checkin="$(grep -cE '"[0-9]{3}", false\)' <<<"$res_lines")"
rerun="$(grep -F 'Rodar o seed de novo' "$rot_file")"
assert_contains "$rerun" "\`alocações: 0 criados, $n_alloc_no_checkin já existiam\`" \
  "roteiro: no seed repetido, alocações = reservas do catálogo com UH e sem check-in ($n_alloc_no_checkin)"
settle="$(sed -n '/private suspend fun settle(/,/^    }/p' "$booking_kt")"
ret_ln="$(grep -n 'if (found.status != CONFIRMED) return' <<<"$settle" | head -1 | cut -d: -f1)"
alloc_ln="$(grep -n 'SeedItem.ROOM_ASSIGNMENT' <<<"$settle" | head -1 | cut -d: -f1)"
if [[ -z "$ret_ln" || -z "$alloc_ln" ]] || ((ret_ln > alloc_ln)); then
  _assert_fail "BookingSeeder.settle: reserva fora de CONFIRMED sai antes de contar a alocação" \
    "return: ${ret_ln:-ausente}; alocação: ${alloc_ln:-ausente}"
fi
assert_eq "" "$(grep -rn 'kept(SeedItem.CHECK_IN)' "$seed_src")" "seed: check-in nunca é contado como 'já existia'"
assert_contains "$(cat "$report_kt")" '.filter { createdCount(it) + keptCount(it) > 0 }' \
  "SeedReport.lines: linha com total 0 não aparece"
assert_contains "$rerun" 'a linha `check-ins` não aparece' "roteiro: no seed repetido, a linha check-ins some"
# As demais linhas não somem no seed repetido: o roteiro só cita check-ins como ausente.
assert_eq "1" "$(grep -oE 'linha `[^`]+` não aparece' <<<"$rerun" | grep -c .)" "roteiro: só a linha check-ins some"

finish
