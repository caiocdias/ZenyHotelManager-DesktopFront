# M4 E05 — Gate do marco M4 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar o M4 como build testável: o seed passa a deixar consumos nas contas dos hospedados, os achados da M3 E06 no `demo-seed` (`B-M4`/`C-M2`, `B-M8`, `C-M8`) ficam resolvidos, o roteiro `docs/demo/roteiro-m4.md` existe, acumula o do M3 e entra no pacote, e a definição de pronto é verificada num clone limpo. A execução do roteiro no Windows, o `smokeTest` e as verificações ao vivo contra o ambiente recriado ficam com o usuário (`Revisão humana: sim`).

**Architecture:** no `demo-seed`, um `FolioSeeder` novo, com a porta `FolioGateway` e o adaptador `SdkFolioGateway` (o padrão do `TraceSeeder`/`SdkTraceGateway`), lança consumos manuais nas contas das reservas hospedadas do seed, de forma idempotente (confere antes por uma marca na descrição). O turno de caixa da Recepcionista é aberto no roteiro, à mão, como parte da demo (H2: o turno é pessoal, e abri-lo pelo seed exigiria entrar como a Recepcionista). Os achados da M3 E06 entram no `SdkPendingGateway`, no `PendingSeeder` e no `demo-seed/build.gradle.kts`. O roteiro é um documento novo, no padrão do `roteiro-m3.md`, e o `tools/server-env/package.sh` passa a levá-lo.

**Tech Stack:** Kotlin, Gradle, bash, Markdown.

**Spec:**
- a seção "E05 — Gate do marco M4" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Restrições e invariantes", as "Notas de máquina e de processo", a hipótese H2 e os handoffs de todas as etapas do M4 (os comandos das verificações ao vivo e os roteiros de inspeção);
- as linhas do `B-M4`/`C-M2`, `B-M8` e `C-M8` na tabela de destinos de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`;
- `docs/roadmaps/README.md` (regra 5, a recriação no dia; regra 7, a validação no Windows) e o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §7.2;
- o plano do gate do M3 (`docs/superpowers/plans/2026-11-17-m3-reservas-e05.md`) como precedente do roteiro, do pacote e das travas.

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** dado pessoal ou segredo (o `detail` do servidor ecoado pelo adaptador do seed, `B-M4`; as senhas e o PIN no roteiro e no pacote); migração ou escrita destrutiva de dados (o seed escreve consumos na demo; o roteiro manda recriar o ambiente); cálculo de valor monetário (os consumos do seed, só valores fixos do catálogo); concorrência (o seed rodado duas vezes não duplica consumos).

## Global Constraints

- **Seed idempotente (design §7.2):** rodar o seed de novo não duplica nada. O `FolioSeeder` marca cada consumo pela descrição ("Seed M4: <item>") e confere os lançamentos da conta antes de lançar.
- **Consumos do seed:** nas contas das reservas hospedadas (`IN_HOUSE`) que o seed deixa, dois consumos por conta, com um código de lançamento ativo que aplica a taxa de serviço (escolhido pelo nome, entre os do catálogo do servidor; sem nenhum que sirva, o seed avisa no relatório e segue, sem falhar). Valores fixos em `DemoCatalog`, como `Money`/string decimal, nunca `Double`. O relatório do seed ganha a linha dos consumos (criados e mantidos).
- **`B-M4`/`C-M2`:** o `SdkPendingGateway` não deixa o `detail` do servidor sair na mensagem nem na causa da exceção que ele lança; o KDoc do `cancelReservation` descreve o que o adaptador devolve.
- **`B-M8`:** o `untouched` do `PendingSeeder` ganha um nome que diz o que guarda; o KDoc do seeder e o `roteiro-m4.md` dizem que o fora de ordem da UH 208 depende da data operacional do dia da recriação. O cálculo da data só muda se o desenvolvedor achar um defeito; a decisão vai ao relatório e ao handoff.
- **`C-M8`:** os opt-ins ao vivo do `demo-seed` (`ZENY_LIVE_*` lidos pela tarefa de teste) entram como `inputs.property` da tarefa `test` do `demo-seed/build.gradle.kts`, no padrão do `app/build.gradle.kts`; mudar um opt-in invalida o resultado em cache. O `outputs.upToDateWhen { false }` atual é revisto: se ele só existe para forçar a reexecução por causa dos opt-ins, sai.
- **Roteiro do M4 (`docs/demo/roteiro-m4.md`):** acumula o `roteiro-m3.md` (que acumula o do M2 e o do M1), no mesmo formato (passos numerados, "Resultado esperado", folha de senhas sem senha impressa), e conta a hospedagem de ponta a ponta: abrir o turno, a conta com os consumos do seed e a taxa de serviço, lançar, estornar com a aprovação do Gerente, extrato na tela e em PDF, ajustar e remover a taxa, transferir e dividir, pagador e limite, receber, reembolsar com a aprovação, registrar, capturar e liberar caução, cauções a vencer, check-out até o saldo zero com a UH Vaga e Suja no mapa, reabrir a conta encerrada, cancelar e recancelar com a multa (`B-M5`) e a isenção, fechar o turno às cegas e o relatório. Cada texto entre aspas do roteiro existe no código (as travas do testador conferem). O roteiro diz que a recriação vale só para o dia (regra 5) e que o fora de ordem da UH 208 depende da data da recriação (`B-M8`).
- **Pacote:** o `tools/server-env/package.sh` leva o `roteiro-m4.md` além dos anteriores, no padrão de como leva o do M3.
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste ou script de `tests/`; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS` (as travas do roteiro e do pacote são do testador: elas ganham o M4 num despacho escopado dele). `SdkPendingGatewayCancelAtaqueTest.kt` e as travas ao vivo do seed em `tests/` passam por essa conferência.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` para o `demo-seed` (e o `app` quando tocado), com `detektMain` e `detektTest`, sempre com `timeout 1500`; script de `tools/` ou `tests/`: `bash tests/run.sh tests/<arquivo>.sh`. Nunca `./gradlew --stop`; nenhum laço de espera. Não rode o seed nem verificações ao vivo (não há servidor nem credenciais na sessão).
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **Seed rodado duas vezes** não duplica consumos. Teste: `secondRunKeepsTheConsumptions` (Task 1).
2. **Sem código de lançamento que aplique a taxa:** o seed avisa e segue. Teste: `noChargeableCodeWarnsAndGoesOn` (Task 1).
3. **`detail` marcado do servidor** não sai na mensagem nem na causa. Teste: `markedDetailNeverLeavesTheAdapter` (Task 2).
4. **Mudar um opt-in ao vivo do seed** invalida a tarefa de teste. Teste ou script: `tests/test_seed_live_optins.sh` (Task 2).
5. **Texto entre aspas do roteiro que não existe no código:** as travas do testador acusam (Task 3, despacho escopado).

## Interfaces

- `demo-seed/.../folio/FolioGateway.kt` (Task 1): `interface FolioGateway { suspend fun reservationFolioId(reservationId: String): String?; suspend fun postingDescriptions(folioId: String): List<String>; suspend fun chargeableCodeId(names: List<String>): String?; suspend fun post(folioId: String, codeId: String, unitPrice: String, description: String) }`; `SdkFolioGateway` sobre o `sdk` (o padrão do `SdkTraceGateway`, com `Idempotency-Key` própria por lançamento e o `detail` do servidor redigido como nos outros adaptadores).
- `demo-seed/.../folio/FolioSeeder.kt` (Task 1): `FolioSeeder(gateway: FolioGateway, report: SeedReport).seed(data: DemoData, seeded: SeededConfig)`, chamado pelo `DemoSeed` depois do `BookingSeeder`.
- `DemoCatalog` (Task 1): os consumos (`ConsumptionSpec(item: String, unitPrice: String)`) e os nomes aceitos do código de lançamento.

## Revisão de arquitetura

- **Veredito:** dispensada pelo orquestrador. Conferido: a etapa não cria nem contraria ADR (não há `ARCHITECTURE.md`); não muda contrato público (o seed só consome operações do contrato fixado, como os outros seeders), persistência, fronteira de módulo nem traz peça nova de runtime. O `FolioSeeder` segue o padrão dos seeders e adaptadores existentes; o roteiro e o pacote seguem o gate do M3. A `revisor-seguranca` confere a dispensa contra o diff na revisão final.

---

### Task 1: Os consumos do seed nas contas dos hospedados

**Objetivo:** o `FolioSeeder`, a porta e o adaptador (Interfaces), ligados no `DemoSeed`, com a linha do relatório, pelas Global Constraints.

**Critérios de aceite:**
- `FolioSeederTest` (novo, porta falsa): `postsTwoConsumptionsOnEachInHouseFolio`; `secondRunKeepsTheConsumptions`; `noChargeableCodeWarnsAndGoesOn`; `reservationWithoutFolioIsSkippedWithAWarning`; `reportCountsCreatedAndKept`.
- `SdkFolioGatewayTest` (novo, `MockEngine`): `postSendsTheCodeThePriceAndAKey`; `postingDescriptionsReadsAllPages`; `serverDetailIsRedacted`.
- Verifica: `timeout 1500 ./gradlew :demo-seed:test :demo-seed:ktlintCheck :demo-seed:detekt :demo-seed:detektMain :demo-seed:detektTest`.

**Áreas tocadas:** `demo-seed/.../folio/` (novos: `FolioGateway.kt`, `FolioSeeder.kt`), `demo-seed/.../sdk/SdkFolioGateway.kt` (novo), `demo-seed/.../sdk/SeedCore.kt` (a porta), `demo-seed/.../DemoSeed.kt`, `demo-seed/.../data/DemoCatalog.kt`, `demo-seed/.../data/SeedReport.kt`; testes do `demo-seed`.

**Tamanho:** ~50 min; 7 arquivos de produção (3 novos).

**Depende de:** nenhuma.

**Interfaces:** produz as das Interfaces.

---

### Task 2: Os achados da M3 E06 no `demo-seed`

**Objetivo:** `B-M4`/`C-M2`, `B-M8` e `C-M8`, pelas Global Constraints.

**Critérios de aceite:**
- `SdkPendingGatewayTest.markedDetailNeverLeavesTheAdapter` (num arquivo do desenvolvedor; o `SdkPendingGatewayCancelAtaqueTest.kt` é do testador): um `detail` marcado do servidor não aparece na mensagem nem na causa da exceção que sai do adaptador (critério `B-M4`/`C-M2`); o KDoc do `cancelReservation` descreve o que ele devolve.
- `PendingSeederTest` verde com o nome novo do `untouched` e o KDoc do fora de ordem da UH 208 (critério `B-M8`); o relatório do desenvolvedor diz se o cálculo da data mudou e por quê.
- `tests/test_seed_live_optins.sh` (novo, do desenvolvedor): prova que cada `ZENY_LIVE_*` lido pelos testes do `demo-seed` é `inputs.property` da tarefa `test` (critério `C-M8`); `bash tests/run.sh tests/test_seed_live_optins.sh`.
- Verifica: `timeout 1500 ./gradlew :demo-seed:test :demo-seed:ktlintCheck :demo-seed:detekt :demo-seed:detektMain :demo-seed:detektTest` e o script.

**Áreas tocadas:** `demo-seed/.../sdk/SdkPendingGateway.kt`, `demo-seed/.../pending/PendingSeeder.kt`, `demo-seed/build.gradle.kts`; testes do `demo-seed` e o script novo.

**Tamanho:** ~40 min; 3 arquivos de produção e um script.

**Depende de:** nenhuma.

**Interfaces:** nenhuma.

---

### Task 3: O roteiro do M4 e o pacote

**Objetivo:** o `docs/demo/roteiro-m4.md` e o `package.sh` levando-o, pelas Global Constraints. As travas do roteiro e do pacote (`tests/test_roteiro_*`, `tests/test_package_roteiro_*`) são do testador: o desenvolvedor não as altera; ele reporta `DONE_WITH_CONCERNS` com o que elas acusarem, e o orquestrador despacha o testador, escopado, para estendê-las ao M4 antes da revisão da fatia.

**Critérios de aceite:**
- O roteiro existe, acumula o do M3 e cobre a hospedagem de ponta a ponta (lista das Global Constraints), com a recriação no dia e o `B-M8`; cada texto entre aspas existe no código (conferido pelo desenvolvedor com `grep` e, depois, pelas travas do testador).
- `bash tools/server-env/package.sh` (com os JARs gerados antes por `timeout 1500 ./gradlew :app:desktopJar :demo-seed:seedJar`) põe o `roteiro-m4.md` em `build/demo-package/`; os testes de pacote existentes continuam verdes (`bash tests/run.sh tests/test_package*.sh`).
- Verifica: os comandos acima e `bash tests/run.sh` (todos os scripts, que leem o roteiro e o pacote).

**Áreas tocadas:** `docs/demo/roteiro-m4.md` (novo), `tools/server-env/package.sh`.

**Tamanho:** ~60 min; 1 script e o roteiro. Se passar de 60 min, o desenvolvedor para e devolve ao orquestrador.

**Depende de:** Task 1 (os consumos do seed que o roteiro cita).

**Interfaces:** consome a linha do relatório do seed da Task 1.

---

## Ordem

Em série: Task 1 → Task 2 → Task 3.

## Fora de escopo, por decisão do plano

- Abrir o turno pelo seed (H2): o roteiro o abre à mão.
- Funcionalidade nova no `app`.
- Rodar o seed, o `smokeTest`, as verificações ao vivo e o roteiro no Windows: ficam com o usuário, na parada da etapa.

## Clone limpo (orquestrador, no fechamento)

Num clone novo do branch, em pasta temporária: `./gradlew build`, `bash tests/run.sh` e `./gradlew ktlintCheck detekt` verdes (critério 1). A saída vai ao handoff.

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:`: o seed rodado duas vezes, sem código que aplique a taxa, sem conta na reserva e com o servidor recusando o lançamento; o `detail` marcado em todos os caminhos do `SdkPendingGateway` e do `SdkFolioGateway`; o opt-in ao vivo do seed mudando sem invalidar a tarefa; o roteiro com texto entre aspas que não existe no código e sem senha impressa; o pacote sem o `roteiro-m4.md`.

## Verificação humana (parada da etapa)

O usuário recria a demo no dia (`down -v`, `up -d`, seed), roda o `smokeTest` e as verificações ao vivo pendentes do M4 (os comandos estão nos handoffs da E03, E09, E04, E08, E10, E12 e E13) e executa o `roteiro-m4.md` no Windows com o pacote. A aprovação fecha a E05 e as etapas que esperam a verificação ao vivo.
