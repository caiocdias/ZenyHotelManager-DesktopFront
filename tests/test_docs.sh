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

finish
