# 0007 — Gerador do `sdk`: ogen com cliente Ktor

- Estado: aceita · 2026-11-11 · decidida pelo construtor na M1 E01 (descoberta da Task 3) e confirmada pelo usuário na parada da etapa
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §9 · relacionada: [ADR 0001](0001-stack-compose-desktop.md)

## Contexto

O design (§9) deixa o gerador do `sdk` para uma descoberta curta, com quatro critérios:

1. compilar o contrato inteiro, sem excluir operação;
2. expor o `ETag` e os headers de resposta;
3. aceitar headers por chamada (`Idempotency-Key`, `If-Match`, `X-Approval-Token`): os declarados sem duplicar e os injetados numa operação que não os declara;
4. gerar `x-extensible-enum` como `String`.

Fatos do contrato (`api/openapi.yaml`, pin `ca9f9e2f`):

- 303 operações, em 17 tags.
- O `ETag` aparece só na descrição de algumas operações, não como header de resposta declarado.
- Headers declarados:
  - `X-Approval-Token` (opcional), por `$ref` em 30 operações;
  - `Idempotency-Key` (obrigatório), por `$ref` em 27;
  - `If-Match`, em 40.
- Nenhuma operação declara os três juntos. `moveReservationRoom` declara `Idempotency-Key` e `If-Match`, e `cancelReservation` declara `Idempotency-Key` e `X-Approval-Token`.
- Os enums:
  - 175 ocorrências de `enum:` fechado, em modelos e parâmetros;
  - os `x-extensible-enum`, todos `type: string`.
- Formatos:
  - dinheiro como `type: string, format: decimal` (159 campos);
  - `date` (167), `date-time` (152), `uuid` e `time-local`;
  - oito `type: number` sem formato (quantidades), que não são dinheiro.

Foram avaliados dois candidatos, um por vez, num rascunho descartável fora do repositório. O rascunho é um build Gradle 9.8.1 com o wrapper do projeto e as mesmas propriedades de memória (`-Xmx2g`, Kotlin in-process, `workers.max=2`, sem paralelismo). Ele lê o contrato pelo caminho absoluto, gera, compila e roda testes com o `MockEngine` do Ktor, sem rede.

- **A:** openapi-generator `7.27.0`, a versão atual no Gradle Plugin Portal. O Maven Central para no `7.14.0` para o artefato do plugin. Gerador `kotlin`, `library=jvm-ktor`, `serializationLibrary=kotlinx_serialization`, `enumUnknownDefaultCase=true`.
- **B:** ogen `0.16.1` (`de.quati.ogen`, a última versão), com `model {}` e `clientKtor {}`. Ele é compilado contra Kotlin 2.4.21, o mesmo do projeto, kotlinx.serialization 1.12.0 e Ktor 3.6.1.

### Critério §9, item a item

Os comandos rodam a partir do diretório do rascunho. Para A, `./gradlew -p a compileKotlin --rerun-tasks --profile` e `./gradlew -p a test`; para B, os mesmos com `-p b`. Os testes do rascunho estão nomeados na evidência.

| Item | A: openapi-generator 7.27.0 (`jvm-ktor` + kotlinx) | B: ogen 0.16.1 (`clientKtor`) | Teste permanente |
|---|---|---|---|
| 1. Contrato inteiro compila | **Não, com a configuração pedida.** `compileKotlin` → `BUILD FAILED`, com três defeitos do gerador. (a) O enum da propriedade `field` (`RoomStatusChangeResponse`) sai com o nome escapado em HTML (`` &#x60;Field&#x60;Serializer ``): 62 erros. (b) `getFolioStatement` sai com `audience: kotlin.String? = Audience.INTERNAL`, e a classe `Audience` não existe. (c) `Map<String, kotlin.Any>` (em `after`, `before`, `payload` e `data`) derruba o plugin do kotlinx (`Serializer for element of type kotlin.Any has not been found`). Compila com `nameMappings` `field→changedField`, `typeMappings` `AnyType→JsonElement`, `importMappings` `JsonElement→kotlinx.serialization.json.JsonElement` e um `object Audience` escrito à mão: `BUILD SUCCESSFUL`, 303 métodos. Além disso, o próprio gerador troca kotlinx por jackson no `jvm-ktor` (`processJVMKtorLibrary`: "in future kotlinx.serialization may be added"). O resultado é híbrido: modelos kotlinx, `ContentNegotiation` vazio e nenhum serializer para `UUID`, `BigDecimal`, `LocalDate` e `OffsetDateTime` (todos `@Contextual`). | **Sim.** `compileKotlin --rerun-tasks` → `BUILD SUCCESSFUL`, sem configuração extra e sem código à mão. 303 métodos de operação: `grep -hE '^  public suspend fun [a-z]' client/*.kt \| grep -vc 'fun prepare'` → `303`. | o build do `sdk` (`:sdk:compileKotlin` compila as 303 operações; `SdkAttackTest.everyOperationInTheContractHasAGeneratedMethod` amarra os 303 `operationId` do contrato a 303 métodos gerados) |
| 2. `ETag` e headers de resposta | Sim. `HttpResponse<T>.headers` é um `Map` comum, com a chave como veio (`headers["ETag"]` → `["\"7\""]`). O `response.headers["etag"]` do Ktor não distingue maiúsculas. Teste `item2_readsEtagFromResponse`: PASSED. | Sim. `HttpResponseTyped<T>.raw.headers[HttpHeaders.ETag]` → `"7"` com as aspas, sem distinção de maiúsculas (`"etag"` também). Teste `item2_readsEtagFromResponse`: PASSED. | `SdkCriteriaTest.readsEtagFromResponse` |
| 3. Headers por chamada | **Só com código à mão.** O método gerado não aceita header extra. Foi preciso escrever um plugin de cliente que lê um elemento do contexto de corrotina e faz `request.headers[nome] = valor`. Com ele, `moveReservationRoom` envia `Idempotency-Key`, `If-Match` e o `X-Approval-Token` injetado uma vez cada, e `getHotel` recebe os dois injetados uma vez cada. Na coexistência com `cancelReservation(xApprovalToken = "gerado")`, o injetado vence: `["injetado"]`. Os três testes `item3_*`: PASSED. | **Sim, nativo.** Toda operação gerada termina em `block: HttpRequestBuilder.() -> Unit`. `moveReservationRoom(..., ifMatch = "\"7\"") { headers["X-Approval-Token"] = … }` envia cada header uma vez. `getHotel { headers["X-Approval-Token"] = …; headers["Idempotency-Key"] = … }` também. Na coexistência, `headers[...] =` no `block` vence (`["injetado"]`), e `header(...)` duplica (`["gerado", "injetado-com-header()"]`). Os três testes `item3_*`: PASSED. | `SdkCriteriaTest.sendsDeclaredIdempotencyKeyIfMatchAndApprovalToken` e `SdkCriteriaTest.injectsHeadersIntoOperationThatDoesNotDeclareThem` |
| 4. `x-extensible-enum` como `String` | Sim. `BookingResponse.channel: kotlin.String`, e `"TELEPATHY"` chega intacto. Teste `item4_*`: PASSED. | Sim. `BookingResponse.channel: kotlin.String`, e `"TELEPATHY"` chega intacto. Teste `item4_*`: PASSED. | `SdkCriteriaTest.deserializesUnknownExtensibleEnumValue` |

Verificações além do §9, feitas no mesmo rascunho:

| Verificação | A | B | Teste permanente |
|---|---|---|---|
| Propriedade JSON desconhecida | Ignorada com `Json { ignoreUnknownKeys = true }`, configurado à mão. | Ignorada com `Json { ignoreUnknownKeys = true }`. Com o `Json` padrão, `JsonDecodingException`. | `SdkCriteriaTest.ignoresUnknownJsonProperty` |
| Dinheiro, data e instante na leitura | `amount: BigDecimal` = `10.50` (escala 2), `LocalDate` 2026-11-10 e `OffsetDateTime` 2026-11-10T13:00Z, só com serializers `@Contextual` escritos à mão. | `amount: String` = `"10.50"`, `businessDate: String` = `"2026-11-10"` e `receivedAt: String` = `"2026-11-10T13:00:00Z"`, sem conversão. Nenhum `Double` em dinheiro. | `SdkCriteriaTest.keepsMoneyDateAndInstantExact` |
| Dinheiro, data e instante na escrita | `{"amount":"10.50","expiresAt":"2026-11-10T13:00:00Z"}` (`registerCardPreAuthorization`) e `{"date":"2026-11-10","roomAmount":"10.50"}`, com os mesmos serializers à mão (`toPlainString` e `ISO_OFFSET_DATE_TIME`). | O mesmo JSON, a partir das strings. | `SdkCriteriaTest.writesMoneyAsDecimalString` |
| Enum fechado com valor novo | `enumUnknownDefaultCase` decodifica para `unknown_default_open_api`, e o valor original se perde. | `body()` lança `JsonConvertException` (Ktor), com a `SerializationException` na causa (`AreaResponse.Kind does not contain element with name 'ROOFTOP'`); prova em `SdkAttackTest.closedEnumWithNewValueFailsTheResponse`. Com `coerceInputValues = true`, o enum opcional vira `Option.Undefined`, mas o obrigatório continua falhando. | — |
| Código à mão necessário | 26 linhas no teste (serializers e plugin de headers), mais 4 de `object Audience`. | Nenhuma. | — |

### Tempo e heap

As medidas usam `--rerun-tasks --profile`, no daemon com `-Xmx2g`. O heap é a soma dos picos por pool do G1, zerados no início do build: um limite superior, que inclui o próprio Gradle. Não houve `OutOfMemoryError` nem daemon perdido.

| | Geração | `compileKotlin` | Arquivos `.kt` gerados | Pico de heap |
|---|---|---|---|---|
| A | 2,9 s (49 s na primeira execução, com download e aquecimento) | 38,0 s | 796 | ≤ 1460 MB (Old Gen 613 MB) |
| B | 3,7 s | 41,8 s (39,4 s numa execução anterior) | 413 | ≤ 1623 MB (Old Gen 733 MB) |

## Decisão

Gerar o `sdk` com o **ogen `0.16.1`** (`de.quati.ogen`): modelos com kotlinx.serialization e cliente Ktor. A não cumpre o item 1 com a configuração pedida e só cumpre o item 3 com código à mão. Por isso a preferência por maturidade não se aplica. B cumpre os quatro itens sem código à mão.

### Configuração que a Task 4 aplica

`gradle/libs.versions.toml`:

```toml
[versions]
ogen = "0.16.1"
quati-kotlin-util = "2.6.1"
ktor = "3.6.1"
kotlinx-serialization = "1.12.0"

[libraries]
ogen-core = { module = "de.quati.ogen:core", version.ref = "ogen" }
ogen-client-ktor = { module = "de.quati.ogen:client-ktor", version.ref = "ogen" }
quati-kotlin-util = { module = "de.quati:kotlin-util", version.ref = "quati-kotlin-util" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

[plugins]
ogen = { id = "de.quati.ogen", version.ref = "ogen" }
```

O Ktor 3.6.1 e o kotlinx.serialization 1.12.0 são as versões de que o ogen 0.16.1 depende. Com versões menores, o Gradle já as sobe (`ktor-client-core:3.5.1 -> 3.6.1`) e mistura módulos do Ktor.

`sdk/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ogen)
}

kotlin {
    jvmToolchain(25)
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
}

ogen {
    utilPackageName("com.zeny.desktop.sdk.util")
    add(packageName = "com.zeny.desktop.sdk") {
        specFile("$rootDir/api/openapi.yaml")
        model { postfix = "" }
        clientKtor {}
    }
}

dependencies {
    api(libs.ogen.core)
    api(libs.ogen.client.ktor)
    api(libs.quati.kotlin.util)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
}
```

- Sem `validator {}`, porque o build não valida o contrato. Sem filtro de operação nem de modelo. Sem `typeMapping`: dinheiro, data e instante ficam `String`.
- O plugin registra `ogenGenerate`, gera em `sdk/build/generated/sources/ogen/src/main/kotlin` e liga a geração ao `compileKotlin` sozinho.
- O plugin declara a saída, mas marca a tarefa com `upToDateWhen { false }` e `notCompatibleWithConfigurationCache`. Por isso `ogenGenerate` roda em todo build (cerca de 4 s), e o `compileKotlin` fica `UP-TO-DATE` quando o contrato não muda. O passo da Task 4 que espera a geração `UP-TO-DATE` vira: `ogenGenerate` executa, e `compileKotlin` fica `UP-TO-DATE`.
- A raiz pode ganhar `alias(libs.plugins.ogen) apply false`, no padrão dos outros plugins, o que o teste do layout permite. O rascunho era de um projeto só: a combinação com o Kotlin declarado na raiz não foi verificada.
- O rascunho compilou com as sete dependências acima. O conjunto mínimo para só compilar não foi isolado. O rascunho também não usou `jvmToolchain(25)`: rodou no JDK 25 do próprio Gradle.

### Forma de uso

- Pacotes gerados:
  - `com.zeny.desktop.sdk.model`: os modelos, sem sufixo, como `HotelResponse` e `AreaResponse.Kind`;
  - `com.zeny.desktop.sdk.client`: uma classe `<Tag>Api` por tag, 17 ao todo (`AuditApi`, `BillingApi`, `ChecklistsApi`, `FNRHApi`, `FilesApi`, `FrontdeskApi`, `GuestsApi`, `HotelApi`, `IamApi` com `login` e `refreshTokens`, `MetaApi`, `NotificationsApi`, `RatesApi`, `ReservationsApi`, `RoomsApi`, `StaffApi`, `StreamApi`, `SyncApi`);
  - `com.zeny.desktop.sdk.util.model`: os serializers internos.
- Uma operação com várias tags fica só na classe da primeira: `moveReservationRoom` está em `FrontdeskApi`.
- Construção, com cliente HTTP e engine injetados:

  ```kotlin
  val json = Json { ignoreUnknownKeys = true }
  val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
  val api = HotelApi(HttpClientOgen.Base(http, baseUrl, {}, json))
  ```

  O mesmo `Json` vai ao `ContentNegotiation` e ao `HttpClientOgen.Base`. O `HttpClient` é um só para todas as classes `*Api`.
- Resposta: `HttpResponseTyped<T>`, com `status`, `body()` e `raw` (o `HttpResponse` do Ktor), e o `ETag` em `raw.headers[HttpHeaders.ETag]`. Cada operação tem também um `prepare<Operação>`, que devolve um `HttpStatement` (útil para SSE e download).
- Header por chamada: no `block` final, sempre com `headers[nome] = valor`, por exemplo `api.updateHotel(ifMatch = etag, hotelRequest = req) { headers["X-Approval-Token"] = token }`. Nunca com `header(nome, valor)`, que acrescenta e duplica.
- Campo opcional: `Option<T?>` (de `de.quati:kotlin-util`), que distingue ausente (`Option.Undefined`) de `null`. O `Json` mantém `encodeDefaults = false` (o padrão), e assim o `Undefined` não vai para o JSON.

## Alternativas descartadas

- **openapi-generator 7.27.0, `jvm-ktor` + kotlinx.serialization (candidato A).** É mais maduro, mas:
  - o próprio gerador não suporta essa combinação;
  - o contrato só compila com três contornos, um deles um `object` à mão para o default de `getFolioStatement`;
  - os serializers de `UUID`, `BigDecimal`, `LocalDate` e `OffsetDateTime` são escritos à mão;
  - o header por chamada exige um plugin à mão;
  - cada classe `*Api` cria o próprio `HttpClient`.

  Se o ogen falhar, A é o plano B, com a configuração e os contornos registrados acima: cerca de 30 linhas à mão.
- **openapi-generator com `jackson`, o suporte oficial do `jvm-ktor`.** Escreve `BigDecimal` como número JSON, não como a string que o contrato pede, e prende o `sdk` à JVM.
- **openapi-generator `library=multiplatform`.** Não foi testado. A documentação diz que ele não decodifica `enumUnknownDefaultCase`, e o dinheiro precisaria de mapeamento.
- **Gerar só os modelos e escrever o cliente à mão no `core`.** É o risco do roadmap, e fica desnecessário porque B cumpre o critério.

## Consequências

- **Interceptação e repetição na E02.** O `core` é dono do único `HttpClient` e instala nele o que a E02 precisar.
  - O refresh no `401` com `code` `UNAUTHENTICATED`, por plugin do Ktor (`HttpSend.intercept` ou `createClientPlugin`), repete a mesma requisição com o novo `Authorization`.
  - O atributo `ogenAuthAttr` de cada requisição diz se a operação exige `bearerAuth`. Em `login` e `refreshTokens` ele é `emptyList()`, e o token não vai.
  - O reenvio após o `403 APPROVAL_REQUIRED` refaz a chamada gerada com o token no `block`. A camada HTTP também pode fazer o reenvio, com `request.headers["X-Approval-Token"] = token` no `HttpRequestBuilder`.
  - **A `Idempotency-Key` da repetição após aprovação foi decidida pelo usuário em 2026-11-11 (decisão D2 do roadmap M1):** nova chave, como diz o contrato (por exemplo, `cancelReservation`: "repita com o `X-Approval-Token` e **outra** `Idempotency-Key`"); o reenvio por falha de rede mantém a chave. Isso substitui o "reaproveitada após a aprovação" do design §6.2.
  - O mecanismo serve às duas regras. O parâmetro `idempotencyKey` gerado, ou `headers["Idempotency-Key"] = …` no `block` ou no `HttpRequestBuilder`, envia a mesma chave ou uma nova, sempre uma vez só.
- **Engine HTTP do `core`.** `ktor-client-java`, o cliente HTTP do JDK: sem dependência nativa e sem biblioteca extra além do Ktor. Alternativas: CIO e OkHttp. O SSE da E06 sobrescreve o `requestTimeout` por requisição.
- **Qual header vence.** O parâmetro gerado (`ifMatch`, `idempotencyKey`, `xApprovalToken`) é aplicado antes do `block`. Um `headers[nome] = valor` no `block` substitui o valor, e o injetado vence. O `core` passa cada header por um caminho só: pelo parâmetro, quando a operação o declara, e pelo `block`, quando não declara. A substituição garante que, mesmo se os dois coexistirem, o header sai uma vez só.
- **Enum fechado com valor novo.** O ogen não tem caso desconhecido: um valor fora do `enum:` fechado faz a resposta inteira falhar: `body()` lança `JsonConvertException` (Ktor), com a `SerializationException` na causa. `JsonConvertException` não é subclasse de `SerializationException`, e por isso o `core` captura a primeira, não a segunda. Prova: `SdkAttackTest.closedEnumWithNewValueFailsTheResponse`.
  - O `Json` do `sdk` não liga `coerceInputValues`, porque ele só cobriria o enum opcional, transformando-o em ausente sem aviso.
  - O `core` traduz a falha numa mensagem amigável de versão incompatível, sem JSON cru.
  - O front roda contra o servidor fixado ([ADR 0002](0002-servidor-fixado-e-contrato-copiado.md)), e valor novo de enum fechado só chega com outro contrato. Ele entra pela sincronização (M0), que regenera o `sdk`.
  - O `x-extensible-enum` é `String` e recebe o tratamento "Outro (VALOR)" do design §6.2.
- **Dinheiro e datas.** Dinheiro, `date` e `date-time` ficam `String` no `sdk`, exatos na leitura e na escrita. O `core` converte dinheiro para `BigDecimal(String)`, sem `Double`. Data de negócio e instante seguem o design §6.2. Os oito `type: number` sem formato (quantidades) saem `Double`.
- **Kotlin Multiplatform (ADR 0001).** A porta fica aberta.
  - O código gerado não tem nenhuma referência `java.*`. Ele usa `kotlin.uuid.Uuid`, `String`, `Option` e kotlinx.serialization, e o ogen suporta KMP.
  - Em A, ao contrário, há 1129 `java.util.UUID`, 227 `java.time.LocalDate`, 167 `java.math.BigDecimal` e 160 `java.time.OffsetDateTime`.
  - O `kotlin.uuid` exige o opt-in `ExperimentalUuidApi`.
- **Maturidade.** O ogen é 0.x, com um mantenedor só. O risco fica contido porque o código gerado não é versionado e a Task 5 trava o critério com testes permanentes. Se uma versão nova quebrar o build, fixa-se a anterior ou cai-se para A.
- **Build.** O `ogenGenerate` roda em todo build e é incompatível com o configuration cache, que o projeto não liga. O plugin traz para o classpath do build o swagger-parser 2.1.48 e o openapi-generator 7.26.0, que ele usa nas recomendações do validador.
