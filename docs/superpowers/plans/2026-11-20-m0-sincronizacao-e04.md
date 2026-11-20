# M0 E04 — Sincronizar com o servidor em `8c563cda0575` — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** apontar o front para a `main` do servidor em `8c563cda0575dab697c89f2caba5a1dd9a12782e`, com o SDK regenerado (376 operações), os campos que saíram do `required` enviados sempre explícitos (ADR 0014), o catálogo de erros com os códigos novos e a regressão ao vivo verde contra o servidor novo, recriado do zero.

**Architecture:** o pin e a cópia do contrato mudam pelo mecanismo da E01 (`tools/server-pin.sh`), e o `sdk` é regenerado pelo plugin do ogen sem código à mão (ADR 0007). O conserto da compilação fica nas fachadas do `core` e nos gateways do `demo-seed`, que passam a mandar cada campo afetado em `Option.Some` com o mesmo valor de hoje (ADR 0014); o JSON enviado não muda. O catálogo de erros e a regra única do resultado desconhecido (`classifyWriteFailure`, M4 E01) continuam no `core/error`. A regressão ao vivo usa o ambiente `zeny-front` (ADR 0003) recriado em `8c563cda0575`.

**Fora das tarefas (orquestrador, no fechamento):** regenerar o pacote `build/demo-package/` do checkout principal sobre o HEAD final (`./gradlew :app:desktopJar :demo-seed:seedJar`, `tools/server-env/package.sh`, `tools/server-env/save-images.sh`), conferir `docs/pedidos-servidor.md` sem mudança de estado e o servidor igual (`status --porcelain` e `worktree list`), e acrescentar a etapa à lista "Validações acumuladas no Windows" do README como item 6.

**Tech Stack:** Kotlin, ogen 0.16.1, kotlinx.serialization, Ktor `MockEngine`, JUnit 5 (`kotlin.test`), bash (`tests/run.sh`), Docker Compose (`tools/server-env/`).

**Spec:** seção "E04" de [`docs/roadmaps/roadmap-m0-sincronizacao.md`](../../roadmaps/roadmap-m0-sincronizacao.md), com o design [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../specs/2026-11-10-front-desktop-design.md) §5, §6.4 e §7.3, os ADRs [0002](../../adr/0002-servidor-fixado-e-contrato-copiado.md), [0003](../../adr/0003-ambiente-do-servidor-isolado.md), [0007](../../adr/0007-gerador-do-sdk.md), [0008](../../adr/0008-fronteira-core-sdk.md) e [0014](../../adr/0014-campos-com-padrao-sempre-explicitos.md), e as regras 3, 5 e 7 de [`docs/roadmaps/README.md`](../../roadmaps/README.md).

**Riscos:** parsing de entrada externa não confiável (o SDK regenerado decodifica as respostas do servidor novo, e o catálogo traduz códigos de erro); cálculo de valor monetário (`PostingRequest.quantity` e `ReceivePaymentRequest.deposit` mudam de forma no lançamento e no pagamento); escrita destrutiva de dados (o `down -v` da Task 3 apaga os volumes e tem de atingir só o projeto `zeny-front`; a Task 1 existe para evitar a sobrescrita por omissão de campo com padrão, ADR 0014, Contexto, item 2); concorrência (`409 CONCURRENT_UPDATE` entra na regra única do resultado desconhecido, Task 2); dado pessoal ou segredo (as fachadas de hóspede, e os segredos do seed e das verificações ao vivo na Task 3).

## Global Constraints

- O repositório do servidor (`/home/caio/repos/ZenyHotelManager`) é **só leitura**: `git -C … rev-parse`, `show <commit>:<arquivo>`, `cat-file`, `archive`, `diff`. Nada de `worktree add`, `checkout`, `fetch` ou arquivo novo lá.
- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela. O pin é `8c563cda0575dab697c89f2caba5a1dd9a12782e`, mesmo que a `main` do servidor ande durante a etapa. Nunca edite os dois arquivos à mão: só por `tools/server-pin.sh`.
- O `sdk` não é versionado e não tem código à mão, filtro nem `typeMapping`. Se o ogen não gerar ou não compilar o `PATCH` `adjustAssignmentPlan` ou qualquer operação nova, pare e devolva `BLOCKED`: trocar de gerador, excluir operação ou escrever código no `sdk` é decisão de arquitetura (ADR 0007, critério 1).
- ADR 0014: todo campo que saiu do `required` e que o front constrói vai em `Option.Some(valor)`, com o valor que ia antes da troca; `Option.Undefined` fica só para o campo realmente opcional (anulável e sem padrão). O JSON enviado pelas escritas existentes não muda. Nada de mudar o que a tela envia nem acrescentar campo.
- O ambiente do front usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` e a porta 8088. Nunca o projeto `zeny` nem a imagem `:dev`. `down` e `down -v` só atingem `zeny-front`. Não dispare o fechamento manual do dia.
- `docker compose config` sem filtro imprime as chaves e senhas do `.env` real: nunca rode nem cole essa saída. Só `--services`, `--images` e `--format json` filtrado por `python3 -I` nos campos `name` e `services.proxy.ports`.
- Segredos nunca entram no Git, em relatório, em log de teste nem na saída de comando: arquivos de segredo com modo 600, carregados num subshell (`set -a; . <arquivo>; set +a`), sem `echo` nem `env` que mostre valores.
- Testes em que o `testador` já commitou (algum commit com o trailer `Soul: testador` em `git log -- <arquivo>`) são adaptados pelo testador, não pelo desenvolvedor (regra do `C-M6`).
- Um Gradle por vez no notebook (o orquestrador do servidor está ativo). Nunca `./gradlew --stop`. Se o build cair com "daemon disappeared", repita uma vez. Todo comando longo leva `timeout` (Gradle: `timeout 1500`), e nada de laço de espera.
- Sem funcionalidade nova: nenhuma tela ou fachada da governança, nem o tópico `housekeeping` no tempo real, nem rótulo dos tipos de notificação novos.
- Documentação em pt-BR; código e identificadores em inglês. Commits em pt-BR, no estilo do histórico, com o trailer `Soul:` da soul que commita.

## Review Focus

1. **Valor trocado no `Option.Some`:** um `Option.Some(false)` onde ia `true`, ou `quantity` com escala diferente, muda o que o servidor grava sem quebrar a compilação. O teste de corpo da Task 1 compara o JSON enviado campo a campo com o valor de antes, inclusive `deposit = true` e `false`, `waivePenalty = true` e `false` e listas vazias (`childAges = []`, `statuses = []`).
2. **Lista vazia que vira ausente:** `childAges`, `guests`, `nightlyRates`, `statuses`, `documents`, `featureIds`, `weekdays` e `packageIds` vazios têm de sair `[]`, não omitidos; omitido, o servidor aplica o padrão dele, que pode mudar numa rodada sem aparecer no contrato. Asserção explícita com lista vazia na Task 1.
3. **Nova reserva:** `ReservationRequest` sai com `guests = []` explícito e `childAges` e `nightlyRates` com o que a tela montou, como hoje. A alteração (`BookingsFacade.changeRequestOf`) monta `ReservationChangeRequest`, que não está no ADR 0014 (`childAges` e `nightlyRates` seguem `required`) e não muda; nenhum `PUT` do `core` usa hoje um DTO compartilhado afetado. Teste de corpo da nova reserva na Task 1.
4. **`CONCURRENT_UPDATE` repetido sozinho:** nenhuma repetição automática; a classificação é `DEFINITIVE` e a mensagem convida o usuário a tentar de novo. Teste na Task 2.
5. **Escrita do front recusada pela entrada estrita** (texto vazio em UUID, data fora do formato ISO, `LocalTime` fora de `HH:mm[:ss]`): a regressão ao vivo da Task 3 é a prova; cada recusa vira conserto da fachada com teste de unidade que fixa o formato enviado.

---

### Task 1: Pin, contrato e SDK em `8c563cda0575`, com os campos explícitos do ADR 0014

**Objetivo:** fixar o servidor em `8c563cda0575`, regenerar o SDK com as 376 operações e devolver a compilação do `core`, do `demo-seed` e dos testes mandando explícito cada campo que saiu do `required`, com o mesmo valor de hoje.

**Critérios de aceite:**
- `api/server-pin.properties` tem `commit=8c563cda0575dab697c89f2caba5a1dd9a12782e`, e `sha256sum api/openapi.yaml`, `grep contract_sha256 api/server-pin.properties` e `git -C /home/caio/repos/ZenyHotelManager show 8c563cda0575:api/openapi.yaml | sha256sum` dão `cb90e8633777cab189c061ddd9f799e9ffb08a5f10e77d4bc8a09617ab30e1a5`; o `README.md` cita o commit novo (`bash tests/run.sh tests/test_docs_ataque.sh`).
- `tools/server-drift.sh` sai com `igual (código 0)` (ou, se a `main` do servidor andou, a saída vai ao relatório e o pin fica).
- `./gradlew :sdk:test` verde, com `SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod` amarrando 376 `operationId`, a classe da tag `housekeeping` na lista e o método `adjustAssignmentPlan` gerado.
- Um teste novo do `core`, `ExplicitDefaultFieldsTest` (em `core/src/test/kotlin/com/zeny/desktop/core/facade/`), captura pelo `MockEngine` o corpo enviado por cada fachada que constrói um schema afetado — criar hóspede, check-in, walk-in, busca por documento, nova reserva, cancelamento, lançamento e pagamento — e prova que cada campo afetado está no JSON com o valor de antes, inclusive listas vazias e booleanos `false`. Comando: `./gradlew :core:test --tests '*ExplicitDefaultFieldsTest*'`.
- Um teste novo do `demo-seed`, `SeedExplicitDefaultFieldsTest` (em `demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/`), faz o mesmo para os gateways (`SdkBookingGateway`, `SdkConfigGateway`, `SdkPendingGateway`, `SdkFolioGateway`) e **todos** os schemas afetados que eles constroem pela lista completa do ADR 0014 (não pela lista do "Contexto" da etapa, que omite casos), inclusive `CashPointRequest.blindClose` (`SdkConfigGateway`), `StayGuestRequest.primary` (`SdkBookingGateway` e `SdkPendingGateway`), `CheckInGuestRequest.primary` (`SdkBookingGateway`) e `ReservationCancelRequest.waivePenalty` (`SdkPendingGateway`). Comando: `./gradlew :demo-seed:test --tests '*SeedExplicitDefaultFieldsTest*'`.
- `grep -rn 'Option.Undefined' core/src/main demo-seed/src/main` não mostra nenhum dos campos afetados.
- A verificação rápida dos módulos tocados verde: `timeout 1500 ./gradlew :sdk:test :sdk:ktlintCheck :sdk:detekt :core:test --tests 'com.zeny.desktop.core.facade.*' --tests 'com.zeny.desktop.core.http.*' --tests 'com.zeny.desktop.core.session.*' --tests '*CoreCriteriaTest*' --tests '*ZenyCoreAttackTest*' :core:ktlintCheck :core:detekt :demo-seed:test :demo-seed:ktlintCheck :demo-seed:detekt :app:compileTestKotlin`. Os testes do catálogo de erros do `core` (`com.zeny.desktop.core.error.*ErrorCatalog*`) leem o contrato em vigor e ficam **vermelhos de propósito** depois desta tarefa, até a Task 2, que vem logo em seguida; o relatório diz quais falham e por quê (códigos novos sem mensagem, contagem 377).

**Áreas tocadas:** `api/` (por script), `README.md`, `core/src/main/.../core/facade/` (`GuestsFacade`, `ReservationsFacade`, `BookingsFacade`, `FoliosFacade`, `PaymentsFacade`), `demo-seed/src/main/.../seed/sdk/` (os quatro gateways), testes novos em `core` e `demo-seed`, e os testes existentes que constroem os schemas afetados (os do desenvolvedor; os do testador ficam com ele, ver abaixo).

**Tamanho:** ~60 min de construção; 9 arquivos de produção (5 fachadas e 4 gateways), além do pin, do contrato e do `README.md`.

**Depende de:** nenhuma.

**Interfaces:**
- Produces: pin em `8c563cda0575`; SDK com 376 operações e a classe `<Tag>Api` da tag `housekeeping`; as assinaturas públicas das fachadas do `core` e dos gateways do `demo-seed` **não mudam** (o conserto é interno). O relatório lista: o nome da classe gerada da tag `housekeeping`; cada campo afetado que o front constrói, por arquivo e com o valor enviado; os arquivos de teste com commit do testador que não compilam **ou falham na asserção** depois da troca (por exemplo, a contagem de `SdkAttackTest` em 335), com o erro, que o orquestrador leva ao testador antes da revisão. Os critérios desta tarefa se conferem depois dessa adaptação.

**Notas:**
- Comece pelo `tools/server-pin.sh 8c563cda0575dab697c89f2caba5a1dd9a12782e` e por `./gradlew :sdk:compileKotlin`; a lista completa de schemas afetados está no ADR 0014 (o "Contexto" da etapa omite casos do `demo-seed`). Os campos a mandar são só os que o código já constrói; o resto dos 34 é de etapas futuras.
- O wrapper do Gradle e o `docker/` do servidor não mudaram entre os pins (confira `git -C … diff 5e06f16d51ff 8c563cda0575 -- gradlew gradlew.bat gradle/wrapper/ docker/` e diga no relatório).
- Testes do desenvolvedor que quebram pela troca (`CoreCriteriaTest`, `ApiExecutor*Test`, `CoreJson*Test`, `AuthPlugin*Test`, `SdkOneOfTest`, `LiveAvailabilityBookingCheck` e outros) recebem o mesmo conserto mecânico. Teste com commit do testador: não toque; liste no relatório.

### Task 2: Catálogo de erros do contrato novo e `CONCURRENT_UPDATE`

**Objetivo:** o catálogo de erros do `core` cobre todos os códigos do contrato em `8c563cda0575`, com mensagem pt-BR para cada código novo, e `CONCURRENT_UPDATE` tem a mensagem que convida a tentar de novo e é tratado como `DEFINITIVE` pela regra única do resultado desconhecido.

**Critérios de aceite:**
- O teste do catálogo que lê os códigos do contrato (`ErrorCatalogTest`, via `ContractErrorCodes`) passa com o número novo, e nenhum código do contrato fica sem mensagem. O relatório dá a contagem antiga (377), a nova e a lista dos códigos novos. Comando: `./gradlew :core:test --tests '*ErrorCatalog*'`.
- A mensagem de `CONCURRENT_UPDATE` diz, em pt-BR, que outra operação mexeu no mesmo registro ao mesmo tempo e que dá para tentar de novo, e um teste fixa o texto exato.
- Um teste novo em `WriteFailureTest` prova `classifyWriteFailure(ApiError.Problem(status = 409, code = "CONCURRENT_UPDATE", …)) == WriteFailure.DEFINITIVE`. Comando: `./gradlew :core:test --tests '*WriteFailure*'`.
- Nenhuma repetição automática entra no `core` (o diff da tarefa não toca `ApiExecutor` nem o cliente HTTP).
- `timeout 1500 ./gradlew :core:test :core:ktlintCheck :core:detekt :app:test :app:ktlintCheck :app:detekt` verde.

**Áreas tocadas:** `core/src/main/.../core/error/ErrorCatalog.kt` (e `WriteFailure.kt` só se a classificação divergir), testes em `core/src/test/.../core/error/`.

**Tamanho:** ~30 min; 1 a 2 arquivos de produção.

**Depende de:** Task 1 (contrato novo em `api/openapi.yaml`).

**Interfaces:**
- Consumes: `ContractErrorCodes` (teste), `ErrorCatalog`, `classifyWriteFailure(error: ApiError): WriteFailure` (existentes).
- Produces: mensagens pt-BR para os códigos novos. A redação das 48 mensagens da governança segue o estilo das existentes e é revista nas etapas que as exibem (M6 E09 a E11 e M7 E13); se o `ErrorCatalogTest` (ou outro teste do catálogo) tiver commit do testador, não o toque, nem a contagem: liste no relatório o que falha, e o orquestrador leva ao testador; os critérios desta tarefa se conferem depois dessa adaptação.

### Task 3: Ambiente em `8c563cda0575`, regressão ao vivo e revisão de `tools/server-env/`

**Objetivo:** subir o `zeny-front` do zero no pin novo, semear e provar contra ele que o `smokeTest` e todas as verificações ao vivo existentes passam; corrigir, com teste, a fachada que a entrada estrita recusar; e revisar `tools/server-env/` arquivo por arquivo.

**Critérios de aceite:**
- `tools/server-env/down.sh -v` e `tools/server-env/up.sh` sobem `zeny-front` com a imagem `zeny-hotel-manager:8c563cda0575` e `curl -fsS http://127.0.0.1:8088/actuator/health/readiness` responde `{"status":"UP"}`.
- O seed roda contra o ambiente novo, com o tempo medido no relatório: `(set -a; . <checkout principal>/.server/seed-secrets.env; set +a; time timeout 1500 ./gradlew :demo-seed:run --args="--server http://127.0.0.1:8088 --admin-login $ZENY_SEED_ADMIN_LOGIN")`.
- `./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088` verde (1 teste, 0 pulados), com as `ZENY_SEED_*` do arquivo de segredos.
- Cada verificação ao vivo existente roda contra o ambiente recriado e semeado e passa, sem ser pulada: as `Live*Check` do `app` (`LiveServerCheck`, `LiveServerAtaqueCheck`, `LiveRoomMapCheck`, `LiveReceptionCheck`, `LiveCheckInCheck`, `LiveWalkInCheck`, `LiveStayActionsCheck`, `LiveTracesCheck`, `LiveRegistrationCardCheck`, `LiveReservationSearchCheck`, `LiveAvailabilityCheck`, `LiveBookingFlowCheck`, `LiveReservationLifecycleCheck`, `LivePreReservationConfirmCheck`, `LiveCashierCheck`, `LiveFolioCheck`, `LivePaymentCheck`, `LivePreAuthorizationCheck`, `LiveCheckOutCheck`, `LiveFolioCorrectionCheck`, `LiveExpiringPreAuthorizationCheck`, `LiveFolioAccountCheck`), a `LiveRealtimeCheck` do `core` e as `Live*Check` do `demo-seed`. O relatório traz uma tabela: verificação, opt-in usado, testes/pulados/falhas, recriação antes dela (sim/não) e o que foi corrigido. A queda do servidor (`ZENY_LIVE_OUTAGE`) entra se o handoff dela permitir rodar sem intervenção; senão, fica registrada como não rodada, com o motivo.
- Cada escrita recusada pela entrada estrita é corrigida na fachada do `core` (ou no gateway do `demo-seed`), com um teste de unidade que fixa o formato enviado, e a verificação ao vivo que falhou passa depois do conserto. Se a recusa vier do payload montado pela própria verificação ao vivo (código de teste), o conserto é do dono do teste: com commit do testador, liste no relatório para o orquestrador levar ao testador. Se a regressão apontar mais de 4 arquivos de produção, pare e relate (`DONE_WITH_CONCERNS`) antes de seguir, para replanejar.
- O gate do projeto verde no HEAD da tarefa: `timeout 1800 ./gradlew build` e `timeout 1800 bash tests/run.sh` (o `build` pega o `detektTest`, que o `ktlintCheck detekt` não roda).
- A tabela da revisão de `tools/server-env/`, apoiada em `git -C /home/caio/repos/ZenyHotelManager diff --stat 5e06f16d51ff 8c563cda0575 -- docker/ gradlew gradle/wrapper/` e na comparação das chaves de `tools/server-env/.env.example` com `git -C … show 8c563cda0575:docker/.env.example` (só os nomes), cobre (`compose.override.yaml`, `up.sh`, `down.sh`, `package.sh`, `save-images.sh`, `gen-env.sh`, `lib.sh`, `.env.example`, `LEIA-ME.md`) está no relatório, com resultado e motivo; o `.env.example` comparado chave a chave com o do servidor em `8c563cda0575`.
- O relatório diz se o seed monta os papéis pelos modelos do servidor ou por lista própria de permissões (`grep -rn 'permission\|template' demo-seed/src/main`), e se o Gerente e a Recepção receberam permissões `housekeeping.*` no ambiente semeado (pela leitura dos papéis com o admin, sem segredos na saída) — nota para a M6 E09.
- Nenhum segredo aparece em commit, relatório ou saída colada; `git status --porcelain` limpo no fim.

**Áreas tocadas:** só leitura e execução em `tools/server-env/` e no ambiente; `.server/` do checkout principal (arquivo de segredos, fora do Git); código só nas fachadas ou gateways que a regressão apontar, com os testes deles.

**Tamanho:** ~20 min de construção se nada for recusado (a execução das verificações ao vivo leva mais, e não conta); até 4 arquivos de produção se houver conserto.

**Depende de:** Task 1 (o pin e o SDK novos) e Task 2 (o gate desta tarefa só fica verde com as mensagens dos códigos novos).

**Interfaces:**
- Consumes: o pin e o SDK da Task 1; os comandos e variáveis de cada verificação ao vivo, registrados nos handoffs dos roadmaps do M1 ao M4 (`docs/roadmaps/roadmap-m*.md`, busque `ZENY_LIVE_`) e no `app/build.gradle.kts`.
- Produces: `zeny-front` no ar em `8c563cda0575`, semeado; o arquivo de segredos `.server/seed-secrets.env` do checkout principal (600, ignorado pelo Git) com `ZENY_SEED_SERVER`, `ZENY_SEED_ADMIN_LOGIN` e `ZENY_SEED_ADMIN_PASSWORD` (iguais a `ZENY_ADMIN_LOGIN` e `ZENY_ADMIN_PASSWORD` do `.server/zeny-front.env`, a senha inicial depois do `down -v`) e as senhas novas geradas aleatoriamente (`openssl rand`, 20+ caracteres) para `ZENY_SEED_ADMIN_NEW_PASSWORD`, `ZENY_SEED_RECEPTIONIST_PASSWORD`, `ZENY_SEED_HOUSEKEEPER_PASSWORD`, `ZENY_SEED_MANAGER_PASSWORD` e um `ZENY_SEED_MANAGER_PIN` de 6 dígitos. Se o arquivo já existir, reaproveite-o.

**Notas:**
- **Operação do ambiente:** antes do `up.sh`, confira a memória livre (`free -g`); o build da imagem dentro do Docker conta como o Gradle da vez, então nada de Gradle do front em paralelo com ele. Todo comando leva `timeout` (`down.sh`/`up.sh` 1800, `smokeTest` e cada `Live*Check` 1500). Se não houver memória para o ambiente, pare e relate (`BLOCKED`), sem matar processos alheios.
- **Seed:** se ele falhar por senha (401 no login do admin), pare e relate, sem repetir. Ao reaproveitar o arquivo de segredos, confira sem imprimir que `ZENY_SEED_ADMIN_PASSWORD` é igual à `ZENY_ADMIN_PASSWORD` de `.server/zeny-front.env` (compare dentro de um subshell, com as duas variáveis carregadas dos arquivos sem eco, e imprima só o resultado: `[ "$a" = "$b" ] && echo igual || echo diferente`; nunca imprima o valor nem um hash dele).
- **03:00:** se a rodada for cruzar as 03:00 (hora do hotel), o fechamento automático avança a data e gera tarefas; recrie e semeie de novo antes de continuar, e registre.
- Verificações que escrevem na demo podem exigir recriação antes da seguinte: siga o handoff de cada uma e registre na tabela.
- Uma verificação que falha por causa do ambiente (dado do seed consumido, `NEED_SEED`) não é defeito: recrie e repita uma vez. Falha que persiste, ou recusa do servidor (`400`, `409`, `422`) numa escrita do front, é o que a etapa procura.
- Se o problema estiver no servidor (comportamento contrário ao contrato), não contorne: pare e relate com a evidência; vira pedido `PF-NN`.
