#!/usr/bin/env bash
# Roda bash -n (e shellcheck, se existir) em tools/**/*.sh e tests/**/*.sh e depois os testes.
# Uso: bash tests/run.sh [ARQUIVO...]   (padrão: todos os tests/test_*.sh)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

fails=0
skipped_files=0
files=0

mapfile -t scripts < <(find tools tests -name '*.sh' -type f 2>/dev/null | sort)
for s in "${scripts[@]}"; do
  if ! bash -n "$s"; then
    echo "FAIL (bash -n): $s"
    fails=$((fails + 1))
  fi
done
if command -v shellcheck >/dev/null 2>&1 && ((${#scripts[@]} > 0)); then
  if ! shellcheck -x "${scripts[@]}"; then
    echo "FAIL (shellcheck)"
    fails=$((fails + 1))
  fi
fi

if (($# > 0)); then
  tests=("$@")
else
  mapfile -t tests < <(find tests -maxdepth 1 -name 'test_*.sh' -type f | sort)
fi

for t in "${tests[@]}"; do
  files=$((files + 1))
  out="$(bash "$t" 2>&1)"
  code=$?
  case "$code" in
    0) echo "PASS $t" ;;
    77)
      echo "SKIP $t"
      skipped_files=$((skipped_files + 1))
      grep '^PULADO:' <<<"$out"
      ;;
    *)
      echo "FAIL $t (código $code)"
      printf '%s\n' "$out"
      fails=$((fails + 1))
      ;;
  esac
done

echo "Resumo: $files arquivos, $fails falhas, $skipped_files com casos pulados"
((fails == 0)) || exit 1
