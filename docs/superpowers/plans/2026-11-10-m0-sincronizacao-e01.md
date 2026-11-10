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
- Testes nunca apontam para o servidor real, exceto os marcados "real", que só leem e se pulam (`SKIP`) quando o repositório não existe. Testes que escrevem (`api/`, `.server/`, `build/`) rodam numa cópia temporária do front (`make_fake_front`), nunca no worktree.
- Gate desta etapa: `bash tests/run.sh` verde (inclui `bash -n` de todo `*.sh` e `shellcheck` se estiver instalado). Não instale ferramentas.

## Review Focus

1. **Rodar de dentro de um worktree** (`.claude/worktrees/…`): o padrão do repositório do servidor tem de achar `/home/caio/repos/ZenyHotelManager`, não `.claude/worktrees/ZenyHotelManager`. Teste em Task 1.
2. **Cópia local adulterada ou pin corrompido** (alguém edita `api/openapi.yaml` à mão, ou falta `contract_sha256`): o pré-voo sai com o código de inconsistência, nunca com "igual". Teste em Task 3.
3. **Falha do Docker ou do oasdiff** (daemon parado, imagem ausente sem internet, exit 125): o pré-voo sai com erro de ambiente, nunca classifica como breaking nem como aditivo. Teste em Task 3.
4. **`gen-env.sh` rodado de novo sobre um `.env` existente**: recusa sem `--force` e não toca no arquivo — perder `ZENY_ENCRYPTION_KEYS` torna ilegível o que foi cifrado no volume. Teste em Task 4.
5. **`down.sh -v` com o projeto `zeny` do servidor no ar**: só o projeto `zeny-front` aparece em qualquer chamada ao Docker. Teste em Task 6.

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
| 2 | 2, 3, 5 | dependem só da 1; arquivos disjuntos |
| 3 | 4, 6 | 4 lê o pin real (2); 6 usa a lib (5) |
| 4 | 7 | usa 4, 5 e 6 |
| 5 | 8 | documenta os comandos de todas |

---

### Task 1: Base do repositório, biblioteca comum e harness de testes

Estimativa: 35 min · Depende de: nenhuma · Risco: alto (é o guarda de só-leitura do servidor)

**Files:**
- Create: `.gitignore`, `tools/lib/common.sh`
- Test: `tests/run.sh`, `tests/lib/assert.sh`, `tests/lib/fixtures.sh`, `tests/test_common.sh`

**Interfaces:**
- Produces (`tools/lib/common.sh`, para `source`; não executa nada ao ser carregado):
  - `die CODE MSG...` — imprime `erro: MSG` no stderr e sai com CODE.
  - `front_root` — raiz do worktree do front em que o script está (`git -C "<dir do script>" rev-parse --show-toplevel`), independente do cwd.
  - `front_main_checkout` — diretório pai de `git rev-parse --path-format=absolute --git-common-dir`.
  - `server_repo` — `$ZENY_SERVER_REPO` ou `$(front_main_checkout)/../ZenyHotelManager`, normalizado para caminho absoluto; `die 2` se não for repositório git.
  - `srv_git ARGS...` — `git -C "$(server_repo)" ARGS...`, aceitando só os subcomandos `rev-parse cat-file show archive diff log ls-tree merge-base`; qualquer outro → `die 2 "subcomando git não permitido no servidor: X"`.
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
  - `make_fake_front DIR` — repositório git temporário com cópia de `tools/` do front (e `api/` se existir), para testes que escrevem.
  - `install_docker_stub DIR` — põe em `DIR/bin` um `docker` e um `curl` falsos que acrescentam a linha de argumentos em `DIR/docker.log` / `DIR/curl.log`; comportamento por variáveis: `STUB_IMAGE_PRESENT=0|1` (`docker image inspect`), `STUB_DOCKER_EXIT` (código geral), `STUB_CURL_BODY` e `STUB_CURL_EXIT`. Imprime o `PATH` a usar.
- Produces (`tests/lib/assert.sh`): `assert_eq EXP ACT MSG`, `assert_contains HAYSTACK NEEDLE MSG`, `assert_not_contains HAYSTACK NEEDLE MSG`, `assert_exit EXP_CODE MSG -- CMD...`, `assert_file PATH MSG`; cada falha imprime `FALHOU: MSG` e incrementa um contador; `finish` sai com 1 se houve falha.
- Produces (`tests/run.sh`): roda `bash -n` em todo `tools/**/*.sh` e `tests/**/*.sh`, `shellcheck` neles se existir no PATH, e cada `tests/test_*.sh` em processo próprio; imprime `PASS`/`FAIL`/`SKIP` por arquivo e `Resumo: N arquivos, F falhas`; sai 1 com falha. Um teste sinaliza SKIP saindo com 77.

- [ ] **Step 1: Escrever `tests/lib/assert.sh`, `tests/lib/fixtures.sh`, `tests/run.sh` e o teste falhando `tests/test_common.sh`**, com os casos:
  - `server_repo` sem `ZENY_SERVER_REPO`, chamado de um worktree criado por `git worktree add` dentro de um front falso em `$tmp/front`, resolve `$tmp/ZenyHotelManager` (crie o servidor falso lá);
  - `server_repo` com `ZENY_SERVER_REPO` apontando para diretório que não é git → exit 2;
  - `ZENY_SERVER_REPO` com espaço no caminho funciona;
  - `srv_git rev-parse main` devolve `$FAKE_C4`; `srv_git checkout main`, `srv_git fetch` e `srv_git worktree add x` → exit 2, e `git -C <servidor falso> status --porcelain` e `worktree list` iguais antes e depois;
  - `pin_get commit` lê de um pin falso; chave ausente → exit 3; arquivo ausente → exit 3;
  - `short_commit` de um SHA de 40 caracteres tem 12.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_common.sh` (common.sh não existe).
- [ ] **Step 3: Implementar `tools/lib/common.sh` e `.gitignore`.** `.gitignore`: `.server/`, `build/`, `.env`, `.gradle/`, `.kotlin/`, `.idea/`, `*.iml`, `out/`, `local.properties`, `.claude/worktrees/`.
- [ ] **Step 4:** `bash tests/run.sh` → `Resumo: 1 arquivos, 0 falhas`.
- [ ] **Step 5: Commit.**

### Task 2: Fixação do commit e cópia do contrato

Estimativa: 30 min · Depende de: Task 1 · Risco: alto (contrato e invariante byte a byte)

**Files:**
- Create: `tools/server-pin.sh`, `api/server-pin.properties`, `api/openapi.yaml` (gerados pelo script, no Step 5)
- Test: `tests/test_server_pin.sh`

**Interfaces:**
- Consumes: `tools/lib/common.sh` (Task 1), `make_fake_server`, `make_fake_front`.
- Produces: `tools/server-pin.sh [REF]` (padrão `main`). Resolve REF por `srv_git rev-parse --verify "REF^{commit}"`, copia `srv_git cat-file blob <commit>:api/openapi.yaml` para `api/openapi.yaml` e escreve `api/server-pin.properties`, ambos por arquivo temporário + `mv` no mesmo diretório, nesta forma exata (três linhas, nesta ordem):
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

- Comportamento:
  - a primeira linha da saída é `Pré-voo: base <curto> × alvo <curto> (<REF do alvo>)`; a última é `Resultado: <igual|avançou sem mudança no contrato|aditivo|breaking> (código N)`;
  - quando base ≠ alvo, lista os arquivos alterados em `docker/` entre os dois (`srv_git diff --name-only base alvo -- docker/`) sob `Ambiente do servidor alterado:` (ou `Ambiente do servidor sem mudanças.`), para a próxima rodada revisar `tools/server-env/`;
  - oasdiff como no script do servidor: os dois contratos em diretório temporário (`mktemp -d`, removido por `trap`), `docker run --rm --network none -v "<tmp>:/specs:ro" -w /specs "$OASDIFF_IMAGE" breaking base/api/openapi.yaml api/openapi.yaml --match-path '^/api/v1(/|$)' --fail-on ERR --allow-external-refs=false --format text --lang pt-br`; exit 0 → aditivo, exit 1 → breaking, qualquer outro → código 2 com `erro: oasdiff falhou (exit N)`;
  - só leitura: toda chamada ao servidor passa por `srv_git`.

- [ ] **Step 1: Teste falhando `tests/test_server_drift.sh`** (front falso com pin de `FAKE_C1` gerado no próprio teste, servidor falso):
  - `--target $FAKE_C1` → 0, saída contém `Resultado: igual (código 0)`;
  - `--target $FAKE_C2` → 10;
  - `--target $FAKE_C3` → 11;
  - `--target $FAKE_C4` → 12; sem `--target` (main = C4) → 12;
  - `ZENY_DRIFT_BASE=$FAKE_C3 ZENY_DRIFT_TARGET=$FAKE_C4` → 12 e `--base $FAKE_C1 --target $FAKE_C3` → 11 (comparação entre dois commits anteriores ao alvo, critério do roadmap);
  - `api/openapi.yaml` local com um byte alterado → 3; pin sem `contract_sha256` → 3; sem pin → 3;
  - `--target nao-existe` → 2;
  - com o stub de `docker` (`install_docker_stub`, `STUB_DOCKER_EXIT=125`) e alvo C3 → 2, nunca 11 nem 12;
  - snapshot do servidor falso igual antes e depois.
  Os casos 11 e 12 usam o Docker real com a imagem do oasdiff; se `docker image inspect "$OASDIFF_IMAGE"` falhar, esses casos saem com SKIP (77) e o resto roda.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_server_drift.sh`.
- [ ] **Step 3: Implementar `tools/server-drift.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas, sem SKIP neste arquivo (a imagem do oasdiff está no cache local desta máquina).
- [ ] **Step 5: Commit.**

### Task 4: Modelo do `.env` e gerador de segredos

Estimativa: 35 min · Depende de: Task 1, Task 2 · Risco: alto (segredos)

**Files:**
- Create: `tools/server-env/.env.example`, `tools/server-env/gen-env.sh`
- Test: `tests/test_gen_env.sh`

**Interfaces:**
- Consumes: `tools/lib/common.sh`; pin real da Task 2 (só no caso "real").
- Produces:
  - `tools/server-env/.env.example`: as mesmas variáveis, na mesma ordem, do `docker/.env.example` do servidor no commit fixado, com os comentários adaptados ao front, e estas diferenças: `ZENY_HTTP_PORT=8088`, `ZENY_STORAGE_PUBLIC_ENDPOINT=http://localhost:8088`, nota sobre `ZENY_HTTP_ADDRESS=0.0.0.0` (rede, mobile) e `ZENY_STORAGE_PUBLIC_ENDPOINT=http://<IP>:8088` (o endereço que o app enxerga, senão as URLs assinadas não abrem). Segredos vazios: `ZENY_ENCRYPTION_KEYS`, `ZENY_JWT_KEYS`, `ZENY_ADMIN_PASSWORD`, `ZENY_STORAGE_ROOT_PASSWORD`, `ZENY_STORAGE_SECRET_KEY`, `ZENY_FCM_CREDENTIALS`.
  - `tools/server-env/gen-env.sh [--force] [--address ADDR] [--public-endpoint URL] DEST`: copia o `.env.example` vizinho para DEST (criado com `umask 077`, modo 600) preenchendo os cinco primeiros segredos acima, com os geradores do README do servidor:
    - `ZENY_ENCRYPTION_KEYS=dev:$(openssl rand -base64 32)`;
    - `ZENY_JWT_KEYS=dev:<RSA 3072, PKCS#8 DER, base64 numa linha>` (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n'`);
    - `ZENY_ADMIN_PASSWORD` = `openssl rand -base64 18`; `ZENY_STORAGE_ROOT_PASSWORD` e `ZENY_STORAGE_SECRET_KEY` = `openssl rand -base64 24`.
    `--address` define `ZENY_HTTP_ADDRESS`; `--public-endpoint` define `ZENY_STORAGE_PUBLIC_ENDPOINT`. A troca de linha é por chave inteira (`^CHAVE=`), sem `sed` com o valor no padrão (base64 tem `/` e `+`). DEST existente sem `--force` → exit 2 com DEST intacto. Nunca imprime valor de segredo; imprime só `Gerado DEST (segredos: <lista de chaves>)`. Usa só `bash`, `openssl`, `base64`, `tr`, `awk`, `mktemp`, `chmod`, `mv` (roda no Alpine, pelo `LEIA-ME`).

- [ ] **Step 1: Teste falhando `tests/test_gen_env.sh`:**
  - gera em `$tmp/.env`: os cinco segredos não vazios; modo `600`; demais linhas iguais ao modelo;
  - `ZENY_JWT_KEYS` sem o prefixo `dev:` decodifica (`base64 -d`) e `openssl pkey -inform DER -noout -text` mostra 3072 bits;
  - `ZENY_ENCRYPTION_KEYS` decodifica para 32 bytes;
  - duas gerações dão segredos diferentes;
  - segunda geração sem `--force` → exit 2 e arquivo idêntico ao primeiro (`cmp`); com `--force` → 0;
  - `--address 0.0.0.0 --public-endpoint http://192.168.0.20:8088` grava as duas linhas;
  - stdout+stderr não contêm nenhum dos valores gerados;
  - `.env.example` do front: `ZENY_HTTP_PORT=8088`, os seis segredos vazios;
  - **real** (SKIP se o servidor ou o pin não existirem): a lista ordenada de nomes de variáveis do `.env.example` do front é igual à do `docker/.env.example` do servidor no commit do pin (`srv_git show <commit>:docker/.env.example`).
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_gen_env.sh`.
- [ ] **Step 3: Escrever `.env.example` e implementar `gen-env.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas, caso real executado (não SKIP).
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
  - `server_tree_dir` — `$(front_root)/.server/<commit completo>`.
  - `ensure_server_tree` — se `server_tree_dir` não existe, extrai `srv_git archive --format=tar <commit>` num temporário irmão e renomeia (nunca deixa árvore parcial); depois confere `sha256sum <árvore>/api/openapi.yaml` = `contract_sha256` (senão `die 3`). Imprime o caminho.
  - `render_override DEST` — copia `compose.override.yaml` trocando `@SERVER_TAG@` por `server_tag`.
  - `env_file` — `${ZENY_FRONT_ENV_FILE:-$(front_root)/tools/server-env/.env}`.
  - `compose ARGS...` — `docker compose -p "$ZENY_FRONT_PROJECT" -f <árvore>/docker/compose.yaml -f <árvore>/compose.override.yaml --env-file "$(env_file)" ARGS...`, garantindo `ensure_server_tree` e `render_override` antes.
- Produces (`tools/server-env/compose.override.yaml`, modelo com marcador):
  ```yaml
  services:
    api:
      image: zeny-hotel-manager:@SERVER_TAG@
      environment:
        ZENY_STORAGE_PUBLIC_ENDPOINT: ${ZENY_STORAGE_PUBLIC_ENDPOINT:-http://localhost:8088}
    proxy:
      ports: !override
        - "${ZENY_HTTP_ADDRESS:-127.0.0.1}:${ZENY_HTTP_PORT:-8088}:80"
  ```
  O `build:` do `api` fica herdado do compose do servidor: o Windows constrói a imagem (ADR 0003) e, com a imagem já presente (carregada por `docker load`), o `up` não reconstrói.

- [ ] **Step 1: Teste falhando `tests/test_server_env_lib.sh`** (front falso com pin de `FAKE_C1`, servidor falso, env file de teste com valores fictícios para as variáveis obrigatórias):
  - `ensure_server_tree` cria `.server/<FAKE_C1>/docker/compose.yaml`; segunda chamada não reextrai (mtime igual);
  - árvore com `api/openapi.yaml` alterado → `die 3`;
  - `compose config --format json` (Docker real; SKIP se `docker compose version` falhar): `.name == "zeny-front"`; `.services.api.image == "zeny-hotel-manager:<12 de FAKE_C1>"`; `.services.proxy.ports` com um único item, `published == "8088"` e `host_ip == "127.0.0.1"`; com `ZENY_HTTP_ADDRESS=0.0.0.0` no env file, `host_ip == "0.0.0.0"`; `.services.api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT == "http://localhost:8088"` sem a variável no env file;
  - `ZENY_FRONT_PROJECT=zeny` no ambiente antes do `source` não muda o projeto (`.name` continua `zeny-front`);
  - **real** (SKIP sem o servidor ou sem o pin real): com a árvore do commit do pin real extraída num front falso (pin copiado do worktree), o mesmo `compose config` vale para o `docker/compose.yaml` verdadeiro, e nenhuma imagem do resultado termina em `:dev`.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_server_env_lib.sh`.
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
  - `tools/server-env/up.sh [--build] [--no-wait]`: `die 2` com a instrução `tools/server-env/gen-env.sh tools/server-env/.env` se `env_file` não existir; constrói (`compose build api`) se `docker image inspect zeny-hotel-manager:<tag>` falhar ou com `--build`; `compose up -d`; sem `--no-wait`, consulta `http://127.0.0.1:<porta>/actuator/health/readiness` (porta = `ZENY_HTTP_PORT` do env file, padrão 8088) a cada 5 s até a resposta conter `"status":"UP"` ou passar `ZENY_UP_TIMEOUT` (padrão 600 s) → exit 1 com a última resposta e a dica `docker compose -p zeny-front logs api`. Sucesso imprime `zeny-front no ar em http://localhost:<porta> (servidor <tag>)`.
  - `tools/server-env/down.sh [-v|--volumes]`: `compose down` (com `-v` só se pedido). Sem árvore extraída, `docker compose -p zeny-front down [-v]`. Imprime o que removeu.
- Regra: nenhuma chamada ao Docker cita outro projeto que não `zeny-front`, nem `zeny-hotel-manager:dev`.

- [ ] **Step 1: Teste falhando `tests/test_up_down.sh`** (front falso com pin de `FAKE_C1`, servidor falso, `install_docker_stub`):
  - sem env file → exit 2 e mensagem cita `gen-env.sh`; nenhuma chamada ao `docker`;
  - `STUB_IMAGE_PRESENT=0` → o log tem `build api` antes de `up -d`; `STUB_IMAGE_PRESENT=1` → sem `build`; `--build` → com `build`;
  - `STUB_CURL_BODY='{"status":"UP"}'` → exit 0 e a URL consultada é `http://127.0.0.1:8088/actuator/health/readiness`; com `ZENY_HTTP_PORT=9099` no env file → porta 9099;
  - `STUB_CURL_BODY='{"status":"DOWN"}'` e `ZENY_UP_TIMEOUT=1` → exit 1;
  - `down.sh` → o log tem `down` sem `-v`; `down.sh -v` → `down -v`; sem árvore extraída, `down.sh` usa `-p zeny-front down`;
  - em todo o `docker.log`: toda linha `compose` contém `-p zeny-front`; nenhuma contém `-p zeny ` nem `:dev`.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_up_down.sh`.
- [ ] **Step 3: Implementar `up.sh` e `down.sh`.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Commit.** A validação real (`up.sh` + readiness no notebook) é do orquestrador, não desta task.

### Task 7: Pacote do Windows, imagens e `LEIA-ME`

Estimativa: 45 min · Depende de: Task 4, Task 5, Task 6 · Risco: normal

**Files:**
- Create: `tools/server-env/package.sh`, `tools/server-env/save-images.sh`, `tools/server-env/LEIA-ME.md`
- Test: `tests/test_package.sh`

**Interfaces:**
- Consumes: `lib.sh` (`ensure_server_tree`, `render_override`, `server_tag`, `pin_get`), `.env.example` e `gen-env.sh` (Task 4).
- Produces:
  - `tools/server-env/package.sh`: recria `$(front_root)/build/demo-package/` (apaga só esse diretório) com:
    - `server/` = cópia da árvore do commit fixado;
    - `compose.override.yaml` renderizado;
    - `.env.example` e `gen-env.sh`;
    - `server-pin.properties` (cópia do pin);
    - `LEIA-ME.md` renderizado (marcadores `@SERVER_COMMIT@` e `@SERVER_TAG@`);
    - `app/LEIA-ME.txt` dizendo que os JARs do app e do `demo-seed` entram na M1 E07;
    - `images/` vazio, com `LEIA-ME.txt` apontando para `save-images.sh`.
    Nunca copia `.env`. Imprime o caminho e o tamanho.
  - `tools/server-env/save-images.sh [OUT]` (padrão `build/demo-package/images/zeny-server-images.tar`): lista as imagens de `<árvore>/docker/compose.yaml` (linhas `image:`), troca `zeny-hotel-manager:dev` por `zeny-hotel-manager:<tag>`, remove duplicadas, confere cada uma com `docker image inspect` (faltando alguma → exit 2 com a lista do que falta e a dica de rodar `up.sh --build` ou `docker pull`) e grava todas num único `docker save -o OUT`.
  - `tools/server-env/LEIA-ME.md` (modelo, pt-BR, comandos PowerShell, a partir da pasta do pacote; todo compose com `docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env`):
    1. **Primeira vez:** gerar o `.env` sem bash local: `docker run --rm -v "${PWD}:/w" -w /w alpine:3.22 sh -c "apk add --no-cache bash openssl >/dev/null && bash gen-env.sh --address 0.0.0.0 --public-endpoint http://<IP do Windows>:8088 .env"`; guardar o `.env` (a chave de cifragem vale enquanto o volume existir); `build` (precisa de internet); `up -d`; readiness com `Invoke-RestMethod http://localhost:8088/actuator/health/readiness` até `status = UP`. Alternativa sem internet: `docker load -i images\zeny-server-images.tar` e `up -d` sem `build`.
    2. **Atualizar:** pacote novo numa pasta nova, copiar o `.env` antigo, `build`, `up -d`.
    3. **Recriar a demo:** `down -v`, `up -d` e, a partir da M1, `java -jar app\demo-seed.jar …`.
    4. **Firewall:** `New-NetFirewallRule -DisplayName "Zeny front 8088" -Direction Inbound -Protocol TCP -LocalPort 8088 -Action Allow` (PowerShell como administrador).
    Também: o login do admin (`ZENY_ADMIN_LOGIN` e a senha do `.env`, troca obrigatória no primeiro login), o servidor só em rede confiável (HTTP), nunca usar `-p zeny`, e o commit do servidor (`@SERVER_COMMIT@`).

- [ ] **Step 1: Teste falhando `tests/test_package.sh`** (front falso com pin de `FAKE_C1`, servidor falso, stub de docker):
  - `package.sh` cria os itens listados; `server/docker/compose.yaml` igual ao do commit; `LEIA-ME.md` sem marcador `@…@` e contendo o SHA de `FAKE_C1`; `compose.override.yaml` com `zeny-hotel-manager:<tag>`;
  - com `tools/server-env/.env` presente no front falso, o pacote não tem nenhum arquivo `.env` (`find -name .env` vazio);
  - segunda execução apaga arquivo estranho deixado em `build/demo-package/` e não toca em `build/outro/`;
  - `save-images.sh` com `STUB_IMAGE_PRESENT=1`: o log tem um único `save -o …` com as quatro imagens `zeny-hotel-manager:<tag>`, `postgres:18`, `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, `caddy:2.11-alpine`, sem `:dev`; com `STUB_IMAGE_PRESENT=0` → exit 2 e nenhum `save`.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_package.sh`.
- [ ] **Step 3: Implementar os três arquivos.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas; rode também `tools/server-env/package.sh` no worktree (servidor real, só leitura) e cole `ls -la build/demo-package` no relatório.
- [ ] **Step 5: Commit.**

### Task 8: README e controle de pedidos ao servidor

Estimativa: 20 min · Depende de: Task 7 · Risco: normal

**Files:**
- Create: `README.md`, `docs/pedidos-servidor.md`
- Test: `tests/test_docs.sh`

**Interfaces:**
- Consumes: os nomes, opções e códigos de saída dos scripts das Tasks 2–7.
- Produces:
  - `README.md` (pt-BR): o que é o projeto (cliente desktop do Zeny Hotel Manager, Compose Multiplatform, ainda sem código do app até a M1); onde estão o design, os ADRs e os roadmaps; o servidor fixado (`api/server-pin.properties`) e a regra de só-leitura; tabela "Para quê | Comando" com `tools/server-pin.sh`, `tools/server-drift.sh` (com a tabela de códigos 0/10/11/12/2/3), `tools/server-env/gen-env.sh`, `up.sh`, `down.sh`, `package.sh`, `save-images.sh` e `bash tests/run.sh`; variáveis `ZENY_SERVER_REPO`, `ZENY_DRIFT_BASE`, `ZENY_DRIFT_TARGET`, `ZENY_FRONT_ENV_FILE`, `ZENY_UP_TIMEOUT`, `OASDIFF_IMAGE`.
  - `docs/pedidos-servidor.md`: as regras do design §8 e do ADR 0005 (captura `ZenyHotelManager - pedido do front PF-NN <título>` com motivo, operação, etapa, commit fixado e prioridade; estados `aberto`, `aceito`, `entregue`, `recusado`; etapa dependente `#bloqueada` com desbloqueio "PF-NN entregue e sincronizado pelo M0"; resposta "atende PF-NN"; antes de 17/11 nenhuma etapa depende de pedido) e a tabela vazia `| PF-NN | título | estado | captura | etapa do front | commit de entrega |`.

- [ ] **Step 1: Teste falhando `tests/test_docs.sh`:** todo `tools/**/*.sh` executável aparece no `README.md` pelo caminho relativo; o README contém os seis códigos do pré-voo; `docs/pedidos-servidor.md` contém o cabeçalho exato da tabela acima.
- [ ] **Step 2:** `bash tests/run.sh` → FAIL em `test_docs.sh`.
- [ ] **Step 3: Escrever os dois documentos.**
- [ ] **Step 4:** `bash tests/run.sh` → 0 falhas.
- [ ] **Step 5: Commit.**

---

## Validação da etapa (orquestrador, depois das tasks)

- `bash tests/run.sh` → 0 falhas.
- `tools/server-drift.sh` no worktree, servidor real → código 0.
- `tools/server-env/up.sh` + `curl -fsS http://localhost:8088/actuator/health/readiness` → `UP`, se houver memória livre no notebook sem disputar com o orquestrador do servidor; senão, registrado e validado no Windows pelo usuário, na parada da etapa. Com `zeny-front` no ar, `docker compose -p zeny ps` e `docker images zeny-hotel-manager` comparados ao snapshot inicial; depois `down.sh -v` e conferência de que só sumiram recursos `zeny-front`.
- `git -C /home/caio/repos/ZenyHotelManager status --porcelain` e `worktree list` → idênticos ao snapshot inicial.
- `git ls-files | grep -E '(^|/)\.env$'` → vazio.
