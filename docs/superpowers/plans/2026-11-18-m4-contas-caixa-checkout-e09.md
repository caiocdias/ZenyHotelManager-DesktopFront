# M4 E09 — Cauções — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** no painel da conta, a operadora registra uma caução com os dados permitidos (sem número de cartão), captura parte ou tudo no turno aberto e libera a sobra. As cauções a vencer passaram à E10 (I5 da revisão de arquitetura; divisão registrada no roadmap).

**Architecture:** a `PaymentsFacade` da E03 ganha as quatro operações de caução da conta, com as entradas e as regras de formato no `core` (`problems()`, como no recebimento), e a porta `PaymentSource` as expõe. Antes de nascerem três formulários novos, a maquinaria comum de escrita da conta (envio com a `Intent`, classificação D1, `closeOnly`, recusa por `code`, atalho do turno), hoje repetida entre `PaymentFormState` e `RefundState`, vira um ajudante único no `app`, que os cinco formulários usam. No painel, as cauções ganham um *holder* próprio (`PreAuthorizationsState`), criado pelo `FolioState`, e uma seção própria na tela, para o `FolioState.kt` (611 linhas) não crescer. O front nunca calcula valor: autorizado, capturado, capturável e liberado vêm do servidor.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E09 — Cauções" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E03, da E02 e da E07 (as dívidas "antes da E09" do handoff da E03);
- o contrato `api/openapi.yaml`: `listCardPreAuthorizations`, `registerCardPreAuthorization`, `captureCardPreAuthorization` e `releaseCardPreAuthorization` e os esquemas `RegisterPreAuthorizationRequest`, `CapturePreAuthorizationRequest`, `CardPreAuthorizationResponse`, `PreAuthorizationCaptureResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização (registrar, capturar e liberar pelo conjunto da casca, com `billing.payments.receive`; liberar também com a conta encerrada); cálculo de valor monetário (autorizado, capturado, capturável e liberado, só exibidos; o valor sugerido da captura é o capturável do servidor); concorrência (registro, captura e liberação idempotentes repetidos depois de falha, duplo clique; a caução pode vencer entre abrir o diálogo e confirmar); dado pessoal ou segredo (bandeira, últimos dígitos, NSU e autorização; a verificação ao vivo lê senhas do ambiente e nunca as imprime); parsing de entrada externa não confiável (valores, datas e códigos digitados; o `status` extensível e os decimais do servidor).

## Global Constraints

- **Dinheiro:** sempre `Money` do `core` e string decimal na API, nunca `Double`. O front nunca calcula autorizado, capturado, capturável, liberado nem saldo: mostra o que o servidor devolve, pelo `MoneyInput.format`. O valor sugerido da captura é o `capturableAmount` da caução, copiado sem conta.
- **Cartão (PCI-DSS):** nenhum campo de número de cartão nem de CVV. Na caução, só bandeira (letras e espaços, até 20, opcional), os 4 últimos dígitos (`^[0-9]{4}$`, opcional), NSU (opcional) e autorização (**obrigatória** no registro), com o formato da E03 (até 20 letras e dígitos, no máximo 12 dígitos). Sequência com cara de número de cartão é recusada antes da rede, com texto em pt-BR, e nenhum desses valores aparece em log, mensagem de exceção ou `toString` (o `CardPreAuthorizationResponse` e o `PreAuthorizationCaptureResponse` do `sdk` são data classes que imprimem o cartão no `toString`: nunca os logue nem interpole).
- **Validade:** o diálogo de registro pede a data de validade (`DateField`, `dd/mm/aaaa`), a data que a operadora lê no comprovante ("válida até"). O `expiresAt` enviado é o **fim desse dia** (23:59:59) no fuso do hotel, em UTC, e valem as datas de hoje até 89 dias depois de hoje no fuso do hotel ("hoje" pelo `Clock` do *holder*): assim o instante fica sempre depois de agora e dentro de 90 dias, e a caução vence no mesmo dia que o comprovante diz. Fora do intervalo: "A validade vai de hoje até 89 dias." Alternativas descartadas (registradas como decisão para o usuário no handoff): o início do dia (00:00), que venceria a caução no primeiro instante do dia do comprovante, e data com hora, que pede um campo que o app não tem. Num fuso com horário de verão, a borda de 89 dias pode ficar uma hora acima de 90×24 h; o servidor recusa com `PRE_AUTHORIZATION_INVALID` (texto do catálogo). Sem o fuso (leitura falhou ou o servidor não mandou), o diálogo diz "Não foi possível ler o fuso do hotel. Feche e abra de novo." e não confirma; o `startRegister()` chama o `reload()` quando o fuso está ausente, então reabrir o diálogo tenta de novo.
- **Situação (`status`, `x-extensible-enum`):** `ACTIVE` "Ativa", `PARTIALLY_CAPTURED` "Capturada em parte", `CAPTURED` "Capturada", `RELEASED` "Liberada", `EXPIRED` "Vencida"; desconhecido, "Outro (VALOR)" pelo `ExtensibleEnumLabels`.
- **Textos do resultado desconhecido:** por rede, "Não foi possível confirmar o registro da caução. Registre de novo sem mudar nada; se fechar, confira a conta antes de registrar de novo.", "Não foi possível confirmar a captura. Capture de novo sem mudar nada; se fechar, confira a conta antes de capturar de novo." e "Não foi possível confirmar a liberação. Libere de novo; se fechar, confira a conta antes de liberar de novo."; guardado (`UNKNOWN_STORED`), os textos das Tasks 2 e 3.
- **Idempotência e D1:** registrar, capturar e liberar levam `Idempotency-Key` por intenção. Repetir depois de erro de rede reaproveita a chave; nenhum duplo clique gera duas escritas. A falha é classificada por `classifyWriteFailure`; os ramos por `code` só rodam com `DEFINITIVE`; `UNKNOWN_STORED` dá `closeOnly`. Os greps da E01 continuam sem linha. Os formulários implementam o `FolioWriteForm` e usam o ajudante comum da Task 1; nenhum copia a maquinaria. Alcance: o `PostingFormState` e o `ReversalState` (E02) também têm `send`/`fail` próprios e ficam fora desta etapa (dívida no handoff).
- **Turno:** a captura exige turno (`CASHIER_SHIFT_REQUIRED`): o texto do catálogo e, com `openShift`, "Abrir turno" (`offersOpenShift`, da E03, e o `closeThenOpenShift` próprio do *holder*). Registrar e liberar não exigem turno.
- **Permissões (ADR 0013):** pelo conjunto da casca no `FolioWiring`. Ver as cauções: `billing.folios.read`. Registrar, capturar e liberar: `billing.payments.receive`. Registrar e capturar só com a conta aberta; **liberar aparece também com a conta encerrada** (o contrato aceita) e em caução vencida. Capturar só em `ACTIVE` ou `PARTIALLY_CAPTURED` com capturável maior que zero; liberar em `ACTIVE`, `PARTIALLY_CAPTURED` ou `EXPIRED`. O servidor continua decidindo (`PRE_AUTHORIZATION_SETTLED`, `PRE_AUTHORIZATION_EXPIRED`, `CAPTURE_EXCEEDS_AUTHORIZED`, `FOLIO_CLOSED`, todos com texto no `ErrorCatalog`).
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Regras transversais (design §6.2):** nada de JSON, `code` cru nem UUID na tela; pt-BR na tela, inglês no código.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Em `app/.../folio/` são do testador, entre outros, `FolioAtaqueTest.kt`, `FolioSessionAtaqueTest.kt`, `LiveFolioCheck.kt`, `PaymentAtaqueTest.kt`, `PaymentDialogsAtaqueTest.kt`, `PaymentShellAtaqueTest.kt`, `FolioPaymentsScreenAtaqueTest.kt`; no `core`, `PaymentsFacadeAtaqueTest.kt`.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (e os que usam o que mudou), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Duplo clique em "Registrar caução"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleConfirmRegistersOnce`, `retryAfterNetworkKeepsTheKey` (Task 2).
2. **Validade no limite** (ontem, hoje, 89 e 90 dias, virada do dia no fuso do hotel diferente do da máquina) dá o `expiresAt` certo (23:59:59 do dia no fuso do hotel) ou a recusa com texto, sem ir à rede. Teste: `validityWindowFollowsTheHotelZone` (Task 2).
3. **Caução que vence entre abrir o diálogo e capturar** (`422 PRE_AUTHORIZATION_EXPIRED`) mostra o texto, relê as cauções e destrava. Teste: `expiredBetweenOpenAndConfirmReloads` (Task 3).
4. **Liberar com a conta encerrada** aparece e funciona; registrar e capturar não aparecem. Teste: `releaseStaysAvailableOnAClosedFolio` (Task 2).
5. **O ajudante comum não muda o recebimento nem o reembolso:** os testes da E03, inclusive os do testador, continuam verdes sem alteração (Task 1).

## Interfaces

- `core/.../facade/PaymentsFacade.kt` (Task 1), mais:
  - `data class PreAuthorizationInput(val amount: Money, val expiresAt: Instant, val authorizationCode: String, val nsu: String? = null, val cardBrand: String? = null, val cardLast4: String? = null)` e `data class CaptureInput(val amount: Money, val nsu: String? = null, val authorizationCode: String? = null)`, com `toString()` sem cartão, NSU nem autorização;
  - `fun PreAuthorizationInput.problems(): Set<PaymentProblem>` (valor maior que zero; autorização presente e no formato; NSU, bandeira e últimos no formato) e `fun CaptureInput.problems(): Set<PaymentProblem>` (valor maior que zero; NSU e autorização no formato). O `expiresAt` é conferido no estado (precisa do fuso); a fachada só exige que ele exista;
  - `suspend fun preAuthorizations(folioId: String): List<CardPreAuthorizationResponse>` (todas as páginas, `CursorPager`, 200);
  - `suspend fun registerPreAuthorization(folioId: String, input: PreAuthorizationInput, intent: Intent): CardPreAuthorizationResponse`;
  - `suspend fun capturePreAuthorization(folioId: String, preAuthorizationId: String, input: CaptureInput, intent: Intent): PreAuthorizationCaptureResponse`;
  - `suspend fun releasePreAuthorization(folioId: String, preAuthorizationId: String, intent: Intent): CardPreAuthorizationResponse`.
  Entrada com `problems()` ou id inválido lança `IllegalArgumentException` antes da rede, sem o valor.
- `PaymentSource` ganha as quatro operações e `suspend fun businessDay(): BusinessDay` (`core.hotel.businessDay()` na `CoreConnection`). Os falsos dos testes ganham as operações; `FakePaymentSource.kt` é do desenvolvedor.
- **Ajudante comum** (Task 1), em `app/.../folio/FolioWrite.kt`. O nome é do implementador (registrado no relatório e no KDoc), mas o contrato mínimo é este:
  - **composição:** o formulário guarda uma instância do ajudante e delega a ele as propriedades do `FolioWriteForm` (`busy`, `unconfirmed`, `closeOnly`, `message`) e o `offersOpenShift`; o formulário continua dono dos campos, das validações (`errors`) e do próprio `close()`, que fecha o ajudante;
  - **construtor:** o escopo, os textos de "desconhecido por rede" e de "guardado" da escrita, o `openShift: (() -> Unit)?` e um `onRefused: (code: String) -> Unit` genérico, chamado só com resposta `DEFINITIVE` e depois do texto do `ErrorCatalog` (é o gancho de `FOLIO_CLOSED`, `PRE_AUTHORIZATION_SETTLED`, `PRE_AUTHORIZATION_EXPIRED` e `CAPTURE_EXCEEDS_AUTHORIZED`); o `CASHIER_SHIFT_REQUIRED` liga o `offersOpenShift` quando há `openShift`;
  - **envio:** recebe `suspend (Intent) -> Outcome<T>`, em que o desfecho é sucesso (com o valor), ou **cancelado** (destrava, mantém a intenção e mostra o texto dado: é o "aprovação cancelada" do reembolso); a falha vem pela exceção e passa por `classifyWriteFailure` (fora do `DEFINITIVE`, a intenção fica; `UNKNOWN_STORED`, `closeOnly`; `DEFINITIVE`, gira a intenção);
  - **edição:** `edited()` descarta a intenção só quando nada está travado (a regra do `RefundState.edit`); o `PaymentFormState` passa a chamá-la também (equivalente hoje, porque lá a intenção só existe com as entradas travadas);
  - **fechamento:** o ajudante tem o `SupervisorJob` e o `closed`; depois do `close()`, nenhum envio roda e nenhuma resposta tardia muda o estado.
  `PaymentFormState` e `RefundState` passam a usá-lo sem mudar a API pública nem o comportamento.
- `PreAuthorizationsState(wiring: FolioWiring, scope: CoroutineScope, folioId: String, folioOpen: () -> Boolean, otherDialogOpen: () -> Boolean, notify: (String) -> Unit, onChanged: () -> Unit, clock: Clock = Clock.systemUTC())` (Task 2), no arquivo novo dele, nunca no `FolioWiring` (os arquivos do testador constroem o wiring): `rows: List<PreAuthorizationRow>`, `loaded`, `error`, `val dialogOpen: Boolean`, `fun reload()`, `val canRegister: Boolean`, `fun startRegister()`, `var register: PreAuthorizationFormState?`, `fun finishRegister()`, `fun canRelease(row)`, `fun startRelease(row)`, `var release: ReleaseState?`, `fun finishRelease()`, `fun close()`. Cada `finish…` segue o `finishPayment` (bloqueado com `busy`; relê com `unconfirmed` ou `closeOnly`). O `reload()` lê as cauções e o `businessDay()` (uma leitura do fuso por releitura; a falha do fuso é tolerada: a validade cai para a data UTC com "(UTC)" e o registro mostra o texto de fuso desconhecido; reabrir o diálogo depois de um `reload()` o relê). O `FolioState` cria o *holder* quando `wiring.payments != null`, passa `folioOpen = { view?.closed == false }`, `otherDialogOpen = { posting != null || reversal != null || payment != null || refund != null }`, `notify = { notice = it }` e `onChanged` (relê a conta e os pagamentos), o relê junto da conta, o inclui no próprio `dialogOpen` e o fecha com ela; o construtor do `FolioState` não muda. O *holder* tem o próprio `closeThenOpenShift` (fecha o diálogo dele e depois chama `wiring.openShift`).
- `data class PreAuthorizationRow(val id: String, val status: String, val statusCode: String, val card: String?, val amount: String, val captured: String, val capturable: Money?, val released: String?, val expiresAt: String)` com `toString()` sem `card`; `capturable` só quando maior que zero; `expiresAt` como "dd/MM/aaaa HH:mm" no fuso do hotel (sem fuso, só a data UTC com "(UTC)").
- Task 3: `fun PreAuthorizationsState.canCapture(row)` (com `folioOpen()`), `fun startCapture(row)`, `var capture: CaptureState?`, `fun finishCapture()`.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 1 (rodada 0: bloqueado, 5 Importantes e 8 Menores; corrigidos em `f0d144e`, com a divisão da E10).
- **Revisor:** `revisor-arquitetura`, modelo fable.
- **Notas Menores:** N1, o corte pré-autorizado da Task 2 para a Task 3 ("Liberar"); N2, o `startRegister()` relê quando falta o fuso; N3, o `closeThenOpenShift` é o do *holder* (os três incorporados); N4, a nota de risco da E09 no roadmap tem três itens e o plano cinco (alinhar no handoff). Não há `ARCHITECTURE.md`.

---

### Task 1: As operações de caução no `core`, a porta e o ajudante comum de escrita

**Objetivo:** acrescentar à `PaymentsFacade` as entradas, as regras e as quatro operações de caução (Interfaces), e à `PaymentSource` as quatro mais o `businessDay()`, ligadas na `CoreConnection`. No `app`, extrair a maquinaria de escrita repetida entre `PaymentFormState` e `RefundState` (`send`, `fail`, os campos de estado, o descarte da intenção) para o ajudante comum em `FolioWrite.kt`, com o contrato mínimo das Interfaces, e fazer os dois estados o usarem, sem mudar a API pública nem o comportamento. A consolidação das constantes repetidas fica no handoff (nenhum critério da E09 a pede).

**Critérios de aceite:**
- `PaymentsFacadeTest` (do desenvolvedor), testes novos: `preAuthorizationsReadAllPages`; `registerSendsExactDecimalsExpiryAndKey` (`amount` `"300.00"`, `expiresAt` no formato ISO em UTC, autorização, sem campo de cartão além de bandeira e últimos); `registerWithoutAuthorizationFailsBeforeTheNetwork`; `captureSendsAmountAndKey`; `releaseSendsOnlyTheKey`; `cardLikePreAuthorizationCodesNeverReachTheNetwork`; `preAuthorizationInputsHideCardDataInToString`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.Payments*'` e o trio do `core`.
- `CoreConnection.paymentSource()` expõe as operações novas e o `businessDay()`. Verifica: `:app:test --tests 'com.zeny.desktop.app.session.*'`.
- **Extração sem mudança de comportamento:** os testes da E03 continuam verdes **sem alteração** (inclusive os do testador): `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`; `grep -c classifyWriteFailure app/src/main/kotlin/com/zeny/desktop/app/folio/PaymentFormState.kt app/src/main/kotlin/com/zeny/desktop/app/folio/RefundState.kt` dá 0 nos dois (a chamada vive no ajudante), e `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` continuam sem linha. Um `FolioWriteTest` novo cobre o ajudante isolado: `unknownKeepsTheIntent`, `storedIsCloseOnly`, `definitiveDropsTheIntent`, `refusedCodeCallsTheHookOnlyWhenDefinitive`, `shiftRefusalOffersTheShortcutOnlyWithIt`, `cancelledKeepsTheKeyAndUnlocks`, `editAfterUnlockRotatesTheKey`, `lateAnswerAfterCloseIsDropped`.

**Áreas tocadas:** `core/.../facade/PaymentsFacade.kt`; `app/.../folio/PaymentSource.kt`, `app/.../session/ServerConnection.kt`, `app/.../folio/FolioWrite.kt` (novo), `PaymentFormState.kt`, `RefundState.kt`; testes do `core` e de `folio/`.

**Tamanho:** ~55 min; 6 arquivos de produção (1 novo). Corte pré-autorizado se passar de 60 min: a porta e a `CoreConnection` passam à Task 2, e a Task 1 fica com a fachada e o ajudante; o corte vai ao ledger.

**Depende de:** nenhuma.

**Interfaces:** produz as operações e as entradas de caução, a porta ampliada e o ajudante comum, que as Tasks 2 e 3 consomem.

---

### Task 2: A seção "Cauções", registrar e liberar

**Objetivo:** o painel da conta ganha a seção "Cauções" (com `wiring.payments` e `billing.folios.read`), num arquivo de tela próprio (`PreAuthorizationSection.kt`), com o *holder* `PreAuthorizationsState` (Interfaces): uma tabela na ordem do servidor com situação, cartão ("<bandeira> final <últimos>" quando vem), autorizado, capturado, capturável, liberado (quando há) e validade; "Nenhuma caução nesta conta." só depois da primeira leitura (como os pagamentos, `c4016ce`); a falha da leitura diz "Não foi possível ler as cauções." sem esconder a conta. "Registrar caução" (`testTag("register-pre-authorization")`) abre um diálogo com o valor (`MoneyField`), a validade (`DateField`, Global Constraints), a autorização (obrigatória), o NSU, a bandeira e os 4 últimos dígitos, todos travados (`enabled = false`) com `locked`. "Liberar" (`testTag("release-<n>")`) abre uma confirmação que mostra o capturável que o servidor devolveu ("A sobra de <capturável> será liberada na maquininha.", ou sem valor quando o capturável é zero) e só confirma. Os dois usam o ajudante comum (`onRefused` relê as cauções em `PRE_AUTHORIZATION_SETTLED` e a conta em `FOLIO_CLOSED`; `UNKNOWN_STORED`: "O servidor não confirmou o registro da caução. Feche e confira a conta antes de registrar de novo." e "O servidor não confirmou a liberação. Feche e confira a conta antes de liberar de novo."). Sucesso: a seção e a conta são relidas, e o painel diz "Caução registrada." ou "Sobra liberada." (pelo `notify`).

**Critérios de aceite:**
- `PreAuthorizationFormStateTest` (novo): `doubleConfirmRegistersOnce`; `retryAfterNetworkKeepsTheKey`; `amountGoesAsExactDecimal`; `authorizationIsRequired`; `cardLikeCodesAreRefusedWithText`; `formOffersNoCardNumberField` (as propriedades públicas, por reflexão, são só as previstas: critério 1 da etapa); `validityWindowFollowsTheHotelZone` (com `Clock` fixo e fuso do hotel diferente do da máquina: hoje e 89 dias aceitos com o `expiresAt` 23:59:59 do dia no fuso do hotel em UTC; ontem e 90 dias recusados com o texto); `missingZoneShowsTextAndDoesNotConfirm` (e reabrir depois de a porta voltar a dar o fuso confirma); `fieldsLockWhileBusyAndUnknown`; `storedFailureIsCloseOnly`; `closedFolioShowsMessageAndReloads`.
- `ReleaseStateTest` (novo): `releaseSendsOnceAndReloads`; `retryAfterNetworkKeepsTheKey`; `settledShowsMessageAndReloads` (`409 PRE_AUTHORIZATION_SETTLED`); `storedFailureIsCloseOnly`.
- `PreAuthorizationsStateTest` (novo): `rowsShowWhatTheServerSends` (valores formatados sem conta, validade no fuso do hotel); `unknownStatusShowsAsOther`; `emptyTextWaitsForTheFirstRead`; `readFailureKeepsTheFolio`; `releaseStaysAvailableOnAClosedFolio` (registrar não aparece; liberar aparece em `ACTIVE`, `PARTIALLY_CAPTURED` e `EXPIRED`, não em `CAPTURED` nem `RELEASED`); `writesNeedPaymentsReceive`; `zoneFailureKeepsTheSection`; `oneDialogAtATimeAcrossFolioAndHolder`.
- Teste de UI (`app/src/test/.../folio/PreAuthorizationSectionTest.kt`, novo): a seção aparece com a porta; registrar mostra a caução nova na tabela; liberar muda a situação para "Liberada" com o valor que a porta devolve; sem `billing.payments.receive` não há botões.
- Os testes de `folio/` e do `AppRoot` continuam verdes. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novos: `PreAuthorizationsState.kt`, `PreAuthorizationFormState.kt`, `ReleaseState.kt`, `PreAuthorizationSection.kt`; `FolioState.kt` (cria, relê e fecha o *holder*), `FolioScreen.kt` (inclui a seção), `FolioText.kt`); testes de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção (4 novos, pequenos, graças ao ajudante da Task 1). Corte pré-autorizado se passar de 60 min (ou se o corte da Task 1 tiver sido aplicado): "Liberar" (`ReleaseState.kt`, a confirmação e o `ReleaseStateTest`) passa à Task 3, que tem folga; o corte vai ao ledger.

**Depende de:** Task 1.

**Interfaces:** produz `PreAuthorizationsState`, `PreAuthorizationRow` e a seção, que a Task 3 amplia.

---

### Task 3: Capturar e a verificação ao vivo

**Objetivo:** "Capturar" (`testTag("capture-<n>")`, `canCapture`) abre um diálogo com o valor (sugerido com o capturável do servidor, mostrado como "Capturável: <valor>"), o NSU e a autorização da captura, travados com `locked`; usa o ajudante comum, com o atalho "Abrir turno" (`offersOpenShift` e o `closeThenOpenShift` do *holder*); `UNKNOWN_STORED`: "O servidor não confirmou a captura. Feche e confira a conta antes de capturar de novo."; `PRE_AUTHORIZATION_EXPIRED` e `CAPTURE_EXCEEDS_AUTHORIZED` mostram o texto do catálogo e, pelo `onRefused`, relêem as cauções. Sucesso: relê as cauções, a conta e os pagamentos (a captura entra no saldo e na seção "Pagamentos") e diz "Caução capturada.". Uma verificação ao vivo, `LivePreAuthorizationCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `CaptureStateTest` (novo): `amountSuggestedIsTheCapturable`; `doubleConfirmCapturesOnce`; `retryAfterNetworkKeepsTheKey`; `shiftRequiredOffersTheShortcut`; `expiredBetweenOpenAndConfirmReloads`; `exceedsAuthorizedShowsMessage`; `storedFailureIsCloseOnly`; `successReloadsFolioPaymentsAndPreAuthorizations`; `fieldsLockWhileBusyAndUnknown`.
- Teste de UI (em `PreAuthorizationSectionTest.kt`): "Capturar" só em caução capturável, com `billing.payments.receive` e conta aberta; a captura muda a situação e o capturável para os da porta e o pagamento aparece na seção "Pagamentos"; com `CASHIER_SHIFT_REQUIRED`, "Abrir turno" fecha o diálogo e chama o atalho.
- **Verificação ao vivo pronta:** `app/src/test/.../folio/LivePreAuthorizationCheck.kt`, pulada sem `ZENY_LIVE_PREAUTH=1` (opt-in no `app/build.gradle.kts` no padrão dos outros), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`), pela `CoreConnection` e pelos estados reais; credenciais só do ambiente, nunca impressas; nenhuma resposta do `sdk` impressa nem concatenada (a trava da E03 é a referência). Numa conta aberta de reserva do seed: (1) registra uma caução de R$ 100,00 com validade de 7 dias e autorização de teste, com a resposta perdida e o `confirm` de novo com a mesma `Intent`, exigindo uma caução só a mais; (2) se a Recepcionista não tem turno, abre no ponto "Recepção" (como a `LivePaymentCheck`); captura R$ 30,00 e exige a situação "Capturada em parte", o capturável R$ 70,00 e o saldo da conta relido igual ao de antes da captura menos R$ 30,00; (3) libera a sobra e exige a situação "Liberada", o liberado R$ 70,00 e o saldo igual ao do passo 2 (critério 2 da etapa). Escreve na demo e não desfaz (KDoc e handoff); a conta pode ficar credora. Sem conta aberta de reserva, falha com `NEED_SEED`.
- Os greps da E01 continuam sem linha. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novo: `CaptureState.kt`; `PreAuthorizationsState.kt`, `PreAuthorizationSection.kt`, `FolioText.kt`), `app/build.gradle.kts` (o opt-in); testes de `folio/`.

**Tamanho:** ~55 min; 5 arquivos de produção (1 novo), os três trechos do opt-in e um check ao vivo.

**Depende de:** Task 1 (operações, ajudante) e Task 2 (*holder*, seção).

**Interfaces:** consome as das Tasks 1 e 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3. A Task 1 abre com a extração, que as outras usam; as Tasks 2 e 3 escrevem nos mesmos arquivos de `folio/`.

## Fora de escopo, por decisão do plano

- `GET /api/v1/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}`: a lista já traz cada caução inteira.
- As cauções a vencer (`GET /api/v1/card-pre-authorizations/expiring`): E10, por decisão desta revisão de plano (I5). Notas para ela: a terceira aba só com `wiring.payments`; `start()`/`close()` e a chave do `remember` como o `FolioAccounts`; o padrão do "Até" quando o `businessDay()` falha; reler ao entrar na aba; a linha não identifica a conta (só `folioId`), e "Abrir conta" é a identificação.
- As outras dívidas da E03 (as constantes repetidas, o `FolioState.kt` grande, o `PostingFormState`/`ReversalState` com a maquinaria própria): o *holder* novo evita que o `FolioState` cresça; o resto segue no handoff.
- O handoff da E09 registra que a base (E03) ainda não passou pela `LivePaymentCheck`.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: as três escritas sem `billing.payments.receive`; a trava contra credencial e cartão impressos da `LivePreAuthorizationCheck` (no padrão de `tests/test_live_payment_ataque.sh`, com concatenação, `error(...)`, `throw` e o objeto inteiro em `assert*`, que a trava da E03 não pegava); a validade nas bordas e com fusos diferentes; capturável e valores nas bordas; duplo clique e repetição com a mesma chave nas três escritas; caução vencida, liquidada e capturada a mais; liberar com a conta encerrada; troca de sessão com um diálogo de caução aberto; `status` desconhecido; o comportamento da E03 depois da extração do ajudante (o recebimento e o reembolso).

## Verificação ao vivo e critérios que dependem do servidor

O critério "liberar a sobra da caução muda a situação e o saldo conforme a API" pede o `zeny-front` e as credenciais do usuário. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo", como a E03.
