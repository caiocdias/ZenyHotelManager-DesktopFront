#!/usr/bin/env bash
# Testes da documentação: README cobre todo script executável e os códigos do pré-voo;
# docs/pedidos-servidor.md traz a tabela de controle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

readme="$ROOT/README.md"
pedidos="$ROOT/docs/pedidos-servidor.md"

assert_file "$readme" "README.md existe"
assert_file "$pedidos" "docs/pedidos-servidor.md existe"

readme_text="$(cat "$readme" 2>/dev/null)"
while IFS= read -r script; do
  assert_contains "$readme_text" "$script" "README cita $script"
done < <(cd "$ROOT" && find tools -name '*.sh' -type f -perm -u+x | sort)

for code in 0 10 11 12 2 3; do
  assert_contains "$readme_text" "| $code |" "README tem o código de saída $code"
done

assert_contains "$(cat "$pedidos" 2>/dev/null)" \
  "| PF-NN | título | estado | captura | etapa do front | commit de entrega |" \
  "cabeçalho da tabela de pedidos"

finish
