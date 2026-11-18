# M4 E07 — Turno de caixa — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a operadora abre o próprio turno num ponto de caixa, vê o turno corrente, registra sangria e suprimento, fecha às cegas informando o contado e vê o relatório com as diferenças; o topo da casca indica se há turno aberto.

**Architecture:** uma fachada nova no `core`, `CashierFacade`, sobre a `BillingApi` do `sdk` (que passa a existir no `SdkApis`), com as leituras e as três escritas idempotentes do turno. No `app`, a área "Contas e caixa" (`Area.ACCOUNTS`) passa a ter tela: uma porta `CashierSource`, um *state holder* `CashierState` e a tela, ligados pelo `AreaContent` com a amarração sessão × conjunto da E06. O indicador do topo é um estado da casca (`ShellState.shift`), lido depois do `me` quando o conjunto tem `billing.cashier.operate`, e relido quando a área abre ou fecha um turno. As escritas classificam a falha com `classifyWriteFailure` (E01). O front nunca calcula esperado, total nem diferença: mostra o que o servidor devolve.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E07 — Turno de caixa" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E06 e da E01;
- o contrato `api/openapi.yaml`: `listCashPoints`, `listCashierShifts`, `openCashierShift`, `getCurrentCashierShift`, `getCashierShift`, `recordCashMovement`, `closeCashierShift`, `getCashierShiftReport` (linhas ~1004–1504) e os esquemas `CashierShift*`, `CashMovement*`, `CashCount*`, `ExpectedAmountResponse`, `CashPointResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 (fronteira `core`/`sdk`) e 0013 (fonte única de permissões).

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** cálculo de valor monetário (fundo, sangria, suprimento, contagem e diferença); concorrência (escritas idempotentes repetidas depois de falha); autenticação ou autorização (a área e o indicador pelo conjunto da casca; o fechamento cego esconde o esperado).

## Global Constraints

- **Dinheiro:** sempre `BigDecimal`/`Money` do `core` e string decimal na API (`^-?\d+(\.\d{1,2})?$`), nunca `Double`. O front nunca calcula saldo, esperado, total nem diferença: mostra o que o servidor devolve, formatado em BRL como as telas existentes (`ReceptionText.money`).
- **Idempotência:** abrir, registrar movimento e fechar levam `Idempotency-Key` por intenção (`Intent` do `core`). Repetir depois de erro de rede reaproveita a chave; nenhum duplo clique gera dois movimentos. A falha é classificada por `classifyWriteFailure` (E01): fora do `DEFINITIVE` a intenção fica; os ramos por `code` só rodam com `outcome == WriteFailure.DEFINITIVE`. Nenhum estado copia a regra (`grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` continuam sem linha).
- **Fechamento cego:** o formulário de fechamento nunca mostra o esperado. O painel do turno só mostra o esperado quando o servidor o manda (`expectedVisible`).
- **Permissões (ADR 0013):** a área e o indicador usam o conjunto da casca, recebido por parâmetro; a área segue a amarração sessão × conjunto da E06 (desenha só com o `sessionId` ativo igual ao da casca; *holders* com `remember(source, sessionId, permissions)`, um `DisposableEffect` por *holder*). As ações aparecem pelo conjunto (`billing.cashier.operate` para abrir, movimentar e fechar; `billing.shifts.read` para o relatório), e o servidor continua decidindo cada ação.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`; tipos de modelo do `sdk` nas portas seguem o padrão das portas existentes.
- **Regras transversais (design §6.2):** nada de JSON ou `code` cru; o `method` da contagem é `x-extensible-enum` (valor desconhecido aparece como "Outro (VALOR)"); textos em pt-BR, código em inglês.
- **Cancelamento:** `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`) quando a linha não é a primeira.
- **Arquivos do testador (`C-M6`):** antes de alterar um teste, confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS` com o arquivo, o teste, a saída e o comportamento esperado.
- **Verificação da tarefa:** a linha `verificação rápida` do `CLAUDE.md` para cada módulo tocado (e os que usam o que mudou), com `:<módulo>:detektMain` e `:<módulo>:detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez; nenhum laço de espera.
- **Commits:** em pt-BR, no estilo do histórico, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo.

## Review Focus

1. **Duplo clique em "Registrar" ou "Fechar"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleSubmitRecordsOnce` e `retryAfterNetworkKeepsTheKey` (Task 3), `openRetryAfterNetworkKeepsTheKey` (Task 2).
2. **Valor digitado com vírgula, ponto de milhar ou mais de duas casas** não vira `Double` nem arredonda em silêncio: o campo (`MoneyField`) recusa ou normaliza e o pedido leva a string decimal exata. Teste: `movementAmountGoesAsExactDecimal` (Task 3).
3. **Turno aberto por outra sessão/outro ponto** (`CASHIER_SHIFT_ALREADY_OPEN`) mostra a mensagem em pt-BR e relê o turno corrente, sem travar. Teste: `alreadyOpenShowsMessageAndReloads` (Task 2).
4. **Contagem com forma desconhecida no relatório** (`method` fora do enum) aparece como "Outro (VALOR)" e o resto do relatório continua. Teste: `unknownMethodShowsAsOther` (Task 3).
5. **Indicador do topo com a leitura do turno falhando** não derruba a casca nem concede nada: o topo diz "Turno não verificado". Teste: `shiftReadFailureKeepsTheShell` (Task 2).

## Interfaces

- `core/src/main/kotlin/com/zeny/desktop/core/facade/CashierFacade.kt`, exposto como `ZenyCore.cashier`; o `SdkApis` ganha `val billing: BillingApi`:
  - `suspend fun cashPoints(): List<CashPointResponse>` — só os ativos (`active = true`), todas as páginas pelo `CursorPager` (nenhuma cópia de cursor);
  - `suspend fun currentShift(): CashierShiftResponse?` — `null` no `404 CASHIER_SHIFT_NOT_FOUND`; outra falha lança o `ApiError`;
  - `suspend fun shift(id: String): CashierShiftResponse`;
  - `suspend fun openShift(cashPointId: String, openingFloat: Money, intent: Intent): CashierShiftResponse`;
  - `suspend fun recordMovement(shiftId: String, type: CashMovementType, amount: Money, reason: String, intent: Intent): CashMovementResponse`, com `enum class CashMovementType { WITHDRAWAL, SUPPLY }` no `core`;
  - `suspend fun closeShift(shiftId: String, counts: List<CashCount>, intent: Intent): CashierShiftReportResponse`, com `data class CashCount(val method: PaymentMethod, val amount: Money)` e `enum class PaymentMethod { CASH, DEBIT_CARD, CREDIT_CARD, PIX, BANK_TRANSFER, OTHER }` no `core`;
  - `suspend fun report(shiftId: String): CashierShiftReportResponse`.
  Id que não é UUID lança `IllegalArgumentException` antes da rede, sem o valor na mensagem, como as fachadas existentes.
- `app/.../cashier/CashierSource.kt`: porta com as mesmas operações; `ServerConnection.cashierSource(): CashierSource?` (implementada em `CoreConnection`).
- `ShellState.shift: ShiftIndicator` (observável) e `fun refreshShift()`, com `sealed interface ShiftIndicator { data object Hidden; data object Unknown; data object None; data class Open(val number: String, val cashPointName: String) }`: `Hidden` sem `billing.cashier.operate` no conjunto; `Unknown` quando a leitura falha; `None` sem turno; `Open` com turno. Zerado para `Hidden` no início de cada `reload()`, lido depois do `me` e da data.
- `AreaContent(area, connection, sessionId, permissions, onShiftChanged: () -> Unit)`: a `ShellScreen` repassa `state::refreshShift`.
- `CashierState(source: CashierSource, scope: CoroutineScope, permissions: Set<String>, onShiftChanged: () -> Unit)`.

---

### Task 1: A fachada do caixa no `core`

**Objetivo:** criar `CashierFacade` (Interfaces), registrá-la no `ZenyCore` e no `SdkApis`, e corrigir o texto de `CASHIER_SHIFT_OPERATOR_REQUIRED` no `ErrorCatalog` para `"Só o operador do turno pode fazer isso."` (o código é o `403` de quem não é o operador).

**Critérios de aceite:**
- `CashierFacadeTest` (arquivo novo, com o `MockEngine` como as outras fachadas): `cashPointsReadsAllPagesOfActive` (duas páginas, `active=true` na query); `currentShiftIsNullOnNotFound` (`404 CASHIER_SHIFT_NOT_FOUND`) e `currentShiftOtherErrorThrows`; `openSendsExactDecimalAndIdempotencyKey` (corpo com `"openingFloat":"150.00"` como string e o header `Idempotency-Key`); `sameIntentKeepsTheKey` (duas chamadas com a mesma `Intent` levam a mesma chave) em abrir, movimentar e fechar; `closeSendsCountsByMethod` (`CASH` e `PIX` no corpo, forma omitida não vai); `nonUuidIdFailsBeforeTheNetwork`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.CashierFacadeTest'`.
- `ErrorCatalogTest` (ou o teste existente do catálogo): o texto novo de `CASHIER_SHIFT_OPERATOR_REQUIRED`.
- Os testes existentes do `core` continuam verdes: `:core:test`.

**Áreas tocadas:** `core/.../facade/` (arquivo novo), `core/.../ZenyCore.kt`, `core/.../error/ErrorCatalog.kt`; testes do `core`.

**Tamanho:** ~40 min; 3 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz `ZenyCore.cashier` e os tipos do bloco Interfaces, que as Tasks 2 e 3 consomem.

---

### Task 2: A área "Contas e caixa" com o turno corrente, a abertura e o indicador do topo

**Objetivo:** `Area.ACCOUNTS` entra em `IMPLEMENTED_AREAS` e o `AreaContent` a liga à tela do caixa, com a amarração da E06. A tela lê o turno corrente e os pontos de caixa; sem turno, mostra o formulário de abertura (ponto de caixa e fundo de troco, com o `MoneyField`); com turno, mostra número, ponto, abertura, fundo e os movimentos, e o esperado só se o servidor o mandar. Abrir usa `Intent` e `classifyWriteFailure`; depois de abrir, a tela relê e chama `onShiftChanged`. O topo mostra o `ShellState.shift` (Interfaces): `"Turno <número> aberto · <ponto>"`, `"Sem turno aberto"`, `"Turno não verificado"`, ou nada.

**Critérios de aceite:**
- `CashierStateTest` (novo, porta falsa): `noShiftShowsOpenForm`; `openCreatesShiftAndNotifies` (chama `onShiftChanged` uma vez); `openRetryAfterNetworkKeepsTheKey`; `alreadyOpenShowsMessageAndReloads` (`409 CASHIER_SHIFT_ALREADY_OPEN` → "Já existe um turno de caixa aberto." e releitura do corrente); `withoutOperatePermissionOffersNoOpen`; `expectedOnlyWhenVisible` (`expectedVisible = false` → nenhum esperado exposto). Verifica: `:app:test --tests 'com.zeny.desktop.app.cashier.*'`.
- `ShellStateTest` / teste de UI da casca: `shiftIndicatorFollowsTheRead` (`Open`, `None`), `shiftHiddenWithoutOperate`, `shiftReadFailureKeepsTheShell` (`Unknown`, a casca sem `error`), `reloadResetsShiftToHidden`, e o texto do topo para cada valor. Verifica: `--tests 'com.zeny.desktop.app.shell.*'`.
- Teste de UI da área (`CashierScreenTest`, novo): abrir pelo formulário mostra o painel do turno e o topo passa a dizer "Turno CX-… aberto · <ponto>"; a área com `sessionId` de outra sessão não desenha (amarração da E06).
- Os testes que montam o `AppRoot` e as áreas continuam verdes: `--tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.ShellRoot*' --tests 'com.zeny.desktop.app.reception.*Shell*' --tests 'com.zeny.desktop.app.reservations.*Shell*' --tests 'com.zeny.desktop.app.rooms.*Shell*'`.

**Áreas tocadas:** `app/.../cashier/` (novo: `CashierSource.kt`, `CashierState.kt`, `CashierScreen.kt`, `CashierText.kt`), `app/.../session/ServerConnection.kt`, `app/.../shell/` (`Areas.kt`, `ShellState.kt`, `ShellScreen.kt`), `app/.../AppRoot.kt`; testes de `cashier/` e `shell/`, e as conexões falsas que precisarem de `cashierSource()`.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** Task 1 (`ZenyCore.cashier` e os tipos).

**Interfaces:** produz `CashierSource`, `CashierState`, `ShellState.shift`/`refreshShift` e o `AreaContent` com `onShiftChanged` (bloco Interfaces), que a Task 3 consome.

---

### Task 3: Sangria, suprimento, fechamento cego e relatório

**Objetivo:** no painel do turno, "Sangria" e "Suprimento" abrem um diálogo com valor (`MoneyField`) e motivo (obrigatório, até 200 caracteres); "Fechar turno" abre o formulário de contagem por forma de pagamento (as seis formas do contrato, vazia vale zero e não vai), que nunca mostra o esperado. Fechado, a tela mostra o relatório (`CashierShiftReportResponse`): por forma, esperado, declarado e diferença em BRL, e os totais, como o servidor devolve; um "Ver relatório" relê pelo `report(shiftId)`. Cada escrita tem a própria `Intent`, a classificação da E01 e, onde o texto for "feche e confira", `closeOnly` como na E01. Depois de fechar, `onShiftChanged`. Uma verificação ao vivo, `LiveCashierCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `CashierStateTest` (continua o arquivo da Task 2): `doubleSubmitRecordsOnce` (dois `confirm` seguidos, uma chamada); `retryAfterNetworkKeepsTheKey` (movimento e fechamento); `movementAmountGoesAsExactDecimal` (`"1.234,56"` digitado → `Money("1234.56")`, nunca `Double`); `movementNeedsReason` (motivo vazio não envia, texto em pt-BR); `closedShiftRefusesMovement` (`409 CASHIER_SHIFT_CLOSED` → mensagem e releitura); `closeFormNeverExposesExpected` (antes de enviar, o estado do formulário não tem esperado, mesmo com `expectedVisible = true` no turno); `closeShowsReportWithDifferences` (os valores do relatório, sem cálculo); `unknownMethodShowsAsOther`; `closeNotifiesShiftChanged`.
- Teste de UI (`CashierScreenTest`): o diálogo de sangria registra e o movimento aparece no painel; o formulário de fechamento não mostra nenhum valor esperado; o relatório mostra a diferença em BRL ("R$ …") por forma.
- **Verificação ao vivo pronta:** `app/src/test/.../cashier/LiveCashierCheck.kt`, pulada sem `ZENY_LIVE_CASHIER=1` (opt-in registrado em `app/build.gradle.kts`, como os outros `inputs.property`); com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_RECEPTIONIST_LOGIN`/`…_PASSWORD` e `ZENY_LIVE_VIEWER_LOGIN`/`…_PASSWORD` (Gerente), nunca impressos. Ela abre (ou reaproveita) o turno da Recepcionista, confere `CASHIER_SHIFT_ALREADY_OPEN` ao reabrir, registra sangria e suprimento repetindo com a mesma chave sem duplicar, fecha com uma diferença e confere como Gerente a notificação `billing.cashier-shift-difference` em `GET /api/v1/notifications` (pelo meio que os checks ao vivo já usam para chamadas fora das fachadas, ou por uma leitura mínima no `core` só de teste; o plano não cria tela de notificações). Escreve na demo e não desfaz. Sem o opt-in, `:app:test` a pula.
- O grep da E01 continua sem linha (Global Constraints). Verifica: `:app:test --tests 'com.zeny.desktop.app.cashier.*' --tests 'com.zeny.desktop.app.shell.*'` e o trio do `design` se o `MoneyField` mudar.

**Áreas tocadas:** `app/.../cashier/` (`CashierState.kt`, `CashierScreen.kt`, `CashierDialogs.kt` novo, `CashierText.kt`), `app/build.gradle.kts` (só a linha do opt-in); testes de `cashier/`.

**Tamanho:** ~60 min; 5 arquivos de produção (mais a linha do Gradle).

**Depende de:** Task 2 (`CashierState`, `CashierSource`, a tela).

**Interfaces:** consome as das Tasks 1 e 2.

---

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: valores de borda (0,00; 0,01; 1.000.000,00; acima; negativo na contagem de forma que não é dinheiro), duplo clique e repetição com a mesma chave nas três escritas, fechamento cego (nenhum caminho mostra o esperado antes do fechamento), troca de sessão com o turno aberto, o indicador do topo sem a permissão e com a leitura falhando, `method` desconhecido, e os itens deixados pelo handoff da E01 ("aprovada perdida" no `moveRoom` e no `walkIn`; `IDEMPOTENCY_KEY_IN_USE` 500 no `WalkInState`; as asserções fracas de `WalkInConfirmAtaqueTest` e `ReservationRetryDialogsAtaqueTest`; o comentário desatualizado acima de `afterAStoredFailureTheNegotiatedRateFieldIsDisabledLikeTheOtherInputs`).

## Verificação ao vivo e critérios que dependem do servidor

Os critérios "abrir como Recepcionista do seed", "reabrir mostra mensagem", "sangria e suprimento sem duplicar ao vivo" e "a diferença gera a notificação ao Gerente" pedem o `zeny-front` e as credenciais da folha de senhas, que ficam com o usuário. O orquestrador roda a `LiveCashierCheck` se as variáveis estiverem no ambiente da sessão; senão, a etapa registra no handoff o comando pronto e espera a execução do usuário antes de fechar `#concluida`.
