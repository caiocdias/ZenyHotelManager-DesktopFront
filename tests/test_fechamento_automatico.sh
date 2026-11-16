#!/usr/bin/env bash
# Aviso do fechamento automático das 03:00 (M0 E03, Task 3): o parágrafo fixado entra igual, como linha inteira,
# no LEIA-ME (seção 3) e nos roteiros do M1 e do M2 (seção 0); o passo 13 do roteiro do M2 trata "(código FNRH)"
# como exceção. Estático: sem pwsh, Docker nem Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/roteiro_checks.sh"

aviso='**A recriação vale só para o dia em que é feita.** O servidor fecha o dia sozinho às 03:00 do dia seguinte: as chegadas confirmadas sem check-in viram "Não compareceu", com multa, as diárias são lançadas e a data operacional passa ao dia seguinte. Recrie a demo (`down -v`, `up -d` e o seed) no mesmo dia do roteiro ou da demo, depois das 03:00, e nunca use no dia seguinte um ambiente recriado na véspera.'
ficha='Os rótulos de motivo da viagem e de meio de transporte saem com o texto do catálogo da FNRH. Só um código fora do catálogo sai como "(código FNRH)": é do servidor, não é reprovação.'
ficha_velha='podem sair como "(código FNRH)"'

# tem_aviso RAIZ ARQUIVO TÍTULO : o parágrafo fixado é linha inteira da seção.
tem_aviso() { rc_section "$1/$2" "$3" | grep -qxF -- "$aviso"; }

docs=(
  "tools/server-env/LEIA-ME.md|## 3. Parar e recriar a demo"
  "docs/demo/roteiro-m1.md|## 0. Antes de começar"
  "docs/demo/roteiro-m2.md|## 0. Antes de começar"
)
for d in "${docs[@]}"; do
  if tem_aviso "$ROOT" "${d%%|*}" "${d#*|}"; then :; else
    _assert_fail "aviso do fechamento automático em ${d%%|*} (${d#*|})"
  fi
done

passo13="$(rc_section "$ROOT/docs/demo/roteiro-m2.md" "## 13. Ficha de registro")"
if grep -qxF -- "$ficha" <<<"$passo13"; then :; else
  _assert_fail "passo 13 do roteiro do M2 traz a frase nova dos rótulos da FNRH"
fi
assert_not_contains "$passo13" "$ficha_velha" "passo 13 do roteiro do M2 sem a frase velha dos rótulos"

# Mutação: sem o parágrafo em um só dos três, a checagem tem de falhar.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for d in "${docs[@]}"; do
  f="${d%%|*}"
  mkdir -p "$tmp/$(dirname "$f")"
  for o in "${docs[@]}"; do
    of="${o%%|*}"
    mkdir -p "$tmp/$(dirname "$of")"
    if [[ "$of" == "$f" ]]; then
      grep -vxF -- "$aviso" "$ROOT/$of" >"$tmp/$of" || true
    else
      cp "$ROOT/$of" "$tmp/$of"
    fi
  done
  achou_todos=1
  for o in "${docs[@]}"; do
    tem_aviso "$tmp" "${o%%|*}" "${o#*|}" || achou_todos=0
  done
  assert_eq "0" "$achou_todos" "mutação: sem o aviso em $f a checagem dos três falha"
done

finish
