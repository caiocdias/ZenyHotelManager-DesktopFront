# M0 E03 — Sincronizar com o servidor em `5e06f16d51ff` — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** apontar o front para a `main` do servidor em `5e06f16d51ff9913f0da09df8f62639996cd6c52`, caracterizar no `sdk` o tipo que o ogen gera para o primeiro `oneOf` do contrato, dar rótulo pt-BR aos valores novos de no-show no detalhe da reserva e avisar nos roteiros e no `LEIA-ME` que o servidor fecha o dia sozinho às 03:00.

**Architecture:** o pin e a cópia do contrato mudam pelo mecanismo da E01 (`tools/server-pin.sh`), e o `sdk` é regenerado pelo plugin do ogen sem código à mão (ADR 0007). O `core` não muda por causa do `oneOf` (ADR 0010: a fachada é da M5 E02). Os rótulos ficam em `ReceptionText`, a única fonte de texto da recepção. Os avisos são documentação, travados por teste estático em `tests/`.

**Tech Stack:** Kotlin, ogen 0.16.1, kotlinx.serialization, Ktor `MockEngine`, JUnit 5 (`kotlin.test`), bash (`tests/run.sh`).

**Spec:** seção "E03" de [`docs/roadmaps/roadmap-m0-sincronizacao.md`](../../roadmaps/roadmap-m0-sincronizacao.md), com o design [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../specs/2026-11-10-front-desktop-design.md) §5, §6.4 e §7.3, os ADRs [0002](../../adr/0002-servidor-fixado-e-contrato-copiado.md), [0003](../../adr/0003-ambiente-do-servidor-isolado.md), [0007](../../adr/0007-gerador-do-sdk.md) e [0010](../../adr/0010-resposta-que-varia-com-a-permissao.md), e a regra 5 de [`docs/roadmaps/README.md`](../../roadmaps/README.md).

## Global Constraints

- O repositório do servidor (`/home/caio/repos/ZenyHotelManager`) é **só leitura**: `git -C … rev-parse`, `show <commit>:<arquivo>`, `cat-file`, `archive`. Nada de `worktree add`, `checkout`, `fetch` ou arquivo novo lá.
- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela.
- O pin é `5e06f16d51ff9913f0da09df8f62639996cd6c52`, mesmo que a `main` do servidor ande durante a etapa. Nunca edite `api/openapi.yaml` nem `api/server-pin.properties` à mão: só por `tools/server-pin.sh`.
- O ambiente do front usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` e a porta 8088 por padrão. Ele nunca usa o projeto `zeny` nem a imagem `:dev`. `down` e `down -v` só atingem `zeny-front`.
- `docker compose config` sem filtro imprime as chaves e senhas do `.env` real: nunca rode nem cole essa saída. Só `--services`, `--images` e `--format json` filtrado por `python3 -I` nos campos `name` e `services.proxy.ports`.
- O `sdk` não é versionado e não tem código à mão. Se o ogen não gerar ou não compilar o `oneOf`, pare e devolva `BLOCKED`: trocar de gerador, excluir operação ou escrever código no `sdk` é decisão de arquitetura (ADR 0007, critério 1).
- Nada de funcionalidade nova: sem tela de restrição, mesclagem, fechamento do dia ou FNRH; sem mudança no `core` por causa do `oneOf`.
- Segredos nunca entram no Git. Scripts em bash, sem Node. Documentação em pt-BR; código e identificadores em inglês.
- Um Gradle por vez no notebook (14 GB, o orquestrador do servidor está ativo). Nunca `./gradlew --stop`. Se o build cair com "daemon disappeared", repita uma vez.
- Commits em pt-BR, no estilo do histórico, com o trailer `Soul:` da soul que commita.

## Review Focus

1. **Corpo completo que também traz `restricted`:** o servidor promete ramos disjuntos pelo `required`, mas nenhum ramo tem `additionalProperties: false`; o cliente gerado não pode descartar nem reescrever propriedades. O teste da Task 1 compara o corpo recebido com o enviado como árvore JSON inteira, num completo com `restricted` (`fullBranchWithRestrictedArrivesIntact`) e num resumo com uma propriedade desconhecida.
2. **`restricted: false` (lift):** o resumo com `false` precisa chegar como `false`, não como ausente. Teste na Task 1 (`summaryBranchKeepsRestrictedFalse`).
3. **`ETag` nas três escritas:** a escrita seguinte usa `If-Match`; o `etag` do resultado tem de vir nos dois ramos. Asserção nos testes da Task 1.
4. **Histórico com `NO_SHOW` e alocação solta com `RESERVATION_NO_SHOW` na mesma reserva:** é o que o fechamento automático das 03:00 produz numa reserva da demo; nenhuma linha do detalhe pode mostrar "Outro (…)". Teste na Task 2 (`noShowReservationDetailHasNoOtherLabel`).
5. **Aviso do fechamento só em um dos três documentos:** quem segue o roteiro do M1 ou só o `LEIA-ME` também recria a demo. Teste na Task 3 confere o mesmo aviso nos três.

---

### Task 1: Pin, contrato e SDK em `5e06f16d51ff`, com a caracterização do `oneOf`

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (contrato público, parsing e núcleo da etapa)

**Files:**
- Modify (por `tools/server-pin.sh`): `api/openapi.yaml`, `api/server-pin.properties`
- Modify: `README.md:36` (linha "Hoje: `57eb5fac5b8a…`"; `tests/test_docs_ataque.sh` exige que o README cite o commit do pin)
- Create: `sdk/src/test/kotlin/com/zeny/desktop/sdk/SdkOneOfTest.kt`
- Test (sem mudança esperada): `sdk/src/test/kotlin/com/zeny/desktop/sdk/SdkAttackTest.kt` (`everyOperationInTheContractHasAGeneratedMethod`, 335 operações), `core/src/test/kotlin/com/zeny/desktop/core/error/ContractErrorCodes.kt` e o `ErrorCatalogTest` (377 códigos)
- Modify, só se quebrar: o mínimo em `core`, `app`, `demo-seed` ou no catálogo de erros

**Interfaces:**
- Produces: pin em `5e06f16d51ff`; `GuestsApi.restrictGuest`, `liftGuestRestriction` e `mergeGuests` regenerados (no pin anterior devolviam `HttpResponseTyped<GuestResponse>`); modelo gerado `GuestWriteSummaryResponse(id: Uuid, restricted: Boolean)`. O relatório diz o tipo `T` de `HttpResponseTyped<T>` que o ogen gerou para as três: o orquestrador o grava no handoff e, se não for `JsonElement`, no ADR 0010.

- [ ] **Step 1: Fixe o servidor no commit analisado**

Run: `tools/server-pin.sh 5e06f16d51ff9913f0da09df8f62639996cd6c52`
Expected: `Servidor fixado em 5e06f16d51ff (contrato sha256 …)`. Depois, `sha256sum api/openapi.yaml`, `grep contract_sha256 api/server-pin.properties` e `git -C /home/caio/repos/ZenyHotelManager show 5e06f16d51ff:api/openapi.yaml | sha256sum` dão o mesmo hash, e `tools/server-drift.sh` sai com `igual (código 0)` se a `main` do servidor ainda estiver em `5e06f16d51ff` (senão, cole a saída no relatório). Troque, em `README.md:36`, o commit por `5e06f16d51ff9913f0da09df8f62639996cd6c52`. O wrapper do Gradle e `docker/` do servidor não mudaram entre os dois pins (o orquestrador conferiu `git diff 57eb5fac5b8a 5e06f16d51ff -- gradlew gradlew.bat gradle/wrapper/ docker/`, vazio).

- [ ] **Step 2: Regenere e compile o SDK**

Run: `./gradlew :sdk:compileKotlin`
Expected: BUILD SUCCESSFUL. Em `sdk/build/generated/sources/ogen/.../client/GuestsApi.kt`, leia o retorno das três operações e o arquivo do modelo `GuestWriteSummaryResponse`. Decida pelo que encontrar:

| O ogen gerou | Faça |
|---|---|
| `HttpResponseTyped<JsonElement>` e o modelo `GuestWriteSummaryResponse` | siga o Step 3 como está |
| `HttpResponseTyped<Any>` (ou outro tipo que não decodifica o corpo) e o modelo | siga o Step 3 declarando esse tipo, e prove "sem perda" sobre o corpo cru: `Json.parseToJsonElement(response.raw.bodyAsText())` igual ao enviado; diga o tipo no relatório (vai ao handoff e ao passo 1 do ADR 0010) |
| um tipo que decodifica e pode descartar ou reescrever propriedades (tipo-soma, um dos modelos), ou nenhum modelo `GuestWriteSummaryResponse` | pare com `BLOCKED`: fere os passos 2 a 5 do ADR 0010, e a decisão é do usuário |
| falha de geração ou de compilação por causa do `oneOf` | pare com `BLOCKED` (Global Constraints) |

- [ ] **Step 3: Escreva `SdkOneOfTest`**

Use o mesmo cliente de teste do `SdkAttackTest` (`MockEngine` + `HttpClientOgen.Base(http, "http://servidor.teste", {}, json)` com `Json { ignoreUnknownKeys = true }`), com o corpo, o `Content-Type: application/json` e um `ETag` `W/"7"`. Declare o resultado com o tipo que o Step 2 mostrou (esperado: `HttpResponseTyped<JsonElement>`); a declaração explícita é a caracterização, porque não compila se o tipo mudar. Testes, um por linha de asserção principal:

```kotlin
@Test fun restrictGuestFullBranchArrivesIntact()       // GuestResponse completo (fullName, vip, restricted ausente…)
@Test fun fullBranchWithRestrictedArrivesIntact()      // o mesmo completo com "restricted":true a mais
@Test fun restrictGuestSummaryBranchArrivesIntact()    // {"id":…,"restricted":true}
@Test fun liftGuestRestrictionBothBranchesArriveIntact()
@Test fun mergeGuestsBothBranchesArriveIntact()        // mergeGuests(id, ifMatch = "W/\"6\"", MergeGuestRequest(...))
@Test fun summaryBranchKeepsRestrictedFalse()
@Test fun summaryWithUnknownPropertyIsKeptVerbatim()   // {"id":…,"restricted":true,"novo":1}
```

Em cada um: `assertEquals(Json.parseToJsonElement(sent), response.body())` (na linha 2 da tabela do Step 2, `Json.parseToJsonElement(response.raw.bodyAsText())` no lugar de `response.body()`) (o corpo inteiro, sem perda nem reescrita); `assertEquals("W/\"7\"", response.raw.headers[HttpHeaders.ETag])` (o acessor do `SdkAttackTest.keepsWeakEtagVerbatim`); e a decodificação do ramo com o serializer gerado e o mesmo `json`: o completo por `GuestResponse.serializer()` dá o `fullName` enviado; o resumo por `GuestWriteSummaryResponse.serializer()` dá o `id` e o `restricted` enviados. Use um `GuestResponse` completo válido no contrato novo (todos os `required` de `GuestResponse` em `api/openapi.yaml`).

- [ ] **Step 4: Rode os testes do `sdk`**

Run: `./gradlew :sdk:test`
Expected: PASS, com `SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod` ainda em 335. Prove que a caracterização morde: troque temporariamente o tipo declarado num teste por `HttpResponseTyped<GuestResponse>` e veja a compilação falhar; desfaça.

- [ ] **Step 5: Rode o build inteiro, o lint e a suíte de shell**

Run: `./gradlew build`, `./gradlew ktlintCheck detekt` e `bash tests/run.sh`
Expected: BUILD SUCCESSFUL nos dois primeiros e 0 falhas na suíte de shell (inclusive `tests/test_docs_ataque.sh` com o README novo e `tests/test_wrapper.sh` contra o wrapper do pin). O teste dos códigos de erro do contrato continua em 377 (o diff não trouxe código novo); se mudar, conserte o catálogo como na E02 e diga no relatório. O que quebrar em `core`, `app` ou `demo-seed` pelo SDK novo recebe o conserto mínimo.

- [ ] **Step 6: Commit**

```bash
git add api/openapi.yaml api/server-pin.properties README.md sdk/src/test/kotlin/com/zeny/desktop/sdk/SdkOneOfTest.kt <arquivos consertados no Step 5, se houver>
git commit -m "Fixa o servidor em 5e06f16d51ff e caracteriza o primeiro oneOf do SDK" -m "Soul: desenvolvedor"
```

---

### Task 2: Rótulos do no-show no detalhe da reserva

**Estimativa:** 20 min · **Depende de:** nenhuma · **Risco:** normal

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionText.kt` (`historyKinds` e `releaseReasons`)
- Modify: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionTextTest.kt` (`historyKindLabels`, `releaseReasonRoomMoved`)
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReservationDetailNoShowLabelTest.kt`

**Interfaces:**
- Consumes: `ReceptionText.historyKind(value: String): String` e `ReceptionText.releaseReason(value: String): String` (existentes); o estado do detalhe em `ReservationDetailState.kt` (`assignmentLine`, `historyLines`).
- Produces: os rótulos `"NO_SHOW" to "Não compareceu"` em `historyKinds` e `"RESERVATION_NO_SHOW" to "Não compareceu"` em `releaseReasons` (o mesmo texto da situação `NO_SHOW`, como pede a nota do roadmap).

- [ ] **Step 1: Escreva os testes que falham**

Em `ReceptionTextTest`: acrescente `"NO_SHOW" to "Não compareceu"` ao mapa esperado de `historyKindLabels` e `assertEquals("Não compareceu", ReceptionText.releaseReason("RESERVATION_NO_SHOW"))` em `releaseReasonRoomMoved`. O caso de valor desconhecido (`"Outro (NEW)"`) fica.

Em `ReservationDetailNoShowLabelTest`, `noShowReservationDetailHasNoOtherLabel`: um detalhe de reserva com situação `NO_SHOW`, uma entrada de histórico `kind = "NO_SHOW"` e uma alocação solta (`active = false`) com `releaseReason = Option.Some("RESERVATION_NO_SHOW")`, carregado pelo estado do detalhe com o `FakeReceptionSource` (o histórico vai no mapa `histories`, `FakeReceptionSource.kt:160`). Copie as fixtures que precisar: a reserva e a alocação do `ReservationDetailAllocationLabelTest`, a `ReservationHistoryResponse(` do `ReservationDetailStateTest`. Asserções: o título da linha de histórico é `"Não compareceu"`; o estado da linha de alocação é `"Solta · Não compareceu"` (com o `SEPARATOR` do arquivo); nenhum texto de linha de histórico nem de alocação contém `"Outro ("`.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew :app:test --tests '*ReceptionTextTest' --tests '*ReservationDetailNoShowLabelTest'`
Expected: FAIL com `Outro (NO_SHOW)` e `Outro (RESERVATION_NO_SHOW)`.

- [ ] **Step 3: Acrescente os dois rótulos em `ReceptionText`**

- [ ] **Step 4: Rode e veja passar, depois o lint**

Run: o comando do Step 2, depois `./gradlew :app:test ktlintCheck detekt`
Expected: PASS e lint verde.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionText.kt app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionTextTest.kt app/src/test/kotlin/com/zeny/desktop/app/reception/ReservationDetailNoShowLabelTest.kt
git commit -m "Dá rótulo ao no-show no histórico e na alocação da reserva" -m "Soul: desenvolvedor"
```

---

### Task 3: Aviso do fechamento automático nos roteiros e no `LEIA-ME`

**Estimativa:** 25 min · **Depende de:** nenhuma · **Risco:** normal

**Files:**
- Modify: `tools/server-env/LEIA-ME.md` (seção "## 3. Parar e recriar a demo")
- Modify: `docs/demo/roteiro-m1.md` e `docs/demo/roteiro-m2.md` (seção "## 0. Antes de começar")
- Modify: `docs/demo/roteiro-m2.md` (seção "## 13. Ficha de registro")
- Create: `tests/test_fechamento_automatico.sh`

**Interfaces:**
- Consumes: `tests/lib/assert.sh` e `tests/lib/roteiro_checks.sh` (`rc_section ARQUIVO TÍTULO`).
- Produces: nada que outra task use.

Texto fixado (a regra 5 revista do README). Este parágrafo entra igual, byte a byte, nos três documentos: no `LEIA-ME`, logo depois do bloco de `down -v`/`up -d` da seção 3; nos roteiros, na seção 0, como parágrafo imediatamente antes da linha `### Folha de senhas`:

```markdown
**A recriação vale só para o dia em que é feita.** O servidor fecha o dia sozinho às 03:00 do dia seguinte: as chegadas confirmadas sem check-in viram "Não compareceu", com multa, as diárias são lançadas e a data operacional passa ao dia seguinte. Recrie a demo (`down -v`, `up -d` e o seed) no mesmo dia do roteiro ou da demo, depois das 03:00, e nunca use no dia seguinte um ambiente recriado na véspera.
```

No passo 13 do `roteiro-m2.md`, a frase `Os rótulos de motivo da viagem e de meio de transporte podem sair como "(código FNRH)": é do servidor, não é reprovação.` passa a ser:

```markdown
Os rótulos de motivo da viagem e de meio de transporte saem com o texto do catálogo da FNRH. Só um código fora do catálogo sai como "(código FNRH)": é do servidor, não é reprovação.
```

- [ ] **Step 1: Escreva `tests/test_fechamento_automatico.sh`, que falha**

No estilo dos `tests/test_roteiro_*.sh` (`source` de `tests/lib/assert.sh` e `tests/lib/roteiro_checks.sh`, `ROOT` definido). Asserções:
- o parágrafo fixado aparece, como linha inteira (`grep -qxF`), em `rc_section tools/server-env/LEIA-ME.md "## 3. Parar e recriar a demo"`, em `rc_section docs/demo/roteiro-m1.md "## 0. Antes de começar"` e em `rc_section docs/demo/roteiro-m2.md "## 0. Antes de começar"`;
- `rc_section docs/demo/roteiro-m2.md "## 13. Ficha de registro"` contém a frase nova como linha inteira e não contém `podem sair como "(código FNRH)"`;
- mutação: o teste falha se o parágrafo sumir de um só dos três (por exemplo, rodando as funções de checagem sobre uma cópia temporária sem ele, em `mktemp -d`, com `trap` que apaga o diretório).

- [ ] **Step 2: Rode e veja falhar**

Run: `bash tests/run.sh tests/test_fechamento_automatico.sh`
Expected: FAIL nas quatro asserções de presença.

- [ ] **Step 3: Edite os três documentos com o texto fixado**

- [ ] **Step 4: Rode a suíte de shell inteira**

Run: `bash tests/run.sh`
Expected: 0 falhas. Os testes dos roteiros e do pacote que comparam linhas com o `LEIA-ME` (`tests/lib/roteiro_checks.sh`, `tests/test_roteiro_m1*_ataque.sh`, `tests/test_roteiro_m2*`, `tests/test_package*`) continuam verdes; se algum travar no parágrafo novo, ajuste o documento (não o teste), salvo regra do teste que contradiga o texto fixado, caso em que você para e relata.

- [ ] **Step 5: Commit**

```bash
git add tools/server-env/LEIA-ME.md docs/demo/roteiro-m1.md docs/demo/roteiro-m2.md tests/test_fechamento_automatico.sh
git commit -m "Avisa do fechamento automático das 03:00 nos roteiros e no LEIA-ME" -m "Soul: desenvolvedor"
```

---

## Verificação da etapa (orquestrador, depois das tasks e do testador de etapa)

Sem código: os resultados vão para o handoff e para o pacote da revisão final. Um Gradle por vez, e nada de Gradle do front enquanto a imagem do servidor é construída.

1. **`tools/server-env/` arquivo por arquivo**, com `git -C <servidor> diff 57eb5fac5b8a 5e06f16d51ff -- docker/` vazio: `compose.override.yaml`, `up.sh`, `down.sh`, `package.sh`, `save-images.sh`, `gen-env.sh`, `lib.sh`, `.env.example` e `LEIA-ME.md`, cada um com resultado e motivo. A configuração efetiva sai só por `compose config --services`, `compose config --images` e `compose config --format json` filtrado por `python3 -I` nos campos `name` e `services.proxy.ports` (como em `tests/test_package.sh`), pelas funções de `tools/server-env/lib.sh`; nunca `config` sem filtro (Global Constraints). Esperado: projeto `zeny-front`, imagem `zeny-hotel-manager:5e06f16d51ff`, porta 8088, nenhum serviço do `docker/gate/`.
2. **Ambiente do zero**, com `MemAvailable` ≥ 6 GiB (esperar e medir de novo, em intervalos de 10 min, até 6 tentativas): `tools/server-env/down.sh -v`, `tools/server-env/up.sh` (constrói `zeny-hotel-manager:5e06f16d51ff`), readiness `UP`.
3. **`smokeTest`:** `./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088`, com os segredos de `.server/seed-secrets.env` do checkout principal, carregados só no ambiente do processo.
4. **Pacote:** `./gradlew :app:desktopJar :demo-seed:seedJar`, `tools/server-env/package.sh` e, se a imagem existir, `tools/server-env/save-images.sh`; `VERSAO.txt` com `server_commit=5e06f16d51ff…` e nenhum `.env` no pacote.
5. **Servidor só leitura:** `status --porcelain` e `worktree list` iguais ao início da etapa (fora mudanças de outras sessões, registradas).
6. **Documentos da etapa** (orquestrador, no fechamento): `docs/pedidos-servidor.md` sem mudança de estado; a E03 na lista "Validações acumuladas no Windows" do `docs/roadmaps/README.md` (regra 7), com a mesma execução do `roteiro-m2.md` e o pacote desta etapa; o tipo `T` da Task 1 no handoff e, se não for `JsonElement`, no passo 1 do ADR 0010.
7. **Gate:** `./gradlew build` + `bash tests/run.sh` + `./gradlew ktlintCheck detekt` no HEAD que vai à revisão final.

**Caminho de falha:**

- **Sem memória** depois das 6 tentativas: o ambiente, o `smokeTest` e o `save-images.sh` ficam pendentes, com a medição no handoff, e a etapa fica `#em-andamento` com "Aguardando o `smokeTest`". Ela **não** conta como concluída para o M3 (a spec diz "o `smokeTest` passa contra o servidor novo, e o M3 pode começar", e a regra 7 só cobre o que o usuário executa no Windows): o próximo orquestrador roda os itens 2 a 4 quando a memória deixar, antes da M3 E01. A ruling vai a "Decisões para o usuário".
- **`up.sh`, readiness ou `smokeTest` falham:** diagnóstico pelos logs (`superpowers:systematic-debugging`). Falha transitória do ambiente (rede, daemon que caiu) tem uma nova tentativa; defeito no front vira rodada de correção do desenvolvedor, com o ciclo de revisão. O `smokeTest` e o seed **nunca** se repetem às cegas: cada tentativa com senha errada gasta as do administrador (`tools/server-env/LEIA-ME.md`, seção 3). Antes de repetir, confira os segredos; se o ambiente ficou num estado parcial, recrie com `down.sh -v`.

## Revisão de arquitetura

- **Veredito:** aprovado com notas (`revisor-arquitetura`, modelo opus), na rodada 1. A rodada 0 bloqueou com 5 Importantes (README e `tests/run.sh` fora da Task 1; caminho para tipo diferente de `JsonElement`; segredos no `compose config`; memória, caminho de falha e invariante do `zeny-front`; símbolo do teste de cobertura no arquivo errado), todos corrigidos.
- **Rodadas:** 1.
- **Notas Menores:**
  - N1: a falta de memória deixava o M3 começar sem o `smokeTest`; resolvido no "Caminho de falha" (a etapa não conta como concluída para o M3 sem ele) e registrado para "Decisões para o usuário".
  - N2: o Step 3 da Task 1 agora remete à linha 2 da tabela do Step 2.
  - N3: o repositório não tem `docs/ARCHITECTURE.md`; o plano foi julgado contra a spec, o design e os ADRs 0002, 0003, 0007, 0008 e 0010.
