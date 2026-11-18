# M4 E06 — Fonte única de permissões — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** as áreas do app passam a usar o conjunto de permissões do `me` que a casca leu na sessão vigente (ADR 0013): só o `ShellState` lê o `me`, e a falha dessa leitura é a falha da casca.

**Architecture:** o `ShellState` guarda uma cópia imutável de `me.permissions`, atribuída junto com o menu e zerada no início de cada `reload()`. A `ShellScreen` só desenha a área com o conjunto presente e o entrega ao `AreaContent`, que o repassa por parâmetro às áreas e aos *state holders* (construtor). Saem as leituras por área (`permissions()` das portas `ReceptionSource`, `AvailabilitySource` e `TracesSource`), o `AreaPermissionsState`, o `PermissionsNotice` e o fallback "falha vira conjunto vazio" do `DayPanelState` e do `TracesListState`. Tudo no módulo `app`; o `core` não muda (a fachada `account.me()` fica como está).

**Tech Stack:** Kotlin, Compose Multiplatform (testes de UI com `runComposeUiTest`), kotlinx.coroutines, Gradle.

**Spec:**
- a seção "E06 — Fonte única de permissões" de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md`;
- o [ADR 0013](../../adr/0013-fonte-unica-de-permissoes-por-sessao.md) (a decisão que esta etapa executa);
- as "Restrições e invariantes" e as "Notas de máquina e de processo" do mesmo roadmap;
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md`, §6.2.

Não há `ARCHITECTURE.md` no repositório: o plano argumenta com a spec, o design, os ADRs e o código.

**Riscos:** autenticação ou autorização (o conjunto decide que ações e abas aparecem); concorrência (troca de sessão e `reload()` com a leitura do `me` em voo).

## Global Constraints

- **Permissões (ADR 0013):** as áreas e o topo da casca usam o conjunto de permissões que o `ShellState` leu na sessão vigente, recebido por parâmetro. Nenhuma área lê o `me` por conta própria, e a falha dessa leitura é a falha da casca. Sem leitura boa, nenhuma área é desenhada; nenhum conjunto sobrevive à troca de `sessionId`. Não há releitura no meio da sessão.
- **O servidor decide:** a mudança não altera o que o servidor autoriza; `403` e `APPROVAL_REQUIRED` continuam tratados por ação.
- **Regras transversais (design §6.2):** nada de JSON ou `code` cru ao usuário; permissões efetivas, com o servidor decidindo.
- **Fronteira (ADR 0008):** as features do `app` falam com o servidor só pelo `core`; nenhum `sdk` cru em `app/src/main`. O `core` não muda nesta etapa.
- **Idioma:** UI e documentação em pt-BR; código em inglês.
- **Cancelamento:** leitura em voo que é cancelada não escreve estado; `catch` de `Exception` (nunca `Throwable`) seguido de `currentCoroutineContext().ensureActive()`, como o `ShellState` já faz.
- **Testes de UI rolam até a linha antes do clique:** teste que procura por texto uma linha que não é a primeira de uma lista ou tabela chama `performScrollToNode` antes do clique (exemplo: `ReservationsActionsTest.kt`, correção `45c4ce4`).
- **Arquivos do testador (`C-M6`), por ruling desta etapa:** a remoção das portas `permissions()` e a mudança dos construtores quebram a compilação de arquivos de teste que têm commit `Soul: testador` (conferir com `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`). Nesses arquivos o desenvolvedor faz **só a adaptação forçada**: tirar o `override` da porta removida; levar o mesmo conjunto de permissões que a porta falsa devolvia para o `me` da conexão falsa ou para o parâmetro novo; passar o parâmetro novo ao construtor. Ele não remove nem afrouxa asserção. Um teste cujo assunto deixa de existir (o `AreaPermissionsState`, o `PermissionsNotice`, a porta `permissions()` das fixtures) é apagado, e o relatório da tarefa lista cada um com o teste novo ou existente que cobre a mesma garantia com força igual ou maior. O relatório também lista todo arquivo do testador alterado. O testador de etapa audita cada um deles.
  - **Por que diverge do ADR 0013 (Consequências) e da nota `C-M6` do roadmap:** a remoção da porta e a quebra da compilação dos testes acontecem no mesmo commit; o testador não toca produção, e o desenvolvedor não consegue deixar a verificação da fatia verde sem a adaptação. **Alternativa descartada:** a nota N2 do roadmap, com o testador adaptando as portas falsas num commit próprio. Antes da fatia, o `override` de uma porta que ainda existe não pode sair; logo depois dela, a fatia ficaria com a compilação dos testes vermelha entre dois despachos, sem verificação nem revisão possível. O que a nota protege (nenhuma asserção do testador perde força) fica com a `check_test_integrity.py`, a revisão da fatia e a auditoria do testador de etapa. A ruling vai às "Decisões para o usuário" do handoff.
  - **Notas do roadmap substituídas:** "testador nas tarefas que mexem na entrega do conjunto" vira o testador de etapa único (regra do orquestrador); as fixtures ficam na fatia que remove a porta, pelo motivo acima.
- **Verificação da tarefa:** `timeout 1500 ./gradlew :app:test --tests '<filtro>' :app:ktlintCheck :app:detekt :app:detektMain :app:detektTest`, com os filtros da tarefa (`--tests` repetido). Nunca `./gradlew --stop`; se aparecer "daemon disappeared", repita uma vez. Nenhum laço de espera; todo comando longo com `timeout`.
- **Commits:** em pt-BR, no estilo do histórico, com o trailer `Soul: desenvolvedor` e `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` no último parágrafo.

## Review Focus

1. **Troca de sessão com o `me` da sessão nova em voo:** a área da sessão nova não é desenhada com o conjunto da anterior, e o `ShellState` não expõe o conjunto velho do início do `reload()` até a resposta. Teste: `reloadClearsPermissionsBeforeTheAnswer`, `newSessionAreaNeverSeesThePreviousSet` e `areaWithForeignSessionDrawsNothing` (Task 1).
2. **`me` que falha:** a casca mostra o `ErrorState`, nenhuma área é desenhada; "Tentar de novo" com o `me` bom desenha a área com as ações das permissões lidas. Teste: `failedMeDrawsNoAreaAndRetryGrants` (Task 1).
3. **Conjunto exposto que alguém tenta mudar:** o conjunto do `ShellState` é cópia imutável; mudar a coleção que o `me` devolveu, depois da leitura, não muda o conjunto. Teste: `permissionsAreAnImmutableCopy` (Task 1).
4. **Leitura única:** abrir a casca e depois a Recepção, as Reservas e os Recados da Recepção, em sequência, faz uma leitura do `me` só. Teste: `oneMeReadAcrossAreas` (Task 2).
5. **Tempo real pelo conjunto:** sem `rooms.status.read` nem `endofday.runs.read`, o painel não assina; com uma delas, assina só o tópico dela. Teste: `subscribesTopicsFromTheReceivedSet` (Task 2).

## Interfaces

Valem para as duas tarefas; os nomes são fixos.

- `ShellState.permissions: Set<String>?` — `null` do início de cada `reload()` até o `me` e a data darem certo; então uma cópia imutável de `me.permissions` (por exemplo, `java.util.Set.copyOf`), atribuída no mesmo bloco que `areas`. Continua `null` com `error`. Setter privado, observável (`mutableStateOf`).
- `ShellScreen(state: ShellState, onLogout: () -> Unit, areaContent: @Composable (Area, Set<String>) -> Unit = { area, _ -> EmptyState(area.label) })` — chama `areaContent(selected, permissions)` só quando `state.permissions != null` (com `loading` e `error` como hoje).
- `internal fun AreaContent(area: Area, connection: ServerConnection, sessionId: String?, permissions: Set<String>)` — `sessionId` é o da casca (a mesma variável que é chave do `remember(sessionId) { ShellState(…) }` no `AppRoot`), não um lido de novo de `connection.state`. Repassa os dois às áreas que os usam (a Reservas na Task 1; a Recepção só na Task 2, para não deixar parâmetro sem uso no detekt).
- **Amarração sessão × conjunto (por construção):** cada área que recebe o conjunto desenha só quando o `sessionId` ativo da conexão (`connection.state`, como hoje) é igual ao `sessionId` recebido da casca e não nulo; senão o quadro fica vazio. Os *state holders* que recebem o conjunto nascem com `remember(source, sessionId, permissions)`, com o `sessionId` recebido. Assim, no quadro em que a conexão já está na sessão B e a casca ainda é a da A, nenhuma área cria *holder* nem assina tempo real com o conjunto de A.
- `DayPanelState(source: ReceptionSource, scope: CoroutineScope, permissions: Set<String>)` — `val permissions: Set<String>` deixa de ser `var`; `listen()` assina `topicsFor(permissions)` sem ler a porta. O `CheckInState` e o `WalkInState` continuam recebendo `panel.permissions`, que agora é o conjunto da casca.
- `TracesListState(source: TracesSource, scope: CoroutineScope, permissions: Set<String>, reservationId: String? = null)` — `val permissions: Set<String>`; sai `readPermissions()`.
- Saem: `ReceptionSource.permissions()`, `AvailabilitySource.permissions()`, `TracesSource.permissions()` e as implementações em `CoreConnection`; `reservations/AreaPermissions.kt` inteiro (`AreaPermissions`, `AreaPermissionsState`, `PermissionsNotice`); `reservationsPermissions` em `AppRoot.kt`.

---

### Task 1: A casca guarda e entrega o conjunto; as Reservas o consomem

**Objetivo:** o `ShellState` guarda e expõe o conjunto (Interfaces), a `ShellScreen` o entrega ao `AreaContent`, e a área Reservas usa o conjunto recebido nas ações do detalhe, na aba de disponibilidade e na nova reserva. Saem o `AreaPermissionsState`, o `PermissionsNotice`, `reservationsPermissions` e a `AvailabilitySource.permissions()` com a implementação. A Recepção não recebe o conjunto nesta fatia (o `AreaContent` só o repassa à Reservas; a Task 2 liga a Recepção).

**Critérios de aceite:**
- `ShellStateTest`: `permissionsFollowTheSuccessfulMe` (com o `me` e a data bons, `permissions == me.permissions`); `reloadClearsPermissionsBeforeTheAnswer` (com um `me` preso num `CompletableDeferred`, logo depois de `reload()` o `permissions` é `null` e assim fica até a resposta); `failedMeLeavesPermissionsNull` (`me` falha → `error != null` e `permissions == null`; a data que falha também deixa `null`); `permissionsAreAnImmutableCopy` (mudar o `MutableSet` que o `me` falso devolveu, depois da leitura, não muda `permissions`; e `(permissions as MutableSet).add(…)` lança). Verifica: `--tests 'com.zeny.desktop.app.shell.ShellStateTest'`.
- `AppRoot`/casca (teste de UI, arquivo novo `app/src/test/kotlin/com/zeny/desktop/app/shell/ShellPermissionsTest.kt`): `failedMeDrawsNoAreaAndRetryGrants` — com o `me` falhando, aparece o `ErrorState` e nenhum texto da área Reservas; o "Tentar de novo" relê, e com o `me` bom (com `reservations.reservations.manage`) as ações da reserva aparecem; `newSessionAreaNeverSeesThePreviousSet` — sessão A com `reservations.availability.read`, troca para a sessão B cujo `me` está em voo e não tem a permissão: até a resposta, nenhuma área desenhada; depois, a aba de disponibilidade não aparece. `areaWithForeignSessionDrawsNothing` (no nível do `AreaContent`, sem a casca): compor `AreaContent(Area.RESERVATIONS, conn, sessionId = "A", permissions = conjuntoA)` com a conexão já ativa na sessão `"B"` não desenha a área nem chama a porta de busca; com `sessionId = "B"`, desenha. Verifica: `--tests 'com.zeny.desktop.app.shell.*'`.
- O comportamento por permissão da M3 continua verde com o conjunto entregue pela casca: a aba de disponibilidade só com `reservations.availability.read`; as ações da reserva só com `reservations.reservations.manage`. Verifica: `--tests 'com.zeny.desktop.app.reservations.*' --tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.ShellRoot*' --tests 'com.zeny.desktop.app.reception.*Shell*' --tests 'com.zeny.desktop.app.reception.StayActionDialogs*' --tests 'com.zeny.desktop.app.rooms.*Shell*'` (os testes que montam o `AppRoot` ou chamam `AreaContent` mudam de forma; nota N3 do roadmap).
- `grep -rnE 'AreaPermissionsState|PermissionsNotice' app/src/main` não devolve linha; `grep -n 'fun permissions' app/src/main/kotlin/com/zeny/desktop/app/reservations/AvailabilitySource.kt` não devolve linha.
- O relatório lista cada asserção apagada com o teste que a substitui (Global Constraints, `C-M6`); o orquestrador confere a lista contra os `ASSERCAO_REMOVIDA` e `DEV_TESTE_DO_TESTADOR` da `check_test_integrity.py`. Em especial: "nunca conceder sem uma leitura que deu certo" → `failedMeDrawsNoAreaAndRetryGrants`; "a leitura de uma sessão nunca vale na outra" → `newSessionAreaNeverSeesThePreviousSet`.

**Áreas tocadas:** `app/.../shell/` (`ShellState.kt`, `ShellScreen.kt`), `app/.../AppRoot.kt`, `app/.../reservations/` (`AreaPermissions.kt` removido, `AvailabilitySource.kt`), `app/.../session/ServerConnection.kt`; testes em `app/src/test/.../shell/`, `.../reservations/` (inclusive `LiveAvailabilityReaders.kt`, que sobrescreve a porta), `app/src/test/.../AppRoot*`, `ShellRootAtaqueTest.kt` e os testes de `reception/` e `rooms/` que chamam `AreaContent` ou montam o `AppRoot`.

**Tamanho:** ~50 min; 6 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces:** produz `ShellState.permissions`, a assinatura nova da `ShellScreen` e do `AreaContent` (bloco Interfaces), que a Task 2 consome.

---

### Task 2: A Recepção e os Recados consomem o conjunto; as portas de leitura saem

**Objetivo:** o painel do dia (e, por ele, o check-in, o walk-in e as demais ações) e os Recados da Recepção usam o conjunto recebido pelo `AreaContent`. Saem `ReceptionSource.permissions()` e `TracesSource.permissions()` com as implementações em `CoreConnection`, a leitura em `DayPanelState.listen()` e `TracesListState.readPermissions()`, e o fallback "falha vira conjunto vazio".

**Critérios de aceite:**
- `DayPanelStateTest`: `subscribesTopicsFromTheReceivedSet` — com `rooms.status.read` no conjunto, assina o tópico `rooms`; com `endofday.runs.read`, o `end-of-day`; sem nenhuma, não chama `events`. A porta falsa não tem mais leitura de permissões. Verifica: `--tests 'com.zeny.desktop.app.reception.DayPanelState*'`.
- Teste de UI (`ReceptionShellTest` ou arquivo novo em `reception/`): `walkInGetsTheShellSet` — o `WalkInState` aberto pelo cabeçalho recebe o mesmo conjunto da casca (com `guests.profiles.manage` no `me`, "criar hóspede" disponível; sem ela, não). Verifica: `--tests 'com.zeny.desktop.app.reception.*Shell*'`.
- `TracesListStateTest`: `canEdit` e `canResolve` seguem o conjunto do construtor (`frontdesk.traces.manage` e `frontdesk.traces.resolve`), sem chamada à porta. Verifica: `--tests 'com.zeny.desktop.app.traces.*'`.
- Teste de UI, arquivo novo `app/src/test/kotlin/com/zeny/desktop/app/shell/ShellMeReadCountTest.kt`: `oneMeReadAcrossAreas` — conexão falsa que conta as chamadas a `me()`; abrir a casca, depois a Recepção (com os Recados visíveis), as Reservas e de novo a Recepção registra `1`. Verifica: `--tests 'com.zeny.desktop.app.shell.ShellMeReadCountTest'`.
- Editar e resolver recado só com as permissões de cada ação: os testes existentes continuam verdes com o conjunto entregue pela casca. Verifica: `--tests 'com.zeny.desktop.app.reception.*' --tests 'com.zeny.desktop.app.traces.*' --tests 'com.zeny.desktop.app.shell.*' --tests 'com.zeny.desktop.app.AppRoot*' --tests 'com.zeny.desktop.app.reservations.*' --tests 'com.zeny.desktop.app.rooms.*Shell*'`.
- `grep -rn 'fun permissions' app/src/main` não devolve linha; `grep -rn 'account\.me()' app/src/main` devolve só `CoreConnection.me()`; `grep -rnE '\.me\(\)' app/src/main` devolve só essa linha e a chamada no `ShellState`.
- O relatório lista as asserções apagadas e os arquivos do testador alterados, como na Task 1.
- Os checks ao vivo que usam a porta (`reception/LiveCheckInCheck.kt`, `reception/LiveWalkInCheck.kt`, `reservations/LiveBookingFlowCheck.kt`, `reservations/LiveReservationLifecycleCheck.kt`) passam a ler `connection.me().permissions` (só em teste; o grep do critério cobre `app/src/main`). Eles só rodam com `ZENY_LIVE_*`: a fatia os compila, e o handoff registra se rodaram.

**Áreas tocadas:** `app/.../reception/` (`DayPanelState.kt`, `ReceptionSource.kt`), `app/.../traces/` (`TracesListState.kt`, `TracesSource.kt`), `app/.../AppRoot.kt`, `app/.../session/ServerConnection.kt`; testes em `app/src/test/.../reception/`, `.../traces/`, `.../shell/`, `.../reservations/` (`ReservationsActionsTest.kt` e `ReservationsActionsAtaqueTest.kt` embrulham a `ReceptionSource`; os dois checks ao vivo) e as conexões falsas que implementam as portas.

**Tamanho:** ~55 min; 6 arquivos de produção.

**Depende de:** Task 1 (`ShellState.permissions` e o parâmetro do `AreaContent`).

**Interfaces:** consome as da Task 1; produz os construtores novos de `DayPanelState` e `TracesListState` (bloco Interfaces).

---

## Testador de etapa

Uma vez, sobre `MERGE_BASE..HEAD`, com foco nos `Riscos:` e:
- ataca a entrega do conjunto: troca de sessão com o `me` em voo, `reload()` repetido, `me` que falha e depois dá certo, conjunto mutável vindo do `me`;
- audita cada arquivo com commit `Soul: testador` que o desenvolvedor alterou (lista nos relatórios das tarefas): confere que nenhuma asserção perdeu força e reescreve o que precisar;
- confere o comportamento por permissão da M3 (critério 7 da etapa) com o conjunto da casca.

## Validação manual

A inspeção contra o `zeny-front` como Recepcionista e como Governanta (roadmap, "Validação obrigatória") pede clicar no app: fica como pendência do usuário no handoff. O orquestrador roda, se o ambiente estiver de pé, as verificações ao vivo que passam pelas permissões e registra o resultado.

## Destino dos Menores M1, M2 e M5 da revisão final da M3 E06

Os três apontam para o `AreaPermissionsState` (M1: o cancelamento alheio; M2: o `catch (e: Throwable)`) e para um teste dele (M5: o nome de `leavingWhileReadIsHeldDropsTheLateAnswer`). A Task 1 apaga o arquivo e o teste; o handoff registra o commit da remoção, que a E01 cita. A leitura que fica, no `ShellState`, já usa `catch (e: Exception)` com `ensureActive()`.
