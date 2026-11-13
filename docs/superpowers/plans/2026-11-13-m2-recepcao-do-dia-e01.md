# M2 E01 — Painel do dia e detalhe da reserva — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a área Recepção abre num painel com três abas (chegadas, saídas e hospedados da data operacional), cada uma com contagem, busca local e situação; clicar numa reserva abre o detalhe (titular com documento mascarado, período, tipo e UH, plano, situação, alocações e histórico em ordem cronológica), tudo em pt-BR, sem UUID nem campo técnico.

**Architecture:** no `core`, fachadas finas sobre as operações geradas (ADR 0008, opção b: devolvem os modelos do `sdk`): `ReservationsFacade` (listas do dia por página, detalhe, histórico inteiro, alocações), `GuestsFacade.guest(id)`, `RatesFacade.ratePlan(id)` e o fuso em `HotelInfo`. No `app`, pacote `com.zeny.desktop.app.reception`: textos e formatação puros (`ReceptionText`), uma porta estreita `ReceptionSource` (obtida por `ServerConnection.receptionSource()`, padrão `null` como `roomsSource()`), dois *state holders* testáveis com um falso (`DayPanelState`, com uma `TableState` do `design` por aba sobre um `CursorPager`; `ReservationDetailState`) e a tela `ReceptionScreen`, ligada à área Recepção na casca, com a chave do `sessionId`.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (`LocalWindowInfo.isWindowFocused`), kotlinx-coroutines, `TableState`/`DataTable`/`StatusChip` do `design`, kotlin-test + JUnit Platform, `kotlinx-coroutines-test`, `ktor-client-mock` (só nos testes do `core`), `runComposeUiTest` nas telas.

**Spec:** seção "E01 — Painel do dia e detalhe da reserva" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` (com "Contexto confirmado", "Restrições e invariantes" e H3); design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.1, §6.2 e §6.5; ADRs `0007-gerador-do-sdk.md` e `0008-fronteira-core-sdk.md`; handoffs da M1 E04 (tabela área → permissão, `LocalApprovalCoordinator`) e E06 (assinatura amarrada ao `sessionId`, semântica do `RealtimeClient`) em `docs/roadmaps/roadmap-m1-fundacao.md`. Contrato `api/openapi.yaml`: `listArrivals`, `listDepartures`, `listInHouse` (`date`, `cursor`, `limit` ≤ 200), `getReservation`, `listReservationHistory` (do mais recente ao mais antigo, por cursor), `listReservationRoomAssignments`, `getGuest` (documentos já mascarados em `maskedNumber`), `getRatePlan` (`rates.plans.read`), `getHotel` (`timezone`; exige `hotel.settings.read`), `listRoomTypes`, `openStream`.

**Fatos do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- tópicos de tempo real existentes: só `rooms` (`rooms.status.read`), `notifications` e `end-of-day`. **Não há tópico de reservas** (H3): o painel recarrega ao voltar o foco da janela, pelo botão "Atualizar" e a cada mensagem do tópico `rooms` (o check-in, o check-out e a troca de UH mudam o status da UH), e expõe `reload()` para as ações das etapas seguintes;
- a lista não traz o nome do titular: só `holderGuestId` (o nome vem de `getGuest`, só em memória);
- `kind` do histórico: `CHANGE`, `CANCEL`, `REACTIVATE`, `CONFIRM`, `GUARANTEE`, `CHECK_IN`, `UNDO_CHECK_IN`, `CHECK_OUT`, `ROOM_MOVE` (extensível). A criação **não** gera entrada (o detalhe a sintetiza a partir de `reservation.createdAt`). `changedFields` vem destes nomes: `adults`, `arrival`, `cancellationTerms`, `childAges`, `departure`, `guaranteeTerms`, `nightlyRates`, `ratePlanId`, `roomTypeId`, `segment`, `specialRequests`, `status`, `checkIn`, `checkOut`, `guests`, `guarantee`, `roomAssignment`;
- o modelo Recepcionista tem `reservations.reservations.read`, `guests.profiles.read`, `rates.plans.read`, `rooms.status.read` e `hotel.settings.read`.

## Global Constraints

- **Regras do design §6.2:** nada de JSON ou `code` cru ao usuário (mensagens por `ApiError.userMessage`, via `errorMessage` do `design`); enum extensível com rótulo pt-BR e "Outro (`VALOR`)" para valor desconhecido **exceto** onde o critério proíbe campo técnico (nome de campo mudado desconhecido → "outros dados"); dinheiro em `BigDecimal` (`Money`), nunca `Double`; instantes no fuso do hotel (`HotelInfo.timezone`), **nunca** no da JVM; datas de negócio sem conversão de fuso; o servidor decide as permissões (`403` tratado).
- **Fronteira (ADR 0008):** `app` e `design` (main e test) não importam `com.zeny.desktop.sdk.client.*`, `com.zeny.desktop.sdk.util.*`, `io.ktor.client.*`, `de.quati.ogen.client.*` nem usam `@RawSdkAccess`; usam os modelos `com.zeny.desktop.sdk.model.*` e as fachadas do `core`.
- **Dados pessoais (Restrições do roadmap):** documento sempre mascarado (só o `maskedNumber` do servidor; nunca a ação de revelar); nenhum dado de hóspede em log, `toString` de estado, mensagem de exceção nem cache de disco — os nomes vivem só em memória, no *state holder* da sessão; o seed e os testes usam só dados fictícios.
- **Nada de UUID na tela:** toda referência a outro registro sem nome legível vira "—" (tipo de UH, plano, titular sem acesso) ou some (autor do histórico).
- **Tempo real:** toda assinatura nasce amarrada ao `sessionId` (a tela é recriada por `remember(source, sessionId)`); um fim de assinatura com erro não derruba o painel (só a atualização automática deixa de vir).
- **Testes não dormem** (`runTest`, tempo virtual). Verificação ao vivo só com o opt-in `ZENY_LIVE_RECEPTION=1` e as variáveis próprias; com o opt-in e sem credencial, falha.
- **Testes existentes:** só mudam os listados como "mandado por este plano", com a mudança esperada; os demais são do testador.
- Antes de cada commit: `./gradlew build` (inteiro) e `bash tests/run.sh` verdes; memória curta: `--max-workers=2`; nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez.
- Textos de UI em pt-BR; código em inglês. Pacotes `com.zeny.desktop.core.facade` e `com.zeny.desktop.app.reception`.
- Commits em pt-BR, no estilo do histórico, com as linhas `Soul: <soul>` e `Co-Authored-By: …` consecutivas no último parágrafo.

## Review Focus

1. **Recarga durante outra recarga** (foco + mensagem `rooms` + "Atualizar" em rajada): no máximo uma recarga em voo e uma pendente; página velha nunca entra numa aba recarregada (a `TableState` descarta). Teste `burstOfTriggersCoalesces` na Task 4.
2. **Hóspede titular sem acesso ou apagado** (`403`/`404` em `getGuest`): a linha mostra "—" e a lista carrega; `SessionEnded`, rede e resposta incompatível continuam virando erro da aba. Testes `guestForbiddenShowsDash` e `guestNetworkErrorFailsTab` na Task 4.
3. **Abrir outra reserva com o detalhe anterior em voo:** a resposta atrasada da primeira nunca aparece no detalhe da segunda. Teste `staleDetailDropped` na Task 5.
4. **Hotel sem fuso legível ou `403` em `getHotel`:** o histórico mostra a data operacional da entrada ("13/11/2026 (data operacional)") e nenhum horário no fuso da JVM. Teste `noZoneFallsBackToBusinessDate` na Task 5.
5. **Mais de 200 reservas numa aba:** a contagem mostra "200+" enquanto há página por carregar e a busca local cobre só o já carregado (a `DataTable` oferece "Carregar mais"). Teste `countShowsPlusWhileMorePages` na Task 4.

---

### Task 1: Fachada das reservas e fuso do hotel no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (contrato público do `core`; parsing de resposta externa)

**Files:**
- Create: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/Facades.kt` (`HotelInfo` ganha `timezone`), `core/src/main/kotlin/com/zeny/desktop/core/ZenyCore.kt` (`val reservations`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/ReservationsFacadeTest.kt`, `HotelFacadeTimezoneTest.kt` (no padrão dos testes de fachada existentes, com `MockEngine`)

**Interfaces:**
- Produces:
  - `enum class DayList { ARRIVALS, DEPARTURES, IN_HOUSE }` (em `ReservationsFacade.kt`);
  - `class ReservationsFacade internal constructor(reservations: ReservationsApi, executor: ApiExecutor)` com
    - `suspend fun dayPage(list: DayList, date: BusinessDate, cursor: String?, limit: Int): Page<ReservationListItemResponse>` (`date.toApi()` sempre enviado; `limit` repassado; `nextCursor` nulo/`Undefined` → `null`);
    - `suspend fun reservation(id: String): ReservationDetailResponse`;
    - `suspend fun history(id: String): List<ReservationHistoryResponse>` (todas as páginas, `limit` 200, na ordem do servidor);
    - `suspend fun roomAssignments(id: String): List<RoomAssignmentResponse>`;
    - `id` que não é UUID → `IllegalArgumentException("id de reserva inválido")` antes da rede (como `RoomsFacade`).
  - `data class HotelInfo(val tradeName: String?, val configured: Boolean, val timezone: ZoneId? = null)`: `HotelFacade.hotel()` preenche com `ZoneId.of(response.timezone)`; fuso ausente, em branco ou inválido → `null` (nunca exceção, nunca o fuso da JVM).
  - `ZenyCore.reservations: ReservationsFacade`.

- [ ] **Step 1: Testes que falham:** `dayPageSendsDateCursorAndLimit` (as três listas batem nos caminhos `/arrivals`, `/departures`, `/in-house` com `date=2026-11-13`, `cursor` e `limit=200`); `dayPageEndsWithoutCursor`; `historyReadsAllPages` (duas páginas → lista concatenada na ordem do servidor); `assignmentsReturnItems`; `invalidIdFailsBeforeNetwork` (nenhuma requisição); `notFoundIsProblem` (`404 RESERVATION_NOT_FOUND` → `ApiError.Problem` com a mensagem do catálogo); `hotelTimezoneParsed` (`"America/Sao_Paulo"`); `hotelTimezoneInvalidIsNull` (`"Mars/Base"`, `""`, ausente).
- [ ] **Step 2:** `./gradlew :core:test --tests '*ReservationsFacadeTest*' --tests '*HotelFacadeTimezoneTest*'` → FAIL.
- [ ] **Step 3:** implemente; a paginação do histórico reaproveita `CursorPager` (como `RoomsFacade.readAll`).
- [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a fachada das reservas do dia e o fuso do hotel`).

---

### Task 2: Fachadas do hóspede e do plano tarifário

**Estimativa:** 25 min · **Depende de:** Task 1 (mesmo `ZenyCore.kt`) · **Risco:** alto (dado pessoal)

**Files:**
- Create: `core/src/main/kotlin/com/zeny/desktop/core/facade/GuestsFacade.kt`, `core/src/main/kotlin/com/zeny/desktop/core/facade/RatesFacade.kt`
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/ZenyCore.kt` (`val guests`, `val rates`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/GuestsFacadeTest.kt`, `RatesFacadeTest.kt`

**Interfaces:**
- Produces: `class GuestsFacade internal constructor(guests: GuestsApi, executor: ApiExecutor) { suspend fun guest(id: String): GuestResponse }`; `class RatesFacade internal constructor(rates: RatesApi, executor: ApiExecutor) { suspend fun ratePlan(id: String): RatePlanResponse }`; `ZenyCore.guests`, `ZenyCore.rates`. Id inválido → `IllegalArgumentException` sem a rede e **sem o id na mensagem**.

- [ ] **Step 1: Testes que falham:** `guestReturnsMaskedDocuments` (o `maskedNumber` chega intacto); `guestNotFoundIsProblem` (`404 GUEST_NOT_FOUND`); `guestForbiddenIsProblem` (`403` → `ApiError.Problem` com status 403); `incompatibleGuestBodyHidesBody` (corpo inválido com um nome fictício → `IncompatibleResponse` cuja mensagem e causa não contêm o nome); `ratePlanReturnsName`; `invalidIdsFailBeforeNetwork`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta as leituras de hóspede e de plano tarifário ao core`).

---

### Task 3: Textos e formatação da recepção

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** normal

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionText.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionTextTest.kt`

**Interfaces:**
- Produces (`object ReceptionText`, funções puras, sem estado):
  - `fun status(value: String): Pair<String, StatusTone>` — `PROVISIONAL` "Pré-reserva" (WARNING), `CONFIRMED` "Confirmada" (INFO), `IN_HOUSE` "Hospedada" (SUCCESS), `CHECKED_OUT` "Saída registrada" (NEUTRAL), `CANCELLED` "Cancelada" (DANGER), `NO_SHOW` "Não compareceu" (DANGER), outro → "Outro (VALOR)" (NEUTRAL);
  - `fun historyKind(value: String): String` — `CHANGE` "Reserva alterada", `CANCEL` "Reserva cancelada", `REACTIVATE` "Reserva reativada", `CONFIRM` "Pré-reserva confirmada", `GUARANTEE` "Garantia registrada", `CHECK_IN` "Check-in", `UNDO_CHECK_IN` "Check-in desfeito", `CHECK_OUT` "Check-out", `ROOM_MOVE` "Troca de UH", outro → "Outro (VALOR)";
  - `fun changedFields(fields: List<String>): String` — rótulos em minúsculas, na ordem recebida, sem repetir, juntados por ", ": `adults` "adultos", `childAges` "crianças", `arrival` "chegada", `departure` "saída", `cancellationTerms` "política de cancelamento", `guaranteeTerms` "política de garantia", `guarantee` "garantia", `nightlyRates` "diárias", `ratePlanId` "plano", `roomTypeId` "tipo de UH", `segment` "segmento", `specialRequests` "pedidos especiais", `status` "situação", `checkIn` "check-in", `checkOut` "check-out", `guests` "hóspedes", `roomAssignment` "UH"; desconhecidos viram um único "outros dados" no fim; lista vazia → "";
  - `fun assignmentReason(value: String): String` — `INITIAL` "Alocação inicial", `GUEST_REQUEST` "Pedido do hóspede", `OUT_OF_ORDER` "UH fora de ordem", `ROOM_ISSUE` "Problema na UH", `OPERATIONAL` "Operacional", outro → "Outro (VALOR)";
  - `fun releaseReason(value: String): String` — `UNASSIGNED` "Liberada", `REALLOCATED` "Realocada", `RESERVATION_CANCELLED` "Reserva cancelada", `RESERVATION_CHANGED` "Reserva alterada", `CHECKED_OUT` "Check-out", `ROOM_MOVE` "Troca de UH", outro → "Outro (VALOR)";
  - `fun planCategory(value: String): String` — `NORMAL` "Normal", `COURTESY` "Cortesia", `HOUSE_USE` "Uso da casa";
  - `fun document(type: String, masked: String): String` — `CPF`/`RG`/`CNH`/`RNM` com a sigla, `PASSPORT` "Passaporte", `FOREIGN_ID` "Documento estrangeiro", outro → "Documento"; formato `"<rótulo> <masked>"` (o número só como veio mascarado);
  - `fun date(iso: String): String` — `2026-11-13` → `13/11/2026`; texto ilegível → "—";
  - `fun period(arrival: String, departure: String, dayUse: Boolean): String` — `"13/11/2026 a 15/11/2026 · 2 noites"`, `"… · 1 noite"`, day use `"13/11/2026 · day use"`;
  - `fun guests(adults: Int, children: Int): String` — `"2 adultos"`, `"1 adulto e 1 criança"`, `"2 adultos e 3 crianças"`;
  - `fun money(decimal: String): String` — por `Money.parse`, `"R$ 1.234,56"` (locale pt-BR, 2 casas); ilegível → "—";
  - `fun instant(iso: String, zone: ZoneId?): String?` — `"13/11/2026 14:05"` no `zone`; `zone` nulo ou texto ilegível → `null`.

- [ ] **Step 1: Testes que falham:** um por função, com os valores acima, mais `unknownValuesNeverShowFieldNames` (`changedFields(listOf("status", "fooBar"))` = "situação, outros dados") e `instantUsesHotelZoneNotJvm` (com `TimeZone.setDefault` trocado no teste e restaurado, `2026-11-13T17:05:00Z` em `America/Sao_Paulo` → `"13/11/2026 14:05"`).
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta os textos e a formatação da recepção`).

---

### Task 4: Porta da recepção e estado do painel do dia

**Estimativa:** 45 min · **Depende de:** Tasks 2 e 3 · **Risco:** alto (concorrência; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionSource.kt`, `app/src/main/kotlin/com/zeny/desktop/app/reception/DayPanelState.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (`fun receptionSource(): ReceptionSource? = null`; o `CoreConnection` implementa sobre `core.hotel`, `core.reservations`, `core.guests`, `core.rates`, `core.rooms.roomTypes()` e `core.realtime.subscribe`; os falsos existentes não mudam)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/FakeReceptionSource.kt`, `DayPanelStateTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3; `TableState`, `Column`, `StatusTone` do `design`; `CursorPager`/`Page` do `core`; `RealtimeEvent` do `core`.
- Produces:
  - `interface ReceptionSource { suspend fun businessDate(): BusinessDate; suspend fun hotelZone(): ZoneId?; suspend fun dayPage(list: DayList, date: BusinessDate, cursor: String?, limit: Int): Page<ReservationListItemResponse>; suspend fun reservation(id: String): ReservationDetailResponse; suspend fun history(id: String): List<ReservationHistoryResponse>; suspend fun roomAssignments(id: String): List<RoomAssignmentResponse>; suspend fun guest(id: String): GuestResponse; suspend fun ratePlan(id: String): RatePlanResponse; suspend fun roomTypes(): List<RoomTypeResponse>; fun events(topics: Set<String>): Flow<RealtimeEvent> }` — no `CoreConnection`, `hotelZone()` devolve `core.hotel.hotel().timezone` e `null` em qualquer `ApiError.Problem` (403 da Governanta).
  - `data class ReservationRow(val id: String, val code: String, val holder: String, val room: String, val roomType: String, val arrival: String, val departure: String, val guests: String, val status: String, val tone: StatusTone, val alert: String?)` — `holder` = `socialName` se houver, senão `fullName`, ou "—"; `room` = `roomAssignment.roomNumber` ou "—"; `roomType` = nome do tipo ou "—"; `alert` = "Chegada pendente" (`arrivalOverdue`), "Saída pendente" (`departureOverdue`), "Day use" ou `null`. `toString()` não mostra `holder` (LGPD).
  - `class DayPanelState(source: ReceptionSource, scope: CoroutineScope)` (mutações no escopo da UI, uma thread; como `RoomMapState`): `val tabs: Map<DayList, TableState<ReservationRow>>` (`CursorPager` com `limit` 200, chave `id`); `var selectedTab: DayList` (começa em `ARRIVALS`); `var date: BusinessDate?`; `var error: ApiError?` (só da data operacional); `fun count(list: DayList): String` (`"4"`, `"200+"` com `hasMore`, `""` antes da primeira carga); `fun select(list: DayList)`; `fun reload()`; `fun onWindowFocus(focused: Boolean)` (recarrega só na transição para `true`); `fun close()`; `val columns: List<Column<ReservationRow>>` ("Reserva", "Titular", "UH", "Tipo", "Chegada", "Saída", "Hóspedes", "Situação"; a busca da `DataTable` filtra por essas células).
  - Protocolo: `init` assina `rooms` e faz a primeira carga (data operacional, depois as três abas); cada página resolve os nomes dos titulares que faltam no cache em memória (até 4 em paralelo) e os tipos de UH (lidos uma vez por recarga); `getGuest` com `ApiError.Problem` de status 403 ou 404 → "—" (guardado); qualquer outro `ApiError` falha a página (vira `error` da aba); `roomTypes()` com `ApiError.Problem` → "—" para todos; mensagem ou `Reset` do tópico `rooms` depois do primeiro `Ready`/`Reset` → recarga; fim da assinatura (normal ou com erro) → para de ouvir, sem erro na tela; **no máximo uma recarga em voo e uma pendente**.

- [ ] **Step 1: Testes que falham** (`FakeReceptionSource` com respostas por lista, por hóspede e eventos controlados; `runTest`): `loadsThreeTabsForBusinessDate` (a data operacional do falso vai em todas as chamadas; contagens 4/2/7); `emptyListShowsZero` (aba vazia: `count` = "0", `rows` vazio, sem erro); `tabErrorKeepsOthers` (erro só nas saídas → `tabs[DEPARTURES].error` com a mensagem pt-BR; as outras abas carregadas); `reloadAfterErrorRecovers`; `businessDateErrorShowsError`; `holderUsesSocialNameThenFullName`; `guestForbiddenShowsDash`; `guestNetworkErrorFailsTab`; `guestFetchedOncePerId` (mesmo titular em duas abas → uma chamada); `roomTypeForbiddenShowsDash`; `alertsForOverdueAndDayUse`; `countShowsPlusWhileMorePages`; `roomsMessageReloads`; `burstOfTriggersCoalesces` (foco + duas mensagens + `reload()` com uma carga presa → no máximo 2 cargas ao todo); `focusReloadsOnlyOnGain`; `streamFailureKeepsPanel` (assinatura termina com `ApiError` → sem `error`, painel segue, `reload()` funciona); `closeCancelsSubscriptionAndLoads`; `rowToStringHidesHolder`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*DayPanelStateTest*'` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do painel do dia da recepção`).

---

### Task 5: Estado do detalhe da reserva

**Estimativa:** 40 min · **Depende de:** Task 4 · **Risco:** alto (dado pessoal; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReservationDetailState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReservationDetailStateTest.kt` (reusa o `FakeReceptionSource`)

**Interfaces:**
- Consumes: `ReceptionSource`, `ReceptionText`.
- Produces:
  - `data class HistoryLine(val at: String, val title: String, val detail: String)`; `data class AssignmentLine(val room: String, val period: String, val reason: String, val state: String, val doNotMove: Boolean)`; `data class ReservationDetailView(val code: String, val status: String, val tone: StatusTone, val holder: String, val document: String?, val period: String, val roomType: String, val room: String, val guests: String, val plan: String, val total: String, val alerts: List<String>, val assignments: List<AssignmentLine>, val assignmentsError: ApiError?, val history: List<HistoryLine>, val historyError: ApiError?)` — `toString()` sem `holder` nem `document`.
  - `class ReservationDetailState(source: ReceptionSource, scope: CoroutineScope)`: `var openId: String?`; `var loading`; `var error: ApiError?` (falha de `reservation(id)`); `var view: ReservationDetailView?`; `fun open(id: String)` (cancela a carga anterior; resposta de outro id nunca entra); `fun reload()`; `fun close()`.
  - Montagem: `reservation`, `history`, `roomAssignments`, titular, plano e fuso em paralelo; `holder`/`document` do titular (`holderGuestId`): nome como na Task 4, `document` = `ReceptionText.document` do primeiro documento ou `null`; titular com 403/404 → "—"/`null`; `plan` = nome do plano (`ratePlan`) ou, com `ApiError.Problem`, `ReceptionText.planCategory`; `room` = "UH <número>" da alocação ativa, senão "Sem UH"; `assignments` na ordem do servidor, `state` = "Ativa" ou "Solta · <releaseReason>", período por `ReceptionText.period(startsOn, endsOn, dayUse)`; `alerts` = "Hóspede com restrição: <reason>" por `restrictionAlerts`; `history` em ordem **cronológica** (mais antiga primeiro; desempate por `version`), começando por uma linha sintetizada "Reserva criada" em `createdAt`; `title` = `historyKind`; `detail` = partes não vazias juntadas por " · ": "Alterou: <changedFields>", "Situação: <antes> → <depois>" quando `status` mudou, "Com aprovação de superior" quando `approvalId` existe; `at` = `ReceptionText.instant(occurredAt, zone)` ou, sem fuso, `"<data> (data operacional)"` a partir de `businessDate` (a linha sintetizada sem fuso usa "—"); nunca autor, ids nem retratos crus; falha de `history` ou de `roomAssignments` vira só o erro da seção.

- [ ] **Step 1: Testes que falham:** `buildsDetailWithMaskedDocument` (o `document` é exatamente `"CPF ***.***.***-34"` vindo de `maskedNumber`); `historyIsChronologicalWithCreation`; `historyDetailInPortuguese` (`CHECK_IN` com `status` mudado de `CONFIRMED` para `IN_HOUSE` e aprovação → "Alterou: situação, check-in, hóspedes · Situação: Confirmada → Hospedada · Com aprovação de superior"); `noUuidAnywhere` (nenhum campo da view casa `[0-9a-f]{8}-[0-9a-f]{4}-`); `noZoneFallsBackToBusinessDate`; `planFallsBackToCategory`; `activeAssignmentGivesRoom`; `holderForbiddenShowsDash`; `historyErrorKeepsDetail`; `notFoundShowsError` (`404 RESERVATION_NOT_FOUND` → `error` com a mensagem do catálogo); `staleDetailDropped`; `reloadRefetches`; `viewToStringHidesPersonalData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do detalhe da reserva`).

---

### Task 6: Tela da recepção

**Estimativa:** 40 min · **Depende de:** Task 5 · **Risco:** alto (superfície da demo; dado pessoal na tela)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionScreenTest.kt` (`runComposeUiTest`, com o `FakeReceptionSource`)

**Interfaces:**
- Consumes: `DayPanelState`, `ReservationDetailState`; `design`: `DataTable`, `StatusChip`, `LoadingState`, `ErrorState`, `EmptyState`.
- Produces: `@Composable fun ReceptionScreen(panel: DayPanelState, detail: ReservationDetailState)` — título "Recepção" e "Data operacional: 13/11/2026"; botão "Atualizar" (`panel.reload()` e, com detalhe aberto, `detail.reload()`); abas "Chegadas (n)", "Saídas (n)", "Hospedados (n)" com `testTag("tab-ARRIVALS")` etc.; a `DataTable` da aba (vazia: "Nenhuma reserva nesta lista."; a coluna "Situação" com o rótulo; o `alert` ao lado); erro da data → `ErrorState` com "Tentar de novo"; clicar numa linha → `detail.open(id)` e painel lateral com: código, situação (`StatusChip`), titular, documento, período, tipo, UH, hóspedes, plano, total, alertas, seção "Alocações" e seção "Histórico" (cada uma com o próprio erro e "Tentar de novo"), "Fechar"; carregando → `LoadingState`.

- [ ] **Step 1: Testes que falham:** `tabsShowCounts`; `switchingTabShowsItsRows`; `emptyTabMessage`; `searchFiltersRows` (digitar o código de uma reserva no filtro deixa só ela); `clickOpensDetailWithMaskedDocument` (o texto do documento mascarado aparece; o número inteiro do falso nunca aparece — o falso guarda um número completo só para essa conferência); `historyInOrder` (os títulos aparecem na ordem cronológica); `noUuidOnScreen` (varre todos os textos da árvore semântica com a regex de UUID); `dateErrorShowsRetry`; `refreshButtonReloads`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a tela da recepção`).

---

### Task 7: A recepção na casca

**Estimativa:** 40 min · **Depende de:** Task 6 · **Risco:** alto (contrato da casca; assinatura por sessão)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/shell/Areas.kt` (`IMPLEMENTED_AREAS = setOf(Area.RECEPTION, Area.HOUSEKEEPING)`; a permissão da área **não muda**: `frontdesk.stays.checkin`/`checkout`, tabela do handoff da M1 E04); `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (`AreaContent`: `Area.RECEPTION` → `ReceptionArea(connection)`, no molde do `HousekeepingMap`: `connection.receptionSource()` nulo → texto "Recepção indisponível nesta conexão."; com sessão `Active`, `DayPanelState` e `ReservationDetailState` criados por `remember(source, sessionId)` sobre o `rememberCoroutineScope`, fechados num `DisposableEffect`; o foco da janela vem de `LocalWindowInfo.current.isWindowFocused` por `snapshotFlow` para `panel.onWindowFocus`)
- Modify (testes existentes, mandados por este plano, só o necessário para Recepção passar a ser implementada): `app/src/test/kotlin/com/zeny/desktop/app/shell/AreasTest.kt:~135` (`IMPLEMENTED_AREAS` = Recepção e Governança); `ShellStateTest.kt:~106/126/142/250` (com as permissões de cada caso, a lista esperada ganha `Area.RECEPTION to true` onde o usuário do teste tem `frontdesk.stays.*`); `ShellScreenUiTest.kt:~89-91`, `AppRootSessionSwitchTest.kt:~98`, `rooms/RoomMapShellTest.kt:~110/204`, `rooms/RoomMapShellAtaqueTest.kt:~244-254`, `LiveServerCheck.kt:~227`, `LiveServerAtaqueCheck.kt:~305-333` — onde a asserção trata Recepção como "(em breve)" ou só Governança como implementada; nenhuma outra asserção muda.
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionShellTest.kt`

**Interfaces:**
- Consumes: Task 6; `ShellState.selected`; `ServerConnection.receptionSource()`.

- [ ] **Step 1: Testes que falham:** `receptionAreaOpensPanel` (usuário com `frontdesk.stays.checkin` vê "Recepção" fora do modo de desenvolvimento; ao abri-la, o painel aparece e o falso recebe a assinatura `rooms` e as três listas); `subscriptionOnlyWhileAreaOpen` (trocar para Governança cancela a assinatura da recepção); `logoutClosesPanel`; `sessionSwitchRecreatesPanel` (sessão nova → estado novo, cache de nomes vazio); `connectionWithoutReceptionShowsUnavailable`; `areaHiddenWithoutPermission`; e os ajustes mandados acima.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build`, `./gradlew :app:desktopJar` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Põe o painel do dia na área Recepção`).

---

### Task 8: Verificação ao vivo contra o `zeny-front`

**Estimativa:** 35 min · **Depende de:** Task 7 · **Risco:** alto (segredos em uso; critérios de aceite)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveReceptionCheck.kt`
- Modify: `app/build.gradle.kts` (só o opt-in como entrada do `test`: `inputs.property("zenyLiveReception", …)` e `outputs.upToDateWhen` como os de `ZENY_LIVE_REALTIME`); correções pequenas com teste, no arquivo dono, se a verificação achar defeito.

**Interfaces:**
- Variáveis (arquivo 600 fora do repositório, gerado pelo orquestrador a partir de `.server/seed-secrets.env`): `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`/`ZENY_LIVE_VIEWER_PASSWORD` (Recepcionista). Opt-in `ZENY_LIVE_RECEPTION=1`; sem ele, pula mesmo com o resto exportado; com ele e sem credencial, falha.
- `LiveReceptionCheck.panelMatchesServer`: `CoreConnection` real, login da Recepcionista; `DayPanelState` real; espera as três abas carregarem; compara as contagens com uma leitura direta de cada lista pela `ReservationsFacade` na mesma data (todas as páginas); afirma contagens > 0 em chegadas e hospedados (o seed cria 4 chegadas, 5 hospedados e 2 day use); imprime as três contagens.
- `LiveReceptionCheck.detailOfInHouseReservation`: abre o detalhe de um hospedado; afirma situação "Hospedada", UH "UH <n>", histórico com "Reserva criada" seguido de "Check-in", em ordem; documento do titular casando `^(CPF|RG|CNH|RNM|Passaporte|Documento estrangeiro|Documento) .*` e sem 11 dígitos seguidos; nenhum texto com UUID; grava a captura `app/build/live/reception.png` (padrão do `LiveServerCheck`) com o detalhe aberto.

- [ ] **Step 1:** escreva as verificações.
- [ ] **Step 2:** `(set -a; . <arquivo do despacho>; set +a; ZENY_LIVE_RECEPTION=1 ./gradlew :app:test --tests '*LiveReceptionCheck*' --rerun --max-workers=2)` → verde; cole as contagens.
- [ ] **Step 3:** `./gradlew build` e `bash tests/run.sh` verdes sem as variáveis (as verificações pulam); commit (`Verifica o painel do dia contra o servidor`).

## Ondas

- Dependências: 2←1; 4←2,3; 5←4; 6←5; 7←6; 8←7. As Tasks 1 e 3 não dependem de nenhuma.
- Onda 1: Tasks 1 e 3 (`core/facade` × `app/reception`, arquivos disjuntos). Depois 2, 4, 5, 6, 7 e 8 em série na branch da etapa.

## Para o handoff

- H3: não há tópico de reservas no servidor fixado; o painel recarrega pelo tópico `rooms`, pelo foco da janela, pelo "Atualizar" e por `reload()` (para as ações da E02 em diante). Avaliar `PF-NN` de um tópico `reservations` se a demo mostrar listas defasadas;
- a área Recepção continua aparecendo por `frontdesk.stays.checkin`/`checkout` (M1 E04); as listas exigem `reservations.reservations.read` (`403` tratado);
- nomes de titular lidos por `getGuest`, um por hóspede, só em memória, na sessão;
- histórico: "Reserva criada" sintetizada; autor omitido (não há leitura de usuários para a Recepcionista);
- o comando da verificação ao vivo e o opt-in; a captura.
