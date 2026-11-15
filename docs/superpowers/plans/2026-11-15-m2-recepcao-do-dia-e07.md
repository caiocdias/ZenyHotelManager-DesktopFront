# M2 E07 — Ficha de registro e visualizador de PDF — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o recepcionista abre a ficha de registro de uma reserva confirmada, hospedada ou encerrada dentro do app (no detalhe e logo após o check-in), confere as páginas na tela com navegação e zoom, salva o arquivo onde escolher ou imprime na impressora do computador, sem que o PDF toque o disco por outro caminho.

**Architecture:** no `core`, uma `DocumentsFacade` baixa o PDF por um caminho binário novo do `ApiExecutor` e devolve `PdfDocument(bytes, fileName?)` ou o `ApiError` mapeado. No `design`, um componente reaproveitável (M3 E03 e M4 E02 vão usá-lo): `PdfBoxSetup` configura o PDFBox para não tocar o disco, `PdfDocumentState` renderiza as páginas em memória com o documento confinado a uma thread, `PdfActions` faz "Salvar como…" e "Imprimir" atrás de portas testáveis, e o composable `PdfViewer` junta tudo. No `app`, uma porta `RegistrationCards`, o estado `RegistrationCardState` e a ligação no detalhe e no fim do check-in. O `smokeTest` baixa a ficha de um hospedado, e um teste carrega o `zeny-desktop.jar` para provar que a ficha renderiza a partir dele.

**Tech Stack:** o da E01–E05, mais o Apache PDFBox 3.0.x (dependência nova: produção só no `design`; nos testes do `app`, só para extrair texto e carregar o JAR).

**Spec:**
- a seção "E07 — Ficha de registro e visualizador de PDF" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md`, com "Contexto confirmado", "Restrições e invariantes", as hipóteses H4 e H5 e os handoffs da E02 e da E05;
- o [ADR 0009](../../adr/0009-documentos-pdf-e-impressao.md) (visualizador em memória, impressão local, "Salvar como…" explícito, nenhum arquivo temporário) e o ADR 0008 (fronteira `core`/`sdk`);
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.2.

**Fatos do contrato e do servidor fixado (`57eb5fac5b8a`, só leitura):**
- `getRegistrationCardPdf` (`GET /api/v1/reservations/{id}/registration-card/pdf`, `FrontdeskApi`): `200 application/pdf` (binário); erros em `application/problem+json`: `404 RESERVATION_NOT_FOUND`, `409 REGISTRATION_CARD_UNAVAILABLE` (fora de `CONFIRMED`, `IN_HOUSE`, `CHECKED_OUT`), `403` sem `frontdesk.stays.checkin`. Sem `Idempotency-Key`. Não é guardado em cache e não deixa trilha.
- O cliente gerado tipa o corpo como `HttpResponseTyped<Any>`; o `prepareGetRegistrationCardPdf(id, acceptContentType, block)` devolve o `HttpStatement` cru, que o `core` executa e lê como bytes.
- O servidor manda `Content-Disposition: attachment; filename="ficha-<código>.pdf"` (`RegistrationCardController.kt`); o contrato só diz o nome na descrição.
- O PDF do servidor usa Helvetica não embutida (`templates/printing/layout.xhtml`) e pode ter o logo do hotel como imagem: o mapeamento de fontes roda em toda ficha, e uma imagem JPEG passa pelo `ImageIO`.
- O conteúdo da ficha é do servidor (nome, nacionalidade, nascimento, endereço, documentos mascarados e os códigos FNRH). O front não o corrige.
- O Recepcionista do seed tem `frontdesk.stays.checkin` (H5).

## Global Constraints

- **Disco (ADR 0009):** o PDF fica só em memória. Proibidos `File.createTempFile`, `Files.createTemp*`, `java.awt.Desktop.open`/`browse`, `deleteOnExit`, a propriedade `pdfbox.fontcache` e qualquer cache em disco. O `PdfBoxSetup.install()` roda antes de qualquer classe de fonte do PDFBox (inclusive nos testes que geram PDFs) e:
  - instala um `FontMapper` próprio que implementa `getTrueTypeFont`, `getFontBoxFont` e `getCIDFont` com as fontes que o PDFBox traz no JAR, sem nunca instanciar o `FontMapperImpl` nem o `FileSystemFontProvider` (que varrem as fontes do sistema e gravam `.pdfbox.cache`);
  - chama `ImageIO.setUseCache(false)` (o decodificador de JPEG gravaria `imageio*.tmp`);
  - baixa o log de `org.apache.pdfbox` para não ecoar texto do documento (avisos de glifo);
  - é idempotente.
  A carga usa cache de stream só em memória (`IOUtils.createMemoryOnlyStreamCache()` ou o equivalente da versão). O disco só recebe o arquivo por "Salvar como…", no destino que o usuário escolheu.
- **Regra de parada (H4):** se a regra de disco não fechar com o PDFBox na versão escolhida, o executor para e reporta BLOCKED; não contorna com `pdfbox.fontcache` nem com arquivo temporário. A decisão volta ao usuário (ADR 0009).
- **Nome do arquivo:** o `core` devolve o nome do servidor saneado ou `null` (cabeçalho ausente ou inválido); o `app` passa o fallback `ficha-<código>.pdf`; o `design` saneia de novo na fronteira com o disco (`suggestedPdfName(raw, fallback)`: só o último segmento, sem `/`, `\`, `..` nem caractere de controle, com `.pdf`).
- **"Salvar como…":** grava exatamente os bytes recebidos; cancelar não grava nada; o diálogo nativo cuida de sobrescrever (o roteiro da E06 confere); falha de escrita aparece em pt-BR ("Não foi possível salvar o arquivo.") sem o caminho.
- **"Imprimir":** o diálogo de impressão do sistema (`PrinterJob.printDialog()`), alimentado por um `PDDocument` próprio da impressão, carregado em memória dos mesmos bytes e fechado ao fim; cancelar não deixa efeito; falha aparece em pt-BR ("Não foi possível imprimir."). Os diálogos rodam na thread do AWT (EDT); a escrita e o `print()` rodam fora da thread de UI.
- **Concorrência:** todo acesso ao `PDDocument` do visualizador (abrir, renderizar, fechar) passa por um único executor confinado (`limitedParallelism(1)` ou equivalente); `close()` roda nele, depois da renderização em voo; resultado de renderização que chega depois de `close()` é descartado.
- **Erros:** `409 REGISTRATION_CARD_UNAVAILABLE` → "A ficha só existe para reservas confirmadas, hospedadas ou encerradas."; `404 RESERVATION_NOT_FOUND` → "A reserva não foi encontrada. Atualize o painel."; `403` → o texto comum de permissão; rede → o texto comum de rede; resposta que não é PDF (tipo que não casa com `application/pdf`, bytes que não começam com `%PDF-`, corpo vazio ou acima de 20 MB) → `ApiError.IncompatibleResponse`; PDF que o PDFBox não abre → "Não foi possível abrir a ficha." Nunca `code` cru, nunca o visualizador vazio.
- **Acesso:** a ação "Ficha de registro" aparece só com `frontdesk.stays.checkin` e com a reserva em `CONFIRMED`, `IN_HOUSE` ou `CHECKED_OUT`; o servidor decide (`403`/`409` tratados).
- **Dados pessoais:** os bytes, as páginas e o texto nunca vão a log, `toString` ou mensagem de exceção (`toString` mostra só tamanho e número de páginas). O documento é fechado e as imagens soltas quando o visualizador fecha, quando a área sai da composição e quando a sessão muda.
- **Memória:** renderização fora da thread de UI, uma página por vez, com a página visível em cache e no máximo a vizinha; zoom de 50% a 200% em passos de 25% (padrão 100%, 96 dpi × zoom).
- **Fronteira (ADR 0008):** o `app` (produção) usa só `core` e `design`; o PDFBox é `implementation` só do `design`, e a API pública do `design` não expõe tipos do PDFBox; nos testes do `app` ele entra como `testImplementation`. Uma checagem estática em `tests/` garante que `org.apache.pdfbox` não está no classpath do `core` nem no `compileClasspath` de produção do `app`.
- **Dependência nova:** a versão do PDFBox vai em `gradle/libs.versions.toml`, e os lockfiles são regravados no mesmo commit (`./gradlew resolveAndLockAll --write-locks`, sem lockfile pendente). Use a última 3.0.x estável do Maven Central e registre no relatório: versão, tamanho do `zeny-desktop.jar` antes (61.435.076 bytes) e depois, tempo do `./gradlew :app:desktopJar` e as fontes que o JAR do PDFBox traz.
- **Testes:**
  - não dormem; verificação ao vivo só com o opt-in próprio `ZENY_LIVE_CARD` e o arquivo 600; com o opt-in e sem credencial, falha;
  - o teste de "nenhum arquivo criado" roda numa tarefa de teste própria do `design`, em JVM própria, com `java.io.tmpdir` e `user.home` apontados para pastas novas desde a partida; a tarefa `test` do `design` ganha o mesmo isolamento de tmp que o `app` já tem;
  - testes existentes só mudam onde a tarefa manda.
- **Gate e commits:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes antes de cada commit; nunca `./gradlew --stop`; UI em pt-BR, código em inglês; commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **O PDFBox gravando em disco** (cache de fontes em `~/.pdfbox.cache`, `imageio*.tmp`, cache de stream): nada é criado, nem de passagem, em `java.io.tmpdir` e `user.home`. Teste `pdfLifecycleCreatesNoFile` na tarefa isolada (Task 3).
2. **Corrida entre renderizar, fechar e imprimir:** fechar durante a renderização ou a impressão não quebra nada; imprimir enquanto o usuário navega é seguro. Testes `closeDuringRenderDiscardsResult` (Task 4), `printWhileNavigatingIsSafe` e `closeDuringPrintDoesNotBreakPrint` (Task 5).
3. **Nome sugerido malicioso ou estranho** vindo do servidor: o nome sai saneado em duas camadas. Testes `fileNameIsSanitizedOrNull` (Task 1) e `suggestedNameSanitizesAndFallsBack` (Task 5).
4. **Resposta que não é PDF** (HTML de proxy, JSON com `200`, corpo vazio, corpo gigante): `IncompatibleResponse`, nunca o visualizador vazio. Teste `nonPdfBodyIsIncompatible` (Task 1).
5. **A ficha aberta quando o usuário sai da área, troca de sessão ou fecha o detalhe:** o documento é fechado. Testes `leavingAreaClosesCard`, `sessionChangeClosesCard` e `closingDetailClosesCard` (Task 8).

---

### Task 1: Download binário da ficha no `core`

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (entrada externa; contrato)

**Files:**
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/http/ApiExecutor.kt` (caminho binário)
- Create: `core/src/main/kotlin/com/zeny/desktop/core/facade/DocumentsFacade.kt`
- Modify: `core/src/main/kotlin/com/zeny/desktop/core/ZenyCore.kt` (`val documents: DocumentsFacade`)
- Test: `core/src/test/kotlin/com/zeny/desktop/core/facade/DocumentsFacadeTest.kt`

**Interfaces:**
- Produces:
  - em `ApiExecutor`: `suspend fun executeBytes(maxBytes: Long, call: suspend (CallOptions) -> HttpStatement): BinaryResponse`, com `class BinaryResponse(val bytes: ByteArray, val contentType: String?, val contentDisposition: String?)`; mesmos erros tipados do `execute` (problema RFC 9457 pelo `ProblemMapper`, `401` → `SessionEnded`, rede → `Network`), sem `Intent`; corpo acima de `maxBytes` → `IncompatibleResponse` sem ler o resto;
  - `class PdfDocument(val bytes: ByteArray, val fileName: String?)`, com `equals`/`hashCode` por conteúdo e `toString` = `PdfDocument(<n> bytes)`;
  - `class DocumentsFacade internal constructor(frontdesk: FrontdeskApi, executor: ApiExecutor)` com `suspend fun registrationCard(reservationId: String): PdfDocument`:
    - id inválido → `IllegalArgumentException` antes da rede, sem o valor;
    - `Accept: application/pdf`; teto de 20 MB;
    - tipo que não casa com `application/pdf` (comparado com `ContentType.match`, ignorando parâmetros) ou bytes que não começam com `%PDF-` → `ApiError.IncompatibleResponse`, sem os bytes na causa;
    - `fileName` do `Content-Disposition` (`filename` e `filename*`), saneado (só o último segmento, sem `/`, `\`, `..` nem controle, termina em `.pdf`); ausente ou inválido → `null`.

- [ ] **Step 1: Testes que falham** (`MockEngine`): `returnsBytesAndServerFileName`, `sendsAcceptPdfWithoutIdempotencyKey`, `unavailableIsProblem` (`409 REGISTRATION_CARD_UNAVAILABLE`), `notFoundIsProblem`, `sessionEndedOn401`, `networkFailureIsTyped`, `nonPdfBodyIsIncompatible` (HTML com `200`, JSON com `200`, corpo vazio, corpo acima do teto), `contentTypeWithParametersIsAccepted` (`application/pdf; charset=binary`), `fileNameIsSanitizedOrNull` (`../../x.pdf` → `x.pdf`; `a/b` → `b.pdf`; sem cabeçalho → `null`; `filename*=UTF-8''ficha-R%C3%A9.pdf`), `invalidIdFailsBeforeNetwork`, `toStringHidesBytes`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*DocumentsFacadeTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o download da ficha de registro ao core`).

---

### Task 2: Porta da ficha e o `smokeTest`

**Estimativa:** 30 min · **Depende de:** Task 1 · **Risco:** alto (o `smokeTest` confere o contrato contra o servidor)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RegistrationCards.kt` (`fun interface RegistrationCards { suspend fun registrationCard(reservationId: String): PdfDocument }`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (`fun registrationCards(): RegistrationCards? = null`; o `CoreConnection` repassa a `core.documents`)
- Modify: `demo-seed/src/smokeTest/kotlin/com/zeny/desktop/seed/smoke/SmokeTest.kt` (baixa a ficha de um hospedado do seed, logado como Recepcionista, e confere que os bytes começam com `%PDF-`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardsSourceTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `RegistrationCards` e `ServerConnection.registrationCards()`.

- [ ] **Step 1:** teste que falha (`adapterPassesIdAndReturnsDocument`); o `smokeTest` fica fora de `check`/`build`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente; compile o `smokeTest` (`./gradlew :demo-seed:compileSmokeTestKotlin --max-workers=2`).
- [ ] **Step 4:** PASS; gate verde. O orquestrador roda o `smokeTest` contra o ambiente.
- [ ] **Step 5: Commit** (`Acrescenta a porta da ficha de registro e a confere no teste de fumaça`).

---

### Task 3: PDFBox sem disco no `design` (H4)

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (dependência nova; irreversível primeiro: valida a H4)

**Files:**
- Modify: `gradle/libs.versions.toml`, `design/build.gradle.kts` e os lockfiles. No `design/build.gradle.kts`: o PDFBox como `implementation`; o isolamento de tmp da tarefa `test` (como no `app`); e uma tarefa nova `noDiskTest` do tipo `Test` **sobre o próprio `sourceSets.test`** (mesmo `testClassesDirs` e `classpath`, sem conjunto de fontes novo, para não nascer configuração nova nos lockfiles), filtrada pelo pacote `com.zeny.desktop.design.pdf.nodisk` (que a tarefa `test` exclui), ligada ao `check`, em JVM própria com `-Djava.io.tmpdir` e `-Duser.home` em pastas novas do build criadas antes da partida
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfBoxSetup.kt`
- Create: `tests/test_pdfbox_scope_ataque.sh` (no molde de `tests/test_design_testdeps_ataque.sh`, pelos lockfiles e pelos `build.gradle.kts`): o PDFBox ausente do lockfile do `core`; ausente do `compileClasspath` do `app`; presente no `runtimeClasspath` do `app` (vem do `design`, e o JAR precisa dele) e no `compileClasspath` do `design`; no `app/build.gradle.kts`, ausente ou só como `testImplementation` (a Task 9 o acrescenta), recusando qualquer outra declaração
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/nodisk/PdfNoDiskTest.kt` e `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfBoxSetupTest.kt`

**Interfaces:**
- Produces (pacote `com.zeny.desktop.design.pdf`): `object PdfBoxSetup { fun install() }` (da Global Constraint "Disco"); um utilitário de teste do `design` que gera, depois do `install()`, um PDF de 3 páginas em memória com Helvetica, Helvetica-Bold e uma imagem JPEG (reaproveitado pelas Tasks 4–5).

- [ ] **Step 1: Testes que falham:**
  - `PdfBoxSetupTest`: `installIsIdempotent`, `fontMapperIsOurs` (`FontMappers.instance()` é o mapper próprio), `imageIoCacheIsOff`, `standardFontsResolveWithoutSystemScan` (Helvetica, Helvetica-Bold, Times-Roman e Courier resolvem para fontes do JAR);
  - `PdfNoDiskTest.pdfLifecycleCreatesNoFile`, na tarefa isolada: aquecimento documentado do que não é PDFBox (Skiko e fontes do AWT) antes da foto; foto recursiva de `java.io.tmpdir` e `user.home`; gerar o PDF (depois do `install()`), carregá-lo só em memória, renderizar as 3 páginas em 2 zooms e fechar; foto de novo, igual à primeira; um `WatchService` nas duas raízes durante o ciclo não vê nenhum evento de criação (sincronização sem dormir: depois da segunda foto, criar um arquivo sentinela em cada raiz e esperar com `poll(timeout)` até o evento dele chegar; só então afirmar que não houve outro evento).
- [ ] **Step 2:** `./gradlew :design:test :design:noDiskTest --max-workers=2` → FAIL.
- [ ] **Step 3:** acrescente o PDFBox, a tarefa isolada, o `PdfBoxSetup` e a checagem estática. Se a regra de disco não fechar, pare (BLOCKED, regra de parada da H4).
- [ ] **Step 4:** PASS; gate verde; `./gradlew :app:desktopJar --max-workers=2` e registre no relatório a versão, o tamanho do JAR antes/depois, o tempo e as fontes do JAR do PDFBox.
- [ ] **Step 5: Commit** (`Acrescenta o PDFBox ao design sem gravar em disco`).

---

### Task 4: Documento PDF em memória no `design`

**Estimativa:** 40 min · **Depende de:** Task 3 · **Risco:** alto (concorrência; memória)

**Files:**
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfDocumentState.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfDocumentStateTest.kt`

**Interfaces:**
- Consumes: Task 3.
- Produces: `class PdfDocumentState(bytes: ByteArray, scope: CoroutineScope, documentDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1))`:
  - `open(): Boolean` (chama `PdfBoxSetup.install()`; falso com PDF inválido, com `error` = "Não foi possível abrir a ficha."), `pageCount: Int`, `page: Int` (0-based), `zoom: Int` (percentual), `image: ImageBitmap?`, `rendering: Boolean`, `error: String?`;
  - `next()`, `previous()`, `zoomIn()`, `zoomOut()` (50..200, passo 25), `close()` (idempotente; roda no executor confinado depois da renderização em voo; fecha o `PDDocument` e solta as imagens);
  - todo acesso ao `PDDocument` passa pelo `documentDispatcher`;
  - `toString` = `PdfDocumentState(pages=<n>, page=<i>)`.

- [ ] **Step 1: Testes que falham:** `opensAndRendersFirstPage`, `navigatesWithinBounds`, `zoomStepsAndLimits`, `invalidBytesShowError` (aleatórios e PDF truncado), `closeDuringRenderDiscardsResult`, `closeIsIdempotent`, `rapidNavigationRendersLastRequestedPage`, `toStringHasNoContent`.
- [ ] **Step 2:** `./gradlew :design:test --tests '*PdfDocumentStateTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o documento PDF em memória ao design`).

---

### Task 5: "Salvar como…" e "Imprimir" no `design`

**Estimativa:** 45 min · **Depende de:** Tasks 3 e 4 · **Risco:** alto (escreve em disco por ação do usuário; impressão)

**Files:**
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfActions.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfActionsTest.kt`

**Interfaces:**
- Consumes: Task 3 (o `install()` e o utilitário de PDF de teste) e Task 4 (`PdfDocumentState`, no teste de concorrência).
- Produces:
  - `fun interface SaveLocationChooser { fun choose(suggestedName: String): java.nio.file.Path? }` (nulo = cancelado) e a real `FileDialogSaveChooser(parent: java.awt.Frame?)` (`java.awt.FileDialog` em modo `SAVE`); o mapeamento do resultado do diálogo é a função pura `saveDialogResult(directory: String?, file: String?): Path?` (sem arquivo → `null`);
  - `fun interface PrintDialog { fun print(pageable: java.awt.print.Pageable): PrintOutcome }`, `enum class PrintOutcome { PRINTED, CANCELLED }` e a real `SystemPrintDialog(jobFactory: () -> PrinterJob = PrinterJob::getPrinterJob)` (`setPageable`; o `printDialog()` roda na EDT por `invokeAndWait` (chamado já na EDT, roda direto; teste `printDialogFromEdtRunsDirectly`); falso → `CANCELLED` sem `print()`; verdadeiro → `print()` na thread que chamou, fora da EDT; `PrinterException` sobe para o `PdfActions`);
  - `class PdfActions(bytes: ByteArray, suggestedName: String, chooser: SaveLocationChooser, printer: PrintDialog, uiDispatcher: CoroutineDispatcher, ioDispatcher: CoroutineDispatcher)`:
    - `suspend fun save(): SaveResult` (`Saved`, `Cancelled`, `Failed(message)`): diálogo no `uiDispatcher` (EDT), escrita no `ioDispatcher`;
    - `suspend fun print(): PrintResult` (`Printed`, `Cancelled`, `Failed(message)`): carrega um `PDDocument` próprio dos bytes (só memória, depois do `install()`), monta o `PDFPageable` e chama a porta no `ioDispatcher` (o `SystemPrintDialog` leva só o diálogo para a EDT) e fecha o documento no fim;
    - textos da Global Constraint;
  - `fun suggestedPdfName(raw: String?, fallback: String): String`.

- [ ] **Step 1: Testes que falham:** `saveWritesExactBytes` (SHA-256 igual, numa pasta do teste), `cancelSaveWritesNothing`, `saveFailureShowsMessageWithoutPath` (destino cujo pai é um arquivo comum), `saveDialogResultMapping`, `suggestedNameSanitizesAndFallsBack`, `cancelPrintDoesNothing` e `printPassesAllPages` (com uma subclasse falsa de `PrinterJob` pelo `SystemPrintDialog`: `printDialog()` falso → `print()` nunca chamado; verdadeiro → o `Pageable` tem as N páginas), `printFailureShowsMessage` (`PrinterException`), `printWhileNavigatingIsSafe` (um `PdfDocumentState` aberto dos mesmos bytes navega durante a impressão), `closeDuringPrintDoesNotBreakPrint`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta salvar e imprimir o PDF ao design`).

---

### Task 6: Composable `PdfViewer`

**Estimativa:** 40 min · **Depende de:** Tasks 4 e 5 · **Risco:** normal (composição sobre estados testados)

**Files:**
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfViewer.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfViewerTest.kt`

O catálogo de componentes do `app` não ganha o visualizador (exigiria bytes de PDF no `app`).

**Interfaces:**
- Consumes: Tasks 4 e 5.
- Produces: `@Composable fun PdfViewer(title: String, document: PdfDocumentState, actions: PdfActions, onClose: () -> Unit)`:
  - barra com o título, "Página n de N", "Anterior"/"Próxima" (desabilitados nas pontas), "−"/"+" e o zoom em %, "Salvar como…", "Imprimir" e "Fechar";
  - a página renderizada com rolagem; carregando enquanto renderiza; o erro do documento no lugar da página;
  - o resultado de salvar/imprimir numa linha de status ("Arquivo salvo." / "Enviado para a impressora." / a mensagem de falha); cancelar não mostra nada; "Salvar como…" e "Imprimir" desabilitados enquanto a ação anterior corre;
  - tags: `pdf-viewer`, `pdf-page`, `pdf-prev`, `pdf-next`, `pdf-zoom-in`, `pdf-zoom-out`, `pdf-save`, `pdf-print`, `pdf-close`, `pdf-status`.

- [ ] **Step 1: Testes que falham** (compose-ui-test, cliques reais): `showsFirstPageAndCounter`, `navigationButtonsRespectBounds`, `zoomButtons`, `saveShowsStatus`, `cancelledSaveShowsNothing`, `printShowsStatus`, `actionsDisabledWhileRunning`, `closeCallsOnClose`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o visualizador de PDF ao design`).

---

### Task 7: Estado da ficha de registro no `app`

**Estimativa:** 35 min · **Depende de:** Tasks 2 e 6 · **Risco:** alto (erros do servidor; ciclo de vida do documento)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RegistrationCardState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardStateTest.kt`

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces: `class RegistrationCardState(source: RegistrationCards, scope: CoroutineScope, reservationId: String, reservationCode: String, newDocument: (ByteArray, CoroutineScope) -> PdfDocumentState, newActions: (bytes: ByteArray, suggestedName: String) -> PdfActions)`:
  - `start()` baixa; `loading`, `error: String?` (textos da Global Constraint), `viewer: CardViewer?` (`data class CardViewer(val document: PdfDocumentState, val actions: PdfActions)`) só quando o PDF abriu (nunca vazio);
  - o nome sugerido é `suggestedPdfName(document.fileName, "ficha-<código>.pdf")`;
  - `retry()`, `close()` (fecha o documento; resultado tardio descartado e o documento tardio fechado);
  - `title` = "Ficha de registro — <código>"; `toString` sem bytes.

- [ ] **Step 1: Testes que falham:** `opensViewerWithDocument`, `fallbackNameUsesCode`, `unavailableShowsMessageWithoutViewer`, `notFoundShowsMessage`, `forbiddenShowsMessage`, `networkShowsMessageAndRetryWorks`, `invalidPdfShowsOpenError`, `closeDuringDownloadClosesLateDocument`, `startWhileLoadingIsNoOp`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o estado da ficha de registro`).

---

### Task 8: A ficha no detalhe e depois do check-in

**Estimativa:** 45 min · **Depende de:** Task 7 · **Risco:** alto (permissão; ciclo de vida de documento com dado pessoal)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` (botão "Ficha de registro" no `DetailPanel`, tag `open-registration-card`; a janela ou diálogo grande com o `PdfViewer`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInDialog.kt` (no estado concluído, "Abrir ficha de registro", tag `check-in-open-card`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (fábrica do `RegistrationCardState` com `connection.registrationCards()`, as portas reais `FileDialogSaveChooser(janela)` e `SystemPrintDialog()`, e o fechamento no `onDispose`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardScreenTest.kt`

**Interfaces:**
- Consumes: Task 7.
- Produces: `ReceptionScreen` ganha o parâmetro opcional `registrationCard: ((reservationId: String, code: String) -> RegistrationCardState)? = null` (padrão nulo; os testes existentes não mudam), lido por `rememberUpdatedState`; o `CheckInDialog` ganha `onOpenCard: (() -> Unit)? = null`, que captura o id e o código da reserva do detalhe no momento em que o check-in abriu.
  - o botão do detalhe aparece só com `frontdesk.stays.checkin` e a reserva em `CONFIRMED`, `IN_HOUSE` ou `CHECKED_OUT`;
  - fechar o visualizador fecha o estado; trocar de reserva, fechar o detalhe, sair da área ou trocar de sessão com a ficha aberta fecha o estado (`DisposableEffect`);
  - o atalho do check-in abre a ficha da reserva que acabou de entrar.

- [ ] **Step 1: Testes que falham:** `buttonOnlyForAllowedStatusesAndPermission` (cancelada, `NO_SHOW` e sem permissão: some), `openingShowsViewer`, `serverRefusalShowsMessageNotViewer`, `checkInDoneOffersCard`, `closingViewerClosesState`, `closingDetailClosesCard`, `leavingAreaClosesCard`, `sessionChangeClosesCard`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde (os testes existentes da recepção continuam verdes sem mudança).
- [ ] **Step 5: Commit** (`Abre a ficha de registro no detalhe e depois do check-in`).

---

### Task 9: Prova pelo JAR e verificação ao vivo

**Estimativa:** 45 min · **Depende de:** Task 8 · **Risco:** alto (critérios da etapa; lê dado pessoal fictício do seed)

**Files:**
- Modify: `app/build.gradle.kts` (`testImplementation(libs.pdfbox)`; o `verifyDesktopJar` passa a exigir `org/apache/pdfbox/` e o recurso de fonte que o `FontMapper` usa; input `zenyLiveCard` de `ZENY_LIVE_CARD` e o opt-in no `outputs.upToDateWhen`) e os lockfiles do `app` no mesmo commit
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/DesktopJarPdfTest.kt`
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveRegistrationCardCheck.kt`

**Interfaces:**
- Produces:
  - `DesktopJarPdfTest.jarRendersFirstPage`: carrega as classes do `zeny-desktop.jar` (propriedade `zeny.desktopJar`, que a tarefa `test` já recebe) num classloader isolado e, por ele, só o que não usa o Skia: `PdfBoxSetup.install()`, a carga em memória de um PDF com Helvetica e o `PDFRenderer` até um `BufferedImage` da página 1 não vazio, conferindo que o recurso de fonte do mapper vem do JAR (o nativo do Skiko não pode ser carregado por um segundo classloader);
  - `LiveRegistrationCardCheck`, pulada sem `ZENY_LIVE_CARD=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (a Recepcionista) e falha sem eles; usa o `CoreConnection` real e os estados reais, no molde de `LiveTracesCheck`.

- [ ] **Step 1: Os testes:**
  - `jarRendersFirstPage` (no gate);
  - `cardOfInHouseOpens` (ao vivo): a ficha da primeira reserva hospedada abre pelo `RegistrationCardState`; o texto extraído com o `PDFTextStripper` (só no teste, em memória) contém o código da reserva, o número da UH alocada e o nome do titular do detalhe; o documento aparece mascarado (nenhuma sequência de 11 dígitos seguidos); a captura `app/build/live/registration-card.png` mostra o visualizador;
  - `cancelledReservationHasNoButton` (ao vivo): com uma reserva cancelada do seed, se houver, o botão não aparece (sem candidata, o teste diz isso e pula só esse caso);
  - `pdfBoxSetupIsActive` (ao vivo): depois de abrir a ficha real, o `FontMapper` é o próprio e o cache do `ImageIO` está desligado.
- [ ] **Step 2:** sem o opt-in → pulada; gate verde.
- [ ] **Step 3:** o orquestrador roda a verificação com o arquivo 600 e o `smokeTest`. A evidência do JAR é o `DesktopJarPdfTest` com o `verifyDesktopJar`; abrir a ficha pelo JAR na mão é complementar, se houver como.
- [ ] **Step 4: Commit** (`Prova a ficha pelo JAR e a verifica ao vivo`).

## Decisões para o usuário (tomadas pela opção recomendada; vão ao handoff)

- **Critério "Imprimir abre o diálogo do sistema":** provado no notebook pelo adaptador `SystemPrintDialog` com um `PrinterJob` falso (cancelar não imprime; confirmar manda as N páginas) e no Windows pelo roteiro da E06, com os itens Imprimir → Cancelar, Imprimir → "Microsoft Print to PDF", Salvar como → Cancelar e Salvar como sobre um arquivo existente.
- **"Salvar como…" sem escrita atômica:** grava direto no destino escolhido; uma falha no meio pode deixar o destino truncado. Um arquivo irmão com rename seria uma segunda cópia do PDF.
- **Impressão no Linux:** o `PSPrinterJob` da JVM grava um `javaprint*.ps` temporário enquanto o `lpr` roda (diverge do ADR 0009; o Windows usa o spooler do sistema). Espera a decisão do usuário.
- **Fidelidade das fontes:** Helvetica, Times e Courier (não embutidas na ficha) saem com as fontes que o PDFBox traz no JAR; se ele trouxer só a Liberation Sans regular, os negritos saem regulares na tela e no papel. Alternativa: embarcar as variantes da Liberation Sans (licença OFL) como recurso do `design`, medindo o JAR.

---

## Revisão de arquitetura

- **Veredito:** aprovado com notas.
- **Rodadas:** 2 (rodada 0 bloqueada por 8 Importantes; rodada 1 bloqueada por 5 Importantes novos da reescrita; rodada 2 aprovada com notas).
- **Modelo:** opus (`revisor-arquitetura`).
- **Notas Menores** (incorporadas): a checagem estática aceita o PDFBox ausente do `app` até a Task 9; o `SystemPrintDialog` chama o diálogo direto quando já está na EDT. O revisor não conferiu os detalhes internos do PDFBox 3.0.x (fontes do JAR, `DCTFilter`, `FileSystemFontProvider`); a Task 3 e a regra de parada da H4 cobrem.
