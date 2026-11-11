# Task 1: testes RED (testador)

Casos novos:
- `tests/test_gradle_layout.sh`: `detekt.yml` contém `value: 'io.ktor.client.*'` e `value: 'de.quati.ogen.client.*'`; o grep em `app/src` e `design/src` procura também `io.ktor.client` e `de.quati.ogen.client`.
- `tests/test_etapa_ataque.sh`: mesmos dois valores no bloco `ForbiddenImport`; `varre_proibidos` agora inclui os pacotes novos, e três plantados são achados (`import io.ktor.client.HttpClient`, `import io.ktor.client.request.get` subpacote, `de.quati.ogen.client.ktor.HttpClientOgen` qualificado em design/src).

Observação: a função de varredura mora no próprio teste, então os 3 casos plantados já passam. O vermelho é só o `detekt.yml` sem os pacotes (o dev implementa isso).

## `bash tests/run.sh`
24 arquivos, 2 falhas (test_etapa_ataque e test_gradle_layout), 22 PASS (todos os antigos verdes).

## Saída das falhas (idêntica nos dois arquivos)
```
FALHOU: ForbiddenImport barra io.ktor.client.* (cliente HTTP só pelo core)
  não contém: value: 'io.ktor.client.*'
FALHOU: ForbiddenImport barra de.quati.ogen.client.* (cliente HTTP só pelo core)
  não contém: value: 'de.quati.ogen.client.*'
```
