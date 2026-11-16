# 0010 — Resposta que varia com a permissão: o `core` escolhe o ramo do `oneOf`

- Estado: aceita · 2026-11-15 · decidida pelo usuário em entrevista (a opção recomendada), na análise da rodada 3 do M0
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §6.1 e §6.2 · relacionadas: [ADR 0007](0007-gerador-do-sdk.md), [ADR 0008](0008-fronteira-core-sdk.md)

## Contexto

No servidor em `5e06f16d51ff` (M6 E17 dele), três escritas do `guests` devolvem um corpo que depende da permissão de quem chama: `restrictGuest`, `liftGuestRestriction` e `mergeGuests`. No contrato, a resposta `200` passou a ser:

```yaml
oneOf:
- $ref: "#/components/schemas/GuestResponse"
- $ref: "#/components/schemas/GuestWriteSummaryResponse"
```

É o primeiro `oneOf` do contrato. Até `57eb5fac5b8a`, não havia nenhum. O `oasdiff` o classifica como `warning` (`response-body-wrapped-in-one-of-original-preserved`), e o pré-voo dá "aditivo" (código 11).

A regra é do servidor, decidida pelo usuário dele em 2026-11-15 e registrada na `docs/ARCHITECTURE.md` §7 dele, na linha "Resposta que varia com a permissão":

- com `guests.profiles.read`, vem o perfil inteiro (`GuestResponse`); sem ela, vem o resumo `GuestWriteSummaryResponse` `{id, restricted}`, sem dado pessoal. A versão vai no `ETag`;
- o `oneOf` **não tem discriminador**. Os ramos são disjuntos pelo `required`: o completo exige `fullName`, e o reduzido exige `restricted`, que o completo não tem. Nenhum ramo usa `additionalProperties: false`;
- "com o ogen 0.16.1 os clientes gerados recebem essas operações como `JsonElement`, e cabe ao cliente distinguir os ramos pelo `required`";
- a regra vale para as próximas respostas que dependerem da permissão.

O ADR 0008 deixa as features usarem os modelos gerados, mas não o `sdk` cru, e o design §6.2 proíbe JSON cru nas telas. Um `JsonElement` não pode chegar ao `app`.

## Decisão

Quem decodifica é o **`core`**. Numa operação cuja resposta é um `oneOf` sem discriminador, a fachada:

1. lê o corpo como o `sdk` o entrega (o `JsonElement`, se a M0 E03 confirmar esse tipo; ver "Consequências");
2. escolhe o ramo pelas propriedades obrigatórias que só um dos ramos exige, como na regra do servidor. Para `GuestResponse` × `GuestWriteSummaryResponse`, `fullName` presente indica o completo, e `restricted` presente sem `fullName` indica o resumo;
3. decodifica o ramo escolhido com o serializer gerado do modelo, com o mesmo `Json` do `sdk`;
4. devolve às features um resultado tipado com os dois casos (perfil completo ou resumo), cada um com o modelo gerado e o `ETag`;
5. falha fechado: se nenhum ramo ou os dois casarem, ou se o ramo escolhido não decodificar, a fachada lança `ApiError.IncompatibleResponse`, como em qualquer resposta ilegível. Não há palpite nem ramo padrão.

O nome do tipo, a forma do resultado e onde fica a função genérica de escolha ficam com o construtor da primeira etapa que consumir essas operações (M5 E02).

## Alternativas descartadas

- **Descartar o corpo e reler o perfil por `GET /api/v1/guests/{id}`.** Seria mais simples, mas custa uma chamada a mais por escrita e abre uma corrida entre a escrita e a leitura (o `ETag` pode não ser o da escrita). Sem `guests.profiles.read`, a releitura também não serve.
- **Entregar o `JsonElement` às features.** Fere o design §6.2 (nada de JSON cru) e o ADR 0008 (as features não usam o `sdk` cru).
- **Ajustar o `sdk` gerado ou trocar de gerador para ter um tipo-soma.** Fere o ADR 0007: o `sdk` não tem código à mão, e o ogen cumpre os quatro critérios.
- **Pedir ao servidor um discriminador (`PF-NN`).** O servidor já recusou essa saída na ARCHITECTURE §7 dele, porque ela pede uma propriedade nova no esquema completo de todas as operações que usam a regra.
- **Tentar decodificar `GuestResponse` e, se falhar, o resumo.** Funciona hoje, porque o `GuestResponse` exige campos que o resumo não tem, mas depende da ordem e não detecta uma resposta que case com os dois ramos. A escolha explícita pelo `required` segue a regra do servidor e falha fechado.

## Consequências

- O `ETag` vem nos dois ramos, e uma escrita seguinte com `If-Match` funciona igual.
- A tela trata o resumo como sucesso sem perfil para mostrar: atualiza o que dá (por exemplo, o aviso de restrição pelo `restricted`) e não tenta ler campos do perfil. Quem não tem `guests.profiles.read` não abre o perfil de qualquer forma.
- A M0 E03 (sincronização com `5e06f16d51ff`) **caracteriza** o tipo que o ogen gera para as três operações, com um teste no `sdk`, e não muda o `core`. Se o tipo não for `JsonElement`, o passo 1 da decisão se ajusta ao que o ogen gerar, e os passos 2 a 5 continuam valendo. Se o ogen não gerar ou não compilar essas operações, a M0 E03 para: é decisão de arquitetura (ADR 0007, critério 1).
- A M5 E02 é a primeira a usar a regra, nas telas de restrição e mesclagem. Uma próxima operação com resposta que varia com a permissão reusa a mesma função de escolha, informando as propriedades que distinguem os ramos.
- A análise de cada rodada do M0 procura `oneOf` novo no contrato e aponta a etapa do front que vai consumi-lo.
