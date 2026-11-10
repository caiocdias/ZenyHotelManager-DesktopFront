# 0004 — Endereço do servidor configurável sem rebuild

- Estado: aceita · 2026-11-10 · o usuário pediu "ip no .env"; o campo na tela é chamada do arquiteto
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §6.3

## Contexto

O servidor e o front podem rodar na mesma máquina ou em máquinas diferentes (notebook Linux e Windows), e o endereço muda de uma demo para outra. O usuário quer trocar isso de forma simples e rápida.

## Decisão

A tela de login tem o campo **Servidor**, com a URL base, e um botão "testar" que chama `GET /api/v1/meta/version`. O valor fica salvo nas preferências do usuário do sistema operacional.

Na primeira abertura, o valor inicial segue esta ordem:

1. o valor salvo;
2. a variável de ambiente `ZENY_SERVER_URL`;
3. `ZENY_SERVER_URL` num `.env` ao lado do JAR;
4. `http://localhost:8088`.

## Alternativas descartadas

- **Só o `.env`.** Exigiria abrir arquivo na frente do gerente para trocar de máquina.
- **URL compilada no build.** Exigiria rebuild a cada mudança de rede.
- **Descoberta automática na LAN.** Custo alto e superfície de rede nova.

## Consequências

- Trocar de servidor leva segundos.
- Nada sensível vai para disco por causa disso: os tokens continuam só em memória (spec §6.2).
