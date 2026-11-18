# M4 E02 — Conta da hospedagem — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a partir do detalhe da reserva ou da área "Contas e caixa", o usuário abre a conta, vê os lançamentos, o saldo e a taxa de serviço separada, lança consumos por código, estorna (com aprovação quando o servidor pede) e vê o extrato `GUEST` e `INTERNAL`, na tela e em PDF no visualizador da M2 E07.

**Architecture:** uma fachada nova no `core`, `FoliosFacade` (`ZenyCore.folios`), sobre a `BillingApi` que a E07 já registrou em `Apis`, com as leituras paginadas pelo `CursorPager`, a escrita idempotente do lançamento e o estorno como `ApprovableAction` pelo `ApiExecutor.approvable`; o extrato em PDF entra na `DocumentsFacade`, com a mesma validação dos outros PDFs. No `app`, um pacote novo `folio/` com a porta `FolioSource`, os *state holders* e a tela. A área "Contas e caixa" ganha duas abas, "Caixa" (a tela da E07, que continua a primeira e a padrão) e "Contas" (as contas abertas); o detalhe da reserva (Recepção e Reservas) ganha o botão "Conta". O front nunca calcula saldo, taxa nem total: mostra o que o servidor devolve.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), PDFBox (só no `design` e nos testes do `app`), Gradle.

**Spec:**
- a seção "E02 — Conta da hospedagem" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` (depois da divisão de 2026-11-18), com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E06, da E01 e da E07;
- o contrato `api/openapi.yaml`: `listFolios`, `getFolio`, `listPostings`, `createPosting`, `reversePosting`, `listTransactionCodes`, `getFolioStatement`, `getFolioStatementPdf` e os esquemas `FolioResponse`, `FolioOwnerDto`, `PostingRequest`, `PostingResponse`, `ReversalRequest`, `TransactionCodeResponse`, `FolioStatementResponse`, `StatementDayResponse`, `StatementLineResponse`, `StatementLineInternalResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 (fronteira `core`/`sdk`), 0009 (documentos PDF e impressão local) e 0013 (fonte única de permissões).

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** cálculo de valor monetário (saldo, taxa de serviço, valor unitário e totais do extrato, só exibidos); concorrência (lançamento e estorno idempotentes repetidos depois de falha, duplo clique); autenticação ou autorização (ações pelo conjunto da casca, estorno como ação sensível com aprovação de superior); dado pessoal ou segredo (o extrato `INTERNAL` traz usuários e motivos que o `GUEST` omite; a verificação ao vivo lê senhas do ambiente e nunca as imprime).

## Global Constraints

- **Dinheiro:** sempre `Money` do `core` e string decimal na API (`^-?\d+(\.\d{1,2})?$`), nunca `Double`. O front nunca calcula saldo, taxa, total nem saldo do dia: mostra o que o servidor devolve, formatado em BRL pelo `MoneyInput.format` do `design` (o pacote `folio` não importa nada do pacote `reception`, salvo o `PdfKind` e o visualizador da ficha, que já são compartilhados). A quantidade do lançamento é inteira, de 1 a 999; só a fachada a converte para o `Double` que o `sdk` gera para `quantity`.
- **Idempotência:** lançar e estornar levam `Idempotency-Key` por intenção (`Intent` do `core`). Repetir depois de erro de rede reaproveita a chave; nenhum duplo clique gera dois lançamentos. A falha é classificada por `classifyWriteFailure` (E01): fora do `DEFINITIVE` a intenção fica; os ramos por `code` só rodam com `outcome == WriteFailure.DEFINITIVE`; `UNKNOWN_STORED` dá `closeOnly` (confirmar vira no-op, entradas travadas, só "Fechar"). Nenhum estado copia a regra (`grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` continuam sem linha).
- **Aprovação:** o estorno usa o `ApiExecutor.approvable` (`core/.../facade/Approval.kt`) e, no `app`, o `approve` do `ApprovalCoordinator` como o `UndoCheckInState`; não monte `ApprovableAction` em linha.
- **Permissões (ADR 0013):** a aba "Contas", o botão "Conta" e as ações usam o conjunto da casca, recebido por parâmetro; os *holders* seguem a amarração sessão × conjunto da E06 (desenham só com o `sessionId` ativo igual ao da casca; `remember(source, sessionId, permissions)`, um `DisposableEffect` por *holder*). Ver a conta e o extrato: `billing.folios.read`. Lançar: `billing.postings.create`. Estornar aparece com `billing.postings.create` ou `billing.postings.reverse` (a Recepcionista lança e não estorna; o servidor pede a aprovação). O servidor continua decidindo cada ação.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`; tipos de modelo do `sdk` nas portas seguem o padrão das portas existentes.
- **Regras transversais (design §6.2):** nada de JSON ou `code` cru; o `type` do lançamento e o `kind` da linha do extrato são `x-extensible-enum` (valor desconhecido aparece como "Outro (VALOR)", pelo `ExtensibleEnumLabels` do `core`); textos em pt-BR, código em inglês.
- **PDF (ADR 0009):** o extrato em PDF abre no visualizador da M2 E07, em memória; nenhum arquivo é criado sem "Salvar como…".
- **Cancelamento:** `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`; `ensureActive()` também depois do `approve(...)`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`) quando a linha não é a primeira.
- **Arquivos do testador (`C-M6`):** antes de alterar um teste, confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS` com o arquivo, o teste, a saída e o comportamento esperado.
- **Verificação da tarefa:** a linha `verificação rápida` do `CLAUDE.md` para cada módulo tocado (e os que usam o que mudou), com `:<módulo>:detektMain` e `:<módulo>:detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; "daemon disappeared" → repita uma vez; nenhum laço de espera.
- **Commits:** em pt-BR, no estilo do histórico, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo.

## Review Focus

1. **Duplo clique em "Lançar"** gera uma chamada só, e a repetição depois de erro de rede leva a mesma chave. Testes: `doubleConfirmPostsOnce` e `retryAfterNetworkKeepsTheKey` (Task 3).
2. **Valor unitário com vírgula, ponto de milhar ou mais de duas casas** não vira `Double` nem arredonda em silêncio: o `MoneyField` recusa ou normaliza e o pedido leva a string decimal exata. Teste: `unitPriceGoesAsExactDecimal` (Task 3).
3. **Lançamento de tipo desconhecido ou linha de extrato de `kind` desconhecido** aparece como "Outro (VALOR)" e o resto da conta continua. Testes: `unknownTypeShowsAsOther` e `unknownStatementKindShowsAsOther` (Task 2).
4. **Estorno com a aprovação cancelada** deixa um texto na tela e destrava o formulário, sem segunda chamada. Teste: `cancelledApprovalLeavesTextAndUnlocks` (Task 3).
5. **Conta encerrada no meio do caminho** (`409 FOLIO_CLOSED` ao lançar ou estornar) mostra a mensagem em pt-BR e relê a conta, sem travar. Teste: `closedFolioShowsMessageAndReloads` (Task 3).

## Interfaces

- `core/src/main/kotlin/com/zeny/desktop/core/facade/FoliosFacade.kt`, exposto como `ZenyCore.folios`:
  - `enum class StatementAudience { GUEST, INTERNAL }` e `data class PostingInput(val transactionCodeId: String, val quantity: Int, val unitPrice: Money, val description: String?)` (quantidade de 1 a 999, conferida na fachada com `IllegalArgumentException` antes da rede; `description` em branco não vai);
  - `suspend fun openFolioPage(cursor: String?, limit: Int = 50): Page<FolioResponse>` — `status=OPEN`, uma página (a tela percorre com o `CursorPager`);
  - `suspend fun reservationFolios(reservationId: String): List<FolioResponse>` — `ownerType=RESERVATION`, `ownerId`, todas as páginas pelo `CursorPager` (página de 200);
  - `suspend fun folio(folioId: String): FolioResponse`;
  - `suspend fun postings(folioId: String): List<PostingResponse>` — todas as páginas pelo `CursorPager` (página de 200), na ordem do servidor;
  - `suspend fun transactionCodes(): List<TransactionCodeResponse>` — `active=true`, todas as páginas pelo `CursorPager`;
  - `suspend fun post(folioId: String, input: PostingInput, intent: Intent): PostingResponse`;
  - `fun reverse(folioId: String, postingId: String, reason: String, intent: Intent): ApprovableAction<PostingResponse>` — os ids são conferidos ao montar a ação;
  - `suspend fun statement(folioId: String, audience: StatementAudience): FolioStatementResponse`.
  Id que não é UUID lança `IllegalArgumentException` antes da rede, sem o valor na mensagem, como as fachadas existentes. Nenhuma cópia de cursor: só o `CursorPager`.
- `DocumentsFacade.folioStatement(folioId: String, audience: StatementAudience): PdfDocument` — `getFolioStatementPdf` com `Accept: application/pdf` e a mesma validação de `pdfOf` (tipo, assinatura, tamanho).
- `app/.../folio/FolioSource.kt`: porta com as mesmas operações da fachada mais `suspend fun statementPdf(folioId: String, audience: StatementAudience): PdfDocument`; `ServerConnection.folioSource(): FolioSource? = null` (implementada em `CoreConnection`).
- `FolioState(source: FolioSource, scope: CoroutineScope, permissions: Set<String>, folioId: String)` — a conta, os lançamentos e o extrato; `fun reload()`, `fun close()`, `var audience: StatementAudience` (padrão `INTERNAL`), `fun showStatement()`.
- `FolioListState(source: FolioSource, scope: CoroutineScope)` — as contas abertas, com `loadMore()` pelo `CursorPager` sobre `openFolioPage`.
- `ReservationFoliosState(source: FolioSource, scope: CoroutineScope, reservationId: String)` — as contas da reserva para o diálogo do botão "Conta" (pode viver no mesmo arquivo do `FolioListState`).
- `DetailActions.onFolio: (() -> Unit)? = null`, com o botão "Conta" (`testTag("open-folio")`).
- `PdfKind.FOLIO_STATEMENT` (título "Extrato — <número>", arquivo `extrato`).

## Revisão de arquitetura

(pendente)

---

### Task 1: A fachada das contas no `core` e a porta no `app`

**Objetivo:** criar `FoliosFacade` e `DocumentsFacade.folioStatement` (Interfaces), registrar `ZenyCore.folios`, conferir no `ErrorCatalog` que os `code` da conta que esta etapa expõe têm texto em pt-BR (`FOLIO_NOT_FOUND`, `FOLIO_CLOSED`, `UNKNOWN_TRANSACTION_CODE`, `TRANSACTION_CODE_INACTIVE`, `POSTING_INVALID`, `POSTING_NOT_FOUND`, `POSTING_NOT_CORRECTABLE`, `POSTING_ALREADY_REVERSED`, `POSTING_NOTHING_TO_REVERSE`, `SERVICE_CHARGE_FOLLOWS_ITEM`, `ROUTING_TARGET_CLOSED`) e acrescentar os que faltarem; criar a porta `FolioSource` e a implementação em `CoreConnection`.

**Critérios de aceite:**
- `FoliosFacadeTest` (novo, com o `MockEngine` como as outras fachadas): `openFolioPageAsksOpenStatus` (`status=OPEN` e o cursor na query); `reservationFoliosReadsAllPages` (duas páginas, `ownerType=RESERVATION` e `ownerId`); `postingsReadAllPages`; `transactionCodesOnlyActive` (`active=true`, duas páginas); `postSendsExactDecimalAndIdempotencyKey` (com `Money.parse("12.50")`, corpo com `"unitPrice":"12.50"` como string, `quantity` 2, sem `description` quando em branco, e o header `Idempotency-Key`); `sameIntentKeepsTheKey` (lançar duas vezes com a mesma `Intent`); `reverseAsksApprovalThenRetriesWithTokenAndNewKey` (`403 APPROVAL_REQUIRED` vira `ApiError.ApprovalRequired`; `run(token)` leva o `X-Approval-Token` e outra chave, como o `BookingsFacadeAtaqueTest` prova para as reservas); `statementSendsAudience` (`GUEST` e `INTERNAL` na query); `quantityOutOfRangeFailsBeforeTheNetwork` (0 e 1000); `nonUuidIdFailsBeforeTheNetwork`. Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.FoliosFacadeTest'`.
- `DocumentsFacade`: `folioStatementSendsAudienceAndValidatesPdf` (audiência na query; corpo que não é PDF vira `IncompatibleResponse`), no arquivo de teste da fachada que não tem `Soul: testador` (se todos tiverem, num arquivo novo `DocumentsFacadeFolioTest`). Verifica: `:core:test --tests 'com.zeny.desktop.core.facade.Documents*'`.
- O teste do catálogo cobre os textos acrescentados. Verifica: `:core:test --tests 'com.zeny.desktop.core.error.ErrorCatalog*'`.
- `CoreConnection.folioSource()` devolve a porta ligada à fachada; as conexões falsas continuam compilando pelo padrão `null`. Verifica: o trio do `core` e o do `app` (`:app:test --tests 'com.zeny.desktop.app.session.*'`).

**Áreas tocadas:** `core/.../facade/` (`FoliosFacade.kt` novo, `DocumentsFacade.kt`), `core/.../ZenyCore.kt`, `core/.../error/ErrorCatalog.kt`; `app/.../folio/FolioSource.kt` (novo), `app/.../session/ServerConnection.kt`; testes do `core`.

**Tamanho:** ~50 min; 6 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz `ZenyCore.folios`, `StatementAudience`, `PostingInput`, `DocumentsFacade.folioStatement`, `FolioSource` e `ServerConnection.folioSource()` (bloco Interfaces), que as Tasks 2 e 3 consomem.

---

### Task 2: A conta na tela, os atalhos e o extrato

**Objetivo:** a área "Contas e caixa" passa a ter as abas "Caixa" (a `CashierScreen` da E07, primeira e padrão) e "Contas" (só com `billing.folios.read`): a lista das contas abertas (número, dono, saldo e "Limite excedido" quando `creditLimitExceeded`), com "Carregar mais", e, ao escolher uma, o painel da conta. O detalhe da reserva, na Recepção e nas Reservas, ganha "Conta" (só com `billing.folios.read` e a porta presente), que abre um diálogo com as contas da reserva: nenhuma → "Esta reserva ainda não tem conta."; uma → o painel dela; mais de uma → um seletor por número. O painel da conta mostra número, situação, saldo e limite como o servidor devolve, e a tabela de lançamentos (data operacional, descrição, tipo, quantidade, valor, "Estornado" quando `reversedBy` existe); a taxa de serviço (`type` `SERVICE_CHARGE`) é uma linha própria, "Taxa de serviço", logo abaixo do item a que pertence (`serviceChargeForPostingId`). Rótulos do `type`: `REGULAR` "Lançamento", `REVERSAL` "Estorno", `ADJUSTMENT` "Ajuste", `TRANSFER_OUT` "Transferência (saída)", `TRANSFER_IN` "Transferência (entrada)", `SERVICE_CHARGE` "Taxa de serviço"; desconhecido, "Outro (VALOR)". "Extrato" mostra o `FolioStatementResponse` da audiência escolhida ("Interno" ou "Para o hóspede"): os dias com as linhas, os totais do dia e o saldo do fim do dia, os totais e o saldo da conta, tudo como o servidor devolve; no `INTERNAL`, o motivo e quem lançou quando o servidor os manda; no `GUEST`, nada além do que veio. "Extrato em PDF" abre o visualizador da M2 E07 (`pdfFactory` com `PdfKind.FOLIO_STATEMENT`) com a mesma audiência.

**Critérios de aceite:**
- `FolioStateTest` (novo, porta falsa): `loadsFolioAndPostings` (o saldo do servidor formatado em BRL, sem conta no front); `serviceChargeSitsUnderItsItem`; `unknownTypeShowsAsOther`; `reversedPostingIsMarked`; `guestStatementShowsOnlyWhatServerSends` (a linha `GUEST` sem `internal` não expõe motivo nem usuário; a `INTERNAL` expõe o motivo que veio); `statementBalanceIsTheServerBalance`; `unknownStatementKindShowsAsOther`; `pdfUsesTheSelectedAudience` (a porta falsa registra a audiência); `loadFailureShowsErrorAndRetryReloads`; `lateAnswerAfterCloseIsDropped`.
- `FolioListStateTest` (novo): `loadMoreFollowsTheCursor` (duas páginas, a segunda com o cursor da primeira); `reservationWithoutFolioSaysSo`; `reservationWithSeveralFoliosOffersTheChoice`.
- Teste de UI (`FolioScreenTest`, novo, montando a casca como o `AppRoot` monta): a aba "Caixa" continua a padrão; sem `billing.folios.read` não há aba "Contas"; com ela, escolher uma conta mostra os lançamentos e o saldo; "Conta" no detalhe da reserva só aparece com `billing.folios.read` e abre o diálogo; "Extrato em PDF" abre o visualizador sem criar arquivo (o mesmo modo de prova dos testes da ficha de registro); a área com `sessionId` de outra sessão não desenha.
- Os testes que montam o `AppRoot`, a Recepção, as Reservas e o caixa continuam verdes; quebra em arquivo do testador segue a regra do `C-M6`: `:app:test --tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.ShellRoot*' --tests 'com.zeny.desktop.app.cashier.*' --tests 'com.zeny.desktop.app.reception.*' --tests 'com.zeny.desktop.app.reservations.*'`. Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*'` mais esse filtro.

**Áreas tocadas:** `app/.../folio/` (novos: `FolioState.kt`, `FolioListState.kt`, `FolioScreen.kt`, `FolioText.kt`), `app/.../AppRoot.kt` (abas da área, fábricas da conta e do PDF, "Conta" nas duas áreas), `app/.../reception/ReservationDetailPanel.kt` (`onFolio`), `app/.../reception/ReceptionScreen.kt`, `app/.../reservations/ReservationsScreen.kt`, `app/.../reception/RegistrationCardState.kt` (`PdfKind.FOLIO_STATEMENT`); testes de `folio/`.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** Task 1 (`FolioSource` e os tipos).

**Interfaces:** produz `FolioState`, `FolioListState`, `ReservationFoliosState`, `DetailActions.onFolio` e `PdfKind.FOLIO_STATEMENT` (bloco Interfaces), que a Task 3 consome.

---

### Task 3: Lançar e estornar

**Objetivo:** no painel da conta aberta, "Lançar" (com `billing.postings.create`) abre um diálogo com o código de lançamento (os ativos de `transactionCodes()`, sem os de `kind` `SERVICE_CHARGE`, rotulados "<code> — <name>"), quantidade inteira (1 a 999, padrão 1), valor unitário (`MoneyField`) e descrição opcional (até 200 caracteres). No menu de contexto de cada lançamento `REGULAR` sem `reversedBy`, "Estornar" (com `billing.postings.create` ou `billing.postings.reverse`) abre um diálogo com o motivo (obrigatório, até 500 caracteres). Cada diálogo tem a própria `Intent` e a classificação da E01; `UNKNOWN_STORED` dá `closeOnly` com o texto "O servidor não confirmou o lançamento. Feche e confira a conta antes de lançar de novo." ou "O servidor não confirmou o estorno. Feche e confira a conta antes de refazer.", e a releitura da conta é o "confira". O estorno passa pelo `approve`; a aprovação cancelada deixa "Aprovação cancelada. O estorno não foi feito." e destrava. Depois do sucesso, e na falha definitiva `FOLIO_CLOSED`, a conta é relida: saldo e taxa vêm do servidor. Uma verificação ao vivo, `LiveFolioCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `PostingFormStateTest` (novo): `doubleConfirmPostsOnce`; `retryAfterNetworkKeepsTheKey`; `unitPriceGoesAsExactDecimal` (`"1.234,56"` digitado → `Money.parse("1234.56")`); `serviceChargeCodesAreNotOffered`; `quantityOutsideRangeIsRefused` (texto em pt-BR, nenhuma chamada); `storedFailureIsCloseOnly`; `closedFolioShowsMessageAndReloads`; `successReloadsTheFolio` (o saldo e a taxa novos são os da porta).
- `ReversalStateTest` (novo): `reversalNeedsReason`; `approvalRequiredGoesThroughApprove` (a ação passada ao `approve` é a da porta, com a mesma `Intent`); `cancelledApprovalLeavesTextAndUnlocks`; `retryAfterNetworkKeepsTheKey`; `storedFailureIsCloseOnly`; `successReloadsAndKeepsTheOriginal` (a conta relida tem o original e o estorno).
- Teste de UI (`FolioScreenTest`): "Lançar" só com `billing.postings.create`; "Estornar" só em lançamento `REGULAR` não estornado e com uma das duas permissões; não aparece em taxa de serviço nem em estorno; o lançamento novo aparece na tabela depois de confirmar.
- **Verificação ao vivo pronta:** `app/src/test/.../folio/LiveFolioCheck.kt`, pulada sem `ZENY_LIVE_FOLIO=1` (o opt-in no `app/build.gradle.kts` segue o padrão dos outros: o `inputs.property`, o provider e a condição do `upToDateWhen`), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`) com o Gerente aprovando (`ZENY_LIVE_APPROVER_LOGIN`, `ZENY_LIVE_APPROVER_PIN`), pela `CoreConnection` e pelos estados reais; credenciais só do ambiente, nunca impressas, com o comando em subshell no KDoc. Numa conta aberta de reserva do seed: lança um consumo com um código que tem `serviceChargeApplies` (dois `confirm` seguidos) e exige um item e uma taxa a mais, com o saldo e a taxa da tela iguais aos de `folio()` e `postings()` relidos; estorna o item como Recepcionista, exige que a aprovação foi pedida, aprova como o Gerente e exige o estorno como lançamento novo, com o original intacto; lê o extrato `GUEST` (nenhuma linha com `internal`) e o `INTERNAL`, com o saldo igual ao da conta; abre o PDF das duas audiências e exige, pelo texto do PDFBox, os dígitos do saldo da conta. Ela escreve na demo e não desfaz (registrar no KDoc e no handoff).
- O grep da E01 continua sem linha (Global Constraints). Verifica: `:app:test --tests 'com.zeny.desktop.app.folio.*'` (o check ao vivo é pulado sem o opt-in, mas compila e passa no lint) e o trio do `app`.

**Áreas tocadas:** `app/.../folio/` (novos: `PostingFormState.kt`, `ReversalState.kt`, `FolioDialogs.kt`; `FolioScreen.kt`, `FolioText.kt`, `FolioState.kt`), `app/.../AppRoot.kt` (o `approve` para o estorno, se a fábrica viver ali), `app/build.gradle.kts` (o opt-in); testes de `folio/`.

**Tamanho:** ~60 min; 7 arquivos de produção, os três trechos do opt-in e um check ao vivo (código de teste).

**Depende de:** Task 2 (`FolioState` e a tela).

**Interfaces:** consome as das Tasks 1 e 2.

---

## Fora de escopo, por decisão do plano

- `POST /api/v1/folios` (conta avulsa): nenhum critério a usa, e a conta da reserva já existe no servidor. Fica para quando uma tela a pedir (registrado no handoff).
- Ajustar, dividir, transferir, remover a taxa a pedido, pagador e limite de crédito, o `B-M5` e a isenção ao vivo: E08.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: valores de borda do valor unitário (0,00; 0,01; negativo; 1.000.000,00), quantidade nas bordas (0, 1, 999, 1000), duplo clique e repetição com a mesma chave no lançamento e no estorno, aprovação cancelada e aprovada perdida no estorno, o extrato `GUEST` sem nada do bloco interno, troca de sessão com a conta aberta, `type` e `kind` desconhecidos, a aba "Contas" e o botão "Conta" sem a permissão, e os itens deixados pelo handoff da E07 ("Para o testador": `ensureActive()` depois do `approve(...)` nos cinco estados citados, as esperas canceláveis de `CashierStateTest.closeDropsTheLateAnswer` e `ShellStateTest.lateShiftAnswerAfter*`, a asserção `BAD_GATEWAY` que não pode falhar, o `grep -oP` da trava de `tests/test_live_cashier_ataque.sh` e o lugar de `LateAnswer`).

## Verificação ao vivo e critérios que dependem do servidor

Os critérios "lançar um consumo numa conta do seed", "estornar como Recepcionista … aprovado pelo Gerente" e "o PDF com o mesmo saldo da tela" pedem o `zeny-front` e as credenciais da folha de senhas, que ficam com o usuário. O orquestrador roda a `LiveFolioCheck` se as variáveis estiverem no ambiente da sessão; senão, a etapa registra no handoff o comando pronto e espera a execução do usuário antes de fechar `#concluida`.
