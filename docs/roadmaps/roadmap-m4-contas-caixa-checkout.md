# Roadmap — M4 Contas, caixa e check-out

> Arquivo: `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` · branch de execução: `roadmap/m4-contas-caixa-checkout` · criado em 2026-11-10 · última revisão 2026-11-10

> **Pré-requisito:** a etapa E05 ("Gate do marco M3") de `docs/roadmaps/roadmap-m3-reservas.md` está `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

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
    - `GET /api/v1/folios/{id}/statement`.
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
- extrato;
- seed ampliado;
- roteiro do M4.

**Fora de escopo:**

- cadastro de pontos de caixa, códigos de lançamento e regras de roteamento (M7);
- Pix e cartão integrados, TEF e fiscal (M7 e M8 do servidor, ainda não existem);
- impressão do extrato (servidor M5 E11, pendente).

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
- **Servidor:** só leitura, e o commit fixado não muda no marco.
- **Idioma:** UI em pt-BR, código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O check-out do servidor devolve erro tipado (`code`) quando há saldo, caução ou conta aberta. Com isso o front monta uma lista de pendências antes de confirmar | O assistente de check-out passa a conferir a conta por leitura antes de chamar o check-out | E04 |
| H2 | O seed consegue abrir um turno para a Recepcionista e lançar consumos nas contas dos hospedados pela API | O roteiro abre o turno à mão no início | E05 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] `smokeTest` verde contra o ambiente recriado.
- [ ] Roteiro `docs/demo/roteiro-m4.md`, acumulando os anteriores, executado pelo usuário no Windows.
- [ ] Teste de idempotência em toda operação financeira do marco.
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
| E01 | Turno de caixa | #pendente | — | Abrir, acompanhar, sangria e suprimento, fechar às cegas e relatório |
| E02 | Conta da hospedagem | #pendente | — | Conta com lançamentos, lançar, estornar, ajustar, dividir, transferir, taxa de serviço, pagador e extrato |
| E03 | Pagamentos e cauções | #pendente | E01, E02 | Receber e reembolsar, registrar, capturar e liberar caução, cauções a vencer |
| E04 | Check-out | #pendente | E03 | Assistente de check-out até o saldo zero, contas encerradas e UH liberada |
| E05 | Gate do marco M4 | #pendente | E04 | Seed com contas e turno, roteiro do M4 e definição de pronto verificada |

---

## E01 — Turno de caixa — #pendente

**Objetivo:** a operadora abre o próprio turno num ponto de caixa e vê o turno corrente. Registra sangria e suprimento e fecha às cegas, informando o contado. O app mostra o relatório de fechamento com as diferenças. O topo indica se há turno aberto.

**Por que agora:** o pagamento (E03) e o check-out (E04) exigem turno aberto.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M3).

**Escopo:**

- **Pontos de caixa:** `GET /api/v1/cash-points` (só leitura).
- **Turno:**
  - `GET`/`POST /api/v1/cashier-shifts` e `GET /api/v1/cashier-shifts/current`;
  - `POST /api/v1/cashier-shifts/{id}/movements` e `/close`;
  - `GET /api/v1/cashier-shifts/{id}/report`.
- **Topo da casca:** indicador de turno.

**Fora de escopo:** cadastro de pontos de caixa (M7).

**Critérios de aceite:**

- [ ] Abrir o turno como Recepcionista do seed: o indicador aparece no topo, e reabrir com um turno já aberto mostra mensagem em pt-BR.
- [ ] Sangria e suprimento aparecem no turno, e repetir após erro de rede não duplica (`Idempotency-Key`, se a operação exigir).
- [ ] O fechamento cego não mostra o esperado antes de informar o contado. O relatório mostra a diferença em BRL.
- [ ] A diferença de caixa gera a notificação ao Gerente no servidor (conferida por `GET /api/v1/notifications` como Gerente). A tela de notificações é do M6.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** "turno cego" e a notificação de diferença foram validados no gate do M3 do servidor (histórico do `docs/roadmaps/README.md` do servidor, 2026-10-20).

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

## E02 — Conta da hospedagem — #pendente

**Objetivo:** a partir do detalhe da reserva ou da área Contas e caixa, o usuário abre a conta. Ele vê os lançamentos, o saldo e a taxa de serviço separada. Lança consumos por código de lançamento, estorna, ajusta, divide, transfere entre contas e remove a taxa a pedido do cliente. Também troca o pagador e o limite de crédito e vê o extrato `GUEST` e `INTERNAL`.

**Por que agora:** a conta é o centro financeiro da hospedagem e pré-requisito do check-out.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Contas:** `GET /api/v1/folios`, `GET`/`PUT /api/v1/folios/{id}` e `POST /api/v1/folios`, se a conta avulsa fizer sentido na tela.
- **Lançamentos:**
  - `GET`/`POST /api/v1/folios/{id}/postings`;
  - `/reverse`, `/adjust`, `/split` e `/remove-service-charge` em `POST /api/v1/folios/{id}/postings/{postingId}/…`;
  - `POST /api/v1/folios/{id}/postings/transfer`.
- **Códigos de lançamento:** `GET /api/v1/transaction-codes`.
- **Extrato:** `GET /api/v1/folios/{id}/statement`.
- **Aprovação de superior:** diálogo onde o servidor exigir.
- **Atalho:** "Conta" no detalhe da reserva.

**Fora de escopo:**

- encerrar e reabrir a conta, que ficam no check-out (E04) ou num atalho gerencial registrado no handoff;
- pagamento (E03).

**Critérios de aceite:**

- [ ] Lançar um consumo numa conta do seed atualiza o saldo e a taxa de serviço separada, com os valores iguais aos da API.
- [ ] Estornar como Recepcionista sem permissão abre o diálogo de aprovação. Aprovado pelo Gerente, o estorno aparece como lançamento novo, sem apagar o original.
- [ ] Duplo clique em "lançar" gera um só lançamento (teste com a mesma `Idempotency-Key`).
- [ ] O extrato `GUEST` não mostra os lançamentos internos que o servidor omite.
- [ ] Um tipo de lançamento desconhecido aparece como "Outro (VALOR)".

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** o `type` do lançamento foi o primeiro `x-extensible-enum` do servidor (ARCHITECTURE §7).

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

**Fora de escopo:** Pix, TEF e cartão integrados, que não existem no servidor.

**Critérios de aceite:**

- [ ] Receber sem turno aberto mostra a mensagem do `code` em pt-BR e o atalho "abrir turno".
- [ ] Receber o saldo de uma conta do seed zera o saldo, e o pagamento aparece no turno.
- [ ] Teste: repetir o recebimento após falha de rede usa a mesma `Idempotency-Key`, e o servidor não duplica.
- [ ] O formulário de caução não tem campo de número de cartão (inspeção e teste do *state holder*).
- [ ] Liberar a sobra da caução muda a situação e o saldo conforme a API.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

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

**Notas para o plano:** a M5 E04 do servidor define o check-out (saldo zero, cauções liberadas, contas encerradas, UH Vago + Sujo, late check-out). Leia o handoff dela e a H14 do roadmap do M5 do servidor.

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

**Revisão humana:** sim. Gate do marco e a primeira build com o ciclo completo da hospedagem.

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
