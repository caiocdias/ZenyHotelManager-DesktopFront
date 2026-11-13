# Roadmap — M6 Governança e operação

> Arquivo: `docs/roadmaps/roadmap-m6-governanca-operacao.md` · branch de execução: `roadmap/m6-governanca-operacao` · criado em 2026-11-10 · última revisão 2026-11-13

> **Pré-requisito:** a etapa E05 ("Gate do marco M5") de `docs/roadmaps/roadmap-m5-hospedes-empresas.md` está `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3). O M6 do servidor (`housekeeping`, `maintenance`, fechamento) pode já ter avançado nessa altura, e a rodada do M0 decide se este marco ganha etapas.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m6-governanca-operacao`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O desktop passa a fazer tudo o que o mobile fará na governança, e mais (decisão do usuário: o desktop é superconjunto do mobile). A governanta e o gerente podem:

- mudar o status de governança das UHs no mapa e registrar observações e sinalizações;
- abrir e liberar OOO/OOS com aprovação;
- ver o histórico e as divergências;
- executar checklists com respostas e fotos, e ver os relatórios de conformidade.

Todos os usuários ganham o painel de notificações com contador em tempo real e as preferências. A escala do dia aparece para consulta. O gerente acompanha e dispara o fechamento do dia.

## Contexto confirmado

- **Base:** M1 a M5, inclusive o mapa de UHs ao vivo (M1 E06) e o upload de anexos (M5 E01).
- **Operações do contrato** (`api/openapi.yaml`, commit fixado):
  - **UHs:**
    - `POST /api/v1/rooms/{id}/housekeeping-status`, `/housekeeping-observations` e `/service-flag`;
    - `GET /api/v1/rooms/{id}/status-history`;
    - `GET /api/v1/rooms/discrepancies`.
  - **OOO/OOS:**
    - `GET /api/v1/out-of-order`;
    - `POST /api/v1/rooms/{id}/out-of-order`;
    - `GET`/`PUT /api/v1/rooms/{id}/out-of-order/{periodId}`;
    - `POST /api/v1/rooms/{id}/out-of-order/{periodId}/release`.
  - **checklists:**
    - execuções: `GET`/`POST /api/v1/checklist-executions`, `GET /api/v1/checklist-executions/{id}`;
    - ciclo da execução: `POST /api/v1/checklist-executions/{id}/start` e `/complete`, `PUT /api/v1/checklist-executions/{id}/answers`;
    - relatórios: `GET /api/v1/checklist-reports/compliance`, `/failed-items` e `/location-history`.
  - **notificações:**
    - `GET /api/v1/notifications`, `/unread-count` e `/{id}`;
    - `POST /api/v1/notifications/{id}/read` e `/read-all`;
    - `GET`/`PUT /api/v1/notification-preferences`.
  - **escala:** `GET /api/v1/schedule`, `GET /api/v1/shifts` e `GET /api/v1/employees`.
  - **tempo real:** tópicos SSE `rooms` e `notifications` (ARCHITECTURE §10 do servidor; `notifications` é pessoal).
  - **fechamento do dia** (desde o pin `57eb5fac5b8a`, M6 E10 do servidor):
    - consultas, com `endofday.runs.read`, que o Gerente, o Recepcionista e o Financeiro têm no modelo: `GET /api/v1/end-of-day/runs`, `/{id}` e `/{id}/issues`;
    - ações: `POST /api/v1/end-of-day/runs` e `POST /api/v1/end-of-day/runs/{id}/resume`, com `Idempotency-Key` e `endofday.runs.execute` ou aprovação de superior (ações `endofday.run.start` e `endofday.run.resume`); respondem `201`/`200` com a execução no estado em que estiver (`RUNNING`, `FAILED` ou `COMPLETED`);
    - tópico SSE `end-of-day`, com o evento `endofday.run`;
    - avisos `notifications.end-of-day-pending` no painel.
    - **No pin `57eb5fac5b8a` o motor não tem etapas** (a M6 E11 do servidor está `#pendente`): o disparo manual responde `409 END_OF_DAY_NOT_CONFIGURED`, o automático registra `NO_STEPS` e a data operacional não avança. Ver o bloqueio da E08.
- **Regras do servidor:**
  - OOO com aprovação de superior, refletido na disponibilidade e na marca de realocação;
  - OOO recusado em UH ocupada;
  - fotos de checklist reencodadas sem EXIF/GPS;
  - uma execução concluída uma vez, mesmo com reenvio.

  Detalhes nas notas do brain `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications` e `ZenyHotelManager - Módulos hotel e staff`, e no gate do M2 do servidor.
- **Fora do desktop:** o fluxo offline (`/api/v1/sync/*`) é do app mobile (ARCHITECTURE §9). O desktop exige conexão.
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §1, §2 e §7.

## Escopo

**Incluído:**

- ações de governança nas UHs;
- OOO/OOS;
- histórico e divergências;
- execução de checklists;
- relatórios de checklist;
- notificações e preferências;
- escala do dia (consulta);
- fechamento do dia (E08, `#bloqueada` até o servidor ter as etapas do fechamento);
- seed ampliado;
- roteiro do M6.

**Fora de escopo:**

- modelos e rotinas de checklist, cadastro de pessoal e montagem da escala (M7);
- tarefas de governança e manutenção corretiva, que pertencem ao M6 do servidor, ainda pendente (E01 a E09 dele), e entram por uma rodada do M0;
- snapshot, estatísticas e PDFs do fechamento (M6 E13 do servidor, pendente);
- a fila de envio à FNRH (M7 E10), embora as falhas dela cheguem ao painel desta E05 como `notifications.integration-failure`;
- sincronização offline;
- push (FCM), que é do mobile.

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru;
  - enum extensível tolerado;
  - uma `Idempotency-Key` por intenção;
  - `ETag` e `If-Match` com `412` tratado;
  - permissões efetivas, com o servidor decidindo.
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1.
- **Fotos:** o app envia o arquivo escolhido e não guarda cópia. A remoção de metadados é do servidor.
- **Notificações:** o painel mostra só as do usuário logado, e o contador vem do servidor.
- **Servidor:** é só leitura, e o commit fixado não muda no marco.
- **Idioma:** a UI fica em pt-BR; o código, em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pelo desktop, o seed consegue criar modelos de checklist publicados e uma rotina, usando a API de modelos que o front só ganha no M7 | O roteiro do M6 usa uma execução avulsa de um modelo criado por `curl` | E03 |
| H2 | Alguma operação do contrato já dispara notificações para o painel (por exemplo, a diferença de caixa do M4) | O roteiro mostra o painel vazio e o contador zerado; a demonstração fica para depois | E05 |
| H3 | Na rodada do M0 que vier antes deste marco, o servidor já terá a M6 E11 dele (as etapas do fechamento e o avanço da data) | A E08 continua `#bloqueada`, e essa análise do M0 decide entre esperar ou tirar a E08 das dependências do gate, registrando no histórico | E08 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] `smokeTest` verde contra o ambiente recriado.
- [ ] Roteiro `docs/demo/roteiro-m6.md`, acumulando os anteriores, executado pelo usuário no Windows.
- [ ] Nenhuma etapa depende de pedido ao servidor.

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

- Toda execução acontece no worktree/branch `roadmap/m6-governanca-operacao`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m6-governanca-operacao-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m6-governanca-operacao Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m6-governanca-operacao.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m6-governanca-operacao com superpowers:using-git-worktrees. Não trabalhe na branch principal.
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
| E01 | Governança no mapa de UHs | #pendente | — | Status de governança, observações, sinalização, histórico e divergências |
| E02 | OOO e OOS | #pendente | E01 | Lista, abertura, edição e liberação com aprovação |
| E03 | Execução de checklists | #pendente | — | Execuções pendentes e avulsas, respostas, fotos e conclusão |
| E04 | Relatórios de checklist | #pendente | E03 | Conformidade por modelo, itens mais reprovados e histórico por local |
| E05 | Notificações | #pendente | — | Painel, contador ao vivo, marcar como lida e preferências |
| E06 | Escala do dia | #pendente | — | Consulta da escala, dos turnos e de quem está trabalhando hoje |
| E08 | Fechamento do dia | #bloqueada | — | Execuções do fechamento com etapas e pendências, disparo manual com aprovação, retomada e andamento ao vivo |
| E07 | Gate do marco M6 | #pendente | E02, E04, E05, E06, E08 | Seed ampliado, roteiro do M6 e definição de pronto verificada |

---

## E01 — Governança no mapa de UHs — #pendente

**Objetivo:** no mapa de UHs (M1), a governanta muda o status de governança de uma UH, registra observações e marca ou desmarca a sinalização de serviço. No painel lateral ela vê o histórico de status, e numa aba própria vê as divergências entre governança e recepção. As mudanças aparecem em tempo real nos outros clientes.

**Por que agora:** transforma o mapa só de leitura no centro da operação.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5).

**Escopo:**

- Ações sobre a UH: `POST /api/v1/rooms/{id}/housekeeping-status`, `/housekeeping-observations` e `/service-flag`.
- Histórico: `GET /api/v1/rooms/{id}/status-history`.
- Divergências: `GET /api/v1/rooms/discrepancies`.
- Ações visíveis só com a permissão correspondente.

**Fora de escopo:** tarefas de governança, que são do M6 do servidor e ainda estão pendentes.

**Critérios de aceite:**

- [ ] Como Governanta do seed, mudar o status de uma UH a atualiza no mapa de outra sessão em até 2 s.
- [ ] Como Recepcionista, as ações sem permissão não aparecem e, se forçadas, o `403` é tratado.
- [ ] O histórico mostra a mudança com o autor e a hora no fuso do hotel.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — OOO e OOS — #pendente

**Objetivo:** o gerente e a governanta veem a lista de períodos fora de ordem e fora de serviço. Eles abrem um período numa UH, com datas e motivo, editam e liberam. Quando o servidor exige, a ação passa pela aprovação de superior. O mapa e a disponibilidade refletem a mudança.

**Por que agora:** mostra a ligação entre a operação e as reservas, porque o OOO marca realocação.

**Dependências:** E01.

**Escopo:**

- Lista: `GET /api/v1/out-of-order`.
- Abertura: `POST /api/v1/rooms/{id}/out-of-order`.
- Consulta e edição: `GET`/`PUT /api/v1/rooms/{id}/out-of-order/{periodId}`.
- Liberação: `POST /api/v1/rooms/{id}/out-of-order/{periodId}/release`.

**Fora de escopo:** ordens de serviço de manutenção (servidor M6).

**Critérios de aceite:**

- [ ] Abrir um OOO numa UH livre do seed com a aprovação do Gerente: a UH muda no mapa, e a disponibilidade do período (M3) cai.
- [ ] Abrir um OOO numa UH ocupada mostra a mensagem do `code` em pt-BR.
- [ ] Liberar o período devolve a UH, e a lista atualiza.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Execução de checklists — #pendente

**Objetivo:** o usuário vê as execuções pendentes que lhe cabem, assume uma de rotina ou inicia uma avulsa a partir de um modelo publicado, responde os itens (com foto quando o item pede) e conclui. A execução concluída fica para consulta.

**Por que agora:** é a funcionalidade que o mobile terá. O desktop precisa fazê-la também (decisão do usuário).

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- Execuções:
  - `GET`/`POST /api/v1/checklist-executions` e `GET /api/v1/checklist-executions/{id}`;
  - `POST /api/v1/checklist-executions/{id}/start` e `/complete`;
  - `PUT /api/v1/checklist-executions/{id}/answers`.
- Modelos e versões, só para leitura: `GET /api/v1/checklist-templates` e `/{id}/versions/{number}`.
- Fotos enviadas por `POST /api/v1/files`.

**Fora de escopo:** editor de modelos e rotinas (M7) e fluxo offline.

**Critérios de aceite:**

- [ ] Iniciar uma execução avulsa de um modelo do seed, responder todos os itens com uma foto e concluir: a execução aparece concluída, com a foto.
- [ ] Concluir duas vezes, simulando reenvio, gera uma só conclusão no servidor.
- [ ] Item obrigatório sem resposta mostra o erro no campo, vindo do servidor ou de validação local.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** a semântica de "uma entrada da execução na trilha na conclusão" e a idempotência foram decididas no gate do M2 do servidor (ajuste da E07 dele).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O seed não consegue criar o modelo (H1). Criar por `curl` no roteiro.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Relatórios de checklist — #pendente

**Objetivo:** o gerente vê três relatórios: conformidade por modelo, itens mais reprovados e histórico por local. Cada um tem filtros de período e um gráfico simples onde ajudar.

**Por que agora:** é a visão gerencial que fecha a governança na demo.

**Dependências:** E03.

**Escopo:** `GET /api/v1/checklist-reports/compliance`, `/failed-items` e `/location-history`.

**Fora de escopo:** relatórios gerenciais do servidor (M10 dele).

**Critérios de aceite:**

- [ ] Os três relatórios carregam contra o seed, com os números iguais aos da API.
- [ ] Um período sem dados mostra o estado vazio em pt-BR.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Notificações — #pendente

**Objetivo:** o topo do app tem um sino com o contador de não lidas, atualizado em tempo real pelo tópico pessoal `notifications`. O painel lista as notificações, abre o detalhe, marca uma ou todas como lidas e dá acesso às preferências do usuário.

**Por que agora:** o servidor já gera notificações, como a diferença de caixa (M3 do servidor), e o painel dá vida ao app.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- Lista e contador: `GET /api/v1/notifications`, `/unread-count` e `/{id}`.
- Leitura: `POST /api/v1/notifications/{id}/read` e `/read-all`.
- Preferências: `GET`/`PUT /api/v1/notification-preferences`.
- Tópico SSE `notifications`.

**Fora de escopo:** push (FCM) e dispositivos de push, que são do mobile.

**Critérios de aceite:**

- [ ] Fechar um turno com diferença como Recepcionista (M4) faz o contador do Gerente, em outra sessão, subir em até 2 s, sem recarregar (H2).
- [ ] "Marcar todas como lidas" zera o contador.
- [ ] As preferências gravadas reaparecem ao reabrir.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** não

**Notas para o plano:** o tópico `notifications` é pessoal e não exige permissão (ARCHITECTURE §10 do servidor). Desde o pin `57eb5fac5b8a`, o painel também recebe `notifications.integration-failure` (submissão da FNRH `FAILED` ou `INVALID`, só com o código da reserva) e `notifications.end-of-day-pending`, os dois obrigatórios no painel. O rótulo de cada tipo sai em pt-BR, e um tipo desconhecido não quebra a tela.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Escala do dia — #pendente

**Objetivo:** a tela "Escala" mostra quem trabalha hoje, e em outra data escolhida, por departamento e turno, só para consulta.

**Por que agora:** é barata e completa a visão operacional. A montagem da escala vai para o M7.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:** `GET /api/v1/schedule`, `GET /api/v1/shifts`, `GET /api/v1/employees` e `GET /api/v1/departments`.

**Fora de escopo:** cadastro de funcionários, turnos e padrões, e a geração da escala (M7).

**Critérios de aceite:**

- [ ] Contra o seed (com escala criada por ele ou por `curl`), a escala de hoje mostra os funcionários por turno.
- [ ] Trocar a data recarrega a escala.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E08 — Fechamento do dia — #bloqueada

**Objetivo:** o gerente acompanha e opera o fechamento do dia pelo app:

- vê a lista das execuções por data operacional, com as etapas em ordem (início, fim e resultado de cada uma) e as pendências de cada execução;
- acompanha uma execução em andamento ao vivo;
- dispara o fechamento manual da data corrente, com aprovação de superior quando não tiver a permissão;
- retoma uma execução que falhou.

O topo do app passa a mostrar a data operacional nova quando o fechamento avança.

**Por que agora:** o fechamento é o que faz a data andar e lança as diárias. É a rotina da operação noturna, e a tela fecha o M6.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5 e o desbloqueio abaixo).

**Escopo:**

- **Consultas:** `GET /api/v1/end-of-day/runs` (cursor), `/{id}` e `/{id}/issues` (cursor).
- **Ações:** `POST /api/v1/end-of-day/runs` (data operacional corrente, de `GET /api/v1/hotel/business-date`) e `POST /api/v1/end-of-day/runs/{id}/resume`, com `Idempotency-Key` e o diálogo de aprovação do M1.
- **Tempo real:** tópico `end-of-day`. Ao receber `endofday.run`, a tela relê a execução. No `reset`, recarrega.
- **Recusas em pt-BR:** `END_OF_DAY_ALREADY_CLOSED`, `END_OF_DAY_RUN_EXISTS` (com e sem `runId`; sem ele, reler a execução da data), `END_OF_DAY_DATE_MISMATCH` (com `currentBusinessDate`), `END_OF_DAY_NOT_CONFIGURED`, `END_OF_DAY_RUN_NOT_RESUMABLE` e `END_OF_DAY_RUN_IN_PROGRESS`.
- **Data no topo:** atualizada quando uma execução chega a `COMPLETED`.

**Fora de escopo:**

- snapshot, estatísticas e PDFs do fechamento (M6 E13 do servidor);
- configuração do horário do fechamento (`endOfDayTime`), que é da M7 E06;
- check-out automático, que o servidor nunca faz.

**Critérios de aceite:**

- [ ] Contra o seed, o fechamento manual como Gerente termina em `COMPLETED`, mostra cada etapa com o resultado e a data operacional do topo avança sem recarregar.
- [ ] Como Recepcionista, sem `endofday.runs.execute`, o disparo abre o diálogo de aprovação. Aprovado pelo Gerente, executa uma vez só (mesma `Idempotency-Key`).
- [ ] Um disparo que responde `RUNNING` continua atualizando pela tela, via `end-of-day`, até o estado final, sem reenviar o `POST`.
- [ ] A saída pendente do seed aparece como pendência da execução, com o texto do servidor e sem dado pessoal além do que ele manda.
- [ ] Teste do *state holder*: `409 END_OF_DAY_RUN_EXISTS` sem `runId` relê a execução da data, e um `status` desconhecido aparece como "Outro (VALOR)".

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`, num ambiente recriado só para isso, porque o fechamento avança a data: rode o seed de novo antes do roteiro.

**Revisão humana:** não. O gate (E07) mostra o fechamento ao usuário.

**Notas para o plano:**

- O fechamento muda a data operacional do ambiente. O roteiro do gate precisa deixá-lo por último ou recriar o ambiente depois (regra 5 de `docs/roadmaps/README.md`).
- Decisões do servidor, M6 E10 dele: uma execução por data; `201` síncrono com espera de até 30 s (`zeny.endofday.manual-wait`); `FAILED` só sai por retomada manual; o automático age só até 24 h depois do horário devido.
- A repetição com a mesma `Idempotency-Key` devolve a resposta guardada, que pode ser `RUNNING`. O estado atual vem do `GET` ou do tópico, nunca da repetição.

**Bloqueios:**

- **Causa:** no servidor fixado (`57eb5fac5b8a`), o motor do fechamento (M6 E10 do servidor) existe, mas não tem nenhuma etapa registrada. As etapas de negócio e o avanço da data são da M6 E11 do servidor, `#pendente`.
- **Evidência:** o contrato descreve `409 END_OF_DAY_NOT_CONFIGURED` para "nenhuma etapa registrada". O handoff da M6 E10 do servidor prova esse `409` e o `NO_STEPS` do gatilho pela API. O índice do roadmap `roadmap-m6-operacao-fechamento.md` do servidor mostra a E11 `#pendente` em 2026-11-13.
- **Impacto:** a tela não teria uma execução real para mostrar, e os critérios de aceite não se verificam. O gate E07 depende desta etapa.
- **Ação de desbloqueio:** uma rodada do M0 que fixe um commit do servidor com a M6 E11 dele `#concluida`. Essa análise registra o novo pin em H3 e troca a tag para `#pendente`. Se o M6 do front começar sem isso, a análise do M0 decide, por entrevista, entre esperar e tirar a E08 das dependências do gate.

**Riscos e mitigação:**

- A M6 E11 do servidor muda o contrato do fechamento (pendências novas, códigos novos). A rodada do M0 que desbloquear revê o escopo desta etapa.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Gate do marco M6 — #pendente

**Objetivo:** o M6 entregue como build testável. O seed passa a ter modelos de checklist com execuções e uma escala. O roteiro do M6 fica escrito, e a definição de pronto é verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e vira a build de demo.

**Dependências:** E02, E04, E05, E06, E08.

**Escopo:**

- seed ampliado (design §7.2);
- `docs/demo/roteiro-m6.md` (a criar), acumulando os anteriores, com o fechamento do dia no fim (ele avança a data);
- pacote atualizado;
- verificação num clone limpo.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] Clone limpo: build, teste e lint verdes.
- [ ] `smokeTest` verde, e o seed termina em menos de 2 minutos.
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.

**Validação obrigatória:**

- Comandos oficiais num clone limpo → verdes.
- `smokeTest` → verde.
- Roteiro executado pelo usuário.

**Revisão humana:** sim. É o gate do marco e a nova build de demo.

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O seed passa de 2 minutos com os checklists. Paralelizar as criações ou reduzir o volume.

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
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`, servidor `57eb5fac5b8a`). Entra a E08 (fechamento do dia), que nasce `#bloqueada`: o motor do servidor ainda não tem etapas, porque a M6 E11 dele está pendente. O gate E07 passa a depender dela. Nasce a H3. A E05 ganha a nota dos tipos de notificação novos (`integration-failure` e `end-of-day-pending`). Nenhuma tag existente mudou.
