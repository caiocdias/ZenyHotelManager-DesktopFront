# M2 E03 — Walk-in — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** um hóspede sem reserva chega ao balcão. O recepcionista informa a saída, os adultos e as crianças e vê as ofertas, cada uma com tipo de UH, plano e total. Sem oferta, aparece a mensagem de indisponibilidade antes de qualquer dado de hóspede. Depois ele escolhe uma oferta e uma UH vaga, pronta e livre no período desse tipo, escolhe ou cadastra o hóspede principal (o painel da E02) e conclui. A reserva nasce hospedada, e o painel e o mapa refletem a mudança.

**Architecture:** no `core`, a `ReservationsFacade` ganha `quoteStay` (cotação no canal `DIRECT`), `assignedRoomIds` (UHs alocadas a reservas no período) e `walkIn` (`ApprovableAction` com a `Intent` da tela, no molde do `checkIn` da E02). No `app`, a porta `ReceptionSource` ganha as duas operações, e a `ActionText` ganha os textos do walk-in. Um *state holder* `WalkInState` cobre a estadia, a cotação, a oferta e a UH, e reaproveita o `GuestPickerState` e a regra da `Idempotency-Key` da E02. A tela ganha o `WalkInDialog`, aberto por "Walk-in" no cabeçalho da recepção.

**Tech Stack:** o da E02: Kotlin 2.4.21, Compose Multiplatform 1.12.2, kotlinx-coroutines (`runTest`), `ApiExecutor`/`Intent`/`ApprovableAction`, `ApprovalCoordinator`, `GuestPickerState`/`GuestPickerPanel`, `ActionText`, `ReceptionText`, `FormState`/`DateInput`, `runComposeUiTest`, `ktor-client-mock` no `core`.

**Spec:** a seção "E03 — Walk-in" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` (com "Contexto confirmado" e "Restrições e invariantes") e o handoff da E02 na mesma seção, que traz a convenção da `Idempotency-Key` e as notas para a E03. Também valem o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.2 e §6.5, os ADRs `0007` (D2) e `0008` e o contrato `api/openapi.yaml`:
- `createWalkIn` (`POST /api/v1/walk-ins`): `Idempotency-Key` obrigatório. Corpo `WalkInRequest`:
  - obrigatórios: `departure`, `adults`, `childAges`, `ratePlanId`, `roomId`, `segment` (`LEISURE`/`BUSINESS`/`EVENT`/`OTHER`) e `guests`, que segue o `CheckInGuestRequest`: exatamente um principal;
  - opcionais: `roomTypeId` (nulo = o tipo da UH), `holderGuestId` (nulo = o principal), `guestCount`, `earlyCheckInFee`, `preAuthorization` e `specialRequests`.

  Devolve `WalkInResponse` com `bookingCode`, `reservation`, `room`, `total` e `restrictionAlerts`.
- `quoteStay` (`GET /api/v1/quotes`): parâmetros `arrival`, `departure`, `adults`, `childAges`, `channel` (`DIRECT`) e os opcionais `roomTypeIds` e `ratePlanIds`. Devolve `QuoteResponse.offers` com `roomTypeId`/`roomTypeName`, `ratePlanId`/`ratePlanName`, `total`, `nights` e `availableUnits`. Vêm só as combinações vendáveis e com UH em todas as noites.

**Fatos do contrato e do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- **Comportamento do walk-in** (handoff da M5 E03 do servidor):
  - tudo numa transação: cria a reserva com origem `FRONT_DESK` e canal `DIRECT`, e a chegada é a data operacional;
  - o preço é o da tarifa, **sem desconto nem diária editada (H24)**, então o critério de desconto da etapa não se aplica;
  - um plano com garantia confirma a pré-reserva no balcão; com depósito, devolve `422 DEPOSIT_INSUFFICIENT`;
  - aloca a UH e faz o check-in da E02;
  - exige `frontdesk.stays.checkin`, `reservations.reservations.manage` e `reservations.assignments.manage`; a Recepcionista tem as três, e também `reservations.availability.read`, que a cotação pede;
  - os hóspedes têm de estar cadastrados antes.
- **Recusas do walk-in, sem nada gravado:**
  - `UNKNOWN_ROOM`, `UNKNOWN_GUEST`, `GUEST_ANONYMIZED` e `GUEST_DATA_INCOMPLETE`;
  - `BOOKING_INVALID`/`STAY_INVALID`, `UNKNOWN_RATE_PLAN`/`UNKNOWN_ROOM_TYPE`;
  - as recusas de venda (`422`, com `refusals`) e `409 ROOM_TYPE_UNAVAILABLE`;
  - `ROOM_TYPE_MISMATCH`, `ROOM_INACTIVE`, `ROOM_OUT_OF_ORDER`, `ROOM_ALREADY_ASSIGNED` e `ROOM_NOT_READY`;
  - as do menor, `DEPOSIT_INSUFFICIENT` e as do early.
- **Escolha da UH:** não há sugestão de UH sem reserva. O `readyForCheckIn` não sabe de alocação, e o seed aloca a 101 e a 102 a chegadas de hoje (`DemoCatalog.kt:331-332`). Por isso a lista sai do `GET /api/v1/rooms/status`: as UHs do tipo da oferta (`roomTypeId`), ativas, vagas e com `readyForCheckIn`, **menos** as que têm alocação no período. Essas vêm do `GET /api/v1/reservations` (`searchReservations`), com:
  - `from` = chegada, `to` = saída − 1 (as estadias que tocam o período, inclusive);
  - `status` = `PROVISIONAL,CONFIRMED`;
  - todas as páginas, colhendo o `roomAssignment.roomId` de cada item;
  - exige `reservations.reservations.read`, que a Recepcionista tem.

  Mesmo assim, `ROOM_ALREADY_ASSIGNED` (corrida com outro terminal) vai para a tela com o próximo passo.
- **Só pernoite:** a cotação aceita de 1 a 365 noites. O servidor recusa chegada igual à saída com `400 STAY_INVALID`, por isso o walk-in de day use fica fora do M2.
- **Códigos:** no `WalkInResponse`, o `bookingCode` é o da reserva agrupadora (`B-…`); o código que o painel mostra (`R-…`) é `reservation.code`.
- **Resposta:** o contrato declara `200`, e o servidor responde `201` com `Location`. O `ApiExecutor` aceita qualquer 2xx.
- **Early e caução fora:** a cotação de early exige uma reserva (`/reservations/{id}/early-late-quote`), e no walk-in ela ainda não existe. Um walk-in antes do horário de check-in entra sem taxa. A caução é do M4.
- **Seed (contagens da E02):** 20 UHs, das quais 7 vagas e prontas antes da verificação ao vivo da E02. A demo precisa ser recriada antes do gate (handoff da E02).

## Global Constraints

- **Regras do design §6.2:**
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado;
  - **uma `Idempotency-Key` por intenção, com a convenção da E02:**
    - a mesma `Intent` só enquanto o resultado é desconhecido (`Network`, `IncompatibleResponse`, `SessionEnded` e `409 IDEMPOTENCY_KEY_IN_USE`);
    - qualquer resposta definitiva gera outra;
    - mudar a tupla `departure`, `adults`, `childAges`, `segment`, `ratePlanId`, `roomTypeId`, `roomId` ou `guestId` também gera outra;
    - a repetição aprovada segue a D2 (ADR 0007);
  - **trava depois de resultado desconhecido:** o walk-in cria um recurso e não tem a guarda natural do check-in. Depois de um resultado desconhecido, o estado fica "não confirmado": todas as entradas (estadia, oferta, UH e hóspede) ficam travadas no próprio estado, e aparece "Não foi possível confirmar o walk-in. Conclua de novo sem mudar nada; se fechar, confira em Hospedados antes de refazer.". A trava só sai com uma resposta definitiva;
  - dinheiro em `BigDecimal` (`Money`, o total como veio);
  - instantes no fuso do hotel; datas de negócio sem conversão.
- **Permissões efetivas:**
  - "Walk-in" aparece com `frontdesk.stays.checkin`, `reservations.reservations.manage`, `reservations.assignments.manage`, `reservations.availability.read`, `reservations.reservations.read`, `rooms.status.read` e `guests.profiles.read`;
  - a busca de hóspede pede `guests.profiles.read`;
  - "Cadastrar hóspede" pede `guests.profiles.manage`;
  - o servidor decide (`403` tratado).
- **Fronteira (ADR 0008):** `app` e `design` (main e test) não importam `com.zeny.desktop.sdk.client.*`, `com.zeny.desktop.sdk.util.*`, `io.ktor.client.*` nem `de.quati.ogen.client.*`, nem usam `@RawSdkAccess`.
- **Dados pessoais:** os da E02. Documento só mascarado; o número digitado só no corpo, fora do estado depois do uso; nenhum dado de hóspede em log, `toString`, mensagem nem disco; testes com dados fictícios.
- **Aprovação:** pelo `ApprovalCoordinator` da sessão. O cancelamento não refaz a ação e não é erro.
- **Estado como dono das travas:** com `busy` (ou "não confirmado"), `quote`, `chooseOffer`, `chooseRoom`, os setters e a escolha de hóspede viram no-op no próprio estado, não só na UI (lição da revisão final da E02).
- **Atualização:** depois do walk-in, `panel.refresh()`; o mapa se atualiza pelo SSE `rooms`.
- **Testes:**
  - não dormem;
  - verificação ao vivo só com o opt-in próprio e o arquivo 600; com o opt-in e sem credencial, falha;
  - testes existentes só mudam onde a tarefa manda.
- **Gate e commits:**
  - `./gradlew build` (inteiro) e `bash tests/run.sh` verdes antes de cada commit, com `--max-workers=2`; nunca `./gradlew --stop`;
  - UI e documentação em pt-BR, código em inglês;
  - commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **Sem oferta para a estadia** (datas, ocupação ou tipo esgotado): aparece "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes." **antes** de qualquer passo de hóspede, e nada é criado. Testes `noOffersShowsMessageBeforeGuest` (Task 4) e a verificação ao vivo `noOfferShowsMessage` (Task 7).
2. **A UH escolhida é tomada entre a cotação e a conclusão:** `ROOM_ALREADY_ASSIGNED` ou `ROOM_NOT_READY` aparece com o próximo passo, a lista de UHs é relida, a chave é outra na próxima tentativa, e nada fica gravado. Testes `roomTakenRereadsRoomsAndRenewsKey` (Task 5).
3. **Erro de rede ao concluir:** a nova tentativa leva a mesma chave, e o servidor devolve o mesmo walk-in sem criar outro. As entradas ficam travadas até uma resposta definitiva, e trocar a UH ou o hóspede não cria um segundo walk-in. Testes `retryAfterNetworkErrorReusesKey`, `unknownResultLocksInputs` e `unknownResultMessageSuggestsCheckingInHouse` (Task 5) e `walkInRetrySendsSameKey` (Task 2).
4. **Mudar a saída ou os hóspedes depois de escolher a oferta:** a oferta e a UH são descartadas e é preciso cotar de novo, para nunca concluir com o preço ou o plano de outra estadia. A chave também é outra. Testes `changingStayDropsOfferAndRoom` (Task 4) e `changingStayRenewsKey` (Task 5).
5. **Plano com depósito antecipado** (`DEPOSIT_INSUFFICIENT`): a mensagem diz para escolher outro plano, e a oferta continua escolhível. Teste `depositPlanShowsNextStep` (Task 5).

---

### Task 1: Cotação da estadia e UHs alocadas no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (contrato público do `core`; resposta externa)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/QuoteStayFacadeTest.kt`

**Interfaces:**
- Produces: `suspend fun quoteStay(arrival: BusinessDate, departure: BusinessDate, adults: Int, childAges: List<Int>): QuoteResponse`:
  - `channel = DIRECT` sempre;
  - `childAges` vazio não vai;
  - sem `roomTypeIds`, `ratePlanIds` nem `includeUnavailable`;
  - devolve a resposta como veio. A operação está na `ReservationsApi`, já ligada ao construtor;
- Produces também: `suspend fun assignedRoomIds(from: BusinessDate, to: BusinessDate): Set<String>`:
  - `searchReservations` com `from`, `to` e `status=PROVISIONAL,CONFIRMED`, limite 200, todas as páginas (`CursorPager`, como `history`);
  - devolve os `roomAssignment.roomId` presentes (ausente ou nulo não conta);
  - `to` antes de `from` → `IllegalArgumentException` antes da rede.

- [ ] **Step 1: Testes que falham:**
  - `quoteSendsStayAndDirectChannel`: `arrival=2026-11-13`, `departure=2026-11-15`, `adults=2`, `childAges=5,8`, `channel=DIRECT`;
  - `noChildrenOmitsChildAges`;
  - `offersReturnedInOrder`;
  - `emptyOffersIsNotAnError`;
  - `stayInvalidIsProblem`: `400 STAY_INVALID` com `errors[]`;
  - `assignedRoomIdsReadsAllPagesWithFilters`: duas páginas, `from`, `to` e `status` na query, ids sem repetição;
  - `assignedRoomIdsSkipsMissingAssignment`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a cotação da estadia e as UHs alocadas ao core`).

---

### Task 2: Walk-in no `core`

**Estimativa:** 30 min · **Depende de:** Task 1 (mesmo arquivo) · **Risco:** alto (contrato público; idempotência e aprovação)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/WalkInFacadeTest.kt`

**Interfaces:**
- Produces:
  - `data class WalkInInput(val departure: BusinessDate, val adults: Int, val childAges: List<Int>, val ratePlanId: String, val roomTypeId: String?, val roomId: String, val segment: String, val guests: List<CheckInGuest>, val guestCount: Int?)` (`CheckInGuest` é o da E02);
  - `fun walkIn(input: WalkInInput, intent: Intent): ApprovableAction<WalkInResponse>`, no molde do `checkIn` da E02:
    - os ids inválidos falham antes da rede, sem o id na mensagem;
    - `roomTypeId`, `guestCount`, `holderGuestId`, `earlyCheckInFee`, `preAuthorization` e `specialRequests` nulos não vão;
    - a resposta `201` é sucesso.

- [ ] **Step 1: Testes que falham:**
  - `walkInSendsKeyAndBody`;
  - `walkInRetrySendsSameKey`: a mesma `Intent` sem token, depois de uma falha de rede, leva a mesma chave;
  - `approvedRetryCarriesTokenAndNewKey`;
  - `createdIsSuccess`: `201` com `Location`;
  - `roomTypeUnavailableIsProblem`: `409 ROOM_TYPE_UNAVAILABLE`;
  - `saleRefusalIsProblem`: `422 CLOSED_TO_ARRIVAL` com `refusals`;
  - `invalidIdsFailBeforeNetwork`;
  - `approvalRequiredSurfaces`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o walk-in ao core`).

---

### Task 3: Porta e textos do walk-in

**Estimativa:** 35 min · **Depende de:** Task 2 · **Risco:** alto (contrato da porta; textos da demo)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionSource.kt`, `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (o `CoreConnection`, sobre `core.reservations.quoteStay/assignedRoomIds/walkIn`) e `app/src/main/kotlin/com/zeny/desktop/app/reception/ActionText.kt`
- Test: `FakeReceptionSource.kt` (só acréscimos), `WalkInSourceTest.kt` e `ActionTextWalkInTest.kt`

**Interfaces:**
- Produces:
  - em `ReceptionSource`:
    - `suspend fun quoteStay(arrival: BusinessDate, departure: BusinessDate, adults: Int, childAges: List<Int>): QuoteResponse`;
    - `fun walkIn(input: WalkInInput, intent: Intent): ApprovableAction<WalkInResponse>`;
    - `suspend fun assignedRoomIds(from: BusinessDate, to: BusinessDate): Set<String>`;
  - o falso registra cada execução de `walkIn` com a `Intent.key` em uso e o token, e aceita respostas, erros e chamadas presas por operação;
  - em `ActionText.error`, os textos exatos do walk-in, sem mudar os da E02:
    - `ROOM_TYPE_UNAVAILABLE` → "Não há UH desse tipo livre em todas as noites. Mude a saída ou escolha outra oferta."
    - `DEPOSIT_INSUFFICIENT` → "Este plano exige depósito antecipado e não serve ao walk-in. Escolha outra oferta."
    - `STAY_INVALID` → "A estadia está inválida. Confira a saída e o número de hóspedes."
    - `UNKNOWN_RATE_PLAN` e `UNKNOWN_ROOM_TYPE` → "A oferta escolhida não existe mais. Refaça a cotação."
    - os de hóspede seguem os textos da E02;
  - `fun walkInError(error: ApiError): String`: no walk-in não há sugestões, por isso os `ROOM_*` ganham textos neutros e o resto cai em `error(error)`:
    - `ROOM_ALREADY_ASSIGNED` → "A UH já está alocada a outra reserva no período. Escolha outra UH da lista."
    - `ROOM_NOT_READY` → "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança."
    - `ROOM_OUT_OF_ORDER`, `ROOM_TYPE_MISMATCH` e `ROOM_INACTIVE` → "A UH não serve para este walk-in. Escolha outra UH da lista."
    - outros `code` de venda (`CLOSED_TO_ARRIVAL`, `RATE_NOT_PRICED`…) caem em `userMessage`;
  - o texto de `noOffers()` = "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes.";
  - `fun offer(offer: QuoteOfferResponse): String` = "<tipo> · <plano> · " + `ReceptionText.money(total)` (o `money` já traz "R$");
  - `fun noOffers(): String` fica no `object ActionText`.

- [ ] **Step 1: Testes que falham:**
  - `coreConnectionDelegatesQuoteAndWalkIn`: servidor de teste em loopback, como na E02; o `walkIn` leva a `Idempotency-Key`;
  - `walkInTexts`: um teste por texto novo, inclusive os de `walkInError`;
  - `e02TextsUnchanged`;
  - `offerText`;
  - `noOffersText`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o walk-in à porta da recepção`).

---

### Task 4: Estado do walk-in: estadia, oferta e UH

**Estimativa:** 40 min · **Depende de:** Task 3 · **Risco:** alto (concorrência; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/WalkInState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/WalkInStateTest.kt`

**Interfaces:**
- Produces:
  - `data class WalkInRoom(val roomId: String, val number: String)`;
  - `class WalkInState`, com construtor `(source: ReceptionSource, scope: CoroutineScope, permissions: Set<String>, approve: suspend (ApprovableAction<WalkInResponse>) -> ApiResponse<WalkInResponse>?, onChanged: () -> Unit)`. Nesta tarefa, `approve` e `onChanged` ficam só como parâmetros, sem virar propriedade, para o detekt não acusar propriedade sem uso; a Task 5 os guarda. Mutações no escopo da UI e convenção de cancelamento da E02. A parte da estadia:
    - `var arrival: BusinessDate?`: a data operacional, lida de `businessDay()` em `start()`. Se a leitura falhar, `error` com o texto da `ActionText` e "Cotar" indisponível (`canQuote` falso);
    - `var departureText: String` (dd/mm/aaaa, por `DateInput.parse`), `var adults: Int` (padrão 1), `var childAges: List<Int>` e `var segment: String` (padrão `LEISURE`; opções `LEISURE` "Lazer", `BUSINESS` "Negócios", `EVENT` "Evento", `OTHER` "Outro");
    - `fun quote()`:
      - valida a saída: legível e de 1 a 365 noites depois da chegada. Saída igual à chegada ou antes dela → "O walk-in é de pernoite: a saída precisa ser depois de hoje.";
      - valida `adults` de 1 a 40 e cada idade de 0 a 17;
      - chama `quoteStay` e põe `offers`; uma cotação por vez, e a mais nova vence (geração);
      - `STAY_INVALID` com `errors[]` vira um texto só (ruling: o diálogo tem poucos campos);
    - `var offers: List<QuoteOfferResponse>`, `var noOffers: Boolean` (cotou e veio vazio), `var quoting`, `var error: String?` e `val canQuote`;
    - `fun chooseOffer(offer: QuoteOfferResponse)`:
      - guarda a oferta e lê em paralelo `roomStatuses()` e `assignedRoomIds(arrival, departure − 1)`;
      - as `rooms` são as do `roomTypeId` da oferta, ativas, vagas, com `readyForCheckIn` e **fora** dos alocados, por número;
      - a escolha mais nova vence (mesma geração);
      - `roomStatuses()` com `403` → "Sem permissão para ver o status das UHs.";
    - `var offer`, `var rooms: List<WalkInRoom>`, `var room: WalkInRoom?` e `fun chooseRoom(room: WalkInRoom)`;
    - mudar a saída, os adultos ou as crianças depois de cotar descarta `offers`, `offer`, `rooms` e `room`. Mudar o segmento não descarta nada, porque o segmento não entra na cotação;
    - `val stepGuestEnabled` = há oferta e UH. O passo do hóspede (Task 5) só aparece com ele;
    - `fun start()` e `fun close()`.

- [ ] **Step 1: Testes que falham:**
  - `startReadsBusinessDay` e `businessDayFailureDisablesQuote`;
  - `quoteSendsStay`;
  - `invalidStayDoesNotQuote`: saída ilegível, igual à chegada, antes da chegada, adultos 0;
  - `sameDayDepartureNotQuoted`, com o texto do pernoite;
  - `noOffersShowsMessageBeforeGuest`: `noOffers` com o texto de `ActionText.noOffers()` e `stepGuestEnabled` falso;
  - `chooseOfferListsReadyVacantRoomsOfType`;
  - `assignedRoomsExcluded`: a 101, vaga e pronta mas alocada no período, não aparece;
  - `noReadyRoomShowsMessage`: "Nenhuma UH desse tipo está pronta e livre agora. Escolha outra oferta ou peça a liberação à governança.";
  - `changingStayDropsOfferAndRoom` e `changingSegmentKeepsOffers`;
  - `newestQuoteWins` e `newestOfferWins`;
  - `quoteErrorShowsText`;
  - `closeCancels`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a estadia, a oferta e a UH do walk-in`).

---

### Task 5: Estado do walk-in: hóspede e conclusão

**Estimativa:** 40 min · **Depende de:** Task 4 (mesmo arquivo) · **Risco:** alto (idempotência, aprovação)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/WalkInState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/WalkInConfirmTest.kt`

**Interfaces:**
- Produces, em `WalkInState`:
  - `val picker: GuestPickerState`, com `canSearch`/`canCreate` pelas permissões e `onPicked` que define `primary`;
  - `var primary: GuestPick?`, `var busy`, `var unconfirmed: Boolean`, `var done: WalkInDone?` e `val canConfirm` (oferta, UH e principal escolhidos, sem `busy`, sem cotação ou `chooseOffer` em voo, sem `picker.creating` e sem `done`);
  - `data class WalkInDone(val reservationCode: String, val roomNumber: String, val total: String, val alerts: List<String>)`:
    - `reservationCode` = `reservation.code` (`R-…`, o que o painel mostra), não o `bookingCode`;
    - total por `ReceptionText.money`; alertas como na E02;
    - `toString` sem `alerts`;
  - as travas das Global Constraints, no estado: com `busy` ou `unconfirmed`, as entradas da Task 4 e `picker.pick` viram no-op;
  - `fun confirm()`:
    - monta `WalkInInput(departure, adults, childAges, offer.ratePlanId, offer.roomTypeId, room.roomId, segment, guests = [CheckInGuest(primary.id, true)], guestCount = (adults + childAges.size).coerceIn(1, 40))` e roda `approve(source.walkIn(input, intent))`;
    - a renovação da `Intent` segue as Global Constraints, comparando a tupla `departure`, `adults`, `childAges`, `segment`, `ratePlanId`, `roomTypeId`, `roomId` e `guestId` (no molde do `IntentInputs` do `CheckInState`);
    - resultado desconhecido → `unconfirmed = true` e o texto de trava das Global Constraints; a mesma `Intent`;
    - sucesso → `done`, `unconfirmed = false` e `onChanged()`;
    - qualquer resposta definitiva → `unconfirmed = false`;
    - `ROOM_ALREADY_ASSIGNED`, `ROOM_NOT_READY`, `ROOM_OUT_OF_ORDER`, `ROOM_TYPE_MISMATCH` ou `ROOM_INACTIVE` → o texto de `ActionText.walkInError`, `room = null` e as `rooms` relidas;
    - `ROOM_TYPE_UNAVAILABLE`, `UNKNOWN_RATE_PLAN` ou `UNKNOWN_ROOM_TYPE` → o texto, `offer`/`rooms`/`room` descartados e a cotação refeita (as `offers` velhas não ficam);
    - `DEPOSIT_INSUFFICIENT` → o texto, e a oferta continua;
    - o resto → `ActionText.walkInError`;
    - aprovação cancelada → nada muda, e a mesma `Intent`.
  - `close()` também fecha o `picker`.

- [ ] **Step 1: Testes que falham:**
  - `confirmSendsWalkIn`;
  - `retryAfterNetworkErrorReusesKey`;
  - `unknownResultLocksInputs` e `unknownResultMessageSuggestsCheckingInHouse`;
  - `keyInUseKeepsKey`;
  - `ruleErrorRenewsKey`;
  - `changingRoomOrGuestRenewsKey` e `changingStayRenewsKey`;
  - `inputsIgnoredWhileBusy`;
  - `sessionEndedShowsMessage` e `approvedRetrySucceeds`;
  - `roomTakenRereadsRoomsAndRenewsKey`;
  - `typeUnavailableDropsOfferAndRequotes`;
  - `depositPlanShowsNextStep`;
  - `approvalCancelledLeavesState`;
  - `doubleConfirmSendsOnce`;
  - `successNotifiesAndShowsDone`;
  - `doneToStringHidesAlerts`;
  - `closeClosesPicker`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a conclusão do walk-in`).

---

### Task 6: Diálogo do walk-in na tela

**Estimativa:** 40 min · **Depende de:** Task 5 · **Risco:** alto (superfície da demo; aprovação)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/WalkInDialog.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` e `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (`ReceptionArea`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/WalkInDialogTest.kt`

**Interfaces:**
- Produces:
  - **`@Composable fun WalkInDialog(state: WalkInState, onClose: () -> Unit)`**, em passos na mesma janela:
    - **"Estadia":** "Chegada: <data>", "Saída" (dd/mm/aaaa), "Adultos", "Crianças" (idades separadas por vírgula), "Segmento" e "Cotar";
    - **"Ofertas":** uma linha por oferta com o texto de `ActionText.offer` e "Escolher"; sem oferta, o texto de `noOffers` e nenhum passo seguinte;
    - **"UH":** as UHs prontas do tipo, cada uma com "Escolher";
    - **"Hóspede principal":** só com oferta e UH; nome e documento mascarado, ou "Escolha o hóspede"; o `GuestPickerPanel` da E02;
    - o `error` em destaque;
    - "Concluir walk-in" (por `canConfirm`) e "Fechar" (desabilitado com `busy`, cotação ou escolha de oferta em voo e `picker.creating`);
    - com `done`: "Walk-in concluído: reserva <reservationCode>, UH <n>, total <valor>.", os alertas e "Fechar";
    - com `busy`, os controles da estadia, das ofertas, da UH e do hóspede ficam desabilitados.
  - **`ReceptionScreen(panel, detail, checkIn = null, walkIn: (() -> WalkInState)? = null)`:**
    - com o valor padrão, as chamadas existentes não mudam;
    - o botão "Walk-in" fica no cabeçalho, ao lado de "Atualizar", e aparece só com `walkIn != null` e todas as permissões do botão nas Global Constraints, em `panel.permissions`;
    - o diálogo chama `start()` uma vez; fechar chama `close()` e `panel.refresh()`.
  - **`ReceptionArea`:** `walkIn = { WalkInState(source, scope, panel.permissions, approve, onChanged = { panel.refresh() }) }`:
    - o `approve` do check-in vira uma função genérica `suspend <T : Any> (ApprovableAction<T>) -> ApiResponse<T>?` sobre o `LocalApprovalCoordinatorOrNull`, com o fallback só quando não há coordenador;
    - a chamada atual com lambda final passa a `checkIn = …` nomeado.

- [ ] **Step 1: Testes que falham:**
  - `walkInButtonFollowsPermissions`;
  - `existingCallsHaveNoWalkInButton`;
  - `quoteShowsOffers`;
  - `noOffersShowsMessageAndNoGuestStep`;
  - `chooseOfferAndRoomShowsGuestStep`;
  - `confirmShowsDoneAndRefreshesPanel`;
  - `controlsDisabledWhileBusy`;
  - `ruleErrorShowsNextStep`;
  - `noUuidOrRawCodeInDialog`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2`, `./gradlew :app:desktopJar --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Põe o walk-in na recepção`).

---

### Task 7: Verificação ao vivo do walk-in

**Estimativa:** 35 min · **Depende de:** Task 6 · **Risco:** alto (segredos; escreve no servidor; critérios de aceite)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveWalkInCheck.kt`
- Modify: `app/build.gradle.kts` (só o opt-in `ZENY_LIVE_WALKIN`, como entrada do `test` e no `upToDateWhen`, no molde dos outros); correções pequenas com teste, no arquivo dono, se a verificação achar defeito.

**Interfaces:**
- **Variáveis:** as da E01 e da E02 (arquivo 600 do orquestrador). Opt-in `ZENY_LIVE_WALKIN=1`: sem ele, pula; com ele e sem credencial, falha. A verificação **escreve** no `zeny-front` e cria um walk-in e um hóspede fictício.
- **`LiveWalkInCheck.twoNightWalkInGoesInHouse`:**
  - **fluxo:** `CoreConnection` real, logado como Recepcionista. `DayPanelState`, `RoomMapState` (assinando `rooms`) e `WalkInState` reais. Cota 2 noites para 1 adulto e escolhe a primeira oferta que tenha UH na lista, já sem as alocadas. Sem nenhuma, falha com as contagens (ofertas e UHs por oferta), sem dados pessoais. Escolhe a primeira UH, cadastra um hóspede fictício pelo `picker` (CPF de teste gerado, BR, 1990) e conclui.
  - **afirma:**
    - `done`;
    - a reserva nova (`done.reservationCode`, o `R-…`) aparece em "Hospedados" depois de `panel.refresh()`, com "Hospedada";
    - a UH aparece ocupada no `RoomMapState` pelo evento `rooms`, sem recarregar.
  - **captura:** `app/build/live/walkin.png`, com o diálogo concluído ou o painel.
- **`LiveWalkInCheck.noOfferShowsMessage`:** cota 2 noites para 40 adultos, além da ocupação de qualquer tipo, e afirma `noOffers`, o texto de `ActionText.noOffers()` e `stepGuestEnabled` falso. Não escreve nada.

- [ ] **Step 1:** escreva as verificações.
- [ ] **Step 2:** rode `(set -a; . <arquivo do despacho>; set +a; ZENY_LIVE_WALKIN=1 ./gradlew :app:test --tests '*LiveWalkInCheck*' --rerun --max-workers=2)` **uma vez** e cole o resultado. Uma segunda execução, só por defeito de código.
- [ ] **Step 3:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes sem as variáveis; commit (`Verifica o walk-in contra o servidor`).

## Ondas

- Dependências: 2←1; 3←2; 4←3; 5←4; 6←5; 7←6. Execução em série na branch da etapa (as ondas paralelas não funcionam nesta configuração).

## Para o handoff

- **Critério do desconto:** não se aplica. O walk-in do servidor não aceita desconto nem diária editada (H24 da M5 E03 do servidor). O diálogo de aprovação continua ligado, para o caso de o servidor pedir aprovação.
- **Calendário de disponibilidade:** `GET /api/v1/availability` não entrou, porque a cotação só devolve ofertas com UH em todas as noites. A disponibilidade por data fica para a tela geral do M3.
- **UH do walk-in:** sai do status das UHs do tipo (vagas, ativas e prontas); não há sugestão sem reserva. Uma UH com reserva futura no período devolve `ROOM_ALREADY_ASSIGNED`.
- **Fora do walk-in:** early, caução e pedidos especiais (M4) e acompanhantes nominais (M5). O check-in registra só o principal, como na E02.
- **Ambiente:** a verificação ao vivo consome UHs prontas e cria um hóspede; recriar a demo antes do gate.
- **Day use:** fica fora do walk-in no M2, porque a cotação só aceita pernoite. Vai para "Decisões para o usuário"; a alternativa (day use sem oferta, preço pelo calendário) cresce o escopo.
- **UH alocada a uma chegada pendente:** fica fora da lista do walk-in. Ceder essa UH e realocar a chegada é fluxo do M3 ou da troca de UH. Vai para "Decisões para o usuário".
- **Resultado desconhecido:** o walk-in trava as entradas até uma resposta definitiva. Se o recepcionista fechar o diálogo, a orientação é conferir "Hospedados" antes de refazer.
- **Contrato:** declara `200`, e o servidor responde `201`.
- **Sem disponibilidade, ao vivo:** a verificação usa 40 adultos (sem oferta por ocupação) no lugar de um tipo esgotado. O caminho no cliente é o mesmo.
- **`STAY_INVALID`:** o `errors[]` vira um texto geral, não vai para o campo (o diálogo tem poucos campos).
- **Fechamento do dia com o diálogo aberto:** a chegada da cotação é a data lida no `start()`. Se o servidor avançar a data, a conclusão falha com uma recusa de regra, e é preciso reabrir.
