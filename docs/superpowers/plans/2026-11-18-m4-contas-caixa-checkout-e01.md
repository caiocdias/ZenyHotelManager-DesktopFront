# M4 E01 — Regra única do resultado desconhecido — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a classificação da falha de uma escrita (definitiva, desconhecida, desconhecida e guardada) passa a ser uma função pública do `core`, usada pelos 8 estados que hoje copiam a regra, com os achados da M3 E06 que dependem dela (D1).

**Architecture:** uma função pura no pacote `error` do `core` recebe um `ApiError` e devolve um `WriteFailure` de três valores. Os estados do `app` trocam o `resultUnknown` privado e a dedução local do "guardado" por ela e mantêm os próprios textos. O ajudante `approvable` passa a ser um só, sobre o `ApiExecutor`: saem a cópia privada do `BookingsFacade` e as montagens em linha do `BookingsFacade` e do `ReservationsFacade`. Nos quatro estados que têm o texto "feche e confira" (o caso guardado), o diálogo deixa de oferecer "Tentar de novo" e passa a permitir fechar.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Gradle.

**Spec:**
- a seção "E01 — Regra única do resultado desconhecido" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` (com D1 em "Hipóteses e decisões em aberto");
- as fontes dos achados citadas nas notas dela: a tabela "Destino dos achados que este plano não corrige" de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md` e o handoff da M3 E06 em `docs/roadmaps/roadmap-m3-reservas.md`;
- o modelo da correção do `A-M1`: a Task 6 da M3 E06, commit `03542f0`;
- as "Restrições e invariantes" e as "Notas de máquina e de processo" do roadmap; o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; o [ADR 0008](../../adr/0008-fronteira-core-sdk.md).

Não há `ARCHITECTURE.md` no repositório. A semântica pública de `classifyWriteFailure` e as alternativas descartadas ficam registradas no handoff da E01.

**Correção da spec (registrada no handoff):** a nota da spec diz que a mudança de variante é "conservadora (não duplica)" nos três estados. Isso vale para o check-in e a troca de UH, que têm `Idempotency-Key`. O recado **não** é escrita idempotente (`createTrace` não declara `Idempotency-Key`; `TracesFacade.kt:107-108`; o `IdempotencyFilter` do servidor só atua em endpoints `@Idempotent`): nele, "desconhecido" quer dizer "pode ter sido feito; a lista é relida para conferir", e não "repita com a mesma chave".

**Riscos:** concorrência (a classificação decide se uma escrita repete com a mesma `Idempotency-Key` ou se a intenção é descartada; errar para "definitiva" permite duplicar); autenticação ou autorização (o ajudante `approvable` anexa o `X-Approval-Token` e gira a chave; o walk-in muda o caminho da aprovação cancelada).

## Global Constraints

- **Idempotência:** toda escrita idempotente leva `Idempotency-Key` por intenção. Repetir depois de resultado desconhecido reaproveita a chave; nenhuma resposta classificada como desconhecida descarta a intenção. Não se acrescenta `Idempotency-Key` a operação cujo contrato não a declara (o `createTrace`, por exemplo); o `TracesFacade` não muda nesta etapa.
- **Fronteira (ADR 0008):** as features do `app` falam com o servidor só pelo `core`; a função nova é API pública do `core` e não expõe tipo do `sdk`.
- **Regras transversais (design §6.2):** nada de JSON ou `code` cru ao usuário. Os textos de tela de cada ação continuam no estado dela, sem mudança, salvo os textos novos citados literalmente nas tarefas.
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1, como hoje.
- **Cancelamento:** `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`; cancelamento próprio se propaga.
- **Idioma:** UI e documentação em pt-BR; código em inglês.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`) quando a linha não é a primeira (exemplo: `ReservationsActionsTest.kt`, `45c4ce4`).
- **Arquivos do testador (`C-M6`):** antes de alterar um arquivo de teste, confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`. Com contagem maior que zero, não o altere. Se um teste do testador quebrar porque o comportamento mudou de propósito, reporte `DONE_WITH_CONCERNS` com o arquivo, o teste, a saída e o comportamento novo esperado, sem mexer nele: o orquestrador leva ao testador antes da revisão. As quebras já previstas estão na Task 2.
- **Verificação da tarefa:** `timeout 1500 ./gradlew :core:test --tests '<filtro core>' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest :app:test --tests '<filtro app>' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`, com os filtros da tarefa (`--tests` repetido). Nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez; nenhum laço de espera; todo comando longo com `timeout`.
- **Commits:** em pt-BR, no estilo do histórico, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo.

## Review Focus

1. **5xx de proxy (`Unexpected` 502/504) não é "guardado":** repetir com a mesma chave pode resolver, então o diálogo continua oferecendo "Tentar de novo". Teste: `unexpected5xxIsUnknownNotStored` (Task 1) e `unexpected502IsUnknownNotCloseOnly` nos estados (Task 2).
2. **`IDEMPOTENCY_KEY_IN_USE` com status diferente de 409** continua desconhecido (o critério é o `code`). Teste: `keyInUseIsUnknownWhateverTheStatus` (Task 1).
3. **Guardado e depois fechar:** com o caso guardado, o diálogo fecha e nenhuma ação do estado dispara a escrita de novo. Testes: `storedFailureOffersOnlyClose` nos diálogos e `confirmIsNoOpWhenCloseOnly` nos estados (Task 2).
4. **Recado com 5xx:** o formulário mostra o texto de resultado desconhecido que já tem e relê a lista (`onSaved`), sem mandar outra criação. Teste: `problem5xxGivesUnknownCreateResultAndReloads` (Task 1).
5. **Criação sem reserva repetida:** depois da resposta sem reserva, nenhuma ação dispara outro `createBooking`. Teste: `creationWithoutReservationNeverCreatesAgain` (Task 2).

## Interfaces

- `core/src/main/kotlin/com/zeny/desktop/core/error/WriteFailure.kt`:
  - `enum class WriteFailure { DEFINITIVE, UNKNOWN, UNKNOWN_STORED }`, com KDoc em pt-BR de cada valor. O KDoc de `UNKNOWN` diz que, numa escrita com chave, a repetição leva a mesma chave; numa escrita sem chave, quer dizer "pode ter sido feito, confira", e não "repita".
  - `fun classifyWriteFailure(error: ApiError): WriteFailure`, pura, com a regra da variante completa:
    - `Network`, `IncompatibleResponse`, `SessionEnded` → `UNKNOWN`;
    - `Problem` com `code == "IDEMPOTENCY_KEY_IN_USE"` → `UNKNOWN` (qualquer status);
    - `Problem` com `status >= 500` → `UNKNOWN_STORED` (o servidor guarda a resposta sob a chave; KDoc do `IdempotencyFilter` do servidor, linhas 55–60);
    - `Unexpected` com `status >= 500` → `UNKNOWN` (502/504 de proxy não são guardados);
    - todo o resto (`Problem` 4xx, `Unexpected` < 500, `ApprovalRequired`, `PreconditionFailed`, `PasswordChangeRequired`) → `DEFINITIVE`.
- `core/.../facade/Approval.kt`: `internal fun <T : Any> ApiExecutor.approvable(intent: Intent, call: suspend (CallOptions) -> HttpResponseTyped<T>): ApprovableAction<T>` é o ajudante único. O `ZenyCore.approvable` atual **passa a delegar** a ele (fica, porque `FacadesTest` e `FacadesAttackTest` o usam). Saem o `private fun <T : Any> approvable` do `BookingsFacade.kt` e as montagens em linha de `ApprovableAction` com `executeApproved` no `BookingsFacade.createBooking` e no `ReservationsFacade` (`checkIn`, `moveRoom`, `walkIn`).
- Nos quatro estados com o caso guardado (`WalkInState`, `NewReservationState`, `ReservationCancelState`, `ReservationReactivateState`): `val closeOnly: Boolean` (observável), verdadeiro quando a última falha foi `UNKNOWN_STORED` ou, só na nova reserva, a criação sem reserva. Com `closeOnly`:
  - o `confirm()` (ou a ação de concluir equivalente) não faz nada;
  - as entradas do formulário ficam travadas;
  - o diálogo não mostra o botão de confirmar nem "Tentar de novo", e o botão de fechar fecha mesmo com `unconfirmed`. Os três diálogos de reservas também fecham com Esc; o `WalkInDialog` continua sem fechar com Esc (`onDismissRequest = {}`), só pelo botão "Fechar".

---

### Task 1: A função única no `core`, o `approvable` único e os três estados que mudam de variante

**Objetivo:** criar `WriteFailure`/`classifyWriteFailure` (Interfaces), unificar o `approvable` (Interfaces), e trocar o `resultUnknown` de `CheckInState`, `RoomMoveState` e `TraceFormState` pela função.
- `CheckInState` e `RoomMoveState` (com chave): `UNKNOWN` mantém o comportamento de hoje (a intenção fica, a repetição leva a mesma chave, o texto é o de `ActionText`); `UNKNOWN_STORED` também mantém a intenção e mostra um texto novo: no check-in, `"O servidor não confirmou o check-in. Feche e confira a reserva antes de refazer."`; na troca de UH, `"O servidor não confirmou a troca de UH. Feche e confira a reserva antes de refazer."`. O botão continua (repetir só devolve a mesma resposta guardada).
- `TraceFormState` (sem chave): `UNKNOWN` e `UNKNOWN_STORED` seguem o caminho "desconhecido" que ele já tem (`TraceText.UNKNOWN_CREATE_RESULT` e `onSaved()`); não ganha `Intent` nem chave.

**Critérios de aceite:**
- `core`, `WriteFailureTest` (arquivo novo): um caso por linha da regra, inclusive `unexpected5xxIsUnknownNotStored`, `keyInUseIsUnknownWhateverTheStatus` (`Problem(422, "IDEMPOTENCY_KEY_IN_USE")` → `UNKNOWN`), `problem5xxIsStored` (500 e 503), `problem4xxIsDefinitive` (409 com outro `code` e 422), `approvalRequiredIsDefinitive`, `unexpected4xxIsDefinitive`. Verifica: `:core:test --tests 'com.zeny.desktop.core.error.WriteFailureTest'`.
- **`approvable` único:** `grep -n 'private fun <T : Any> approvable' core/src/main/kotlin/com/zeny/desktop/core/facade/BookingsFacade.kt` não devolve linha, e `grep -rn 'executeApproved(' core/src/main/kotlin/com/zeny/desktop/core/facade` devolve só linhas de `Approval.kt`. Os testes existentes das fachadas continuam verdes. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.*' --tests 'com.zeny.desktop.core.*Facades*'`.
- `CheckInStateTest` e `RoomMoveStateTest` (do desenvolvedor): `problem5xxKeepsTheKeyAndSaysCloseAndCheck` — `Problem(500)` mostra o texto novo literal, a repetição sem mudar nada leva a mesma chave (a porta falsa grava a chave); `networkStillKeepsTheKey` continua como hoje. Verifica: `:app:test --tests 'com.zeny.desktop.app.reception.CheckInState*' --tests 'com.zeny.desktop.app.reception.RoomMoveState*'`.
- `TraceFormStateTest` (do desenvolvedor): `problem5xxGivesUnknownCreateResultAndReloads` — `Problem(500)` dá `TraceText.UNKNOWN_CREATE_RESULT` e chama `onSaved()` uma vez. Verifica: `--tests 'com.zeny.desktop.app.traces.TraceFormState*'`.
- `grep -nE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInState.kt app/src/main/kotlin/com/zeny/desktop/app/reception/RoomMoveState.kt app/src/main/kotlin/com/zeny/desktop/app/traces/TraceFormState.kt` não devolve linha.
- Os testes do `app` que passam pelas fachadas do `ReservationsFacade` (check-in, troca, walk-in) continuam verdes. Verifica: `--tests 'com.zeny.desktop.app.reception.*'`.

**Áreas tocadas:** `core/.../error/` (arquivo novo), `core/.../facade/Approval.kt`, `core/.../facade/BookingsFacade.kt`, `core/.../facade/ReservationsFacade.kt`; `app/.../reception/CheckInState.kt`, `app/.../reception/RoomMoveState.kt`, `app/.../traces/TraceFormState.kt`; testes do `core` em `error/`, e os testes de estado dos três (do desenvolvedor).

**Tamanho:** ~50 min; 7 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz `WriteFailure` e `classifyWriteFailure` (bloco Interfaces), que a Task 2 consome.

---

### Task 2: Os cinco estados da variante completa, o `B-M2a`, o walk-in e a criação sem reserva

**Objetivo:** `WalkInState`, `NewReservationState`, `ReservationConfirmState`, `ReservationCancelState` e `ReservationReactivateState` trocam o `resultUnknown` e a dedução local do "guardado" pela função. Nos quatro com o texto "feche e confira", o estado expõe `closeOnly` e o diálogo passa a só oferecer fechar (`B-M2a`, Interfaces). O `WalkInState` ganha a correção do `A-M1` (modelo: `03542f0`). A nova reserva trata a resposta de criação sem reserva como `ApiError.IncompatibleResponse` definitivo, com texto próprio e `closeOnly`.

**Critérios de aceite:**
- `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` não devolvem linha.
- **Onde ficam os testes (do desenvolvedor, contagem `Soul: testador` zero em 2026-11-18):** `WalkInStateTest`, `WalkInDialogTest`, `NewReservationStateTest`, `NewReservationDialogTest`, `ReservationCancelStateTest`, `ReservationCancelDialogTest`, `ReservationConfirmStateTest` e `ReservationReactivateTest` (estado e diálogo da reativação). São do testador, e não se alteram: `WalkInStateAttackTest`, `NewReservationStateAtaqueTest`, `NewReservationFinishAtaqueTest`, `NewReservationDialog*AtaqueTest`, `ReservationRetryDialogsAtaqueTest` e `CancelledApprovalAfterUnknownAtaqueTest`.
- **`B-M2a`, estado:** nos testes de estado do walk-in, da nova reserva, de cancelar e de reativar, `problem5xxIsStoredAndCloseOnly`: `Problem(500)` → `unconfirmed`, `closeOnly`, o texto "feche e confira" que o estado já tem; `unexpected502IsUnknownNotCloseOnly`: `Unexpected(502)` → `unconfirmed`, sem `closeOnly`, o texto de "não confirmado"; `confirmIsNoOpWhenCloseOnly`: com `closeOnly`, a ação de confirmar não chama a porta.
- **`B-M2a`, diálogo:** `storedFailureOffersOnlyClose` nos testes de UI dos quatro diálogos: com `closeOnly`, nenhum botão de confirmar nem "Tentar de novo" existe, as entradas estão desabilitadas e o botão de fechar fecha.
- **`WalkInState` (`A-M1`):** `WalkInStateTest.unknownThenApprovalCancelledLeavesTextAndUnlocks` — resultado desconhecido, "Tentar de novo" com `APPROVAL_REQUIRED`, aprovação cancelada: `unconfirmed` falso, `busy` falso, a ação seguinte é possível, e `error == "Aprovação cancelada. O walk-in não foi concluído."` (texto novo; o `APPROVAL_REQUIRED` na mesma chave prova que nada foi feito, e o texto de "não confirmado" ficaria falso). Os três estados de reservas não mudam esse caminho (o testador fixou `error` nulo neles em `CancelledApprovalAfterUnknownAtaqueTest`).
- **Criação sem reserva:** `NewReservationStateTest.creationWithoutReservationIsIncompatibleAndCloseOnly` — um `BookingResponse` com `reservations` vazio vira `ApiError.IncompatibleResponse` tratado como definitivo: `unconfirmed` falso, `closeOnly` verdadeiro, `error == "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas."`; e `creationWithoutReservationNeverCreatesAgain` — nenhuma ação seguinte do estado chama `createBooking` de novo.
- **Quebras intencionais em arquivos do testador** (o desenvolvedor não as corrige; reporta `DONE_WITH_CONCERNS` com a saída, e o orquestrador as leva ao testador antes da revisão):
  - `ReservationRetryDialogsAtaqueTest.labelStaysRetryAcrossRepeatedUnknownResults` usa `Problem(502, "BAD_GATEWAY")`: com a regra nova é `UNKNOWN_STORED` e `closeOnly`;
  - `NewReservationFinishAtaqueTest` (`emptyReservationsIsAnUnknownResult…`, `emptyReservationsRetryKeepsTheKey…`, `emptyReservationsThenAnAnswer…FinishesOnce`) fixa o oposto do critério da criação sem reserva;
  - qualquer outro teste do testador que quebrar, com a mesma regra.
- Fora as quebras listadas, os testes existentes dos cinco estados e dos quatro diálogos continuam verdes sem afrouxar asserção. Verifica: `:app:test --tests 'com.zeny.desktop.app.reception.WalkIn*' --tests 'com.zeny.desktop.app.reservations.NewReservation*' --tests 'com.zeny.desktop.app.reservations.ReservationConfirm*' --tests 'com.zeny.desktop.app.reservations.ReservationCancel*' --tests 'com.zeny.desktop.app.reservations.ReservationReactivate*' --tests 'com.zeny.desktop.app.reservations.ReservationRetry*' --tests 'com.zeny.desktop.app.reservations.CancelledApproval*' --tests 'com.zeny.desktop.app.reservations.Reservations*' --tests 'com.zeny.desktop.app.reception.Reception*'`, mais o trio do `core` com `--tests 'com.zeny.desktop.core.error.*'`.

**Áreas tocadas:** `app/.../reception/` (`WalkInState.kt`, `WalkInDialog.kt`), `app/.../reservations/` (`NewReservationState.kt`, `NewReservationDialog.kt`, `ReservationConfirmState.kt`, `ReservationCancelState.kt`, `ReservationCancelDialog.kt`, `ReservationReactivateState.kt`, `ReservationReactivateDialog.kt`); os testes de estado e de diálogo deles que são do desenvolvedor.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** Task 1 (`classifyWriteFailure` e `WriteFailure`).

**Interfaces:** consome as da Task 1; produz `closeOnly` nos quatro estados (bloco Interfaces).

---

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:` e:
- ataca a função (todo subtipo de `ApiError`, status de borda 499/500/599, `code` com espaços ou caixa diferente) e a repetição com a mesma chave em cada estado; o `approvable` único (token anexado só na repetição aprovada, chave girada) nas fachadas que passaram a usá-lo;
- os itens do roadmap que são dele: **M4** (os dois testes de `NewReservationDialogEtapaAtaqueTest` com nome e asserção fortalecidos), **M6** (`NewReservationFlowAtaqueTest`: "tentativa aprovada com resultado desconhecido, depois aprovação cancelada") e **M7** (um mutante que explora o contorno da trava do `config.` em `tests/test_live_seed_availability_limpeza_ataque.sh`, linhas 95–101 no `dbeacff`, fica vermelho; roda `bash tests/run.sh tests/test_live_seed_availability_limpeza_ataque.sh`);
- da E06: o nome desatualizado `panelPermissionsStayEmptyWhileTheReadIsPendingAndNeverCallPermissionsTwice` em `FakeReceptionActionsAtaqueTest`, a seção de recados da reserva (`forReservation`) sem asserção pela casca e a mensagem "sem ler a permissão" em `ReservationsAvailabilityTabAtaqueTest`.

## Destino dos Menores M1, M2 e M5 da M3 E06

Resolvidos pela remoção do `AreaPermissionsState` na E06, commit `bca85f1`. A leitura do `me` que ficou (`ShellState.reload()`) usa `catch (e: Exception)` seguido de `ensureActive()` e não pega `Throwable` (conferido no `785d864`). O handoff registra os três com esse commit.
