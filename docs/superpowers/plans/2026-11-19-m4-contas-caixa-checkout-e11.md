# M4 E11 — Duplicação entre o painel do dia e a busca — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o painel do dia (Recepção) e a busca de reservas montam as colunas da tabela, leem os nomes dos tipos de UH e travam as leituras auxiliares (titulares e tipos) pela mesma função, sem mudar o comportamento de nenhuma das duas telas.

**Architecture:** o destino é o `app/.../reception/ReservationRows.kt` que já existe (o pacote `reservations` importa `reception`, não o contrário; I8 da revisão de arquitetura do plano da E04). Ele ganha as colunas da tabela de reservas, a leitura única dos nomes dos tipos de UH e um ajudante que guarda a trava (`Mutex`), o cache dos tipos e o `HolderNames`, e converte uma página de `ReservationListItemResponse` em `ReservationRow`. As duas telas usam portas diferentes (`ReceptionSource` e `ReservationSearchSource`), então o ajudante recebe as leituras como funções.

**Tech Stack:** Kotlin, Compose Multiplatform, kotlinx.coroutines.

**Spec:** a seção "E11 — Duplicação entre o painel do dia e a busca" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`, com as "Notas de máquina e de processo"; o plano da E04 (`docs/superpowers/plans/2026-11-19-m4-contas-caixa-checkout-e04.md`, "Fora de escopo", nota I8).

Não há `ARCHITECTURE.md` no repositório.

**Riscos:** concorrência (a trava que impede a mesma leitura de titular ou de tipos sair duas vezes com as abas do painel carregando em paralelo; a releitura dos tipos a cada carga do painel; a busca fechada no meio de uma página).

## Global Constraints

- **Sem mudança de comportamento:** as colunas (ids, títulos e ordem), o texto de cada célula, o "—" do tipo sem nome, a falha da leitura dos tipos (`ApiError.Problem` vira mapa vazio; as demais sobem), a leitura dos tipos uma vez por carga, os titulares lidos uma vez cada e no máximo 4 em paralelo continuam como estão.
- **O que cada tela guarda:** o painel do dia esquece os tipos a cada carga da data operacional (hoje `roomTypeNames = null` no `DayPanelState`); a busca os lê uma vez enquanto vive. A busca continua sem sair à rede para titulares e tipos quando foi fechada durante a página (o `if (closed)` antes da trava fica nela).
- **Testes existentes sem afrouxar:** os testes das duas telas (`DayPanelState*Test`, `ReservationSearchState*Test`) e os de `ReservationRows*Test` continuam verdes sem mudar asserção. `ReservationRowsAtaqueTest.kt` é do testador (`C-M6`: 1): não o altere.
- **Testes de UI rolam até a linha antes do clique** (`performScrollToNode`).
- **Arquivos do testador (`C-M6`):** confira `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'` antes de alterar um teste; com contagem maior que zero, não o altere e reporte `DONE_WITH_CONCERNS`.
- **Verificação da tarefa:** a `verificação rápida` do `CLAUDE.md` para o `app`, com `detektMain` e `detektTest`, sempre com `timeout 1500`. Nunca `./gradlew --stop`; nenhum laço de espera.
- **Commits:** em pt-BR, com `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` juntos no último parágrafo.

## Review Focus

1. **As abas do painel carregando em paralelo:** cada titular e os tipos saem à rede uma vez só. Teste: `parallelPagesReadEachHolderAndTheTypesOnce` (no teste novo do ajudante).
2. **Nova carga do painel relê os tipos; a busca não.** Testes: `forgetRoomTypesRereadsOnTheNextPage` (ajudante) e os testes existentes das duas telas.
3. **Leitura dos tipos recusada pelo servidor** vira "—" sem derrubar a página; falha de rede sobe. Teste: `roomTypesProblemGivesDashesAndNetworkFailureRises`.

## Interfaces

- `app/.../reception/ReservationRows.kt`:
  - `internal val reservationColumns: List<Column<ReservationRow>>` — as oito colunas de hoje, na mesma ordem;
  - `internal suspend fun readRoomTypeNames(read: suspend () -> List<RoomTypeResponse>): Map<String, String>` — a única `fun readRoomTypeNames` em `app/src/main`;
  - `internal class ReservationRowResolver(readHolder: suspend (String) -> HolderSummary, readRoomTypes: suspend () -> List<RoomTypeResponse>)` com `suspend fun rows(items: List<ReservationListItemResponse>): List<ReservationRow>` (sob a trava: os tipos da cache ou lidos, os titulares pelo `HolderNames`) e `fun forgetRoomTypes()`.

## Revisão de arquitetura

- **Veredito:** dispensada pelo orquestrador. Conferido: a etapa não cria nem contraria ADR (não há `ARCHITECTURE.md`); não muda contrato público, persistência, fronteira de módulo nem traz peça nova de runtime. É uma refatoração interna ao módulo `app`, entre dois pacotes cuja direção de dependência (`reservations` → `reception`) já existe e não muda. A `revisor-seguranca` confere a dispensa contra o diff na revisão final.

---

### Task 1: As colunas, os tipos de UH e a trava numa função só

**Objetivo:** criar no `ReservationRows.kt` o que as Interfaces dizem e trocar o código copiado do `DayPanelState` e do `ReservationSearchState` (as `columns`, o `roomTypeNames`, o `lookups`, o `holderNames` e o `readRoomTypeNames` de cada um) por ele, pelas Global Constraints.

**Critérios de aceite:**
- `grep -rn 'fun readRoomTypeNames' app/src/main` devolve uma linha só, e `grep -rnE 'Column\("code", "Reserva"\)' app/src/main` também (critério da etapa).
- `ReservationRowResolverTest` (novo, leituras falsas): `parallelPagesReadEachHolderAndTheTypesOnce`; `forgetRoomTypesRereadsOnTheNextPage`; `roomTypesProblemGivesDashesAndNetworkFailureRises`; `rowsKeepTheServerOrder`; `columnsAreTheEightOfToday` (ids e títulos na ordem).
- Os testes existentes das duas telas e de `ReservationRows*` continuam verdes, sem mudar asserção. Verifica: `timeout 1500 ./gradlew :app:test --tests 'com.zeny.desktop.app.reception.*' --tests 'com.zeny.desktop.app.reservations.*' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`.

**Áreas tocadas:** `app/.../reception/ReservationRows.kt`, `app/.../reception/DayPanelState.kt`, `app/.../reservations/ReservationSearchState.kt`; um teste novo em `reception/`.

**Tamanho:** ~30 min; 3 arquivos de produção (nenhum novo).

**Depende de:** nenhuma.

**Interfaces:** produz as das Interfaces.

---

## Fora de escopo, por decisão do plano

- Funcionalidade nova, e qualquer outra duplicação entre as duas telas (filtros, paginação, `TableState`).

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco no `Riscos:`: as abas do painel em paralelo e a trava; a nova carga do painel com a página anterior ainda em voo; a busca fechada durante a página; a falha da leitura dos tipos e dos titulares; e que nenhum teste existente perdeu asserção (o critério da etapa).
