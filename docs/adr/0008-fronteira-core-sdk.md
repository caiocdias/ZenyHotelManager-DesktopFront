# 0008 — Fronteira entre `core` e `sdk`

- Estado: proposta — decidida pelo orquestrador pela opção recomendada; aguarda confirmação do usuário na parada da M1 E01 · 2026-11-11
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §6.1 · relacionada: [ADR 0007](0007-gerador-do-sdk.md)

## Contexto

O roadmap e o design (§6.1) proíbem as features de **chamar** o `sdk`: elas falam com o servidor só pelo `core`. Ao mesmo tempo, o `demo-seed` e o `smokeTest` (E05) precisam de acesso amplo à API, e as features precisam dos tipos dos modelos para exibir os dados.

Falta decidir como o `core` expõe o `sdk` aos módulos que dependem dele.

## Alternativas

- **(a)** `core` com `implementation(project(":sdk"))` e mapeamento de todo modelo gerado para um tipo do `core`.
- **(b)** `core` com `api(project(":sdk"))`, e uma regra de lint `ForbiddenImport` que proíbe, em `app` e `design`, o import das classes de operação do `sdk` (a escolhida).
- **(c)** `sdk` dividido em dois módulos: modelos (`api`) e cliente (`implementation`).

## Decisão

Opção (b). O `core` declara `api(project(":sdk"))`; `app` e `design` não citam `project(":sdk")` no build e chegam aos modelos pelo `core`. A proibição de chamar o `sdk` é verificada por lint, não pelo grafo de módulos.

## Consequências

- As features usam os modelos gerados diretamente, sem camada de mapeamento.
- O `ForbiddenImport` das classes de operação em `app` e `design` entra na Task 9; até lá, a regra vale por convenção.
- O `demo-seed` pode depender do `sdk` (pelo `core`) na E05, para o acesso amplo de que precisa.
- Trocar para (a) depois custa fachadas no `core` para cada modelo usado pelas features.
- Um teste estático (`tests/test_gradle_layout.sh`) fixa que o `core` expõe o `sdk` por `api` e que `app` e `design` não o citam.
