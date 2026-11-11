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
| run | `./gradlew run` |
| JAR | `./gradlew :app:desktopJar` → `app/build/libs/zeny-desktop.jar` |
| travas | `./gradlew resolveAndLockAll --write-locks` |
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
