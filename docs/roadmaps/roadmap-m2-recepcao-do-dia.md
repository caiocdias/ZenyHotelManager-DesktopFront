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
| E01 | Painel do dia e detalhe da reserva | #em-andamento | — | Chegadas, saídas e hospedados da data operacional e o detalhe da reserva com histórico e alocações |
| E02 | Alocação de UH e check-in | #pendente | E01 | Sugestão e alocação de UH, cotação de early e check-in com hóspede buscado ou criado |
| E03 | Walk-in | #pendente | E02 | Reserva, alocação e check-in no mesmo fluxo |
| E04 | Troca de UH e desfazer check-in | #pendente | E02 | Troca de UH do hospedado e desfazer check-in com aprovação de superior |
| E05 | Recados | #pendente | E01 | Recados por setor ligados a reserva ou UH, com resolução |
| E07 | Ficha de registro e visualizador de PDF | #pendente | E02 | Ficha da hospedagem em PDF no app, em memória, com "Salvar como…" e impressão local |
| E06 | Gate do marco M2 | #pendente | E03, E04, E05, E07 | Seed ampliado, roteiro do M2 e definição de pronto verificada |

---

## E01 — Painel do dia e detalhe da reserva — #em-andamento

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

- [ ] Contra o seed, as três abas mostram as reservas criadas para a data operacional, com contagem correta.
- [ ] O detalhe mostra o histórico em ordem cronológica, com textos em pt-BR, sem UUID e sem campo técnico.
- [ ] O documento aparece mascarado.
- [ ] Teste do *state holder*: lista vazia, erro e recarga.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com o seed.

**Revisão humana:** não

**Notas para o plano:** o mapeamento área → permissão foi registrado no handoff da M1 E04.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Listas grandes. Usar a tabela com cursor do `design`.

**Evidências e handoff:**

- **Estado (2026-11-13):** `#em-andamento`. O usuário mandou parar o lote no meio da etapa. Das 10 tarefas, as Tasks 1 a 4 estão concluídas, revisadas e integradas; as Tasks 5 a 10 faltam. A área Recepção ainda **não** está ligada na casca (`IMPLEMENTED_AREAS` sem mudança), então a `main` segue com o comportamento do M1. A base (M0 E02) ainda não foi validada no Windows (regra 7).
- **Plano:** [`docs/superpowers/plans/2026-11-13-m2-recepcao-do-dia-e01.md`](../superpowers/plans/2026-11-13-m2-recepcao-do-dia-e01.md), com 10 tarefas, todas de risco alto. A revisão de arquitetura bloqueou na rodada 0 (8 Importantes) e na rodada 1 (1 Importante) e aprovou com notas na rodada 2.
- **Commits:** `030788b..4c973c3` na branch `roadmap/m2-recepcao-do-dia`, cada um com o trailer `Soul:`. As tarefas rodaram em série na branch da etapa. As ondas paralelas não funcionam nesta configuração: a sessão isolada no worktree da etapa não deixa os subagentes escreverem nos worktrees de tarefa.
  - **Task 1**, fachada das reservas e `HotelFacade.businessDay()` (fuso de `getBusinessDate`): `030788b..75ad47f`. O testador achou o cursor em branco; corrigido. Revisão: aprovada.
  - **Task 3**, `ReceptionText`: `75ad47f..2e63179`. O testador achou noites negativas; corrigido. Revisão: aprovada.
  - **Task 2**, `GuestsFacade` e `RatesFacade`: `2e63179..488955f`. Revisão: aprovada.
  - **Task 4**, `TableState.refresh()` com atualização pendente: `488955f..4c973c3`. Revisão: aprovada.
  - Verificação mecânica `check_test_integrity.py`: "OK: nenhum apontamento" nas quatro tarefas.
- **Revisão final:** — (a etapa não terminou).
- **Critérios verificados:** nenhum ainda. Os critérios dependem das Tasks 6 a 10.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Área Recepção:** continua aparecendo por `frontdesk.stays.checkin`/`checkout`, como na M1 E04, e não por `reservations.reservations.read`, como diz a etapa. A leitura de reservas também está com Comercial/Eventos e Financeiro, que veriam uma Recepção sem nenhuma ação. As listas exigem `reservations.reservations.read`, com `403` tratado.
  - **H3:** o servidor fixado não tem tópico de reservas (só `rooms`, `notifications` e `end-of-day`). O painel vai se atualizar por:
    - `rooms`, agrupado em janelas de 2 s;
    - `end-of-day`;
    - foco da janela;
    - "Atualizar";
    - `refresh()`.

    Sem `PF-NN` por ora. Se a demo mostrar listas defasadas, avaliar pedir um tópico `reservations`.
  - **Titular:** lido por `GET /api/v1/guests/{id}` (a lista só traz o id; não há leitura em lote). Fica em memória só o nome e o primeiro documento mascarado.
  - **Fuso:** o fuso do hotel vem de `getBusinessDate`, que não exige permissão. `timezone` ausente no corpo vira resposta incompatível, porque o campo é obrigatório no contrato.
  - **`detail` do servidor:** um `code` fora do catálogo mostra o `detail` do servidor (design §6.2), e o `ApiError.Problem` leva esse texto como mensagem. Se um `detail` vier com dado pessoal, ele vai para a tela e para o `toString`. Recomendado: manter a spec. Alternativa: mostrar o `detail` só na tela.
- **Notas para a próxima execução (retomar esta etapa):**
  - **Retomada:**
    - Worktree: `.claude/worktrees/roadmap-m2-recepcao-do-dia` (fica no disco).
    - Ledger do SDD: `.superpowers/sdd/2026-11-13-m2-recepcao-do-dia-e01/progress.md` dentro desse worktree. É ignorado pelo Git; traz os rulings, os minors adiados e a varredura de conflitos.
    - Seguir pelo ledger e pelo `git log`, sem refazer as Tasks 1 a 4.
  - **Próximo passo:** a Task 5 (porta da recepção e adaptador), do zero, com BASE no HEAD da branch.
    - Um rascunho interrompido, sem commit nem revisão, está em `task-5-rascunho-interrompido/` no workspace. É só referência.
    - Depois vêm as Tasks 6 a 10, em série.
    - Ao retomar, recrie o `global-constraints.md` do workspace a partir do plano, se o workspace tiver sido apagado.
  - **Task 6:** `TableState.refresh()` propaga exceção que não é `ApiError`. O painel deve chamá-lo num escopo que não derrube a tela.
  - **Ambiente:** `zeny-front` no ar em `57eb5fac5b8a`, com a demo da data 2026-11-13. A Task 10 precisa das credenciais de `.server/seed-secrets.env`.

---

## E02 — Alocação de UH e check-in — #pendente

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

- [ ] Contra o seed, uma chegada sem UH recebe a UH sugerida e faz check-in. Ela sai de "chegadas", entra em "hospedados", e a UH muda de status no mapa sem recarregar.
- [ ] Check-in com UH não pronta mostra a mensagem do `code` correspondente em pt-BR, sem efeito parcial.
- [ ] Teste: repetir a confirmação após erro de rede reaproveita a mesma `Idempotency-Key`, quando a operação exigir a chave.
- [ ] O hóspede criado no diálogo aparece no detalhe da reserva com o documento mascarado.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com o seed, em duas janelas: painel e mapa.

**Revisão humana:** não

**Notas para o plano:** a orquestração e as regras do check-in estão na M5 E02 do roadmap do servidor e na nota `ZenyHotelManager - Hospedagem e recepção`. Os `code` de erro possíveis estão no contrato, na operação de check-in.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O check-in exige dados que o cadastro mínimo não tem (H1). Ampliar o diálogo com os campos exigidos e registrar isso no handoff.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E03 — Walk-in — #pendente

**Objetivo:** um hóspede sem reserva chega ao balcão. O recepcionista informa as datas e o tipo de UH, vê o preço, escolhe a UH, informa ou cria o hóspede e conclui. A reserva nasce já hospedada.

**Por que agora:** completa o ciclo de entrada da recepção com um fluxo que impressiona na demo.

**Dependências:** E02.

**Escopo:**

- **Assistente de walk-in:** `POST /api/v1/walk-ins`, com os dados que o contrato exige. Cotação por `GET /api/v1/quotes` e disponibilidade por `GET /api/v1/availability`, só no que o walk-in precisar.
- **Reaproveitamento:** o diálogo de hóspede da E02.
- **Erros e aprovação:** como na E02.

**Fora de escopo:** a tela geral de disponibilidade e cotação (M3).

**Critérios de aceite:**

- [ ] Contra o seed, um walk-in de 2 noites numa UH livre termina com a reserva em "hospedados" e a UH ocupada no mapa.
- [ ] Sem disponibilidade, mostra mensagem em pt-BR antes de pedir os dados do hóspede.
- [ ] Desconto acima do limite do Recepcionista abre o diálogo de aprovação, se o walk-in aceitar desconto no contrato.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** a M5 E03 do servidor define o walk-in. Leia o handoff dela no roadmap do servidor.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além dos da E02.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Troca de UH e desfazer check-in — #pendente

**Objetivo:** num hospedado, o recepcionista troca a UH, escolhendo a nova e o motivo. Também desfaz um check-in feito por engano, o que exige a aprovação do gerente no próprio balcão.

**Por que agora:** mostra o tratamento de exceções e a aprovação de superior, que é diferencial do produto.

**Dependências:** E02.

**Escopo:**

- **Troca de UH:** `POST /api/v1/reservations/{id}/room-moves`.
- **Desfazer check-in:** `POST /api/v1/reservations/{id}/undo-check-in`, via o diálogo de aprovação do M1 quando vier `APPROVAL_REQUIRED`.
- **Reflexo:** status das duas UHs atualizado no mapa.

**Fora de escopo:** extensão e redução da estadia, se não houver operação própria no contrato. Se houver, registrar no handoff como candidata a etapa nova do M3.

**Critérios de aceite:**

- [ ] Troca de UH contra o seed: a UH antiga e a nova mudam de status no mapa, e o histórico da reserva registra a troca.
- [ ] Desfazer check-in como Recepcionista → diálogo de aprovação. Com login e PIN do Gerente do seed, a ação conclui. Com PIN errado, aparece mensagem em pt-BR e nada muda.
- [ ] Teste: a repetição após a aprovação usa a mesma `Idempotency-Key` e o `X-Approval-Token`.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o desfazer check-in tem efeito na FNRH, e quem cuida dele é o servidor: grava a submissão `UNDO_CHECK_IN`, que vira `FAILED` (`NO_REMOTE_OPERATION`) se o check-in já tinha sido enviado. A tela não mostra nada disso e não chama o `fnrh`. A fila é da M7 E10. No `zeny-front`, sem credencial, tudo fica `PENDING`.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O PIN do Gerente não foi definido pelo seed. Conferir na M1 E05 e corrigir o seed nesta etapa.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Recados — #pendente

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

- [ ] Criar um recado para uma reserva do seed → aparece na lista e no detalhe da reserva.
- [ ] Resolver → muda de situação, e a lista de pendentes atualiza.
- [ ] Edição concorrente → `412` tratado com recarga e aviso.

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

## E07 — Ficha de registro e visualizador de PDF — #pendente

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
