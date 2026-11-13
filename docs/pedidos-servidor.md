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

## Rodadas de análise do M0

Registro de cada análise do M0: pedidos atendidos, pedidos novos e o que o servidor entregou por conta própria e que o front aguarda.

| Rodada | Data | Servidor analisado | Pedidos atendidos | Pedidos novos | Observações |
|---|---|---|---|---|---|
| 2 | 2026-11-13 | `57eb5fac5b8a` (pin anterior `ca9f9e2f9808`) | nenhum: não havia `PF-NN`, e nenhuma captura do brain cita "atende PF-NN" | nenhum | O servidor entregou sem pedido a FNRH assíncrona, os documentos em PDF, a impressão e o motor do fechamento, distribuídos nos marcos pelo [ADR 0009](adr/0009-documentos-pdf-e-impressao.md) e pela M0 E02. Ficam esperando o próprio roadmap do servidor, sem pedido: as etapas do fechamento (M6 E11 dele), que desbloqueiam a M6 E08 do front, e os rótulos da FNRH na ficha (M6 E15 dele) |
