# 0005 — Pedidos do front ao servidor pelo Second Brain

- Estado: aceita · 2026-11-10 · o usuário pediu o canal pelo brain; o desenho é do arquiteto
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §8, §10

## Contexto

Quando faltar algo na API, o front não pode remendar o servidor (anti-objetivo da spec §2). O usuário quer que a comunicação entre os projetos da máquina passe pelo Second Brain. Sessões fora do vault só criam capturas novas em `00 Inbox/` (`99 System/AI/external-agents.md`). O hook `load-brain.py` lista, em cada sessão do servidor, as capturas pendentes cujo texto contém o nome do projeto.

## Decisão

- **Pedido:** uma captura nova `ZenyHotelManager - pedido do front PF-NN <título>`, com:
  - o motivo;
  - a operação desejada;
  - a etapa do front que espera por ela;
  - o commit fixado do contrato;
  - a prioridade.
- **Controle local:** `docs/pedidos-servidor.md`, com estado `aberto`, `aceito`, `entregue` ou `recusado`. A etapa dependente fica `#bloqueada` até o M0 sincronizar a entrega.
- **Resposta:** a captura de fechamento da etapa do servidor cita "atende PF-NN", e o M0 (análise) do front a encontra.
- **Antes da demo de 17/11:** nenhuma etapa depende de pedido.

## Alternativas descartadas

- **Issue no GitHub do servidor.** Fica fora do fluxo das souls, e nenhum hook a injeta na sessão do servidor.
- **Arquivo no repositório do servidor escrito pelo front.** Violaria a regra de só ler o servidor.
- **Avisar o usuário para repassar à mão.** Perde o rastro e o vínculo com etapas.

## Consequências

Três pendências do usuário:

1. Registrar o front no brain.
2. Mudar o hook para casar pelo prefixo do nome do arquivo, `<data> - <Projeto> - ` (decisão do usuário, spec §10). Hoje ele casa por substring, e "ZenyHotelManager" está contido em "ZenyHotelManager-DesktopFront", então as capturas do front poluiriam o contexto do servidor. O nome do projeto do front no brain é `ZenyHotelManager-DesktopFront`.
3. Acrescentar, numa sessão do servidor, a regra de que a sincronização de marco considera os pedidos `PF-NN` e que o fechamento cita "atende PF-NN".

Depois que o curador processa a Inbox, o pedido sai da lista do hook e passa a viver numa nota do projeto do servidor.
