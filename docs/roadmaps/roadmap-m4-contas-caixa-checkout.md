# Roadmap — M4 Contas, caixa e check-out

> Arquivo: `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` · branch de execução: `roadmap/m4-contas-caixa-checkout` · criado em 2026-11-10 · última revisão 2026-11-17

> **Pré-requisito:** a etapa E05 ("Gate do marco M3") de `docs/roadmaps/roadmap-m3-reservas.md` está `#concluida`, e a etapa E06 ("Revisão adiada do processo acelerado") do mesmo roadmap também ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md) e regra 8 de `docs/roadmaps/README.md`). Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m4-contas-caixa-checkout`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

Fechar o ciclo da hospedagem no app. A operadora abre o turno de caixa e vê a conta do hóspede, com lançamentos, taxa de serviço e extrato. Ela lança, estorna e ajusta (com aprovação quando preciso), recebe pagamentos e registra cauções. Por fim, faz o check-out: saldo zero, cauções liberadas, contas encerradas e UH vaga e suja no mapa. O turno fecha com o relatório.

## Contexto confirmado

- **Base:** M1 a M3, com os caminhos e comandos registrados nos handoffs.
- **Operações do contrato** (`api/openapi.yaml`, commit fixado):
  - **contas:**
    - `GET /api/v1/folios`, `GET /api/v1/folios/{id}` e `PUT /api/v1/folios/{id}` (pagador e limite de crédito);
    - `POST /api/v1/folios/{id}/close` e `/reopen`;
    - `GET /api/v1/folios/{id}/statement`;
    - `GET /api/v1/folios/{id}/statement/pdf` (desde o pin `57eb5fac5b8a`, M0 E02), o mesmo extrato em PDF A4, com `audience` (padrão `GUEST`) e `billing.folios.read`. O `POST …/statement/print` (fila do servidor) é da M7 E12 ([ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)).
  - **lançamentos:**
    - `GET`/`POST /api/v1/folios/{id}/postings`;
    - `POST /api/v1/folios/{id}/postings/{postingId}/reverse`, `/adjust`, `/split` e `/remove-service-charge`;
    - `POST /api/v1/folios/{id}/postings/transfer`;
    - `GET /api/v1/transaction-codes`.
  - **pagamentos:** `GET`/`POST /api/v1/folios/{folioId}/payments` e `POST /api/v1/folios/{folioId}/payments/{paymentId}/refund`.
  - **cauções:**
    - `GET`/`POST /api/v1/folios/{folioId}/card-pre-authorizations`;
    - `POST /api/v1/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}/capture` e `/release`;
    - `GET /api/v1/card-pre-authorizations/expiring`.
  - **turno de caixa:**
    - `GET`/`POST /api/v1/cashier-shifts`, `GET /api/v1/cashier-shifts/current`;
    - `POST /api/v1/cashier-shifts/{id}/close` e `/movements`;
    - `GET /api/v1/cashier-shifts/{id}/report`;
    - `GET /api/v1/cash-points`.
  - **check-out:** `POST /api/v1/reservations/{id}/check-out`.
- **Regras do servidor:**
  - contas imutáveis (estorno, nunca edição);
  - taxa de serviço de 10% separada e removível a pedido, com registro (RN-10);
  - recebimento sem turno aberto é recusado;
  - turno cego;
  - estorno, reembolso, reabertura e anonimização com aprovação de superior;
  - encerramento só com saldo zero e sem caução;
  - extrato `INTERNAL` e `GUEST`;
  - check-out lança o que o fechamento nunca lançará (H14 do M5 do servidor);
  - `Idempotency-Key` obrigatório nas operações financeiras (ARCHITECTURE §7).

  Detalhes nas notas do brain `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)` e `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, e no gate do M3 do servidor (`docs/roadmaps/README.md` do servidor, 2026-10-20).
- **PCI-DSS:** o servidor nunca recebe número de cartão, só token, bandeira e 4 últimos dígitos (ARCHITECTURE §8.3). O pagamento é registro manual até o M7 do servidor.
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6.2 e §7 (check-out neste marco).

## Escopo

**Incluído:**

- conta da hospedagem e seus lançamentos;
- turno de caixa;
- pagamentos e reembolsos manuais;
- cauções;
- check-out;
- extrato, na tela e em PDF, no visualizador da M2 E07;
- seed ampliado;
- roteiro do M4;
- as dívidas da M3 E06 que o usuário mandou resolver antes do dinheiro: a fonte única de permissões (D2, E06) e a regra única do resultado desconhecido (D1, E01). Os demais achados da M3 E06 que o handoff dela destinou a este marco também entram, cada um na etapa nomeada.

**Fora de escopo:**

- cadastro de pontos de caixa, códigos de lançamento e regras de roteamento (M7);
- Pix e cartão integrados, TEF e fiscal (M7 e M8 do servidor, ainda não existem);
- envio do extrato pela fila de impressão do servidor (M7 E12). O PDF e a impressão local entram na E02 (ADR 0009);
- fechamento do dia, que lança as diárias (M6 E08, depois da M6 E11 do servidor).

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru;
  - enum extensível tolerado: o `type` do lançamento é `x-extensible-enum` (ARCHITECTURE §7);
  - `ETag` e `If-Match`;
  - permissões efetivas, com o servidor decidindo.
- **Dinheiro:** sempre `BigDecimal` e string decimal na API, nunca `Double`. O front nunca calcula saldo, taxa nem total: mostra o que o servidor devolve.
- **Idempotência:** toda operação financeira leva `Idempotency-Key` por intenção. Repetir depois de erro de rede ou de aprovação reaproveita a chave. Nenhum duplo clique gera dois lançamentos.
- **Cartão:** nenhum campo de número de cartão no app. Na caução, só os dados que o contrato pede (token ou referência, bandeira e últimos dígitos).
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1.
- **Resultado desconhecido (D1):** toda escrita idempotente do marco classifica a falha pela função única do `core` criada na primeira tarefa da E01. Nenhum estado novo copia a regra.
- **Permissões (D2, [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md)):** as áreas e o topo da casca usam o conjunto de permissões que o `ShellState` leu na sessão vigente, recebido por parâmetro. Nenhuma área lê o `me` por conta própria, e a falha dessa leitura é a falha da casca.
- **Servidor:** só leitura, e o commit fixado não muda no marco.
- **Idioma:** UI em pt-BR, código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O check-out do servidor devolve erro tipado (`code`) quando há saldo, caução ou conta aberta. Com isso o front monta uma lista de pendências antes de confirmar | O assistente de check-out passa a conferir a conta por leitura antes de chamar o check-out | E04 |
| H2 | O seed consegue abrir um turno para a Recepcionista e lançar consumos nas contas dos hospedados pela API | O roteiro abre o turno à mão no início | E05 |
| D1 | **Decidida pelo usuário em 2026-11-17** (D4 do M3, na entrevista do fim do lote da M3 E06, pela opção recomendada). A regra do "resultado desconhecido" (`A-M10`/`B-M7`) está copiada em 8 estados, com 3 variantes. Ela vira uma função única no `core`, na **primeira tarefa da E01**, antes da primeira escrita de dinheiro. Vão junto o `B-M2a`, o `WalkInState`, a criação sem reserva como `IncompatibleResponse` e os Menores M1, M2 e M4 a M7 da revisão final da M3 E06 | Sem a extração, o M4 cria mais cópias em telas de dinheiro | E01 |
| D2 | **Decidida pelo usuário em 2026-11-17** (D5 do M3; desenho fechado por entrevista na sessão do arquiteto, as quatro respostas pela opção recomendada): **uma fonte de permissões por sessão**, registrada no [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md). O estado fica no `ShellState` e vai às áreas por parâmetro. A falha e o "Tentar de novo" são os da casca. O valor vive no `ShellState` da sessão, recriado a cada `sessionId`. A migração das áreas existentes é uma etapa própria (E06), antes da E01 | Sem a fonte, cada área do M4 repetiria a leitura e o estado de falha | E06, E01, E02 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] `smokeTest` verde contra o ambiente recriado.
- [ ] Roteiro `docs/demo/roteiro-m4.md`, acumulando os anteriores, executado pelo usuário no Windows.
- [ ] Teste de idempotência em toda operação financeira do marco.
- [ ] Nenhuma etapa depende de pedido ao servidor.

## Notas de máquina e de processo

Vêm do handoff da M3 E06 ("Notas para a próxima etapa (M4 E01)") e valem para todas as etapas deste marco. Quem planeja leva cada uma às Global Constraints do plano ou ao despacho das tarefas.

- **Testes de UI rolam até a linha antes do clique.** Um teste que procura por texto uma linha que não é a primeira de uma lista ou tabela chama `performScrollToNode` antes do clique. Com o detalhe aberto, a lista fica com 96 px na janela de teste, e o resultado passa a depender das métricas da fonte da máquina. Exemplo no repositório: `ReservationsActionsTest.kt` (correção `45c4ce4`).
- **`detektMain` e `detektTest` por tarefa.** A `verificação rápida` do `CLAUDE.md` roda `detekt`, mas não `detektMain` nem `detektTest`, que têm resolução de tipos e só rodam no `check` ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), Consequências). Nas tarefas de Kotlin, o despacho pede `:<módulo>:detektMain` e `:<módulo>:detektTest` junto da verificação rápida, para o achado não esperar o gate.
- **Conferência do `C-M6`.** Antes de mandar o desenvolvedor a um arquivo de teste, confira se o testador o endureceu: `git log --format=%B -- <arquivo> | grep -c '^Soul: testador'`. Com contagem maior que zero, a mudança vai ao testador.
- **Ubuntu novo (WSL):**
  - o locale do sistema é só `C.UTF-8`;
  - o `awk` é o gawk;
  - a configuração global do git tem `core.autocrlf = input`.

  Os scripts de `tests/` já não dependem disso (`58b6f2d`: `[[:alpha:]]`, `ENVIRON` no awk e independência do `core.autocrlf`). Script novo segue as mesmas formas, e uma falha que só aparece nesta máquina é tratada no teste, sem reconfigurar o sistema (ruling da M3 E06). O `busybox` não está instalado (recomendado: `sudo apt install busybox-static`), e o caso do busybox em `test_gen_env_ataque.sh` fica pulado até lá. A imagem do oasdiff já está baixada.

## Protocolo de execução

Vale para toda execução deste roadmap. O planejamento e a execução de cada etapa são feitos pelo plugin superpowers; este protocolo diz como encadear as etapas e manter o roadmap em dia.

**Papéis**

| Papel | Soul | Usa |
|---|---|---|
| Orquestrador | `orquestrador` (a sessão principal, iniciada com `claude --agent orquestrador`) | este protocolo; atualiza tags e handoffs; roda a verificação mecânica dos testes |
| Planejador | `orquestrador` | `superpowers:writing-plans` |
| Implementador | `desenvolvedor`, subagente por tarefa; tarefas independentes em ondas paralelas | `superpowers:subagent-driven-development`, prompt implementer |
| Testador | `testador`, subagente nas tarefas `Risco: alto`, nas lacunas da revisão e uma vez por etapa | testes, sem tocar em código de produção |
| Revisor de plano | `revisor-arquitetura`, subagente novo por etapa, modelo mais capaz | revisão do plano antes da execução |
| Revisor de tarefa | `revisor-seguranca`, subagente novo por tarefa | revisão da `subagent-driven-development` |
| Revisor final | `revisor-seguranca`, subagente novo, modelo mais capaz | revisão final da `subagent-driven-development` |
| Captura no brain | `brain-escriba` | publica a captura de atualização em `00 Inbox/` |

Souls: este protocolo exige `orquestrador`, `desenvolvedor`, `testador`, `revisor-seguranca` e `revisor-arquitetura`. Não há fallback: se a sessão não roda como `orquestrador` ou alguma dessas souls não está instalada, pare e diga ao usuário para configurar a máquina (skill `configurar-maquina`) ou iniciar com `claude --agent orquestrador`. O caminho inline (`superpowers:executing-plans`) não vale aqui.

**Branch**

- Toda execução acontece no worktree/branch `roadmap/m4-contas-caixa-checkout`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Pré-voo do servidor**

Antes de escolher a etapa, rode `tools/server-drift.sh` e registre o resultado no relatório do lote:

- igual: siga;
- só acréscimos: siga, sem trocar o commit fixado;
- breaking change em operação usada pelo front: pare e recomende a rodada do M0.

O repositório do servidor é só leitura.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m4-contas-caixa-checkout-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m4-contas-caixa-checkout Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m4-contas-caixa-checkout.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m4-contas-caixa-checkout com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Rode o pré-voo do servidor (tools/server-drift.sh) e siga a regra do protocolo.
5. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + design citado), execute com superpowers:subagent-driven-development, feche a etapa e preencha o handoff conforme o protocolo.
6. Pare nas paradas do protocolo ou ao atingir o limite.
7. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

O repositório do servidor (/home/caio/repos/ZenyHotelManager) é só leitura: nunca escreva nele. Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, o resultado do pré-voo, a branch e os commits do lote e o próximo passo sugerido.
```

## Índice de etapas

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E06 | Fonte única de permissões | #concluida | — | As áreas usam as permissões lidas pela casca na sessão (ADR 0013); saem as leituras por área e a falha silenciosa |
| E01 | Turno de caixa | #pendente | E06 | Regra única do "resultado desconhecido" no `core` (D1); abrir, acompanhar, sangria e suprimento, fechar às cegas e relatório |
| E02 | Conta da hospedagem | #pendente | E06, E01 | Conta com lançamentos, lançar, estornar, ajustar, dividir, transferir, taxa de serviço, pagador e extrato |
| E03 | Pagamentos e cauções | #pendente | E01, E02 | Receber e reembolsar, registrar, capturar e liberar caução, cauções a vencer |
| E04 | Check-out | #pendente | E03 | Assistente de check-out até o saldo zero, contas encerradas e UH liberada |
| E05 | Gate do marco M4 | #pendente | E04 | Seed com contas e turno, roteiro do M4 e definição de pronto verificada |

---

## E06 — Fonte única de permissões — #concluida

**Objetivo:** as áreas do app usam o conjunto de permissões do `me` que a casca leu na sessão vigente, conforme o [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md). Nenhuma área lê o `me` por conta própria, e a falha da leitura passa a ser a falha da casca, com o "Tentar de novo" dela. A Recepção e os Recados deixam de esconder ações por falha silenciosa.

**Por que agora:** D2 (D5 do M3). A etapa vem antes da E01 para o caixa (E01) e a conta (E02) já nascerem usando a fonte. Sem ela, cada área do M4 repetiria a leitura e o estado de falha (ADR 0013, item 5).

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M3). O ID é o próximo livre; a posição no índice segue a dependência.

**Escopo:**

- **Casca:** o `ShellState` guarda o conjunto de permissões do `me` que deu certo, numa cópia imutável. Ele atribui o conjunto junto com o menu e o zera no início de cada `reload()` (ADR 0013, itens 1 e 4).
- **Entrega:** o conjunto vai por parâmetro do `AreaContent` às áreas Reservas, Recepção (painel do dia e walk-in) e Recados, e aos *state holders* delas pelo construtor (ADR 0013, item 2).
- **Remoção:**
  - o `AreaPermissionsState` e o `PermissionsNotice`;
  - as portas `permissions()` de `ReceptionSource`, `AvailabilitySource` e `TracesSource`, com as implementações delas em `ServerConnection`;
  - o fallback "falha vira conjunto vazio" do `DayPanelState` e do `TracesListState`.
- **Testes:** os testes e as portas falsas afetados são reescritos ou removidos (ADR 0013, Consequências), sem afrouxar as asserções do comportamento por permissão que a M3 deixou.

**Fora de escopo:**

- releitura das permissões no meio da sessão (ADR 0013, item 4);
- mudança no `core`: a fachada `account.me()` fica como está;
- área nova (o caixa é da E01).

**Critérios de aceite:**

- [x] Só a casca lê o `me`. `grep -rn 'fun permissions' app/src/main` não devolve linha, e `grep -rn 'account\.me()' app/src/main` devolve só a implementação de `ServerConnection.me()`, chamada só pelo `ShellState`.
- [x] `grep -rnE 'AreaPermissionsState|PermissionsNotice' app/src/main` não devolve linha.
- [x] Teste: com uma porta falsa que conta as chamadas ao `me`, abrir a casca e depois a Recepção, as Reservas e os Recados, em sequência, registra uma leitura só.
- [x] Teste: com o `me` falhando, a casca mostra o `ErrorState`, e nenhuma área é desenhada. O "Tentar de novo" relê, e com o `me` dando certo na repetição as ações da área aberta seguem as permissões lidas.
- [x] Teste: na troca de `sessionId`, as áreas da sessão nova nunca recebem o conjunto da anterior, nem com a leitura da sessão nova ainda em voo. Do início do `reload()` até a resposta, o `ShellState` não expõe o conjunto velho.
- [x] Teste: o `DayPanelState` assina os tópicos do tempo real pelo conjunto recebido da casca, e o `WalkInState` recebe o mesmo conjunto.
- [x] O comportamento por permissão da M3 continua verde com o conjunto entregue pela casca:
  - a aba de disponibilidade só com `reservations.availability.read`;
  - as ações da reserva só com `reservations.reservations.manage`;
  - editar e resolver recado só com as permissões de cada ação.

  Toda `ASSERCAO_REMOVIDA` de `check_test_integrity.py` na etapa tem justificativa da revisão, com a asserção nova de força igual ou maior.
- [x] O handoff registra o commit que removeu o `AreaPermissionsState`, que a E01 cita no destino dos Menores M1, M2 e M5 da M3 E06.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`: entrar como Recepcionista e como Governanta do seed e abrir cada área que o menu mostra. As ações seguem as permissões, e não aparece aviso de permissões na área.

**Revisão humana:** não. O desenho foi decidido pelo usuário (ADR 0013), a revisão de arquitetura do plano confere a aderência a ele, e a etapa não muda o que o servidor autoriza: o servidor continua decidindo cada ação.

**Notas para o plano:**

- **Risco:** autorização está na lista fechada de `Risco: alto` (ADR 0012). Por isso, testador nas tarefas que mexem na entrega do conjunto.
- **Arquivos do testador (`C-M6`).** Muitos dos testes afetados foram endurecidos pelo testador. No `dbeacff`, a contagem de `Soul: testador` era maior que zero, entre outros, em:
  - `FakeReceptionSource.kt` (4) e `FakeTracesSource.kt` (2);
  - `ReservationsPermissionsAtaqueTest.kt` (2) e `AreaPermissionsStateAtaqueTest.kt` (1);
  - `FakeAvailabilitySourceAtaqueTest.kt`, `ReceptionSourceAtaqueTest.kt`, `CoreReceptionSourceAtaqueTest.kt`, `CoreTracesSourceAtaqueTest.kt` e `FakeTracesSourceAtaqueTest.kt` (1 cada).

  Confira de novo com o comando de "Notas de máquina e de processo" no momento do plano.
- **Ondas:** a casca (guardar e entregar o conjunto) vem primeiro. A migração de cada área depende dela, e as três são independentes entre si. O ajuste das portas falsas pode ser um commit próprio (nota N2 da revisão de arquitetura da M3 E06).
- **Código que sai e achados da M3 E06.** Os Menores M1, M2 e M5 da revisão final da M3 E06 (destino: E01) apontam para o `AreaPermissionsState` e um teste dele. Esta etapa não os corrige à parte: remove o código e registra o commit.
- Os testes de UI que montam o `AppRoot` com portas falsas mudam de forma. Rode também os filtros dos outros testes que passam pelo `AppRoot` (nota N3 da mesma revisão).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A troca mexe em muitos arquivos de teste de três áreas. Mitigação: as ondas acima e as fixtures num commit próprio.
- Uma asserção do comportamento por permissão se perde na reescrita. Mitigação: o critério da `check_test_integrity.py` e o testador de etapa atacando a entrega do conjunto.

**Evidências e handoff:**

- Plano: [`docs/superpowers/plans/2026-11-17-m4-contas-caixa-checkout-e06.md`](../superpowers/plans/2026-11-17-m4-contas-caixa-checkout-e06.md). Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado na rodada 0 (3 Importantes: a amarração sessão × conjunto, os filtros das fatias e a ruling do `C-M6` sem registro), aprovado com notas na rodada 1 (`371baac`).
- Commits (`MERGE_BASE` `510bc44`, HEAD `785d864`):
  - Task 1, a casca guarda e entrega o conjunto, e as Reservas o consomem: `847bfdb`, `bca85f1`, `6dd85d3`. Revisão de fatia (`revisor-seguranca`): aprovada, 0 Críticos e 0 Importantes.
  - Task 2, a Recepção e os Recados consomem o conjunto, e as portas saem: `b749a58`, `6b575c2`. Revisão de fatia: aprovada, 0 Críticos e 0 Importantes.
  - Testador de etapa: `9b4a355` (19 testes novos, auditoria dos 30 arquivos do testador alterados pelo desenvolvedor, nenhum defeito de produção).
  - Correção do fim da etapa: `785d864` (um `DisposableEffect` por *holder* na Recepção e na Reservas; `import` do `Set.copyOf`).
  - **Remoção do `AreaPermissionsState`** (e do `PermissionsNotice` e do `ReservationsPermissionsAtaqueTest`): **`bca85f1`**. É o commit que a E01 cita no destino dos Menores M1, M2 e M5 da M3 E06.
- Revisão final (`revisor-seguranca`, fable, `code-reviewer.md`, sobre `510bc44..6b575c2`): 0 Críticos, 2 Importantes de teste (o ataque integrado do `me` da sessão A preso e a entrega aos Recados pela casca), fechados pelo testador em `9b4a355`. A re-revisão escopada de `6b575c2..785d864` saiu limpa: "Ready to merge: Yes".
- Gate no HEAD `785d864`: `./gradlew build` → BUILD SUCCESSFUL, com o `:app:test` executado (2572 testes, 0 falhas, 45 pulados, que são os checks ao vivo); `bash tests/run.sh` → 60 arquivos, 0 falhas, 1 com casos pulados (o busybox, alheio à etapa).
- Scanner: `osv-scanner --recursive --no-ignore ./` no `6b575c2` → 6 lockfiles do Gradle (1429 pacotes), "No issues found". A etapa não muda dependência, catálogo, plugin nem lockfile.
- Integridade: `check_test_integrity.py --base 510bc44` no `785d864` → exit 1, 222 `ASSERCAO_REMOVIDA` e 21 `TESTE_REMOVIDO`, sem avisos. Cada remoção tem justificativa da revisão (assunto extinto pelo ADR 0013, mudança de regra decidida ou substituto de força igual ou maior), conferida por nome nas revisões de fatia e na final. O script conta `TESTE_REMOVIDO` líquido (remoções menos adições) e atribui por arquivo de forma pouco confiável; em etapa que apaga e cria muitos testes, o mapa precisa ser conferido por nome. Ele também só marca `DEV_TESTE_DO_TESTADOR` para commits do testador dentro do intervalo: a lista dos arquivos do testador alterados foi conferida contra o histórico pelo orquestrador.
- Critérios verificados:
  1. Greps no `785d864`: `fun permissions` em `app/src/main` não devolve linha; `account.me()` só em `ServerConnection.kt:168` (`CoreConnection.me()`); `.me()` só nessa linha e em `ShellState.kt:81`.
  2. `AreaPermissionsState|PermissionsNotice` em `app/src/main` não devolve linha.
  3. Leitura única: `ShellMeReadCountTest.oneMeReadAcrossAreas` (`meCalls == 1`; o vermelho registrava 5) e `ShellPermissionsAtaqueTest.receptionTracesFromTheShellShowTheListWithOneMeRead`.
  4. `me` que falha: `ShellPermissionsTest.failedMeDrawsNoAreaAndRetryGrants` e `failedMeDrawsNoReceptionAndRetryGrants`.
  5. Troca de `sessionId`: `ShellStateTest.reloadClearsPermissionsBeforeTheAnswer`, `ShellPermissionsTest.newSessionAreaNeverSeesThePreviousSet`, `areaWithForeignSessionDrawsNothing`, `receptionWithForeignSessionDrawsNothing` e, do testador, `heldMeOfSessionAIsCancelledAndSessionBAreaNeverSeesItsLargerSet`.
  6. `DayPanelStateTest.subscribesTopicsFromTheReceivedSet` e `ReceptionShellTest.walkInGetsTheShellSet`.
  7. Comportamento por permissão da M3: `ShellPermissionsTest.receivedSetDecidesTabAndActions`, os testes de Reservas, Recepção e Recados adaptados e `TracesShellSetAtaqueTest`, todos verdes no gate; as `ASSERCAO_REMOVIDA` com justificativa da revisão.
  8. Commit da remoção do `AreaPermissionsState`: `bca85f1` (acima).
- Validação manual: **pendente do usuário.** O `zeny-front` estava de pé, mas as credenciais `ZENY_LIVE_*` não estavam no ambiente da sessão, e o orquestrador não procurou segredos na máquina. Entrar como Recepcionista e como Governanta do seed e abrir cada área do menu; os `Live*Check` tocados pela etapa só compilaram.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **`C-M6` na E06.** O desenvolvedor fez a adaptação forçada em 30 arquivos com commit do testador e apagou os testes cujo assunto deixou de existir (`AreaPermissionsStateAtaqueTest`, `ReservationsPermissionsAtaqueTest`, a porta `permissions()` das fixtures), com o mapa para os substitutos. Diverge da letra do ADR 0013 (Consequências) e da nota `C-M6`, porque a remoção da porta quebra a compilação dos testes no mesmo commit. O testador de etapa auditou os 30 arquivos e repôs o que tinha só substituto parcial. Custo se errado: uma asserção do testador enfraquecida, coberta pela integridade, pelas revisões e pela auditoria.
  2. **`Error` no `me`.** A casca captura `Exception`, nunca `Throwable` (correção do M2 da M3 E06). Um `Error` da JVM no `me` deixa a casca em "carregando", sem conceder nada; não há tela de erro. Custo se errado: um `Error` raro trava a casca até sair e entrar de novo.
  3. **Verificação ao vivo não rodada** pelo orquestrador (acima).
- Notas para a próxima etapa (E01):
  - **M1, M2 e M5 da M3 E06:** resolvidos pela remoção em `bca85f1`. A leitura que ficou (`ShellState.reload()`) usa `catch (e: Exception)` com `ensureActive()`, e o `permissions` fica nulo com `error` (`b749a58`).
  - **Para a passada do testador na E01:** o nome desatualizado `panelPermissionsStayEmptyWhileTheReadIsPendingAndNeverCallPermissionsTwice` em `FakeReceptionActionsAtaqueTest`, hoje eco do construtor; a seção de recados da reserva (`forReservation` em `receptionTraces`) sem asserção pela casca; a mensagem "sem ler a permissão" em `ReservationsAvailabilityTabAtaqueTest`.
  - `ShellStateTest.errorNeverComesWithPermissions` provoca a falha tardia pelo `writeObserver` do snapshot do Compose; é frágil a mudanças internas do runtime.
  - O indicador de turno no topo (E01) lê `ShellState.permissions`. A área nova recebe o conjunto e o `sessionId` da casca pelo `AreaContent` e segue a amarração sessão × conjunto (desenha só com o `sessionId` ativo igual ao da casca; *holders* com `remember(source, sessionId, permissions)` e um `DisposableEffect` por *holder*).
  - Os testes de UI que montam a casca passam o conjunto pelo `me` da conexão falsa; as portas falsas não têm mais `permissions()`.

---

## E01 — Turno de caixa — #pendente

**Objetivo:** a primeira tarefa transforma a regra do "resultado desconhecido" numa função única do `core` (D1), antes da primeira escrita de dinheiro, e leva junto os achados da M3 E06 que dependem dela. Depois, a operadora abre o próprio turno num ponto de caixa e vê o turno corrente. Ela registra sangria e suprimento e fecha às cegas, informando o contado. O app mostra o relatório de fechamento com as diferenças, e o topo indica se há turno aberto.

**Por que agora:** o pagamento (E03) e o check-out (E04) exigem turno aberto. A sangria, o suprimento e o fechamento são as primeiras escritas de dinheiro do app, e o usuário decidiu que a regra única vem antes delas (D1).

**Dependências:** E06 (a área de caixa e o indicador do topo usam a fonte de permissões).

**Escopo:**

- **Regra do resultado desconhecido (D1, primeira tarefa):**
  - **A função:** uma função pública do `core` classifica a falha de uma escrita idempotente em três casos:
    - definitiva;
    - desconhecida, que admite repetir com a mesma chave;
    - desconhecida e guardada pelo servidor (o 5xx sob a chave, que repetir não resolve).
  - **Os estados:** os 8 estados que copiam a regra no `dbeacff` passam a usar a função:
    - `CheckInState`, `RoomMoveState`, `WalkInState` e `TraceFormState`;
    - `NewReservationState`, `ReservationConfirmState`, `ReservationCancelState` e `ReservationReactivateState`.

    Os textos de cada ação continuam com o estado.
  - **`createBooking`:** usa o ajudante `approvable` comum do `core` (parte do `A-M10`).
  - **`B-M2a`:** com o 5xx guardado, o texto manda fechar, e o diálogo não oferece "Tentar de novo".
  - **`WalkInState`:** resultado desconhecido seguido de aprovação cancelada não deixa o formulário travado e sem texto (o desenho do `A-M1`, corrigido nos três estados de reservas pela Task 6 da M3 E06).
  - **Criação sem reserva:** uma resposta de criação sem reserva vira `ApiError.IncompatibleResponse`, com texto próprio, e não "resultado desconhecido".
  - **Menores da revisão final da M3 E06:**
    - M1 e M2 (no `AreaPermissionsState`: o cancelamento alheio e o `catch (e: Throwable)`);
    - M4, M5, M6 e M7 (testes e trava; ver as notas).
- **Pontos de caixa:** `GET /api/v1/cash-points` (só leitura).
- **Turno:**
  - `GET`/`POST /api/v1/cashier-shifts` e `GET /api/v1/cashier-shifts/current`;
  - `POST /api/v1/cashier-shifts/{id}/movements` e `/close`;
  - `GET /api/v1/cashier-shifts/{id}/report`.
- **Topo da casca:** indicador de turno. A área de caixa e o indicador usam o conjunto de permissões do `ShellState` (ADR 0013).

**Fora de escopo:**

- cadastro de pontos de caixa (M7);
- o cursor duplicado entre fachadas, citado na mesma linha do `A-M10`/`B-M7`. Não é a regra do resultado desconhecido; a nota da E02 cuida de não criar outra cópia.

**Critérios de aceite:**

- [ ] **Função única:** existe no `core` uma função pública que classifica a falha de uma escrita idempotente nos três casos do escopo. Testes do `core` cobrem cada caso de `ApiError`:
  - `Network`, `IncompatibleResponse` e `SessionEnded`;
  - `Problem` com `IDEMPOTENCY_KEY_IN_USE`;
  - `Problem` 5xx e `Unexpected` 5xx;
  - pelo menos um `Problem` 4xx definitivo.
- [ ] **Nenhuma cópia no `app`:** `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` não devolve linha. Os 8 estados do escopo e as escritas desta etapa usam a função do `core`. Em `CheckInState`, `RoomMoveState` e `TraceFormState`, um teste prova que um `Problem` 5xx agora dá resultado desconhecido.
- [ ] **`approvable` único:** `grep -n 'private fun <T : Any> approvable' core/src/main/kotlin/com/zeny/desktop/core/facade/BookingsFacade.kt` não devolve linha, e `createBooking` usa o ajudante comum.
- [ ] **`B-M2a`:** com o 5xx guardado, o diálogo mostra o texto de fechar e conferir e não oferece "Tentar de novo". Há teste nos estados que têm esse texto no `dbeacff`: walk-in, nova reserva, cancelar e reativar.
- [ ] **`WalkInState`:** um teste prova que resultado desconhecido, seguido de "Tentar de novo" com `APPROVAL_REQUIRED` e da aprovação cancelada, deixa um texto na tela e não trava o formulário.
- [ ] **Criação sem reserva:** um teste prova que a resposta de criação sem reserva vira `ApiError.IncompatibleResponse`, com texto próprio em pt-BR, e não deixa o estado em "resultado desconhecido".
- [ ] **M1, M2 e M5:** o handoff dá a cada um um destino com commit: corrigido, ou resolvido pela remoção do `AreaPermissionsState` na E06 (commit citado no handoff dela). A leitura do `me` que ficou, no `ShellState`, não engole o cancelamento alheio (usa `ensureActive()`) e não pega `Throwable`.
- [ ] **M4, M6 e M7, pelo testador:**
  - os dois testes de `NewReservationDialogEtapaAtaqueTest` apontados no M4 têm nome e asserção fortalecidos;
  - `NewReservationFlowAtaqueTest` tem o caso "tentativa aprovada com resultado desconhecido, depois aprovação cancelada" (M6);
  - um mutante que explora o contorno da trava do `config.` em `tests/test_live_seed_availability_limpeza_ataque.sh` (linhas 95–101 no `dbeacff`) fica vermelho (M7).
- [ ] Abrir o turno como Recepcionista do seed: o indicador aparece no topo, e reabrir com um turno já aberto mostra mensagem em pt-BR.
- [ ] Sangria e suprimento aparecem no turno, e repetir após erro de rede não duplica (`Idempotency-Key`, se a operação exigir).
- [ ] O fechamento cego não mostra o esperado antes de informar o contado. O relatório mostra a diferença em BRL.
- [ ] A diferença de caixa gera a notificação ao Gerente no servidor (conferida por `GET /api/v1/notifications` como Gerente). A tela de notificações é do M6.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- "Turno cego" e a notificação de diferença foram validados no gate do M3 do servidor (histórico do `docs/roadmaps/README.md` do servidor, 2026-10-20).
- **Fonte dos itens do D1:**
  - a tabela "Destino dos achados que este plano não corrige" de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`;
  - o handoff da M3 E06 em `docs/roadmaps/roadmap-m3-reservas.md` ("Destino dos achados" e "Notas para a próxima etapa (M4 E01)").

  A correção do `A-M1` nos três estados de reservas é a Task 6 daquele plano (`03542f0`), e é o modelo para o `WalkInState`.
- **As 3 variantes da regra**, no `dbeacff`. Todas tratam como desconhecidos `Network`, `IncompatibleResponse` e `SessionEnded`. A diferença está no resto:
  - **completa**, em `WalkInState`, `NewReservationState`, `ReservationConfirmState`, `ReservationCancelState` e `ReservationReactivateState`: também `IDEMPOTENCY_KEY_IN_USE`, `Problem` 5xx e `Unexpected` 5xx;
  - **sem o 5xx**, em `CheckInState` e `RoomMoveState`: também `IDEMPOTENCY_KEY_IN_USE`;
  - **mínima**, em `TraceFormState`: só as três comuns.

  A função única adota a variante completa. Nos três estados das outras variantes, um 5xx (e, nos recados, o `IDEMPOTENCY_KEY_IN_USE`) passa de definitivo a desconhecido. A mudança é intencional, conservadora (não duplica), e tem teste em cada um desses estados. O servidor guarda a resposta sob a chave qualquer que seja o status (KDoc do `IdempotencyFilter` do servidor, linhas 55–60, citado no `B-M2a`), e os 502/504 de proxy não são guardados (comentário em `WalkInState.fail`). A função preserva essa distinção no caso "guardado".
- **Ordem e ondas:** a extração e as correções que dependem dela vêm antes de qualquer escrita do turno. As leituras do turno (pontos de caixa, turno corrente e relatório) não dependem da extração e podem ir na mesma onda dela.
- **Testador:**
  - O M4, o M6 e o M7 são do testador (arquivos dele; conferência do `C-M6`).
  - O M5 (o nome de `leavingWhileReadIsHeldDropsTheLateAnswer`) é de um teste do `AreaPermissionsState`, que a E06 remove. Se o teste ainda existir, ele também é do testador.
- **Risco:** cálculo de valor monetário e concorrência estão na lista fechada de `Risco: alto` (ADR 0012). Por isso, testador por tarefa nas escritas do turno e na extração.
- **Módulos tocados:** a extração mexe no `core`, e a verificação rápida cobre o `core` e o `app`. Uma mudança na trava do seed (M7) roda também `bash tests/run.sh tests/test_live_seed_availability_limpeza_ataque.sh`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A extração toca 8 estados de três áreas antes da tela nova, e o plano pode passar de três fatias. Mitigação: a extração e os achados ficam numa fatia, e o turno na outra. Se não couber, o orquestrador para e relata, e a divisão da etapa é feita numa sessão do arquiteto.
- Uma variante da regra muda de comportamento sem querer. Mitigação: os testes existentes dos 8 estados ficam sem afrouxar asserção (`check_test_integrity.py`), e o testador ataca a função nova.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Conta da hospedagem — #pendente

**Objetivo:** a partir do detalhe da reserva ou da área Contas e caixa, o usuário abre a conta. Ele vê os lançamentos, o saldo e a taxa de serviço separada. Lança consumos por código de lançamento, estorna, ajusta, divide, transfere entre contas e remove a taxa a pedido do cliente. Também troca o pagador e o limite de crédito e vê o extrato `GUEST` e `INTERNAL`.

**Por que agora:** a conta é o centro financeiro da hospedagem e pré-requisito do check-out.

**Dependências:** E06 (fonte de permissões) e E01 (as escritas da conta usam a função única do resultado desconhecido, D1).

**Escopo:**

- **Contas:** `GET /api/v1/folios`, `GET`/`PUT /api/v1/folios/{id}` e `POST /api/v1/folios`, se a conta avulsa fizer sentido na tela.
- **Lançamentos:**
  - `GET`/`POST /api/v1/folios/{id}/postings`;
  - `/reverse`, `/adjust`, `/split` e `/remove-service-charge` em `POST /api/v1/folios/{id}/postings/{postingId}/…`;
  - `POST /api/v1/folios/{id}/postings/transfer`.
- **Códigos de lançamento:** `GET /api/v1/transaction-codes`.
- **Extrato:** `GET /api/v1/folios/{id}/statement` na tela e `GET /api/v1/folios/{id}/statement/pdf` no visualizador da M2 E07 (em memória, "Salvar como…" e impressão local, ADR 0009), com a mesma escolha de `audience`.
- **Aprovação de superior:** diálogo onde o servidor exigir.
- **Atalho:** "Conta" no detalhe da reserva.
- **Multa de cancelamento (achados da M3 E06):**
  - `B-M5`: os textos da multa numa reserva cancelada mais de uma vez;
  - a isenção da multa, verificada ao vivo. Na M3 E04 o diálogo oferece "Isentar a multa", mas a isenção nunca foi verificada ao vivo.

**Fora de escopo:**

- encerrar e reabrir a conta, que ficam no check-out (E04) ou num atalho gerencial registrado no handoff;
- pagamento (E03);
- a prévia da multa antes de cancelar, que depende do pedido não bloqueante `PF-04` (D2 do M3).

**Critérios de aceite:**

- [ ] Lançar um consumo numa conta do seed atualiza o saldo e a taxa de serviço separada, com os valores iguais aos da API.
- [ ] Estornar como Recepcionista sem permissão abre o diálogo de aprovação. Aprovado pelo Gerente, o estorno aparece como lançamento novo, sem apagar o original.
- [ ] Duplo clique em "lançar" gera um só lançamento (teste com a mesma `Idempotency-Key`).
- [ ] O extrato `GUEST` não mostra os lançamentos internos que o servidor omite.
- [ ] O extrato em PDF abre no visualizador com o mesmo saldo do extrato na tela, para `GUEST` e para `INTERNAL`, e nenhum arquivo é criado sem "Salvar como…".
- [ ] Um tipo de lançamento desconhecido aparece como "Outro (VALOR)".
- [ ] **`B-M5`:** cancelar, reativar e cancelar de novo uma reserva do seed. O texto da multa no diálogo de cancelamento e a multa que a conta mostra batem com os lançamentos da API. No segundo cancelamento o servidor não cobrou de novo na verificação ao vivo da M3 E04 (critério 2), e o texto não diz que cobrou. Isso vale num teste do estado e na verificação ao vivo.
- [ ] **Isenção ao vivo:** uma verificação ao vivo contra o `zeny-front` cancela uma reserva do seed com "Isentar a multa", com a aprovação do Gerente se o servidor pedir. A conta da reserva fica sem a multa, ou com o lançamento que a API devolver para a isenção, e a tela diz o mesmo que a conta.
- [ ] As ações da conta usam o conjunto de permissões da casca (ADR 0013), e as escritas usam a função única do resultado desconhecido (D1): o grep do critério da E01 continua sem linha.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.
- Verificação ao vivo da multa e da isenção, com as credenciais só no ambiente do subshell, como as da M3 E04.

**Revisão humana:** não

**Notas para o plano:**

- O `type` do lançamento foi o primeiro `x-extensible-enum` do servidor (ARCHITECTURE §7).
- **Fonte do `B-M5`:** a descrição está só na tabela de destinos do plano da M3 E06 (`docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`). O planejador lê o texto da multa no `ReservationCancelState` e no diálogo e o compara com o que a conta mostra. A verificação ao vivo da multa e da reativação é a `LiveReservationLifecycleCheck` (M3 E04), e a isenção pode entrar nela ou numa verificação ao lado. Ela escreve na demo, como a da M3 E04 (decisão 5 do handoff dela).
- **Cursor:** as listas de contas e lançamentos são as primeiras fachadas paginadas do M4. O `core` já tem `CursorPager` (`core/.../http/CursorPager.kt`), e o `A-M10`/`B-M7` apontou o cursor duplicado entre fachadas. A fachada nova usa o `CursorPager` e não cria outra cópia.
- Partes independentes, para as ondas do plano: o extrato (`…/statement` na tela e `…/statement/pdf` no visualizador) só lê a conta e não depende das ações sobre os lançamentos. A troca do pagador e do limite de crédito (`PUT /api/v1/folios/{id}`) também não depende delas.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Muitas ações numa tela só. Agrupar em menu de contexto por lançamento.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Pagamentos e cauções — #pendente

**Objetivo:** na conta, a operadora recebe um pagamento manual, com forma e valor, dentro do turno aberto. Ela reembolsa com aprovação, registra uma caução com os dados permitidos (sem número de cartão), captura ou libera a sobra e acompanha as cauções a vencer.

**Por que agora:** zerar o saldo é a condição do check-out.

**Dependências:** E01, E02.

**Escopo:**

- **Pagamentos:** `GET`/`POST /api/v1/folios/{folioId}/payments` e `POST /api/v1/folios/{folioId}/payments/{paymentId}/refund`.
- **Cauções:**
  - `GET`/`POST /api/v1/folios/{folioId}/card-pre-authorizations`;
  - `POST /api/v1/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}/capture` e `/release`;
  - `GET /api/v1/card-pre-authorizations/expiring`.
- **Campos do `design` (`A-M7` da M3 E06):** o `MoneyField` e o `DateField` passam a aceitar `enabled`, e a colagem acima de 1000 caracteres deixa de ser recusada em silêncio. Esta é a primeira tela que recebe valor em dinheiro.

**Fora de escopo:** Pix, TEF e cartão integrados, que não existem no servidor.

**Critérios de aceite:**

- [ ] Receber sem turno aberto mostra a mensagem do `code` em pt-BR e o atalho "abrir turno".
- [ ] Receber o saldo de uma conta do seed zera o saldo, e o pagamento aparece no turno.
- [ ] Teste: repetir o recebimento após falha de rede usa a mesma `Idempotency-Key`, e o servidor não duplica.
- [ ] O formulário de caução não tem campo de número de cartão (inspeção e teste do *state holder*).
- [ ] Liberar a sobra da caução muda a situação e o saldo conforme a API.
- [ ] **`A-M7`, `enabled`:** com `enabled = false`, o `MoneyField` e o `DateField` não aceitam digitação nem colagem (teste no `design`). O formulário de pagamento os desabilita enquanto `busy` e no resultado desconhecido (teste do *state holder*).
- [ ] **`A-M7`, colagem:** colar mais de 1000 caracteres no `MoneyField` e no `DateField` deixa um sinal visível em pt-BR, e não só ignora a colagem (teste no `design`). A forma do sinal é decidida no plano.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- Partes independentes, para as ondas do plano: pagamentos (`…/payments`) e cauções (`…/card-pre-authorizations`) usam operações distintas do contrato. O único vínculo é do servidor: a captura da caução gera um pagamento (`capturePaymentIds`), que a lista de pagamentos só lê. A lista de cauções a vencer (`GET /api/v1/card-pre-authorizations/expiring`) é leitura à parte.
- **`A-M7`:** os campos estão em `design/.../input/MoneyInput.kt` e `DateInput.kt`. A mudança no `design` é usada pelo `app`: a verificação rápida cobre os dois módulos, e as telas do M2 e do M3 que usam os campos continuam verdes. O ajuste dos campos não depende de pagamentos nem de cauções e pode abrir a primeira onda.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além da idempotência, coberta pelo critério.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Check-out — #pendente

**Objetivo:** a partir de uma saída do painel do dia (M2), um assistente mostra o que falta para sair: saldo, cauções e contas abertas, além da cotação de late check-out se for o caso. Ele leva a operadora a receber, liberar e encerrar, e conclui o check-out. A reserva sai de "hospedados", e a UH fica vaga e suja no mapa em tempo real.

**Por que agora:** fecha o ciclo da hospedagem na demo. Saiu da recepção (M2) porque depende de conta e caixa (design §7.1).

**Dependências:** E03.

**Escopo:**

- **Assistente:**
  - leitura das contas da reserva;
  - cotação de late check-out (`GET /api/v1/reservations/{id}/early-late-quote`);
  - recebimento (E03);
  - liberação de cauções (E03);
  - encerramento das contas (`POST /api/v1/folios/{id}/close`);
  - `POST /api/v1/reservations/{id}/check-out`.
- **Erros:** com pendências, mensagens em pt-BR por `code`.
- **Reabrir conta encerrada:** `POST /api/v1/folios/{id}/reopen`, com aprovação.
- **Duplicação com o `DayPanelState` (estacionado da M3 E01, destino da M3 E06):** o assistente parte de uma saída do painel do dia. Por isso, a duplicação entre o `DayPanelState` e o `ReservationSearchState` sai nesta etapa: as colunas da tabela de reservas, o `readRoomTypeNames` e a trava das leituras auxiliares. O destino previsto na M3 E01 era `reservationColumns()` e um leitor de página compartilhado em `ReservationRows.kt`.

**Fora de escopo:**

- extrato impresso (impressão no servidor pendente);
- FNRH do check-out (servidor M5 E08, pendente).

**Critérios de aceite:**

- [ ] Saída do seed com saldo: o assistente leva até o saldo zero e conclui o check-out. A UH fica vaga e suja no mapa em até 2 s.
- [ ] Check-out com caução ainda capturável mostra a pendência e não conclui até a liberação.
- [ ] Se o servidor recusar o check-out, a mensagem vem em pt-BR e nada fica pela metade na tela (recarga do estado).
- [ ] Late check-out cotado aparece como lançamento na conta antes do saldo final, se a política lançar.
- [ ] **Duplicação:** `grep -rn 'fun readRoomTypeNames' app/src/main` devolve uma linha só, e o painel do dia e a busca de reservas montam as colunas pela mesma função. Os testes existentes das duas telas continuam verdes, sem afrouxar asserção.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`, com o mapa aberto em paralelo.

**Revisão humana:** não

**Notas para o plano:**

- A M5 E04 do servidor define o check-out (saldo zero, cauções liberadas, contas encerradas, UH Vago + Sujo, late check-out). Leia o handoff dela e a H14 do roadmap do M5 do servidor.
- **Duplicação:** no `dbeacff`, `readRoomTypeNames` está em `reception/DayPanelState.kt` e em `reservations/ReservationSearchState.kt`. A origem é a decisão 2 do handoff da M3 E01. Essa extração não depende do assistente e pode ir na primeira onda. Se um teste da tabela procura por texto uma linha que não é a primeira, vale a nota de rolar até a linha.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O servidor não devolve as pendências de forma tipada (H1). O assistente confere por leitura antes de chamar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Gate do marco M4 — #pendente

**Objetivo:** o M4 entregue como build testável. O seed passa a ter contas com lançamentos e um turno de caixa, o roteiro do M4 existe e a definição de pronto está verificada num clone limpo e no Windows.

**Por que agora:** fecha o ciclo completo da hospedagem para a demo.

**Dependências:** E04.

**Escopo:**

- seed ampliado (design §7.2, H2);
- `docs/demo/roteiro-m4.md` (a criar), acumulando os anteriores e contando a hospedagem de ponta a ponta (reserva, check-in, consumo, pagamento, check-out);
- pacote atualizado;
- verificação num clone limpo;
- **achados da M3 E06 no `demo-seed`**, que esta etapa amplia:
  - `B-M4`/`C-M2`: o `SdkPendingGateway` propaga a quem chama o `detail` do servidor, e o KDoc do `cancelReservation` está impreciso;
  - `B-M8`: o fora de ordem da UH 208 usa a data operacional do dia, e o `untouched` do `PendingSeeder` tem o nome invertido;
  - `C-M8`: os opt-ins ao vivo do `demo-seed` ficam fora do estado da tarefa de teste do Gradle.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] Clone limpo: build, teste e lint verdes.
- [ ] `smokeTest` verde, e o seed termina em menos de 2 minutos.
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.
- [ ] **`B-M4`/`C-M2`:** um teste do `SdkPendingGateway` com um `detail` marcado do servidor prova que o texto marcado não aparece na mensagem nem na causa da exceção que sai do adaptador. O KDoc do `cancelReservation` descreve o que o adaptador devolve.
- [ ] **`B-M8`:** o `untouched` do `PendingSeeder` tem nome que diz o que guarda, e os testes do seeder seguem verdes. O KDoc do seeder e o `roteiro-m4.md` dizem que o fora de ordem da UH 208 depende da data operacional do dia da recriação. O handoff registra se o planejador mudou o cálculo da data, e por quê.
- [ ] **`C-M8`:** os opt-ins ao vivo do `demo-seed` são entradas da tarefa de teste do Gradle. Mudar um opt-in invalida o resultado em cache da tarefa, e um teste estático ou de script prova isso.

**Validação obrigatória:**

- Comandos oficiais num clone limpo → verdes.
- `smokeTest` → verde.
- Roteiro executado pelo usuário.

**Revisão humana:** sim. Gate do marco e a primeira build com o ciclo completo da hospedagem.

**Notas para o plano:**

- **Origem dos três achados:** a tabela de destinos do plano da M3 E06. O `B-M4` também cita a última fronteira do seed, que já redige (a revisão seguiu o `detail` até a saída). Mexer no adaptador pede nova verificação ao vivo do seed, e esta etapa a ganha com o `C-M8`.
- **Arquivos do testador:** `SdkPendingGatewayCancelAtaqueTest.kt` e as travas ao vivo do seed em `tests/` passam pela conferência do `C-M6`.
- **Recriação no dia:** o motivo do destino do `B-M8` é a regra 5 do README: o roteiro manda recriar a demo no mesmo dia. A descrição do achado está só na linha da tabela; o planejador lê o `PendingSeeder` (`untouched` na linha 161 no `dbeacff`) antes de decidir se o cálculo muda.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além dos anteriores.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## Histórico de revisões

- 2026-11-10 — roadmap criado (`claude --agent arquiteto`).
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`, servidor `57eb5fac5b8a`): a E02 ganha o extrato em PDF, no visualizador da M2 E07 ([ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)). A impressão pela fila do servidor fica com a M7 E12, e o fechamento do dia com a M6 E08. Nenhuma tag mudou.
- 2026-11-17 — revogação do processo acelerado ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), `claude --agent arquiteto`): a seção "Prompt acelerado" foi removida. O protocolo e o prompt de execução não mudaram, porque já remetem ao orquestrador para o ciclo por tarefa. O pré-requisito do marco passa a exigir também a M3 E06 (a revisão adiada, paga antes do M4 por decisão do usuário). A E02 e a E03 ganharam, em "Notas para o plano", as partes que não dependem uma da outra, para o plano formar ondas. Objetivo, escopo e critérios de aceite não mudaram, e nenhuma tag mudou.
- 2026-11-17 — decisões do fim do M3 levadas ao marco, antes da primeira execução (`claude --agent arquiteto`, `development-roadmap` em modo atualização). Nenhuma tag mudou.
  - **D2 (D5 do M3):** o desenho da fonte única de permissões foi fechado com o usuário por entrevista e registrado no [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md). Entra a **E06, "Fonte única de permissões"**, antes da E01 no índice (ID novo, posição pela dependência). A E01 e a E02 passam a depender dela.
  - **D1 (D4 do M3):** a E01 ganha a primeira tarefa, a regra única do resultado desconhecido no `core`, com o `B-M2a`, o `WalkInState`, a criação sem reserva como `IncompatibleResponse` e os Menores M1, M2 e M4 a M7. Ela também ganha os critérios de aceite. A E02 passa a depender da E01, porque as escritas da conta usam a função.
  - **Achados da M3 E06 nas etapas:**
    - E02: o `B-M5` e a isenção da multa ao vivo;
    - E03: o `A-M7`;
    - E04: a duplicação com o `DayPanelState`;
    - E05: o `B-M4`/`C-M2`, o `B-M8` e o `C-M8`.
  - **Notas de processo:** entra a seção "Notas de máquina e de processo", com as notas do handoff da M3 E06:
    - rolar até a linha antes do clique;
    - `detektMain` e `detektTest` por tarefa;
    - a conferência do `C-M6`;
    - o Ubuntu novo.
  - As restrições ganharam o resultado desconhecido e as permissões, e "Hipóteses e decisões em aberto" ganhou D1 e D2.
- 2026-11-18 — a E06 fecha `#concluida` (`claude --agent orquestrador`): a casca é a fonte única das permissões; o `AreaPermissionsState` saiu em `bca85f1`.
