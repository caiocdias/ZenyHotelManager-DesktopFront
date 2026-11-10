# 0001 — Stack: Compose Multiplatform Desktop em Kotlin

- Estado: aceita · 2026-11-10 · decidida pelo usuário
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §4 (F1), §6

## Contexto

O front precisa de um cliente desktop (recepção, gerência, governança) contra o servidor Kotlin/Spring. Ele precisa estar demonstrável em sete dias e ser desenvolvido por agentes. O usuário quer evitar Node. A máquina já tem JDK 25 e Docker. A ARCHITECTURE do servidor (§1) prevê um cliente desktop e, no futuro, TEF por biblioteca local (Q-13).

## Decisão

Usar Compose Multiplatform Desktop em Kotlin sobre JDK 25, com Gradle pelo wrapper e o cliente da API gerado do `api/openapi.yaml`.

## Alternativas descartadas

- **Web renderizada no servidor com HTMX (Ktor).** CRUD rápido e token fora do navegador, mas acrescenta um segundo servidor para operar e complica TEF e impressão locais.
- **Flutter (Dart).** Desktop e web com o mesmo código, mas traz linguagem e SDK novos, e o cliente gerado do OpenAPI é menos maduro.
- **Tauri ou web SPA.** Exigiria Node ou Rust no ciclo de build.

## Consequências

- Mesma linguagem do servidor e nenhuma ferramenta nova além das dependências do Gradle.
- O cliente HTTP da JVM não precisa de CORS, então não exige mudança no servidor.
- O jpackage só gera instalador para o sistema onde roda. A entrega para o Windows é um JAR com as bibliotecas nativas de Linux e Windows (hipótese da M1 E01).
- O Compose não tem tabela de dados pronta, então ela é construída uma vez no módulo `design`.
- Os módulos `sdk` e `core` podem virar Kotlin Multiplatform se o app mobile quiser compartilhá-los.
