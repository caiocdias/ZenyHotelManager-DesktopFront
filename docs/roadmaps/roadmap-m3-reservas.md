# Roadmap — M3 Reservas

> Arquivo: `docs/roadmaps/roadmap-m3-reservas.md` · branch de execução: `roadmap/m3-reservas` · criado em 2026-11-10 · última revisão 2026-11-17

> **Pré-requisito:** etapa E06 ("Gate do marco M2") de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md` marcada `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3). A rodada 3 já foi analisada: a etapa E03 de `docs/roadmaps/roadmap-m0-sincronizacao.md` (servidor em `5e06f16d51ff`) precisa estar integrada na `main` antes da E01 deste marco. Pela regra 7, ela pode estar "Aguardando o Windows".

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
  - **confirmação da reserva** (desde o pin `57eb5fac5b8a`, M0 E02): `GET /api/v1/reservations/{id}/confirmation/pdf`, que exige `reservations.reservations.read` e devolve `409 CONFIRMATION_UNAVAILABLE` para reserva cancelada ou no-show. O `POST …/confirmation/print` (fila do servidor) é da M7 E12 ([ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)).
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
- confirmação da reserva em PDF, no visualizador da M2 E07;
- histórico;
- pendências;
- seed ampliado;
- roteiro do M3;
- revisão de segurança adiada do processo acelerado, paga antes do M4 (E06, [ADR 0012](../adr/0012-revoga-o-processo-acelerado.md)).

**Fora de escopo:**

- bloqueios de grupo, rooming list e limite de overbooking, que vão para o M7 (cadastros e gestão);
- cadastro de tarifas (M7);
- conta e pagamento de depósito (M4);
- envio da confirmação pela fila de impressão do servidor (M7 E12) e envio por e-mail, que o contrato não tem.

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
| H1 | `POST /api/v1/bookings` cria tanto a reserva individual quanto a agrupadora, e a alteração do hospedado (estender ou reduzir) é `PUT /api/v1/reservations/{id}`. **Confirmada na E03** (2026-11-16): a tela cria a individual, e o `PUT` estende e reduz o hospedado. O `PUT` não troca titular nem hóspedes (handoff da E03) | A tela de nova reserva ou a de alteração precisa de outro fluxo; registrar no handoff | E03 |
| H2 | `GET /api/v1/availability` responde rápido o bastante para um calendário de 30 dias por tipo de UH | Calendário paginado por semana | E02 |
| D1 | **Decidida pelo usuário em 2026-11-17** (entrevista do fim do lote, pela opção recomendada): abrir um pedido **não bloqueante** ao servidor (`PF-NN`, ADR 0005) de uma operação para trocar o titular e os hóspedes de uma reserva antes do check-in. **Aberto em 2026-11-17 como `PF-03`** (`docs/pedidos-servidor.md`) | Sem o pedido, titular e hóspedes só mudam criando outra reserva; nenhuma etapa depende dele | E03 |
| D2 | **Decidida pelo usuário em 2026-11-17** (entrevista do fim do lote, pela opção recomendada): abrir um pedido **não bloqueante** ao servidor (`PF-NN`, ADR 0005) de uma prévia da multa de cancelamento (valor devido agora e prazo). **Aberto em 2026-11-17 como `PF-04`** (`docs/pedidos-servidor.md`) | Sem o pedido, o diálogo mostra os termos da política antes e o valor lançado depois; nenhuma etapa depende dele | E04 |

## Definição global de pronto

- [ ] Os comandos oficiais de build, teste e lint passam.
- [ ] O `smokeTest` passa contra o ambiente recriado.
- [ ] O roteiro `docs/demo/roteiro-m3.md`, que acumula os anteriores, foi executado pelo usuário no Windows.
- [ ] Nenhuma etapa depende de pedido ao servidor.
- [ ] A revisão de segurança adiada do processo acelerado está paga (E06).

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
| E01 | Busca de reservas | #concluida | — | Lista com filtros e cursor, busca por documento e acesso ao detalhe |
| E02 | Disponibilidade e cotação | #concluida | — | Calendário de disponibilidade por tipo, inventário e cotação de preço |
| E03 | Nova reserva e alteração | #concluida | E01, E02 | Criar reserva a partir da cotação, alterar (inclusive estender e reduzir) e confirmar |
| E04 | Cancelar, reativar e pendências | #concluida | E01 | Cancelamento com multa, reativação aprovada e listas de pendências |
| E05 | Gate do marco M3 | #em-andamento | E03, E04 | Seed ampliado, roteiro do M3 e definição de pronto verificada |
| E06 | Revisão adiada do processo acelerado | #pendente | E05 | Revisão de segurança do intervalo devido, com scanner e integridade, e os achados Críticos e Importantes corrigidos |

---

## E01 — Busca de reservas — #concluida

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

- [x] Contra o seed, filtrar por período e situação devolve o esperado, e a página seguinte carrega sem repetir linhas.
- [x] A busca por documento de um hóspede do seed encontra as reservas dele, com o documento mascarado na tela.
- [x] O termo buscado não aparece em log.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum relevante.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-16-m3-reservas-e01.md`, com 7 tarefas, todas `Risco: alto`.
  - **Revisão de arquitetura:** aprovado com notas, em 1 rodada, no opus. A rodada 0 bloqueou por 5 Importantes, todos incorporados ao plano.
- **Commits:** `a300a23..2958f15` na `roadmap/m3-reservas`, em série.
  - Task 1, fachada `search`/`searchByDocument`: `49beab0..537f25a`.
  - Task 2, `ReservationRow` e `HolderNames`: `537f25a..1c5b5b2`.
  - Task 3, porta, adaptador e fake: `1c5b5b2..d447a9f`.
  - Task 4, estado da busca: `d447a9f..737aa58`, com 1 rodada de correção. A carga inicial sobrescrevia a busca do usuário.
  - Task 5, tela e `ReservationDetailPanel` extraído da Recepção: `737aa58..47e6ab4`, mais o teste do undo do documento.
  - Task 6, área Reservas na casca: `47e6ab4..331af8f`, mais os testes que pegam a remoção do `close()`.
  - Task 7, `LiveReservationSearchCheck`: `331af8f..3e87aec`, com 1 rodada de correção. A busca por nome herdava o período padrão.
  - Onda final: `5aefe60`.
  - Correção da medição ao vivo: `2958f15`.
- **Revisão final** (revisor-seguranca, opus, `code-reviewer.md`): com correções, 0 Critical e 1 Important.
  - O Important era o país emissor que sobrava ao trocar de Passaporte para CPF.
  - Também ficaram para corrigir dois KDoc adiados das tarefas.
  - A onda única foi `5aefe60`, e a re-revisão a aprovou como pronta para merge.
  - Evidências:
    - `osv-scanner --recursive --no-ignore ./`: 6 lockfiles, "No issues found";
    - `./gradlew test --rerun --max-workers=2`, isolado: 4088 testes, 0 falhas, 38 pulados por opt-in ao vivo;
    - `bash tests/run.sh`: 49 arquivos, 0 falhas;
    - `check_test_integrity.py --base a300a23`: `SEM_SOUL` no `58b35b7` (abertura da etapa, só roadmap) e 4 `ASSERCAO_REMOVIDA`. Estes são ajustes mandados na Task 6, de mesma força.
- **Verificação ao vivo:**
  - Na 1ª execução, a `pagesDoNotRepeat` falhou por medição. O `DataTable` pede a página seguinte sozinho perto do fim, e o teste contava páginas pelos tamanhos da tabela.
  - A correção `2958f15` conta as chamadas à fonte. Ela é só de teste, com integridade exit 0, e a re-revisão a aprovou com 2 Minor.
  - A 2ª execução, no `zeny-front` e no HEAD `2958f15`, passou 4/4, sem nenhum pulado.
- **Critérios verificados:**
  - Período, situação e paginação: `periodAndStatusFilter` deu 5 linhas Hospedada, iguais à lista do dia. `pagesDoNotRepeat` em páginas de 3 deu 5 linhas em 2 páginas, com `limit=3` em toda chamada, sem repetição e com o total certo.
  - Documento mascarado: `documentSearchFindsHolder` achou a reserva do G1. A captura mostra "Reservas do documento CPF ***0146" com o campo do número vazio.
  - Termo fora do log: `termNotInLogs` não encontrou o nome nem o CPF no JUL, em stdout ou em stderr. Somam-se os testes unitários de `toString`, de causa trocada e de undo.
  - Build, teste e lint: verdes.
  - Inspeção manual: captura `app/build/live/reservation-search.png` inspecionada.
- **Decisões para o usuário**, já tomadas e revisáveis:
  1. **Demo sem recriar.** O classificador negou `down -v`, e a E01 só lê. A verificação ao vivo rodou no `zeny-front` depois do fechamento das 03:00, com A1–A4 em no-show e S1/S2 com saída vencida.
  2. **Duplicação com o `DayPanelState`.** Colunas, `readRoomTypeNames` e trava ficam como dívida para a E03: `reservationColumns()` e um leitor de página compartilhado em `ReservationRows.kt`.
  3. **Execução ao vivo pelo usuário.** O verificador do worktree isolado recusa `. arquivo` com as credenciais. A senha não foi para a linha de comando.
- **Notas para a próxima etapa:**
  - **Candidatos M2.**
    - `GuestPickerState.kt:112` tem o mesmo defeito do país que sobra ao trocar o tipo.
    - `GuestsFacade.searchByName` põe o nome na URL, e a causa de `ApiError.Network` pode trazê-lo. Revisão de arquitetura, nota 3.
  - **Seed.** `pagesDoNotRepeat` exige mais de 3 hospedados não vencidos, e hoje há 5. H1 e H2 vencem em d+2 e d+3. Recrie a demo antes de repetir a verificação depois disso.
  - **Teste instável.** `DocumentsFacadeAtaqueTest.networkFailureMidBodyIsNetworkWithoutPartialBytes` falhou uma vez na Task 1, rodando junto com outro Gradle. Passou na execução isolada.
  - **Undo.** A prova do undo do documento usa `performTextInput`. Falta a checagem manual com teclado físico no `:app:run`: digitar um CPF, buscar e apertar Ctrl+Z.
  - **Máscara.** Um número curto, de 5 a 7 caracteres, mostra 4 caracteres, como a regra do plano manda. Sugestão: mostrar 2 abaixo de 8.
  - **Ambiente desta sessão.**
    - Ondas paralelas continuam inviáveis.
    - O verificador recusa sourcing de arquivo, então a verificação ao vivo precisa ser rodada pelo usuário.
    - O verificador leu `osv-scanner scan source` como o builtin `source`; use `osv-scanner --recursive --no-ignore ./`.
  - **Dívidas menores.** Estão no ledger, triadas na revisão final:
    - os asserts frouxos `startTwice` (≤2) e `holderReads` (1..3);
    - o `rowClickWithoutDetailDoesNothing`, que é tautológico;
    - o teste que fixa o país entre Passaporte e Documento estrangeiro;
    - a troca de causa assimétrica em `ReservationsFacade`;
    - na verificação ao vivo, as asserções de `limit` e cursor ignoram a chamada final vazia;
    - o período padrão pode correr com o `clear()` e daria um vermelho falso. Isso é mais estrito, não mais fraco.

---

## E02 — Disponibilidade e cotação — #concluida

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

- [x] Contra o seed, o calendário de 30 dias mostra a disponibilidade por tipo. Uma reserva criada em outro cliente reduz a disponibilidade depois de recarregar.
- [x] A cotação mostra os planos com os valores formatados em BRL, iguais aos devolvidos pela API (conferidos por `curl`).
- [x] Uma cotação que fere uma restrição mostra o motivo em pt-BR.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Lentidão (H2). Paginar por semana.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-16-m3-reservas-e02.md`, com 9 tarefas. As Tasks 1–8 rodaram como `Risco: alto`, no processo completo; a Task 9 e o fechamento, no processo acelerado (ADR 0011).
  - Revisão de arquitetura: aprovado com notas, em 2 rodadas, no opus.
  - A rodada 0 bloqueou por 7 Importantes:
    - aba visível sem a permissão;
    - motivo falso do ruling 2;
    - critério 3 sem restrição real;
    - cancelamento com `If-Match`;
    - `externalId` fixo;
    - molde de opt-in inexistente;
    - total igual à soma das noites.
  - A rodada 1 bloqueou por 2 Importantes:
    - validação em `quoteStay` quebraria 4 testes;
    - códigos de recusa no `ErrorCatalog` sobreporiam o `detail` do walk-in.
- **Commits:** `b6aafa9..2ad4b09` na `roadmap/m3-reservas`, em série. Plano: `ac558f4`, `48a222a`, `5239cde` e `70c49e9`. O intervalo inclui o merge da `main` (`141b5ba`), que trouxe o ADR 0011 e só toca documentos.

  | Task | Entrega | Commits | Observação |
  |---|---|---|---|
  | 1 | `availability` e `quoteStay(includeUnavailable)` na fachada; `ErrorCatalog.isShowableDetail` | `70c49e9..eb717c9` | |
  | 2 | Porta `AvailabilitySource`, adaptador, `AvailabilityText` e fake | `eb717c9..cd502bd` | 1 rodada de correção: mensagem igual ao código em outra caixa chegava crua |
  | 3 | `AvailabilityState` | `cd502bd..4b84767` | 1 rodada de correção: dias fora da janela ficavam nas células |
  | 4 | `QuoteState` | `4b84767..bf4bb90` | |
  | 5 | `CalendarPanel`, mais a sobrecarga `ErrorState(message)` no `design` | `bf4bb90..231901c` | |
  | 6 | `QuotePanel` e `AvailabilityScreen` | `231901c..ecc9d9b` | |
  | 7 | Aba "Disponibilidade e cotação" na área Reservas, só com `reservations.availability.read` | `ecc9d9b..1e65a58` | 1 rodada de correção: a permissão da sessão anterior valia na sessão nova |
  | 8 | `LiveAvailabilityCheck`, só leitura, com travas estáticas e de mutação | `1e65a58..d26f9dd` | 1 rodada de correção: travas frágeis ao ktlint |
  | 9 | `LiveAvailabilityBookingCheck` no `demo-seed`, que escreve e desfaz, com a trava estática | `141b5ba..006c8d8` | Processo acelerado: sem testador por tarefa e sem revisão |
  | Etapa | Testador de etapa: trava `test_live_seed_availability_limpeza_ataque.sh` | `006c8d8..2ad4b09` | Sem defeito; a trava da Task 9 deixava passar 14 de 19 mutantes, e a nova mata 18 |

  As Tasks 1–8 passaram por desenvolvedor, testador, verificação de integridade e revisão (`revisor-seguranca`), e todas foram aprovadas. Os apontamentos `DEV_TESTE_DO_TESTADOR` das Tasks 2, 3 e 7 foram julgados justificados: o teste vermelho do testador foi colado sem mudança.
- **Revisão final:** não feita, pelo processo acelerado.
- **Revisão de segurança: adiada (ADR 0011) — commits `b6aafa9..2ad4b09`, integridade exit 1.** As Tasks 1–8 tiveram revisão por tarefa; faltam a da Task 9, a do testador de etapa e a revisão final do intervalo.
- **Fechamento** (HEAD `2ad4b09`, 2026-11-16):
  - **Gate:** `./gradlew build --max-workers=2` → BUILD SUCCESSFUL; os XML somam 4314 testes, 0 falhas, 0 erros e 43 pulados (opt-ins ao vivo). `bash tests/run.sh` → 53 arquivos, 0 falhas, 0 com casos pulados.
  - **Integridade** (`check_test_integrity.py --base b6aafa9`): exit 1.
    - `MERGE_IGNORADO`: o merge da `main` (`141b5ba`), só de documentos.
    - 3 `DEV_TESTE_DO_TESTADOR` (`cd502bd`, `4b84767` e `1e65a58`): o teste vermelho do testador colado sem mudança pelo desenvolvedor nas Tasks 2, 3 e 7. A revisora os julgou justificados em cada tarefa.
    - Por tarefa, a Task 9 deu exit 0 (`--base 141b5ba`).
- **Critérios verificados**, ao vivo no `zeny-front`, pelo orquestrador, com as credenciais só no ambiente do subshell:
  1. **Calendário e outro cliente.** `LiveAvailabilityCheck.calendarMatchesApi` passou: o calendário de 30 dias traz os três tipos com os números da API. `LiveAvailabilityBookingCheck.otherClientBookingReducesAvailability` passou: a reserva criada pelo `demo-seed` baixou a STD em d+20 de `a0` para `a0 - 1`, e o cancelamento a devolveu.
  2. **Cotação em BRL.** `LiveAvailabilityCheck.quoteMatchesApi` passou: o total e cada noite da tela são os decimais da API formatados. A captura mostra "Total R$ 2.040,00" com duas noites de R$ 1.020,00.
  3. **Restrição em pt-BR.** `LiveAvailabilityBookingCheck.minStayRestrictionRefusesInPortuguese` passou: com `minStay = 2` em d+25, a cotação de 1 noite recusa o `BALCAO` com "exige ao menos 2 noite(s)", e a restrição foi retirada. `LiveAvailabilityCheck.refusalInPortuguese` passou com a recusa de ocupação.
  - As duas verificações: 3/3 e 2/2, nenhuma pulada.
  - Build, teste e lint: verdes (gate acima).
  - Inspeção manual: captura `app/build/live/availability.png` inspecionada. A aba não fica dentro de outra rolagem vertical: só a `AvailabilityScreen` rola (nenhum `verticalScroll` na casca, no `AppRoot` nem no `AppScaffold`).
- **Decisões para o usuário**, já tomadas e revisáveis:
  1. **Inventário pela disponibilidade.** O detalhe da célula usa os contadores do `GET /availability`, e o `room-type-inventory` não é chamado, porque os contadores são os mesmos, com uma chamada por tipo.
  2. **Calendário sem plano.** Decisão de escopo: as restrições do plano aparecem como recusa na cotação. O seletor de plano custaria uma fachada `listRatePlans` e mostraria um plano por vez.
  3. **Verificação ao vivo no `demo-seed`.** Ela escreve e desfaz na demo:
     - uma reserva `DEMO-E02-<uuid>` criada e cancelada, que fica como "Cancelada" na busca;
     - uma `minStay = 2` gravada e retirada em d+25.
  4. **`ErrorState(message: String)` no `design`.** A sobrecarga ficou fora da lista de arquivos da Task 5. A versão com `ApiError` delega a ela.
  5. **Erros de campo.** Com `fieldErrors`, a cotação não repete o erro genérico, e a chave de campo desconhecida aparece acima do resultado.
  6. **Task 9 no processo acelerado.** Ela partiu do trabalho interrompido guardado no workspace e rodou como `Risco: normal`, sem testador por tarefa e sem revisão. O testador de etapa a atacou.
  7. **Verificação ao vivo pelo orquestrador.** Nesta sessão o subshell com as credenciais foi permitido, então o orquestrador rodou as duas verificações. A senha não foi para a linha de comando nem para a saída.
- **Notas para a próxima etapa (dívidas menores, todas no ledger):**
  - `runCatching` engole `CancellationException` na leitura da permissão da aba (`AppRoot.kt`). Falha fechada; a correção entrou no plano da E03 (fiação da área).
  - `LiveAvailabilityBookingCheck`, do testador de etapa, tudo de baixa severidade:
    - se a data operacional virar durante o teste, a restrição de d+25 fica na demo;
    - uma resposta perdida na criação deixa uma reserva `DEMO-E02-` sem cancelar;
    - a conta `a0 - 1` supõe que ninguém mais reserva a STD em d+20 durante o teste;
    - a exceção do `finally` pode mascarar a do corpo (melhoria: `addSuppressed`).
  - Enter cota mesmo com `loading`, e a última cotação vence.
  - Sem indicador de carga quando o calendário já tem linhas.
  - O fallback de "Indisponíveis" mostra o código técnico do tipo ou do plano.
  - Data malformada no motivo aparece como "—".
  - Mensagem de servidor de uma palavra sem acento cai no texto de reserva.
  - Helpers de teste duplicados entre os arquivos do desenvolvedor e do testador.
  - As travas `.sh` da verificação ao vivo dependem de `perl`.
  - E03: a duplicação com o `DayPanelState` segue da E01.

---

## E03 — Nova reserva e alteração — #concluida

**Objetivo:** a partir de uma cotação, o usuário cria a reserva: titular buscado ou criado, plano, garantia exigida pela política e observações. Ele também altera uma reserva existente (datas, tipo, plano, hóspede), o que inclui estender ou reduzir a estadia de um hospedado, e confirma uma pré-reserva. Desconto acima do limite pede aprovação. Depois de criar ou confirmar, abre a confirmação da reserva em PDF para salvar ou imprimir.

**Por que agora:** completa o ciclo comercial da reserva.

**Dependências:** E01, E02.

**Escopo:**

- Criação: `POST /api/v1/bookings` e `GET /api/v1/bookings/{id}`.
- Alteração e confirmação: `PUT /api/v1/reservations/{id}` com `If-Match` e `POST /api/v1/reservations/{id}/confirm`.
- Diálogo de hóspede da M2 E02.
- Diálogo de aprovação para desconto.
- Confirmação: `GET /api/v1/reservations/{id}/confirmation/pdf` no visualizador da M2 E07 (em memória, "Salvar como…" e impressão local, ADR 0009), a partir do detalhe da reserva e logo após criar ou confirmar.

**Fora de escopo:** pagamento de depósito (M4), bloqueios de grupo (M7) e envio da confirmação pela fila de impressão do servidor (M7 E12).

**Critérios de aceite:**

- [x] Criar uma reserva de 3 noites a partir da cotação. Ela aparece na busca e reduz a disponibilidade.
- [x] Desconto acima do limite do Recepcionista abre o diálogo de aprovação. Aprovado pelo Gerente do seed, a reserva é criada uma vez só.
- [x] Estender um hospedado em 1 noite atualiza o período e o histórico.
- [x] A alteração concorrente trata o `412` com recarga e aviso.
- [x] A confirmação da reserva criada abre no visualizador com o código, as datas e o total iguais aos da API. Numa reserva cancelada, a ação não aparece, ou mostra em pt-BR a recusa `CONFIRMATION_UNAVAILABLE`. Nenhum arquivo é criado sem "Salvar como…".

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o preço fica congelado na reserva: alterar a tarifa publicada não muda reserva existente, conforme o gate do M4 do servidor.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O fluxo de reserva agrupadora é complexo (H1). Entregar a individual primeiro; a agrupadora entra se couber, senão vira etapa nova registrada no handoff.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-16-m3-reservas-e03.md`, com 11 tarefas, todas `Risco: normal`, no processo acelerado (ADR 0011).
  - Revisão de arquitetura: aprovado com notas, em 1 rodada, no fable. A rodada 0 bloqueou por 5 Importantes, todos incorporados:
    - o opt-in da verificação ao vivo fora do `app/build.gradle.kts`;
    - a semântica do `412` contraditória entre duas tarefas;
    - a confirmação em PDF verificada sem abrir o visualizador;
    - dois fatos errados na verificação ao vivo;
    - dois itens sem consumidor.
- **Commits:** `5cbac31..87f03cb` na `roadmap/m3-reservas`. As tarefas rodaram em ondas, com no máximo 2 ativas ao mesmo tempo, cada uma no seu worktree.

  | Task | Entrega | Commit | Observação |
  |---|---|---|---|
  | 1 | `BookingsFacade` (`createBooking`, `booking`, `changeReservation`, `confirmReservation`) e `DocumentsFacade.reservationConfirmation` | `4be49eb` | |
  | 2 | `PdfKind` no visualizador da ficha e a porta `ReservationConfirmations` | `5f168c4` | 1 rodada: o título passou a `titlePrefix`, para a trava do roteiro do M2 seguir valendo sem mudar |
  | 3 | Textos `BookingText` | `c24a575` | |
  | 4 | `NewReservationState` | `fbc6b4c` | |
  | 5 | `ReservationChangeState` | `a8d3737`, `075e37f` | 1 rodada de correção: `canConfirm` lia variável não observável, e a tela não recompunha depois da carga |
  | 6 | `ReservationConfirmState` e o diálogo | `7e05d76` | |
  | 7 | `NewReservationDialog` e o "Reservar" da cotação | `94daf6d` | |
  | 8 | `ReservationChangeDialog` | `8c76afc` | |
  | 9 | Portas no `CoreConnection` e botões do detalhe | `060ccbb` | |
  | 10 | Fiação na área Reservas (`ReservationDialogs`, `NewReservationHost`) | `1868d35` | Corrige também o `CancellationException` engolido na leitura de permissões (dívida da E02) |
  | 11 | `LiveBookingFlowCheck` e o opt-in `ZENY_LIVE_BOOKING` | `e86c68f` | |
  | Etapa | Testador de etapa: 24 testes e a trava `test_live_booking_ataque.sh` | `e86c68f..87f03cb` | Sem defeito |

  A verificação de integridade por tarefa deu exit 0 em todas.
- **Revisão final:** não feita, pelo processo acelerado.
- **Revisão de segurança: adiada (ADR 0011) — commits `5cbac31..87f03cb`, integridade exit 1.**
- **Fechamento** (HEAD `87f03cb`, 2026-11-16):
  - **Gate:** `./gradlew build --max-workers=2` → BUILD SUCCESSFUL; os XML somam 4523 testes, 0 falhas, 0 erros e 46 pulados (opt-ins ao vivo). `bash tests/run.sh` → 54 arquivos, 0 falhas, 0 com casos pulados. A suíte também rodou verde depois de cada onda integrada.
  - **Integridade** (`check_test_integrity.py --base 5cbac31`): exit 1, com 1 apontamento, `SKIP_ADICIONADO` em `tests/test_live_booking_ataque.sh` (`87f03cb`). É falso positivo: a linha é `nao_tem '@Disabled'`, a trava que **proíbe** desligar a verificação ao vivo.
- **Critérios verificados**, ao vivo no `zeny-front`, pelo orquestrador, com as credenciais só no ambiente do subshell. `LiveBookingFlowCheck` passou 3/3, nenhuma pulada, no HEAD `e86c68f`; depois dele só entraram testes.
  1. **Criar 3 noites a partir da cotação** (`bookingLifecycle`): a reserva `R-2026-000013` nasceu Confirmada, de 16/12 a 19/12, com o total da oferta (R$ 1.170,00). A busca pelo código achou uma linha, e a STD de d+30 ficou com uma unidade a menos.
  2. **Desconto com aprovação** (`discountNeedsApprovalAndCreatesOnce`): a diária negociada de R$ 1,00 abriu a pendência de `rates.discounts.apply`, com o limite do servidor. Aprovada pelo Gerente do seed (login e PIN), a reserva foi criada, e a contagem do titular no período subiu em exatamente uma.
  3. **Estender um hospedado** (`extendInHouseUpdatesPeriodAndHistory`): a saída ganhou 1 noite, o histórico ganhou uma entrada, e a saída original foi devolvida no fim (a redução).
  4. **`412`** (`bookingLifecycle`): com duas telas na mesma reserva, a segunda recebeu o aviso, voltou aos dados do servidor (os pedidos "ENSAIO E03 A" da primeira) e, refeita a edição, salvou.
  5. **Confirmação em PDF** (`bookingLifecycle`): abriu no visualizador, com o título "Confirmação da reserva — R-2026-000013", e o texto do PDF traz o código, as duas datas e o total da reserva relida. A reserva cancelada ou em no-show não mostra o botão (`confirmationHiddenForCancelled`), e a recusa `CONFIRMATION_UNAVAILABLE` mostra o texto do catálogo (`confirmationRefusalShowsCatalogText`). "Nenhum arquivo sem Salvar como…" é a `noDiskTest` do `design` (M2 E07) mais o `confirmationNeverSavesByItself`.
  - Build, teste e lint: verdes (gate acima).
  - Inspeção manual: capturas `app/build/live/new-reservation.png`, `reservation-change.png` e `reservation-confirmation.png` inspecionadas.
- **Decisões para o usuário**, já tomadas e revisáveis:
  1. **Titular e hóspedes não mudam na alteração.** O objetivo da etapa cita "hóspede", mas o `PUT /reservations/{id}` não troca o titular nem os hóspedes, e o contrato não tem outra operação para isso antes do check-in. A tela altera datas, ocupação, tipo, plano, segmento e pedidos. **O usuário decidiu em 2026-11-17 abrir um pedido `PF-NN` não bloqueante ao servidor** (decisão D1); o pedido ainda não foi aberto.
  2. **Só a reserva individual (H1).** A tela cria uma reserva (uma UH) por vez. A agrupadora com várias UHs vira etapa nova, se o usuário quiser.
  3. **Desconto como "diária negociada", só na criação.** O mesmo valor vai em todas as noites, e o servidor decide se é desconto e se pede aprovação. Na alteração, as noites novas entram pelo preço calculado.
  4. **Alteração por recotação.** Para trocar o tipo ou o plano, "Ver opções" cota a estadia digitada e lista as ofertas. Exige `reservations.availability.read` e não oferece plano de um tipo esgotado.
  5. **Origens oferecidas:** Balcão, Telefone, E-mail, WhatsApp e Outra, as do canal direto. Site e OTA ficam fora, porque a cotação do app não consulta esses canais.
  6. **Ações só no detalhe da busca.** O detalhe da Recepção não mudou; o hospedado é alcançado pela busca, com a situação "Hospedada".
  7. **A verificação ao vivo escreve na demo e não desfaz a criação.** Cada execução deixa duas reservas futuras do G1, marcadas "ENSAIO E03". A E04 as cancela na verificação dela.
  8. **Ondas de 2, não de 3.** Dois builds frios em paralelo deixaram 1,4 GB livres de 14,8 GB, com a outra trilha (do servidor) na mesma máquina. Os worktrees de tarefa são reaproveitados entre ondas.
- **Notas para a próxima etapa:**
  - **Sem evidência ao vivo:** a confirmação da pré-reserva ("Confirmar reserva") só tem teste com fake, porque o seed não cria pré-reserva. O seed da E04 cria uma (plano com garantia por cartão), e o botão passa a aparecer na demo.
  - **Sem tela:** `BookingsFacade.booking(id)` (o `GET /bookings/{id}` do escopo) é usado só pela fachada. A garantia exigida só aparece depois de criar: a oferta traz só o id da política.
  - **Observações do testador de etapa**, de gravidade baixa e sem teste:
    - `NewReservationState.finish` não confere `closed`;
    - um `IllegalStateException` do `ApprovalCoordinator` vira "resultado desconhecido";
    - resultado desconhecido seguido de aprovação cancelada deixa a tela travada sem texto;
    - na hospedada, um erro de campo `arrival` vindo do servidor se perderia na tela.
  - **Sem teste direto:**
    - a regra do Esc do `NewReservationDialog` (não fecha com `busy`, `unconfirmed` ou `picker.creating`): o harness de teste não entrega o Esc ao diálogo. A mesma trava está coberta pelo botão de fechar;
    - o `readOrNull` da leitura de permissões (`AppRoot.kt`): de fora, engolir ou relançar o cancelamento dá o mesmo resultado. O efeito que importa está coberto (a permissão da sessão anterior não vale na nova).
  - **Dívidas menores:**
    - os 6 textos de validação da estadia estão duplicados entre `ReservationChangeState` e `QuoteState`;
    - `resultUnknown`/`fail` repetem a lógica privada do `WalkInState`;
    - o `ReservationChangeDialog` guarda um contorno (`!state.loading && state.canConfirm`) que ficou redundante depois da correção;
    - a duplicação com o `DayPanelState` segue da E01.
  - **Teste instável:** `DocumentsFacadeAtaqueTest.networkFailureMidBodyIsNetworkWithoutPartialBytes` (core) falhou uma vez sob dois builds em paralelo, no aquecimento de um worktree. Não falhou em nenhuma tarefa.
  - **Ambiente:** nesta sessão o orquestrador roda as verificações ao vivo. O login da Recepcionista é `recepcao`, e o aprovador é `gerente` com o PIN do seed.

---

## E04 — Cancelar, reativar e pendências — #concluida

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

- [x] Cancelar uma reserva do seed com política de multa: a multa é mostrada antes de confirmar, e a reserva vira cancelada.
- [x] Reativar como Recepcionista abre o diálogo de aprovação. Com o Gerente, a reserva volta.
- [x] As quatro listas carregam contra o seed, e o seed tem ao menos um item em cada uma que o servidor permitir criar.

**Validação obrigatória:**

- Os comandos oficiais de build, teste e lint passam.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:**

- Desde o pin `5e06f16d51ff` (M0 E03), o fechamento do dia do servidor marca o no-show. As reservas de `expected-no-shows` viram `NO_SHOW` às 03:00 do dia seguinte (regra 5 do README), e o `reactivate` também vale para elas, não só para a cancelada.
- O seed não cria no-show. Para isso, teria de disparar um fechamento, que avança a data e desalinha o roteiro. A reativação de um no-show fica coberta por teste do *state holder*, e a lista de no-shows previstos usa as chegadas confirmadas do seed na data operacional.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alguma pendência depende de job ou data e não se cria pelo seed. Registrar isso no roteiro, sem forçar.

**Evidências e handoff:**

- **Plano:** `docs/superpowers/plans/2026-11-16-m3-reservas-e04.md`, com 13 tarefas, todas `Risco: normal`, no processo acelerado (ADR 0011).
  - Revisão de arquitetura: aprovado com notas, em 2 rodadas, no fable. Ela correu adiantada, sobre o rascunho, enquanto a E03 terminava.
    - A rodada 0 bloqueou por 1 Crítico (a pré-reserva do seed não entraria na lista de garantias na janela padrão do servidor) e 7 Importantes.
    - A rodada 1 bloqueou por 3 Importantes, criados pela correção do seed.
- **Commits:** `fc7a0b3..72e4316` na `roadmap/m3-reservas`. As tarefas rodaram em ondas, com até 3 ativas ao mesmo tempo, cada uma no seu worktree.

  | Task | Entrega | Commit | Observação |
  |---|---|---|---|
  | 1 | `BookingsFacade.cancelReservation` e `reactivateReservation` | `b642503` | |
  | 2 | `PendingFacade` (as quatro listas) | `d038f97` | |
  | 3 | Seed: `PendingSeeder` e a porta `PendingGateway` | `56b9736` | |
  | 4 | Seed: `SdkPendingGateway` e a ligação no `DemoSeed` | `4273c7b` | |
  | 5 | `ReservationCancelState` | `4a769ba` | |
  | 6 | `ReservationReactivateState` e o diálogo | `a0c72b2` | |
  | 7 | `PendingListsState` e `PendingText` | `4b6ba10` | |
  | 8 | `ReservationCancelDialog` | `489bae1` | |
  | 9 | `PendingPanel` | `31e9ece` | |
  | 10 | Portas no `CoreConnection` e botões "Cancelar reserva" e "Reativar" | `3a90bde` | |
  | 11 | `PendingTab` (as listas com o detalhe ao lado) | `275c84d` | |
  | 12 | Fiação na área Reservas e a aba "Pendências" | `629cd09`, `72e4316` | 1 rodada de correção: confirmar ou alterar passa a recarregar a lista de pendências |
  | 13 | `LiveReservationLifecycleCheck` e o opt-in `ZENY_LIVE_LIFECYCLE` | `325f658` | |
  | Etapa | Testador de etapa: 23 testes e a trava `test_live_lifecycle_ataque.sh` | `81164ed`, `1ea3473`, `e222c43` | Sem defeito |

  A verificação de integridade por tarefa deu exit 0 em todas.
- **Revisão final:** não feita, pelo processo acelerado.
- **Revisão de segurança: adiada (ADR 0011) — commits `fc7a0b3..72e4316`, integridade exit 1.**
- **Fechamento** (HEAD `72e4316`, 2026-11-17):
  - **Gate:** `./gradlew build --max-workers=2` → BUILD SUCCESSFUL; os XML somam 4696 testes, 0 falhas, 0 erros e 49 pulados (opt-ins ao vivo). `bash tests/run.sh` → 55 arquivos, 0 falhas, 0 com casos pulados. A suíte também rodou verde depois de cada onda integrada.
  - **Integridade** (`check_test_integrity.py --base fc7a0b3`): exit 1, com 2 apontamentos `SKIP_ADICIONADO` em `tests/test_live_lifecycle_ataque.sh`. São falsos positivos: as linhas `nao_tem '@Disabled'` e `nao_tem '@Ignore'` são a trava que **proíbe** desligar a verificação ao vivo.
- **Critérios verificados**, ao vivo no `zeny-front`, pelo orquestrador, com as credenciais só no ambiente do subshell. `LiveReservationLifecycleCheck` passou 3/3, nenhuma pulada, no `325f658` e de novo no HEAD final `72e4316`.
  1. **Cancelar com a multa** (`cancelAndReactivate`): numa reserva com a chegada no dia, o diálogo mostrou os termos da política antes de confirmar ("Cancelamento sem multa até 24 h antes do check-in; depois, multa de 1 diária(s)."). Cancelada, a reserva ficou `CANCELLED`, com a multa de R$ 320,00 lançada na conta (lançamento conferido), e a tela disse "Multa lançada na conta: R$ 320,00.".
  2. **Reativar com a aprovação** (`cancelAndReactivate`): como Recepcionista, a reativação abriu a pendência de `reservations.reservations.reactivate`; aprovada pelo Gerente do seed, a reserva voltou a `CONFIRMED`. Ela apareceu em "No-shows previstos", e o segundo cancelamento não cobrou multa de novo.
  3. **Pendências** (`pendingListsLoad`): as quatro listas carregaram sem erro. "Garantias pendentes" trouxe a pré-reserva do seed (`DEMO-PEND-P1`), e "Realocação necessária", a `DEMO-PEND-R1`, exatamente uma de cada. A linha da pré-reserva abriu o detalhe ao lado. "Depósitos em falta" fica vazia: o item exige operações de `billing`, do M4.
  - **Seed:** rodado duas vezes contra o `zeny-front`, em 3,9 s e 3,6 s, sem falha e com a saída impressa inalterada.
  - Build, teste e lint: verdes (gate acima).
  - Inspeção manual: capturas `app/build/live/reservation-cancel.png` e `pending.png` inspecionadas.
- **Decisões para o usuário**, já tomadas e revisáveis:
  1. **"A multa mostrada antes de confirmar" são os termos da política.** O contrato não tem prévia da multa, e o front não calcula preço nem prazo. O diálogo mostra os termos copiados na reserva e, depois de cancelar, a multa que o servidor lançou. **O usuário decidiu em 2026-11-17 pedir ao servidor uma prévia da multa** (`PF-NN`, não bloqueante; decisão D2); o pedido ainda não foi aberto.
  2. **Isenção oferecida.** O diálogo tem "Isentar a multa", que passa pela aprovação quando o servidor pede. A isenção não foi verificada ao vivo.
  3. **Aba própria de pendências, com todas as garantias.** A lista de garantias vai das vencidas até 730 dias à frente. O padrão do servidor (só as que vencem em 12 h) esconderia a pré-reserva recém-criada.
  4. **Seed por um seeder próprio.** O `PendingSeeder` cria a política "Garantia por cartão (demo)", o plano "Tarifa garantida" (só no canal do site e só Standard, para a cotação do app não ganhar ofertas gêmeas), a pré-reserva `DEMO-PEND-P1` e a reserva `DEMO-PEND-R1` (UH 208, que entra em fora de ordem de d+5 a d+7). O titular das duas é o Hóspede Doze. O relatório impresso do seed não mudou.
  5. **A verificação ao vivo escreve na demo.** Ela deixa uma reserva cancelada do G1, marcada "ENSAIO E04", e cancelou as duas "ENSAIO E03" da etapa anterior.
  6. **Ondas de até 3.** O limite passou a ser medido a cada despacho: 3 tarefas ativas com 9 GB livres e a outra trilha parada; senão, 2.
- **Notas para a próxima etapa:**
  - **Para a E05:**
    - o relatório do seed não lista as pendências; a decisão sobre a linha "pendências" está no plano da E05;
    - o passo 14 do `roteiro-m2.md` (walk-in) diz que o Hóspede Doze "não tem reserva", o que deixou de ser verdade com o seed desta etapa. O roteiro do M3 corrige a frase;
    - avisos para o roteiro do M3: depósitos sem item, a pré-reserva some em 48 h, e a multa só aparece em reserva com a chegada próxima.
  - **Observações do testador de etapa**, de gravidade baixa:
    - se o seed rodar de novo em outro dia operacional sem recriar a demo, ele abre um segundo fora de ordem na UH 208, fora da estadia da `R1`;
    - no cancelamento e na reativação, uma falha local que não chega à rede aparece como resultado desconhecido (hoje inalcançável pela tela);
    - o botão da reativação não vira "Tentar de novo" com resultado desconhecido, ao contrário do cancelamento.
  - **Sem teste direto:** a guarda do Esc dos diálogos de cancelar e de reativar (o harness não entrega a tecla ao diálogo); a mesma trava está coberta pelo botão.
  - **Dívidas menores:**
    - criar uma reserva nova pela cotação não recarrega a lista de pendências aberta;
    - o `createBooking` repete a lógica do ajudante novo de execução aprovável na `BookingsFacade`;
    - o tratamento do cursor está duplicado entre a `PendingFacade` e a `ReservationsFacade`;
    - o `PendingSeeder` não tem a espera de inventário do `BookingSeeder`; a primeira execução num banco novo acontece no gate (E05).
  - **Teste instável:** `DocumentsFacadeAtaqueTest.networkFailureMidBodyIsNetworkWithoutPartialBytes` (core) falhou uma vez, num gate com três builds em paralelo, e passou na repetição.

---

## E05 — Gate do marco M3 — #em-andamento

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

**Notas para o plano:**

- Desde o pin `5e06f16d51ff` (M0 E03), o servidor fecha o dia sozinho às 03:00 do dia seguinte à recriação (regra 5 do README). O início do `roteiro-m3.md` herda o aviso que a M0 E03 pôs nos roteiros anteriores: recriar no mesmo dia da execução, depois das 03:00. A lista acumulada do Windows (regra 7), executada neste gate, segue a mesma regra.
- As reservas futuras do seed ficam depois da data operacional, e nenhuma pode depender de um fechamento para aparecer como o roteiro descreve.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:** nenhum além dos do M2.

**Evidências e handoff** (parcial: o lote parou a pedido do usuário, em 2026-11-17, com a etapa `#em-andamento`):

- **Plano:** `docs/superpowers/plans/2026-11-17-m3-reservas-e05.md`, com 6 tarefas, todas `Risco: normal`, no processo acelerado (ADR 0011).
  - Revisão de arquitetura: aprovado com notas, em 1 rodada, no fable. Ela correu adiantada, sobre o rascunho, enquanto a E04 rodava. A rodada 0 bloqueou por 1 Crítico (a tarefa do pacote não fecha verde sem o testador ajustar travas antes) e 5 Importantes.
  - A Task 3 é do testador e roda antes da Task 4: é uma exceção escrita ao "testador uma vez por etapa".
- **Processo, desde 2026-11-17 ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md)):** o processo acelerado foi revogado com esta etapa em andamento. O restante dela segue o processo normal do orquestrador:
  - a `revisor-seguranca` em cada tarefa e na revisão final, com o scanner;
  - o testador por tarefa só em `Risco: alto` da lista fechada, e uma vez na etapa;
  - o gate de integração;
  - o fechamento só com a revisão final limpa.

  O plano foi escrito no processo acelerado e não foi alterado: todas as tarefas são `Risco: normal`, e ele não tem a seção `## Ondas`. Pelo passo 3a do orquestrador, um plano sem a seção roda cada tarefa restante como onda de uma tarefa só. Rever o `Risco:` pela lista fechada ou acrescentar a seção fica com o orquestrador, que é o dono do plano.

  Antes de retomar, a branch `roadmap/m3-reservas` precisa receber a `main` (é fast-forward): o worktree dela ainda não tem o ADR 0012, o `CLAUDE.md` sem o processo acelerado nem a E06. O workspace da etapa fica só nesse worktree e não pode ser apagado.
- **Revisão de segurança: adiada (ADR 0011) — commits `830f296..920dcd2`, integridade exit 0.** É o trecho feito antes da revogação. Só a Task 1 (`e8a1542..920dcd2`) tem código, e ela não teve revisão por tarefa. O trecho é a entrada 4 da lista "Revisões de segurança adiadas" do README. A revisão final desta etapa cobre o intervalo inteiro dela e alcança esse trecho; a E06 paga a lista.
- **Commits:** `830f296..920dcd2` na `roadmap/m3-reservas`.

  | Task | Entrega | Commit | Estado |
  |---|---|---|---|
  | 1 | Seed: `FutureSeeder` e a porta `FutureGateway` (duas reservas futuras e uma cancelada, do Hóspede Doze) | `920dcd2` | Completa e integrada; integridade exit 0 |
  | 2 | Seed: adaptador, ligação no `DemoSeed` e `LiveSeedReservationsCheck` | — | Despachada e interrompida logo no início; nada alterado |
  | 3 | Testador: as travas aceitam o `roteiro-m3.md` | — | Interrompida com duas travas alteradas, sem commit |
  | 4 | O pacote leva o roteiro do M3 | — | Não começou (depende da Task 3) |
  | 5 | `LivePreReservationConfirmCheck` (confirmação da pré-reserva ao vivo) | — | Interrompida com a verificação escrita e o opt-in registrado, sem commit e sem gate |
  | 6 | O roteiro do M3 | — | Não começou (depende das Tasks 2 e 4) |

  A Task 1 só cria arquivos novos: o `DemoSeed` ainda não chama o `FutureSeeder`, e o seed se comporta como na E04.
- **Trabalho interrompido, fora do git.** Está no workspace do SDD, `.superpowers/sdd/2026-11-17-m3-reservas-e05/`, que é ignorado pelo git e fica só no worktree `roadmap-m3-reservas`:
  - `task-3-wip/travas.patch`: o diff do testador em `tests/test_roteiro_m1_zero_ataque.sh` e `tests/test_roteiro_m2_ataque.sh`. O build tinha passado; faltavam o `tests/run.sh`, a prova nos dois estados e o commit;
  - `task-5-wip/`: `LivePreReservationConfirmCheck.kt` e o diff do `app/build.gradle.kts`. Faltavam o gate e o commit;
  - o ledger (`progress.md`), os briefs das 6 tarefas e o contexto do plano.

  Nada disso foi conferido nem revisado: o próximo orquestrador retoma a partir deles ou refaz pelo brief.
- **Falta para fechar:**
  0. Fora do plano da etapa, e sem bloquear nada: abrir os dois pedidos ao servidor que o usuário decidiu em 2026-11-17 (decisões D1 e D2), em `docs/pedidos-servidor.md` e no brain, conforme o ADR 0005.
  1. As Tasks 2 a 6, pelo plano. Podem correr em paralelo a 2, a 3 e a 5; depois a 4; por último a 6.
  2. O testador de etapa, que escreve as travas do roteiro novo (`tests/test_roteiro_m3_ataque.sh`) e ajusta os laços de `tests/test_fechamento_automatico_ataque.sh`.
  3. A verificação do gate, na ordem do plano: clone limpo; recriar a demo do notebook (`tools/server-env/down.sh -v` e `up.sh`, no mesmo dia da verificação e depois das 03:00); seed duas vezes, com o tempo; `smokeTest`; as verificações ao vivo do M2 e do M3; pacote e `save-images.sh`.
  4. O fechamento: a etapa fica "Aguardando o Windows" (regra 7), entra na lista "Validações acumuladas no Windows", e as entradas 1 a 3 da lista passam a pedir o `roteiro-m3.md`.
- **Revisão final:** ainda não feita.
- **Critérios verificados:** nenhum ainda.
- **Decisões para o usuário**, já tomadas no plano e revisáveis:
  1. **O relatório impresso do seed não ganha linha.** As reservas futuras, a cancelada e as pendentes entram por seeders próprios, sem aparecer na saída; o roteiro do M3 diz onde vê-las. A dívida fica registrada: o relatório subconta políticas, planos, reservas e bloqueios.
  2. **Reservas novas do seed:** duas futuras e uma cancelada, todas do Hóspede Doze, por um seeder separado (`FutureSeeder`), para o contrato do `PendingSeeder` ficar como o testador da E04 o fixou.
  3. **O roteiro do M3 não é executado clicando no notebook.** No lugar: cada "Resultado esperado" novo aponta a asserção ao vivo que o sustenta; a confirmação da pré-reserva ganha verificação ao vivo; e o gate roda as verificações do M2 e do M3 no ambiente recriado. A execução de ponta a ponta é a do usuário no Windows.
  4. **O orquestrador recria a demo do notebook para o gate** (regra 5 do README e design §7.2). Se a sessão negar, ele não contorna, e o critério 2 fica aberto. Dois avisos: se o app mobile da outra trilha estiver usando o `zeny-front` do notebook, a recriação zera os dados dele; e depois do `down -v` a senha do administrador volta à inicial.
  5. **A etapa fica "Aguardando o Windows"** e não fecha `#concluida` no lote.
- **Notas para a próxima etapa:**
  - A recriação da demo ainda não foi tentada nesta sessão.
  - O `PendingSeeder` e o `FutureSeeder` não têm a espera de inventário do `BookingSeeder`. Se o seed falhar no banco novo com `ROOM_TYPE_UNAVAILABLE` ou `INVENTORY_DATE_OUT_OF_HORIZON`, a correção é a repetição com espera, como adendo ao plano.
  - A pré-reserva `DEMO-PEND-P1` do ambiente de pé foi criada em 16/11 às 23:23 e vence 48 h depois.

---

## E06 — Revisão adiada do processo acelerado — #pendente

**Objetivo:** pagar a dívida de revisão do processo acelerado. O código que entrou sem a `revisor-seguranca` passa pela revisão que faltou, com o scanner de dependências e a verificação mecânica desde `a300a23`. Os achados Críticos e Importantes são corrigidos nesta etapa, e a lista "Revisões de segurança adiadas" de `docs/roadmaps/README.md` fica marcada como paga.

**Por que agora:** decisão do usuário em 2026-11-17 ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md)). O M4 trata de dinheiro e parte das telas e fachadas de reservas, e o código da E03 e da E04 nunca foi lido por uma revisora. Antes do M4 o intervalo ainda é pequeno, e nenhum marco depende dele.

**Dependências:** E05. Pela regra 7 do README, a E05 "Aguardando o Windows" conta como concluída.

**Escopo:**

- **Revisão:** a `revisor-seguranca`, com o prompt `code-reviewer.md` e o modelo mais capaz disponível, sobre `a300a23..HEAD` da branch no início da etapa. O pacote leva:
  - o diff do intervalo;
  - a saída do scanner de dependências no HEAD revisado;
  - a saída de `check_test_integrity.py --base a300a23`;
  - a saída do gate do projeto no HEAD revisado;
  - a lista "Revisões de segurança adiadas" do README, que diz onde focar, com os achados estacionados que ela cita.
- **Correção:** cada achado Crítico ou Importante, pelo ciclo normal do orquestrador.
- **Registro:** o destino de cada achado no handoff, e a marca de paga em cada entrada da lista do README.
- **Pacote:** gerado de novo, se a etapa mudar código de produção.

**Fora de escopo:**

- funcionalidade nova;
- revisar de novo, a fundo, o que já teve revisão (M3 E01 e as Tasks 1 a 8 da E02). Esse código está no intervalo, mas a lista do README diz o que ficou sem revisão;
- mudar o plano ou o handoff das etapas já fechadas.

**Critérios de aceite:**

- [ ] O handoff traz o parecer da `revisor-seguranca` sobre `a300a23..HEAD`: os dois SHAs do intervalo, o modelo usado e a contagem de achados por severidade.
- [ ] O handoff cita a saída do scanner de dependências no HEAD revisado e a de `check_test_integrity.py --base a300a23`. Cada apontamento e cada aviso dela tem a justificativa da revisora.
- [ ] Cada achado Crítico ou Importante está corrigido, com o commit citado. O que o usuário decidir não corrigir está registrado como decisão dele, com a data.
- [ ] Cada achado Menor e cada achado estacionado que a lista do README cita tem destino registrado: corrigido, aceito com o motivo ou levado a uma etapa nomeada.
- [ ] Em `docs/roadmaps/README.md`, cada entrada de "Revisões de segurança adiadas" traz a marca de paga, com a data e esta etapa.
- [ ] O gate do projeto passa no HEAD final da etapa.
- [ ] Se a etapa mudou código de produção, o pacote de demo foi gerado de novo depois da última correção, e o handoff diz que é ele o pacote da lista "Validações acumuladas no Windows".

**Validação obrigatória:**

- Gate do projeto (`./gradlew build` e `bash tests/run.sh`) → passa.
- Scanner de dependências → saída inteira no pacote da revisão.
- `check_test_integrity.py --base a300a23` → saída inteira no pacote da revisão.

**Revisão humana:** não

**Notas para o plano:**

- A etapa tem duas fases, e a revisão vem antes do plano: os achados dela são a spec do plano de correção. Sem achado Crítico nem Importante, não há plano de correção, e a etapa fecha com a evidência da revisão.
- Em `--base a300a23` reaparecem os apontamentos que a lista do README já descreve: o `MERGE_IGNORADO` do merge da `main`, os três `DEV_TESTE_DO_TESTADOR` da E02 e os `SKIP_ADICIONADO` das travas `nao_tem '@Disabled'` e `nao_tem '@Ignore'`. A revisora os julga com essa descrição em mãos.
- As correções passam pelo ciclo normal, com revisão por tarefa e revisão final. A revisão final da etapa cobre o intervalo das correções.
- Achados em features diferentes não dependem uns dos outros, e as correções deles podem ir em tarefas da mesma onda.
- A E05 é o gate e gera o pacote antes desta etapa. Por isso o último critério: a validação no Windows usa o pacote mais recente (regra 7 do README).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Os achados não cabem num plano de poucas tarefas. O orquestrador corrige os Críticos, para e relata, e as etapas de correção que faltarem são criadas numa sessão do arquiteto.
- Um achado pede mudança de contrato ou de fronteira entre módulos. É decisão de arquitetura: vai ao usuário por entrevista, conforme o protocolo.

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
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`, servidor `57eb5fac5b8a`): a E03 ganha a confirmação da reserva em PDF, no visualizador da M2 E07 ([ADR 0009](../adr/0009-documentos-pdf-e-impressao.md)). A impressão pela fila do servidor fica com a M7 E12. Nenhuma tag mudou.
- 2026-11-15 — análise da rodada 3 do M0 (`claude --agent arquiteto`, servidor `5e06f16d51ff`): o fechamento do dia do servidor marca o no-show e avança a data às 03:00. A E04 ganhou a nota da reativação de no-show, e a E05 a da recriação no mesmo dia. O marco passa a exigir a M0 E03 antes da E01 (regra 3). Nenhuma tag mudou.
- 2026-11-17 — fim do lote do processo acelerado (`claude --agent orquestrador`): E02, E03 e E04 `#concluida`, com a revisão de segurança adiada (ADR 0011); E05 `#em-andamento`, parada a pedido do usuário. O usuário decidiu em entrevista abrir dois pedidos não bloqueantes ao servidor (D1 e D2 em "Hipóteses e decisões em aberto").
- 2026-11-17 — revogação do processo acelerado ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), `claude --agent arquiteto`): a seção "Prompt acelerado" foi removida. O protocolo e o prompt de execução não mudaram, porque já remetem ao orquestrador para o ciclo por tarefa. A E05, iniciada no processo acelerado, não foi reescrita: o handoff dela registra que o restante segue o processo normal, e o trecho já commitado (`830f296..920dcd2`) entrou na lista "Revisões de segurança adiadas" do README. Por decisão do usuário em entrevista, a dívida de revisão é paga antes do M4: entra a E06. Nenhuma tag mudou.
