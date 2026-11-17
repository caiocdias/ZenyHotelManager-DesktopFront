#!/usr/bin/env bash
# Poder do teste do roteiro do M3 (M3 E05, testador de etapa): mutantes do docs/demo/roteiro-m3.md que um defeito
# visível ao usuário produziria, cada um rodado contra o tests/test_roteiro_m3_ataque.sh inteiro (em paralelo). As
# cópias mutadas ficam num diretório temporário; o roteiro nunca é alterado. Cada mutante tem de mudar o arquivo, e
# o teste tem de reprovar citando o que o mutante quebrou.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

rot="$ROOT/docs/demo/roteiro-m3.md"
base_test="$ROOT/tests/test_roteiro_m3_ataque.sh"
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
sed -e "s#^ROOT=.*#ROOT='$ROOT'#" -e 's#^rot="\$ROOT/docs/demo/roteiro-m3.md"#rot="$ROT"#' "$base_test" >"$drv"
grep -q '^rot="\$ROT"$' "$drv" || _assert_fail "o test_roteiro_m3_ataque.sh declara rot=\"\$ROOT/docs/demo/roteiro-m3.md\" (o driver depende disso)"

# NOME|EXPRESSÃO SED|TEXTO QUE A FALHA CITA
mutants=(
  # passos 0 a 18: a lista fechada de diferenças
  'passo9-contador|/^## 9\./,/^## 10\./s/"Ocupada: 7"/"Ocupada: 8"/|passos 0 a 18'
  'gerente-sem-reativacao|s/, a diária negociada, no passo 22, e a reativação, no passo 25, e entra/, a diária negociada, no passo 22, e entra/|passos 0 a 18'
  'seed-sem-linha-m3|/^- As reservas futuras, a cancelada e as pendentes/d|passos 0 a 18'
  'walkin-sem-reserva|s/Esse hóspede do seed é o titular das reservas futuras e pendentes do seed, nenhuma delas de hoje: o walk-in com ele vale do mesmo jeito\./Esse hóspede do seed não tem reserva./|passos 0 a 18'
  'folha-sem-25|s/nos passos 5, 7, 8, 10, 11, 16, 18, 22 e 25,/nos passos 5, 7, 8, 10, 11, 16, 18 e 22,/|folha'
  'pacote-sem-m3|s/`roteiro-m2\.md`, `roteiro-m3\.md` \(este\), `LEIA-ME\.md`/`roteiro-m2.md` (este), `LEIA-ME.md`/|pacote'
  'cabecalho-intervalo|s/^(Ele vai do zero.*)Do passo 11 ao 26, cada passo/\1Do passo 11 ao 18, cada passo/|intervalo'
  # estrutura
  'titulo-24|s/^## 24\. Cancelar com a multa$/## 24. Cancelar com multa/|passos do M3'
  'sem-reprovacao-24|/^## 24\./,/^## 25\./{/^\*\*Reprovação/d}|reprovação'
  # fatos do seed
  'c1-chegada-27|s/^(\| Cancelada \| Standard \| )mais 28 dias/\1mais 27 dias/|19.2'
  'p1-confirmada|s/^\| Pré-reserva \| Standard \|/| Confirmada | Standard |/|19.2'
  'cpf-digito|s/`20260011207`/`20260011208`/|CPF'
  'mascara|s/CPF \*\*\*1207/CPF ***1208/|mascarado'
  'cpf-no-26|/^- \*\*Garantias pendentes:\*\*/s/de "Hóspede Demonstração Doze" \(chegada/de "Hóspede Demonstração Doze" (CPF 20260011207, chegada/|CPF fictício'
  'sem-pre-reserva-do-dia|s/ A linha "Pré-reserva" só aparece numa demo recriada no dia \(o aviso do passo 0\): a pré-reserva do seed vence 48 h depois de criada\.//|pré-reserva'
  'interrompido-d21|/^\*\*Se o seed foi interrompido/s/mais 28 dias/mais 21 dias/|seed interrompido'
  'reativar-periodo|/^## 25\./,/^## 26\./s/em \*\*Até\*\* mais 30 dias/em **Até** mais 29 dias/|25'
  # cotação
  'garantida-uma-linha|s/com duas linhas que começam por "Standard ·"/com uma linha que começa por "Standard ·"/|20.1'
  'sem-garantida-20-1|s/ Uma das linhas Standard é "Standard · GARANTIDA" \(o plano com garantia por cartão que o seed vende só no site, mostrado pelo código\), que traz também o motivo "O plano não é vendido neste canal\.": isso é o esperado, não é reprovação\.//|GARANTIDA'
  'adultos-4|s/o tipo de UH recebe até 3 adultos/o tipo de UH recebe até 4 adultos/|motivo'
  'calendario-29|s/e 30 colunas, da data/e 29 colunas, da data/|30 colunas'
  'data-exemplo|s/no exemplo, 13\/12\/2026/no exemplo, 14\/12\/2026/|exemplo de data'
  # pendências
  'realocacao-titular|s/a coluna "Titular" mostra "—"/a coluna "Titular" mostra "Hóspede Demonstração Doze"/|sem titular'
  'realocacao-periodo|/^- \*\*Realocação necessária:\*\*/s/à data operacional mais 7 dias/à data operacional mais 8 dias/|realocação'
  'depositos-com-linha|s/- \*\*Depósitos em falta:\*\* vazia, com "Nenhum depósito em falta\."/- **Depósitos em falta:** uma reserva com o depósito pendente/|depósitos'
  'garantias-sem-48h|/^- \*\*Garantias pendentes:\*\*/s/48 h depois de criada/2 dias depois de criada/|48 h'
  'noshow-sem-0300|/^- \*\*No-shows previstos:\*\*/s/às 03:00/de madrugada/|03:00'
  'garantia-deposito|s/"Pré-reserva · Cartão"/"Pré-reserva · Depósito"/|garantia pendente'
  'pagina-seguinte|s/^(\*\*19\.2\. Buscar pelo documento\.\*\*)/\1 Se a lista for longa, clique em **Carregar mais**./|página seguinte'
  # aprovações, nomes e rótulos
  'reativar-item-errado|/^## 25\./,/^## 26\./s/o PIN do Gerente \(item 5 da folha\)/o PIN do Gerente (item 2 da folha)/|item'
  'desconto-recepcao|/^## 22\./,/^## 23\./s/Em \*\*Login de quem aprova\*\*, digite `gerente`/Em **Login de quem aprova**, digite `recepcao`/|aprova'
  'desconto-sem-mesmo-pin|s/, o mesmo do passo 16;/;/|mesmo PIN'
  'busca-nome-ambigua|/^## 21\./,/^## 22\./s/digite `Um`/digite `Do`/|busca por nome'
  'estende-outro|/^\*\*23\.2\./s/O hóspede da UH 104/O hóspede da UH 103/|23.2'
  'rotulo-negrito|s/\*\*Salvar alteração\*\*/**Salvar alterações**/g|negrito'
  'rotulo-aspas|s/"Garantia exigida: Cartão"/"Garantia exigida: cartão"/|aspas'
  'origem-telefone|s/"Origem" \("Balcão" marcado\)/"Origem" ("Telefone" marcado)/|origem'
  'motivos-ordem|/^## 24\./,/^## 25\./s/"Decisão do hotel", "Reserva duplicada"/"Reserva duplicada", "Decisão do hotel"/|motivos'
  # No fim
  'nofim-sem-m3|/^- As reservas do M3 não aparecem na saída/d|No fim'
  'nofim-reservas|/^### Rodar o seed/,/^### Esqueci/s/`reservas: 0 criados, 11 já existiam`/`reservas: 0 criados, 14 já existiam`/|reservas'
  'seed-saida-com-futuras|s/^  reservas: 11 criados, 0 já existiam$/  reservas: 11 criados, 0 já existiam\n  reservas futuras: 3 criados, 0 já existiam/|SeedItem'
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
assert_eq "0" "$?" "controle: o test_roteiro_m3_ataque.sh passa no roteiro intacto"
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
  assert_contains "$out" "rc=1" "mutante $name: o test_roteiro_m3_ataque.sh reprova"
  assert_contains "$(grep -E '^FALHOU' <<<"$out")" "$want" "mutante $name: a falha cita [$want]"
done

finish
