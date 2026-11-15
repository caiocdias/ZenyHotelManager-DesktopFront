# Roadmap — M2 Recepção do dia

> Arquivo: `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` · branch de execução: `roadmap/m2-recepcao-do-dia` · criado em 2026-11-10 · última revisão 2026-11-13

> **Pré-requisito:** etapa E07 ("Gate do marco M1") de `docs/roadmaps/roadmap-m1-fundacao.md` marcada `#concluida` e etapa E02 ("Sincronizar com o servidor em `57eb5fac5b8a`") de `docs/roadmaps/roadmap-m0-sincronizacao.md` marcada `#concluida` (rodada 2, análise de 2026-11-13). Enquanto valer a regra 7 do `docs/roadmaps/README.md` (validação no Windows acumulada), uma etapa `#em-andamento` aguardando só o Windows também satisfaz o pré-requisito. Nesse caso, o handoff da etapa do M2 registra que a base ainda não foi validada no Windows. Se a `main` do servidor avançou de novo desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m2-recepcao-do-dia`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O recepcionista trabalha o dia no app. Ele vê chegadas, saídas e hospedados da data operacional e abre o detalhe de uma reserva. Faz o check-in, alocando a UH com sugestão e cotando early check-in, e faz walk-in. Abre e imprime a ficha de registro da hospedagem. Também troca a UH de um hóspede, desfaz um check-in com aprovação do gerente e registra e resolve recados. Tudo isso contra o servidor real, com as listas e o mapa de UHs atualizados em tempo real.

## Contexto confirmado

- **Base do M1:** módulos `core`, `design` e `app`, sessão, pipeline HTTP, diálogo de aprovação, SSE, mapa de UHs e `demo-seed`. Os caminhos e comandos reais estão nos handoffs de `docs/roadmaps/roadmap-m1-fundacao.md`.
- **Servidor deste marco:** `57eb5fac5b8a`, fixado pela M0 E02 (o pin anterior, `ca9f9e2f9808`, não tinha a ficha). Pré-voo de 2026-11-13: aditivo (código 11), 303 → 335 operações, nenhuma removida, nenhuma operação existente alterada; mudaram só os schemas `GuestProfileRequest`, `GuestResponse`, `MergeGuestRequest` (`genderDescription`, M5 front) e a descrição de `OperationalSettingsDto.endOfDayTime`.
- **Operações do contrato** (`api/openapi.yaml`, commit fixado em `api/server-pin.properties`):
  - **reservas do dia e detalhe:** `GET /api/v1/reservations/arrivals`, `/departures` e `/in-house`; `GET /api/v1/reservations/{id}`, `/{id}/history`, `/{id}/room-assignments` e `/{id}/room-suggestions`;
  - **alocação:** `POST /api/v1/reservations/{id}/assign-room`, `/unassign-room` e `/do-not-move`;
  - **check-in e estadia:** `GET /api/v1/reservations/{id}/early-late-quote`; `POST /api/v1/reservations/{id}/check-in`, `/undo-check-in` e `/room-moves`; `POST /api/v1/walk-ins`;
  - **recados:** `GET`/`POST /api/v1/traces`, `GET`/`PUT /api/v1/traces/{id}` e `POST /api/v1/traces/{id}/resolve`;
  - **hóspedes:** `GET`/`POST /api/v1/guests` e `POST /api/v1/guests/search-by-document`;
  - **ficha de registro:** `GET /api/v1/reservations/{id}/registration-card/pdf` (`application/pdf`, exige `frontdesk.stays.checkin`; reserva fora de `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`: `409 REGISTRATION_CARD_UNAVAILABLE`).
- **FNRH no servidor fixado** (M5 E08 e E16 dele, `#concluida`; a E09, homologação com o Serpro, está `#bloqueada`):
  - o envio é **assíncrono**: cada evento de reserva e de hospedagem (registro, alteração, confirmação, reativação, cancelamento, check-in, desfazer check-in e check-out) grava uma submissão (`REGISTER_RESERVATION`, `CHECK_IN`, `UNDO_CHECK_IN`…), e um job a envia; o `frontdesk` nunca chama o `fnrh`;
  - o check-in não muda de contrato e conclui com `200` mesmo com a FNRH fora (critério da M5 E08 do servidor);
  - sem credencial configurada, que é o caso do ambiente `zeny-front`, as submissões ficam `PENDING` sem gastar tentativa;
  - desfazer um check-in já enviado vira submissão `FAILED` (`NO_REMOTE_OPERATION`), corrigida no portal e encerrada por `dismiss`; a fila e essas ações são da M7 E10 do front.
- **Regras do servidor:**
  - check-in orquestrado numa transação (ARCHITECTURE §6.1);
  - desfazer check-in é ação sensível, com aprovação de superior;
  - cotação de early/late pela política do `hotel`.

  Detalhes nas notas do brain `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` e no roadmap do servidor `docs/roadmaps/roadmap-m5-recepcao-fnrh.md` (E01 a E06, `#concluida`).
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6.2 e §7; documentos em PDF e impressão: [ADR 0009](../adr/0009-documentos-pdf-e-impressao.md).
- **Second Brain:** o hub `ZenyHotelManager-DesktopFront` e as notas do servidor citadas acima.

## Escopo

**Incluído:**

- painel do dia;
- detalhe da reserva;
- alocação e sugestão de UH;
- check-in com cotação de early;
- walk-in;
- ficha de registro: visualizador de PDF em memória, "Salvar como…" e impressão local (ADR 0009);
- troca de UH;
- desfazer check-in;
- recados;
- busca e cadastro mínimo de hóspede dentro desses fluxos;
- ampliação do seed para os roteiros;
- roteiro do M2.

**Fora de escopo:**

- busca geral e criação de reservas (M3);
- conta, pagamento e check-out (M4);
- perfil completo do hóspede (M5);
- a fila de envio à FNRH (lista, reenvio e encerramento) e a limpeza da credencial (M7 E10). O servidor já envia, de forma assíncrona e fora do check-in; a recepção não muda por isso;
- confirmação da reserva em PDF (M3 E03) e extrato da conta em PDF (M4 E02), que reaproveitam o visualizador da E07;
- impressão pela fila do servidor ("Enviar à impressora do hotel"), que entra com o cadastro de impressoras e agentes (M7 E12, ADR 0009);
- fechamento do dia (M6 E08).

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado;
  - uma `Idempotency-Key` por intenção;
  - `ETag`/`If-Match` com `412` tratado;
  - menu e ações por permissão efetiva, com o servidor decidindo;
  - dinheiro em `BigDecimal`;
  - instantes no fuso do hotel.
- **Uso do `core`:** as features usam só o `core` e o `design` do M1. Ação recusada com `APPROVAL_REQUIRED` passa sempre pelo diálogo de aprovação do M1.
- **Dados pessoais:** documento sempre mascarado. Nenhum dado de hóspede em log nem em cache de disco. O seed usa só dados fictícios.
- **Documentos em PDF (ADR 0009):** o PDF fica só em memória. Nada de arquivo temporário nem de leitor do sistema; o disco só recebe o arquivo por "Salvar como…", ação explícita do usuário. "Imprimir" usa a impressora do sistema operacional a partir da memória.
- **Servidor:** é só leitura, e o commit fixado não muda no marco. Lacuna na API vira `PF-NN`, mas nenhuma etapa até 17/11 depende de pedido.
- **Idioma:** UI e documentação em pt-BR; código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O cadastro mínimo de hóspede exigido pelo check-in cabe num diálogo curto (nome e documento), sem os dados sensíveis da FNRH | O diálogo cresce, ou o check-in pede o perfil completo do M5 | E02, E03 |
| H2 | O seed consegue deixar UHs prontas e alocadas para as chegadas de hoje | O roteiro de check-in precisa de passos manuais extras | E02 |
| H3 | Os tópicos SSE existentes (`rooms` e outros do contrato) bastam para manter as listas do dia em dia; senão, a tela rebusca ao voltar ao foco | Listas desatualizadas durante a demo; avaliar `PF-NN` | E01 |
| H4 | Uma biblioteca de PDF para a JVM (candidata: Apache PDFBox) renderiza em memória a ficha do servidor e a manda para a impressora do sistema, no Linux e no Windows, dentro do JAR multiplataforma e sem estourar a memória do build | Trocar de biblioteca, registrando no handoff; se nenhuma servir, a decisão volta ao usuário (ADR 0009) antes de cair para arquivo temporário | E07 |
| H5 | O Recepcionista do seed tem `frontdesk.stays.checkin`, que abre a ficha (D-E11-10 do servidor) | O botão some para ele; o roteiro mostra a ficha logado como Gerente | E07 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] O `smokeTest` passa contra o ambiente recriado.
- [ ] O roteiro `docs/demo/roteiro-m2.md`, acumulando o do M1, executado pelo usuário no Windows.
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

- Toda execução acontece no worktree/branch `roadmap/m2-recepcao-do-dia`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m2-recepcao-do-dia-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m2-recepcao-do-dia Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m2-recepcao-do-dia.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m2-recepcao-do-dia com superpowers:using-git-worktrees. Não trabalhe na branch principal.
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
| E01 | Painel do dia e detalhe da reserva | #concluida | — | Chegadas, saídas e hospedados da data operacional e o detalhe da reserva com histórico e alocações |
| E02 | Alocação de UH e check-in | #concluida | E01 | Sugestão e alocação de UH, cotação de early e check-in com hóspede buscado ou criado |
| E03 | Walk-in | #concluida | E02 | Reserva, alocação e check-in no mesmo fluxo |
| E04 | Troca de UH e desfazer check-in | #concluida | E02 | Troca de UH do hospedado e desfazer check-in com aprovação de superior |
| E05 | Recados | #concluida | E01 | Recados por setor ligados a reserva ou UH, com resolução |
| E07 | Ficha de registro e visualizador de PDF | #em-andamento | E02 | Ficha da hospedagem em PDF no app, em memória, com "Salvar como…" e impressão local |
| E06 | Gate do marco M2 | #pendente | E03, E04, E05, E07 | Seed ampliado, roteiro do M2 e definição de pronto verificada |

---

## E01 — Painel do dia e detalhe da reserva — #concluida

**Objetivo:** a área Recepção abre num painel com três abas: chegadas, saídas e hospedados da data operacional. Cada aba mostra contagem, busca local e status. Clicar numa reserva abre o detalhe: hóspede titular (documento mascarado), período, tipo e UH alocada, plano, situação, alocações e histórico.

**Por que agora:** é a tela de partida dos fluxos do marco e a primeira que o recepcionista vê.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M1).

**Escopo:**

- **Listas do dia:** `GET /api/v1/reservations/arrivals`, `/departures` e `/in-house`.
- **Detalhe da reserva:** `GET /api/v1/reservations/{id}`, `/history` e `/room-assignments`.
- **Atualização:**
  - ao voltar ao foco;
  - pelo SSE, quando houver tópico aplicável (H3);
  - depois de qualquer ação do marco.
- **Acesso:** item "Recepção" no menu, conforme a permissão de leitura de reservas.

**Fora de escopo:** as ações sobre a reserva (E02 a E04) e a busca geral (M3).

**Critérios de aceite:**

- [x] Contra o seed, as três abas mostram as reservas criadas para a data operacional, com contagem correta.
- [x] O detalhe mostra o histórico em ordem cronológica, com textos em pt-BR, sem UUID e sem campo técnico.
- [x] O documento aparece mascarado.
- [x] Teste do *state holder*: lista vazia, erro e recarga.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com o seed.

**Revisão humana:** não

**Notas para o plano:** o mapeamento área → permissão foi registrado no handoff da M1 E04.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Listas grandes. Usar a tabela com cursor do `design`.

**Evidências e handoff:**

- **Estado (2026-11-14):** `#concluida`. A área Recepção abre o painel do dia na casca (`IMPLEMENTED_AREAS` = Recepção e Governança). A base (M0 E02) ainda não foi validada no Windows (regra 7 do `docs/roadmaps/README.md`).
- **Plano:** [`docs/superpowers/plans/2026-11-13-m2-recepcao-do-dia-e01.md`](../superpowers/plans/2026-11-13-m2-recepcao-do-dia-e01.md), com 10 tarefas, todas de risco alto, executadas em série. A revisão de arquitetura bloqueou nas rodadas 0 e 1 e aprovou com notas na rodada 2.
- **Commits:** `030788b..381eb0f` na branch `roadmap/m2-recepcao-do-dia`, cada um com o trailer `Soul:`. Cada tarefa passou por desenvolvedor, testador e revisão (spec e qualidade), todas aprovadas sem achado Crítico ou Importante:
  - Tasks 1 a 4: `030788b..4c973c3` (lote anterior);
  - Task 5, porta da recepção e adaptador: `a25f89c..5ecd0f4`;
  - Task 6, estado do painel do dia: `5ecd0f4..f7d8224`;
  - Task 7, estado do detalhe: `f7d8224..15acdf7`;
  - Task 8, tela da recepção: `15acdf7..300a2ee`;
  - Task 9, a recepção na casca: `300a2ee..15f5021`. Inclui o ajuste das linhas do menu em `docs/demo/roteiro-m1.md`, que o teste do roteiro exige iguais ao build;
  - Task 10, verificação ao vivo: `15f5021..604ceec`;
  - correção da revisão final: `604ceec..381eb0f`.
- **Revisão final** (`revisor-seguranca`, opus, sobre `2f090cb..604ceec`): "With fixes". Nenhum Crítico; 2 Importantes, corrigidos em `381eb0f` e aprovados na re-revisão:
  - as listas podiam ficar presas no dia anterior depois do fechamento do dia, conforme o despacho da corrotina;
  - a falha ao recarregar o detalhe não aparecia na tela.
- **Verificação mecânica e scanner:**
  - `check_test_integrity.py --base 2f090cb`: os únicos apontamentos são o merge do lote anterior e as 5 asserções trocadas pela Task 9 (mandadas pelo plano, de mesma força), todos justificados pela revisão.
  - `osv-scanner scan source -r --no-ignore .`: 6 lockfiles (131 a 357 pacotes), "No issues found". Nenhuma dependência nova.
- **Critérios verificados:**
  - Contagens: `LiveReceptionCheck.panelMatchesServer`, contra o `zeny-front` com o seed e logado como Recepcionista. Mostrou chegadas=11, saídas=2 e hospedados=7, iguais à leitura direta de todas as páginas pela `ReservationsFacade`.
  - Histórico e documento: `LiveReceptionCheck.detailOfInHouseReservation` deu situação "Hospedada", "UH 103", histórico "Reserva criada" → "Check-in", documento "CPF ***0570" e nenhum UUID. A captura `app/build/live/reception.png` foi inspecionada pelo orquestrador. A inspeção manual foi feita pela captura, não clicando no app.
  - *State holder*: `emptyListShowsZero`, `tabErrorKeepsOthers`, `forbiddenListsShowMessage` e `refreshAfterErrorRecovers` (`DayPanelStateTest`), mais os ataques do testador.
  - Gate em `381eb0f`: `./gradlew build ktlintCheck detekt` BUILD SUCCESSFUL (core 849 testes, design 259, app 714, demo-seed 506, sdk 40; 0 falhas; 21 pulados, as verificações ao vivo sem opt-in); `bash tests/run.sh` 36 arquivos, 0 falhas.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Área Recepção:** continua aparecendo por `frontdesk.stays.checkin`/`checkout`, como na M1 E04, e não por `reservations.reservations.read`, como diz a etapa. A leitura de reservas também está com Comercial/Eventos e Financeiro, que veriam uma Recepção sem nenhuma ação. As listas exigem `reservations.reservations.read`, com `403` tratado.
  - **H3:** o servidor fixado não tem tópico de reservas. O painel se atualiza por `rooms` (agrupado em janelas de 2 s), `end-of-day`, foco da janela, "Atualizar" e `refresh()`. Sem `PF-NN` por ora; se a demo mostrar listas defasadas, avaliar pedir um tópico `reservations`.
  - **Titular:** lido por `GET /api/v1/guests/{id}`, um por hóspede, até 4 em paralelo. Fica só em memória, na sessão: só o nome e o primeiro documento mascarado.
  - **`detail` do servidor:** um `code` fora do catálogo mostra o `detail` do servidor (design §6.2), e o `ApiError.Problem` leva esse texto como mensagem. Se um `detail` vier com dado pessoal, ele vai para a tela e para o `toString`. Recomendado: manter a spec. Alternativa: mostrar o `detail` só na tela.
  - **Falhas no painel e no detalhe:**
    - falha do dia operacional com o dia já conhecido mantém as listas e mostra uma faixa de erro;
    - falha do titular que não seja `403`/`404`, ou do plano que não seja `Problem`, derruba o detalhe com "Tentar de novo";
    - exceção que não é da API dentro de uma página deixa a aba sem erro (não derruba a tela).
- **Notas para a próxima etapa:**
  - **E02 (check-in):** constrói ações sobre o detalhe e o painel. Depois de cada ação, chame `panel.refresh()` e `detail.reload()`. O detalhe já mostra a falha de recarga numa faixa (`detail-error`).
  - **`TableState.refresh()` recolhe a lista à primeira página:** toda rajada de `rooms` faz isso, e o rótulo volta a "(200+)". Não aparece no seed, mas aparece em hotel com mais de 200 linhas numa lista.
  - **Detalhe:** falha de não-API vira a mensagem de rede. Com as alocações falhando e sem alocação no retrato da reserva, o cabeçalho diz "Sem UH" (o ideal seria "—").
  - **Verificação ao vivo:**
    - gere um arquivo 600 fora do repositório com `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN=recepcao` e `ZENY_LIVE_VIEWER_PASSWORD`, a partir de `.server/seed-secrets.env`;
    - rode `(set -a; . <arquivo>; set +a; ZENY_LIVE_RECEPTION=1 ./gradlew :app:test --tests '*LiveReceptionCheck*' --rerun --max-workers=2)`;
    - sem o opt-in, a verificação pula; com ele e sem credencial, falha.
  - **Ondas paralelas:** não funcionam nesta configuração, porque a sessão isolada no worktree da etapa não deixa os subagentes escreverem em outros worktrees. Planeje as tarefas em série.

---

## E02 — Alocação de UH e check-in — #concluida

**Objetivo:** a partir de uma chegada, o recepcionista aloca a UH, conferindo as sugestões do servidor e o status da UH. Pode marcar "não mover" e cota o early check-in quando cabe. Depois confirma ou cria o hóspede e faz o check-in. O mapa de UHs e o painel refletem a mudança.

**Por que agora:** o check-in é o centro da demo da recepção.

**Dependências:** E01.

**Escopo:**

- **Alocação:**
  - sugestões: `GET /api/v1/reservations/{id}/room-suggestions`;
  - ações: `POST /api/v1/reservations/{id}/assign-room`, `/unassign-room` e `/do-not-move`.
- **Cotação:** `GET /api/v1/reservations/{id}/early-late-quote`, mostrando o valor e a política.
- **Hóspede:** busca por nome e por documento (`GET /api/v1/guests` e `POST /api/v1/guests/search-by-document`) e cadastro mínimo (`POST /api/v1/guests`) num diálogo.
- **Check-in:** `POST /api/v1/reservations/{id}/check-in`.
- **Erros de regra** (UH não pronta, documento faltando e outros `code` do contrato): mensagens em pt-BR com o próximo passo sugerido.
- **Aprovação de superior:** quando o servidor pedir.

**Fora de escopo:**

- pré-autorização e caução (M4);
- o perfil completo do hóspede (M5);
- os dados sensíveis da FNRH (M5).

**Critérios de aceite:**

- [x] Contra o seed, uma chegada sem UH recebe a UH sugerida e faz check-in. Ela sai de "chegadas", entra em "hospedados", e a UH muda de status no mapa sem recarregar.
- [x] Check-in com UH não pronta mostra a mensagem do `code` correspondente em pt-BR, sem efeito parcial.
- [x] Teste: repetir a confirmação após erro de rede reaproveita a mesma `Idempotency-Key`, quando a operação exigir a chave.
- [x] O hóspede criado no diálogo aparece no detalhe da reserva com o documento mascarado.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com o seed, em duas janelas: painel e mapa.

**Revisão humana:** não

**Notas para o plano:** a orquestração e as regras do check-in estão na M5 E02 do roadmap do servidor e na nota `ZenyHotelManager - Hospedagem e recepção`. Os `code` de erro possíveis estão no contrato, na operação de check-in.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O check-in exige dados que o cadastro mínimo não tem (H1). Ampliar o diálogo com os campos exigidos e registrar isso no handoff.

**Evidências e handoff:**

- **Estado (2026-11-14):** `#concluida`. A base (M0 E02) ainda não foi validada no Windows (regra 7).
- **Plano:** [`docs/superpowers/plans/2026-11-14-m2-recepcao-do-dia-e02.md`](../superpowers/plans/2026-11-14-m2-recepcao-do-dia-e02.md), com 12 tarefas, todas de risco alto, executadas em série. A revisão de arquitetura bloqueou na rodada 0 (1 Crítico: a `Idempotency-Key` mantida depois de um erro definitivo; 4 Importantes) e aprovou com notas na rodada 1.
- **Commits:** `ed1749f..3274f31` na branch `roadmap/m2-recepcao-do-dia`, cada um com o trailer `Soul:`. Cada tarefa passou por desenvolvedor, testador e revisão. Os vermelhos do testador e os Importantes da revisão foram corrigidos em rodadas:
  - Task 4: horário do early sem os segundos;
  - Task 7: formulário do hóspede limpo e sem dado pessoal depois do cadastro e no abandono;
  - Task 8: não confirma com alocação ou cadastro em voo;
  - Task 11: a aprovação cancelada não refaz o check-in.
- **Revisão final** (`revisor-seguranca`, opus, sobre `ed1749f..f4113f8`): pronto para merge, sem Crítico nem Importante. Os Menores M1 a M4 (UH e hóspede travados durante o check-in, "Fechar" com alocação em voo, taxa ilegível, painel de hóspede) foram corrigidos em `3274f31` e aprovados na re-revisão.
- **Verificação mecânica e scanner:** `check_test_integrity.py --base ed1749f` sem apontamentos. `osv-scanner scan source -r --no-ignore .`: 6 lockfiles (131 a 357 pacotes), "No issues found". Nenhuma dependência nova; o `build.gradle.kts` só ganhou o opt-in `ZENY_LIVE_CHECKIN`.
- **Critérios verificados:**
  - Chegada sem UH: `LiveCheckInCheck.checkInMovesArrivalAndRoom`, contra o `zeny-front`, logado como Recepcionista. A R-2026-000003 recebeu a UH 208, sugerida e pronta, e fez check-in. Deixou de ser chegada confirmada e entrou em "Hospedados", que foi de 7 para 8. A UH 208 ficou "Ocupada" no mapa pelo evento `rooms`, sem recarregar. As capturas `app/build/live/checkin.png` e `checkin-map.png` foram inspecionadas pelo orquestrador; a inspeção em duas janelas foi feita pelas capturas, não clicando no app.
  - UH não pronta: `LiveCheckInCheck.notReadyRoomLeavesNoEffect`. Com a R-2026-000004 numa UH vaga e não pronta, aparece a mensagem de `ROOM_NOT_READY` com o próximo passo. A reserva continua confirmada, a UH continua vaga e é solta no fim. Nos testes, `roomNotReadyShowsNextStep` cobre o mesmo.
  - Idempotência: `retryAfterNetworkErrorReusesKey` e `ruleErrorRenewsKey`, em `CheckInStateTest`, e `checkInRetrySendsSameKey`, em `CheckInFacadeTest`.
  - Hóspede criado no diálogo: na verificação ao vivo, aparece em "Hóspedes da estadia" com o CPF mascarado ("CPF ***8877").
  - Gate em `3274f31`: `./gradlew build ktlintCheck detekt` BUILD SUCCESSFUL (core 921 testes, design 259, app 1048, demo-seed 506, sdk 40; 0 falhas; 23 pulados, as verificações ao vivo sem opt-in); `bash tests/run.sh` 36 arquivos, 0 falhas.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Aba Chegadas:** o servidor lista as chegadas do dia, inclusive as já hospedadas, que aparecem como "Hospedada". A reserva com check-in feito continua em "Chegadas" e entra em "Hospedados". Alternativa: a aba mostrar só as chegadas pendentes. Isso muda a contagem e o comportamento da E01.
  - **Só o hóspede principal no check-in,** com `guestCount` = adultos + crianças. O contrato pede a lista completa da estadia, que vai para a FNRH; os acompanhantes nominais e os menores ficam para o M5.
  - **H1 ampliada:** o cadastro mínimo tem nome, documento, nacionalidade e data de nascimento, porque o check-in os exige (`GUEST_DATA_INCOMPLETE`). Brasileiro adulto precisa do CPF.
  - **Early check-in:** a cobrança é opcional e começa desligada. Só é oferecida antes do fim da janela, contada pelo relógio do desktop; o servidor confere o valor.
  - **`detail` do servidor:** segue a decisão da E01 (code fora do catálogo mostra o `detail`).
- **Notas para a próxima etapa:**
  - **Ambiente:** a verificação ao vivo escreveu no `zeny-front`. A R-2026-000003 está hospedada na UH 208, e há um hóspede fictício a mais. Só resta uma chegada confirmada sem UH (R-2026-000004). Recrie a demo (`down -v`, `up -d`, `demo-seed`) antes de rodar `LiveCheckInCheck` de novo ou o roteiro.
  - **Verificação ao vivo:** o arquivo 600 é o da E01 (`ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN=recepcao`, `ZENY_LIVE_VIEWER_PASSWORD`). Rode `(set -a; . <arquivo>; set +a; ZENY_LIVE_CHECKIN=1 ./gradlew :app:test --tests '*LiveCheckInCheck*' --rerun --max-workers=2)`.
  - **Convenção da `Idempotency-Key`** para a E03 e a E04: a mesma `Intent` só enquanto o resultado é desconhecido (`Network`, `IncompatibleResponse`, `SessionEnded`, `IDEMPOTENCY_KEY_IN_USE`). Qualquer resposta definitiva, ou mudança de entrada, cria outra. O servidor repete a resposta guardada da chave, inclusive o erro.
  - **`FakeReceptionSource`:** não gira a chave na repetição aprovada (`rotateForApproval` é interno ao `core`). Nos testes de aprovação, compare a identidade da `Intent` ou as execuções sem token. Vale extrair um helper de `ApprovableAction` sobre o `ApiExecutor` quando a E04 trouxer outra ação aprovável.
  - **Reaproveitamento:** o diálogo de hóspede (`GuestPickerPanel`/`GuestPickerState`) e a alocação (`RoomAllocationState`) servem ao walk-in. `DateField` e `DocumentField` do `design` ainda não foram reaproveitados no cadastro, que não tem máscara ao digitar.
  - **Ondas paralelas:** continuam inviáveis nesta configuração; planeje em série.

---

## E03 — Walk-in — #concluida

**Objetivo:** um hóspede sem reserva chega ao balcão. O recepcionista informa as datas e o tipo de UH, vê o preço, escolhe a UH, informa ou cria o hóspede e conclui. A reserva nasce já hospedada.

**Por que agora:** completa o ciclo de entrada da recepção com um fluxo que impressiona na demo.

**Dependências:** E02.

**Escopo:**

- **Assistente de walk-in:** `POST /api/v1/walk-ins`, com os dados que o contrato exige. Cotação por `GET /api/v1/quotes` e disponibilidade por `GET /api/v1/availability`, só no que o walk-in precisar.
- **Reaproveitamento:** o diálogo de hóspede da E02.
- **Erros e aprovação:** como na E02.

**Fora de escopo:** a tela geral de disponibilidade e cotação (M3).

**Critérios de aceite:**

- [x] Contra o seed, um walk-in de 2 noites numa UH livre termina com a reserva em "hospedados" e a UH ocupada no mapa.
- [x] Sem disponibilidade, mostra mensagem em pt-BR antes de pedir os dados do hóspede.
- [x] *(Não se aplica: o walk-in do servidor não aceita desconto, H24.)* Desconto acima do limite do Recepcionista abre o diálogo de aprovação, se o walk-in aceitar desconto no contrato.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** a M5 E03 do servidor define o walk-in. Leia o handoff dela no roadmap do servidor.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além dos da E02.

**Evidências e handoff:**

- **Estado (2026-11-14):** `#concluida`. A base (M0 E02) ainda não foi validada no Windows (regra 7).
- **Plano:** [`docs/superpowers/plans/2026-11-14-m2-recepcao-do-dia-e03.md`](../superpowers/plans/2026-11-14-m2-recepcao-do-dia-e03.md), com 7 tarefas, todas de risco alto, executadas em série. A revisão de arquitetura bloqueou na rodada 0 e aprovou com notas na rodada 1. Os 4 Importantes da rodada 0 foram:
  - as UHs já alocadas às chegadas do dia entravam na lista;
  - o código mostrado era o da reserva agrupadora;
  - a cotação não aceita day use;
  - havia risco de walk-in duplicado depois de um resultado desconhecido.
- **Commits:** `b4591ac..3f29888` na branch `roadmap/m2-recepcao-do-dia`, cada um com o trailer `Soul:`. Cada tarefa passou por desenvolvedor, testador e revisão; rodadas de correção:
  - Task 4: UHs na ordem natural do mapa; segmento e oferta fechados;
  - Task 5: um 5xx trava o walk-in como resultado desconhecido.
- **Revisão final** (`revisor-seguranca`, opus, sobre `b4591ac..1ad965a`): pronto para merge, sem Crítico nem Importante. Três Menores foram corrigidos em `3f29888` e aprovados na re-revisão:
  - texto próprio para o 500 guardado pelo servidor;
  - cadastro e busca de hóspede barrados pela trava no estado;
  - asserção de lista não vazia na verificação ao vivo.
- **Verificação mecânica e scanner:** `check_test_integrity.py --base b4591ac`: o único apontamento é a asserção que o orquestrador mandou acrescentar num teste do testador, justificada na re-revisão. `osv-scanner`: 6 lockfiles, "No issues found". Nenhuma dependência nova; o build só ganhou o opt-in `ZENY_LIVE_WALKIN`.
- **Critérios verificados:**
  - Walk-in de 2 noites: `LiveWalkInCheck.twoNightWalkInGoesInHouse`, contra o `zeny-front`, logado como Recepcionista. Gerou R-2026-000013 na UH 202, de 13/11 a 15/11, por R$ 560,00. Aparece em "Hospedados" como "Hospedada" (9 → 10), e a UH ficou ocupada no mapa pelo evento `rooms`. A captura `app/build/live/walkin.png` foi inspecionada pelo orquestrador; a inspeção manual foi feita pela captura.
  - Sem disponibilidade: `noOfferShowsMessage`, com 40 adultos (sem oferta por ocupação). A mensagem aparece antes do passo do hóspede. Os testes de estado e de tela cobrem o mesmo caminho.
  - Desconto: não se aplica. O `WalkInRequest` não tem desconto nem diária editada (H24 da M5 E03 do servidor). O diálogo de aprovação continua ligado, e os testes da casca provam que a aprovação aprovada conclui e que a cancelada não refaz a ação.
  - Gate em `3f29888`: `./gradlew build ktlintCheck detekt` BUILD SUCCESSFUL (core 968 testes, design 259, app 1231, demo-seed 506, sdk 40; 0 falhas; 26 pulados, as verificações ao vivo sem opt-in); `bash tests/run.sh` 36 arquivos, 0 falhas.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **5xx no walk-in:** conta como resultado desconhecido, com a mesma chave e as entradas travadas, porque o walk-in cria reserva e o servidor guarda o 500 "porque o efeito pode ter acontecido". Um 500 guardado deixa o diálogo travado até fechar e conferir "Hospedados".
  - **Fechar com "não confirmado":** descarta a chave. Reabrir cria outra, e um segundo "Concluir" pode duplicar se o primeiro passou; a tela orienta a conferir "Hospedados" antes de refazer. Alternativa: guardar o estado não confirmado ao fechar.
  - **Day use fora do walk-in no M2:** a cotação só aceita pernoite.
  - **UH alocada a uma chegada pendente:** fica fora da lista do walk-in. Ceder essa UH é fluxo do M3 ou da troca de UH.
  - **Sessão encerrada no walk-in:** mostra o texto da trava. Se a sessão trocar com o POST em voo, o diálogo some sem aviso.
  - **Recusas de venda (`refusals`):** não são lidas; aparece o `detail` do servidor (design §6.2).
  - **Fora do walk-in:** early, caução e pedidos especiais (M4) e acompanhantes nominais (M5).
- **Notas para a próxima etapa:**
  - **Ambiente:** o `zeny-front` está alterado. Há R-2026-000012 (UH 201) e R-2026-000013 (UH 202) de walk-in, R-2026-000003 hospedada na 208 (E02) e três hóspedes fictícios. Recrie a demo antes do roteiro ou de outra verificação que escreve.
  - **Verificação ao vivo:** `(set -a; . <arquivo 600>; set +a; ZENY_LIVE_WALKIN=1 ./gradlew :app:test --tests '*LiveWalkInCheck*' --rerun --max-workers=2)`. Cada execução de `twoNightWalkInGoesInHouse` cria um walk-in.
  - **Aprovação na casca:** a `ReceptionArea` tem uma função genérica de aprovação, com fallback só sem coordenador; a E04 (desfazer check-in) a reaproveita. O falso não gira a chave na repetição aprovada.
  - **`GuestPickerState`:** ganhou `isLocked`, com padrão falso.
  - **`NaturalOrder` do mapa:** ficou `internal` e serve a outras listas de UH.

---

## E04 — Troca de UH e desfazer check-in — #concluida

**Objetivo:** num hospedado, o recepcionista troca a UH, escolhendo a nova e o motivo. Também desfaz um check-in feito por engano, o que exige a aprovação do gerente no próprio balcão.

**Por que agora:** mostra o tratamento de exceções e a aprovação de superior, que é diferencial do produto.

**Dependências:** E02.

**Escopo:**

- **Troca de UH:** `POST /api/v1/reservations/{id}/room-moves`.
- **Desfazer check-in:** `POST /api/v1/reservations/{id}/undo-check-in`, via o diálogo de aprovação do M1 quando vier `APPROVAL_REQUIRED`.
- **Reflexo:** status das duas UHs atualizado no mapa.

**Fora de escopo:** extensão e redução da estadia, se não houver operação própria no contrato. Se houver, registrar no handoff como candidata a etapa nova do M3.

**Critérios de aceite:**

- [x] Troca de UH contra o seed: a UH antiga e a nova mudam de status no mapa, e o histórico da reserva registra a troca.
- [x] Desfazer check-in como Recepcionista → diálogo de aprovação. Com login e PIN do Gerente do seed, a ação conclui. Com PIN errado, aparece mensagem em pt-BR e nada muda.
- [x] *(Verificado pela D2 do ADR 0007: a repetição aprovada leva o `X-Approval-Token` e outra chave; o desfazer não tem chave. O texto está desatualizado; ver "Decisões para o usuário".)* Teste: a repetição após a aprovação usa a mesma `Idempotency-Key` e o `X-Approval-Token`.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o desfazer check-in tem efeito na FNRH, e quem cuida dele é o servidor: grava a submissão `UNDO_CHECK_IN`, que vira `FAILED` (`NO_REMOTE_OPERATION`) se o check-in já tinha sido enviado. A tela não mostra nada disso e não chama o `fnrh`. A fila é da M7 E10. No `zeny-front`, sem credencial, tudo fica `PENDING`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O PIN do Gerente não foi definido pelo seed. Conferir na M1 E05 e corrigir o seed nesta etapa.

**Evidências e handoff:**

- **Estado (2026-11-14):** `#concluida`. A base (M0 E02) ainda não foi validada no Windows (regra 7).
- **Plano:** [`docs/superpowers/plans/2026-11-14-m2-recepcao-do-dia-e04.md`](../superpowers/plans/2026-11-14-m2-recepcao-do-dia-e04.md), com 7 tarefas, todas de risco alto, executadas em série. A revisão de arquitetura (opus) bloqueou na rodada 0 e aprovou com notas na rodada 1. Os 4 Importantes da rodada 0 foram:
  - uma mudança desnecessária no `ApiExecutor` (o SDK já declara o `xApprovalToken`);
  - textos do check-in reaproveitados errados na troca e no desfazer;
  - o período da lista de UHs sem noite restante e no day use;
  - o `412` relia só o `ETag`, e o `etag` não estava na tupla da chave.
- **Commits:** `bbfa8d9..0c81152` na branch `roadmap/m2-recepcao-do-dia`, cada um com o trailer `Soul:`. Cada tarefa passou por desenvolvedor, testador e revisão; rodadas de correção:
  - Task 4 (estado da troca): aprovação cancelada preserva o aviso anterior, `canConfirm` falso depois de fechar, a UH atual limpa quando a recarga falha e a ordem do texto do `412` com o erro da recarga;
  - Task 5 (estado do desfazer): o resultado tardio depois de fechar é descartado (teste vermelho do testador);
  - Task 6 (diálogos): o diálogo aberto sobrevive à recomposição da área (teste vermelho do testador).
- **Revisão final** (`revisor-seguranca`, opus, sobre `bbfa8d9..89116f8`): pronto com correções menores, sem Crítico nem Importante. Três Menores foram corrigidos em `1b57277` (desenvolvedor) e `0c81152` (testador) e aprovados na re-revisão:
  - a alocação encerrada de zero noites (troca no dia do check-in) aparecia como "day use" no detalhe; agora o rótulo só vale para reserva day use;
  - o texto de "estadia sem noites" deixou de ser duplicado no estado da troca;
  - uma asserção trivial no teste de ataque da porta passou a afirmar a UH.
- **Verificação mecânica e scanner:** `check_test_integrity.py --base bbfa8d9`: nenhum apontamento. `osv-scanner`: 6 lockfiles (131 a 357 pacotes), "No issues found". Nenhuma dependência nova; o build só ganhou o opt-in `ZENY_LIVE_STAY`.
- **Critérios verificados:**
  - Troca de UH: `LiveStayActionsCheck.roomMoveChangesBothRoomsAndHistory`, contra o `zeny-front`, logado como Recepcionista. A R-2026-000005 foi da UH 103 para a 203 (motivo "Pedido do hóspede", tarifa mantida). Pelo evento `rooms`, a 103 ficou vaga e suja e a 203 ocupada; o histórico do detalhe registra "Troca de UH". A captura `app/build/live/room-move.png` foi inspecionada pelo orquestrador ("Troca feita para a UH 203. Noites restantes: R$ 320,00 → R$ 320,00 (diferença R$ 0,00).").
  - Desfazer check-in: `undoCheckInNeedsManagerApproval`, como Recepcionista, na R-2026-000003 (check-in na data operacional). O servidor pediu aprovação; uma tentativa com PIN errado mostrou a mensagem pt-BR do coordenador e a reserva continuou hospedada; com login e PIN do Gerente do seed, o check-in foi desfeito, a reserva voltou a "Confirmada" e a UH 208 deixou de estar ocupada. Captura `app/build/live/undo-check-in.png` inspecionada ("Check-in desfeito.").
  - Repetição aprovada: verificada pela D2 do ADR 0007 (ver decisões). `ReservationsFacade`/`ApiExecutor`: a repetição aprovada da troca leva o `X-Approval-Token` e outra `Idempotency-Key`; o desfazer leva só o token, sem chave (o contrato não a declara). Testes: `RoomMoveFacadeTest`, `UndoCheckInFacadeTest` (`undoNeverSendsIdempotencyKey`, `approvedUndoCarriesToken`) e os ataques da porta.
  - Gate em `0c81152`: `./gradlew build ktlintCheck detekt --rerun-tasks` BUILD SUCCESSFUL, 85 tarefas executadas (core 1014 testes, design 259, app 1430, demo-seed 506, sdk 40; 0 falhas; 28 pulados, as verificações ao vivo sem opt-in); `bash tests/run.sh` 37 arquivos, 0 falhas.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Critério 3 pela D2:** o texto do critério ("a mesma `Idempotency-Key`") ficou desatualizado pela D2 do ADR 0007, que o usuário aceitou: a repetição aprovada gira a chave e leva o token, e o desfazer não tem chave. Proposta: corrigir o texto do critério.
  - **Rótulo de day use:** a alocação de zero noites só é rotulada "day use" quando a reserva é day use (o servidor marca `dayUse` em toda alocação com início igual ao fim). Corrigido no detalhe da E02.
  - **Desfazer com sessão encerrada no meio:** mostra o texto da sessão e não recarrega o detalhe; com a sessão encerrada a recarga falharia, e o novo login refaz as telas.
  - **Resultado tardio depois de fechar:** um desfazer ou uma troca que conclui depois de o diálogo ser fechado não atualiza o painel na hora; o detalhe se atualiza na próxima carga.
  - **Desfazer depois de falha de rede na repetição aprovada:** a nova tentativa pede aprovação de novo, porque o token não é guardado.
  - **Fica para depois (Menores da revisão final):** o diálogo da troca não tem "Tentar de novo" depois de uma falha de carga (fechar e reabrir resolve); "Desfazer" continua habilitado depois de "a reserva não está mais hospedada" (repetir só devolve a mesma mensagem).
- **Notas para a próxima etapa:**
  - **Extensão e redução da estadia:** sem operação própria no contrato (é a alteração da reserva). Candidata a etapa nova do M3.
  - **Troca só no mesmo tipo:** a troca para outro tipo ainda não é aceita pelo servidor (`ROOM_TYPE_MISMATCH`). A tarefa de limpeza da UH de origem é da governança (M6). No desfazer, a caução e o early ficam na conta (liberar e estornar é do `billing`, M4); a fila da FNRH é da M7 E10.
  - **Risco do PIN do Gerente:** não se confirmou; o seed define `ZENY_SEED_MANAGER_PIN` e o login `gerente`.
  - **Ambiente:** o `zeny-front` está alterado. Além do que a E03 deixou, a R-2026-000005 está na UH 203 (a 103 ficou suja) e a R-2026-000003 voltou a "Confirmada" na 208; uma tentativa de PIN errado do `gerente` foi gasta. Recrie a demo antes do roteiro ou de outra verificação que escreve.
  - **Verificação ao vivo:** `(set -a; . <arquivo 600>; set +a; ZENY_LIVE_STAY=1 ./gradlew :app:test --tests '*LiveStayActionsCheck*' --rerun --max-workers=2)`, com `ZENY_LIVE_APPROVER_LOGIN` e `ZENY_LIVE_APPROVER_PIN` (numérico). Cada execução troca uma UH, desfaz um check-in e gasta uma tentativa de PIN errado; sem candidata, falha com as contagens.
  - **Estados novos:** `RoomMoveState` e `UndoCheckInState` seguem a convenção dos estados (escopo próprio, cancelamento, travas no estado); `ReceptionScreen` ganhou `roomMove` e `undoCheckIn` com padrão nulo.

---

## E05 — Recados — #concluida

**Objetivo:** recados (traces) por setor, ligados a uma reserva ou UH. O recepcionista cria, edita e resolve, vê os pendentes do dia e o detalhe da reserva mostra os recados dela.

**Por que agora:** fecha o conjunto da recepção do servidor (M5 E06 dele) com pouco esforço.

**Dependências:** E01.

**Escopo:**

- **Operações:** `GET`/`POST /api/v1/traces`, `GET`/`PUT /api/v1/traces/{id}` e `POST /api/v1/traces/{id}/resolve`.
- **Telas:**
  - lista com filtros por setor e situação;
  - formulário;
  - seção de recados no detalhe da reserva.

**Fora de escopo:** notificações (M6).

**Critérios de aceite:**

- [x] Criar um recado para uma reserva do seed → aparece na lista e no detalhe da reserva.
- [x] Resolver → muda de situação, e a lista de pendentes atualiza.
- [x] Edição concorrente → `412` tratado com recarga e aviso.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- **Estado (2026-11-15):** `#concluida`. A base (M0 E02) ainda não foi validada no Windows (regra 7).
- **Pré-voo do lote:** aditivo (código 11). A `main` do servidor está em `3eeef989afb6`; só dois enums extensíveis ganharam valores (`NO_SHOW` no tipo de submissão da FNRH e `RESERVATION_NO_SHOW` no motivo de encerramento da alocação). O pin continua `57eb5fac5b8a`.
- **Plano:** [`docs/superpowers/plans/2026-11-14-m2-recepcao-do-dia-e05.md`](../superpowers/plans/2026-11-14-m2-recepcao-do-dia-e05.md), com 9 tarefas executadas em série (7 de risco alto, 2 normais). A revisão de arquitetura (opus) bloqueou nas rodadas 0 e 1 e aprovou com notas na rodada 2. Os achados foram: a validação do vencimento na edição, a divisão da tarefa do seed, o risco da porta, o nome da classe `Apis` e o alcance real da concessão de setores.
- **Commits:** `df4fedb..20f9f5a` na branch `roadmap/m2-recepcao-do-dia`. Os commits das souls levam o trailer `Soul:`; o `df4fedb` (tag do roadmap, do orquestrador) saiu com o trailer fora do último parágrafo.
  - Task 1, `TracesFacade` no `core`: `a354676..ff1b8ed`;
  - Task 2, porta `TracesSource`, adaptador e `TraceText`: `ff1b8ed..4febb5a`;
  - Task 3, `TracesListState`: `4febb5a..94cd23b`;
  - Task 4, `TraceFormState` (criação, edição e `412`): `94cd23b..9dc978a`;
  - Task 5, telas (`TracesPanel`, `TraceFormDialog`): `9dc978a..a90d88f`, com uma rodada de correção (vínculo atual na edição);
  - Task 6, recados na recepção (aba "Recados" e seção do detalhe): `a90d88f..674ed30`, com duas rodadas (resolver atualiza a outra lista; a seção virou cartões, porque as ações da tabela ficavam inalcançáveis em 380dp);
  - Task 7, `ensureRole` no gateway do seed: `674ed30..453569e`;
  - Task 8, role de consulta de setores da recepção da demo: `453569e..9d0dff3`;
  - Task 9, verificação ao vivo: `9d0dff3..d8d0d17`;
  - testador de etapa e correções: `d8d0d17..b67448b` (a faixa de erro de ação não sumia no "Tentar de novo"; o "Carregar mais" da seção recomeçava da primeira página);
  - onda de correção da revisão final: `20f9f5a`.
- **Revisão final** (`revisor-seguranca`, opus, sobre `a7ff653..b67448b`): "com correções", sem Crítico. Os 2 Importantes de código e 2 Menores foram corrigidos em `20f9f5a` e aprovados na re-revisão:
  - a lista não se atualizava depois de um resultado incerto na criação ou de um `409` na edição;
  - abrir a aba "Recados" não a atualizava;
  - a coluna de ações cortava "Editar" (agora 230dp nos recados, com `actionsWidth` opcional na `DataTable`);
  - o seletor de setor podia vir vazio na edição.
  O terceiro Importante era o registro do PF-01, feito neste fechamento.
- **Verificação mecânica e scanner:** `check_test_integrity.py --base a7ff653` com 5 apontamentos, todos justificados pela revisão: o trailer do `df4fedb`; as extensões dos fakes (`FakeTracesSource`, `FakeIam`); a troca de assinatura do formulário num teste do testador; e as duas asserções de conjunto exato da recepcionista, que o plano mandou trocar (continuam conjunto exato, agora com a role de demo). `osv-scanner scan source -r --no-ignore .`: 6 lockfiles (131 a 357 pacotes), "No issues found". Nenhuma dependência nova.
- **Critérios verificados** (`LiveTracesCheck`, opt-in `ZENY_LIVE_TRACES`, contra o `zeny-front` com o seed rodado de novo, logado como Recepcionista; 3 testes, 0 falhas, em `d8d0d17` e em `20f9f5a`):
  - Criar: `createdTraceAppearsInListAndDetail`. Um recado para a R-2026-000005, setor Governança, vencimento na data operacional, aparece na aba (abertos) e na seção "Recados" do detalhe. A captura `app/build/live/traces.png` foi inspecionada pelo orquestrador: aba Recados com os filtros e o recado aberto com "Resolver" e "Editar".
  - Resolver: `resolvingUpdatesPendingList`. O recado sai dos abertos, aparece em "Resolvidos" e o servidor confirma `RESOLVED`.
  - Edição concorrente: `concurrentEditShowsReloadAndNotice`. O segundo formulário recebe o `412`, mostra "Alguém alterou este recado. Confira e salve de novo.", relê o texto do primeiro e salva na segunda confirmação com o `ETag` novo.
  - Gate em `20f9f5a`: `./gradlew build --max-workers=2` BUILD SUCCESSFUL (core 1051 testes, design 262, app 1632, demo-seed 554, sdk 40; 0 falhas; 31 pulados, as verificações ao vivo sem opt-in); `bash tests/run.sh` 38 arquivos, 0 falhas.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Setores dos recados (segurança e dados):** a Recepcionista do modelo padrão cria recados, mas não lê os setores (`GET /api/v1/departments` exige `staff.employees.read`). O seed dá ao usuário `recepcao` uma role extra "Consulta de setores (demo)" com só `staff.employees.read`, sem mexer na role do modelo. **Alcance real:** pela API, essa Recepcionista também lê colaboradores (nome, e-mail, telefone) e turnos; o app não mostra nada disso. É um desvio do design §7.2 (os usuários da demo deixam de ter só os modelos do servidor). O app trata o `403`: some o filtro por setor e "Novo recado" mostra o texto da permissão. Alternativa: não conceder e demonstrar a criação como Gerente (a reversão é tirar a role do seed). Pedido **PF-01** aberto ao servidor.
  - **Seção do detalhe em cartões:** a tabela de 6 colunas não cabia nos 380dp do detalhe e as ações ficavam inalcançáveis; a seção mostra um cartão por recado, com as ações abaixo.
  - **Criação sem resultado confirmado:** o servidor não é idempotente na criação de recado. Uma falha de rede não é repetida sozinha: o formulário avisa "Não foi possível confirmar se o recado foi criado. Confira a lista antes de tentar de novo.", atualiza a lista e fica destravado para um novo clique (diferente do walk-in, que trava). Um recado duplicado custa pouco e se resolve na lista.
  - **Responsável:** o formulário não escolhe responsável (exigiria ler colaboradores); mostra o nome quando houver e devolve o mesmo na edição, para o servidor não apagá-lo.
  - **Fica para depois** (Menores triados pela revisão final como "pode esperar"): a situação aparece como texto na tabela (sem o chip colorido); um `412` na edição relê o formulário mas só atualiza a lista na próxima atualização; cada reserva aberta relê permissões e setores; `ensureRole` não compara limites das concessões nem exclui roles de sistema na busca por nome.
- **Notas para a próxima etapa:**
  - **PF-01:** captura `ZenyHotelManager - pedido do front PF-01 Setores dos recados legíveis com frontdesk.traces.manage` na Inbox do brain (2026-11-15). Quando o servidor atender, tire a role extra do seed.
  - **E06 (gate):** o roteiro do M2 deve avisar que uma Recepcionista com o modelo padrão de role vê a mensagem de falta de permissão em "Novo recado"; o seed já tem a linha `perfis` no relatório (o `roteiro-m1.md` foi atualizado). O seed ainda não cria recados de exemplo (escopo da E06).
  - **Estados novos:** `TracesListState` (aba e seção; `onResolved` liga as duas) e `TraceFormState` seguem a convenção dos estados (escopo próprio, travas no estado, resultado tardio descartado, `toString` sem o texto do recado). O pacote é `com.zeny.desktop.app.traces`, sem depender de `app.reception`, para a Governança reaproveitar no M6.
  - **`DataTable`:** ganhou `rowActions` e `actionsWidth` opcionais.
  - **Ambiente:** o `zeny-front` tem recados criados pela verificação ao vivo (todos resolvidos no fim de cada execução) e a role nova. Recrie a demo antes do roteiro.
  - **Verificação ao vivo:** `(set -a; . <arquivo 600>; set +a; ZENY_LIVE_TRACES=1 ./gradlew :app:test --tests '*LiveTracesCheck*' --rerun --max-workers=2)`, com `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN=recepcao` e `ZENY_LIVE_VIEWER_PASSWORD`; rode o seed antes, para a role existir.

---

## E07 — Ficha de registro e visualizador de PDF — #em-andamento

**Objetivo:** depois do check-in, ou no detalhe de uma reserva confirmada, hospedada ou encerrada, o recepcionista abre a ficha de registro da hospedagem dentro do app. Ele a confere na tela, salva o arquivo onde escolher ou a imprime na impressora do computador. O PDF nunca toca o disco sem a ação dele.

**Por que agora:** fecha a história do check-in com o documento que o gerente conhece em papel. Também cria o visualizador que a confirmação (M3 E03) e o extrato (M4 E02) reaproveitam (ADR 0009).

**Dependências:** E02.

**Escopo:**

- **Download:** `GET /api/v1/reservations/{id}/registration-card/pdf`, por uma fachada do `core` que devolve os bytes do PDF ou o problema RFC 9457 mapeado. As features não tocam no `sdk` (ADR 0008).
- **Visualizador**, componente reaproveitável no `design`:
  - páginas renderizadas em memória;
  - navegação entre páginas e zoom;
  - "Salvar como…", com o nome sugerido pelo servidor (`ficha-<código>.pdf`);
  - "Imprimir", pelo diálogo de impressão do sistema operacional, a partir da memória.
- **Acesso:**
  - ação "Ficha de registro" no detalhe da reserva (E01), só com `frontdesk.stays.checkin` e nas situações `CONFIRMED`, `IN_HOUSE` e `CHECKED_OUT`;
  - atalho logo após o check-in (E02).
- **Erros:** `409 REGISTRATION_CARD_UNAVAILABLE`, `404 RESERVATION_NOT_FOUND` e falha de rede, em pt-BR, sem abrir o visualizador vazio.
- **Dependência nova:** a biblioteca de PDF (H4), no catálogo e nos lockfiles do mesmo commit.

**Fora de escopo:**

- confirmação da reserva (M3 E03) e extrato (M4 E02);
- "Enviar à impressora do hotel" pela fila do servidor (M7 E12);
- corrigir o conteúdo da ficha: os rótulos de motivo de viagem e de meio de transporte saem como "(código FNRH)" até a M6 E15 do servidor.

**Critérios de aceite:**

- [ ] Contra o seed, a ficha de um hospedado abre no app com o código da reserva, a UH e o nome do hóspede, e o documento aparece mascarado como o servidor manda.
- [ ] Teste: abrir, navegar e fechar o visualizador não cria arquivo nenhum. A pasta temporária da JVM e a pasta do usuário são comparadas antes e depois, com o diretório temporário redirecionado no teste.
- [ ] "Salvar como…" grava exatamente os bytes recebidos (hash igual ao da resposta) no destino escolhido. Cancelar o diálogo não grava nada.
- [ ] "Imprimir" abre o diálogo de impressão do sistema, e cancelar não deixa efeito. Uma impressão real (ou para PDF, pela impressora virtual do Windows) é conferida pelo usuário no gate (E06).
- [ ] Reserva cancelada ou sem ficha: o botão não aparece. Se o servidor recusar, a mensagem sai em pt-BR, sem `code` cru.
- [ ] O `smokeTest` baixa a ficha de um hospedado do seed e confere que a resposta começa com `%PDF-`.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- `./gradlew resolveAndLockAll --write-locks` no commit da dependência nova, sem lockfile pendente.
- `./gradlew :app:desktopJar` e abertura da ficha pelo JAR, para conferir que a biblioteca vai dentro do JAR multiplataforma.
- Inspeção manual contra `zeny-front` com o seed.

**Revisão humana:** não. A convenção, a dependência e a regra de disco foram fixadas pelo ADR 0009, e o gate (E06) mostra a ficha ao usuário.

**Notas para o plano:**

- A ficha é gerada na hora e o servidor não a guarda em cache. Cada abertura é um `GET` novo, sem trilha no servidor (D-E11-10 dele).
- `java.awt.Desktop.open` e `File.createTempFile` violam o ADR 0009. A impressão vai da memória para o `PrinterJob`, sem arquivo intermediário.
- O teste de "nenhum arquivo criado" precisa redirecionar `java.io.tmpdir`, porque bibliotecas de PDF costumam usar cache de fontes em disco. Cache de fonte não tem dado de hóspede, mas registre no handoff o que a biblioteca grava.
- Ler o handoff da M1 E01 sobre o tamanho do JAR multiplataforma e a memória do build antes de escolher a biblioteca (H4).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A biblioteca de PDF aumenta o JAR ou o tempo de build. Medir antes e depois no handoff.
- A impressão real no Windows não pode ser testada no notebook. Fica para o roteiro do gate, com a impressora "Microsoft Print to PDF" como alternativa.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Gate do marco M2 — #pendente

**Objetivo:** o M2 entregue como build testável. O seed tem dados para todos os fluxos do marco, o roteiro do M2 existe e a definição global de pronto foi verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e passa a ser a build de demo.

**Dependências:** E03, E04, E05, E07.

**Escopo:**

- **Seed ampliado:** chegadas sem UH, um hospedado para troca e recados de exemplo.
- **Roteiro:** `docs/demo/roteiro-m2.md` (a criar), que acumula o do M1 e conta "um dia na recepção": painel, check-in com a ficha de registro aberta e impressa, walk-in, troca de UH, desfazer check-in com aprovação do gerente e recados.
- **Pacote:** atualizado.
- **Verificação:** num clone limpo.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] Clone limpo: build, teste e lint verdes.
- [ ] O `smokeTest` passa e o seed roda em menos de 2 minutos.
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.

**Validação obrigatória:**

- Comandos oficiais num clone limpo → verdes.
- `smokeTest` → verde.
- Roteiro executado pelo usuário.

**Revisão humana:** sim. Gate do marco e nova build de demo.

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O seed passa de 2 minutos. Paralelizar as criações independentes.

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
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`), contra a `main` do servidor em `57eb5fac5b8a`. O marco passa a exigir a M0 E02 concluída. O contexto foi corrigido: a FNRH já é enviada pelo servidor, de forma assíncrona e sem mudar o check-in, e sua fila vai para a M7 E10. Entra a E07 (ficha de registro e visualizador de PDF), por decisão do usuário em entrevista ([ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)), e o gate E06 passa a depender dela. Confirmação, extrato, impressão pela fila do servidor e fechamento do dia ficam fora de escopo, com destino. H4 e H5 novas. Nenhuma tag mudou.
- 2026-11-13 — E01 iniciada (`claude --agent orquestrador`). Pré-voo: igual (código 0, `57eb5fac5b8a`). O plano foi aprovado com notas na rodada 2 da revisão de arquitetura. As Tasks 1 a 4 (fachadas do `core`, textos da recepção e `TableState.refresh()`) foram concluídas e revisadas. O lote parou a pedido do usuário, com a E01 `#em-andamento` e a retomada pelo ledger da etapa. H3: não há tópico de reservas no servidor fixado.
- 2026-11-14 — E01 `#concluida` (`claude --agent orquestrador`). Pré-voo: igual (código 0, `57eb5fac5b8a`). As Tasks 5 a 10 foram retomadas pelo ledger; a revisão final pediu duas correções, feitas e aprovadas na re-revisão.
- 2026-11-14 — E02 `#concluida` (`claude --agent orquestrador`). A revisão de arquitetura aprovou com notas na rodada 1 (rodada 0 bloqueada pela regra da `Idempotency-Key`). A verificação ao vivo fez check-in de uma chegada do seed. Recriar a demo antes do roteiro.
- 2026-11-14 — E03 `#concluida` (`claude --agent orquestrador`). O critério de desconto não se aplica (H24 do servidor). A verificação ao vivo criou dois walk-ins; recriar a demo antes do roteiro.
- 2026-11-14 — E04 `#concluida` (`claude --agent orquestrador`). O critério 3 foi verificado pela D2 do ADR 0007 (texto a corrigir). A verificação ao vivo trocou a UH da R-2026-000005 e desfez o check-in da R-2026-000003; recriar a demo antes do roteiro.
- 2026-11-15 — E05 `#concluida` (`claude --agent orquestrador`). Pré-voo: aditivo (código 11, servidor em `3eeef989afb6`, só enums extensíveis), pin mantido. Recados com aba e seção no detalhe; a Recepcionista da demo ganhou a consulta de setores pelo seed, e o PF-01 foi aberto. Recriar a demo antes do roteiro.
