# M0 E02 — Sincronizar com o servidor em `57eb5fac5b8a` (rodada 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o front passa a apontar para a `main` do servidor em `57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`: pin e contrato trocados, SDK regenerado com as 335 operações, o que quebrar consertado, `tools/server-env/` revisto e o ambiente `zeny-front` verificado com o `smokeTest`.

**Architecture:** a troca usa só o mecanismo da E01 (`tools/server-pin.sh`), e o SDK continua gerado inteiro pelo ogen (ADR 0007), sem exclusão de operação nem código à mão no `sdk`. Nada de funcionalidade nova: as operações novas ficam sem fachada no `core` até os marcos que as usam (ADR 0009). A revisão do ambiente confirma, com teste, que `docker/gate/` do servidor não entra no projeto `zeny-front` nem no pacote de imagens.

**Tech Stack:** Kotlin, Gradle 9.8.1 (wrapper), ogen (plugin `libs.plugins.ogen`), Ktor client (MockEngine nos testes), bash (`tests/run.sh`), Docker Compose.

**Spec:** [`docs/roadmaps/roadmap-m0-sincronizacao.md`](../../roadmaps/roadmap-m0-sincronizacao.md), seção "E02", mais o design [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../specs/2026-11-10-front-desktop-design.md) §5, §6.4 e §7.3, e os ADRs [0002](../../adr/0002-servidor-fixado-e-contrato-copiado.md), [0003](../../adr/0003-ambiente-do-servidor-isolado.md), [0007](../../adr/0007-gerador-do-sdk.md) e [0009](../../adr/0009-documentos-pdf-e-impressao.md).

## Global Constraints

- O repositório do servidor é **só leitura**: `git -C … rev-parse`, `git -C … show <commit>:<arquivo>`, `git -C … archive`. Nada de `worktree add`, `checkout`, `fetch` ou arquivo novo lá.
- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela. A troca é feita só por `tools/server-pin.sh`; nunca edite esses dois arquivos à mão.
- O ambiente do front usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` e a porta 8088 por padrão. Ele nunca usa o projeto `zeny` nem a imagem `:dev`. `down` e `down -v` só atingem `zeny-front`.
- Segredos (`.env` com chaves, senhas do admin) nunca entram no Git. Só versões `.env.example` com valores vazios ou de exemplo.
- Scripts em bash, sem dependência de Node. O que roda no Windows é só o `LEIA-ME` em PowerShell.
- Documentação em pt-BR; código e identificadores em inglês.
- Trocar de gerador, excluir operação do contrato ou escrever código à mão no `sdk` é decisão de arquitetura (ADR 0007, critério 1): se a geração ou a compilação do contrato novo falhar por causa de `application/pdf` (`type: string, format: binary`) ou do esquema `printAgentToken`, **pare e devolva BLOCKED** com a saída do erro.
- Nunca `./gradlew --stop` (Gradle e `~/.gradle` compartilhados com o servidor). Se o build falhar com "daemon disappeared", repita uma vez. Não rode a suíte junto com o gate do servidor (design §5).
- Versões só em `gradle/libs.versions.toml`; mudança de catálogo ou plugin regrava os lockfiles no mesmo commit (`./gradlew resolveAndLockAll --write-locks`). Este plano não prevê mudança de catálogo.
- Commits em pt-BR, no estilo do histórico, com o trailer `Soul:` da soul que commita.

## Review Focus

1. **Resposta `application/pdf`:** `getRegistrationCardPdf` (e as outras duas rotas de PDF) devolvem bytes crus; um método gerado que tente decodificar JSON quebraria a M2 E07 sem aviso. Teste de caracterização na Task 1 (`pdfOperationReturnsRawBytes`).
2. **`genderDescription` presente, ausente e `null`:** o `core` e o `demo-seed` leem `GuestResponse` do servidor novo; o campo novo não pode quebrar a leitura de respostas antigas nem novas. Teste na Task 1 (`guestResponseToleratesGenderDescription`).
3. **Cobertura do SDK com as tags novas (`Printing`, `end-of-day`):** a contagem de 335 só fecha se as classes geradas das tags novas entrarem na lista. Teste existente atualizado na Task 1.
4. **`docker/gate/` no `git archive`:** `save-images.sh` não pode gravar imagens do `compose.gate.yaml` (`zeny-hotel-manager:gate`, WireMock). Teste na Task 2 (`save-images ignora docker/gate`).
5. **Pacote com `docker/gate/`:** `package.sh` continua gerando o pacote com o override e o `LEIA-ME` só sobre `docker/compose.yaml`, mesmo com `docker/gate/` presente na árvore do servidor. Teste na Task 2 (`pacote com docker/gate`).

---

### Task 1: Pin, contrato e SDK em `57eb5fac5b8a`

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (contrato público e núcleo da etapa)

**Files:**
- Modify (por script): `api/server-pin.properties`, `api/openapi.yaml`
- Modify: `sdk/src/test/kotlin/com/zeny/desktop/sdk/SdkAttackTest.kt` (`everyOperationInTheContractHasAGeneratedMethod` e testes novos no mesmo arquivo)
- Modify: `README.md:35` (linha "Hoje: `<commit>`.")
- Modify, só se a compilação quebrar: os pontos de `core/src/main`, `app/src/main` e `demo-seed/src/main` que usam tipos gerados alterados (o único construtor conhecido é `GuestProfileRequest(` em `demo-seed/src/main/kotlin/com/zeny/desktop/seed/sdk/SdkBookingGateway.kt:111`)

**Interfaces:**
- Consumes: `tools/server-pin.sh [REF]` (E01).
- Produces: `api/server-pin.properties` com `commit=57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`; SDK gerado com as classes das tags novas (nomes como o ogen os gera; esperado `PrintingApi` e `EndOfDayApi`, confirme em `sdk/build/`) — a Task 2 e a verificação da etapa usam o pin.

- [ ] **Step 1: Atualize o teste de cobertura (vermelho)**

Em `everyOperationInTheContractHasAGeneratedMethod`: `assertEquals(335, contract)` e acrescente à lista as classes das tags `Printing` e `end-of-day` (`"Printing"`, `"EndOfDay"`; se o ogen gerar outro nome, use o gerado).

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew :sdk:test --tests 'com.zeny.desktop.sdk.SdkAttackTest'`
Expected: FAIL (`ClassNotFoundException` para a classe nova ou `expected:<335> but was:<303>`).

- [ ] **Step 3: Troque o pin**

Run: `tools/server-pin.sh 57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`
Expected: `Servidor fixado em 57eb5fac5b8a (contrato sha256 …)`. Confira e cite as três igualdades: `sha256sum api/openapi.yaml`, `contract_sha256` do pin e `git -C /home/caio/repos/ZenyHotelManager show 57eb5fac5b8a:api/openapi.yaml | sha256sum`. `grep -c 'operationId:' api/openapi.yaml` → `335`.

- [ ] **Step 4: Gere e compile o SDK**

Run: `./gradlew :sdk:compileKotlin`
Expected: `BUILD SUCCESSFUL`. Se falhar por `application/pdf`/`format: binary` ou por `printAgentToken`, pare: **BLOCKED** com o erro (Global Constraints). Qualquer outra falha do gerador também vai como BLOCKED, sem contorno no `sdk`.

- [ ] **Step 5: Testes de caracterização das mudanças do contrato**

No `SdkAttackTest`, no estilo dos testes de MockEngine já existentes (`client(body, status)`):
- `pdfOperationReturnsRawBytes`: chame o método gerado de `getRegistrationCardPdf` com um id qualquer, com o mock respondendo `200`, `Content-Type: application/pdf` e o corpo `"%PDF-1.7\n%âãÏÓ\n"` em bytes (inclua bytes fora de UTF-8 válido, por exemplo `0xFF 0x00`); assert: os bytes obtidos pelo meio público que o método gerado oferece (corpo tipado ou `raw`) são iguais aos enviados, e a requisição foi `GET /api/v1/reservations/{id}/registration-card/pdf`. Se o método gerado não der acesso aos bytes sem passar por JSON (falha de desserialização), **não commite o teste**: termine como DONE_WITH_CONCERNS, cole a saída e diga o que o método gerado expõe. Isso não bloqueia a etapa; vira nota para a M2 E07.
- `guestResponseToleratesGenderDescription`: desserialize, pelo `Json` que o SDK usa, um `GuestResponse` com `"genderDescription": "Não binário"`, um com `"genderDescription": null` e um sem a chave; assert: o campo vale `"Não binário"`, `null` e `null`, e os demais campos lidos são iguais nos três.

- [ ] **Step 6: Rode os testes do SDK**

Run: `./gradlew :sdk:test`
Expected: `BUILD SUCCESSFUL`, com os testes do Step 1 e do Step 5 passando.

- [ ] **Step 7: Build inteiro e conserto mínimo**

Run: `./gradlew build`
Se quebrar a compilação em `core`, `app` ou `demo-seed`, conserte o mínimo (por exemplo, argumento nomeado do campo novo), sem funcionalidade nova e sem fachada nova no `core`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: README**

`README.md:35` → `Hoje: \`57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b\`.`

- [ ] **Step 9: Gate**

Run: `bash tests/run.sh` e `./gradlew ktlintCheck detekt`
Expected: `tests/run.sh` com 0 falhas (cite o total de arquivos e de casos pulados); lint `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add api/ sdk/src/test README.md <arquivos consertados>
git commit -m "Fixa o servidor em 57eb5fac5b8a e regenera o SDK com 335 operações" -m "Soul: desenvolvedor"
```

---

### Task 2: Revisão de `tools/server-env/` diante do `docker/` do servidor

**Estimativa:** 30 min · **Depende de:** Task 1 · **Risco:** normal

**Files:**
- Modify: `tests/test_package.sh` (fixture do servidor falso e dois casos novos)
- Modify, só se algo mudar de fato: `tools/server-env/LEIA-ME.md`, `README.md`
- Nenhum script de `tools/server-env/` muda, a menos que a revisão ache defeito real (então descreva no relatório antes de mudar)

**Interfaces:**
- Consumes: pin da Task 1 (`server_tag` → `57eb5fac5b8a`).
- Produces: no relatório, a tabela da revisão arquivo por arquivo (`compose.override.yaml`, `up.sh`, `down.sh`, `package.sh`, `save-images.sh`, `gen-env.sh`, `lib.sh`, `.env.example`, `LEIA-ME.md`): "sem mudança" ou a mudança, com o motivo e o comando que o comprova. O orquestrador copia a tabela para o handoff.

- [ ] **Step 1: Levante o que mudou no servidor**

Run: `git -C /home/caio/repos/ZenyHotelManager diff --stat ca9f9e2f9808 57eb5fac5b8a -- docker/` e `git -C /home/caio/repos/ZenyHotelManager diff ca9f9e2f9808 57eb5fac5b8a -- docker/compose.yaml docker/.env.example docker/Dockerfile`
Expected: só `docker/gate/` e `docker/prometheus/alerts.yaml` mudaram; o `diff` dos três arquivos sai vazio. Compare também as chaves de `docker/.env.example` (no commit novo) com as de `tools/server-env/.env.example`: mesmo conjunto.

- [ ] **Step 2: Testes novos (vermelho primeiro, se couber)**

Em `tests/test_package.sh`, faça o servidor falso ter `docker/gate/compose.gate.yaml` com `image: zeny-hotel-manager:gate` e `image: wiremock/wiremock:3.13.1` (e um `docker/gate/README.md`), e acrescente:
- `save-images ignora docker/gate`: com o stub de imagens presentes, a linha do `docker save` não contém `zeny-hotel-manager:gate` nem `wiremock`, e continua com as quatro imagens de `docker/compose.yaml`.
- `pacote com docker/gate`: `package.sh` sai com 0; `server/docker/gate/` está no pacote (vem do `git archive`); o `compose.override.yaml` do pacote e o `LEIA-ME.md` não citam `gate`.
Se os casos já passarem sem mudança de script (o esperado), registre no relatório que são testes de regressão que confirmam o comportamento, com a saída.

- [ ] **Step 3: Confira o compose real**

Com o pin novo, monte a árvore e a configuração efetiva sem subir nada: use as funções de `tools/server-env/lib.sh` (`ensure_server_tree` e o `compose config`) e confirme no resultado: projeto `zeny-front`, imagem `zeny-hotel-manager:57eb5fac5b8a`, porta 8088, nenhum serviço do `compose.gate.yaml`. Cite o comando e as linhas.

- [ ] **Step 4: Documentação**

Só se algo mudou: registre no `LEIA-ME.md` ou no `README.md`. Se nada mudou, não toque neles e diga isso no relatório.

- [ ] **Step 5: Gate e commit**

Run: `bash tests/run.sh` → 0 falhas.

```bash
git add tests/test_package.sh <docs, se mudaram>
git commit -m "Confirma que docker/gate do servidor não entra no ambiente nem no pacote" -m "Soul: desenvolvedor"
```

---

## Verificação da etapa (orquestrador, depois da revisão final)

Não é tarefa de implementação: são comandos do projeto que o orquestrador roda e registra no handoff. Falha vira rodada de correção do desenvolvedor.

1. `tools/server-drift.sh` → "igual" (código 0) se a `main` do servidor ainda estiver em `57eb5fac5b8a`; senão, registrar a saída, com o pin mantido.
2. Snapshot do servidor (`status --porcelain` e `worktree list`) igual ao do início.
3. Ambiente: com `MemAvailable` ≥ 6 GiB (esperar e tentar de novo se faltar; sem memória, registrar e passar a subida ao Windows na parada), `tools/server-env/down.sh -v`, `tools/server-env/up.sh` (constrói `zeny-hotel-manager:57eb5fac5b8a`), readiness `UP`, administrador inicial e `smokeTest` com os segredos de `.server/seed-secrets.env` e do `.env` (`./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088`); depois, o seed da demo como no roteiro.
4. Pacote: JARs (`./gradlew :app:desktopJar :demo-seed:seedJar`), `tools/server-env/package.sh` e `tools/server-env/save-images.sh`, com o `VERSAO.txt` citando o servidor `57eb5fac5b8a`.
5. Parada humana: o usuário executa `roteiro-m1.md` no Windows com o pacote novo e aprova.
