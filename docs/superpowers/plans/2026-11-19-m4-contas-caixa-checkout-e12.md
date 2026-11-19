# M4 E12 — Pagador, limite de crédito e multa de cancelamento — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o texto da multa de uma reserva cancelada mais de uma vez bate com o que a conta mostra (`B-M5`), a isenção da multa é verificada ao vivo, e na conta aberta o usuário troca o pagador (uma empresa do servidor) e o limite de crédito com `If-Match`.

**Architecture:** a multa fica no `ReservationCancelState` (pacote `reservations`), que passa a ler o `penaltiesCharged` da reserva (o total de multas já lançadas, que o contrato devolve) além da multa do cancelamento corrente. A verificação ao vivo da multa e da isenção amplia a `LiveReservationLifecycleCheck` (M3 E04). Para a conta, o `core` ganha a leitura da conta com o `ETag` e o `updateFolio` com `If-Match` na `FoliosFacade`, e uma fachada nova de leitura das empresas ativas (`CompaniesFacade`, só `listCompanies`); o `app` ganha uma porta `FolioAccountSource` (as portas existentes não mudam: arquivos do testador as implementam) e o formulário `FolioAccountFormState` no cabeçalho da conta.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines, Ktor (via `sdk`), Gradle.

**Spec:**
- a seção "E12 — Pagador, limite de crédito e multa de cancelamento" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes" e as "Notas de máquina e de processo"; a linha do `B-M5` na tabela de destinos de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`; o handoff da M3 E04 (`docs/roadmaps/roadmap-m3-reservas.md`, decisão 5: a verificação ao vivo escreve na demo);
- o contrato `api/openapi.yaml`: `updateFolio` (`FolioUpdateRequest`: `payerCompanyId` e `creditLimit`, ambos anuláveis; `If-Match` obrigatório: ausente 428, versão diferente 412; `UNKNOWN_COMPANY`, `COMPANY_INACTIVE`, `FOLIO_CLOSED`), `getFolio` (o servidor devolve o `ETag`, que muda a cada lançamento), `listCompanies` (`active`, `name`, cursor; exige `guests.companies.read`), `ReservationResponse.penaltiesCharged`, `CancellationPenaltyResponse`;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2; os ADRs 0008 e 0013.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** autenticação ou autorização ("Pagador e limite" com `billing.folios.manage`; a escolha da empresa com `guests.companies.read`; a isenção pela aprovação de superior); dado pessoal ou segredo (CNPJ e razão social das empresas; as credenciais e o PIN do aprovador na verificação ao vivo); cálculo de valor monetário (o limite digitado, a multa e o total de multas, só copiados do servidor); concorrência (`If-Match` e `412` com outra estação lançando na conta; a escrita sem `Idempotency-Key`); parsing de entrada externa não confiável (o `penaltiesCharged`, os decimais e a lista de empresas).

## Global Constraints

- **Dinheiro:** `Money` do `core`, string decimal na API, nunca `Double`. O front não soma multas nem calcula saldo: mostra o `penalty.amount` do cancelamento corrente e o `penaltiesCharged` da reserva como o servidor os devolve.
- **Texto da multa no resumo do cancelamento (`B-M5`, `ReservationCancelState.done`):**
  - isenta: "Multa isenta: <amount>." (como hoje);
  - multa nova (`amount` maior que zero): "Multa lançada na conta: <amount>." e, quando `penaltiesCharged` é maior que `amount`, também "Total de multas de cancelamento desta reserva: <penaltiesCharged>.";
  - sem multa nova (`amount` zero) e `penaltiesCharged` maior que zero: "Sem multa nova. A conta já tem <penaltiesCharged> de multa de cancelamento anterior." (nunca "Sem multa.", que hoje contradiz a conta);
  - sem multa nova e `penaltiesCharged` zero: "Sem multa." (como hoje);
  - valor ilegível (o `amount` ou o `penaltiesCharged` fora do formato): não afirma nada sobre a multa (como hoje).
- **Texto da multa no diálogo antes de cancelar:** com a reserva lida e `penaltiesCharged` maior que zero, o diálogo acrescenta "Esta reserva já tem <penaltiesCharged> de multa de cancelamento na conta." abaixo de "A multa é lançada na conta da reserva conforme o prazo da política.". O contrato não tem prévia da multa nova: o front não a calcula.
- **Pagador e limite (`FolioAccountFormState`):** "Pagador e limite" aparece no cabeçalho da conta aberta com `billing.folios.manage` no conjunto da casca e a porta `FolioAccountSource` no wiring. O formulário lê a conta com o `ETag` ao abrir (a versão que vai no `If-Match` é a dessa leitura, nunca a da tela). O `PUT` substitui os dois campos: o formulário sempre envia o pagador e o limite vigentes, mesmo o que não mudou. Limite vazio = "Sem limite" (`creditLimit` nulo); limite digitado pelo `MoneyField` (maior ou igual a zero). Pagador: "Sem pagador" ou uma empresa ativa escolhida na lista do servidor (`listCompanies` com `active=true`, busca por nome, "Carregar mais"); sem `guests.companies.read`, o pagador fica como está e só o limite muda, com o texto "Seu perfil não pode escolher a empresa pagadora.". A empresa aparece pelo nome fantasia ou a razão social; o CNPJ não aparece na lista nem em `toString`.
- **`412` (critério 1):** o texto do catálogo em pt-BR (o `ApiError.PreconditionFailed` já tem texto de pessoa), a conta relida (o painel da conta e o formulário, com o `ETag` novo) e as entradas mantidas para a operadora conferir e salvar de novo. `UNKNOWN_COMPANY`, `COMPANY_INACTIVE` e `FOLIO_CLOSED` mostram o texto do catálogo; `FOLIO_CLOSED` relê a conta.
- **Escrita sem `Idempotency-Key`:** o `updateFolio` é idempotente por natureza (o mesmo corpo com a mesma versão); a falha que `classifyWriteFailure` não dá como definitiva relê a conta e diz "Não foi possível confirmar a alteração. A conta foi relida; confira antes de salvar de novo." (repetir com o `ETag` velho dá `412`, nunca alteração em dobro). Duplo clique: um envio só.
- **Depois do sucesso:** o formulário fecha, a conta e a lista das contas são relidas (o `rereadAfterWrite` do `FolioState`), e o aviso diz "Pagador e limite atualizados.".
- **Permissões (ADR 0013):** só o conjunto da casca que o wiring traz; nenhuma leitura do `me`.
- **Fronteira (ADR 0008):** o `app` fala com o servidor só pelo `core`.
- **Cancelamento:** `catch` de `Exception` seguido de `ensureActive()`.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`. Campos novos de classes que os testes constroem (`FolioWiring`) entram no fim, com padrão.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` por módulo tocado (`core` → `app` e `demo-seed`), com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Segundo cancelamento sem multa nova** com a multa do primeiro na conta: o texto não diz "Sem multa." nem que cobrou de novo. Teste: `secondCancellationKeepsThePreviousPenaltyInTheText` (Task 1).
2. **`penaltiesCharged` fora do formato:** nenhuma afirmação sobre a multa. Teste: `unreadablePenaltiesSayNothing` (Task 1).
3. **Outra estação lança na conta entre a leitura e o salvar:** `412`, texto em pt-BR, conta relida, nada salvo pela metade. Teste: `staleVersionShowsTextAndRereads` (Task 3).
4. **Trocar só o limite** envia o pagador vigente junto (o `PUT` substitui os dois). Teste: `changingOnlyTheLimitKeepsThePayer` (Task 2).
5. **Duplo clique em "Salvar":** um `PUT` só. Teste: `doubleSaveSendsOnce` (Task 2).

## Interfaces

- `core/.../facade/FoliosFacade.kt` (Task 2): `data class VersionedFolio(val folio: FolioResponse, val etag: String)`; `suspend fun versionedFolio(folioId: String): VersionedFolio` (sem `ETag` na resposta: `ApiError.IncompatibleResponse`); `suspend fun updateFolio(folioId: String, etag: String, payerCompanyId: String?, creditLimit: Money?): VersionedFolio` (`If-Match = etag`, sem `Idempotency-Key`; `412` sobe como `ApiError.PreconditionFailed`; `etag` em branco ou limite negativo lança `IllegalArgumentException` antes da rede).
- `core/.../facade/CompaniesFacade.kt` (Task 2, nova, exposta como `ZenyCore.companies`): `data class CompanyChoice(val id: String, val name: String)` (nome fantasia ou, sem ele, a razão social; o `toString` sem CNPJ, que nem é guardado); `suspend fun activeCompanyPage(name: String?, cursor: String?, limit: Int = 50): Page<CompanyChoice>`.
- `app/.../folio/FolioAccountSource.kt` (Task 2): `versionedFolio`, `updateFolio` e `activeCompanyPage` com as mesmas assinaturas; `ServerConnection.folioAccountSource(): FolioAccountSource? = null`, implementada no `CoreConnection`.
- `FolioWiring` (Task 2), no fim e com padrão: `val account: FolioAccountSource? = null`, preenchido pelo `folioWiring` do `AppRoot`.
- `app/.../folio/FolioAccountFormState.kt` (Task 2): `FolioAccountFormState(source: FolioAccountSource, scope: CoroutineScope, folioId: String, canChoosePayer: Boolean, onSaved: () -> Unit, onReread: () -> Unit)` com `start()`, `payer: CompanyChoice?` (nulo = "Sem pagador"), `payerName: String?` (o pagador vigente lido; sem a leitura das empresas, "Empresa pagadora definida"), `limitText`, `companies` (a lista com busca e "Carregar mais"), `busy`, `message`, `canSave`, `save()`, `close()`.

## Revisão de arquitetura

(a preencher pelo passo 2 do orquestrador)

---

### Task 1: O texto da multa (`B-M5`) e a isenção ao vivo

**Objetivo:** o resumo do cancelamento e o diálogo antes de cancelar seguem as Global Constraints da multa, pelo `penaltiesCharged` da reserva; a `LiveReservationLifecycleCheck` ganha a conferência do `B-M5` no recancelamento e um teste novo da isenção.

**Critérios de aceite:**
- `ReservationCancelStateTest` (do desenvolvedor; confira o `C-M6`): `secondCancellationKeepsThePreviousPenaltyInTheText` (critério 2: `amount` zero e `penaltiesCharged` "150.00" dá "Sem multa nova. A conta já tem R$ 150,00 de multa de cancelamento anterior."); `newPenaltyWithPreviousShowsTheTotal`; `firstPenaltyShowsOnlyTheNewOne`; `noPenaltyEverSaysSemMulta`; `waivedSaysWaived`; `unreadablePenaltiesSayNothing`. `ReservationCancelDialogTest`: `dialogWarnsOfThePenaltyAlreadyOnTheFolio` (com `penaltiesCharged` maior que zero) e o texto ausente com zero.
- **Verificação ao vivo:** a `LiveReservationLifecycleCheck` (opt-in `ZENY_LIVE_LIFECYCLE`, já existente) passa a exigir, no recancelamento (`cancelAgainWithoutPenalty`), que o texto da tela diga "Sem multa nova" com o `penaltiesCharged` da reserva relida e que a soma dos lançamentos de multa (código `CANCELLATION_FEE`) não estornados da conta da reserva, lida pela `FolioSource`, seja igual ao `penaltiesCharged` (critério 2). Um teste novo, `cancelWithWaiver`, cancela uma reserva confirmada do seed depois do prazo com "Isentar a multa", aprova como o Gerente se o servidor pedir, e exige o texto "Multa isenta" e a conta da reserva sem lançamento de multa novo, ou com o que a API devolver para a isenção (`penalty.postingId`), e o mesmo no texto (critério 3). Sem reserva que sirva, `NEED_SEED`. Credenciais só do ambiente; nenhuma resposta do `sdk` impressa nem concatenada; a trava `tests/test_live_lifecycle_ataque.sh` continua verde (rode-a). Escreve na demo e não desfaz (KDoc).
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.reservations.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest` e `bash tests/run.sh tests/test_live_lifecycle_ataque.sh`.

**Áreas tocadas:** `app/.../reservations/ReservationCancelState.kt`, `ReservationCancelDialog.kt`; testes de `reservations/` e a verificação ao vivo.

**Tamanho:** ~45 min; 2 arquivos de produção e a verificação ao vivo.

**Depende de:** nenhuma.

**Interfaces:** nenhuma para as outras tarefas.

---

### Task 2: O `PUT` da conta, as empresas e o formulário

**Objetivo:** as leituras e a escrita do `core` (Interfaces), a porta `FolioAccountSource` com o `CoreConnection`, o campo `account` do `FolioWiring` preenchido pelo `AppRoot`, e o `FolioAccountFormState` pelas Global Constraints. Sem tela.

**Critérios de aceite:**
- `FolioAccountFacadeTest` (novo, `MockEngine`): `versionedFolioKeepsTheEtag`; `missingEtagIsIncompatible`; `updateSendsIfMatchBothFieldsAndNoIdempotencyKey`; `nullLimitAndNullPayerAreSentAsNull`; `preconditionFailedRises`; `blankEtagOrNegativeLimitFailBeforeTheNetwork`; `activeCompaniesSendActiveTrueAndName`; `companyChoiceUsesTradeNameThenLegalNameAndNoCnpj`. Verifica: `timeout 1500 ./gradlew :core:test --tests 'com.zeny.desktop.core.facade.Folio*' --tests 'com.zeny.desktop.core.facade.Compan*' :core:ktlintCheck :core:detekt :core:detektMain :core:detektTest`; o trio do `app` e o do `demo-seed` verdes.
- `FolioAccountFormStateTest` (novo, porta falsa): `startReadsTheFolioWithItsVersion`; `changingOnlyTheLimitKeepsThePayer`; `emptyLimitIsNoLimit`; `choosingACompanySendsItsId`; `withoutCompanyReadThePayerStaysAndSaysSo`; `doubleSaveSendsOnce`; `unknownResultRereadsAndSaysToCheck`; `companyInactiveShowsTheCatalogText`; `successCallsOnSavedAndCloses`; `closeDropsLateAnswers`.
- `CoreConnectionTest.folioAccountSourceIsTheFacade` (servidor HTTP local) e `FolioWiringTest.wiringCarriesTheAccountSource` (confira o `C-M6`). Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' --tests 'com.zeny.desktop.app.session.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `core/.../facade/FoliosFacade.kt`, `core/.../facade/CompaniesFacade.kt` (nova), `core/.../ZenyCore.kt`, `app/.../folio/FolioAccountSource.kt` (nova), `app/.../folio/FolioAccountFormState.kt` (nova), `app/.../folio/FolioWiring.kt`, `app/.../session/ServerConnection.kt`, `app/.../AppRoot.kt` (só o `folioWiring`); testes.

**Tamanho:** ~60 min; 8 arquivos de produção (3 novos). Se passar de 60 min, o desenvolvedor para e devolve ao orquestrador; não há corte pré-autorizado.

**Depende de:** nenhuma.

**Interfaces:** produz as do `core`, a porta, o campo `account` e o `FolioAccountFormState`, que a Task 3 consome.

---

### Task 3: "Pagador e limite" no cabeçalho da conta

**Objetivo:** o botão "Pagador e limite" no cabeçalho da conta (`FolioScreen`) e o diálogo do `FolioAccountFormState` (o pagador vigente, "Sem pagador", a busca das empresas com "Carregar mais", o limite pelo `MoneyField` e "Salvar"), aberto pelo `FolioState` como mais um diálogo do painel (um diálogo por vez), com o `412` pelas Global Constraints.

**Critérios de aceite:**
- `FolioAccountDialogTest` (novo): `staleVersionShowsTextAndRereads` (critério 1: o `412` mostra o texto em pt-BR, o painel da conta relido e o formulário com a versão nova; as entradas ficam); `buttonOnlyWithManageAndOpenFolio`; `searchAndChooseACompany` (sem CNPJ na árvore de UI); `oneDialogAtATime`; `successRereadsAndNotifies` ("Pagador e limite atualizados.").
- Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.folio.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `app/.../folio/FolioAccountDialog.kt` (novo), `FolioState.kt` (abrir e fechar o diálogo, reler), `FolioScreen.kt` (o botão), `FolioText.kt`; testes de `folio/`.

**Tamanho:** ~45 min; 4 arquivos de produção (1 novo).

**Depende de:** Task 2.

**Interfaces:** consome as da Task 2.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3.

## Fora de escopo, por decisão do plano

- Cadastro de empresas (M5): só a leitura das ativas para escolher o pagador.
- Verificação ao vivo do pagador e do limite: a validação da etapa pede inspeção manual; o `412` é provado por teste. Fica no handoff o roteiro de inspeção.
- Prévia da multa antes de cancelar: o contrato não a tem.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: o texto da multa em cada combinação de `amount`, `waived` e `penaltiesCharged` (zero, maior, menor, ilegível); "Pagador e limite" sem `billing.folios.manage`, sem `guests.companies.read` e na conta encerrada; o `412` com o `ETag` velho e a repetição depois do resultado desconhecido; a lista de empresas paginada, vazia e com CNPJ (que nunca aparece); o limite negativo e vazio; duplo clique; a troca de sessão com o formulário aberto; as travas da verificação ao vivo do ciclo (o PIN do aprovador e as respostas do `sdk`).

## Verificação ao vivo e critérios que dependem do servidor

O critério 2 (parte ao vivo) e o 3 pedem o `zeny-front` e as credenciais do usuário e do Gerente. Sem as variáveis no ambiente da sessão, a etapa registra no handoff o comando pronto e fica "Aguardando verificação ao vivo".
