# M4 E09 — Cauções — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** no painel da conta, a operadora registra uma caução com os dados permitidos (sem número de cartão), captura parte ou tudo no turno aberto, libera a sobra, e acompanha as cauções a vencer numa aba da área "Contas e caixa".

**Architecture:** a `PaymentsFacade` da E03 ganha as cinco operações de caução, com as entradas e as regras de formato no `core` (`problems()`, como no recebimento), e a porta `PaymentSource` as expõe. Antes de nascerem três formulários novos, a maquinaria comum de escrita da conta (envio com a `Intent`, classificação D1, `closeOnly`, recusa por `code`, atalho do turno), hoje repetida entre `PaymentFormState` e `RefundState`, vira um ajudante único no `app`, que os cinco formulários usam. No painel, as cauções ganham um *holder* próprio (`PreAuthorizationsState`), criado pelo `FolioState`, e uma seção própria na tela, para o `FolioState.kt` (611 linhas) não crescer. A aba "Cauções a vencer" lê a lista paginada pelo `CursorPager` e abre a conta na aba "Contas". O front nunca calcula valor: autorizado, capturado, capturável e liberado vêm do servidor.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E09 — Cauções" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E03, da E02 e da E07 (as dívidas "antes da E09" do handoff da E03);
- o contrato `api/openapi.yaml`: `listCardPreAuthorizations`, `registerCardPreAuthorization`, `captureCardPreAuthorization`, `releaseCardPreAuthorization`, `listExpiringCardPreAuthorizations` e os esquemas `RegisterPreAuthorizationRequest`, `CapturePreAuthorizationRequest`, `CardPreAuthorizationResponse`, `PreAuthorizationCaptureResponse`, `CursorPageCardPreAuthorizationResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** cálculo de valor monetário (autorizado, capturado, capturável e liberado, só exibidos; o valor sugerido da captura é o capturável do servidor); concorrência (registro, captura e liberação idempotentes repetidos depois de falha, duplo clique; a caução pode vencer entre abrir o diálogo e confirmar); dado pessoal ou segredo (bandeira, últimos dígitos, NSU e autorização; a verificação ao vivo lê senhas do ambiente e nunca as imprime); parsing de entrada externa não confiável (valores, datas e códigos digitados; o `status` extensível e os decimais do servidor).

## Global Constraints

- **Dinheiro:** sempre `Money` do `core` e string decimal na API, nunca `Double`. O front nunca calcula autorizado, capturado, capturável, liberado nem saldo: mostra o que o servidor devolve, pelo `MoneyInput.format`. O valor sugerido da captura é o `capturableAmount` da caução, copiado sem conta.
- **Cartão (PCI-DSS):** nenhum campo de número de cartão nem de CVV. Na caução, só bandeira (letras e espaços, até 20, opcional), os 4 últimos dígitos (`^[0-9]{4}$`, opcional), NSU (opcional) e autorização (**obrigatória** no registro), com o formato da E03 (até 20 letras e dígitos, no máximo 12 dígitos). Sequência com cara de número de cartão é recusada antes da rede, com texto em pt-BR, e nenhum desses valores aparece em log, mensagem de exceção ou `toString` (o `CardPreAuthorizationResponse` e o `PreAuthorizationCaptureResponse` do `sdk` são data classes que imprimem o cartão no `toString`: nunca os logue nem interpole).
- **Validade:** o diálogo de registro pede a data de validade (`DateField`, `dd/mm/aaaa`). Valem as datas de amanhã até 90 dias depois de hoje, no fuso do hotel (`BusinessDay.zone`, lido pela porta; "hoje" pelo `Clock` injetado), e o `expiresAt` enviado é o início (00:00) dessa data no fuso do hotel, em UTC. Fora do intervalo: "A validade vai de amanhã até 90 dias." Sem o fuso (leitura falhou ou o servidor não mandou), o diálogo diz "Não foi possível ler o fuso do hotel. Tente de novo." e não confirma.
- **Situação (`status`, `x-extensible-enum`):** `ACTIVE` "Ativa", `PARTIALLY_CAPTURED` "Capturada em parte", `CAPTURED` "Capturada", `RELEASED` "Liberada", `EXPIRED` "Vencida"; desconhecido, "Outro (VALOR)" pelo `ExtensibleEnumLabels`.
- **Idempotência e D1:** registrar, capturar e liberar levam `Idempotency-Key` por intenção. Repetir depois de erro de rede reaproveita a chave; nenhum duplo clique gera duas escritas. A falha é classificada por `classifyWriteFailure`; os ramos por `code` só rodam com `DEFINITIVE`; `UNKNOWN_STORED` dá `closeOnly`. Os greps da E01 continuam sem linha. Os formulários implementam o `FolioWriteForm` e usam o ajudante comum da Task 1; nenhum copia a maquinaria.
- **Turno:** a captura exige turno (`CASHIER_SHIFT_REQUIRED`): o texto do catálogo e, com `openShift`, "Abrir turno" (`offersOpenShift` e o `closeThenOpenShift` do `FolioState`, da E03). Registrar e liberar não exigem turno.
- **Permissões (ADR 0013):** pelo conjunto da casca no `FolioWiring`. Ver as cauções e a aba "Cauções a vencer": `billing.folios.read`. Registrar, capturar e liberar: `billing.payments.receive`. Registrar e capturar só com a conta aberta; **liberar aparece também com a conta encerrada** (o contrato aceita) e em caução vencida. Capturar só em `ACTIVE` ou `PARTIALLY_CAPTURED` com capturável maior que zero; liberar em `ACTIVE`, `PARTIALLY_CAPTURED` ou `EXPIRED`. O servidor continua decidindo (`PRE_AUTHORIZATION_SETTLED`, `PRE_AUTHORIZATION_EXPIRED`, `CAPTURE_EXCEEDS_AUTHORIZED`, `FOLIO_CLOSED`, todos com texto no `ErrorCatalog`).
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Regras transversais (design §6.2):** nada de JSON, `code` cru nem UUID na tela; pt-BR na tela, inglês no código.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Em `app/.../folio/` são do testador, entre outros, `FolioAtaqueTest.kt`, `FolioSessionAtaqueTest.kt`, `LiveFolioCheck.kt`, `PaymentAtaqueTest.kt`, `PaymentDialogsAtaqueTest.kt`, `PaymentShellAtaqueTest.kt`, `FolioPaymentsScreenAtaqueTest.kt`; no `core`, `PaymentsFacadeAtaqueTest.kt`.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (e os que usam o que mudou), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Duplo clique em "Registrar caução"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleConfirmRegistersOnce`, `retryAfterNetworkKeepsTheKey` (Task 2).
2. **Validade no limite** (hoje, amanhã, 90 e 91 dias, virada do dia no fuso do hotel diferente do da máquina) dá o `expiresAt` certo ou a recusa com texto, sem ir à rede. Teste: `validityWindowFollowsTheHotelZone` (Task 2).
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
  - `suspend fun releasePreAuthorization(folioId: String, preAuthorizationId: String, intent: Intent): CardPreAuthorizationResponse`;
  - `suspend fun expiringPreAuthorizationPage(to: BusinessDate, cursor: String?, limit: Int = 50): Page<CardPreAuthorizationResponse>` (sem `from`: o servidor usa a data operacional).
  Entrada com `problems()` ou id inválido lança `IllegalArgumentException` antes da rede, sem o valor.
- `PaymentSource` ganha as cinco operações e `suspend fun businessDay(): BusinessDay` (`core.hotel.businessDay()` na `CoreConnection`). Os falsos dos testes ganham as operações; `FakePaymentSource.kt` é do desenvolvedor.
- **Ajudante comum** (Task 1), em `app/.../folio/FolioWrite.kt`: uma classe que guarda `busy`, `unconfirmed`, `closeOnly`, `message`, a `Intent` e o `shiftRefused`, e expõe `suspend`/`launch` de um envio com a classificação D1, o `closeOnly` com o texto dado, o texto do `code` pelo `ErrorCatalog`, `FOLIO_CLOSED` e `CASHIER_SHIFT_REQUIRED`, e a regra de quando descartar a intenção (resposta definitiva, edição real). O nome e a assinatura ficam com o implementador, que os registra no relatório e no KDoc; a Task 2 e a Task 3 os consomem. `PaymentFormState` e `RefundState` passam a usá-lo sem mudar a API pública nem o comportamento.
- `PreAuthorizationsState(wiring: FolioWiring, scope: CoroutineScope, folioId: String, onChanged: () -> Unit)` (Task 2): `rows: List<PreAuthorizationRow>`, `loaded`, `error`, `fun reload()`, `fun canRegister(folioOpen: Boolean)`, `fun startRegister()`, `var register: PreAuthorizationFormState?`, `fun canRelease(row)`, `fun startRelease(row)`, `var release: ReleaseState?`, `fun close()`. O `FolioState` o cria quando `wiring.payments != null`, o relê junto da conta e o fecha com ela; o `onChanged` relê a conta e os pagamentos.
- `data class PreAuthorizationRow(val id: String, val status: String, val statusCode: String, val card: String?, val amount: String, val captured: String, val capturable: Money?, val released: String?, val expiresAt: String)` com `toString()` sem `card`; `capturable` só quando maior que zero; `expiresAt` como "dd/MM/aaaa HH:mm" no fuso do hotel (sem fuso, só a data UTC com "(UTC)").
- Task 3: `fun PreAuthorizationsState.canCapture(row)`, `fun startCapture(row)`, `var capture: CaptureState?`; `ExpiringPreAuthorizationsState(source: PaymentSource, scope: CoroutineScope, open: (folioId: String) -> Unit)` com `to`, `rows`, `loadMore()`, `refresh()`.

## Revisão de arquitetura

(preenchida depois da revisão)

---

### Task 1: As operações de caução no `core`, a porta e o ajudante comum de escrita

**Objetivo:** acrescentar à `PaymentsFacade` as entradas, as regras e as cinco operações de caução (Interfaces), e à `PaymentSource` as cinco mais o `businessDay()`, ligadas na `CoreConnection`. No `app`, extrair a maquinaria de escrita repetida entre `PaymentFormState` e `RefundState` (`send`, `fail`, os campos de estado, o descarte da intenção) para o ajudante comum em `FolioWrite.kt`, e fazer os dois estados o usarem, sem mudar a API pública nem o comportamento. Os limites repetidos (`REASON_MAX`/`MAX_REASON` e o `present()`) passam a vir de um lugar só no `core` (dívida do handoff da E03).

**Critérios de aceite:**
- `PaymentsFacadeTest` (do desenvolvedor), testes novos: `preAuthorizationsReadAllPages`; `registerSendsExactDecimalsExpiryAndKey` (`amount` `"300.00"`, `expiresAt` no formato ISO em UTC, autorização, sem campo de cartão além de bandeira e últimos); `registerWithoutAuthorizationFailsBeforeTheNetwork`; `captureSendsAmountAndKey`; `releaseSendsOnlyTheKey`; `expiringAsksTheEndDateAndCursor` (`to` e `cursor` na query, sem `from`); `cardLikePreAuthorizationCodesNeverReachTheNetwork`; `preAuthorizationInputsHideCardDataInToString`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.Payments*'` e o trio do `core`.
- `CoreConnection.paymentSource()` expõe as operações novas e o `businessDay()`. Verifica: `:app:test --tests 'com.zeny.desktop.app.session.*'`.
- **Extração sem mudança de comportamento:** os testes da E03 continuam verdes **sem alteração** (inclusive os do testador): `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`; `PaymentFormState.kt` e `RefundState.kt` ficam sem `classifyWriteFailure` próprio (a chamada vive no ajudante), e o grep da E01 continua sem linha. Um `FolioWriteTest` novo cobre o ajudante isolado: `unknownKeepsTheIntent`, `storedIsCloseOnly`, `definitiveDropsTheIntent`, `shiftRefusalOffersTheShortcutOnlyWithIt`.

**Áreas tocadas:** `core/.../facade/PaymentsFacade.kt`; `app/.../folio/PaymentSource.kt`, `app/.../session/ServerConnection.kt`, `app/.../folio/FolioWrite.kt` (novo), `PaymentFormState.kt`, `RefundState.kt`, `PaymentDialogs.kt` (o limite do motivo); testes do `core` e de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção (1 novo).

**Depende de:** nenhuma.

**Interfaces:** produz as operações e as entradas de caução, a porta ampliada e o ajudante comum, que as Tasks 2 e 3 consomem.

---

### Task 2: A seção "Cauções", registrar e liberar

**Objetivo:** o painel da conta ganha a seção "Cauções" (com `wiring.payments` e `billing.folios.read`), num arquivo de tela próprio (`PreAuthorizationSection.kt`), com o *holder* `PreAuthorizationsState` (Interfaces): uma tabela na ordem do servidor com situação, cartão ("<bandeira> final <últimos>" quando vem), autorizado, capturado, capturável, liberado (quando há) e validade; "Nenhuma caução nesta conta." só depois da primeira leitura (como os pagamentos, `c4016ce`); a falha da leitura diz "Não foi possível ler as cauções." sem esconder a conta. "Registrar caução" (`testTag("register-pre-authorization")`) abre um diálogo com o valor (`MoneyField`), a validade (`DateField`, Global Constraints), a autorização (obrigatória), o NSU, a bandeira e os 4 últimos dígitos, todos travados (`enabled = false`) com `locked`. "Liberar" (`testTag("release-<n>")`) abre uma confirmação que mostra o capturável que o servidor devolveu ("A sobra de <capturável> será liberada na maquininha.", ou sem valor quando o capturável é zero) e só confirma. Os dois usam o ajudante comum (`UNKNOWN_STORED`: "O servidor não confirmou o registro da caução. Feche e confira a conta antes de registrar de novo." e "O servidor não confirmou a liberação. Feche e confira a conta antes de liberar de novo."). Sucesso: a seção e a conta são relidas, e o painel diz "Caução registrada." ou "Sobra liberada.".

**Critérios de aceite:**
- `PreAuthorizationFormStateTest` (novo): `doubleConfirmRegistersOnce`; `retryAfterNetworkKeepsTheKey`; `amountGoesAsExactDecimal`; `authorizationIsRequired`; `cardLikeCodesAreRefusedWithText`; `formOffersNoCardNumberField` (as propriedades públicas, por reflexão, são só as previstas: critério 1 da etapa); `validityWindowFollowsTheHotelZone` (com `Clock` fixo e fuso do hotel diferente do da máquina: amanhã e 90 dias aceitos com o `expiresAt` 00:00 do hotel em UTC; hoje e 91 dias recusados com o texto); `missingZoneShowsTextAndDoesNotConfirm`; `fieldsLockWhileBusyAndUnknown`; `storedFailureIsCloseOnly`; `closedFolioShowsMessageAndReloads`.
- `ReleaseStateTest` (novo): `releaseSendsOnceAndReloads`; `retryAfterNetworkKeepsTheKey`; `settledShowsMessageAndReloads` (`409 PRE_AUTHORIZATION_SETTLED`); `storedFailureIsCloseOnly`.
- `PreAuthorizationsStateTest` (novo): `rowsShowWhatTheServerSends` (valores formatados sem conta, validade no fuso do hotel); `unknownStatusShowsAsOther`; `emptyTextWaitsForTheFirstRead`; `readFailureKeepsTheFolio`; `releaseStaysAvailableOnAClosedFolio` (registrar não aparece; liberar aparece em `ACTIVE`, `PARTIALLY_CAPTURED` e `EXPIRED`, não em `CAPTURED` nem `RELEASED`); `writesNeedPaymentsReceive`.
- Teste de UI (`PreAuthorizationSectionTest`, novo): a seção aparece com a porta; registrar mostra a caução nova na tabela; liberar muda a situação para "Liberada" com o valor que a porta devolve; sem `billing.payments.receive` não há botões.
- Os testes de `folio/` e do `AppRoot` continuam verdes. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novos: `PreAuthorizationsState.kt`, `PreAuthorizationFormState.kt`, `ReleaseState.kt`, `PreAuthorizationSection.kt`; `FolioState.kt` (cria, relê e fecha o *holder*), `FolioScreen.kt` (inclui a seção), `FolioText.kt`); testes de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção (4 novos, pequenos, graças ao ajudante da Task 1).

**Depende de:** Task 1.

**Interfaces:** produz `PreAuthorizationsState`, `PreAuthorizationRow` e a seção, que a Task 3 amplia.

---

### Task 3: Capturar, as cauções a vencer e a verificação ao vivo

**Objetivo:** "Capturar" (`testTag("capture-<n>")`, `canCapture`) abre um diálogo com o valor (sugerido com o capturável do servidor, mostrado como "Capturável: <valor>"), o NSU e a autorização da captura; usa o ajudante comum, com o atalho "Abrir turno" (`offersOpenShift`, `closeThenOpenShift`); `UNKNOWN_STORED`: "O servidor não confirmou a captura. Feche e confira a conta antes de capturar de novo."; `PRE_AUTHORIZATION_EXPIRED` e `CAPTURE_EXCEEDS_AUTHORIZED` mostram o texto do catálogo e relêem as cauções. Sucesso: relê as cauções, a conta e os pagamentos (a captura entra no saldo e na seção "Pagamentos") e diz "Caução capturada.". A área "Contas e caixa" ganha a terceira aba, **"Cauções a vencer"** (só com o `FolioWiring` presente, depois de "Contas"): o campo "Até" (`DateField`, padrão a data operacional mais 7 dias, lida pelo `businessDay()`; "Até" anterior à data operacional é recusado com texto antes da rede), a lista do servidor em ordem de validade (validade, situação, cartão, autorizado, capturável) com "Carregar mais" pelo `CursorPager`, "Nenhuma caução a vencer até <data>." depois da leitura, e "Abrir conta" em cada linha, que seleciona a conta na aba "Contas" (`FolioAccounts.select`). Uma verificação ao vivo, `LivePreAuthorizationCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `CaptureStateTest` (novo): `amountSuggestedIsTheCapturable`; `doubleConfirmCapturesOnce`; `retryAfterNetworkKeepsTheKey`; `shiftRequiredOffersTheShortcut`; `expiredBetweenOpenAndConfirmReloads`; `exceedsAuthorizedShowsMessage`; `storedFailureIsCloseOnly`; `successReloadsFolioPaymentsAndPreAuthorizations`.
- `ExpiringPreAuthorizationsStateTest` (novo): `defaultEndIsBusinessDatePlusSeven`; `endBeforeBusinessDateIsRefusedBeforeTheNetwork`; `loadMoreFollowsTheCursor`; `emptyTextWaitsForTheRead`; `openSelectsTheFolio`.
- Teste de UI: a aba "Cauções a vencer" aparece só com o wiring e não muda a aba padrão ("Caixa"); "Abrir conta" leva à aba "Contas" com a conta escolhida; "Capturar" só em caução capturável com `billing.payments.receive` e conta aberta.
- **Verificação ao vivo pronta:** `app/src/test/.../folio/LivePreAuthorizationCheck.kt`, pulada sem `ZENY_LIVE_PREAUTH=1` (opt-in no `app/build.gradle.kts` no padrão dos outros), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`), pela `CoreConnection` e pelos estados reais; credenciais só do ambiente, nunca impressas; nenhum `toString` de resposta do `sdk` impresso. Numa conta aberta de reserva do seed: (1) registra uma caução de R$ 100,00 com validade de 7 dias e autorização de teste, com a resposta perdida e o `confirm` de novo com a mesma `Intent`, exigindo uma caução só a mais; (2) se a Recepcionista não tem turno, abre no ponto "Recepção" (como a `LivePaymentCheck`); captura R$ 30,00 e exige a situação "Capturada em parte", o capturável R$ 70,00 e o saldo da conta relido igual ao de antes menos R$ 30,00, como o servidor devolve; (3) libera a sobra e exige a situação "Liberada", o liberado R$ 70,00 e o saldo igual ao do passo 2 (critério 2 da etapa); (4) a caução registrada aparece na lista "Cauções a vencer" até a data de validade. Escreve na demo e não desfaz (KDoc e handoff). A conta usada pode ficar com saldo credor; o check escolhe uma conta aberta de reserva com saldo positivo ou lança um consumo de R$ 50,00 antes, e falha com `NEED_SEED` sem conta.
- O grep da E01 continua sem linha. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*'` e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novos: `CaptureState.kt`, `ExpiringPreAuthorizationsState.kt`, `ExpiringPreAuthorizationsScreen.kt`; `PreAuthorizationsState.kt`, `PreAuthorizationSection.kt`, `FolioText.kt`), `app/.../AppRoot.kt` (a terceira aba), `app/build.gradle.kts` (o opt-in); testes de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção (3 novos), os três trechos do opt-in e um check ao vivo.

**Depende de:** Task 1 (operações, ajudante) e Task 2 (*holder*, seção).

**Interfaces:** consome as das Tasks 1 e 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3. A Task 1 abre com a extração, que as outras usam; as Tasks 2 e 3 escrevem nos mesmos arquivos de `folio/`.

## Fora de escopo, por decisão do plano

- `GET /api/v1/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}`: a lista já traz cada caução inteira.
- O `from` das cauções a vencer: o padrão do servidor (a data operacional) basta para "a vencer".
- As outras dívidas da E03 (o `FolioState.kt` grande, as constantes do `design`): o *holder* novo evita que ele cresça; o resto segue no handoff.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: a trava contra credencial e cartão impressos da `LivePreAuthorizationCheck` (no padrão de `tests/test_live_payment_ataque.sh`, com concatenação, `error(...)`, `throw` e o objeto inteiro em `assert*`, que a trava da E03 não pegava); a validade nas bordas e com fusos diferentes; capturável e valores nas bordas; duplo clique e repetição com a mesma chave nas três escritas; caução vencida, liquidada e capturada a mais; liberar com a conta encerrada; troca de sessão com um diálogo de caução aberto; `status` desconhecido; a aba "Cauções a vencer" sem a permissão e com a lista vazia; o comportamento da E03 depois da extração do ajudante (o recebimento e o reembolso).

## Verificação ao vivo e critérios que dependem do servidor

O critério "liberar a sobra da caução muda a situação e o saldo conforme a API" pede o `zeny-front` e as credenciais do usuário. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo", como a E03.
