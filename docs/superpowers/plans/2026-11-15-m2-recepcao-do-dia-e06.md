# M2 E06 — Gate do marco M2 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o M2 vira a build de demo: o seed tem dados para todos os fluxos do marco (falta só recados de exemplo), o roteiro `docs/demo/roteiro-m2.md` conta "um dia na recepção" acumulando o do M1, o pacote da demo leva o roteiro novo e a definição global de pronto é verificada num clone limpo; a execução no Windows fica com o usuário (regra 7 do `docs/roadmaps/README.md`).

**Architecture:** no `demo-seed`, uma porta `TraceGateway` com o adaptador sobre o `sdk` e um `TraceSeeder` idempotente que cria recados de exemplo ligados a reservas e UHs do seed. Nos docs, o `roteiro-m2.md` (passo a passo único, do zero, como o do M1) e o `tools/server-env/package.sh` passando a levar os dois roteiros. O orquestrador faz a verificação do gate (clone limpo, ambiente recriado, seed em menos de 2 minutos, `smokeTest`, verificações ao vivo, pacote).

**Tech Stack:** o do M1/M2; nenhuma dependência nova.

**Spec:**
- a seção "E06 — Gate do marco M2" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md`, com a "Definição global de pronto", e os handoffs da E01 à E07 (em especial as notas para o roteiro: a Recepcionista com a role de consulta de setores, a ficha impressa para "Microsoft Print to PDF", o "Salvar como…", a escala de tela e o diálogo de impressão);
- o handoff da M1 E07 (`docs/roadmaps/roadmap-m1-fundacao.md`) e o `docs/demo/roteiro-m1.md`, modelo do roteiro e do pacote;
- os ADRs 0003, 0006 e 0009.

**Fatos que o plano usa:**
- O seed já tem chegadas sem UH (`A3`, `A4`) e hospedados para a troca (`H1` a `H5`) em `DemoCatalog.reservations`; o roteiro do M1 lista as linhas da saída do seed (o teste `tests/test_roteiro_m1_ataque.sh` compara com a ordem do `SeedItem`).
- `POST /api/v1/traces` não é idempotente no servidor: o seeder precisa listar antes (`GET /api/v1/traces` com o filtro da reserva ou da UH) e só criar o que falta, reconhecendo o recado pelo texto exato.
- O administrador do seed lê os setores (`GET /api/v1/departments`).
- O app é aberto no roteiro com `-Dzeny.devShell=true` (D6 do M1, decidida pelo usuário).

## Global Constraints

- **Seed:** só dados fictícios; idempotente (rodar duas vezes não duplica nada); o relatório ganha a linha `recados: N criados, M já existiam` na ordem do `SeedItem`; continua abaixo de 2 minutos; nenhum segredo em saída ou mensagem.
- **Recados de exemplo:** três, com vencimento na data operacional: um para a Governança ligado à UH `101` ("Trocar o enxoval extra da UH antes da chegada."), um para a Manutenção ligado à reserva do slot `H1` ("Verificar o ar-condicionado, reclamação do hóspede."), um para a Recepção ligado à reserva do slot `A1` ("Confirmar o horário de chegada por telefone."). Nenhum dado pessoal no texto.
- **Roteiro:** pt-BR, passo a passo único e do zero como o `roteiro-m1.md` (numa pasta nova, folha de senhas, recriar a demo, `.env`, servidor, admin, seed, app com `-Dzeny.devShell=true`); acumula os passos do M1 e acrescenta, com "Resultado esperado" em cada passo: painel do dia (chegadas, saídas, hospedados); check-in de uma chegada sem UH com alocação; ficha de registro aberta, "Salvar como…" (cancelar; salvar; salvar sobre um arquivo existente) e "Imprimir" para "Microsoft Print to PDF" (e cancelar), conferindo que o diálogo de impressão abre na frente e a escala de 100%; walk-in; troca de UH; desfazer check-in com a aprovação do Gerente (login e PIN da folha; PIN errado mostra a mensagem); recados (aba "Recados", criar no detalhe e na aba, resolver, editar em dois lugares para ver o aviso de "Alguém alterou este recado"); e um aviso de que uma Recepcionista com o modelo padrão de role vê a mensagem de permissão em "Novo recado".
- **Pacote:** `tools/server-env/package.sh` leva `roteiro-m2.md` além do `roteiro-m1.md`; os testes do pacote continuam valendo.
- **Testes:** testes existentes só mudam onde a tarefa manda; nada de dormir.
- **Gate e commits:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes antes de cada commit; nunca `./gradlew --stop`; commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **Seed rodado duas vezes** sobre o mesmo ambiente não duplica recados (o servidor não é idempotente na criação). Teste `secondRunCreatesNothing` (Task 2).
2. **Setor ou reserva que não existe** no ambiente (servidor sem o setor pré-carregado, reserva do slot ausente): o seed falha com `SeedFailure` em pt-BR e não cria recado pela metade. Teste `missingDepartmentFails` (Task 2).
3. **Roteiro divergente da build:** as linhas do seed e os rótulos de tela citados no roteiro batem com o código (teste de shell no molde do `test_roteiro_m1_ataque.sh`). Task 3.

---

### Task 1: Porta de recados no gateway do seed

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (escreve no servidor; idempotência manual)

**Files:**
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/booking/TraceGateway.kt` (ou no pacote que o seed usar para a operação; siga a organização existente)
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/sdk/SdkTraceGateway.kt`
- Test: `demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/SdkTraceGatewayTest.kt` e um falso em memória para a Task 2

**Interfaces:**
- Produces: `interface TraceGateway { suspend fun departments(): Map<String, String>` (nome → id) `; suspend fun traces(reservationId: String?, roomId: String?): List<TraceRef>; suspend fun create(departmentId: String, dueOn: LocalDate, text: String, reservationId: String?, roomId: String?): String }`, com `data class TraceRef(val id: String, val text: String)` (`toString` sem o texto); erros sobem como `ApiError`, como nas outras portas do seed; listas lidas por inteiro, página a página.

- [ ] **Step 1: Testes que falham** (`MockEngine`, no molde de `SdkUserGatewayTest`): `departmentsByName`, `tracesFilteredByReservationOrRoom` (duas páginas), `createSendsBodyWithoutIdempotencyKey`, `errorsAreApiError`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta a porta de recados ao seed`).

---

### Task 2: Recados de exemplo no seed

**Estimativa:** 40 min · **Depende de:** Task 1 · **Risco:** alto (idempotência; relatório que o roteiro cita)

**Files:**
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/booking/TraceSeeder.kt`
- Modify: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/data/SeedReport.kt` (`SeedItem.TRACE("recados")`, depois das alocações) e `demo-seed/src/main/kotlin/com/zeny/desktop/seed/DemoSeed.kt` (roda o `TraceSeeder` depois das reservas, com os ids delas e das UHs)
- Modify (testes existentes, mudança mandada e de mesma força): `SeedReportAtaqueTest` (o rótulo `recados` entra na lista exata) e qualquer teste que fixe a lista de etapas do `DemoSeed`, só acrescentando a etapa nova
- Modify: `docs/demo/roteiro-m1.md` (a linha `recados: 3 criados, 0 já existiam` na saída do seed, na ordem) — o teste do roteiro exige
- Test: `demo-seed/src/test/kotlin/com/zeny/desktop/seed/booking/TraceSeederTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: os três recados da Global Constraint, criados uma vez; o relatório conta `SeedItem.TRACE` criados e mantidos; um setor ausente ou uma reserva/UH ausente vira `SeedFailure` em pt-BR antes de qualquer criação.

- [ ] **Step 1: Testes que falham:** `createsThreeTraces`, `secondRunCreatesNothing`, `partialStateCreatesOnlyMissing`, `missingDepartmentFails`, `missingReservationFails`, `reportCountsTraces`, `textsHaveNoPersonalData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta recados de exemplo ao seed`).

---

### Task 3: Roteiro do M2 no pacote

**Estimativa:** 45 min · **Depende de:** Task 2 · **Risco:** alto (é o que o usuário executa no gate)

**Files:**
- Create: `docs/demo/roteiro-m2.md`
- Modify: `tools/server-env/package.sh` (copia também o `roteiro-m2.md`)
- Test: `tests/test_roteiro_m2_ataque.sh` (no molde de `tests/test_roteiro_m1_ataque.sh` e `test_roteiro_m1_zero_ataque.sh`: as linhas do seed na ordem do `SeedItem`; os rótulos e textos de tela citados existem no código — "Recados", "Novo recado", "Ficha de registro", "Salvar como…", "Imprimir", "Trocar UH", "Desfazer check-in", "Walk-in", "Alguém alterou este recado. Confira e salve de novo."; o roteiro começa do zero; o app abre com `-Dzeny.devShell=true`; nenhum segredo literal) e o caso novo nos testes do pacote (`roteiro-m2.md` presente e igual ao do repositório)

**Interfaces:**
- Consumes: Task 2 (a linha `recados`) e os textos de tela das E01–E07.

- [ ] **Step 1: Testes que falham:** o teste de shell do roteiro e o do pacote.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** escreva o roteiro (Global Constraint "Roteiro") e ajuste o `package.sh`. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o roteiro do M2 e o leva no pacote`).

---

## Verificação do gate (orquestrador, depois das tarefas)

1. Clone limpo do HEAD: `./gradlew build --max-workers=2`, `ktlintCheck detekt`, `:app:desktopJar :demo-seed:seedJar` e `bash tests/run.sh` verdes.
2. Ambiente recriado: `down -v`, `up`, seed (tempo abaixo de 2 minutos, com a linha `recados`), `smokeTest`, e as verificações ao vivo do marco (`ZENY_LIVE_RECEPTION`, `ZENY_LIVE_CHECKIN`, `ZENY_LIVE_WALKIN`, `ZENY_LIVE_STAY`, `ZENY_LIVE_TRACES`, `ZENY_LIVE_CARD`).
3. Pacote regenerado (`tools/server-env/package.sh`) com os JARs do HEAD, os dois roteiros e o `VERSAO.txt`; nenhum segredo no pacote.
4. A etapa fica `#em-andamento` com "Aguardando o Windows" e entra na lista "Validações acumuladas no Windows" (regra 7).
