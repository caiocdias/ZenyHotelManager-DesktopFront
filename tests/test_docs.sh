#!/usr/bin/env bash
# Testes da documentação: README cobre todo script executável e os códigos do pré-voo;
# docs/pedidos-servidor.md traz a tabela de controle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

readme="$ROOT/README.md"
pedidos="$ROOT/docs/pedidos-servidor.md"

assert_file "$readme" "README.md existe"
assert_file "$pedidos" "docs/pedidos-servidor.md existe"

readme_text="$(cat "$readme" 2>/dev/null)"
while IFS= read -r script; do
  assert_contains "$readme_text" "$script" "README cita $script"
done < <(cd "$ROOT" && find tools -name '*.sh' -type f -perm -u+x | sort)

for code in 0 10 11 12 2 3; do
  assert_contains "$readme_text" "| $code |" "README tem o código de saída $code"
done

assert_contains "$(cat "$pedidos" 2>/dev/null)" \
  "| PF-NN | título | estado | captura | etapa do front | commit de entrega |" \
  "cabeçalho da tabela de pedidos"

leia="$(cat "$ROOT/tools/server-env/LEIA-ME.md" 2>/dev/null)"
envex="$(cat "$ROOT/tools/server-env/.env.example" 2>/dev/null)"
pkgsh="$(cat "$ROOT/tools/server-env/package.sh" 2>/dev/null)"
assert_not_contains "$readme_text" "gen-env.sh .server/zeny-front.env" "README não manda gerar o env com caminho relativo"
assert_not_contains "$envex" "gen-env.sh .server/zeny-front.env" ".env.example não manda gerar o env com caminho relativo"
assert_contains "$readme_text" "tools/server-env/up.sh" "README manda começar pelo up.sh"
assert_contains "$envex" "tools/server-env/up.sh" ".env.example manda começar pelo up.sh"
assert_contains "$readme_text" "depois do \`package.sh\`" "README: save-images.sh roda depois do package.sh"
assert_contains "$pkgsh" "DEPOIS de package.sh" "images/LEIA-ME.txt: save-images.sh depois do package.sh"
assert_contains "$leia" "alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f" "LEIA-ME: alpine fixado por digest"
assert_contains "$leia" "Parar sem apagar" "LEIA-ME: parar sem apagar"
assert_contains "$leia" "--env-file .env down"$'\n' "LEIA-ME: down sem -v"
assert_contains "$leia" "down -v\` apaga" "LEIA-ME: aviso do down -v"
assert_contains "$leia" "Get-NetConnectionProfile" "LEIA-ME: checar categoria da rede"
assert_contains "$leia" "Set-NetConnectionProfile -InterfaceAlias" "LEIA-ME: ajustar categoria da rede"

# Comandos oficiais: mesmos textos no README e no CLAUDE.md.
claude="$ROOT/CLAUDE.md"
assert_file "$claude" "CLAUDE.md existe"
claude_text="$(cat "$claude" 2>/dev/null)"
official_commands=(
  '| build | `./gradlew build` |'
  '| testes | `./gradlew test` e `bash tests/run.sh` |'
  '| lint | `./gradlew ktlintCheck detekt` |'
  '| run | `./gradlew :app:run` |'
  '| JAR | `./gradlew :app:desktopJar` → `app/build/libs/zeny-desktop.jar` |'
  '| travas | `./gradlew resolveAndLockAll --write-locks` |'
  '| gate do projeto | `./gradlew build` + `bash tests/run.sh` |'
)
for cmd in "${official_commands[@]}"; do
  assert_contains "$readme_text" "$cmd" "README tem o comando: $cmd"
  assert_contains "$claude_text" "$cmd" "CLAUDE.md tem o comando: $cmd"
done
for section in "## Projeto" "## Stack e comandos" "## Git" "## Decisões do usuário"; do
  assert_contains $'\n'"$claude_text"$'\n' $'\n'"$section"$'\n' "CLAUDE.md tem a seção $section"
done
assert_contains "$readme_text" "0007" "README cita a ADR 0007"
assert_contains "$readme_text" "0008" "README cita a ADR 0008"
assert_not_contains "$readme_text" "Ainda não há código do app" "README não diz mais que não há código"

# Roteiro da demo do M1 (E07): passos em ordem, comandos do PowerShell 5.1 e nenhum segredo.
roteiro="$ROOT/docs/demo/roteiro-m1.md"
assert_file "$roteiro" "docs/demo/roteiro-m1.md existe"
rot_text="$(cat "$roteiro" 2>/dev/null)"
steps=(
  "## 1. Recriar a demo"
  "## 2. Abrir o app"
  "## 3. Configurar o servidor"
  "## 4. Entrar como Recepcionista"
  "## 5. Entrar como Gerente"
  "## 6. Mapa de UHs"
  "## 7. Mudança de status ao vivo"
  "## 8. Sair"
)
prev=0
for step in "${steps[@]}"; do
  line="$(grep -nFx -- "$step" "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  if [[ -z "$line" ]]; then
    _assert_fail "roteiro tem o passo: $step"
  elif ((line <= prev)); then
    _assert_fail "roteiro: passo fora de ordem: $step"
  else
    prev="$line"
  fi
done
for word in recepcao gerente governanta '"-Dzeny.devShell=true"' "http://localhost:8088" MARK_CLEAN MARK_DIRTY \
  If-Match Get-Credential "[guid]::NewGuid().ToString()" "charset=utf-8" "-UseBasicParsing"; do
  assert_contains "$rot_text" "$word" "roteiro cita $word"
done
# Seções do LEIA-ME pelo título, nunca pelo número; cada título citado existe no LEIA-ME.
assert_eq "" "$(grep -nE '[Ss]eção [0-9]' "$roteiro" 2>/dev/null)" "roteiro não cita seção do LEIA-ME pelo número"
cited="$(grep -oE 'seção "[^"]+"' "$roteiro" 2>/dev/null | sed -E 's/^seção "//; s/"$//' | sort -u)"
for t in "Parar e recriar a demo" "Primeira vez (com internet)" "Abrir o app"; do
  assert_contains "$cited" "$t" "roteiro remete à seção \"$t\" do LEIA-ME"
done
while IFS= read -r t; do
  [[ -n "$t" ]] || continue
  grep -qE "^## ([0-9]+\. )?$(sed 's/[][\.*^$()+?{}|]/\\&/g' <<<"$t")\$" "$ROOT/tools/server-env/LEIA-ME.md" ||
    _assert_fail "seção citada pelo roteiro existe no LEIA-ME: $t"
done <<<"$cited"
# Sem segredo nem comando perigoso.
assert_eq "" "$(grep -nE -e '-Password\b' "$roteiro" 2>/dev/null)" "roteiro sem -Password"
assert_eq "" "$(grep -niE 'password[[:space:]]*=[[:space:]]*["'"'"']?[^$<"'"'"'[:space:]]' "$roteiro" 2>/dev/null)" \
  "roteiro sem password= com valor literal"
assert_eq "" "$(grep -niE '"password"[[:space:]]*:[[:space:]]*"[^<$]' "$roteiro" 2>/dev/null)" \
  "roteiro sem \"password\":\"valor\""
assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*[[:space:]]*=[[:space:]]*"?[^<"[:space:]]' "$roteiro" 2>/dev/null)" \
  "roteiro sem valor atribuído a ZENY_SEED_*"
assert_eq "" "$(grep -nE -e '-p zeny( |$|`)' "$roteiro" 2>/dev/null)" "roteiro sem -p zeny"
ps_blocks="$(awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' "$roteiro" 2>/dev/null)"
assert_contains "$ps_blocks" "Invoke-RestMethod" "roteiro tem blocos PowerShell com os comandos HTTP"
assert_eq "" "$(grep -nE '(^|[^.A-Za-z0-9_-])curl([[:space:]]|$)' <<<"$ps_blocks")" "bloco PowerShell sem curl (alias), só curl.exe"

# LEIA-ME do pacote: abrir o app, JDK 25 e o demo-seed.jar já em app\.
assert_contains "$leia" 'java -jar app\zeny-desktop.jar' "LEIA-ME: abrir o app"
assert_contains "$leia" 'java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar' "LEIA-ME: abrir o app no modo da demo"
assert_contains "$leia" "JDK 25" "LEIA-ME: JDK 25"
assert_not_contains "$leia" "copie-o para" "LEIA-ME: o demo-seed.jar já vem em app\\"
assert_contains "$leia" 'java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login' "LEIA-ME: comando do seed"

# Segredos do seed: nenhum valor digitado numa linha (o PSReadLine do 5.1 grava o histórico em texto puro);
# cada senha vem do Get-Credential e o PIN do Read-Host -AsSecureString.
leia_file="$ROOT/tools/server-env/LEIA-ME.md"
for f in "$leia_file" "$roteiro"; do
  n="${f#"$ROOT"/}"
  assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[A-Z_]*["'"'"']?[[:space:]]*=' "$f" 2>/dev/null)" \
    "$n: nenhuma atribuição a ZENY_SEED_*PASSWORD ou *_PIN"
  assert_eq "" "$(grep -nE 'SetEnvironmentVariable\([^,]*ZENY_SEED_[^,]*,[[:space:]]*["'"'"']' "$f" 2>/dev/null)" \
    "$n: SetEnvironmentVariable de ZENY_SEED_* sem valor literal"
done
for v in ADMIN_PASSWORD ADMIN_NEW_PASSWORD MANAGER_PASSWORD RECEPTIONIST_PASSWORD HOUSEKEEPER_PASSWORD; do
  set_line="$(grep -E "SetEnvironmentVariable\\(\"ZENY_SEED_$v\"," "$leia_file" 2>/dev/null)"
  assert_contains "$set_line" "(Get-Credential " "LEIA-ME: ZENY_SEED_$v vem do Get-Credential"
  assert_contains "$set_line" ".GetNetworkCredential().Password, \"Process\")" "LEIA-ME: ZENY_SEED_$v só no processo"
done
pin_line="$(grep -E 'SetEnvironmentVariable\("ZENY_SEED_MANAGER_PIN",' "$leia_file" 2>/dev/null)"
pin_var="$(grep -oE 'SecureStringToBSTR\(\$[A-Za-z_]+\)' <<<"$pin_line" | sed -E 's/.*\(\$//; s/\)$//')"
if [[ -z "$pin_var" ]]; then
  _assert_fail "LEIA-ME: ZENY_SEED_MANAGER_PIN vem de uma SecureString" "linha: $pin_line"
else
  assert_contains "$(grep -E "^\\\$$pin_var[[:space:]]*=" "$leia_file")" "Read-Host -AsSecureString" \
    "LEIA-ME: o PIN é lido por Read-Host -AsSecureString"
fi
assert_contains "$leia" "ConsoleHost_history.txt" "LEIA-ME explica por que não digitar o valor"
# Uma linha por vez: colado inteiro, o Read-Host e o Get-Credential leriam a linha seguinte do bloco.
one_ln="$(grep -niF 'uma linha por vez (cole, Enter, responda a janela)' "$leia_file" 2>/dev/null | head -1 | cut -d: -f1)"
first_set_ln="$(grep -nF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$leia_file" 2>/dev/null | head -1 | cut -d: -f1)"
if [[ -z "$one_ln" || -z "$first_set_ln" ]] || ((one_ln > first_set_ln)); then
  _assert_fail "LEIA-ME manda rodar o bloco do seed uma linha por vez, antes do bloco" "linha da frase: $one_ln; bloco: $first_set_ln"
fi
assert_contains "$leia" "aquela variável não é definida (ou fica com o valor da rodada anterior, na mesma janela)" \
  "LEIA-ME: janela cancelada deixa o valor da rodada anterior"
assert_not_contains "$leia" "não a deixe vazia" "LEIA-ME: valor vazio não é proibido (apaga a variável)"
new_pw_note="$(grep -F '**omita** `ZENY_SEED_ADMIN_NEW_PASSWORD`' "$leia_file" 2>/dev/null)"
assert_contains "$new_pw_note" "valor vazio" "LEIA-ME: a nota da senha nova fala do valor vazio"
assert_contains "$new_pw_note" "apaga a variável" "LEIA-ME: valor vazio no SetEnvironmentVariable apaga a variável"

# README: link do roteiro e os JARs gerados antes do package.sh.
assert_contains "$readme_text" "(docs/demo/roteiro-m1.md)" "README liga o roteiro do M1"
pkg_row="$(grep -F '`tools/server-env/package.sh`' "$readme" | head -1)"
assert_contains "$pkg_row" ":demo-seed:seedJar" "linha do package.sh manda gerar os JARs"
pre="${pkg_row%%\`tools/server-env/package.sh\`*}"
assert_contains "$pre" './gradlew :app:desktopJar :demo-seed:seedJar' "JARs gerados antes do package.sh"
assert_contains "$pkg_row" "python3" "linha do package.sh diz que ele precisa do python3 para validar os JARs"

finish
