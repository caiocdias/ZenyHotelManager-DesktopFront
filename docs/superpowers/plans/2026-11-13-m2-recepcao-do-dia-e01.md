# M2 E01 — Painel do dia e detalhe da reserva — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a área Recepção abre num painel com três abas (chegadas, saídas e hospedados da data operacional), cada uma com contagem, busca local e situação; clicar numa reserva abre o detalhe (titular com documento mascarado, período, tipo e UH, plano, situação, alocações e histórico em ordem cronológica), tudo em pt-BR, sem UUID nem campo técnico.

**Architecture:** no `core`, fachadas finas sobre as operações geradas (ADR 0008, opção b: devolvem os modelos do `sdk`): `ReservationsFacade` (listas do dia por página, detalhe, histórico inteiro, alocações), `GuestsFacade.guest(id)`, `RatesFacade.ratePlan(id)` e `HotelFacade.businessDay()` (data operacional **e** fuso, de `getBusinessDate`, sem permissão). No `design`, a `TableState` ganha `refresh()`, uma recarga que não esvazia a tabela. No `app`, pacote `com.zeny.desktop.app.reception`: textos e formatação puros (`ReceptionText`), uma porta estreita `ReceptionSource` (obtida por `ServerConnection.receptionSource()`, padrão `null` como `roomsSource()`; o hóspede chega só como resumo), dois *state holders* testáveis com um falso (`DayPanelState`, com uma `TableState` por aba sobre um `CursorPager`; `ReservationDetailState`) e a tela `ReceptionScreen`, ligada à área Recepção na casca, com a chave do `sessionId`.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (`LocalWindowInfo.current.isWindowFocused`), kotlinx-coroutines, `TableState`/`DataTable`/`StatusChip`/`chipModel` do `design`, `ExtensibleEnumLabels`/`Money`/`parseApiInstant`/`HotelClock` do `core`, kotlin-test + JUnit Platform, `kotlinx-coroutines-test`, `ktor-client-mock` (só nos testes do `core`), `runComposeUiTest` nas telas.

**Spec:** seção "E01 — Painel do dia e detalhe da reserva" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` (com "Contexto confirmado", "Restrições e invariantes" e H3); design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.1, §6.2 e §6.5; ADRs `0007-gerador-do-sdk.md` e `0008-fronteira-core-sdk.md`; handoffs da M1 E04 (tabela área → permissão; "a data operacional nunca depende de `getHotel`") e E06 (assinatura amarrada ao `sessionId`; um `403` de um tópico derruba todos os assinantes da união) em `docs/roadmaps/roadmap-m1-fundacao.md`. Contrato `api/openapi.yaml`: `listArrivals`, `listDepartures`, `listInHouse` (`date`, `cursor`, `limit` ≤ 200), `getReservation`, `listReservationHistory` (do mais recente ao mais antigo, por cursor), `listReservationRoomAssignments`, `getGuest` (documentos já mascarados em `maskedNumber`), `getRatePlan` (`rates.plans.read`), `getBusinessDate` (`businessDate` e `timezone`, obrigatórios, para todo usuário autenticado), `listRoomTypes`, `getMe`, `openStream`.

**Fatos do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- tópicos de tempo real existentes: só `rooms` (`rooms.status.read`), `notifications` e `end-of-day` (`endofday.runs.read`). **Não há tópico de reservas** (H3). O painel se atualiza: ao voltar o foco da janela; pelo botão "Atualizar"; pelo tópico `rooms` (check-in, check-out e troca de UH mudam o status da UH), com as mensagens agrupadas numa janela; pelo tópico `end-of-day` (o fechamento avança a data e marca no-shows); e por `refresh()` público, para as ações das etapas seguintes. Cada tópico só é assinado com a permissão dele em `getMe`, para um `403` não derrubar o outro;
- a lista não traz o nome do titular: só `holderGuestId` (o contrato manda ler o nome no `guests`; não há leitura em lote);
- `kind` do histórico: `CHANGE`, `CANCEL`, `REACTIVATE`, `CONFIRM`, `GUARANTEE`, `CHECK_IN`, `UNDO_CHECK_IN`, `CHECK_OUT`, `ROOM_MOVE` (extensível). A criação **não** gera entrada (o detalhe a sintetiza a partir de `reservation.createdAt`). `changedFields` vem destes nomes: `adults`, `arrival`, `cancellationTerms`, `childAges`, `departure`, `guaranteeTerms`, `nightlyRates`, `ratePlanId`, `roomTypeId`, `segment`, `specialRequests`, `status`, `checkIn`, `checkOut`, `guests`, `guarantee`, `roomAssignment`;
- `releaseReason` da alocação: `UNASSIGNED`, `REALLOCATED`, `RESERVATION_CANCELLED`, `RESERVATION_CHANGED`, `CHECKED_OUT`, `ROOM_MOVED` (extensível);
- o modelo Recepcionista tem `reservations.reservations.read`, `guests.profiles.read`, `rates.plans.read`, `rooms.status.read` e `endofday.runs.read`.

## Global Constraints

- **Regras do design §6.2:** nada de JSON ou `code` cru ao usuário (mensagens por `ApiError.userMessage`, via `errorMessage` do `design`); enum extensível com rótulo pt-BR e "Outro (`VALOR`)" para valor desconhecido (por `ExtensibleEnumLabels`) **exceto** onde o critério proíbe campo técnico (nome de campo mudado desconhecido → "outros dados"); dinheiro em `BigDecimal` (`Money`), nunca `Double`; instantes no fuso do hotel (`BusinessDateResponse.timezone`, via `HotelFacade.businessDay()`), **nunca** no da JVM; datas de negócio sem conversão de fuso; o servidor decide as permissões (`403` tratado).
- **Fronteira (ADR 0008):** `app` e `design` (main e test) não importam `com.zeny.desktop.sdk.client.*`, `com.zeny.desktop.sdk.util.*`, `io.ktor.client.*`, `de.quati.ogen.client.*` nem usam `@RawSdkAccess`; usam os modelos `com.zeny.desktop.sdk.model.*` e as fachadas do `core`.
- **Dados pessoais (Restrições do roadmap):** documento sempre mascarado (só o `maskedNumber` do servidor; nunca a ação de revelar); do perfil do hóspede, o `app` guarda só nome e primeiro documento mascarado (`HolderSummary`); nenhum dado de hóspede (nome, documento, motivo de restrição) em log, `toString` de estado, mensagem de exceção nem cache de disco — vivem só em memória, no *state holder* da sessão; o seed e os testes usam só dados fictícios.
- **Nada de UUID na tela:** toda referência a outro registro sem nome legível vira "—" (tipo de UH, plano, titular sem acesso) ou some (autor do histórico).
- **Tempo real:** toda assinatura nasce amarrada ao `sessionId` (a tela é recriada por `remember(source, sessionId)`); um fim de assinatura com erro não derruba o painel (só a atualização automática deixa de vir).
- **Testes não dormem** (`runTest`, tempo virtual). Verificação ao vivo só com o opt-in `ZENY_LIVE_RECEPTION=1` e as variáveis próprias; com o opt-in e sem credencial, falha.
- **Testes existentes:** só mudam os listados como "mandado por este plano", com a mudança esperada; os demais são do testador.
- Antes de cada commit: `./gradlew build` (inteiro) e `bash tests/run.sh` verdes; memória curta: `--max-workers=2`; nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez.
- Textos de UI em pt-BR; código em inglês. Pacotes `com.zeny.desktop.core.facade`, `com.zeny.desktop.design.table` e `com.zeny.desktop.app.reception`.
- Commits em pt-BR, no estilo do histórico, com as linhas `Soul: <soul>` e `Co-Authored-By: …` consecutivas no último parágrafo.

## Review Focus

1. **Gatilhos em rajada** (governança mudando várias UHs, foco, "Atualizar"): as mensagens de `rooms` dentro de 2 s viram uma atualização; no máximo uma atualização em voo e uma pendente; a tabela nunca pisca vazia numa atualização. Testes `roomsBurstIsDebounced`, `burstOfTriggersCoalesces` e `refreshKeepsRowsVisible` na Task 6 e `refreshKeepsRowsUntilNewPage` na Task 4.
2. **Hóspede titular sem acesso ou apagado** (`403`/`404` em `getGuest`): a linha mostra "—" e a lista carrega; `SessionEnded`, rede e resposta incompatível continuam virando erro da aba. Testes `guestForbiddenShowsDash` e `guestNetworkErrorFailsTab` na Task 6.
3. **Abrir outra reserva com o detalhe anterior em voo:** a resposta atrasada da primeira nunca aparece no detalhe da segunda. Teste `staleDetailDropped` na Task 7.
4. **Fuso do hotel ilegível:** o histórico mostra a data operacional da entrada ("13/11/2026 (data operacional)") e nenhum horário no fuso da JVM. Testes `businessDayInvalidZoneIsNull` na Task 1 e `noZoneFallsBackToBusinessDate` na Task 7.
5. **Usuário com a área Recepção e sem `reservations.reservations.read`:** as três abas mostram a mensagem de permissão em pt-BR, sem quebrar a tela. Teste `forbiddenListsShowMessage` na Task 6.

---

### Task 1: Fachada das reservas e dia operacional com fuso no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (contrato público do `core`; parsing de resposta externa)

**Files:**
- Create: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/Facades.kt` (só acrescenta `BusinessDay` e `HotelFacade.businessDay()`; `HotelInfo`, `hotel()` e `businessDate()` **não mudam**), `core/src/main/kotlin/com/zeny/desktop/core/ZenyCore.kt` (`val reservations`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/ReservationsFacadeTest.kt`, `BusinessDayTest.kt` (no padrão dos testes de fachada existentes, com `MockEngine`)

**Interfaces:**
- Produces:
  - `enum class DayList { ARRIVALS, DEPARTURES, IN_HOUSE }` (em `ReservationsFacade.kt`);
  - `class ReservationsFacade internal constructor(reservations: ReservationsApi, executor: ApiExecutor)` com
    - `suspend fun dayPage(list: DayList, date: BusinessDate, cursor: String?, limit: Int): Page<ReservationListItemResponse>` (`date.toApi()` sempre enviado; `limit` repassado; `nextCursor` nulo/`Undefined` → `null`);
    - `suspend fun reservation(id: String): ReservationDetailResponse`;
    - `suspend fun history(id: String): List<ReservationHistoryResponse>` (todas as páginas, `limit` 200, na ordem do servidor, por `CursorPager` como `RoomsFacade.readAll`);
    - `suspend fun roomAssignments(id: String): List<RoomAssignmentResponse>`;
    - `id` que não é UUID → `IllegalArgumentException("id de reserva inválido")` antes da rede (como `RoomsFacade`).
  - `data class BusinessDay(val date: BusinessDate, val zone: ZoneId?)` e `suspend fun HotelFacade.businessDay(): BusinessDay` — uma chamada a `getBusinessDate`; data ilegível → `ApiError.IncompatibleResponse` (como `businessDate()`); fuso ausente, em branco ou inválido → `zone = null` (nunca exceção, nunca o fuso da JVM).
  - `ZenyCore.reservations: ReservationsFacade`.

- [ ] **Step 1: Testes que falham:** `dayPageSendsDateCursorAndLimit` (as três listas batem nos caminhos `/arrivals`, `/departures`, `/in-house` com `date=2026-11-13`, `cursor` e `limit=200`); `dayPageEndsWithoutCursor`; `historyReadsAllPages` (duas páginas → lista concatenada na ordem do servidor); `assignmentsReturnItems`; `invalidIdFailsBeforeNetwork` (nenhuma requisição); `notFoundIsProblem` (`404 RESERVATION_NOT_FOUND` → `ApiError.Problem` com a mensagem do catálogo); `businessDayReadsDateAndZone` (`"America/Sao_Paulo"`); `businessDayInvalidZoneIsNull` (`"Mars/Base"` e `""`); `businessDayBadDateIsIncompatible`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*ReservationsFacadeTest*' --tests '*BusinessDayTest*'` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a fachada das reservas do dia e o fuso do dia operacional`).

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

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (interpreta entrada externa; monta o texto do documento)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionText.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionTextTest.kt`

**Interfaces:**
- Produces (`object ReceptionText`, funções puras, sem estado; rótulos de enum extensível por `ExtensibleEnumLabels`, tom por `chipModel`/`ChipModel` do `design` quando houver):
  - `fun status(value: String): ChipModel` — `PROVISIONAL` "Pré-reserva" (WARNING), `CONFIRMED` "Confirmada" (INFO), `IN_HOUSE` "Hospedada" (SUCCESS), `CHECKED_OUT` "Saída registrada" (NEUTRAL), `CANCELLED` "Cancelada" (DANGER), `NO_SHOW` "Não compareceu" (DANGER), outro → "Outro (VALOR)" (NEUTRAL);
  - `fun historyKind(value: String): String` — `CHANGE` "Reserva alterada", `CANCEL` "Reserva cancelada", `REACTIVATE` "Reserva reativada", `CONFIRM` "Pré-reserva confirmada", `GUARANTEE` "Garantia registrada", `CHECK_IN` "Check-in", `UNDO_CHECK_IN` "Check-in desfeito", `CHECK_OUT` "Check-out", `ROOM_MOVE` "Troca de UH", outro → "Outro (VALOR)";
  - `fun changedFields(fields: List<String>): String` — rótulos em minúsculas, na ordem recebida, sem repetir, juntados por ", ": `adults` "adultos", `childAges` "crianças", `arrival` "chegada", `departure` "saída", `cancellationTerms` "política de cancelamento", `guaranteeTerms` "política de garantia", `guarantee` "garantia", `nightlyRates` "diárias", `ratePlanId` "plano", `roomTypeId` "tipo de UH", `segment` "segmento", `specialRequests` "pedidos especiais", `status` "situação", `checkIn` "check-in", `checkOut` "check-out", `guests` "hóspedes", `roomAssignment` "UH"; desconhecidos viram um único "outros dados" no fim; lista vazia → "";
  - `fun assignmentReason(value: String): String` — `INITIAL` "Alocação inicial", `GUEST_REQUEST` "Pedido do hóspede", `OUT_OF_ORDER` "UH fora de ordem", `ROOM_ISSUE` "Problema na UH", `OPERATIONAL` "Operacional", outro → "Outro (VALOR)";
  - `fun releaseReason(value: String): String` — `UNASSIGNED` "Liberada", `REALLOCATED` "Realocada", `RESERVATION_CANCELLED` "Reserva cancelada", `RESERVATION_CHANGED` "Reserva alterada", `CHECKED_OUT` "Check-out", `ROOM_MOVED` "Troca de UH", outro → "Outro (VALOR)";
  - `fun planCategory(value: String): String` — `NORMAL` "Normal", `COURTESY` "Cortesia", `HOUSE_USE` "Uso da casa", outro → "Outro (VALOR)";
  - `fun document(type: String, masked: String): String` — `CPF`/`RG`/`CNH`/`RNM` com a sigla, `PASSPORT` "Passaporte", `FOREIGN_ID` "Documento estrangeiro", outro → "Documento"; formato `"<rótulo> <masked>"` (o número só como veio mascarado);
  - `fun date(iso: String): String` — `2026-11-13` → `13/11/2026`; texto ilegível → "—";
  - `fun period(arrival: String, departure: String, dayUse: Boolean): String` — `"13/11/2026 a 15/11/2026 · 2 noites"`, `"… · 1 noite"`, day use `"13/11/2026 · day use"`; data ilegível → "—";
  - `fun guests(adults: Int, children: Int): String` — `"2 adultos"`, `"1 adulto e 1 criança"`, `"2 adultos e 3 crianças"`;
  - `fun money(decimal: String): String` — por `Money.parse`, `"R$ 1.234,56"` (locale pt-BR, 2 casas); ilegível → "—";
  - `fun instant(iso: String, zone: ZoneId?): String?` — por `parseApiInstant` e `HotelClock`, `"13/11/2026 14:05"` no `zone`; `zone` nulo ou texto ilegível → `null`.

- [ ] **Step 1: Testes que falham:** um por função, com os valores acima, mais `unknownValuesNeverShowFieldNames` (`changedFields(listOf("status", "fooBar"))` = "situação, outros dados"), `releaseReasonRoomMoved` (`ROOM_MOVED` → "Troca de UH"; `ROOM_MOVE` → "Outro (ROOM_MOVE)") e `instantUsesHotelZoneNotJvm` (com `TimeZone.setDefault` trocado no teste e restaurado num `finally`, `2026-11-13T17:05:00Z` em `America/Sao_Paulo` → `"13/11/2026 14:05"`).
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta os textos e a formatação da recepção`).

---

### Task 4: Atualização sem esvaziar na `TableState`

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (concorrência; componente compartilhado do `design`)

**Files:**
- Modify: `design/src/main/kotlin/com/zeny/desktop/design/table/TableState.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/table/TableStateRefreshTest.kt`

**Interfaces:**
- Produces: `suspend fun TableState<T>.refresh()` (membro) — pede uma listagem nova **sem esvaziar**: como `reload`, sobe a geração e cancela o `fetch` em voo (uma carga mais nova sempre vence); `loading` fica `true` durante a carga; as `rows` atuais, `hasMore` e `error` ficam como estão até a primeira página do pager novo chegar; com a página, troca de uma vez `rows`, as chaves vistas, o pager e `hasMore`, e limpa `error`; `ApiError` → mantém as `rows` e põe `error`; cancelada por um `reload`/`refresh` mais novo → nada muda. **Atualização pendente:** depois de um `refresh()` que falhou com `ApiError`, ou que foi cancelado sem nenhum `reload`/`refresh` mais novo (o chamador foi cancelado), a tabela fica com a atualização pendente: as `rows` antigas continuam à vista e a próxima chamada de `loadMore()` (inclusive a do "Tentar de novo" da `DataTable`, que com linhas na tela chama `loadMore`) **repete a atualização** — pager novo, primeira página e troca de uma vez, **mesmo com `hasMore` falso** na lista antiga —, nunca acrescenta página do pager velho nem do novo às linhas antigas; um `reload`/`refresh` posterior também desfaz a pendência. `reload`, `sort`, `filter` e `visibleRows` não mudam de comportamento; `loadMore` só muda no caso da atualização pendente.

- [ ] **Step 1: Testes que falham** (`runTest`, pager falso com páginas controladas): `refreshKeepsRowsUntilNewPage` (durante a carga, `rows` é a lista antiga e `loading` é `true`; depois, a página nova inteira); `refreshReplacesHasMoreAndKeys` (linha que só existia na lista antiga some; `loadMore` seguinte usa o cursor do pager novo); `refreshErrorKeepsRows`; `retryAfterRefreshErrorRefreshes` (lista antiga de uma página só, com `hasMore` falso; depois do erro, `loadMore()` troca as linhas pela lista nova inteira, inclusive uma reserva cuja versão mudou, e limpa `error`); `cancelledRefreshLeavesTableUsable` (o chamador do `refresh` é cancelado no meio; as linhas antigas ficam e o `loadMore()` seguinte faz a atualização); `newerReloadCancelsRefresh` (a página do `refresh` cancelado nunca entra); `refreshCancelsInFlightLoadMore`; `reloadStillEmpties` (o `reload` continua esvaziando, como hoje); `loadMoreUnchangedWithoutPendingRefresh`.
- [ ] **Step 2:** `./gradlew :design:test --tests '*TableStateRefreshTest*'` → FAIL.
- [ ] **Step 3:** implemente, reaproveitando o protocolo de geração e o `mutex` da classe.
- [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a atualização da tabela sem esvaziar as linhas`).

---

### Task 5: Porta da recepção e adaptador

**Estimativa:** 25 min · **Depende de:** Tasks 2 e 3 · **Risco:** alto (dado pessoal; contrato da porta)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionSource.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (`fun receptionSource(): ReceptionSource? = null`; o `CoreConnection` implementa sobre `core.account`, `core.hotel.businessDay()`, `core.reservations`, `core.guests`, `core.rates`, `core.rooms.roomTypes()` e `core.realtime.subscribe`; os falsos existentes não mudam)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/FakeReceptionSource.kt` (falso controlável, usado pelas Tasks 6 a 9), `ReceptionSourceTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3; `RealtimeEvent` do `core`.
- Produces:
  - `data class HolderSummary(val name: String, val document: String?)` — `toString()` = `"HolderSummary(***)"`; e a função pura `fun GuestResponse.toHolderSummary(): HolderSummary` (em `ReceptionSource.kt`): `name` = `socialName` se houver e não estiver em branco, senão `fullName`; `document` = `ReceptionText.document(type, maskedNumber)` do primeiro documento, ou `null`. O `CoreConnection` usa essa função; o `GuestResponse` não sai do adaptador.
  - `interface ReceptionSource { suspend fun permissions(): Set<String>; suspend fun businessDay(): BusinessDay; suspend fun dayPage(list: DayList, date: BusinessDate, cursor: String?, limit: Int): Page<ReservationListItemResponse>; suspend fun reservation(id: String): ReservationDetailResponse; suspend fun history(id: String): List<ReservationHistoryResponse>; suspend fun roomAssignments(id: String): List<RoomAssignmentResponse>; suspend fun holder(guestId: String): HolderSummary; suspend fun ratePlanName(id: String): String; suspend fun roomTypes(): List<RoomTypeResponse>; fun events(topics: Set<String>): Flow<RealtimeEvent> }` (`permissions()` = `core.account.me().permissions`; `ratePlanName` = `core.rates.ratePlan(id).name`).
  - `FakeReceptionSource`: respostas por lista e página, por reserva, por titular e por plano; permissões; eventos por um canal controlado; registro de cada chamada (lista, data, cursor) e de cada assinatura (tópicos, cancelamento); chamadas que podem ficar presas até o teste soltar.

- [ ] **Step 1: Testes que falham:** `summaryPrefersSocialName`; `summaryFallsBackToFullNameWhenSocialBlank`; `summaryUsesFirstMaskedDocument` (`"CPF ***.***.***-34"`); `summaryWithoutDocumentIsNull`; `summaryToStringHidesData`; `coreConnectionOffersReceptionSource` (no molde do `CoreConnectionTest`: `receptionSource()` não nulo sobre um `ZenyCore` de teste); `defaultConnectionHasNoReceptionSource` (um falso de `ServerConnection` existente devolve `null`).
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a porta da recepção sobre o core`).

---

### Task 6: Estado do painel do dia

**Estimativa:** 45 min · **Depende de:** Tasks 4 e 5 · **Risco:** alto (concorrência; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/DayPanelState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/DayPanelStateTest.kt` (com o `FakeReceptionSource` da Task 5; pode acrescentar ao falso o que faltar)

**Interfaces:**
- Consumes: Tasks 3–5; `TableState`, `Column` e `ChipModel` do `design`; `CursorPager`/`Page` e `RealtimeEvent` do `core`.
- Produces:
  - `data class ReservationRow(val id: String, val code: String, val holder: String, val room: String, val roomType: String, val arrival: String, val departure: String, val guests: String, val status: String)` — `holder` = `HolderSummary.name` ou "—"; `room` = `roomAssignment.roomNumber` ou "—"; `roomType` = nome do tipo ou "—"; `status` = rótulo da situação seguido, por " · ", dos alertas que valem, nesta ordem: "Day use", "Chegada pendente" (`arrivalOverdue`), "Saída pendente" (`departureOverdue`) (a busca da tabela acha os alertas); `toString()` sem `holder`.
  - `class DayPanelState(source: ReceptionSource, scope: CoroutineScope)` (mutações no escopo da UI, uma thread; como `RoomMapState`):
    - `val tabs: Map<DayList, TableState<ReservationRow>>` (`CursorPager` com `limit` 200, chave `id`); o `fetch` do pager **espera** a primeira data operacional (nenhuma `dayPage` sai antes dela, nem pela `DataTable`) e usa sempre a data da carga vigente;
    - `var selectedTab: DayList` (começa em `ARRIVALS`); `var day: BusinessDay?`; `var error: ApiError?` (só da data operacional); `val columns: List<Column<ReservationRow>>` ("Reserva", "Titular", "UH", "Tipo", "Chegada", "Saída", "Hóspedes", "Situação");
    - `fun tabLabel(list: DayList): String` — "Chegadas", "Saídas", "Hospedados", com `" (n)"` depois de a aba carregar e `" (n+)"` enquanto há página por carregar (n = linhas carregadas);
    - `fun select(list: DayList)`; `fun refresh()` (relê o dia operacional e chama `TableState.refresh()` nas três abas; o primeiro carregamento usa `reload()`); `fun onWindowFocus(focused: Boolean)` (atualiza só na transição para `true`); `fun close()`.
  - Protocolo:
    - `init`: lê `permissions()` e assina `rooms` só com `rooms.status.read` e `end-of-day` só com `endofday.runs.read` (uma assinatura com a união; sem nenhuma das duas, ou com falha de `permissions()`, não assina); faz a primeira carga (dia operacional, depois as três abas);
    - cada página resolve os titulares que faltam no cache em memória (até 4 `holder()` em paralelo) e os tipos de UH (lidos uma vez por carga); `holder()` com `ApiError.Problem` de status 403 ou 404 → "—" (guardado); qualquer outro `ApiError` falha a página (vira `error` da aba); `roomTypes()` com `ApiError.Problem` → "—" para todos;
    - eventos: mensagem de `end-of-day`, o primeiro `Ready` e todo `Reset` → `refresh()`; mensagens de `rooms` → `refresh()` uma vez, 2 000 ms depois da primeira mensagem da rajada (as que chegam nessa janela entram na mesma); fim da assinatura (normal ou com erro) → para de ouvir, sem erro na tela;
    - no máximo uma atualização em voo e uma pendente, venha de onde vier.

- [ ] **Step 1: Testes que falham** (`FakeReceptionSource` da Task 5; `runTest`): `loadsThreeTabsForBusinessDate` (a data do falso vai em todas as chamadas; rótulos "Chegadas (4)", "Saídas (2)", "Hospedados (7)"); `noPageBeforeBusinessDay` (um `loadMore` da aba antes da data responder não chama `dayPage`; depois, chama com a data); `emptyListShowsZero` ("Chegadas (0)", sem erro); `tabErrorKeepsOthers` (erro só nas saídas → `tabs[DEPARTURES].error` com a mensagem pt-BR; as outras carregadas); `forbiddenListsShowMessage` (`403` nas três listas → as três com a mensagem de permissão; `error` do painel nulo); `refreshAfterErrorRecovers`; `businessDayErrorShowsError`; `holderNameFromSummary`; `guestForbiddenShowsDash`; `guestNetworkErrorFailsTab`; `holderFetchedOncePerId` (mesmo titular em duas abas → uma chamada); `roomTypeForbiddenShowsDash`; `statusCellCarriesAlerts` ("Confirmada · Day use · Chegada pendente"); `labelShowsPlusWhileMorePages` ("Chegadas (200+)" com `nextCursor`; depois de `loadMore`, "Chegadas (230)"); `subscribesOnlyPermittedTopics` (só `rooms`; só `end-of-day`; os dois; nenhum); `roomsBurstIsDebounced` (5 mensagens em 1 s → uma atualização, 2 000 ms depois da primeira); `endOfDayRefreshesWithNewDate` (a data nova vai nas listas); `burstOfTriggersCoalesces` (foco + `refresh()` + `Reset` com uma carga presa → no máximo 2 cargas ao todo); `refreshKeepsRowsVisible` (durante a atualização, as linhas antigas continuam); `focusRefreshesOnlyOnGain`; `streamFailureKeepsPanel` (assinatura termina com `ApiError` → sem `error`, painel segue, `refresh()` funciona); `closeCancelsSubscriptionAndLoads`; `rowToStringHidesHolder`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*DayPanelStateTest*'` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do painel do dia da recepção`).

---

### Task 7: Estado do detalhe da reserva

**Estimativa:** 40 min · **Depende de:** Task 6 · **Risco:** alto (dado pessoal; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReservationDetailState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReservationDetailStateTest.kt` (reusa o `FakeReceptionSource`)

**Interfaces:**
- Consumes: `ReceptionSource`, `HolderSummary`, `ReceptionText`.
- Produces:
  - `data class HistoryLine(val at: String, val title: String, val detail: String)`; `data class AssignmentLine(val room: String, val period: String, val reason: String, val state: String, val doNotMove: Boolean)`; `data class ReservationDetailView(val code: String, val status: ChipModel, val holder: String, val document: String?, val period: String, val roomType: String, val room: String, val guests: String, val plan: String, val total: String, val alerts: List<String>, val assignments: List<AssignmentLine>, val assignmentsError: ApiError?, val history: List<HistoryLine>, val historyError: ApiError?)` — `toString()` sem `holder`, `document` nem `alerts`.
  - `class ReservationDetailState(source: ReceptionSource, scope: CoroutineScope)`: `var openId: String?`; `var loading`; `var error: ApiError?` (falha de `reservation(id)`); `var view: ReservationDetailView?`; `fun open(id: String)` (cancela a carga anterior; resposta de outro id nunca entra); `fun reload()` (relê o aberto; sem nada aberto, nada faz); `fun close()`.
  - Montagem: em paralelo `reservation(id)`, `history(id)`, `roomAssignments(id)`, `businessDay()` (só o fuso; falha → sem fuso); **depois** da reserva, em paralelo `holder(holderGuestId)` e `ratePlanName(ratePlanId)`. `holder`/`document` do resumo; titular com 403/404 → "—"/`null`; `plan` = nome do plano ou, com `ApiError.Problem`, `ReceptionText.planCategory`; tipo de UH por `roomTypes()` ou "—"; `room` = "UH <número>" da alocação ativa, senão "Sem UH"; `total` = `ReceptionText.money`; `assignments` na ordem do servidor, `state` = "Ativa" ou "Solta · <releaseReason>", período por `ReceptionText.period(startsOn, endsOn, dayUse)`; `alerts` = "Hóspede com restrição: <reason>" por `restrictionAlerts`; `history` em ordem **cronológica** (mais antiga primeiro; desempate por `version`), começando por uma linha sintetizada "Reserva criada" em `createdAt`; `title` = `historyKind`; `detail` = partes não vazias juntadas por " · ": "Alterou: <changedFields>", "Situação: <antes> → <depois>" quando `status` mudou, "Com aprovação de superior" quando `approvalId` existe; `at` = `ReceptionText.instant(occurredAt, zone)` ou, sem fuso, `"<data> (data operacional)"` a partir de `businessDate` (a linha sintetizada sem fuso usa "—"); nunca autor, ids nem retratos crus; falha de `history` ou de `roomAssignments` vira só o erro da seção.

- [ ] **Step 1: Testes que falham:** `buildsDetailWithMaskedDocument` (o `document` é exatamente o texto do resumo, `"CPF ***.***.***-34"`); `historyIsChronologicalWithCreation`; `historyDetailInPortuguese` (`CHECK_IN` com `status` mudado de `CONFIRMED` para `IN_HOUSE` e aprovação → "Alterou: situação, check-in, hóspedes · Situação: Confirmada → Hospedada · Com aprovação de superior"); `noUuidAnywhere` (nenhum campo da view casa `[0-9a-f]{8}-[0-9a-f]{4}-`); `noZoneFallsBackToBusinessDate`; `planFallsBackToCategory`; `activeAssignmentGivesRoom`; `releasedByRoomMoveShowsLabel` ("Solta · Troca de UH"); `holderForbiddenShowsDash`; `historyErrorKeepsDetail`; `notFoundShowsError` (`404 RESERVATION_NOT_FOUND` → `error` com a mensagem do catálogo); `staleDetailDropped`; `reloadRefetches`; `viewToStringHidesPersonalData` (sem nome, documento nem motivo de restrição).
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do detalhe da reserva`).

---

### Task 8: Tela da recepção

**Estimativa:** 40 min · **Depende de:** Task 7 · **Risco:** alto (superfície da demo; dado pessoal na tela)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionScreenTest.kt` (`runComposeUiTest`, com o `FakeReceptionSource`)

**Interfaces:**
- Consumes: `DayPanelState`, `ReservationDetailState`; `design`: `DataTable`, `StatusChip`, `LoadingState`, `ErrorState`, `EmptyState`.
- Produces: `@Composable fun ReceptionScreen(panel: DayPanelState, detail: ReservationDetailState)` — título "Recepção" e "Data operacional: 13/11/2026"; botão "Atualizar" (`panel.refresh()` e `detail.reload()`); abas com `panel.tabLabel(...)` e `testTag("tab-ARRIVALS")` etc.; enquanto `panel.day == null`: `LoadingState` (sem erro) ou `ErrorState` com "Tentar de novo" (com erro) — a `DataTable` só entra com a data; depois, a `DataTable` da aba (vazia: "Nenhuma reserva nesta lista."); clicar numa linha → `detail.open(id)` e painel lateral com: código, situação (`StatusChip` com o `ChipModel`), titular, documento, período, tipo, UH, hóspedes, plano, total, alertas, seção "Alocações" e seção "Histórico" (cada uma com o próprio erro e "Tentar de novo"), "Fechar"; detalhe carregando → `LoadingState`; erro do detalhe → `ErrorState`.

- [ ] **Step 1: Testes que falham:** `tabsShowCounts`; `switchingTabShowsItsRows`; `emptyTabMessage`; `noTableBeforeBusinessDay` (com a data presa, nenhuma `dayPage` é chamada e aparece o carregando); `searchFiltersRows` (digitar o código de uma reserva no filtro deixa só ela); `clickOpensDetailWithMaskedDocument` (o texto do documento mascarado aparece; um número completo que o falso guarda só para a conferência nunca aparece); `historyInOrder` (os títulos aparecem na ordem cronológica); `noUuidOnScreen` (varre todos os textos da árvore semântica com a regex de UUID); `dateErrorShowsRetry`; `refreshButtonRefreshes`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a tela da recepção`).

---

### Task 9: A recepção na casca

**Estimativa:** 40 min · **Depende de:** Task 8 · **Risco:** alto (contrato da casca; assinatura por sessão)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/shell/Areas.kt` (`IMPLEMENTED_AREAS = setOf(Area.RECEPTION, Area.HOUSEKEEPING)`; a permissão da área **não muda**: `frontdesk.stays.checkin`/`checkout`, tabela do handoff da M1 E04); `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (`AreaContent`: `Area.RECEPTION` → `ReceptionArea(connection)`, no molde do `HousekeepingMap`: `connection.receptionSource()` nulo → texto "Recepção indisponível nesta conexão."; com sessão `Active`, `DayPanelState` e `ReservationDetailState` criados por `remember(source, sessionId)` sobre o `rememberCoroutineScope`, fechados num `DisposableEffect`; o foco da janela vem de `LocalWindowInfo.current.isWindowFocused` por `snapshotFlow`: no ganho de foco, `panel.onWindowFocus(true)` e `detail.reload()`; na perda, `panel.onWindowFocus(false)`)
- Modify (testes existentes, mandados por este plano, só o necessário para a Recepção passar a ser implementada):
  - `app/src/test/kotlin/com/zeny/desktop/app/shell/AreasTest.kt:~135` — `IMPLEMENTED_AREAS` = `setOf(Area.RECEPTION, Area.HOUSEKEEPING)`;
  - `ShellStateTest.kt:~249-250` (`devModeShowsComingSoonAreas`, administrador) — `it in setOf(Area.RECEPTION, Area.HOUSEKEEPING)` e `listOf(Area.RECEPTION to true, Area.HOUSEKEEPING to true)`;
  - `ShellStateTest.kt:~254-261` (`comingSoonAreaCannotBeSelected`) — a área "(em breve)" do teste passa de `Area.RECEPTION` para `Area.RESERVATIONS`; a asserção (`assertNull(state.selected)`) fica;
  - as linhas `~106/126/142` do `ShellStateTest` **não mudam** (o `Me` padrão do falso só tem `rooms.status.read`);
  - `ShellScreenUiTest.kt:~89-91`, `AppRootSessionSwitchTest.kt:~98`, `rooms/RoomMapShellTest.kt:~110/204`, `rooms/RoomMapShellAtaqueTest.kt:~244-254`, `LiveServerCheck.kt:~227`, `LiveServerAtaqueCheck.kt:~305-333` — **só** onde a asserção trata a Recepção como "(em breve)", ou só a Governança como implementada, troca para a Recepção implementada (sem "(em breve)"); nenhuma outra asserção muda. Se algum desses casos não precisar mudar, não mude.
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/ReceptionShellTest.kt`

**Interfaces:**
- Consumes: Task 8; `ShellState.selected`; `ServerConnection.receptionSource()`.

- [ ] **Step 1: Testes que falham:** `receptionAreaOpensPanel` (usuário com `frontdesk.stays.checkin` vê "Recepção" fora do modo de desenvolvimento; ao abri-la, o painel aparece e o falso recebe a assinatura e as três listas); `subscriptionOnlyWhileAreaOpen` (trocar para Governança cancela a assinatura da recepção); `logoutClosesPanel`; `sessionSwitchRecreatesPanel` (sessão nova → estado novo, cache de titulares vazio); `connectionWithoutReceptionShowsUnavailable`; `areaHiddenWithoutPermission`; e os ajustes mandados acima.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build`, `./gradlew :app:desktopJar` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Põe o painel do dia na área Recepção`).

---

### Task 10: Verificação ao vivo contra o `zeny-front`

**Estimativa:** 35 min · **Depende de:** Task 9 · **Risco:** alto (segredos em uso; critérios de aceite)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveReceptionCheck.kt`
- Modify: `app/build.gradle.kts` (só o opt-in como entrada do `test`: `inputs.property("zenyLiveReception", …)` e `outputs.upToDateWhen` como os de `ZENY_LIVE_REALTIME`); correções pequenas com teste, no arquivo dono, se a verificação achar defeito.

**Interfaces:**
- Variáveis (arquivo 600 fora do repositório, gerado pelo orquestrador a partir de `.server/seed-secrets.env`): `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`/`ZENY_LIVE_VIEWER_PASSWORD` (Recepcionista). Opt-in `ZENY_LIVE_RECEPTION=1`; sem ele, pula mesmo com o resto exportado; com ele e sem credencial, falha.
- `LiveReceptionCheck.panelMatchesServer`: `CoreConnection` real, login da Recepcionista; `DayPanelState` real; espera as três abas carregarem; compara as contagens com uma leitura direta de cada lista pela `ReservationsFacade` na mesma data (todas as páginas); afirma contagens > 0 em chegadas e hospedados; imprime as três contagens.
- `LiveReceptionCheck.detailOfInHouseReservation`: abre o detalhe de um hospedado; afirma situação "Hospedada", UH "UH <n>", histórico com "Reserva criada" seguido de "Check-in", em ordem; documento do titular casando `^(CPF|RG|CNH|RNM|Passaporte|Documento estrangeiro|Documento) .*` e sem 11 dígitos seguidos; nenhum texto com UUID; grava a captura `app/build/live/reception.png` (padrão do `LiveServerCheck`) com o detalhe aberto.

- [ ] **Step 1:** escreva as verificações.
- [ ] **Step 2:** `(set -a; . <arquivo do despacho>; set +a; ZENY_LIVE_RECEPTION=1 ./gradlew :app:test --tests '*LiveReceptionCheck*' --rerun --max-workers=2)` → verde; cole as contagens.
- [ ] **Step 3:** `./gradlew build` e `bash tests/run.sh` verdes sem as variáveis (as verificações pulam); commit (`Verifica o painel do dia contra o servidor`).

## Ondas

- Dependências: 2←1; 5←2,3; 6←4,5; 7←6; 8←7; 9←8; 10←9. As Tasks 1, 3 e 4 não dependem de nenhuma.
- Ondas de no máximo 2 (memória curta para Gradles simultâneos, como na M1 E07): onda 1 = Tasks 1 e 3 (`core/facade` × `app/reception`); onda 2 = Tasks 2 e 4 (`core/facade` × `design/table`). Depois 5, 6, 7, 8, 9 e 10 em série na branch da etapa (a 7 também edita o falso da Task 5).

## Para o handoff

- H3: não há tópico de reservas no servidor fixado; o painel se atualiza por `rooms` (agrupado em 2 s), `end-of-day`, foco da janela, "Atualizar" e `refresh()` (para as ações da E02 em diante). Avaliar `PF-NN` de um tópico `reservations` se a demo mostrar listas defasadas;
- a área Recepção continua aparecendo por `frontdesk.stays.checkin`/`checkout` (M1 E04), e não por `reservations.reservations.read` como diz a etapa (que daria a Recepção a Comercial/Eventos e Financeiro sem nenhuma ação); as listas exigem `reservations.reservations.read` (`403` tratado) — "Decisões para o usuário";
- titulares lidos por `getGuest`, um por hóspede (até 200 por página, 4 em paralelo), só nome e primeiro documento mascarado, só em memória, na sessão;
- fuso do hotel lido de `getBusinessDate` (sem permissão);
- `TableState.refresh()` no `design` (atualização sem esvaziar), disponível às próximas telas;
- histórico: "Reserva criada" sintetizada; autor omitido (não há leitura de usuários para a Recepcionista);
- o comando da verificação ao vivo e o opt-in; a captura.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 2 (revisor-arquitetura, modelo opus).
- **Rodada 0:** bloqueado (0 Críticos, 8 Importantes: fuso por `getHotel` em vez de `getBusinessDate`, que dispensa permissão e não quebra os testes de `HotelInfo`; `ROOM_MOVED`; `DataTable` pedindo página antes da data; recarga a cada mensagem de `rooms` esvaziando a tabela; tópico `end-of-day` ignorado; `toString` com o motivo de restrição; Task 3 com risco normal; lista errada de testes mandados da casca; 10 Menores). Todos corrigidos.
- **Rodada 1:** bloqueado (1 Importante novo: o "Tentar de novo" morto ou com linhas velhas depois de um `refresh()` que falha; 1 Menor: tarefa do painel grande demais, separada em porta e painel). Corrigidos.
- **Notas Menores da rodada 2:** N3 (o retry da atualização pendente ignora `hasMore`; teste com lista de uma página) — aplicada antes da execução. Ficam como registro: o total em dinheiro não está nos critérios da etapa (custo baixo, dado do servidor); o repositório não tem `ARCHITECTURE.md` (o julgamento foi contra a spec, o design, as ADRs e o código); `LocalWindowInfo.current.isWindowFocused` e as contagens do seed não foram conferidos pelo revisor.

