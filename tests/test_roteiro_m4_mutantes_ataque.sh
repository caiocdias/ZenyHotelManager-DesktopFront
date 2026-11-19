#!/usr/bin/env bash
# Poder do teste do roteiro do M4 (M4 E05, testador escopado): mutantes do docs/demo/roteiro-m4.md que um defeito
# visível ao usuário produziria, cada um rodado contra o tests/test_roteiro_m4_ataque.sh inteiro (em paralelo). As
# cópias mutadas ficam num diretório temporário; o roteiro nunca é alterado. Cada mutante tem de mudar o arquivo, e
# o teste tem de reprovar citando o que o mutante quebrou.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

rot="$ROOT/docs/demo/roteiro-m4.md"
base_test="$ROOT/tests/test_roteiro_m4_ataque.sh"
for f in "$rot" "$base_test"; do
  [[ -f "$f" ]] || {
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    exit 1
  }
done
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# O script é copiado com o ROOT fixo e o roteiro trocado por $ROT; a cópia fica fora de tests/.
drv="$tmp/driver.sh"
sed -e "s#^ROOT=.*#ROOT='$ROOT'#" -e 's#^rot="\$ROOT/docs/demo/roteiro-m4.md"#rot="$ROT"#' "$base_test" >"$drv"
grep -q '^rot="\$ROT"$' "$drv" || _assert_fail "o test_roteiro_m4_ataque.sh declara rot=\"\$ROOT/docs/demo/roteiro-m4.md\" (o driver depende disso)"

# NOME|EXPRESSÃO SED|TEXTO QUE A FALHA CITA
mutants=(
  # textos entre aspas e negritos que não existem no código
  'aspas-inventada|s/"Caução registrada\."/"Caução registrada com sucesso."/|entre aspas'
  'aspas-molde|s/"Dividido entre … contas\."/"Dividido em … contas."/|entre aspas'
  'negrito-inventado|s/\*\*Registrar caução\*\*/**Registrar a caução**/g|negrito'
  'negrito-relatorio|s/\*\*Fechar relatório\*\*/**Fechar o relatório**/|negrito'
  'seed-desc-aspas|s/`Seed M4: Frigobar`, de R/"Seed M4: Frigobar", de R/|crases'
  # senha e PIN impressos
  'pin-digitado|/^## 29\./,/^## 30\./s/em \*\*PIN\*\*, o PIN do Gerente \(item 5 da folha\)/em **PIN**, digite `4821`/|PIN'
  'pin-depois-da-linha-1000|s/o PIN do Gerente \(item 5 da folha\); em \*\*Motivo\*\*, digite `Reabertura/o PIN do Gerente (item 5 da folha, como 4821); em **Motivo**, digite `Reabertura/|sem PIN literal'
  'senha-impressa|s/^(Clique no ponto \*\*Recepção\*\*\.)/\1 Se pedir a senha, digite `Recepcao#2026x`./|senha impressa'
  # regra 5 e B-M8
  'sem-regra5|/^\*\*A recriação vale só para o dia em que é feita\.\*\*/d|regra 5'
  'sem-bm8|s/, também depende da data operacional do dia da recriação:/, é fixo:/|B-M8'
  'bm8-periodo|s/da data operacional mais 5 dias à mais 7 dias/da data operacional mais 5 dias à mais 8 dias/|B-M8'
  # pacote
  'pacote-sem-m4|s/`roteiro-m3\.md`, `roteiro-m4\.md` \(este\), /`roteiro-m3.md` (este), /|passo 0'
  'passo1-sem-m4|/a lista tem `app`/s/`roteiro-m3\.md`, `roteiro-m4\.md`, /`roteiro-m3.md`, /|passo 1'
  # estrutura e herança do M3
  'titulo-36|s/^## 36\. Check-out até o saldo zero$/## 36. Check-out/|passos do M4'
  'sem-reprovacao-33|/^## 33\./,/^## 34\./{/^\*\*Reprovação/d}|reprovação'
  'folha-sem-38|s/, 34, 37 e 38,/, 34 e 37,/|folha'
  'herdado-muda|/^## 9\./,/^## 10\./s/"Ocupada: 7"/"Ocupada: 8"/|passos 0 a 26'
  'intervalo|s/Do passo 11 ao 39, cada passo/Do passo 11 ao 26, cada passo/|intervalo'
  'remissao-inexistente|s/o passo 39 o fecha/o passo 49 o fecha/|remissão'
  # seed e conta
  'seed-consumos|s/^  consumos: 14 criados, 0 já existiam$/  consumos: 15 criados, 0 já existiam/|consumos'
  'seed-taxa|s/^  taxa de serviço: 10% \(definida\)$/  taxa de serviço: 12% (definida)/|taxa'
  'hospede-uh-errada|s/"Hóspede Demonstração Sete" \(UH 205\)/"Hóspede Demonstração Sete" (UH 206)/g|UH 206'
  'checkout-nao-dayuse|s/"Hóspede Demonstração Dez" \(UH 105\)/"Hóspede Demonstração Nove" (UH 209)/g|day use'
  'contagem-hospedados|s/que passa a contar 7/que passa a contar 6/|hospedados'
  'saldo-inicial|s/o saldo é R\$ 130,35/o saldo é R$ 130,25/|saldo inicial'
  'taxa-errada|s/R\$ 8,65 e R\$ 3,20/R$ 8,65 e R$ 3,02/|taxa de Frigobar'
  # aprovações
  'aprovador-recepcao|/^## 31\./,/^## 32\./s/Em \*\*Login de quem aprova\*\*, digite `gerente`/Em **Login de quem aprova**, digite `recepcao`/|aprova'
  'item-errado|/^## 34\./,/^## 35\./s/o PIN do Gerente \(item 5 da folha\)/o PIN do Gerente (item 2 da folha)/|item'
  'sem-aprovacao-37|/^## 37\./,/^## 38\./s/Em \*\*Login de quem aprova\*\*, digite `gerente`; em \*\*PIN\*\*, o PIN do Gerente \(item 5 da folha\); em \*\*Motivo\*\*, digite `Reabertura para lançar consumo`\. //|Gerente aprova'
  # No fim
  'nofim-consumos|/^- `consumos: 0 criados, 14 já existiam`: o seed/s/14 já/13 já/|No fim'
  'nofim-status-uh|/^- `status de UH: 2 criados/s/`status de UH: 2 criados, 18 já existiam`/`status de UH: 1 criados, 19 já existiam`/|No fim'
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
assert_eq "0" "$?" "controle: o test_roteiro_m4_ataque.sh passa no roteiro intacto"
[[ -z "$ctl_out" ]] || printf '%s\n' "$ctl_out" | head -20
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
  assert_contains "$out" "rc=1" "mutante $name: o test_roteiro_m4_ataque.sh reprova"
  assert_contains "$(grep -E '^FALHOU' <<<"$out")" "$want" "mutante $name: a falha cita [$want]"
done

finish
