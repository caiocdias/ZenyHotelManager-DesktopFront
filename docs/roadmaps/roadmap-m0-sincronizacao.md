# Roadmap — M0 Sincronização com o servidor

> Arquivo: `docs/roadmaps/roadmap-m0-sincronizacao.md` · branch de execução: `roadmap/m0-sincronizacao` · criado em 2026-11-10 · última revisão 2026-11-20

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
| H1 | O compose do servidor aceita um override de projeto, imagem, porta e endereço sem editar o arquivo original. Para tirar o `build:` no pacote, `!reset` do Compose v2.24+ | Seria preciso copiar e editar o compose dentro do pacote | E01 — **confirmada (2026-11-10)** para projeto, imagem, porta e endereço: `ports: !override` e `docker compose config` real contra o compose do commit fixado. O `build:` **fica** no override (D1 do plano da E01, confirmada pelo usuário em 2026-11-10), porque o ADR 0003 constrói a imagem no Windows; com a imagem carregada por `docker load`, o `up -d` não reconstrói |
| H2 | O `docker build` do servidor roda no Docker Desktop do Windows a partir do `git archive` | O pacote passaria a levar a imagem pronta (`docker save`), construída no notebook | E01 — **confirmada (2026-11-10)** pelo usuário: seguindo o fluxo 1 do `LEIA-ME` em `C:\Users\Caio\Desktop\demo-package`, `curl.exe -fsS http://localhost:8088/actuator/health/readiness` respondeu `{"status":"UP"}` |
| H3 | O oasdiff por Docker serve para o pré-voo, como no script do servidor | O pré-voo cairia para comparação por hash, sem classificar a mudança | E01 — **confirmada (2026-11-10)**: contra o servidor real, `igual (código 0)` na `main` e `aditivo (código 11)` a partir de `60080b7c5585`; o breaking (12) foi demonstrado no servidor falso, porque o servidor proíbe breaking em `/api/v1` |
| H4 | As chaves do `.env` podem ser geradas por script conforme o README do servidor | Geração manual documentada no `LEIA-ME` | E01 — **confirmada (2026-11-10)**: `tools/server-env/gen-env.sh`, autocontido; no Windows roda em `alpine:3.22@sha256:49340b34…` com `apk add bash openssl` (D3) |

## Definição global de pronto

- [x] `api/server-pin.properties` e `api/openapi.yaml` conferem com a `main` do servidor no commit escolhido.
- [x] `tools/server-drift.sh` distingue igual, aditivo e breaking, com códigos de saída documentados.
- [x] O ambiente `zeny-front` sobe com readiness `UP`, numa das máquinas, sem tocar no projeto `zeny`.
- [x] O pacote do Windows sobe o servidor seguindo só o `LEIA-ME`.
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
| E01 | Fixação do servidor e ambiente isolado (rodada 1) | #concluida | — | Commit fixado, contrato copiado, pré-voo, scripts do ambiente `zeny-front`, pacote e `LEIA-ME` do Windows, controle de pedidos |
| E02 | Sincronizar com o servidor em `57eb5fac5b8a` (rodada 2) | #concluida | E01 | Pin e contrato em `57eb5fac5b8a`, SDK com 335 operações, `tools/server-env/` revisto, `smokeTest` e roteiro do M1 verdes contra o servidor novo |
| E03 | Sincronizar com o servidor em `5e06f16d51ff` (rodada 3) | #concluida | E02 | Pin e contrato em `5e06f16d51ff`, `oneOf` das escritas de hóspede caracterizado no SDK, rótulos do no-show nas telas existentes, roteiros avisando do fechamento automático, `smokeTest` verde contra o servidor novo |
| E04 | Sincronizar com o servidor em `8c563cda0575` (rodada 4) | #em-andamento | E03 | Pin e contrato em `8c563cda0575`, SDK com 376 operações, campos com padrão enviados explícitos (ADR 0014), catálogo de erros com os códigos novos, `smokeTest` e verificações ao vivo verdes contra o servidor novo |

---

## E01 — Fixação do servidor e ambiente isolado (rodada 1) — #concluida

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
- [x] `tools/server-env/up.sh` sobe o projeto `zeny-front`, e `curl -fsS http://localhost:8088/actuator/health/readiness` responde `UP`. Isso vale no notebook, se houver memória; senão, no Windows pelo `LEIA-ME`, com a evidência dada pelo usuário. *(No notebook, `MemAvailable` de 4,2 GiB, abaixo do limiar de 6 GiB do plano, impediu o build da api. Validado no Windows pelo `LEIA-ME`: o usuário devolveu `{"status":"UP"}` em 2026-11-10.)*
- [x] Com `zeny-front` no ar, `docker compose -p zeny ps` e as imagens `zeny-hotel-manager:dev` ficam como estavam antes, sem nada criado nem alterado no projeto `zeny`. *(Validado com `db`, `storage` e `storage-init` no ar, sem a api.)*
- [x] `tools/server-env/down.sh` remove só containers e volumes de `zeny-front`.
- [x] `git -C /home/caio/repos/ZenyHotelManager status --porcelain` e `git -C /home/caio/repos/ZenyHotelManager worktree list` ficam iguais antes e depois da etapa.
- [x] Nenhum segredo versionado: `git ls-files` não lista `.env` e o `.env.example` não tem chave real.
- [x] O `package.sh` gera `build/demo-package/` com `LEIA-ME.md`, e o usuário sobe o servidor no Windows seguindo só o `LEIA-ME`, conferido pelo usuário na parada da etapa. *(Conferido pelo usuário em 2026-11-10: `curl.exe -fsS http://localhost:8088/actuator/health/readiness` em `C:\Users\Caio\Desktop\demo-package` → `{"status":"UP"}`.)*

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

- **Estado (2026-11-10):** `#concluida`. A implementação foi revisada e integrada à `main` no lote anterior. Na parada humana, o usuário seguiu o `LEIA-ME` no Windows e devolveu `{"status":"UP"}` (`curl.exe -fsS http://localhost:8088/actuator/health/readiness`, em `C:\Users\Caio\Desktop\demo-package`). O orquestrador seguinte conferiu antes de fechar: pré-voo `igual (código 0)` (pin `ca9f9e2f9808` igual à `main` do servidor), suíte com 16 arquivos, 0 falhas e 0 casos pulados, e o servidor sem mudança (`status --porcelain` e `worktree list` iguais antes e depois). H2 confirmada.
- **Ruling do fechamento:** a evidência colada foi aceita como conferência do usuário dos dois critérios pendentes e como confirmação da H2. É o comando do fluxo 1 do `LEIA-ME`, rodado no diretório do pacote e com o resultado que o critério exige. O usuário não disse com todas as letras que construiu a imagem no Windows nem que seguiu *só* o `LEIA-ME`. Custo se errado: a H2 seria reaberta como rodada nova desta etapa, que é barata.
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
  - **Readiness e `LEIA-ME` no Windows:** `{"status":"UP"}`, evidência do usuário (2026-11-10).
- **Decisões para o usuário** (já tomadas pela opção recomendada; revisáveis):
  1. D1, desfecho da H1: o override **mantém** o `build:`, contra o texto "override sem `build`" deste roadmap. O ADR 0003 manda construir no Windows. Custo se errado: um `up -d` sem a imagem dispara o build, que leva minutos e precisa de internet. **Confirmada pelo usuário em entrevista (2026-11-10)**, e o ADR 0003 foi atualizado.
  2. D2: `ZENY_HTTP_ADDRESS` tem padrão `127.0.0.1` no notebook e `0.0.0.0` no pacote, pelo marcador do override; o `.env.example` deixa endereço e endpoint vazios. O ADR 0003 punha `0.0.0.0` no override para os dois. **Confirmada pelo usuário em entrevista (2026-11-10)**, e o ADR 0003 foi atualizado.
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
    - ~~`.gitattributes` (`api/openapi.yaml -text`, `*.sh eol=lf`), recomendado antes de qualquer clone no Windows~~ — **resolvido em 2026-11-10** pelo plano [`2026-11-10-m0-gitattributes.md`](../superpowers/plans/2026-11-10-m0-gitattributes.md). As regras ficaram: `api/openapi.yaml -text`, `*.sh text eol=lf`, `api/server-pin.properties`, `tools/server-env/.env.example` e `tools/server-env/compose.override.yaml` com `text eol=lf`. Os testes são `tests/test_gitattributes.sh` e `tests/test_gitattributes_ataque.sh`. Ficou adiado para quando a suíte precisar rodar em Git Bash no Windows (M1): `.gitattributes` e `LEIA-ME.md` em LF, o assert de `.SH` sob `core.ignorecase` e o `test_docs.sh:37`. Na M1 E01, `gradlew text eol=lf` e `*.bat text eol=crlf` entram na lista;
    - pontos de progresso do `openssl` no stderr do `gen-env.sh`;
    - asserts em subshell nos testes.
  - As ondas paralelas não funcionam com a sessão do orquestrador isolada num worktree, porque os subagentes não escrevem em outros worktrees: as tasks rodaram em série no worktree da etapa.

---

## E02 — Sincronizar com o servidor em `57eb5fac5b8a` (rodada 2) — #concluida

**Objetivo:** o front passa a apontar para a `main` do servidor em `57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`. O pin e a cópia do contrato são trocados, o SDK é regenerado com as 335 operações e o que quebrar é consertado. `tools/server-env/` é revisto diante das mudanças de `docker/` do servidor. Ao fim, o `smokeTest` e o roteiro do M1 passam contra o servidor novo, e o M2 pode começar.

**Por que agora:** a regra 3 de `docs/roadmaps/README.md` manda sincronizar antes do M2. A ficha de registro, que o M2 ganhou na análise desta rodada (M2 E07), só existe no servidor novo.

**Dependências:** E01.

**Contexto (análise de 2026-11-13, `claude --agent arquiteto`):**

- Pré-voo de 2026-11-13: aditivo (código 11), com o pin `ca9f9e2f9808` 271 commits atrás da `main`, sem breaking change em `/api/v1`.
- Contrato, comparado por script entre `git show <commit>:api/openapi.yaml` dos dois commits:
  - de 303 para 335 operações, nenhuma removida e nenhuma existente alterada;
  - 23 schemas novos, um esquema de segurança novo (`printAgentToken`) e 4 schemas alterados (`GuestProfileRequest`, `GuestResponse`, `MergeGuestRequest`, `OperationalSettingsDto`), só com `genderDescription` e descrições;
  - as 32 operações novas, por tag:
    - `end-of-day`: 5;
    - `FNRH`: 4;
    - `Printing`: 17, das quais 5 de `/print-agents/me/*`, com `printAgentToken`;
    - documentos em PDF e impressão: 6, nas tags `frontdesk`, `reservations` e `billing`.
- Onde cada coisa entrou: ficha → M2 E07; confirmação → M3 E03; extrato → M4 E02; `genderDescription` → M5 E01; fechamento do dia → M6 E08; fila da FNRH e limpeza da credencial → M7 E10; impressoras, agentes e fila de impressão → M7 E12. Decisões em [ADR 0009](../adr/0009-documentos-pdf-e-impressao.md).
- `docker/` do servidor entre os dois commits:
  - `compose.yaml`, `.env.example`, `Dockerfile`, `caddy/`, `postgres/`, `storage/` e `.dockerignore` estão **iguais**;
  - mudaram só `docker/gate/` (novo: o compose isolado do gate do servidor, projeto `zeny-gate`, imagem `zeny-hotel-manager:gate`, WireMock fazendo o papel da FNRH, `gate-env.sh`, `run-gate.sh` e o roteiro `m5-roteiro.sh`) e `docker/prometheus/alerts.yaml` (regras novas de fechamento e FNRH; o compose não sobe Prometheus).
- Pedidos ao servidor: nenhum `PF-NN` registrado e nenhuma captura "atende PF-NN" no brain.

**Escopo:**

- **Pin e contrato:** `api/server-pin.properties` e `api/openapi.yaml` em `57eb5fac5b8a`, pelo mecanismo da E01.
- **SDK:** regenerar e compilar o contrato inteiro (ADR 0007, item 1). O teste que amarra o total de operações passa a esperar 335.
- **Conserto:** o que quebrar em `core`, `app`, `demo-seed` e nos testes. Nada de funcionalidade nova: as telas das operações novas são dos marcos citados acima.
- **Revisão de `tools/server-env/`:**
  - confirmar que o override, `up.sh`, `package.sh`, `save-images.sh` e o `.env.example` do front continuam valendo com o compose e o `.env.example` do servidor iguais;
  - confirmar que `docker/gate/` vai no `git archive` sem efeito no projeto `zeny-front`, porque só é usado com `-f docker/gate/compose.gate.yaml`, e que o `save-images.sh` continua achando só as imagens do `docker/compose.yaml`;
  - registrar no `LEIA-ME` ou no README do front só o que mudar.
- **Ambiente e pacote:** subir `zeny-front` em `57eb5fac5b8a` e regenerar `build/demo-package/`.
- **Verificação:** `smokeTest` e o roteiro acumulado (`docs/demo/roteiro-m1.md`) contra o servidor novo.
- **Pedidos:** `docs/pedidos-servidor.md` sem mudança de estado (não há `PF-NN`).

**Fora de escopo:**

- telas para as operações novas (M2 a M7, conforme acima);
- adotar o WireMock da FNRH (`docker/gate/fnrh-mock`) no `zeny-front`. Ruling da análise: sem tela da FNRH antes da M7 E10, ele só acrescentaria uma imagem ao pacote. Sem credencial, as submissões ficam `PENDING` e não atrapalham o check-in. A M7 E10 reavalia;
- qualquer escrita no repositório do servidor.

**Critérios de aceite:**

- [x] `api/server-pin.properties` tem `commit=57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`, e `sha256sum api/openapi.yaml` é igual a `contract_sha256` e ao hash de `git -C <servidor> show 57eb5fac5b8a:api/openapi.yaml`. *(Os três: `60b309db82622662e2a1196504fe48bac8dd742b1ed51f7bcde1e769535c2d08`.)*
- [x] `tools/server-drift.sh` sai com "igual" (código 0) logo após a troca, se a `main` do servidor ainda estiver em `57eb5fac5b8a`. Se ela tiver andado, a saída é registrada no handoff e o pin continua em `57eb5fac5b8a`. *(`igual (código 0)`, base e alvo `57eb5fac5b8a`.)*
- [x] O SDK compila as 335 operações, e o teste de cobertura do SDK amarra os 335 `operationId` a métodos gerados. *(`everyOperationInTheContractHasAGeneratedMethod`: 335 métodos e 335 `prepare*`, com `PrintingApi` e `EndOfDayApi`.)*
- [x] `./gradlew build` e `bash tests/run.sh` verdes, e `./gradlew ktlintCheck detekt` verde. *(Em `d9d1c02`: 2032 testes, 0 falhas, 19 pulados; 36 arquivos, 0 falhas; lint verde.)*
- [x] `zeny-front` sobe em `57eb5fac5b8a` (notebook ou Windows) com readiness `UP`, e o `smokeTest` passa contra ele. *(No notebook: `{"status":"UP"}` com `zeny-hotel-manager:57eb5fac5b8a`; `smokeTest` 1/1.)*
- [x] A revisão de `tools/server-env/` está registrada no handoff, arquivo por arquivo, com o que mudou ou "sem mudança" e o motivo.
- [x] O usuário executou o roteiro do M1 contra o servidor novo, no Windows, com o pacote regenerado, e aprovou na parada da etapa. *(Pela regra 7: aprovado pelo usuário em 2026-11-17 ("A demo que rodei no windows deu certo"), na execução única do `roteiro-m3.md` com o pacote mais recente (o de `a800b25`, entrada 4 da lista "Validações acumuladas no Windows"); o roteiro do M3 acumula o do M1.)*
- [x] O repositório do servidor fica igual antes e depois (`status --porcelain` e `worktree list`). *(`status --porcelain` vazio antes e depois; o `worktree list` mudou só por sessões do servidor, ver o handoff.)*

**Validação obrigatória:**

- `tools/server-drift.sh` → "igual" (ou o registro da defasagem nova).
- `./gradlew build` + `bash tests/run.sh` → verdes.
- `./gradlew smokeTest -PserverUrl=…` contra `zeny-front` em `57eb5fac5b8a` → verde.
- Roteiro do M1 executado pelo usuário.

**Revisão humana:** sim. Troca o servidor da build de demo a 4 dias de 17/11. O roteiro acumulado só o usuário executa.

**Notas para o plano:**

- O ogen (ADR 0007) ainda não viu duas coisas do contrato novo: respostas `application/pdf` (`type: string, format: binary`) e um segundo esquema de segurança (`printAgentToken`). Se a geração ou a compilação falhar por causa delas, pare e relate. Trocar de gerador, excluir operação ou escrever código à mão no `sdk` é decisão de arquitetura (ADR 0007, critério 1).
- As propriedades novas do servidor (`zeny.fnrh.submissions.*`, `zeny.endofday.*`, `zeny.printing.*`) têm padrão no `application.yaml` dele, e o `.env.example` não mudou. Se o `up.sh` reclamar de variável, confira no README do servidor em `57eb5fac5b8a` antes de mexer no `.env.example` do front.
- O job `end-of-day-trigger` roda a cada minuto. Sem etapas registradas (a M6 E11 do servidor está pendente), ele não fecha nada e a data operacional não avança. A recriação na manhã da demo (design §7.2) continua necessária.
- O job `fnrh-submissions-send` roda sem credencial e não gasta tentativa.
- A suíte do front com o servidor novo pede memória (M1). Não rode junto com o gate do servidor (design §5).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O SDK novo quebra a compilação do `core` por mudança nos tipos gerados de `GuestProfileRequest` ou `GuestResponse`, que ganharam um campo anulável. Ajustar os construtores no `core` e no `demo-seed`.
- A `main` do servidor anda de novo durante a etapa. O pin fica em `57eb5fac5b8a`, que foi o analisado, e a próxima rodada do M0 trata o resto.
- O build da imagem no Windows leva minutos e precisa de internet (ADR 0003). Fazer antes do roteiro.

**Evidências e handoff:**

- **Estado (2026-11-17):** `#concluida`. A validação no Windows foi aprovado pelo usuário em 2026-11-17 ("A demo que rodei no windows deu certo"), na execução única do `roteiro-m3.md` com o pacote mais recente (o de `a800b25`, entrada 4 da lista "Validações acumuladas no Windows"). Fechada pelo `claude --agent orquestrador` do lote que fechou o M3.
- **Estado anterior (2026-11-13):** `#em-andamento`, **aguardando o Windows**. A implementação foi revisada e integrada na `main`, a verificação da etapa foi feita no notebook e o pacote foi regenerado. Falta o usuário executar o `roteiro-m1.md` no Windows com o pacote novo e aprovar; com isso a etapa fecha `#concluida`. O usuário decidiu acumular essa validação até voltar a ter o Windows (regra 7 do [README](README.md); item 1 de "Validações acumuladas no Windows"). Por essa regra, a etapa conta como concluída para dependências, e o M2 pode começar. Não retomar esta etapa antes da aprovação do usuário.
- **Plano:** [`docs/superpowers/plans/2026-11-13-m0-sincronizacao-e02.md`](../superpowers/plans/2026-11-13-m0-sincronizacao-e02.md), 2 tasks. Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado na rodada 0 (4 Importantes: `Option` em `genderDescription`, `printAgentToken` × `AuthPlugin`/`ApiExecutor`, segredos no `compose config`, `smokeTest` sem caminho no fallback de memória) e aprovado com notas na rodada 1.
- **Commits** na branch `roadmap/m0-sincronizacao` (cada um com o trailer `Soul:`):
  - documentação: `29cc674` (análise da rodada 2 trazida do checkout principal), `4145959` (início), `3796ab9`, `494b9a9`, `45b7d79` (plano e revisão);
  - Task 1, pin, contrato e SDK: `45b7d79..5667625` (`f234b33` catálogo de erros, `3f69d0f` pin e SDK, `5667625` testes do testador);
  - Task 2, revisão de `tools/server-env/`: `5667625..7b8c99e`;
  - testador de etapa: `d9d1c02`; nota na M7 E12: `9bf0bb2`; este handoff.
- **Revisão final** (`revisor-seguranca`, opus): aprovado com ressalvas, "Ready to merge: With fixes", 0 Críticos. Os dois Importantes eram de documentação e procedimento: registrar o R4 (abaixo e na M7 E12) e fazer o merge sem perder as mudanças não commitadas do checkout principal (R1). Sem onda de correção de código.
  - Scanner `osv-scanner 2.7.0 -r --no-ignore .`: 6 lockfiles (131 a 357 pacotes), "No issues found". Nenhuma dependência nova.
  - Verificação mecânica `check_test_integrity.py --base 83fd21f`: 2 `ASSERCAO_REMOVIDA` (`assertEquals(303, contract)` → 335 e `assertEquals(353, codes.size)` → 377), justificadas pela revisora: atualização de contagem pelo contrato novo, com teste mais específico ao lado.
  - Gate em `d9d1c02`: `./gradlew build` BUILD SUCCESSFUL (2032 testes, 0 falhas, 19 pulados, as verificações ao vivo); `bash tests/run.sh` 36 arquivos, 0 falhas; `ktlintCheck detekt` verde.
- **Critérios verificados:**
  - **Pin:** `commit=57eb5fac5b8a18df01ed4f30e099ab1cd8dcb27b`; sha256 `60b309db82622662e2a1196504fe48bac8dd742b1ed51f7bcde1e769535c2d08` na cópia, no pin e no `git show` do servidor; 335 `operationId`.
  - **Pré-voo:** antes da troca, aditivo (código 11), `ca9f9e2f9808` × `57eb5fac5b8a`; depois, `igual (código 0)`.
  - **SDK:** o ogen gerou o contrato inteiro sem erro e sem código à mão. Tags novas em `PrintingApi` e `EndOfDayApi`. Caracterização no `SdkAttackTest`: os PDFs (ficha, confirmação, extrato e `getPrintJobContent`) saem como `HttpResponseTyped<Any>` e os bytes vêm íntegros por `raw` (os 256 valores de byte e 70 KB); `genderDescription` é `Option<String?>` (`Some`, `Some(null)`, `Undefined`, nos dois sentidos, também pelo `demo-seed`); o `ogenAuthAttr` de `/print-agents/me/identity` é `printAgentToken`, e o de uma operação comum é `bearerAuth`.
  - **Conserto:** a compilação não quebrou. O que quebrou foram os testes do catálogo de erros do `core`: o contrato trouxe 24 códigos novos, e o `ErrorCatalog.kt` ganhou 24 mensagens pt-BR (R5).
  - **Ambiente no notebook:** `down.sh -v`, `up.sh` (build da imagem `zeny-hotel-manager:57eb5fac5b8a`, `cc6ee5ad27bd`), readiness `{"status":"UP"}`, `smokeTest -PserverUrl=http://127.0.0.1:8088` 1/1 (14,9 s), com os segredos de `.server/seed-secrets.env`.
  - **Pacote** (`build/demo-package/` do checkout principal, 90 MB mais `images/zeny-server-images.tar` de 427 MB): `VERSAO.txt` com `front_commit=9bf0bb2…`, `front_dirty=false`, `server_commit=57eb5fac5b8a…`, `zeny-desktop.jar` sha256 `803f4eb8…` e `demo-seed.jar` `7b9e668d…`; nenhum `.env`.
  - **Revisão de `tools/server-env/`** (Task 2). No servidor, entre `ca9f9e2f9808` e `57eb5fac5b8a`, só mudaram `docker/gate/` e `docker/prometheus/alerts.yaml`; o diff de `docker/compose.yaml`, `docker/.env.example` e `docker/Dockerfile` é vazio, e os dois `.env.example` têm as mesmas 21 chaves.

    | Arquivo | Resultado | Motivo |
    |---|---|---|
    | `compose.override.yaml` | sem mudança | só toca `api.image`, o endpoint público e `proxy.ports`; o compose do servidor não mudou; `compose config` dá projeto `zeny-front` e `127.0.0.1:8088->80` |
    | `up.sh` | sem mudança | sobe só `docker/compose.yaml`; `compose config --services` = api, db, proxy, storage, storage-init |
    | `down.sh` | sem mudança | mesmo projeto `zeny-front` |
    | `package.sh` | sem mudança | o `git archive` leva `docker/gate/` para `server/` (inerte, sem segredo); override e `LEIA-ME` não o citam (testes novos) |
    | `save-images.sh` | sem mudança | lê só `docker/compose.yaml`; `compose config --images` = caddy, minio, postgres e `zeny-hotel-manager:57eb5fac5b8a` (testes novos) |
    | `gen-env.sh` | sem mudança | as chaves do `.env.example` não mudaram |
    | `lib.sh` | sem mudança | `server_tag` resolve `57eb5fac5b8a`; `ensure_server_tree` extraiu a árvore nova e conferiu o sha256 |
    | `.env.example` | sem mudança | idêntico em chaves ao do servidor |
    | `LEIA-ME.md` | sem mudança | não cita o gate |

    Testes de regressão: dois casos em `tests/test_package.sh` e o arquivo `tests/test_package_gate_etapa_ataque.sh` (imagem repetida no gate, outros compose em `docker/`, caminho de erro, pacote sem `.env`), todos com teste de mutação.
  - **Servidor só leitura:** `status --porcelain` vazio antes e depois. O `worktree list` tem os mesmos caminhos e branches do início, mais `/tmp/claude-1000/-home-caio-repos-ZenyHotelManager/399fb2e7-…/scratchpad/old-89601c2` (de uma sessão aberta no repositório do servidor, com outro id) e o `roadmap/m6-operacao-fechamento` avançado de `29ff8d7` para `69588ca` pelo orquestrador do servidor. Nada foi criado por esta sessão.
  - **Pedidos:** `docs/pedidos-servidor.md` só ganhou a tabela da rodada 2 da análise; nenhum `PF-NN`.
  - **Pendente (usuário, Windows):** o roteiro do M1 com o pacote novo.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  1. **R1, análise trazida do checkout principal:** a análise da rodada 2 (esta etapa, o ADR 0009, as etapas novas da M2 à M7, `pedidos-servidor.md` e o design) só existia sem commit no checkout principal. Ela foi copiada byte a byte para a branch (`29cc674`). No merge, as mesmas mudanças sem commit do checkout principal são conferidas contra esse commit antes de restauradas. Custo se errado: reverter `29cc674`.
  2. **R4, `printAgentToken` fora do `core` (segurança):** o `AuthPlugin` (`core/.../session/AuthPlugin.kt:34-37`) põe o `Bearer` do usuário em qualquer requisição com `ogenAuthAttr` não vazio, e o `ApiExecutor` (`core/.../http/ApiExecutor.kt:81-87`) transforma o `401` em `SessionEnded`. Nas 5 rotas `/print-agents/me/*`, isso encerraria a sessão do usuário. Hoje ninguém as chama (ADR 0009). O defeito ficou caracterizado por teste e registrado na M7 E12, sem mudança no `core`. Correção recomendada, quando for preciso: só tratar como sessão de usuário o requisito `bearerAuth`, falhando fechado. **Pendência do arquiteto:** a frase do ADR 0007 ("o `ogenAuthAttr` diz se a operação exige `bearerAuth`") deixou de valer para essas 5 operações.
  3. **R5, 24 mensagens de erro novas:** o catálogo de erros do `core` ganhou mensagens pt-BR para os códigos novos (fechamento do dia, envios à FNRH, impressão, ficha e confirmação), escritas pelo desenvolvedor a partir das descrições do contrato. A redação é revista nos marcos que exibem esses códigos.
  4. **R6, demo local recriada:** o `zeny-front` do notebook foi recriado com `down.sh -v` no servidor novo e semeado pelo `smokeTest`. A demo da E05 do M1 deixou de existir (as senhas continuam em `.server/seed-secrets.env`). A imagem `zeny-hotel-manager:ca9f9e2f9808` continua no Docker.
  5. **Pacote com `server/docker/gate/`:** inerte (o fluxo do `LEIA-ME` não o usa) e sem segredo; só aumenta o pacote. Excluí-lo do `git archive` é decisão do arquiteto.
- **Notas para a próxima etapa:**
  - **Na volta ao Windows:** o usuário segue o `roteiro-m1.md` do pacote mais recente (`build/demo-package/`) no Windows, do zero. Para não construir a imagem lá, use o `docker load` de `images/zeny-server-images.tar`, que traz a imagem do servidor fixado. A aprovação fecha esta etapa como `#concluida`.
  - **M2 E07 (ficha em PDF):** `ApiExecutor.run` chama `typed.body()` (`ApiExecutor.kt:54`). Com `HttpResponseTyped<Any>`, uma fachada de PDF que passe por ali provavelmente vira `IncompatibleResponse` (não testado no `core`). A fachada deve ler os bytes de `raw`, sem log e sem disco (design §6.2).
  - **M7 E12:** ver a nota do R4 na própria etapa.
  - **Menores estacionados:** a checagem de "português" e de "fechamento" no `ErrorCatalogNewContractCodesTest` é frouxa (a cobertura real é o `ErrorCatalogTest`); a redação de 3 mensagens ("agente de impressão", "trabalho de impressão", `PRINT_CONTENT_TOO_LARGE` sem dizer o que fazer); a ordem de `"EndOfDay"` na lista do `SdkAttackTest`; a asserção solta `"gate"` em `tests/test_package.sh` (a versão ancorada está no arquivo de ataque); a caracterização do `printAgentToken` cobre só `identity`, não as 5 rotas.
  - **Ambiente local:** `zeny-front` no ar em `57eb5fac5b8a`, semeado pelo `smokeTest`; `.server/57eb5fac5b8a…/` criado. As ondas paralelas não foram usadas (as 2 tasks eram dependentes).

---

## E03 — Sincronizar com o servidor em `5e06f16d51ff` (rodada 3) — #concluida

**Objetivo:** o front passa a apontar para a `main` do servidor em `5e06f16d51ff9913f0da09df8f62639996cd6c52`. O pin e a cópia do contrato são trocados e o SDK é regenerado. O tipo que o ogen gera para o primeiro `oneOf` do contrato fica caracterizado por teste. As telas existentes ganham os rótulos dos valores novos de enum, e os roteiros e o `LEIA-ME` passam a avisar que a data operacional avança sozinha. Ao fim, o `smokeTest` passa contra o servidor novo, e o M3 pode começar.

**Por que agora:** a regra 3 de `docs/roadmaps/README.md` manda sincronizar antes do M3. O servidor novo traz as etapas do fechamento do dia, que desbloqueiam a M6 E08, e os rótulos da FNRH na ficha da M2 E07.

**Dependências:** E02. Pela regra 7 do README, a E02, aguardando o Windows, conta como concluída.

**Contexto (análise de 2026-11-15, `claude --agent arquiteto`):**

- **Pré-voo de 2026-11-15:** aditivo (código 11). O pin `57eb5fac5b8a` está 131 commits atrás da `main` (`git rev-list --count`), e não há `ERR` em `/api/v1`. O oasdiff dá 3 `warning` `response-body-wrapped-in-one-of-original-preserved`, um em cada operação da linha seguinte.
- **Contrato**, pelo diff de `git show <commit>:api/openapi.yaml` entre os dois commits:
  - 335 operações nos dois. Nenhuma operação entrou ou saiu.
  - Um schema novo, `GuestWriteSummaryResponse` `{id, restricted}`.
  - A resposta `200` de `restrictGuest`, `liftGuestRestriction` e `mergeGuests` passou a ser `oneOf: [GuestResponse, GuestWriteSummaryResponse]`, sem discriminador. É o primeiro `oneOf` do contrato. A regra é do servidor (ARCHITECTURE §7 dele, M6 E17), e o tratamento no front está no [ADR 0010](../adr/0010-resposta-que-varia-com-a-permissao.md).
  - Valores novos de `x-extensible-enum`:
    - `ReservationHistoryResponse.kind`: `NO_SHOW`;
    - `RoomAssignmentResponse.releaseReason`: `RESERVATION_NO_SHOW`;
    - `FnrhSubmissionResponse.operation`: `NO_SHOW`;
    - `FnrhSubmissionResponse.lastFailure`: `REMOTE_NO_SHOW`.
  - Mudaram só descrições:
    - a ficha de registro mostra o rótulo do catálogo de domínios da FNRH no motivo da viagem e no meio de transporte (M6 E15 do servidor);
    - `GuestResponse.genderDescription`.
  - Nenhum código de erro novo apareceu no diff.
- **Servidor**, de `57eb5fac5b8a` a `5e06f16d51ff`:
  - entraram as M6 E11, E14, E15, E16 e E17 dele;
  - a E11 traz as 8 etapas do fechamento do dia: `no-show`, `pending-departures`, `room-charges`, `block-release`, `provisional-expiry`, `card-pre-authorizations` (`NOT_APPLICABLE`), `occupied-rooms-dirty` e `advance-business-date`;
  - a E18 dele (cursor, rollback, datas estritas) **não** está neste pin.
- **`docker/` do servidor:** `git diff 57eb5fac5b8a 5e06f16d51ff -- docker/` vazio. O `application.yaml` só ganhou `zeny.endofday.trigger.wait`, com padrão (`PT5S`). Não há variável de ambiente nova.
- **Fechamento automático:**
  - O `EndOfDayTriggerJob` roda a cada minuto.
  - Num banco novo, a data operacional é a de hoje, no fuso do hotel (`BusinessDay.start`).
  - O horário padrão do fechamento (`endOfDayTime`) é 03:00. Antes do meio-dia, o fechamento vence no dia seguinte (`EndOfDaySchedule.dueAt`). O gatilho só age até 24 h depois do vencimento.
  - Por isso, um ambiente que fica no ar até as 03:00 do dia seguinte ao da criação é fechado sozinho:
    - as chegadas confirmadas sem check-in viram `NO_SHOW`, com a multa da política;
    - as diárias são lançadas;
    - as saídas pendentes viram pendências;
    - a data avança.
  - A regra 5 do README foi revista nesta análise.
- **Pedidos ao servidor:** PF-01 e PF-02 continuam abertos. Nenhuma captura do brain e nenhum documento do servidor (`main` e branches de roadmap) cita "atende PF-NN".

**Escopo:**

- **Pin e contrato:** `api/server-pin.properties` e `api/openapi.yaml` em `5e06f16d51ff`, pelo mecanismo da E01.
- **SDK:** regenerar e compilar o contrato inteiro (ADR 0007, item 1). O teste de cobertura continua amarrando as 335 operações. Um teste de caracterização no `sdk` fixa o tipo gerado para a resposta de `restrictGuest`, `liftGuestRestriction` e `mergeGuests`. Ele prova que os dois ramos chegam íntegros pelo cliente gerado: um `GuestResponse` completo e um resumo `{id, restricted}`. O `core` não muda por causa do `oneOf`: a fachada é da M5 E02 (ADR 0010).
- **Rótulos nas telas existentes:** `NO_SHOW` no histórico da reserva e `RESERVATION_NO_SHOW` na alocação solta, no detalhe da reserva da M2 E01, com texto pt-BR próprio em vez de "Outro (VALOR)". Os dois valores da FNRH ficam para a tela dela (M7 E10).
- **Conserto:** o que quebrar em `core`, `app`, `demo-seed` e nos testes. Nada de funcionalidade nova.
- **Revisão de `tools/server-env/`:** confirmar, arquivo por arquivo, que o override, os scripts e o `.env.example` continuam valendo com `docker/` igual, e registrar o resultado no handoff.
- **Fechamento automático na demo:**
  - o fluxo 3 do `LEIA-ME` ("Parar e recriar a demo") e o início de `docs/demo/roteiro-m1.md` e `docs/demo/roteiro-m2.md` avisam que a recriação vale para o dia em que é feita e que o servidor fecha o dia sozinho às 03:00 do dia seguinte. Esse aviso segue a regra 5 revista;
  - no passo da ficha do `roteiro-m2.md`, o "(código FNRH)" passa a ser a exceção (código fora do catálogo), não o esperado.
- **Ambiente e pacote:** subir `zeny-front` em `5e06f16d51ff` do zero (`down.sh -v` antes) e regenerar `build/demo-package/`.
- **Verificação:** o `smokeTest` contra o servidor novo.
- **Pedidos:** `docs/pedidos-servidor.md` sem mudança de estado.

**Fora de escopo:**

- telas de restrição e mesclagem, com a fachada do `oneOf` (M5 E02, ADR 0010);
- a tela do fechamento do dia (M6 E08, desbloqueada por esta rodada);
- a fila de envio à FNRH e os rótulos dela (M7 E10);
- mudar o `endOfDayTime` no `demo-seed`. A análise manteve o padrão de 03:00 (ver a regra 5);
- disparar um fechamento manual (`POST /api/v1/end-of-day/runs`) no ambiente da etapa;
- qualquer escrita no repositório do servidor.

**Critérios de aceite:**

- [x] `api/server-pin.properties` tem `commit=5e06f16d51ff9913f0da09df8f62639996cd6c52`, e `sha256sum api/openapi.yaml` é igual a `contract_sha256` e ao hash de `git -C <servidor> show 5e06f16d51ff:api/openapi.yaml`. *(Os três: `6c0512fdd91e805b6a67664b6881080707eaea820b3425ae92884250daeebdc9`; `cmp` idênticos.)*
- [x] `tools/server-drift.sh` sai com "igual" (código 0) logo após a troca, se a `main` do servidor ainda estiver em `5e06f16d51ff`. Se ela tiver andado, a saída vai para o handoff e o pin fica em `5e06f16d51ff`. *(`igual (código 0)`, base e alvo `5e06f16d51ff`.)*
- [x] O SDK compila as 335 operações, e o teste de cobertura do SDK amarra os 335 `operationId` a métodos gerados. *(`SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod`, 335.)*
- [x] Um teste do `sdk` caracteriza o tipo de resposta gerado para `restrictGuest`, `liftGuestRestriction` e `mergeGuests`, e os dois corpos (completo e resumo) passam pelo cliente gerado sem perda. O tipo encontrado fica registrado no handoff e no ADR 0010, se divergir de `JsonElement`. *(`HttpResponseTyped<JsonElement>`, como o ADR 0010 previa; o ADR não muda. `SdkOneOfTest` e `SdkOneOfAttackTest`.)*
- [x] No detalhe da reserva, `NO_SHOW` (histórico) e `RESERVATION_NO_SHOW` (alocação) têm rótulo pt-BR próprio, e um teste prova que nenhum dos dois cai em "Outro (…)". *("Não compareceu" nos dois; `ReservationDetailNoShowLabelTest`.)*
- [x] `./gradlew build` e `bash tests/run.sh` verdes, e `./gradlew ktlintCheck detekt` verde. *(Em `fdfbc89`: 3884 testes, 0 falhas, 34 pulados; 48 arquivos, 0 falhas; lint verde.)*
- [x] `zeny-front` sobe do zero em `5e06f16d51ff` (notebook ou Windows) com readiness `UP`, e o `smokeTest` passa contra ele. *(No notebook: `down.sh -v`, `up.sh`, `{"status":"UP"}` com `zeny-hotel-manager:5e06f16d51ff`; `smokeTest` 1/1.)*
- [x] O fluxo 3 do `LEIA-ME` e o início dos dois roteiros avisam do fechamento automático às 03:00, e o passo da ficha do `roteiro-m2.md` trata "(código FNRH)" como exceção. *(`tests/test_fechamento_automatico.sh` e o de ataque.)*
- [x] A revisão de `tools/server-env/` está no handoff, arquivo por arquivo, com o resultado e o motivo.
- [x] O usuário executou o `roteiro-m2.md` no Windows com o pacote regenerado e aprovou. Pela regra 7 do README, essa validação é acumulada para o gate do M3. *(aprovado pelo usuário em 2026-11-17 ("A demo que rodei no windows deu certo"), na execução única do `roteiro-m3.md` com o pacote mais recente (o de `a800b25`, entrada 4 da lista "Validações acumuladas no Windows"); o roteiro do M3 acumula o do M2.)*
- [x] O repositório do servidor fica igual antes e depois (`status --porcelain` e `worktree list`). *(`status --porcelain` vazio antes e depois; o `worktree list` mudou só pelo orquestrador do servidor, ver o handoff.)*

**Validação obrigatória:**

- `tools/server-drift.sh` → "igual" (ou o registro da defasagem nova).
- `./gradlew build` + `bash tests/run.sh` → verdes; `./gradlew ktlintCheck detekt` → verde.
- `./gradlew smokeTest -PserverUrl=…` contra `zeny-front` em `5e06f16d51ff` → verde.
- Roteiro do M2 executado pelo usuário no Windows (acumulado, regra 7).

**Revisão humana:** sim. Troca o servidor da build de demo, e só o usuário executa o roteiro acumulado. Pela regra 7, a parada no Windows não segura o lote: a etapa fica "Aguardando o Windows" e entra na lista acumulada.

**Notas para o plano:**

- É a primeira vez que o ogen vê um `oneOf`. Segundo a ARCHITECTURE §7 do servidor, ele gera `JsonElement`, mas o front ainda não provou isso. Se a geração ou a compilação falhar por causa do `oneOf`, pare e relate: trocar de gerador, excluir operação ou escrever código à mão no `sdk` é decisão de arquitetura (ADR 0007, critério 1).
- Ninguém no `core` nem no `demo-seed` chama hoje as três operações. A troca do tipo de resposta não deve quebrar a compilação. Se quebrar, o conserto mínimo vale, e a fachada tipada fica com a M5 E02.
- O catálogo de erros do `core` não deve mudar, porque o diff não trouxe código novo. Se o teste que conta os códigos do contrato mudar, registre no handoff.
- **Recrie do zero.** O `zeny-front` do notebook está em `57eb5fac5b8a`, com dados de 2026-11-13. Reaproveitar os volumes deixaria a data operacional antiga: o gatilho não age depois da janela de 24 h e só avisa `END_OF_DAY_PENDING`. Por isso, `down.sh -v` antes do `up.sh`.
- Não dispare o fechamento manual nesta etapa. Ele avança a data e desalinha o seed do roteiro. Quem exercita o fechamento é a M6 E08.
- A redação dos rótulos novos segue a dos existentes em `ReceptionText` (por exemplo, a situação `NO_SHOW` já é "Não compareceu").
- Um Gradle por vez no notebook: o orquestrador do servidor está ativo (`roadmap/m5-recepcao-fnrh` e `roadmap/m6-operacao-fechamento`), e o gate dele já foi morto por falta de memória com o Gradle do front rodando junto.
- Na lista "Validações acumuladas no Windows" do README, esta etapa entra com a mesma execução do `roteiro-m2.md` das entradas 1 e 2, com o pacote desta etapa.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- O ogen gera para o `oneOf` algo diferente do `JsonElement` anunciado. O teste de caracterização registra o tipo real, e o ADR 0010 ajusta o passo 1 da decisão.
- A `main` do servidor anda de novo durante a etapa (a E18 dele está em curso). O pin fica em `5e06f16d51ff`, que foi o analisado, e a próxima rodada trata o resto.
- O ambiente da demo atravessa as 03:00 sem recriação, e o roteiro encontra no-shows e a data avançada. O aviso nos roteiros e no `LEIA-ME` cobre isso.

**Evidências e handoff:**

- **Estado (2026-11-17):** `#concluida`. A validação no Windows foi aprovado pelo usuário em 2026-11-17 ("A demo que rodei no windows deu certo"), na execução única do `roteiro-m3.md` com o pacote mais recente (o de `a800b25`, entrada 4 da lista "Validações acumuladas no Windows"). Fechada pelo `claude --agent orquestrador` do lote que fechou o M3.
- **Estado anterior (2026-11-16):** `#em-andamento`, **aguardando o Windows**. A implementação foi revisada e integrada na `main`, a verificação da etapa foi feita no notebook e o pacote foi regenerado. Falta só o usuário executar o `roteiro-m2.md` no Windows com o pacote novo e aprovar; pela regra 7 do [README](README.md), a etapa entrou na lista "Validações acumuladas no Windows" (item 3), conta como concluída para dependências, e o M3 pode começar. Não retomar esta etapa antes da aprovação do usuário.
- **Pré-voo (início do lote, 2026-11-15):** aditivo (código 11), pin `57eb5fac5b8a` × `main` `5e06f16d51ff`, 3 `warning` de `oneOf`, 0 `error`.
- **Plano:** [`docs/superpowers/plans/2026-11-15-m0-sincronizacao-e03.md`](../superpowers/plans/2026-11-15-m0-sincronizacao-e03.md), 3 tasks. Revisão de arquitetura (`revisor-arquitetura`, opus): bloqueado na rodada 0 (5 Importantes: o README do pin fora da Task 1, caminho para tipo diferente de `JsonElement`, segredos no `compose config`, memória e caminho de falha do ambiente, símbolo do teste de cobertura) e aprovado com notas na rodada 1.
- **Commits** na branch `roadmap/m0-sincronizacao` (cada um com o trailer `Soul:`):
  - documentação: `fa91d53` (início), `a1d8602`, `29089d6`, `4768396` (plano e revisão);
  - Task 1, pin, contrato e caracterização do `oneOf`: `4768396..f014c3b` (`f262c4e` desenvolvedor, `f014c3b` testador);
  - Task 2, rótulos do no-show: `f014c3b..268d36e`;
  - Task 3, aviso do fechamento automático: `268d36e..fd183fe`;
  - testador de etapa: `9a9077c`, `fdfbc89` (correção do `detektTest`); este handoff.
- **Revisão final** (`revisor-seguranca`, opus): aprovado com ressalvas, "Ready to merge: sim" quanto ao código, 0 Críticos e 0 Importantes. Três Menores estacionados (abaixo). Sem onda de correção.
  - Scanner `osv-scanner 2.7.0 scan source -r --no-ignore .`: 6 lockfiles (131 a 361 pacotes), "No issues found". Nenhuma dependência, lockfile ou script de build mudou.
  - Verificação mecânica `check_test_integrity.py --base ebfd468`: "OK: nenhum apontamento" (também por task).
  - Gate em `fdfbc89`: `./gradlew build` BUILD SUCCESSFUL (3884 testes, 0 falhas, 34 pulados, as verificações ao vivo); `bash tests/run.sh` 48 arquivos, 0 falhas; `ktlintCheck detekt` verde. Um gate anterior, em `9a9077c`, caiu no `:app:detektTest` (`RedundantSuspendModifier` num teste do testador), corrigido em `fdfbc89`.
- **Critérios verificados:**
  - **Pin:** `commit=5e06f16d51ff9913f0da09df8f62639996cd6c52`; sha256 `6c0512fdd91e805b6a67664b6881080707eaea820b3425ae92884250daeebdc9` na cópia, no pin e no `git show` do servidor; `cmp` idênticos; 335 `operationId`; `README.md` cita o pin novo.
  - **Pré-voo depois da troca:** `igual (código 0)`.
  - **SDK e `oneOf`:** o ogen gerou e compilou o contrato inteiro sem código à mão. As três operações devolvem `HttpResponseTyped<JsonElement>` (`GuestsApi.kt` gerado), e o modelo `GuestWriteSummaryResponse(id: Uuid, restricted: Boolean)` existe. `SdkOneOfTest` (7) prova os dois ramos íntegros nas três operações, com a árvore JSON inteira, o `ETag` e a decodificação pelo serializer gerado; a declaração explícita do tipo não compila se ele mudar (provado). `SdkOneOfAttackTest` (12) cobre `If-Match`, método e caminho, `ETag` ausente, precisão e unicode, e corpos fora do contrato. O ADR 0010 não muda.
  - **Conserto:** nada quebrou em `core`, `app` ou `demo-seed`; o catálogo de erros continua com 377 códigos.
  - **Rótulos:** `ReceptionText` ganhou `NO_SHOW` → "Não compareceu" (histórico) e `RESERVATION_NO_SHOW` → "Não compareceu" (motivo de liberação), o mesmo texto da situação `NO_SHOW`. `ReservationDetailNoShowLabelTest` passa pelo estado do detalhe: "Não compareceu" no histórico, "Solta · Não compareceu" na alocação, nenhum "Outro (".
  - **Avisos:** o mesmo parágrafo no fluxo 3 do `LEIA-ME` (logo depois do `down -v`/`up -d`) e na seção 0 dos dois roteiros (antes da folha de senhas); o passo 13 do `roteiro-m2.md` trata "(código FNRH)" como exceção. Testes estáticos com mutação, unicidade, posição e chegada ao pacote.
  - **Ambiente no notebook:** `down.sh -v`, `up.sh` (build da imagem `zeny-hotel-manager:5e06f16d51ff`), readiness `{"status":"UP"}`, `smokeTest -PserverUrl=http://127.0.0.1:8088` 1/1, com os segredos de `.server/seed-secrets.env` (a senha do administrador conferida com a inicial do `.env` antes, sem imprimir valores).
  - **Pacote** (`build/demo-package/` do checkout principal, 503 MB com `images/zeny-server-images.tar`): `VERSAO.txt` com `front_commit=fdfbc89…`, `front_dirty=false`, `server_commit=5e06f16d51ff…`, `zeny-desktop.jar` sha256 `844cf108…` e `demo-seed.jar` `a8b4a512…`; nenhum `.env`; o aviso aparece uma vez no `LEIA-ME.md` e nos dois roteiros do pacote.
  - **Revisão de `tools/server-env/`.** No servidor, `git diff 57eb5fac5b8a 5e06f16d51ff -- docker/` é vazio; o `application.yaml` só ganhou `zeny.endofday.trigger.wait` (`PT5S`, com padrão); o wrapper do Gradle não mudou; os dois `.env.example` têm as mesmas 21 chaves. A configuração efetiva saiu só por `config --services`, `config --images` e `config --format json` filtrado em `name` e `services.proxy.ports`.

    | Arquivo | Resultado | Motivo |
    |---|---|---|
    | `compose.override.yaml` | sem mudança | o compose do servidor não mudou; `name` = `zeny-front`, `proxy` em `127.0.0.1:8088->80` |
    | `up.sh` | sem mudança | subiu do zero e construiu `zeny-hotel-manager:5e06f16d51ff`; serviços db, storage, storage-init, api, proxy |
    | `down.sh` | sem mudança | o `down -v` atingiu só o projeto `zeny-front` |
    | `package.sh` | sem mudança | gerou o pacote com o pin novo; o aviso chega ao `LEIA-ME` e aos roteiros do pacote (teste de ataque) |
    | `save-images.sh` | sem mudança | `config --images` = minio, caddy, `zeny-hotel-manager:5e06f16d51ff`, postgres; gravou o `.tar` |
    | `gen-env.sh` | sem mudança | as chaves do `.env.example` do servidor não mudaram |
    | `lib.sh` | sem mudança | `server_tag` resolve `5e06f16d51ff`; `ensure_server_tree` extraiu a árvore nova e conferiu o sha256 |
    | `.env.example` | sem mudança | as mesmas 21 chaves do servidor |
    | `LEIA-ME.md` | **mudou** | o aviso do fechamento automático no fluxo 3 (Task 3) |

  - **Servidor só leitura:** `status --porcelain` vazio antes e depois. O `worktree list` tem os mesmos caminhos e branches do início; só o `roadmap/m6-operacao-fechamento` avançou (`a295c85` → `ba79642`), pelo orquestrador do servidor. Nada foi criado por esta sessão.
  - **Pedidos:** `docs/pedidos-servidor.md` sem mudança; PF-01 e PF-02 continuam abertos.
  - **Pendente (usuário, Windows, regra 7):** o `roteiro-m2.md` com o pacote novo.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  1. **R4, sem memória o M3 não começa:** se o notebook não tivesse memória para o ambiente, a E03 não contaria como concluída para o M3, porque a spec põe o `smokeTest` como condição para o M3 começar e a regra 7 só cobre o que o usuário executa no Windows. Não foi acionada (10,8 GiB livres, `smokeTest` verde). Custo se errado: o M3 esperaria memória livre no notebook.
  2. **R6, Menores estacionados:** os três Menores da revisão final ficaram sem correção (abaixo). Custo se errado: um teste de caracterização aceita qualquer exceção, e duas fixtures mudam juntas.
- **Notas para a próxima etapa:**
  - **M3 E01:** a base desta etapa ainda não foi validada no Windows (regra 7, item 3 da lista).
  - **M5 E02 (fachada do `oneOf`):** o `sdk` entrega corpo array, `null` ou primitivo sem erro, e a fachada precisa tratá-los como `IncompatibleResponse`; um completo que traga `restricted` decodifica também como resumo, então a escolha é pelo `fullName` (ADR 0010, passo 2), nunca pela ordem; o `ETag` pode faltar; o `JsonElement` do ramo completo traz dados pessoais e nunca vai a log (design §6.2). Ver `SdkOneOfAttackTest`.
  - **Menores estacionados:** `emptyBodyFailsToDecodeInsteadOfBecomingAnObject` (`SdkOneOfAttackTest`) só confere que houve falha, não o tipo; as fixtures de reserva estão copiadas em `ReservationDetailNoShowLabelTest` e `ReservationDetailNoShowAtaqueTest`; o `./gradlew ktlintCheck detekt` não roda o `detektTest`, que só o `build` pega (vale pedir `build` antes do commit nos briefs).
  - **Ambiente local:** `zeny-front` no ar em `5e06f16d51ff`, recriado em 2026-11-16 às 02:20 UTC e semeado pelo `smokeTest`. Pela regra 5, ele é fechado sozinho às 03:00 (hora do hotel) do dia seguinte; recrie antes de usá-lo num roteiro. `.server/5e06f16d51ff…/` criado; a imagem `zeny-hotel-manager:57eb5fac5b8a` continua no Docker. As ondas paralelas não foram usadas (R1: um Gradle por vez).

---

## E04 — Sincronizar com o servidor em `8c563cda0575` (rodada 4) — #em-andamento

**Objetivo:** o front passa a apontar para a `main` do servidor em `8c563cda0575dab697c89f2caba5a1dd9a12782e`. O pin e a cópia do contrato são trocados, e o SDK é regenerado com as 376 operações. O `core` e o `demo-seed` voltam a compilar enviando explícitos os campos que deixaram de ser obrigatórios ([ADR 0014](../adr/0014-campos-com-padrao-sempre-explicitos.md)). O catálogo de erros cobre os códigos novos. A regressão ao vivo prova que a entrada estrita do servidor novo aceita tudo o que o front já envia. Ao fim, o `smokeTest` e as verificações ao vivo passam contra o servidor novo, e o M5 pode começar.

**Por que agora:** a regra 3 de `docs/roadmaps/README.md` manda sincronizar antes do M5. Três coisas do servidor novo pesam no M5:
- a regra de `required` da M6 E20 dele, que muda os tipos do SDK que o perfil do hóspede (M5 E01) escreve;
- a entrada estrita, que vale para toda escrita;
- o gate do M5 (E05), que vai validar no Windows também o M4 (regra 7) e precisa rodar no servidor que o M6 e o M7 vão usar.

**Dependências:** E03.

**Contexto (análise de 2026-11-20, `claude --agent arquiteto`):**

- **Pré-voo:** aditivo (código 11) em 2026-11-19, registrado pelo terceiro lote do M4. O pin `5e06f16d51ff` está 174 commits atrás da `main` (`git rev-list --count`). A análise não rodou o pré-voo de novo. O diff estrutural abaixo confirma a classificação: nenhuma operação saiu, e nenhuma existente mudou.
- **Servidor**, de `5e06f16d51ff` a `8c563cda0575` (merges `46dabea` e `9dd7012` da M6 dele):
  - **Etapas transversais:**
    - **E18:** faixa do cursor, rollback em toda exceção, datas e instantes estritos no JSON e log dos jobs;
    - **E20:** entrada estrita no corpo, na query e no caminho, e `required` gerado do DTO;
    - **E21:** `409 CONCURRENT_UPDATE` e `lock_timeout` de 10 s.
  - **Governança** (módulo `housekeeping`):
    - **E01:** tipos de limpeza, créditos e faxinas programadas;
    - **E02:** geração das tarefas;
    - **E03:** distribuição sugerida e publicação;
    - **E04:** máquina de estados e ações online;
    - **E19:** comandos offline e conflito;
    - **E05:** inspeção e liberação da UH;
    - **E06:** achados e perdidos, e danos.
  - **Fora deste pin:** a E07, E08, E09, E13, E22 e E12 da M6 dele (ordens de serviço, OOO pela manutenção, relatórios, snapshot do fechamento, hora prevista de chegada e o gate dele). Algumas já têm captura na Inbox do brain, mas estão só na branch `roadmap/m6-operacao-fechamento` do servidor, à frente da `main`. Ficam para a próxima rodada.
- **Contrato**, pelo diff estrutural de `git show <commit>:api/openapi.yaml` entre os dois commits:
  - **Operações:** de 335 para 376. As 41 novas estão todas na tag nova `housekeeping`. Nenhuma saiu. Nenhuma existente mudou em parâmetros, corpo, respostas ou segurança, nem só na descrição.
  - **Primeiro `PATCH` do contrato:** `adjustAssignmentPlan`, `PATCH /api/v1/assignment-plans/{date}`.
  - **Schemas:** 40 novos e nenhum removido.
  - **`required`:** 26 schemas de requisição perderam 34 campos do `required`, e nenhum campo entrou. A regra é da M6 E20 do servidor: campo com valor padrão no DTO deixa de ser obrigatório. A lista e o tratamento estão no ADR 0014. Os que o front constrói hoje: `CheckInGuestRequest`, `CreateGuestRequest`, `GuestProfileRequest`, `PostingRequest`, `ReceivePaymentRequest`, `ReservationCancelRequest`, `ReservationDocumentSearchRequest`, `ReservationRequest` e `WalkInRequest`, no `core`; e `CreateGuestRequest`, `GuestProfileRequest`, `PostingRequest`, `RatePlanCreateRequest`, `RatePriceUpdateRequest`, `ReservationRequest`, `RoomRequest` e `RoomTypeRequest`, no `demo-seed`.
  - **Respostas que variam com a permissão:** nenhum `oneOf` novo (os 3 são os do ADR 0010) e nenhum discriminador.
  - **Enums:** valores novos só nos schemas novos (`x-extensible-enum` de 104 para 120 ocorrências, `enum` de 181 para 187). Nenhum enum existente mudou.
  - **Códigos de erro novos:**
    - `CONCURRENT_UPDATE`, nos comuns de `Problem.code`;
    - 48 da governança, nas descrições das operações novas (`ASSIGNMENT_*`, `CLEANING_TYPE_*`, `HOUSEKEEPING_*`, `SCHEDULED_CLEANING_*`).

    Nenhum saiu. A contagem é desta análise. Quem dá o número exato é o `ContractErrorCodes` do `core`.
  - **Esquemas de segurança:** iguais (`bearerAuth`, `printAgentToken`).
- **Comportamento novo, sem mudança no contrato** (avisos do servidor ao front nas capturas da E18, E20, E21, E04 e E06 dele e no fim do lote E20–E06):
  - **Formatos:**
    - `""` ou texto em branco em UUID, inteiro ou booleano dá `400 INVALID_FORMAT`;
    - número fracionário num inteiro dá `400`;
    - primitivo ausente ou `null` dá `REQUIRED`;
    - data e instante só valem como texto ISO, no corpo, na query e no caminho; fora do `StorableRange`, dão `400`;
    - `LocalTime` só vale como `HH:mm` ou `HH:mm:ss`.

    "Sem valor" é omitir o campo ou mandar `null`.
  - **`409 CONCURRENT_UPDATE`** (deadlock ou espera de trava acima de 10 s): o servidor desfaz a transação e libera a `Idempotency-Key`, então a operação pode ser repetida.
  - **Tarefas de governança geradas sozinhas:**
    - o check-out e a troca de UH geram a tarefa de saída;
    - a virada da data operacional (`BusinessDateAdvanced`, inclusive a do fechamento automático das 03:00) gera as tarefas do dia;
    - a conclusão e a inspeção mudam o status de governança da UH;
    - a UH que fica pronta gera o aviso `notifications.room-released` a quem tem `housekeeping.tasks.read`.
  - **Tempo real e avisos:** o SSE ganhou o tópico `housekeeping`, e os tipos de notificação `housekeeping.task-conflict` e `housekeeping.lost-found-expired` são novos.
  - **Carga inicial:** a migração cria os seis tipos de limpeza da SPEC (a abertura de cama desativada).
- **`docker/` do servidor:** `git diff 5e06f16d51ff 8c563cda0575 -- docker/` vazio. O `application.yaml` ganhou o `lock_timeout` da sessão do banco (`options`) e `zeny.housekeeping.*`, os dois com valor no próprio arquivo. Não há variável de ambiente nova.
- **Pedidos ao servidor:** de PF-01 a PF-06, todos continuam abertos.
  - Nenhuma captura do brain (Inbox, arquivo e notas) cita "atende PF-NN".
  - Nenhum commit do intervalo cita um `PF-NN`.
  - Nenhuma operação existente mudou, então nenhum pedido foi atendido sem citação.
- **Uso pelo front:**
  - **M5:** as operações de `guests`, `companies` e `files`, sem operação nova. Mudam a regra do `required` e a entrada estrita.
  - **M6:** 29 das 41 novas, nas etapas novas E09, E10 e E11 (tarefas, plano do dia, execução, inspeção, achados e danos), e o tópico `housekeeping`.
  - **M7:** as outras 12, na etapa nova E13 (tipos de limpeza e faxinas programadas).

  A Camareira não lê tarefas online, e o desktop não tem sync. As tarefas no desktop são da Governanta, do Gerente e da Recepção ([ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md)).

**Escopo:**

- **Pin e contrato:** `api/server-pin.properties` e `api/openapi.yaml` em `8c563cda0575`, pelo mecanismo da E01.
- **SDK:** regenerar e compilar o contrato inteiro (ADR 0007, item 1), com a classe nova da tag `housekeeping`. O teste de cobertura passa a amarrar as 376 operações.
- **Conserto pelo ADR 0014:** o `core` e o `demo-seed` voltam a compilar. Cada campo afetado vai em `Option.Some`, com o mesmo valor de hoje, e o JSON enviado pelas escritas existentes não muda. Fica fora: mudar o que a tela envia e acrescentar campo.
- **Catálogo de erros:**
  - mensagens pt-BR para os códigos novos;
  - para `CONCURRENT_UPDATE`, uma mensagem que diga que outra operação mexeu no mesmo registro ao mesmo tempo e que dá para tentar de novo.

  A classificação da regra única do resultado desconhecido (`classifyWriteFailure`, M4 E01) fica `DEFINITIVE` para esse código, porque o servidor confirma que nada foi feito. Não há repetição automática (ruling da análise).
- **Entrada estrita:** a regressão ao vivo (abaixo) é a prova. Se uma escrita do front for recusada pela regra nova, a fachada que manda o valor fora da regra é corrigida nesta etapa, com teste. Fica fora: funcionalidade nova.
- **Revisão de `tools/server-env/`:** confirmar, arquivo por arquivo, que o override, os scripts e o `.env.example` continuam valendo com `docker/` igual, e registrar o resultado no handoff.
- **Ambiente e pacote:** subir `zeny-front` em `8c563cda0575` do zero (`down.sh -v` antes) e regenerar `build/demo-package/`.
- **Verificação:**
  - o `smokeTest` contra o servidor novo;
  - todas as verificações ao vivo existentes (`Live*Check` do `app`, do M1 ao M4), contra o `zeny-front` recriado e semeado.
- **Pedidos:** `docs/pedidos-servidor.md` sem mudança de estado.

**Fora de escopo:**

- telas e fachadas da governança (M6 E09 a E11 e M7 E13) e o tópico `housekeeping` no cliente de tempo real (M6 E09);
- rótulos dos tipos de notificação novos (M6 E05);
- pedir ao servidor que separe os DTOs compartilhados entre criação e alteração; o ADR 0014 cobre o risco no front;
- disparar um fechamento manual no ambiente da etapa;
- qualquer escrita no repositório do servidor.

**Critérios de aceite:**

- [ ] `api/server-pin.properties` tem `commit=8c563cda0575dab697c89f2caba5a1dd9a12782e`. O `sha256sum api/openapi.yaml` é igual ao `contract_sha256` e ao hash de `git -C <servidor> show 8c563cda0575:api/openapi.yaml`. A análise mediu `cb90e8633777cab189c061ddd9f799e9ffb08a5f10e77d4bc8a09617ab30e1a5`.
- [ ] `tools/server-drift.sh` sai com "igual" (código 0) logo após a troca, se a `main` do servidor ainda estiver em `8c563cda0575`. Se ela tiver andado, a saída vai para o handoff, e o pin fica em `8c563cda0575`.
- [ ] O SDK compila as 376 operações, inclusive o primeiro `PATCH`, e o teste de cobertura do SDK amarra os 376 `operationId` a métodos gerados.
- [ ] Um teste das fachadas do `core` que constroem os schemas afetados (criar hóspede, check-in, walk-in, busca por documento, nova reserva, cancelamento, lançamento e pagamento) prova que o corpo enviado traz cada campo afetado, com o valor de antes da troca. Nenhum desses campos sai como `Option.Undefined` no `core` nem no `demo-seed`.
- [ ] O teste do catálogo de erros cobre todos os códigos do contrato novo. `CONCURRENT_UPDATE` tem mensagem pt-BR, e um teste prova que `classifyWriteFailure` o trata como `DEFINITIVE`.
- [ ] `./gradlew build` e `bash tests/run.sh` verdes, e `./gradlew ktlintCheck detekt` verde.
- [ ] `zeny-front` sobe do zero em `8c563cda0575` (notebook ou Windows) com readiness `UP`, e o `smokeTest` passa contra ele.
- [ ] Todas as verificações ao vivo existentes passam contra o `zeny-front` recriado e semeado. O handoff lista cada uma, com o resultado e o que foi corrigido, se algo foi.
- [ ] A revisão de `tools/server-env/` está no handoff, arquivo por arquivo, com o resultado e o motivo.
- [ ] O usuário executou o roteiro acumulado no Windows com o pacote desta etapa, ou um mais recente, e aprovou. Pela regra 7 do README, essa validação fica para o gate do M5 (M5 E05), junto com a da M4 E05.
- [ ] O repositório do servidor fica igual antes e depois (`status --porcelain` e `worktree list`).

**Validação obrigatória:**

- `tools/server-drift.sh` → "igual" (ou o registro da defasagem nova).
- `./gradlew build` + `bash tests/run.sh` → verdes; `./gradlew ktlintCheck detekt` → verde.
- `./gradlew smokeTest -PserverUrl=…` contra `zeny-front` em `8c563cda0575` → verde.
- As verificações ao vivo `Live*Check` contra o mesmo ambiente → verdes.
- Roteiro acumulado executado pelo usuário no Windows (regra 7, no gate do M5).

**Revisão humana:** sim. A etapa troca o servidor da build de demo, e só o usuário executa o roteiro acumulado. Pela regra 7, a parada no Windows não segura o lote: a etapa fica "Aguardando o Windows" e entra na lista acumulada, para ser executada no gate do M5.

**Notas para o plano:**

- **Primeiro `PATCH`:** o ogen nunca gerou um `PATCH` deste contrato (`adjustAssignmentPlan`). Se a geração ou a compilação falhar por causa dele, ou de qualquer outra operação nova, pare e relate. Trocar de gerador, excluir operação ou escrever código à mão no `sdk` é decisão de arquitetura (ADR 0007, critério 1).
- **Teste de cobertura do SDK:** o `SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod` lista as classes `<Tag>Api` à mão. A classe da tag `housekeeping` entra na lista, e o total passa a 376.
- **Onde a compilação quebra:** são os pontos listados em "Contexto", no `core` (`GuestsFacade`, `ReservationsFacade`, `BookingsFacade`, `FoliosFacade`, `PaymentsFacade`) e no `demo-seed` (`SdkBookingGateway`, `SdkConfigGateway`, `SdkPendingGateway`, `SdkFolioGateway`). Também quebram os testes que constroem esses schemas, como o `SdkOneOfTest`, que constrói `MergeGuestRequest` com `takeFromMerged`. Pela regra do `C-M6`, os testes endurecidos pelo testador são mexidos pelo testador.
- **Catálogo de erros:** o `ErrorCatalogTest` amarra hoje 377 códigos. O número novo sai do `ContractErrorCodes`, e uma mudança na contagem vai para o handoff, com a lista dos códigos novos. A redação das mensagens da governança é revista nas etapas que as exibem (M6 E09 a E11 e M7 E13).
- **`CONCURRENT_UPDATE` como `DEFINITIVE` (ruling da análise):** o servidor desfez a transação e liberou a chave. Uma nova tentativa do usuário é uma nova intenção, com chave nova, e o servidor a executa normalmente. Uma repetição automática no `core` esconderia até 10 s de espera a mais e mudaria a regra única do M4 E01, sem ganho para a tela.
- **Verificações ao vivo:** use os comandos e as variáveis registrados nos handoffs do M1 ao M4, com os segredos de `.server/seed-secrets.env` (handoff da M4 E05), sem imprimir valores. Recrie e semeie antes. Uma verificação que altera estado pode exigir uma nova recriação antes da seguinte: siga o que o handoff dela diz.
- **Recrie do zero.** O `zeny-front` do notebook está em `5e06f16d51ff`. Os volumes antigos não servem: a data operacional ficaria velha, e a imagem é outra. Por isso, `down.sh -v` antes do `up.sh`. Não dispare o fechamento manual. Ele avança a data e, desde este pin, gera também as tarefas de governança do dia.
- **Papéis do seed:** confira se o seed monta os papéis pelos modelos do servidor ou por lista própria de permissões. Pelos modelos, o Gerente e a Recepção passam a ter as permissões `housekeeping.*` (ver o ADR 0015), sem efeito nas telas atuais. Registre o que encontrar no handoff, para a M6 E09.
- **Um Gradle por vez no notebook:** o orquestrador do servidor está ativo (worktree `roadmap/m6-operacao-fechamento`).
- **Lista do README:** na lista "Validações acumuladas no Windows", esta etapa entra como item 6, com a mesma execução da entrada 5 (o `roteiro-m5.md`, no gate do M5).

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- **O ogen não gera o `PATCH` ou um schema novo.** Pare e relate (nota acima). O pin não muda sem o SDK compilando.
- **Uma verificação ao vivo é recusada pela entrada estrita.** É o que a etapa procura. Corrija a fachada que manda o valor fora da regra, com teste, e registre no handoff.
- **A `main` do servidor anda de novo durante a etapa** (a M6 dele segue com a E22 e a E07). O pin fica em `8c563cda0575`, que foi o analisado, e a próxima rodada trata o resto.
- **O seed passa de 2 minutos no servidor novo**, por causa das tarefas geradas no check-out. Meça e registre. O limite é critério do gate do M5, não desta etapa.

**Evidências e handoff:**

- **Estado (2026-11-20, 03:10):** `#em-andamento`, **lote interrompido a pedido do usuário** no meio da Task 3. As Tasks 1 e 2 estão commitadas e com revisão de fatia limpa. Falta a Task 3 inteira, o testador de etapa, a revisão final, o gate e o fechamento. Nada foi integrado na `main`: a etapa ainda não passou pelo testador de etapa nem pela revisão final.
- **Como retomar** (próximo `claude --agent orquestrador`): retome no worktree `.claude/worktrees/roadmap-m0-sincronizacao` (branch `roadmap/m0-sincronizacao`). O ledger do SDD está em `.superpowers/sdd/2026-11-20-m0-sincronizacao-e04/progress.md` desse worktree (fora do Git), com os relatórios e os briefs. **Não refaça as Tasks 1 e 2.** Comece pela Task 3, com BASE `733671e` e um desenvolvedor novo, a partir do brief `task-3-brief.md` do workspace. Se o workspace tiver se perdido, gere de novo o brief da Task 3 a partir do plano. O `MERGE_BASE` da etapa é `7b3537b`.
- **Pré-voo (início do lote):** aditivo (código 11), pin `5e06f16d51ff` × `main` `8c563cda0575`, com "Ambiente do servidor sem mudanças". Depois da troca do pin: `igual (código 0)`.
- **Plano:** [`docs/superpowers/plans/2026-11-20-m0-sincronizacao-e04.md`](../superpowers/plans/2026-11-20-m0-sincronizacao-e04.md), com 3 fatias. A revisão de arquitetura (`revisor-arquitetura`, opus) bloqueou nas rodadas 0 e 1 e aprovou na rodada 2:
  - rodada 0: a alteração de reserva estava fora do ADR 0014, faltava a escrita destrutiva em `Riscos:`, e havia 6 Menores;
  - rodada 1: o catálogo ficava vermelho entre a Task 1 e a Task 2, e um hash de senha ia para a saída.
- **Commits** na branch `roadmap/m0-sincronizacao` (todos com o trailer `Soul:`):
  - documentação: `d9575be` (início), `9115c75` (plano), `12e6fe7` e `6d1ef04` (correções do plano), `55ba278` (aprovação);
  - **Task 1**, `55ba278..f9d2a84`: pin, contrato e SDK em `8c563cda0575`, e os campos do ADR 0014 em `Option.Some` (`4940c63`, desenvolvedor). O testador adaptou 6 testes de ataque dele (`f9d2a84`). Revisão de fatia (`revisor-seguranca`, opus): aprovada, 0 Críticos e 0 Importantes;
  - **Task 2**, `f9d2a84..733671e`: o catálogo de erros passou de 377 para 426 códigos (`CONCURRENT_UPDATE` e 48 da governança), e um teste prova `CONCURRENT_UPDATE` → `DEFINITIVE` (`733671e`, desenvolvedor). Revisão de fatia (`revisor-seguranca`, sonnet): aprovada, 0 Críticos e 0 Importantes.
- **Já verificado nas fatias:**
  - pin `commit=8c563cda0575dab697c89f2caba5a1dd9a12782e`;
  - sha256 `cb90e8633777cab189c061ddd9f799e9ffb08a5f10e77d4bc8a09617ab30e1a5`, igual na cópia, no pin e no `git show` do servidor;
  - 376 `operationId`, com a classe nova `HousekeepingApi` e o `adjustAssignmentPlan` gerado, sem código à mão;
  - `ExplicitDefaultFieldsTest` (11 testes) e `SeedExplicitDefaultFieldsTest` (13 testes) verdes;
  - os 7 `*ErrorCatalog*` verdes depois da Task 2;
  - `gradlew`, `gradle/wrapper/` e `docker/` iguais entre os pins.
- **Verificação mecânica:** exit 1 nas duas fatias, julgado justificado pela revisora:
  - Task 1: `ASSERCAO_REMOVIDA`, a contagem 335 → 376 do `SdkAttackTest`, trocada pelo dono do arquivo;
  - Task 2: `ASSERCAO_REMOVIDA`, a contagem 377 → 426 do `ErrorCatalogTest`.
- **Ambiente local, deixado pela Task 3 interrompida:**
  - o `zeny-front` foi recriado às 03:09 com a imagem `zeny-hotel-manager:8c563cda0575`, readiness `{"status":"UP"}`, **sem seed**;
  - os segredos novos estão em `.server/seed-secrets.env` e `.server/live-secrets.env` do checkout principal (600, fora do Git): o admin inicial do `.server/zeny-front.env` e senhas geradas;
  - a Task 3 não deixou relatório nem commit;
  - recrie de novo (depois das 03:05) antes de semear, se o ambiente tiver atravessado um fechamento.
- **Servidor só leitura:** `status --porcelain` vazio antes e depois. No `worktree list`, só o `roadmap/m6-operacao-fechamento` avançou (`bbdafcf` → `0a20877`), pelo orquestrador do servidor.
- **Revisão final:** pendente.
- **Decisões para o usuário** (tomadas pela opção recomendada; revisáveis):
  1. **Branch criada de `7b3537b`** (a análise da rodada 4, que ainda não está na `main`), e não da `main`. A E04 e os ADRs 0014 e 0015 só existem nessa branch. O merge do lote leva a análise junto para a `main`.
  2. **Segredos novos para a demo do notebook.** O `.server/seed-secrets.env` citado na nota não existia, e a recriação do zero exige seed novo. As senhas da demo do notebook passam a ser as do arquivo 600.
- **Notas para a próxima etapa (retomada):**
  - **Lacuna para o testador de etapa:** o `SdkAttackTest` amarra o `adjustAssignmentPlan` só pela contagem, não pelo nome (Menor da revisão da Task 1).
  - **Menores estacionados:** a redação de `HOUSEKEEPING_TASK_LOCATION_INVALID`, `SCHEDULED_CLEANING_LOCATION_INVALID` e `HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID` é inconsistente entre si. Revisar na M6 e na M7, que exibem essas mensagens.
  - **Fechamento:** regenerar o pacote `build/demo-package/` sobre o HEAD final. Acrescentar esta etapa à lista "Validações acumuladas no Windows" do README como item 6.

---

## Histórico de revisões

- 2026-11-10 — roadmap criado (`claude --agent arquiteto`), com a rodada 1. As rodadas seguintes entram como etapas novas, pelo `development-roadmap` em modo atualização.
- 2026-11-10 — E01 executada pelo `claude --agent orquestrador`: implementada, revisada e integrada na `main`. Fica `#em-andamento` aguardando a parada humana (readiness no Windows). H1, H3 e H4 confirmadas; H2 aberta.
- 2026-11-10 — E01 `#concluida` pelo `claude --agent orquestrador`, com a evidência do usuário: readiness `UP` no Windows pelo `LEIA-ME`. H2 confirmada. Não resta etapa elegível no M0 até a próxima rodada de sincronização.
- 2026-11-10 — `.gitattributes` acrescentado (Menor adiado da E01), com plano revisado e revisão final aprovada (`claude --agent orquestrador`).
- 2026-11-13 — análise da rodada 2 (`claude --agent arquiteto`, `development-roadmap` em modo atualização), contra a `main` do servidor em `57eb5fac5b8a` (271 commits à frente; pré-voo aditivo, código 11). Entra a E02, a etapa mecânica, com a revisão de `tools/server-env/` diante de `docker/gate/` no escopo. As capacidades novas foram distribuídas: M2 E07, M3 E03, M4 E02, M5 E01, M6 E08, M7 E10 e M7 E12. Decisões no [ADR 0009](../adr/0009-documentos-pdf-e-impressao.md). Nenhum `PF-NN` existia, nenhum foi aberto, e nenhuma captura "atende PF-NN" foi achada.
- 2026-11-13 — E02 executada pelo `claude --agent orquestrador`: pin e contrato em `57eb5fac5b8a` (335 operações), SDK regenerado, 24 mensagens de erro novas no `core`, `tools/server-env/` revisto (sem mudança, com testes de regressão para `docker/gate/`), `zeny-front` no ar e `smokeTest` verde no notebook, pacote regenerado. Revisão final aprovada com ressalvas, integrada na `main`. Fica `#em-andamento` aguardando a parada humana (roteiro do M1 no Windows).
- 2026-11-13 — o usuário decidiu acumular a validação no Windows até voltar a ter acesso a ele (regra 7 do README). A E02 fica `#em-andamento`, aguardando o Windows, e conta como concluída para dependências; o M2 pode começar (`claude --agent orquestrador`).
- 2026-11-15 — análise da rodada 3 (`claude --agent arquiteto`, `development-roadmap` em modo atualização) contra a `main` do servidor em `5e06f16d51ff`: 131 commits à frente, pré-voo aditivo (código 11), com 3 `warning` de `oneOf`. Entra a E03, a etapa mecânica, com a caracterização do primeiro `oneOf` no SDK, os rótulos do no-show e o aviso do fechamento automático nos roteiros. Decisão no [ADR 0010](../adr/0010-resposta-que-varia-com-a-permissao.md). A M6 E08 sai de `#bloqueada`. A regra 5 do README foi revista, porque a data operacional passa a avançar sozinha às 03:00. A M3 E04, a M3 E05, a M5 E02 e a M7 E10 ganharam notas. PF-01 e PF-02 continuam abertos, e nenhuma captura "atende PF-NN" foi achada.
- 2026-11-16 — E03 executada pelo `claude --agent orquestrador`: pin e contrato em `5e06f16d51ff`, o primeiro `oneOf` caracterizado como `HttpResponseTyped<JsonElement>` (o ADR 0010 não muda), rótulos do no-show no detalhe da reserva, aviso do fechamento automático no `LEIA-ME` e nos roteiros, `zeny-front` recriado e `smokeTest` verde no notebook, pacote regenerado. Revisão final aprovada com ressalvas, integrada na `main`. Fica `#em-andamento` aguardando o Windows (regra 7, item 3 da lista), e o M3 pode começar.
- 2026-11-17 — revogação do processo acelerado ([ADR 0012](../adr/0012-revoga-o-processo-acelerado.md), `claude --agent arquiteto`): a seção "Prompt acelerado" foi removida. O protocolo e o prompt de execução não mudaram, porque já remetem ao orquestrador para o ciclo por tarefa. Nenhuma etapa mudou.
- 2026-11-17 — E02 e E03 `#concluida`: o usuário aprovou a execução no Windows do `roteiro-m3.md` (entradas 1 e 3 da lista "Validações acumuladas no Windows" do README) (`claude --agent orquestrador`).
- 2026-11-20 — análise da rodada 4 (`claude --agent arquiteto`, `development-roadmap` em modo atualização) contra a `main` do servidor em `8c563cda0575`: 174 commits à frente, pré-voo aditivo (código 11, de 2026-11-19).
  - Entra a E04, a etapa mecânica.
  - O contrato passa de 335 para 376 operações, as 41 novas na tag `housekeeping`.
  - 34 campos de requisição deixaram de ser obrigatórios. Pelo [ADR 0014](../adr/0014-campos-com-padrao-sempre-explicitos.md), decidido pelo usuário em entrevista, o `core` os envia sempre explícitos.
  - O servidor passou a ler a entrada de forma estrita e responde `409 CONCURRENT_UPDATE`.
  - A governança foi para o M6 (E09 a E11) e para o M7 (E13). O [ADR 0015](../adr/0015-tarefas-de-governanca-sem-a-camareira-no-desktop.md), decidido pelo usuário em entrevista, tira a Camareira das tarefas no desktop.
  - De PF-01 a PF-06, todos seguem abertos, e nenhuma captura "atende PF-NN" foi achada.
