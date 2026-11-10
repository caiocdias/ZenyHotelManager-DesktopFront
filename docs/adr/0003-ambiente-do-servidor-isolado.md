# 0003 — Ambiente do servidor isolado para o front

- Estado: aceita · 2026-11-10 · proposta do arquiteto aprovada pelo usuário
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §5, §6.4

## Contexto

O `docker/compose.yaml` do servidor fixa:

- `name: zeny`;
- `image: zeny-hotel-manager:dev`;
- o proxy em `ZENY_HTTP_PORT`, com padrão 80;
- o endereço `ZENY_HTTP_ADDRESS`, com padrão `127.0.0.1`.

Se o front subisse esse compose como está, um rebuild ou um `down -v` do orquestrador do servidor derrubaria a demo. Além disso, o notebook tem pouca memória livre, enquanto há uma máquina Windows com 64 GB e Docker Desktop.

## Decisão

- Servidor e app da demo rodam juntos no Windows (decisão do usuário). A imagem do servidor é construída lá (chamada do arquiteto), porque o `Dockerfile` do servidor roda o Gradle dentro do Docker e o notebook não tem memória para isso.
- **`tools/server-env/package.sh`** (no notebook) monta o pacote para o Windows:
  - o `git archive` do commit fixado (ADR 0002);
  - o override do front, com imagem `zeny-hotel-manager:<commit curto>`, porta 8088 e `ZENY_HTTP_ADDRESS=0.0.0.0` como padrão no pacote. No notebook, o mesmo override tem padrão `127.0.0.1`, e o `.env` pode sobrescrever nos dois (confirmado pelo usuário em 2026-11-10, M0 E01, D2). O override mantém o `build:` do compose do servidor, para a imagem ser construída no Windows (confirmado pelo usuário em 2026-11-10, M0 E01, D1);
  - um `.env.example`;
  - os JARs do app e do `demo-seed`;
  - um `LEIA-ME.md` em PowerShell: `build`, `up`, recriar a demo e abrir o app.
- **`save-images.sh`** grava as quatro imagens com `docker save`, para a demo não depender de internet.
- **`tools/server-env/up.sh` e `down.sh`** usam o mesmo projeto `-p zeny-front` no notebook, quando houver memória. O `down.sh` derruba só esse projeto.
- **Acesso de outra máquina** (mobile, notebook):
  - `ZENY_STORAGE_PUBLIC_ENDPOINT=http://<IP do Windows>:8088`;
  - a porta 8088 liberada no firewall.
- **Dono do ambiente:** o servidor da demo é do front desktop. O mobile aponta para ele, mas não roda seed nem `down -v`.
- **Leitura apenas:** as sessões do front só leem o repositório do servidor.

## Alternativas descartadas

- **Usar o compose do servidor como está.** Haveria conflito de nome, porta, imagem e volumes com o orquestrador do servidor.
- **`git worktree add` no repositório do servidor.** Escreveria metadados no `.git` do servidor.
- **Registro de imagens (ghcr).** Exigiria push, que é do usuário, e credenciais. Fica como opção futura.

## Consequências

- A demo sobrevive a qualquer operação do servidor.
- O primeiro build no Windows precisa de internet: o Gradle baixa as dependências dentro do Docker.
- O pacote com as imagens salvas é grande.
- O `.env` com as chaves é gerado localmente e nunca é versionado.
- Os scripts são em bash, e o Windows usa só os comandos do `LEIA-ME.md`.
