# 0002 — Servidor fixado num commit e contrato copiado

- Estado: aceita · 2026-11-10 · proposta do arquiteto aprovada pelo usuário
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §5, §7.3

## Contexto

Outro orquestrador desenvolve o servidor ao mesmo tempo. Ele trabalha em `roadmap/<slug>` e faz merge na `main` ao fim de cada lote (`CLAUDE.md` do servidor, "Git"). O contrato `api/openapi.yaml` é gerado e versionado no servidor (ARCHITECTURE D6). Se o front lesse o contrato vivo do servidor, ele quebraria a cada merge.

## Decisão

- O front fica preso a **um commit da `main` do servidor**, registrado em `api/server-pin.properties` (`commit`, `contract_sha256`, `synced_at`).
- O contrato desse commit é copiado byte a byte para `api/openapi.yaml` do front, e o SDK é gerado dessa cópia no build.
- Trocar o commit fixado é só do Marco 0, nunca no meio de um marco.
- Toda execução do orquestrador começa pelo pré-voo `tools/server-drift.sh`, que só lê o servidor (`git -C … rev-parse main`, `git -C … show <commit>:api/openapi.yaml`) e roda o oasdiff:
  - sem mudança, segue;
  - com mudança aditiva, registra e segue;
  - com breaking change em operação usada pelo front, para e recomenda o M0.

## Alternativas descartadas

- **Usar a `main` viva do servidor.** O front quebraria a qualquer merge do outro orquestrador.
- **Submódulo Git do servidor.** Escreveria metadados no repositório do front apontando para o servidor e incentivaria builds a partir da árvore do servidor.
- **Baixar o contrato de `/v3/api-docs` de um servidor rodando.** Dependeria de um servidor no ar e do profile (o Swagger fica fora de produção), e não teria commit de referência.

## Consequências

- A build do front é reproduzível e independente do estado do servidor.
- As funcionalidades novas do servidor só chegam ao front por um M0 explícito.
- O oasdiff do pré-voo precisa de Docker, como o script do servidor.
