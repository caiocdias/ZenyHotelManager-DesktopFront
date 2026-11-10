#!/usr/bin/env bash
# Asserções mínimas para os testes em bash. Para `source`; não executa nada ao ser carregado.
# Congelado após a Task 1: tasks seguintes só consomem.

ASSERT_FAILS=0
ASSERT_SKIPS=0
ASSERT_OUT=""

_assert_fail() {
  printf 'FALHOU: %s\n' "$1"
  shift
  local line
  for line in "$@"; do printf '  %s\n' "$line"; done
  ASSERT_FAILS=$((ASSERT_FAILS + 1))
}

assert_eq() {
  if [[ "$1" != "$2" ]]; then
    _assert_fail "$3" "esperado: $1" "obtido:   $2"
  fi
}

assert_contains() {
  if [[ "$1" != *"$2"* ]]; then
    _assert_fail "$3" "não contém: $2" "texto: $1"
  fi
}

assert_not_contains() {
  if [[ "$1" == *"$2"* ]]; then
    _assert_fail "$3" "contém: $2" "texto: $1"
  fi
}

# assert_exit EXP_CODE MSG -- CMD...
# Roda CMD num subshell; guarda stdout+stderr em ASSERT_OUT (mostrado só se falhar).
assert_exit() {
  local expected="$1" msg="$2"
  shift 2
  [[ "${1:-}" == "--" ]] && shift
  local code
  ASSERT_OUT="$( ( "$@" ) 2>&1 )"
  code=$?
  if [[ "$code" != "$expected" ]]; then
    _assert_fail "$msg" "código esperado: $expected" "código obtido:   $code" "saída: $ASSERT_OUT"
  fi
}

assert_file() {
  if [[ ! -f "$1" ]]; then
    _assert_fail "$2" "arquivo inexistente: $1"
  fi
}

skip_case() {
  printf 'PULADO: %s\n' "$1"
  ASSERT_SKIPS=$((ASSERT_SKIPS + 1))
}

# Falha vence pulo: 1 se houve falha, senão 77 se houve pulo, senão 0.
finish() {
  if ((ASSERT_FAILS > 0)); then
    exit 1
  elif ((ASSERT_SKIPS > 0)); then
    exit 77
  fi
  exit 0
}
