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

## Processo acelerado (até segunda ordem)

Por decisão do usuário, o orquestrador segue o [ADR 0011](docs/adr/0011-processo-acelerado-ate-a-demo.md). Toda tarefa entra como `Risco: normal`, e o testador roda uma vez por etapa. Os planos são escritos para ondas paralelas de até 3 tarefas. A `revisor-seguranca` e o scanner ficam fora até a revogação. Nesse período, a etapa fecha com suíte verde, gate verde e a evidência de cada critério de aceite. A revisão adiada cobre `a300a23..HEAD`.
