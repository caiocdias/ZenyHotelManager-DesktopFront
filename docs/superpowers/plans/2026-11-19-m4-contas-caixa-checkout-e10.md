# M4 E10 — Cauções a vencer — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** na área "Contas e caixa", uma aba "Cauções a vencer" lista as cauções do hotel que vencem até uma data, em ordem de validade, como o servidor as devolve, com "Carregar mais" e "Abrir conta" em cada linha.

**Architecture:** no `core`, a `PaymentsFacade` ganha a página das cauções a vencer (`listExpiringCardPreAuthorizations`). No `app`, uma porta nova `ExpiringPreAuthorizationSource` (a `PaymentSource` não muda: arquivos do testador a implementam), ligada ao `FolioWiring` por um campo novo com padrão; um *state holder* `ExpiringPreAuthorizationsState` sobre o `CursorPager`, no padrão do `FolioListState`; e a aba nova no `AccountsTabs` do `AppRoot`. "Abrir conta" é o `FolioAccounts.select` seguido da troca para a aba "Contas".

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E10 — Cauções a vencer" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e o handoff da E09;
- o contrato `api/openapi.yaml`: `listExpiringCardPreAuthorizations` (`from` opcional, padrão a data operacional; `to` obrigatório, inclusive; `cursor`; `limit` até 200), `CursorPageCardPreAuthorizationResponse`, `CardPreAuthorizationResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização (a aba só com `billing.folios.read` no conjunto da casca); concorrência ("Buscar" e "Carregar mais" em voo, a entrada na aba com a leitura da data em voo, a resposta depois do `close`; acrescentado depois da revisão final, I2); dado pessoal ou segredo (a caução traz bandeira, últimos dígitos, autorização e NSU, que a lista não mostra nem põe em `toString`); parsing de entrada externa não confiável (decimais, instantes e `status` extensível do servidor; o cursor); as credenciais da verificação ao vivo.

## Global Constraints

- **Permissões (ADR 0013):** a aba "Cauções a vencer" aparece só com `billing.folios.read` no conjunto da casca e com a porta das cauções a vencer no wiring (`wiring.expiring` não nulo, que o `AppRoot` só liga junto da `wiring.payments`). A aba padrão continua "Caixa", e a ordem fica "Caixa", "Contas", "Cauções a vencer" (critério 3).
- **O que a linha mostra (critério 1):** a validade (`FolioText.expiresAt` com o fuso do hotel, o mesmo da seção "Cauções"), a situação (`FolioText.preAuthorizationStatus`, extensível: o desconhecido aparece como "Outro (VALOR)"), o valor e o capturável (`FolioText.money`; fora do formato, "—"), na ordem do servidor, sem reordenar nem filtrar. A linha **não** mostra bandeira, últimos dígitos, autorização nem NSU, e o `toString` dela não os tem. O `folioId` serve só ao "Abrir conta" e nunca aparece.
- **O período:** "Até" é um `DateField` (o da E03, com `enabled`). Ao entrar na aba, o "Até" nasce com a data operacional mais 7 dias (`PaymentSource.businessDay()`); se a leitura falhar, o "Até" fica vazio com o texto "Informe até quando." e nada é lido até a operadora digitar a data. O `from` não vai (o servidor usa a data operacional). "Buscar" relê da primeira página com o "Até" digitado; data inválida mostra "Data inválida." e não lê.
- **Paginação:** o `CursorPager` com páginas de 50; "Carregar mais" enquanto há cursor. Uma leitura por vez: "Buscar" descarta a leitura em voo, e a resposta que chega depois de [close] é descartada (o padrão do `FolioListState`, inclusive o `replacePending`).
- **Reler ao entrar na aba:** cada entrada na aba relê a primeira página com o "Até" vigente (nota da revisão de arquitetura da E09).
- **Ciclo de vida:** o estado nasce com a chave do `remember` igual à do `FolioAccounts` (a porta, o `sessionId` e as permissões) e fecha com a área, a casca ou a sessão (`start()`/`close()`).
- **Erros:** a falha da leitura mostra o texto de pessoa do `ApiError` (`PRE_AUTHORIZATION_INVALID` já está no `ErrorCatalog`) e "Tentar de novo"; nada de JSON, `code` cru nem UUID.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Campos novos de classes que os testes constroem (`FolioWiring`) entram no fim, com padrão.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (`core` → `app` e `demo-seed`), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **"Buscar" com uma página em voo** e a resposta tardia depois de fechar: só a leitura vigente muda as linhas. Testes: `searchDropsThePageInFlight`, `closeDropsLateAnswers` (Task 1).
2. **Valores e instantes fora do formato** e `status` desconhecido: "—" e "Outro (VALOR)", sem derrubar a lista. Teste: `malformedFieldsShowDashes` (Task 1).
3. **A leitura da data operacional falha:** o "Até" fica vazio com o texto, e nada é lido. Teste: `businessDayFailureWaitsForTheDate` (Task 1).
4. **Dados do cartão:** nem a linha nem o `toString` mostram bandeira, últimos dígitos, autorização ou NSU. Teste: `rowsNeverCarryCardData` (Task 1).

## Interfaces

- `core/.../facade/PaymentsFacade.kt` (Task 1): `suspend fun expiringPreAuthorizationPage(to: BusinessDate, cursor: String?, limit: Int = 50): Page<CardPreAuthorizationResponse>` — sem `from`.
- `app/.../folio/ExpiringPreAuthorizationSource.kt` (Task 1): `fun interface ExpiringPreAuthorizationSource { suspend fun page(to: BusinessDate, cursor: String?, limit: Int): Page<CardPreAuthorizationResponse> }`; `ServerConnection.expiringPreAuthorizationSource(): ExpiringPreAuthorizationSource? = null`, implementada no `CoreConnection`.
- `FolioWiring` (Task 1), no fim e com padrão: `val expiring: ExpiringPreAuthorizationSource? = null`; o `folioWiring` do `AppRoot` o preenche junto da `payments`.
- `app/.../folio/ExpiringPreAuthorizationsState.kt` (Task 1): `ExpiringPreAuthorizationsState(source: ExpiringPreAuthorizationSource, payments: PaymentSource, scope: CoroutineScope)` com `var untilText: String`, `val untilError: String?`, `rows: List<ExpiringRow>`, `loading`, `error`, `hasMore`, `fun enter()` (reler ao entrar), `fun search()`, `fun loadMore()`, `fun close()`; `data class ExpiringRow(val folioId: String, val expires: String, val status: String, val amount: String, val capturable: String)`.

## Revisão de arquitetura

- **Veredito:** dispensada pelo orquestrador. Conferido: a etapa não cria nem contraria ADR (não há `ARCHITECTURE.md`; segue o 0008 e o 0013); não muda contrato público (só consome uma operação existente do contrato fixado), persistência, fronteira de módulo nem traz peça nova de runtime. A porta nova, o campo do `FolioWiring` e a aba seguem os padrões da E03/E09 (`PaymentSource`, `FolioListState`, `AccountsTabs`), e as notas da revisão de arquitetura da E09 para esta etapa estão nas Global Constraints. A `revisor-seguranca` confere a dispensa contra o diff na revisão final.

---

### Task 1: A página no `core`, a porta e o estado da lista

**Objetivo:** a página da `PaymentsFacade`, a porta `ExpiringPreAuthorizationSource` com a implementação no `CoreConnection`, o campo do `FolioWiring` preenchido pelo `folioWiring` do `AppRoot`, e o `ExpiringPreAuthorizationsState` (Interfaces), pelas Global Constraints. Sem tela.

**Critérios de aceite:**
- `PaymentsFacadeExpiringTest` (novo, `MockEngine`): `expiringSendsToWithoutFromAndTheCursor` (`to` em ISO, sem `from`, o `cursor` e o `limit`); `expiringKeepsTheServerOrderAndTheNextCursor`; `blankNextCursorEndsThePages`. Verifica: `timeout 1500 ./gradlew :core:test --tests 'com.zeny.desktop.core.facade.Payments*' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest`; o trio do `app` e o do `demo-seed` continuam verdes.
- `ExpiringPreAuthorizationsStateTest` (novo, portas falsas): `entersWithTheBusinessDayPlusSevenAndReads` (critério 1: as linhas na ordem do servidor, com situação, valor, capturável e validade formatados como a API devolveu); `loadMoreFollowsTheCursor` (critério 1, "Carregar mais"); `searchRereadsFromTheFirstPage`; `searchDropsThePageInFlight`; `invalidUntilDoesNotRead`; `businessDayFailureWaitsForTheDate`; `everyEnterRereads`; `malformedFieldsShowDashes`; `rowsNeverCarryCardData`; `readFailureShowsTextAndRetries`; `closeDropsLateAnswers`.
- `CoreConnectionTest.expiringSourceIsTheFacade` (servidor HTTP local, no padrão de `paymentSourceReadsTheBusinessDayFromTheHotel`) e `FolioWiringTest.wiringCarriesTheExpiringSource` (o `folioWiring` real leva a porta junto da `payments`; sem a porta de pagamentos, nula). Confira o `C-M6` dos dois arquivos antes. Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.session.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `core/.../facade/PaymentsFacade.kt`, `app/.../folio/ExpiringPreAuthorizationSource.kt` (novo), `app/.../folio/ExpiringPreAuthorizationsState.kt` (novo), `app/.../folio/FolioWiring.kt`, `app/.../session/ServerConnection.kt`, `app/.../AppRoot.kt` (só o `folioWiring`); testes do `core`, da sessão e de `folio/`.

**Tamanho:** ~50 min; 6 arquivos de produção (2 novos).

**Depende de:** nenhuma.

**Interfaces:** produz as das Interfaces.

---

### Task 2: A aba "Cauções a vencer" e "Abrir conta"

**Objetivo:** a tela da lista (`ExpiringPreAuthorizationsScreen.kt`: o "Até" com "Buscar", a tabela, "Carregar mais", o erro com "Tentar de novo" e "Abrir conta" em cada linha) e a terceira aba do `AccountsTabs`, pelas Global Constraints. O estado é lembrado na `CashierArea` com a mesma chave do `FolioAccounts` e fechado com a área; a entrada na aba chama `enter()`. "Abrir conta" chama `FolioAccounts.select(folioId)` e troca para a aba "Contas".

**Critérios de aceite:**
- `ExpiringPreAuthorizationsScreenTest` (novo): `openFolioGoesToTheAccountsTabWithTheFolio` (critério 2: na aba "Contas", a conta da linha aberta); `tabOnlyWithFolioReadAndKeepsCashierAsDefault` (critério 3: sem `billing.folios.read`, a aba não existe; com ela, a aba padrão continua "Caixa"); `loadMoreShowsTheNextPage`; `readFailureShowsTheTextAndRetries`.
- **Verificação ao vivo pronta (só leitura):** `app/src/test/.../folio/LiveExpiringPreAuthorizationCheck.kt`, pulada sem `ZENY_LIVE_EXPIRING=1` (opt-in no `app/build.gradle.kts`, no padrão dos outros), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`), pela `CoreConnection` e pelo estado real: com o "Até" na data operacional mais 89 dias, exige ao menos uma linha (a caução da `LivePreAuthorizationCheck` ou do seed; sem nenhuma, `NEED_SEED`), as validades em ordem não decrescente e, para a primeira linha, que a conta lida pelo `folioId` existe. Credenciais só do ambiente, nunca impressas; nenhuma resposta do `sdk` impressa nem concatenada. Não escreve na demo. Comando: `(set -a; . <arquivo>; set +a; ZENY_LIVE_EXPIRING=1 timeout 1500 ./gradlew :app:test --tests '*LiveExpiringPreAuthorizationCheck' --rerun)`.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.cashier.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `app/.../folio/ExpiringPreAuthorizationsScreen.kt` (novo), `app/.../AppRoot.kt` (`CashierArea` e `AccountsTabs`); `app/build.gradle.kts` (o opt-in); testes de `folio/` e a verificação ao vivo.

**Tamanho:** ~50 min; 2 arquivos de produção (1 novo), o opt-in e um check ao vivo só de leitura.

**Depende de:** Task 1.

**Interfaces:** consome as da Task 1.

---

## Ordem

Em série: Task 1 → Task 2.

## Fora de escopo, por decisão do plano

- As escritas da caução (E09) e o `from` do período (o servidor usa a data operacional).
- Mostrar o número da conta na linha: o contrato só traz `folioId`, e ler cada conta multiplicaria as leituras; "Abrir conta" é a identificação (nota da revisão de arquitetura da E09).

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: a aba sem `billing.folios.read` e sem a porta; dados do cartão na tela, no `toString` e nos logs; valores, instantes e `status` fora do formato; o cursor vazio ou repetido; "Buscar" e "Carregar mais" em voo; a troca de sessão com a aba aberta; o "Até" com data inválida e antes da data operacional (`PRE_AUTHORIZATION_INVALID`).

## Verificação ao vivo

A inspeção manual contra o `zeny-front` pede as credenciais do usuário e uma caução registrada (a `LivePreAuthorizationCheck` da E09 deixa uma); a `LiveExpiringPreAuthorizationCheck` (Task 2) a automatiza, só lendo. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo".
