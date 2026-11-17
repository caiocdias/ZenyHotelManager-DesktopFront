# Pedidos do front ao servidor

Quando faltar algo na API, o front não remenda o servidor (ele é só leitura aqui). Pede pelo Second Brain, e este arquivo controla o que foi pedido. Regras: [design §8](superpowers/specs/2026-11-10-front-desktop-design.md) e [ADR 0005](adr/0005-pedidos-ao-servidor-pelo-brain.md).

## Como pedir

Uma captura nova em `00 Inbox/` do brain, chamada:

```
ZenyHotelManager - pedido do front PF-NN <título>
```

Ela traz:

- o motivo (a tela ou o fluxo que precisa);
- a operação desejada (método, caminho, campos);
- a etapa do front que espera por ela;
- o commit fixado do contrato (`api/server-pin.properties`);
- a prioridade.

O pedido é publicado pelo `brain-escriba` ou pelo orquestrador do front, no fechamento da etapa que encontrou a lacuna.

## Estados

| Estado | Significado |
|---|---|
| `aberto` | pedido feito, sem resposta do servidor |
| `aceito` | o servidor planejou uma etapa que o atende |
| `entregue` | a entrega está no servidor e o M0 a sincronizou (commit de entrega registrado) |
| `recusado` | o servidor decidiu não atender |

## Dependência de etapa

A etapa do front que depende de um pedido fica `#bloqueada`, com a ação de desbloqueio **"PF-NN entregue e sincronizado pelo M0"**. Quando o pedido vira `entregue`, a etapa volta a `#pendente`.

A resposta do servidor é a captura de fechamento da etapa dele, que cita **"atende PF-NN"**. O M0 (análise) do front a lê junto com o contrato e o resultado do `tools/server-drift.sh`.

**Antes de 17/11 (demo) nenhuma etapa depende de pedido.** A demo usa só o que o servidor já tem.

## Controle

| PF-NN | título | estado | captura | etapa do front | commit de entrega |
|---|---|---|---|---|---|
| PF-01 | Setores dos recados legíveis com `frontdesk.traces.manage` | aberto | `ZenyHotelManager - pedido do front PF-01 Setores dos recados legíveis com frontdesk.traces.manage` (2026-11-15) | M2 E05 (não depende; tira a role extra do seed quando entregue) | — |
| PF-02 | Histórico do desfazer check-in com a aprovação do superior | aberto | `ZenyHotelManager - pedido do front PF-02 Histórico do desfazer check-in com a aprovação do superior` (2026-11-15) | M2 E06 (não depende; o roteiro do M2 deixa de prometer "Com aprovação de superior" nessa linha do histórico até a entrega) | — |
| PF-03 | Trocar o titular e os hóspedes de uma reserva antes do check-in | aberto | `ZenyHotelManager - pedido do front PF-03 Trocar o titular e os hóspedes de uma reserva antes do check-in` (2026-11-17) | M3 E03 (não depende; a tela de alteração ganha a troca de titular e de hóspedes quando entregue) | — |
| PF-04 | Prévia da multa de cancelamento da reserva | aberto | `ZenyHotelManager - pedido do front PF-04 Prévia da multa de cancelamento da reserva` (2026-11-17) | M3 E04 (não depende; o diálogo de cancelamento passa a mostrar o valor e o prazo antes de confirmar quando entregue) | — |

## Rodadas de análise do M0

Registro de cada análise do M0: pedidos atendidos, pedidos novos e o que o servidor entregou por conta própria e que o front aguarda.

| Rodada | Data | Servidor analisado | Pedidos atendidos | Pedidos novos | Observações |
|---|---|---|---|---|---|
| 2 | 2026-11-13 | `57eb5fac5b8a` (pin anterior `ca9f9e2f9808`) | nenhum: não havia `PF-NN`, e nenhuma captura do brain cita "atende PF-NN" | nenhum | O servidor entregou sem pedido a FNRH assíncrona, os documentos em PDF, a impressão e o motor do fechamento, distribuídos nos marcos pelo [ADR 0009](adr/0009-documentos-pdf-e-impressao.md) e pela M0 E02. Ficam esperando o próprio roadmap do servidor, sem pedido: as etapas do fechamento (M6 E11 dele), que desbloqueiam a M6 E08 do front, e os rótulos da FNRH na ficha (M6 E15 dele) |
| 3 | 2026-11-15 | `5e06f16d51ff` (pin anterior `57eb5fac5b8a`) | nenhum: PF-01 e PF-02 seguem `aberto`; nenhuma captura do brain e nenhum documento do servidor (`main` e branches `roadmap/m5-recepcao-fnrh` e `roadmap/m6-operacao-fechamento`) cita "atende PF-NN" | nenhum | Chegaram sem pedido os dois itens que a rodada 2 deixou esperando: as etapas do fechamento (M6 E11 dele), que tiram a M6 E08 do front de `#bloqueada`, e os rótulos da FNRH na ficha (M6 E15). Também chegaram o no-show na FNRH (M6 E14) e o corpo mínimo das escritas de restrição e mesclagem (M6 E17), com o primeiro `oneOf` do contrato, tratado no [ADR 0010](adr/0010-resposta-que-varia-com-a-permissao.md). Sincronização: M0 E03 |
