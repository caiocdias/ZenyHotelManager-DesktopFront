#!/usr/bin/env bash
# Ataque de etapa (M1 E01): o que os testes estáticos de tests/test_gradle_layout.sh, test_lockfiles.sh e
# test_docs.sh deixam passar. Aqui os asserts olham só código ativo (sem comentário) e a coerência entre o que
# a documentação promete e o que o build declara. Não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Texto de um arquivo sem comentários de linha (// em Kotlin, # em yml e properties).
sem_comentario() {
  local f="$1" estilo="${2:-kts}"
  case "$estilo" in
    kts) sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$f" 2>/dev/null ;;
    hash) sed -E 's@^\s*#.*$@@' "$f" 2>/dev/null ;;
  esac
}
# Bloco de topo `nome { ... }` de um texto (até a primeira linha que é só `}`).
bloco_de() { awk -v nome="$1" '$0 ~ "^"nome" *\\{"{d=1} d{print} d&&/^\}/{d=0}' <<<"$2"; }

raiz="$(sem_comentario "$ROOT/build.gradle.kts")"
sub="$(bloco_de subprojects "$raiz")"
catalogo="$(cat "$ROOT/gradle/libs.versions.toml" 2>/dev/null)"

# --- 1. Lint: asserts sobre código ativo, não sobre comentário ou nome de extensão -----------------------
assert_contains "$sub" 'apply(plugin = "dev.detekt")' "subprojects aplica o plugin dev.detekt de fato"
assert_contains "$sub" 'apply(plugin = "org.jlleitschuh.gradle.ktlint")' "subprojects aplica o plugin do ktlint de fato"
assert_contains "$sub" 'it.name == "detektMain"' "check depende de detektMain (comparação de nome da tarefa)"
assert_contains "$sub" 'it.name == "detektTest"' "check depende de detektTest (comparação de nome da tarefa)"
assert_contains "$sub" 'dependsOn(' "o check do lint usa dependsOn"
assert_contains "$sub" 'it.name == "check"' "o lint pendura-se na tarefa check"
assert_contains "$sub" 'version = rootProject.libs.versions.ktlint.cli' "ktlint CLI fixado pelo catálogo, não pelo padrão do plugin"
# Os ids aplicados por string têm de ser os ids do catálogo (o plugin só está no classpath via alias).
assert_contains "$catalogo" 'detekt = { id = "dev.detekt"' "catálogo: o id do plugin detekt é o aplicado na raiz"
assert_contains "$catalogo" 'ktlint = { id = "org.jlleitschuh.gradle.ktlint"' "catálogo: o id do plugin ktlint é o aplicado na raiz"
assert_contains "$raiz" 'alias(libs.plugins.detekt) apply false' "raiz declara o detekt do catálogo (apply false)"
assert_contains "$raiz" 'alias(libs.plugins.ktlint) apply false' "raiz declara o ktlint do catálogo (apply false)"
# O código gerado fica fora dos dois lints.
assert_eq "2" "$(grep -c 'contains("/build/generated/")' <<<"$sub")" "detekt e ktlint excluem /build/generated/"

# --- 2. detekt.yml: ForbiddenImport ativo, escopo certo, todo pacote do sdk que não é modelo --------------
dy="$(sem_comentario "$ROOT/config/detekt/detekt.yml" hash)"
fi_bloco="$(awk '/^  ForbiddenImport:/{d=1;next} d&&/^  [A-Za-z]/{d=0} d{print}' <<<"$dy")"
assert_contains "$fi_bloco" 'active: true' "ForbiddenImport está ativo"
assert_contains "$fi_bloco" '**/app/src/**' "ForbiddenImport vale em app/src"
assert_contains "$fi_bloco" '**/design/src/**' "ForbiddenImport vale em design/src"
assert_not_contains "$fi_bloco" '**/core/src/**' "ForbiddenImport não cobre o core (o core chama o sdk)"
pacotes=(client util)
gen="$ROOT/sdk/build/generated/sources/ogen/src/main/kotlin/com/zeny/desktop/sdk"
if [[ -d "$gen" ]]; then
  while IFS= read -r d; do
    [[ "$d" == model ]] && continue
    [[ " ${pacotes[*]} " == *" $d "* ]] || pacotes+=("$d")
  done < <(cd "$gen" && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)
else
  skip_case "sdk/build/generated ausente: pacotes do sdk lidos só da lista fixa (client, util)"
fi
for p in "${pacotes[@]}"; do
  assert_contains "$fi_bloco" "value: 'com.zeny.desktop.sdk.$p.*'" "ForbiddenImport barra o pacote com.zeny.desktop.sdk.$p do sdk gerado"
done

# --- 3. Nenhum uso (import ou nome qualificado) de pacote de operação em app e design ----------------------
# find monta a lista de arquivos; sem arquivo, o grep leria o stdin, e /dev/null evita isso.
varre_proibidos() {
  local base="$1" d
  local -a arquivos=()
  for d in app/src design/src; do
    [[ -d "$base/$d" ]] || continue
    while IFS= read -r -d '' f; do arquivos+=("$f"); done < <(find "$base/$d" -type f -print0)
  done
  grep -nE 'com\.zeny\.desktop\.sdk\.(client|util)|io\.ktor\.client|de\.quati\.ogen\.client' /dev/null "${arquivos[@]}" 2>/dev/null
}
if [[ ! -d "$ROOT/app/src" ]]; then _assert_fail "app/src existe (a varredura de fronteira tem o que varrer)"; fi
assert_eq "" "$(varre_proibidos "$ROOT")" "app/src e design/src sem pacote de operação do sdk"
plantado="$(mktemp -d)"
mkdir -p "$plantado/app/src/main/kotlin" "$plantado/design/src/main/kotlin"
printf 'val x = com.zeny.desktop.sdk.util.Foo()\n' >"$plantado/design/src/main/kotlin/A.kt"
assert_contains "$(varre_proibidos "$plantado")" "sdk.util.Foo" "a varredura de fronteira acha nome qualificado em design/src"
printf 'import com.zeny.desktop.sdk.client.AuthApi\n' >"$plantado/app/src/main/kotlin/B.kt"
assert_contains "$(varre_proibidos "$plantado")" "sdk.client.AuthApi" "a varredura de fronteira acha import em app/src"
# Cliente HTTP só pelo core (ADR 0008, D1): pacote ktor e ogen-client, com subpacote e nome qualificado.
for pkg in 'io.ktor.client.*' 'de.quati.ogen.client.*'; do
  assert_contains "$fi_bloco" "value: '$pkg'" "ForbiddenImport barra $pkg (cliente HTTP só pelo core)"
done
printf 'import io.ktor.client.HttpClient\n' >"$plantado/app/src/main/kotlin/C.kt"
assert_contains "$(varre_proibidos "$plantado")" "io.ktor.client.HttpClient" "a varredura de fronteira acha import de io.ktor.client em app/src"
printf 'import io.ktor.client.request.get\n' >"$plantado/app/src/main/kotlin/D.kt"
assert_contains "$(varre_proibidos "$plantado")" "io.ktor.client.request.get" "a varredura de fronteira acha subpacote io.ktor.client.request em app/src"
printf 'val c = de.quati.ogen.client.ktor.HttpClientOgen()\n' >"$plantado/design/src/main/kotlin/E.kt"
assert_contains "$(varre_proibidos "$plantado")" "de.quati.ogen.client.ktor.HttpClientOgen" "a varredura de fronteira acha nome qualificado de de.quati.ogen.client em design/src"
rm -rf "$plantado"

# --- 4. Módulos: grafo e plugins por módulo (design §6.1) --------------------------------------------------
kts() { sem_comentario "$ROOT/$1/build.gradle.kts"; }
for m in sdk core design app demo-seed; do
  assert_contains "$(kts "$m")" 'jvmToolchain(25)' "$m fixa jvmToolchain(25)"
  assert_not_contains "$(kts "$m")" 'repositories' "$m não declara repositórios (FAIL_ON_PROJECT_REPOS)"
done
assert_not_contains "$(kts sdk)" 'project(' "sdk não depende de outro módulo"
assert_not_contains "$(kts core)" 'project(":design")' "core não depende do design"
assert_not_contains "$(kts core)" 'project(":app")' "core não depende do app"
assert_contains "$(kts design)" 'implementation(project(":core"))' "design depende do core"
assert_contains "$(kts demo-seed)" 'implementation(project(":core"))' "demo-seed depende do core"
assert_contains "$(kts app)" 'implementation(project(":core"))' "app depende do core"
assert_contains "$(kts app)" 'implementation(project(":design"))' "app depende do design"
for m in core demo-seed sdk; do
  assert_not_contains "$(kts "$m")" 'compose' "$m não usa Compose (só design e app)"
done
for m in demo-seed core design; do
  assert_not_contains "$(kts "$m")" 'application' "$m não aplica o plugin application (o run é do app)"
done
for m in design demo-seed; do
  assert_not_contains "$(kts "$m")" 'api(project' "$m não reexporta módulo por api()"
done
assert_contains "$(kts core)" 'api(project(":sdk"))' "core expõe o sdk por api() em código ativo (não em comentário)"
assert_not_contains "$(kts core)" 'implementation(project(":sdk"))' "core não esconde o sdk por implementation()"

# --- 5. sdk compila o contrato inteiro, gerado e não versionado -----------------------------------------
sdk_kts="$(kts sdk)"
assert_contains "$sdk_kts" 'specFile("$rootDir/api/openapi.yaml")' "sdk gera de api/openapi.yaml"
for filtro in 'exclude' 'operationFilter' 'includeOperations' 'excludeOperations' 'ignoreOperation' 'filter' 'tags ='; do
  assert_not_contains "$sdk_kts" "$filtro" "sdk não filtra operações do contrato ($filtro)"
done
assert_contains "$sdk_kts" 'inputs.file("$rootDir/api/openapi.yaml")' "ogenGenerate declara o contrato como entrada (regera ao mudar)"
assert_contains "$(cat "$ROOT/.gitignore" 2>/dev/null)" 'build/' ".gitignore ignora build/ (onde o sdk gera)"
for g in '.gradle/' '.kotlin/' 'build/'; do
  assert_contains $'\n'"$(cat "$ROOT/.gitignore" 2>/dev/null)"$'\n' $'\n'"$g"$'\n' ".gitignore tem a linha $g"
done
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  gerado="$(git -C "$ROOT" ls-files -z | tr '\0' '\n' | grep -E '(^|/)build/|/generated/|^sdk/src/main/')"
  assert_eq "" "$gerado" "nenhum arquivo gerado ou de build versionado"
  versionado_sdk="$(git -C "$ROOT" grep -lE '^package com\.zeny\.desktop\.sdk\.(client|model|util)' -- ':!*.md' 2>/dev/null)"
  assert_eq "" "$versionado_sdk" "nenhum código do pacote gerado do sdk versionado"
else
  skip_case "sem repositório git: versionamento do código gerado não checado"
fi

# --- 6. Lockfiles versionados de verdade (git ls-files) e não ignorados -------------------------------------
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  for lf in buildscript-gradle.lockfile settings-gradle.lockfile sdk/gradle.lockfile core/gradle.lockfile \
    design/gradle.lockfile app/gradle.lockfile demo-seed/gradle.lockfile; do
    if ! git -C "$ROOT" ls-files --error-unmatch "$lf" >/dev/null 2>&1; then _assert_fail "$lf está versionado (git ls-files)"; fi
    if git -C "$ROOT" check-ignore -q "$lf"; then _assert_fail "$lf não é ignorado pelo .gitignore"; fi
  done
else
  skip_case "sem repositório git: lockfiles versionados não checados"
fi

# --- 7. gradle.properties: cada chave exatamente uma vez (a última linha venceria) ---------------------------
props="$(sem_comentario "$ROOT/gradle.properties" hash)"
for k in org.gradle.jvmargs kotlin.compiler.execution.strategy org.gradle.workers.max org.gradle.parallel \
  org.gradle.daemon.idletimeout; do
  assert_eq "1" "$(grep -cE "^[[:space:]]*${k//./\\.}[[:space:]]*[=:]" <<<"$props")" "gradle.properties: $k aparece uma vez só"
done
assert_not_contains "$props" 'org.gradle.configuration-cache=true' "configuration cache desligado (o ogenGenerate é incompatível, ADR 0007)"
assert_not_contains "$props" 'org.gradle.caching=true' "build cache remoto/local não ligado sem decisão"

# --- 8. Janela do app: o título vem da constante e a classe principal existe ----------------------------------
main_kt="$ROOT/app/src/main/kotlin/com/zeny/desktop/app/Main.kt"
main_ativo="$(sem_comentario "$main_kt")"
assert_contains "$main_ativo" 'const val APP_TITLE = "Zeny Hotel Manager"' "APP_TITLE é Zeny Hotel Manager"
assert_contains "$main_ativo" 'title = APP_TITLE' "a Window usa APP_TITLE (o MainTest só vê a constante)"
assert_contains "$main_ativo" 'fun main()' "Main.kt tem fun main()"
assert_contains "$main_ativo" 'onCloseRequest = ::exitApplication' "fechar a janela encerra o app"
app_ativo="$(kts app)"
assert_eq "2" "$(grep -c 'com.zeny.desktop.app.MainKt' <<<"$app_ativo")" "app: mainClass do run e Main-Class do JAR apontam para MainKt"
assert_contains "$app_ativo" 'mainClass = "com.zeny.desktop.app.MainKt"' "compose.desktop.application.mainClass (./gradlew run)"
assert_contains "$app_ativo" '"Main-Class" to "com.zeny.desktop.app.MainKt"' "Main-Class do manifesto do JAR"

# --- 9. Documentação confere com o build -----------------------------------------------------------------
readme="$(cat "$ROOT/README.md" 2>/dev/null)"
claude="$(cat "$ROOT/CLAUDE.md" 2>/dev/null)"
jar_destino="$(sed -nE 's@.*destinationDirectory = layout\.buildDirectory\.dir\("([^"]+)"\).*@\1@p' <<<"$app_ativo")"
jar_nome="$(sed -nE 's@.*archiveFileName = "([^"]+)".*@\1@p' <<<"$app_ativo")"
assert_contains "$app_ativo" 'tasks.register<Jar>("desktopJar")' "app registra a tarefa desktopJar citada nos comandos"
for doc in "$readme" "$claude"; do
  assert_contains "$doc" "\`./gradlew :app:desktopJar\` → \`app/build/$jar_destino/$jar_nome\`" "doc cita o caminho real do JAR (app/build/$jar_destino/$jar_nome)"
done
assert_contains "$raiz" 'tasks.register("resolveAndLockAll")' "a raiz registra resolveAndLockAll (comando 'travas')"
assert_contains "$raiz" '--write-locks' "resolveAndLockAll exige --write-locks, como a doc manda"
for nome in README.md CLAUDE.md; do
  texto="$(cat "$ROOT/$nome" 2>/dev/null)"
  recomenda="$(grep -- '--stop' <<<"$texto" | grep -v -iE '(nunca|não)')"
  assert_eq "" "$recomenda" "$nome nunca recomenda gradlew --stop"
  if ! grep -qiE 'nunca.{0,40}--stop' <<<"$texto"; then _assert_fail "$nome tem a proibição do --stop"; fi
done
assert_contains "$claude" 'Nunca `./gradlew --stop`' "CLAUDE.md proíbe ./gradlew --stop com todas as letras"
assert_contains "$claude" 'Nunca faça push' "CLAUDE.md proíbe push"
assert_contains "$claude" 'trailer `Soul:`' "CLAUDE.md cita o trailer Soul:"
# Links de ADR do README apontam para arquivos que existem.
while IFS= read -r adr; do
  assert_file "$ROOT/$adr" "ADR citada no README existe: $adr"
done < <(grep -oE 'docs/adr/[A-Za-z0-9_.-]+\.md' <<<"$readme" | sort -u)
for adr in 0007-gerador-do-sdk 0008-fronteira-core-sdk; do
  assert_file "$ROOT/docs/adr/$adr.md" "ADR $adr existe"
  assert_contains "$(cat "$ROOT/docs/adr/$adr.md")"$'\n' $'\n- Estado: ' "ADR $adr tem a linha de Estado"
  for secao in '## Contexto' '## Decisão' '## Consequências'; do
    assert_contains $'\n'"$(cat "$ROOT/docs/adr/$adr.md")"$'\n' $'\n'"$secao"$'\n' "ADR $adr tem a seção $secao"
  done
done
assert_contains $'\n'"$(cat "$ROOT/docs/adr/0008-fronteira-core-sdk.md")"$'\n' $'\n## Alternativas\n' "ADR 0008 tem a seção Alternativas"
assert_contains $'\n'"$(cat "$ROOT/docs/adr/0007-gerador-do-sdk.md")"$'\n' $'\n## Alternativas descartadas\n' "ADR 0007 tem a seção Alternativas descartadas"
estado_0008="$(grep -m1 '^- Estado: ' "$ROOT/docs/adr/0008-fronteira-core-sdk.md" 2>/dev/null)"
assert_contains "$estado_0008" "aceita" "ADR 0008 está aceita (linha de Estado)"
assert_contains "$estado_0008" "confirmada pelo usuário" "ADR 0008 registra a confirmação do usuário (linha de Estado)"

# ADR 0007: todo teste permanente que a tabela cita existe como função de teste no sdk.
adr7="$(cat "$ROOT/docs/adr/0007-gerador-do-sdk.md" 2>/dev/null)"
n_citados=0
while IFS= read -r ref; do
  classe="${ref%%.*}"
  metodo="${ref#*.}"
  arq="$(find "$ROOT/sdk/src/test" -name "$classe.kt" -type f 2>/dev/null | head -n1)"
  n_citados=$((n_citados + 1))
  if [[ -z "$arq" ]]; then
    _assert_fail "ADR 0007 cita $ref: a classe $classe existe em sdk/src/test"
  elif ! grep -qE "fun $metodo\b" "$arq"; then
    _assert_fail "ADR 0007 cita $ref: $metodo existe em $classe"
  fi
done < <(grep -oE 'Sdk(Criteria|Attack)Test\.[A-Za-z0-9_]+' <<<"$adr7" | sort -u)
if ((n_citados < 5)); then _assert_fail "ADR 0007 cita ao menos 5 testes permanentes" "citou $n_citados"; fi

finish
