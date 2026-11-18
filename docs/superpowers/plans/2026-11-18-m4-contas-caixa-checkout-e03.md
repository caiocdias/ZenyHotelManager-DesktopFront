# M4 E03 — Pagamentos — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** no painel da conta, a operadora recebe um pagamento manual (forma e valor) no turno de caixa aberto e reembolsa com aprovação; sem turno, a recusa vem em pt-BR com o atalho "Abrir turno"; o `MoneyField` e o `DateField` do `design` aceitam `enabled` e dão um sinal visível à colagem acima de 1000 caracteres (`A-M7`).

**Architecture:** uma fachada nova no `core`, `PaymentsFacade` (`ZenyCore.payments`), sobre a `BillingApi` já registrada em `Apis`, com a lista paginada pelo `CursorPager`, o recebimento idempotente e o reembolso como `ApprovableAction` pelo `ApiExecutor.approvable`. As regras de formato do recebimento e do reembolso (valor, troco, cartão, NSU e autorização) vivem uma vez no `core`, como `problems()` das entradas, e servem à fachada e aos estados. No `app`, uma porta nova `PaymentSource` (a E09 a amplia com as cauções), que o `FolioWiring` leva às três entradas da conta, junto da aprovação do reembolso e do atalho "Abrir turno". O painel da conta (`FolioState`/`FolioScreen`) ganha a seção "Pagamentos", "Receber" e "Reembolsar". O front nunca calcula saldo, troco nem reembolsável: mostra o que o servidor devolve.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E03 — Pagamentos" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` (depois da divisão de 2026-11-18), com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E07 e da E02;
- o contrato `api/openapi.yaml`: `listPayments`, `receivePayment`, `refundPayment` e os esquemas `ReceivePaymentRequest`, `RefundPaymentRequest`, `PaymentResponse`, `CursorPagePaymentResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 (fronteira `core`/`sdk`) e 0013 (fonte única de permissões).

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** cálculo de valor monetário (valor, valor entregue, troco, reembolsado e reembolsável, só exibidos ou copiados do servidor); concorrência (recebimento e reembolso idempotentes repetidos depois de falha, duplo clique); autenticação ou autorização (ações pelo conjunto da casca, reembolso como ação sensível com aprovação de superior); dado pessoal ou segredo (bandeira, últimos dígitos, NSU e autorização do cartão; a verificação ao vivo lê senhas do ambiente e nunca as imprime); parsing de entrada externa não confiável (valores e códigos digitados, a colagem longa nos campos, decimais e enums extensíveis do servidor).

## Global Constraints

- **Dinheiro:** sempre `Money` do `core` e string decimal na API (`^-?\d+(\.\d{1,2})?$`), nunca `Double`. O front nunca calcula saldo, troco, reembolsado nem reembolsável: mostra o que o servidor devolve, formatado em BRL pelo `MoneyInput.format`. O valor sugerido no recebimento é o saldo da conta lido do servidor (só quando positivo), e no reembolso é o `refundableAmount` do pagamento, copiados sem conta.
- **Cartão (PCI-DSS):** nenhum campo de número de cartão nem de CVV. No cartão (`DEBIT_CARD`, `CREDIT_CARD`), só a bandeira (letras e espaços, até 20) e os 4 últimos dígitos (`^[0-9]{4}$`), ambos opcionais. NSU e autorização: até 20 letras e dígitos, no máximo 12 dígitos, e não existem no dinheiro. Uma sequência que parece número de cartão (mais de 12 dígitos) é recusada antes da rede, com texto em pt-BR, e nunca vai ao servidor. Nenhum desses valores aparece em log, mensagem de exceção ou `toString`.
- **Formas oferecidas:** `CASH` "Dinheiro", `DEBIT_CARD` "Cartão de débito", `CREDIT_CARD` "Cartão de crédito", `PIX` "Pix", `BANK_TRANSFER` "Transferência", `OTHER` "Outra". `CITY_LEDGER` não é oferecido (o servidor responde `PAYMENT_METHOD_NOT_AVAILABLE`). Na leitura, `method` e `type` são `x-extensible-enum`: valor desconhecido aparece como "Outro (VALOR)" pelo `ExtensibleEnumLabels`; `type` `PAYMENT` "Recebimento", `REFUND` "Reembolso".
- **Idempotência:** receber e reembolsar levam `Idempotency-Key` por intenção (`Intent` do `core`). Repetir depois de erro de rede reaproveita a chave; nenhum duplo clique gera dois pagamentos. A falha é classificada por `classifyWriteFailure` (E01): fora do `DEFINITIVE` a intenção fica; os ramos por `code` só rodam com `outcome == WriteFailure.DEFINITIVE`; `UNKNOWN_STORED` dá `closeOnly`. Nenhum estado copia a regra (`grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` continuam sem linha). Os estados novos implementam o `FolioWriteForm` existente.
- **Turno:** `CASHIER_SHIFT_REQUIRED` (definitivo) mostra o texto do `ErrorCatalog` ("É preciso um turno de caixa aberto.") e, quando o `FolioWiring` tem `openShift`, o botão "Abrir turno" (`testTag("open-shift")`), que fecha o diálogo e chama `openShift`. Sem `billing.cashier.operate` no conjunto, o `AppRoot` não dá `openShift`, e só o texto aparece.
- **Aprovação:** o reembolso usa o `ApiExecutor.approvable` (`core/.../facade/Approval.kt`) na fachada e, no `app`, a lambda `approveRefund` do `FolioWiring`, montada no `AppRoot` com `{ approveAction(approval, it) }`, como o `approve` do estorno; não monte `ApprovableAction` em linha nem chame o coordenador de dentro do estado.
- **Permissões (ADR 0013):** tudo pelo conjunto da casca que o `FolioWiring` leva. Ver os pagamentos: `billing.folios.read` (já exigido pelo wiring). "Receber": `billing.payments.receive`. "Reembolsar" aparece com `billing.payments.receive` ou `billing.payments.refund` (o servidor pede a aprovação a quem não tem a segunda ou passa do limite). Com a conta encerrada (`status == CLOSED`), nem "Receber" nem "Reembolsar" aparecem; o servidor continua decidindo (`FOLIO_CLOSED`).
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`; tipos de modelo do `sdk` nas portas seguem o padrão das portas existentes (`PaymentResponse`).
- **Campos do `design` (`A-M7`):** limite de 1000 caracteres por edição no `MoneyField` e no `DateField`; o `DateField` ganha `enabled: Boolean = true` no fim da lista, como o `MoneyField`. Os outros campos do `design` não mudam.
- **Regras transversais (design §6.2):** nada de JSON, `code` cru nem UUID na tela; textos em pt-BR, código em inglês.
- **Cancelamento:** `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`; `ensureActive()` também depois do `approveRefund(...)`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`) quando a linha não é a primeira.
- **Arquivos do testador (`C-M6`):** antes de alterar um teste, confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS` com o arquivo, o teste, a saída e o comportamento esperado. Em `app/.../folio/`, hoje são do testador `FolioAtaqueTest.kt`, `FolioSessionAtaqueTest.kt` e `LiveFolioCheck.kt`: os campos novos do `FolioWiring` têm padrão justamente para eles compilarem sem mudança.
- **Verificação da tarefa:** a linha `verificação rápida` do `CLAUDE.md` para cada módulo tocado (e os que usam o que mudou), com `:<módulo>:detektMain` e `:<módulo>:detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez; nenhum laço de espera.
- **Commits:** em pt-BR, no estilo do histórico, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo (os dois trailers juntos, sem linha em branco entre eles).

## Review Focus

1. **Duplo clique em "Receber"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleConfirmReceivesOnce` e `retryAfterNetworkKeepsTheKey` (Task 2).
2. **NSU ou autorização com cara de número de cartão** (13 a 19 dígitos, com ou sem espaços) é recusado antes da rede, com texto em pt-BR, e o valor não aparece em mensagem nem exceção. Testes: `cardLikeCodesNeverReachTheNetwork` (Task 1) e `cardLikeNsuIsRefusedWithText` (Task 2).
3. **Valor com vírgula, ponto de milhar ou mais de duas casas** não vira `Double` nem arredonda em silêncio. Teste: `amountGoesAsExactDecimal` (Task 2).
4. **Turno fechado entre abrir o diálogo e confirmar** (`422 CASHIER_SHIFT_REQUIRED`) mostra a mensagem e o atalho, destrava o formulário e descarta a intenção. Teste: `shiftRequiredShowsMessageAndShortcut` (Task 2).
5. **Reembolso acima do reembolsável** (`422 REFUND_EXCEEDS_REFUNDABLE`, inclusive depois da aprovação) mostra a mensagem em pt-BR, destrava e gera outra intenção no próximo envio. Teste: `refundOverRefundableShowsMessage` (Task 3).

## Interfaces

- `core/src/main/kotlin/com/zeny/desktop/core/facade/PaymentsFacade.kt`, exposto como `ZenyCore.payments`:
  - `enum class PaymentMethod { CASH, DEBIT_CARD, CREDIT_CARD, PIX, BANK_TRANSFER, OTHER }` (sem `CITY_LEDGER`);
  - `data class PaymentInput(val method: PaymentMethod, val amount: Money, val deposit: Boolean = false, val tendered: Money? = null, val cardBrand: String? = null, val cardLast4: String? = null, val nsu: String? = null, val authorizationCode: String? = null)` com `toString()` que não mostra cartão, NSU nem autorização;
  - `data class RefundInput(val amount: Money, val reason: String, val nsu: String? = null, val authorizationCode: String? = null)`, com o mesmo cuidado no `toString()`;
  - `enum class PaymentProblem { AMOUNT, TENDERED, CARD_BRAND, CARD_LAST4, NSU, AUTHORIZATION_CODE, REASON }`, `fun PaymentInput.problems(): Set<PaymentProblem>` e `fun RefundInput.problems(): Set<PaymentProblem>` — as regras das Global Constraints: valor maior que zero; `tendered` só no dinheiro e não menor que o valor; bandeira e últimos dígitos só no cartão, no formato; NSU e autorização fora do dinheiro, no formato; motivo do reembolso não vazio e até 200 caracteres. Texto em branco conta como ausente e não vai no pedido. O `RefundInput` não leva a forma: no reembolso, o `core` confere só o formato do NSU e da autorização, e a regra "não existem no dinheiro" fica no `RefundState` (`cashRefundSendsNoNsu`) e no servidor;
  - `suspend fun payments(folioId: String): List<PaymentResponse>` — todas as páginas pelo `CursorPager` (página de 200), na ordem do servidor;
  - `suspend fun receive(folioId: String, input: PaymentInput, intent: Intent): PaymentResponse`;
  - `fun refund(folioId: String, paymentId: String, input: RefundInput, intent: Intent): ApprovableAction<PaymentResponse>` — ids e `problems()` conferidos ao montar a ação.
  Entrada com `problems()` não vazio ou id que não é UUID lança `IllegalArgumentException` antes da rede, sem o valor na mensagem, como as fachadas existentes. Nenhuma cópia de cursor: só o `CursorPager`.
- `app/.../folio/PaymentSource.kt`: porta com as três operações da fachada; `ServerConnection.paymentSource(): PaymentSource? = null` (implementada em `CoreConnection`).
- `FolioWiring` ganha três parâmetros no fim, com padrão (Task 2):
  ```kotlin
  val payments: PaymentSource? = null,
  val approveRefund: suspend (ApprovableAction<PaymentResponse>) -> ApiResponse<PaymentResponse>? = { null },
  val openShift: (() -> Unit)? = null,
  ```
  O padrão de `approveRefund` nunca roda a ação (equivale a "aprovação cancelada"): uma entrada sem o coordenador não reembolsa por engano. É padrão de produção que existe só para os arquivos do testador compilarem; o handoff o registra como dívida, junto do `T3 M1` da E02. O KDoc da classe ("Nenhuma entrada fica sem coordenador de aprovação") passa a dizer isso.
- `AppRoot`: a assinatura final é `internal fun folioWiring(connection: ServerConnection, permissions: Set<String>, scope: CoroutineScope, approval: ApprovalCoordinator?, window: Frame?, openShift: (() -> Unit)? = null): FolioWiring?` — o parâmetro novo no fim e com padrão, porque `FolioSessionAtaqueTest.kt` (do testador) e `FolioWiringTest.kt` a chamam com cinco argumentos posicionais. Ela passa `payments = connection.paymentSource()`, `approveRefund = { approveAction(approval, it) }` e `openShift` só com `billing.cashier.operate` no conjunto: **a decisão pelo conjunto mora nesta função**, não no composable.
- **Atalho "Abrir turno"** (Task 2): `AreaContent(..., onOpenShift: () -> Unit = {})`, que o `AppRoot` liga a `{ state.select(Area.ACCOUNTS) }`. Na Recepção e nas Reservas, o `openShift` do wiring é esse; na área "Contas e caixa", a aba escolhida sobe para a `CashierArea` (lembrada com o `sessionId`) e o `openShift` troca para a aba "Caixa". O `rememberFolioWiring` recebe o `openShift` e o lê por `rememberUpdatedState`, para a chave do wiring continuar `remember(source, sessionId, permissions)`. **Quem tira o diálogo é o `FolioState`:** o `PaymentFormState` e o `RefundState` recebem do `FolioState` um `openShift` que primeiro fecha o diálogo (`finishPayment()`/`finishRefund()`) e depois chama o do wiring; assim, voltando à aba "Contas", não há diálogo morto.
- `FolioState` (Task 2): `var payments: List<PaymentRow>`, `var paymentsError: String?`, `val canReceive: Boolean`, `fun startPayment()`, `var payment: PaymentFormState?`, `fun finishPayment()`; os pagamentos são relidos junto da conta em `reload()` quando `wiring.payments != null`, e a falha deles mostra "Não foi possível ler os pagamentos." sem esconder a conta. `data class PaymentRow(val id: String, val date: String, val type: String, val method: String, val methodCode: PaymentMethod?, val card: String?, val amount: String, val refunded: String?, val refundable: Money?, val deposit: Boolean)` (o `toString()` é sobrescrito e não mostra `card`; `method` é o rótulo; `methodCode` é a forma reconhecida, nula quando desconhecida; `card` é "<bandeira> final <últimos>" quando vem; `refundable` só num `PAYMENT` com `refundableAmount` maior que zero).
- `PaymentFormState(source: PaymentSource, scope: CoroutineScope, folioId: String, suggested: Money?, openShift: (() -> Unit)?, onChanged: (PaymentResponse) -> Unit, onFolioClosed: () -> Unit) : FolioWriteForm` (Task 2), com `val locked: Boolean`, `val shiftRequired: Boolean` e `fun openShiftAndClose()`.
- Task 3: `fun FolioState.canRefund(row: PaymentRow): Boolean`, `fun startRefund(row: PaymentRow)`, `var refund: RefundState?`; `RefundState(source: PaymentSource, scope: CoroutineScope, folioId: String, payment: PaymentRow, approve: suspend (ApprovableAction<PaymentResponse>) -> ApiResponse<PaymentResponse>?, openShift: (() -> Unit)?, onChanged: () -> Unit, onFolioClosed: () -> Unit) : FolioWriteForm`.

## Revisão de arquitetura

(preenchida depois da revisão)

---

### Task 1: Os campos do `design` (`A-M7`), a fachada dos pagamentos e a porta

**Objetivo:** no `design`, o `DateField` ganha `enabled` (o `MoneyField` já tem, `533f98d`), e os dois campos ganham o limite de 1000 caracteres por edição: uma edição que deixaria o texto com mais de 1000 caracteres não é aplicada (o texto anterior fica), e o campo mostra na hora, como erro, "Texto colado com mais de 1000 caracteres. Ele não foi aceito." até a próxima edição aceita. O sinal tem prioridade sobre o erro do formulário e o de formato. O limite vem por um campo novo do `FieldSpec` (`maxLength: Int? = null`), aplicado só no ramo sem máscara do `ParsedTextField` (o único que o `MoneyField` e o `DateField` usam); o `FieldSpec` recusa a combinação (`require(mask == null || maxLength == null)`), e os campos com máscara não mudam. No `core`, criar `PaymentsFacade` (Interfaces), registrar `ZenyCore.payments`; no `app`, criar a porta `PaymentSource` e a implementação em `CoreConnection`. Os `code` desta etapa (`CASHIER_SHIFT_REQUIRED`, `FOLIO_NOT_FOUND`, `FOLIO_CLOSED`, `PAYMENT_INVALID`, `PAYMENT_METHOD_NOT_AVAILABLE`, `PAYMENT_NOT_FOUND`, `PAYMENT_NOT_REFUNDABLE`, `REFUND_EXCEEDS_REFUNDABLE`) já têm texto em `ErrorCatalog.kt`; o catálogo não muda.

**Critérios de aceite:**
- `FieldLimitsTest` (novo, no `design`, com o `runComposeUiTest` dos testes de campo existentes): `disabledMoneyFieldRefusesTypingAndPaste` e `disabledDateFieldRefusesTypingAndPaste` (`enabled = false`: `assertIsNotEnabled()` e o valor não muda); `pasteOverLimitShowsSignalAndKeepsText` (nos dois campos: 1001 caracteres não chegam ao `onValueChange`, o texto anterior fica, o sinal aparece sem perder o foco; a próxima edição válida o tira); `pasteAtLimitIsAccepted` (1000 caracteres chegam ao `onValueChange`). Os testes de campo existentes continuam verdes. Verifica: `:design:test --tests 'com.zeny.desktop.design.input.*'` e o trio do `design`, com o filtro do `app` para as telas que usam os campos: `:app:test --tests 'com.zeny.desktop.app.reservations.*' --tests 'com.zeny.desktop.app.cashier.*' --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.traces.*'`.
- `PaymentsFacadeTest` (novo, `MockEngine` como as outras fachadas): `paymentsReadAllPages` (duas páginas); `receiveSendsExactDecimalsAndIdempotencyKey` (`Money.parse("12.50")` vai como `"12.50"`, `method` `CASH`, `deposit` falso, `tendered` `"20.00"`, sem campos de cartão, com o header `Idempotency-Key`); `receiveCardSendsOnlyBrandLast4NsuAndAuthorization` (`CREDIT_CARD`, bandeira, `"1234"`, NSU e autorização no corpo; nenhum outro campo de cartão); `sameIntentKeepsTheKey`; `refundAsksApprovalThenRetriesWithTokenAndNewKey` (`403 APPROVAL_REQUIRED` vira `ApiError.ApprovalRequired`; `run(token)` leva `X-Approval-Token` e outra chave); `cardLikeCodesNeverReachTheNetwork` (NSU e autorização com 13 e 19 dígitos: `IllegalArgumentException` sem o valor na mensagem, nenhuma requisição); `problemsFollowTheContract` (uma tabela: valor 0 e negativo, `tendered` fora do dinheiro e menor que o valor, bandeira com dígito e com 21 letras, últimos com 3 e 5 dígitos, cartão no Pix, NSU no dinheiro, 21 caracteres, 12 dígitos aceitos, motivo vazio e com 201 caracteres, texto em branco ausente); `toStringHidesCardData`; `nonUuidIdFailsBeforeTheNetwork`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.PaymentsFacadeTest'` e o trio do `core` (a mudança no `core` é aditiva, sem tipo alterado: o `demo-seed` não usa a fachada nova e fica fora da verificação da tarefa; o gate o cobre).
- `CoreConnection.paymentSource()` devolve a porta ligada à fachada; as conexões falsas continuam compilando pelo padrão `null`. Verifica: `:app:test --tests 'com.zeny.desktop.app.session.*'` e o trio do `app`.

**Áreas tocadas:** `design/.../input/` (`InputResult.kt`, `MoneyInput.kt`, `DateInput.kt`); `core/.../facade/PaymentsFacade.kt` (novo), `core/.../ZenyCore.kt`; `app/.../folio/PaymentSource.kt` (novo), `app/.../session/ServerConnection.kt`; testes do `design` e do `core`.

**Tamanho:** ~55 min; 7 arquivos de produção (2 novos).

**Depende de:** nenhuma.

**Interfaces:** produz `ZenyCore.payments`, `PaymentMethod`, `PaymentInput`, `RefundInput`, `PaymentProblem`, os dois `problems()`, `PaymentSource`, `ServerConnection.paymentSource()` e o `DateField(enabled)`, que as Tasks 2 e 3 consomem.

---

### Task 2: Receber, a lista de pagamentos e o atalho "Abrir turno"

**Objetivo:** o painel da conta ganha a seção "Pagamentos" (só com `wiring.payments`): uma tabela na ordem do servidor com data operacional, tipo, forma (com "<bandeira> final <últimos>" quando vem), valor, "Depósito" quando `deposit`, e "Reembolsado <valor>" quando `refundedAmount` é maior que zero. "Receber" (`testTag("receive-payment")`, com `canReceive`) abre um diálogo com a forma (as seis das Global Constraints, padrão "Dinheiro"), o valor (`MoneyField`, sugerido com o saldo da conta quando positivo), "Depósito (sinal)" (padrão desligado), o valor entregue (só no dinheiro, opcional), a bandeira e os 4 últimos dígitos (só no cartão, opcionais) e o NSU e a autorização (fora do dinheiro, opcionais). Os erros de formato vêm de `PaymentInput.problems()`, cada um com um texto em pt-BR ao lado do campo, e nenhum envio com problema. Os campos ficam `enabled = false` com `locked` (em voo, resultado desconhecido, `closeOnly`). `UNKNOWN_STORED` dá `closeOnly` com "O servidor não confirmou o recebimento. Feche e confira a conta antes de receber de novo.", e a releitura da conta é o "confira". Sucesso: o diálogo fecha, a conta e os pagamentos são relidos (o saldo vem do servidor), a lista de contas é avisada pelo `onWritten` e o painel mostra "Pagamento registrado." mais " Troco: <change>." quando o servidor devolve troco maior que zero. `FOLIO_CLOSED` mostra a mensagem e relê a conta. `CASHIER_SHIFT_REQUIRED` segue as Global Constraints ("Turno"), e o atalho funciona nas três entradas. O `FolioWiring` ganha os três parâmetros (Interfaces), e o `AppRoot` os monta completos, inclusive o `approveRefund`, que a Task 3 só consome.

**Critérios de aceite:**
- `PaymentFormStateTest` (novo, porta falsa): `doubleConfirmReceivesOnce`; `retryAfterNetworkKeepsTheKey`; `amountGoesAsExactDecimal` (`"1.234,56"` → `Money.parse("1234.56")`); `suggestedAmountIsTheServerBalance` (o saldo positivo da conta aparece formatado; saldo zero ou negativo deixa o campo vazio); `cashHidesCardFieldsAndCardHidesTendered` (o pedido de dinheiro não leva cartão, NSU nem autorização, mesmo que tenham sido digitados antes da troca da forma); `cardLikeNsuIsRefusedWithText`; `formOffersNoCardNumberField` (o estado não expõe nenhuma propriedade de número de cartão nem CVV: a lista de propriedades públicas, por reflexão, contém só as previstas); `shiftRequiredShowsMessageAndShortcut` (o texto do catálogo, `shiftRequired`, `openShiftAndClose()` chama o `openShift`; sem `openShift`, só o texto); `fieldsLockWhileBusyAndUnknown` (`locked` com o envio em voo e com o resultado desconhecido); `storedFailureIsCloseOnly`; `closedFolioShowsMessageAndReloads`; `successReloadsAndShowsChange`.
- `FolioStateTest`/novo `FolioPaymentsStateTest`: `paymentsLoadWithTheFolio`; `paymentsFailureKeepsTheFolio`; `unknownMethodAndTypeShowAsOther`; `receiveNeedsPermissionAndOpenFolio`.
- `FolioWiringTest`: `wiringCarriesPaymentsAndApproval` (o `folioWiring(...)` do `AppRoot` leva a porta de pagamentos e um `approveRefund` que passa por um coordenador real, que o teste distingue do padrão `{ null }`: a ação roda e o coordenador a viu); `openShiftNeedsCashierOperate`.
- Teste de UI (`FolioScreenTest` ou um `FolioPaymentsScreenTest` novo): "Receber" só com `billing.payments.receive` e conta aberta; o pagamento novo aparece na tabela depois de confirmar; com `CASHIER_SHIFT_REQUIRED`, "Abrir turno" na aba "Contas" leva à aba "Caixa" e, voltando à aba "Contas", não há diálogo aberto; em "Conta" na Recepção leva à área "Contas e caixa".
- Os testes que montam o `AppRoot`, a Recepção, as Reservas, o caixa e a conta continuam verdes; quebra em arquivo do testador segue a regra do `C-M6`. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.ShellRoot*' --tests 'com.zeny.desktop.app.cashier.*' --tests 'com.zeny.desktop.app.reception.*' --tests 'com.zeny.desktop.app.reservations.*'` e o trio do `app`; o grep da E01 continua sem linha.

**Áreas tocadas:** `app/.../folio/` (novos: `PaymentFormState.kt`, `PaymentDialogs.kt`; `FolioState.kt`, `FolioScreen.kt`, `FolioText.kt`, `FolioWiring.kt`), `app/.../AppRoot.kt`; testes de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção (2 novos). Corte pré-autorizado se passar de 60 min: a parte do atalho que mexe no `AppRoot` (`AreaContent.onOpenShift`, a subida da aba e os testes de navegação pela casca) passa à Task 3, e a Task 2 fica com `shiftRequired`, o texto e o parâmetro; o corte vai ao ledger como ruling.

**Depende de:** Task 1 (`PaymentSource`, `PaymentInput`, `problems()`).

**Interfaces:** produz os campos novos do `FolioWiring`, `PaymentRow`, `FolioState.payments`, `PaymentFormState` e o atalho (bloco Interfaces), que a Task 3 consome.

---

### Task 3: Reembolsar e a verificação ao vivo

**Objetivo:** em cada linha `PAYMENT` com `refundable`, o botão "Reembolsar" (`testTag("refund-<n>")`, pela posição; `canRefund`) abre um diálogo com o valor (`MoneyField`, sugerido com o reembolsável do servidor e mostrado como "Reembolsável: <valor>"), o motivo (obrigatório, até 200 caracteres; a edição que passaria do limite é recusada, como no estorno) e, fora do dinheiro, o NSU e a autorização da devolução. A forma do pagamento vem da linha (`PaymentRow.methodCode`; forma desconhecida esconde NSU e autorização e deixa o servidor decidir). O reembolso passa pelo `approveRefund` do wiring; a aprovação cancelada deixa "Aprovação cancelada. O reembolso não foi feito." e destrava. `UNKNOWN_STORED` dá `closeOnly` com "O servidor não confirmou o reembolso. Feche e confira a conta antes de refazer.". `CASHIER_SHIFT_REQUIRED` e `FOLIO_CLOSED` como no recebimento. Sucesso: fecha, relê a conta e os pagamentos, avisa a lista e mostra "Reembolso registrado.". Uma verificação ao vivo, `LivePaymentCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `RefundStateTest` (novo): `refundNeedsReason`; `amountSuggestedIsTheRefundable`; `approvalRequiredGoesThroughApprove` (a ação passada ao `approve` é a da porta, com a mesma `Intent`); `cancelledApprovalLeavesTextAndUnlocks`; `retryAfterNetworkKeepsTheKey`; `storedFailureIsCloseOnly`; `refundOverRefundableShowsMessage`; `cashRefundSendsNoNsu`; `successReloadsTheFolio`.
- Teste de UI: "Reembolsar" só em recebimento com reembolsável, com uma das duas permissões e conta aberta; não aparece em reembolso; **o reembolso com `APPROVAL_REQUIRED` abre o diálogo de aprovação da casca a partir da aba "Contas" e a partir de "Conta" na Recepção** (prova que as entradas levam o coordenador).
- **Verificação ao vivo pronta:** `app/src/test/.../folio/LivePaymentCheck.kt`, pulada sem `ZENY_LIVE_PAYMENT=1` (o opt-in no `app/build.gradle.kts` segue o padrão dos outros: o `inputs.property`, o provider e a condição do `upToDateWhen`), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`), pela `CoreConnection` e pelos estados reais; credenciais só do ambiente, nunca impressas, com o comando em subshell no KDoc. Passos: (1) se a Recepcionista não tem turno, "Receber" numa conta aberta de reserva do seed mostra o texto de `CASHIER_SHIFT_REQUIRED` com `shiftRequired`, e o check abre o turno no ponto "Recepção" do seed com fundo zero; com turno aberto, segue com ele e registra no relatório que o passo 1 foi conferido só pelos testes; (2) se a conta escolhida não tem saldo positivo, lança um consumo de R$ 10,00 (como a `LiveFolioCheck`); (3) recebe o saldo em dinheiro e prova a deduplicação pela chave no padrão do `recordTwice` da `LiveCashierCheck`: a primeira resposta se perde (a porta do check a descarta depois de o servidor atender), o `confirm` de novo leva a mesma `Intent`, e o check exige um pagamento só a mais (critério 3; a guarda de duplo clique fica com `doubleConfirmReceivesOnce`); (4) exige o saldo relido `0.00` e a tela em "R$ 0,00", e o `cashierShiftId` do pagamento igual ao turno corrente (critério 2). Se depois do consumo a conta continuar sem saldo positivo, o check falha com `NEED_SEED`. Escreve na demo e não desfaz (o pagamento, o consumo quando lançado e o turno aberto; registrar no KDoc e no handoff).
- O grep da E01 continua sem linha. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*'` (o check ao vivo é pulado sem o opt-in, mas compila e passa no lint) e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novo: `RefundState.kt`; `PaymentDialogs.kt`, `FolioState.kt`, `FolioScreen.kt`, `FolioText.kt`), `app/build.gradle.kts` (o opt-in); testes de `folio/`.

**Tamanho:** ~55 min; 6 arquivos de produção (1 novo), os três trechos do opt-in e um check ao vivo (código de teste).

**Depende de:** Task 1 (`RefundInput`, `PaymentSource.refund`) e Task 2 (`PaymentRow`, `FolioState.payments`, o wiring).

**Interfaces:** consome as das Tasks 1 e 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3. Os campos do `design` não dependem dos pagamentos, mas são pequenos e cabem na Task 1, e as Tasks 2 e 3 escrevem nos mesmos arquivos de `folio/`.

## Fora de escopo, por decisão do plano

- As cauções e as cauções a vencer: E09.
- `GET /api/v1/folios/{folioId}/payments/{paymentId}`: a lista já traz cada pagamento inteiro.
- O reembolso na verificação ao vivo: ele desfaria o saldo zero do critério 2 e pede o Gerente; fica nos testes de estado e de UI.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: a trava contra credencial impressa da `LivePaymentCheck`, no padrão de `tests/test_live_folio_ataque.sh`; nenhum dado de cartão em log, mensagem ou `toString` (NSU e autorização com cara de número de cartão, inclusive com espaços e traços); valores de borda do valor e do valor entregue (0,00; 0,01; negativo; 1.000.000,00; mais de duas casas); duplo clique e repetição com a mesma chave no recebimento e no reembolso; aprovação cancelada e aprovação perdida no reembolso; `CASHIER_SHIFT_REQUIRED` em cada entrada e o atalho sem `billing.cashier.operate`; a colagem de 1000 e 1001 caracteres nos dois campos, com e sem foco; `method` e `type` desconhecidos; troca de sessão com o diálogo de pagamento aberto. Também os itens deixados pelo handoff da E02 ("Para o testador"): o mutante "nunca desligar `replacePending`", a trava de `test_live_folio_ataque.sh` com argumento não literal, a varredura de `.pdf` em `/tmp` do `FolioSessionAtaqueTest`, o título de `guestStatementWithoutInternalBlockShowsNoReason` e a troca de sessão com o diálogo "Conta" aberto nas Reservas e nas pendências.

## Verificação ao vivo e critérios que dependem do servidor

O critério "receber o saldo de uma conta do seed zera o saldo, e o pagamento aparece no turno" pede o `zeny-front` e as credenciais da folha de senhas, que ficam com o usuário. O orquestrador roda a `LivePaymentCheck` se as variáveis estiverem no ambiente da sessão; senão, a etapa registra no handoff o comando pronto e espera a execução do usuário antes de fechar `#concluida`.
