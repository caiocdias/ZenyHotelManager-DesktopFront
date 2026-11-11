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
- O `ForbiddenImport` em `config/detekt/detekt.yml` barra `com.zeny.desktop.sdk.client`, `com.zeny.desktop.sdk.util` e `com.zeny.desktop.sdk.util.model` em `app` e `design`. A varredura de nome qualificado em `tests/test_gradle_layout.sh` e `tests/test_etapa_ataque.sh` cobre o uso sem `import`.
- O `demo-seed` pode depender do `sdk` (pelo `core`) na E05, para o acesso amplo de que precisa.
- Trocar para (a) depois custa fachadas no `core` para cada modelo usado pelas features.
- Brecha: o `ForbiddenImport` não barra `io.ktor.client.*` nem `de.quati.ogen.client.*`. Como `sdk/build.gradle.kts` exporta Ktor e ogen por `api(...)` e o `core` reexporta o `sdk` por `api`, `app` e `design` conseguem montar um `HttpClient` e falar com o servidor sem passar pelo `core`. Fechar essa brecha, acrescentando esses pacotes ao `ForbiddenImport`, é decisão do usuário, junto com a escolha entre (a), (b) e (c).
- Um teste estático (`tests/test_gradle_layout.sh`) fixa que o `core` expõe o `sdk` por `api` e que `app` e `design` não o citam.
