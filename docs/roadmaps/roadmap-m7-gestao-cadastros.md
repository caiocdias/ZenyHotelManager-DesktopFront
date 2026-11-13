# Roadmap — M7 Gestão e cadastros

> Arquivo: `docs/roadmaps/roadmap-m7-gestao-cadastros.md` · branch de execução: `roadmap/m7-gestao-cadastros` · criado em 2026-11-10 · última revisão 2026-11-13

> **Pré-requisito:** a etapa E07 ("Gate do marco M6") de `docs/roadmaps/roadmap-m6-governanca-operacao.md` precisa estar `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

## Como usar

1. Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador`.
2. Cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m7-gestao-cadastros`; cada uma é planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain).
3. A execução para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você.

Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O administrador e o gerente configuram o hotel inteiro pelo app, sem depender do `demo-seed` nem de `curl`. O marco cobre:

- UHs, tipos, áreas e características;
- tarifas: temporadas, planos, derivação, preços e restrições em massa, pacotes e políticas;
- grupos e bloqueios, com o limite de overbooking;
- usuários, roles, permissões e sessões;
- dados, políticas e impostos do hotel, com o logotipo;
- pontos de caixa, códigos de lançamento e roteamento;
- modelos e rotinas de checklist;
- pessoal e escala;
- configuração da FNRH e fila de envio;
- impressoras, agentes de impressão e fila de impressão;
- trilha de auditoria.

Ao fim, o front cobre todo o contrato do commit fixado, exceto o que é do mobile (`sync`, push) e do agente de impressão (`/api/v1/print-agents/me/*`).

## Contexto confirmado

- **Base:** M1 a M6, com o design system, a aprovação de superior e o upload de arquivos.
- **Operações do contrato** (`api/openapi.yaml`, commit fixado):

  | Área | Operações |
  |---|---|
  | UHs | `/api/v1/rooms`, `/room-types`, `/areas`, `/room-features`, com `deactivate`/`reactivate` |
  | Tarifas | `/api/v1/seasons`, `/rate-plans` (com `derivation`, `prices`, `prices/bulk-update`, `prices/bulk-remove`, `restrictions`, `restrictions/bulk-update`), `/packages`, `/cancellation-policies`, `/guarantee-policies` |
  | Grupos e bloqueios | `/api/v1/room-blocks` (com `pick-up` e `rooming-list`), `/room-type-inventory/overbooking-limit` e `/recount` |
  | Acesso | `/api/v1/users` (com `roles`, `reset-password`, `reset-pin`, `sessions`), `/roles` (com `duplicate`), `/permissions`, `/auth/sessions` |
  | Hotel | `/api/v1/hotel`, `/hotel/operational-settings`, `/hotel/policies`, `/hotel/tax-settings`, `/files` |
  | Financeiro | `/api/v1/cash-points`, `/transaction-codes`, `/routing-rules` |
  | Checklists | `/api/v1/checklist-templates` (com `publish` e `versions`), `/checklist-routines` |
  | Pessoal | `/api/v1/departments`, `/employees`, `/shifts`, `/schedule-patterns`, `/schedule/entries`, `/schedule/generate` |
  | FNRH | `/api/v1/fnrh/settings` (com `clear-credentials`), `/fnrh/requesters`, `/fnrh/domains` (com `refresh`), `/fnrh/status`, `/fnrh/submissions` (com `retry` e `dismiss`) |
  | Impressão | `/api/v1/print-agents` (com `deactivate` e `rotate-token`), `/printers` (com `deactivate`), `/print-jobs`, e os `POST …/print` de `/reservations/{id}/registration-card`, `/reservations/{id}/confirmation` e `/folios/{id}/statement` |
  | Auditoria | `/api/v1/audit-entries` |

  As linhas de FNRH (submissões e `clear-credentials`) e de Impressão existem desde o pin `57eb5fac5b8a` (M0 E02).

- **Regras do servidor:**
  - **Delegação (RF-ACS-09, ARCHITECTURE §8.2):**
    - ninguém concede o que não tem;
    - só um administrador gere administradores;
    - o último administrador ativo não é desativado;
    - usuários e roles nunca são apagados, só desativados.
  - **Tarifas:** tarifa publicada alterada com aprovação sem mudar o preço congelado das reservas.
  - **Credenciais:** credenciais da FNRH cifradas e nunca devolvidas em claro.
  - **Envio à FNRH** (M5 E08 e E16 do servidor): uma submissão por evento, com ordem causal por reserva. São 8 tentativas, de 1 min a 1 h. `FAILED` e `INVALID` seguram a fila da reserva até `retry` ou `dismiss`. Sem credencial, tudo fica `PENDING` sem gastar tentativa, e ao ligar a integração sai todo o acumulado. `clear-credentials` apaga usuário, senha e CPF padrão e desliga a integração. A homologação com o Serpro (M5 E09 do servidor) está `#bloqueada`.
  - **Impressão** (M5 E10 do servidor): o agente de impressão é um cliente na LAN, em repositório próprio, e fica fora do desktop (design §2). O token do agente aparece uma única vez, na criação e na troca. Cadastrar agentes e impressoras exige `printing.agents.manage`, que só o Administrador tem. Listar impressoras e jobs exige `printing.jobs.read`.
  - **Logotipo e cabeçalho:** o logotipo do hotel fica em `files`.

  Detalhes nas notas do brain:
  - `ZenyHotelManager - Segurança, autenticação e autorização`;
  - `ZenyHotelManager - Módulo rates`;
  - `ZenyHotelManager - Módulo rooms`;
  - `ZenyHotelManager - Módulos hotel e staff`;
  - `ZenyHotelManager - Módulo checklists`;
  - `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`;
  - `ZenyHotelManager - Módulo fnrh`;
  - `ZenyHotelManager - Auditoria`.
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6.2 e §7.

## Escopo

**Incluído:** os onze grupos de cadastro e gestão listados no Objetivo e o roteiro do M7.

**Fora de escopo:**

- sincronização offline e push (mobile);
- o agente de impressão e as rotas dele (`/api/v1/print-agents/me/*`, esquema `printAgentToken`): outro repositório (ADR 0009);
- fechamento do dia (M6 E08 do front);
- funcionalidades que o servidor ainda não tem, que entram por rodadas do M0:
  - tarefas de governança e manutenção (M6 do servidor);
  - pagamentos integrados e fiscal (M7 do servidor);
  - PDV e distribuição (M8 do servidor).

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru;
  - enum extensível tolerado;
  - uma `Idempotency-Key` por intenção;
  - `ETag` e `If-Match` com `412` tratado;
  - permissões efetivas, com o servidor decidindo.
- **Autorização:** o front nunca decide regras de delegação nem de escopo. Mostra só o que o usuário pode conceder, a partir das próprias permissões efetivas, e trata o `403` do servidor.
- **Segredos e credenciais:** senhas, PINs, credenciais da FNRH e o token do agente de impressão vão só no corpo da requisição ou da resposta. Nunca ficam em log, preferências nem campo pré-preenchido. A credencial gravada aparece como "configurada", nunca em claro. O token do agente aparece uma vez, com cópia explícita, e some ao fechar o diálogo.
- **Exclusão:** cadastros são desativados, não apagados, conforme o contrato.
- **Aprovação:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1.
- **Servidor:** é só leitura, e o commit fixado não muda no marco.
- **Idioma:** UI em pt-BR, código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O catálogo `GET /api/v1/permissions` traz descrição, escopos suportados e chaves de limite suficientes para montar o editor de roles sem tabela fixa no front | O editor precisa de rótulos em pt-BR mantidos no front, com risco de defasagem no M0 | E05 |
| H2 | A configuração da FNRH pode ser editada e testada sem credencial real do Serpro, mostrando o status "não configurada" (Q-12 do servidor em aberto) | A tela fica só com a leitura do status até a credencial existir | E10 |
| H3 | Sem um agente de impressão real, a fila se demonstra com um agente e uma impressora cadastrados pelo app: o job fica pendente e aparece na lista de jobs | O roteiro mostra só o cadastro, e o envio fica para quando houver agente | E12 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] O `smokeTest` passa contra o ambiente recriado.
- [ ] Roteiro `docs/demo/roteiro-m7.md`, acumulando os anteriores, executado pelo usuário no Windows. Inclui configurar uma UH, uma tarifa e um usuário só pelo app.
- [ ] Toda operação do contrato fixado tem tela no app ou está listada como fora do desktop (mobile ou agente de impressão) no handoff do gate.

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

- Toda execução acontece no worktree/branch `roadmap/m7-gestao-cadastros`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m7-gestao-cadastros-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m7-gestao-cadastros Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m7-gestao-cadastros.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m7-gestao-cadastros com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Rode o pré-voo do servidor (tools/server-drift.sh) e siga a regra do protocolo.
5. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + design citado), execute com superpowers:subagent-driven-development, feche a etapa e preencha o handoff conforme o protocolo.
6. Pare nas paradas do protocolo ou ao atingir o limite.
7. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

O repositório do servidor (/home/caio/repos/ZenyHotelManager) é só leitura: nunca escreva nele. Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, o resultado do pré-voo, a branch e os commits do lote e o próximo passo sugerido.
```

## Índice de etapas

**Bloco A — Estrutura e comercial**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | UHs, tipos, áreas e características | #pendente | — | Cadastro completo das UHs e do que as classifica |
| E02 | Temporadas, planos e derivação | #pendente | E01 | Temporadas, planos tarifários e plano derivado |
| E03 | Preços, restrições, pacotes e políticas | #pendente | E02 | Grade de preços e restrições em massa, pacotes, políticas de cancelamento e garantia |
| E04 | Grupos, bloqueios e inventário | #pendente | E01 | Bloqueios com pick-up e rooming list, limite de overbooking e recontagem |

**Bloco B — Acesso e hotel**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E05 | Usuários, roles e sessões | #pendente | — | Usuários, editor de roles com escopo e limites, senha, PIN e sessões |
| E06 | Dados e parâmetros do hotel | #pendente | — | Dados com logotipo, parâmetros operacionais, políticas e impostos |
| E07 | Financeiro: caixas, códigos e roteamento | #pendente | — | Pontos de caixa, códigos de lançamento e regras de roteamento |

**Bloco C — Operação, pessoal e conformidade**

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E08 | Modelos e rotinas de checklist | #pendente | — | Editor de modelos com rascunho, publicação e versões, e rotinas |
| E09 | Pessoal e montagem da escala | #pendente | — | Departamentos, funcionários, turnos, padrões e geração da escala |
| E10 | FNRH e trilha de auditoria | #pendente | E05 | Configuração da FNRH, limpeza da credencial, CPF solicitante, domínios, status, fila de envio e consulta da auditoria |
| E12 | Impressoras, agentes e fila de impressão | #pendente | E05 | Cadastro de agentes (token exibido uma vez) e impressoras, fila de jobs e "Enviar à impressora do hotel" nas telas de documento |
| E11 | Gate do marco M7 | #pendente | E03, E04, E06, E07, E08, E09, E10, E12 | Roteiro do M7, cobertura do contrato e definição de pronto verificada |

---

## E01 — UHs, tipos, áreas e características — #pendente

**Objetivo:** o gerente cadastra, edita, desativa e reativa UHs, tipos de UH, áreas e características. O mapa de UHs reflete os cadastros.

**Por que agora:** as UHs e os tipos são a base de tarifas e bloqueios, o resto do bloco A.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M6).

**Escopo:** `GET`/`POST`/`PUT` e `deactivate`/`reactivate` de `/api/v1/rooms`, `/room-types`, `/areas` e `/room-features`.

**Fora de escopo:** status e OOO (M6).

**Critérios de aceite:**

- [ ] Criar um tipo e uma UH nova pelo app faz a UH aparecer no mapa e na disponibilidade (M3).
- [ ] Número de UH duplicado mostra o erro no campo, em pt-BR.
- [ ] Desativar uma UH a tira do mapa operacional, e reativar a devolve.

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

## E02 — Temporadas, planos e derivação — #pendente

**Objetivo:** o gerente cadastra temporadas e planos tarifários e define um plano derivado de outro, por exemplo um percentual sobre o base, ou remove a derivação. Desativa e reativa planos.

**Por que agora:** os preços (E03) dependem dos planos.

**Dependências:** E01.

**Escopo:**

- **Temporadas:** `GET`/`POST`/`PUT` em `/api/v1/seasons`, com `deactivate`/`reactivate`.
- **Planos:** `GET`/`POST`/`PUT` em `/api/v1/rate-plans`, com `deactivate`/`reactivate`.
- **Derivação:** `PUT /api/v1/rate-plans/{id}/derivation` e `POST /api/v1/rate-plans/{id}/derivation/remove`.

**Fora de escopo:** grade de preços (E03).

**Critérios de aceite:**

- [ ] Um plano derivado criado pelo app aparece na cotação (M3) com o valor calculado pelo servidor.
- [ ] Alterar um plano publicado que exige aprovação abre o diálogo de aprovação.
- [ ] A edição concorrente trata o `412`.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o preço fica congelado na reserva existente (gate do M4 do servidor).

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

## E03 — Preços, restrições, pacotes e políticas — #pendente

**Objetivo:** o gerente edita a grade de preços por plano, tipo e data, com atualização e remoção em massa, e as restrições, também em massa. Cadastra pacotes e as políticas de cancelamento e de garantia.

**Por que agora:** completa a área comercial.

**Dependências:** E02.

**Escopo:**

- **Preços:** `GET /api/v1/rate-plans/{id}/prices` e `POST /api/v1/rate-plans/{id}/prices/bulk-update` e `/prices/bulk-remove`.
- **Restrições:** `GET /api/v1/rate-plans/{id}/restrictions` e `POST /api/v1/rate-plans/{id}/restrictions/bulk-update`.
- **Pacotes e políticas:** `/api/v1/packages`, `/cancellation-policies` e `/guarantee-policies` (`GET`/`POST`/`PUT`, `deactivate`/`reactivate`).

**Fora de escopo:** distribuição para OTAs (servidor M8).

**Critérios de aceite:**

- [ ] Atualizar em massa os preços de um período muda a cotação (M3) desse período.
- [ ] A grade mostra os valores em BRL, e uma entrada inválida é recusada no campo.
- [ ] Uma política de cancelamento criada pelo app aparece como opção ao criar um plano.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A grade fica grande. Paginar por mês e tipo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Grupos, bloqueios e inventário — #pendente

**Objetivo:** o gerente cria bloqueios de UHs para grupos e acompanha o pick-up. Ele importa ou edita a rooming list, ajusta o limite de overbooking por tipo e pede a recontagem do inventário.

**Por que agora:** completa a gestão de reservas que ficou de fora no M3.

**Dependências:** E01.

**Escopo:**

- **Bloqueios:** `GET`/`POST`/`PUT /api/v1/room-blocks` e `POST /api/v1/room-blocks/{id}/pick-up` e `/rooming-list`.
- **Inventário:** `POST /api/v1/room-type-inventory/overbooking-limit` e `/recount`.

**Fora de escopo:** eventos (servidor M10).

**Critérios de aceite:**

- [ ] Um bloqueio criado pelo app reduz a disponibilidade, e o pick-up de uma reserva aparece no bloqueio.
- [ ] Mudar o limite de overbooking exige a permissão e reflete no inventário.

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

## E05 — Usuários, roles e sessões — #pendente

**Objetivo:** o administrador gerencia o acesso:

- cria e edita usuários e atribui roles;
- redefine a senha e o PIN;
- desativa e reativa usuários e vê e revoga as sessões deles;
- cria, edita, duplica, desativa e reativa roles num editor de permissões com escopo e limites.

Todo usuário vê e revoga as próprias sessões.

**Por que agora:** é a última peça de configuração. Até aqui o acesso vinha do seed.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Usuários:** `/api/v1/users` (`GET`/`POST`/`PUT`, `roles`, `reset-password`, `reset-pin`, `deactivate`/`reactivate`, `sessions` e `revoke`).
- **Roles:** `/api/v1/roles` (`GET`/`POST`/`PUT`, `duplicate`, `deactivate`/`reactivate`).
- **Catálogo e sessões próprias:** `GET /api/v1/permissions`, `GET /api/v1/auth/sessions` e `POST /api/v1/auth/sessions/{id}/revoke`.

**Fora de escopo:** qualquer regra de delegação no cliente (o servidor decide).

**Critérios de aceite:**

- [ ] Criar uma role pelo app, atribuí-la a um usuário novo e fazer login com ele mostra o menu correspondente.
- [ ] Um Gerente tentando conceder uma permissão que não tem recebe a recusa do servidor em pt-BR.
- [ ] Desativar o último administrador mostra a recusa do servidor em pt-BR.
- [ ] Revogar a sessão de um usuário logado em outra janela leva essa janela ao login no próximo refresh.
- [ ] Senha e PIN digitados não aparecem em log nem em preferências (teste).

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com duas sessões.

**Revisão humana:** sim. É autorização: um erro no editor de roles se propaga para todo o acesso do hotel.

**Notas para o plano:** delegação, escopos e limites estão na ARCHITECTURE §8.2 do servidor e na nota `ZenyHotelManager - Segurança, autenticação e autorização`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O catálogo não basta para rótulos amigáveis (H1). Manter um mapa de rótulos em pt-BR no front, conferido a cada rodada do M0.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Dados e parâmetros do hotel — #pendente

**Objetivo:** o administrador edita os dados do hotel, com o logotipo enviado como arquivo, os parâmetros operacionais, as políticas (horários de check-in e check-out, early e late) e as taxas. O topo do app mostra o logotipo.

**Por que agora:** deixa a demo com a identidade do hotel e fecha a configuração geral.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Dados do hotel:** `GET`/`PUT /api/v1/hotel`.
- **Parâmetros, políticas e taxas:** `GET`/`PUT /api/v1/hotel/operational-settings`, `/hotel/policies` e `/hotel/tax-settings`.
- **Logotipo:** `POST /api/v1/files` e `GET /api/v1/files/{id}/download-url`.

**Fora de escopo:** avanço da data operacional, que é do servidor no M6 dele.

**Critérios de aceite:**

- [ ] Trocar o logotipo pelo app faz ele aparecer no topo depois de recarregar.
- [ ] Mudar o horário de check-in muda a cotação de early (M2) conforme a política nova.
- [ ] A edição concorrente trata o `412`.

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

## E07 — Financeiro: caixas, códigos e roteamento — #pendente

**Objetivo:** o gerente cadastra pontos de caixa, códigos de lançamento e regras de roteamento (que lançamento vai para qual conta). Pode desativar e reativar cada um.

**Por que agora:** completa a configuração do financeiro usado no M4.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:** `/api/v1/cash-points`, `/transaction-codes` e `/routing-rules`, com `GET`/`POST`/`PUT` e `deactivate`/`reactivate`.

**Fora de escopo:** contas a receber (servidor M10).

**Critérios de aceite:**

- [ ] Um código de lançamento criado pelo app aparece como opção ao lançar numa conta (M4).
- [ ] Uma regra de roteamento criada pelo app manda as diárias para a conta da empresa pagadora numa reserva nova.

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

## E08 — Modelos e rotinas de checklist — #pendente

**Objetivo:** a governanta cria e edita o rascunho de um modelo de checklist, com itens, tipos de resposta e exigência de foto. Ela publica o modelo como versão nova, consulta as versões e cria rotinas que geram execuções.

**Por que agora:** completa os checklists executados no M6.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Modelos:** `/api/v1/checklist-templates` (`GET`/`POST`/`PUT`, `publish`, `versions`, `deactivate`/`reactivate`).
- **Rotinas:** `/api/v1/checklist-routines` (`GET`/`POST`/`PUT`, `deactivate`/`reactivate`).

**Fora de escopo:** ações de checklist sobre estoque (servidor M9).

**Critérios de aceite:**

- [ ] Um modelo criado e publicado pelo app pode ser executado na tela do M6.
- [ ] Editar o rascunho depois de publicar não muda a versão publicada.

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

## E09 — Pessoal e montagem da escala — #pendente

**Objetivo:** o gerente cadastra departamentos, funcionários, turnos e padrões de escala. Ele gera a escala de um período a partir dos padrões e ajusta entradas avulsas. A tela de consulta do M6 mostra o resultado.

**Por que agora:** completa a área de pessoal.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Cadastros:**
  - `/api/v1/departments`, `/employees`, `/shifts` e `/schedule-patterns`;
  - `GET`/`POST`/`PUT` e `deactivate`/`reactivate` onde houver.
- **Escala:** `POST /api/v1/schedule/generate` e `/schedule/entries`.

**Fora de escopo:** folha de pagamento (fora da v1).

**Critérios de aceite:**

- [ ] Gerar a escala da semana a partir de um padrão criado pelo app a mostra na tela de escala (M6).
- [ ] Uma entrada avulsa aparece na data escolhida.

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

## E10 — FNRH e trilha de auditoria — #pendente

**Objetivo:** o administrador cuida da FNRH e consulta a trilha de auditoria:

- edita a configuração da FNRH, e a credencial aparece só como "configurada";
- limpa a credencial, o que desliga a integração;
- define e desativa o CPF solicitante de cada usuário;
- atualiza os domínios oficiais e vê o status da integração;
- acompanha a fila de envio: submissões por situação, com os erros por campo das `INVALID`, e reenvia ou encerra as `FAILED` e `INVALID`;
- consulta a trilha com filtros por período, ator e recurso.

**Por que agora:** fecha a conformidade, que é argumento de venda.

**Dependências:** E05, para escolher o usuário do CPF solicitante.

**Escopo:**

- **FNRH:**
  - `GET`/`PUT /api/v1/fnrh/settings` e `POST /api/v1/fnrh/settings/clear-credentials` (`If-Match`, confirmação explícita);
  - `GET /api/v1/fnrh/requesters`, `PUT /api/v1/fnrh/requesters/{userId}` e `POST /api/v1/fnrh/requesters/{userId}/deactivate`;
  - `GET /api/v1/fnrh/domains/{domain}` e `POST /api/v1/fnrh/domains/refresh`;
  - `GET /api/v1/fnrh/status`;
  - fila de envio: `GET /api/v1/fnrh/submissions?status=…` (cursor, `fnrh.submissions.read`) e `POST /api/v1/fnrh/submissions/{id}/retry` e `/dismiss` (`fnrh.submissions.manage`), com o código da reserva levando ao detalhe dela (M2 E01).
- **Auditoria:** `GET /api/v1/audit-entries`.

**Fora de escopo:**

- homologação e envio real ao Serpro (M5 E09 do servidor, `#bloqueada`);
- o WireMock da FNRH no `zeny-front` (`docker/gate/fnrh-mock` do servidor). Ruling da análise da rodada 2 do M0: não adotar por ora. Esta etapa reavalia, por rodada do M0, se a demo deve mostrar submissões `SENT`.

**Critérios de aceite:**

- [ ] Gravar a configuração sem credencial real mostra o status que o servidor devolver, em pt-BR (H2).
- [ ] A credencial gravada nunca reaparece em claro na tela (teste do *state holder*).
- [ ] A trilha mostra a revelação de documento feita no M5, com os valores mascarados como o servidor manda.
- [ ] Contra o seed, sem credencial, as submissões dos check-ins aparecem `PENDING`, com operação e situação em pt-BR, e um valor desconhecido aparece como "Outro (VALOR)".
- [ ] `retry` e `dismiss` numa submissão que não está `FAILED` nem `INVALID` mostram em pt-BR a recusa do servidor (`FNRH_SUBMISSION_NOT_RETRYABLE` ou `FNRH_SUBMISSION_NOT_DISMISSABLE`). Teste do *state holder* com repositório falso para os casos `FAILED` e `INVALID`.
- [ ] "Limpar credencial" pede confirmação, envia `If-Match` e trata o `412` com recarga e aviso. Depois dela, o status mostra a integração desligada.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** a nota `ZenyHotelManager - Módulo fnrh` traz o saneamento, os domínios e a semântica da desativação do CPF solicitante. As submissões não trazem dado de hóspede além de ids e do código da reserva, e os erros dos dados sensíveis vêm neutros (`person.sensitiveData`, decisão E08 (e) do servidor). A tela orienta a abrir o perfil (M5 E01) e não tenta adivinhar o campo. Ao ligar a integração, sai todo o acumulado (nota da M5 E08 do servidor): a tela de configuração avisa isso antes de gravar a credencial.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Sem credencial (Q-12 do servidor), o status fica sempre "não configurado". Mostrar isso como estado esperado.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E12 — Impressoras, agentes e fila de impressão — #pendente

**Objetivo:** o administrador cadastra os agentes de impressão e as impressoras do hotel e acompanha a fila de jobs:

- ao criar um agente, ou ao trocar o token dele, o token aparece uma única vez, para ser copiado para o agente;
- as telas de documento (ficha da M2 E07, confirmação da M3 E03 e extrato da M4 E02) ganham "Enviar à impressora do hotel", ao lado da impressão local (ADR 0009).

**Por que agora:** completa a impressão pelo modelo central do servidor, que o mobile também usa, e cobre as rotas de impressão do contrato.

**Dependências:** E05, para os papéis e as permissões de impressão.

**Escopo:**

- **Agentes:** `GET`/`POST /api/v1/print-agents`, `GET /api/v1/print-agents/{id}`, `POST …/{id}/deactivate` e `POST …/{id}/rotate-token`.
- **Impressoras:** `GET`/`POST /api/v1/printers`, `GET`/`PUT /api/v1/printers/{id}` e `POST …/{id}/deactivate`, com o tipo (`x-extensible-enum`: `THERMAL_ESCPOS`, `A4_PDF`) em pt-BR.
- **Fila:** `GET /api/v1/print-jobs` (cursor, filtros por situação e impressora) e o `POST /api/v1/print-jobs` manual, se fizer sentido na tela (decidir no plano e registrar no handoff).
- **Envio de documento:** `POST /api/v1/reservations/{id}/registration-card/print`, `/confirmation/print` e `POST /api/v1/folios/{id}/statement/print`. Cada um leva `Idempotency-Key` e uma impressora ativa escolhida de `GET /api/v1/printers`. A ação só aparece com a permissão efetiva e com pelo menos uma impressora ativa.

**Fora de escopo:**

- o agente de impressão e as rotas `/api/v1/print-agents/me/*` (ADR 0009, design §2);
- a impressão local, que já existe desde a M2 E07.

**Critérios de aceite:**

- [ ] Criar um agente mostra o token uma vez, com "copiar". Fechar o diálogo o apaga da tela, e ele não aparece em log, preferências nem disco (teste do *state holder*).
- [ ] Uma impressora cadastrada para o agente aparece ativa. Desativá-la a tira da escolha das telas de documento.
- [ ] "Enviar à impressora do hotel" na ficha de um hospedado cria um job que aparece na fila (H3), e repetir após erro de rede reaproveita a mesma `Idempotency-Key` (teste).
- [ ] `UNKNOWN_PRINTER` e `PRINTER_INACTIVE` aparecem em pt-BR, sem `code` cru.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** sem agente, um job fica `PENDING` para sempre, e a tela deixa isso claro. O README do servidor, seção "Impressão", e a nota `ZenyHotelManager - Módulo printing`, se já existir no brain, trazem os detalhes do agente (long-poll, SSE, confirmação e reenvio). O front não implementa nada disso.

*Nota da M0 E02 (2026-11-13, ruling R4):* o `core` não serve às 5 operações `/api/v1/print-agents/me/*`, que usam o esquema `printAgentToken`. O `AuthPlugin` (`core/.../session/AuthPlugin.kt:34-37`) põe o `Bearer` do usuário em qualquer requisição com `ogenAuthAttr` não vazio, e o `ApiExecutor` (`core/.../http/ApiExecutor.kt:81-87`) transforma o `401` dela em `SessionEnded`: chamar uma dessas rotas pelo `core` encerraria a sessão do usuário. Esta etapa não as chama (as rotas do agente ficam fora do desktop, ADR 0009). Se o plano precisar delas, a correção recomendada é o `AuthPlugin` e o `ApiExecutor` só tratarem como sessão de usuário as requisições cujo requisito se chame `bearerAuth`, falhando fechado. O teste `SdkAttackTest.printAgentOperationsDeclarePrintAgentTokenScheme` caracteriza o atributo.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O token copiado vai parar na área de transferência, fora do controle do app. Copiar só por ação explícita e avisar na tela.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E11 — Gate do marco M7 — #pendente

**Objetivo:** o M7 entregue como build testável. O roteiro do M7 mostra a configuração feita só pelo app. Há uma tabela de cobertura do contrato fixado, com cada operação ligada a uma tela ou marcada como do mobile ou do agente de impressão. A definição de pronto é verificada num clone limpo e no Windows.

**Por que agora:** fecha a cobertura do servidor fixado.

**Dependências:** E03, E04, E06, E07, E08, E09, E10, E12.

**Escopo:**

- `docs/demo/roteiro-m7.md` (a criar), acumulando os roteiros anteriores;
- tabela de cobertura do contrato no handoff;
- pacote atualizado;
- verificação num clone limpo.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] Clone limpo: build, teste e lint verdes.
- [ ] O `smokeTest` passa, e o seed roda em menos de 2 minutos.
- [ ] A tabela de cobertura liga cada operação do contrato fixado a uma tela, a "mobile" (`sync`, `push-devices`) ou a "agente de impressão" (`/print-agents/me/*`).
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.

**Validação obrigatória:**

- Comandos oficiais num clone limpo → verdes.
- O `smokeTest` passa.
- O usuário executou o roteiro.

**Revisão humana:** sim. É o gate do marco e fecha a cobertura do servidor fixado.

**Notas para o plano:** nenhuma.

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
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`, servidor `57eb5fac5b8a`). A E10 ganha a fila de envio à FNRH (lista, `retry` e `dismiss`) e a limpeza da credencial. Ficou registrada a ruling de não adotar o WireMock da FNRH no `zeny-front` por ora. Entra a E12 (impressoras, agentes e fila de impressão, com "Enviar à impressora do hotel" nas telas de documento; [ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)), e o gate E11 passa a depender dela e a separar na cobertura as rotas do agente de impressão. O fechamento do dia saiu de "Fora de escopo" e foi para a M6 E08. O marco tem 12 etapas, no limite de referência. Nenhuma tag mudou.
