#!/usr/bin/env bash
# Lê demo-seed/build/libs/demo-seed.jar já gerado (Task 11 da E05). Não chama o Gradle; sem JAR, sai com 77.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

jar="$ROOT/demo-seed/build/libs/demo-seed.jar"
plain="$ROOT/demo-seed/build/libs/demo-seed-plain.jar"
if [[ ! -f "$jar" ]]; then
  skip_case "demo-seed.jar inexistente (rode ./gradlew :demo-seed:seedJar)"
  exit 77
fi
for t in unzip zipinfo java timeout; do
  command -v "$t" >/dev/null || { skip_case "falta $t"; exit 77; }
done

names="$(zipinfo -1 "$jar")"
assert_eq "" "$(sort <<<"$names" | uniq -d)" "sem entradas duplicadas"
assert_eq "" "$(grep -Ei -e 'module-info\.class$' -e '^META-INF/[^/]+\.(SF|DSA|RSA|EC)$' <<<"$names")" "sem module-info nem assinaturas"
mf="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')"
assert_contains "$mf" "Main-Class: com.zeny.desktop.seed.MainKt" "manifesto com Main-Class"
assert_contains "$names" "com/zeny/desktop/seed/MainKt.class" "MainKt no JAR"
assert_contains "$names" "com/zeny/desktop/core/ZenyCore.class" "classes do core no JAR"
assert_contains "$names" "com/zeny/desktop/sdk/model/HotelRequest.class" "classes do sdk no JAR"
assert_contains "$names" "io/ktor/client/HttpClient.class" "classes do Ktor no JAR"

# O JAR só das classes existe, com outro nome, e não é o executável.
if [[ -f "$plain" ]]; then
  assert_not_contains "$(unzip -p "$plain" META-INF/MANIFEST.MF | tr -d '\r')" "Main-Class" "demo-seed-plain.jar sem Main-Class"
  assert_eq "" "$(zipinfo -1 "$plain" | grep -F 'io/ktor/client/HttpClient.class')" "demo-seed-plain.jar sem o runtime"
else
  _assert_fail "demo-seed-plain.jar inexistente"
fi

out="$(java -jar "$jar" --help 2>&1)"
assert_eq "0" "$?" "--help sai com 0"
assert_contains "$out" "--server" "--help lista --server"

# Servidor fora do ar: segredos de teste fictícios, gerados aqui e só no ambiente do processo.
fake_secrets() {
  local n
  for n in ADMIN MANAGER RECEPTIONIST HOUSEKEEPER; do
    export "ZENY_SEED_${n}_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)Aa1"
  done
  export "ZENY_SEED_MANAGER_PIN=$(( (RANDOM % 9000) + 1000 ))"
}
start=$SECONDS
out="$(
  fake_secrets
  unset ZENY_SEED_ADMIN_NEW_PASSWORD
  timeout 30 java -jar "$jar" --server http://127.0.0.1:1 --admin-login admin 2>&1
)"
code=$?
elapsed=$((SECONDS - start))
assert_eq "1" "$code" "servidor fora do ar sai com 1"
[[ "$elapsed" -lt 15 ]] || _assert_fail "servidor fora do ar levou ${elapsed}s (limite 15 s)"
assert_contains "$out" "Não foi possível falar com o servidor" "mensagem em pt-BR"
assert_not_contains "$out" "Exception" "sem nome de exceção"
assert_not_contains "$out" "at com." "sem pilha"

finish
