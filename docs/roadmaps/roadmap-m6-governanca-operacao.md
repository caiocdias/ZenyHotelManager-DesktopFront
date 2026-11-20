# Roadmap — M6 Governança e operação

> Arquivo: `docs/roadmaps/roadmap-m6-governanca-operacao.md` · branch de execução: `roadmap/m6-governanca-operacao` · criado em 2026-11-10 · última revisão 2026-11-20

> **Pré-requisito:** a etapa E05 ("Gate do marco M5") de `docs/roadmaps/roadmap-m5-hospedes-empresas.md` está `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3). O M6 do servidor (`housekeeping`, `maintenance`, fechamento) pode já ter avançado nessa altura, e a rodada do M0 decide se este marco ganha etapas.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m6-governanca-operacao`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O desktop passa a fazer tudo o que o mobile fará na governança, e mais (decisão do usuário: o desktop é superconjunto do mobile). A governanta e o gerente podem:

- mudar o status de governança das UHs no mapa e registrar observações e sinalizações;
- abrir e liberar OOO/OOS com aprovação;
- ver o histórico e as divergências;
- executar checklists com respostas e fotos, e ver os relatórios de conformidade;
- ver as tarefas de governança do dia, distribuí-las entre as camareiras, executá-las e inspecioná-las, e registrar e tratar achados e danos. A Recepção consulta as tarefas, solicita a avulsa, gere os achados e cobra os danos. A Camareira fica no mobile ([ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md)).

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
    - **No pin `57eb5fac5b8a` o motor não tem etapas** (a M6 E11 do servidor está `#pendente`): o disparo manual responde `409 END_OF_DAY_NOT_CONFIGURED`, o automático registra `NO_STEPS` e a data operacional não avança.
    - **Desde o pin `5e06f16d51ff` (M0 E03), o motor tem as 8 etapas da M6 E11 do servidor**, nesta ordem:
      - `no-show` (as confirmadas com chegada pendente viram `NO_SHOW`, com a multa da política copiada na reserva, e a UH é solta);
      - `pending-departures` (a saída pendente vira pendência, sem check-out automático);
      - `room-charges` (diárias, pacotes e taxas, uma vez por reserva e noite);
      - `block-release` e `provisional-expiry`;
      - `card-pre-authorizations` (`NOT_APPLICABLE` até o M7 do servidor);
      - `occupied-rooms-dirty`;
      - `advance-business-date`, por último.

      O automático fecha a data às 03:00 do dia seguinte, o `endOfDayTime` padrão (regra 5 do README). Snapshot e PDFs do fechamento (M6 E13 do servidor) continuam pendentes.
- **Tarefas de governança** (desde o pin `8c563cda0575`, M0 E04; M6 E01 a E06 e E19 do servidor, tag `housekeeping`):
  - **tarefas:**
    - `GET /api/v1/housekeeping-tasks` (as da data, por padrão a data operacional, primeiro as UHs com chegada) e `GET /api/v1/housekeeping-tasks/{id}`;
    - `POST /api/v1/housekeeping-tasks`, a tarefa avulsa numa UH ou numa área.
  - **plano do dia:**
    - `GET /api/v1/assignment-plans/{date}`;
    - `POST …/{date}/suggest`, `PATCH /api/v1/assignment-plans/{date}` (o primeiro `PATCH` do contrato) e `POST …/{date}/publish`, todos com `If-Match`.
  - **execução:** `POST /api/v1/housekeeping-tasks/{id}/start`, `/pause`, `/resume`, `/refuse`, `/retry` e `/complete`, com `If-Match`. A conclusão leva as respostas do checklist da tarefa e conclui o checklist na mesma transação.
  - **inspeção:**
    - `POST /api/v1/housekeeping-tasks/{id}/inspection/start` e `/inspection/complete`, com `If-Match`;
    - `POST /api/v1/room-inspections`, a inspeção voluntária, que só aprova.
  - **achados e perdidos:**
    - `GET`/`POST /api/v1/lost-and-found-items` e `GET …/{id}`;
    - `PUT …/{id}/storage-place`;
    - `POST …/{id}/return`, `/discard` e `/donate`. Descartar e doar só valem depois do prazo de guarda.
  - **danos:**
    - `GET`/`POST /api/v1/damage-reports` e `GET …/{id}`;
    - `POST …/{id}/review`, `/charge` e `/waive`. A cobrança lança na conta aberta da reserva vinculada.
  - **permissões e papéis:** os modelos estão no [ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md). Resumo:
    - a Governanta e o Gerente distribuem, executam e inspecionam;
    - a Recepção consulta, solicita a avulsa, gere os achados e cobra os danos;
    - a Camareira (`OWN`) não lê as tarefas online e fica fora das tarefas no desktop (decisão do usuário, ADR 0015).
  - **tempo real:** o tópico SSE `housekeeping` exige `housekeeping.tasks.read`. Ele leva `housekeeping.assignment-plan` (só na publicação) e `housekeeping.task` (a cada transição e inspeção, com o `id` e a versão, sem dado de hóspede).
  - **avisos no painel:** `notifications.task-assigned` (à camareira, na publicação), `notifications.room-released` (UH que ficou pronta, a quem tem `housekeeping.tasks.read`), `notifications.inspection-rejected`, `housekeeping.task-conflict` e `housekeeping.lost-found-expired`.
  - **geração das tarefas:**
    - o check-out e a troca de UH geram a tarefa de saída;
    - a virada da data operacional gera as do dia (permanência, pré-chegada, faxinas programadas);
    - num banco recém-criado, só há tarefas depois de um check-out, de uma troca de UH, de uma avulsa ou de um fechamento.

    A migração do servidor cria os seis tipos de limpeza da SPEC. A configuração deles é da M7 E13.
  - **regras que a tela mostra:**
    - a conclusão leva a UH a Limpa, e a inspeção aprovada a Inspecionada;
    - a inspeção reprovada leva a UH a Suja e a tarefa a `REJECTED`, para refazer;
    - a recusa por "não perturbe" numa UH ocupada liga o sinal na UH, e numa UH vaga é aceita com `200`;
    - a tarefa alheia com `OWN` e a inexistente dão o mesmo `403`.

  Fontes: ARCHITECTURE §6.2, §7, §9 e §10 do servidor; capturas do brain `ZenyHotelManager - atualização m6-operacao-fechamento` E01 a E06, E19 e "fim do lote E20-E06".
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
- fechamento do dia (E08, desbloqueada pela rodada 3 do M0: as etapas do fechamento estão no pin `5e06f16d51ff`);
- tarefas de governança: lista do dia, avulsa e distribuição (E09), execução e inspeção (E10), achados e perdidos e danos (E11), que chegaram na rodada 4 do M0;
- seed ampliado;
- roteiro do M6.

**Fora de escopo:**

- modelos e rotinas de checklist, cadastro de pessoal e montagem da escala (M7);
- configuração da governança (tipos de limpeza com créditos e faxinas programadas), na M7 E13;
- a visão da Camareira sobre as próprias tarefas, que o servidor entrega só pela sincronização offline ([ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md));
- ordens de serviço da manutenção e OOO aberto pela manutenção (M6 E07 e E08 do servidor), relatórios operacionais e hora prevista de chegada (E09 e E22 dele). Estão fora do pin `8c563cda0575` e entram por uma rodada do M0;
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
- **Escritas** (desde o pin `8c563cda0575`):
  - campo com valor padrão no servidor vai sempre explícito ([ADR 0014](../adr/0014-campos-com-padrao-sempre-explicitos.md));
  - "sem valor" é campo ausente ou `null`, nunca `""` (a entrada estrita da M6 E20 do servidor);
  - `409 CONCURRENT_UPDATE` mostra a mensagem do catálogo, sem repetição automática.
- **Fotos:** o app envia o arquivo escolhido e não guarda cópia. A remoção de metadados é do servidor.
- **Notificações:** o painel mostra só as do usuário logado, e o contador vem do servidor.
- **Servidor:** é só leitura, e o commit fixado não muda no marco.
- **Idioma:** a UI fica em pt-BR; o código, em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Pelo desktop, o seed consegue criar modelos de checklist publicados e uma rotina, usando a API de modelos que o front só ganha no M7 | O roteiro do M6 usa uma execução avulsa de um modelo criado por `curl` | E03 |
| H2 | Alguma operação do contrato já dispara notificações para o painel (por exemplo, a diferença de caixa do M4) | O roteiro mostra o painel vazio e o contador zerado; a demonstração fica para depois | E05 |
| H3 | Na rodada do M0 que vier antes deste marco, o servidor já terá a M6 E11 dele (as etapas do fechamento e o avanço da data) | A E08 continua `#bloqueada`, e essa análise do M0 decide entre esperar ou tirar a E08 das dependências do gate, registrando no histórico | E08 — **confirmada (2026-11-15)** pela análise da rodada 3 do M0: a `main` do servidor em `5e06f16d51ff` tem a M6 E11 dele `#concluida`, e a M0 E03 fixa esse commit. A E08 passou a `#pendente` |
| H4 | O seed consegue pôr camareiras escaladas na data operacional (funcionários, turnos e escala pelas operações de `staff`), para a distribuição ter a quem atribuir. A distribuição recusa colaborador não escalado na data (`422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`) | A sugestão deixa as tarefas sem camareira, e o roteiro mostra a publicação sem atribuição | E09, E07 |

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
| E08 | Fechamento do dia | #pendente | — | Execuções do fechamento com etapas e pendências, disparo manual com aprovação, retomada e andamento ao vivo |
| E09 | Tarefas do dia e distribuição | #pendente | — | Lista das tarefas por data, tarefa avulsa, plano do dia sugerido, ajustado e publicado, tópico `housekeeping` |
| E10 | Execução e inspeção das tarefas | #pendente | E09, E03 | Iniciar, pausar, retomar, recusar, tentar de novo e concluir com checklist; inspeção da tarefa e voluntária |
| E11 | Achados e perdidos e danos | #pendente | E09 | Registro, guarda, devolução, descarte e doação dos achados; revisão, cobrança na conta e dispensa dos danos |
| E07 | Gate do marco M6 | #pendente | E02, E04, E05, E06, E08, E10, E11 | Seed ampliado, roteiro do M6 e definição de pronto verificada |

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

**Fora de escopo:** tarefas de governança (E09 a E11).

**Critérios de aceite:**

- [ ] Como Governanta do seed, mudar o status de uma UH a atualiza no mapa de outra sessão em até 2 s.
- [ ] Como Recepcionista, as ações sem permissão não aparecem e, se forçadas, o `403` é tratado.
- [ ] O histórico mostra a mudança com o autor e a hora no fuso do hotel.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** não

**Notas para o plano:**

- Partes independentes, para as ondas do plano: a aba de divergências só lê `GET /api/v1/rooms/discrepancies` e não depende das ações sobre a UH nem do histórico. O histórico (`…/status-history`) é leitura à parte das três ações.
- Desde o pin `8c563cda0575` (M0 E04), o status de governança também muda pelas tarefas. A conclusão leva a UH a Limpa, a inspeção aprovada a Inspecionada, e a reprovada de volta a Suja (E10). O mapa recebe essas mudanças pelo tópico `rooms`, como as manuais, e o histórico mostra o autor que o servidor manda.

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

**Notas para o plano:**

- A semântica de "uma entrada da execução na trilha na conclusão" e a idempotência foram decididas no gate do M2 do servidor (ajuste da E07 dele).
- Desde o pin `8c563cda0575` (M0 E04), a tarefa de governança e a inspeção abrem execuções de checklist próprias (`checklistExecutionId` na tarefa e na inspeção). Elas são concluídas pela conclusão da tarefa ou da inspeção, na mesma transação (ARCHITECTURE §6.2 do servidor). O plano confere no servidor como essas execuções aparecem na lista de pendentes e o que um `…/complete` direto faz com elas. A tela não pode oferecer a conclusão por aqui, se ela deixar a tarefa para trás.
- O formulário de respostas, com foto, é reaproveitado pela execução e pela inspeção da tarefa (E10). Faça-o como componente próprio, que recebe a versão do modelo e devolve as respostas.

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

**Notas para o plano:** partes independentes, para as ondas do plano: os três relatórios usam operações distintas e não dependem uns dos outros. O que eles têm em comum é o filtro de período.

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

Desde o pin `8c563cda0575` (M0 E04), a governança emite cinco tipos:
- `notifications.task-assigned`, à camareira, na publicação do plano;
- `notifications.room-released`, a quem tem `housekeeping.tasks.read`, quando uma UH fica pronta;
- `notifications.inspection-rejected`;
- `housekeeping.task-conflict`, a quem distribui as tarefas;
- `housekeeping.lost-found-expired`, a quem gere os achados.

Nenhum deles é obrigatório no painel, e nenhum traz dado de hóspede. A `room-released` é a que a Recepção mais verá: um H2 positivo sem depender do caixa.

Partes independentes, para as ondas do plano: as preferências (`/api/v1/notification-preferences`) não dependem do painel nem do contador.

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

## E08 — Fechamento do dia — #pendente

**Objetivo:** o gerente acompanha e opera o fechamento do dia pelo app:

- vê a lista das execuções por data operacional, com as etapas em ordem (início, fim e resultado de cada uma) e as pendências de cada execução;
- acompanha uma execução em andamento ao vivo;
- dispara o fechamento manual da data corrente, com aprovação de superior quando não tiver a permissão;
- retoma uma execução que falhou.

O topo do app passa a mostrar a data operacional nova quando o fechamento avança.

**Por que agora:** o fechamento é o que faz a data andar e lança as diárias. É a rotina da operação noturna, e a tela fecha o M6.

**Dependências:** nenhuma dentro deste roadmap. Exige o gate do M5 e o servidor fixado em `5e06f16d51ff` ou depois (M0 E03).

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
- [ ] Depois do fechamento, uma chegada confirmada do seed que não fez check-in aparece no detalhe da reserva como "Não compareceu", com a linha de no-show no histórico, ou, se o servidor recusou a multa, como pendência da execução.
- [ ] Teste do *state holder*: `409 END_OF_DAY_RUN_EXISTS` sem `runId` relê a execução da data, e um `status` desconhecido aparece como "Outro (VALOR)".

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`, num ambiente recriado só para isso, porque o fechamento avança a data: rode o seed de novo antes do roteiro.

**Revisão humana:** não. O gate (E07) mostra o fechamento ao usuário.

**Notas para o plano:**

- O fechamento muda a data operacional do ambiente. O roteiro do gate precisa deixá-lo por último ou recriar o ambiente depois (regra 5 de `docs/roadmaps/README.md`).
- Decisões do servidor, M6 E10 dele: uma execução por data; `201` síncrono com espera de até 30 s (`zeny.endofday.manual-wait`); `FAILED` só sai por retomada manual; o automático age só até 24 h depois do horário devido.
- A repetição com a mesma `Idempotency-Key` devolve a resposta guardada, que pode ser `RUNNING`. O estado atual vem do `GET` ou do tópico, nunca da repetição.
- A rodada 3 do M0 revisou o escopo diante da M6 E11 do servidor. A E11 não mudou o contrato do fechamento: nenhuma operação nova e nenhum código de erro novo, e `kind` e `stepName` das pendências são texto livre (`EndOfDayIssueResponse`), mostrados como o servidor manda. As etapas e a ordem estão em "Contexto confirmado".
- Decisões do servidor, M6 E11 dele, que a tela precisa mostrar sem esconder:
  - se o servidor recusa a multa de no-show (conta encerrada ou código `MULTA-NOSHOW` desativado), o no-show inteiro não acontece, e a reserva vira pendência a cada fechamento;
  - uma etapa que falha não avisa as pendências que gravou: elas aparecem só no `GET …/issues`;
  - o avanço que esgota o prazo fica `FAILED` até a retomada manual.
- O seed do M2 põe todas as chegadas e os day use na data operacional. Logo, um fechamento logo depois do seed gera no-shows e pendências de saída: é o material dos critérios, não defeito.

**Bloqueios:** Nenhum bloqueio conhecido. O bloqueio registrado na rodada 2 do M0 (motor sem etapas no pin `57eb5fac5b8a`) caiu na rodada 3: a M6 E11 do servidor está em `5e06f16d51ff`, fixado pela M0 E03 (H3 e histórico).

**Riscos e mitigação:**

- Se o M6 do front começar antes da M0 E03 ser integrada, o servidor fixado ainda não tem as etapas. A regra 3 do README impede isso: a E03 vem antes do M3.
- A M6 E13 do servidor (snapshot e PDFs) pode entrar com etapas novas antes do avanço (`daily-statistics` e `end-of-day-reports`, segundo o handoff da E11 dele). A tela mostra as etapas como o servidor as lista, sem uma lista fixa no app.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E09 — Tarefas do dia e distribuição — #pendente

**Objetivo:** a área Governança mostra as tarefas de uma data (por padrão, a data operacional), na ordem do servidor: primeiro as UHs com chegada, depois pela prioridade. Cada tarefa mostra a situação, o tipo de limpeza, a UH ou a área, a camareira e os créditos. A tela abre o detalhe e solicita uma tarefa avulsa numa UH ou numa área. A Governanta e o Gerente montam o plano do dia: pedem a sugestão do servidor, ajustam (movem, atribuem ou retiram tarefas) e publicam. A lista e o plano se atualizam ao vivo pelo tópico `housekeeping`.

**Por que agora:** é a rotina da governanta todas as manhãs, e as etapas E10 e E11 partem desta área.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M5 e o servidor fixado em `8c563cda0575` ou depois, M0 E04).

**Escopo:**

- **Tarefas:** `GET /api/v1/housekeeping-tasks` (com a data) e `GET /api/v1/housekeeping-tasks/{id}`; `POST /api/v1/housekeeping-tasks` (avulsa).
- **Plano do dia:**
  - `GET /api/v1/assignment-plans/{date}`, com o `404 ASSIGNMENT_PLAN_NOT_FOUND` tratado como "sem plano";
  - `POST …/{date}/suggest`, `PATCH /api/v1/assignment-plans/{date}` e `POST …/{date}/publish`, todos com `If-Match`.
- **Tempo real:** o tópico `housekeeping`, assinado só com `housekeeping.tasks.read`. `housekeeping.task` relê a tarefa, `housekeeping.assignment-plan` relê o plano, e o `reset` recarrega.
- **Menu:** a área aparece com `housekeeping.tasks.read`. As ações seguem as permissões efetivas: avulsa com `housekeeping.tasks.request`, plano com `housekeeping.assignments.manage`.

**Fora de escopo:**

- execução e inspeção (E10);
- achados e danos (E11);
- tipos de limpeza e faxinas programadas (M7 E13);
- a visão da Camareira ([ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md)).

**Critérios de aceite:**

- [ ] Como Governanta, a lista da data operacional do seed mostra as tarefas na ordem do servidor, e uma situação desconhecida aparece como "Outro (VALOR)".
- [ ] Uma avulsa criada como Recepcionista aparece na lista dela na hora e na da Governanta ao atualizar. A publicação do plano feita pela Governanta atualiza a lista do Gerente, em outra sessão, em até 2 s, sem recarregar.
- [ ] Sugerir, mover uma tarefa para outra camareira e publicar deixa as tarefas `ASSIGNED`, com a camareira e a posição do plano. Uma publicação com versão velha trata o `412`, relendo o plano.
- [ ] Como Recepcionista, a área mostra a lista e a avulsa, sem as ações do plano. Um usuário só com `OWN` em `housekeeping.tasks.execute` (a Camareira do seed) não vê a área (ADR 0015).
- [ ] As recusas da distribuição aparecem em pt-BR no campo ou na tarefa, sem `code` cru. Entre elas, `ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED` e `ASSIGNMENT_TASK_NOT_ASSIGNABLE`.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** não

**Notas para o plano:**

- Num banco recém-criado, não há tarefas até um check-out, uma troca de UH, uma avulsa ou um fechamento. Confira o que o seed já faz. Se faltar material, a etapa amplia o seed com check-outs ou avulsas (ver H4 para a distribuição).
- O tópico `housekeeping` só publica a publicação do plano, as transições da execução e a inspeção (ARCHITECTURE §10 do servidor). A criação da avulsa, a sugestão e o ajuste não publicam, então a lista tem "Atualizar", e quem escreveu segue pelo retorno da escrita.
- É o primeiro `PATCH` do contrato. A fachada do `core` usa o método gerado pelo ogen, caracterizado na M0 E04.
- O plano tem `If-Match` em toda escrita, e só a publicação muda as tarefas. A sugestão e o ajuste mexem só no rascunho.
- A tarefa terminada sai do plano publicado sem aviso (D12 da M6 E04 do servidor).
- O `employeeId` nulo num item do `PATCH` deixa a tarefa no plano sem camareira (descrição da operação).
- Partes independentes, para as ondas do plano: a lista com a avulsa e o plano do dia usam operações distintas. O tópico `housekeeping` serve aos dois e vem antes.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A distribuição sem camareira escalada no seed (H4). O roteiro mostra a publicação sem atribuição, e a lacuna fica registrada.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E10 — Execução e inspeção das tarefas — #pendente

**Objetivo:** no detalhe da tarefa, a Governanta ou o Gerente registra a execução: inicia, pausa, retoma, recusa (não perturbe ou recusa do hóspede), tenta de novo e conclui, respondendo o checklist da tarefa, com foto quando o item pede. Depois, inspeciona a UH: inicia e conclui a inspeção, aprovando ou reprovando com os itens reprovados. Também há a inspeção voluntária de uma UH limpa. O mapa de UHs e a lista mostram o efeito ao vivo.

**Por que agora:** fecha o ciclo da limpeza que o mapa (E01) mostra, e é o que o desktop faz no lugar do mobile, pela Governanta (ADR 0015).

**Dependências:** E09 e E03 (o formulário de respostas do checklist).

**Escopo:**

- **Execução:** `POST /api/v1/housekeeping-tasks/{id}/start`, `/pause`, `/resume`, `/refuse`, `/retry` e `/complete`, com `If-Match`. A conclusão leva as respostas, a observação de ocupação e as fotos enviadas por `POST /api/v1/files`.
- **Inspeção:** `POST /api/v1/housekeeping-tasks/{id}/inspection/start` e `/inspection/complete`, com `If-Match`; `POST /api/v1/room-inspections`, a voluntária.
- **Ações por permissão:** execução com `housekeeping.tasks.execute`, inspeção com `housekeeping.inspections.perform`.

**Fora de escopo:**

- comandos offline e o feed da camareira (mobile; ADR 0015);
- o editor dos modelos de checklist de limpeza e de inspeção (M7 E08).

**Critérios de aceite:**

- [ ] Como Governanta, iniciar e concluir uma tarefa de saída do seed com o checklist respondido leva a tarefa a aguardar inspeção (ou a concluída, se o tipo não exige inspeção) e a UH a Limpa no mapa de outra sessão, em até 2 s.
- [ ] Concluir com item obrigatório sem resposta mostra o erro no item (`CHECKLIST_REQUIRED_ITEMS_MISSING`), e nada muda.
- [ ] A inspeção reprovada leva a UH a Suja e a tarefa a `REJECTED`, com os itens reprovados. Iniciar de novo a refaz, e a inspeção aprovada leva a UH a Inspecionada.
- [ ] Recusar por "não perturbe" uma tarefa de UH ocupada liga o sinal na UH. "Tentar de novo" devolve a tarefa a `ASSIGNED`.
- [ ] Uma ação com a versão velha trata o `412`, relendo a tarefa. Uma transição que o servidor recusa (`HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`) aparece em pt-BR.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** não

**Notas para o plano:**

- Decisões do servidor (M6 E04 e E05 dele):
  - `start` só até a data operacional, e a tarefa de data posterior dá `422 HOUSEKEEPING_TASK_NOT_DUE`;
  - quando a Governanta inicia a tarefa, ela vira a executora do checklist, e a foto obrigatória é de quem iniciou;
  - `AWAITING_INSPECTION` só quando a conclusão deixou a inspeção pendente na UH;
  - `answers` não nulo numa tarefa sem checklist dá `400`;
  - `foundGuestCount` sem `foundOccupancy` dá `REQUIRED`;
  - numa tarefa sem modelo de inspeção, o `inspection/complete` abre e conclui numa chamada, e com modelo, sem início, dá `409 HOUSEKEEPING_INSPECTION_NOT_STARTED`;
  - numa UH limpa com inspeção pendente, a inspeção voluntária dá `409 HOUSEKEEPING_INSPECTION_USE_TASK`.
- A resposta das ações traz a tarefa com a versão nova: a tela segue com ela, sem reler. O tópico `housekeeping` atualiza as outras sessões.
- Partes independentes, para as ondas do plano: a execução e a inspeção usam operações distintas. A inspeção voluntária é independente das duas.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O seed sem modelo de checklist de limpeza ou de inspeção. A tarefa conclui sem checklist, e o critério do item obrigatório usa um modelo criado pelo seed (H1).

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Achados e perdidos e danos — #pendente

**Objetivo:** na área Governança, a equipe registra um item achado ou um dano numa UH ou numa área. Quem gere os achados define o local de guarda, devolve o item (a quem) ou, depois do prazo de guarda, descarta ou doa. Quem gere os danos revisa (valor final, código de lançamento, reserva vinculada), cobra na conta aberta da reserva ou dispensa, com o motivo.

**Por que agora:** completa a governança com o que mais aparece na operação e liga a governança à conta do hóspede (M4).

**Dependências:** E09 (a área Governança).

**Escopo:**

- **Achados:**
  - `GET`/`POST /api/v1/lost-and-found-items`, `GET …/{id}` e `PUT …/{id}/storage-place`;
  - `POST …/{id}/return`, `/discard` e `/donate`, com `If-Match`.
- **Danos:**
  - `GET`/`POST /api/v1/damage-reports` e `GET …/{id}`;
  - `POST …/{id}/review`, `/charge` e `/waive`, com `If-Match`.
- **Permissões:**
  - registrar e ver os próprios com `housekeeping.occurrences.report`;
  - gerir com `housekeeping.lostfound.manage` e `housekeeping.damages.manage`, que, no modelo, são do Gerente e da Recepção, sem a Governanta (ADR 0015).

**Fora de escopo:**

- o registro offline (mobile);
- o lançamento manual na conta, que é da M4.

**Critérios de aceite:**

- [ ] Registrar um item achado numa UH do seed o mostra `STORED` na lista. Descartar antes do prazo mostra em pt-BR a recusa `HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED` e não muda nada.
- [ ] Devolver o item grava a quem, e o "a quem" só aparece para quem gere os achados.
- [ ] Registrar um dano numa UH ocupada, revisar com valor e código de lançamento e cobrar faz o lançamento aparecer na conta da hospedagem (M4), em outra sessão.
- [ ] Cobrar um dano não revisado mostra em pt-BR a recusa `HOUSEKEEPING_DAMAGE_NOT_REVIEWED`. Dispensar grava o motivo, e o dano não aparece na conta.
- [ ] As telas de descarte, doação e dispensa avisam que o texto livre fica gravado e não deve levar dado pessoal.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- Avisos do servidor ao front (M6 E06 dele, fim do lote E20–E06):
  - o custo vai como texto decimal, até 2 casas e `99999.99`;
  - na revisão do dano, a tela envia o `reservationId` atual;
  - descarte, doação e dispensa levam o aviso de texto livre.
- A cobrança lança pela `BillingApi` do servidor só com `housekeeping.damages.manage`. A conta precisa ser uma conta aberta da reserva vinculada: inexistente dá `400 HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO`, e encerrada ou de outro dono dá `409 HOUSEKEEPING_DAMAGE_FOLIO_INVALID`. Reaproveite a escolha de conta da M4.
- O dano que vem de uma tarefa de saída ou de troca de UH nunca vincula reserva (ARCHITECTURE §6.2 do servidor). Os achados e danos que um checklist abre (`OPEN_OCCURRENCE`) aparecem com origem `CHECKLIST`.
- Confira o que a Camareira (`OWN`) consegue com o modelo padrão. Por exemplo, se ela lê as UHs e as áreas para escolher o local. Registre no handoff (ADR 0015, item 2).
- Partes independentes, para as ondas do plano: achados e danos usam operações distintas e não dependem um do outro.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Um dano cobrado por engano na demo. A cobrança pede confirmação com o valor e a conta, e o roteiro usa um hóspede do seed criado para isso.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Gate do marco M6 — #pendente

**Objetivo:** o M6 entregue como build testável. O seed passa a ter modelos de checklist com execuções, uma escala com camareiras e tarefas de governança na data operacional, com um item achado e um dano. O roteiro do M6 fica escrito, e a definição de pronto é verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e vira a build de demo.

**Dependências:** E02, E04, E05, E06, E08, E10, E11.

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

**Notas para o plano:**

- O roteiro segue a manhã da governanta: tarefas do dia e plano publicado (E09); execução, inspeção reprovada e refeita (E10); achado e dano cobrado na conta (E11). Os passos são feitos pela Governanta, pelo Gerente e pela Recepção, sem passo da Camareira no desktop (ADR 0015). O fechamento do dia continua por último (regra 5).
- O fechamento também gera as tarefas do dia seguinte (desde a M0 E04): depois dele, a lista da nova data já tem tarefas de permanência.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O seed passa de 2 minutos com os checklists, a escala e as tarefas. Paralelizar as criações ou reduzir o volume.

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
- 2026-11-15 — análise da rodada 3 do M0 (`claude --agent arquiteto`, servidor `5e06f16d51ff`). A E08 sai de `#bloqueada` para `#pendente`, porque a M6 E11 do servidor (as 8 etapas do fechamento e o avanço da data) está no commit que a M0 E03 fixa. A H3 foi confirmada, e o escopo da E08 foi revisto: o contrato do fechamento não mudou. Entraram um critério de no-show e notas com as decisões da E11 do servidor.
- 2026-11-17 — revogação do processo acelerado ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), `claude --agent arquiteto`): a seção "Prompt acelerado" foi removida. O protocolo e o prompt de execução não mudaram, porque já remetem ao orquestrador para o ciclo por tarefa. A E01, a E04 e a E05 ganharam, em "Notas para o plano", as partes que não dependem uma da outra, para o plano formar ondas. Objetivo, escopo e critérios de aceite não mudaram, e nenhuma tag mudou.
- 2026-11-20 — análise da rodada 4 do M0 (`claude --agent arquiteto`, servidor `8c563cda0575`). A governança do servidor (M6 E01 a E06 e E19 dele) chegou com 41 operações.
  - Entram três etapas: E09 (tarefas do dia e distribuição), E10 (execução e inspeção; depende da E09 e da E03) e E11 (achados e perdidos e danos). O gate E07 passa a depender da E10 e da E11.
  - O marco tem agora 11 etapas.
  - A configuração da governança foi para a M7 E13.
  - Pelo [ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md), decidido pelo usuário em entrevista, as tarefas no desktop são da Governanta, do Gerente e da Recepção, e a Camareira fica no mobile.
  - Objetivo, "Contexto confirmado", escopo e restrições ganharam a governança, o [ADR 0014](../adr/0014-campos-com-padrao-sempre-explicitos.md) e a entrada estrita. Nasce a H4 (camareiras escaladas no seed).
  - A E01, a E03 e a E05 ganharam notas, e o gate ganhou objetivo e notas.
  - Nenhuma tag existente mudou.
