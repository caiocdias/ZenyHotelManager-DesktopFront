# Roadmap — M3 Reservas

> Arquivo: `docs/roadmaps/roadmap-m3-reservas.md` · branch de execução: `roadmap/m3-reservas` · criado em 2026-11-10 · última revisão 2026-11-10

> **Pré-requisito:** etapa E06 ("Gate do marco M2") de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` marcada `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m3-reservas`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O recepcionista e o gerente gerenciam reservas no app. Eles:

- buscam reservas por filtros e por documento;
- consultam disponibilidade e cotam preço por período e tipo;
- criam reservas e alteram as existentes, inclusive estender ou reduzir uma estadia em curso;
- confirmam, cancelam (com a multa da política) e reativam (com aprovação);
- acompanham as pendências: garantias, depósitos, no-shows esperados e realocação necessária.

## Contexto confirmado

- **Base:** M1 e M2 (núcleo, design, aprovação, SSE, painel do dia, diálogo de hóspede). Caminhos e comandos reais nos handoffs dos roadmaps M1 e M2.
- **Operações do contrato** (`api/openapi.yaml`, commit fixado):
  - **busca e consulta:**
    - `GET /api/v1/reservations`, com filtros e cursor;
    - `POST /api/v1/reservations/search-by-document`;
    - `GET /api/v1/reservations/{id}` e `/history`.
  - **disponibilidade e cotação:**
    - `GET /api/v1/availability`;
    - `GET /api/v1/room-type-inventory`;
    - `GET /api/v1/quotes`.
  - **criação e mudanças:**
    - `POST /api/v1/bookings` e `GET /api/v1/bookings/{id}`;
    - `PUT /api/v1/reservations/{id}`;
    - `POST /api/v1/reservations/{id}/confirm`, `/cancel` e `/reactivate`.
  - **pendências:**
    - `GET /api/v1/reservations/pending-guarantees`, `/deposit-shortfalls` e `/expected-no-shows`;
    - `GET /api/v1/room-assignments/reallocation-required`.
- **Regras do servidor:**
  - disponibilidade sem overbooking;
  - preço congelado na reserva;
  - desconto com limite por role e aprovação;
  - multa de cancelamento lançada na conta;
  - reativação aprovada;
  - pré-reserva vencida cancelada por job.

  Ficam nas notas do brain `ZenyHotelManager - Módulo reservations (inventário, disponibilidade e reservas)`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` e `ZenyHotelManager - Módulo rates`, e no gate do M4 do servidor (`docs/roadmaps/README.md` do servidor, histórico de 2026-10-22).
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6.2 e §7.

## Escopo

**Incluído:**

- busca de reservas;
- disponibilidade e cotação;
- nova reserva (individual, e agrupadora se o contrato de `bookings` a cobrir);
- alteração, inclusive estender e reduzir uma estadia em curso;
- confirmar, cancelar e reativar;
- histórico;
- pendências;
- seed ampliado;
- roteiro do M3.

**Fora de escopo:**

- bloqueios de grupo, rooming list e limite de overbooking, que vão para o M7 (cadastros e gestão);
- cadastro de tarifas (M7);
- conta e pagamento de depósito (M4).

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado;
  - uma `Idempotency-Key` por intenção;
  - `ETag` e `If-Match` com o `412` tratado;
  - permissões efetivas, com o servidor decidindo;
  - dinheiro em `BigDecimal`;
  - datas de negócio sem conversão de fuso.
- **Preço:** o front nunca calcula preço nem disponibilidade por conta própria. Mostra o que o servidor devolve.
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1.
- **Dados pessoais:** documento sempre mascarado. Nenhum dado de hóspede em log ou cache de disco. O seed só usa dados fictícios.
- **Servidor:** é só leitura, e o commit fixado não muda no marco.
- **Idioma:** a interface e a documentação ficam em pt-BR; o código, em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | `POST /api/v1/bookings` cria tanto a reserva individual quanto a agrupadora, e a alteração do hospedado (estender ou reduzir) é `PUT /api/v1/reservations/{id}` | A tela de nova reserva ou a de alteração precisa de outro fluxo; registrar no handoff | E03 |
| H2 | `GET /api/v1/availability` responde rápido o bastante para um calendário de 30 dias por tipo de UH | Calendário paginado por semana | E02 |

## Definição global de pronto

- [ ] Os comandos oficiais de build, teste e lint passam.
- [ ] O `smokeTest` passa contra o ambiente recriado.
- [ ] O roteiro `docs/demo/roteiro-m3.md`, que acumula os anteriores, foi executado pelo usuário no Windows.
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

- Toda execução acontece no worktree/branch `roadmap/m3-reservas`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m3-reservas-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m3-reservas Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m3-reservas.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m3-reservas com superpowers:using-git-worktrees. Não trabalhe na branch principal.
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
| E01 | Busca de reservas | #pendente | — | Lista com filtros e cursor, busca por documento e acesso ao detalhe |
| E02 | Disponibilidade e cotação | #pendente | — | Calendário de disponibilidade por tipo, inventário e cotação de preço |
| E03 | Nova reserva e alteração | #pendente | E01, E02 | Criar reserva a partir da cotação, alterar (inclusive estender e reduzir) e confirmar |
| E04 | Cancelar, reativar e pendências | #pendente | E01 | Cancelamento com multa, reativação aprovada e listas de pendências |
| E05 | Gate do marco M3 | #pendente | E03, E04 | Seed ampliado, roteiro do M3 e definição de pronto verificada |

---

## E01 — Busca de reservas — #pendente

**Objetivo:** a área Reservas lista as reservas com filtros por período, situação e titular, com paginação por cursor e busca por documento. Um clique abre o detalhe criado no M2.

**Por que agora:** é a porta de entrada das demais ações do marco.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M2).

**Escopo:**

- `GET /api/v1/reservations`, com os filtros que o contrato oferece.
- `POST /api/v1/reservations/search-by-document`.
- Situação em pt-BR, com o enum extensível tolerado.
- Reaproveitamento do detalhe da M2 E01.

**Fora de escopo:** ações (E03 e E04).

**Critérios de aceite:**

- [ ] Contra o seed, filtrar por período e situação devolve o esperado, e a página seguinte carrega sem repetir linhas.
- [ ] A busca por documento de um hóspede do seed encontra as reservas dele, com o documento mascarado na tela.
- [ ] O termo buscado não aparece em log.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
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

## E02 — Disponibilidade e cotação — #pendente

**Objetivo:** um calendário mostra, por tipo de UH, a disponibilidade dos próximos dias. Escolhendo período, tipo e ocupação, o usuário vê a cotação por plano, com o total e o preço por noite vindos do servidor.

**Por que agora:** é a base da nova reserva (E03) e um bom momento visual da demo.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- Disponibilidade e inventário: `GET /api/v1/availability` e `GET /api/v1/room-type-inventory`.
- Cotação: `GET /api/v1/quotes`.
- Restrições do plano (estadia mínima e outras, conforme o contrato) mostradas como aviso.

**Fora de escopo:**

- Ajuste de inventário e limite de overbooking (M7).
- Cadastro de tarifas (M7).

**Critérios de aceite:**

- [ ] Contra o seed, o calendário de 30 dias mostra a disponibilidade por tipo. Uma reserva criada em outro cliente reduz a disponibilidade depois de recarregar.
- [ ] A cotação mostra os planos com os valores formatados em BRL, iguais aos devolvidos pela API (conferidos por `curl`).
- [ ] Uma cotação que fere uma restrição mostra o motivo em pt-BR.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lentidão (H2). Paginar por semana.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Nova reserva e alteração — #pendente

**Objetivo:** a partir de uma cotação, o usuário cria a reserva: titular buscado ou criado, plano, garantia exigida pela política e observações. Ele também altera uma reserva existente (datas, tipo, plano, hóspede), o que inclui estender ou reduzir a estadia de um hospedado, e confirma uma pré-reserva. Desconto acima do limite pede aprovação.

**Por que agora:** completa o ciclo comercial da reserva.

**Dependências:** E01, E02.

**Escopo:**

- Criação: `POST /api/v1/bookings` e `GET /api/v1/bookings/{id}`.
- Alteração e confirmação: `PUT /api/v1/reservations/{id}` com `If-Match` e `POST /api/v1/reservations/{id}/confirm`.
- Diálogo de hóspede da M2 E02.
- Diálogo de aprovação para desconto.

**Fora de escopo:** pagamento de depósito (M4) e bloqueios de grupo (M7).

**Critérios de aceite:**

- [ ] Criar uma reserva de 3 noites a partir da cotação. Ela aparece na busca e reduz a disponibilidade.
- [ ] Desconto acima do limite do Recepcionista abre o diálogo de aprovação. Aprovado pelo Gerente do seed, a reserva é criada uma vez só.
- [ ] Estender um hospedado em 1 noite atualiza o período e o histórico.
- [ ] A alteração concorrente trata o `412` com recarga e aviso.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o preço fica congelado na reserva: alterar a tarifa publicada não muda reserva existente, conforme o gate do M4 do servidor.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O fluxo de reserva agrupadora é complexo (H1). Entregar a individual primeiro; a agrupadora entra se couber, senão vira etapa nova registrada no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Cancelar, reativar e pendências — #pendente

**Objetivo:** o usuário cancela uma reserva, vendo antes a multa da política, e reativa uma cancelada com aprovação do gerente. Também acompanha as quatro listas de pendências e abre a reserva a partir delas.

**Por que agora:** fecha o ciclo de vida da reserva e mostra o controle gerencial.

**Dependências:** E01.

**Escopo:**

- Cancelar e reativar: `POST /api/v1/reservations/{id}/cancel` e `/reactivate`.
- Pendências:
  - `GET /api/v1/reservations/pending-guarantees`;
  - `GET /api/v1/reservations/deposit-shortfalls`;
  - `GET /api/v1/reservations/expected-no-shows`;
  - `GET /api/v1/room-assignments/reallocation-required`.

**Fora de escopo:** cobrança da multa (M4: a multa vai para a conta).

**Critérios de aceite:**

- [ ] Cancelar uma reserva do seed com política de multa: a multa é mostrada antes de confirmar, e a reserva vira cancelada.
- [ ] Reativar como Recepcionista abre o diálogo de aprovação. Com o Gerente, a reserva volta.
- [ ] As quatro listas carregam contra o seed, e o seed tem ao menos um item em cada uma que o servidor permitir criar.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alguma pendência depende de job ou data e não se cria pelo seed. Registrar isso no roteiro, sem forçar.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Gate do marco M3 — #pendente

**Objetivo:** entregar o M3 como build testável. O seed passa a ter reservas futuras, canceladas e pendentes. O roteiro do M3 fica escrito, e a definição de pronto é verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e passa a ser a build de demo.

**Dependências:** E03, E04.

**Escopo:**

- Seed ampliado (design §7.2).
- `docs/demo/roteiro-m3.md` (a criar), acumulando os anteriores.
- Pacote atualizado.
- Verificação num clone limpo.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] No clone limpo, build, teste e lint passam.
- [ ] O `smokeTest` passa, e o seed roda em menos de 2 minutos.
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.

**Validação obrigatória:**

- Os comandos oficiais passam num clone limpo.
- O `smokeTest` passa.
- O roteiro foi executado pelo usuário.

**Revisão humana:** sim. Gate do marco e nova build de demo.

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além dos do M2.

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
