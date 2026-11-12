# Zeny Hotel Manager — Front Desktop

Cliente desktop do Zeny Hotel Manager, em Compose Multiplatform (ADR 0001). O repositório é um build Gradle com os módulos `sdk` (cliente gerado do contrato com o ogen, ADR 0007), `core` (fronteira entre as features e o `sdk`, ADR 0008, aceita pelo usuário em 2026-11-11), `design`, `app` (a janela) e `demo-seed` (programa que popula o servidor da demo). Além do app, guarda o contrato da API fixado, as ferramentas que sincronizam com o servidor e o ambiente local do servidor para desenvolver e demonstrar.

## Onde está o quê

- Design: [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](docs/superpowers/specs/2026-11-10-front-desktop-design.md) (o §8 define os pedidos ao servidor).
- Decisões (ADRs): [`docs/adr/`](docs/adr/). O 0005 trata dos pedidos ao servidor.
- Roadmaps por marco: [`docs/roadmaps/`](docs/roadmaps/).
- Pedidos do front ao servidor: [`docs/pedidos-servidor.md`](docs/pedidos-servidor.md).
- Pacote do Windows (demo): [`tools/server-env/LEIA-ME.md`](tools/server-env/LEIA-ME.md). O passo a passo no Windows fica lá, não aqui.

## Comandos oficiais do app

| Para quê | Comando |
|---|---|
| build | `./gradlew build` |
| testes | `./gradlew test` e `bash tests/run.sh` |
| lint | `./gradlew ktlintCheck detekt` |
| run | `./gradlew :app:run` |
| JAR | `./gradlew :app:desktopJar` → `app/build/libs/zeny-desktop.jar` |
| seed | `./gradlew :demo-seed:run --args="--server http://localhost:8088 --admin-login <login>"`, com os segredos por variáveis de ambiente (veja `--help` e `tools/server-env/LEIA-ME.md`) |
| JAR do seed | `./gradlew :demo-seed:seedJar` → `demo-seed/build/libs/demo-seed.jar` |
| travas | `./gradlew resolveAndLockAll --write-locks` |
| gate do projeto | `./gradlew build` + `bash tests/run.sh` |

As dependências são travadas em lockfiles estritos: regrave-os no mesmo commit que muda o catálogo de versões ou um plugin. O código do `sdk` é gerado no build e não é versionado. O Gradle 9.8.1 e o `~/.gradle` são compartilhados com o servidor, então nunca rode `./gradlew --stop`.

## Servidor fixado

O front fala com um commit específico do servidor, registrado em `api/server-pin.properties` (`commit`, `contract_sha256`, `synced_at`). `api/openapi.yaml` é cópia byte a byte do contrato nesse commit, e o hash do pin confere com ela.

Hoje: `ca9f9e2f9808c74c98154f1e8e60381fecbf1b5d`.

O repositório do servidor é **só leitura** para o front: só `rev-parse`, `cat-file`, `show`, `archive` e `diff`. Nada de `checkout`, `worktree add`, `fetch` ou arquivos novos lá. Se faltar algo na API, o front abre um pedido (veja `docs/pedidos-servidor.md`) em vez de remendar o servidor.

## Estado de máquina

O ambiente do servidor é da máquina, não do worktree. Por isso tudo isto fica no **checkout principal** do front, mesmo quando o script roda de um worktree:

- `.server/<commit>/`: árvore do servidor extraída do commit fixado (SHA completo, 40 caracteres);
- `.server/<commit>.override.yaml`: override do compose renderizado;
- `.server/zeny-front.env`: o `.env` real, com chaves e senhas (nunca vai para o Git);
- `build/demo-package/`: o pacote do Windows.

`.server/` e `build/` se autoignoram (cada um tem um `.gitignore` com `*`). **Nunca rode `git clean` no checkout principal**: apagaria o `.env`, cujas chaves estão presas ao volume do banco.

O ambiente usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` (commit curto = 12 primeiros caracteres do SHA, só na tag da imagem) e a porta 8088 por padrão. Nunca toca o projeto `zeny` nem a imagem `:dev`.

## Comandos

| Para quê | Comando |
|---|---|
| Fixar o servidor num commit (padrão: `main`), copiando o contrato | `tools/server-pin.sh [REF]` |
| Pré-voo: comparar o contrato do pin com o do servidor e classificar a mudança | `tools/server-drift.sh [--base REF] [--target REF]` |
| Gerar o `.env` do ambiente a partir do `.env.example` | `tools/server-env/gen-env.sh [--force] [--address ADDR] [--public-endpoint URL] DEST` |
| Subir o servidor fixado (`zeny-front`) e esperar o readiness; `--build` força a imagem | `tools/server-env/up.sh [--build]` |
| Derrubar o `zeny-front`; `-v` remove também os volumes dele | `tools/server-env/down.sh [-v\|--volumes]` |
| Montar o pacote do Windows em `build/demo-package/` | `tools/server-env/package.sh` |
| Gravar as imagens do servidor num `.tar` (padrão: `build/demo-package/images/zeny-server-images.tar`) | `tools/server-env/save-images.sh [OUT]` |
| Rodar os testes (`bash -n`, `shellcheck` se existir, e `tests/test_*.sh`) | `bash tests/run.sh [ARQUIVO...]` |

Primeiro uso do ambiente: rode `tools/server-env/up.sh`; na primeira vez ele cria `.server/` e imprime o comando exato do `gen-env.sh` (com o caminho absoluto). Rode esse comando e depois o `up.sh` de novo. O `save-images.sh` deve rodar depois do `package.sh`, porque o `package.sh` recria `build/demo-package/` e apaga um `images/zeny-server-images.tar` anterior. O `down.sh` sempre atinge só o projeto `zeny-front` e funciona sem `.env`.

### Códigos de saída do `tools/server-drift.sh`

| Código | Significado |
|---|---|
| 0 | igual: base e alvo são o mesmo commit |
| 10 | o servidor avançou, contrato idêntico |
| 11 | o servidor avançou com mudança compatível no contrato (aditivo) |
| 12 | breaking: o oasdiff acusa erro em `/api/v1` |
| 2 | erro de uso ou de ambiente (repositório, REF, Docker, oasdiff) |
| 3 | pin inconsistente: ausente, incompleto, ou `api/openapi.yaml` com hash diferente de `contract_sha256` |

A opção vence a variável de ambiente; variável vazia conta como ausente. Com base explícita (`--base` ou `ZENY_DRIFT_BASE`) o pin não é lido nem conferido.

## Variáveis de ambiente

| Variável | Efeito | Padrão |
|---|---|---|
| `ZENY_SERVER_REPO` | caminho do repositório do servidor | `../ZenyHotelManager`, relativo ao checkout principal do front |
| `ZENY_DRIFT_BASE` | base do `server-drift.sh` (a opção `--base` vence) | commit do pin |
| `ZENY_DRIFT_TARGET` | alvo do `server-drift.sh` (a opção `--target` vence) | `main` do servidor |
| `ZENY_FRONT_ENV_FILE` | caminho do `.env` usado por `up.sh` | `.server/zeny-front.env` no checkout principal |
| `ZENY_UP_TIMEOUT` | segundos que o `up.sh` espera pelo readiness | `600` |
| `OASDIFF_IMAGE` | imagem do oasdiff usada pelo pré-voo (sempre com `--network none`) | `tufin/oasdiff:v1.33.0@sha256:6263a96d…` (fixada no script) |

## Segredos

`.env` com chaves e senhas nunca entra no Git. Só versões `.env.example` com valores vazios ou de exemplo.
