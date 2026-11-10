#!/usr/bin/env bash
# Testes adversariais de tools/server-pin.sh (servidor falso e front falso em diretório temporário).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwx "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

make_fake_server "$tmp/server"
srv="$tmp/server"
export ZENY_SERVER_REPO="$srv"
sgit() { git -C "$srv" -c user.name=T -c user.email=t@e -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"; }
same() { cmp -s "$1" "$2" || assert_eq "iguais" "diferentes" "$3"; }

# fresh NAME : front falso novo com pin de C1 gravado; define $f.
fresh() {
  f="$tmp/$1"
  make_fake_front "$f"
  write_fake_pin "$f" "$srv" "$FAKE_C1"
  cp "$f/api/server-pin.properties" "$tmp/$1.pin.bak"
  cp "$f/api/openapi.yaml" "$tmp/$1.yaml.bak"
}
pin() { (cd / && bash "$f/tools/server-pin.sh" "$@"); }
intact() { # NAME MSG
  same "$tmp/$1.pin.bak" "$f/api/server-pin.properties" "pin intacto: $2"
  same "$tmp/$1.yaml.bak" "$f/api/openapi.yaml" "openapi intacto: $2"
  assert_eq "openapi.yaml
server-pin.properties" "$(ls -A "$f/api")" "sem temporários em api/: $2"
}
prop() { grep "^$1=" "$f/api/server-pin.properties" | cut -d= -f2-; }

# --- REF malicioso, com hífen ---
fresh a
for bad in "--output=$tmp/pwned" "-o" "-oX" "--exec=touch $tmp/pwned" "--foo" "-"; do
  assert_exit 2 "REF recusado: $bad" -- pin "$bad"
  intact a "REF $bad"
done
[[ ! -e "$tmp/pwned" ]] || assert_eq "inexistente" "existe" "REF malicioso não gravou arquivo"
for bad in "main;touch $tmp/pwned2" "\$(touch $tmp/pwned2)" "\`touch $tmp/pwned2\`" "main
C1" "main:api/openapi.yaml" "$FAKE_C1:api/openapi.yaml" "nao existe" "../../etc/passwd"; do
  assert_exit 2 "REF inválido recusado" -- pin "$bad"
  intact a "REF inválido"
done
[[ ! -e "$tmp/pwned2" ]] || assert_eq "inexistente" "existe" "injeção por REF não executou"
blob="$(git -C "$srv" rev-parse "$FAKE_C4:api/openapi.yaml")"
tree="$(git -C "$srv" rev-parse "$FAKE_C4^{tree}")"
assert_exit 2 "REF é SHA de blob" -- pin "$blob"
intact a "blob"
assert_exit 2 "REF é SHA de árvore" -- pin "$tree"
intact a "árvore"

# --- REF tag (leve e anotada), SHA curto, main~1, branch ---
sgit tag leve "$FAKE_C3"
sgit tag -a anotada -m "tag anotada" "$FAKE_C1"
assert_exit 0 "REF tag leve" -- pin leve
assert_eq "$FAKE_C3" "$(prop commit)" "tag leve resolve ao commit"
assert_exit 0 "REF tag anotada" -- pin anotada
assert_eq "$FAKE_C1" "$(prop commit)" "tag anotada resolve ao commit (não ao objeto da tag)"
assert_exit 0 "REF SHA curto" -- pin "${FAKE_C3:0:7}"
assert_eq "$FAKE_C3" "$(prop commit)" "SHA curto vira SHA completo de 40"
assert_exit 0 "REF main~1" -- pin 'main~1'
assert_eq "$FAKE_C3" "$(prop commit)" "main~1 é C3"
git -C "$srv" cat-file blob "$FAKE_C3:api/openapi.yaml" >"$tmp/c3.yaml"
same "$tmp/c3.yaml" "$f/api/openapi.yaml" "openapi de main~1 byte a byte"
assert_exit 0 "REF branch" -- pin refs/heads/main
assert_eq "$FAKE_C4" "$(prop commit)" "refs/heads/main é C4"
assert_exit 0 "REF SHA completo de C2" -- pin "$FAKE_C2"
assert_eq "$FAKE_C2" "$(prop commit)" "C2 (contrato idêntico ao de C1)"
assert_eq "$(sha256sum "$f/api/openapi.yaml" | cut -d' ' -f1)" "$(prop contract_sha256)" "hash de C2"

# --- permissões e idempotência ---
assert_eq "644" "$(stat -c %a "$f/api/openapi.yaml")" "openapi.yaml com modo 644"
assert_eq "644" "$(stat -c %a "$f/api/server-pin.properties")" "pin com modo 644"
cp "$f/api/openapi.yaml" "$tmp/idem.yaml"
assert_exit 0 "segunda execução" -- pin "$FAKE_C2"
same "$tmp/idem.yaml" "$f/api/openapi.yaml" "reexecução mantém o contrato"

# --- contrato grande e binário com bytes nulos ---
head -c 6000000 /dev/urandom >"$tmp/bin.dat"
printf 'a\0b\0\0\xff\xfe\n\0' >>"$tmp/bin.dat"
cp "$tmp/bin.dat" "$srv/api/openapi.yaml"
sgit add api/openapi.yaml
sgit commit -q -m "contrato binário grande"
binc="$(sgit rev-parse HEAD)"
fresh b
assert_exit 0 "fixa contrato binário de 6 MB com NULs" -- pin "$binc"
same "$tmp/bin.dat" "$f/api/openapi.yaml" "binário com NULs byte a byte"
assert_eq "$(sha256sum "$tmp/bin.dat" | cut -d' ' -f1)" "$(prop contract_sha256)" "hash do binário"
: >"$srv/api/openapi.yaml"
sgit add api/openapi.yaml
sgit commit -q -m "contrato vazio"
assert_exit 0 "fixa contrato vazio" -- pin HEAD
assert_eq "0" "$(wc -c <"$f/api/openapi.yaml" | tr -d ' ')" "contrato vazio copiado com 0 bytes"
assert_eq "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" "$(prop contract_sha256)" "hash do vazio"
printf '\n\n\n' >"$srv/api/openapi.yaml"
sgit add api/openapi.yaml
sgit commit -q -m "só quebras"
assert_exit 0 "fixa contrato só com quebras de linha" -- pin HEAD
same "$srv/api/openapi.yaml" "$f/api/openapi.yaml" "quebras de linha preservadas (sem trim)"

# --- contrato ausente / não-blob no commit ---
sgit rm -q -f api/openapi.yaml
mkdir -p "$srv/api/openapi.yaml"
printf 'x' >"$srv/api/openapi.yaml/arquivo"
sgit add -A api
sgit commit -q -m "openapi.yaml virou diretório"
dirc="$(sgit rev-parse HEAD)"
fresh c
assert_exit 2 "api/openapi.yaml é diretório no commit" -- pin "$dirc"
intact c "contrato é diretório"
sgit rm -q -r -f api
sgit commit -q -m "sem api/"
noapi="$(sgit rev-parse HEAD)"
assert_exit 2 "commit sem diretório api/" -- pin "$noapi"
intact c "sem api/"
assert_contains "$ASSERT_OUT" "${noapi:0:12}" "mensagem cita o commit"

# --- front sem api/ ---
f="$tmp/d"
make_fake_front "$f"
rm -rf "$f/api"
assert_exit 0 "front sem api/ cria o diretório" -- pin "$FAKE_C4"
assert_eq "$FAKE_C4" "$(prop commit)" "pin criado em api/ novo"
git -C "$srv" cat-file blob "$FAKE_C4:api/openapi.yaml" >"$tmp/c4.yaml"
same "$tmp/c4.yaml" "$f/api/openapi.yaml" "openapi criado byte a byte"
f="$tmp/d2"
make_fake_front "$f"
rm -rf "$f/api"
assert_exit 2 "front sem api/ e REF inválido" -- pin refs/nao-existe
assert_eq "" "$(ls -A "$f/api" 2>/dev/null)" "REF inválido não deixa arquivos em api/"

# --- atomicidade: falha ao gravar o pin não pode deixar cópia nova com pin velho ---
fresh e
rm -f "$f/api/server-pin.properties"
mkdir "$f/api/server-pin.properties"   # o mv do pin não substitui um diretório
(cd / && bash "$f/tools/server-pin.sh" "$FAKE_C4") >"$tmp/e.out" 2>&1
code=$?
if [[ "$code" == 0 ]]; then
  assert_eq "código diferente de 0" "código 0" "pin impossível de gravar deve falhar (saída: $(cat "$tmp/e.out"))"
fi
same "$tmp/e.yaml.bak" "$f/api/openapi.yaml" "openapi não muda se o pin não pôde ser gravado"

# api/ sem permissão de escrita: nada muda
fresh g
chmod a-w "$f/api"
if [[ -w "$f/api/x" || "$(id -u)" == 0 ]]; then
  skip_case "api/ somente leitura (usuário ignora permissões)"
else
  (cd / && bash "$f/tools/server-pin.sh" "$FAKE_C4") >"$tmp/g.out" 2>&1
  code=$?
  [[ "$code" != 0 ]] || assert_eq "código diferente de 0" "0" "api/ somente leitura deve falhar"
  intact g "api/ somente leitura"
fi
chmod u+w "$f/api"

# --- worktree do front: o pin vai para o worktree, não para o checkout principal ---
mkdir "$tmp/wtbase"
mm="$tmp/wtbase/front"
make_fake_front "$mm"
write_fake_pin "$mm" "$srv" "$FAKE_C1"
_fgit "$mm" add -A
_fgit "$mm" commit -q -m "pin C1"
_fgit "$mm" worktree add -q -b wtb "$tmp/wtbase/wt" HEAD
cp "$mm/api/server-pin.properties" "$tmp/main.pin.bak"
cp "$mm/api/openapi.yaml" "$tmp/main.yaml.bak"
assert_exit 0 "roda do worktree" -- bash "$tmp/wtbase/wt/tools/server-pin.sh" "$FAKE_C4"
assert_eq "$FAKE_C4" "$(grep '^commit=' "$tmp/wtbase/wt/api/server-pin.properties" | cut -d= -f2)" "pin gravado no worktree"
same "$tmp/c4.yaml" "$tmp/wtbase/wt/api/openapi.yaml" "contrato gravado no worktree"
same "$tmp/main.pin.bak" "$mm/api/server-pin.properties" "pin do checkout principal intacto"
same "$tmp/main.yaml.bak" "$mm/api/openapi.yaml" "openapi do checkout principal intacto"
assert_eq "" "$(git -C "$mm" status --porcelain)" "checkout principal limpo"

# servidor padrão (sem ZENY_SERVER_REPO) vem do checkout principal, não do worktree
mkdir -p "$tmp/wtbase2"
cp -a "$srv" "$tmp/wtbase2/ZenyHotelManager"
mm2="$tmp/wtbase2/front"
make_fake_front "$mm2"
cp "$mm2/api/server-pin.properties" "$tmp/main2.pin.bak"
_fgit "$mm2" worktree add -q -b wtb2 "$tmp/wtbase2/deep/wt" HEAD
assert_exit 0 "worktree usa ../ZenyHotelManager do checkout principal" -- env -u ZENY_SERVER_REPO bash "$tmp/wtbase2/deep/wt/tools/server-pin.sh" "$FAKE_C1"
assert_eq "$FAKE_C1" "$(grep '^commit=' "$tmp/wtbase2/deep/wt/api/server-pin.properties" | cut -d= -f2)" "pin no worktree com servidor padrão"
same "$tmp/main2.pin.bak" "$mm2/api/server-pin.properties" "pin do checkout principal intacto (servidor padrão)"

# --- nenhuma escrita no servidor falso ---
srvsnap() {
  (cd "$srv" && find . -print0 | sort -z | xargs -0 stat -c '%n %s %Y %a'; git for-each-ref; git worktree list | cut -d' ' -f1)
}
fresh h
s1="$(srvsnap)"
pin >/dev/null 2>&1
pin "$FAKE_C1" >/dev/null 2>&1
pin "${FAKE_C3:0:8}" >/dev/null 2>&1
pin leve >/dev/null 2>&1
pin refs/nao-existe >/dev/null 2>&1
pin --output=x >/dev/null 2>&1
pin "$noapi" >/dev/null 2>&1
s2="$(srvsnap)"
assert_eq "$s1" "$s2" "servidor falso (arquivos, mtime, .git, índice, refs) intacto após várias execuções"
assert_eq "" "$(git -C "$srv" status --porcelain)" "status do servidor falso limpo"

finish
