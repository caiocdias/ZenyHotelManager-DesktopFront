# M2 E05 — Recados — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a recepção ganha os recados (traces) por setor: uma aba "Recados" no painel do dia, com filtros por setor, situação e vencimento, e uma seção "Recados" no detalhe da reserva. O recepcionista cria (ligado à reserva aberta, ou a uma UH pela aba), edita (com `412` tratado por recarga e aviso) e resolve.

**Architecture:** no `core`, uma `TracesFacade` nova (listar por cursor, ler com `ETag`, criar, alterar com `If-Match`, resolver e listar os setores), ligada no `ZenyCore`. No `app`, uma porta própria `TracesSource` (fora da `ReceptionSource`, que já está grande) com o adaptador no `CoreConnection`, textos puros em `TraceText`, dois *state holders* (`TracesListState`, que serve à aba e à seção do detalhe, e `TraceFormState`, criação e edição) e as telas. O `demo-seed` concede à Recepcionista da demo a leitura de setores, que o modelo do servidor não lhe dá.

**Tech Stack:** o da E01–E04 (Kotlin, Compose Multiplatform, Ktor via `sdk` gerado, kotlinx-coroutines-test, compose-ui-test).

**Spec:**
- a seção "E05 — Recados" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md`, com "Contexto confirmado", "Restrições e invariantes" e o handoff da E01;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.2 (regras transversais; no `412`, recarrega e avisa) e §7 (M2);
- os ADRs `0002`, `0005`, `0007` e `0008`.

**Fatos do contrato (`api/openapi.yaml`) e do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- **Operações (tag `frontdesk`, `FrontdeskApi` do `sdk`):**
  - `listTraces` (`GET /api/v1/traces`): filtros `departmentId`, `dueOn`, `status` (`OPEN`/`RESOLVED`), `reservationId`, `roomId`; cursor, `limit` até 200; ordem de vencimento e id. Exige `frontdesk.traces.read`.
  - `createTrace` (`POST`): `TraceRequest` (`departmentId`, `dueOn`, `text` obrigatórios; `reservationId` **ou** `roomId`; `assigneeUserId` opcional) → `201 TraceResponse` com `ETag`. Exige `frontdesk.traces.manage`. **Sem `Idempotency-Key`** (o servidor não marca a operação como idempotente).
  - `getTrace` (`GET /{id}`) → `TraceResponse` com `ETag`.
  - `updateTrace` (`PUT /{id}`): `If-Match` (`412` com outra versão, `428` sem ele), `TraceChangeRequest` (`departmentId`, `dueOn`, `text`, `assigneeUserId`; o vínculo não muda; **sem `assigneeUserId`, o recado fica sem responsável**); resolvido → `409 TRACE_ALREADY_RESOLVED`. Devolve `TraceResponse` com `ETag`. Exige `frontdesk.traces.manage`.
  - `resolveTrace` (`POST /{id}/resolve`) → `TraceResponse`; já resolvido → `409 TRACE_ALREADY_RESOLVED`. Sem `Idempotency-Key` e sem `If-Match`. Exige `frontdesk.traces.resolve`.
  - Erros da criação/alteração: `400 TRACE_INVALID` (campo `text`, `reservationId` ou `dueOn`), `400 UNKNOWN_DEPARTMENT`, `400 UNKNOWN_RESERVATION`, `400 UNKNOWN_ROOM`, `422 ROOM_INACTIVE`, `400 UNKNOWN_ASSIGNEE`; `404 TRACE_NOT_FOUND`.
  - `TraceResponse`: `id`, `departmentId`, `departmentName`, `dueOn`, `text`, `status` (enum extensível `OPEN`/`RESOLVED`), `reservationId?`, `roomId?`, `roomNumber?`, `assigneeUserId?`, `assigneeName?`, `createdAt`, `createdBy`, `resolvedAt?`, `resolvedBy?`, `version`. **Não traz o código da reserva.**
- **Setores:** `listDepartments` (`GET /api/v1/departments`, `StaffApi` do `sdk`), cursor, exige **`staff.employees.read`**. Pelos modelos do servidor (`StaffPermissions`), só Gerente e Governanta a têm; a **Recepcionista não** (mas tem `frontdesk.traces.manage`). Os seis setores pré-carregados têm ids fixos só na migration do servidor, fora do contrato: o front **não** os embute (ADR 0002).
- **Permissões (`FrontdeskPermissions`, D2 do servidor):** `traces.read` e `traces.resolve` para Gerente, Recepcionista, Governanta e outros setores; `traces.manage` só Gerente e Recepcionista. Nenhuma é sensível.
- **Tempo real:** não há tópico de recados (H3). A lista se atualiza ao abrir a aba, no foco da janela, em "Atualizar" e depois de cada ação.
- **Responsável:** escolher um exige ler colaboradores (`staff.employees.read`, e o vínculo usuário–colaborador). Fica fora desta etapa: o formulário não escolhe responsável, mostra o `assigneeName` quando houver e, na edição, **devolve o `assigneeUserId` que veio do servidor** (senão o PUT o apagaria).

**Ruling do planejamento (vai para "Decisões para o usuário" da etapa):** a lacuna dos setores é resolvida assim, sem depender de pedido ao servidor:
1. O `demo-seed` cria (ou mantém) uma role adicional "Consulta de setores (demo)" com só `staff.employees.read` (`ALL`) e a dá ao usuário `recepcao`. A role do modelo `RECEPTIONIST` não muda.
2. O app trata o `403` dos setores: a criação e a troca de setor ficam indisponíveis com o texto "Sem permissão para ver os setores. Peça ao administrador a consulta de setores.", o filtro por setor some e o resto (lista, edição sem trocar o setor, resolução) funciona.
3. Abre-se o pedido **PF-01** ("Setores dos recados legíveis com `frontdesk.traces.manage`"), registrado em `docs/pedidos-servidor.md` e publicado pelo `brain-escriba` no fechamento da etapa (ADR 0005). Nenhuma etapa depende dele.

Alternativas descartadas: embutir os ids da migration (viola o ADR 0002 e quebra num hotel com setor novo); conceder a permissão na role do modelo `RECEPTIONIST` (muda o modelo de todos os recepcionistas do hotel); verificar só como Gerente (a demo é da recepção).

## Global Constraints

- **Regras do design §6.2:**
  - nada de JSON, UUID ou `code` cru ao usuário;
  - enum extensível tolerado: `status` desconhecido aparece como "Situação desconhecida" e não ganha "Resolver" nem "Editar";
  - datas de negócio (`dueOn`) sem fuso, exibidas `dd/MM/aaaa` (`ReceptionText.date`); entrada pelo `DateInput` do `design`; a etapa não mostra instantes;
  - **concorrência:** o formulário de edição guarda o `ETag` lido. No `412`, relê o recado (valores e `ETag` novos), mostra "Alguém alterou este recado. Confira e salve de novo." e não salva até nova confirmação.
- **Idempotência:** nenhuma das operações de recado declara `Idempotency-Key`; o `core` não manda chave nem `Intent`. Falha de rede ou resposta incompatível na **criação** não é repetida sozinha: o formulário fica aberto com "Não foi possível confirmar se o recado foi criado. Confira a lista antes de tentar de novo." e a lista é atualizada.
- **Permissões efetivas** (de `permissions()`; quem decide é o servidor, `403` tratado):
  - a aba "Recados" e a seção do detalhe aparecem com `frontdesk.traces.read`;
  - "Novo recado" e "Editar" com `frontdesk.traces.manage`; "Resolver" com `frontdesk.traces.resolve`;
  - "Editar" e "Resolver" só em recado `OPEN`.
- **Validação local antes da rede:** texto aparado de 1 a 500 caracteres ("Escreva o recado (até 500 caracteres)."); setor escolhido ("Escolha o setor."); vencimento válido e não antes da data operacional ("O vencimento não pode ser antes da data operacional."); no recado de UH, uma UH escolhida ("Escolha a UH.").
- **Texto do recado:** é texto livre que o servidor nunca loga. Nada de texto do recado em log, em `toString` de estado ou em mensagem de exceção. O formulário mostra a dica "Não escreva dados pessoais do hóspede no recado."
- **Fronteira (ADR 0008):** as features usam só o `core` (e o `design`); o `sdk` só dentro do `core`. Ids inválidos lançam `IllegalArgumentException` antes da rede, sem o valor na mensagem.
- **O estado é dono das travas:** com `busy`, as entradas viram no-op no próprio estado; resultado que chega depois de `close()` é descartado.
- **Testes:**
  - não dormem (tempo virtual do `kotlinx-coroutines-test`);
  - verificação ao vivo só com o opt-in próprio `ZENY_LIVE_TRACES` e o arquivo 600; com o opt-in e sem credencial, falha;
  - testes existentes só mudam onde a tarefa manda.
- **Gate e commits:**
  - `./gradlew build --max-workers=2` (inteiro) e `bash tests/run.sh` verdes antes de cada commit; nunca `./gradlew --stop`;
  - UI e documentação em pt-BR, código em inglês;
  - commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **Edição concorrente:** dois formulários abertos no mesmo recado; o segundo a salvar recebe `412`, relê, avisa e só salva depois de nova confirmação, com o `ETag` novo. Testes `preconditionFailedReloadsAndWarns` e `saveAfterReloadUsesNewEtag` (Task 4); `updateSendsIfMatch` (Task 1); e o critério ao vivo (Task 8).
2. **Edição apaga o responsável:** o PUT sem `assigneeUserId` limpa o responsável. Teste `updateKeepsAssignee` (Task 4) e `updateBodyCarriesAssignee` (Task 1).
3. **Criação com resultado desconhecido** (rede ou resposta incompatível): sem nova tentativa automática, aviso e lista atualizada; uma segunda criação só com clique novo. Teste `networkFailureOnCreateDoesNotRetry` (Task 4).
4. **Recepcionista sem `staff.employees.read`** (o modelo do servidor): a aba abre, o filtro por setor some, "Novo recado" mostra o texto da permissão, editar mantém o setor atual, resolver funciona. Testes `departmentsForbiddenKeepsList` (Task 3) e `editWithoutDepartmentsKeepsCurrent` (Task 4).
5. **Resolver um recado já resolvido por outro setor** (`409 TRACE_ALREADY_RESOLVED`): a mensagem em pt-BR e a lista atualizada, sem erro preso. Testes `resolveAlreadyResolvedRefreshes` (Task 3).

---

### Task 1: `TracesFacade` no `core`

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (contrato público; concorrência)

**Files:**
- Create: `core/src/main/kotlin/com/zeny/desktop/core/facade/TracesFacade.kt`
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/ZenyCore.kt` (a `SdkApis` ganha `staff: StaffApi`; o `ZenyCore` ganha `val traces: TracesFacade`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/TracesFacadeTest.kt`

**Interfaces:**
- Produces (pacote `com.zeny.desktop.core.facade`):
  - `enum class TraceStatusFilter { OPEN, RESOLVED }`;
  - `data class TraceFilter(val departmentId: String? = null, val dueOn: BusinessDate? = null, val status: TraceStatusFilter? = null, val reservationId: String? = null, val roomId: String? = null)`;
  - `data class TraceVersion(val trace: TraceResponse, val etag: String)`; `toString` sem o texto do recado;
  - `data class Department(val id: String, val name: String)`;
  - `data class NewTrace(val departmentId: String, val dueOn: BusinessDate, val text: String, val reservationId: String?, val roomId: String?)`, com exatamente um de `reservationId`/`roomId` (senão `IllegalArgumentException` antes da rede); `toString` sem o texto;
  - `data class TraceChange(val departmentId: String, val dueOn: BusinessDate, val text: String, val assigneeUserId: String?)`; `toString` sem o texto;
  - `class TracesFacade internal constructor(frontdesk: FrontdeskApi, staff: StaffApi, executor: ApiExecutor)` com:
    - `suspend fun page(filter: TraceFilter, cursor: String?, limit: Int): Page<TraceResponse>`;
    - `suspend fun trace(id: String): TraceVersion`;
    - `suspend fun create(input: NewTrace): TraceVersion`;
    - `suspend fun update(id: String, change: TraceChange, etag: String): TraceVersion` (`If-Match = etag`);
    - `suspend fun resolve(id: String): TraceResponse`;
    - `suspend fun departments(): List<Department>` (todas as páginas, `limit` 200, na ordem do servidor).
  - Sem `ETag` onde o contrato o promete (`trace`, `create`, `update`): `ApiError.IncompatibleResponse`, como `reservationVersion`. Nenhuma chamada leva `Idempotency-Key` (`executor.execute` sem `Intent`).

- [ ] **Step 1: Testes que falham** (no molde de `RoomMoveFacadeTest`, com o `MockEngine`):
  - `pageSendsFilters`: os cinco filtros e o cursor vão na query (`dueOn` como `AAAA-MM-DD`, `status=OPEN`); filtro nulo não vai;
  - `traceCarriesEtag` e `traceWithoutEtagIsIncompatible`;
  - `createSendsBodyWithoutIdempotencyKey`: corpo com `departmentId`, `dueOn`, `text`, `reservationId` e sem `roomId`; nenhum header `Idempotency-Key`;
  - `createNeedsExactlyOneLink`: os dois ou nenhum → `IllegalArgumentException` sem chamada;
  - `updateSendsIfMatch` e `updateBodyCarriesAssignee` (com `assigneeUserId` preenchido, ele vai; nulo, não vai);
  - `updatePreconditionFailedIsTyped`: `412` → `ApiError.PreconditionFailed`;
  - `resolveAlreadyResolvedIsProblem`: `409 TRACE_ALREADY_RESOLVED` → `ApiError.Problem` com o código;
  - `departmentsReadsAllPages` e `departmentsForbiddenIsTyped` (`403` → o `ApiError` de acesso negado do `ProblemMapper`);
  - `invalidIdsFailBeforeNetwork` e `toStringHidesText` (`NewTrace`, `TraceChange`, `TraceVersion`).
- [ ] **Step 2:** rode `./gradlew :core:test --tests '*TracesFacadeTest*' --max-workers=2` → FAIL (classe inexistente).
- [ ] **Step 3:** implemente `TracesFacade.kt` e ligue no `ZenyCore` (`val staff: StaffApi by lazy { StaffApi(base) }`).
- [ ] **Step 4:** o mesmo comando → PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a fachada de recados ao core`).

---

### Task 2: Porta `TracesSource`, adaptador e textos

**Estimativa:** 35 min · **Depende de:** Task 1 · **Risco:** normal (repasse fino e textos puros)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TracesSource.kt`
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TraceText.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (`fun tracesSource(): TracesSource? = null` na interface; override no `CoreConnection`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/TraceTextTest.kt`, `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/FakeTracesSource.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces (pacote `com.zeny.desktop.app.reception.traces`):
  - `interface TracesSource` com `permissions(): Set<String>`, `businessDay(): BusinessDay`, `page(filter, cursor, limit)`, `trace(id)`, `create(input)`, `update(id, change, etag)`, `resolve(id)`, `departments()`, `reservationCode(id: String): String` (o `code` de `core.reservations.reservation(id)`) e `rooms(): List<RoomOption>` (as UHs ativas de `core.rooms.statuses()`, em ordem de número);
  - `data class RoomOption(val id: String, val number: String)`;
  - `object TraceText`:
    - `status(value: String): ChipModel` (`OPEN` → "Aberto", `RESOLVED` → "Resolvido", outro → "Situação desconhecida");
    - `message(error: ApiError): String`, com os textos de recado e, fora deles, o `errorMessage` comum da recepção:
      - `TRACE_INVALID` → "O recado está inválido: confira o texto (até 500 caracteres) e o vencimento (não antes da data operacional).";
      - `TRACE_ALREADY_RESOLVED` → "Este recado já foi resolvido.";
      - `TRACE_NOT_FOUND` → "O recado não existe mais. Atualize a lista.";
      - `UNKNOWN_DEPARTMENT` → "O setor escolhido não existe mais. Escolha outro.";
      - `UNKNOWN_RESERVATION` → "A reserva do recado não existe mais.";
      - `UNKNOWN_ROOM` → "A UH escolhida não existe mais. Escolha outra.";
      - `ROOM_INACTIVE` → "A UH está inativa. Escolha outra.";
      - `UNKNOWN_ASSIGNEE` → "O responsável do recado não está mais vinculado a um colaborador. Salve de novo para deixá-lo sem responsável." (o formulário então manda `assigneeUserId` nulo na próxima tentativa, ver Task 4);
      - `PreconditionFailed` → "Alguém alterou este recado. Confira e salve de novo.";
    - `link(trace, reservationCode: String?): String` ("Reserva R-…", "Reserva" sem o código, "UH 203");
    - `DEPARTMENTS_DENIED = "Sem permissão para ver os setores. Peça ao administrador a consulta de setores."`.
  - `FakeTracesSource` (teste): em memória, com versões e `ETag` por recado, `412` quando o `etag` não confere, `409` ao resolver/alterar resolvido, falhas injetáveis por operação e o registro das chamadas. Os testes das Tasks 3, 4 e 5 o usam.

- [ ] **Step 1: Testes que falham:** `TraceTextTest` — `statusLabels` (incluindo valor desconhecido), `problemTexts` (cada código acima e um código fora do catálogo nunca aparece cru), `linkTexts`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*TraceTextTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente porta, adaptador (só repasse ao `core.traces`, `core.reservations`, `core.rooms`, `core.account` e `core.hotel`) e `TraceText`; escreva o `FakeTracesSource`.
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta a porta e os textos dos recados`).

---

### Task 3: `TracesListState` (aba e seção do detalhe)

**Estimativa:** 45 min · **Depende de:** Task 2 · **Risco:** alto (núcleo da etapa; concorrência de cargas)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TracesListState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/TracesListStateTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `enum class DueFilter { ANY, TODAY }`;
  - `data class TraceRow(val id: String, val due: String, val department: String, val link: String, val text: String, val status: ChipModel, val assignee: String?, val open: Boolean, val reservationId: String?)`; `toString` sem `text`;
  - `class TracesListState(source: TracesSource, scope: CoroutineScope, fixedReservationId: String? = null)`:
    - `val table: TableState<TraceRow>` (cursor do `core`, página de 50);
    - filtros `departmentFilter: String?`, `statusFilter: TraceStatusFilter?` (padrão `OPEN` na aba; nulo, ou seja todos, com `fixedReservationId`), `dueFilter: DueFilter` (padrão `ANY`); `setDepartment`, `setStatus`, `setDue` recarregam a tabela;
    - `departments: List<Department>`, `departmentsDenied: Boolean` (`403` nos setores; outra falha deixa a lista vazia com `departmentsError`);
    - `permissions: Set<String>`, `canCreate` (`traces.manage` e setores legíveis), `canEdit(row)`, `canResolve(row)`;
    - `start()`, `refresh()`, `resolve(id: String)`, `error: ApiError?` (falha de ação ou de carga, mostrada por `TraceText.message`), `busy: Boolean`, `close()`;
  - o código da reserva de cada linha vem de `reservationCode`, com cache por id na vida do estado e no máximo 4 leituras em paralelo; falha de uma leitura vira "Reserva" sem código, sem derrubar a lista. Com `fixedReservationId`, o vínculo da linha é omitido (todas são da reserva aberta) e nenhuma leitura de código é feita.
  - `dueFilter = TODAY` manda `dueOn` = data operacional do `businessDay()`.

- [ ] **Step 1: Testes que falham** (com `FakeTracesSource` e `runTest`):
  - `startLoadsOpenTracesByDefault` e `fixedReservationListsAllOfIt` (filtro `reservationId`, sem `status`);
  - `filtersGoToSource` (setor, situação e `TODAY` com a data operacional);
  - `departmentsForbiddenKeepsList`: `403` nos setores → `departmentsDenied`, `canCreate` falso, a tabela carrega;
  - `resolveRefreshesList`: depois de resolver, o recado sai da lista de abertos;
  - `resolveAlreadyResolvedRefreshes`: `409 TRACE_ALREADY_RESOLVED` → `error` com o texto e a lista recarregada;
  - `resolveWhileBusyIsNoOp` e `resultAfterCloseIsDiscarded`;
  - `reservationCodesAreCachedAndBounded`: duas páginas com a mesma reserva → uma leitura; nunca mais de 4 simultâneas;
  - `reservationCodeFailureShowsGenericLink`;
  - `unknownStatusHasNoActions` e `rowToStringHidesText`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*TracesListStateTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente no molde do `DayPanelState` (escopo próprio cancelado no `close()`).
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o estado da lista de recados`).

---

### Task 4: `TraceFormState` (criação e edição)

**Estimativa:** 45 min · **Depende de:** Task 2 · **Risco:** alto (concorrência; idempotência ausente no contrato)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TraceFormState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/TraceFormStateTest.kt`

**Interfaces:**
- Consumes: Task 2 (e `Department` da Task 1).
- Produces:
  - `sealed interface TraceTarget { data class ForReservation(val reservationId: String, val code: String) : TraceTarget; data object ForRoom : TraceTarget; data class Existing(val traceId: String) : TraceTarget }`;
  - `class TraceFormState(source: TracesSource, scope: CoroutineScope, target: TraceTarget, departments: List<Department>, departmentsDenied: Boolean, onSaved: () -> Unit)`:
    - campos `text`, `dueText` (`dd/MM/aaaa`, inicial = data operacional na criação), `departmentId`, `roomId` (só `ForRoom`), com setters;
    - `rooms: List<RoomOption>` (carregadas só em `ForRoom`), `assigneeName: String?` (só leitura, na edição);
    - `start()` (na edição, lê `trace(id)` e guarda `ETag`, `assigneeUserId` e os campos), `save()`, `close()`;
    - `fieldErrors: Map<String, String>` (validação local da Global Constraints), `error: String?`, `notice: String?`, `busy: Boolean`, `saved: Boolean`;
    - na edição sem setores legíveis, o seletor de setor mostra só o setor atual (`departmentName`) e o mantém.
  - Regras do `save()`:
    - criação: chama `create` uma vez; sucesso → `saved` e `onSaved()`; `Network`/`IncompatibleResponse`/`SessionEnded` → `error` com o texto de resultado desconhecido da Global Constraints e `onSaved()` (a lista atualiza), sem repetir;
    - edição: `update(id, change, etag)` com o `assigneeUserId` lido; `412` → relê o recado, troca campos e `ETag` e põe `notice` = texto do `412`; `409 TRACE_ALREADY_RESOLVED` → `error` e `onSaved()`; `UNKNOWN_ASSIGNEE` → `error` e a próxima tentativa vai sem responsável.

- [ ] **Step 1: Testes que falham:**
  - `createForReservationSendsLink` e `createForRoomNeedsRoom`;
  - `localValidationBeforeNetwork`: texto vazio, 501 caracteres, data inválida, data antes da operacional e setor ausente → `fieldErrors`, nenhuma chamada;
  - `networkFailureOnCreateDoesNotRetry`: uma chamada só, `error` com o aviso, `onSaved` chamado, `saved` falso;
  - `editLoadsCurrentValues`, `updateKeepsAssignee`;
  - `preconditionFailedReloadsAndWarns` e `saveAfterReloadUsesNewEtag`;
  - `alreadyResolvedOnEditShowsMessage`;
  - `unknownAssigneeClearsOnRetry`;
  - `editWithoutDepartmentsKeepsCurrent`;
  - `saveWhileBusyIsNoOp` e `resultAfterCloseIsDiscarded`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*TraceFormStateTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente no molde do `RoomMoveState` (travas no estado, escopo próprio).
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o formulário de recados`).

---

### Task 5: Telas dos recados

**Estimativa:** 45 min · **Depende de:** Tasks 3 e 4 · **Risco:** normal (composição sobre estados testados)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TracesPanel.kt`
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/traces/TraceFormDialog.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/TracesPanelTest.kt`, `app/src/test/kotlin/com/zeny/desktop/app/reception/traces/TraceFormDialogTest.kt`

**Interfaces:**
- Consumes: Tasks 3 e 4.
- Produces:
  - `@Composable fun TracesPanel(list: TracesListState, onNew: (() -> Unit)?, onEdit: (TraceRow) -> Unit, onOpenReservation: ((String) -> Unit)?, compact: Boolean = false)`:
    - filtros (setor, só sem `departmentsDenied`; situação "Abertos"/"Resolvidos"/"Todos"; vencimento "Todos"/"Hoje"), tabela (`DataTable`) com Vencimento, Setor, Vínculo, Recado, Responsável e Situação; "Resolver" e "Editar" por linha conforme `canResolve`/`canEdit`; "Novo recado" quando `onNew` não é nulo; com `departmentsDenied` e sem `onNew`, o texto `DEPARTMENTS_DENIED`; `error` numa faixa com "Tentar de novo";
    - `compact = true` (seção do detalhe): sem filtros e sem coluna de vínculo;
    - clicar numa linha de reserva chama `onOpenReservation(reservationId)` quando não nulo;
    - tags de teste: `traces-panel`, `trace-new`, `trace-resolve-<índice>`, `trace-edit-<índice>`, `traces-error`.
  - `@Composable fun TraceFormDialog(state: TraceFormState, onClose: () -> Unit)`: título "Novo recado" ou "Editar recado"; vínculo (código da reserva, seletor de UH ou o vínculo atual); setor; vencimento; texto (contador `n/500`); dica de dados pessoais; responsável só leitura; `notice`, `error` e `fieldErrors`; "Salvar" (desabilitado com `busy`) e "Cancelar"; fecha sozinho com `saved`. Tags: `trace-form`, `trace-text`, `trace-due`, `trace-save`.

- [ ] **Step 1: Testes que falham** (compose-ui-test, `FakeTracesSource`):
  - `panelShowsRowsWithoutIds`: nenhum UUID e nenhum `code` na árvore;
  - `resolveButtonOnlyForOpenWithPermission`;
  - `deniedDepartmentsHideFilterAndShowText`;
  - `formShowsFieldErrors` e `formShowsPreconditionNotice`;
  - `formClosesAfterSave`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*TracesPanelTest*' --tests '*TraceFormDialogTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente com os componentes do `design` (`DataTable`, `StatusChip`, `ErrorState`, diálogos de `feedback`).
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta as telas dos recados`).

---

### Task 6: Recados na recepção (aba e detalhe)

**Estimativa:** 40 min · **Depende de:** Task 5 · **Risco:** normal (ligação na tela existente)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` (chip "Recados" depois das três listas; a seção "Recados" no `DetailPanel`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (a `ReceptionArea` cria os estados de recados com `connection.tracesSource()`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionTracesTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 4 e 5.
- Produces:
  - `ReceptionScreen` ganha o parâmetro opcional `traces: ReceptionTraces? = null` (padrão nulo, os testes existentes não mudam), com `data class ReceptionTraces(val list: TracesListState, val forReservation: (String) -> TracesListState, val form: (TraceTarget) -> TraceFormState)`;
  - com `traces` e `frontdesk.traces.read`, o chip `tab-TRACES` ("Recados") troca a tabela do dia pelo `TracesPanel`; "Novo recado" ali abre o formulário `ForRoom`;
  - o detalhe aberto mostra a seção "Recados" (`TracesPanel` compacto) da reserva, com "Novo recado" → `ForReservation(id, code)`; o estado da seção nasce e fecha com a reserva aberta (troca de reserva fecha o anterior);
  - depois de salvar ou resolver, a lista da aba e a seção do detalhe atualizam; o ganho de foco da janela também atualiza a lista da aba;
  - na `ReceptionArea`, os estados nascem com a chave do `sessionId` e fecham no `onDispose`, como o painel.

- [ ] **Step 1: Testes que falham:**
  - `tracesTabHiddenWithoutReadPermission`;
  - `tracesTabShowsPanel`;
  - `detailShowsReservationTraces` e `newTraceFromDetailLinksReservation`;
  - `savingRefreshesTabAndDetail`;
  - `switchingReservationClosesPreviousSection`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*ReceptionTracesTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; gate verde (os testes existentes da recepção continuam verdes sem mudança).
- [ ] **Step 5: Commit** (`Liga os recados à recepção`).

---

### Task 7: Consulta de setores para a recepção da demo

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (concessão de permissão)

**Files:**
- Modify: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/people/UserGateway.kt` (`suspend fun ensureRole(name: String, description: String, grants: Map<String, String>): String`, devolve o id; cria se não existe, reativa se inativa e regrava as concessões se diferentes, com `If-Match`)
- Modify: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/sdk/SdkUserGateway.kt`
- Modify: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/people/UserSeeder.kt`
- Test: `demo-seed/src/test/kotlin/com/zeny/desktop/seed/people/UserSeederRoleTest.kt`

**Interfaces:**
- Produces: o usuário `recepcao` recebe, além da role do modelo `RECEPTIONIST`, a role "Consulta de setores (demo)", descrição "Leitura de setores para os recados da recepção (pedido PF-01).", concessão `staff.employees.read` = `ALL`. A role do modelo não muda; o relatório do seed conta a role como criada ou mantida.

- [ ] **Step 1: Testes que falham** (`UserSeederRoleTest`, com o gateway falso do seed): `createsRoleAndGivesItToReceptionist`, `keepsExistingRole` (re-execução não cria outra nem regrava), `reactivatesInactiveRole`, `rewritesDifferentGrants`, `otherUsersDoNotGetRole`.
- [ ] **Step 2:** `./gradlew :demo-seed:test --tests '*UserSeederRoleTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Concede a consulta de setores à recepção da demo`).

---

### Task 8: Verificação ao vivo dos recados

**Estimativa:** 40 min · **Depende de:** Tasks 6 e 7 · **Risco:** alto (escreve no servidor; critérios da etapa)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveTracesCheck.kt`
- Modify: `app/build.gradle.kts` (input `zenyLiveTraces` de `ZENY_LIVE_TRACES`, como os outros opt-ins)

**Interfaces:**
- Consumes: Tasks 1–7.
- Produces: `LiveTracesCheck`, pulada sem `ZENY_LIVE_TRACES=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (a Recepcionista) e falha sem eles. Usa o `CoreConnection` real e os estados reais, no molde de `LiveStayActionsCheck`.

- [ ] **Step 1: A verificação** (contra o `zeny-front` com o seed rodado de novo, logado como Recepcionista):
  - `createdTraceAppearsInListAndDetail`: cria um recado para a primeira reserva hospedada, setor "Governança", vencimento hoje; ele aparece na aba (abertos) e na seção do detalhe;
  - `resolvingUpdatesPendingList`: resolve esse recado; ele sai dos abertos e aparece em "Resolvidos";
  - `concurrentEditShowsReloadAndNotice`: dois `TraceFormState` no mesmo recado aberto; o primeiro salva; o segundo recebe `412`, mostra o aviso, relê e salva com sucesso na segunda confirmação;
  - captura `app/build/live/traces.png` da aba com o recado.
- [ ] **Step 2:** sem o opt-in → pulada; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 3:** o orquestrador roda o seed de novo e a verificação: `(set -a; . <arquivo 600>; set +a; ZENY_LIVE_TRACES=1 ./gradlew :app:test --tests '*LiveTracesCheck*' --rerun --max-workers=2)` → verde.
- [ ] **Step 4: Commit** (`Verifica os recados ao vivo`).
