#!/usr/bin/env bash
# Casos adversariais de tools/lib/common.sh, assert.sh e run.sh. Só servidores falsos em temporários.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"
source "$ROOT/tools/lib/common.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"

in_front() {
  local front="$1"
  shift
  (cd / && env -u ZENY_SERVER_REPO bash -c 'source "$1/tools/lib/common.sh" && shift && "$@"' _ "$front" "$@")
}

exists() { [[ -e "$1" ]] && echo yes || echo no; }

# ---- srv_git: opções que escrevem arquivo mesmo em subcomandos permitidos ----
mkdir -p "$tmp/out"
srv_git diff "--output=$tmp/out/diff.txt" "$FAKE_C1" "$FAKE_C2" >/dev/null 2>&1
assert_eq "no" "$(exists "$tmp/out/diff.txt")" "srv_git diff --output não escreve arquivo"
srv_git archive "--output=$tmp/out/a.tar" "$FAKE_C1" >/dev/null 2>&1
assert_eq "no" "$(exists "$tmp/out/a.tar")" "srv_git archive --output não escreve arquivo"
srv_git archive -o "$tmp/out/b.tar" "$FAKE_C1" >/dev/null 2>&1
assert_eq "no" "$(exists "$tmp/out/b.tar")" "srv_git archive -o não escreve arquivo"
srv_git show "--output=$tmp/out/show.txt" "$FAKE_C1" >/dev/null 2>&1
assert_eq "no" "$(exists "$tmp/out/show.txt")" "srv_git show --output não escreve arquivo"
srv_git diff "--output=$srv/pwned" "$FAKE_C1" "$FAKE_C2" >/dev/null 2>&1
assert_eq "no" "$(exists "$srv/pwned")" "srv_git diff --output não cria arquivo dentro do servidor"
rm -f "$srv/pwned"

# ---- srv_git: subcomandos recusados e formas inválidas ----
for sub in commit gc config tag branch push update-ref stash add rm; do
  assert_exit 2 "srv_git $sub recusado" -- srv_git "$sub"
done
assert_exit 2 "srv_git sem argumentos recusado" -- srv_git
assert_exit 2 "srv_git subcomando vazio recusado" -- srv_git ''
assert_exit 2 "srv_git não aceita opção no lugar do subcomando (-c)" -- srv_git -c core.x=y rev-parse HEAD
assert_exit 2 "srv_git não aceita maiúscula (Show)" -- srv_git Show HEAD
assert_exit 2 "srv_git não aceita prefixo (rev)" -- srv_git rev HEAD
assert_contains "$(srv_git show "$FAKE_C1:README.md")" "servidor falso v1" "srv_git show lê arquivo de um commit"
assert_contains "$(srv_git cat-file -p "$FAKE_C1:README.md")" "servidor falso v1" "srv_git cat-file lê arquivo de um commit"

# ---- servidor com espaço e ZENY_SERVER_REPO relativo ----
mkdir -p "$tmp/com espaco"
cp -a "$srv/." "$tmp/com espaco/"
assert_eq "$FAKE_C4" "$(ZENY_SERVER_REPO="$tmp/com espaco" srv_git rev-parse main)" "srv_git com caminho com espaço"
assert_eq "$FAKE_C4" "$(ZENY_SERVER_REPO="$tmp/com espaco" resolve_server_ref main)" "resolve_server_ref com caminho com espaço"
assert_eq "$srv" "$(cd "$tmp" && ZENY_SERVER_REPO="ZenyHotelManager" server_repo)" "ZENY_SERVER_REPO relativo é normalizado a absoluto"
assert_eq "$srv" "$(cd "$tmp/out" && ZENY_SERVER_REPO="../ZenyHotelManager/." server_repo)" "ZENY_SERVER_REPO relativo com .. e ."
assert_exit 2 "ZENY_SERVER_REPO relativo inexistente -> 2" -- env ZENY_SERVER_REPO=nada-aqui bash -c "source '$ROOT/tools/lib/common.sh'; server_repo"
assert_exit 2 "ZENY_SERVER_REPO num subdiretório do servidor -> 2" -- env ZENY_SERVER_REPO="$srv/api" bash -c "source '$ROOT/tools/lib/common.sh'; server_repo"

# ---- resolve_server_ref ----
assert_eq "$FAKE_C1" "$(resolve_server_ref "${FAKE_C1:0:12}")" "resolve_server_ref aceita SHA curto"
assert_eq "$FAKE_C3" "$(resolve_server_ref main~1)" "resolve_server_ref main~1"
assert_eq "$FAKE_C4" "$(resolve_server_ref "$FAKE_C4")" "resolve_server_ref SHA completo"
for bad in -h -- --end-of-options -x --output=x -C; do
  assert_exit 2 "REF '$bad' -> 2" -- resolve_server_ref "$bad"
done
assert_exit 2 "REF que aponta para blob -> 2" -- resolve_server_ref "main:api/openapi.yaml"
assert_exit 2 "REF só espaço -> 2" -- resolve_server_ref ' '
assert_exit 2 "REF com espaço -> 2" -- resolve_server_ref 'main x'
assert_exit 2 "sem argumento -> 2" -- resolve_server_ref
assert_exit 2 "ZENY_SERVER_REPO inexistente -> 2" -- env ZENY_SERVER_REPO="$tmp/nao-existe" bash -c "source '$ROOT/tools/lib/common.sh'; resolve_server_ref main"
(cd "$tmp/out" && resolve_server_ref --output=x >/dev/null 2>&1)
assert_eq "no" "$(exists "$tmp/out/x")" "REF malicioso não cria arquivo no cwd"

# ---- chamado de worktree: o pin é do worktree em que o script está ----
make_fake_front "$tmp/front"
git -C "$tmp/front" worktree add -q "$tmp/front/.claude/worktrees/wt" -b wt >/dev/null 2>&1
wt="$tmp/front/.claude/worktrees/wt"
mkdir -p "$wt/api"
printf 'commit=%s\n' "$FAKE_C2" >"$wt/api/server-pin.properties"
assert_eq "$FAKE_C2" "$(in_front "$wt" pin_get commit)" "pin_get lê o pin do worktree"
assert_eq "$wt/api/server-pin.properties" "$(in_front "$wt" pin_file)" "pin_file é do worktree"
assert_exit 3 "pin do worktree não vaza para o checkout principal" -- in_front "$tmp/front" pin_get commit
assert_eq "$FAKE_C4" "$(cd / && ZENY_SERVER_REPO="$srv" in_front "$wt" resolve_server_ref main)" "resolve_server_ref a partir de worktree"

# ---- pin_get ----
make_fake_front "$tmp/front3"
p="$tmp/front3/api/server-pin.properties"
mkdir -p "$tmp/front3/api"
printf 'commit_extra=zzz\ncommit=abc123\ncommits=no\nxcommit=no\n' >"$p"
assert_eq "abc123" "$(in_front "$tmp/front3" pin_get commit)" "pin_get: chave que é prefixo de outra"
assert_eq "zzz" "$(in_front "$tmp/front3" pin_get commit_extra)" "pin_get: chave mais longa"
assert_exit 3 "pin_get: prefixo de chave existente não casa" -- in_front "$tmp/front3" pin_get comm
assert_exit 3 "pin_get: sufixo de chave existente não casa" -- in_front "$tmp/front3" pin_get ommit
printf 'a=b=c\nk==\nempty=\nb64=YWJj==\n' >"$p"
assert_eq "YWJj==" "$(in_front "$tmp/front3" pin_get b64)" "pin_get: valor terminado em = (base64) não perde o padding"
assert_eq "b=c" "$(in_front "$tmp/front3" pin_get a)" "pin_get: valor com ="
assert_eq "=" "$(in_front "$tmp/front3" pin_get k)" "pin_get: valor só '='"
assert_eq "" "$(in_front "$tmp/front3" pin_get empty)" "pin_get: valor vazio devolve vazio"
assert_exit 0 "pin_get: valor vazio sai 0" -- in_front "$tmp/front3" pin_get empty
printf 'commit=abc\ncommit=def\n' >"$p"
assert_eq "1" "$(in_front "$tmp/front3" pin_get commit | wc -l | tr -d ' ')" "pin_get: chave repetida devolve uma linha só"
printf 'commit=abc' >"$p"
assert_eq "abc" "$(in_front "$tmp/front3" pin_get commit)" "pin_get: última linha sem newline"
printf 'a=1\r\ncommit=abc\r\n' >"$p"
assert_eq "abc" "$(in_front "$tmp/front3" pin_get commit)" "pin_get: CRLF não vaza \\r no valor"
printf 'commit = abc\n' >"$p"
assert_exit 3 "pin_get: espaço ao redor do = não casa (formato estrito)" -- in_front "$tmp/front3" pin_get commit
printf '' >"$p"
assert_exit 3 "pin_get: arquivo vazio -> 3" -- in_front "$tmp/front3" pin_get commit
printf 'a=1\n' >"$p"
assert_exit 3 "pin_get: chave vazia -> 3" -- in_front "$tmp/front3" pin_get ''

# ---- short_commit ----
assert_eq "abc" "$(short_commit abc)" "short_commit de string curta não preenche"
assert_eq "" "$(short_commit '')" "short_commit vazio"

# ---- finish: falha > pulado > passa ----
aux() { # aux NOME CORPO : cria script auxiliar que carrega assert.sh e termina com finish
  printf 'source "%s/tests/lib/assert.sh"\n%s\nfinish\n' "$ROOT" "$2" >"$tmp/aux_$1.sh"
}
aux ok 'assert_eq a a "ok"'
aux fail 'assert_eq a b "falha"'
aux skip_fail 'assert_eq a a "ok"; skip_case "pulado antes"; assert_contains abc x "falha depois"; skip_case "pulado depois"'
aux exit_fail 'assert_exit 0 "comando falha" -- false; skip_case "pulado"'
aux not_contains 'assert_not_contains abc b "contém"'
aux file "assert_file \"$tmp/nao-existe\" \"sem arquivo\""
aux exit_wrong 'assert_exit 5 "esperava 5" -- bash -c "exit 4"'
aux exit_right 'assert_exit 5 "esperava 5" -- bash -c "exit 5"'
assert_exit 0 "só asserções ok -> 0" -- bash "$tmp/aux_ok.sh"
assert_exit 1 "falha -> 1" -- bash "$tmp/aux_fail.sh"
assert_exit 1 "pulo antes e depois da falha -> 1" -- bash "$tmp/aux_skip_fail.sh"
assert_exit 1 "assert_exit falho + pulo -> 1" -- bash "$tmp/aux_exit_fail.sh"
assert_exit 1 "assert_not_contains falho -> 1" -- bash "$tmp/aux_not_contains.sh"
assert_exit 1 "assert_file falho -> 1" -- bash "$tmp/aux_file.sh"
assert_exit 1 "assert_exit com código diferente falha" -- bash "$tmp/aux_exit_wrong.sh"
assert_exit 0 "assert_exit com código igual passa" -- bash "$tmp/aux_exit_right.sh"
assert_contains "$(bash "$tmp/aux_skip_fail.sh")" "FALHOU: falha depois" "falha imprime FALHOU: MSG"
assert_contains "$(bash "$tmp/aux_skip_fail.sh")" "PULADO: pulado antes" "pulo imprime PULADO: MSG"

# ---- run.sh: precedência por arquivo e no resumo (cópia de run.sh numa árvore temporária) ----
rt="$tmp/rt"
mkdir -p "$rt/tests" "$rt/tools"
cp "$ROOT/tests/run.sh" "$rt/tests/run.sh"
printf '#!/usr/bin/env bash\nexit 0\n' >"$rt/tests/test_ok.sh"
printf '#!/usr/bin/env bash\necho "PULADO: motivo-x"\nexit 77\n' >"$rt/tests/test_skip.sh"
printf '#!/usr/bin/env bash\necho "FALHOU: boom"\nexit 1\n' >"$rt/tests/test_fail.sh"
printf '#!/usr/bin/env bash\nexit 2\n' >"$rt/tests/test_two.sh"

out="$(bash "$rt/tests/run.sh" tests/test_skip.sh 2>&1)"
code=$?
assert_eq "0" "$code" "run.sh: só SKIP sai 0"
assert_contains "$out" "SKIP tests/test_skip.sh" "run.sh: exit 77 vira SKIP"
assert_not_contains "$out" "FAIL" "run.sh: SKIP não é FAIL"
assert_contains "$out" "PULADO: motivo-x" "run.sh: repete a linha PULADO"
assert_contains "$out" "Resumo: 1 arquivos, 0 falhas, 1 com casos pulados" "run.sh: resumo com SKIP"

out="$(bash "$rt/tests/run.sh" tests/test_fail.sh 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh: falha sai 1"
assert_contains "$out" "FAIL tests/test_fail.sh (código 1)" "run.sh: exit 1 vira FAIL"
assert_contains "$out" "FALHOU: boom" "run.sh: mostra a saída do arquivo que falhou"
assert_contains "$out" "Resumo: 1 arquivos, 1 falhas, 0 com casos pulados" "run.sh: resumo com falha"

out="$(bash "$rt/tests/run.sh" tests/test_two.sh 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh: código 2 é falha, não pulo"
assert_contains "$out" "FAIL tests/test_two.sh (código 2)" "run.sh: código 2 vira FAIL"

out="$(bash "$rt/tests/run.sh" tests/test_ok.sh tests/test_skip.sh tests/test_fail.sh 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh: falha + skip + ok sai 1"
assert_contains "$out" "PASS tests/test_ok.sh" "run.sh: ok vira PASS"
assert_contains "$out" "Resumo: 3 arquivos, 1 falhas, 1 com casos pulados" "run.sh: resumo misto"

out="$(bash "$rt/tests/run.sh" tests/nao-existe.sh 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh: arquivo de teste inexistente é falha"

out="$(bash "$rt/tests/run.sh" 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh sem argumentos: com FAIL na pasta sai 1"
assert_contains "$out" "Resumo: 4 arquivos, 2 falhas, 1 com casos pulados" "run.sh sem argumentos: descobre os 4 arquivos"
assert_eq "0" "$(cd / && bash "$rt/tests/run.sh" tests/test_ok.sh >/dev/null 2>&1; echo $?)" "run.sh independe do cwd"

printf '#!/usr/bin/env bash\nif then\n' >"$rt/tests/test_syntax.sh"
out="$(bash "$rt/tests/run.sh" tests/test_ok.sh 2>&1)"
code=$?
assert_eq "1" "$code" "run.sh: erro de sintaxe em qualquer *.sh sai 1"
assert_contains "$out" "FAIL (bash -n): tests/test_syntax.sh" "run.sh: bash -n aponta o arquivo"

finish
