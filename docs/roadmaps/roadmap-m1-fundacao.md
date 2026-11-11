# Roadmap — M1 Fundação e mapa de UHs

> Arquivo: `docs/roadmaps/roadmap-m1-fundacao.md` · branch de execução: `roadmap/m1-fundacao` · criado em 2026-11-10 · última revisão 2026-11-10

> **Pré-requisito:** a etapa E01 de `docs/roadmaps/roadmap-m0-sincronizacao.md` está `#concluida`, com o commit fixado, o contrato copiado e o ambiente `zeny-front` no ar.

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m1-fundacao`. Cada etapa é planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain). No fim, a execução para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

Ter um app desktop Compose que abre e faz login num servidor escolhido na tela. O app mostra o menu conforme as permissões do usuário e exibe o **mapa de UHs ao vivo**, atualizado por SSE. A base fica pronta para as features seguintes: SDK gerado, núcleo HTTP e de sessão, design system, aprovação de superior, tempo real e `demo-seed`. No gate, o usuário roda o JAR no Windows contra o servidor da demo, já populado.

## Contexto confirmado

- Stack decidida ([ADR 0001](../adr/0001-stack-compose-desktop.md)): Compose Multiplatform Desktop, Kotlin, JDK 25 e Gradle pelo wrapper. Versões e bibliotecas são hipóteses, ver H1 a H3.
- Na máquina há JDK `openjdk 25.0.5` e Docker 29. Não há Gradle global, Node nem Rust. O wrapper do Gradle existe no servidor (`/home/caio/repos/ZenyHotelManager/gradlew` e `gradle/wrapper/`), que usa Gradle 9.8.
- Contrato em `api/openapi.yaml`, cópia da M0 E01, OpenAPI 3.0.1. Pontos relevantes:
  - **auth:** `POST /api/v1/auth/login` (`LoginRequest`: `login`, `password`, `device`), `/refresh`, `/logout`, `/password`, `GET /api/v1/auth/me` (`MeResponse`: `permissions`, `roles`, `sessionId`), `POST /api/v1/auth/approvals`;
  - **sistema:** `GET /api/v1/meta/version`, `GET /api/v1/stream?topics=…` com `Last-Event-ID`;
  - **leitura do hotel e das UHs:** `GET /api/v1/hotel`, `GET /api/v1/hotel/business-date`, `GET /api/v1/rooms/status`, `GET /api/v1/room-types`, `GET /api/v1/areas`.
  - **91 propriedades `x-extensible-enum`.**
- Convenções da API: ARCHITECTURE §7, §8.1, §8.2 e §10 do servidor e a descrição `info` do contrato. Erros em RFC 9457 com `code`.
  - `401 UNAUTHENTICATED` pede refresh; os demais `401`, novo login.
  - `403 PASSWORD_CHANGE_REQUIRED` e `403 APPROVAL_REQUIRED`, este com `action`, `permission`, `resourceRef` e `limit`.
  - `ETag`/`If-Match`, `Idempotency-Key`, paginação por cursor e dinheiro como string decimal.
- Modelos de role criados no boot do servidor (SPEC §3.1 e ARCHITECTURE §8.2): Administrador, Gerente, Recepcionista, Governanta etc. O administrador inicial vem de `ZENY_ADMIN_LOGIN`/`ZENY_ADMIN_PASSWORD` no primeiro boot.
- Design: [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6 (arquitetura do app, regras transversais, endereço do servidor, pacote, testes) e §7.2 (`demo-seed`). ADRs [0001](../adr/0001-stack-compose-desktop.md), [0004](../adr/0004-endereco-do-servidor-configuravel.md) e [0006](../adr/0006-marcos-por-valor-de-demo.md).
- Conhecimento do projeto no Second Brain: o hub `ZenyHotelManager-DesktopFront`, depois do registro. Do servidor: as notas `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Segurança, autenticação e autorização`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Módulo rooms` e `ZenyHotelManager - Módulos hotel e staff`.

## Escopo

**Incluído:**

- o projeto Gradle com os módulos `sdk`, `core`, `design`, `app` e `demo-seed`;
- a escolha do gerador do SDK;
- o núcleo HTTP e de sessão;
- o design system;
- login com endereço do servidor e troca de senha obrigatória;
- a casca do app (menu por permissão, data operacional e hotel no topo);
- aprovação de superior genérica;
- cliente SSE;
- `demo-seed` com a configuração e as reservas do dia;
- mapa de UHs ao vivo (só leitura);
- o JAR multiplataforma e o pacote da demo;
- o roteiro de demo do M1.

**Fora de escopo:**

- telas de operação além do mapa de UHs (M2 em diante);
- ações sobre UHs, como status, OOO e observações (M6);
- cadastros (M7);
- instalador nativo;
- modo offline;
- testes de UI além dos componentes do `design` que valem a pena.

## Restrições e invariantes

- **Regras transversais do design §6.2**, válidas para todas as etapas:
  - nada de JSON ou `code` cru ao usuário;
  - enum extensível tolerado ("Outro (VALOR)");
  - uma `Idempotency-Key` por intenção, reaproveitada no reenvio e após aprovação;
  - `ETag` e `If-Match` com tratamento do `412`;
  - menu e ações por permissão efetiva, com o servidor decidindo;
  - dinheiro em `BigDecimal`, nunca `Double`;
  - instantes no fuso do hotel e datas de negócio sem conversão.
- **Tokens e LGPD:** access e refresh token só em memória. Em disco, só o endereço do servidor, o último login e o id do dispositivo. Nenhum dado de hóspede em log nem em cache de disco. Documento sempre mascarado.
- **Uso do SDK:** as features não chamam o `sdk` direto, só pelo `core` ou por repositórios em cima dele. O código gerado não é versionado.
- **O servidor é só leitura:** nenhuma sessão escreve em `/home/caio/repos/ZenyHotelManager`. Copiar arquivos de lá para o front, como o wrapper do Gradle, é leitura do servidor.
- **Idioma:** UI e documentação em pt-BR; código em inglês.
- **Contrato fixo:** não se troca o commit fixado neste marco (M0).

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Compose Multiplatform 1.12.x (estável em agosto de 2026) compila e roda com Kotlin 2.4 e JDK 25 — **confirmada na E01** (1.12.2, Kotlin 2.4.21, JDK 25.0.5) | Ajustar versões (Kotlin ou JDK alvo de bytecode) na própria E01 | E01 |
| H2 | Um gerador Kotlin cumpre o critério do design §9: compila o contrato inteiro, expõe `ETag` e headers de resposta, aceita headers por chamada e gera `x-extensible-enum` como `String` — **confirmada na E01** com o ogen 0.16.1 (ADR 0007); o openapi-generator 7.27.0 não cumpriu | Cliente escrito à mão no `core` para os recursos das features da demo; registrar a decisão em ADR | E01 |
| H3 | Um JAR único com as bibliotecas nativas do Skia de Linux x64 e Windows x64 roda com `java -jar` nos dois sistemas — **confirmada na E01**: Linux pelo orquestrador; Windows pelo usuário em 2026-11-11 (`java -jar zeny-desktop.jar` abriu a janela normalmente) | Distribuir por `./gradlew run` num clone no Windows | E01, E07 |
| H4 | O SSE passa pelo proxy Caddy do servidor sem buffering que atrase os eventos | Pedido `PF-NN` ao servidor e, até lá, atualização periódica no mapa | E06 |
| D1 | **Decidido pelo usuário em 2026-11-11 (entrevista da parada da E01):** fronteira `core`/`sdk` pela opção (b) da ADR 0008 — `core` expõe o `sdk` por `api`, as features usam os modelos gerados e o `ForbiddenImport` barra as classes de operação — **fechando a brecha**: barrar também `io.ktor.client.*` e `de.quati.ogen.client.*` em `app` e `design` | Ajuste do `config/detekt/detekt.yml` e da varredura de nome qualificado ainda não feito: entra no plano da E02 | E02 em diante |
| D2 | **Decidido pelo usuário em 2026-11-11:** depois do `403 APPROVAL_REQUIRED`, a repetição com `X-Approval-Token` usa uma **nova** `Idempotency-Key`, como diz o contrato (por exemplo, `cancelReservation`); o reenvio por falha de rede continua com a mesma chave. Substitui o "mesma chave" do design §6.2 e do critério da E02 | A E02 testa a nova chave na repetição aprovada; o design §6.2 fica para o arquiteto atualizar | E02, E04 |
| D3 | **Decidido pelo usuário em 2026-11-11:** gerador do SDK confirmado, ogen 0.16.1 (ADR 0007) | Plano B na ADR 0007 se uma versão nova quebrar | todas |
| H5 | O `demo-seed` cria toda a configuração do M1 pela API com o administrador inicial, inclusive usuários e PIN (`POST /api/v1/users/{id}/reset-pin`), sem SQL direto | Lacuna vira pedido `PF-NN`; o roteiro se adapta ao que a API permite | E05 |

## Definição global de pronto

- [ ] `./gradlew build` verde, com os comandos oficiais registrados no README e na tabela de comandos do `CLAUDE.md`.
- [ ] O JAR do app abre no Linux e no Windows (no Windows, conferido pelo usuário no gate).
- [ ] O `smokeTest` passa contra o ambiente `zeny-front`.
- [ ] O roteiro `docs/demo/roteiro-m1.md` foi executado pelo usuário, do jeito que está escrito, contra a demo recriada.
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

- Toda execução acontece no worktree/branch `roadmap/m1-fundacao`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m1-fundacao-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m1-fundacao Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m1-fundacao.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m1-fundacao com superpowers:using-git-worktrees. Não trabalhe na branch principal.
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
| E01 | Esqueleto Gradle, SDK gerado e JAR multiplataforma | #concluida | — | Módulos, gerador escolhido, janela que abre, comandos oficiais, JAR Linux + Windows |
| E02 | Núcleo HTTP e sessão | #concluida | E01 | Login, refresh em voo único, erros em pt-BR, idempotência, ETag, enum extensível, tokens em memória |
| E03 | Design system | #em-andamento | E01 | Tema, tabela com cursor, formulário com erros por campo, campos de dinheiro, data e documento, diálogos e estados |
| E04 | Login, casca do app e aprovação de superior | #pendente | E02, E03 | Tela de login com servidor, troca de senha, menu por permissão, topo com hotel e data, diálogo de aprovação |
| E05 | Dados de demonstração e teste de fumaça | #pendente | E02 | `demo-seed` idempotente em menos de 2 minutos, JAR do seed, `smokeTest` |
| E06 | Tempo real e mapa de UHs ao vivo | #pendente | E04, E05 | Cliente SSE com reconexão e mapa de UHs atualizado em tempo real |
| E07 | Gate do marco M1 | #pendente | E06 | Pacote da demo com os JARs, roteiro do M1 e definição de pronto verificada |

---

## E01 — Esqueleto Gradle, SDK gerado e JAR multiplataforma — #concluida

**Objetivo:** projeto Gradle com os módulos `sdk`, `core`, `design`, `app` e `demo-seed`. O `sdk` é gerado de `api/openapi.yaml` por um gerador escolhido com critério explícito. Uma janela "Zeny Hotel Manager" abre com `./gradlew run`, e um JAR único roda no Linux e no Windows.

**Por que agora:** confirma cedo as três incógnitas mais caras do marco (H1, H2 e H3). Também define as convenções e os comandos oficiais que todas as etapas seguintes repetem.

**Dependências:** nenhuma dentro deste roadmap. Exige a M0 E01 `#concluida`.

**Escopo:**

- wrapper do Gradle, copiado do servidor (só leitura) ou gerado;
- catálogo de versões;
- módulos e dependências entre eles, conforme o design §6.1;
- geração do `sdk` no build, sem versionar o código gerado;
- uma descoberta curta comparando no máximo dois geradores contra o critério do design §9, com a decisão registrada em `docs/adr/0007-gerador-do-sdk.md` (a criar; contexto, alternativas e consequências);
- `./gradlew run` abre a janela;
- tarefa que gera o JAR do app com as bibliotecas nativas de Linux x64 e Windows x64;
- detekt e ktlint, ou equivalente, como lint oficial;
- `.gitignore` ampliado com os artefatos do Gradle e do Kotlin;
- comandos oficiais (build, teste, lint, run, JAR) no `README.md` e na tabela de comandos do `CLAUDE.md` (se o `CLAUDE.md` ainda não existir, crie-o só com as seções Projeto, Stack e comandos, Git e Decisões do usuário).

**Fora de escopo:** qualquer tela além da janela vazia, o núcleo HTTP (E02) e o design system (E03).

**Critérios de aceite:**

- [x] `./gradlew build` verde num clone limpo.
- [x] O `sdk` gerado compila o contrato inteiro, sem excluir operações.
- [x] A ADR 0007 registra o gerador, mostrando o critério do design §9 item a item, com evidência: um teste do `sdk`, ou do `core` usando o `sdk`, que lê o `ETag` de uma resposta, envia `Idempotency-Key`, `If-Match` e `X-Approval-Token` numa chamada e desserializa um valor desconhecido de `x-extensible-enum` sem falhar.
- [x] `./gradlew run` abre a janela com o título "Zeny Hotel Manager".
- [x] O JAR abre a janela com `java -jar` no Linux. No Windows, o usuário confere na parada da etapa.
- [x] Os comandos oficiais estão no `README.md` e no `CLAUDE.md`.

**Validação obrigatória:**

- `./gradlew build` → verde.
- Comando de lint registrado → verde.
- `java -jar <JAR gerado>` no Linux → janela abre. Registre o caminho real do JAR no handoff.

**Revisão humana:** sim. Ela define o gerador, os módulos e as convenções que todo o resto repete, e confirma o JAR no Windows.

**Notas para o plano:**

- Não há Gradle global na máquina. O servidor usa Gradle 9.8 pelo wrapper.
- A documentação oficial do gerador Kotlin do openapi-generator parecia desatualizada em 2026-11-10 (design §9). Confira a versão real antes de escolher.
- O contrato tem 303 operações: tempo de geração e de compilação contam no critério.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Nenhum gerador cumpre o critério. Gerar só os modelos e escrever à mão o cliente das operações da demo no `core`, registrando isso na ADR 0007.
- O JAR multiplataforma falha no Windows. Usar `./gradlew run` num clone no Windows (H3).

**Evidências e handoff:**

- **Estado (2026-11-11):** `#concluida`. Implementada, revisada e integrada à `main`; a parada humana fechou com as decisões D1 a D3 confirmadas na entrevista e o JAR conferido pelo usuário no Windows.
- **Plano:** [`docs/superpowers/plans/2026-11-10-m1-fundacao-e01.md`](../superpowers/plans/2026-11-10-m1-fundacao-e01.md), com 11 tarefas.
  - A revisão de arquitetura bloqueou na rodada 0 (1 Crítico: o `./gradlew --stop` derrubava os daemons do servidor) e aprovou com notas na rodada 1.
- **Commits:** `9d1fb5e..26306a0` na branch `roadmap/m1-fundacao`, cada um com o trailer `Soul:`.
  - O gate no checkout principal pegou um defeito que o worktree não mostrava. O `tests/test_gradle_layout.sh` varria os `*.gradle.kts` do disco e pegava a árvore do servidor em `.server/`.
  - O merge local foi desfeito antes de qualquer push. O testador corrigiu o teste em `26306a0`, que passou a usar `git ls-files`, e a correção foi re-revisada.
- **Revisão final:** aprovado com ressalvas ("Ready to merge: Yes"), sem achado Crítico.
  - A onda única de correção (frases desatualizadas nas ADRs 0007 e 0008, no README e num comentário de teste) foi re-revisada e está limpa.
  - Scanner `osv-scanner -r --no-ignore .`: 6 lockfiles (131 a 344 pacotes cada), "No issues found".
  - A verificação mecânica dos testes saiu com 5 apontamentos, todos justificados pela revisora.
- **Critérios verificados:**
  - **`./gradlew build` verde num clone limpo:** clone em `62ea5bf`, BUILD SUCCESSFUL em 1m19s (67 tarefas), `git status` vazio depois do build. No worktree, `./gradlew build --rerun-tasks` passou com 41 testes Kotlin, 0 falhas e 0 pulados. `bash tests/run.sh` passou com 24 arquivos e 0 falhas; no clone fora do checkout principal, dois testes da M0 pulam casos porque procuram o servidor relativo ao checkout.
  - **O `sdk` gerado compila o contrato inteiro:** ogen 0.16.1 gera 303 métodos de operação para 303 `operationId`, conferido por `SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod`. O código gerado fica em `sdk/build/` e não é versionado.
  - **A ADR 0007 registra o gerador item a item:** [`docs/adr/0007-gerador-do-sdk.md`](../adr/0007-gerador-do-sdk.md). A evidência permanente é `SdkCriteriaTest`: lê o `ETag`, envia `Idempotency-Key`, `If-Match` e `X-Approval-Token` cada um exatamente uma vez, aceita enum extensível desconhecido e propriedade desconhecida, e mantém dinheiro, data e instante exatos na leitura e na escrita.
  - **`./gradlew run` abre a janela "Zeny Hotel Manager":** conferido com `xwininfo` (`"Zeny Hotel Manager": ("com-zeny-desktop-app-MainKt" …) 800x563`).
  - **O JAR abre a janela com `java -jar` no Linux:** o JAR é `app/build/libs/zeny-desktop.jar` (59.420.229 bytes, gerado por `./gradlew :app:desktopJar`). Do clone limpo, ele abriu a janela `"Zeny Hotel Manager"` sem nada em stderr. O `verifyDesktopJar` (ligado ao `check`) exige os nativos do Skia de Linux x64 e Windows x64. **No Windows: conferido pelo usuário em 2026-11-11 — `java -jar zeny-desktop.jar` abriu a tela normalmente.**
  - **Os comandos oficiais estão no `README.md` e no `CLAUDE.md`,** e o `tests/test_docs.sh` exige a mesma tabela nos dois.
  - **Validação:** lint `./gradlew ktlintCheck detekt` verde.
- **Respostas do usuário na entrevista (2026-11-11):** fronteira `core`/`sdk` pela opção (b), fechando a brecha de `io.ktor.client.*` e `de.quati.ogen.client.*` (D1); nova `Idempotency-Key` na repetição após aprovação, como diz o contrato (D2); ogen confirmado (D3). Ver "Hipóteses e decisões em aberto". O JAR no Windows foi conferido pelo usuário no mesmo dia (H3 confirmada).
- **Decisões para o usuário** (tomadas pela opção recomendada; as três de arquitetura já confirmadas na entrevista, as demais revisáveis):
  - **Gerador do SDK:** ogen 0.16.1, e não openapi-generator 7.27.0, que não compila o contrato com kotlinx no `jvm-ktor`. O ogen é 0.x, de um mantenedor só, e a versão saiu 4 dias antes da adoção. Plano B na ADR 0007.
  - **Fronteira `core`/`sdk`:** ADR 0008, aceita (D1). O `core` expõe o `sdk` por `api` e o `ForbiddenImport` barra as classes de operação em `app` e `design`. A brecha de `io.ktor.client.*` e `de.quati.ogen.client.*` foi decidida: fechar, com o ajuste do lint no plano da E02.
  - **`Idempotency-Key` na repetição após aprovação:** decidido (D2): nova chave, como diz o contrato (por exemplo, `cancelReservation`); o reenvio por falha de rede mantém a chave. O critério da E02 já foi ajustado; o design §6.2 (e as menções nos roadmaps M2 e M4) ficam para o arquiteto alinhar.
  - **Enum fechado com valor novo derruba a resposta inteira** (`JsonConvertException`). Aceito na M1 porque o servidor está fixado; o `core` deve traduzir isso numa mensagem de "versão incompatível".
  - **Vulnerabilidades no build:** o classpath dos plugins tinha 7 vulnerabilidades High (guava, jackson e handlebars, que chegam pelo ogen e pelo openapi-generator) e a configuração do ktlint trazia o logback com Low. Elas foram corrigidas por constraints no `buildscript` (jackson-bom 2.22.3, guava 33.7.2-jre, handlebars 4.5.2 e logback 1.5.34). O catálogo registra os motivos e a condição de saída.
  - **Travamento de dependências:** estrito, com lockfiles em todos os projetos e no classpath dos plugins, seguindo o precedente do servidor. A regravação é `./gradlew resolveAndLockAll --write-locks`.
  - **Checksums das dependências:** não há `gradle/verification-metadata.xml`, e os lockfiles travam versão, não hash. Recomenda-se decidir antes da E07, quando sai o pacote do Windows.
- **Notas para a próxima etapa (E02 e E03):**
  - O `sdk` devolve dinheiro, data e instante como `String`. O `core` converte para `BigDecimal(String)` e escreve com `toPlainString()`, nunca com `toString()` ou `Double`. Isso precisa de teste.
  - O cliente gerado não lança exceção em 4xx. O `core` checa `raw.status` antes de chamar `body()` e lê o RFC 9457 sozinho. `body()` num erro lança `JsonConvertException`, que não é subclasse de `SerializationException`.
  - Header por chamada vai pelo `block` com `headers[n] = v`, que substitui. `header(...)` duplica. A ADR 0007 diz qual header vence.
  - O `Json` do `core` precisa de `ignoreUnknownKeys = true` e `encodeDefaults = false`, com um teste contra o `Json` do próprio `core`.
  - Na E05, ao pôr `application` no `demo-seed`, o comando oficial `./gradlew run` passa a subir os dois módulos.
  - Nunca use `./gradlew --stop`: o Gradle 9.8.1 e o `~/.gradle` são compartilhados com o servidor. Nesta etapa, um build morreu com "stop command received" vindo de fora; repita uma vez.
  - Rodar `./gradlew check` gera o JAR de 59 MB, porque `DesktopJarTest` e `verifyDesktopJar` dependem dele.
  - Para a E07:
    - rever `DuplicatesStrategy.EXCLUDE` no JAR, que descarta em silêncio colisões, inclusive de NOTICE e LICENSE;
    - trocar `contains("/build/generated/")` por `invariantSeparatorsPath`, para o build rodar no Windows.

---

## E02 — Núcleo HTTP e sessão — #concluida

**Objetivo:** o módulo `core` oferece às features uma sessão autenticada e um pipeline HTTP que cumpre as convenções da API. As features nunca tratam token, refresh, erro cru, idempotência ou `ETag` à mão.

**Por que agora:** todas as telas dependem disso, e o `demo-seed` (E05) também.

**Dependências:** E01.

**Escopo:**

- **Sessão:** login (com os dados do dispositivo, cujo id é persistido), refresh em voo único sob requisições concorrentes, logout, troca de senha, expiração e revogação. Tokens só em memória.
- **Pipeline HTTP:** `Authorization`, `Idempotency-Key` por intenção, `ETag`/`If-Match` com `412` tipado e paginação por cursor.
- **Erros:** problema RFC 9457 vira um erro tipado com `code`, `detail` e `errors[]`. Há um catálogo `code → mensagem pt-BR` para os códigos já presentes no contrato, com o `detail` como reserva.
- **`APPROVAL_REQUIRED`:** vira um erro tipado com `action`, `permission`, `resourceRef` e `limit`. O `core` oferece uma operação que repete a chamada com `X-Approval-Token` e uma **nova** `Idempotency-Key` (decisão D2 do usuário, 2026-11-11; o reenvio por falha de rede mantém a chave).
- **Tipos:** `Money` sobre `BigDecimal`, data de negócio e instante no fuso do hotel.
- **Enum extensível:** um utilitário que resolve o rótulo e devolve "Outro (VALOR)" para o desconhecido.
- **Endereço do servidor:** resolução da URL base pela precedência do design §6.3 (valor salvo, `ZENY_SERVER_URL`, `.env` ao lado do JAR, `http://localhost:8088`).

**Fora de escopo:** telas (E04), SSE (E06) e o diálogo de aprovação (E04).

**Critérios de aceite:**

- [x] Teste: 5 requisições concorrentes com access token vencido → um único `POST /api/v1/auth/refresh`, e todas concluem.
- [x] Teste: `401 UNAUTHENTICATED` → refresh e repetição. Outro `401` → evento de "sessão encerrada", sem repetição.
- [x] Teste: `403 APPROVAL_REQUIRED` → erro tipado com os quatro campos. A repetição envia `X-Approval-Token` e uma **nova** `Idempotency-Key` (D2); um reenvio por falha de rede mantém a chave.
- [x] Teste: `412` → erro tipado de conflito.
- [x] Teste: valor desconhecido de enum extensível → rótulo "Outro (VALOR)", sem exceção.
- [x] Teste: os tokens não aparecem em nenhum arquivo escrito pelo app, nem nas preferências, nem em log.
- [x] Teste: a precedência da URL base segue o design §6.3.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint registrados na E01 → verdes.

**Revisão humana:** não

**Notas para o plano:** a descrição `info` do contrato e a ARCHITECTURE §7 e §8.1 do servidor definem a semântica de cada `401` e `403`. Leia-as, não deduza. **Decisão D2 do usuário (2026-11-11):** a repetição após aprovação usa uma **nova** `Idempotency-Key` (escopo e critério acima já ajustados). **Decisão D1:** acrescentar `io.ktor.client.*` e `de.quati.ogen.client.*` ao `ForbiddenImport` de `app` e `design` (e à varredura de nome qualificado dos testes). Leia também as "Notas para a próxima etapa" do handoff da E01.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O cliente gerado não permite interceptar e repetir a chamada. Fazer a interceptação na camada HTTP subjacente, decisão já tomada na E01.

**Evidências e handoff:**

- **Estado (2026-11-11):** `#concluida`.
- **Plano:** [`docs/superpowers/plans/2026-11-11-m1-fundacao-e02.md`](../superpowers/plans/2026-11-11-m1-fundacao-e02.md), com 10 tarefas. A revisão de arquitetura bloqueou na rodada 0 (7 Importantes: troca de senha encerrando a sessão, refresh cancelado levando a `REFRESH_TOKEN_REUSED`, refresh fora do voo único, padrão do `Money`, `@RawSdkAccess` sem registro, dependência escondida entre ondas, tamanho da Task 3) e aprovou com notas na rodada 1.
- **Commits:** `31a1df6..b3277bc` na branch `roadmap/m1-fundacao` (44 commits, cada um com o trailer `Soul:`). As tarefas correram em série: a sessão isolada no worktree da etapa impede subagentes de escrever em worktrees de tarefa.
- **Revisão final:** "Ready to merge: With fixes" (1 Importante: um 5xx no `/auth/refresh` encerrava a sessão, defeito herdado do plano). A onda única de correção resolveu o Importante e 7 Menores; a re-revisão aprovou, com 3 Menores estacionados (abaixo).
  - Scanner `osv-scanner -r --no-ignore .`: 6 lockfiles (131 a 347 pacotes), "No issues found".
  - Verificação mecânica `--base 31a1df6`: 1 apontamento (asserção reescrita com regex mais amplo na Task 1), justificado; na onda final, 21 apontamentos da migração dos testes para as formas com sessão, todos justificados (equivalentes ou mais fortes).
  - Gate em `b3277bc`: `./gradlew build --rerun-tasks` BUILD SUCCESSFUL (71 tarefas), 396 testes no `core` e 0 falhas; `bash tests/run.sh` 25 arquivos e 0 falhas.
- **Critérios verificados:**
  - **5 concorrentes com token vencido → um refresh:** `CoreCriteriaTest.fiveConcurrentRequestsWithExpiredTokenRefreshOnce` (barreira com as 5 em A1; um `POST /auth/refresh`; 5×200).
  - **`401 UNAUTHENTICATED` → refresh e repetição; outro `401` → sessão encerrada sem repetição:** `CoreCriteriaTest.unauthenticatedRefreshesOtherUnauthorizedEndsSession`.
  - **`APPROVAL_REQUIRED` com os 4 campos; repetição com `X-Approval-Token` e chave nova (D2); reenvio por rede com a mesma chave e o mesmo token:** `CoreCriteriaTest.approvalRequiredEndToEnd`.
  - **`412` → erro tipado:** `CoreCriteriaTest.preconditionFailedEndToEnd`.
  - **Enum desconhecido → "Outro (VALOR)":** `ExtensibleEnumTest`.
  - **Tokens fora de arquivos, preferências e log:** `TokenLeakTest` (disco isolado com controle positivo, stdout/stderr, `toString`, mensagens, pilha e cadeia de causas; o `core` não tem binding de log).
  - **Precedência da URL base:** `ServerUrlResolverTest` (salvo, `ZENY_SERVER_URL`, `.env` ao lado do JAR, `http://localhost:8088`; fonte em branco ou inválida segue adiante; `.env` com BOM, CRLF e aspas).
  - **Validação:** build, testes e lint oficiais verdes.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  - **Troca de senha:** os `401 INVALID_CREDENTIALS`/`ACCOUNT_LOCKED` de `changePassword` mantêm a sessão (a descrição da operação prevalece sobre a leitura literal do `info`). Se o POST foi aceito e o refresh seguinte falhou (rede ou erro transitório), o estado expõe `passwordChangeAwaitingRefresh` e a interface só oferece "tentar de novo" (`retryPasswordChangeRefresh`), sem campos de senha.
  - **Refresh:** só reativo ao `401 UNAUTHENTICATED`, sem antecipação pelo relógio local. Encerra a sessão só com 401 (com ou sem corpo de problema), resposta 2xx ilegível ou `SessionEnded`; rede, 5xx e outros 4xx mantêm a sessão. Uma chamada recusada só é repetida com token da **mesma** sessão; logout ou novo login durante um refresh não são desfeitos.
  - **Reenvio aprovado:** o `Intent` guarda o `X-Approval-Token`; o reenvio por rede de uma chamada aprovada leva a mesma chave e o mesmo token (D2).
  - **Fronteira (ADR 0008 atualizada):** `ZenyCore.apis` e `ZenyCore.executor` exigem o opt-in `@RawSdkAccess` (nível ERROR); o detekt barra em `app` e `design` os imports de `io.ktor.client.*` e `de.quati.ogen.client.*` (D1) e a supressão `OPT_IN_USAGE_ERROR`/`OPT_IN_IS_NOT_ENABLED`; um teste de shell barra fonte `.java` em `app/src` e `design/src`.
  - **Engine HTTP:** `ktor-client-java` (do JDK), registrada na ADR 0007; `followRedirects = false` e só 2xx conta como sucesso.
  - **Mensagens de erro:** catálogo pt-BR para os 353 códigos de erro do contrato; `detail` do servidor só é mostrado quando não é JSON nem identificador cru; a causa de uma resposta ilegível não carrega o corpo (que ecoaria tokens).
  - **`Money`:** formato do contrato para BRL (`^-?\d+(\.\d{1,2})?$`); percentual e limite de aprovação não passam por ele.
- **Notas para a próxima etapa (E03 e E04):**
  - **E04:** as features chegam ao servidor só por fachadas do `core` (`getMe`, `getHotel`, data operacional, `grantApproval`), nunca por `executor`/`apis`. Trocar de servidor (ou o "testar" com outro endereço) é `session.logout()`, depois `close()` do `ZenyCore` antigo e um novo `ZenyCore`; chamadas depois do `close()` terminam em `CancellationException`. O `ZenyCore` é dono do engine e o fecha. O critério da E04 "repete a ação uma vez com a mesma chave" é substituído pela D2 (nova chave na repetição aprovada).
  - **E04:** a tela de login trata `SessionState.Ended`, `passwordChangeAwaitingRefresh` e o `IllegalStateException` de `changePassword` com a pendência ativa. Um `BackingStoreException` ao gravar `lastLogin` depois do login sai como exceção não-`ApiError`; tratar na tela. `jarDirectory` pode lançar `IllegalArgumentException` num caminho estranho; `ServerUrl` aceita caminho (`http://h/api` daria `/api/api/v1`) e esquema em maiúsculas: validar no "testar endereço". Quando o `app` ganhar um provider de log, conferir que nenhum token vai para o log.
  - **E03/E04:** o construtor de `Money`/`BusinessDate` é público e não valida, e o `equals` do `BigDecimal` diferencia escala (`10.5` ≠ `10.50`): o campo de dinheiro compara com `compareTo`. `CursorPager` não valida `pageSize` nem detecta ciclo de cursor com mais de um passo.
  - **E05:** o `demo-seed` usa `LocalSettings` próprio, com outro `deviceId` (um novo login no mesmo dispositivo encerra a sessão anterior dele).
  - **E06:** o `requestTimeoutMillis = 30_000` global corta o SSE; o cliente SSE sobrescreve o timeout por requisição.
  - **Menores estacionados:** o comentário "BOM por escape" de `ProblemMapperAttackTest` descreve um U+FEFF que está literal; o ramo `Unexpected(401)` do refresh não tem teste próprio; comentários de seção de `AuthenticatorTest`/`AuthenticatorAttackTest` e o KDoc do `AuthPlugin` ainda descrevem a regra anterior ao refinamento do 5xx.
  - **Arquiteto:** alinhar à D2 a linha "reaproveitada … após aprovação" das "Restrições e invariantes" deste roadmap, o critério da E04 e o design §6.2 e §6.5.

---

## E03 — Design system — #em-andamento

**Objetivo:** o módulo `design` tem o tema e os componentes reaproveitáveis que dão ao app aparência de produto. As features só os montam.

**Por que agora:** M2 a M7 são compostos dessas peças. Construí-las uma vez evita telas inconsistentes na demo.

**Dependências:** E01.

**Escopo:**

- tema Material 3, claro, com a paleta e a tipografia escolhidas pelo construtor;
- tabela de dados com colunas configuráveis, ordenação, filtro, carregamento por cursor e linha clicável;
- formulário com validação local e erros por campo, alimentados por `errors[]`;
- campos de dinheiro (BRL), data, CPF/CNPJ com máscara e telefone;
- chips de status com cor por estado;
- diálogos de confirmação;
- estados vazio, carregando e erro (com mensagem em pt-BR e "tentar de novo");
- toasts e avisos;
- layout com menu lateral e área de conteúdo.

**Fora de escopo:** telas de feature e o diálogo de aprovação (E04).

**Critérios de aceite:**

- [ ] Teste de UI do Compose: a tabela carrega a próxima página ao chegar ao fim e não repete linhas.
- [ ] Teste de UI do Compose: o formulário mostra o erro de `errors[]` no campo certo.
- [ ] Teste: o campo de dinheiro converte para `BigDecimal` sem perda e recusa entrada inválida.
- [ ] Existe uma tela de catálogo dos componentes, só no modo de desenvolvimento, para conferência visual.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual pela tela de catálogo, com capturas anexadas ao handoff se possível.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- A tabela com cursor fica lenta com muitas linhas. Usar lista preguiçosa e páginas de tamanho fixo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Login, casca do app e aprovação de superior — #pendente

**Objetivo:** o usuário informa o servidor, testa a conexão, faz login (trocando a senha se o servidor exigir) e entra num app com menu lateral filtrado pelas permissões. No topo aparecem o nome do hotel e a data operacional. Qualquer ação recusada com `APPROVAL_REQUIRED` abre o diálogo de aprovação de superior e é repetida.

**Por que agora:** é a primeira coisa que o gerente vê, e todas as features entram nessa casca.

**Dependências:** E02, E03.

**Escopo:**

- **Tela de login:**
  - campo "Servidor" com "testar" (`GET /api/v1/meta/version`), conforme a ADR 0004;
  - campos de login e senha;
  - mensagens em pt-BR para credencial inválida, bloqueio e servidor fora do ar.
- **Troca de senha obrigatória:** tratamento de `PASSWORD_CHANGE_REQUIRED`.
- **Casca:**
  - menu lateral com as áreas dos marcos (Recepção, Reservas, Contas e caixa, Hóspedes, Governança, Gestão), cada item visível só com a permissão efetiva correspondente (`GET /api/v1/auth/me`);
  - áreas ainda sem tela não aparecem;
  - topo com o nome do hotel (`GET /api/v1/hotel`), a data operacional (`GET /api/v1/hotel/business-date`, RF-CFG-05), o usuário e "sair".
- **Sessão encerrada:** volta ao login com aviso.
- **Diálogo de aprovação de superior:** login e PIN de quem aprova, `POST /api/v1/auth/approvals` e repetição pelo `core`. Exposto como componente que qualquer feature usa.

**Fora de escopo:** o mapa de UHs (E06) e as telas de feature.

**Critérios de aceite:**

- [ ] Login contra o ambiente `zeny-front` com o administrador e com um usuário Recepcionista do seed: os menus diferem conforme as permissões.
- [ ] "Testar" com endereço inválido → mensagem em pt-BR, sem travar a tela.
- [ ] O endereço salvo é reaproveitado na próxima abertura. Fechar e reabrir pede login de novo.
- [ ] Teste do *state holder*: `PASSWORD_CHANGE_REQUIRED` leva à troca de senha, e a troca concluída leva à casca.
- [ ] Teste do *state holder*: um `APPROVAL_REQUIRED` simulado abre o diálogo, e a aprovação repete a ação uma vez com a mesma chave.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra o ambiente `zeny-front`, com o seed da E05 se ele já existir. Se não existir, use o administrador e registre isso.

**Revisão humana:** não

**Notas para o plano:** o mapeamento área → permissão sai do catálogo de permissões do servidor (`GET /api/v1/permissions` e os códigos em `MeResponse.permissions`). Registre a tabela no handoff para os marcos seguintes.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Permissão sem correspondência óbvia com uma área. Registrar a escolha no handoff; o servidor continua decidindo.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E05 — Dados de demonstração e teste de fumaça — #pendente

**Objetivo:** o `demo-seed` popula um servidor recém-criado pela API, de forma idempotente e em menos de 2 minutos, com um hotel crível. O `smokeTest` confere o SDK contra o servidor real.

**Por que agora:** o mapa de UHs (E06) e todos os roteiros de demo dependem de dados. Sem cadastros no front até o M7, só o seed cria a configuração.

**Dependências:** E02.

**Escopo:**

- **CLI `demo-seed`:** recebe servidor, login e senha do administrador e as senhas dos usuários da demo por argumento ou variável de ambiente. Também é empacotado como JAR executável.
- **O que ele cria** (design §7.2):
  - os dados do hotel;
  - tipos, áreas e cerca de 20 UHs com status variados;
  - temporadas, planos e preços para os próximos 60 dias;
  - os usuários Gerente, Recepcionista e Governanta, com os modelos de role do servidor e o PIN do Gerente;
  - hóspedes e empresas fictícios, com CPF/CNPJ de dígito válido gerados e nunca de pessoa real;
  - reservas relativas à data operacional: chegadas hoje, hospedados (por check-in) e saídas hoje.
- **Idempotência:** confere antes de criar.
- **Tarefa `smokeTest`:** roda o seed e algumas leituras pelo `sdk` contra `-PserverUrl`. Fica fora do `build` padrão.
- **`LEIA-ME`:** o fluxo "recriar a demo" do `LEIA-ME` do pacote, criado na M0 E01, passa a chamar o JAR do seed.

**Fora de escopo:**

- dados de contas, caixa e checklists (entram nos marcos M4 e M6);
- SQL direto no banco;
- qualquer dado pessoal real.

**Critérios de aceite:**

- [ ] Num ambiente recém-recriado (`down -v`, `up`), o seed termina sem erro em menos de 2 minutos, com o tempo registrado no handoff.
- [ ] Rodar o seed uma segunda vez não cria duplicados (contagens de UHs, usuários, hóspedes e reservas iguais antes e depois).
- [ ] Login como Gerente, Recepcionista e Governanta funciona com as senhas passadas.
- [ ] `GET /api/v1/reservations/arrivals`, `/departures` e `/in-house` da data operacional devolvem reservas.
- [ ] O `smokeTest` passa contra `zeny-front`.
- [ ] Nenhuma senha ou chave versionada.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- `tools/server-env/down.sh` com apagar volumes, `tools/server-env/up.sh`, o seed e o `smokeTest` → verdes, com o tempo do seed registrado. Se faltar memória no notebook, rode contra o Windows pela URL.

**Revisão humana:** não

**Notas para o plano:**

- O check-in dos hospedados do seed passa pelas regras do servidor (`POST /api/v1/reservations/{id}/check-in`): a UH precisa estar pronta e alocada. Leia as notas `ZenyHotelManager - Hospedagem e recepção` e `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)` do brain.
- A data operacional não avança até o M6 do servidor.
- Lacuna na API → pedido `PF-NN` (H5).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Alguma configuração exige aprovação ou um passo não exposto na API. Registrar como `PF-NN` e adaptar o roteiro.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E06 — Tempo real e mapa de UHs ao vivo — #pendente

**Objetivo:** o `core` mantém uma conexão SSE por sessão, com reconexão. O app mostra o **mapa de UHs**: um quadro com as UHs por área ou andar, cor por status, filtros por tipo e status, e um detalhe resumido. O mapa muda sozinho quando o status de uma UH muda no servidor.

**Por que agora:** é o primeiro "uau" da demo e prova o tempo real que todas as telas operacionais vão usar.

**Dependências:** E04, E05.

**Escopo:**

- **Cliente SSE no `core`:**
  - `GET /api/v1/stream?topics=…`, com os tópicos pedidos pelas telas abertas;
  - reconexão com `Last-Event-ID` e espera crescente;
  - o evento `reset` leva a tela a recarregar;
  - desconexão no logout.
- **Mapa de UHs:**
  - dados de `GET /api/v1/rooms/status`, `GET /api/v1/room-types` e `GET /api/v1/areas`;
  - legenda de status;
  - filtros;
  - contadores por status;
  - detalhe da UH em painel lateral (`GET /api/v1/rooms/{id}`, `GET /api/v1/rooms/{id}/status`).
- **Atualização:** pelo tópico `rooms`, rebuscando a UH afetada.

**Fora de escopo:** ações sobre a UH (M6) e ocupação por reserva no mapa, além do que `rooms/status` já traz.

**Critérios de aceite:**

- [ ] Teste: reconexão envia o `Last-Event-ID` do último evento. `reset` dispara o recarregamento.
- [ ] Com o mapa aberto, uma mudança de status feita por outro cliente (por exemplo, `curl` em `POST /api/v1/rooms/{id}/housekeeping-status` com o usuário Governanta) aparece no mapa em até 2 s (RNF-04 do servidor).
- [ ] Derrubar e subir o `api` do `zeny-front` reconecta sozinho e o mapa volta consistente.
- [ ] Os status aparecem em pt-BR. Um status desconhecido aparece como "Outro (VALOR)".

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front` com o seed, com o tempo de propagação medido e registrado.

**Revisão humana:** não

**Notas para o plano:** o payload do SSE é mínimo (`type`, `id`, `version`), e o cliente rebusca o recurso (ARCHITECTURE §10 do servidor e nota `ZenyHotelManager - Tempo real (SSE)`).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O proxy atrasa eventos (H4). Medir. Se passar de 2 s, abrir `PF-NN` e usar atualização periódica como alternativa temporária.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E07 — Gate do marco M1 — #pendente

**Objetivo:** o M1 entregue como build testável. O pacote da demo leva os JARs do app e do seed, o roteiro do M1 existe, e a definição global de pronto está verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e libera o M2. Até a próxima build, esta é a build da demo.

**Dependências:** E06.

**Escopo:**

- `tools/server-env/package.sh` passa a incluir os JARs do app e do `demo-seed`;
- `docs/demo/roteiro-m1.md` (a criar): recriar a demo; abrir o app; configurar o servidor; login como Recepcionista e como Gerente, mostrando os menus diferentes; mapa de UHs; mudança de status ao vivo; sair;
- verificação num clone limpo.

**Fora de escopo:** funcionalidade nova.

**Critérios de aceite:**

- [ ] Clone limpo: build, teste e lint verdes, e o JAR gerado.
- [ ] O `smokeTest` passa contra o ambiente recriado.
- [ ] O pacote contém os JARs, o `LEIA-ME` e o roteiro.
- [ ] O usuário executou o roteiro no Windows, do jeito que está escrito, e aprovou na parada da etapa.

**Validação obrigatória:**

- Comandos oficiais num clone limpo → verdes.
- `smokeTest` → verde.
- Roteiro executado pelo usuário.

**Revisão humana:** sim. É o gate do marco e a build de demo enquanto o M2 não fechar.

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Diferença de comportamento no Windows (fontes, escala de tela). Ajustar no próprio gate e registrar.

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
- 2026-11-11 — E01 executada pelo `claude --agent orquestrador`: implementada, revisada e integrada na `main`. Fica `#em-andamento` aguardando a parada humana (JAR no Windows e decisões da fronteira `core`/`sdk` e da `Idempotency-Key`). H1 e H2 confirmadas; H3 confirmada no Linux.
- 2026-11-11 — E01 `#concluida` (`claude --agent orquestrador`): o usuário conferiu o JAR no Windows; H3 confirmada. Pré-voo do lote: aditivo (código 11).
- 2026-11-11 — E02 `#concluida` (`claude --agent orquestrador`): núcleo HTTP e sessão no `core`, revisão final limpa após a onda única de correção.
