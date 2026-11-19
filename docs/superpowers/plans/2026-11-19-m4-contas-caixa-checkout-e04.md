# M4 E04 — Check-out — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a partir de uma reserva hospedada na Recepção, um assistente mostra o que falta para sair (o saldo de cada conta da reserva, as cauções ainda capturáveis e a cotação do late check-out), leva a operadora a receber e a capturar ou liberar pela conta, e conclui o check-out; a reserva sai de "hospedados" e a UH fica vaga e suja no mapa em tempo real. A duplicação entre o painel do dia e a busca de reservas passou à E11 (I7 da revisão de arquitetura).

**Architecture:** no `core`, uma fachada nova `CheckOutFacade` (`ZenyCore.checkOut`) com a cotação do late (`getEarlyLateQuote`) e o check-out (`checkOutReservation`) idempotente, que devolve um resultado tipado: concluído, ou o saldo devido por conta (`409 FOLIO_BALANCE_NOT_ZERO`, com `folios` e `total` do problema, já com as cobranças da noite da chegada e o late). Para isso, o `ApiError.Problem` passa a guardar os campos extras do problema num campo `internal` (o parâmetro do construtor é público, no fim e com padrão), que só o `core` lê. No `app`, uma porta `CheckOutSource` e um *state holder* `CheckOutState` no pacote `reception`, que lê as contas e as cauções pelas portas da E02/E09 e usa o `FolioWrite` (E09) para a escrita. O recebimento do devido e a resolução das cauções usam o que já existe: o painel da conta (`FolioState`) num diálogo e o `PaymentFormState` com o valor sugerido. O servidor faz o check-out numa transação só (lança o late, confere os saldos, libera as cauções, encerra as contas e deixa a UH Vaga e Suja): o front não chama o encerramento de conta à parte. O mapa de UHs já é de tempo real (M1).

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, kotlinx.serialization, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E04 — Check-out" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E03 e da E09;
- o contrato `api/openapi.yaml`: `checkOutReservation`, `getEarlyLateQuote` (`EarlyLateQuoteResponse`) e, para as leituras, `listFolios`, `listCardPreAuthorizations`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2 e §7; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização ("Check-out" pelo conjunto da casca, `frontdesk.stays.checkout`; o recebimento e as cauções pelas permissões da E03/E09); dado pessoal ou segredo (o cartão das cauções lidas, as credenciais da verificação ao vivo, as `extensions` fora de `toString` e de log); cálculo de valor monetário (saldos, devido por conta, total e late, só exibidos ou copiados do servidor); concorrência (check-out idempotente repetido depois de falha, duplo clique, outra recepção alterando a reserva ao mesmo tempo, `RESERVATION_CHANGED`); parsing de entrada externa não confiável (os campos extras do problema `FOLIO_BALANCE_NOT_ZERO`, decimais e instantes do servidor).

## Global Constraints

- **Dinheiro:** `Money` do `core`, string decimal na API, nunca `Double`. O front nunca calcula saldo, devido, total nem late: mostra o que o servidor devolve (`folio.balance`, os `folios[].balance` e o `total` do problema, o `fee` da cotação). O valor sugerido para receber é o devido daquela conta, copiado sem conta.
- **Conferência por leitura (H1):** antes de concluir, o assistente lê as contas da reserva (`FolioSource.reservationFolios`) e as cauções de cada conta (`PaymentSource.preAuthorizations`) e mostra as pendências: conta com saldo diferente de zero ("Falta receber <valor>" ou, credora, "Conta credora: <valor> a devolver") e caução ainda capturável (`ACTIVE` ou `PARTIALLY_CAPTURED`, não vencida: "Caução a capturar ou liberar"). **Com caução capturável, "Concluir check-out" fica desabilitado** e o texto diz "Capture ou libere a caução antes de concluir." (critério 2: o servidor liberaria a caução sozinho, e quem decide entre capturar e liberar é a operadora). A pendência de caução é decidida só pelo que o servidor devolve (`status` em `ACTIVE`/`PARTIALLY_CAPTURED` e capturável maior que zero), pelo mesmo predicado da seção "Cauções" (o `CAPTURABLE`/`canCapture` de `PreAuthorizationsState.kt` sai para uma função `internal` comum, usada pelos dois), nunca pelo relógio local; `EXPIRED` não é pendência (o servidor a libera), o que diverge da nota da E09 para a E04 e fica registrado no handoff. **Falha de qualquer leitura** (contas ou cauções de uma conta) bloqueia "Concluir", com "Não foi possível conferir as contas e as cauções." e "Tentar de novo". Quem tem `frontdesk.stays.checkout` sem `billing.payments.receive` vê a pendência de caução com o texto "Peça a quem pode receber pagamentos para capturar ou liberar a caução." e não conclui (regra do front, registrada como decisão).
- **Saldo devido:** "Concluir check-out" envia o check-out mesmo com saldo lido zero (o servidor pode lançar a noite da chegada e o late na transação). A resposta `409 FOLIO_BALANCE_NOT_ZERO` vira o resultado `BalanceDue` (não uma falha): o assistente mostra o devido por conta e o total como o servidor mandou, cada conta com "Receber <valor>", que abre o `PaymentFormState` daquela conta com o valor sugerido igual ao devido dela; uma conta credora no devido mostra "Conta credora: <valor> a devolver" e "Abrir conta" (o reembolso é da E03). Depois de receber, "Concluir check-out" envia de novo, com **outra** `Idempotency-Key` (o contrato pede: a resposta definitiva gira a intenção; o `BalanceDue` passa pelo `WriteOutcome.Done` do `FolioWrite`, que zera a intenção). **O recebimento do devido** (`receiveDue`) repete as regras do `FolioState.startPayment`/`received`: "Receber" só com `billing.payments.receive` e a porta; o troco do servidor aparece no assistente (`FolioText.received`); `CASHIER_SHIFT_REQUIRED` oferece "Abrir turno", que fecha o recebimento e o assistente e chama o `openShift` do wiring; `FOLIO_CLOSED` relê o assistente. **Depois de receber um devido**, o assistente relê, e a conta que teve o devido recebido mostra "Recebido. Conclua o check-out." em vez de "Conta credora" (o late e a noite da chegada não ficaram na conta: o `409` desfez a transação) até a próxima resposta do check-out; fechar o assistente nesse estado avisa "O pagamento foi registrado, mas o check-out não foi concluído." no painel.
- **Late check-out:** a cotação (`getEarlyLateQuote`) aparece quando `lateCheckOut.offered`: "Late check-out até <HH:mm>: <valor>", com a caixa "Cobrar o late check-out" (desligada por padrão). Ligada, o check-out leva `lateCheckOutFee` igual ao `fee` da cotação. A caixa e o valor fazem parte da intenção: com o resultado desconhecido, a caixa trava e a repetição reenvia o valor guardado quando a intenção nasceu (o padrão da E09, Task 3), nunca o da cotação relida; mudar a caixa sem trava chama `edited()`; depois de receber um devido que incluía o late, a caixa trava ligada até a próxima resposta do check-out. `EARLY_LATE_FEE_CHANGED` mostra o texto do catálogo e relê a cotação, sem enviar de novo; `EARLY_LATE_NOT_OFFERED` desliga a caixa e relê a cotação. Sem late oferecido, ou com a leitura falhando, nada de late aparece e o check-out segue sem ele (a falha da cotação não bloqueia). **Critério 4:** o servidor só lança o late na conta dentro da transação do check-out (tudo ou nada), então "aparece como lançamento antes do saldo final" é cumprido assim: com a caixa ligada, o assistente mostra a linha "Late check-out: <valor>" acima do total, e o `BalanceDue` que o servidor devolve já inclui o late; depois do sucesso, a conta relida mostra o lançamento do late (verificação ao vivo, passo 3).
- **Nada pela metade:** `RESERVATION_CHANGED` e as recusas do late mostram o texto do catálogo em pt-BR e relêem o assistente inteiro (contas, cauções, cotação). `RESERVATION_TRANSITION_NOT_ALLOWED` e `RESERVATION_NOT_FOUND` (outra recepção já fez o check-out, ou a reserva sumiu) mostram o texto, fecham o assistente e relêem o painel do dia e o detalhe. O sucesso fecha o assistente, avisa "Check-out concluído." no painel e relê o painel do dia e o detalhe.
- **Idempotência e D1:** o check-out leva `Idempotency-Key` por intenção, pelo `FolioWrite` (E09), com `classifyWriteFailure`; `UNKNOWN_STORED` dá `closeOnly` com "O servidor não confirmou o check-out. Feche e confira a reserva antes de tentar de novo."; por rede, "Não foi possível confirmar o check-out. Conclua de novo sem mudar nada; se fechar, confira a reserva antes de tentar de novo.". Os greps da E01 continuam sem linha. O `BalanceDue` é resposta definitiva (gira a intenção).
- **Permissões (ADR 0013):** "Check-out" no detalhe da reserva só com `frontdesk.stays.checkout` no conjunto da casca e a reserva `CHECKED_IN`. Receber e cauções seguem as permissões da E03/E09 dentro do painel da conta; sem `billing.folios.read` (o `FolioWiring` nulo) ou sem a porta de pagamentos (`wiring.payments` nulo), o assistente não lê pendências, mostra "Seu perfil não pode ler as contas desta reserva." e ainda permite concluir (o servidor confere os saldos e libera as cauções; decisão registrada no handoff).
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`. O `ApiError.Problem` ganha o parâmetro `extensions: JsonObject = JsonObject(emptyMap())` (no fim, com padrão: os testes que o constroem, inclusive os do testador, e o `demo-seed` continuam compilando), guardado em `internal val extensions`: só o `core` o lê, e nenhum `.extensions` aparece em `app/src` (`grep -rn '\.extensions' app/src/main` sem linha). Alternativa descartada: tipar o devido no `ProblemMapper`, como o `ApprovalRequired`, que levaria domínio de conta ao mapeador transversal. O `Redaction.kt` do `demo-seed` refaz o `Problem` sem os valores e continua sem extensões. As `extensions` não aparecem em `toString` nem em log.
- **Regras transversais (design §6.2):** nada de JSON, `code` cru nem UUID na tela; o número da conta (`number`) identifica a conta; pt-BR na tela, inglês no código.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. - **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (e os que usam o que mudou: `core` → `app` e `demo-seed`), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Duplo clique em "Concluir check-out"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleConfirmChecksOutOnce`, `retryAfterNetworkKeepsTheKey` (Task 2).
2. **Saldo devido com duas contas, uma devedora e uma credora** (o servidor não as anula): as duas aparecem com o valor do servidor, o total é o do servidor, e o segundo envio leva outra chave. Teste: `balanceDueShowsEachFolioAndRetriesWithANewKey` (Task 2).
3. **Problema `FOLIO_BALANCE_NOT_ZERO` com extensões malformadas** (`folios` ausente, `balance` fora do formato, `total` ausente) não derruba o assistente: vira o texto do catálogo e a releitura, sem valor inventado. Teste: `malformedBalanceDueFallsBackToTheCatalogText` (Task 1).
4. **Outra recepção faz o check-out ao mesmo tempo** (`409 RESERVATION_CHANGED` ou `RESERVATION_TRANSITION_NOT_ALLOWED`): o texto em pt-BR e a releitura, nada pela metade. Teste: `concurrentChangeShowsTextAndReloads` (Task 2).
5. **A cotação muda entre a leitura e o envio** (`409 EARLY_LATE_FEE_CHANGED`): o texto, a cotação relida e nenhum envio automático. Teste: `feeChangedRereadsTheQuoteAndWaits` (Task 2).

## Interfaces

- `core/.../facade/CheckOutFacade.kt` (Task 1), exposto como `ZenyCore.checkOut`:
  - `data class LateCheckOutQuote(val limitTime: String, val fee: Money)`: `limitTime` é o `lateCheckOut.limitTime` do `EarlyLateQuoteResponse` (`time-local`, por exemplo `10:00:00`), cortado em `HH:mm` pela fachada; a cotação é `null` quando `offered` é falso ou quando vem `offered` sem `fee` ou sem `limitTime`;
  - `suspend fun lateCheckOutQuote(reservationId: String): LateCheckOutQuote?`;
  - `data class FolioDue(val folioId: String, val number: String, val balance: Money)`;
  - `sealed interface CheckOutResult { data object Done : CheckOutResult; data class BalanceDue(val folios: List<FolioDue>, val total: Money) : CheckOutResult }`;
  - `suspend fun checkOut(reservationId: String, lateFee: Money?, intent: Intent): CheckOutResult` — `409 FOLIO_BALANCE_NOT_ZERO` com extensões válidas vira `BalanceDue`; com extensões malformadas, a `ApiError.Problem` original sobe; as outras falhas sobem como `ApiError`.
  Id que não é UUID lança `IllegalArgumentException` antes da rede, sem o valor.
- `ApiError.Problem(..., extensions: JsonObject = JsonObject(emptyMap()))`, preenchido pelo `ProblemMapper` com os campos do corpo que não são do RFC 9457 nem os já tipados (`type`, `title`, `status`, `detail`, `instance`, `code`, `errors`).
- `app/.../reception/CheckOutSource.kt` (Task 1): `lateCheckOutQuote(reservationId)` e `checkOut(reservationId, lateFee, intent)`; `ServerConnection.checkOutSource(): CheckOutSource? = null` (implementada em `CoreConnection`).
- `CheckOutState(source: CheckOutSource, folios: FolioWiring?, scope: CoroutineScope, reservationId: String, onDone: () -> Unit)` (Task 2), no pacote `reception`: `pending: List<CheckOutPending>`, `loaded`, `error`, `quote: LateCheckOutQuote?`, `var chargeLate: Boolean`, `due: CheckOutResult.BalanceDue?`, `val canConclude: Boolean`, `fun conclude()`, `fun reload()`, `var dueReceived: Set<String>` (as contas com devido recebido, Global Constraints), `fun receiveDue(folioId: String)` / `var payment: PaymentFormState?` / `fun finishPayment()`, `var notice: String?`, `fun close()`; implementa o `FolioWriteForm` pelo `FolioWrite`. "Abrir conta" é da Task 3, pelo `ReservationFolioDialog` existente (Interfaces da Task 3). `sealed interface CheckOutPending` com `Balance(number, text, folioId)` e `PreAuthorization(number, text, folioId)`.
- `DetailActions.onCheckOut: (() -> Unit)? = null` (Task 3), com o botão "Check-out" (`testTag("check-out")`). O assistente vive na `ReceptionScreen` no padrão `FolioDialogUi`/`CheckInUi` (fecha ao trocar a reserva do detalhe, de área ou de sessão).
- "Abrir conta" no assistente (Task 3): abre o `ReservationFolioDialog` da E02 para a reserva (o mesmo diálogo do botão "Conta"), por cima do assistente; o `onWritten` do `FolioState` e o fechamento do diálogo chamam o `reload()` do assistente.

## Revisão de arquitetura

(preenchida depois da revisão)

---

### Task 1: O check-out e a cotação no `core`, as extensões do problema e a porta

**Objetivo:** o `ProblemMapper` guarda os campos extras do problema em `ApiError.Problem.extensions` (Interfaces), sem mudar o que já é lido. Criar a `CheckOutFacade` (Interfaces), registrar `ZenyCore.checkOut`, e a porta `CheckOutSource` com a implementação em `CoreConnection`. Os `code` (`FOLIO_BALANCE_NOT_ZERO`, `RESERVATION_CHANGED`, `RESERVATION_TRANSITION_NOT_ALLOWED`, `EARLY_LATE_NOT_OFFERED`, `EARLY_LATE_FEE_CHANGED`, `RESERVATION_NOT_FOUND`) já têm texto em pt-BR no `ErrorCatalog`; o catálogo não muda.

**Critérios de aceite:**
- `ProblemMapperTest` (do desenvolvedor, ou um arquivo novo se for do testador): `extensionsKeepTheExtraFields` (`folios` e `total` ficam em `extensions`; `code`, `detail` e `errors` não); `problemWithoutExtrasHasEmptyExtensions`; `extensionsStayOutOfToString`.
- `CheckOutFacadeTest` (novo, `MockEngine`): `quoteOfferedGivesLimitTimeAndFee` (`10:00:00` vira `10:00`); `quoteOfferedWithoutFeeOrLimitIsNull`; `quoteNotOfferedGivesNull`; `checkOutSendsKeyAndNoBodyWithoutLate`; `checkOutSendsTheLateFeeAsExactDecimal`; `sameIntentKeepsTheKey`; `balanceNotZeroBecomesBalanceDue` (duas contas, uma devedora e uma credora, e o total, como o servidor mandou); `malformedBalanceDueFallsBackToTheCatalogText` (sem `folios`, com `balance` fora do formato, sem `total`: sobe a `ApiError.Problem` com o `code`); `otherConflictsStayErrors`; `nonUuidIdFailsBeforeTheNetwork`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.CheckOut*' --tests 'com.zeny.desktop.core.error.*'` e o trio do `core`; o trio do `app` e o do `demo-seed` continuam verdes (a mudança no `ApiError` é aditiva).
- `CoreConnection.checkOutSource()` devolve a porta ligada à fachada. Verifica: `:app:test --tests 'com.zeny.desktop.app.session.*'`.

**Áreas tocadas:** `core/.../error/ApiError.kt`, `ProblemMapper.kt`, `core/.../facade/CheckOutFacade.kt` (novo), `core/.../ZenyCore.kt`; `app/.../reception/CheckOutSource.kt` (novo), `app/.../session/ServerConnection.kt`; testes do `core`.

**Tamanho:** ~50 min; 6 arquivos de produção (2 novos).

**Depende de:** nenhuma.

**Interfaces:** produz `ZenyCore.checkOut`, `LateCheckOutQuote`, `FolioDue`, `CheckOutResult`, `ApiError.Problem.extensions`, `CheckOutSource` e `ServerConnection.checkOutSource()`.

---

### Task 2: O assistente de check-out

**Objetivo:** o `CheckOutState` e a tela dele (`CheckOutDialog.kt`, um diálogo grande) fazem o fluxo das Global Constraints: lê as contas da reserva, as cauções de cada conta e a cotação; mostra as pendências, a cotação do late com a caixa, o total e "Concluir check-out"; o lugar do "Abrir conta" em cada pendência fica pronto (um callback `onOpenFolio: (() -> Unit)?`, nulo nesta fatia); no `BalanceDue`, cada conta devedora tem "Receber <valor>", que abre o `PaymentFormState` daquela conta com o valor sugerido igual ao devido. O check-out passa pelo `FolioWrite`. Um diálogo por vez (assistente → conta ou recebimento). Sem `folios` (o wiring nulo), as pendências não aparecem e o assistente diz "Seu perfil não pode ler as contas desta reserva.".

**Critérios de aceite:**
- `CheckOutStateTest` (novo, portas falsas): `pendingShowsBalancesAndCapturablePreAuthorizations` (os valores do servidor, sem conta; caução vencida não é pendência); `capturablePreAuthorizationBlocksConclude` (texto "Capture ou libere a caução antes de concluir." e nenhum envio — critério 2); `releasingTheCautionUnblocks` (o `reload()` depois de liberar destrava); `preAuthorizationReadFailureBlocksConclude`; `expiredIsNotPending`; `withoutReceiveTheCautionSaysWhoCan`; `doubleConfirmChecksOutOnce`; `retryAfterNetworkKeepsTheKey`; `storedFailureIsCloseOnly`; `balanceDueShowsEachFolioAndRetriesWithANewKey`; `receiveDueSuggestsTheServerAmount`; `receiveDueNeedsPermission`; `receiveDueShowsTheChange`; `receiveDueShiftRequiredClosesAndOpensTheShift`; `receiveDueFolioClosedReloads`; `afterReceivingDueDoesNotSayRefund`; `closingWithDuePaidWarns`; `lateIsSentOnlyWhenChecked` (o `lateCheckOutFee` é o `fee` da cotação); `retryAfterNetworkResendsTheStoredFee`; `lateBoxChangeRotatesTheIntent`; `lateBoxLocksAfterReceivingADueWithLate`; `lateLineShowsAboveTheTotal` (critério 4); `feeChangedRereadsTheQuoteAndWaits`; `lateNotOfferedUnchecksAndRereads`; `quoteFailureDoesNotBlock`; `concurrentChangeShowsTextAndReloads` (critério 3); `alreadyCheckedOutClosesAndReloadsThePanel`; `successCallsOnDoneAndCloses`; `withoutFolioReadSaysSoAndStillConcludes`; `closeDropsLateAnswers`.
- Teste de UI (`CheckOutDialogTest`, novo): as pendências e o total aparecem; "Concluir check-out" desabilitado com caução capturável; "Receber" no devido abre o recebimento com o valor; o late aparece com o horário e o valor e a caixa desligada.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.reception.CheckOut*' --tests 'com.zeny.desktop.app.folio.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`; `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main`, `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` e `grep -rn '\.extensions' app/src/main` sem linha.

**Áreas tocadas:** `app/.../reception/` (novos: `CheckOutState.kt`, `CheckOutDialog.kt`, `CheckOutText.kt`), `app/.../folio/PreAuthorizationsState.kt` (o predicado de capturável vira `internal` e comum); testes de `reception/`.

**Tamanho:** ~60 min; 4 arquivos de produção (3 novos; o recebimento e o `FolioWrite` são reusados, e o "Abrir conta" foi para a Task 3). Corte pré-autorizado se passar de 60 min: o late (cotação, caixa e as regras dela) passa à Task 3, que tem folga; o corte vai ao ledger.

**Depende de:** Task 1.

**Interfaces:** produz `CheckOutState` e `CheckOutDialog`, que a Task 3 liga à Recepção.

---

### Task 3: "Check-out" na Recepção, "Abrir conta" e a verificação ao vivo

**Objetivo:** o detalhe da reserva na Recepção (painel do dia, inclusive a lista "Saídas") ganha "Check-out" (`DetailActions.onCheckOut`, Global Constraints), que abre o assistente com a porta, o `FolioWiring` da área (ou `null`) e um `onDone` que relê o painel do dia e o detalhe; o `AppRoot` monta a porta pela `checkOutSource()`, e o assistente vive na `ReceptionScreen` no padrão `FolioDialogUi`/`CheckInUi`. "Abrir conta" em cada pendência abre o `ReservationFolioDialog` da reserva por cima do assistente (Interfaces), e o `onWritten` e o fechamento relêem o assistente. Uma verificação ao vivo, `LiveCheckOutCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- Teste de UI (`ReceptionCheckOutTest`, novo): "Check-out" só aparece com `frontdesk.stays.checkout` e a reserva `CHECKED_IN`; abre o assistente; "Abrir conta" abre a conta e, ao liberar a caução e fechar, o assistente relido destrava "Concluir" (critério 2); o sucesso fecha o assistente, mostra "Check-out concluído." e relê o painel do dia (a reserva sai de "Hospedados" na porta falsa); trocar a reserva do detalhe fecha o assistente.
- **Verificação ao vivo pronta:** `app/src/test/.../reception/LiveCheckOutCheck.kt`, pulada sem `ZENY_LIVE_CHECKOUT=1` (opt-in no `app/build.gradle.kts`, no padrão dos outros), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`), pela `CoreConnection` e pelos estados reais; credenciais só do ambiente, nunca impressas; nenhuma resposta do `sdk` impressa nem concatenada (as travas da E03/E09 são a referência). Numa saída do dia do seed com conta: (1) abre o turno no ponto "Recepção" se a Recepcionista não tem; (2) com uma caução capturável (registra uma de R$ 50,00 se não há), "Concluir" fica desabilitado; libera a caução e destrava (critério 2); (3) se a cotação oferece late, liga a caixa; conclui, recebe o devido de cada conta pelo assistente (com o valor do servidor), conclui de novo com outra chave, e exige o sucesso; com late, exige na conta relida um lançamento com o valor do late (critério 4); (4) com o mapa de UHs assinado em paralelo (o estado real do mapa, como a verificação ao vivo do M1), exige a UH da reserva Vaga e Suja em até 2 s depois do sucesso (critério 1); (5) a reserva não está mais em "Hospedados" no painel do dia relido. Escreve na demo e não desfaz (KDoc e handoff). Sem saída do dia com conta, falha com `NEED_SEED`.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.reception.*' --tests 'com.zeny.desktop.app.AppRoot*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest` e os três greps da Task 2 sem linha.

**Áreas tocadas:** `app/.../reception/ReservationDetailPanel.kt` (`onCheckOut`), `ReceptionScreen.kt` (o assistente e o "Abrir conta"), `CheckOutDialog.kt`; `app/.../AppRoot.kt`; `app/build.gradle.kts` (o opt-in); testes de `reception/`.

**Tamanho:** ~55 min; 4 arquivos de produção, os três trechos do opt-in e um check ao vivo.

**Depende de:** Task 2.

**Interfaces:** consome as das Tasks 1 e 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3.

## Fora de escopo, por decisão do plano

- `POST /api/v1/folios/{id}/close`: o check-out do servidor encerra as contas na mesma transação; encerrar à parte deixaria contas encerradas numa reserva ainda hospedada se o check-out falhasse.
- `POST /api/v1/folios/{id}/reopen` (reabrir conta encerrada, com aprovação): nenhum critério da E04 a usa, e é ação da conta com aprovação como os ajustes; vai para a E08 (registrado no roadmap).
- O early check-in da cotação: é do check-in (M2).
- A duplicação entre o `DayPanelState` e o `ReservationSearchState` (antigo critério 5): E11, por I7 da revisão de arquitetura. Nota para ela (I8): o destino é o `reception/ReservationRows.kt` que já existe (o pacote `reservations` importa `reception`, não o contrário); as assinaturas recebem a leitura como função, porque as duas telas usam portas diferentes; os testes `ReservationRowsTest` e `ReservationRowsAtaqueTest` passam pela conferência do `C-M6`.
- Dívida (M3): o `FolioWrite` é usado em `reception`; mover para um pacote comum quando o `PostingFormState` migrar (dívida da E09).
- Extrato impresso e FNRH do check-out (fora do escopo da etapa no roadmap).

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: "Check-out" sem `frontdesk.stays.checkout` e em reserva não hospedada; o problema `FOLIO_BALANCE_NOT_ZERO` com extensões malformadas, valores fora do formato e listas vazias; devido com conta credora e devedora; duplo clique e repetição com a mesma chave; o segundo envio com outra chave depois do `BalanceDue`; a cotação que muda e a que deixa de ser oferecida; check-out concorrente; troca de sessão com o assistente aberto; a trava da `LiveCheckOutCheck` (no padrão de `tests/test_live_preauth_ataque.sh`); falha das leituras de contas e de cauções; o devido recebido com o assistente fechado e reaberto; o perfil sem `billing.payments.receive`.

## Verificação ao vivo e critérios que dependem do servidor

Os critérios 1 (saída do seed até o saldo zero, com a UH Vaga e Suja no mapa em até 2 s) e a parte ao vivo do 2 pedem o `zeny-front` e as credenciais do usuário. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo", como a E03 e a E09.
