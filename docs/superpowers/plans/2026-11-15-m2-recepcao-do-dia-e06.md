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
- `POST /api/v1/traces` não é idempotente no servidor: o seeder precisa listar antes (`GET /api/v1/traces` com o filtro da reserva ou da UH) e só criar o que falta, reconhecendo o recado pela chave vínculo + setor (Global Constraint).
- O administrador do seed lê os setores (`GET /api/v1/departments`); o `DepartmentResponse` tem a chave estável `key` (`RECEPTION`, `HOUSEKEEPING`, `MAINTENANCE`), que o seed usa (o nome pode mudar).
- As reservas do seed são achadas por `BookingGateway.findReservation("DEMO-SEED-<slot>")` e as UHs por `SeededConfig.roomIds`; o `BookingSeeder` não muda.
- Rodar o seed de novo depois da demo: o `BookingSeeder.settle` refaz o check-in de um `H*` que voltou a `CONFIRMED`; por isso o roteiro só desfaz o check-in feito no próprio roteiro (`A3`/`A4`) e só edita e resolve recados criados pelo usuário.
- O app é aberto no roteiro com `-Dzeny.devShell=true` (D6 do M1, decidida pelo usuário).

## Global Constraints

- **Seed:** só dados fictícios; idempotente; o relatório ganha a linha `recados: N criados, M já existiam` com `SeedItem.TRACE("recados")` **depois de `CHECK_IN`**, na ordem em que o seed roda; continua abaixo de 2 minutos; nenhum segredo em saída ou mensagem.
- **Recados de exemplo:** três, com vencimento na data operacional: Governança (`HOUSEKEEPING`) ligado à UH `101` ("Trocar o enxoval extra da UH antes da chegada."); Manutenção (`MAINTENANCE`) ligado à reserva do slot `H1` ("Verificar o ar-condicionado, reclamação do hóspede."); Recepção (`RECEPTION`) ligado à reserva do slot `A1` ("Confirmar o horário de chegada por telefone."). Nenhum dado pessoal no texto.
- **Idempotência dos recados (ruling):** qualquer recado existente com o mesmo vínculo (reserva ou UH) e o mesmo setor conta como "já existia", qualquer que seja o texto ou a situação; só cria o que falta.
- **Roteiro:** pt-BR, passo a passo único e do zero como o `roteiro-m1.md` (pasta nova, folha de senhas, recriar a demo, `.env`, servidor, admin, seed, app com `-Dzeny.devShell=true`), acumulando os passos do M1 com as mesmas travas de segredo e as linhas idênticas ao `LEIA-ME.md`. Passos novos, cada um com "Resultado esperado" e o que conta como reprovação:
  - painel do dia (chegadas, saídas, hospedados, com as contagens do seed);
  - check-in de uma chegada sem UH (`A3` ou `A4`) com alocação;
  - ficha de registro dessa reserva: conferir código, UH, nome e documento mascarado; "Salvar como…" (cancelar; salvar; salvar sobre um arquivo existente; abrir o PDF salvo e conferir); "Imprimir" (cancelar; imprimir para "Microsoft Print to PDF" e abrir o PDF impresso). Defeitos conhecidos, aceitos no gate: o diálogo de impressão pode abrir atrás da janela (saída: Alt+Tab ou a barra de tarefas) e, com escala de tela de 125–150%, o "100%" aparece menor que a folha real;
  - walk-in;
  - troca de UH de um hospedado;
  - desfazer o check-in feito no próprio roteiro com a aprovação do Gerente (login e PIN da folha); uma tentativa com PIN errado mostra a mensagem e gasta uma tentativa do `gerente`;
  - recados: a aba "Recados" com os três do seed; criar um no detalhe e um na aba; resolver um criado pelo usuário; a edição concorrente com dois apps abertos (duas janelas `java -jar`), mostrando "Alguém alterou este recado. Confira e salve de novo.";
  - aviso: uma Recepcionista com o modelo padrão de role vê a mensagem de permissão em "Novo recado" (a demo dá a role "Consulta de setores (demo)");
  - o passo "No fim" diz o que acontece ao rodar o seed de novo depois da demo.
- **Pacote:** `tools/server-env/package.sh` leva `roteiro-m2.md` além do `roteiro-m1.md`; o `LEIA-ME.md` do pacote e o `README.md` apontam o `roteiro-m2.md` como o roteiro do gate atual.
- **Testes:** testes existentes só mudam onde a tarefa manda (listado em cada tarefa); nada de dormir.
- **Gate e commits:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes antes de cada commit; nunca `./gradlew --stop`; commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **Seed rodado duas vezes** não duplica recados. Testes `secondRunCreatesNothing` (Task 2) e o seed rodado de novo contra o servidor real, antes e depois da demo (Task 6).
2. **Setor, reserva ou UH ausente:** `SeedFailure` em pt-BR antes de qualquer criação. Testes `missingDepartmentFails`, `missingReservationFails` e `missingRoomFails` (Task 2).
3. **Roteiro divergente da build:** os "Resultado esperado" conferidos executando o roteiro do zero no Linux com o pacote real (Task 6); as linhas do seed e os rótulos conferidos por teste (Task 5).
4. **Segredos no roteiro novo:** as mesmas travas do `roteiro-m1.md`, extraídas na Task 4 e aplicadas na Task 5.

---

### Task 1: Porta de recados no gateway do seed

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (escreve no servidor; idempotência manual)

**Files:**
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/frontdesk/TraceGateway.kt`
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/sdk/SdkTraceGateway.kt`
- Test: `demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/SdkTraceGatewayTest.kt` e o falso em memória `demo-seed/src/test/kotlin/com/zeny/desktop/seed/frontdesk/FakeTraces.kt`

**Interfaces:**
- Produces: `interface TraceGateway { suspend fun departmentIdsByKey(): Map<String, String>; suspend fun traces(reservationId: String?, roomId: String?): List<TraceRef>; suspend fun create(departmentId: String, dueOn: LocalDate, text: String, reservationId: String?, roomId: String?): String }`, com `data class TraceRef(val id: String, val departmentId: String)`; erros sobem como `ApiError`, como nas outras portas do seed; listas lidas por inteiro, página a página.

- [ ] **Step 1: Testes que falham** (`MockEngine`, no molde de `SdkUserGatewayTest`): `departmentsByKey`, `tracesFilteredByReservationOrRoom` (duas páginas), `createSendsBodyWithoutIdempotencyKey`, `errorsAreApiError`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta a porta de recados ao seed`).

---

### Task 2: Recados de exemplo no seed

**Estimativa:** 40 min · **Depende de:** Task 1 · **Risco:** alto (idempotência; relatório que o roteiro cita)

**Files:**
- Create: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/frontdesk/TraceSeeder.kt` (usa `BookingGateway.findReservation("DEMO-SEED-<slot>")` e `SeededConfig.roomIds`)
- Modify: `demo-seed/src/main/kotlin/com/zeny/desktop/seed/data/SeedReport.kt` (`SeedItem.TRACE("recados")` depois de `CHECK_IN`) e `demo-seed/src/main/kotlin/com/zeny/desktop/seed/DemoSeed.kt` (o `AdminContext` ganha `traces: TraceGateway`; o `TraceSeeder` roda depois das reservas e dos check-ins)
- Modify (testes existentes, mudança mandada, de mesma força): `SeedReportAtaqueTest` (o rótulo `recados` entra na lista exata); `DemoSeedTest.kt` e `DemoSeedAtaqueTest.kt` (só o argumento novo do `AdminContext`, com o falso `FakeTraces`)
- Modify: `docs/demo/roteiro-m1.md` (a linha `recados: 3 criados, 0 já existiam` na saída do seed, na ordem) — o teste do roteiro do M1 exige
- Test: `demo-seed/src/test/kotlin/com/zeny/desktop/seed/frontdesk/TraceSeederTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: os três recados da Global Constraint; idempotência pela chave (vínculo + setor); setor, reserva ou UH ausente → `SeedFailure` em pt-BR antes de qualquer criação.

- [ ] **Step 1: Testes que falham:** `createsThreeTraces`, `secondRunCreatesNothing`, `existingTraceWithOtherTextCounts`, `partialStateCreatesOnlyMissing`, `missingDepartmentFails`, `missingReservationFails`, `missingRoomFails`, `reportCountsTraces`, `textsHaveNoPersonalData`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta recados de exemplo ao seed`).

---

### Task 3: O pacote leva o roteiro do M2

**Estimativa:** 35 min · **Depende de:** Task 2 · **Risco:** alto (o pacote é o que vai ao Windows)

**Files:**
- Modify: `tools/server-env/package.sh` (exige e copia `docs/demo/roteiro-m2.md`); nesta tarefa nasce um `docs/demo/roteiro-m2.md` provisório com só o título, que a Task 5 escreve
- Modify (testes e fixtures, mudança mandada): `tests/lib/fixtures.sh` (`make_fake_jars` e os fronts falsos criam também o `roteiro-m2.md`); os casos de `test_package*.sh` e `test_gate_m1_ataque.sh` que montam o front falso; `tests/test_roteiro_m1_zero_ataque.sh` (a lista do pacote aceita o `roteiro-m2.md`)
- Modify (docs): a lista do conteúdo do pacote nos passos 0 e 1 do `docs/demo/roteiro-m1.md`; `tools/server-env/LEIA-ME.md` (aponta o `roteiro-m2.md` como o roteiro do gate atual, e a asserção correspondente de `tests/test_docs.sh`); `README.md` (liga os dois roteiros)
- Test: os casos novos nos testes do pacote (`roteiro-m2.md` presente e igual ao do repositório)

- [ ] **Step 1: Testes que falham** (pacote sem o `roteiro-m2.md` → falha). - [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Leva o roteiro do M2 no pacote da demo`).

---

### Task 4: Testes de roteiro parametrizados (refatoração pura)

**Estimativa:** 40 min · **Depende de:** Task 3 · **Risco:** alto (travas de segredo)

**Files:**
- Modify (testes existentes, refatoração mandada, sem afrouxar): `tests/test_roteiro_m1_ataque.sh`, `tests/test_roteiro_m1_zero_ataque.sh` e as partes de roteiro de `tests/test_docs.sh` passam a receber o caminho do roteiro, separando as travas genéricas (segredos: `Get-Credential`/`Read-Host -AsSecureString`, `Remove-Variable pin`, nenhum `--*-password`; regras do PowerShell 5.1; folha de senhas por item; linhas idênticas ao `LEIA-ME.md`) das asserções só do M1 (estrutura dos passos 0–12, o passo de sair depois da mudança ao vivo, a legenda contra o seed, "Gerente nos passos do mapa e do ao vivo"). As genéricas ficam num arquivo comum em `tests/lib/` (por exemplo `roteiro_checks.sh`).
- Nesta tarefa tudo roda só sobre o `roteiro-m1.md`.

- [ ] **Step 1:** conte as asserções de cada teste antes. - [ ] **Step 2:** refatore. - [ ] **Step 3:** os três testes verdes no `roteiro-m1.md`, com a mesma contagem de asserções (ou maior), e uma mutação de segredo no roteiro (por exemplo um `--admin-password`) ainda reprova; gate verde.
- [ ] **Step 4: Commit** (`Parametriza os testes de roteiro pelo arquivo`).

---

### Task 5: O roteiro do M2

**Estimativa:** 45 min · **Depende de:** Task 4 · **Risco:** alto (é o que o usuário executa no gate)

**Files:**
- Modify: `docs/demo/roteiro-m2.md` (o roteiro inteiro, pela Global Constraint "Roteiro")
- Create: `tests/test_roteiro_m2_ataque.sh`: aplica ao `roteiro-m2.md` as travas genéricas da Task 4 e acrescenta só o que é do M2 (as linhas do seed com `recados`; os rótulos e textos de tela citados existem no código do `app` ou do `design` — "Recados", "Novo recado", "Ficha de registro", "Salvar como…", "Imprimir", "Trocar UH", "Desfazer check-in", "Walk-in", "Alguém alterou este recado. Confira e salve de novo."; o desfazer usa só `A3`/`A4`; nenhum segredo literal)

- [ ] **Step 1: Teste que falha** sobre o roteiro provisório. - [ ] **Step 2:** FAIL. - [ ] **Step 3:** escreva o roteiro. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Escreve o roteiro do M2`).

---

### Task 6: O roteiro do M2 executado no notebook

**Estimativa:** 45 min · **Depende de:** Task 5 · **Risco:** alto (recria o ambiente; confere a demo)

**Files:**
- Modify: `docs/demo/roteiro-m2.md` (só os "Resultado esperado" que divergirem do que o app e o servidor fazem de fato, com o motivo no relatório)

**Interfaces:** nenhuma de código. A tarefa segue o roteiro do zero no Linux com o pacote real (`build/demo-package/` gerado pelo `package.sh` a partir do HEAD), adaptando o que é de Windows (PowerShell → bash; janelas → variáveis), como a 5b da M1 E07:
- recria o ambiente (`down -v`, `up`), troca a senha do admin, roda o seed (tempo abaixo de 2 minutos; a saída bate com o roteiro) e roda o seed de novo logo em seguida (idempotência sobre o estado inicial);
- confere cada passo do M2 seguindo o próprio fluxo do roteiro, pela API e por capturas do app (o relatório lista o que foi conferido e como); as verificações ao vivo que escrevem (`CHECKIN`, `STAY`, `WALKIN`, `TRACES`) não rodam antes dessa conferência, para não gastar as candidatas nem mudar as contagens;
- depois da demo, roda o seed uma terceira vez e compara a saída com o passo "No fim" (os efeitos da demo: check-in desfeito em `A3`/`A4`, o hospedado trocado de UH com a origem suja, o walk-in, os recados do usuário);
- não executa o que só existe no Windows (impressão real, diálogos nativos), que fica com o usuário.

- [ ] **Step 1:** execute e anote cada divergência. - [ ] **Step 2:** corrija só o texto do roteiro. - [ ] **Step 3:** gate verde.
- [ ] **Step 4: Commit** (`Ajusta o roteiro do M2 à execução no notebook`), se houver ajuste.

---

### Task 7 (adendo da execução): Recado ligado a UH aparece como "Reserva"

**Estimativa:** 25 min · **Depende de:** Task 6 · **Risco:** alto (leitura de resposta do servidor; texto que o roteiro do gate cita)

**Origem:** a execução do roteiro no notebook (Task 6) achou um defeito da M2 E05. O servidor fixado manda `"reservationId": null` explícito nos recados ligados a UH; o `sdk` lê isso como `Option.Some(null)`, e `TraceText.link` (`app/src/main/kotlin/com/zeny/desktop/app/traces/TraceText.kt:56`) testa `trace.reservationId is Option.Some` antes da UH. A tabela de recados e o formulário de edição mostram "Reserva" em vez de "UH 101"/"UH 102" (passos 17 e 18 do `roteiro-m2.md`, que dizem "UH 101"/"UH 102" e ficam como estão). Não é funcionalidade nova: é a correção do que a E05 prometeu.

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/traces/TraceText.kt` (o vínculo só é reserva quando o valor da reserva existe; `Option.Some(null)` conta como ausente, como já fazem `TracesListState` e `TraceFormState`)
- Test: um arquivo novo de teste no pacote `traces` do `app`, com os três casos decodificados do JSON no formato real do servidor (os campos sem valor vêm com `null` explícito, `Json { ignoreUnknownKeys = true }.decodeFromString(TraceResponse.serializer(), …)`, como em `RoomDisplayAtaqueTest`): ligado a UH (`"reservationId": null`, `"roomNumber": "101"`) → "UH 101"; ligado a reserva (`"roomId": null, "roomNumber": null`) → "Reserva R-…"; sem vínculo (os três nulos) → o texto de sem vínculo. Mais um caso de estado (`TracesListState`) com um recado em `Option.Some(null)`, num arquivo novo, sem mudar os falsos existentes.

- [ ] **Step 1: Teste que falha** (JSON com `reservationId` nulo explícito mostra "Reserva"). - [ ] **Step 2:** FAIL. - [ ] **Step 3:** corrija. - [ ] **Step 4:** PASS; procure em `app`, `core`, `design` e `demo-seed` (`src/main`) outras leituras de campo anulável de resposta que tratem `Option.Some(null)` como presente — `is Option.Some`, `is Option.Some ->` em `when`, comparação com `Option.Undefined`/`Option.None` — e relate (corrija só os do mesmo defeito, com teste); gate verde.
- [ ] **Step 5: Commit** (`Mostra a UH no vínculo do recado quando a reserva vem nula`).

---

## Verificação do gate (orquestrador, depois das tarefas)

1. Clone limpo do HEAD: `./gradlew build --max-workers=2`, `ktlintCheck detekt`, `:app:desktopJar :demo-seed:seedJar` e `bash tests/run.sh` verdes; o JAR do pacote abre no Linux (`xwininfo`).
2. Ambiente recriado (`down -v`, `up`, seed), `smokeTest` e as verificações ao vivo do marco: primeiro as que só leem (`ZENY_LIVE_RECEPTION`, `ZENY_LIVE_CARD`) e a conferência da Task 7 no app real (a aba "Recados" mostra o recado de Governança do seed com "UH 101"), depois as que escrevem (`ZENY_LIVE_REALTIME`, `ZENY_LIVE_TRACES`, `ZENY_LIVE_CHECKIN`, `ZENY_LIVE_WALKIN`, `ZENY_LIVE_STAY`); depois, o ambiente é recriado de novo, para entregar a demo limpa.
3. "Nenhuma etapa depende de pedido ao servidor": conferir `docs/pedidos-servidor.md` (PF-01 aberto, nenhuma etapa dependente) e as dependências do roadmap.
4. Pacote regenerado (`tools/server-env/package.sh`) com os JARs do HEAD, os dois roteiros e o `VERSAO.txt`; nenhum segredo no pacote.
5. A etapa fica `#em-andamento` com "Aguardando o Windows" e entra na lista "Validações acumuladas no Windows" (regra 7), registrando que o `roteiro-m2.md` fecha também a M0 E02: a entrada 1 da lista em `docs/roadmaps/README.md` passa a pedir o `roteiro-m2.md`, e a ruling vai às "Decisões para o usuário" do handoff.

---

## Revisão de arquitetura

- **Veredito:** aprovado com notas.
- **Rodadas:** 2 (rodada 0 bloqueada por 6 Importantes; rodada 1 bloqueada por 2 Importantes remanescentes; rodada 2 aprovada com notas).
- **Modelo:** opus (`revisor-arquitetura`).
- **Notas Menores** (incorporadas): a remissão da Task 3 ao roteiro passou para a Task 5; o `missingRoomFails` entrou na Review Focus 2. Não verificado pelo revisor (fica com as Tasks 4 e 6): a contagem de asserções na separação das travas; o seed rodado depois da demo; a edição concorrente com duas janelas.

### Adendo da execução (Task 7)

- **Veredito:** aprovado com notas (revisão do adendo, rodada 0, sem Crítico nem Importante).
- **Modelo:** opus (`revisor-arquitetura`).
- **Notas Menores** (incorporadas): os três casos do teste decodificados do JSON no formato real do servidor, com os nulos explícitos; a varredura cobre também `design` e `demo-seed` e nomeia as outras formas do defeito; a conferência no app real entrou no passo 2 da verificação do gate; o handoff da E06 registra que o defeito era da E05 e que o plano passou a 7 tarefas. A nota de alinhar os falsos (`FakeTracesSource`) ao formato do servidor virou um caso de estado novo, sem mudar os falsos existentes.
