#!/usr/bin/env bash
# M4 E05, C-M8 (testador de etapa): poder da trava tests/test_seed_live_optins.sh e uma checagem independente dela.
# (1) A trava do desenvolvedor, rodada numa cópia da árvore mínima (build.gradle.kts e fontes de teste), tem de
#     reprovar cada mutante que um opt-in não invalidante produziria. (2) Uma checagem própria: todo nome ZENY_*LIVE*
#     ou ZENY_SEED_SERVER escrito em código de teste, em qualquer forma de leitura (não só getenv("...")), é
#     inputs.property da tarefa test. Estático; nenhum Gradle; nada do repositório é alterado.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

kts="$ROOT/demo-seed/build.gradle.kts"
trava="$ROOT/tests/test_seed_live_optins.sh"
for f in "$kts" "$trava" "$ROOT/tests/lib/assert.sh"; do
  [[ -f "$f" ]] || {
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    finish
  }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Monta uma árvore mínima em $1; o build.gradle.kts é copiado e as fontes de teste também.
build_tree() {
  local dir="$1"
  mkdir -p "$dir/tests/lib" "$dir/demo-seed/src"
  cp "$ROOT/tests/lib/assert.sh" "$dir/tests/lib/"
  cp "$trava" "$dir/tests/test_seed_live_optins.sh"
  cp "$kts" "$dir/demo-seed/build.gradle.kts"
  cp -r "$ROOT/demo-seed/src/test" "$dir/demo-seed/src/test"
}

run_trava() {
  (cd "$1" && bash tests/test_seed_live_optins.sh 2>&1)
}

# Base: a cópia intacta passa (senão os mutantes provam nada).
base="$tmp/base"
build_tree "$base"
out="$(run_trava "$base")"
code=$?
assert_eq "0" "$code" "a cópia intacta da árvore passa na trava do desenvolvedor"
[[ "$code" == 0 ]] || printf '%s\n' "$out"

# mutante NOME | comando em bash que muda a árvore em $d | trecho que a falha tem de citar
mutate_kts() { # $1 dir, $2 expressão sed sobre o build.gradle.kts
  sed -E -i "$2" "$1/demo-seed/build.gradle.kts"
}
mut_fora() { # tira a propriedade da tarefa test e a põe noutra tarefa
  mutate_kts "$d" '/environmentVariable\("ZENY_SEED_LIVE"\)/d'
  printf '\ntasks.named("jar") { inputs.property("x", providers.environmentVariable("ZENY_SEED_LIVE").orElse("")) }\n' \
    >>"$d/demo-seed/build.gradle.kts"
}
mut_novo() {
  mkdir -p "$d/demo-seed/src/test/kotlin"
  printf 'package x\nval a = System.getenv("ZENY_SEED_LIVE_NOVO")\n' >"$d/demo-seed/src/test/kotlin/Novo.kt"
}
mutants=(
  'sem-propriedade-live|mutate_kts "$d" "/environmentVariable\\(\"ZENY_SEED_LIVE\"\\)/d"|ZENY_SEED_LIVE'
  'sem-propriedade-server|mutate_kts "$d" "/environmentVariable\\(\"ZENY_SEED_SERVER\"\\)/d"|ZENY_SEED_SERVER'
  'sem-propriedade-reservations|mutate_kts "$d" "/environmentVariable\\(\"ZENY_SEED_LIVE_RESERVATIONS\"\\)/d"|ZENY_SEED_LIVE_RESERVATIONS'
  'sem-orelse|mutate_kts "$d" "/ZENY_SEED_SERVER/s/\\.orElse\\(\"\"\\)//"|.orElse'
  'comentada|mutate_kts "$d" "/ZENY_SEED_LIVE_AVAILABILITY/s#^( *)#\\1// #"|ZENY_SEED_LIVE_AVAILABILITY'
  'nome-trocado|mutate_kts "$d" "/ZENY_SEED_SERVER/s/ZENY_SEED_SERVER/ZENY_SEED_SERVR/"|ZENY_SEED_SERVER'
  'fora-da-tarefa-test|mut_fora|ZENY_SEED_LIVE'
  'credencial-vira-entrada|mutate_kts "$d" "/environmentVariable\\(\"ZENY_SEED_SERVER\"\\)/a\\    inputs.property(\"pw\", providers.environmentVariable(\"ZENY_SEED_ADMIN_PASSWORD\").orElse(\"\"))"|senha ou PIN'
  'pin-vira-entrada|mutate_kts "$d" "/environmentVariable\\(\"ZENY_SEED_SERVER\"\\)/a\\    inputs.property(\"pin\", providers.environmentVariable(\"ZENY_SEED_MANAGER_PIN\").orElse(\"\"))"|senha ou PIN'
  'novo-optin-nos-testes|mut_novo|ZENY_SEED_LIVE_NOVO'
)
for spec in "${mutants[@]}"; do
  IFS='|' read -r name cmd cite <<<"$spec"
  d="$tmp/m-$name"
  build_tree "$d"
  before="$(cksum "$d/demo-seed/build.gradle.kts" | cut -d' ' -f1)$(find "$d/demo-seed/src" -type f | wc -l)"
  eval "$cmd"
  after="$(cksum "$d/demo-seed/build.gradle.kts" | cut -d' ' -f1)$(find "$d/demo-seed/src" -type f | wc -l)"
  assert_eq "1" "$([[ "$before" != "$after" ]] && echo 1 || echo 0)" "mutante $name muda a árvore"
  out="$(run_trava "$d")"
  code=$?
  assert_eq "1" "$code" "mutante $name: a trava reprova (código 1)"
  assert_contains "$out" "$cite" "mutante $name: a falha cita $cite"
done

# (2) Checagem independente: qualquer menção a um nome de opt-in nas fontes de teste (não comentário) é
# inputs.property da tarefa test, inclusive formas que o getenv("...") literal da trava não enxerga.
optin_names() { # $1 diretório raiz da árvore
  find "$1/demo-seed/src/test" -name '*.kt' -type f -print0 |
    xargs -0 grep -hvE '^[[:space:]]*(//|/?\*)' |
    grep -oE 'ZENY_SEED_[A-Z_]*(LIVE|SERVER)[A-Z_]*' | sort -u
}
declared_names() { # $1 diretório raiz da árvore
  awk '/^tasks\.test[[:space:]]*\{/ {dentro=1} dentro {print} dentro && /^\}/ {exit}' "$1/demo-seed/build.gradle.kts" |
    sed -E 's@^[[:space:]]*//.*$@@' |
    grep -E 'inputs\.property\(' | grep -oE 'environmentVariable\("ZENY_[A-Z_]+"\)' | sed -E 's/.*\("//; s/"\)$//' | sort -u
}
missing_names() { # $1 diretório raiz da árvore
  comm -23 <(optin_names "$1") <(declared_names "$1")
}

assert_eq "" "$(missing_names "$ROOT")" "todo nome de opt-in citado nos testes do demo-seed é inputs.property da tarefa test"
assert_contains "$(optin_names "$ROOT")" "ZENY_SEED_LIVE" "a busca própria enxerga ao menos o ZENY_SEED_LIVE (o padrão não envelheceu)"

# A forma que a trava do desenvolvedor não vê: leitura sem getenv("...") literal. Ela passa na trava dele e reprova aqui.
d="$tmp/forma-oculta"
build_tree "$d"
mkdir -p "$d/demo-seed/src/test/kotlin"
printf 'package x\nval a = System.getenv()["ZENY_SEED_LIVE_OCULTO"]\n' >"$d/demo-seed/src/test/kotlin/Oculto.kt"
out="$(run_trava "$d")"
assert_eq "0" "$?" "(documenta o limite da trava do desenvolvedor) getenv()[\"...\"] passa nela"
assert_eq "ZENY_SEED_LIVE_OCULTO" "$(missing_names "$d")" "a checagem própria reprova o opt-in lido por getenv()[\"...\"]"

finish
