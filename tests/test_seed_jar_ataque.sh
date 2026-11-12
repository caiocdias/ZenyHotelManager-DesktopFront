#!/usr/bin/env bash
# Ataque da Task 11 (E05): o demo-seed.jar e a documentação do seed. Não chama o Gradle; sem JAR, a parte do JAR
# é pulada (77). Servidores de teste são locais (127.0.0.1), nunca o do ambiente.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

sem_comentario() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1"; }

# --- 1. Documentação e script de build (estático) ----------------------------------------------------------
kts="$(sem_comentario "$ROOT/demo-seed/build.gradle.kts")"
if grep -Eiw 'application' <<<"$kts" >/dev/null; then
  _assert_fail "demo-seed/build.gradle.kts tem a palavra application em código ativo"
fi
assert_contains "$kts" 'archiveClassifier = "plain"' "o jar fino leva o classificador plain"
assert_contains "$kts" 'archiveFileName = "demo-seed.jar"' "o seedJar se chama demo-seed.jar"
assert_contains "$kts" 'assemble { dependsOn(seedJar) }' "assemble depende do seedJar"
assert_contains "$kts" 'tasks.register<JavaExec>("run")' "run é uma JavaExec"

docs=("$ROOT/README.md" "$ROOT/CLAUDE.md" "$ROOT/tools/server-env/LEIA-ME.md")
for d in "${docs[@]}"; do
  nome="${d#"$ROOT"/}"
  if grep -Eq '\./gradlew run([^:A-Za-z-]|$)' "$d"; then
    _assert_fail "$nome ainda recomenda ./gradlew run sem módulo (executaria também o run do seed)"
  fi
  # Nenhum valor de segredo: nem variável atribuída a algo que não seja <placeholder>, nem opção de segredo com valor.
  if grep -Eq 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[A-Z_]*\s*=\s*"?[^<"[:space:]]' "$d"; then
    _assert_fail "$nome atribui valor literal a variável de segredo do seed"
  fi
  if grep -Eq -e '--(admin-|admin-new-|manager-|receptionist-|housekeeper-)?(password|pin)[ =]+[^<[:space:]`"]' "$d"; then
    _assert_fail "$nome mostra segredo como argumento de linha de comando"
  fi
done
assert_contains "$(cat "$ROOT/README.md")" ':app:run' "README com :app:run"
assert_contains "$(cat "$ROOT/CLAUDE.md")" ':app:run' "CLAUDE.md com :app:run"
assert_contains "$(cat "$ROOT/README.md")" ':demo-seed:seedJar' "README com seedJar"
assert_contains "$(cat "$ROOT/CLAUDE.md")" 'demo-seed/build/libs/demo-seed.jar' "CLAUDE.md com o caminho do seedJar"
leia="$(cat "$ROOT/tools/server-env/LEIA-ME.md")"
assert_contains "$leia" 'java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login' "LEIA-ME com o comando do JAR"
assert_contains "$leia" '$env:ZENY_SEED_ADMIN_PASSWORD' "LEIA-ME recomenda variáveis de ambiente"
assert_contains "$leia" 'omita' "LEIA-ME manda omitir a senha nova quando não há"
assert_contains "$leia" 'ZENY_SEED_ADMIN_NEW_PASSWORD' "LEIA-ME cita a senha nova"
assert_contains "$leia" 'bloqueia' "LEIA-ME avisa do bloqueio por senha errada repetida"
for v in ADMIN_PASSWORD ADMIN_NEW_PASSWORD MANAGER_PASSWORD RECEPTIONIST_PASSWORD HOUSEKEEPER_PASSWORD MANAGER_PIN; do
  assert_contains "$leia" "ZENY_SEED_$v" "LEIA-ME cobre a variável $v"
done

# --- 2. JAR ------------------------------------------------------------------------------------------------
jar="$ROOT/demo-seed/build/libs/demo-seed.jar"
plain="$ROOT/demo-seed/build/libs/demo-seed-plain.jar"
if [[ ! -f "$jar" ]]; then
  skip_case "demo-seed.jar inexistente (rode ./gradlew :demo-seed:seedJar)"
  finish
  exit $?
fi
for t in unzip zipinfo java timeout python3; do
  command -v "$t" >/dev/null || { skip_case "falta $t"; finish; exit $?; }
done

names="$(zipinfo -1 "$jar")"
# O fino não pode ter engolido o executável: o executável é bem maior e o fino não tem manifesto executável.
tam_jar="$(stat -c %s "$jar")"
tam_plain="$(stat -c %s "$plain" 2>/dev/null || echo 0)"
[[ "$tam_jar" -gt $((tam_plain * 10)) && "$tam_jar" -gt 5000000 ]] || _assert_fail "demo-seed.jar não parece o JAR com runtime" "jar=$tam_jar plain=$tam_plain"
assert_contains "$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')" "Multi-Release: true" "manifesto Multi-Release (dependências multi-release)"
assert_contains "$names" "kotlinx/serialization/json/Json.class" "serialização kotlinx no JAR"
assert_contains "$names" "kotlinx/coroutines/BuildersKt.class" "coroutines no JAR"
assert_contains "$names" "io/ktor/client/engine/java/Java.class" "engine Java do Ktor no JAR"

# Serviços fundidos: o Ktor acha o engine e o JAR não repete linha nem tem arquivo vazio.
eng="$(unzip -p "$jar" META-INF/services/io.ktor.client.HttpClientEngineContainer 2>/dev/null | tr -d '\r')"
assert_contains "$eng" "io.ktor.client.engine.java.JavaHttpEngineContainer" "serviço do engine Java do Ktor"
while IFS= read -r svc; do
  body="$(unzip -p "$jar" "$svc" | tr -d '\r' | sed 's/#.*//; s/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$')"
  [[ -n "$body" ]] || _assert_fail "serviço $svc vazio"
  assert_eq "" "$(sort <<<"$body" | uniq -d)" "serviço $svc sem linhas repetidas"
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    grep -Fxq "${cls//.//}.class" <<<"$names" || _assert_fail "serviço $svc aponta para $cls, ausente do JAR"
  done <<<"$body"
done < <(grep -E '^META-INF/services/[^/]+$' <<<"$names")

# O fino não é executável.
if [[ -f "$plain" ]]; then
  out="$(java -jar "$plain" --help 2>&1)"
  code=$?
  [[ "$code" != "0" ]] || _assert_fail "demo-seed-plain.jar executou com sucesso"
  assert_contains "$out" "no main manifest" "plain jar recusado pela JVM por falta de Main-Class"
fi

# --- 3. Execução --------------------------------------------------------------------------------------------
fake() { head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16; }
SENHA_A="$(fake)Aa1"; SENHA_N="$(fake)Aa1"; SENHA_G="$(fake)Aa1"; SENHA_R="$(fake)Aa1"; SENHA_H="$(fake)Aa1"
PIN="$(( (RANDOM % 9000) + 1000 ))"
com_segredos() {
  (
    export "ZENY_SEED_ADMIN_PASSWORD=$SENHA_A" "ZENY_SEED_ADMIN_NEW_PASSWORD=$SENHA_N" \
      "ZENY_SEED_MANAGER_PASSWORD=$SENHA_G" "ZENY_SEED_RECEPTIONIST_PASSWORD=$SENHA_R" \
      "ZENY_SEED_HOUSEKEEPER_PASSWORD=$SENHA_H" "ZENY_SEED_MANAGER_PIN=$PIN"
    timeout 40 "$@"
  )
}
sem_segredo_na_saida() {
  local texto="$1" msg="$2" s
  for s in "$SENHA_A" "$SENHA_N" "$SENHA_G" "$SENHA_R" "$SENHA_H"; do
    assert_not_contains "$texto" "$s" "$msg: vazou senha"
  done
}
# Remove o ruído do SLF4J (sem provedor, avisa no stderr); o resto é a saída do programa.
sem_slf4j() { grep -v '^SLF4J' <<<"$1"; }

# --help: stdout, sem stderr, sem valores de segredo, mesmo com os segredos no ambiente.
saida="$(com_segredos java -jar "$jar" --help 2>/dev/null)"; code=$?
assert_eq "0" "$code" "--help sai 0"
assert_contains "$saida" "ZENY_SEED_ADMIN_NEW_PASSWORD" "--help cita as variáveis"
sem_segredo_na_saida "$saida" "--help"
assert_eq "" "$(com_segredos java -jar "$jar" -h 2>&1 >/dev/null | grep -v '^SLF4J')" "-h sem nada no stderr"

# Uso inválido: 2, nada no stdout, uso no stderr, segredo passado como argumento não é ecoado.
err="$(java -jar "$jar" --nada 2>&1 >/dev/null)"; code=$?
assert_eq "2" "$code" "opção desconhecida sai 2"
assert_contains "$err" "Opção desconhecida: --nada." "mensagem de opção desconhecida"
assert_contains "$err" "Uso: demo-seed" "uso no stderr"
assert_eq "" "$(java -jar "$jar" --nada 2>/dev/null)" "uso inválido não escreve no stdout"
java -jar "$jar" >/dev/null 2>&1; assert_eq "2" "$?" "sem nada (faltam as variáveis) sai 2"
curto="Curto$(fake)"
saida="$(java -jar "$jar" --server http://127.0.0.1:1 --admin-login admin --admin-password "$curto" 2>&1)"; code=$?
assert_eq "2" "$code" "senha curta como argumento sai 2"
assert_not_contains "$saida" "$curto" "senha inválida não é ecoada"
saida="$(java -jar "$jar" --server "ftp://$SENHA_A@host" 2>&1)"
assert_not_contains "$saida" "$SENHA_A" "endereço inválido com segredo não é ecoado"
assert_not_contains "$saida" "Exception" "uso inválido sem nome de exceção"

# Servidores locais de mentira: JSON com marcador, 500 e porta fechada. Em todos: 1, uma linha, sem vazamento.
srv="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$srv"' EXIT
cat >"$srv/srv.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
mode = sys.argv[2]
class H(BaseHTTPRequestHandler):
    def _do(self):
        n = int(self.headers.get('Content-Length') or 0)
        if n:
            self.rfile.read(n)
        b = b'{"leak":"MARCADOR-VAZADO","detail":"MARCADOR-VAZADO"}'
        self.send_response(200 if mode == 'json' else 500)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _do
    def log_message(self, *a):
        pass
HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
roda() { # roda NOME URL
  local nome="$1" url="$2" ini=$SECONDS
  saida="$(com_segredos java -jar "$jar" --server "$url" --admin-login admin 2>&1)"; code=$?
  local dur=$((SECONDS - ini))
  assert_eq "1" "$code" "$nome sai 1"
  [[ "$dur" -lt 15 ]] || _assert_fail "$nome levou ${dur}s (limite 15 s)"
  saida="$(sem_slf4j "$saida")"
  assert_eq "1" "$(wc -l <<<"$saida" | tr -d ' ')" "$nome imprime uma linha só" "saída: $saida"
  [[ "$saida" == Falha:* ]] || _assert_fail "$nome não começa com 'Falha:'" "saída: $saida"
  sem_segredo_na_saida "$saida" "$nome"
  assert_not_contains "$saida" "MARCADOR-VAZADO" "$nome: corpo da resposta vazou"
  assert_not_contains "$saida" "$PIN" "$nome: PIN vazou"
  for ruim in Exception "at com." "at io." "at kotlin" "127.0.0.1" "Caused by" '{' "ConnectException" "UnknownHost"; do
    assert_not_contains "$saida" "$ruim" "$nome sem '$ruim'"
  done
}
for modo in json erro; do
  porta=$((20000 + RANDOM % 20000))
  python3 "$srv/srv.py" "$porta" "$modo" &
  sleep 1
  roda "servidor $modo" "http://127.0.0.1:$porta"
  kill $! 2>/dev/null
done
roda "porta fechada" "http://127.0.0.1:1"
roda "host inexistente" "http://host-que-nao-existe.invalid"

finish
