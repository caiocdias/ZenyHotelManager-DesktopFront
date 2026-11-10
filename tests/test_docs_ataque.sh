#!/usr/bin/env bash
# Ataque à documentação (Task 8): o que o README afirma bate com o que os scripts fazem?
# Só leitura. Não executa containers.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

readme="$ROOT/README.md"
pedidos="$ROOT/docs/pedidos-servidor.md"
readme_text="$(cat "$readme")"
drift="$ROOT/tools/server-drift.sh"
libsh="$ROOT/tools/server-env/lib.sh"
common="$ROOT/tools/lib/common.sh"

# --- 1. opções citadas na tabela de comandos existem no script correspondente ---
n_rows=0
while IFS= read -r row; do
  [[ "$row" == '| '*' |' ]] || continue
  script="$(grep -oE 'tools/[A-Za-z0-9_./-]+\.sh' <<<"$row" | head -1)"
  [[ -n "$script" ]] || continue
  n_rows=$((n_rows + 1))
  if [[ ! -f "$ROOT/$script" ]]; then
    _assert_fail "script citado existe: $script"
    continue
  fi
  cmd="$(grep -oE '`tools/[^`]+`' <<<"$row" | tail -1)"
  for opt in $(grep -oE -- '(^|[ [|`])--?[A-Za-z][A-Za-z-]*' <<<"$cmd" | tr -d ' [|`' | sort -u); do
    if ! grep -qE -- "(^|[^A-Za-z-])${opt}([^A-Za-z-]|$)" "$ROOT/$script"; then
      _assert_fail "opção $opt do README existe em $script"
    fi
  done
done <<<"$readme_text"
assert_eq "7" "$n_rows" "a tabela de comandos tem 7 linhas com script em tools/"

# --- 2. variáveis de ambiente: README <-> scripts ---
documented="$(sed -n '/^## Variáveis de ambiente/,/^## Segredos/p' "$readme" | grep -oE '^\| `[A-Z_]+`' | grep -oE '[A-Z_]+')"
for v in $documented; do
  grep -rqE "\b$v\b" "$ROOT/tools" --include='*.sh' ||
    _assert_fail "variável $v do README é lida por algum script"
done
# toda variável de operador lida (${VAR:-...}) em tools/*.sh está no README
for v in $(grep -rhoE '\$\{(ZENY_[A-Z_]+|OASDIFF_IMAGE):-' "$ROOT/tools" --include='*.sh' | grep -oE '[A-Z_]{5,}' | sort -u); do
  assert_contains "$documented" "$v" "variável $v lida pelos scripts está documentada no README"
done
# variável citada em qualquer ponto do README existe nos scripts
for v in $(grep -oE '\b(ZENY_[A-Z_]+|OASDIFF_IMAGE)\b' "$readme" | sort -u); do
  grep -rqE "\b$v\b" "$ROOT/tools" --include='*.sh' || _assert_fail "variável $v citada no README existe nos scripts"
done

# padrões documentados
assert_contains "$(grep -n 'ZENY_UP_TIMEOUT:-600' "$ROOT/tools/server-env/up.sh")" "600" "padrão 600 do ZENY_UP_TIMEOUT confere com up.sh"
prefix="$(grep -oE 'tufin/oasdiff:v[0-9.]+@sha256:[0-9a-f]{8}' <<<"$readme_text" | head -1)"
assert_contains "$(grep -E '^OASDIFF_IMAGE=' "$drift")" "$prefix" "imagem do oasdiff do README é a do script ($prefix)"
assert_contains "$(cat "$drift")" "--network none" "server-drift.sh realmente usa --network none"
assert_contains "$(grep -n 'target_ref:-main' "$drift")" "main" "alvo padrão do drift é main"
assert_contains "$(grep -n ':-8088' "$ROOT/tools/server-env/up.sh")" "8088" "porta padrão 8088 confere com up.sh"

# --- 3. códigos de saída: par código -> significado ---
header="$(sed -n '/^# Códigos de saída:/,/^# Variáveis:/p' "$drift")"
check_pair() { # codigo trecho-no-README trecho-no-cabecalho
  local row hdr
  row="$(grep -E "^\| $1 \|" <<<"$readme_text" | head -1)"
  hdr="$(grep -E "^#   $1 " <<<"$header")"
  if [[ -z "$row" ]]; then _assert_fail "README tem a linha do código $1"; return; fi
  if [[ -z "$hdr" ]]; then _assert_fail "cabeçalho do script tem a linha do código $1"; return; fi
  assert_contains "$row" "$2" "README: código $1 significa '$2'"
  assert_contains "$hdr" "$3" "script: código $1 significa '$3'"
}
check_pair 0 "igual" "igual"
check_pair 10 "contrato idêntico" "contrato idêntico"
check_pair 11 "aditivo" "aditivo"
check_pair 12 "breaking" "breaking"
check_pair 2 "erro de uso" "erro de uso"
check_pair 3 "pin inconsistente" "pin inconsistente"
readme_codes="$(sed -n '/^### Códigos de saída/,/^A opção vence/p' "$readme" | grep -oE '^\| [0-9]+ \|' | grep -oE '[0-9]+' | sort -n | tr '\n' ' ')"
script_codes="$(grep -oE '^#   [0-9]+ ' <<<"$header" | grep -oE '[0-9]+' | sort -n | tr '\n' ' ')"
assert_eq "$script_codes" "$readme_codes" "conjunto de códigos do README == do cabeçalho do script"
# o corpo do script realmente emite 10, 11 e 12 (fora dos comentários)
body="$(grep -vE '^\s*#' "$drift")"
for c in 10 11 12; do
  assert_contains "$body" "$c" "o corpo do script usa o código $c"
done
# execução real e barata
assert_exit 2 "repositório do servidor inexistente sai com 2" -- env ZENY_SERVER_REPO=/nonexistent-zeny ZENY_DRIFT_BASE=x "$drift"
assert_exit 2 "opção desconhecida sai com 2" -- "$drift" --nada
assert_exit 2 "--target vazio é erro de uso (2)" -- "$drift" --target ""
assert_contains "$readme_text" "variável vazia conta como ausente" "README documenta variável vazia"

# --- 4. caminhos relativos citados existem ---
check_links() { # arquivo dir-base
  local file="$1" base="$2" p
  while IFS= read -r p; do
    p="${p%%#*}"
    [[ -n "$p" && "$p" != http* ]] || continue
    [[ -e "$base/$p" ]] || _assert_fail "link de $(basename "$file") resolve: $p"
  done < <(grep -oE '\]\([^)]+\)' "$file" | sed -E 's/^\]\(//; s/\)$//')
}
check_links "$readme" "$ROOT"
check_links "$pedidos" "$ROOT/docs"
while IFS= read -r p; do
  case "$p" in
    .server/* | build/* | *'<'* | *'*'*) continue ;;
  esac
  [[ -e "$ROOT/$p" ]] || _assert_fail "caminho citado no README existe: $p"
done < <(grep -oE '`(api|docs|tools|tests)/[A-Za-z0-9_./-]+`' "$readme" | tr -d '`' | sort -u)
assert_eq "1" "$(find "$ROOT"/docs/adr -name '0005-*.md' | wc -l | tr -d ' ')" "ADR 0005 existe (README diz que trata dos pedidos)"
assert_contains "$(grep -E '^## 8\.' "$ROOT/docs/superpowers/specs/2026-11-10-front-desktop-design.md")" "servidor" "§8 do design trata do servidor"

# --- 5. segredos ---
pin_commit="$(grep -E '^commit=' "$ROOT/api/server-pin.properties" | cut -d= -f2)"
assert_contains "$readme_text" "$pin_commit" "SHA citado no README é o do pin atual"
stripped="${readme_text//$pin_commit/}"
long="$(grep -oE '[A-Za-z0-9+/_=-]{32,}' <<<"$stripped" | grep -v '/' | grep -E '[0-9]' | grep -E '[A-Za-z]')"
assert_eq "" "$long" "README não tem sequência opaca longa (chave, hash ou senha)"
assert_not_contains "$readme_text" "PRIVATE KEY" "README sem chave privada"
assert_not_contains "$readme_text" "AKIA" "README sem chave AWS"
for k in $(grep -hoE '^ZENY_[A-Z_]*(KEY|KEYS|PASSWORD|SECRET)[A-Z_]*' "$ROOT/tools/server-env/.env.example" | sort -u); do
  if grep -qE "\b$k=." "$readme" "$pedidos"; then _assert_fail "README ou pedidos atribuem valor a $k"; fi
done
assert_eq "" "$(git -C "$ROOT" ls-files | grep -E '\.env$')" "nenhum .env versionado"

# --- 6. nomes de estado de máquina batem com lib.sh ---
assert_contains "$(cat "$libsh")" "readonly ZENY_FRONT_PROJECT=zeny-front" "projeto compose é zeny-front"
assert_contains "$readme_text" 'projeto compose `zeny-front`' "README cita o projeto zeny-front"
assert_contains "$(grep -n 'zeny-front.env' "$libsh")" ".server/zeny-front.env" "lib.sh: .env real em .server/zeny-front.env"
assert_contains "$readme_text" ".server/zeny-front.env" "README cita .server/zeny-front.env"
assert_contains "$(grep -n 'server_tree_dir()' -A5 "$libsh")" '/.server/%s' "lib.sh: árvore em .server/<commit>"
assert_contains "$readme_text" ".server/<commit>/" "README cita .server/<commit>/"
assert_contains "$(grep -n 'override_file()' -A2 "$libsh")" ".override.yaml" "lib.sh: override .override.yaml"
assert_contains "$readme_text" ".server/<commit>.override.yaml" "README cita o override"
assert_contains "$(grep -n 'pkg=' "$ROOT/tools/server-env/package.sh")" "build/demo-package" "package.sh: build/demo-package"
assert_contains "$readme_text" "build/demo-package/" "README cita build/demo-package/"
assert_contains "$(grep -n 'out=' "$ROOT/tools/server-env/save-images.sh")" "build/demo-package/images/zeny-server-images.tar" "save-images: padrão do tar"
assert_contains "$readme_text" "build/demo-package/images/zeny-server-images.tar" "README: padrão do tar"
assert_contains "$(grep -n 'short_commit()' -A2 "$common")" '${1:0:12}' "commit curto = 12 caracteres"
assert_contains "$readme_text" "12 primeiros caracteres" "README: commit curto de 12"
assert_contains "$(grep -n 'zeny-hotel-manager:' "$ROOT/tools/server-env/save-images.sh")" 'zeny-hotel-manager:$tag' "script usa zeny-hotel-manager:<tag>"
assert_contains "$readme_text" "zeny-hotel-manager:<commit curto>" "README: nome da imagem"
assert_contains "$(cat "$ROOT/.gitignore")" ".server/" "raiz ignora .server/"
assert_contains "$(cat "$ROOT/.gitignore")" "build/" "raiz ignora build/"
assert_contains "$(grep -n 'ensure_state_dir' "$ROOT/tools/server-env/up.sh")" ".server" "up.sh autoignora .server"
assert_contains "$(grep -n 'ensure_state_dir' "$ROOT/tools/server-env/package.sh")" "build" "package.sh autoignora build"
# o README lista os subcomandos git que srv_git de fato permite? (nunca pode listar um que não permite)
allowed="$(grep -E '^\s+rev-parse \|' "$common")"
for sub in rev-parse show archive; do assert_contains "$allowed" "$sub" "srv_git permite $sub"; done
for bad in checkout fetch pull push worktree; do
  assert_not_contains "$allowed" "$bad" "srv_git não permite $bad"
done
for sub in cat-file diff; do
  assert_contains "$readme_text" "\`$sub\`" "README lista o subcomando $sub, que srv_git permite"
done

# --- 7. pedidos-servidor.md ---
pt="$(cat "$pedidos")"
adr5="$(cat "$ROOT"/docs/adr/0005-*.md)"
for s in aberto aceito entregue recusado; do
  assert_contains "$pt" "\`$s\`" "pedidos: estado $s"
  assert_contains "$adr5" "$s" "ADR 0005 cita o estado $s"
done
assert_contains "$pt" "ZenyHotelManager - pedido do front PF-NN <título>" "pedidos: nome da captura"
assert_contains "$pt" "atende PF-NN" "pedidos: resposta atende PF-NN"
assert_contains "$pt" "17/11" "pedidos: regra de 17/11"
assert_contains "$pt" "PF-NN entregue e sincronizado pelo M0" "pedidos: desbloqueio"
assert_eq "1" "$(grep -cE '^\| PF-NN \|' "$pedidos")" "pedidos: uma só tabela de controle"
assert_contains "$(grep -A1 -E '^\| PF-NN \|' "$pedidos")" "|---|---|---|---|---|---|" "pedidos: separador com 6 colunas"
assert_eq "" "$(grep -E '^\| PF-[0-9]+' "$pedidos")" "pedidos: tabela de controle começa vazia"

finish
