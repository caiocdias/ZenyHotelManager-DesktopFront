# M0 E01 — Fixação do servidor e ambiente isolado — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** prender o front a um commit da `main` do servidor (pin + cópia do contrato), medir a defasagem com um pré-voo só-leitura e subir o servidor fixado num ambiente compose isolado (`zeny-front`), no notebook ou no Windows por um pacote com `LEIA-ME`.

**Architecture:** scripts bash em `tools/`, com uma biblioteca comum (`tools/lib/common.sh`) que resolve o repositório do servidor e lê o pin, e só admite subcomandos git de leitura contra ele. `tools/server-pin.sh` grava o pin; `tools/server-drift.sh` compara pin × `main` com o oasdiff por Docker; `tools/server-env/` monta a árvore do commit fixado por `git archive` em `.server/<commit>/`, renderiza um override de compose e sobe `docker compose -p zeny-front`. O pacote do Windows reaproveita a mesma árvore, o mesmo override e o mesmo gerador de `.env`.

**Tech Stack:** bash 5, git, Docker Engine 29 + Compose v2.40 (notebook), Docker Desktop (Windows), oasdiff `tufin/oasdiff:v1.33.0` por Docker, openssl, jq (só nos testes). Testes em bash puro (`tests/run.sh`), sem bats nem shellcheck instalados.

**Spec:** `docs/roadmaps/roadmap-m0-sincronizacao.md`, seção "E01 — Fixação do servidor e ambiente isolado (rodada 1)", mais `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §5, §6.4, §7.3, §8 e os ADRs `docs/adr/0002-servidor-fixado-e-contrato-copiado.md`, `docs/adr/0003-ambiente-do-servidor-isolado.md` e `docs/adr/0005-pedidos-ao-servidor-pelo-brain.md`.

## Global Constraints

- O repositório do servidor é **só leitura**: `git -C … rev-parse`, `git -C … show <commit>:<arquivo>`, `git -C … archive`. Nada de `worktree add`, `checkout`, `fetch` ou arquivo novo lá.
- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela.
- O ambiente do front usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` e a porta 8088 por padrão. Ele nunca usa o projeto `zeny` nem a imagem `:dev`. `down` e `down -v` só atingem `zeny-front`.
- Segredos (`.env` com chaves, senhas do admin) nunca entram no Git. Só versões `.env.example` com valores vazios ou de exemplo.
- Scripts em bash, sem dependência de Node. O que roda no Windows é só o `LEIA-ME` em PowerShell.
- Documentação em pt-BR. Mensagens dos scripts em pt-BR; nomes de funções e variáveis em inglês.
- **Commit curto** = os 12 primeiros caracteres do SHA completo (determinístico; não `git rev-parse --short`).
- `ZENY_SERVER_REPO` tem padrão `../ZenyHotelManager` **relativo ao checkout principal do front** (o diretório pai de `git rev-parse --path-format=absolute --git-common-dir`), para valer também dentro de worktrees em `.claude/worktrees/`.
- Imagem do oasdiff: `tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c` (a mesma do `.github/scripts/check-api-breaking.sh` do servidor), sobrescrevível por `OASDIFF_IMAGE`, sempre com `--network none`.
- **Estado de máquina fica no checkout principal do front** (`front_main_checkout`), nunca no worktree, porque o projeto `zeny-front` e os volumes dele são da máquina inteira e o worktree é removido no fim do lote: a árvore extraída (`.server/<commit>/`), o override renderizado (`.server/<commit>.override.yaml`, fora da árvore extraída), o `.env` real (`.server/zeny-front.env`) e o pacote (`build/demo-package/`). Os scripts e o pin vêm do worktree em que o script está (`front_root`).
- **Estado de máquina se autoignora**, sem depender do `.gitignore` versionado (que só chega ao checkout principal no merge): quem cria `.server/` ou `build/` grava neles um `.gitignore` com `*` se ainda não existir. `git status --porcelain` do checkout principal fica vazio depois de qualquer script. Nunca use `git clean` no checkout principal: ele apagaria o `.env` com as chaves presas ao volume.
- REF vindo do usuário (opção ou variável) é resolvido uma vez com `rev-parse --verify --end-of-options "REF^{commit}"`; REF que começa com `-` é recusado (exit 2); depois disso só SHAs vão a outros subcomandos.
- Testes nunca apontam para o servidor real, exceto os casos marcados "real", que só leem e se pulam por caso (`skip_case`) quando o repositório ou o pin real não existem. Testes que escrevem (`api/`, `.server/`, `build/`, `.env`) rodam numa cópia temporária do front (`make_fake_front`), nunca no worktree.
- `tests/lib/fixtures.sh` e `tests/lib/assert.sh` ficam congelados depois da Task 1: tasks seguintes não os editam (helpers próprios ficam no arquivo de teste da task).
- Bibliotecas (`tools/lib/common.sh`, `tools/server-env/lib.sh`) não têm bit de execução; scripts de entrada têm.
- Cada task roda, durante o trabalho, `bash tests/run.sh tests/test_<sua>.sh` (só o próprio teste mais o `bash -n` de todos os scripts) e, antes do commit, a suíte inteira `bash tests/run.sh`. Commits com `git add` por caminho explícito, nunca `git add -A`.
- Gate desta etapa: `bash tests/run.sh` verde (inclui `bash -n` de todo `*.sh` e `shellcheck` se estiver instalado). Não instale ferramentas.

## Review Focus

1. **Rodar de dentro de um worktree** (`.claude/worktrees/…`): o padrão do repositório do servidor tem de achar `/home/caio/repos/ZenyHotelManager`, não `.claude/worktrees/ZenyHotelManager`. Teste em Task 1.
2. **Cópia local adulterada ou pin corrompido** (alguém edita `api/openapi.yaml` à mão, ou falta `contract_sha256`): o pré-voo sai com o código de inconsistência, nunca com "igual". Teste em Task 3.
3. **Falha do Docker ou do oasdiff** (daemon parado, imagem ausente sem internet, exit 125): o pré-voo sai com erro de ambiente, nunca classifica como breaking nem como aditivo. Teste em Task 3.
4. **`gen-env.sh` rodado de novo sobre um `.env` existente**: recusa sem `--force` e não toca no arquivo — perder `ZENY_ENCRYPTION_KEYS` torna ilegível o que foi cifrado no volume. Teste em Task 4.
5. **`down.sh -v` com o projeto `zeny` do servidor no ar**: só o projeto `zeny-front` aparece em qualquer chamada ao Docker. Teste em Task 6.
6. **Scripts rodados de um worktree que depois some**: o `.env` e a árvore extraída têm de sobreviver à remoção do worktree (estão no checkout principal). Teste em Task 5.

## Decisões deste plano que divergem do texto do roadmap ou do ADR

O orquestrador registra o desfecho no roadmap ("Hipóteses e decisões em aberto" e handoff da E01) no fechamento da etapa.

| # | Decisão | Texto original | Por quê | Alternativas descartadas |
|---|---|---|---|---|
| D1 (H1) | O override **mantém** o `build:` do `api` herdado do compose do servidor | roadmap: "o override sem `build`"; H1 cita `!reset` | ADR 0003 e design §6.4 constroem a imagem no Windows (`compose … build`); com a imagem presente (build anterior ou `docker load`), `up -d` não reconstrói | `build: !reset null` (quebra o build no Windows); cópia editada do compose (diverge do commit fixado) |
| D2 | `ZENY_HTTP_ADDRESS` tem padrão `127.0.0.1` no override do notebook e `0.0.0.0` no override do pacote (marcador `@DEFAULT_ADDRESS@`); o `.env` pode sobrescrever | ADR 0003: override com `ZENY_HTTP_ADDRESS=0.0.0.0` | o notebook não deve abrir a porta para a rede por padrão; o pacote do Windows segue o ADR | endereço fixo em `0.0.0.0` nos dois (expõe o notebook) |
| D3 (H4) | `.env` gerado por `gen-env.sh` (bash + openssl); no Windows, dentro de `alpine:3.22` com `apk add bash openssl` | H4: geração por script, senão manual | um único gerador, igual ao README do servidor; o Docker já é obrigatório no Windows | PowerShell próprio (segunda implementação); geração manual |
| D4 (H3) | pré-voo por oasdiff no Docker, com código 10 separado ("avançou, contrato idêntico") | roadmap: igual, aditivo, breaking | o critério pede "igual" logo após a fixação, e o avanço sem contrato novo ainda pode mudar `docker/` | só hash (sem classificar) |
| D5 | estado de máquina no checkout principal (ver Global Constraints) | roadmap: `.server/<commit>/` sem dizer a raiz | worktrees são removidos no fim do lote | worktree (perde `.env` e bind mounts); diretório XDG (foge do texto do roadmap) |

---

## Mapa de arquivos

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `.gitignore` | ignora `.server/`, `build/`, `.env`, Gradle/IDE, `.claude/worktrees/` | 1 |
| `tools/lib/common.sh` | `die`, raiz do front, repositório do servidor, git só-leitura, leitura do pin | 1 |
| `tests/run.sh`, `tests/lib/assert.sh`, `tests/lib/fixtures.sh` | runner, asserções, servidor e front falsos, stub de `docker`/`curl` | 1 |
| `tools/server-pin.sh`, `api/server-pin.properties`, `api/openapi.yaml` | fixa o commit e copia o contrato | 2 |
| `tools/server-drift.sh` | pré-voo pin × alvo | 3 |
| `tools/server-env/.env.example`, `tools/server-env/gen-env.sh` | modelo do `.env` e gerador de segredos | 4 |
| `tools/server-env/lib.sh`, `tools/server-env/compose.override.yaml` | árvore do commit, override, comando compose | 5 |
| `tools/server-env/up.sh`, `tools/server-env/down.sh` | sobe e derruba `zeny-front` | 6 |
| `tools/server-env/package.sh`, `tools/server-env/save-images.sh`, `tools/server-env/LEIA-ME.md` | pacote do Windows | 7 |
| `README.md`, `docs/pedidos-servidor.md` | entrada do repositório e controle de pedidos | 8 |

Testes: um `tests/test_<script>.sh` por task.

## Ondas

| Onda | Tasks | Por quê |
|---|---|---|
| 1 | 1 | base de todas |
| 2 | 2, 3, 5 | dependem só da 1; arquivos disjuntos; cada task num worktree próprio (`<branch>-tN`), fixtures congeladas na Task 1 |
| 3 | 4, 6 | 4 lê o pin real (2); 6 usa a lib (5) |
| 4 | 7 | usa 4, 5 e 6 |
| 5 | 8 | documenta os comandos de todas |

---

### Task 1: Base do repositório, biblioteca comum e harness de testes

Estimativa: 45 min · Depende de: nenhuma · Risco: alto (é o guarda de só-leitura do servidor)

**Files:**
- Create: `.gitignore`, `tools/lib/common.sh`
- Test: `tests/run.sh`, `tests/lib/assert.sh`, `tests/lib/fixtures.sh`, `tests/test_common.sh`

**Interfaces:**
- Produces (`tools/lib/common.sh`, para `source`; não executa nada ao ser carregado):
  - `die CODE MSG...` — imprime `erro: MSG` no stderr e sai com CODE.
  - `front_root` — raiz do worktree do front em que o script está (`git -C "<dir do script>" rev-parse --show-toplevel`), independente do cwd.
  - `front_main_checkout` — diretório pai de `git rev-parse --path-format=absolute --git-common-dir`.
  - `server_repo` — `$ZENY_SERVER_REPO` ou `$(front_main_checkout)/../ZenyHotelManager`, normalizado para caminho absoluto; `die 2` se não for repositório git.
  - `srv_git ARGS...` — `git -C "$(server_repo)" ARGS...`, aceitando só os subcomandos `rev-parse cat-file show archive diff`; qualquer outro → `die 2 "subcomando git não permitido no servidor: X"`.
  - `resolve_server_ref REF` — recusa REF vazio ou iniciado por `-` (`die 2`), devolve o SHA de 40 por `srv_git rev-parse --verify --end-of-options "REF^{commit}"` ou `die 2 "commit não encontrado no servidor: REF"`.
  - `pin_file` — `$(front_root)/api/server-pin.properties`.
  - `pin_get KEY` — valor de `KEY=` no pin; `die 3` se o arquivo ou a chave faltar.
  - `short_commit SHA` — os 12 primeiros caracteres.
- Produces (`tests/lib/fixtures.sh`):
  - `make_fake_server DIR` — repositório git com `api/openapi.yaml`, `docker/compose.yaml`, `docker/.env.example` e os commits, exportados como `FAKE_C1`..`FAKE_C4`, `main` em `FAKE_C4`:
    - C1: contrato base (OpenAPI 3.0.1, `GET /api/v1/meta/version` com resposta `{version: string}`), compose mínimo e `.env.example`;
    - C2: só muda `README.md` (contrato idêntico);
    - C3: acrescenta `GET /api/v1/things` (aditivo);
    - C4: remove `GET /api/v1/meta/version` (breaking).
    O compose falso imita o do servidor: `name: zeny`; `api` com `build: {context: .., dockerfile: docker/Dockerfile}` e `image: zeny-hotel-manager:dev`; `db` com `image: postgres:18`; `storage` e `storage-init` com `image: pgsty/minio:RELEASE.2026-08-04T00-00-00Z`; `proxy` com `image: caddy:2.11-alpine` e `ports: ["${ZENY_HTTP_ADDRESS:-127.0.0.1}:${ZENY_HTTP_PORT:-80}:80"]`; `api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT: ${ZENY_STORAGE_PUBLIC_ENDPOINT:-http://localhost}` e uma variável obrigatória `ZENY_JWT_KEYS: ${ZENY_JWT_KEYS:?defina}`.
  - `make_fake_front DIR` — repositório git temporário com cópia de `tools/` do front (preservando o bit de execução) e de `api/` se existir, para testes que escrevem.
  - `write_fake_pin FRONT SERVER COMMIT` — escreve em `FRONT/api/` o `openapi.yaml` do COMMIT do servidor falso e um `server-pin.properties` coerente (commit, sha256 da cópia, `synced_at` fixo `2026-11-10T00:00:00Z`), no formato da Task 2.
  - `write_test_env FILE [CHAVE=VALOR...]` — env file de teste com valores fictícios para todas as variáveis obrigatórias do compose falso e do real (`ZENY_DB_USERNAME`, `ZENY_DB_PASSWORD`, `ZENY_DB_MIGRATION_USERNAME`, `ZENY_DB_MIGRATION_PASSWORD`, `ZENY_ENCRYPTION_KEYS`, `ZENY_ENCRYPTION_ACTIVE_KEY_ID`, `ZENY_JWT_KEYS`, `ZENY_JWT_ACTIVE_KEY_ID`, `ZENY_STORAGE_ROOT_USER`, `ZENY_STORAGE_ROOT_PASSWORD`, `ZENY_STORAGE_ACCESS_KEY`, `ZENY_STORAGE_SECRET_KEY`), mais as linhas extras passadas.
  - `install_docker_stub DIR` — põe em `DIR/bin` um `docker` e um `curl` falsos que acrescentam uma linha `cwd=<pwd> <argumentos>` em `DIR/docker.log` / `DIR/curl.log`; comportamento por variáveis: `STUB_IMAGE_PRESENT=0|1` (`docker image inspect`), `STUB_DOCKER_EXIT` (código geral), `STUB_CURL_BODY` e `STUB_CURL_EXIT`. Imprime o `PATH` a usar.
- Produces (`tests/lib/assert.sh`): `assert_eq EXP ACT MSG`, `assert_contains HAYSTACK NEEDLE MSG`, `assert_not_contains HAYSTACK NEEDLE MSG`, `assert_exit EXP_CODE MSG -- CMD...`, `assert_file PATH MSG`, `skip_case MSG`; cada falha imprime `FALHOU: MSG` e incrementa um contador; `skip_case` imprime `PULADO: MSG` e incrementa outro; `finish` sai 1 se houve falha (mesmo com casos pulados), senão 77 se houve caso pulado, senão 0.
- Produces (`tests/run.sh [ARQUIVO...]`): roda `bash -n` em todo `tools/**/*.sh` e `tests/**/*.sh`, `shellcheck` neles se existir no PATH, e cada teste pedido (padrão: todos os `tests/test_*.sh`) em processo próprio; imprime `PASS`/`FAIL`/`SKIP` por arquivo, repete as linhas `PULADO:` de cada arquivo e termina com `Resumo: N arquivos, F falhas, S com casos pulados`; sai 1 com falha.

- [ ] **Step 1: Escrever `tests/lib/assert.sh`, `tests/lib/fixtures.sh`, `tests/run.sh` e o teste falhando `tests/test_common.sh`**, com os casos:
  - `server_repo` sem `ZENY_SERVER_REPO`, chamado de um worktree criado por `git worktree add` dentro de um front falso em `$tmp/front`, resolve `$tmp/ZenyHotelManager` (crie o servidor falso lá);
  - `server_repo` com `ZENY_SERVER_REPO` apontando para diretório que não é git → exit 2;
  - `ZENY_SERVER_REPO` com espaço no caminho funciona;
  - `srv_git rev-parse main` devolve `$FAKE_C4`; `srv_git checkout main`, `srv_git fetch`, `srv_git log` e `srv_git worktree add x` → exit 2, e `git -C <servidor falso> status --porcelain` e `worktree list` iguais antes e depois;
  - `resolve_server_ref main` → `$FAKE_C4`; `resolve_server_ref --output=x` e `resolve_server_ref ''` → exit 2 sem criar `x`; `resolve_server_ref nao-existe` → exit 2;
  - `skip_case` + `finish` sai 77; falha + `skip_case` sai 1 (num script de teste auxiliar dentro do próprio teste);
  - `write_fake_pin` gera pin cujo `contract_sha256` confere com a cópia;
  - `pin_get commit` lê de um pin falso; chave ausente → exit 3; arquivo ausente → exit 3;
  - `short_commit` de um SHA de 40 caracteres tem 12.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_common.sh` (common.sh não existe).
- [ ] **Step 3: Implementar `tools/lib/common.sh` e `.gitignore`.** `.gitignore`: `.server/`, `build/`, `.env`, `.gradle/`, `.kotlin/`, `.idea/`, `*.iml`, `out/`, `local.properties`, `.claude/worktrees/`.
- [ ] **Step 4:** `bash tests/run.sh` → `Resumo: 1 arquivos, 0 falhas, 0 com casos pulados`.
- [ ] **Step 5: Commit.**

### Task 2: Fixação do commit e cópia do contrato

Estimativa: 30 min · Depende de: Task 1 · Risco: alto (contrato e invariante byte a byte)

**Files:**
- Create: `tools/server-pin.sh`, `api/server-pin.properties`, `api/openapi.yaml` (gerados pelo script, no Step 5)
- Test: `tests/test_server_pin.sh`

**Interfaces:**
- Consumes: `tools/lib/common.sh` (Task 1), `make_fake_server`, `make_fake_front`.
- Produces: `tools/server-pin.sh [REF]` (padrão `main`). Resolve REF por `resolve_server_ref`, copia `srv_git cat-file blob <commit>:api/openapi.yaml` para `api/openapi.yaml` e escreve `api/server-pin.properties`, ambos por arquivo temporário + `mv` no mesmo diretório, nesta forma exata (três linhas, nesta ordem):
  ```
  commit=<sha de 40>
  contract_sha256=<sha256 hex do api/openapi.yaml>
  synced_at=<UTC, formato 2026-11-10T20:15:00Z>
  ```
  Imprime `Servidor fixado em <commit curto> (contrato sha256 <12 primeiros do hash>)`. Saídas: 0 ok; 2 REF inexistente ou sem `api/openapi.yaml` (nada é alterado no front).

- [ ] **Step 1: Teste falhando `tests/test_server_pin.sh`** (em front falso + servidor falso):
  - sem argumento fixa `FAKE_C4`; `commit=` é o SHA completo;
  - `cmp` entre `api/openapi.yaml` e `git -C <servidor> cat-file blob $FAKE_C4:api/openapi.yaml` → idênticos; `sha256sum api/openapi.yaml` = `contract_sha256`;
  - com argumento `$FAKE_C1` fixa C1;
  - `synced_at` casa `^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$`;
  - REF inexistente → exit 2 e pin anterior intacto (`cmp` com cópia de antes);
  - contrato com CRLF e sem newline final (commit extra no servidor falso, feito no próprio teste) sai byte a byte igual;
  - `status --porcelain` e `worktree list` do servidor falso iguais antes e depois.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_server_pin.sh`.
- [ ] **Step 3: Implementar `tools/server-pin.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Fixar o servidor real.** Antes, guarde `git -C /home/caio/repos/ZenyHotelManager status --porcelain` e `worktree list`; rode `tools/server-pin.sh` (padrão `main`, sem `ZENY_SERVER_REPO`); confira `sha256sum api/openapi.yaml` = `contract_sha256` = `git -C /home/caio/repos/ZenyHotelManager show <commit>:api/openapi.yaml | sha256sum`, e o snapshot do servidor igual. Cole as saídas no relatório.
- [ ] **Step 6: Commit** (script, teste e os dois arquivos de `api/`).

### Task 3: Pré-voo de defasagem

Estimativa: 45 min · Depende de: Task 1 (formato do pin está na Task 2) · Risco: alto (entrada externa, classificação de contrato)

**Files:**
- Create: `tools/server-drift.sh`
- Test: `tests/test_server_drift.sh`

**Interfaces:**
- Consumes: `tools/lib/common.sh`; formato do pin da Task 2 (o teste cria pins falsos nesse formato).
- Produces: `tools/server-drift.sh [--base REF] [--target REF]`, com `ZENY_DRIFT_BASE` e `ZENY_DRIFT_TARGET` como alternativas às opções. Base padrão = `commit` do pin; alvo padrão = `main` do servidor. Códigos de saída (documentados no cabeçalho do script e no `--help`):

  | Código | Significado |
  |---|---|
  | 0 | igual: base e alvo são o mesmo commit |
  | 10 | o servidor avançou, contrato idêntico |
  | 11 | o servidor avançou com mudança compatível no contrato (aditivo; oasdiff sem `ERR` em `/api/v1`) |
  | 12 | breaking: oasdiff com `ERR` em `/api/v1` |
  | 2 | erro de uso ou de ambiente (repositório, REF, Docker, oasdiff) |
  | 3 | pin inconsistente: pin ausente ou incompleto, ou `api/openapi.yaml` local com hash diferente de `contract_sha256` (só verificado quando a base é a do pin) |

  Com `--base` explícito, o pin não é lido nem conferido: pin ausente não é erro.

- Comportamento:
  - a primeira linha da saída é `Pré-voo: base <curto> × alvo <curto> (<REF do alvo>)`; a última é `Resultado: <igual|avançou sem mudança no contrato|aditivo|breaking> (código N)`;
  - quando base ≠ alvo, lista os arquivos alterados em `docker/` entre os dois (`srv_git diff --name-only base alvo -- docker/`) sob `Ambiente do servidor alterado:` (ou `Ambiente do servidor sem mudanças.`), para a próxima rodada revisar `tools/server-env/`;
  - REFs resolvidos por `resolve_server_ref`;
  - antes do oasdiff, `docker image inspect "$OASDIFF_IMAGE"` (sem pull); falhou → código 2 com `erro: imagem do oasdiff indisponível (…); com internet: docker pull "<imagem>"`, para o daemon parado ou a imagem ausente nunca chegarem à classificação;
  - oasdiff como no script do servidor: os dois contratos em diretório temporário (`mktemp -d`, removido por `trap`, com `chmod -R a+rX` antes do `docker run`, como na linha 48 do script do servidor), `docker run --rm --network none -v "<tmp>:/specs:ro" -w /specs "$OASDIFF_IMAGE" breaking base/api/openapi.yaml api/openapi.yaml --match-path '^/api/v1(/|$)' --fail-on ERR --allow-external-refs=false --format text --lang pt-br`; exit 0 → aditivo, exit 1 → breaking, qualquer outro → código 2 com `erro: oasdiff falhou (exit N)`;
  - só leitura: toda chamada ao servidor passa por `srv_git`.

- [ ] **Step 1: Teste falhando `tests/test_server_drift.sh`** (front falso com pin de `FAKE_C1` gerado no próprio teste, servidor falso):
  - `--target $FAKE_C1` → 0, saída contém `Resultado: igual (código 0)`;
  - `--target $FAKE_C2` → 10;
  - `--target $FAKE_C3` → 11;
  - `--target $FAKE_C4` → 12; sem `--target` (main = C4) → 12;
  - `ZENY_DRIFT_BASE=$FAKE_C3 ZENY_DRIFT_TARGET=$FAKE_C4` → 12 e `--base $FAKE_C1 --target $FAKE_C3` → 11 (comparação entre dois commits anteriores ao alvo, critério do roadmap);
  - `api/openapi.yaml` local com um byte alterado → 3; pin sem `contract_sha256` → 3; sem pin → 3;
  - `--base $FAKE_C1 --target $FAKE_C3` sem pin no front → 11 (pin não é exigido com `--base`);
  - `--target nao-existe` → 2; `--target --output=x` → 2 e `x` não existe;
  - com o stub de `docker` e alvo C3: `STUB_DOCKER_EXIT=125` → 2; `STUB_DOCKER_EXIT=1` (falha já na checagem da imagem) → 2; nunca 11 nem 12;
  - **real** com spec malformada: commit extra no servidor falso, feito no próprio teste, com `api/openapi.yaml` que não é YAML válido, como alvo → 2;
  - snapshot do servidor falso igual antes e depois.
  Os casos 11, 12 e o da spec malformada usam o Docker real com a imagem do oasdiff; se `docker image inspect "$OASDIFF_IMAGE"` falhar, cada um vira `skip_case` e o resto roda. Não baixe a imagem.
- [ ] **Step 2:** `bash tests/run.sh tests/test_server_drift.sh` → FAIL.
- [ ] **Step 3: Implementar `tools/server-drift.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas, sem casos pulados neste arquivo (a imagem `tufin/oasdiff` com o digest acima está no cache local desta máquina, conferido pelo orquestrador).
- [ ] **Step 5: Commit.**

### Task 4: Modelo do `.env` e gerador de segredos

Estimativa: 35 min · Depende de: Task 1, Task 2 · Risco: alto (segredos)

**Files:**
- Create: `tools/server-env/.env.example`, `tools/server-env/gen-env.sh`
- Test: `tests/test_gen_env.sh`

**Interfaces:**
- Consumes: só o teste usa `tools/lib/common.sh` e o pin real da Task 2 (caso "real"). **O `gen-env.sh` é autocontido**: não carrega `common.sh`, não chama git e acha o modelo pelo próprio diretório (`$(dirname "$0")/.env.example`), porque roda no pacote do Windows, dentro do Alpine, fora de qualquer repositório.
- Produces:
  - `tools/server-env/.env.example`: as mesmas variáveis, na mesma ordem, do `docker/.env.example` do servidor no commit fixado, com os comentários adaptados ao front, e estas diferenças: `ZENY_HTTP_PORT=8088`; `ZENY_HTTP_ADDRESS=` **vazio** (o padrão vem do override: `127.0.0.1` no notebook, `0.0.0.0` no pacote; o `:-` do compose trata vazio como ausente) e `ZENY_STORAGE_PUBLIC_ENDPOINT=` **vazio** (padrão `http://localhost:<ZENY_HTTP_PORT>` pelo override), cada um com comentário dizendo de onde vem o padrão; nota sobre `ZENY_HTTP_ADDRESS=0.0.0.0` (rede, mobile) e `ZENY_STORAGE_PUBLIC_ENDPOINT=http://<IP>:8088` (o endereço que o app enxerga, senão as URLs assinadas não abrem). Segredos vazios: `ZENY_ENCRYPTION_KEYS`, `ZENY_JWT_KEYS`, `ZENY_ADMIN_PASSWORD`, `ZENY_STORAGE_ROOT_PASSWORD`, `ZENY_STORAGE_SECRET_KEY`, `ZENY_FCM_CREDENTIALS`.
  - `tools/server-env/gen-env.sh [--force] [--address ADDR] [--public-endpoint URL] DEST`: copia o `.env.example` vizinho para DEST (criado com `umask 077`, modo 600) preenchendo os cinco primeiros segredos acima, com os geradores do README do servidor:
    - `ZENY_ENCRYPTION_KEYS=dev:$(openssl rand -base64 32)`;
    - `ZENY_JWT_KEYS=dev:<RSA 3072, PKCS#8 DER, base64 numa linha>` (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n'`);
    - `ZENY_ADMIN_PASSWORD` = `openssl rand -base64 18`; `ZENY_STORAGE_ROOT_PASSWORD` e `ZENY_STORAGE_SECRET_KEY` = `openssl rand -base64 24`.
    `--address` define `ZENY_HTTP_ADDRESS`; `--public-endpoint` define `ZENY_STORAGE_PUBLIC_ENDPOINT`. A troca de linha é por chave inteira (`^CHAVE=`), sem `sed` com o valor no padrão (base64 tem `/` e `+`). DEST existente sem `--force` → exit 2 com DEST intacto. Falha do `chmod 600` (bind mount do Windows, sem permissões POSIX) vira aviso no stderr, não erro. Nunca imprime valor de segredo; imprime só `Gerado DEST (segredos: <lista de chaves>)`. Usa só `bash`, `openssl`, `base64`, `tr`, `awk`, `mktemp`, `chmod`, `mv` (roda no Alpine, pelo `LEIA-ME`).

- [ ] **Step 1: Teste falhando `tests/test_gen_env.sh`:**
  - gera em `$tmp/.env`: os cinco segredos não vazios; modo `600`; demais linhas iguais ao modelo;
  - `ZENY_JWT_KEYS` sem o prefixo `dev:` decodifica (`base64 -d`) e `openssl pkey -inform DER -noout -text` mostra 3072 bits;
  - `ZENY_ENCRYPTION_KEYS` decodifica para 32 bytes;
  - duas gerações dão segredos diferentes;
  - segunda geração sem `--force` → exit 2 e arquivo idêntico ao primeiro (`cmp`); com `--force` → 0;
  - `--address 0.0.0.0 --public-endpoint http://192.168.0.20:8088` grava as duas linhas;
  - stdout+stderr não contêm nenhum dos valores gerados;
  - `.env.example` do front: `ZENY_HTTP_PORT=8088`, `ZENY_HTTP_ADDRESS=` e `ZENY_STORAGE_PUBLIC_ENDPOINT=` vazios, os seis segredos vazios;
  - `gen-env.sh` copiado com o `.env.example` para um diretório temporário fora de qualquer repositório git, rodado de lá com `PATH` sem `git` → exit 0 e `.env` gerado;
  - **real** (`skip_case` se o servidor ou o pin não existirem): a sequência de nomes de variáveis do `.env.example` do front, na ordem do arquivo, é igual à do `docker/.env.example` do servidor no commit do pin (`srv_git show <commit>:docker/.env.example`).
- [ ] **Step 2:** `bash tests/run.sh tests/test_gen_env.sh` → FAIL.
- [ ] **Step 3: Escrever `.env.example` e implementar `gen-env.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas, caso real executado (sem casos pulados neste arquivo).
- [ ] **Step 5: Commit.** Confira antes que `git status --porcelain` não lista nenhum `.env`.

### Task 5: Árvore do commit fixado, override e comando compose

Estimativa: 40 min · Depende de: Task 1 · Risco: alto (isolamento do ambiente do servidor)

**Files:**
- Create: `tools/server-env/lib.sh`, `tools/server-env/compose.override.yaml`
- Test: `tests/test_server_env_lib.sh`

**Interfaces:**
- Consumes: `tools/lib/common.sh`.
- Produces (`tools/server-env/lib.sh`, para `source`; carrega `common.sh`):
  - `readonly ZENY_FRONT_PROJECT=zeny-front` (não sobrescrevível por ambiente).
  - `server_tag` — `short_commit "$(pin_get commit)"`.
  - `server_tree_dir` — `$(front_main_checkout)/.server/<commit completo>` (estado de máquina, D5).
  - `override_file` — `$(front_main_checkout)/.server/<commit completo>.override.yaml` (fora da árvore extraída, que fica intocada depois de extraída).
  - `ensure_state_dir DIR` — cria DIR (`.server/` ou `build/` do checkout principal) e, se não houver, grava `DIR/.gitignore` com `*`.
  - `ensure_server_tree` — chama `ensure_state_dir` para `.server/`; se `server_tree_dir` não existe, extrai `srv_git archive --format=tar <commit>` num temporário irmão e renomeia (nunca deixa árvore parcial); depois confere `sha256sum <árvore>/api/openapi.yaml` = `contract_sha256` (senão `die 3`). Imprime o caminho.
  - `render_override DEST DEFAULT_ADDRESS` — copia `compose.override.yaml` trocando `@SERVER_TAG@` por `server_tag` e `@DEFAULT_ADDRESS@` por DEFAULT_ADDRESS (`127.0.0.1` no notebook; o pacote usa `0.0.0.0`, D2).
  - `env_file` — `${ZENY_FRONT_ENV_FILE:-$(front_main_checkout)/.server/zeny-front.env}`.
  - `env_get KEY [DEFAULT]` — valor de `^KEY=` no `env_file` lido com `awk` (sem `source`), ou DEFAULT.
  - `compose ARGS...` — `docker compose -p "$ZENY_FRONT_PROJECT" -f <árvore>/docker/compose.yaml -f "$(override_file)" --env-file "$(env_file)" ARGS...`, garantindo `ensure_server_tree` e `render_override "$(override_file)" 127.0.0.1` antes.
- Produces (`tools/server-env/compose.override.yaml`, modelo com marcadores):
  ```yaml
  services:
    api:
      image: zeny-hotel-manager:@SERVER_TAG@
      environment:
        ZENY_STORAGE_PUBLIC_ENDPOINT: ${ZENY_STORAGE_PUBLIC_ENDPOINT:-http://localhost:${ZENY_HTTP_PORT:-8088}}
    proxy:
      ports: !override
        - "${ZENY_HTTP_ADDRESS:-@DEFAULT_ADDRESS@}:${ZENY_HTTP_PORT:-8088}:80"
  ```
  O `build:` do `api` fica herdado do compose do servidor (D1): o Windows constrói a imagem (ADR 0003) e, com a imagem já presente (carregada por `docker load`), o `up` não reconstrói. `!override` exige Compose v2.24.4 ou mais novo.

- [ ] **Step 1: Teste falhando `tests/test_server_env_lib.sh`** (front falso com `write_fake_pin` de `FAKE_C1`, servidor falso, `write_test_env`):
  - `ensure_server_tree` cria `.server/<FAKE_C1>/docker/compose.yaml` na raiz do front falso; segunda chamada não reextrai (mtime igual);
  - chamado de um worktree do front falso (`git worktree add`), `server_tree_dir`, `override_file` e `env_file` apontam para o checkout principal do front falso; depois de `git worktree remove`, a árvore e o `.env` continuam lá;
  - num front falso **sem** `.gitignore`, depois de `ensure_server_tree`, `render_override` e de gravar `.server/zeny-front.env`, `git status --porcelain` do front falso fica vazio; um `.server/.gitignore` já existente com outro conteúdo não é sobrescrito;
  - árvore com `api/openapi.yaml` alterado → `die 3`;
  - `env_get ZENY_HTTP_PORT 8088` lê `9099` de um env file com `ZENY_HTTP_PORT=9099` e uma linha `$(touch pwned)` que não é executada;
  - `compose config --format json` (Docker real; `skip_case` se `docker compose version` falhar): `.name == "zeny-front"`; `.services.api.image == "zeny-hotel-manager:<12 de FAKE_C1>"`; `.services.proxy.ports` com um único item, `published == "8088"` e `host_ip == "127.0.0.1"`; com `ZENY_HTTP_ADDRESS=0.0.0.0` no env file, `host_ip == "0.0.0.0"`; sem `ZENY_STORAGE_PUBLIC_ENDPOINT` no env file, `.services.api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT == "http://localhost:8088"`, e com `ZENY_HTTP_PORT=9099`, `"http://localhost:9099"`;
  - `render_override X 0.0.0.0` e `compose config` com esse override sem `ZENY_HTTP_ADDRESS` no env file → `host_ip == "0.0.0.0"`;
  - `ZENY_FRONT_PROJECT=zeny` no ambiente antes do `source` não muda o projeto (`.name` continua `zeny-front`);
  - **real** (`skip_case` sem o servidor ou sem o pin real em `api/` do worktree): com a árvore do commit do pin real extraída num front falso (pin copiado do worktree), o mesmo `compose config` vale para o `docker/compose.yaml` verdadeiro, e nenhuma imagem do resultado termina em `:dev`. Na onda 2 este caso pula (o pin real chega pela Task 2); o orquestrador o exige executado na suíte da branch da etapa.
- [ ] **Step 2:** `bash tests/run.sh tests/test_server_env_lib.sh` → FAIL.
- [ ] **Step 3: Implementar `lib.sh` e o override.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Commit.**

### Task 6: Subir e derrubar `zeny-front`

Estimativa: 40 min · Depende de: Task 5 · Risco: alto (isolamento; outro orquestrador usa o Docker desta máquina)

**Files:**
- Create: `tools/server-env/up.sh`, `tools/server-env/down.sh`
- Test: `tests/test_up_down.sh`

**Interfaces:**
- Consumes: `tools/server-env/lib.sh` (Task 5).
- Produces:
  - `tools/server-env/up.sh [--build]`: `die 2` com a instrução `tools/server-env/gen-env.sh <caminho de env_file>` se `env_file` não existir; constrói (`compose build api`) se `docker image inspect zeny-hotel-manager:<tag>` falhar ou com `--build`; `compose up -d`; depois consulta `http://<host>:<porta>/actuator/health/readiness` a cada 5 s até a resposta conter `"status":"UP"` ou passar `ZENY_UP_TIMEOUT` (padrão 600 s) → exit 1 com a última resposta e a dica `docker compose -p zeny-front logs api`. Porta = `env_get ZENY_HTTP_PORT 8088`; host = `127.0.0.1` se `env_get ZENY_HTTP_ADDRESS` for vazio, `127.0.0.1` ou `0.0.0.0`, senão o próprio endereço. Sucesso imprime `zeny-front no ar em http://<host>:<porta> (servidor <tag>)`.
  - `tools/server-env/down.sh [-v|--volumes]`: `compose down` (com `-v` só se pedido). Sem árvore extraída, `docker compose -p zeny-front down [-v]` rodado a partir de um diretório temporário vazio (para nenhum `compose.yaml` do cwd entrar). Imprime o que removeu.
- Regra: nenhuma chamada ao Docker cita outro projeto que não `zeny-front`, nem `zeny-hotel-manager:dev`.

- [ ] **Step 1: Teste falhando `tests/test_up_down.sh`** (front falso com `write_fake_pin` de `FAKE_C1`, servidor falso, `write_test_env`, `install_docker_stub`):
  - sem env file → exit 2 e mensagem cita `gen-env.sh`; nenhuma chamada ao `docker`;
  - `STUB_IMAGE_PRESENT=0` → o log tem `build api` antes de `up -d`; `STUB_IMAGE_PRESENT=1` → sem `build`; `--build` → com `build`;
  - `STUB_CURL_BODY='{"status":"UP"}'` → exit 0 e a URL consultada é `http://127.0.0.1:8088/actuator/health/readiness`; com `ZENY_HTTP_PORT=9099` → porta 9099; com `ZENY_HTTP_ADDRESS=0.0.0.0` → host `127.0.0.1`; com `ZENY_HTTP_ADDRESS=192.168.0.20` → host `192.168.0.20`;
  - `STUB_CURL_BODY='{"status":"DOWN"}'` e `ZENY_UP_TIMEOUT=1` → exit 1;
  - `down.sh` → o log tem `down` sem `-v`; `down.sh -v` → `down -v`; sem árvore extraída, `down.sh` usa `-p zeny-front down` e o cwd registrado pelo stub não é o do front;
  - em todo o `docker.log`: toda linha `compose` contém `-p zeny-front`; nenhuma contém `-p zeny ` nem `:dev`.
- [ ] **Step 2:** `bash tests/run.sh tests/test_up_down.sh` → FAIL.
- [ ] **Step 3: Implementar `up.sh` e `down.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Commit.** A validação real (`up.sh` + readiness no notebook) é do orquestrador, não desta task.

### Task 7: Pacote do Windows, imagens e `LEIA-ME`

Estimativa: 45 min · Depende de: Task 4, Task 5, Task 6 · Risco: alto (exclusão do `.env`, geração de segredos no Windows, rede `0.0.0.0` e firewall, `rm -rf`)

**Files:**
- Create: `tools/server-env/package.sh`, `tools/server-env/save-images.sh`, `tools/server-env/LEIA-ME.md`
- Test: `tests/test_package.sh`

**Interfaces:**
- Consumes: `lib.sh` (`ensure_server_tree`, `render_override`, `server_tag`, `pin_get`), `.env.example` e `gen-env.sh` (Task 4).
- Produces:
  - `tools/server-env/package.sh`: chama `ensure_state_dir` para `build/` e recria `$(front_main_checkout)/build/demo-package/` (apaga só esse diretório, depois de conferir que o caminho a apagar termina em `/build/demo-package` e fica dentro de `front_main_checkout`) com:
    - `server/` = cópia da árvore do commit fixado;
    - `compose.override.yaml` renderizado com `render_override … 0.0.0.0` (D2);
    - `.env.example` e `gen-env.sh`;
    - `server-pin.properties` (cópia do pin);
    - `LEIA-ME.md` renderizado (marcadores `@SERVER_COMMIT@` e `@SERVER_TAG@`);
    - `app/LEIA-ME.txt` dizendo que os JARs do app e do `demo-seed` entram na M1 E07;
    - `images/` vazio, com `LEIA-ME.txt` apontando para `save-images.sh`.
    Nunca copia `.env`. Imprime o caminho e o tamanho.
  - `tools/server-env/save-images.sh [OUT]` (padrão `build/demo-package/images/zeny-server-images.tar`): lista as imagens de `<árvore>/docker/compose.yaml` (linhas `image:`), troca `zeny-hotel-manager:dev` por `zeny-hotel-manager:<tag>`, remove duplicadas, confere cada uma com `docker image inspect` (faltando alguma → exit 2 com a lista do que falta e a dica de rodar `up.sh --build` ou `docker pull`) e grava todas num único `docker save -o OUT`.
  - `tools/server-env/LEIA-ME.md` (modelo, pt-BR, comandos PowerShell, a partir da pasta do pacote; todo compose com `docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env`):
    0. **Pré-requisitos:** Docker Desktop com Compose v2.24.4 ou mais novo (`docker compose version`), por causa do `!override`; JDK 25 a partir da M1.
    1. **Primeira vez (com internet):** gerar o `.env` sem bash local: `docker run --rm -v "${PWD}:/w" -w /w alpine:3.22 sh -c "apk add --no-cache bash openssl >/dev/null && bash gen-env.sh --public-endpoint http://<IP do Windows>:8088 .env"` (o endereço `0.0.0.0` já é o padrão do override do pacote); guardar o `.env` fora da pasta do pacote também (a chave de cifragem e a senha root do MinIO valem enquanto o volume existir); `build`; `up -d`; readiness com `curl.exe -fsS http://localhost:8088/actuator/health/readiness` repetido até `{"status":"UP"}` (o `Invoke-RestMethod` lança exceção no 503 enquanto sobe). **Sem internet** só depois de ter um `.env`: `docker load -i images\zeny-server-images.tar` e `up -d` sem `build`.
    2. **Atualizar:** pacote novo numa pasta nova, copiar o `.env` antigo, comparar os nomes de variáveis com o `.env.example` novo e acrescentar as que faltarem, `build`, `up -d`.
    3. **Recriar a demo:** `down -v`, `up -d` e, a partir da M1, `java -jar app\demo-seed.jar …`.
    4. **Firewall (PowerShell como administrador):** `New-NetFirewallRule -DisplayName "Zeny front 8088" -Direction Inbound -Protocol TCP -LocalPort 8088 -Action Allow -Profile Private -RemoteAddress LocalSubnet`; para remover: `Remove-NetFirewallRule -DisplayName "Zeny front 8088"`.
    Também: o login do admin (`ZENY_ADMIN_LOGIN` e a senha do `.env`, troca obrigatória no primeiro login), o servidor só em rede confiável (HTTP), nunca usar `-p zeny`, e o commit do servidor (`@SERVER_COMMIT@`).

- [ ] **Step 1: Teste falhando `tests/test_package.sh`** (front falso com pin de `FAKE_C1`, servidor falso, stub de docker):
  - `package.sh` cria os itens listados; `server/docker/compose.yaml` igual ao do commit; `LEIA-ME.md` sem marcador `@…@` e contendo o SHA de `FAKE_C1`; `compose.override.yaml` com `zeny-hotel-manager:<tag>`;
  - `compose.override.yaml` do pacote com padrão de endereço `0.0.0.0`;
  - com `.server/zeny-front.env` e um `tools/server-env/.env` presentes no front falso, o pacote não tem nenhum `.env` nem `*.env` (`find` por `.env` e `*.env` vazio);
  - segunda execução apaga arquivo estranho deixado em `build/demo-package/` e não toca em `build/outro/`;
  - `bash <pacote>/gen-env.sh <tmp>/.env` rodado com cwd num diretório temporário fora de qualquer repositório git → exit 0; com esse `.env` (gerado do `.env.example` do pacote, sem `--address`) e o override do pacote, `docker compose -p zeny-front -f <pacote>/server/docker/compose.yaml -f <pacote>/compose.override.yaml --env-file <tmp>/.env config --format json` (Docker real; `skip_case` sem `docker compose`) → `host_ip == "0.0.0.0"` e `ZENY_STORAGE_PUBLIC_ENDPOINT == "http://localhost:8088"`;
  - depois do `package.sh`, `git status --porcelain` do front falso sem `.gitignore` fica vazio (`build/.gitignore` criado);
  - `save-images.sh` com `STUB_IMAGE_PRESENT=1`: o log tem um único `save -o …` com as quatro imagens `zeny-hotel-manager:<tag>`, `postgres:18`, `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, `caddy:2.11-alpine`, sem `:dev`; com `STUB_IMAGE_PRESENT=0` → exit 2 e nenhum `save`.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_package.sh`.
- [ ] **Step 3: Implementar os três arquivos.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas; rode também `tools/server-env/package.sh` (servidor real, só leitura; o pacote sai no checkout principal) e cole `ls -la` do diretório impresso no relatório.
- [ ] **Step 5: Commit.**

### Task 8: README e controle de pedidos ao servidor

Estimativa: 20 min · Depende de: Task 7 · Risco: normal

**Files:**
- Create: `README.md`, `docs/pedidos-servidor.md`
- Test: `tests/test_docs.sh`

**Interfaces:**
- Consumes: os nomes, opções e códigos de saída dos scripts das Tasks 2–7.
- Produces:
  - `README.md` (pt-BR): o que é o projeto (cliente desktop do Zeny Hotel Manager, Compose Multiplatform, ainda sem código do app até a M1); onde estão o design, os ADRs e os roadmaps; o servidor fixado (`api/server-pin.properties`) e a regra de só-leitura; onde fica o estado de máquina (`.server/` com a árvore, o override e o `.env` real `.server/zeny-front.env`, e `build/demo-package/`, no checkout principal mesmo rodando de um worktree, autoignorados; nunca `git clean` lá); tabela "Para quê | Comando" com `tools/server-pin.sh`, `tools/server-drift.sh` (com a tabela de códigos 0/10/11/12/2/3), `tools/server-env/gen-env.sh`, `up.sh`, `down.sh`, `package.sh`, `save-images.sh` e `bash tests/run.sh`; variáveis `ZENY_SERVER_REPO`, `ZENY_DRIFT_BASE`, `ZENY_DRIFT_TARGET`, `ZENY_FRONT_ENV_FILE`, `ZENY_UP_TIMEOUT`, `OASDIFF_IMAGE`.
  - `docs/pedidos-servidor.md`: as regras do design §8 e do ADR 0005 (captura `ZenyHotelManager - pedido do front PF-NN <título>` com motivo, operação, etapa, commit fixado e prioridade; estados `aberto`, `aceito`, `entregue`, `recusado`; etapa dependente `#bloqueada` com desbloqueio "PF-NN entregue e sincronizado pelo M0"; resposta "atende PF-NN"; antes de 17/11 nenhuma etapa depende de pedido) e a tabela vazia `| PF-NN | título | estado | captura | etapa do front | commit de entrega |`.

- [ ] **Step 1: Teste falhando `tests/test_docs.sh`:** todo `tools/**/*.sh` executável aparece no `README.md` pelo caminho relativo; o README contém os seis códigos do pré-voo; `docs/pedidos-servidor.md` contém o cabeçalho exato da tabela acima.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_docs.sh`.
- [ ] **Step 3: Escrever os dois documentos.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Commit.**

---

## Validação da etapa (orquestrador, depois das tasks)

- `bash tests/run.sh` → 0 falhas, e **nenhum caso pulado** nos casos reais de `test_gen_env.sh` e `test_server_env_lib.sh` nem nos casos 11, 12 e spec malformada de `test_server_drift.sh` (a saída lista os `PULADO:`).
- `tools/server-drift.sh` no worktree, servidor real → código 0.
- `tools/server-drift.sh --base <ancestral da main real cujo api/openapi.yaml difere do fixado>` → código 11, com a saída colada no handoff (H3 com o contrato real). O 12 fica demonstrado pelo servidor falso, porque o servidor proíbe breaking em `/api/v1`; o handoff diz isso.
- `tools/server-env/up.sh` + `curl -fsS http://localhost:8088/actuator/health/readiness` → `UP` no notebook só se `MemAvailable` ≥ 6 GiB no momento (leitura registrada no ledger; sem a imagem, o `up.sh` dispara o build do Gradle com heap de 2 GB); senão, registrado e validado no Windows pelo usuário, na parada da etapa. Com `zeny-front` no ar, `docker compose -p zeny ps` e `docker images zeny-hotel-manager` comparados ao snapshot inicial; depois `down.sh -v` e conferência de que só sumiram recursos `zeny-front`.
- `git status --porcelain` do checkout principal vazio depois da validação, sem `git clean` (o `.env` real fica em `.server/zeny-front.env`).
- No fechamento, registrar D1–D5 no roadmap ("Hipóteses e decisões em aberto": desfecho de H1–H4) e no handoff da E01 ("Decisões para o usuário").
- `git -C /home/caio/repos/ZenyHotelManager status --porcelain` e `worktree list` → idênticos ao snapshot inicial.
- `git ls-files | grep -E '(^|/)\.env$'` → vazio.
