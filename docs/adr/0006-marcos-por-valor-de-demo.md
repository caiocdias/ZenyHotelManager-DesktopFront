# 0006 — Marcos por valor de demo, com dados criados pela API

- Estado: aceita · 2026-11-10 · ordem aprovada pelo usuário
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §7

## Contexto

Há sete dias até a demo de 17/11, para um gerente que não é de TI e quer ver o máximo rodando. O servidor tem 303 operações. Seguir as camadas do servidor obrigaria a construir as telas de cadastro (hotel, UHs, tarifas, usuários) antes de qualquer tela de operação.

## Decisão

Os marcos seguem esta ordem:

- **M0**, sincronização, que se repete;
- **M1**, fundação e mapa de UHs ao vivo;
- **M2**, recepção do dia;
- **M3**, reservas;
- **M4**, contas, caixa e check-out;
- **M5**, hóspedes e empresas;
- **M6**, governança e operação;
- **M7**, gestão e cadastros.

A primeira proposta tinha cinco marcos de operação. O usuário pediu marcos menores, com um gate por dia, e por isso "Reservas e recepção" e "Hóspedes, contas e caixa" foram partidos em dois cada um. O check-out foi para o M4 porque exige conta com saldo zero e turno de caixa.

A configuração que a operação exige é criada pela API por um `demo-seed` idempotente, com dados fictícios. Na manhã da demo, o ambiente é recriado para que a data operacional seja "hoje". Cada marco termina numa build apresentável, com gate de revisão humana e roteiro de demo acumulado.

## Alternativas descartadas

- **Seguir as camadas do servidor (cadastros primeiro).** A demo mostraria formulários de configuração em vez da operação do hotel se o tempo acabasse no meio.
- **Um fluxo só, ponta a ponta.** O usuário pediu amplitude.

## Consequências

- Qualquer ponto de corte rende uma demo coerente.
- O `demo-seed` vira peça viva do projeto, ampliada por marco e usada no `smokeTest`.
- Governança (M6) e cadastros (M7) podem não ficar prontos para 17/11.
