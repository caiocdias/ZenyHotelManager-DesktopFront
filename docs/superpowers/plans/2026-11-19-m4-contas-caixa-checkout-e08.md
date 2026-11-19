# M4 E08 — Correções da conta — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** na conta aberta pela E02, o usuário ajusta um lançamento (com a aprovação de superior quando o servidor pede), remove a taxa de serviço a pedido do cliente, transfere lançamentos para outra conta, divide um lançamento entre contas e reabre uma conta encerrada (com aprovação).

**Architecture:** no `core`, a `FoliosFacade` ganha as cinco escritas (`adjust` e `reopen` como `ApprovableAction`; `removeServiceCharge`, `transfer` e `split` diretas), todas com `Idempotency-Key` pela `Intent`, menos a reabertura, que o contrato não declara idempotente. No `app`, uma porta nova `FolioCorrectionSource` (as portas da E02 ficam como estão: arquivos do testador implementam `FolioSource`), ligada ao `FolioWiring` por campos novos com padrão; e um *state holder* `FolioCorrectionsState`, fora do `FolioState` (como o `PreAuthorizationsState` da E09), dono dos diálogos das correções, que usam o `FolioWrite` (E09) e relêem a conta pelo `FolioState`. A escolha da conta de destino reaproveita o `FolioListState` (as contas abertas, página a página).

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E08 — Correções da conta" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo" e os handoffs da E02, da E03 e da E09;
- o contrato `api/openapi.yaml`: `adjustPosting` (`AdjustmentRequest`), `splitPosting` (`SplitRequest`, `SplitPartDto`), `removeServiceCharge`, `transferPostings` (`TransferRequest`), `reopenFolio` (`FolioReopenRequest`), as respostas `PostingResponse`, `TransferResponse` e `FolioResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização (cada ação pelo conjunto de permissões da casca; ajuste e reabertura pela aprovação de superior); dado pessoal ou segredo (as credenciais e o PIN do aprovador na verificação ao vivo); cálculo de valor monetário (valor do ajuste, partes da divisão por valor e por percentual, saldos depois da transferência, só digitados ou copiados do servidor); concorrência (escrita repetida depois de falha, duplo clique, outra estação estornando ou encerrando a conta ao mesmo tempo: `POSTING_ALREADY_REVERSED`, `FOLIO_CLOSED`, `FOLIO_NOT_CLOSED`); parsing de entrada externa não confiável (o `remainingAmount`, os decimais e o `TransferResponse` do servidor).

## Global Constraints

- **Dinheiro:** `Money` do `core`, string decimal na API, nunca `Double`. O front não soma partes, não calcula o restante nem o saldo: mostra o `remainingAmount` e o `balance` do servidor. A divisão que não fecha é recusada pelo servidor (`SPLIT_TOTAL_MISMATCH`, texto do catálogo); o front não confere a soma (decisão do plano, registrada no handoff).
- **Percentual da divisão:** decimal com até 4 casas, maior que zero e até 100 (padrão do contrato `^-?\d{1,3}(\.\d{1,4})?$`), digitado como texto e conferido na fachada; nunca `Double`.
- **Quem vê cada ação (regra do front, como o "Estornar" da E02; o servidor decide):** com a conta aberta e a porta de correções no wiring:
  - "Ajustar" na linha: tipo `REGULAR`, não estornado, `remainingAmount` lido maior que zero, com `billing.postings.create` ou `billing.postings.adjust` (a Recepcionista não tem o ajuste e o servidor pede a aprovação);
  - "Remover a taxa" na linha: linha de taxa (tem `serviceChargeForPostingId`), não estornada, `remainingAmount` maior que zero, com `billing.servicecharge.remove`. Nunca em linha que não é taxa (critério 3);
  - "Transferir" e "Dividir" na linha: tipo `REGULAR` ou `TRANSFER_IN`, não estornado, sem `serviceChargeForPostingId`, `remainingAmount` maior que zero, com `billing.postings.transfer`;
  - "Reabrir conta" no cabeçalho: conta encerrada, com `billing.folios.manage` ou `billing.folios.reopen` (os demais pedem a aprovação).

  `remainingAmount` ausente ou fora do formato: a ação não aparece (nunca um valor inventado).
- **Aprovação de superior:** o ajuste passa pelo `approve` que o `FolioWiring` já tem (`ApprovableAction<PostingResponse>`); a reabertura, por um campo novo `approveReopen` (Interfaces). A aprovação cancelada destrava e mantém a intenção (`WriteOutcome.Cancelled`, texto "Aprovação cancelada. O ajuste não foi feito." / "… A conta não foi reaberta."). O `AppRoot` liga os dois ao coordenador de aprovação da sessão nas três entradas da conta.
- **Idempotência e D1:** ajustar, remover a taxa, transferir e dividir levam `Idempotency-Key` por intenção pelo `FolioWrite` (`classifyWriteFailure`); a mesma `Intent` vale enquanto o resultado é desconhecido, com as entradas travadas; resposta definitiva gira a intenção; mudar uma entrada destravada chama `edited()`. Os greps da E01 continuam sem linha: `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main`.
- **Reabertura sem chave:** o `reopenFolio` não declara `Idempotency-Key`, e a reabertura não envia esse header (nem na repetição aprovada, que leva só o `X-Approval-Token`). A falha que `classifyWriteFailure` não dá como definitiva quer dizer "pode ter sido feita, confira" (como o recado, `TraceFormState`): o diálogo mostra "Não foi possível confirmar a reabertura. A conta foi relida; confira antes de tentar de novo.", a conta é relida e "Reabrir" volta a valer só por clique (repetir é inofensivo: a conta já aberta dá `FOLIO_NOT_CLOSED`, texto do catálogo e releitura).
- **Recusas:** texto do `ErrorCatalog` (os `code` das cinco operações já estão lá). `FOLIO_CLOSED`, `FOLIO_NOT_CLOSED`, `POSTING_ALREADY_REVERSED`, `POSTING_NOTHING_TO_TRANSFER`, `POSTING_NOTHING_TO_REVERSE` e `UNKNOWN_FOLIO` relêem a conta (o `onRefused` do `FolioWrite`). Nada de JSON, `code` cru nem UUID na tela.
- **Depois do sucesso:** o diálogo fecha, a conta, os lançamentos e o extrato à vista são relidos, e a lista das contas também (o `rereadAfterWrite` do `FolioState`, que chama o `onWritten`). O aviso do painel (`FolioState.notice`): "Ajuste lançado.", "Taxa de serviço removida: <reason do servidor>" (o `reason` do `PostingResponse` devolvido; sem ele, "Taxa de serviço removida."), "Transferido para a conta <número>." e "Dividido entre <n> contas." (n = partes enviadas), "Conta reaberta.".
- **O lançamento vinculado:** a linha de um `ADJUSTMENT` ou `REVERSAL` com `correctsId` de um lançamento da conta mostra "Corrige: <descrição do original>" (critério 1: o original fica intacto, com o valor dele); a linha da taxa transferida continua sob o item (a regra do `rowsOf` já vale para `TRANSFER_IN` com `serviceChargeForPostingId`).
- **Um diálogo por vez:** os diálogos das correções contam no `dialogOpen` do `FolioState`, e os do painel e das cauções contam no deles (o padrão `otherDialogOpen` da E09).
- **Permissões (ADR 0013):** só o conjunto da casca que o `FolioWiring` traz; nenhuma leitura do `me`.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Campos novos de classes que os testes constroem (`FolioWiring`, `PostingRow`) entram no fim, com padrão.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (e os que usam o que mudou: `core` → `app` e `demo-seed`), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Duplo clique e repetição depois de erro de rede** em cada escrita com chave: uma chamada só, e a repetição leva a mesma chave. Testes: `doubleConfirmAdjustsOnce`, `retryAfterNetworkKeepsTheKey` (Task 2) e `retryAfterNetworkKeepsTheTransferKey` (Task 3).
2. **Aprovação negada ou cancelada no ajuste e na reabertura:** nada feito, entradas destravadas, mesma intenção. Testes: `approvalCancelledKeepsTheIntent` e `reopenApprovalCancelledSaysSo` (Task 2).
3. **`remainingAmount` ausente ou fora do formato** e lançamento já estornado: nenhuma ação aparece, nenhum valor inventado. Teste: `actionsNeedARemainingFromTheServer` (Task 2).
4. **Outra estação corrige ao mesmo tempo** (`POSTING_ALREADY_REVERSED`, `FOLIO_CLOSED`): o texto do catálogo e a conta relida. Testes: `alreadyReversedShowsTextAndRereads` (Task 2) e `transferToClosedFolioRereads` (Task 3).
5. **Divisão com partes fora do formato** (percentual com 5 casas, conta repetida, menos de 2 ou mais de 10 partes): recusada antes da rede, sem o valor na mensagem. Teste: `splitPartsOutOfShapeFailBeforeTheNetwork` (Task 1).

## Interfaces

- `core/.../facade/FoliosFacade.kt` (Task 1):
  - `fun adjust(folioId: String, postingId: String, amount: Money, reason: String, intent: Intent): ApprovableAction<PostingResponse>` — `amount` maior que zero (positivo; o servidor aplica o sinal oposto), `reason` obrigatório até 500;
  - `suspend fun removeServiceCharge(folioId: String, postingId: String, intent: Intent): PostingResponse` — sem corpo;
  - `suspend fun transfer(folioId: String, postingIds: List<String>, targetFolioId: String, reason: String?, intent: Intent): TransferResponse` — de 1 a 100 ids sem repetir, destino diferente da conta; `reason` em branco não vai;
  - `enum class SplitBy { PERCENT, AMOUNT }`; `data class SplitPart(val folioId: String, val value: BigDecimal)` (com `PERCENT`, até 4 casas, `0 < value <= 100`; com `AMOUNT`, até 2 casas e maior que zero);
  - `suspend fun split(folioId: String, postingId: String, by: SplitBy, parts: List<SplitPart>, reason: String?, intent: Intent): TransferResponse` — de 2 a 10 partes, contas sem repetir, uma delas a própria conta;
  - `fun reopen(folioId: String, reason: String): ApprovableAction<FolioResponse>` — sem `Intent` e sem `Idempotency-Key`; com token, só o `X-Approval-Token`. Se o `ApiExecutor` não tiver caminho para isso, acrescente um `internal` sem mudar os existentes.

  Id que não é UUID e entrada fora das regras lançam `IllegalArgumentException` antes da rede, sem o valor na mensagem.
- `app/.../folio/FolioCorrectionSource.kt` (Task 1): a interface com as cinco operações acima, com as mesmas assinaturas; `ServerConnection.folioCorrectionSource(): FolioCorrectionSource? = null`, implementada no `CoreConnection`.
- `FolioWiring` (Task 1), no fim e com padrão: `val corrections: FolioCorrectionSource? = null` e `val approveReopen: suspend (ApprovableAction<FolioResponse>) -> ApiResponse<FolioResponse>? = { null }` (o padrão nunca roda a ação, como o `approveRefund`). O `AppRoot` preenche os dois nas três entradas da conta.
- `PostingRow` (Task 2), no fim e com padrão: `val remaining: Money? = null`, `val adjustable: Boolean = false`, `val serviceChargeRemovable: Boolean = false`, `val transferable: Boolean = false`, `val corrects: String? = null` (a descrição do original). O `rowOf` os preenche pelas regras das Global Constraints (sem permissão: a permissão é do estado).
- `app/.../folio/FolioCorrectionsState.kt` (Task 2): `FolioCorrectionsState(wiring: FolioWiring, scope: CoroutineScope, folioId: String, folioOpen: () -> Boolean, otherDialogOpen: () -> Boolean, notify: (String) -> Unit, onChanged: () -> Unit)`, criado pelo `FolioState` como `val corrections: FolioCorrectionsState?` (nulo sem `wiring.corrections`) e fechado com ele; `canAdjust(row)`, `canRemoveServiceCharge(row)`, `canReopen(closed: Boolean)`, `startAdjustment(row)`, `startRemoval(row)`, `startReopen()`, os `finish…()` de cada diálogo e `val dialogOpen: Boolean`; os formulários `AdjustmentState` (valor, motivo; `FolioWriteForm`), `ServiceChargeRemovalState` (confirmação) e `ReopenState` (motivo). A Task 3 acrescenta `canTransfer(row)`, `startTransfer(row)`, `startSplit(row)` e os formulários `TransferState` e `SplitState`.

## Revisão de arquitetura

(a preencher pelo passo 2 do orquestrador)

---

### Task 1: As correções no `core`, a porta e o wiring

**Objetivo:** as cinco escritas da `FoliosFacade` (Interfaces), a porta `FolioCorrectionSource` com a implementação no `CoreConnection`, e os campos novos do `FolioWiring` preenchidos pelo `AppRoot` nas três entradas da conta (sem UI nova).

**Critérios de aceite:**
- `FolioCorrectionsFacadeTest` (novo, `MockEngine`): `adjustSendsAmountReasonAndKey` (decimal exato, `Idempotency-Key`); `adjustApprovedRetriesWithTokenAndNewKey`; `removeServiceChargeSendsNoBody`; `transferSendsIdsTargetAndReason` (motivo em branco não vai); `splitByPercentSendsPercentStrings` e `splitByAmountSendsAmountStrings` (sem `Double`, sem expoente); `splitPartsOutOfShapeFailBeforeTheNetwork` (5 casas no percentual, 3 casas no valor, conta repetida, 1 parte, 11 partes, sem a própria conta); `transferOutOfShapeFailsBeforeTheNetwork` (0 ids, 101 ids, repetido, destino igual à conta); `reopenSendsNoIdempotencyKey` e `reopenApprovedSendsOnlyTheToken`; `sameIntentKeepsTheKey`; `nonUuidIdFailsBeforeTheNetwork` (mensagem sem o valor). Verifica: `timeout 1500 ./gradlew :core:test --tests 'com.zeny.desktop.core.facade.Folio*' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest`; o trio do `app` e o do `demo-seed` continuam verdes.
- `CoreConnection.folioCorrectionSource()` devolve a porta ligada à fachada, e o `AppRoot` monta o `FolioWiring` com `corrections` e `approveReopen` nas três entradas. Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.session.*' --tests 'com.zeny.desktop.app.AppRoot*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `core/.../facade/FoliosFacade.kt` (e `core/.../http/ApiExecutor.kt` se a reabertura pedir), `app/.../folio/FolioCorrectionSource.kt` (novo), `app/.../folio/FolioWiring.kt`, `app/.../session/ServerConnection.kt`, `app/.../AppRoot.kt`; testes do `core` e da sessão.

**Tamanho:** ~45 min; 6 arquivos de produção (1 novo).

**Depende de:** nenhuma.

**Interfaces:** produz a `FoliosFacade` ampliada, `SplitBy`, `SplitPart`, `FolioCorrectionSource`, `ServerConnection.folioCorrectionSource()` e os campos `corrections`/`approveReopen` do `FolioWiring`.

---

### Task 2: Ajustar, remover a taxa e reabrir

**Objetivo:** o `FolioCorrectionsState` (Interfaces) com os três primeiros diálogos, os campos novos do `PostingRow`, a linha "Corrige: …", "Ajustar" e "Remover a taxa" no menu da linha (o `DropdownMenu` que já tem "Estornar") e "Reabrir conta" no cabeçalho da conta encerrada, pelas Global Constraints. O valor do ajuste usa o `MoneyField` (com `enabled = !locked`) e sugere nada; o diálogo mostra "Resta: <remaining do servidor>". O motivo do ajuste e da reabertura é obrigatório, até 500 caracteres, enviado sem os espaços das pontas. A remoção da taxa é uma confirmação que diz "O servidor registra o motivo: pedido do cliente." e envia sem corpo.

**Critérios de aceite:**
- `FolioCorrectionsStateTest` (novo, portas falsas): `adjustOverTheLimitAsksApprovalAndShowsTheLinkedPosting` (critério 1: a aprovação é pedida pelo `approve` do wiring; aprovada, o aviso "Ajuste lançado.", a conta relida mostra o ajuste com "Corrige: <descrição>" e o original com o valor dele); `approvalCancelledKeepsTheIntent`; `doubleConfirmAdjustsOnce`; `retryAfterNetworkKeepsTheKey`; `storedFailureIsCloseOnly`; `editingAfterADefinitiveAnswerRotatesTheIntent`; `alreadyReversedShowsTextAndRereads`; `folioClosedRereads`; `removalShowsTheServerReason` (critério 3: o aviso traz o `reason` da porta falsa, não um texto fixo); `removalOnlyOnServiceChargeRows` (critério 3: nunca em linha que não é taxa, nem com a permissão); `removalRetryKeepsTheKey`; `actionsNeedARemainingFromTheServer`; `actionsFollowThePermissions` (cada ação com e sem a permissão das Global Constraints; reabrir com `manage` ou `reopen`); `reopenAsksApprovalAndRereads`; `reopenApprovalCancelledSaysSo`; `reopenUnknownRereadsAndAllowsRetryByClick`; `reopenNotClosedShowsTextAndRereads`; `oneDialogAtATime` (com "Receber" aberto, nenhum diálogo de correção abre, e o contrário); `closeDropsLateAnswers`.
- Teste de UI (`FolioCorrectionsScreenTest`, novo): o menu da linha mostra "Ajustar" e "Remover a taxa" só onde valem; o diálogo de ajuste mostra "Resta:" e o campo desabilitado com a escrita em voo; "Reabrir conta" só na conta encerrada.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest` e os greps da E01 sem linha.

**Áreas tocadas:** `app/.../folio/` (novos: `FolioCorrectionsState.kt`, `CorrectionForms.kt` com os três formulários, `CorrectionDialogs.kt`), `FolioState.kt` (o `corrections`, o `dialogOpen`, os campos do `PostingRow` no `rowOf`), `FolioScreen.kt` (menu e cabeçalho), `FolioText.kt`; testes de `folio/`.

**Tamanho:** ~60 min; 6 arquivos de produção (3 novos). Se passar de 60 min, o desenvolvedor para e devolve ao orquestrador (`DONE_WITH_CONCERNS` com o que falta); não há corte pré-autorizado.

**Depende de:** Task 1.

**Interfaces:** produz o `FolioCorrectionsState` e os campos do `PostingRow`, que a Task 3 amplia.

---

### Task 3: Transferir, dividir e a verificação ao vivo

**Objetivo:** "Transferir" e "Dividir" no menu da linha (Global Constraints). "Transferir" abre o `TransferState`: a lista dos lançamentos transferíveis da conta, com caixas (o da linha já marcado), a conta de destino escolhida entre as contas abertas (o `FolioListState` sobre a porta da conta, com "Carregar mais", sem a própria conta; cada linha com número, dono e saldo do servidor) e o motivo opcional. "Dividir" abre o `SplitState`: "Por percentual" ou "Por valor", a própria conta como primeira parte (a que fica) e de 1 a 9 contas escolhidas do mesmo jeito, cada parte com o valor ou o percentual digitado, o "Resta: <remaining do servidor>" e o motivo opcional. Uma verificação ao vivo, `LiveFolioCorrectionCheck`, fica pronta para o usuário.

**Critérios de aceite:**
- `FolioTransferStateTest` (novo, portas falsas): `transferMovesTheCheckedPostingsAndRereads` (critério 2: o aviso "Transferido para a conta <número>.", a conta relida e a lista das contas relida; na porta falsa, a conta de destino passa a ter a entrada e a taxa do item); `serviceChargeFollowsTheItem` (critério 2: a taxa não é marcável à parte e a linha `TRANSFER_IN` da taxa aparece sob o item na conta de destino relida); `splitByPercentSendsTheTypedParts`; `splitByAmountSendsTheTypedParts`; `splitTotalMismatchShowsTheCatalogText`; `splitPartsOutOfShapeAreRefusedWithText` (o texto de pessoa, sem chamar a porta); `retryAfterNetworkKeepsTheTransferKey`; `doubleConfirmTransfersOnce`; `transferToClosedFolioRereads`; `targetListExcludesThisFolio`; `closeDropsLateAnswers`.
- Teste de UI (`FolioTransferScreenTest`, novo): "Transferir" e "Dividir" só nas linhas transferíveis; a escolha do destino com "Carregar mais"; a divisão com as partes e o "Resta:"; abrir a conta de destino na aba "Contas" mostra a entrada (critério 2, "nas duas contas").
- **Verificação ao vivo pronta:** `app/src/test/.../folio/LiveFolioCorrectionCheck.kt`, pulada sem `ZENY_LIVE_CORRECTION=1` (opt-in no `app/build.gradle.kts`, no padrão dos outros), como a Recepcionista (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`, `ZENY_LIVE_VIEWER_PASSWORD`) com o Gerente aprovando (`ZENY_LIVE_APPROVER_LOGIN`, `ZENY_LIVE_APPROVER_PIN`), pela `CoreConnection` e pelos estados reais, no padrão da `LiveFolioCheck`; credenciais só do ambiente, nunca impressas; nenhuma resposta do `sdk` impressa nem concatenada (a trava `tests/test_live_preauth_ataque.sh` é a referência). Em duas contas abertas de reserva do seed: (1) lança um consumo com taxa de serviço na primeira; (2) ajusta R$ 1,00 dele: exige o `APPROVAL_REQUIRED`, aprova como o Gerente e exige o ajuste com `correctsId` do item e o item intacto (critério 1); (3) divide o restante do item 50% / 50% com a segunda conta e exige, nas duas contas relidas, a entrada e a taxa que a API devolveu (critério 2); (4) transfere o que resta do item para a segunda conta e exige o mesmo (critério 2); (5) remove a taxa de um item que ainda tem taxa e exige o estorno com o motivo do servidor (critério 3). Escreve na demo e não desfaz (KDoc e handoff). Sem duas contas abertas de reserva, falha com `NEED_SEED`.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest` e os greps da E01 sem linha.

**Áreas tocadas:** `app/.../folio/` (novos: `TransferForms.kt` com o `TransferState` e o `SplitState`, `TransferDialogs.kt`), `FolioCorrectionsState.kt`, `FolioScreen.kt` (menu); `app/build.gradle.kts` (o opt-in); testes de `folio/` e a verificação ao vivo.

**Tamanho:** ~60 min; 5 arquivos de produção (2 novos), o opt-in e um check ao vivo. Se passar de 60 min, o desenvolvedor para e devolve ao orquestrador; não há corte pré-autorizado.

**Depende de:** Task 2.

**Interfaces:** consome as das Tasks 1 e 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3.

## Fora de escopo, por decisão do plano

- O pagador, o limite de crédito, o `B-M5` e a isenção ao vivo: E12.
- A dívida do `approveRefund = { null }` (E03): o `approveReopen` segue o mesmo padrão; a troca dos dois por um coordenador obrigatório fica para quando os arquivos do testador que montam o wiring forem revistos.
- A migração do `PostingFormState` e do `ReversalState` para o `FolioWrite` (dívida da E09): não é desta etapa.
- Escolher a conta de destino fora das contas abertas (a conta encerrada não recebe lançamento; o servidor recusa com `FOLIO_CLOSED`).

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: cada ação sem a permissão e com o lançamento errado (taxa, estornado, sem `remainingAmount`, `remainingAmount` fora do formato); a aprovação cancelada, negada e o token expirado no ajuste e na reabertura; a reabertura sem chave (nenhum `Idempotency-Key` no fio) e repetida; duplo clique e repetição com a mesma chave nas quatro escritas idempotentes; a divisão com partes fora do formato e que não fecha; a transferência para conta encerrada e para conta inexistente; o `TransferResponse` sem a taxa; troca de sessão com uma correção em voo; um diálogo por vez entre o painel, as cauções e as correções; a trava da `LiveFolioCorrectionCheck` (no padrão de `tests/test_live_preauth_ataque.sh`, com o PIN do aprovador).

## Verificação ao vivo e critérios que dependem do servidor

A inspeção manual contra o `zeny-front` e a parte ao vivo dos critérios 1 a 3 pedem as credenciais do usuário. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo", como a E03, a E09 e a E04.
