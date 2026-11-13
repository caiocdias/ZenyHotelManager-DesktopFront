# Roadmap — M5 Hóspedes e empresas

> Arquivo: `docs/roadmaps/roadmap-m5-hospedes-empresas.md` · branch de execução: `roadmap/m5-hospedes-empresas` · criado em 2026-11-10 · última revisão 2026-11-13

> **Pré-requisito:** a etapa E05 ("Gate do marco M4") de `docs/roadmaps/roadmap-m4-contas-caixa-checkout.md` precisa estar `#concluida`. Se a `main` do servidor avançou desde a fixação, rode antes a rodada do M0 (`docs/roadmaps/README.md`, regra 3).

## Como usar

Inicie uma sessão limpa do Claude Code na raiz do repositório com `claude --agent orquestrador` e cole o "Prompt de execução". Ele executa até 4 etapas em sequência na branch `roadmap/m5-hospedes-empresas`. Cada etapa é planejada pelo plugin superpowers e executada pelas souls (subagentes do Second Brain), e a execução para para revisão. No fim do lote, o orquestrador integra a branch na `main` com merge local e deixa pronto o `git push origin main`, que fica com você. Para não parar em cada pedido de permissão, libere antes os comandos de build/teste do projeto nas permissões do Claude Code.

## Objetivo

O app passa a cuidar do cadastro de hóspedes e empresas com LGPD de verdade:

- busca e perfil completo do hóspede, com documentos sempre mascarados;
- revelação de documento só por ação própria, que o servidor registra na trilha;
- dados sensíveis da FNRH, anexos e consentimentos;
- duplicados e mesclagem, e lista de restrição;
- exportação dos dados do titular e anonimização, as duas com aprovação;
- empresas (pagadoras e clientes).

## Contexto confirmado

- **Base:** M1 a M4. O diálogo de hóspede da M2 E02 é o ponto de partida.
- **Operações do contrato** (`api/openapi.yaml`, commit fixado):
  - **perfil:**
    - `GET`/`POST /api/v1/guests`, `GET`/`PUT /api/v1/guests/{id}`;
    - `POST /api/v1/guests/search-by-document`;
    - `PUT /api/v1/guests/{id}/sensitive-data`.
  - **documentos:** `POST /api/v1/guests/{id}/documents`, `/documents/{documentId}/remove` e `/documents/{documentId}/reveal`.
  - **anexos:**
    - `GET`/`POST /api/v1/guests/{id}/attachments` e `/attachments/{fileId}/remove`;
    - `POST /api/v1/files`, `GET /api/v1/files/{id}/download-url` e `/content`.
  - **consentimentos:** `GET`/`POST /api/v1/guests/{id}/consents` e `/consents/{consentId}/revoke`.
  - **duplicados e restrição:**
    - `GET /api/v1/guests/duplicate-candidates`;
    - `POST /api/v1/guests/{id}/merge`;
    - `POST /api/v1/guests/{id}/restrict` e `/lift-restriction`.
  - **LGPD:** `POST /api/v1/guests/{id}/export` e `/anonymize`.
  - **empresas:**
    - `GET`/`POST /api/v1/companies`, `GET`/`PUT /api/v1/companies/{id}`;
    - `/deactivate` e `/reactivate`.
- **Regras do servidor:**
  - documento cifrado e achado por índice cego;
  - listagens mostram o documento mascarado;
  - revelar exige permissão e fica na trilha;
  - mesclagem repõe as reservas;
  - anonimização é recusada com estadia ativa e exige aprovação;
  - anexos sensíveis são cifrados e servidos por URL assinada de curta duração;
  - **descrição do gênero** (desde o pin `57eb5fac5b8a`, M5 E15 do servidor): `genderDescription` em `GuestProfileRequest` e `GuestResponse`, exigida pela FNRH quando o gênero é `OTHER`. É dado pessoal gravado cifrado, recusado com outro gênero (`400 GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`) e apagado quando o gênero muda sem reenviá-la. Na mesclagem, acompanha o gênero (`GENDER`);
  - o servidor envia à FNRH de forma assíncrona e revalida os hóspedes a cada tentativa: um perfil editado depois do check-in pode deixar a submissão `INVALID` (decisão E08 (d) do servidor). A fila é da M7 E10.

  As fontes são a ARCHITECTURE §8.3, a nota do brain `ZenyHotelManager - Módulo files` e as notas de hóspedes ligadas ao hub do servidor, além dos gates do M3 e do M4 do servidor.
- **Design:** [`docs/superpowers/specs/2026-11-10-front-desktop-design.md`](../superpowers/specs/2026-11-10-front-desktop-design.md), §6.2 (LGPD no cliente) e §6.4 (`ZENY_STORAGE_PUBLIC_ENDPOINT` para as URLs assinadas).

## Escopo

**Incluído:**

- busca e perfil;
- documentos, com revelação;
- dados sensíveis da FNRH;
- anexos e consentimentos;
- duplicados e mesclagem;
- restrição;
- exportação e anonimização;
- empresas;
- seed ampliado;
- roteiro do M5.

**Fora de escopo:**

- configuração da FNRH e a fila de envio (lista, reenvio e encerramento das submissões), na M7 E10;
- anonimização por prazo, que é job do servidor no M10.

## Restrições e invariantes

- **Regras transversais do design §6.2:**
  - nada de JSON ou `code` cru;
  - enum extensível tolerado;
  - uma `Idempotency-Key` por intenção;
  - `ETag` e `If-Match` com `412` tratado;
  - permissões efetivas, com o servidor decidindo.
- **LGPD:**
  - o número do documento revelado aparece só na tela que o pediu, some ao sair dela e nunca vai para log, cache, área de transferência automática nem preferências;
  - nenhum dado de hóspede em log ou em cache de disco;
  - o pacote de exportação é salvo só onde o usuário escolher, com aviso de dado pessoal.
- **Anexos:** o download sensível usa a URL assinada ou o conteúdo pela API, sem cópia persistente fora do destino escolhido pelo usuário.
- **Aprovação de superior:** ação recusada com `APPROVAL_REQUIRED` passa pelo diálogo do M1.
- **Seed:** só dados fictícios.
- **Servidor:** só leitura, e o commit fixado não muda no marco.
- **Idioma:** UI em pt-BR e código em inglês.

## Hipóteses e decisões em aberto

| # | Hipótese ou decisão | Impacto se errada / sem decisão | Etapa afetada |
|---|---|---|---|
| H1 | Os domínios oficiais da FNRH (`GET /api/v1/fnrh/domains/{domain}`) servem para os campos de seleção dos dados sensíveis sem credencial do Serpro, a partir da semente local do servidor | Os campos viram texto livre validado pelo servidor | E01 |
| H2 | As URLs assinadas abrem do app com `ZENY_STORAGE_PUBLIC_ENDPOINT` configurado como no design §6.4 | O anexo não abre; corrigir o `.env` do ambiente, sem mudar o app | E01 |

## Definição global de pronto

- [ ] Comandos oficiais de build, teste e lint verdes.
- [ ] `smokeTest` verde contra o ambiente recriado.
- [ ] Teste de que o documento revelado não é escrito em disco nem em log.
- [ ] Roteiro `docs/demo/roteiro-m5.md`, acumulando os anteriores, executado pelo usuário no Windows.
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

- Toda execução acontece no worktree/branch `roadmap/m5-hospedes-empresas`, criado ou retomado com `superpowers:using-git-worktrees`. Nunca execute na branch principal.
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
4. **Plano:** use `superpowers:writing-plans`. A spec é a seção desta etapa mais o design citado em "Contexto confirmado"; as "Restrições e invariantes" entram como Global Constraints. Salve em `docs/superpowers/plans/AAAA-MM-DD-m5-hospedes-empresas-eNN.md`. Antes de executar, o plano passa pela revisão de arquitetura do `orquestrador` (`revisor-arquitetura`); as regras (rodadas, veredito, seção no plano) vivem só nele. A execução já está definida (abaixo): não pergunte ao usuário o método, apenas siga.
5. **Execução:** use `superpowers:subagent-driven-development` com esse plano, executado pelas souls conforme o ciclo por tarefa, a verificação mecânica dos testes e a revisão final da etapa definidos no `orquestrador` (as regras de encaixe vivem só nele). Não invoque `superpowers:finishing-a-development-branch` ao fim da etapa: ele roda uma vez, no fim do lote.
6. **Fechamento:** publique a captura de atualização do brain, se o bloco "Second Brain" se aplica. Marque `#concluida` (índice e título) só com a revisão final limpa e cada critério de aceite verificado com evidência. Diante de impedimento real, marque `#bloqueada` e preencha "Bloqueios" com causa, evidência, impacto e ação de desbloqueio. Preencha "Evidências e handoff". Rulings do ledger que mudam contrato, escopo, segurança, dados ou algo caro de desfazer vão para "Decisões para o usuário".

**Second Brain**

Vale se o `CLAUDE.md` tiver a seção "Conhecimento do projeto no Second Brain"; senão, ignore este bloco.

- O contexto da sessão precisa trazer a linha `BRAIN: carregado (ZenyHotelManager-DesktopFront)`, injetada pelo hook. Sem ela, pare e relate.
- Na conferência de cada etapa, leia as notas de tema do brain ligadas a ela e as capturas pendentes do projeto que tratem do assunto: são a referência técnica do projeto.
- No fechamento de cada etapa que mudou algo descrito no brain (inclusive o estado do roadmap), o orquestrador manda o `brain-escriba` publicar em `00 Inbox/` do vault a captura `ZenyHotelManager-DesktopFront - atualização m5-hospedes-empresas Eyy`, com o texto novo ou alterado de cada nota afetada e qual nota ele substitui ou complementa; o contrato de agente externo do vault fica com o `brain-escriba`. Nunca edite notas do brain nem registre referência técnica no `CLAUDE.md`.
- Pedido ao servidor (lacuna na API encontrada durante uma etapa): registre `PF-NN` em `docs/pedidos-servidor.md` e mande o `brain-escriba` publicar a captura `ZenyHotelManager - pedido do front PF-NN` seguida do título, conforme o design §8.

**Paradas**

Qualquer outra necessidade de intervenção é decidida pelo orquestrador pela opção recomendada, depois de avaliar prós e contras, com a ruling registrada; só decisão de arquitetura ou system design espera o usuário (regras em "Decisões" do `orquestrador`). O lote para ao concluir uma etapa com **Revisão humana: sim**, diante de decisão de arquitetura sem a qual nada mais anda, nas paradas que ficam com o usuário (push, deploy, publicação, instalação na máquina, apagar trabalho fora da etapa, segredo exposto), quando não resta etapa elegível e ao atingir o limite de etapas do prompt. Etapa que fica `#bloqueada` não para o lote: o orquestrador segue para a próxima elegível que não depende dela.

**Fim do lote**

1. Use `superpowers:finishing-a-development-branch` com a integração já decidida: merge local na `main`, conforme o passo de fechamento do `orquestrador` (branch atualizada e com gate verde, `merge --no-ff`, repositório limpo e pronto para o próximo orquestrador). Push e deploy ficam com o usuário: o relatório termina com o comando `git push origin main` pronto.
2. Liste as "Decisões para o usuário" acumuladas, que já foram tomadas pela opção recomendada, com prós e contras, para revisão. As de arquitetura que esperam o usuário vão como entrevista: perguntas de múltipla escolha, cada uma com uma opção recomendada e justificada (no Claude Code, `AskUserQuestion` com a recomendada em primeiro lugar e o rótulo "(Recomendado)"). Registre as respostas no handoff das etapas e em "Hipóteses e decisões em aberto".

## Prompt de execução

```text
Execute o roadmap docs/roadmaps/roadmap-m5-hospedes-empresas.md deste repositório seguindo a seção "Protocolo de execução" dele, com o plugin superpowers.

Limite desta execução: no máximo 4 etapas concluídas.

1. Confira a pré-condição do protocolo: esta sessão roda como orquestrador e as souls desenvolvedor, testador, revisor-seguranca e revisor-arquitetura estão disponíveis; se não, pare e diga como resolver. Leia CLAUDE.md (e AGENTS.md, se existir), docs/roadmaps/README.md e, no roadmap, o cabeçalho inteiro, o pré-requisito, o protocolo e o índice. Se o CLAUDE.md exigir o Second Brain, confira a linha BRAIN: carregado no contexto e leia o hub. Rode git status e git branch -a.
2. Se alguma etapa estiver #em-andamento, retome-a a partir do ledger do superpowers e do handoff; não recomece tarefas já commitadas.
3. Crie ou retome o worktree/branch roadmap/m5-hospedes-empresas com superpowers:using-git-worktrees. Não trabalhe na branch principal.
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
| E01 | Busca e perfil do hóspede | #pendente | — | Busca, perfil, edição, documentos com revelação, dados sensíveis da FNRH, anexos e consentimentos |
| E02 | Duplicados, mesclagem e restrição | #pendente | E01 | Candidatos a duplicado, mesclagem com aprovação e lista de restrição |
| E03 | Direitos do titular (LGPD) | #pendente | E01 | Exportação dos dados e anonimização com aprovação |
| E04 | Empresas | #pendente | — | Busca, cadastro, edição, desativação e reativação de empresas |
| E05 | Gate do marco M5 | #pendente | E02, E03, E04 | Seed ampliado, roteiro do M5 e definição de pronto verificada |

---

## E01 — Busca e perfil do hóspede — #pendente

**Objetivo:** a área Hóspedes permite buscar por nome ou documento e abrir o perfil, que tem quatro abas:

- **dados:** com edição;
- **documentos:** sempre mascarados, com acréscimo, remoção e o botão "revelar", que mostra o número só naquela tela e informa que o acesso fica registrado;
- **dados sensíveis da FNRH;**
- **anexos e consentimentos.**

O diálogo de hóspede da M2 passa a abrir o perfil completo.

**Por que agora:** é a peça de LGPD mais visível para o gerente, e as demais etapas do marco partem dela.

**Dependências:** nenhuma dentro deste roadmap (exige o gate do M4).

**Escopo:**

- **Perfil:**
  - `GET`/`POST /api/v1/guests`, `GET`/`PUT /api/v1/guests/{id}`;
  - `POST /api/v1/guests/search-by-document`.
- **Documentos:** `POST /api/v1/guests/{id}/documents`, `/remove` e `/reveal`.
- **Dados sensíveis:** `PUT /api/v1/guests/{id}/sensitive-data`.
- **Anexos:**
  - `GET`/`POST /api/v1/guests/{id}/attachments` e `/remove`;
  - `POST /api/v1/files`, `GET /api/v1/files/{id}/download-url` e `/content`.
- **Consentimentos:** `GET`/`POST /api/v1/guests/{id}/consents` e `/revoke`.
- **Seleções da FNRH:** valores de `GET /api/v1/fnrh/domains/{domain}` (H1).
- **Descrição do gênero:** campo `genderDescription` na aba de dados, visível e exigido só com o gênero `OTHER`. Ao trocar o gênero, o campo some e a tela avisa que a descrição será apagada.

**Fora de escopo:** mesclagem (E02) e exportação e anonimização (E03).

**Critérios de aceite:**

- [ ] As listagens e o perfil mostram o documento só mascarado.
- [ ] "Revelar" mostra o número e, ao sair da tela, ele não fica em memória de tela nem em disco. Teste do *state holder* e teste de que nenhum arquivo escrito pelo app contém o número.
- [ ] A revelação aparece na trilha de auditoria do servidor, conferida por `curl` em `GET /api/v1/audit-entries` como administrador.
- [ ] O anexo enviado aparece no perfil e abre pelo download assinado contra `zeny-front`.
- [ ] Os dados sensíveis gravados não aparecem em nenhuma listagem.
- [ ] Gênero `OTHER` com descrição grava e reaparece no perfil. Mandar descrição com outro gênero mostra em pt-BR a recusa `GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER`, sem `code` cru. A descrição não vai para log nem para cache (teste do *state holder*).

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** ARCHITECTURE §8.3 do servidor: o documento inteiro sai só por ação própria, com permissão e trilha.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- URL assinada inacessível (H2). Ajustar o `.env` do ambiente.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E02 — Duplicados, mesclagem e restrição — #pendente

**Objetivo:** o gerente vê os candidatos a duplicado, compara dois perfis lado a lado e mescla um no outro (com aprovação, se exigida), e as reservas passam ao perfil que fica. Também inclui ou retira um hóspede da lista de restrição, com motivo.

**Por que agora:** mostra o cuidado com a qualidade do cadastro.

**Dependências:** E01.

**Escopo:**

- **Duplicados:** `GET /api/v1/guests/duplicate-candidates`.
- **Mesclagem:** `POST /api/v1/guests/{id}/merge`.
- **Restrição:** `POST /api/v1/guests/{id}/restrict` e `/lift-restriction`.

**Fora de escopo:** nenhuma regra de deduplicação no cliente.

**Critérios de aceite:**

- [ ] O seed tem um par de duplicados. Ele aparece nos candidatos e, depois de mesclado, as reservas do perfil absorvido aparecem no perfil que fica.
- [ ] Sem permissão, a mesclagem abre o diálogo de aprovação.
- [ ] O hóspede restrito mostra o aviso no perfil e no diálogo de hóspede da recepção.

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

## E03 — Direitos do titular (LGPD) — #pendente

**Objetivo:** no perfil, o gerente exporta os dados do titular e salva o pacote onde escolher, com aviso de dado pessoal. Também anonimiza o perfil a pedido do titular: com aprovação, recusado com estadia ativa, e com confirmação explícita, porque não tem volta.

**Por que agora:** completa a LGPD no front, que é um argumento forte para o gerente.

**Dependências:** E01.

**Escopo:**

- **Exportar:** `POST /api/v1/guests/{id}/export`, salvando onde o usuário escolher.
- **Anonimizar:** `POST /api/v1/guests/{id}/anonymize`, com a confirmação dupla e o diálogo de aprovação.

**Fora de escopo:** anonimização por prazo (servidor M10).

**Critérios de aceite:**

- [ ] A exportação de um hóspede do seed gera o arquivo no destino escolhido e em nenhum outro lugar (teste do caminho escrito).
- [ ] Anonimizar um hóspede com estadia ativa mostra a mensagem do `code` em pt-BR e não muda nada.
- [ ] Anonimizar um hóspede sem estadia, com aprovação do Gerente: o perfil passa a mostrar os dados anonimizados, e a ação aparece na trilha.

**Validação obrigatória:**

- Comandos oficiais de build, teste e lint → verdes.
- Inspeção manual contra `zeny-front`.

**Revisão humana:** não

**Notas para o plano:** nenhuma.

**Bloqueios:** Nenhum bloqueio conhecido.

**Riscos e mitigação:**

- Anonimizar por engano na demo. Exigir a confirmação dupla e usar no roteiro um hóspede criado para isso.

**Evidências e handoff:**

- Plano: —
- Commits: —
- Revisão final: —
- Critérios verificados: —
- Decisões para o usuário: —
- Notas para a próxima etapa: —

---

## E04 — Empresas — #pendente

**Objetivo:** a área Hóspedes ganha a aba Empresas, com busca, cadastro com CNPJ validado, edição, desativação e reativação. A empresa pode ser escolhida como pagadora na conta (M4).

**Por que agora:** as empresas são pagadoras e clientes, e o gerente espera vê-las.

**Dependências:** nenhuma dentro deste roadmap.

**Escopo:**

- **Busca e cadastro:** `GET`/`POST /api/v1/companies`, `GET`/`PUT /api/v1/companies/{id}`.
- **Ciclo de vida:** `/deactivate` e `/reactivate`.
- **Conta (M4):** atalho para escolher a empresa como pagadora.

**Fora de escopo:** contas a receber (servidor M10).

**Critérios de aceite:**

- [ ] Cadastrar uma empresa com CNPJ inválido mostra o erro no campo, e com CNPJ válido ela aparece na busca.
- [ ] A edição concorrente trata o `412`.
- [ ] Uma empresa desativada não aparece como opção de pagador.

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

## E05 — Gate do marco M5 — #pendente

**Objetivo:** o M5 entregue como build testável. O seed tem duplicados, anexos e um hóspede para anonimizar, o roteiro do M5 existe e a definição de pronto foi verificada num clone limpo e no Windows.

**Por que agora:** fecha o marco e passa a ser a build da demo.

**Dependências:** E02, E03, E04.

**Escopo:**

- seed ampliado;
- `docs/demo/roteiro-m5.md` (a criar), acumulando os roteiros anteriores;
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

**Revisão humana:** sim. Gate do marco e nova build da demo.

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
- 2026-11-13 — análise da rodada 2 do M0 (`claude --agent arquiteto`, servidor `57eb5fac5b8a`). A E01 ganha a descrição do gênero (`genderDescription`). "Fora de escopo" foi corrigido: o servidor já envia à FNRH, e a fila vai para a M7 E10. A M6 E17 do servidor, ainda pendente, vai reduzir o corpo das respostas de `restrict`, `lift-restriction` e `merge` para quem não tem `guests.profiles.read`. A E02 deve reler o contrato na rodada do M0 que vier antes deste marco. Nenhuma tag mudou.
