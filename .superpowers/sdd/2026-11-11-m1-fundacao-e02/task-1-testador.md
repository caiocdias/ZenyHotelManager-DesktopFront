# Task 1 testador (beba7fb..HEAD): VERDE

## Testes novos
- `core/src/test/kotlin/com/zeny/desktop/core/TestIsolationTest.kt` (4 testes, rodados: tests=4 failures=0):
  - `javaIoTmpdirPointsInsideCoreBuildTmp`, `prefsUserRootPointsInsideCoreBuildTmp`: as propriedades apontam para dentro de `core/build/tmp/`.
  - `tempFilesLandInsideCoreBuildTmp`: `File.createTempFile` cai lá.
  - `preferencesUserRootWritesUnderCoreBuildTmp`: `Preferences.userRoot()` grava e o arquivo existe sob a raiz isolada.
- `tests/test_core_deps_ataque.sh`: `ktor-client-java` com `version.ref = ktor` e travado nessa versão (compile e runtime); `ktor-client-mock` só em teste; `kotlinx-coroutines-test` 1.11.0 igual ao `coroutines-core` resolvido no core e só em teste; sem engine CIO/OkHttp; detekt.yml com os dois pacotes.

## Cerca do detekt, prova real
Plantei `app/src/main/kotlin/zz/Planted.kt` (apagado depois) e rodei `./gradlew :app:detekt`:
```
Planted.kt:3:1 The import `io.ktor.client.request.get` has been forbidden: Cliente HTTP só pelo core (ADR 0008, decisão D1). [ForbiddenImport]
Planted.kt:4:1 The import `de.quati.ogen.client.ktor.HttpClientOgen` has been forbidden: ... [ForbiddenImport]
```
Subpacotes são pegos. Nome qualificado sem import NÃO é pego pelo detekt (só `import`); essa lacuna é coberta pela varredura de shell (tests/test_gradle_layout.sh e test_etapa_ataque.sh), que acha o nome qualificado.

## Suíte
- `bash tests/run.sh`: `Resumo: 25 arquivos, 0 falhas, 0 com casos pulados`.
- `./gradlew ktlintCheck :core:test`: BUILD SUCCESSFUL.

Nenhum defeito de produção encontrado; nenhum teste existente alterado.
