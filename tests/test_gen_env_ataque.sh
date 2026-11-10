#!/usr/bin/env bash
# Testes adversariais de tools/server-env/gen-env.sh: segredos fora de argv, temporários, modo 600,
# recusa sem --force, symlinks, opções com caracteres especiais, formatos, falha do openssl,
# sinais, corrida e portabilidade para o busybox (Alpine). Nenhum valor de segredo é impresso.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwx "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

GEN="$ROOT/tools/server-env/gen-env.sh"
EX="$ROOT/tools/server-env/.env.example"
SECRETS=(ZENY_ENCRYPTION_KEYS ZENY_JWT_KEYS ZENY_ADMIN_PASSWORD ZENY_STORAGE_ROOT_PASSWORD ZENY_STORAGE_SECRET_KEY)

val() { awk -v k="$2" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { print v }' "$1"; }
others() {
  local re
  re="$(IFS='|'; echo "${SECRETS[*]}")"
  grep -vE "^($re)=" "$1"
}
# contains_any FILE_OR_TEXT_FILE ENVFILE : 0 se algum valor de segredo do ENVFILE aparece no arquivo.
leaks() {
  local f="$1" env="$2" k v
  for k in "${SECRETS[@]}"; do
    v="$(val "$env" "$k")"
    [[ -n "$v" ]] && grep -qF -- "$v" "$f" && { echo "$k"; return 0; }
  done
  return 1
}

REAL_OPENSSL="$(command -v openssl)"
declare -A REAL
for c in openssl base64 tr awk mktemp chmod mv rm dirname cat; do REAL[$c]="$(command -v "$c")"; done

# mkspy DIR : wrappers que registram o argv de cada subprocesso em DIR/log e deixam openssl/chmod
# falharem sob demanda (FAKE_FAIL=rand:K | genpkey | pkcs8 | chmod).
mkspy() {
  local d="$1" c
  mkdir -p "$d/bin"
  : >"$d/log"
  for c in openssl base64 tr awk mktemp chmod mv rm dirname cat; do
    {
      echo "#!$BASH"
      echo "SPY='$d'"
      echo "{ printf '%s' '$c'; printf ' [%s]' \"\$@\"; echo; } >>\"\$SPY/log\""
      case "$c" in
        awk) echo "echo \"awkout-mode \$(stat -L -c %a /proc/\$\$/fd/1)\" >>\"\$SPY/log\"" ;;
        mv) echo "for a in \"\$@\"; do [[ \"\$a\" != -* && -f \"\$a\" ]] && echo \"mv-src-mode \$(stat -c %a \"\$a\")\" >>\"\$SPY/log\"; done" ;;
        chmod) echo "[[ \"\${FAKE_FAIL:-}\" == chmod ]] && exit 1" ;;
        openssl)
          cat <<'EOS'
case "${FAKE_FAIL:-}" in
  genpkey) [[ "$1" == genpkey ]] && exit 1 ;;
  pkcs8) [[ "$1" == pkcs8 ]] && exit 1 ;;
  rand:*)
    if [[ "$1" == rand ]]; then
      n=$(($(cat "$SPY/randcount" 2>/dev/null || echo 0) + 1))
      echo "$n" >"$SPY/randcount"
      [[ "$n" == "${FAKE_FAIL#rand:}" ]] && exit 1
    fi ;;
  sleep-genpkey)
    if [[ "$1" == genpkey ]]; then echo $$ >"$SPY/sleeper"; exec sleep 30; fi ;;
esac
EOS
          ;;
      esac
      echo "exec '${REAL[$c]}' \"\$@\""
    } >"$d/bin/$c"
    chmod +x "$d/bin/$c"
  done
}

spy="$tmp/spy"
mkspy "$spy"
gen() { PATH="$spy/bin:$PATH" bash "$GEN" "$@"; }

# check_env FILE LABEL : formatos dos cinco segredos.
check_env() {
  local f="$1" l="$2" k v jwt enc adm
  for k in "${SECRETS[@]}"; do
    v="$(val "$f" "$k")"
    [[ -n "$v" ]] || { _assert_fail "$l: $k preenchido"; continue; }
    [[ "$v" =~ ^(dev:)?[A-Za-z0-9+/=]+$ ]] || _assert_fail "$l: $k só com caracteres base64 (sem espaço/aspas)"
  done
  jwt="$(val "$f" ZENY_JWT_KEYS)"
  printf '%s' "${jwt#dev:}" | base64 -d >"$tmp/jwt.der" 2>/dev/null
  openssl pkcs8 -inform DER -nocrypt -in "$tmp/jwt.der" -out /dev/null 2>/dev/null || _assert_fail "$l: JWT é PKCS#8 DER válido"
  openssl pkey -inform DER -in "$tmp/jwt.der" -noout -check >/dev/null 2>&1 || _assert_fail "$l: chave JWT consistente"
  assert_contains "$(openssl pkey -inform DER -in "$tmp/jwt.der" -noout -text 2>/dev/null | head -1)" "3072 bit" "$l: JWT 3072 bits"
  enc="$(val "$f" ZENY_ENCRYPTION_KEYS)"
  assert_eq "32" "$(printf '%s' "${enc#dev:}" | base64 -d 2>/dev/null | wc -c)" "$l: cifragem com 32 bytes"
  adm="$(val "$f" ZENY_ADMIN_PASSWORD)"
  ((${#adm} >= 12 && ${#adm} <= 128)) || _assert_fail "$l: senha do admin entre 12 e 128 caracteres (tem ${#adm})"
  ((${#adm} >= 12)) || true
  for k in ZENY_STORAGE_ROOT_PASSWORD ZENY_STORAGE_SECRET_KEY; do
    v="$(val "$f" "$k")"
    ((${#v} >= 8)) || _assert_fail "$l: $k com 8 ou mais caracteres (tem ${#v})"
  done
}

# ---------- 1. segredos fora de argv, modo na criação, temporários ----------
d="$tmp/a"; mkdir -p "$d"
: >"$spy/log"
assert_exit 0 "geração com espiões" -- gen "$d/.env"
out="$ASSERT_OUT"
if leaks "$spy/log" "$d/.env" >"$tmp/leakkey"; then
  _assert_fail "nenhum segredo aparece no argv de subprocessos (visível no ps)" "chave vazada no argv: $(cat "$tmp/leakkey")"
fi
printf '%s' "$out" >"$tmp/out.txt"
leaks "$tmp/out.txt" "$d/.env" >/dev/null && _assert_fail "stdout+stderr sem segredos"
bad="$(grep -E '^(awkout|mv-src)-mode|^mv-src-mode' "$spy/log" | grep -vE ' 600$' || true)"
[[ -z "$bad" ]] || _assert_fail "arquivo temporário com modo 600 desde a criação (awk e mv)" "$bad"
grep -q '^awkout-mode' "$spy/log" || _assert_fail "espião do awk registrou o modo (sanidade do teste)"
assert_eq ".env" "$(ls -A "$d")" "nenhum temporário sobra após sucesso"
check_env "$d/.env" "formato"

# umask frouxo do chamador e DEST preexistente com modo aberto
d="$tmp/um"; mkdir -p "$d"
: >"$d/.env"; chmod 666 "$d/.env"
assert_exit 0 "--force com umask 000 e DEST 666" -- bash -c 'umask 000; exec "$@"' _ bash "$GEN" --force "$d/.env"
assert_eq "600" "$(stat -c %a "$d/.env")" "modo 600 apesar de umask 000 e DEST antigo 666"
assert_eq ".env" "$(ls -A "$d")" "sem temporários com umask 000"

# chmod falho vira aviso, não erro, e não vaza segredo
d="$tmp/chm"; mkdir -p "$d"
assert_exit 0 "chmod falho não é erro" -- env FAKE_FAIL=chmod PATH="$spy/bin:$PATH" bash "$GEN" "$d/.env"
assert_contains "$ASSERT_OUT" "aviso" "chmod falho avisa no stderr"
printf '%s' "$ASSERT_OUT" >"$tmp/out.txt"
leaks "$tmp/out.txt" "$d/.env" >/dev/null && _assert_fail "aviso do chmod sem segredos"
check_env "$d/.env" "chmod falho"

# ---------- 2. falha do openssl: sem temporários, DEST intacto ----------
for mode in rand:1 rand:2 rand:3 rand:4 genpkey pkcs8; do
  d="$tmp/f_${mode//:/_}"; mkdir -p "$d"
  printf 'ANTIGO=1\n' >"$d/.env"
  cp "$d/.env" "$tmp/antigo.env"
  rm -f "$spy/randcount"
  assert_exit 3 "openssl falha ($mode) -> exit 3" -- env FAKE_FAIL="$mode" PATH="$spy/bin:$PATH" bash "$GEN" --force "$d/.env"
  [[ "$ASSERT_OUT" != *Gerado* ]] || _assert_fail "openssl falha ($mode): não anuncia Gerado"
  assert_eq ".env" "$(ls -A "$d")" "openssl falha ($mode): sem temporários"
  cmp -s "$d/.env" "$tmp/antigo.env" || _assert_fail "openssl falha ($mode): DEST antigo intacto"
done
d="$tmp/f_new"; mkdir -p "$d"
rm -f "$spy/randcount"
assert_exit 3 "openssl falha sem DEST prévio" -- env FAKE_FAIL=rand:1 PATH="$spy/bin:$PATH" bash "$GEN" "$d/.env"
assert_eq "" "$(ls -A "$d")" "openssl falha sem DEST: nada criado"

# ---------- 3. recusa sem --force, symlinks, diretórios ----------
d="$tmp/r"; mkdir -p "$d"
printf 'conteudo\000binario\n' >"$d/.env"; chmod 640 "$d/.env"
cp "$d/.env" "$tmp/r.orig"
assert_exit 2 "recusa DEST existente" -- gen "$d/.env"
cmp -s "$d/.env" "$tmp/r.orig" || _assert_fail "DEST intacto byte a byte"
assert_eq "640" "$(stat -c %a "$d/.env")" "modo do DEST intacto"
assert_eq ".env" "$(ls -A "$d")" "recusa não deixa temporário"

# symlink para arquivo existente, sem e com --force
d="$tmp/s"; mkdir -p "$d"
printf 'alvo-original\n' >"$d/alvo"
ln -s alvo "$d/.env"
assert_exit 2 "symlink existente sem --force" -- gen "$d/.env"
assert_eq "alvo-original" "$(cat "$d/alvo")" "alvo do symlink intacto (sem --force)"
assert_exit 0 "symlink existente com --force" -- gen --force "$d/.env"
assert_eq "alvo-original" "$(cat "$d/alvo")" "alvo do symlink intacto (com --force): symlink não é seguido"
[[ -f "$d/.env" && ! -L "$d/.env" ]] || _assert_fail "com --force o symlink é substituído por arquivo regular"
assert_eq ".env alvo" "$(ls -A "$d" | tr '\n' ' ' | sed 's/ $//')" "symlink: sem temporários"

# symlink pendente (alvo inexistente) nunca cria o alvo
d="$tmp/sp"; mkdir -p "$d"
ln -s "$tmp/sp/criado-pelo-symlink" "$d/.env"
gen "$d/.env" >/dev/null 2>&1
[[ ! -e "$d/criado-pelo-symlink" ]] || _assert_fail "symlink pendente: o alvo não é criado"

# DEST é diretório
d="$tmp/dir"; mkdir -p "$d/.env"
assert_exit 2 "DEST diretório sem --force" -- gen "$d/.env"
code=0
gen --force "$d/.env" >"$tmp/o.txt" 2>&1 || code=$?
((code != 0)) || _assert_fail "DEST diretório com --force deve falhar" "saiu 0: $(cat "$tmp/o.txt")"
assert_eq "" "$(ls -A "$d/.env")" "DEST diretório com --force: nenhum arquivo largado dentro dele"

# diretório inexistente e sem permissão
assert_exit 2 "diretório inexistente" -- gen "$tmp/nope/.env"
[[ ! -e "$tmp/nope" ]] || _assert_fail "diretório inexistente não é criado"
if [[ "$(id -u)" != 0 ]]; then
  d="$tmp/ro"; mkdir -p "$d"; chmod 555 "$d"
  code=0
  gen "$d/.env" >/dev/null 2>&1 || code=$?
  ((code != 0)) || _assert_fail "diretório sem permissão de escrita falha"
  [[ ! -e "$d/.env" ]] || _assert_fail "diretório sem escrita: DEST não criado"
  chmod 755 "$d"
else
  skip_case "rodando como root: diretório sem permissão de escrita"
fi

# modelo ausente: script sozinho
d="$tmp/nomodel"; mkdir -p "$d/pkg" "$d/out"
cp "$GEN" "$d/pkg/"
assert_exit 3 "modelo ausente" -- bash "$d/pkg/gen-env.sh" "$d/out/.env"
assert_eq "" "$(ls -A "$d/out")" "modelo ausente: nada criado"

# ---------- 4. opções ----------
d="$tmp/o"; mkdir -p "$d"
assert_exit 2 "--address sem valor" -- gen "$d/.env" --address
assert_exit 2 "--public-endpoint sem valor" -- gen "$d/.env" --public-endpoint
assert_exit 2 "--address consome o DEST (sem DEST)" -- gen --address "$d/.env"
assert_exit 2 "dois DEST" -- gen "$d/.env" "$d/outro.env"
assert_exit 2 "opção desconhecida" -- gen --nada "$d/.env"
assert_eq "" "$(ls -A "$d")" "uso incorreto não cria arquivos"

# valores especiais, gravados literalmente
i=0
for v in 'a&b' 'http://h/p?x=1&y=2' 'a|b' 'a\b' 'a\\b' 'a\nb' '\1' '&' '/' 'a b' 'x=y' '$HOME' '`id`' "it's" '"q"' '-n' '--force' '#c' ' lead' 'trail '; do
  i=$((i + 1))
  d="$tmp/sp$i"; mkdir -p "$d"
  if ! assert_out="$(gen --address "$v" --public-endpoint "$v" "$d/.env" 2>&1)"; then
    _assert_fail "valor especial '$v' aceito" "$assert_out"
    continue
  fi
  assert_eq "$v" "$(val "$d/.env" ZENY_HTTP_ADDRESS)" "--address literal: '$v'"
  assert_eq "$v" "$(val "$d/.env" ZENY_STORAGE_PUBLIC_ENDPOINT)" "--public-endpoint literal: '$v'"
  assert_eq "$(wc -l <"$EX")" "$(wc -l <"$d/.env")" "mesmo número de linhas com '$v'"
  assert_eq "$(others "$EX" | grep -vE '^ZENY_(HTTP_ADDRESS|STORAGE_PUBLIC_ENDPOINT)=')" "$(others "$d/.env" | grep -vE '^ZENY_(HTTP_ADDRESS|STORAGE_PUBLIC_ENDPOINT)=')" "demais linhas intactas com '$v'"
done

# quebra de linha: recusado ou gravado sem criar linhas novas (nunca injeta variáveis)
nl=$'0.0.0.0\nZENY_ADMIN_PASSWORD=injetada'
for opt in --address --public-endpoint; do
  d="$tmp/nl$opt"; mkdir -p "$d"
  code=0
  gen "$opt" "$nl" "$d/.env" >"$tmp/o.txt" 2>&1 || code=$?
  if ((code == 0)); then
    assert_eq "$(wc -l <"$EX")" "$(wc -l <"$d/.env")" "$opt com quebra de linha: não cria linhas novas"
    [[ "$(val "$d/.env" ZENY_ADMIN_PASSWORD)" != injetada ]] || _assert_fail "$opt com quebra de linha: injeta ZENY_ADMIN_PASSWORD"
  else
    assert_eq "2" "$code" "$opt com quebra de linha recusado com exit 2"
    [[ ! -e "$d/.env" ]] || _assert_fail "$opt recusado não deixa DEST"
  fi
done

# caminhos com espaço, relativo e começando por ponto
d="$tmp/com espaço"; mkdir -p "$d"
(cd "$d" && assert_exit 0 "DEST relativo em diretório com espaço" -- gen "meu .env")
assert_eq "meu .env" "$(ls -A "$d")" "DEST relativo com espaço, sem temporários"

# ---------- 5. unicidade e formatos ----------
declare -a runs
for n in 1 2 3; do
  d="$tmp/u$n"; mkdir -p "$d"
  gen "$d/.env" >/dev/null 2>&1
  runs+=("$d/.env")
  check_env "$d/.env" "execução $n"
done
for k in "${SECRETS[@]}"; do
  u="$(for f in "${runs[@]}"; do val "$f" "$k"; done | sort -u | wc -l)"
  assert_eq "3" "$u" "$k diferente em 3 execuções"
done
# dentro da mesma execução, os segredos não se repetem
f="${runs[0]}"
assert_eq "3" "$( (val "$f" ZENY_ADMIN_PASSWORD; val "$f" ZENY_STORAGE_ROOT_PASSWORD; val "$f" ZENY_STORAGE_SECRET_KEY) | sort -u | wc -l)" "três senhas distintas na mesma execução"
assert_eq "$(others "$EX")" "$(others "$f")" "demais linhas iguais ao modelo"

# ---------- 6. sinais: temporário removido ----------
d="$tmp/sig"; mkdir -p "$d"
rm -f "$spy/sleeper"
FAKE_FAIL=sleep-genpkey PATH="$spy/bin:$PATH" bash "$GEN" "$d/.env" >/dev/null 2>&1 &
gp=$!
for _ in $(seq 1 50); do [[ -f "$spy/sleeper" ]] && break; sleep 0.1; done
if [[ -f "$spy/sleeper" ]]; then
  kill -TERM "$gp" 2>/dev/null
  sleep 0.3
  kill "$(cat "$spy/sleeper")" 2>/dev/null
  wait "$gp" 2>/dev/null
  sleep 0.3
  assert_eq "" "$(ls -A "$d")" "SIGTERM durante a geração não deixa temporário"
else
  kill "$gp" 2>/dev/null
  _assert_fail "sanidade: o openssl falso não foi alcançado"
fi

# ---------- 7. corrida: duas gerações simultâneas sem --force ----------
d="$tmp/race"; mkdir -p "$d"
(gen "$d/.env" >/dev/null 2>&1; echo $? >"$tmp/race1") &
p1=$!
(gen "$d/.env" >/dev/null 2>&1; echo $? >"$tmp/race2") &
p2=$!
wait "$p1" "$p2"
ok=0
for f in "$tmp/race1" "$tmp/race2"; do [[ "$(cat "$f")" == 0 ]] && ok=$((ok + 1)); done
assert_eq "1" "$ok" "duas gerações simultâneas sem --force: só uma pode gravar (a outra recusa)"
check_env "$d/.env" "corrida"
assert_eq ".env" "$(ls -A "$d")" "corrida: sem temporários"

# ---------- 8. portabilidade: busybox (Alpine) ----------
BB=/usr/bin/busybox
if [[ -x "$BB" ]]; then
  bb="$tmp/bb"; mkdir -p "$bb/bin" "$bb/work"
  missing=""
  for a in base64 tr awk mktemp chmod mv cat dirname rm; do
    if "$BB" --list | grep -qx "$a"; then ln -s "$BB" "$bb/bin/$a"; else missing="$missing $a"; fi
  done
  ln -s "$(command -v bash)" "$bb/bin/bash"
  ln -s "$REAL_OPENSSL" "$bb/bin/openssl"
  if [[ -n "$missing" ]]; then
    skip_case "busybox sem os applets:$missing"
  else
    mkdir -p "$bb/pkg"
    cp "$GEN" "$EX" "$bb/pkg/"
    chmod +x "$bb/pkg/gen-env.sh"
    assert_exit 0 "busybox: geração" -- bash -c 'cd "$1/work" && PATH="$1/bin" bash "$1/pkg/gen-env.sh" --address 0.0.0.0 --public-endpoint "http://a/b&c|d\\e" "$1/work/.env"' _ "$bb"
    printf '%s' "$ASSERT_OUT" >"$tmp/out.txt"
    leaks "$tmp/out.txt" "$bb/work/.env" >/dev/null && _assert_fail "busybox: saída sem segredos"
    assert_eq "600" "$(stat -c %a "$bb/work/.env")" "busybox: modo 600"
    check_env "$bb/work/.env" "busybox"
    assert_eq "0.0.0.0" "$(val "$bb/work/.env" ZENY_HTTP_ADDRESS)" "busybox: --address"
    assert_eq 'http://a/b&c|d\e' "$(val "$bb/work/.env" ZENY_STORAGE_PUBLIC_ENDPOINT)" "busybox: endpoint literal"
    assert_eq "$(wc -l <"$EX")" "$(wc -l <"$bb/work/.env")" "busybox: mesmo número de linhas"
    assert_eq "$(others "$EX" | grep -vE '^ZENY_(HTTP_ADDRESS|STORAGE_PUBLIC_ENDPOINT)=')" "$(others "$bb/work/.env" | grep -vE '^ZENY_(HTTP_ADDRESS|STORAGE_PUBLIC_ENDPOINT)=')" "busybox: demais linhas iguais"
    assert_eq ".env" "$(ls -A "$bb/work")" "busybox: sem temporários"
    assert_exit 2 "busybox: recusa sem --force" -- bash -c 'PATH="$1/bin" bash "$1/pkg/gen-env.sh" "$1/work/.env"' _ "$bb"
    assert_exit 0 "busybox: --force" -- bash -c 'PATH="$1/bin" bash "$1/pkg/gen-env.sh" --force "$1/work/.env"' _ "$bb"
    assert_exit 2 "busybox: diretório inexistente" -- bash -c 'PATH="$1/bin" bash "$1/pkg/gen-env.sh" "$1/nada/.env"' _ "$bb"
  fi
else
  skip_case "busybox ausente: portabilidade para Alpine"
fi

finish
