# CLAUDE.md

## Projeto

Cliente desktop do Zeny Hotel Manager, em Compose Multiplatform (ADR 0001). Módulos: `sdk` (gerado do contrato, não versionado), `core` (única porta das features para o `sdk`), `design`, `app` e `demo-seed`. Docs em pt-BR; código e identificadores em inglês. O repositório do servidor (`/home/caio/repos/ZenyHotelManager`) é só leitura. Visão geral e ferramentas: `README.md`.

## Stack e comandos

Kotlin, Compose Multiplatform e Gradle 9.8.1 (wrapper). Versões só em `gradle/libs.versions.toml`.

| Para quê | Comando |
|---|---|
| build | `./gradlew build` |
| testes | `./gradlew test` e `bash tests/run.sh` |
| lint | `./gradlew ktlintCheck detekt` |
| run | `./gradlew :app:run` |
| JAR | `./gradlew :app:desktopJar` → `app/build/libs/zeny-desktop.jar` |
| seed | `./gradlew :demo-seed:run --args="--server http://localhost:8088 --admin-login <login>"`, com os segredos por variáveis de ambiente (veja `--help` e `tools/server-env/LEIA-ME.md`) |
| JAR do seed | `./gradlew :demo-seed:seedJar` → `demo-seed/build/libs/demo-seed.jar` |
| smokeTest | `./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088`, com os segredos pelas variáveis `ZENY_SEED_*` (fora de `check` e de `build`) |
| travas | `./gradlew resolveAndLockAll --write-locks` |
| verificação rápida | `./gradlew :<módulo>:test --tests '<filtro>' :<módulo>:ktlintCheck :<módulo>:detekt`, com `<módulo>` em `sdk`, `core`, `design`, `app` ou `demo-seed` e `<filtro>` na classe ou no padrão dos testes do que a tarefa tocou (`--tests` pode repetir). Um trio por módulo tocado e por módulo que usa o que mudou (`sdk` → `core` → `design`, `app` e `demo-seed`; `design` → `app`). Script de `tools/` ou `tests/`: `bash tests/run.sh tests/<arquivo>.sh` |
| gate do projeto | `./gradlew build` + `bash tests/run.sh` |

- Regrave os lockfiles no mesmo commit que muda catálogo ou plugin.
- Nunca `./gradlew --stop`: o Gradle 9.8.1 e o `~/.gradle` são compartilhados com o servidor.
- A memória é curta: se o build falhar com "daemon disappeared", repita uma vez.

## Git

- Trabalhe em branch `roadmap/<slug>`.
- Nunca faça push nem deploy.
- Commits em pt-BR, no estilo do histórico. Commits das souls levam o trailer `Soul:`.
- Nunca `git clean` no checkout principal: apagaria `.server/` e `build/demo-package/`.

## Decisões do usuário

As decisões são tomadas por entrevista, com uma opção recomendada. Elas ficam registradas em `docs/adr/`; consulte lá em vez de copiar aqui. ADR em estado de proposta espera a decisão do usuário.
