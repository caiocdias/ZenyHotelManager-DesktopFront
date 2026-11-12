#!/usr/bin/env bash
# Lê app/build/libs/zeny-desktop.jar já gerado (Task 8). Não chama o Gradle; sem JAR, sai com 77.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

jar="$ROOT/app/build/libs/zeny-desktop.jar"
if [[ ! -f "$jar" ]]; then
  skip_case "zeny-desktop.jar inexistente (rode ./gradlew :app:desktopJar)"
  exit 77
fi
for t in unzip zipinfo sha256sum; do
  command -v "$t" >/dev/null || { skip_case "falta $t"; exit 77; }
done

names="$(zipinfo -1 "$jar")"

assert_eq "" "$(sort <<<"$names" | uniq -d)" "sem entradas duplicadas"
assert_eq "" "$(grep -Ei -e 'module-info\.class$' -e '^META-INF/[^/]+\.(SF|DSA|RSA|EC)$' <<<"$names")" "sem module-info nem assinaturas"
mf="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')"
assert_contains "$mf" "Main-Class: com.zeny.desktop.app.MainKt" "manifesto com Main-Class"
assert_contains "$mf" "Enable-Native-Access: ALL-UNNAMED" "manifesto com Enable-Native-Access"
assert_contains "$names" "com/zeny/desktop/app/MainKt.class" "MainKt no JAR"
for f in skiko-windows-x64.dll libskiko-linux-x64.so; do
  assert_eq "$f" "$(grep -Fx "$f" <<<"$names")" "nativo $f na raiz do JAR"
done

# O .dll do JAR é idêntico ao do artefato skiko-awt-runtime-windows-x64 no cache do Gradle.
art="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.skiko/skiko-awt-runtime-windows-x64" -name 'skiko-awt-runtime-windows-x64-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -n1)"
if [[ -z "$art" ]]; then
  skip_case "artefato skiko-awt-runtime-windows-x64 fora do cache do Gradle"
else
  want="$(unzip -p "$art" skiko-windows-x64.dll | sha256sum | cut -d' ' -f1)"
  got="$(unzip -p "$jar" skiko-windows-x64.dll | sha256sum | cut -d' ' -f1)"
  assert_eq "$want" "$got" "skiko-windows-x64.dll idêntico ao artefato"
fi

# Serviços: sem linhas repetidas e com ao menos um provedor.
while IFS= read -r svc; do
  body="$(unzip -p "$jar" "$svc" | tr -d '\r' | sed 's/#.*//; s/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$')"
  assert_eq "" "$(sort <<<"$body" | uniq -d)" "serviço $svc sem linhas repetidas"
  [[ -n "$body" ]] || _assert_fail "serviço $svc sem nenhum provedor"
done < <(grep '^META-INF/services/.' <<<"$names")

# Com display, o JAR abre e segue rodando sem nada em stderr. Sem DISPLAY, registra o pulo.
if [[ -n "${DISPLAY:-}" ]] && command -v java >/dev/null; then
  # Preferências isoladas (raiz nova sob app/build): o teste não lê nem cria ~/.java/.userPrefs do usuário.
  prefs="$ROOT/app/build/test-prefs-jar.$$"
  rm -rf "$prefs"; mkdir -p "$prefs"
  errf="$(mktemp)"
  timeout 12 java -Djava.util.prefs.userRoot="$prefs" -jar "$jar" >/dev/null 2>"$errf"
  code=$?
  assert_eq "124" "$code" "java -jar segue rodando até o timeout"
  assert_eq "" "$(cat "$errf")" "java -jar sem nada em stderr"
  rm -rf "$errf" "$prefs"
else
  skip_case "sem DISPLAY ou java: java -jar não exercitado"
fi

finish
