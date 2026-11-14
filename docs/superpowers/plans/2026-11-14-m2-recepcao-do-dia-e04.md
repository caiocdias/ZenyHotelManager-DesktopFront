# M2 E04 — Troca de UH e desfazer check-in — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** num hospedado aberto no detalhe, o recepcionista troca a UH, escolhendo uma UH pronta do mesmo tipo, o motivo e a tarifa das noites restantes. Também desfaz um check-in feito por engano, com a aprovação do gerente no balcão (diálogo de aprovação do M1). O mapa e o histórico refletem as duas ações.

**Architecture:** no `core`, a `ReservationsFacade` ganha:
- `reservationVersion` (a reserva com o `ETag`);
- `moveRoom` (`ApprovableAction` com `If-Match` e a `Intent` da tela);
- `undoCheckIn` (`ApprovableAction` sem `Idempotency-Key`, porque o contrato não a declara; o token só na repetição aprovada).

No `app`, a porta ganha as três operações, e a `ActionText` ganha os textos. Dois *state holders* (`RoomMoveState` e `UndoCheckInState`) reaproveitam a convenção da `Idempotency-Key` e a aprovação genérica da E02/E03. O detalhe ganha "Trocar UH" e "Desfazer check-in".

**Tech Stack:** o da E02/E03.

**Spec:**
- a seção "E04 — Troca de UH e desfazer check-in" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md`, com "Contexto confirmado", "Restrições e invariantes" e os handoffs da E02 e da E03;
- o design §6.2 (concorrência: a tela guarda o `ETag` lido; no `412`, recarrega e avisa);
- os ADRs `0007` (D2) e `0008`.

No contrato `api/openapi.yaml`:
- `moveReservationRoom` (`POST …/{id}/room-moves`):
  - **`If-Match`** com o `ETag` da reserva (`428` sem ele; `412` com outra versão) e `Idempotency-Key`;
  - corpo `RoomMoveRequest`: `roomId`, `reason` (`GUEST_REQUEST`/`OUT_OF_ORDER`/`ROOM_ISSUE`/`OPERATIONAL`; `INITIAL` → `400 ROOM_MOVE_INVALID`) e `tariff` (`KEEP` padrão, ou `RECALCULATE`);
  - devolve `RoomMoveResponse` com `fromRoom`, `toRoom`, `remainingBefore`, `remainingAfter`, `difference` e `reservation`.
- `undoCheckInReservation` (`POST …/{id}/undo-check-in`):
  - **sem `Idempotency-Key`**; só `X-Approval-Token`;
  - ação sensível (`frontdesk.stays.undocheckin`): sem a permissão, `403 APPROVAL_REQUIRED`;
  - só numa hospedada (`409 RESERVATION_TRANSITION_NOT_ALLOWED`) e na mesma data operacional do check-in (`422 UNDO_CHECK_IN_EXPIRED`);
  - devolve `UndoCheckInResponse` (`reservation`, `room`).

**Fatos do contrato e do servidor fixado (`57eb5fac5b8a`, só leitura) que o plano usa:**
- **Troca:**
  - só para o **mesmo tipo** de UH (`422 ROOM_TYPE_MISMATCH`), e para UH pronta (`409 ROOM_NOT_READY`) e livre no período (`409 ROOM_ALREADY_ASSIGNED`);
  - a origem fica "Vaga e Suja", e o destino, ocupado; publica `RoomMoved`;
  - o histórico ganha `ROOM_MOVE` ("Troca de UH" no texto da E01);
  - a permissão é `frontdesk.stays.move`, que a Recepcionista tem, e a troca não é sensível;
  - o `If-Match` é a guarda natural: depois de uma troca feita, o `ETag` velho dá `412`.
- **Desfazer:**
  - a reserva volta a `CONFIRMED`, com a UH ainda alocada; a UH fica vaga, sem sujar;
  - a Recepcionista **não** tem `frontdesk.stays.undocheckin`, e o Gerente tem;
  - o seed define o login `gerente` e o PIN (`ZENY_SEED_MANAGER_PIN` em `.server/seed-secrets.env`);
  - o efeito na FNRH é do servidor (`UNDO_CHECK_IN`), e a tela não mostra nem chama nada disso.
- **Critério 3 da etapa ("a mesma `Idempotency-Key` e o `X-Approval-Token`"):** a D2 (ADR 0007, aceita pelo usuário) diz que a repetição aprovada leva o token e **outra** chave, e substitui o "reaproveitada" do design §6.2. No `undo-check-in` nem há chave. O critério é verificado pela D2:
  - na troca: a repetição aprovada leva o `X-Approval-Token` e outra chave, e o reenvio por rede mantém a chave aprovada e o token;
  - no desfazer: a repetição leva só o `X-Approval-Token`.
- **Extensão e redução da estadia:** não há operação própria no contrato (a alteração de datas é a alteração da reserva, M3). Fica registrada no handoff.

## Global Constraints

- **Regras do design §6.2:**
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado;
  - dinheiro em `BigDecimal` (`Money`);
  - **concorrência:** a tela guarda o `ETag` lido; no `412 PRECONDITION_FAILED`, relê a reserva e o `ETag` e mostra "Alguém alterou esta reserva. Confira e confirme de novo.", com outra `Intent`;
  - **`Idempotency-Key` (troca), pela convenção da E02:**
    - a mesma `Intent` só enquanto o resultado é desconhecido (`Network`, `IncompatibleResponse`, `SessionEnded` e `409 IDEMPOTENCY_KEY_IN_USE`);
    - qualquer resposta definitiva gera outra;
    - mudar a UH, o motivo ou a tarifa também gera outra;
    - a repetição aprovada segue a D2;
  - **desfazer:** nunca envia `Idempotency-Key`.
- **Permissões efetivas:**
  - "Trocar UH" pede `frontdesk.stays.move`, `rooms.status.read` e `reservations.reservations.read`;
  - "Desfazer check-in" pede `frontdesk.stays.checkin` (a aprovação entra quando o servidor pede);
  - os dois botões aparecem só com a reserva `IN_HOUSE`;
  - o servidor decide (`403` tratado).
- **Aprovação:**
  - pelo `ApprovalCoordinator` da sessão, por meio da função genérica da `ReceptionArea` (E03), com o fallback só sem coordenador;
  - o cancelamento não refaz a ação e não é erro;
  - o PIN errado mantém o diálogo com a mensagem do coordenador (M1), e nada muda.
- **Fronteira (ADR 0008)** e **dados pessoais** como na E02.
- **O estado é dono das travas:** com `busy`, as entradas viram no-op no próprio estado.
- **Atualização:** depois de cada ação, `panel.refresh()` e `detail.reload()`; o mapa se atualiza pelo SSE `rooms`.
- **Testes:**
  - não dormem;
  - verificação ao vivo só com o opt-in próprio e o arquivo 600; com o opt-in e sem credencial, falha;
  - testes existentes só mudam onde a tarefa manda.
- **Gate e commits:**
  - `./gradlew build` (inteiro) e `bash tests/run.sh` verdes antes de cada commit, com `--max-workers=2`; nunca `./gradlew --stop`;
  - UI em pt-BR, código em inglês;
  - commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **Outro terminal mexe na reserva entre a abertura e a confirmação da troca:** `412` relê o `ETag` e avisa; a chave é outra na próxima tentativa; nada muda até a nova confirmação. Testes `preconditionFailedRereadsVersionAndRenewsKey` (Task 4) e `moveSendsIfMatch` (Task 1).
2. **Erro de rede depois de a troca ter sido gravada:** a nova tentativa leva a mesma chave e o servidor repete a resposta. Com outra chave, o `ETag` velho dá `412`, e a troca nunca acontece duas vezes. Testes `retryAfterNetworkErrorReusesKey` (Task 4) e `moveRetrySendsSameKey` (Task 1).
3. **Desfazer com aprovação:**
   - sem `Idempotency-Key` em nenhuma execução;
   - o token só na repetição aprovada;
   - a aprovação cancelada não refaz;
   - o PIN errado não muda nada.

   Testes `undoNeverSendsIdempotencyKey` e `approvedUndoCarriesToken` (Task 2) e `cancelledApprovalLeavesReservation` (Task 5).
4. **Desfazer fora da data operacional do check-in** (`UNDO_CHECK_IN_EXPIRED`): mensagem com o próximo passo, e o botão continua. Teste `expiredShowsMessage` (Task 5).
5. **Lista de UHs da troca:** só o mesmo tipo, ativas, vagas, prontas e livres no período (de hoje à saída − 1), sem a UH atual. Uma falha de leitura nunca mostra a lista sem o filtro. Testes `roomsOfSameTypeReadyAndFree` e `assignedRoomsFailureShowsNoRooms` (Task 4).

---

### Task 1: Versão da reserva e troca de UH no `core`

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (contrato público; concorrência e idempotência)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/RoomMoveFacadeTest.kt`

**Interfaces:**
- Produces:
  - `data class ReservationVersion(val reservation: ReservationDetailResponse, val etag: String)` e `suspend fun reservationVersion(id: String): ReservationVersion`:
    - `GET` da reserva, com o `ETag` da resposta;
    - sem `ETag`, `ApiError.IncompatibleResponse`, porque o contrato o promete;
  - `data class RoomMoveInput(val roomId: String, val reason: String, val tariff: String)`;
  - `fun moveRoom(id: String, input: RoomMoveInput, etag: String, intent: Intent): ApprovableAction<RoomMoveResponse>`:
    - no molde do `checkIn` da E02, com `If-Match = etag`;
    - ids inválidos, motivo fora de `GUEST_REQUEST`/`OUT_OF_ORDER`/`ROOM_ISSUE`/`OPERATIONAL` e tarifa fora de `KEEP`/`RECALCULATE` → `IllegalArgumentException` antes da rede, sem o valor na mensagem.

- [ ] **Step 1: Testes que falham:**
  - `versionCarriesEtag` e `versionWithoutEtagIsIncompatible`;
  - `moveSendsIfMatch`: `If-Match` com o `ETag`, `Idempotency-Key` e corpo (`roomId`, `reason`, `tariff`);
  - `moveRetrySendsSameKey`;
  - `approvedMoveCarriesTokenAndNewKey`;
  - `preconditionFailedIsTyped`: `412` → `ApiError.PreconditionFailed`;
  - `notReadyIsProblem`;
  - `sameRoomIsProblem` (`422 ROOM_MOVE_SAME_ROOM`);
  - `invalidInputsFailBeforeNetwork`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a troca de UH ao core`).

---

### Task 2: Desfazer check-in no `core`

**Estimativa:** 30 min · **Depende de:** Task 1 (mesmo arquivo) · **Risco:** alto (ação sensível; aprovação)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/facade/ReservationsFacade.kt`, e `core/src/main/kotlin/com/zeny/desktop/core/http/ApiExecutor.kt` se for preciso um caminho aprovado sem chave
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/UndoCheckInFacadeTest.kt`

**Interfaces:**
- Produces: `fun undoCheckIn(id: String): ApprovableAction<UndoCheckInResponse>`:
  - `run(null)` sem token e `run(token)` com `X-Approval-Token`;
  - **nenhuma execução envia `Idempotency-Key`**; se o `executeApproved` atual sempre gera uma chave, acrescente ao `ApiExecutor` um caminho aprovado sem chave (por exemplo, `executeWithApproval(token, call)`), sem mudar os outros;
  - id inválido → `IllegalArgumentException` antes da rede.

- [ ] **Step 1: Testes que falham:**
  - `undoNeverSendsIdempotencyKey` (sem token e com token);
  - `approvedUndoCarriesToken`;
  - `approvalRequiredSurfaces`: `403 APPROVAL_REQUIRED` → `ApiError.ApprovalRequired` com `action`/`permission`/`resourceRef`;
  - `expiredIsProblem` (`422 UNDO_CHECK_IN_EXPIRED`);
  - `notInHouseIsProblem`;
  - `invalidIdFailsBeforeNetwork`;
  - `checkInKeepsKeySemantics`: os testes de `checkIn`/`walkIn` existentes continuam verdes, sem mudança.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o desfazer check-in ao core`).

---

### Task 3: Porta e textos da troca e do desfazer

**Estimativa:** 35 min · **Depende de:** Task 2 · **Risco:** alto (contrato da porta; textos da demo)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionSource.kt`, `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` e `app/src/main/kotlin/com/zeny/desktop/app/reception/ActionText.kt`
- Test: `FakeReceptionSource.kt` (só acréscimos, recusando como a porta real), `StayActionsSourceTest.kt` e `ActionTextStayTest.kt`

**Interfaces:**
- Produces:
  - na porta: `reservationVersion(id)`, `moveRoom(id, input, etag, intent)` e `undoCheckIn(id)`. O falso registra cada execução (a `Intent.key` em uso, o `etag` e o token) e aceita respostas, erros por execução e chamadas presas;
  - em `ActionText.error`, os textos exatos, sem mudar os existentes:
    - `ROOM_MOVE_SAME_ROOM` → "A UH escolhida é a mesma atual. Escolha outra UH."
    - `ROOM_MOVE_INVALID` → "O motivo da troca é inválido. Escolha outro motivo."
    - `UNDO_CHECK_IN_EXPIRED` → "O check-in só pode ser desfeito na mesma data operacional em que foi feito."
    - `ROOM_BLOCK_RESERVATION_STAY_FIXED` → "A tarifa desta reserva de grupo não pode ser recalculada. Mantenha a tarifa."
    - `ApiError.PreconditionFailed` → "Alguém alterou esta reserva. Confira e confirme de novo."
  - `fun moveReason(value: String): String`: os quatro motivos com os rótulos de `ReceptionText.assignmentReason`;
  - `fun tariff(value: String): String`: `KEEP` "Manter a tarifa", `RECALCULATE` "Recalcular pela tarifa atual", outro "Outro (VALOR)";
  - `fun moveSummary(response: RoomMoveResponse): String` = "Troca feita para a UH <toRoom.number>. Noites restantes: <antes> → <depois> (diferença <difference>).", com os valores por `ReceptionText.money`.

- [ ] **Step 1: Testes que falham:**
  - `coreConnectionDelegatesMoveAndUndo`: loopback; `If-Match` e chave na troca; nenhuma chave no desfazer;
  - um teste por texto novo;
  - `existingTextsUnchanged`;
  - `moveSummaryText`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta a troca de UH e o desfazer check-in à porta`).

---

### Task 4: Estado da troca de UH

**Estimativa:** 45 min · **Depende de:** Task 3 · **Risco:** alto (concorrência, idempotência; núcleo da etapa)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RoomMoveState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RoomMoveStateTest.kt`

**Interfaces:**
- Produces:
  - `data class MoveSeed(val reservationId: String, val roomTypeId: String, val currentRoomId: String?, val departure: BusinessDate)` e `fun ReservationDetailResponse.toMoveSeed(): MoveSeed`;
  - `class RoomMoveState(source: ReceptionSource, scope: CoroutineScope, seed: MoveSeed, approve: suspend (ApprovableAction<RoomMoveResponse>) -> ApiResponse<RoomMoveResponse>?, onChanged: () -> Unit)`, com mutações no escopo da UI e a convenção de cancelamento:
    - `fun start()`: em paralelo, `reservationVersion` (guarda o `etag`), `businessDay()`, `roomStatuses()` e `assignedRoomIds(hoje, departure − 1)`;
    - `rooms`: as do `roomTypeId`, ativas, vagas, `readyForCheckIn`, fora das alocadas e diferentes da atual, em `NaturalOrder`; falha de leitura → `rooms` vazia e `error`, nunca a lista sem o filtro;
    - `var room`, `var reason` (padrão `GUEST_REQUEST`; só os quatro motivos), `var tariff` (padrão `KEEP`; só `KEEP`/`RECALCULATE`), `var busy`, `var error`, `var done: String?` (o texto de `ActionText.moveSummary`) e `val canConfirm`;
    - `fun confirm()`:
      - `approve(source.moveRoom(id, RoomMoveInput(room, reason, tariff), etag, intent))`;
      - a `Intent` segue as Global Constraints;
      - sucesso → `done` e `onChanged()`;
      - `PreconditionFailed` → relê a versão, outra `Intent` e o texto do `412`;
      - `ROOM_NOT_READY`/`ROOM_ALREADY_ASSIGNED`/`ROOM_OUT_OF_ORDER` → o texto da `ActionText`, `room = null` e as UHs relidas;
      - o resto → `ActionText.error`;
      - aprovação cancelada → nada muda;
    - com `busy` ou `done`, as entradas viram no-op; `fun close()`.

- [ ] **Step 1: Testes que falham:**
  - `startReadsVersionAndRooms`;
  - `roomsOfSameTypeReadyAndFree`: sem a atual, sem as alocadas, sem as de outro tipo;
  - `assignedRoomsFailureShowsNoRooms`;
  - `confirmSendsMoveWithEtag`;
  - `retryAfterNetworkErrorReusesKey`;
  - `ruleErrorRenewsKey`;
  - `changingInputsRenewsKey`;
  - `preconditionFailedRereadsVersionAndRenewsKey`;
  - `roomTakenRereadsRooms`;
  - `approvalCancelledLeavesState`;
  - `doubleConfirmSendsOnce`;
  - `inputsIgnoredWhileBusyOrDone`;
  - `successNotifiesAndShowsSummary`;
  - `closeCancels`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado da troca de UH`).

---

### Task 5: Estado do desfazer check-in

**Estimativa:** 25 min · **Depende de:** Task 3 · **Risco:** alto (ação sensível; aprovação)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/UndoCheckInState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/UndoCheckInStateTest.kt`

**Interfaces:**
- Produces: `class UndoCheckInState(source: ReceptionSource, scope: CoroutineScope, reservationId: String, approve: suspend (ApprovableAction<UndoCheckInResponse>) -> ApiResponse<UndoCheckInResponse>?, onChanged: () -> Unit)`:
  - `var busy`, `var error`, `var done: Boolean`;
  - `fun confirm()`:
    - `approve(source.undoCheckIn(id))`;
    - sucesso → `done` e `onChanged()`;
    - `null` → nada muda;
    - erro → `ActionText.error`, inclusive `UNDO_CHECK_IN_EXPIRED` e `RESERVATION_TRANSITION_NOT_ALLOWED` (este último também chama `onChanged()`, como no check-in);
    - uma chamada por vez;
  - `fun close()`.

- [ ] **Step 1: Testes que falham:**
  - `confirmUndoesAndNotifies`;
  - `cancelledApprovalLeavesReservation`;
  - `expiredShowsMessage`;
  - `transitionNotAllowedRefreshes`;
  - `doubleConfirmSendsOnce`;
  - `cancellationNeverAnError`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Acrescenta o estado do desfazer check-in`).

---

### Task 6: Diálogos e botões no detalhe

**Estimativa:** 45 min · **Depende de:** Tasks 4 e 5 · **Risco:** alto (superfície da demo; aprovação)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/StayActionDialogs.kt` (`RoomMoveDialog` e `UndoCheckInDialog`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` e `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (`ReceptionArea`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/StayActionDialogsTest.kt`

**Interfaces:**
- Produces:
  - **`RoomMoveDialog(state, onClose)`:**
    - "UH atual: <n>", a lista de UHs (número e "Escolher"), "Motivo" (os quatro rótulos) e "Tarifa das noites restantes" (os dois rótulos);
    - o `error` em destaque;
    - "Trocar UH" (por `canConfirm`) e "Fechar" (desabilitado com `busy`);
    - com `done`: o texto do resumo e "Fechar";
    - entradas desabilitadas com `busy`.
  - **`UndoCheckInDialog(state, onClose)`:**
    - "Desfazer o check-in da reserva <código>? A reserva volta a confirmada e a UH fica vaga.";
    - "Desfazer" e "Fechar" (desabilitado com `busy`);
    - o `error` em destaque;
    - com `done`: "Check-in desfeito." e "Fechar".
  - **`ReceptionScreen`** ganha `roomMove: ((MoveSeed) -> RoomMoveState)? = null` e `undoCheckIn: ((String) -> UndoCheckInState)? = null`, com padrão nulo, para as chamadas existentes não mudarem. Os botões "Trocar UH" e "Desfazer check-in" ficam no detalhe, só com `detail.reservation` em `IN_HOUSE` e as permissões das Global Constraints. Fechar um diálogo chama `close()`.
  - **`ReceptionArea`** liga as duas fábricas com a mesma função genérica de aprovação da E03 e `onChanged = { panel.refresh(); detail.reload() }`.

- [ ] **Step 1: Testes que falham:**
  - `buttonsOnlyForInHouseWithPermissions`;
  - `existingCallsHaveNoStayButtons`;
  - `moveDialogListsRoomsAndConfirms`;
  - `moveDialogShowsPreconditionText`;
  - `undoDialogConfirms`;
  - `undoApprovalCancelledShowsNothing`;
  - `controlsDisabledWhileBusy`;
  - `noUuidOrRawCode`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; `./gradlew build --max-workers=2`, `./gradlew :app:desktopJar --max-workers=2` e `bash tests/run.sh` verdes.
- [ ] **Step 5: Commit** (`Põe a troca de UH e o desfazer check-in no detalhe`).

---

### Task 7: Verificação ao vivo da troca e do desfazer

**Estimativa:** 40 min · **Depende de:** Task 6 · **Risco:** alto (segredos; escreve no servidor; aprovação real)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveStayActionsCheck.kt`
- Modify: `app/build.gradle.kts` (só o opt-in `ZENY_LIVE_STAY`, como entrada do `test` e no `upToDateWhen`); correções pequenas com teste, se a verificação achar defeito.

**Interfaces:**
- **Variáveis:** as da E01/E02, mais `ZENY_LIVE_APPROVER_LOGIN` (`gerente`) e `ZENY_LIVE_APPROVER_PIN`, no arquivo 600 do orquestrador. Opt-in `ZENY_LIVE_STAY=1`; sem ele, pula; com ele e sem credencial, falha. A verificação **escreve** no `zeny-front`.
- **`LiveStayActionsCheck.roomMoveChangesBothRoomsAndHistory`:**
  - **fluxo:** como Recepcionista, com o `RoomMoveState` real, escolhe uma hospedada que tenha UH pronta do mesmo tipo. Sem nenhuma, falha com as contagens. Troca com motivo `GUEST_REQUEST` e `KEEP`.
  - **afirma:**
    - no `RoomMapState`, pelo evento `rooms`, a origem fica vaga e suja e o destino, ocupado;
    - o histórico do detalhe tem "Troca de UH" depois de `detail.reload()`;
  - **captura:** `app/build/live/room-move.png`.
- **`LiveStayActionsCheck.undoCheckInNeedsManagerApproval`:**
  - **fluxo:** como Recepcionista, numa reserva hospedada com check-in na data operacional, com o `UndoCheckInState` real e um `approve` de teste sobre o `ApprovalCoordinator` real.
  - **PIN errado:** a mensagem pt-BR do coordenador aparece e a reserva continua `IN_HOUSE`.
  - **PIN certo:** `done`; a reserva volta a `CONFIRMED`; a UH fica vaga no mapa.
  - **afirma:** a repetição aprovada levou o `X-Approval-Token` e nenhuma `Idempotency-Key`, pelos registros do coordenador ou pelo resultado.
  - **captura:** `app/build/live/undo-check-in.png`.

- [ ] **Step 1:** escreva as verificações.
- [ ] **Step 2:** rode `(set -a; . <arquivo do despacho>; set +a; ZENY_LIVE_STAY=1 ./gradlew :app:test --tests '*LiveStayActionsCheck*' --rerun --max-workers=2)` **uma vez** e cole o resultado. Uma segunda execução, só por defeito de código.
- [ ] **Step 3:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes sem as variáveis; commit (`Verifica a troca de UH e o desfazer check-in contra o servidor`).

## Ondas

- Dependências: 2←1; 3←2; 4←3; 5←3; 6←4,5; 7←6. Execução em série, na ordem 1 a 7.

## Para o handoff

- O critério 3 é verificado pela D2 (ADR 0007): a repetição aprovada leva o `X-Approval-Token` e outra chave. No desfazer não há chave, porque o contrato não a declara.
- **Extensão e redução da estadia:** sem operação própria no contrato (é a alteração da reserva). Fica como candidata a etapa do M3.
- **Troca só no mesmo tipo:** a troca para outro tipo ainda não é aceita pelo servidor (`ROOM_TYPE_MISMATCH`).
- **Tarefa de limpeza da UH de origem:** é da governança (M6).
- **Desfazer:** a caução e o early ficam na conta; liberar e estornar é do `billing` (M4). A fila da FNRH é da M7 E10.
- **Ambiente:** a verificação ao vivo troca a UH de um hospedado e desfaz um check-in; recriar a demo antes do roteiro.
