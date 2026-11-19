#!/usr/bin/env bash
# Ataque à biblioteca de travas genéricas dos roteiros (M2 E06 Task 4): tests/lib/roteiro_checks.sh.
# (1) segura para `source`: não imprime, não liga set -e, trap, cd nem variável global; o nome não é test_*.sh.
# (2) caminho do roteiro ausente ou vazio reprova alto (um caminho errado na Task 5 não pode passar calado).
# (3) mutações de segredo, PowerShell 5.1, folha de senhas e linhas do LEIA-ME, feitas numa cópia do
#     roteiro-m1.md no diretório temporário, reprovam as travas genéricas; o roteiro intacto passa.
# Estático: sem pwsh, Docker nem Gradle. Não altera nenhum arquivo do repositório.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

lib="$ROOT/tests/lib/roteiro_checks.sh"
rot="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
readme_f="$ROOT/README.md"
docs_test="$ROOT/tests/test_docs.sh"
for f in "$lib" "$rot" "$leia_f" "$readme_f" "$docs_test"; do
  [[ -f "$f" ]] || { _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"; finish; }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- 1. segura para source ----------------------------------------------------------------------------------
case "$(basename "$lib")" in
  test_*) _assert_fail "a biblioteca não pode se chamar test_*.sh (o tests/run.sh a executaria como teste)" ;;
esac
probe="$tmp/probe.sh"
cat >"$probe" <<'PROBE'
cd "$1" || exit 99
before_opts="$(set +o; shopt -p)"
before_vars="$(compgen -v | sort)"
before_funcs="$(declare -F | sort)"
before_pwd="$PWD"
before_flags="$-"
ROOT="$1"
out="$(source "$1/tests/lib/roteiro_checks.sh" 2>&1)"
source "$1/tests/lib/roteiro_checks.sh"
after_flags="$-"
echo "OUT=[$out]"
echo "OPTS_SAME=$([[ "$before_opts" == "$(set +o; shopt -p)" && "$before_flags" == "$after_flags" ]] && echo 1 || echo 0)"
echo "TRAPS=[$(trap -p)]"
echo "PWD_SAME=$([[ "$before_pwd" == "$PWD" ]] && echo 1 || echo 0)"
echo "NEWVARS=[$(comm -13 <(printf '%s\n' "$before_vars") <(compgen -v | sort) | grep -vE '^(before_|after_|out$|_$|ROOT$|BASH_|PIPESTATUS|LINENO)' | tr '\n' ' ')]"
echo "FOREIGN_FUNCS=[$(comm -13 <(printf '%s\n' "$before_funcs") <(declare -F | sort) | awk '{print $3}' | grep -vE '^(rc_|roteiro_check_)' | tr '\n' ' ')]"
PROBE
src_out="$(env -i PATH="$PATH" HOME="$HOME" bash "$probe" "$ROOT" </dev/null 2>&1)"
assert_contains "$src_out" "OUT=[]" "carregar a biblioteca não imprime nada"
assert_contains "$src_out" "OPTS_SAME=1" "carregar a biblioteca não muda as opções do shell (set -e, shopt...)"
assert_contains "$src_out" "TRAPS=[]" "carregar a biblioteca não instala trap"
assert_contains "$src_out" "PWD_SAME=1" "carregar a biblioteca não muda o diretório corrente"
assert_contains "$src_out" "NEWVARS=[]" "carregar a biblioteca não cria variável global"
assert_contains "$src_out" "FOREIGN_FUNCS=[]" "toda função da biblioteca tem prefixo rc_ ou roteiro_check_"

# --- 2. caminho ausente ou vazio reprova alto ---------------------------------------------------------------
: >"$tmp/vazio.md"
# driver FN ARGS... : roda a função com a biblioteca e imprime as falhas (ASSERT_FAILS) na última linha.
driver="$tmp/driver.sh"
cat >"$driver" <<'DRV'
ROOT="$1"; shift
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"
fn="$1"; shift
"$fn" "$@" 2>&1
echo "ASSERT_FAILS=$ASSERT_FAILS"
DRV
fails_of() { timeout 60 bash "$driver" "$ROOT" "$@" </dev/null | sed -n 's/^ASSERT_FAILS=//p'; }

for bad in "$tmp/nao-existe.md" "$tmp/vazio.md" ""; do
  label="${bad:+${bad#"$tmp"/}}"
  label="${label:-<vazio>}"
  while read -r fn rest; do
    # shellcheck disable=SC2086
    n="$(fails_of "$fn" "$bad" $rest)"
    [[ "${n:-0}" =~ ^[0-9]+$ ]] || n=0
    ((n > 0)) || _assert_fail "$fn reprova com o caminho do roteiro $label" "falhas: ${n:-0}"
  done <<LISTA
roteiro_check_powershell_51 $leia_f
roteiro_check_seed_secrets $leia_f $readme_f
roteiro_check_leia_app_and_vars $leia_f
roteiro_check_password_sheet
roteiro_check_identical_to_leia $leia_f $docs_test
roteiro_check_folha_before_secrets
roteiro_check_shared_lines $leia_f linha-qualquer
roteiro_check_seed_order
roteiro_check_ps51_lines
roteiro_check_prompt_blocks
roteiro_check_no_secret_text
roteiro_check_seed_secret_values
roteiro_check_md_refs
LISTA
done

# --- 3. mutações no roteiro-m1.md --------------------------------------------------------------------------
# all_run ROTEIRO : roda as travas genéricas (menos shared_lines, cuja lista vive no test_docs.sh) e imprime a
# saída; a última linha traz a contagem de falhas.
allrun="$tmp/allrun.sh"
cat >"$allrun" <<'DRV'
ROOT="$1"; r="$2"; L="$3"; R="$4"; D="$5"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"
roteiro_check_powershell_51 "$r" "$L"
roteiro_check_seed_secrets "$r" "$L" "$R"
roteiro_check_leia_app_and_vars "$r" "$L"
roteiro_check_password_sheet "$r"
roteiro_check_identical_to_leia "$r" "$L" "$D"
roteiro_check_folha_before_secrets "$r"
roteiro_check_seed_order "$r"
roteiro_check_ps51_lines "$r"
roteiro_check_prompt_blocks "$r"
roteiro_check_no_secret_text "$r"
roteiro_check_seed_secret_values "$r"
roteiro_check_md_refs "$r"
echo "ASSERT_FAILS=$ASSERT_FAILS"
DRV
all_run() { timeout 100 bash "$allrun" "$ROOT" "$1" "$leia_f" "$readme_f" "$docs_test" </dev/null; }

ctl="$(all_run "$rot")"
assert_eq "ASSERT_FAILS=0" "$(tail -1 <<<"$ctl")" "controle: o roteiro-m1.md intacto passa nas travas genéricas"
[[ "$(tail -1 <<<"$ctl")" == "ASSERT_FAILS=0" ]] || printf '%s\n' "$ctl" | head -20

# kills NOME PADRÃO_NA_SAÍDA DESCRIÇÃO (a expressão sed vem em $MUT): faz a cópia mutada, exige que ela difira do
# original e que as travas genéricas reprovem citando a regra esperada.
kills() {
  local name="$1" want="$2" msg="$3" out
  sed -E "$MUT" "$rot" >"$tmp/$name.md"
  if cmp -s "$rot" "$tmp/$name.md"; then
    _assert_fail "mutante $name difere do roteiro (a expressão não casou; o teste seria vazio)"
    return
  fi
  out="$(all_run "$tmp/$name.md")"
  [[ "$(tail -1 <<<"$out")" != "ASSERT_FAILS=0" ]] || _assert_fail "$msg: as travas genéricas passaram com o mutante $name"
  assert_contains "$out" "$want" "$msg: a falha cita a regra esperada"
}

MUT='s/Read-Host -AsSecureString/Read-Host/'
kills read-host-texto "Read-Host" "PIN lido com Read-Host em texto"
MUT='/^Remove-Variable pin$/d'
kills sem-remove-pin "Remove-Variable pin" "sem Remove-Variable pin"
MUT='0,/^(java -jar app\\demo-seed\.jar .*)$/s//\1 --admin-password x/'
kills admin-password-arg "password" "senha do administrador em argumento"
MUT='0,/^(java -jar app\\demo-seed\.jar .*)$/s//\1 --manager-pin 1234/'
kills manager-pin-arg "pin" "PIN do Gerente em argumento"
MUT='s/\(Get-Credential -UserName gerente -Message "Senha do Gerente"\)\.GetNetworkCredential\(\)\.Password/"segredo123"/'
kills getcred-gerente-literal "Get-Credential" "senha do Gerente literal no lugar do Get-Credential"
MUT='0,/-UseBasicParsing /s///'
kills iwr-sem-basicparsing "UseBasicParsing" "Invoke-WebRequest sem -UseBasicParsing"
MUT='s/^Remove-Variable loginBody$/Remove-Variable loginBody \&\& $true/'
kills operador-and-and "&&" "operador && do PowerShell 7"
MUT='s/ConvertFrom-Json/ConvertFrom-Json -AsHashtable/'
kills ashashtable "AsHashtable" "parâmetro -AsHashtable do PowerShell 7"
MUT='s/senha da Recepção \(item 3 da folha\)/senha da Recepção (item 2 da folha)/'
kills folha-item-errado "item 3" "login da Recepção apontando o item errado da folha"
MUT='/^\| 5 \| PIN do Gerente/d'
kills folha-sem-pin "folha" "folha sem o item 5 (PIN)"
MUT='0,/^(java -jar app\\demo-seed\.jar --server )http:\/\/localhost:8088/s//\1http:\/\/localhost:9099/'
kills seed-diferente-do-leia "LEIA-ME" "linha do seed diferente da do LEIA-ME"
# (o nome da variável é montado aqui para o test_smoke_task.sh não achar um segredo literal neste arquivo)
MUT='s/"Process"\)$/"Process"); $env:ZENY_SEED_ADMIN_'"PASS"'WORD="abc"/'
kills segredo-literal-no-env "ZENY_SEED" "valor de segredo atribuído numa linha"
# roteiro_check_md_refs (M3 E05): um roteiro inexistente, um .md fora da lista e um roteiro que o pacote não exige.
# Desde a M4 E05 o roteiro-m4.md fica entre o m3 e o LEIA-ME: o m9 entra antes do LEIA-ME, na lista do passo 0.
MUT='/Ela tem as pastas/s/, `LEIA-ME\.md`, /, `roteiro-m9.md`, `LEIA-ME.md`, /'
kills cita-roteiro-m9 "roteiro-m9.md" "roteiro citando um roteiro que não existe"
MUT='s/Você não precisa abrir o `LEIA-ME\.md`/Você não precisa abrir o `NOTAS.md`/'
kills cita-outro-md "não remete a outro .md" "roteiro citando um .md fora da lista"

finish
