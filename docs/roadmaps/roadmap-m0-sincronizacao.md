# Roadmap — M0 Sincronização com o servidor

> Arquivo: `docs/roadmaps/roadmap-m0-sincronizacao.md` · branch de execução: `roadmap/m0-sincronizacao` · criado em 2026-11-10 · última revisão 2026-11-10

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa as etapas pendentes deste marco na branch `roadmap/m0-sincronizacao`, cada uma planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Libere antes os comandos de build/teste do projeto nas permissões do Claude Code se quiser que o lote rode sem interrupções.

Este marco **se repete**:

- A rodada 1 (E01) cria o mecanismo.
- Cada rodada seguinte começa por uma sessão `claude --agent arquiteto` com `development-roadmap` em modo atualização. Ela faz a análise, isto é, lê o contrato novo, o oasdiff e as capturas do servidor que citam "atende PF-NN", e então:
  - acrescenta etapas aos marcos do front;
  - atualiza `docs/pedidos-servidor.md`;
  - acrescenta aqui a etapa mecânica `Enn — Sincronizar com o servidor em <commit>`.
- O orquestrador executa essa etapa (design §7.3).

## Objetivo

O front fica preso a um commit da `main` do servidor, com o contrato copiado e um pré-voo que mede a defasagem. Há também um ambiente isolado do servidor que sobe no notebook ou no Windows sem conflitar com o orquestrador do servidor. Depois de cada rodada, a build do front usa um servidor conhecido e reproduzível.

## Contexto confirmado

- Repositório do front: `/home/caio/repos/ZenyHotelManager-DesktopFront`, na branch `main` e sem commits em 2026-11-10. Só traz a documentação desta sessão.
- Servidor: `/home/caio/repos/ZenyHotelManager`, com `main` em `ca9f9e2f9808c74c98154f1e8e60381fecbf1b5d` em 2026-11-10. O orquestrador do servidor trabalha em `roadmap/m5-recepcao-fnrh`, em `.claude/worktrees/`. O contrato é `api/openapi.yaml` (OpenAPI 3.0.1, 303 operações).
- Compose do servidor (`docker/compose.yaml`):
  - `name: zeny`;
  - `image: zeny-hotel-manager:dev`, construída pelo `docker/Dockerfile`, que roda o Gradle dentro do Docker;
  - serviços `api`, `db` (`postgres:18`), `storage` e `storage-init` (`pgsty/minio:RELEASE.2026-08-04T00-00-00Z`) e `proxy` (Caddy);
  - porta `ZENY_HTTP_PORT` (padrão 80) em `ZENY_HTTP_ADDRESS` (padrão `127.0.0.1`);
  - variáveis obrigatórias em `docker/.env.example`;
  - readiness em `/actuator/health/readiness`.
- Script de breaking change do servidor: `.github/scripts/check-api-breaking.sh` (oasdiff por Docker), usado como referência.
- Máquinas:
  - **notebook Linux** (14 GB, pouca RAM livre): JDK 25, Docker 29, sem Node e sem Gradle global;
  - **Windows** (64 GB, Docker Desktop): o usuário vai instalar o JDK 25.
- Design: [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §5, §6.4 e §7.3; ADRs [0002](../adr/0002-servidor-fixado-e-contrato-copiado.md) e [0003](../adr/0003-ambiente-do-servidor-isolado.md).
- Conhecimento do projeto no Second Brain: o hub `ZenyHotelManager-DesktopFront`, depois do registro (pendência do usuário). Do servidor: as notas `ZenyHotelManager - Docker, observabilidade e CI` e `ZenyHotelManager - Ambiente, banco, chaves e build`.

## Escopo

**Incluído:**

- o arquivo do commit fixado;
- a cópia do contrato;
- o pré-voo de defasagem;
- os scripts do ambiente isolado do servidor (subir, derrubar, empacotar, salvar imagens);
- o `LEIA-ME` do Windows;
- o controle de pedidos ao servidor;
- `.gitignore` e README iniciais;
- as rodadas de sincronização futuras (uma etapa por rodada).

**Fora de escopo:**

- o projeto Gradle e o SDK (M1 E01), e qualquer código do app;
- a inclusão dos JARs do app e do `demo-seed` no pacote (M1 E07);
- qualquer escrita no repositório do servidor.

## Restrições e invariantes

- O repositório do servidor é **só leitura**: `git -C … rev-parse`, `git -C … show <commit>:<arquivo>`, `git -C … archive`. Nada de `worktree add`, `checkout`, `fetch` ou arquivo novo lá.
- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela.
- O ambiente do front usa sempre o projeto compose `zeny-front`, a imagem `zeny-hotel-manager:<commit curto>` e a porta 8088 por padrão. Ele nunca usa o projeto `zeny` nem a imagem `:dev`. `down` e `down -v` só atingem `zeny-front`.
- Segredos (`.env` com chaves, senhas do admin) nunca entram no Git. Só versões `.env.example` com valores vazios ou de exemplo.
- Scripts em bash, sem dependência de Node. O que roda no Windows é só o `LEIA-ME` em PowerShell.
- Documentação em pt-BR.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | O compose do servidor aceita um override de projeto, imagem, porta e endereço sem editar o arquivo original. Para tirar o `build:` no pacote, `!reset` do Compose v2.24+ | Seria preciso copiar e editar o compose dentro do pacote | E01 — **confirmada (2026-11-10)** para projeto, imagem, porta e endereço: `ports: !override` e `docker compose config` real contra o compose do commit fixado. O `build:` **fica** no override (D1 do plano da E01), porque o ADR 0003 constrói a imagem no Windows; com a imagem carregada por `docker load`, o `up -d` não reconstrói |
| H2 | O `docker build` do servidor roda no Docker Desktop do Windows a partir do `git archive` | O pacote passaria a levar a imagem pronta (`docker save`), construída no notebook | E01 — **aberta**: depende do usuário rodar o fluxo 1 do `LEIA-ME` no Windows, na parada da E01 |
| H3 | O oasdiff por Docker serve para o pré-voo, como no script do servidor | O pré-voo cairia para comparação por hash, sem classificar a mudança | E01 — **confirmada (2026-11-10)**: contra o servidor real, `igual (código 0)` na `main` e `aditivo (código 11)` a partir de `60080b7c5585`; o breaking (12) foi demonstrado no servidor falso, porque o servidor proíbe breaking em `/api/v1` |
| H4 | As chaves do `.env` podem ser geradas por script conforme o README do servidor | Geração manual documentada no `LEIA-ME` | E01 — **confirmada (2026-11-10)**: `tools/server-env/gen-env.sh`, autocontido; no Windows roda em `alpine:3.22@sha256:49340b34…` com `apk add bash openssl` (D3) |

## Definição global de pronto

- [x] `api/server-pin.properties` e `api/openapi.yaml` conferem com a `main` do servidor no commit escolhido.
- [x] `tools/server-drift.sh` distingue igual, aditivo e breaking, com códigos de saída documentados.
- [ ] O ambiente `zeny-front` sobe com readiness `UP`, numa das máquinas, sem tocar no projeto `zeny`.
- [ ] O pacote do Windows sobe o servidor seguindo só o `LEIA-ME`.
- [x] O README do front lista os comandos dos scripts.

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

- Toda execução acontece no worktree/branch `roadmap/m0-sincronizacao`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
- Se a branch já existir com trabalho não integrado, continue nela. Se o lote anterior já foi integrado, crie a branch de novo a partir da `main` atualizada.
- Este roadmap é atualizado dentro da branch: tags e handoffs viajam com o código.

**Pré-voo do servidor**

Antes de escolher a etapa, se `tools/server-drift.sh` já existir, rode-o e registre o resultado no relatório do lote. Ele compara o commit fixado com a `main` do servidor, só lendo. Neste marco o resultado é informativo, porque é aqui que o commit fixado muda.

**Por etapa**

1. **Escolha:** a primeira etapa, na ordem do documento, marcada `#pendente` e com todas as dependências `#concluida`. Etapas `#bloqueada` e as que dependem delas ficam para trás.
2. **Conferência:** leia a seção da etapa, os handoffs das dependências e o código que eles citam. Se o código divergiu do que a etapa supõe, pare e relate.
3. **Início:** troque a tag para `#em-andamento` (índice e título).
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m0-sincronizacao-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m0-sincronizacao Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN <título>` conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m0-sincronizacao.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 3 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m0-sincronizacao com superpowers:using-git-worktrees. Não trabalhe na branch principal.
4. Rode o pré-voo do servidor, se tools/server-drift.sh existir, e registre o resultado.
5. Repita, uma etapa por vez: escolha a próxima etapa elegível, marque #em-andamento, planeje com superpowers:writing-plans (spec = seção da etapa + design citado), execute com superpowers:subagent-driven-development, feche a etapa e preencha o handoff conforme o protocolo.
6. Pare nas paradas do protocolo ou ao atingir o limite.
7. No fim, rode superpowers:finishing-a-development-branch com merge local na main, conforme o fechamento do orquestrador: tudo commitado, pronto para git push origin main e o repositório pronto para o próximo orquestrador. Depois, liste as decisões tomadas e apresente como entrevista com opção recomendada só as de arquitetura que esperam por mim.

O repositório do servidor (/home/caio/repos/ZenyHotelManager) é só leitura: nunca escreva nele. Qualquer necessidade de intervenção: escolha a opção recomendada depois de uma avaliação crítica dos prós e contras e registre a ruling; intervenção humana só para decisão de arquitetura ou system design. Nunca faça push ou deploy. Não altere tags de etapas que não executou.

Ao parar, responda com: etapas concluídas e o resultado da revisão final de cada uma, a etapa atual e seu estado, o motivo da parada, as etapas #bloqueada com a ação de desbloqueio, o resultado do pré-voo, a branch e os commits do lote e o próximo passo sugerido.
```

## Índice de etapas

| ID | Etapa | Estado | Dependências | Entrega principal |
|---|---|---|---|---|
| E01 | Fixação do servidor e ambiente isolado (rodada 1) | #em-andamento | — | Commit fixado, contrato copiado, pré-voo, scripts do ambiente `zeny-front`, pacote e `LEIA-ME` do Windows, controle de pedidos |

---

## E01 — Fixação do servidor e ambiente isolado (rodada 1) — #em-andamento

**Objetivo:** criar o mecanismo de fixação do servidor e o ambiente isolado em que todo gate do front roda. Ao fim, o servidor sobe como `zeny-front`, a partir do commit fixado, no notebook ou no Windows.

**Por que agora:** todo marco do front depende de um servidor conhecido. O SDK da M1 é gerado da cópia do contrato. O orquestrador do servidor está ativo e pode derrubar ou reconstruir o ambiente `zeny` a qualquer momento.

**Dependências:** nenhuma.

**Escopo:**

- **Commit fixado:** a `main` do servidor no momento da execução (`git -C /home/caio/repos/ZenyHotelManager rev-parse main`).
- **Contrato:** `api/server-pin.properties` (a criar), com `commit`, `contract_sha256` e `synced_at`, e `api/openapi.yaml` (a criar), copiado byte a byte do commit.
- **Pré-voo, `tools/server-drift.sh` (a criar), só leitura.** Compara o commit fixado com a `main` do servidor e roda o oasdiff entre os dois contratos. Saída legível e códigos de saída distintos para igual, aditivo e breaking. O caminho do servidor vem de `ZENY_SERVER_REPO`, com padrão `../ZenyHotelManager`.
- **Ambiente, `tools/server-env/` (a criar):**
  - **`up.sh`:** faz `git archive` do commit fixado para `.server/<commit>/`, constrói `zeny-hotel-manager:<commit curto>` e sobe `docker compose -p zeny-front` com `compose.override.yaml`, que define imagem, porta 8088 e `ZENY_HTTP_ADDRESS` configurável.
  - **`down.sh`:** derruba só o projeto `zeny-front` (com opção de apagar volumes).
  - **`package.sh`:** monta `build/demo-package/` com o `git archive`, o override sem `build`, `.env.example` e `LEIA-ME.md`. Deixa o lugar dos JARs, que a M1 E07 preenche.
  - **`save-images.sh`:** grava com `docker save` as quatro imagens do compose (`api`, `postgres:18`, o MinIO e a do proxy), para a demo sem internet.
  - **`.env.example`:** as variáveis do `docker/.env.example` do servidor, com `ZENY_HTTP_PORT=8088` e notas sobre `ZENY_HTTP_ADDRESS=0.0.0.0` e `ZENY_STORAGE_PUBLIC_ENDPOINT=http://<IP>:8088`.
- **`LEIA-ME.md` do pacote**, em PowerShell, com quatro fluxos:
  1. primeira vez (gerar o `.env`, `build`, `up -d`, readiness);
  2. atualizar (pacote novo, `build`, `up -d`);
  3. recriar a demo (`down -v`, `up -d` e, a partir da M1, o `demo-seed`);
  4. firewall (liberar a porta 8088).
- **Pedidos ao servidor:** `docs/pedidos-servidor.md` (a criar), com a tabela vazia `PF-NN | título | estado | captura | etapa do front | commit de entrega` e as regras do design §8.
- **Repositório:**
  - `.gitignore` (a criar), cobrindo `.server/`, `build/`, `.env` e artefatos do Gradle e da IDE;
  - `README.md` (a criar), com o que é o projeto, onde estão design, ADRs e roadmaps, e a tabela de comandos dos scripts.

**Fora de escopo:**

- projeto Gradle, SDK e app (M1);
- JARs no pacote (M1 E07);
- geração automática de chaves, se exigir ferramenta ausente (cai para instrução no `LEIA-ME`, ver H4);
- qualquer escrita no repositório do servidor.

**Critérios de aceite:**

- [x] O `sha256sum api/openapi.yaml` é igual a `contract_sha256` e igual ao hash de `git -C <servidor> show <commit>:api/openapi.yaml`.
- [x] `tools/server-drift.sh` sai com o código de "igual" logo após a fixação. Contra um commit anterior do servidor, passado por parâmetro ou variável, distingue aditivo de breaking.
- [ ] `tools/server-env/up.sh` sobe o projeto `zeny-front`, e `curl -fsS http://localhost:8088/actuator/health/readiness` responde `UP`. Isso vale no notebook, se houver memória; senão, no Windows pelo `LEIA-ME`, com a evidência dada pelo usuário. *(Aguarda o usuário: `MemAvailable` de 4,2 GiB, abaixo do limiar de 6 GiB do plano, impediu o build da api no notebook.)*
- [x] Com `zeny-front` no ar, `docker compose -p zeny ps` e as imagens `zeny-hotel-manager:dev` ficam como estavam antes, sem nada criado nem alterado no projeto `zeny`. *(Validado com `db`, `storage` e `storage-init` no ar, sem a api.)*
- [x] `tools/server-env/down.sh` remove só containers e volumes de `zeny-front`.
- [x] `git -C /home/caio/repos/ZenyHotelManager status --porcelain` e `git -C /home/caio/repos/ZenyHotelManager worktree list` ficam iguais antes e depois da etapa.
- [x] Nenhum segredo versionado: `git ls-files` não lista `.env` e o `.env.example` não tem chave real.
- [ ] O `package.sh` gera `build/demo-package/` com `LEIA-ME.md`, e o usuário sobe o servidor no Windows seguindo só o `LEIA-ME`, conferido pelo usuário na parada da etapa. *(O pacote foi gerado; falta a conferência do usuário no Windows.)*

**Validação obrigatória:**

- `bash -n` em todos os scripts → sem erro de sintaxe. Use `shellcheck` se estiver instalado; não instale sem pedir.
- `tools/server-drift.sh` → código "igual".
- `tools/server-env/up.sh` e depois `curl -fsS http://localhost:8088/actuator/health/readiness` → `UP`. Se faltar memória no notebook, registrar e validar no Windows com o usuário, na parada da etapa.
- `git -C /home/caio/repos/ZenyHotelManager status --porcelain` → idêntico ao snapshot inicial.

**Revisão humana:** sim. É a primeira integração com o ambiente do servidor e com o Windows, e todo gate do front depende dela.

**Notas para o plano:**

- O compose do servidor tem `name: zeny` fixo: o `-p zeny-front` na linha de comando prevalece.
- O `docker/Dockerfile` roda o Gradle dentro do Docker, e no notebook isso disputa memória com o orquestrador do servidor (design §5). Prefira validar o build da imagem no Windows.
- As URLs assinadas de anexos usam `ZENY_STORAGE_PUBLIC_ENDPOINT`, que precisa ser o endereço que o app enxerga.
- O `README.md` do servidor descreve a geração das chaves (JWT e cifragem) e do administrador inicial: leia, sem copiar segredo.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Memória do notebook insuficiente para `up.sh`. Validar no Windows (H2).
- O override não conseguir remover o `build:` no pacote. Usar a cópia editada do compose dentro do pacote (H1).

**Evidências e handoff:**

- **Estado (2026-11-10):** implementação completa, revisada e integrada à `main`. A etapa continua `#em-andamento` aguardando a **parada humana**: o usuário roda o `LEIA-ME` no Windows e devolve a evidência da readiness. Ao receber `{"status":"UP"}`, o próximo orquestrador marca `#concluida`. Se falhar, a correção entra como rodada nova desta etapa.
- **Plano:** [`docs/superpowers/plans/2026-11-10-m0-sincronizacao-e01.md`](../superpowers/plans/2026-11-10-m0-sincronizacao-e01.md). Revisão de arquitetura: aprovado com notas em 2 rodadas (`revisor-arquitetura`, opus).
- **Commits:** `abe64cd..59c62d3` na branch `roadmap/m0-sincronizacao`, 8 tasks:
  - Task 1, base, `common.sh` e harness: `7aefaf4..e6d06af`;
  - Task 2, pin: `e6d06af..6aa34ef`;
  - Task 3, pré-voo: `6aa34ef..2d6775b`;
  - Task 4, `gen-env`: `1d1f6c8..4bae8a0`;
  - Task 5, lib e override: `3bf02bb..2d6775b`;
  - Task 6, up/down: `7dad5e1..d4aedca`;
  - Task 7, pacote: `4bae8a0..3ed1192`;
  - Task 8, README e pedidos: `d4aedca..c344bc0`;
  - onda final: `f640e90`, `59c62d3`.
- **Revisão final** (`revisor-seguranca`, opus): primeiro bloqueada pela falta de varredura da `alpine:3.22` e por três Importantes (primeiro uso no README, evidência real do `down.sh`, CVEs do oasdiff). Depois da onda única de correção, a re-revisão aprovou com ressalvas: "Ready to merge: Yes". Suíte: 16 arquivos, 0 falhas, 0 casos pulados. `check_test_integrity.py --base abe64cd`: sem apontamentos.
- **Critérios verificados:**
  - Pin: `ca9f9e2f9808c74c98154f1e8e60381fecbf1b5d`, sha256 `79362dac5589a0ed837027ff7afa49a32be5a25c6a546bc21e39dd8f446873a1`, que confere com a cópia e com o `git show` do servidor.
  - Pré-voo real: `igual (código 0)` contra a `main`; `aditivo (código 11)` com `--base e8b7303~1`. O breaking (12) foi demonstrado no servidor falso.
  - Isolamento: `db`, `storage` e `storage-init` de `zeny-front` ficaram healthy. Com eles no ar, o projeto `zeny` e as imagens `zeny-hotel-manager:dev 6e9ea80a5355`/`:latest 9dc240957611` ficaram inalterados. O `down.sh -v` real removeu só os 3 containers, os volumes `zeny-front_db-data` e `zeny-front_storage-data` e a rede `zeny-front_default`.
  - Servidor: `status --porcelain` igual ao snapshot inicial. O `worktree list` tem os mesmos caminhos e branches; o HEAD do worktree do orquestrador do servidor avançou por conta dele.
  - Segredos: `git ls-files` não lista nenhum `.env`; os seis segredos do `.env.example` estão vazios.
  - Pacote: `build/demo-package/` gerado no checkout principal, sem `.env`.
  - **Pendentes, com o usuário:** readiness da api e o fluxo do `LEIA-ME` no Windows.
- **Decisões para o usuário** (já tomadas pela opção recomendada; revisáveis):
  1. D1, desfecho da H1: o override **mantém** o `build:`, contra o texto "override sem `build`" deste roadmap. O ADR 0003 manda construir no Windows. Custo se errado: um `up -d` sem a imagem dispara o build, que leva minutos e precisa de internet.
  2. D2: `ZENY_HTTP_ADDRESS` tem padrão `127.0.0.1` no notebook e `0.0.0.0` no pacote, pelo marcador do override; o `.env.example` deixa endereço e endpoint vazios. O ADR 0003 punha `0.0.0.0` no override para os dois.
  3. D3, desfecho da H4: o `.env` é gerado no Windows com `docker run alpine:3.22@sha256:49340b34…`. A imagem tem 1 CVE Critical, no zlib (ALPINE-CVE-2026-4738, corrigida no zlib 1.4-r0), e foi aceita porque o container é efêmero e só roda `openssl` local. Rever quando houver Alpine com zlib 1.4.
  4. A imagem do oasdiff (`tufin/oasdiff:v1.33.0@sha256:6263a96d…`, a mesma do CI do servidor) tem 48 vulnerabilidades conhecidas (9 Critical), todas com correção. Foi aceita porque roda com `--network none`, specs só leitura e entrada confiável. Recomendação: atualizar junto com o servidor, ou divergir via `OASDIFF_IMAGE`.
  5. D5: o estado de máquina fica no checkout principal do front (`.server/<commit>/`, `.server/<commit>.override.yaml`, `.server/zeny-front.env`, `build/demo-package/`), autoignorado. **Nunca rode `git clean` no checkout principal**: o `.env` tem as chaves presas ao volume.
  6. O `down.sh` derruba sempre pelo nome do projeto, sem `-f` e sem `.env`. A readiness exige o `status` de topo `UP`.
  7. A imagem `alpine:3.22` foi baixada só para a varredura e removida em seguida.
- **Notas para a próxima etapa:**
  - Na parada, o usuário segue `build/demo-package/LEIA-ME.md` no Windows: Compose ≥ 2.24.4, gerar o `.env`, `build`, `up -d`, readiness com `curl.exe`. Isso fecha H2 e os dois critérios pendentes.
  - O pré-voo agora existe (`tools/server-drift.sh`): toda execução de roadmap do front começa por ele.
  - A M1 E07 preenche `app/` no pacote.
  - Os Menores adiados (ver o ledger da etapa, resumido na mensagem do lote) incluem:
    - zeros à esquerda em `ZENY_UP_TIMEOUT`;
    - `.gitattributes` (`api/openapi.yaml -text`, `*.sh eol=lf`), recomendado antes de qualquer clone no Windows;
    - pontos de progresso do `openssl` no stderr do `gen-env.sh`;
    - asserts em subshell nos testes.
  - As ondas paralelas não funcionam com a sessão do orquestrador isolada num worktree, porque os subagentes não escrevem em outros worktrees: as tasks rodaram em série no worktree da etapa.

---

## Histórico de revisões

- 2026-11-10 — roadmap criado (`claude --agent arquiteto`), com a rodada 1. As rodadas seguintes entram como etapas novas, pelo `development-roadmap` em modo atualização.
- 2026-11-10 — E01 executada pelo `claude --agent orquestrador`: implementada, revisada e integrada na `main`. Fica `#em-andamento` aguardando a parada humana (readiness no Windows). H1, H3 e H4 confirmadas; H2 aberta.
