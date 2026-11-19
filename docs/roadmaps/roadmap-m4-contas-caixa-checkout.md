# Roadmap — M4 Contas, caixa e check-out

> Arquivo: `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` · branch de execução: `roadmap/m4-contas-caixa-checkout` · criado em 2026-11-10 · última revisão 2026-11-18

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
- **Resultado desconhecido (D1):** toda escrita idempotente do marco classifica a falha pela função única do `core` criada na E01. Nenhum estado novo copia a regra.
- **Permissões (D2, [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md)):** as áreas e o topo da casca usam o conjunto de permissões que o `ShellState` leu na sessão vigente, recebido por parâmetro. Nenhuma área lê o `me` por conta própria, e a falha dessa leitura é a falha da casca.
- **Servidor:** só leitura, e o commit fixado não muda no marco.
- **Idioma:** UI em pt-BR, código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O check-out do servidor devolve erro tipado (`code`) quando há saldo, caução ou conta aberta. Com isso o front monta uma lista de pendências antes de confirmar | O assistente de check-out passa a conferir a conta por leitura antes de chamar o check-out | E04 |
| H2 | O seed consegue abrir um turno para a Recepcionista e lançar consumos nas contas dos hospedados pela API | O roteiro abre o turno à mão no início | E05 |
| D1 | **Decidida pelo usuário em 2026-11-17** (D4 do M3, na entrevista do fim do lote da M3 E06, pela opção recomendada). A regra do "resultado desconhecido" (`A-M10`/`B-M7`) está copiada em 8 estados, com 3 variantes. Ela vira uma função única no `core`, na **primeira tarefa da E01**, antes da primeira escrita de dinheiro. Vão junto o `B-M2a`, o `WalkInState`, a criação sem reserva como `IncompatibleResponse` e os Menores M1, M2 e M4 a M7 da revisão final da M3 E06 | Sem a extração, o M4 cria mais cópias em telas de dinheiro | E01 |
| D2 | **Decidida pelo usuário em 2026-11-17** (D5 do M3; desenho fechado por entrevista na sessão do arquiteto, as quatro respostas pela opção recomendada): **uma fonte de permissões por sessão**, registrada no [ADR 0013](../adr/0013-fonte-unica-de-permissoes-por-sessao.md). O estado fica no `ShellState` e vai às áreas por parâmetro. A falha e o "Tentar de novo" são os da casca. O valor vive no `ShellState` da sessão, recriado a cada `sessionId`. A migração das áreas existentes é uma etapa própria (E06), antes da E01 | Sem a fonte, cada área do M4 repetiria a leitura e o estado de falha | E06, E07, E02 |
| D3 | **Decidida pelo usuário em 2026-11-18** (entrevista do fim do lote, pela opção recomendada): ratificada a divisão da antiga E01 em E01 (regra única do resultado desconhecido) e **E07, "Turno de caixa"**, com a E02 e a E03 dependendo da E07 | Sem a ratificação, uma sessão do arquiteto reveria a divisão e as dependências | E01, E07, E02, E03 |
| D4 | **Decidida pelo usuário em 2026-11-18** (mesma entrevista, pela opção recomendada): o [ADR 0012](../adr/0012-revoga-o-processo-acelerado.md) foi emendado (item 7): testador de etapa sobre o diff inteiro, mais despachos escopados do testador dentro da fatia quando ela quebra testes dele, em vez de testador por tarefa | Sem a emenda, cada tarefa de risco alto pediria um testador | Todas |

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
| E01 | Regra única do resultado desconhecido | #concluida | E06 | Regra única do "resultado desconhecido" no `core` (D1), com os achados da M3 E06 que dependem dela |
| E07 | Turno de caixa | #concluida | E06, E01 | Abrir, acompanhar, sangria e suprimento, fechar às cegas e relatório; indicador de turno no topo |
| E02 | Conta da hospedagem | #concluida | E06, E01, E07 | Conta com lançamentos, saldo e taxa de serviço separada, lançar, estornar e extrato na tela e em PDF |
| E03 | Pagamentos | #em-andamento | E07, E02 | Receber e reembolsar no turno, o atalho "abrir turno" e os campos de valor do `design` (`A-M7`) |
| E09 | Cauções | #em-andamento | E07, E02, E03 | Registrar, capturar e liberar caução |
| E04 | Check-out | #em-andamento | E03, E09 | Assistente de check-out até o saldo zero, contas encerradas e UH liberada |
| E08 | Correções da conta e multa de cancelamento | #pendente | E02 | Ajustar, dividir, transferir, remover a taxa a pedido, pagador e limite de crédito; os textos da multa (`B-M5`) e a isenção ao vivo |
| E11 | Duplicação entre o painel do dia e a busca | #pendente | — | As colunas da tabela de reservas, o `readRoomTypeNames` e a trava das leituras auxiliares numa função só (achado da M3 E06) |
| E10 | Cauções a vencer | #pendente | E09 | A lista do hotel das cauções a vencer, numa aba da área "Contas e caixa", com "Abrir conta" |
| E05 | Gate do marco M4 | #pendente | E04, E08, E10, E11 | Seed com contas e turno, roteiro do M4 e definição de pronto verificada |

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

## E01 — Regra única do resultado desconhecido — #concluida

**Objetivo:** a regra do "resultado desconhecido" vira uma função única do `core` (D1), antes da primeira escrita de dinheiro, e leva junto os achados da M3 E06 que dependem dela.

**Por que agora:** a sangria, o suprimento e o fechamento do turno (E07) são as primeiras escritas de dinheiro do app, e o usuário decidiu que a regra única vem antes delas (D1).

**Dependências:** E06 (a remoção do `AreaPermissionsState`, citada no destino dos Menores M1, M2 e M5).

**Escopo:**

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

**Fora de escopo:**

- o turno de caixa (E07);
- o cursor duplicado entre fachadas, citado na mesma linha do `A-M10`/`B-M7`. Não é a regra do resultado desconhecido; a nota da E02 cuida de não criar outra cópia.

**Critérios de aceite:**

- [x] **Função única:** existe no `core` uma função pública que classifica a falha de uma escrita idempotente nos três casos do escopo. Testes do `core` cobrem cada caso de `ApiError`:
  - `Network`, `IncompatibleResponse` e `SessionEnded`;
  - `Problem` com `IDEMPOTENCY_KEY_IN_USE`;
  - `Problem` 5xx e `Unexpected` 5xx;
  - pelo menos um `Problem` 4xx definitivo.
- [x] **Nenhuma cópia no `app`:** `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` não devolve linha. Os 8 estados do escopo usam a função do `core`. Em `CheckInState`, `RoomMoveState` e `TraceFormState`, um teste prova que um `Problem` 5xx agora dá resultado desconhecido.
- [x] **`approvable` único:** `grep -n 'private fun <T : Any> approvable' core/src/main/kotlin/com/zeny/desktop/core/facade/BookingsFacade.kt` não devolve linha, e `createBooking` usa o ajudante comum.
- [x] **`B-M2a`:** com o 5xx guardado, o diálogo mostra o texto de fechar e conferir e não oferece "Tentar de novo". Há teste nos estados que têm esse texto no `dbeacff`: walk-in, nova reserva, cancelar e reativar.
- [x] **`WalkInState`:** um teste prova que resultado desconhecido, seguido de "Tentar de novo" com `APPROVAL_REQUIRED` e da aprovação cancelada, deixa um texto na tela e não trava o formulário.
- [x] **Criação sem reserva:** um teste prova que a resposta de criação sem reserva vira `ApiError.IncompatibleResponse`, com texto próprio em pt-BR, e não deixa o estado em "resultado desconhecido".
- [x] **M1, M2 e M5:** o handoff dá a cada um um destino com commit: corrigido, ou resolvido pela remoção do `AreaPermissionsState` na E06 (commit citado no handoff dela). A leitura do `me` que ficou, no `ShellState`, não engole o cancelamento alheio (usa `ensureActive()`) e não pega `Throwable`.
- [x] **M4, M6 e M7, pelo testador:**
  - os dois testes de `NewReservationDialogEtapaAtaqueTest` apontados no M4 têm nome e asserção fortalecidos;
  - `NewReservationFlowAtaqueTest` tem o caso "tentativa aprovada com resultado desconhecido, depois aprovação cancelada" (M6);
  - um mutante que explora o contorno da trava do `config.` em `tests/test_live_seed_availability_limpeza_ataque.sh` (linhas 95–101 no `dbeacff`) fica vermelho (M7).

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.

**Revisão humana:** não

**Notas para o plano:**

- **Fonte dos itens do D1:**
  - a tabela "Destino dos achados que este plano não corrige" de `docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`;
  - o handoff da M3 E06 em `docs/roadmaps/roadmap-m3-reservas.md` ("Destino dos achados" e "Notas para a próxima etapa (M4 E01)").

  A correção do `A-M1` nos três estados de reservas é a Task 6 daquele plano (`03542f0`), e é o modelo para o `WalkInState`.
- **As 3 variantes da regra**, no `dbeacff`. Todas tratam como desconhecidos `Network`, `IncompatibleResponse` e `SessionEnded`. A diferença está no resto:
  - **completa**, em `WalkInState`, `NewReservationState`, `ReservationConfirmState`, `ReservationCancelState` e `ReservationReactivateState`: também `IDEMPOTENCY_KEY_IN_USE`, `Problem` 5xx e `Unexpected` 5xx;
  - **sem o 5xx**, em `CheckInState` e `RoomMoveState`: também `IDEMPOTENCY_KEY_IN_USE`;
  - **mínima**, em `TraceFormState`: só as três comuns.

  A função única adota a variante completa. Nos três estados das outras variantes, um 5xx (e, nos recados, o `IDEMPOTENCY_KEY_IN_USE`) passa de definitivo a desconhecido. A mudança é intencional, conservadora (não duplica), e tem teste em cada um desses estados. O servidor guarda a resposta sob a chave qualquer que seja o status (KDoc do `IdempotencyFilter` do servidor, linhas 55–60, citado no `B-M2a`), e os 502/504 de proxy não são guardados (comentário em `WalkInState.fail`). A função preserva essa distinção no caso "guardado".
- **Testador:**
  - O M4, o M6 e o M7 são do testador (arquivos dele; conferência do `C-M6`).
  - O M5 (o nome de `leavingWhileReadIsHeldDropsTheLateAnswer`) era de um teste do `AreaPermissionsState`, que a E06 removeu em `bca85f1`.
- **Risco:** concorrência (o resultado desconhecido decide se uma escrita repete com a mesma chave).
- **Módulos tocados:** a extração mexe no `core`, e a verificação rápida cobre o `core` e o `app`. Uma mudança na trava do seed (M7) roda também `bash tests/run.sh tests/test_live_seed_availability_limpeza_ataque.sh`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Uma variante da regra muda de comportamento sem querer. Mitigação: os testes existentes dos 8 estados ficam sem afrouxar asserção (`check_test_integrity.py`), e o testador ataca a função nova.

**Evidências e handoff:**

- Plano: [`docs/superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e01.md`](../superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e01.md). Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado na rodada 0 (7 Importantes) e na rodada 1 (2 Importantes novos), aprovado com notas na rodada 2 (`d202c0d`).
- Commits (`MERGE_BASE` `ff17059`, HEAD `e18142f`):
  - Task 1, a função única no `core`, o `approvable` único e os três estados de outra variante: `37de7c3` (desenvolvedor) e `c00382c` (testador, os três testes de ataque quebrados de propósito). Revisão de fatia: aprovada, 0 Críticos e 0 Importantes.
  - Task 2, os cinco estados da variante completa, o `B-M2a`, o walk-in e a criação sem reserva: `e3dc3be` (desenvolvedor) e `70de043` (testador, seis testes quebrados de propósito). Revisão de fatia: aprovada, 0 Críticos e 0 Importantes.
  - Testador de etapa: `6ad217f`, `2ad6d7a`, `96fda01`, `8ec2ae7` e `bc5a23f` (M4, M6, M7, pendências da E06, ataques às bordas da função e ao `approvable`; achou o D1).
  - Correções do fim da etapa: `533f98d` (D1: o `MoneyField` ganhou `enabled` e a diária negociada trava no caso guardado; os ramos por `code` do check-in e da troca de UH só rodam na falha definitiva; `start()` do cancelamento respeita o `closeOnly`; KDoc do recado) e `e18142f` (o gate ficou vermelho por `DocumentsFacadeAtaqueTest.networkFailureMidBodyIsNetworkWithoutPartialBytes`, que falhava 3 de 4 isolado; causa raiz de produção: no Ktor 3.6.1, ler um canal já fechado com causa devolve 0 bytes sem lançar, e o corpo vazio virava "não é um PDF"; o `executeBytes` passa a relançar o `closedCause`, que vira `Network`, com teste determinístico).
- Revisão final (`revisor-seguranca`, fable, `code-reviewer.md`, `ff17059..70de043`): 0 Críticos, 1 Importante de processo (abaixo, decisão 2) e 8 Menores. A re-revisão escopada de `70de043..e18142f` (testes do testador e as duas rodadas) saiu "Ready to merge: Yes", com 0 Críticos, 0 Importantes e 5 Menores de registro.
- Gate no HEAD `e18142f`: `./gradlew build` → BUILD SUCCESSFUL, com `:app:test` (2615 testes, 0 falhas, 45 pulados, que são os checks ao vivo), `:core:test` (1193, 0 falhas, 1 pulado) e `:design:test` (372, 0 falhas) executados; `bash tests/run.sh` → 60 arquivos, 0 falhas, 1 com casos pulados (o busybox).
- Scanner: `osv-scanner --recursive --no-ignore ./` no `70de043` → 6 lockfiles do Gradle, "No issues found". Depois dele nenhum commit mexe em dependência.
- Integridade: `check_test_integrity.py --base ff17059` no `e18142f` → exit 1, 17 `ASSERCAO_REMOVIDA`, sem avisos. Todas justificadas pelas revisões: trocas autorizadas pelo plano (a mesma `Intent` na segunda chamada vira "nenhuma segunda chamada" com `closeOnly`), mudanças de comportamento por critério (a criação sem reserva e a aprovação cancelada no walk-in) e uma mensagem corrigida.
- Critérios verificados:
  1. **Função única:** `core/.../error/WriteFailure.kt` (`WriteFailure` e `classifyWriteFailure`), com `WriteFailureTest` (um caso por linha da regra) e `WriteFailureAtaqueTest` (bordas 499/500/599, `code` com caixa ou espaços, `when` exaustivo sobre os subtipos de `ApiError`).
  2. **Nenhuma cópia no `app`:** no `e18142f`, `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` não devolvem linha. O 5xx que passa a desconhecido: `problem5xxKeepsTheKeyAndSaysCloseAndCheck` (check-in e troca de UH) e `problem5xxGivesUnknownCreateResultAndReloads` (recado).
  3. **`approvable` único:** o grep do critério não devolve linha, e `grep -rn 'executeApproved(' core/src/main/kotlin/com/zeny/desktop/core/facade` devolve só `Approval.kt:29`. `createBooking`, cancelar, reativar, check-in, troca e walk-in usam o ajudante comum; `BookingsFacadeAtaqueTest` prova o token e a chave pelo cliente HTTP real.
  4. **`B-M2a`:** `problem5xxIsStoredAndCloseOnly`, `unexpected502IsUnknownNotCloseOnly` e `confirmIsNoOpWhenCloseOnly` nos estados do walk-in, da nova reserva, de cancelar e de reativar; `storedFailureOffersOnlyClose` nos quatro diálogos.
  5. **`WalkInState`:** `WalkInStateTest.unknownThenApprovalCancelledLeavesTextAndUnlocks`, com o texto "Aprovação cancelada. O walk-in não foi concluído.".
  6. **Criação sem reserva:** cumprido no comportamento, não no tipo. O estado trata o 201 sem reserva como falha definitiva, com o texto "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas.", `closeOnly` e nenhum `createBooking` a mais (`creationWithoutReservationIsIncompatibleAndCloseOnly`, `creationWithoutReservationNeverCreatesAgain`). Não se constrói um `ApiError.IncompatibleResponse`: a função única classifica esse tipo como desconhecido, e só o estado sabe que o 201 chegou sem reserva.
  7. **M1, M2 e M5:** resolvidos pela remoção do `AreaPermissionsState` na E06, commit `bca85f1`. A leitura que ficou (`ShellState.reload()`) usa `catch (e: Exception)` com `ensureActive()` (`ShellState.kt:96` e `:125`) e não pega `Throwable`.
  8. **M4, M6 e M7, pelo testador:** M4 e M6 em `96fda01` (`NewReservationDialogEtapaAtaqueTest` com os dois testes renomeados e fortalecidos; `NewReservationFlowAtaqueTest` com o caso de ponta a ponta), M7 em `2ad6d7a` (seis mutantes do contorno da trava do `config.` ficam vermelhos com a trava nova, que olha a palavra inteira).
- **Semântica de `classifyWriteFailure`** (registro pedido pela revisão de arquitetura, na falta de `ARCHITECTURE.md`): `Network`, `IncompatibleResponse` e `SessionEnded` → `UNKNOWN`; `Problem` com `IDEMPOTENCY_KEY_IN_USE` → `UNKNOWN` em qualquer status; `Problem` 5xx → `UNKNOWN_STORED` (o `IdempotencyFilter` do servidor guarda a resposta sob a chave qualquer que seja o status); `Unexpected` 5xx → `UNKNOWN` (502/504 de proxy não são guardados); o resto → `DEFINITIVE`. Numa escrita sem chave (o recado), `UNKNOWN` quer dizer "pode ter sido feito, confira". Alternativas descartadas: manter as três variantes por estado (o D1 decidiu pela única); pôr a criação sem reserva no `core` (o `IncompatibleResponse` é desconhecido pela regra); `closeOnly` também no check-in, na troca de UH e no recado (lá a repetição só devolve a resposta guardada, e o texto novo já manda conferir).
- **Correção da spec:** a nota "conservadora (não duplica)" vale para o check-in e a troca de UH, que têm chave. O recado não é escrita idempotente (o contrato do `createTrace` não declara `Idempotency-Key`): nele o desconhecido relê a lista para conferir.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **Divisão da E01** (ratificada pelo usuário em 2026-11-18, D3). O plano não cabia em três fatias: só a regra única somava 15 arquivos de produção, e o turno sozinho chegava a 12, com mais de 60 min. O roadmap mandava parar e dividir numa sessão do arquiteto; pelo pedido do usuário de decidir as intervenções pela recomendada, o orquestrador dividiu pela mitigação que a própria etapa previa. A E01 ficou com a regra única e os achados da M3 E06, e entrou a **E07, "Turno de caixa"**, com os critérios do turno sem mudança. A E03 passou a depender da E07, e a E02 também (a área "Contas e caixa" nasce na E07). Custo se errado: reorganizar o índice numa sessão do arquiteto.
  2. **Testador por tarefa (Importante de processo da revisão final; o usuário emendou o ADR 0012 em 2026-11-18, D4).** A nota "Risco" antiga da E01 pedia testador por tarefa nas escritas e na extração, e a divisão a tirou. O ciclo do orquestrador usa um testador de etapa sobre o diff inteiro, mais despachos escopados do testador dentro da fatia quando a mudança quebra testes dele de propósito. Isso diverge do item 2 do [ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), e o usuário decide se emenda o ADR. Nesta etapa o testador de etapa achou um defeito (D1) que a revisão de fatia não pegou.
  3. **Correção fora do escopo (`e18142f`).** O defeito do `executeBytes` não era da E01, mas deixava o gate vermelho, e a causa raiz foi achada; foi corrigido na rodada 2, com teste determinístico.
  4. **Antecipação parcial do `A-M7`.** O `MoneyField` já tem `enabled` (D1, `533f98d`). Na E03 continuam o `enabled` do `DateField` e o sinal da colagem acima de 1000 caracteres nos dois campos.
  5. **Rótulo "Fechar" com `closeOnly`.** Nos diálogos de nova reserva e de cancelar, com o caso guardado, "Cancelar" e "Voltar" viram "Fechar": eles sugeririam desistir do que pode ter sido feito.
  6. **Casca e o `Error`** (vem da E06): um `Error` da JVM no `me` deixa a casca em "carregando", sem conceder nada.
- Notas para a próxima etapa (E07):
  - Toda escrita do turno classifica a falha com `classifyWriteFailure` e mantém a intenção fora do `DEFINITIVE`. Se o diálogo tiver texto de "feche e confira", ele expõe `closeOnly` como os quatro estados da Task 2 (confirmar vira no-op, entradas travadas, só "Fechar").
  - O `MoneyField` aceita `enabled`: use-o no formulário de sangria, suprimento e contagem.
  - Use o `ApiExecutor.approvable` (`core/.../facade/Approval.kt`) para escrita com aprovação; não monte `ApprovableAction` em linha.
  - Os ramos por `code` de uma falha só rodam com `outcome == WriteFailure.DEFINITIVE` (correção do M1 da revisão final).
  - Para o testador: `WalkInConfirmAtaqueTest` afirma o destravamento só por `canConfirm`, que não depende de `unconfirmed` (a prova real está em `WalkInStateTest`); `ReservationRetryDialogsAtaqueTest.labelStaysRetryAcrossRepeatedUnknownResults` tem a asserção vestigial de `BAD_GATEWAY`; o comentário acima de `afterAStoredFailureTheNegotiatedRateFieldIsDisabledLikeTheOtherInputs` ainda diz que o `MoneyField` não tem `enabled`; sem ataque, a "aprovada perdida" no `moveRoom` e no `walkIn` e o `IDEMPOTENCY_KEY_IN_USE` 500 no `WalkInState`.
  - `AuthPluginAttackTest.refresherUsingTheSameClientNeitherRecursesNorReusesARotatedRefreshToken` falhou uma vez no build cheio e não reproduziu (hipótese: 20 chamadas concorrentes num `runTest` de 5 s sob carga). Se voltar, vai ao testador.
  - No `executeBytes`, uma causa de fechamento que não é `IOException` sai sem tipagem (Menor da re-revisão); improvável.
  - A trava do M7 (`tests/test_live_seed_availability_limpeza_ataque.sh`) depende de `perl` e de `grep -P`; sem eles ela falha fechada.

---

## E07 — Turno de caixa — #concluida

**Objetivo:** a operadora abre o próprio turno num ponto de caixa e vê o turno corrente. Ela registra sangria e suprimento e fecha às cegas, informando o contado. O app mostra o relatório de fechamento com as diferenças, e o topo indica se há turno aberto.

**Por que agora:** o pagamento (E03) e o check-out (E04) exigem turno aberto. A sangria, o suprimento e o fechamento são as primeiras escritas de dinheiro do app e usam a regra única da E01.

**Dependências:** E06 (a área de caixa e o indicador do topo usam a fonte de permissões) e E01 (as escritas do turno usam a função única do resultado desconhecido, D1). O ID é o próximo livre; a posição no índice segue a dependência.

**Escopo:**

- **Pontos de caixa:** `GET /api/v1/cash-points` (só leitura).
- **Turno:**
  - `GET`/`POST /api/v1/cashier-shifts` e `GET /api/v1/cashier-shifts/current`;
  - `POST /api/v1/cashier-shifts/{id}/movements` e `/close`;
  - `GET /api/v1/cashier-shifts/{id}/report`.
- **Topo da casca:** indicador de turno. A área de caixa e o indicador usam o conjunto de permissões do `ShellState` (ADR 0013).

**Fora de escopo:**

- cadastro de pontos de caixa (M7).

**Critérios de aceite:**

- [x] Abrir o turno como Recepcionista do seed: o indicador aparece no topo, e reabrir com um turno já aberto mostra mensagem em pt-BR.
- [x] Sangria e suprimento aparecem no turno, e repetir após erro de rede não duplica (`Idempotency-Key`, se a operação exigir).
- [x] O fechamento cego não mostra o esperado antes de informar o contado. O relatório mostra a diferença em BRL.
- [x] A diferença de caixa gera a notificação ao Gerente no servidor (conferida por `GET /api/v1/notifications` como Gerente). A tela de notificações é do M6.
- [x] As escritas do turno usam a função única do resultado desconhecido (D1): o grep do critério da E01 continua sem linha. A área de caixa e o indicador usam o conjunto de permissões da casca (ADR 0013).

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- "Turno cego" e a notificação de diferença foram validados no gate do M3 do servidor (histórico do `docs/roadmaps/README.md` do servidor, 2026-10-20).
- **Ordem:** as leituras do turno (pontos de caixa, turno corrente e relatório) não dependem das escritas e podem vir antes delas.
- **Risco:** cálculo de valor monetário e concorrência estão na lista fechada.
- **A área nova** segue o padrão da E06: recebe o conjunto e o `sessionId` da casca pelo `AreaContent`, desenha só com o `sessionId` ativo igual ao da casca, e os *holders* nascem com `remember(source, sessionId, permissions)`, cada um com o próprio `DisposableEffect` (handoff da E06). As notas da E06 que dizem "o caixa (E01)" ou "indicador de turno no topo (E01)" são anteriores à divisão e valem para esta etapa.
- **Da E01:** escritas com `classifyWriteFailure`, `closeOnly` onde houver "feche e confira", `ApiExecutor.approvable` e `MoneyField(enabled)` (handoff da E01, "Notas para a próxima etapa").

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Primeira tela de dinheiro. Mitigação: `BigDecimal` e string decimal da API, sem cálculo no front; teste de idempotência em cada escrita.

**Evidências e handoff:**

- **Estado:** `#concluida` em 2026-11-18, depois da verificação ao vivo rodada pelo usuário contra o `zeny-front` (abaixo).
- Plano: [`docs/superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e07.md`](../superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e07.md). Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado nas rodadas 0 (6 Importantes) e 1 (1 Importante), aprovado com notas na rodada 2 (`1262670`).
- Commits (`MERGE_BASE` `16a3cc4`, HEAD de código `6a631ee`):
  - Task 1, a fachada no `core`: `4382d70`. Revisão de fatia aprovada.
  - Task 2, a área, a abertura e o indicador do topo: `c8b3bcb` (desenvolvedor) e `ae3a575` (testador, os testes dele quebrados de propósito pela área nova). Revisão de fatia aprovada.
  - Task 3, movimentos, fechamento cego, relatório e as duas verificações ao vivo: `7edd357`. Revisão de fatia aprovada.
  - Roteiros m1–m3 com a área e o indicador: `2b0b14b` e `f2356c4` (orquestrador).
  - Testador de etapa: `1e7a056` e `1831526` (bordas de valor, repetição, fechamento cego com controle positivo, respostas atrasadas com a porta `LateAnswer`, trava `tests/test_live_cashier_ataque.sh` contra credencial impressa, pendências da E01).
  - Rodada 2, depois da primeira execução ao vivo (falhou com "sem ponto de caixa ativo para abrir o turno": a demo não tinha ponto de caixa, o seed não criava e o servidor não cria um padrão): `9468e64` (o `demo-seed` cria, idempotente, o ponto "Recepção" com fechamento cego), `185bff9` (testador) e `e365c85` (a linha "pontos de caixa" na saída do seed dos roteiros m1–m3). Re-revisão escopada: "Ready to merge: Yes".
  - Correção do fim da etapa: `6a631ee` (aviso à casca no `CASHIER_SHIFT_CLOSED` com releitura falha, texto de valor mal formado, motivo do 403 num booleano, `endsWith` no título da notificação e `ensureActive()` depois do sucesso no `RoomMoveState`).
- Revisão final (`revisor-seguranca`, fable, `16a3cc4..2b0b14b`): 0 Críticos, 1 Importante de processo (o risco "dado pessoal ou segredo" faltava no plano; acrescentado e levado ao testador) e 7 Menores. Re-revisão escopada de `2b0b14b..6a631ee`: "Ready to merge: Yes".
- Gate no `6a631ee`: `./gradlew build` → BUILD SUCCESSFUL (app 2704 testes, 46 pulados, 0 falhas; core 1210, 1, 0; design 372, 0, 0; demo-seed 696, 10, 0); `bash tests/run.sh` → 61 arquivos, 0 falhas, 1 com casos pulados (o busybox).
- Scanner: `osv-scanner --recursive --no-ignore ./` no `2b0b14b` → 6 lockfiles do Gradle, "No issues found"; a etapa não muda dependência.
- Integridade: `check_test_integrity.py --base 16a3cc4` no `6a631ee` → exit 1, 2 `ASSERCAO_REMOVIDA`, as trocas previstas (`AreasTest`, o conjunto implementado com quatro áreas; `RoomMapShellAtaqueTest`, "Contas e caixa" sem "(em breve)"), com asserção de força igual ou maior.
- Critérios verificados (testes, revisões e a execução ao vivo do usuário em 2026-11-18, depois de rodar o seed, que criou o ponto: `pontos de caixa: 1 criados`):
  1. **Abrir como Recepcionista, indicador, reabrir:** `LiveCashierCheck` passou (1 teste, 0 pulados): "turno CX-2026-000001 aberto; o topo mostra o turno" e "reabrir mostrou \"Já existe um turno de caixa aberto.\" e releu o turno aberto". Testes: `openCreatesShiftAndNotifies`, `alreadyOpenShowsMessageAndReloads`, `shiftIndicatorFollowsTheRead`.
  2. **Sangria e suprimento sem duplicar:** ao vivo, "sangria e suprimento repetidos com a mesma chave: um movimento de cada". Testes: `doubleSubmitRecordsOnce`, `retryAfterNetworkKeepsTheKey`, `sameIntentKeepsTheKey`.
  3. **Fechamento cego e diferença em BRL:** ao vivo, "turno CX-2026-000001 fechado com diferença; Dinheiro -R$ 110,00". Testes: `closeFormNeverExposesExpected` com o controle positivo do seletor e `closeShowsReportWithDifferences`.
  4. **Notificação ao Gerente:** `LiveCashierNotificationCheck` passou (1 teste, 0 pulados): "turno CX-2026-000002 fechado com diferença" e "o Gerente recebeu \"Diferença no fechamento do caixa CX-2026-000002\"".
  5. **Função única e conjunto da casca:** os greps da E01 sem linha no HEAD; a área segue a amarração da E06 e lê permissões só do parâmetro.
- **Como repetir a verificação ao vivo** (fecha turnos da Recepcionista na demo; a H2 e a E05 esperam um turno aberto): no worktree, com todas as `ZENY_SEED_*` no ambiente (o `SeedArgs` exige todas), rodar o seed, `ZENY_LIVE_URL=$ZENY_SEED_SERVER ZENY_LIVE_VIEWER_LOGIN=recepcao ZENY_LIVE_VIEWER_PASSWORD="$ZENY_SEED_RECEPTIONIST_PASSWORD" ZENY_LIVE_CASHIER=1 ./gradlew :app:test --tests '*LiveCashierCheck*' --rerun` e `ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*CashierNotification*' --rerun`. Confira no relatório de testes que nenhum dos dois foi pulado.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **Verificação ao vivo com o usuário.** As credenciais estão na folha de senhas; o orquestrador não procurou segredos na máquina, e o usuário rodou os comandos.
  7. **Seed com ponto de caixa (rodada 2).** O `demo-seed` passou a criar o ponto "Recepção" com fechamento cego, o que antecipa uma parte pequena do seed ampliado da E05; sem ele nenhum turno abre na demo. Um "Recepção" desativado vira aviso, sem reativar nem falhar.
  2. **Escopo reduzido:** `GET /api/v1/cashier-shifts` (lista dos turnos) e `GET /api/v1/cashier-shifts/{id}` ficaram fora: nenhum critério os usa, e "Ver relatório" alcança só o turno recém-fechado. Uma lista de turnos anteriores entra quando uma tela gerencial a pedir.
  3. **Fechar com todas as formas vazias** declara zero em tudo num clique (o plano manda "vazia vale zero e não vai"). A escrita é irreversível e a diferença notifica o Gerente: pedir confirmação ou exigir ao menos o dinheiro é decisão de produto.
  4. **Limites do servidor** (teto de R$ 1.000.000,00; dinheiro negativo na contagem) ficam com o servidor, que responde com o texto do catálogo.
  5. **Notificações no `demo-seed`:** o check usa a `NotificationsApi` do `sdk` cru reaproveitando o cliente autenticado; quando a tela de notificações (M6) trouxer uma fachada, o check passa a usá-la.
  6. **Roteiros m1–m3** atualizados pelo orquestrador: a área "Contas e caixa" sem "(em breve)" e o indicador do caixa nos passos da Recepcionista e do Gerente.
- Notas para a próxima etapa:
  - **E05:** o ponto de caixa "Recepção" já vem do seed (`9468e64`); não criar outro. A H2 (seed abre um turno da Recepcionista) continua da E05. Menores da re-revisão da rodada 2: o texto do aviso do ponto desativado manda "desativá-lo", o seed termina com código 0 mesmo sem caixa ativo, e a permissão do administrador para cadastrar ponto foi confirmada só pela execução ao vivo (passou).
  - Para o testador: `ensureActive()` depois do `approve(...)` falta em `WalkInState`, `NewReservationState`, `ReservationCancelState`, `ReservationChangeState` e `ReservationReactivateState` (sem conferir se outra guarda cobre); `CashierStateTest.closeDropsTheLateAnswer` e `ShellStateTest.lateShiftAnswerAfter*` esperam de forma cancelável (a cobertura real está nos `late*` com `LateAnswer`); a asserção `BAD_GATEWAY` de `labelStaysRetryAcrossRepeatedUnknownResults` não pode falhar; a regra de `println` com credencial em `tests/test_live_cashier_ataque.sh` usa `grep -oP` (sem PCRE ela abre); `LateAnswer` mora em `cashier/` e é usada pela recepção e pela casca.
  - A E02 abre a conta também pela área "Contas e caixa", que agora existe; as escritas da conta seguem a E01 e o `closeOnly`.

---

## E02 — Conta da hospedagem — #concluida

**Objetivo:** a partir do detalhe da reserva ou da área Contas e caixa, o usuário abre a conta. Ele vê os lançamentos, o saldo e a taxa de serviço separada. Lança consumos por código de lançamento e estorna, e vê o extrato `GUEST` e `INTERNAL`, na tela e em PDF. Ajustar, dividir, transferir, remover a taxa a pedido, trocar o pagador e o limite de crédito, o `B-M5` e a isenção ao vivo passaram à E08 (divisão de 2026-11-18, no histórico).

**Por que agora:** a conta é o centro financeiro da hospedagem e pré-requisito do check-out.

**Dependências:** E06 (fonte de permissões), E01 (as escritas da conta usam a função única do resultado desconhecido, D1) e E07 (a área "Contas e caixa", onde a conta também abre, nasce no turno).

**Escopo:**

- **Contas:** `GET /api/v1/folios` e `GET /api/v1/folios/{id}`, e `POST /api/v1/folios`, se a conta avulsa fizer sentido na tela.
- **Lançamentos:**
  - `GET`/`POST /api/v1/folios/{id}/postings`;
  - `POST /api/v1/folios/{id}/postings/{postingId}/reverse`.
- **Códigos de lançamento:** `GET /api/v1/transaction-codes`.
- **Extrato:** `GET /api/v1/folios/{id}/statement` na tela e `GET /api/v1/folios/{id}/statement/pdf` no visualizador da M2 E07 (em memória, "Salvar como…" e impressão local, ADR 0009), com a mesma escolha de `audience`.
- **Aprovação de superior:** diálogo onde o servidor exigir.
- **Atalho:** "Conta" no detalhe da reserva.

**Fora de escopo:**

- encerrar e reabrir a conta, que ficam no check-out (E04) ou num atalho gerencial registrado no handoff;
- pagamento (E03);
- ajustar, dividir, transferir, remover a taxa a pedido, `PUT /api/v1/folios/{id}` (pagador e limite de crédito), o `B-M5` e a isenção ao vivo (E08);
- a prévia da multa antes de cancelar, que depende do pedido não bloqueante `PF-04` (D2 do M3).

**Critérios de aceite:**

- [x] Lançar um consumo numa conta do seed atualiza o saldo e a taxa de serviço separada, com os valores iguais aos da API.
- [x] Estornar como Recepcionista sem permissão abre o diálogo de aprovação. Aprovado pelo Gerente, o estorno aparece como lançamento novo, sem apagar o original.
- [x] Duplo clique em "lançar" gera um só lançamento (teste com a mesma `Idempotency-Key`).
- [x] O extrato `GUEST` não mostra os lançamentos internos que o servidor omite.
- [x] O extrato em PDF abre no visualizador com o mesmo saldo do extrato na tela, para `GUEST` e para `INTERNAL`, e nenhum arquivo é criado sem "Salvar como…".
- [x] Um tipo de lançamento desconhecido aparece como "Outro (VALOR)".
- [x] As ações da conta usam o conjunto de permissões da casca (ADR 0013), e as escritas usam a função única do resultado desconhecido (D1): o grep do critério da E01 continua sem linha.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- O `type` do lançamento foi o primeiro `x-extensible-enum` do servidor (ARCHITECTURE §7).
- **Cursor:** as listas de contas e lançamentos são as primeiras fachadas paginadas do M4. O `core` já tem `CursorPager` (`core/.../http/CursorPager.kt`), e o `A-M10`/`B-M7` apontou o cursor duplicado entre fachadas. A fachada nova usa o `CursorPager` e não cria outra cópia.
- Partes independentes, para as ondas do plano: o extrato (`…/statement` na tela e `…/statement/pdf` no visualizador) só lê a conta e não depende das ações sobre os lançamentos.
- **Divisão (2026-11-18):** o escopo original não cabia em três fatias (só a leitura da conta com os atalhos e o extrato chega a ~10 arquivos de produção, e as sete escritas, a multa e as duas verificações ao vivo passavam de 60 min cada). O que saiu está na E08, com as notas do `B-M5` e da isenção.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Muitas ações numa tela só. Agrupar em menu de contexto por lançamento.

**Evidências e handoff:**

- **Estado:** `#concluida` em 2026-11-18, depois da verificação ao vivo rodada pelo usuário contra o `zeny-front` (seed com a taxa de serviço e `LiveFolioCheck`: 1 teste, 0 pulados, 0 falhas). O código, o testador de etapa e a revisão final estão limpos no `12ab199`, e a rodada do seed em `6c4b66d`.
- Plano: [`docs/superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e02.md`](../superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e02.md). Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado na rodada 0 (2 Importantes: o PDF `INTERNAL` por padrão e a fiação da aprovação aberta entre as fatias), aprovado com notas na rodada 1 (`4e80af8`).
- Commits (`MERGE_BASE` `5e0fb8f`, HEAD de código `12ab199`):
  - Task 1, a fachada `FoliosFacade` (`ZenyCore.folios`), o extrato em PDF na `DocumentsFacade` e a porta `FolioSource`: `126e491`. Revisão de fatia aprovada.
  - Task 2, as abas "Caixa" e "Contas", a lista das contas abertas, o painel da conta, o extrato na tela, os dois PDFs e o botão "Conta" na Recepção, nas Reservas e nas pendências: `64838ee` e `3be4e0a`. Revisão de fatia aprovada.
  - Task 3, lançar e estornar (com aprovação) e a `LiveFolioCheck`: `9432163` e `a539be9`. Revisão de fatia aprovada.
  - Testador de etapa: `34877d3`, `f287080`, `a1e7a0f` e `5e85a52` (trava `tests/test_live_folio_ataque.sh` contra credencial e PDF impressos, com 19 mutantes; bordas de quantidade, valor e PDF na fachada; valores do servidor fora do formato; duplo clique, aprovação perdida e cancelada no estorno; troca de sessão com a conta aberta; o PDF interno pelo `folioWiring` real; a `LiveFolioCheck` com o número da conta no PDF e o roteamento tratado). Nenhum defeito de produção.
  - Correções do fim da etapa: `18054af` (a lista "Contas abertas" é relida depois de lançar, estornar ou de `FOLIO_CLOSED`; `key(row.id)` nas linhas; a taxa que aponta para outra taxa não some da tabela), `9b10fc9` (KDoc do teto de PDF) e `12ab199` (o "Tentar de novo" de uma releitura falha troca as linhas em vez de anexar).
  - **Rodada depois da primeira execução ao vivo.** A `LiveFolioCheck` falhou em `LiveFolioCheck.kt:165`: "dois \"Lançar\" seguidos não deram um item e uma taxa", com 1 lançamento onde esperava 2. O duplo clique não duplicou, mas o consumo entrou sem a taxa. A causa está no servidor (só leitura): a taxa de serviço padrão do hotel nasce em `0%` (`HotelSettings.DEFAULTS`), e os códigos do seed não têm percentual próprio. A execução deixou na demo um consumo de R$ 10,00 sem taxa e sem estorno.
    - Correção `6c4b66d`: o `demo-seed` define a taxa de serviço padrão do hotel em 10% (`DemoCatalog.SERVICE_CHARGE_RATE`). Ele lê `GET /api/v1/hotel/tax-settings` com o `ETag` e grava com `If-Match` só quando a taxa é zero, preservando o ISS e a taxa de turismo. A saída ganha a linha `taxa de serviço: 10% (definida)` ou `(já configurada)`.
    - Re-revisão escopada (`revisor-seguranca`): "Ready to merge: Yes".
    - Gate no `6c4b66d`: BUILD SUCCESSFUL (app 2785/47/0, core 1234/1/0, design 372/0/0, demo-seed 722/10/0); `tests/run.sh` com 62 arquivos e 0 falhas.
- Revisão final (`revisor-seguranca`, fable, `code-reviewer.md`, `5e0fb8f..a539be9`): 0 Críticos, 2 Importantes (a lista não relida depois de uma escrita; o `Riscos:` do plano sem "parsing de entrada externa", acrescentado em `c3a9eba` e levado ao testador). Re-revisão escopada de `a539be9..5e85a52`: os dois corrigidos e 1 Importante novo (o "Tentar de novo" duplicava contas); re-revisão de `5e85a52..12ab199`: "Ready to merge: Yes".
- Gate no `12ab199`: `./gradlew build` → BUILD SUCCESSFUL (app 2785 testes, 47 pulados, 0 falhas; core 1234, 1, 0; design 372, 0, 0; demo-seed 710, 10, 0); `bash tests/run.sh` → 62 arquivos, 0 falhas, 1 com casos pulados (o busybox).
- Scanner: `osv-scanner --recursive --no-ignore ./` no `a539be9` → 6 lockfiles do Gradle (1429 pacotes), "No issues found"; a etapa não muda dependência.
- Integridade: `check_test_integrity.py --base 5e0fb8f` no `12ab199` → exit 0, nenhum apontamento; 3 avisos `SEM_SOUL` em commits de documento do orquestrador (`646052b`, `01beb04`, `4e80af8`: o `Soul:` ficou num parágrafo separado do `Co-Authored-By`), julgados justificados pela revisão final.
- Critérios (testes com porta falsa; os três marcados "ao vivo" esperam a `LiveFolioCheck`):
  1. **Lançar atualiza saldo e taxa** — testes: `PostingFormStateTest.successReloadsTheFolio`, `FolioStateTest.serviceChargeSitsUnderItsItem`, `FolioListStateTest.postingInThePanelRefreshesTheListRow`. **Ao vivo:** "conta C-2026-000016: item R$ 10,00 e taxa R$ 1,00; saldo R$ 31,00", com o duplo "Lançar" dando um item e uma taxa.
  2. **Estornar como Recepcionista com aprovação do Gerente** — testes: `ReversalStateTest.approvalRequiredGoesThroughApprove`, `successReloadsAndKeepsTheOriginal`, `cancelledApprovalLeavesTextAndUnlocks`; o diálogo de aprovação da casca abre pela aba "Contas" e pela Recepção (`FolioScreenTest`). **Ao vivo:** "estorno aprovado pelo Gerente: -R$ 10,00, original intacto".
  3. **Duplo clique, um lançamento** — `PostingFormStateTest.doubleConfirmPostsOnce`, `retryAfterNetworkKeepsTheKey`; `FoliosFacadeTest.sameIntentKeepsTheKey`.
  4. **`GUEST` sem o que o servidor omite** — `FolioStateTest.guestStatementShowsOnlyWhatServerSends`, `switchingAudienceDropsTheShownStatement`.
  5. **PDF com o mesmo saldo, sem arquivo** — `FolioWiringTest` (audiência e título por botão), `FolioScreenTest.guestPdfOpensTheViewerWithoutAFile`, `FolioSessionAtaqueTest` (nome `extrato-interno-<número>.pdf` e nenhum `.pdf` gravado pelo wiring real). **Ao vivo:** "extratos GUEST e INTERNAL com o saldo da conta R$ 20,00" e "PDFs GUEST e INTERNAL com os dígitos do saldo R$ 20,00".
  6. **"Outro (VALOR)"** — `FolioStateTest.unknownTypeShowsAsOther` e `unknownStatementKindShowsAsOther`.
  7. **Conjunto da casca e D1** — no `12ab199`, `grep -rnE 'fun resultUnknown|IDEMPOTENCY_KEY_IN_USE' app/src/main` e `grep -rnE 'SERVER_ERROR|status >= 5' app/src/main` não devolvem linha; `fun permissions` em `app/src/main` não devolve linha; a aba, o botão e as ações leem o conjunto pelo `FolioWiring`, lembrado com `remember(source, sessionId, permissions)`.
- **Antes da verificação ao vivo, rode o seed** (a partir de `6c4b66d`), para o hotel ganhar a taxa de serviço de 10%. A saída mostra `taxa de serviço: 10% (definida)`.
- **Como fazer a verificação ao vivo** (escreve na demo e não desfaz: cada execução deixa um consumo de R$ 10,00 com a taxa e o estorno numa conta aberta de reserva do seed; grava `build/live/folio.png` com o extrato `INTERNAL` à vista): no worktree, com `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` (a Recepcionista), `ZENY_LIVE_VIEWER_PASSWORD`, `ZENY_LIVE_APPROVER_LOGIN` (o Gerente) e `ZENY_LIVE_APPROVER_PIN` no ambiente de um subshell, `ZENY_LIVE_FOLIO=1 ./gradlew :app:test --tests '*LiveFolioCheck*' --rerun`. Confira no relatório de testes que ela não foi pulada. `NEED_SEED` na falha quer dizer que falta conta aberta de reserva ou código de consumo com taxa: rode o seed. Passando, a etapa fecha `#concluida`.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **Divisão da E02** (ruling de 2026-11-18). O escopo original não cabia em três fatias de até 60 min e 12 arquivos de produção. A E02 ficou com a leitura, lançar, estornar e o extrato, com os critérios dela sem mudança. Entrou a **E08**, "Correções da conta e multa de cancelamento", com ajustar, dividir, transferir, remover a taxa, o pagador e o limite, e com os critérios do `B-M5` e da isenção ao vivo, que saíram da E02. A E08 vem depois da E04 no índice, e a E05 passou a depender dela. Custo se errado: reorganizar o índice numa sessão do arquiteto.
  2. **PDF do extrato com dois botões** (achado I1 da revisão de arquitetura): "Extrato em PDF (hóspede)" (`GUEST`, o padrão do contrato) e "(interno)". O interno se chama "Extrato interno — <número>" e sugere `extrato-interno-<número>.pdf`, e o nome do servidor é descartado. A tela abre o extrato `INTERNAL` por padrão, porque só quem opera o vê.
  3. **Quantidade inteira de 1 a 9999.** O contrato aceita até 3 casas, mas o `sdk` gera `quantity: Double`.
  4. **"Menu de contexto" é um botão "Ações" por linha**, com "Estornar" num menu suspenso. Não há precedente de clique direito no app.
  5. **A conta avulsa (`POST /api/v1/folios`) ficou fora:** nenhum critério a usa.
  6. **A aba "Contas" depende da porta do caixa:** sem `cashierSource()`, a área mostra "Caixa indisponível". A `CoreConnection` tem as duas portas.
  7. **O seed define a taxa de serviço do hotel em 10%** (rodada da verificação ao vivo). O valor é o do RN-10. A alternativa, aceitar no check um item sem taxa, enfraqueceria o critério 1. O seed só grava quando a taxa está em 0%, então uma taxa zerada de propósito volta a 10% na próxima execução dele. Antecipa uma parte pequena do seed ampliado da E05, como o ponto de caixa na E07.
- Notas para a próxima etapa:
  - **Antes da E03:** o `AuthPluginAttackTest.refresherUsingTheSameClientNeitherRecursesNorReusesARotatedRefreshToken` falhou de novo no gate (2ª vez, depois da E01). Uma das 20 chamadas concorrentes recebeu 401 em 0,014 s, então não é estouro de tempo; provável corrida no refresh do `AuthPlugin`. Na repetição passou, e o arquivo está fora desta etapa. Despache o testador para reproduzir (repetindo o teste) e, provado o defeito, o desenvolvedor.
  - **Estacionado (T3 M1):** `PostingFormState.onFolioClosed` tem o padrão `{}`. Tirá-lo pede que o testador passe o argumento em `FolioAtaqueTest.kt:42`. O `FolioState`, único chamador, passa a callback. O `FolioState.onWritten` também tem padrão `{}`, de propósito: a Recepção e as Reservas não têm lista.
  - **Para o testador:** o mutante "nunca desligar `replacePending`" sobrevive, porque falta um teste de releitura bem-sucedida seguida de `loadMore()` da página 2. A trava `test_live_folio_ataque.sh` não pega `println`/`fail` com argumento não literal. `FolioSessionAtaqueTest` lê campos privados do `PdfActions` por reflexão e varre `.pdf` em `/tmp`, que o build do servidor divide. O título de `guestStatementWithoutInternalBlockShowsNoReason` promete mais do que o teste prova. A troca de sessão com o diálogo "Conta" aberto nas Reservas e nas pendências não tem teste. Os itens herdados da E07 continuam abertos.
  - **Diferidos (Menores):** o diálogo continua confirmável depois de `FOLIO_CLOSED`, embora o servidor recuse de novo. O `403` dos códigos deixa o botão desabilitado em vez de sumir. Um `IllegalArgumentException` local vira "resultado desconhecido". A colagem acima do limite (200/500) é descartada inteira. A releitura da lista lê só a primeira página. O `testTag` da linha leva o UUID.
  - **E03:** o pagamento entra no painel da conta (`FolioState`/`FolioScreen`) e usa o `FolioWiring`, que já chega às três entradas com o `approve`. O reembolso é ação sensível como o estorno. A releitura da lista vem pelo `onWritten`.
  - **E08:** ajustar, dividir e transferir entram no menu "Ações" da linha (`FolioScreen`). O `PUT` do pagador e do limite precisa do `ETag` do `getFolio`, que a fachada ainda não expõe (`folio()` devolve só o `FolioResponse`).

---

## E03 — Pagamentos — #em-andamento

**Objetivo:** na conta, a operadora recebe um pagamento manual, com forma e valor, dentro do turno aberto, e reembolsa com aprovação. Registrar, capturar e liberar caução e as cauções a vencer passaram à E09 (divisão de 2026-11-18, no histórico).

**Por que agora:** zerar o saldo é a condição do check-out.

**Dependências:** E07 (turno aberto), E02.

**Escopo:**

- **Pagamentos:** `GET`/`POST /api/v1/folios/{folioId}/payments` e `POST /api/v1/folios/{folioId}/payments/{paymentId}/refund`.
- **Campos do `design` (`A-M7` da M3 E06):** o `MoneyField` e o `DateField` passam a aceitar `enabled`, e a colagem acima de 1000 caracteres deixa de ser recusada em silêncio. Esta é a primeira tela que recebe valor em dinheiro. O `enabled` do `MoneyField` já entrou na E01 (`533f98d`); aqui ficam o do `DateField` e a colagem nos dois.

**Fora de escopo:**

- Pix, TEF e cartão integrados, que não existem no servidor;
- as cauções (E09).

**Critérios de aceite:**

- [ ] Receber sem turno aberto mostra a mensagem do `code` em pt-BR e o atalho "abrir turno".
- [ ] Receber o saldo de uma conta do seed zera o saldo, e o pagamento aparece no turno.
- [ ] Teste: repetir o recebimento após falha de rede usa a mesma `Idempotency-Key`, e o servidor não duplica.
- [ ] **`A-M7`, `enabled`:** com `enabled = false`, o `MoneyField` e o `DateField` não aceitam digitação nem colagem (teste no `design`). O formulário de pagamento os desabilita enquanto `busy` e no resultado desconhecido (teste do *state holder*).
- [ ] **`A-M7`, colagem:** colar mais de 1000 caracteres no `MoneyField` e no `DateField` deixa um sinal visível em pt-BR, e não só ignora a colagem (teste no `design`). A forma do sinal é decidida no plano.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- Partes independentes, para as ondas do plano: pagamentos (`…/payments`) e cauções (`…/card-pre-authorizations`) usam operações distintas do contrato. O único vínculo é do servidor: a captura da caução gera um pagamento (`capturePaymentIds`), que a lista de pagamentos só lê. A lista de cauções a vencer (`GET /api/v1/card-pre-authorizations/expiring`) é leitura à parte.
- **Divisão (2026-11-18):** o escopo original não cabia em três fatias de até 60 min e 12 arquivos de produção (os pagamentos com o reembolso, o atalho e os campos do `design` já ocupam três; as cauções somam quatro operações e uma tela de cauções a vencer). As cauções e os critérios delas foram para a E09.
- **Conferência (2026-11-18):** no `a1cde4b`, nem o `MoneyField` nem o `DateField` têm limite de tamanho: o texto colado entra inteiro e só fica inválido depois que o campo perde o foco. O critério da colagem continua valendo como está: o plano cria o limite de 1000 caracteres com o sinal visível.
- **`A-M7`:** os campos estão em `design/.../input/MoneyInput.kt` e `DateInput.kt`. A mudança no `design` é usada pelo `app`: a verificação rápida cobre os dois módulos, e as telas do M2 e do M3 que usam os campos continuam verdes. O ajuste dos campos não depende de pagamentos nem de cauções e pode abrir a primeira onda.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além da idempotência, coberta pelo critério.

**Evidências e handoff:**

- **Estado:** `#em-andamento`, **Aguardando verificação ao vivo** (2026-11-18). O código, o testador de etapa, a revisão final e a re-revisão estão limpos no `c4016ce`. Falta a `LivePaymentCheck` contra o `zeny-front`, que pede as credenciais do usuário. Por decisão do usuário neste lote ("pode substituir a revisão humana pela opção recomendada [...] O importante é avançar na entrega do marco"), a espera não trava o lote: para a escolha da próxima etapa, a E03 conta como concluída, como a regra 7 do README faz com o Windows, e as etapas que dependem dela registram que a base ainda não foi verificada ao vivo.
- Plano: [`docs/superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e03.md`](../superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e03.md). Revisão de arquitetura (`revisor-arquitetura`, fable): bloqueado na rodada 0 (3 Importantes: a assinatura de `folioWiring` sem padrão quebraria um arquivo do testador; o "servidor não duplica" sem prova ao vivo; o limite no ramo com máscara sem consumidor), aprovado com notas na rodada 1 (`d013789`).
- Commits (`MERGE_BASE` `a1cde4b`, HEAD de código `c4016ce`):
  - Antes das fatias: `b736771` (divisão E03/E09) e `0a725fe` (testador: a fixture intermitente do `AuthPluginAttackTest`; ver abaixo).
  - Task 1, os campos do `design` (`A-M7`), a `PaymentsFacade` (`ZenyCore.payments`) e a porta `PaymentSource`: `fd2c6b3`. Revisão de fatia aprovada.
  - Task 2, a seção "Pagamentos", "Receber" e o atalho "Abrir turno" nas três entradas: `4caaf7d`. Revisão de fatia aprovada.
  - Task 3, "Reembolsar" com a aprovação da casca e a `LivePaymentCheck`: `a3d86c8`, `7cdc195` e `2077e42`. Revisão de fatia aprovada.
  - Testador de etapa: `7ec20de`, `80107b4`, `6ffd7f2` e `5a29da7` (trava `tests/test_live_payment_ataque.sh` contra credencial e cartão impressos, com os templates da verificação numa lista fechada; cartão com espaços e traços; bordas do valor e do valor entregue; duplo clique e aprovação perdida no reembolso, com a chave girada e o token no fio; campos desabilitados com `locked` nos dois diálogos; o reembolso pelo `ApprovalCoordinator` real; o atalho pelo `AppRoot` inteiro). Nenhum defeito de produção.
  - Correção do fim da etapa: `c4016ce` ("Nenhum pagamento nesta conta." só depois da primeira leitura; o motivo do reembolso medido aparado).
- Revisão final (`revisor-seguranca`, fable, `code-reviewer.md`, `a1cde4b..2077e42`): 0 Críticos, 0 Importantes, 8 Menores, "Ready to merge: Yes". Re-revisão escopada de `2077e42..c4016ce` (os testes do testador e a correção): os dois Menores corrigidos, 3 Menores novos (abaixo), "Ready to merge: Yes".
- Gate no `c4016ce`: `./gradlew build` → BUILD SUCCESSFUL (app 2851 testes, 48 pulados, 0 falhas; core 1252, 1, 0; design 379, 0, 0; demo-seed 722, 10, 0; sdk 59, 0, 0); `bash tests/run.sh` → 63 arquivos, 0 falhas, 1 com casos pulados (o busybox).
- Scanner: `osv-scanner --recursive --no-ignore ./` no `2077e42` → 6 lockfiles do Gradle (1429 pacotes), "No issues found"; a etapa não muda dependência (só o opt-in `ZENY_LIVE_PAYMENT` no `app/build.gradle.kts`).
- Integridade: `check_test_integrity.py --base a1cde4b` no `c4016ce` → exit 0, nenhum apontamento. Na Task 3 (`--base 4caaf7d`), 4 `ASSERCAO_REMOVIDA` da troca de `shiftRequired` por `offersOpenShift`, julgadas trocas 1:1 pela revisão.
- **Antes da E03 (nota da E02), resolvido:** o testador reproduziu a falha do `AuthPluginAttackTest.refresherUsingTheSameClientNeitherRecursesNorReusesARotatedRefreshToken` em 157 de 2000 repetições. A causa é a fixture, não o `AuthPlugin`: o par inicial tinha `sessionId "s1"`, e o refresh falso devolvia outro, o que o `Authenticator` lê como troca de sessão (o servidor conserva a sessão no refresh). Com a fixture coerente, 0 de 2000, também com a CPU ocupada; um teste novo determinístico fica vermelho com a fixture antiga (`0a725fe`).
- Critérios (testes com porta falsa; os marcados "ao vivo" esperam a `LivePaymentCheck`):
  1. **Sem turno: mensagem e atalho** — `PaymentFormStateTest.shiftRequiredShowsMessageAndShortcut`; `FolioPaymentsScreenTest` (a aba "Contas" leva à aba "Caixa", sem diálogo morto; "Conta" na Recepção e nas Reservas leva à área "Contas e caixa"); `PaymentShellAtaqueTest` (pelo `AppRoot` inteiro). **Ao vivo:** o passo 1 da `LivePaymentCheck`, quando a Recepcionista está sem turno.
  2. **Receber o saldo zera o saldo e entra no turno** — `successReloadsAndShowsChange`, `suggestedAmountIsTheServerBalance`. **Ao vivo:** o saldo relido `0.00`, a tela em "R$ 0,00" e o `cashierShiftId` igual ao turno corrente.
  3. **Repetir depois de falha de rede usa a mesma chave, sem duplicar** — `PaymentFormStateTest.retryAfterNetworkKeepsTheKey`, `doubleConfirmReceivesOnce`; `PaymentsFacadeTest.sameIntentKeepsTheKey`. **Ao vivo:** a resposta perdida, o `confirm` de novo com a mesma `Intent` e um pagamento só a mais (padrão `recordTwice`).
  4. **`A-M7`, `enabled`** — `FieldLimitsTest.disabledMoneyFieldRefusesTypingAndPaste` e `disabledDateFieldRefusesTypingAndPaste`; `PaymentFormStateTest.fieldsLockWhileBusyAndUnknown`; `PaymentDialogsAtaqueTest` (campos desabilitados na tela com `locked`).
  5. **`A-M7`, colagem** — `FieldLimitsTest.pasteOverLimitShowsSignalAndKeepsText` e `pasteAtLimitIsAccepted`; `FieldLimitsAtaqueTest`. O sinal é o erro do campo, na hora: "Texto colado com mais de 1000 caracteres. Ele não foi aceito.".
- **Como fazer a verificação ao vivo** (escreve na demo e não desfaz: um pagamento em dinheiro que zera uma conta aberta de reserva do seed, um consumo de R$ 10,00 quando a conta não tem saldo e, se a Recepcionista estava sem turno, um turno aberto no ponto "Recepção"): no worktree, com `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` (a Recepcionista) e `ZENY_LIVE_VIEWER_PASSWORD` no ambiente de um subshell, `(set -a; . <arquivo>; set +a; ZENY_LIVE_PAYMENT=1 ./gradlew :app:test --tests '*LivePaymentCheck*' --rerun)`. Confira no relatório de testes que ela não foi pulada. `NEED_SEED` quer dizer que falta conta aberta de reserva com saldo: rode o seed. Passando, a etapa fecha `#concluida`.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **Divisão da E03** (ruling de 2026-11-18). O escopo original não cabia em três fatias de até 60 min e 12 arquivos de produção. A E03 ficou com receber, reembolsar, o atalho e o `A-M7`, com os critérios dela sem mudança. Entrou a **E09, "Cauções"**, com os dois critérios da caução. A E04 passou a depender também da E09. Custo se errado: reorganizar o índice numa sessão do arquiteto.
  2. **A verificação ao vivo não trava o lote** (instrução do usuário neste lote). Custo se errado: um defeito que só aparece ao vivo pede correção também nas etapas que vieram depois.
  3. **"Reembolsar" é mais restrito que o servidor** (M6 da revisão de arquitetura): aparece com `billing.payments.receive` ou `billing.payments.refund`, como o estorno da E02. O servidor só exige `billing.folios.read` antes da aprovação.
  4. **O motivo do reembolso é texto livre e pode levar um número de cartão** (Menor 2 da revisão final). A recusa de sequência com cara de cartão vale só para o NSU e a autorização, como o plano decidiu. A alternativa é recusar uma sequência de 13 a 19 dígitos também no motivo, em `RefundInput.problems()`.
  5. **O limite de 1000 caracteres** é por edição, nos dois campos, e o sinal fica até a próxima edição aceita. O `A-M7` falava em colagem recusada em silêncio, mas no `a1cde4b` os campos não tinham limite nenhum (conferência registrada nas notas do plano).
  6. **O `PaymentMethod` foi reaproveitado** do `CashierFacade.kt` (as mesmas seis formas, sem `CITY_LEDGER`). Um `CITY_LEDGER` lido do servidor aparece como "Outro (CITY_LEDGER)".
- Notas para a próxima etapa:
  - **Dívidas (antes da E09, que traz o terceiro formulário de escrita):**
    - o `RefundState` repete quase todo o `PaymentFormState` (`send`, `edit`, `fail`), e o `FolioState.kt` passou de 600 linhas;
    - o padrão `approveRefund = { null }` do `FolioWiring` existe só para os arquivos do testador compilarem, como o `T3 M1` da E02;
    - constantes repetidas: o limite do motivo (`REASON_MAX`/`MAX_REASON`, três vezes), o `present()` (três vezes) e o `MAX_LENGTH = 1000` (duas vezes, com "1000" fixo no texto do sinal).
  - **Diferidos (Menores):**
    - o `400 PAYMENT_INVALID` mostra o texto genérico, sem o campo;
    - o motivo acima de 200 caracteres é recusado em silêncio, como no estorno;
    - NSU, autorização, bandeira e últimos dígitos não têm limite de colagem (o valor longo é recusado na confirmação e nunca enviado);
    - um `IllegalArgumentException` local vira "resultado desconhecido" (herdado da E02);
    - dois testes do testador marcados "Hoje:" (`PaymentDialogsAtaqueTest.longPasteInRefundReasonAndCodesIsNeverSent` e `longPasteInPaymentCodesIsRefusedOnConfirmAndNeverSent`) fixam esse comportamento e ficam vermelhos quando o usuário decidir o M8. É decisão, não regressão.
  - **Para o testador:**
    - a trava `test_live_payment_ataque.sh` não pega concatenação (`println("x" + payment)`), `error(payment.toString())`, `throw` nem um `assertEquals` com o objeto inteiro;
    - duas asserções de borda em `PaymentAtaqueTest` aceitam qualquer texto;
    - continuam abertos os itens da E02: o mutante `replacePending`, a varredura de `.pdf` em `/tmp`, o título de `guestStatementWithoutInternalBlockShowsNoReason` e a troca de sessão com o diálogo "Conta" aberto nas Reservas e nas pendências;
    - a colagem sem foco não é testável pela API de teste, e o wiring do `CoreConnection` não tem ponto de injeção do engine HTTP.
  - **E09:** a captura gera um pagamento, que a seção "Pagamentos" já mostra (`PaymentRow`, tipo `PAYMENT` com `cardPreAuthorizationId`), e exige turno: use o `offersOpenShift` e o `closeThenOpenShift` do `FolioState`. A porta `PaymentSource` é a que a E09 amplia.

---

## E09 — Cauções — #em-andamento

**Objetivo:** na conta, a operadora registra uma caução com os dados permitidos (sem número de cartão), captura ou libera a sobra. As cauções a vencer passaram à E10 (divisão de 2026-11-18, no histórico).

**Por que agora:** saiu da E03 na divisão de 2026-11-18 (histórico). O check-out (E04) confere e libera as cauções, por isso vem antes dele.

**Dependências:** E07 (a captura exige turno aberto), E02 (a tela da conta) e E03 (a porta dos pagamentos e a lista de pagamentos, onde a captura aparece). O ID é o próximo livre; a posição no índice segue a dependência.

**Escopo:**

- **Cauções:**
  - `GET`/`POST /api/v1/folios/{folioId}/card-pre-authorizations`;
  - `POST /api/v1/folios/{folioId}/card-pre-authorizations/{preAuthorizationId}/capture` e `/release`.

**Fora de escopo:**

- Pix, TEF e cartão integrados, que não existem no servidor;
- as cauções a vencer (E10).

**Critérios de aceite:**

- [ ] O formulário de caução não tem campo de número de cartão (inspeção e teste do *state holder*).
- [ ] Liberar a sobra da caução muda a situação e o saldo conforme a API.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- A captura gera um pagamento (`capturePaymentIds`) e exige turno aberto (`CASHIER_SHIFT_REQUIRED`): o atalho "abrir turno" da E03 serve aqui também. A liberação não exige turno e vale com a conta encerrada.
- A validade (`expiresAt`) é um instante em UTC, de agora até 90 dias; o plano decide como a tela a recebe (o `DateField` ganha o `enabled` na E03).
- **Divisão (2026-11-18):** a revisão de arquitetura do plano (I5) achou as três fatias acima do teto de 60 min com a lista de cauções a vencer; ela é leitura à parte e o check-out não depende dela, e foi para a E10.
- **Risco:** autenticação ou autorização, cálculo de valor monetário, concorrência, dado pessoal ou segredo (dados do cartão) e parsing de entrada externa estão na lista fechada (alinhado ao plano, N4 da revisão de arquitetura).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além da idempotência e do cartão, cobertos pelas restrições.

**Evidências e handoff:**

- **Estado:** `#em-andamento`, **Aguardando verificação ao vivo** (2026-11-19). O código, o testador de etapa, a revisão final e as re-revisões estão limpos no `e3227ee`. Falta a `LivePreAuthorizationCheck` contra o `zeny-front`, que pede as credenciais do usuário. Pela instrução do usuário neste lote, a espera não trava: a E09 conta como concluída para a escolha da próxima etapa. **A base (E03) também não foi verificada ao vivo** (`LivePaymentCheck`).
- Plano: [`docs/superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e09.md`](../superpowers/plans/2026-11-18-m4-contas-caixa-checkout-e09.md). Revisão de arquitetura (`revisor-arquitetura`, fable): bloqueado na rodada 0, com 5 Importantes:
  - I1: autorização fora do `Riscos:`;
  - I2: o contrato do ajudante comum em aberto;
  - I3: a validade às 00:00 sem alternativas;
  - I4: o relógio e o fuso sem caminho;
  - I5: as três fatias acima do teto.

  Aprovado com notas na rodada 1 (`aa1332c`), com a divisão da E10.
- Commits (`MERGE_BASE` `00aa9de`, HEAD de código `e3227ee`):
  - **Task 1**, as quatro operações de caução na `PaymentsFacade`, a porta `PaymentSource` (com `businessDay()`) e o ajudante comum `FolioWrite`, extraído do recebimento e do reembolso sem mudar o comportamento da E03 (`058f2a0`, `57ccf5c`, `eacd0a7`). Revisão de fatia aprovada.
  - **Task 2**, a seção "Cauções" (`PreAuthorizationsState`, fora do `FolioState`), registrar e liberar, e duas correções no `FolioWrite`: o `toString` do `Done` sem a resposta e o `onDone` fora do `try` (`252abc2`, `a834065`, `1e2b9ba`). Revisão de fatia aprovada.
  - **Task 3**, capturar com o atalho "Abrir turno", a repetição com resultado desconhecido reenviando a entrada guardada, sem revalidar a data, e a `LivePreAuthorizationCheck` (`9d476e4`, `7f45429`, `9c37744`). Revisão de fatia aprovada.
  - **Testador de etapa:** `9555a4c`, `6c53384`, `81bcb7b` e `e3227ee`. Cobriu:
    - a trava `tests/test_live_preauth_ataque.sh`, com 35 mutantes, que pega também concatenação, `error(...)`, `throw` e o objeto inteiro em `assert*`;
    - a chave do registro amarrada à `Intent`;
    - o `ensureActive` depois da escrita, inclusive com `NonCancellable`;
    - a validade com horário de verão;
    - um diálogo por vez entre o painel e as cauções;
    - a troca de sessão com escrita em voo.

    Nenhum defeito de produção.
  - **Correção do fim da etapa** (`e12378a`):
    - "Registrar caução" desabilitado até a primeira leitura, o que evita um falso "fuso desconhecido";
    - o fuso conhecido mantido numa falha transitória;
    - o "Capturável" do diálogo segue a releitura;
    - `PRE_AUTHORIZATION_NOT_FOUND` relê as cauções.
- **Revisões:**
  - Revisão final (`revisor-seguranca`, fable, `00aa9de..9c37744`): 0 Críticos, 0 Importantes, 8 Menores, "Ready to merge: Yes".
  - Re-revisão de `9c37744..e12378a`: os 4 Menores corrigidos e 1 Menor novo, um teste do testador com data fixa que ficaria vazio no dia seguinte, corrigido em `e3227ee`.
  - Re-revisão de `e12378a..e3227ee`: Menor corrigido, "Ready to merge: Yes".
- **Gate e scanner:**
  - Gate no `e3227ee`: `./gradlew build` → BUILD SUCCESSFUL (app 2932 testes, 49 pulados, 0 falhas; core 1265, 1, 0; design 379, 0, 0; demo-seed 722, 10, 0; sdk 59, 0, 0); `bash tests/run.sh` → 64 arquivos, 0 falhas, 1 com casos pulados (o busybox).
  - Scanner: `osv-scanner --recursive --no-ignore ./` no `9c37744` → 6 lockfiles do Gradle, "No issues found". A etapa não muda dependência; só entra o opt-in `ZENY_LIVE_PREAUTH`.
- Integridade: `check_test_integrity.py --base 00aa9de` no `e3227ee` → exit 0, nenhum apontamento.
- Critérios:
  1. **Sem campo de número de cartão** — `PreAuthorizationFormStateTest.formOffersNoCardNumberField` (as propriedades públicas por reflexão), `cardLikeCodesAreRefusedWithText`; `PaymentsFacadeTest.cardLikePreAuthorizationCodesNeverReachTheNetwork`; inspeção: o diálogo tem só valor, validade, autorização, NSU, bandeira e os 4 últimos dígitos.
  2. **Liberar a sobra muda a situação e o saldo conforme a API** — `ReleaseStateTest.releaseSendsOnceAndReloads`, `PreAuthorizationSectionTest` (liberar mostra "Liberada" com o valor da porta), `CaptureStateTest.successReloadsFolioPaymentsAndPreAuthorizations`. **Ao vivo:** os passos 2 e 3 da `LivePreAuthorizationCheck`:
     - capturar R$ 30,00 de R$ 100,00 dá "Capturada em parte", capturável R$ 70,00 e saldo R$ 30,00 menor;
     - liberar dá "Liberada", liberado R$ 70,00 e o saldo sem mudança.
- **Como fazer a verificação ao vivo.** Ela escreve na demo e não desfaz: deixa uma caução de R$ 100,00 com captura de R$ 30,00 e liberação da sobra numa conta aberta de reserva do seed (que pode ficar credora) e, se a Recepcionista estava sem turno, um turno aberto no ponto "Recepção".
  - No worktree, ponha `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` no ambiente de um subshell e rode `(set -a; . <arquivo>; set +a; ZENY_LIVE_PREAUTH=1 ./gradlew :app:test --tests '*LivePreAuthorizationCheck' --rerun)`.
  - Rode antes a `LivePaymentCheck` da E03.
  - `NEED_SEED` quer dizer que falta conta aberta de reserva: rode o seed.
  - Passando as duas, a E03 e a E09 fecham `#concluida`.
- Decisões para o usuário (já tomadas pela opção recomendada, revisáveis):
  1. **Divisão da E09** (I5 da revisão de arquitetura). A lista das cauções a vencer virou a **E10**, porque o check-out não depende dela. A E05 passou a depender da E10. Custo se errado: reorganizar o índice.
  2. **Validade da caução** (I3):
     - a operadora digita a data do comprovante, e o `expiresAt` é o **fim desse dia** (23:59:59) no fuso do hotel;
     - valem as datas de hoje até 89 dias;
     - foram descartados o início do dia, que venceria a caução no primeiro instante do dia do comprovante, e data com hora, que pede um campo que o app não tem.

     Num fuso com horário de verão, a borda de 89 dias pode passar uma hora de 90×24 h; o servidor recusa com o texto genérico.
  3. **Liberar aparece com a conta encerrada e em caução vencida**, como o contrato permite. Registrar e capturar exigem a conta aberta.
  4. **Textos do implementador:**
     - "A sobra será liberada na maquininha.", na liberação com capturável zero;
     - "Informe a validade.", com a data vazia.
  5. **Termo do catálogo:** os textos do `ErrorCatalog` dizem "pré-autorização" ("A pré-autorização expirou.") dentro de diálogos que falam em "caução". Trocar é uma decisão de redação.
- Notas para a próxima etapa:
  - **E04 (check-out):** o assistente libera cauções pela seção "Cauções" (`PreAuthorizationsState.canRelease`/`startRelease`). Cauções `ACTIVE`, `PARTIALLY_CAPTURED` e `EXPIRED` são pendência.
  - **Dívidas:**
    - o `PostingFormState` e o `ReversalState` (E02) ainda têm `send`/`fail` próprios: migrar para o `FolioWrite`;
    - constantes repetidas do motivo e do `present()`;
    - o `FolioState.kt` grande;
    - o padrão `approveRefund = { null }`;
    - o `busy` volta a `false` depois do `close()` do `FolioWrite`, o que é inofensivo.
  - **Diferidos (Menores):**
    - a falha da leitura das cauções esvazia a tabela sem "Tentar de novo";
    - os avisos de formato do registro só aparecem com o valor e a data válidos;
    - a trava aceita qualquer `FolioText.money(...)` e caminho terminado em `.value` em `assert*`;
    - `$name` e `$expected` são templates permitidos pelo nome.
  - **Para o testador:**
    - um fuso cujo horário de verão vira à meia-noite;
    - um falso positivo possível da trava com `" + "` dentro de um literal;
    - os itens da E03 e da E02 que seguem abertos.

---

## E04 — Check-out — #em-andamento

**Objetivo:** a partir de uma saída do painel do dia (M2), um assistente mostra o que falta para sair: saldo, cauções e contas abertas, além da cotação de late check-out se for o caso. Ele leva a operadora a receber, liberar e encerrar, e conclui o check-out. A reserva sai de "hospedados", e a UH fica vaga e suja no mapa em tempo real.

**Por que agora:** fecha o ciclo da hospedagem na demo. Saiu da recepção (M2) porque depende de conta e caixa (design §7.1).

**Dependências:** E03 e E09 (a liberação da caução).

**Escopo:**

- **Assistente:**
  - leitura das contas da reserva;
  - cotação de late check-out (`GET /api/v1/reservations/{id}/early-late-quote`);
  - recebimento (E03);
  - liberação de cauções (E09);
  - `POST /api/v1/reservations/{id}/check-out`, que no servidor encerra as contas, libera as cauções não encerradas e lança o late na mesma transação (conferência de 2026-11-19: o front não chama `POST /api/v1/folios/{id}/close` à parte).
- **Erros:** com pendências, mensagens em pt-BR por `code`.

**Fora de escopo:**

- extrato impresso (impressão no servidor pendente);
- FNRH do check-out (servidor M5 E08, pendente).

**Critérios de aceite:**

- [ ] Saída do seed com saldo: o assistente leva até o saldo zero e conclui o check-out. A UH fica vaga e suja no mapa em até 2 s.
- [ ] Check-out com caução ainda capturável mostra a pendência e não conclui até a liberação.
- [ ] Se o servidor recusar o check-out, a mensagem vem em pt-BR e nada fica pela metade na tela (recarga do estado).
- [ ] Late check-out cotado aparece como lançamento na conta antes do saldo final, se a política lançar.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`, com o mapa aberto em paralelo.

**Revisão humana:** não

**Notas para o plano:**

- A M5 E04 do servidor define o check-out (saldo zero, cauções liberadas, contas encerradas, UH Vago + Sujo, late check-out). Leia o handoff dela e a H14 do roadmap do M5 do servidor.
- **Duplicação:** passou à E11 em 2026-11-19 (I7 da revisão de arquitetura do plano: as fatias passavam do teto).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O servidor não devolve as pendências de forma tipada (H1). O assistente confere por leitura antes de chamar.

**Evidências e handoff:**

- **Estado:** `#em-andamento`, **Aguardando verificação ao vivo** (2026-11-19). O código, o testador de etapa, a revisão final e a re-revisão estão limpos no `0bb5f1e`. Falta a `LiveCheckOutCheck` contra o `zeny-front`, que pede as credenciais do usuário. Pela instrução do usuário neste lote, a espera não trava a escolha da próxima etapa. **A base (E03 e E09) também não foi verificada ao vivo.**
- Plano: [`docs/superpowers/plans/2026-11-19-m4-contas-caixa-checkout-e04.md`](../superpowers/plans/2026-11-19-m4-contas-caixa-checkout-e04.md). Revisão de arquitetura (`revisor-arquitetura`, fable): bloqueado na rodada 0, com 9 Importantes:
  - `extensions` público;
  - o `until` contra o contrato;
  - a falha da leitura das cauções;
  - as regras do recebimento do devido;
  - a conta que parece credora depois de receber;
  - a caixa do late na intenção;
  - o tamanho das fatias;
  - o destino da duplicação;
  - `Riscos:` incompleto.

  Aprovado com notas na rodada 1 (`9b25b52`), com a divisão da E11.
- **Commits** (`MERGE_BASE` `e3227ee`, HEAD de código `0bb5f1e`):
  - **Task 1:** a `CheckOutFacade` (`ZenyCore.checkOut`): cotação do late e check-out com o resultado tipado `BalanceDue`. Também `ApiError.Problem.extensions` (`internal`) e a porta `CheckOutSource` (`8086cb0`). Sem late, o check-out vai sem corpo, porque o `sdk` mandava o JSON `null` (conferido contra o servidor, `@RequestBody(required = false)`). Revisão de fatia aprovada.
  - **Task 2:** o `CheckOutState` e o `CheckOutDialog`: pendências por leitura, bloqueio por caução capturável, devido por conta com "Receber", late na intenção, `FolioWrite` (`04acc16`). Revisão de fatia aprovada.
  - **Task 3:** "Check-out" no detalhe da reserva na Recepção, "Abrir conta" pelo `ReservationFolioDialog`, o aviso no painel e a `LiveCheckOutCheck`. Entraram também os itens da revisão da Task 2 (`f21ba00`, `7d22a2c`, `2ed0126`):
    - `RESERVATION_CHANGED` no `ErrorCatalog`;
    - a caução com capturável ilegível bloqueia;
    - fechar com resultado desconhecido relê o painel.

    Revisão de fatia aprovada.
  - **Testador de etapa:** `0078ab0`, `ba8c5e0` e `882f487`. Nenhum defeito de produção. Cobriu:
    - a trava `tests/test_live_checkout_ataque.sh`, com 30 mutantes;
    - o devido no `core` e a porta sobre um servidor HTTP local;
    - o assistente e o botão na Recepção, inclusive `CHECKED_OUT`, a aba "Saídas", sair da área e a troca de sessão com o check-out em voo.
  - **Correção do fim da etapa:** `144666c` e `0bb5f1e`:
    - o devido velho sai da tela depois de um pagamento com resultado desconhecido ou de um pagamento pelo "Abrir conta" ("Confira a conta e conclua de novo."); era o risco de pagamento em dobro, Importante da revisão final;
    - "Receber" e "Abrir conta" ficam desabilitados com o check-out em voo ou desconhecido;
    - o aviso "Check-out concluído." some ao trocar de reserva;
    - fechar com o check-out em voo relê o painel;
    - a conferência do late na verificação ao vivo compara por valor e exige um lançamento novo.
- **Revisões:**
  - Revisão final (`revisor-seguranca`, fable, `e3227ee..2ed0126`): 0 Críticos, 1 Importante, 7 Menores, "Ready to merge: With fixes".
  - Re-revisão de `2ed0126..0bb5f1e`: no ledger.
- **Gate no `0bb5f1e`:**
  - `./gradlew build` → BUILD SUCCESSFUL. Contagens (testes, pulados, falhas): app 3013, 50, 0; core 1286, 1, 0; design 379, 0, 0; demo-seed 722, 10, 0; sdk 59, 0, 0.
  - `bash tests/run.sh` → 65 arquivos, 0 falhas, 1 com casos pulados (o busybox).
- **Scanner:** `osv-scanner --recursive --no-ignore ./` no `2ed0126` → 6 lockfiles do Gradle, "No issues found". A etapa não muda dependência; só entra o opt-in `ZENY_LIVE_CHECKOUT`.
- **Integridade:** `check_test_integrity.py --base e3227ee` no `0bb5f1e` → exit 0. Na Task 3 (`--base 04acc16`), houve 1 `ASSERCAO_REMOVIDA`: uma asserção tautológica trocada pelo texto fixo do catálogo, julgada justificada.
- **Critérios:**
  1. **Saída do seed até o saldo zero, com a UH Vaga e Suja em até 2 s.** Testes: `CheckOutStateTest.balanceDueShowsEachFolioAndRetriesWithANewKey`, `receiveDueSuggestsTheServerAmount` e `successCallsOnDoneAndCloses`; `ReceptionCheckOutTest`. **Ao vivo:** os passos 3 a 5 da `LiveCheckOutCheck`, com o mapa assinado em paralelo e os 2 s contados da resposta do servidor.
  2. **Caução capturável mostra a pendência e não conclui até a liberação.** Testes: `capturablePreAuthorizationBlocksConclude`, `releasingTheCautionUnblocks`, `malformedCapturableBlocksConclude` e `preAuthorizationReadFailureBlocksConclude`; `ReceptionCheckOutTest.openFolioReleasesTheCautionAndTheReloadedAssistantUnlocks`. **Ao vivo:** o passo 2.
  3. **Recusa em pt-BR, nada pela metade.** Testes: `concurrentChangeShowsTextAndReloads` (texto fixo do catálogo), `alreadyCheckedOutClosesAndReloadsThePanel`, `feeChangedRereadsTheQuoteAndWaits` e `lateNotOfferedUnchecksAndRereads`.
  4. **Late cotado aparece antes do saldo final.** Testes: `lateLineShowsAboveTheTotal` e `lateIsSentOnlyWhenChecked`. **Ao vivo:** o lançamento do late na conta relida depois do sucesso (passo 3). O servidor só lança o late dentro da transação do check-out; ver a decisão 2.
- **Como fazer a verificação ao vivo.** Ela escreve na demo e não desfaz: faz o check-out de uma saída do dia do seed, com a caução registrada e liberada e o devido recebido, e abre o turno se a Recepcionista estava sem turno.
  - No worktree, ponha `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` no ambiente de um subshell e rode `(set -a; . <arquivo>; set +a; ZENY_LIVE_CHECKOUT=1 ./gradlew :app:test --tests '*LiveCheckOutCheck' --rerun)`.
  - Rode antes a `LivePaymentCheck` (E03) e a `LivePreAuthorizationCheck` (E09).
  - `NEED_SEED` quer dizer que falta uma saída do dia com conta: recrie a demo e rode o seed no mesmo dia.
- **Decisões para o usuário** (já tomadas pela opção recomendada, revisáveis):
  1. **O assistente não encerra as contas à parte.** O check-out do servidor faz isso na mesma transação. Encerrar à parte deixaria contas encerradas numa reserva hospedada se o check-out falhasse.
  2. **Critério 4 (late):**
     - a linha "Late check-out: <valor>" aparece acima do total devido do servidor, e o lançamento aparece na conta depois do sucesso;
     - foi descartado lançar o late à mão antes do check-out, que deixaria o lançamento órfão numa falha e fugiria do `EARLY_LATE_FEE_CHANGED`;
     - a linha do late ao lado do devido, que já o inclui, pode ser lida como soma; vale decidir uma redação como "(já incluído)".
  3. **Caução capturável bloqueia "Concluir"** (regra do front). O servidor a liberaria sozinho, mas quem decide entre capturar e liberar é a operadora. `EXPIRED` não é pendência, ao contrário da nota da E09 para esta etapa.
  4. **Perfil sem `billing.folios.read` conclui sem conferir pendências.** O servidor confere os saldos e libera as cauções capturáveis em silêncio, então para esse perfil o critério 2 não vale. Perfil com `frontdesk.stays.checkout` e sem `billing.payments.receive` vê a pendência e não conclui.
  5. **`ApiError.Problem.extensions`** é API do `core`, `internal`. A alternativa, tipar no `ProblemMapper`, levaria domínio de conta ao mapeador.
  6. **Status `IN_HOUSE`.** O plano dizia `CHECKED_IN`, que não existe no contrato.
  7. **`RESERVATION_CHANGED` entrou no catálogo:** "A reserva mudou enquanto você concluía. Confira e tente de novo.".
  8. **A reabertura de conta (`…/reopen`) passou à E08**, e **a duplicação entre o painel do dia e a busca passou à E11**.
- **Notas para a próxima etapa:**
  - **Dívidas:**
    - o `withoutBody` da `CheckOutFacade` depende de o `sdk` aplicar o `block` depois do corpo (o teste `checkOutSendsKeyAndNoBodyWithoutLate` pega uma regressão);
    - o `write.edited()` no `chargeLate` não tem efeito observável hoje;
    - o `FolioWrite` é usado em `reception` e pode ir para um pacote comum quando o `PostingFormState` migrar.
  - **Diferidos (Menores):**
    - a recusa definitiva sem `code` não relê o assistente;
    - a conta credora aparece sem sinal;
    - a rolagem do assistente se perde ao voltar do recebimento;
    - ao reabrir o assistente, o "recebido" se perde e a conta aparece como credora; o aviso "pagamento registrado" no painel é a mitigação;
    - depois de um pagamento incerto ou feito pela conta, fechar o assistente não avisa;
    - "Abrir conta" segue habilitado com `closeOnly`;
    - a cotação que não responde bloqueia "Concluir" até o tempo limite do cliente.
  - **Para o testador:** o duplo clique pela UI real (coberto no estado e pelo `enabled`).

---

## E08 — Correções da conta e multa de cancelamento — #pendente

**Objetivo:** na conta aberta pela E02, o usuário ajusta um lançamento, divide-o entre contas, transfere lançamentos para outra conta e remove a taxa de serviço a pedido do cliente. Ele também troca o pagador e o limite de crédito. A multa de uma reserva cancelada mais de uma vez tem texto que bate com a conta (`B-M5`), e a isenção da multa é verificada ao vivo.

**Por que agora:** saiu da E02 na divisão de 2026-11-18 (histórico). Não é pré-requisito do pagamento nem do check-out, por isso vem depois da E04; o gate (E05) espera por ela.

**Dependências:** E02 (a tela da conta, a fachada e a porta). O ID é o próximo livre; a posição no índice segue a dependência e o valor para a demo.

**Escopo:**

- **Correções:** `/adjust`, `/split` e `/remove-service-charge` em `POST /api/v1/folios/{id}/postings/{postingId}/…` e `POST /api/v1/folios/{id}/postings/transfer`.
- **Conta:** `PUT /api/v1/folios/{id}` (pagador e limite de crédito), com `If-Match`.
- **Reabrir conta encerrada:** `POST /api/v1/folios/{id}/reopen`, com aprovação (veio da E04 em 2026-11-19: ação da conta com aprovação, sem critério na E04).
- **Aprovação de superior:** diálogo onde o servidor exigir (o ajuste é ação sensível com limite).
- **Multa de cancelamento (achados da M3 E06, antes destinados à E02):**
  - `B-M5`: os textos da multa numa reserva cancelada mais de uma vez;
  - a isenção da multa, verificada ao vivo. Na M3 E04 o diálogo oferece "Isentar a multa", mas a isenção nunca foi verificada ao vivo.

**Fora de escopo:**

- encerrar a conta, que o check-out do servidor faz (E04);
- cadastro de empresas (M5); o pagador é escolhido entre as empresas que o servidor lista.

**Critérios de aceite:**

- [ ] Ajustar acima do limite como Recepcionista abre o diálogo de aprovação. Aprovado pelo Gerente, o ajuste aparece como lançamento vinculado, com o original intacto.
- [ ] Dividir e transferir mostram, nas duas contas, os lançamentos e os saldos que a API devolve, e a taxa de serviço acompanha o item.
- [ ] Remover a taxa a pedido gera o estorno da taxa com o motivo do servidor; a ação não aparece em lançamento que não é taxa.
- [ ] Trocar o limite de crédito com uma versão velha (`412`) mostra a mensagem em pt-BR e relê a conta.
- [ ] Cada escrita repetida depois de erro de rede leva a mesma `Idempotency-Key` (teste), e as escritas usam a função única do resultado desconhecido (D1).
- [ ] **`B-M5`:** cancelar, reativar e cancelar de novo uma reserva do seed. O texto da multa no diálogo de cancelamento e a multa que a conta mostra batem com os lançamentos da API. No segundo cancelamento o servidor não cobrou de novo na verificação ao vivo da M3 E04 (critério 2), e o texto não diz que cobrou. Isso vale num teste do estado e na verificação ao vivo.
- [ ] **Isenção ao vivo:** uma verificação ao vivo contra o `zeny-front` cancela uma reserva do seed com "Isentar a multa", com a aprovação do Gerente se o servidor pedir. A conta da reserva fica sem a multa, ou com o lançamento que a API devolver para a isenção, e a tela diz o mesmo que a conta.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.
- Verificação ao vivo da multa e da isenção, com as credenciais só no ambiente do subshell, como as da M3 E04.

**Revisão humana:** não

**Notas para o plano:**

- **Fonte do `B-M5`:** a descrição está só na tabela de destinos do plano da M3 E06 (`docs/superpowers/plans/2026-11-17-m3-reservas-e06.md`). O planejador lê o texto da multa no `ReservationCancelState` e no diálogo e o compara com o que a conta mostra. No segundo cancelamento, a M3 E04 viu `penalty.amount` zero, sem `postingId`, e o resumo atual diz "Sem multa.", embora a multa do primeiro cancelamento continue na conta (`penaltiesCharged`). A verificação ao vivo da multa e da reativação é a `LiveReservationLifecycleCheck` (M3 E04), e a isenção pode entrar nela ou numa verificação ao lado. Ela escreve na demo, como a da M3 E04 (decisão 5 do handoff dela).
- Partes independentes, para as ondas do plano: a troca do pagador e do limite (`PUT`) não depende das correções; a multa não depende de nenhuma das duas.
- **Risco:** cálculo de valor monetário e concorrência estão na lista fechada.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Muitas ações numa tela só. Agrupar em menu de contexto por lançamento (nota da E02).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Cauções a vencer — #pendente

**Objetivo:** na área "Contas e caixa", a operadora acompanha as cauções do hotel que vencem até uma data e abre a conta de cada uma.

**Por que agora:** saiu da E09 na divisão de 2026-11-18 (histórico). O check-out (E04) não depende dela; o gate (E05) espera por ela.

**Dependências:** E09 (as cauções na conta e a porta). O ID é o próximo livre.

**Escopo:**

- `GET /api/v1/card-pre-authorizations/expiring`, paginada pelo `CursorPager`.

**Fora de escopo:** as escritas da caução (E09).

**Critérios de aceite:**

- [ ] A lista mostra as cauções que o servidor devolve até a data escolhida, em ordem de validade, com a situação, os valores e a validade como a API devolve, e "Carregar mais" segue o cursor (teste do *state holder*).
- [ ] "Abrir conta" numa linha leva à conta dela na aba "Contas" (teste de UI).
- [ ] A aba só aparece com `billing.folios.read` no conjunto da casca e não muda a aba padrão ("Caixa").

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- Notas da revisão de arquitetura do plano da E09 (M3): a aba só com `wiring.payments`; `start()`/`close()` e a chave do `remember` como o `FolioAccounts`; o padrão do "Até" quando o `businessDay()` falha; reler ao entrar na aba; a linha não identifica a conta (o contrato só traz `folioId`), e "Abrir conta" (`FolioAccounts.select`) é a identificação.
- Sem `from`, o servidor usa a data operacional.

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

## E11 — Duplicação entre o painel do dia e a busca — #pendente

**Objetivo:** o painel do dia (Recepção) e a busca de reservas montam as colunas da tabela, leem os nomes dos tipos de UH e travam as leituras auxiliares pela mesma função.

**Por que agora:** saiu da E04 em 2026-11-19 (I7 da revisão de arquitetura do plano: as fatias passavam do teto). É o achado estacionado da M3 E01, destinado ao M4 pela M3 E06.

**Dependências:** nenhuma. O ID é o próximo livre; a posição no índice segue o valor para a demo.

**Escopo:** as colunas da tabela de reservas, o `readRoomTypeNames` e a trava das leituras auxiliares (`lookups.withLock`), hoje copiados em `reception/DayPanelState.kt` e `reservations/ReservationSearchState.kt`.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] `grep -rn 'fun readRoomTypeNames' app/src/main` devolve uma linha só, e o painel do dia e a busca de reservas montam as colunas pela mesma função. Os testes existentes das duas telas continuam verdes, sem afrouxar asserção.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.

**Revisão humana:** não

**Notas para o plano:**

- Notas da revisão de arquitetura do plano da E04 (I8): o destino é o `reception/ReservationRows.kt` que já existe (o pacote `reservations` importa `reception`, não o contrário); as assinaturas recebem a leitura como função, porque as duas telas usam portas diferentes (`ReceptionSource` e `ReservationSearchSource`); `ReservationRowsTest` e `ReservationRowsAtaqueTest` passam pela conferência do `C-M6`.
- Se um teste da tabela procura por texto uma linha que não é a primeira, vale a nota de rolar até a linha.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além de afrouxar testes, proibido pelo critério.

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

**Dependências:** E04, E08, E10 e E11 (o gate cobre o marco inteiro).

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
- 2026-11-18 — a E01 foi dividida pelo orquestrador (`claude --agent orquestrador`), pela mitigação que ela mesma previa: o plano não cabia em três fatias (a regra única passa de 12 arquivos de produção, e o turno sozinho também). A E01 fica com a regra única do resultado desconhecido (D1) e os achados da M3 E06; entra a **E07, "Turno de caixa"**, com o resto do escopo e os critérios do turno sem mudança, dependente da E06 e da E01. A E03 passa a depender da E07. O roadmap previa a divisão numa sessão do arquiteto; o usuário autorizou, para este lote, decidir as intervenções pela opção recomendada, e a divisão está nas "Decisões para o usuário" do handoff da E01.
- 2026-11-18 — a E01 fecha `#concluida` (`claude --agent orquestrador`): a regra única do resultado desconhecido está no `core` (`WriteFailure`/`classifyWriteFailure`). A E02 passa a depender também da E07, onde nasce a área "Contas e caixa" (Menor da revisão final), e a E03 registra que o `enabled` do `MoneyField` já entrou.
- 2026-11-18 — a E07 fica `#em-andamento`, **Aguardando verificação ao vivo** (`claude --agent orquestrador`): código, testador de etapa e revisão final limpos; faltam as duas verificações ao vivo, que pedem as credenciais do usuário. A E02 e as seguintes esperam por ela.
- 2026-11-18 — entrevista do fim do lote (`claude --agent orquestrador`): o usuário ratificou a divisão da E01 com a E07 (D3) e emendou o ADR 0012, item 7, para o testador de etapa com despachos escopados (D4). Nenhuma tag mudou.
- 2026-11-18 — a E07 fecha `#concluida` (`claude --agent orquestrador`): o usuário rodou o seed (que agora cria o ponto de caixa "Recepção") e as duas verificações ao vivo, que passaram. A E02 fica elegível.
- 2026-11-18 — a E02 foi dividida pelo orquestrador (`claude --agent orquestrador`), como a E01: o escopo não cabia em três fatias de até 60 min e 12 arquivos de produção. A E02 fica com a leitura da conta (lançamentos, saldo e taxa separada), lançar, estornar e o extrato na tela e em PDF, com os critérios dela sem mudança. Entra a **E08, "Correções da conta e multa de cancelamento"**, com ajustar, dividir, transferir, remover a taxa a pedido, o pagador e o limite de crédito, e os critérios do `B-M5` e da isenção ao vivo, que saíram da E02. A E08 depende da E02 e vem depois da E04 no índice; a E05 passa a depender também dela. A E02 fica `#em-andamento`. A divisão está nas "Decisões para o usuário" do handoff da E02.
- 2026-11-18 — a E02 fica `#em-andamento`, **Aguardando verificação ao vivo** (`claude --agent orquestrador`). O código, o testador de etapa e a revisão final estão limpos no `12ab199`. Faltam os critérios que a `LiveFolioCheck` confere contra o `zeny-front`, que pedem as credenciais do usuário. A E03 e a E08 esperam por ela.
- 2026-11-18 — a E02 fecha `#concluida` (`claude --agent orquestrador`). A primeira execução da `LiveFolioCheck` mostrou que a demo não tinha taxa de serviço (o padrão do hotel nasce em 0%). O seed passou a defini-la em 10% (`6c4b66d`). Depois de rodar o seed, o usuário rodou a verificação, que passou. A E03 e a E08 ficam elegíveis.
- 2026-11-18 — a E03 foi dividida pelo orquestrador (`claude --agent orquestrador`), como a E01 e a E02: o escopo não cabia em três fatias de até 60 min e 12 arquivos de produção. A E03, "Pagamentos", fica com receber e reembolsar, o atalho "abrir turno" e o `A-M7`, com os critérios dela sem mudança. Entra a **E09, "Cauções"**, com registrar, capturar e liberar caução e as cauções a vencer, e os dois critérios da caução, que saíram da E03. A E09 depende da E07, da E02 e da E03 e vem logo depois da E03 no índice; a E04 passa a depender também dela. A E03 fica `#em-andamento`. A divisão está nas "Decisões para o usuário" do handoff da E03.
- 2026-11-18 — a E03 fica `#em-andamento`, **Aguardando verificação ao vivo** (`claude --agent orquestrador`): código, testador de etapa, revisão final e re-revisão limpos no `c4016ce`; falta a `LivePaymentCheck`, que pede as credenciais do usuário. Pela instrução do usuário neste lote, a espera não trava: a E03 conta como concluída para a escolha da próxima etapa, e a E09 fica elegível.
- 2026-11-18 — a E09 foi dividida pelo orquestrador (`claude --agent orquestrador`), pela recomendação da revisão de arquitetura do plano (I5): as três fatias passavam do teto de 60 min com a lista de cauções a vencer. Entra a **E10, "Cauções a vencer"**, dependente da E09, antes do gate; a E05 passa a depender também dela. Os critérios da E09 não mudam. A divisão está nas "Decisões para o usuário" do handoff da E09.
- 2026-11-19 — a E09 fica `#em-andamento`, **Aguardando verificação ao vivo** (`claude --agent orquestrador`): código, testador de etapa, revisão final e re-revisões limpos no `e3227ee`; falta a `LivePreAuthorizationCheck`, que pede as credenciais do usuário. Pela instrução do usuário neste lote, a espera não trava: a E04 fica elegível.
- 2026-11-19 — conferência da E04 pelo orquestrador (`claude --agent orquestrador`): o check-out do servidor encerra as contas, libera as cauções e lança o late na mesma transação, então o assistente não chama o encerramento à parte. A reabertura de conta (`…/reopen`, com aprovação) passou à E08, onde ficam as ações da conta com aprovação. Os critérios não mudaram.
- 2026-11-19 — a duplicação entre o painel do dia e a busca saiu da E04 para a **E11** (`claude --agent orquestrador`), pela recomendação da revisão de arquitetura do plano da E04 (I7: as fatias passavam do teto, e a duplicação não depende do assistente). A E05 passa a depender também da E11. O critério da duplicação foi junto, sem mudança.
