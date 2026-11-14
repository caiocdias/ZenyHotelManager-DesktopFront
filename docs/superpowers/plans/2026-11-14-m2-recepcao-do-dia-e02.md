# M2 E02 — Alocação de UH e check-in — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a partir de uma chegada aberta no detalhe, o recepcionista aloca a UH (sugestões do servidor com o status de cada UH), solta ou marca "não mover", vê a cotação do early check-in e decide cobrá-lo, confirma ou troca o hóspede principal (busca por nome ou documento, ou cadastro mínimo num diálogo) e faz o check-in. Os erros de regra aparecem em pt-BR com o próximo passo, a aprovação de superior entra quando o servidor pede, e o painel, o detalhe e o mapa de UHs refletem a mudança.

**Architecture:** no `core`, as escritas e leituras novas ficam nas fachadas existentes (ADR 0008, opção b): `ReservationsFacade` ganha alocação, cotação e check-in (este como `ApprovableAction`, com a `Intent` da tela), e `GuestsFacade` ganha busca e cadastro. No `app`, a porta `ReceptionSource` cresce com essas operações (o `GuestResponse` continua sem sair do adaptador: vira `GuestPick`). Três *state holders* testáveis com o falso compõem o fluxo: `RoomAllocationState`, `GuestPickerState` e `CheckInState`. A tela ganha o diálogo `CheckInDialog`, aberto do detalhe por "Fazer check-in", e o detalhe passa a listar os hóspedes da estadia.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2, kotlinx-coroutines (`runTest`), `ApiExecutor`/`Intent`/`ApprovableAction` do `core`, `ApprovalCoordinator`/`LocalApprovalCoordinator` do `app`, `FormState`/`FormTextField`/`DateInput`/`DocumentInput` do `design`, `displayStatus` de `app/rooms/RoomDisplay.kt`, kotlin-test, `ktor-client-mock` (testes do `core`), `runComposeUiTest`.

**Spec:** seção "E02 — Alocação de UH e check-in" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` (com "Contexto confirmado", "Restrições e invariantes", H1 e H2), o handoff da E01 na mesma seção, o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.2 e §6.5 e os ADRs `0007` e `0008`. Contrato `api/openapi.yaml`:
- `listReservationRoomSuggestions` (`GET …/room-suggestions`, `featureIds` opcional);
- `assignReservationRoom` (`RoomAssignRequest`: `roomId`, `doNotMove?`, `reason?`), `unassignReservationRoom` e `setReservationRoomDoNotMove` (`DoNotMoveRequest`), as três devolvendo `ReservationDetailResponse` e **sem** `Idempotency-Key`;
- `getEarlyLateQuote` (`EarlyLateQuoteResponse`: `earlyCheckIn.offered`, `fee`, `limitTime`, `from`, `until`, `timezone`);
- `checkInReservation` (`Idempotency-Key` obrigatório; `CheckInRequest`: `guests` [`guestId`, `primary`], `guestCount?`, `roomId?`, `earlyCheckInFee?`; devolve `CheckInResponse` com `reservation`, `room` e `restrictionAlerts`);
- `listGuests` (`name`, `cursor`, `limit`), `searchGuestsByDocument` (corpo `type`, `number`, `issuingCountry?`) e `createGuest` (`CreateGuestRequest`: `profile` [`fullName`, `vip`, `nationality`, `birthDate`…], `documents` [`type`, `number`, `issuingCountry?`]).

**Fatos do contrato e do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- **Exigências do check-in:** hóspede com nacionalidade, data de nascimento e documento aceito (CPF do brasileiro adulto; CPF ou RG do menor brasileiro; passaporte, RNM ou identidade estrangeira do estrangeiro), senão `422 GUEST_DATA_INCOMPLETE`. **A H1 não vale como escrita:** o cadastro mínimo precisa de nome, documento, nacionalidade e data de nascimento (mitigação prevista na etapa).
- **Ordem das recusas do check-in:** `RESERVATION_NOT_FOUND`, `RESERVATION_TRANSITION_NOT_ALLOWED`, `CHECK_IN_BEFORE_ARRIVAL`, `FOLIO_CLOSED`, `UNKNOWN_GUEST`, `GUEST_ANONYMIZED`, `GUEST_DATA_INCOMPLETE`, `ROOM_NOT_ASSIGNED`, `ROOM_NOT_READY` (RN-05: vaga, fora de OOO e inspecionada, ou limpa sem inspeção pendente), as do menor, `EARLY_LATE_FEE_CHANGED` e `EARLY_LATE_NOT_OFFERED`. Tudo numa transação: nenhuma recusa deixa efeito parcial.
- **Alocação:**
  - recusas: `ROOM_ALREADY_ASSIGNED` (a UH está com outra reserva em parte do período), `ROOM_ASSIGNMENT_LOCKED` ("não mover"), `ROOM_OUT_OF_ORDER`, `ROOM_TYPE_MISMATCH`, `ROOM_INACTIVE`, `ROOM_ASSIGNMENT_NOT_ALLOWED` (fora de pré-reserva ou confirmada) e `RESERVATION_STAY_ENDED`;
  - as sugestões vêm em ordem (OOS por último) com `current`, `outOfService` e `matchesFloorPreference`.
- **Permissões:** o modelo Recepcionista tem `frontdesk.stays.checkin`, `reservations.assignments.manage`, `guests.profiles.read`, `guests.profiles.manage` e `rooms.status.read`. O check-in não é ação sensível no servidor fixado; a aprovação só entra se o servidor pedir.
- **Hóspedes da reserva:** `ReservationResponse.guests` (`StayGuestResponse`: `guestId`, `primary`, `checkedInAt`) traz os hóspedes registrados no check-in. O titular (`holderGuestId`) é o da reserva.
- **Seed (H2):** os hóspedes do seed têm data de nascimento e nacionalidade BR. Na data da demo há chegadas `CONFIRMED` com e sem UH (contagens da E01: chegadas=11, hospedados=7).

## Global Constraints

- **Regras do design §6.2:**
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado;
  - **uma `Idempotency-Key` por intenção**: a mesma `Intent` no reenvio após erro de rede; a repetição aprovada segue a decisão D2 do `core` (`executeApproved`);
  - `errors[]` vai para o campo do formulário (`FormState.applyServerErrors`);
  - ações por permissão efetiva, com o servidor decidindo (`403` tratado);
  - dinheiro em `BigDecimal` (`Money`);
  - instantes no fuso do hotel (`HotelFacade.businessDay().zone` ou `EarlyLateQuoteResponse.timezone`), nunca no da JVM.
- **Fronteira (ADR 0008):** `app` e `design` (main e test) não importam `com.zeny.desktop.sdk.client.*`, `com.zeny.desktop.sdk.util.*`, `io.ktor.client.*`, `de.quati.ogen.client.*` nem usam `@RawSdkAccess`; usam os modelos `com.zeny.desktop.sdk.model.*` e as fachadas do `core` (inclusive `com.zeny.desktop.core.http.Intent` e `com.zeny.desktop.core.facade.ApprovableAction`).
- **Dados pessoais:**
  - documento sempre mascarado na tela (só o `maskedNumber` do servidor);
  - o número digitado no cadastro ou na busca vai só no corpo da requisição (nunca na URL), não fica no estado depois da chamada e nunca aparece em log, `toString`, mensagem de exceção nem cache de disco;
  - do perfil, o `app` guarda só nome e primeiro documento mascarado (`HolderSummary`, `GuestPick`);
  - seed e testes usam só dados fictícios (CPF de teste gerado com dígito verificador válido).
- **Aprovação:** ação recusada com `APPROVAL_REQUIRED` passa sempre pelo `ApprovalCoordinator` da sessão (`LocalApprovalCoordinator`). Cancelar a aprovação não é erro.
- **Atualização:** depois de cada ação que muda a reserva (alocar, soltar, "não mover", check-in), chame `panel.refresh()` e `detail.reload()`. O mapa de UHs se atualiza pelo SSE `rooms` que já assina.
- **Testes:**
  - não dormem (`runTest`, tempo virtual; relógio injetado onde houver horário);
  - verificação ao vivo só com o opt-in próprio e as variáveis do arquivo 600; com o opt-in e sem credencial, falha;
  - testes existentes só mudam onde a tarefa manda; os demais são do testador.
- **Gate e commits:**
  - antes de cada commit, `./gradlew build` (inteiro) e `bash tests/run.sh` verdes; memória curta: `--max-workers=2`; nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez;
  - textos de UI em pt-BR; código em inglês; pacotes `com.zeny.desktop.core.facade` e `com.zeny.desktop.app.reception`;
  - commits em pt-BR, no estilo do histórico, com as linhas `Soul: <soul>` e `Co-Authored-By: …` consecutivas no último parágrafo.

## Review Focus

1. **Erro de rede ao confirmar o check-in e nova confirmação:** a segunda chamada leva a mesma `Idempotency-Key` (o servidor pode ter feito o check-in e a resposta se perdeu). Mudar UH, hóspede ou cobrança de early troca a chave. Testes `retryAfterNetworkErrorReusesKey` e `changingInputsRenewsKey` (Task 8) e `checkInRetrySendsSameKey` (Task 2).
2. **Dois cliques em "Fazer check-in", "Alocar" ou "Cadastrar":** uma chamada só. Testes `doubleConfirmSendsOnce` (Task 8), `doubleAssignSendsOnce` (Task 6) e `doubleCreateSendsOnce` (Task 7).
3. **A UH deixa de estar pronta entre a alocação e o check-in** (governança marca suja, ou UH ocupada por outro terminal): `ROOM_NOT_READY` aparece com o próximo passo, o diálogo continua aberto e nada muda na reserva. Testes `roomNotReadyShowsNextStep` (Task 8) e a verificação ao vivo `notReadyRoomLeavesNoEffect` (Task 11).
4. **Aprovação cancelada ou sessão encerrada no meio do check-in:**
   - cancelar não mostra erro e deixa o diálogo como estava;
   - `SessionEnded` mostra a mensagem de sessão.

   Testes `approvalCancelledLeavesDialog` e `sessionEndedShowsMessage` (Task 8).
5. **Número de documento digitado:**
   - vai só no corpo, nunca na URL;
   - some do estado depois da busca ou do cadastro;
   - não aparece em `toString`.

   Testes `documentSearchKeepsNumberOutOfUrl` (Task 3) e `numberClearedAfterSearchAndCreate` e `pickerToStringHidesData` (Task 7).

---

### Task 1: Alocação e cotação no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (contrato público do `core`; resposta externa)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`; `core/src/main/kotlin/com/zeny/desktop/core/error/ErrorCatalog.kt` (só a linha de `ROOM_ALREADY_ASSIGNED`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/ReservationAssignmentsFacadeTest.kt` (no padrão de `ReservationsFacadeTest`, com `MockEngine`)

**Interfaces:**
- Produces (membros de `ReservationsFacade`; id que não é UUID → `IllegalArgumentException("id de reserva inválido")` ou `("id de UH inválido")` antes da rede):
  - `suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse>` (sem `featureIds`; devolve `items` na ordem do servidor);
  - `suspend fun assignRoom(id: String, roomId: String, doNotMove: Boolean?): ReservationDetailResponse` (`reason` não enviado: o servidor decide `INITIAL`/`OPERATIONAL`);
  - `suspend fun unassignRoom(id: String): ReservationDetailResponse`;
  - `suspend fun setDoNotMove(id: String, doNotMove: Boolean): ReservationDetailResponse`;
  - `suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse`.
- `ErrorCatalog`: `"ROOM_ALREADY_ASSIGNED"` passa a `"A UH já está alocada a outra reserva em parte do período."` (o texto atual descreve outro caso).

- [ ] **Step 1: Testes que falham:**
  - `suggestionsReturnItemsInOrder`;
  - `assignSendsRoomAndDoNotMove`: corpo com `roomId` e `doNotMove`; sem `doNotMove`, o campo não vai; nenhum header `Idempotency-Key`;
  - `unassignAndDoNotMovePostToTheirPaths`;
  - `quoteReadsEarlyOffer`: `fee` `"50.00"`, `limitTime`, `from` e `until` intactos;
  - `invalidIdsFailBeforeNetwork`;
  - `alreadyAssignedMessage`: `409 ROOM_ALREADY_ASSIGNED` → `ApiError.Problem` com o texto novo;
  - `notAllowedIsProblem`: `409 ROOM_ASSIGNMENT_NOT_ALLOWED`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*ReservationAssignmentsFacadeTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a alocação de UH e a cotação de early ao core`).

---

### Task 2: Check-in no `core`

**Estimativa:** 35 min · **Depende de:** Task 1 (mesmo arquivo) · **Risco:** alto (contrato público; idempotência e aprovação)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/CheckInFacadeTest.kt`

**Interfaces:**
- Produces:
  - `data class CheckInGuest(val guestId: String, val primary: Boolean)`;
  - `data class CheckInInput(val guests: List<CheckInGuest>, val guestCount: Int?, val roomId: String?, val earlyCheckInFee: Money?)`;
  - `fun checkIn(id: String, input: CheckInInput, intent: Intent): ApprovableAction<CheckInResponse>`, montada como o `ZenyCore.approvable` de `Approval.kt` (sem token: `executor.execute(intent, …)`; com token: `executor.executeApproved(intent, token, …)`);
  - ids inválidos (reserva, UH, hóspede) → `IllegalArgumentException` antes da rede, sem o id na mensagem;
  - `earlyCheckInFee` vai como o texto decimal de `Money` com 2 casas (`"50.00"`); nulo, o campo não vai;
  - `guestCount` e `roomId` nulos não vão.

- [ ] **Step 1: Testes que falham:**
  - `checkInSendsIdempotencyKeyAndBody`;
  - `checkInRetrySendsSameKey`: a mesma `Intent` em duas execuções sem token, sendo a primeira com falha de rede do `MockEngine`, leva a mesma chave;
  - `approvedRetryCarriesTokenAndNewKey` (decisão D2);
  - `earlyFeeAsDecimalText`;
  - `nullOptionalsOmitted`;
  - `roomNotReadyIsProblem`: `409 ROOM_NOT_READY` → `ApiError.Problem` com o texto do catálogo;
  - `approvalRequiredSurfaces`: `403 APPROVAL_REQUIRED` → `ApiError.ApprovalRequired`;
  - `invalidIdsFailBeforeNetwork`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o check-in ao core`).

---

### Task 3: Busca e cadastro de hóspede no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (dado pessoal; entrada externa)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/GuestsFacade.kt`
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/GuestSearchFacadeTest.kt`

**Interfaces:**
- Produces:
  - `data class NewGuest(val fullName: String, val documentType: String, val documentNumber: String, val issuingCountry: String?, val nationality: String, val birthDate: BusinessDate)`, com `toString()` = `"NewGuest(***)"`;
  - `suspend fun GuestsFacade.searchByName(name: String, limit: Int): List<GuestResponse>`: uma página, `limit` repassado, nome aparado;
  - `suspend fun GuestsFacade.searchByDocument(type: String, number: String, issuingCountry: String?): List<GuestResponse>`: `POST …/search-by-document`, o número só no corpo;
  - `suspend fun GuestsFacade.create(input: NewGuest): GuestResponse`:
    - `profile`: `fullName`, `vip = false`, `nationality` e `birthDate`;
    - `documents`: um, com `type`, `number` e `issuingCountry` (omitido para `CPF`, `RG`, `CNH` e `RNM`);
    - sem `consents` nem `sensitiveData`;
  - nenhuma mensagem de exceção, causa ou `toString` contém o número nem o nome; corpo inválido → `IncompatibleResponse` sem o corpo, como em `guest(id)`.

- [ ] **Step 1: Testes que falham:**
  - `nameSearchSendsNameAndLimit`;
  - `documentSearchKeepsNumberOutOfUrl`: a URL não contém o número e o corpo contém;
  - `createSendsMinimalProfile`: inclui o `issuingCountry` omitido para `CPF` e presente para `PASSPORT`;
  - `createInvalidDocumentCarriesFieldError`: `400 DOCUMENT_NUMBER_INVALID` com `errors[]` do campo `documents[0].number` → `Problem.fieldErrors`;
  - `nameTooBroadIsProblem`;
  - `newGuestToStringHidesData`;
  - `incompatibleBodyHidesData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a busca e o cadastro mínimo de hóspede ao core`).

---

### Task 4: Textos das ações da recepção

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (interpreta entrada externa; textos da demo)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ActionText.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ActionTextTest.kt`

**Interfaces:**
- Produces: `object ActionText`, de funções puras.
  - `fun error(error: ApiError): String`. Para `ApiError.Problem`, conforme o `code` (texto exato):
    - `ROOM_NOT_READY` → "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança."
    - `ROOM_NOT_ASSIGNED` → "A reserva ainda não tem UH. Aloque uma UH antes do check-in."
    - `ROOM_ALREADY_ASSIGNED` → "A UH já está alocada a outra reserva em parte do período. Escolha outra UH das sugestões."
    - `ROOM_ASSIGNMENT_LOCKED` → "A UH desta reserva está marcada como \"não mover\". Desmarque antes de trocar ou soltar."
    - `ROOM_OUT_OF_ORDER` → "A UH está fora de ordem em alguma noite da estadia. Escolha outra UH."
    - `ROOM_TYPE_MISMATCH` → "A UH não é do tipo da reserva. Escolha uma UH das sugestões."
    - `ROOM_INACTIVE` → "A UH está inativa. Escolha outra UH."
    - `GUEST_DATA_INCOMPLETE` → "Faltam dados do hóspede: nacionalidade, data de nascimento ou um documento aceito (CPF para brasileiro adulto). Cadastre o hóspede com esses dados ou complete o perfil na área Hóspedes."
    - `GUEST_BIRTH_DATE_REQUIRED`, `MINOR_AUTHORIZATION_REQUIRED` e `MINOR_AUTHORIZATION_INVALID` → "Hóspede menor de idade precisa da data de nascimento e da autorização do responsável no perfil (área Hóspedes)."
    - `CHECK_IN_BEFORE_ARRIVAL` → "O check-in não pode ser feito antes da data de chegada. Confira a data da reserva."
    - `RESERVATION_TRANSITION_NOT_ALLOWED` → "Só uma reserva confirmada faz check-in. Confirme a pré-reserva antes."
    - `EARLY_LATE_FEE_CHANGED` → "O valor do early check-in mudou. Confira a nova cotação e confirme de novo."
    - `EARLY_LATE_NOT_OFFERED` → "O early check-in não é oferecido agora. Desmarque a cobrança e confirme de novo."
    - `FOLIO_CLOSED` → "A conta da reserva está encerrada. Fale com o financeiro."
    - `GUEST_ANONYMIZED` → "Os dados deste hóspede foram anonimizados. Escolha ou cadastre outro hóspede."
    - `UNKNOWN_GUEST` → "O hóspede escolhido não existe mais. Busque de novo."
    - qualquer outro → `error.userMessage`.

    Para os outros tipos de `ApiError`, `error.userMessage`.
  - `fun isReady(status: RoomStatusResponse): Boolean` (RN-05): vaga, sem OOO, e `INSPECTED` ou `CLEAN` sem `inspectionPending`.
  - `fun readiness(status: RoomStatusResponse?): ChipModel`: "Pronta" (SUCCESS) se `isReady`; senão o rótulo e o tom de `displayStatus(status)` de `app/rooms/RoomDisplay.kt`; `null` → "Status desconhecido" (NEUTRAL).
  - `fun earlyOffer(quote: EarlyLateQuoteResponse): String?`: oferecido → "Entrada a partir de HH:mm · R$ 50,00" (`limitTime` e `ReceptionText.money(fee)`); não oferecido, ou sem horário ou valor → `null`.

- [ ] **Step 1: Testes que falham:**
  - um teste por linha de `error`, com o texto exato;
  - `unknownCodeFallsBackToUserMessage` e `nonProblemUsesUserMessage`;
  - `readyRules`: vaga e inspecionada; vaga e limpa sem inspeção pendente; limpa com inspeção pendente; suja; ocupada; com OOO;
  - `readinessChip` e `earlyOfferText`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta os textos das ações da recepção`).

---

### Task 5: A porta da recepção com as ações

**Estimativa:** 40 min · **Depende de:** Tasks 1, 2 e 3 · **Risco:** alto (contrato da porta; dado pessoal)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionSource.kt`; `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (o `CoreConnection` implementa sobre as fachadas das Tasks 1 a 3 e `core.rooms.statuses()`); `app/src/main/kotlin/com/zeny/desktop/app/reception/DayPanelState.kt` (só expõe as permissões)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/FakeReceptionSource.kt` (só acréscimos), `ReceptionActionsSourceTest.kt`

**Interfaces:**
- Produces:
  - `data class GuestPick(val id: String, val summary: HolderSummary)`, com `toString()` = `"GuestPick(***)"`, e `fun GuestResponse.toGuestPick(): GuestPick` (pura, usa `toHolderSummary()`);
  - em `ReceptionSource`, os membros:
    - `suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse>`;
    - `suspend fun roomStatuses(): List<RoomStatusResponse>`;
    - `suspend fun assignRoom(id: String, roomId: String, doNotMove: Boolean?): ReservationDetailResponse`;
    - `suspend fun unassignRoom(id: String): ReservationDetailResponse`;
    - `suspend fun setDoNotMove(id: String, doNotMove: Boolean): ReservationDetailResponse`;
    - `suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse`;
    - `fun checkIn(id: String, input: CheckInInput, intent: Intent): ApprovableAction<CheckInResponse>`;
    - `suspend fun searchGuests(name: String): List<GuestPick>` (limite 20);
    - `suspend fun searchGuestsByDocument(type: String, number: String, issuingCountry: String?): List<GuestPick>`;
    - `suspend fun createGuest(input: NewGuest): GuestPick`;
  - `DayPanelState.permissions: Set<String>` (vazio até `permissions()` responder; o valor lido no `init` que já existe);
  - `FakeReceptionSource`:
    - respostas e erros por operação;
    - registro de cada chamada (com a `Intent.key` em uso em cada execução de `checkIn`, e o token, se houver);
    - chamadas que podem ficar presas;
    - um `checkIn` cujo `run(token)` pode falhar com `ApprovalRequired` na primeira execução.

- [ ] **Step 1: Testes que falham:**
  - `pickUsesSummary` e `pickToStringHidesData`;
  - `coreConnectionDelegatesActions`: no molde do `CoreConnectionTest`, sobre um servidor de teste em loopback como o `CoreReceptionSourceAtaqueTest` da E01. Cobre `assignRoom` (caminho e corpo), `checkIn` (`Idempotency-Key`), `searchGuestsByDocument` (número fora da URL) e `createGuest` (devolve `GuestPick`, não `GuestResponse`);
  - `panelExposesPermissions`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta as ações da recepção à porta`).

---

### Task 6: Estado da alocação de UH

**Estimativa:** 40 min · **Depende de:** Tasks 4 e 5 · **Risco:** alto (concorrência; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RoomAllocationState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RoomAllocationStateTest.kt`

**Interfaces:**
- Produces:
  - `data class RoomOption(val roomId: String, val number: String, val floor: String?, val current: Boolean, val outOfService: Boolean, val matchesFloorPreference: Boolean, val readiness: ChipModel, val ready: Boolean)`;
  - `class RoomAllocationState(source: ReceptionSource, scope: CoroutineScope, reservationId: String, onChanged: () -> Unit)`, com mutações no escopo da UI:
    - `var options: List<RoomOption>`, `var currentRoomId: String?`, `var currentRoomNumber: String?`, `var doNotMove: Boolean`, `var loading`, `var busy` e `var error: String?` (texto de `ActionText.error`);
    - `fun load()`: em paralelo `roomSuggestions` e `roomStatuses`, juntados por `roomId`; `roomStatuses` com `ApiError.Problem` → `readiness` "Status desconhecido" e `ready = false`, sem erro; a UH atual vem da sugestão `current`;
    - `fun assign(roomId: String)`, `fun unassign()` e `fun toggleDoNotMove()`: com `busy`, uma chamada por vez (cliques durante `busy` são ignorados); o sucesso atualiza `currentRoomId`, `currentRoomNumber` e `doNotMove` a partir de `ReservationDetailResponse.reservation.roomAssignment`, recarrega as opções e chama `onChanged()`; falha → `error`;
    - `fun close()`.

- [ ] **Step 1: Testes que falham:**
  - `loadsSuggestionsWithReadiness`: pronta, suja e ocupada, com o rótulo de cada;
  - `statusesForbiddenStillLists`;
  - `assignUpdatesCurrentAndNotifies`;
  - `unassignClearsCurrent`;
  - `doNotMoveToggles`;
  - `lockedShowsNextStep`: `ROOM_ASSIGNMENT_LOCKED` → texto da Task 4;
  - `doubleAssignSendsOnce`;
  - `notAllowedShowsMessage` (reserva hospedada);
  - `closeCancels`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado da alocação de UH`).

---

### Task 7: Estado da busca e do cadastro de hóspede

**Estimativa:** 40 min · **Depende de:** Tasks 4 e 5 · **Risco:** alto (dado pessoal)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/GuestPickerState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/GuestPickerStateTest.kt`

**Interfaces:**
- Produces: `class GuestPickerState(source: ReceptionSource, scope: CoroutineScope, onPicked: (GuestPick) -> Unit)`.
  - **Busca:**
    - `var query: String`;
    - `var documentType: String` ("CPF" por padrão; opções `CPF`, `RG`, `CNH`, `PASSPORT`, `RNM` e `FOREIGN_ID`, com os rótulos de `ReceptionText.document`);
    - `var documentNumber: String`, `var issuingCountry: String`, `var results: List<GuestPick>`, `var searching`, `var error: String?`;
    - `fun searchByName()`: exige ao menos 2 letras, senão `error` = "Digite ao menos 2 letras do nome.";
    - `fun searchByDocument()`: exige número; `issuingCountry` exigido para `PASSPORT` e `FOREIGN_ID` ("Informe o país emissor."); limpa `documentNumber` depois da chamada, com sucesso ou falha;
    - `fun pick(pick: GuestPick)`: chama `onPicked`.
  - **Cadastro:** `val form: FormState`, com os campos:
    - `fullName`: obrigatório;
    - `documentType`;
    - `documentNumber`: obrigatório; CPF validado por `DocumentInput.parse`;
    - `issuingCountry`: 2 letras, obrigatório para `PASSPORT` e `FOREIGN_ID`;
    - `nationality`: 2 letras maiúsculas, padrão "BR";
    - `birthDate`: `DateInput.parse`, não futura.

    `var creating`. `fun create()`:
    - valida no cliente; inválido → nenhuma chamada;
    - `errors[]` do servidor → `form.applyServerErrors`;
    - sucesso → `onPicked(pick)` e limpa número e nome do formulário;
    - uma chamada por vez.
  - `toString()` sem nome nem número.

- [ ] **Step 1: Testes que falham:**
  - `searchByNameListsPicks` e `shortNameAsksMore`;
  - `searchByDocumentSendsTypeNumberCountry`;
  - `passportNeedsCountry`;
  - `numberClearedAfterSearchAndCreate`;
  - `createValidatesBeforeCalling`: CPF inválido, data futura, nacionalidade fora de 2 letras;
  - `createSendsNewGuestAndPicks`;
  - `serverFieldErrorGoesToField`: `DOCUMENT_NUMBER_INVALID` em `documents[0].number` → erro no campo `documentNumber`;
  - `doubleCreateSendsOnce`;
  - `pickerToStringHidesData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado da busca e do cadastro de hóspede`).

---

### Task 8: Estado do check-in

**Estimativa:** 45 min · **Depende de:** Tasks 6 e 7 · **Risco:** alto (idempotência, aprovação; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/CheckInStateTest.kt`

**Interfaces:**
- Consumes: `RoomAllocationState`, `GuestPickerState`, `ReceptionSource`, `ActionText`, `CheckInInput`/`CheckInGuest`, `Intent`, `ApprovableAction`, `ApiResponse`.
- Produces:
  - `data class CheckInDone(val roomNumber: String, val alerts: List<String>)`, com `toString()` sem `alerts`; os alertas no formato "Hóspede com restrição: <reason>";
  - `class CheckInState`, com o construtor `(source: ReceptionSource, scope: CoroutineScope, reservationId: String, holderGuestId: String, adults: Int, children: Int, approve: suspend (ApprovableAction<CheckInResponse>) -> ApiResponse<CheckInResponse>?, now: () -> Instant, onChanged: () -> Unit)`:
    - `val allocation: RoomAllocationState`, com `onChanged` repassado;
    - `val picker: GuestPickerState`, cujo `onPicked` troca o hóspede principal;
    - `var primary: GuestPick?`: começa pelo titular, `holder(holderGuestId)` com o id; titular 403/404 → `null`, e o recepcionista escolhe outro;
    - `var quote: EarlyLateQuoteResponse?`;
    - `var earlyText: String?`: `ActionText.earlyOffer` só se oferecido e `now()` antes de `earlyCheckIn.until`; senão `null`;
    - `var chargeEarly: Boolean` (falso por padrão; só ligável com `earlyText != null`);
    - `var busy`, `var error: String?` e `var done: CheckInDone?`;
    - `val canConfirm`: UH atual, `primary` e não `busy`.
  - **`fun start()`:** carrega a alocação, o titular e a cotação em paralelo. Falha da cotação → sem early e sem erro.
  - **`fun confirm()`:**
    - monta `CheckInInput(guests = [CheckInGuest(primary.id, true)], guestCount = (adults + children).coerceIn(1, 40), roomId = null, earlyCheckInFee = fee da cotação se chargeEarly)`;
    - roda `approve(source.checkIn(reservationId, input, intent))`;
    - `intent` é uma `Intent` mantida enquanto UH, hóspede principal e `chargeEarly` não mudam; qualquer mudança ou um sucesso cria outra;
    - `null` (aprovação cancelada) → nada muda, sem erro;
    - sucesso → `done`, e chama `onChanged()`;
    - `EARLY_LATE_FEE_CHANGED` → relê a cotação, desliga `chargeEarly` e mostra o texto da Task 4;
    - outra falha → `error = ActionText.error(e)`.
  - **`fun close()`.**

- [ ] **Step 1: Testes que falham:**
  - `startsWithHolderAndQuote`;
  - `earlyOfferedOnlyBeforeUntil`: `now` antes e depois de `until`;
  - `confirmSendsPrimaryCountAndFee`;
  - `retryAfterNetworkErrorReusesKey`: a mesma `Intent.key` nas duas execuções registradas pelo falso;
  - `changingInputsRenewsKey`: trocar a UH, o hóspede ou `chargeEarly`;
  - `doubleConfirmSendsOnce`;
  - `roomNotReadyShowsNextStep`: o texto da Task 4, sem `done` e sem `onChanged`;
  - `approvalCancelledLeavesDialog`;
  - `approvedRetrySucceeds`;
  - `sessionEndedShowsMessage`;
  - `feeChangedRequotes`;
  - `successNotifiesAndShowsAlerts`;
  - `holderForbiddenNeedsPick`;
  - `doneToStringHidesAlerts`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do check-in`).

---

### Task 9: Hóspedes da estadia no detalhe

**Estimativa:** 30 min · **Depende de:** Task 5 · **Risco:** alto (dado pessoal na tela)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReservationDetailState.kt`; `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` (só a seção nova)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReservationDetailGuestsTest.kt`

**Interfaces:**
- Produces:
  - `data class GuestLine(val name: String, val document: String?, val primary: Boolean)`, com `toString()` sem nome nem documento;
  - `ReservationDetailView.stayGuests: List<GuestLine>`: de `reservation.guests`, na ordem do servidor, cada um por `holder(guestId)`, lido em paralelo depois da reserva; 403/404 → "—"/`null`; outro `ApiError` → `stayGuestsError` e lista vazia, sem derrubar o detalhe. Também `ReservationDetailView.stayGuestsError: ApiError?`. O `toString` da view continua sem dados pessoais;
  - na tela, a seção "Hóspedes da estadia", só com a lista não vazia ou com erro: "<nome> · <documento>", com " (principal)" no principal.
  - `ReservationDetailState.reservation: ReservationDetailResponse?`: a última resposta de `reservation(id)` do aberto (nula fechado ou com outro id carregando), para as ações da Task 10; fora da view e do `toString`.

- [ ] **Step 1: Testes que falham:**
  - `stayGuestsListedWithMaskedDocument`;
  - `exposesOpenReservation` (e nula depois de `close()`);
  - `stayGuestForbiddenShowsDash`;
  - `stayGuestErrorKeepsDetail`;
  - `noStayGuestsHidesSection` (UI);
  - `stayGuestsSectionShowsMasked` (UI);
  - `viewToStringStillHidesData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Mostra os hóspedes da estadia no detalhe da reserva`).

---

### Task 10: Diálogo do check-in na tela

**Estimativa:** 45 min · **Depende de:** Tasks 8 e 9 · **Risco:** alto (superfície da demo; dado pessoal; aprovação)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInDialog.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt`, `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (`ReceptionArea`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/CheckInDialogTest.kt`

**Interfaces:**
- Produces:
  - **`@Composable fun CheckInDialog(state: CheckInState, onClose: () -> Unit)`**, com as seções:
    - **"UH":** UH atual ou "Sem UH", e a lista de opções com número, andar, o chip de `readiness`, "Fora de serviço" e "Andar preferido" quando valem, e "Alocar" em cada uma; "Soltar UH"; a caixa "Não mover";
    - **"Early check-in":** `earlyText` e a caixa "Cobrar early check-in"; sem oferta, "Sem early check-in agora.";
    - **"Hóspede principal":** nome e documento mascarado, ou "Escolha o hóspede"; "Trocar hóspede" abre a busca (abas "Por nome" e "Por documento", resultados com "Escolher") e "Cadastrar hóspede" (o formulário da Task 7, com os rótulos "Nome completo", "Documento", "Número", "País emissor", "Nacionalidade" e "Data de nascimento");
    - o `error` em destaque;
    - "Fazer check-in" (habilitado por `canConfirm`) e "Fechar";
    - com `done`: "Check-in feito na UH <n>.", os alertas e "Fechar".
  - **`ReceptionScreen(panel, detail, checkIn: (ReservationDetailView) -> CheckInState?)`:**
    - o botão "Fazer check-in" aparece no detalhe só com a situação "Confirmada" e `"frontdesk.stays.checkin" in panel.permissions`;
    - abre o diálogo com o estado que `checkIn` devolve; fechar o diálogo chama `close()` do estado;
    - o que o estado pede vem de `ReservationDetailState.reservation` (Task 9); `ReservationDetailView` não muda e nenhum teste existente muda.
  - **`ReceptionArea`:**
    - cria o `CheckInState` com `approve = LocalApprovalCoordinator.current::run` (via uma lambda) e `now = Clock.systemUTC()::instant`;
    - `onChanged` chama `panel.refresh()` e `detail.reload()`;
    - `reservationId`, `adults`, `children` (= `childAges.size`) e `holderGuestId` vêm de `detail.reservation`.

- [ ] **Step 1: Testes que falham:**
  - `checkInButtonOnlyForConfirmedWithPermission`;
  - `dialogShowsSuggestionsWithReadiness`;
  - `assignFromDialog`;
  - `pickGuestByDocument`;
  - `createGuestFromDialog` (o documento digitado nunca aparece na árvore depois do cadastro; só o mascarado);
  - `confirmShowsDoneAndRefreshes`: `onChanged`, que no teste conta `panel.refresh` e `detail.reload`;
  - `ruleErrorShowsNextStep`;
  - `noUuidInDialog`;
  - `noRawCodeInDialog`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2`, `./gradlew :app:desktopJar --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Põe o check-in no detalhe da recepção`).

---

### Task 11: Verificação ao vivo do check-in

**Estimativa:** 40 min · **Depende de:** Task 10 · **Risco:** alto (segredos; escreve no servidor; critérios de aceite)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveCheckInCheck.kt`
- Modify: `app/build.gradle.kts` (só o opt-in `ZENY_LIVE_CHECKIN` como entrada do `test`, no molde de `ZENY_LIVE_RECEPTION`); correções pequenas com teste, no arquivo dono, se a verificação achar defeito.

**Interfaces:**
- **Variáveis:** as da E01 (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`/`PASSWORD` da Recepcionista), do arquivo 600 gerado pelo orquestrador. Opt-in `ZENY_LIVE_CHECKIN=1`: sem ele, pula; com ele e sem credencial, falha. A verificação **escreve** no `zeny-front` (consome uma chegada do seed), por isso tem opt-in próprio.
- **`LiveCheckInCheck.checkInMovesArrivalAndRoom`:**
  - **preparação:** `CoreConnection` real, login da Recepcionista; `DayPanelState` e `RoomMapState` reais, este assinando `rooms`; escolhe uma chegada `CONFIRMED` sem UH;
  - **fluxo:** cria o `CheckInState` real, aloca a primeira opção `ready` e cadastra um hóspede fictício pelo `GuestPickerState` (CPF de teste com dígito válido, gerado na hora; nacionalidade BR; nascimento em 1990) como principal; confirma;
  - **afirma:**
    - `done`;
    - a reserva sai de "Chegadas" e entra em "Hospedados" depois de `panel.refresh()`;
    - a UH aparece ocupada no `RoomMapState`, sem recarregar a tela, pelo evento de `rooms`;
    - o detalhe mostra o hóspede criado em "Hóspedes da estadia" com o documento mascarado, sem os 11 dígitos;
  - **captura:** `app/build/live/checkin.png`, com o detalhe depois do check-in.
- **`LiveCheckInCheck.notReadyRoomLeavesNoEffect`:**
  - pega outra chegada `CONFIRMED` e uma UH do mesmo tipo que não esteja pronta (`ActionText.isReady` falso, vaga e sem OOO), aloca essa UH e confirma o check-in;
  - afirma o texto de `ROOM_NOT_READY` da Task 4, a reserva ainda `CONFIRMED` e a UH ainda vaga;
  - no fim, solta a UH;
  - sem UH não pronta desse tipo no seed, falha com a contagem do que achou (sem dados pessoais). O orquestrador decide.

- [ ] **Step 1:** escreva as verificações.
- [ ] **Step 2:** `(set -a; . <arquivo do despacho>; set +a; ZENY_LIVE_CHECKIN=1 ./gradlew :app:test --tests '*LiveCheckInCheck*' --rerun --max-workers=2)` → verde; cole o resultado.
- [ ] **Step 3:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes sem as variáveis (as verificações pulam); commit (`Verifica o check-in contra o servidor`).

## Ondas

- Dependências: 2←1; 5←1,2,3; 6←4,5; 7←4,5; 8←6,7; 9←5; 10←8,9; 11←10. As Tasks 1, 3 e 4 não dependem de nenhuma.
- Execução em série na branch da etapa, na ordem 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11. As ondas paralelas não funcionam nesta configuração (handoff da E01).

## Para o handoff

- H1: o cadastro mínimo tem nome, documento, nacionalidade e data de nascimento, porque o check-in exige os três últimos (`GUEST_DATA_INCOMPLETE`). Menor de idade e dados sensíveis da FNRH ficam para o M5.
- Cada check-in registra só o hóspede principal, e `guestCount` = adultos + crianças. Os acompanhantes nominais ficam para o M5.
- Sobre o early check-in: a cobrança é opcional e desligada por padrão, só antes do fim da janela.
- `ROOM_ALREADY_ASSIGNED` tinha texto errado no catálogo; foi corrigido.
- A verificação ao vivo consome chegadas do seed; o roteiro do gate começa pela recriação (README, regra 5).
