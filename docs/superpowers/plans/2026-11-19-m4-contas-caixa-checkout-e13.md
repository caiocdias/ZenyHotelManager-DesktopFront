# M4 E13 — Reabrir conta encerrada — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** na conta encerrada, o usuário com a permissão (ou com a aprovação de um superior) reabre a conta, com o motivo.

**Architecture:** no `core`, a `FoliosFacade` ganha `reopen` como `ApprovableAction<FolioResponse>` **sem** `Idempotency-Key` (o contrato não a declara): a tentativa vai sem chave e sem token, a repetição aprovada vai só com o `X-Approval-Token`. Para isso o `ApiExecutor` ganha um caminho `internal` sem `Intent` que leva o token. No `app`, a porta `FolioAccountSource` (E12, a das ações sobre a conta) ganha `reopen`; o `FolioWiring` ganha `approveReopen`, ligado pelo `folioWiring` do `AppRoot` ao coordenador de aprovação da sessão; e um `ReopenState` com envio próprio (sem o `FolioWrite`, que pressupõe a chave), no padrão do `TraceFormState`.

**Decisão sobre a porta (diverge da seção E13 do roadmap):** a seção diz "A porta é a `FolioCorrectionSource` da E08"; este plano põe a reabertura na `FolioAccountSource` da E12. Motivo: coesão — a `FolioCorrectionSource` é a porta das correções de lançamento, todas com `Intent`, num estado (`FolioCorrectionsState`) que só vale com a conta aberta; a reabertura age sobre a conta em si, encerrada, sem `Intent`, e devolve `FolioResponse`, como o `updateFolio` da `FolioAccountSource`. Os implementadores de teste que mudam são `FakeFolioAccountSource.kt` e o `object` de `FolioWiringTest.kt` (`C-M6` 0). Alternativa descartada: a `FolioCorrectionSource`, que obrigaria o mesmo trabalho nos implementadores dela (`FakeFolioCorrectionSource.kt` e `FolioWiringTest.kt`; a `LiveFolioCorrectionCheck` a implementa por delegação e não mudaria) e misturaria uma escrita sem chave entre as com chave. A troca vai às "Decisões para o usuário"; a dependência da E13 passa a ser a E12 (a porta), além da E02.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E13 — Reabrir conta encerrada" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo", as notas I1, M1, M4 e M7 da revisão de arquitetura da E08 (na própria seção) e os handoffs da E08 e da E12;
- o contrato `api/openapi.yaml`: `reopenFolio` (`FolioReopenRequest`: `reason` até 500; `200` com `FolioResponse`; `403 APPROVAL_REQUIRED` com `action` `billing.folio.reopen`; `409 FOLIO_NOT_CLOSED`, conferido antes da aprovação; exige antes `billing.folios.read`);
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização ("Reabrir conta" pelo conjunto da casca; a aprovação de superior); concorrência (a escrita sem `Idempotency-Key` repetida depois de falha; outra estação reabrindo ao mesmo tempo, `FOLIO_NOT_CLOSED`; duplo clique).

## Global Constraints

- **Quem vê "Reabrir conta" (regra do front, como o "Estornar" da E02):** no cabeçalho da conta **encerrada**, com a porta `FolioAccountSource` no wiring e `billing.folios.manage` ou `billing.folios.reopen` no conjunto da casca (quem encerra ou quem reabre; os demais pedem a aprovação pelo servidor). O servidor só exige `billing.folios.read` antes da aprovação: a regra fica nas "Decisões para o usuário" (M7 da revisão da E08).
- **Motivo:** obrigatório, até 500 caracteres contados depois de aparar. O estado apara e valida (vazio dá "Informe o motivo." sem chamar a porta; a edição acima de 500 é recusada); a fachada apara de novo e recusa vazio ou acima de 500 com `IllegalArgumentException`, como rede de segurança. O contrato aceita motivo vazio (`minLength: 0`), mas a descrição da operação exige o motivo: o front mais estrito vai às "Decisões para o usuário".
- **Segredo:** o caminho novo do `ApiExecutor` e o `ReopenState` não põem o token de aprovação nem o motivo em `toString`, mensagem de exceção ou log (o padrão do `TraceFormState`).
- **"Voltar" com o envio em voo:** nada (como o `dismissAccount`); com o resultado desconhecido, fechar relê a conta.
- **Sem `Idempotency-Key`:** a reabertura não envia esse header, nem na repetição aprovada (que leva só o `X-Approval-Token`). O `ApiExecutor` ganha um caminho `internal` que executa sem `Intent` e com o token, sem mudar os existentes.
- **Resultado desconhecido:** a falha que `classifyWriteFailure` não dá como definitiva (`UNKNOWN` e `UNKNOWN_STORED`, porque não há chave guardada) relê a conta, destrava "Reabrir" e diz "Não foi possível confirmar a reabertura. A conta foi relida; confira antes de tentar de novo.". Repetir é inofensivo: a conta já aberta dá `FOLIO_NOT_CLOSED`.
- **Recusas:** `FOLIO_NOT_CLOSED` mostra o texto do catálogo e relê a conta; as demais, o texto do catálogo. Nada de JSON, `code` cru nem UUID.
- **Aprovação:** o `approveReopen` do `FolioWiring` (`suspend (ApprovableAction<FolioResponse>) -> ApiResponse<FolioResponse>?`, no fim, com padrão `{ null }`, como o `approveRefund`) é ligado pelo `folioWiring` ao coordenador da sessão, nas três entradas da conta (a função é única). A aprovação cancelada destrava e diz "Aprovação cancelada. A conta não foi reaberta.".
- **Depois do sucesso:** o diálogo fecha, a conta, os pagamentos, as cauções e a lista das contas são relidos (o `rereadAfterWrite` do `FolioState`) e o aviso do painel diz "Conta reaberta.".
- **Um diálogo por vez:** o diálogo da reabertura conta no `dialogOpen` do `FolioState`.
- **Permissões (ADR 0013):** só o conjunto da casca que o wiring traz.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`; resposta depois do `close()` descartada.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Os implementadores da `FolioAccountSource` nos testes (`FakeFolioAccountSource.kt` e o `object` de `FolioWiringTest.kt`) e o `CoreConnectionTest.kt` deram 0. Campos novos de classes que os testes constroem (`FolioWiring`) entram no fim, com padrão.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (`core` → `design`, `app` e `demo-seed`), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **A reabertura nunca leva `Idempotency-Key`**, nem na repetição aprovada. Testes: `reopenSendsTheTrimmedReasonAndNoIdempotencyKey`, `reopenApprovedSendsOnlyTheToken` (Task 1).
2. **O `approveReopen` ligado ao coordenador nas três entradas** (o padrão `{ null }` tornaria a omissão silenciosa). Teste: `FolioWiringTest.wiringCarriesTheReopenApproval` (Task 1).
3. **Resultado desconhecido por queda de rede:** relê, destrava, diz para conferir; a repetição numa conta já aberta mostra `FOLIO_NOT_CLOSED` e relê. Testes: `reopenUnknownRereadsAndAllowsRetryByClick`, `reopenNotClosedShowsTextAndRereads` (Task 2).
4. **Duplo clique em "Reabrir":** uma chamada só. Teste: `doubleConfirmReopensOnce` (Task 2).

## Interfaces

- `core/.../facade/FoliosFacade.kt` (Task 1): `fun reopen(folioId: String, reason: String): ApprovableAction<FolioResponse>` — `reason` aparado, de 1 a 500 caracteres; id que não é UUID ou motivo fora das regras lança `IllegalArgumentException` antes da rede, sem o valor.
- `core/.../http/ApiExecutor.kt` (Task 1): `internal suspend fun <T : Any> executeWithoutKey(approvalToken: String?, call: suspend (CallOptions) -> HttpResponseTyped<T>): ApiResponse<T>` (sem `Idempotency-Key`; com o token, só o `X-Approval-Token`); em `core/.../facade/Approval.kt`, ao lado do `approvable`, um ajudante `internal fun <T : Any> ApiExecutor.approvableWithoutKey(call): ApprovableAction<T>` sobre ele, para os ajudantes de aprovação seguirem num lugar só.
- `app/.../folio/FolioAccountSource.kt` (Task 1): `fun reopen(folioId: String, reason: String): ApprovableAction<FolioResponse>`, implementada no `CoreConnection`.
- `FolioWiring` (Task 1), no fim e com padrão: `val approveReopen: suspend (ApprovableAction<FolioResponse>) -> ApiResponse<FolioResponse>? = { null }`.
- `app/.../folio/ReopenState.kt` (Task 2): `ReopenState(source: FolioAccountSource, scope: CoroutineScope, folioId: String, approve: suspend (ApprovableAction<FolioResponse>) -> ApiResponse<FolioResponse>?, onDone: () -> Unit, onReread: () -> Unit)` com `reason`, `busy`, `message`, `canConfirm`, `locked`, `confirm()`, `close()`.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 1 (rodada 0: bloqueado, 1 Importante e 8 Menores; corrigidos em `aa9ea6b`).
- **Revisor:** `revisor-arquitetura`, modelo fable.
- **Notas Menores (incorporadas):** N1, o handoff da E08 marca a porta antiga como superada; N2, o histórico do roadmap registra a troca da dependência da E13 ao fechar a etapa. Não há `ARCHITECTURE.md`.

---

### Task 1: A reabertura no `core`, a porta e o wiring

**Objetivo:** o `reopen` da `FoliosFacade` e o caminho sem chave do `ApiExecutor` (Interfaces), o `reopen` da `FolioAccountSource` com o `CoreConnection`, e o `approveReopen` do `FolioWiring` ligado pelo `folioWiring` do `AppRoot` ao coordenador da sessão. Sem tela.

**Critérios de aceite:**
- `FolioReopenFacadeTest` (novo, `MockEngine`): `reopenSendsTheTrimmedReasonAndNoIdempotencyKey`; `reopenApprovedSendsOnlyTheToken` (sem `Idempotency-Key`); `approvalRequiredRisesAsApprovalRequired`; `folioNotClosedRises`; `blankOrLongReasonAndNonUuidFailBeforeTheNetwork` (mensagem sem o valor). Os testes existentes do `ApiExecutor` continuam verdes. Verifica: `timeout 1500 ./gradlew :core:test --tests 'com.zeny.desktop.core.facade.Folio*' --tests 'com.zeny.desktop.core.http.*' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest` e `timeout 1500 ./gradlew :design:test :design:ktlintCheck :design:detekt :demo-seed:test :demo-seed:ktlintCheck :demo-seed:detekt`.
- `CoreConnectionTest.reopenGoesThroughTheFacade` (servidor HTTP local; sem `Idempotency-Key` no fio) e `FolioWiringTest.wiringCarriesTheReopenApproval` (critério 3: o `folioWiring` real com um coordenador falso leva a ação de reabrir até o coordenador; o resultado dele volta). Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.session.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `core/.../facade/FoliosFacade.kt`, `core/.../facade/Approval.kt`, `core/.../http/ApiExecutor.kt`, `app/.../folio/FolioAccountSource.kt`, `app/.../folio/FolioWiring.kt`, `app/.../session/ServerConnection.kt`, `app/.../AppRoot.kt` (só o `folioWiring`); os implementadores de teste da porta; testes do `core`, da sessão e do wiring. Os KDocs que passam a cobrir a reabertura ou a escrita sem chave são atualizados: o da `FolioAccountSource` ("a porta do pagador e do limite"), o do `FolioWiring.account` ("sem 'Pagador e limite'"), o da `ApprovableAction` e do `approvable` em `Approval.kt` ("chave nova", "o ajudante único") e o da `FoliosFacade` ("as escritas levam `Idempotency-Key`").

**Tamanho:** ~45 min; 7 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz as do `core`, o `reopen` da porta e o `approveReopen`, que a Task 2 consome.

---

### Task 2: "Reabrir conta" no cabeçalho da conta encerrada

**Objetivo:** o `ReopenState` (Interfaces) pelas Global Constraints, o botão "Reabrir conta" no cabeçalho da conta encerrada (`FolioScreen`) e o diálogo com o motivo, aberto pelo `FolioState` como mais um diálogo do painel.

**Critérios de aceite:**
- `ReopenStateTest` (novo, porta falsa): `reopenAsksApprovalAndRereads` (critério 1: a aprovação passa pelo `approve`; aprovada, `onDone` e "Conta reaberta."); `reopenApprovalCancelledSaysSo`; `doubleConfirmReopensOnce`; `emptyReasonIsRefusedWithText`; `reopenUnknownRereadsAndAllowsRetryByClick` (critério 2: rede e `Problem` 5xx); `reopenNotClosedShowsTextAndRereads` (critério 2); `closeDropsLateAnswers`.
- `FolioReopenScreenTest` (novo): `buttonOnlyOnClosedFolioWithManageOrReopen` (as combinações de permissão e de situação); `reopenFromTheHeaderRereadsTheFolio` (a conta relida aparece aberta, com "Conta reaberta."); `oneDialogAtATime`.
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `app/.../folio/ReopenState.kt` (novo), `app/.../folio/ReopenDialog.kt` (novo), `FolioState.kt` (abrir e fechar o diálogo, reler), `FolioScreen.kt` (o botão), `FolioText.kt`; testes de `folio/`.

**Tamanho:** ~45 min; 5 arquivos de produção (2 novos).

**Depende de:** Task 1.

**Interfaces:** consome as da Task 1.

---

## Ordem

Em série: Task 1 → Task 2.

## Fora de escopo, por decisão do plano

- Encerrar a conta (o check-out do servidor faz isso, E04).
- Uma verificação ao vivo da reabertura: a validação da etapa pede inspeção manual; o caminho sem chave e com o token é provado pelo `MockEngine` e pelo servidor HTTP local (M4 da revisão da E08). Fica no handoff o roteiro de inspeção.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: "Reabrir conta" sem as permissões, na conta aberta e sem a porta; a aprovação cancelada, negada e o token expirado; nenhum `Idempotency-Key` no fio, também na repetição aprovada; o resultado desconhecido por rede e por `5xx`, e a repetição; duplo clique; `FOLIO_NOT_CLOSED` com outra estação; troca de sessão com a reabertura em voo; um diálogo por vez com as correções, os pagamentos, as cauções e "Pagador e limite".

## Verificação ao vivo

A inspeção manual contra o `zeny-front` pede as credenciais do usuário e uma conta encerrada (a `LiveCheckOutCheck` da E04 deixa uma). Sem as variáveis no ambiente da sessão, a etapa registra no handoff o roteiro de inspeção e fica "Aguardando verificação ao vivo".
