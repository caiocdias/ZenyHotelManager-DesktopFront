#!/usr/bin/env bash
# Poder do teste do roteiro do M2 (M2 E06, Task 5): mutantes do docs/demo/roteiro-m2.md que um defeito visível ao
# usuário produziria. As cópias mutadas ficam num diretório temporário; o roteiro nunca é alterado.
#  (1) travas extras (função xchecks), que cobrem o que o test_roteiro_m2_ataque.sh deixava passar: título do último
#      passo, contagens da aba Hospedados, "Resultado esperado" de cada subpasso, defeitos conhecidos da ficha, linhas
#      do Erros comuns, contagens do seed repetido e a busca do walk-in. Rodam no roteiro real (têm de passar) e em
#      mutantes (têm de reprovar).
#  (2) uma amostra de mutantes que o test_roteiro_m2_ataque.sh já reprova, para ele não perder força: o script é
#      rodado inteiro, em paralelo, contra cada cópia.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

rot="$ROOT/docs/demo/roteiro-m2.md"
catalog_kt="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/data/DemoCatalog.kt"
base_test="$ROOT/tests/test_roteiro_m2_ataque.sh"
for f in "$rot" "$catalog_kt" "$base_test"; do
  [[ -f "$f" ]] || {
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    exit 1
  }
done
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- (1) travas extras ------------------------------------------------------------------------------------------
# xchecks ROTEIRO : imprime uma linha "FALHA: ..." por trava que o roteiro não cumpre.
xchecks() {
  local r="$1" last last_title res n_ci n_res c k s n h seg
  fail() { printf 'FALHA: %s\n' "$1"; }
  local st="rc_step $r"
  last="$(grep -oE '^## [0-9]+\.' "$r" | tail -1 | grep -oE '[0-9]+')"
  last_title="$(grep -E "^## $last\. " "$r")"
  [[ "$last_title" == "## $last. No fim" ]] || fail "o último passo se chama \"No fim\" (achei: $last_title)"
  res="$(grep -E '^ +r\("[A-Z][0-9]+",' "$catalog_kt")"
  n_res="$(grep -c . <<<"$res")"
  n_ci="$(grep -cE ', true\),?$' <<<"$res")"
  # Aba Hospedados: 7 do seed, +1 no check-in, +1 no walk-in; o desfazer devolve o do check-in.
  local ci_n walk_n undo_n
  ci_n="$(rc_step_with "$r" '\*\*Fazer check-in\*\*')"
  walk_n="$(rc_step_with "$r" '\*\*Concluir walk-in\*\*')"
  undo_n="$(rc_step_with "$r" '\*\*Login de quem aprova\*\*')"
  grep -qF "\"Hospedados ($((n_ci + 1)))\"" <<<"$($st "${ci_n:-x}")" || fail "check-in: a aba passa a Hospedados ($((n_ci + 1)))"
  grep -qF "\"Hospedados ($((n_ci + 2)))\"" <<<"$($st "${walk_n:-x}")" || fail "walk-in: a aba passa a Hospedados ($((n_ci + 2)))"
  grep -qF "\"Hospedados ($((n_ci + 1)))\"" <<<"$($st "${undo_n:-x}")" || fail "desfazer: a aba volta a Hospedados ($((n_ci + 1)))"
  # Todo subpasso **N.M.** traz o seu "Resultado esperado" antes do próximo subpasso ou passo.
  while IFS= read -r l; do
    [[ -n "$l" ]] || continue
    fail "subpasso sem \"Resultado esperado\": $l"
  done < <(awk '
    function flush() { if (cur != "" && !ok) print cur; cur = ""; ok = 0 }
    /^## / || /^\*\*[0-9]+\.[0-9]+\. / { flush() }
    /^\*\*[0-9]+\.[0-9]+\. / { cur = substr($0, 1, 40); ok = 0 }
    /\*\*Resultado esperado/ { ok = 1 }
    END { flush() }' "$r")
  # Defeitos conhecidos da ficha: escala 125/150% e o diálogo de impressão atrás da janela.
  n="$(rc_step_with "$r" 'Microsoft Print to PDF')"
  s="$($st "${n:-x}")"
  grep -qF 'Defeitos conhecidos' <<<"$s" || fail "ficha: seção de defeitos conhecidos"
  grep -qF 'o diálogo de impressão pode abrir atrás da janela do app' <<<"$s" || fail "ficha: o diálogo de impressão pode abrir atrás da janela"
  grep -qF '125% ou 150%' <<<"$s" || fail "ficha: escala de tela de 125% ou 150%"
  grep -qF 'asteriscos' <<<"$s" || fail "ficha: o documento aparece mascarado com asteriscos"
  grep -E "^\| No passo ${n:-x}, depois de clicar em \*\*Imprimir\*\*, nada aparece \|" "$r" | grep -qF 'Alt+Tab' ||
    fail "Erros comuns: linha do diálogo de impressão atrás da janela, com o passo $n e o Alt+Tab"
  # Erros comuns: aprovador bloqueado e sessão encerrada na edição concorrente.
  grep -E '^\| No app, ao aprovar: `A conta do aprovador está bloqueada\. Aguarde e tente de novo\.` \|' "$r" | grep -qF 'PIN do `gerente`' ||
    fail "Erros comuns: linha do aprovador bloqueado"
  n="$(rc_step_with "$r" 'Alguém alterou este recado')"
  grep -E "^\| No passo ${n:-x}, a primeira janela mostra \`Sua sessão foi encerrada\. Entre novamente\.\` \|" "$r" | grep -qF 'mesmo usuário' ||
    fail "Erros comuns: linha da sessão encerrada no passo $n"
  # Recados do seed: três.
  n="$(rc_step_with "$r" '\*\*Novo recado\*\*')"
  grep -qF 'mostra os três recados de exemplo do seed' <<<"$($st "${n:-x}")" || fail "recados: a tabela mostra os três recados do seed"
  # O seed repetido depois da demo, no passo final (contagens do catálogo).
  s="$($st "$last" | awk '/^### Rodar o seed de novo depois da demo$/ {on = 1; next} on && /^###? / {exit} on')"
  grep -qF "\`reservas: 0 criados, $n_res já existiam\`" <<<"$s" || fail "No fim: reservas: 0 criados, $n_res já existiam"
  grep -qF '`usuários: 0 criados, 3 já existiam`' <<<"$s" || fail "No fim: usuários: 0 criados, 3 já existiam"
  # A linha final da saída do seed está no bloco de texto da saída, não só na prosa.
  n="$(rc_step_with "$r" 'demo-seed\.jar --server')"
  grep -qE '^  Concluído em [0-9]+,[0-9] s\.$' <<<"$($st "${n:-x}")" || fail "seed: a saída termina na linha \"Concluído em N,N s.\" do bloco"
  # Walk-in: o termo buscado é o do hóspede escolhido.
  n="$(rc_step_with "$r" '\*\*Concluir walk-in\*\*')"
  s="$($st "${n:-x}")"
  k="$(grep -oE 'no campo \*\*Nome\*\*, digite `[^`]+`' <<<"$s" | grep -oE '`[^`]+`' | tr -d '`')"
  h="$(grep -oE 'aparece "Hóspede Demonstração [^"]+"' <<<"$s" | grep -oE '[^ "]+"$' | tr -d '"')"
  [[ -n "$k" && "$k" == "$h" ]] || fail "walk-in: a busca ($k) acha o hóspede escolhido ($h)"
  # O mapa da troca de UH mostra o selo "Ao vivo".
  n="$(rc_step_with "$r" 'Troca feita para a UH')"
  grep -qF 'selo "Ao vivo"' <<<"$($st "${n:-x}")" || fail "troca: o mapa mostra o selo \"Ao vivo\""
}

ctl="$(xchecks "$rot")"
assert_eq "" "$ctl" "controle: o roteiro-m2.md intacto cumpre as travas extras"

# xkills NOME EXPRESSÃO_SED PADRÃO : a cópia mutada difere do roteiro e as travas extras reprovam citando PADRÃO.
xkills() {
  local name="$1" out
  sed -E "$2" "$rot" >"$tmp/$name.md"
  if cmp -s "$rot" "$tmp/$name.md"; then
    _assert_fail "mutante $name difere do roteiro (a expressão não casou; o teste seria vazio)"
    return
  fi
  out="$(xchecks "$tmp/$name.md")"
  assert_contains "$out" "$3" "mutante $name: as travas extras reprovam"
}
xkills hospedados-check-in '/^## 12\./,/^## 13\./s/Hospedados \(8\)/Hospedados (7)/' 'check-in: a aba passa a Hospedados'
xkills hospedados-desfazer '/^## 16\./,/^## 17\./s/Hospedados \(8\)/Hospedados (9)/' 'desfazer: a aba volta a Hospedados'
xkills hospedados-walkin '/^## 14\./,/^## 15\./s/Hospedados \(9\)/Hospedados (8)/' 'walk-in: a aba passa a Hospedados'
xkills titulo-no-fim 's/^## 20\. No fim$/## 20. Fim/' 'o último passo se chama'
xkills sem-resultado-subpasso '/^\*\*Resultado esperado:\*\* a linha sai da lista dos abertos/d' 'subpasso sem'
xkills sem-defeito-impressao 's/o diálogo de impressão pode abrir atrás da janela do app/o diálogo de impressão abre normalmente/' 'atrás da janela'
xkills sem-linha-erro-impressao '/^\| No passo 13, depois de clicar/d' 'Erros comuns: linha do diálogo de impressão'
xkills linha-erro-impressao-outro-passo 's/No passo 13, depois de clicar/No passo 14, depois de clicar/' 'Erros comuns: linha do diálogo de impressão'
xkills sem-defeito-escala 's/125% ou 150%/200%/' '125% ou 150%'
xkills ficha-sem-asteriscos 's/o documento mascarado, com asteriscos no lugar dos primeiros dígitos do CPF; o número inteiro não aparece\./o documento./' 'asteriscos'
xkills sem-linha-aprovador-bloqueado '/^\| No app, ao aprovar:/d' 'aprovador bloqueado'
xkills sem-linha-sessao-encerrada '/^\| No passo 18, a primeira janela/d' 'sessão encerrada'
xkills recados-dois-no-seed 's/mostra os três recados de exemplo do seed/mostra os dois recados de exemplo do seed/' 'três recados'
xkills nofim-reservas '/^### Rodar o seed/,/^### Esqueci/s/`reservas: 0 criados, 11 já existiam`/`reservas: 0 criados, 10 já existiam`/' 'reservas: 0 criados'
xkills nofim-usuarios 's/^- As demais linhas dizem `0 criados, N já existiam`, e `usuários: 0 criados, 3 já existiam`/- As demais linhas dizem `0 criados, N já existiam`, e `usuários: 0 criados, 2 já existiam`/' 'usuários: 0 criados'
xkills seed-sem-linha-final 's/^  Concluído em 9,6 s\.$/  Pronto./' 'Concluído em'
xkills walkin-busca-outro-hospede '/^## 14\./,/^## 15\./s/digite `Doze`/digite `Cinco`/' 'a busca'
xkills troca-sem-selo 's/com o selo "Ao vivo"/com o selo/' 'selo'

# --- (2) amostra de mutantes que o test_roteiro_m2_ataque.sh precisa continuar reprovando ---------------------------
# O script é copiado com o ROOT fixo e o roteiro trocado por $ROT; a cópia fica fora de tests/.
drv="$tmp/driver.sh"
sed -e "s#^ROOT=.*#ROOT='$ROOT'#" -e 's#^rot="\$ROOT/docs/demo/roteiro-m2.md"#rot="$ROT"#' "$base_test" >"$drv"
grep -q '^rot="\$ROT"$' "$drv" || _assert_fail "o test_roteiro_m2_ataque.sh declara rot=\"\$ROOT/docs/demo/roteiro-m2.md\" (o driver dos mutantes depende disso)"
mutants=(
  'literal-pw-seed|0,/^(java -jar app\\demo-seed\.jar .*)$/s//\1 --manager-password x/|FALHOU'
  'admin-password-arg|0,/^(java -jar app\\demo-seed\.jar .*)$/s//\1 --admin-password x/|FALHOU'
  'readhost-texto|s/Read-Host -AsSecureString/Read-Host/|Read-Host'
  'sem-remove-pin|/^Remove-Variable pin$/d|Remove-Variable'
  'ps7-coalesce|s/^\$session\.passwordChangeRequired$/$session.passwordChangeRequired ?? $false/|FALHOU'
  'ps7-andand|s/^Remove-Variable loginBody$/Remove-Variable loginBody \&\& $true/|&&'
  'pin-literal-16|s/um PIN diferente do item 5 da folha, com 4 a 8 dígitos/o PIN 1234, com 4 a 8 dígitos/|PIN'
  'aprovacao-item-errado|s/o PIN do Gerente \(item 5 da folha\)/o PIN do Gerente (item 2 da folha)/|item'
  'aprovacao-login-governanta|0,/Em \*\*Login de quem aprova\*\*, digite `gerente`/s//Em **Login de quem aprova**, digite `governanta`/|gerente'
  'desfazer-hospedado-do-seed|/^## 16\./,/^## 17\./s/Hóspede Demonstração Três/Hóspede Demonstração Cinco/g|desfazer'
  'recado-do-seed-resolvido|s/na linha "Teste do roteiro: recado no detalhe.", clique em \*\*Resolver\*\*/na linha "Confirmar o horário de chegada por telefone.", clique em **Resolver**/|seed'
  'seed-checkins-depois-recados|/^  check-ins: 7 criados, 0 já existiam$/d;s/^  recados: 3 criados, 0 já existiam$/  recados: 3 criados, 0 já existiam\n  check-ins: 7 criados, 0 já existiam/|seed'
  'seed-sem-recados|/^  recados: 3 criados, 0 já existiam$/d|recados'
  'rotulo-412|s/Alguém alterou este recado\. Confira e salve de novo\./Alguem alterou este recado. Confira e salve de novo./|412'
  'sem-reprovacao-16|/^## 16\./,/^## 17\./{/^\*\*Reprovação/d}|Reprovação'
  'sem-resultado-ficha|/^## 15\./,/^## 16\./s/\*\*Resultado esperado/Esperado/g|Resultado esperado'
  'nofim-sem-passo|/^## 20\. No fim$/,$d|FALHOU'
  'nofim-bloco-seed|/^### Rodar o seed de novo depois da demo$/s/$/\n\n```powershell\njava -jar app\\demo-seed.jar --server http:\/\/localhost:8088 --admin-login admin\n```/|FALHOU'
  'leia-compose-diverge|0,/^docker compose -p zeny-front -f server\/docker\/compose\.yaml -f compose\.override\.yaml --env-file \.env build$/s//docker compose -p zeny-front -f server\/docker\/compose.yaml --env-file .env build/|LEIA-ME'
  'app-sem-modo-demo|s/^java "-Dzeny\.devShell=true" -jar app\\zeny-desktop\.jar$/java -jar app\\zeny-desktop.jar/|FALHOU'
  'segunda-janela-recepcao|/^## 18\./,/^## 19\./s/Em \*\*Login\*\*, digite `gerente`/Em **Login**, digite `recepcao`/|recepcao'
  'segunda-janela-comando-proprio|s/rode o comando de abrir o app do passo 6: copie a linha do bloco do passo 6, com as aspas, e cole aqui\./rode:\n\n```powershell\njava -jar app\\zeny-desktop.jar\n```/|FALHOU'
  'chegadas-do-painel|s/"Chegadas \(11\)"/"Chegadas (10)"/|Chegadas'
)
run_mutant() {
  local name="${1%%|*}" rest="${1#*|}"
  local expr="${rest%|*}"
  sed -E "$expr" "$rot" >"$tmp/s-$name.md"
  if cmp -s "$rot" "$tmp/s-$name.md"; then
    echo "NOCHANGE" >"$tmp/s-$name.out"
    return
  fi
  ROT="$tmp/s-$name.md" timeout 150 bash "$drv" >"$tmp/s-$name.out" 2>&1 </dev/null
  echo "rc=$?" >>"$tmp/s-$name.out"
}
ctl_out="$(ROT="$rot" timeout 150 bash "$drv" 2>&1 </dev/null)"
assert_eq "0" "$?" "controle: o test_roteiro_m2_ataque.sh passa no roteiro intacto"
running=0
for m in "${mutants[@]}"; do
  run_mutant "$m" &
  running=$((running + 1))
  if ((running >= 4)); then
    wait -n
    running=$((running - 1))
  fi
done
wait
for m in "${mutants[@]}"; do
  name="${m%%|*}"
  want="${m##*|}"
  out="$(cat "$tmp/s-$name.out")"
  if [[ "$out" == NOCHANGE ]]; then
    _assert_fail "mutante $name difere do roteiro (a expressão não casou; o teste seria vazio)"
    continue
  fi
  assert_contains "$out" "rc=1" "mutante $name: o test_roteiro_m2_ataque.sh reprova"
  assert_contains "$out" "$want" "mutante $name: a falha cita [$want]"
done

finish
