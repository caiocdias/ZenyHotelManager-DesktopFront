# M4 E01 — Regra única do resultado desconhecido — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a classificação da falha de uma escrita idempotente (definitiva, desconhecida, desconhecida e guardada) passa a ser uma função pública do `core`, usada pelos 8 estados que hoje copiam a regra, com os achados da M3 E06 que dependem dela (D1).

**Architecture:** uma função pura no pacote `error` do `core` recebe um `ApiError` e devolve um `WriteFailure` de três valores. Os estados do `app` trocam o `resultUnknown` privado por ela e mantêm os próprios textos. O ajudante `approvable` passa a ser um só, sobre o `ApiExecutor`, e o `BookingsFacade` deixa a cópia privada. Nos estados que têm o texto "feche e confira" (o caso guardado), o diálogo deixa de oferecer "Tentar de novo" e passa a permitir fechar.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Gradle.

**Spec:**
- a seção "E01 — Regra única do resultado desconhecido" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` (com D1 em "Hipóteses e decisões em aberto");
- as fontes dos achados citadas nas notas dela: a tabela "Destino dos achados que este plano não corrige" de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md` e o handoff da M3 E06 em `docs/roadmaps/roadmap-m3-reservas.md`;
- o modelo da correção do `A-M1`: a Task 6 da M3 E06, commit `03542f0`;
- as "Restrições e invariantes" e as "Notas de máquina e de processo" do roadmap; o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; o [ADR 0008](../../adr/0008-fronteira-core-sdk.md).

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** concorrência (a classificação decide se uma escrita repete com a mesma `Idempotency-Key` ou se a intenção é descartada; errar para "definitiva" permite duplicar).

## Global Constraints

- **Idempotência:** toda escrita leva `Idempotency-Key` por intenção. Repetir depois de resultado desconhecido reaproveita a chave; nenhuma resposta classificada como desconhecida descarta a intenção.
- **Fronteira (ADR 0008):** as features do `app` falam com o servidor só pelo `core`; a função nova é API pública do `core` e não expõe tipo do `sdk`.
- **Regras transversais (design §6.2):** nada de JSON ou `code` cru ao usuário. Os textos de tela de cada ação continuam no estado dela, sem mudança, salvo os textos novos citados nas tarefas.
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1, como hoje.
- **Cancelamento:** `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`; cancelamento próprio se propaga.
- **Idioma:** UI e documentação em pt-BR; código em inglês.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`) quando a linha não é a primeira (exemplo: `ReservationsActionsTest.kt`, `45c4ce4`).
- **Arquivos do testador (`C-M6`):** antes de alterar um arquivo de teste, confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`. Com contagem maior que zero, não o altere: a mudança vai ao testador de etapa, e o relatório lista o arquivo e o que precisa mudar. Se um teste do testador quebrar só porque o comportamento mudou de propósito (um 5xx que passa de definitivo a desconhecido, por exemplo), reporte `DONE_WITH_CONCERNS` com o arquivo, o teste e a saída, sem mexer nele.
- **Verificação da tarefa:** `timeout 1500 ./gradlew :core:test --tests '<filtro core>' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest :app:test --tests '<filtro app>' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`, com os filtros da tarefa (`--tests` repetido). Nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez; nenhum laço de espera; todo comando longo com `timeout`.
- **Commits:** em pt-BR, no estilo do histórico, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo.

## Review Focus

1. **5xx de proxy (`Unexpected` 502/504) não é "guardado":** repetir com a mesma chave pode resolver, então o diálogo continua oferecendo "Tentar de novo". Teste: `unexpected5xxIsUnknownNotStored` (Task 1) e, nos estados, o caso `Unexpected(502)` (Task 2).
2. **`IDEMPOTENCY_KEY_IN_USE` com status diferente de 409** continua desconhecido (o critério é o `code`). Teste: `keyInUseIsUnknownWhateverTheStatus` (Task 1).
3. **Guardado e depois fechar:** com o caso guardado, o diálogo fecha (Esc e "Fechar") e não deixa disparar a escrita de novo. Teste: `storedFailureOffersOnlyClose` em cada diálogo (Task 2).
4. **Recado com `IDEMPOTENCY_KEY_IN_USE`** passa de definitivo a desconhecido: o formulário fica com a mesma intenção e não cria outro recado ao repetir. Teste: `keyInUseNowKeepsTheIntent` (Task 1, `TraceFormState`).
5. **Criação sem reserva repetida:** depois da resposta sem reserva, nenhuma ação dispara outro `createBooking`. Teste: `creationWithoutReservationNeverCreatesAgain` (Task 2).

## Interfaces

- `core/src/main/kotlin/com/zeny/desktop/core/error/WriteFailure.kt`:
  - `enum class WriteFailure { DEFINITIVE, UNKNOWN, UNKNOWN_STORED }`, com KDoc em pt-BR de cada valor;
  - `fun classifyWriteFailure(error: ApiError): WriteFailure`, pura, com a regra da variante completa:
    - `Network`, `IncompatibleResponse`, `SessionEnded` → `UNKNOWN`;
    - `Problem` com `code == "IDEMPOTENCY_KEY_IN_USE"` → `UNKNOWN` (qualquer status);
    - `Problem` com `status >= 500` → `UNKNOWN_STORED` (o servidor guarda a resposta sob a chave; KDoc do `IdempotencyFilter` do servidor, linhas 55–60);
    - `Unexpected` com `status >= 500` → `UNKNOWN` (502/504 de proxy não são guardados);
    - todo o resto (`Problem` 4xx, `Unexpected` < 500, `ApprovalRequired`, `PreconditionFailed`, `PasswordChangeRequired`) → `DEFINITIVE`.
- `core/.../facade/Approval.kt`: `internal fun <T : Any> ApiExecutor.approvable(intent: Intent, call: suspend (CallOptions) -> HttpResponseTyped<T>): ApprovableAction<T>` é o ajudante único; o `ZenyCore.approvable` atual passa a delegar a ele (ou sai, com os chamadores trocados). Sai o `private fun <T : Any> approvable` do `BookingsFacade.kt`, e `createBooking`, `cancelReservation` e `reactivateReservation` usam o comum.
- Nos estados com o caso guardado (`WalkInState`, `NewReservationState`, `ReservationCancelState`, `ReservationReactivateState`): `val closeOnly: Boolean` (observável), verdadeiro quando a última falha foi `UNKNOWN_STORED` ou, só na nova reserva, a criação sem reserva. Com `closeOnly`, o diálogo não mostra o botão de confirmar nem "Tentar de novo", e fechar (Esc e o botão de fechar) é permitido mesmo com `unconfirmed`.

---

### Task 1: A função única no `core`, o `approvable` único e os três estados que mudam de variante

**Objetivo:** criar `WriteFailure`/`classifyWriteFailure` (Interfaces), unificar o `approvable`, e trocar o `resultUnknown` de `CheckInState`, `RoomMoveState` e `TraceFormState` pela função. Nesses três, `UNKNOWN` e `UNKNOWN_STORED` contam como resultado desconhecido, com o texto de "não confirmado" que cada estado já tem (eles não têm o texto de "feche e confira", e o diálogo deles não muda nesta etapa).

**Critérios de aceite:**
- `core`, `WriteFailureTest` (arquivo novo): um caso por linha da regra, inclusive `unexpected5xxIsUnknownNotStored`, `keyInUseIsUnknownWhateverTheStatus` (`Problem(422, "IDEMPOTENCY_KEY_IN_USE")` → `UNKNOWN`), `problem5xxIsStored` (500 e 503), `problem4xxIsDefinitive` (409 com outro `code` e 422), `approvalRequiredIsDefinitive`, `unexpected4xxIsDefinitive`. Verifica: `:core:test --tests 'com.zeny.desktop.core.error.WriteFailureTest'`.
- `core`: `grep -n 'private fun <T : Any> approvable' core/src/main/kotlin/com/zeny/desktop/core/facade/BookingsFacade.kt` não devolve linha; os testes existentes do `BookingsFacade` continuam verdes. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.*'`.
- `CheckInStateTest`, `RoomMoveStateTest` e `TraceFormStateTest` (arquivos do desenvolvedor; confira o `C-M6`): `problem5xxIsNowUnknown` em cada um (o `Problem(500)` deixa `unconfirmed`, mantém a intenção e a repetição leva a mesma chave). No `TraceFormStateTest`, também `keyInUseNowKeepsTheIntent`. Verifica: `:app:test --tests 'com.zeny.desktop.app.reception.CheckInState*' --tests 'com.zeny.desktop.app.reception.RoomMoveState*' --tests 'com.zeny.desktop.app.traces.TraceFormState*'`.
- `grep -nE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInState.kt app/src/main/kotlin/com/zeny/desktop/app/reception/RoomMoveState.kt app/src/main/kotlin/com/zeny/desktop/app/traces/TraceFormState.kt` não devolve linha.

**Áreas tocadas:** `core/.../error/` (arquivo novo), `core/.../facade/Approval.kt`, `core/.../facade/BookingsFacade.kt` (e o `ZenyCore.kt` só se o `approvable` dele sair); `app/.../reception/CheckInState.kt`, `app/.../reception/RoomMoveState.kt`, `app/.../traces/TraceFormState.kt`; testes do `core` em `error/` e `facade/`, e os testes de estado dos três.

**Tamanho:** ~45 min; 6 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz `WriteFailure`, `classifyWriteFailure` e o `ApiExecutor.approvable` comum (bloco Interfaces), que a Task 2 consome.

---

### Task 2: Os cinco estados da variante completa, o `B-M2a`, o walk-in e a criação sem reserva

**Objetivo:** `WalkInState`, `NewReservationState`, `ReservationConfirmState`, `ReservationCancelState` e `ReservationReactivateState` trocam o `resultUnknown` e a dedução local do "guardado" pela função. Nos quatro com o texto "feche e confira", o estado expõe `closeOnly` e o diálogo passa a só oferecer fechar (`B-M2a`). O `WalkInState` ganha a correção do `A-M1` (modelo: `03542f0`). A nova reserva trata a resposta de criação sem reserva como `ApiError.IncompatibleResponse` definitivo, com texto próprio e só "fechar".

**Critérios de aceite:**
- `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` não devolve linha. Verifica no fim da fatia.
- **`B-M2a`, estado:** nos testes de estado do walk-in, da nova reserva, de cancelar e de reativar (arquivos do desenvolvedor; confira o `C-M6`), `problem5xxIsStoredAndCloseOnly`: `Problem(500)` → `unconfirmed`, `closeOnly`, o texto "feche e confira" que o estado já tem; e `unexpected502IsUnknownNotCloseOnly`: `Unexpected(502)` → `unconfirmed`, sem `closeOnly`, texto de "não confirmado".
- **Onde ficam os testes (do desenvolvedor, contagem `Soul: testador` zero em 2026-11-18):** `WalkInStateTest`, `WalkInDialogTest`, `NewReservationStateTest`, `NewReservationDialogTest`, `ReservationCancelStateTest`, `ReservationCancelDialogTest`, `ReservationConfirmStateTest` e `ReservationReactivateTest` (estado e diálogo da reativação). São do testador, e não se alteram: `WalkInStateAttackTest`, `NewReservationStateAtaqueTest`, `NewReservationDialog*AtaqueTest`, `ReservationRetryDialogsAtaqueTest` e `CancelledApprovalAfterUnknownAtaqueTest`.
- **`B-M2a`, diálogo:** `storedFailureOffersOnlyClose` nos testes de UI dos quatro diálogos (`WalkInDialog`, `NewReservationDialog`, `ReservationCancelDialog`, `ReservationReactivateDialog`): com `closeOnly`, nenhum botão de confirmar nem "Tentar de novo" existe, e o botão de fechar fecha.
- **`WalkInState` (`A-M1`):** `WalkInStateTest.unknownThenApprovalCancelledLeavesTextAndUnlocks` — resultado desconhecido, "Tentar de novo" com `APPROVAL_REQUIRED`, aprovação cancelada: há texto na tela (o de aprovação cancelada que o estado usar, em pt-BR) e o formulário não fica travado (`busy` falso e a ação seguinte é possível), como nos três estados de reservas depois de `03542f0`.
- **Criação sem reserva:** `NewReservationStateTest.creationWithoutReservationIsIncompatibleAndCloseOnly` — um `BookingResponse` com `reservations` vazio vira `ApiError.IncompatibleResponse` tratado como definitivo: `unconfirmed` falso, `closeOnly` verdadeiro, `error == "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas."`; e `creationWithoutReservationNeverCreatesAgain` — nenhuma ação seguinte do estado chama `createBooking` de novo.
- Os testes existentes dos cinco estados e dos quatro diálogos continuam verdes sem afrouxar asserção. Verifica: `:app:test --tests 'com.zeny.desktop.app.reception.WalkIn*' --tests 'com.zeny.desktop.app.reservations.NewReservation*' --tests 'com.zeny.desktop.app.reservations.ReservationConfirm*' --tests 'com.zeny.desktop.app.reservations.ReservationCancel*' --tests 'com.zeny.desktop.app.reservations.ReservationReactivate*' --tests 'com.zeny.desktop.app.reservations.Reservations*' --tests 'com.zeny.desktop.app.reception.Reception*'`, mais o trio do `core` com `--tests 'com.zeny.desktop.core.error.*'`.

**Áreas tocadas:** `app/.../reception/` (`WalkInState.kt`, `WalkInDialog.kt`), `app/.../reservations/` (`NewReservationState.kt`, `NewReservationDialog.kt`, `ReservationConfirmState.kt`, `ReservationCancelState.kt`, `ReservationCancelDialog.kt`, `ReservationReactivateState.kt`, `ReservationReactivateDialog.kt`); os testes de estado e de diálogo deles que são do desenvolvedor.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** Task 1 (`classifyWriteFailure`, `WriteFailure` e o `approvable` comum).

**Interfaces:** consome as da Task 1; produz `closeOnly` nos quatro estados (bloco Interfaces).

---

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco no `Riscos:` e:
- ataca a função (todo subtipo de `ApiError`, status de borda 499/500/599, `code` com espaços ou caixa diferente) e a repetição com a mesma chave em cada estado;
- os itens do roadmap que são dele: **M4** (os dois testes de `NewReservationDialogEtapaAtaqueTest` com nome e asserção fortalecidos), **M6** (`NewReservationFlowAtaqueTest`: "tentativa aprovada com resultado desconhecido, depois aprovação cancelada") e **M7** (um mutante que explora o contorno da trava do `config.` em `tests/test_live_seed_availability_limpeza_ataque.sh`, linhas 95–101 no `dbeacff`, fica vermelho; roda `bash tests/run.sh tests/test_live_seed_availability_limpeza_ataque.sh`);
- os arquivos do testador que quebrarem pela mudança intencional de variante (relatórios das tarefas);
- da E06: o nome desatualizado `panelPermissionsStayEmptyWhileTheReadIsPendingAndNeverCallPermissionsTwice` em `FakeReceptionActionsAtaqueTest`, a seção de recados da reserva (`forReservation`) sem asserção pela casca e a mensagem "sem ler a permissão" em `ReservationsAvailabilityTabAtaqueTest`.

## Destino dos Menores M1, M2 e M5 da M3 E06

Resolvidos pela remoção do `AreaPermissionsState` na E06, commit `bca85f1`. A leitura do `me` que ficou (`ShellState.reload()`) usa `catch (e: Exception)` seguido de `ensureActive()` e não pega `Throwable` (conferido no `785d864`). O handoff registra os três com esse commit.
