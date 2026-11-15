# M2 E07 — Ficha de registro e visualizador de PDF — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o recepcionista abre a ficha de registro de uma reserva confirmada, hospedada ou encerrada dentro do app (no detalhe e logo após o check-in), confere as páginas na tela com navegação e zoom, salva o arquivo onde escolher ou imprime na impressora do computador, sem que o PDF toque o disco por outro caminho.

**Architecture:** no `core`, uma `DocumentsFacade` baixa o PDF por um caminho binário novo do `ApiExecutor` e devolve `PdfDocument(bytes, fileName)` ou o `ApiError` mapeado. No `design`, um componente reaproveitável (M3 E03 e M4 E02 vão usá-lo): `PdfDocumentState` renderiza as páginas em memória com o Apache PDFBox, `PdfFileSaver` e `PdfPrinter` fazem "Salvar como…" e "Imprimir" atrás de portas testáveis, e o composable `PdfViewer` junta tudo. No `app`, uma porta `RegistrationCards`, o estado `RegistrationCardState` e a ligação no detalhe e no fim do check-in. O `smokeTest` baixa a ficha de um hospedado.

**Tech Stack:** o da E01–E05, mais o Apache PDFBox 3.0.x (dependência nova, só no `design`).

**Spec:**
- a seção "E07 — Ficha de registro e visualizador de PDF" de `docs/roadmaps/roadmap-m2-recepcao-do-dia.md`, com "Contexto confirmado", "Restrições e invariantes", a hipótese H4/H5 e os handoffs da E02 e da E05;
- o [ADR 0009](../../adr/0009-documentos-pdf-e-impressao.md) (visualizador em memória, impressão local, "Salvar como…" explícito) e o ADR 0008 (fronteira `core`/`sdk`);
- o design `docs/superpowers/specs/2026-11-10-front-desktop-design.md` §6.2.

**Fatos do contrato e do servidor fixado (`57eb5fac5b8a`, só leitura):**
- `getRegistrationCardPdf` (`GET /api/v1/reservations/{id}/registration-card/pdf`, `FrontdeskApi`): `200 application/pdf` (binário); erros em `application/problem+json`: `404 RESERVATION_NOT_FOUND`, `409 REGISTRATION_CARD_UNAVAILABLE` (fora de `CONFIRMED`, `IN_HOUSE`, `CHECKED_OUT`), `403` sem `frontdesk.stays.checkin`. Sem `Idempotency-Key`. Não é guardado em cache e não deixa trilha.
- O cliente gerado tipa o corpo como `HttpResponseTyped<Any>`; o `prepareGetRegistrationCardPdf(id, acceptContentType, block)` devolve o `HttpStatement` cru, que o `core` executa e lê como bytes.
- O servidor manda `Content-Disposition: attachment; filename="ficha-<código>.pdf"` (`RegistrationCardController.kt`, linhas 71–74); o contrato só diz o nome na descrição.
- O conteúdo da ficha é do servidor: nome, nacionalidade, nascimento, endereço, documentos mascarados e os códigos FNRH. O front não o corrige.
- O Recepcionista do seed tem `frontdesk.stays.checkin` (H5).

## Global Constraints

- **Disco (ADR 0009):** o PDF fica só em memória. Proibidos `File.createTempFile`, `Files.createTemp*`, `java.awt.Desktop.open`/`browse`, `deleteOnExit` e qualquer cache em disco. O PDFBox é configurado para não gravar nada: carga com cache de stream só em memória (`IOUtils.createMemoryOnlyStreamCache()` ou equivalente da versão) e um `FontMapper` próprio, instalado uma vez no `design`, que resolve as fontes não embutidas pelas fontes que o próprio PDFBox traz no JAR (sem varrer as fontes do sistema, o que gravaria `.pdfbox.cache` na pasta do usuário). O disco só recebe o arquivo por "Salvar como…", no destino que o usuário escolheu.
- **"Salvar como…":** grava exatamente os bytes recebidos; o nome sugerido é o do servidor, saneado (sem separador de caminho nem caractere de controle; vazio ou suspeito → `ficha-<código>.pdf`, e sem código → `ficha.pdf`); cancelar não grava nada; destino existente pede confirmação do diálogo do sistema; falha de escrita aparece em pt-BR ("Não foi possível salvar o arquivo.") sem o caminho completo.
- **"Imprimir":** o diálogo de impressão do sistema (`PrinterJob.printDialog()`), alimentado pelo documento em memória (`PDFPageable`); cancelar não deixa efeito; falha de impressão aparece em pt-BR ("Não foi possível imprimir.").
- **Erros:** `409 REGISTRATION_CARD_UNAVAILABLE` → "A ficha só existe para reservas confirmadas, hospedadas ou encerradas."; `404 RESERVATION_NOT_FOUND` → "A reserva não foi encontrada. Atualize o painel."; `403` → o texto comum de permissão; rede → o texto comum de rede; resposta que não é PDF (tipo diferente de `application/pdf` ou bytes que não começam com `%PDF-`) → `ApiError.IncompatibleResponse`; PDF que o PDFBox não abre → "Não foi possível abrir a ficha." Nunca `code` cru, nunca o visualizador vazio.
- **Acesso:** a ação "Ficha de registro" aparece só com `frontdesk.stays.checkin` e com a reserva em `CONFIRMED`, `IN_HOUSE` ou `CHECKED_OUT`; o servidor decide (`403`/`409` tratados).
- **Dados pessoais:** os bytes e as páginas nunca vão a log, `toString` ou mensagem de exceção (`toString` de `PdfDocument` e dos estados mostra só o tamanho e o número de páginas). O documento é fechado (`PDDocument.close()`) e as imagens soltas quando o visualizador fecha.
- **Memória:** renderização fora da thread de UI, uma página por vez, com a página visível em cache e no máximo a vizinha; zoom de 50% a 200% em passos de 25% (padrão 100%, 96 dpi × zoom).
- **Fronteira (ADR 0008):** o `app` usa só `core` e `design`; o PDFBox entra só no `design`; o `core` não o conhece.
- **Dependência nova:** a versão do PDFBox vai em `gradle/libs.versions.toml`, e os lockfiles são regravados no mesmo commit (`./gradlew resolveAndLockAll --write-locks`, sem lockfile pendente). Escolha a última 3.0.x estável do Maven Central e registre no relatório: versão, tamanho do `zeny-desktop.jar` antes (61.435.076 bytes) e depois, e o tempo do `./gradlew :app:desktopJar`.
- **Testes:**
  - não dormem; verificação ao vivo só com o opt-in próprio `ZENY_LIVE_CARD` e o arquivo 600; com o opt-in e sem credencial, falha;
  - o teste de "nenhum arquivo criado" redireciona `java.io.tmpdir` e `user.home` para pastas novas do teste e compara a árvore inteira de ambas antes e depois de abrir, navegar, mudar o zoom e fechar;
  - testes existentes só mudam onde a tarefa manda.
- **Gate e commits:** `./gradlew build --max-workers=2` e `bash tests/run.sh` verdes antes de cada commit; nunca `./gradlew --stop`; UI em pt-BR, código em inglês; commits em pt-BR com `Soul:` e `Co-Authored-By:` consecutivos no último parágrafo.

## Review Focus

1. **O PDFBox gravando em disco** (cache de fontes em `~/.pdfbox.cache`, arquivo temporário de stream): nada é criado em `java.io.tmpdir` nem em `user.home`. Teste `openNavigateZoomCloseCreatesNoFile` (Task 3).
2. **Nome sugerido malicioso ou estranho** vindo do servidor (`../../x.pdf`, `a/b.pdf`, aspas, vazio, sem `.pdf`): o nome sai saneado. Testes `fileNameIsSanitized` (Task 1) e `suggestedNameFallsBack` (Task 4).
3. **Resposta que não é PDF** (HTML de proxy, JSON com `200`, corpo vazio): `IncompatibleResponse`, nunca o visualizador vazio. Teste `nonPdfBodyIsIncompatible` (Task 1).
4. **Fechar o visualizador no meio da renderização** ou trocar de página rápido: o documento fecha uma vez, renderização tardia é descartada, sem exceção. Teste `closeDuringRenderDiscardsResult` (Task 3).
5. **Cancelar os diálogos do sistema** ("Salvar como…" e "Imprimir"): nada gravado, nada impresso, sem mensagem de erro. Testes `cancelSaveWritesNothing` e `cancelPrintDoesNothing` (Task 4).

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
  - em `ApiExecutor`: `suspend fun executeBytes(call: suspend (CallOptions) -> HttpStatement): BinaryResponse`, com `class BinaryResponse(val bytes: ByteArray, val contentType: String?, val contentDisposition: String?)`; mesmos erros tipados do `execute` (o problema RFC 9457 pelo `ProblemMapper`, `401` → `SessionEnded`, rede → `Network`), sem `Intent`;
  - `data class PdfDocument(val bytes: ByteArray, val fileName: String)`, com `equals`/`hashCode` por conteúdo e `toString` = `PdfDocument(<n> bytes, <fileName>)` sem os bytes;
  - `class DocumentsFacade internal constructor(frontdesk: FrontdeskApi, executor: ApiExecutor)` com `suspend fun registrationCard(reservationId: String): PdfDocument`:
    - id inválido → `IllegalArgumentException` antes da rede, sem o valor;
    - `Accept: application/pdf`;
    - corpo que não começa com `%PDF-` ou tipo diferente de `application/pdf` → `ApiError.IncompatibleResponse`, sem os bytes na causa;
    - `fileName` do `Content-Disposition` (`filename` e `filename*`), saneado: só o último segmento, sem `/`, `\`, `..` nem caractere de controle, com `.pdf`; ausente ou inválido → `ficha.pdf`.

- [ ] **Step 1: Testes que falham** (`MockEngine`): `returnsBytesAndServerFileName`, `sendsAcceptPdfWithoutIdempotencyKey`, `unavailableIsProblem` (`409 REGISTRATION_CARD_UNAVAILABLE`), `notFoundIsProblem`, `sessionEndedOn401`, `networkFailureIsTyped`, `nonPdfBodyIsIncompatible` (HTML com `200`, JSON com `200`, corpo vazio), `fileNameIsSanitized` (`../../x.pdf` → `x.pdf`; `a/b` → `b.pdf`; vazio → `ficha.pdf`; `filename*=UTF-8''ficha-R%C3%A9.pdf`), `invalidIdFailsBeforeNetwork`, `toStringHidesBytes`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*DocumentsFacadeTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** implemente.
- [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o download da ficha de registro ao core`).

---

### Task 2: Porta da ficha e o `smokeTest`

**Estimativa:** 30 min · **Depende de:** Task 1 · **Risco:** normal (repasse fino)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RegistrationCards.kt` (`fun interface RegistrationCards { suspend fun registrationCard(reservationId: String): PdfDocument }`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/session/ServerConnection.kt` (`fun registrationCards(): RegistrationCards? = null`; o `CoreConnection` repassa a `core.documents`)
- Modify: `demo-seed/src/smokeTest/kotlin/com/zeny/desktop/seed/smoke/SmokeTest.kt` (o teste de fumaça baixa a ficha de um hospedado do seed, logado como Recepcionista, e confere que os bytes começam com `%PDF-`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardsSourceTest.kt` (o adaptador repassa id e devolve o documento)

**Interfaces:**
- Consumes: Task 1.
- Produces: `RegistrationCards` e `ServerConnection.registrationCards()`.

- [ ] **Step 1:** testes que falham (`adapterPassesIdAndReturnsDocument`); o `smokeTest` não roda no gate (fica fora de `check`/`build`).
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente; compile o `smokeTest` (`./gradlew :demo-seed:compileSmokeTestKotlin --max-workers=2`).
- [ ] **Step 4:** PASS; gate verde. O orquestrador roda o `smokeTest` contra o ambiente.
- [ ] **Step 5: Commit** (`Acrescenta a porta da ficha de registro e a confere no teste de fumaça`).

---

### Task 3: Documento PDF em memória no `design` (PDFBox)

**Estimativa:** 45 min · **Depende de:** nenhuma · **Risco:** alto (dependência nova; disco; memória)

**Files:**
- Modify: `gradle/libs.versions.toml`, `design/build.gradle.kts` e os lockfiles (`./gradlew resolveAndLockAll --write-locks`)
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfDocumentState.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfDocumentStateTest.kt` (com um PDF de 3 páginas gerado no próprio teste pelo PDFBox, em memória)

**Interfaces:**
- Produces (pacote `com.zeny.desktop.design.pdf`):
  - `class PdfDocumentState(bytes: ByteArray, scope: CoroutineScope, renderDispatcher: CoroutineDispatcher = Dispatchers.Default)`:
    - `open(): Boolean` (falso com PDF inválido, com `error` = "Não foi possível abrir a ficha."), `pageCount: Int`, `page: Int` (0-based), `zoom: Int` (percentual), `image: ImageBitmap?`, `rendering: Boolean`, `error: String?`;
    - `next()`, `previous()`, `goTo(page)`, `zoomIn()`, `zoomOut()` (50..200, passo 25), `close()` (idempotente; fecha o `PDDocument` e solta as imagens);
    - `internal fun pageable(): java.awt.print.Pageable` (para a Task 4);
    - `toString` = `PdfDocumentState(pages=<n>, page=<i>)`.
  - `object PdfBoxSetup { fun install() }`: instala o `FontMapper` sem varredura do sistema, uma vez por processo; `PdfDocumentState.open()` chama.

- [ ] **Step 1: Testes que falham:**
  - `opensAndRendersFirstPage`, `navigatesWithinBounds`, `zoomStepsAndLimits`, `invalidBytesShowError` (bytes aleatórios e PDF truncado);
  - `openNavigateZoomCloseCreatesNoFile`: com `java.io.tmpdir` e `user.home` apontados para pastas novas do teste (e restaurados no fim), abrir um PDF que usa uma fonte padrão não embutida (Helvetica), navegar, mudar o zoom e fechar; a lista recursiva de arquivos das duas pastas é igual antes e depois;
  - `closeDuringRenderDiscardsResult` e `closeIsIdempotent`;
  - `toStringHasNoContent`.
- [ ] **Step 2:** `./gradlew :design:test --tests '*PdfDocumentStateTest*' --max-workers=2` → FAIL.
- [ ] **Step 3:** acrescente o PDFBox (catálogo, `design`, lockfiles) e implemente.
- [ ] **Step 4:** PASS; gate verde; `./gradlew :app:desktopJar --max-workers=2` e registre no relatório a versão, o tamanho do JAR antes/depois e o tempo; confira que as classes `org/apache/pdfbox` estão no `zeny-desktop.jar`.
- [ ] **Step 5: Commit** (`Acrescenta o documento PDF em memória ao design`).

---

### Task 4: "Salvar como…" e "Imprimir" no `design`

**Estimativa:** 40 min · **Depende de:** Task 3 · **Risco:** alto (escreve em disco por ação do usuário; impressão)

**Files:**
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfActions.kt`
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfActionsTest.kt`

**Interfaces:**
- Consumes: Task 3 (`PdfDocumentState.pageable()`).
- Produces:
  - `fun interface SaveLocationChooser { fun choose(suggestedName: String): java.nio.file.Path? }` (nulo = cancelado) e a implementação real `FileDialogSaveChooser` (`java.awt.FileDialog` em modo `SAVE`, com o nome sugerido);
  - `fun interface PrintDialog { fun show(pageable: Pageable): PrintOutcome }`, `enum class PrintOutcome { PRINTED, CANCELLED }` e a implementação real `SystemPrintDialog` (`PrinterJob.getPrinterJob()`, `setPageable`, `printDialog()`; falso → `CANCELLED`; verdadeiro → `print()`);
  - `class PdfActions(bytes: ByteArray, suggestedName: String, chooser: SaveLocationChooser, printer: PrintDialog, ioDispatcher: CoroutineDispatcher)`, com `suspend fun save(): SaveResult` (`Saved`, `Cancelled`, `Failed(message)`) e `suspend fun print(document: PdfDocumentState): PrintResult` (`Printed`, `Cancelled`, `Failed(message)`), com os textos da Global Constraints;
  - o nome sugerido passa pelo mesmo saneamento (função `suggestedPdfName(raw: String?, fallback: String): String` neste arquivo).

- [ ] **Step 1: Testes que falham:** `saveWritesExactBytes` (SHA-256 do arquivo = SHA-256 dos bytes recebidos, numa pasta temporária do teste), `cancelSaveWritesNothing`, `saveFailureShowsMessageWithoutPath` (destino numa pasta sem permissão de escrita), `suggestedNameFallsBack`, `cancelPrintDoesNothing`, `printPassesAllPages` (o `PrintDialog` falso recebe um `Pageable` com o número de páginas do documento), `printFailureShowsMessage`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta salvar e imprimir o PDF ao design`).

---

### Task 5: Composable `PdfViewer`

**Estimativa:** 40 min · **Depende de:** Task 4 · **Risco:** normal (composição sobre estados testados)

**Files:**
- Create: `design/src/main/kotlin/com/zeny/desktop/design/pdf/PdfViewer.kt`
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/catalog/CatalogData.kt` e `ComponentCatalog.kt` só se o catálogo de componentes listar todos os componentes do `design` (siga o que já existe)
- Test: `design/src/test/kotlin/com/zeny/desktop/design/pdf/PdfViewerTest.kt`

**Interfaces:**
- Consumes: Tasks 3 e 4.
- Produces: `@Composable fun PdfViewer(title: String, document: PdfDocumentState, actions: PdfActions, onClose: () -> Unit)`:
  - barra com o título, "Página n de N", "Anterior"/"Próxima" (desabilitados nas pontas), "−"/"+" e o zoom em %, "Salvar como…", "Imprimir" e "Fechar";
  - a página renderizada com rolagem; carregando enquanto renderiza; o erro do documento no lugar da página;
  - o resultado de salvar/imprimir numa linha de status ("Arquivo salvo." / "Enviado para a impressora." / a mensagem de falha); cancelar não mostra nada;
  - tags: `pdf-viewer`, `pdf-page`, `pdf-prev`, `pdf-next`, `pdf-zoom-in`, `pdf-zoom-out`, `pdf-save`, `pdf-print`, `pdf-close`, `pdf-status`.

- [ ] **Step 1: Testes que falham** (compose-ui-test, cliques reais): `showsFirstPageAndCounter`, `navigationButtonsRespectBounds`, `zoomButtons`, `saveShowsStatus`, `cancelledSaveShowsNothing`, `printShowsStatus`, `closeCallsOnClose`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o visualizador de PDF ao design`).

---

### Task 6: Estado da ficha de registro no `app`

**Estimativa:** 35 min · **Depende de:** Tasks 2 e 5 · **Risco:** alto (erros do servidor; ciclo de vida do documento)

**Files:**
- Create: `app/src/main/kotlin/com/zeny/desktop/app/reception/RegistrationCardState.kt`
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardStateTest.kt`

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces: `class RegistrationCardState(source: RegistrationCards, scope: CoroutineScope, reservationId: String, reservationCode: String, newDocument: (ByteArray, CoroutineScope) -> PdfDocumentState = ::PdfDocumentState)`:
  - `start()` baixa; `loading`, `error: String?` (textos da Global Constraints), `viewer: Pair<PdfDocumentState, PdfActions>?` só quando o PDF abriu (nunca vazio);
  - `retry()`, `close()` (fecha o documento; resultado tardio descartado e o documento tardio fechado);
  - `title` = "Ficha de registro — <código>";
  - `toString` sem bytes.

- [ ] **Step 1: Testes que falham:** `opensViewerWithDocument`, `unavailableShowsMessageWithoutViewer`, `notFoundShowsMessage`, `forbiddenShowsMessage`, `networkShowsMessageAndRetryWorks`, `invalidPdfShowsOpenError`, `closeDuringDownloadClosesLateDocument`, `startWhileLoadingIsNoOp`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde.
- [ ] **Step 5: Commit** (`Acrescenta o estado da ficha de registro`).

---

### Task 7: A ficha no detalhe e depois do check-in

**Estimativa:** 40 min · **Depende de:** Task 6 · **Risco:** normal (ligação em telas existentes)

**Files:**
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/ReceptionScreen.kt` (botão "Ficha de registro" no `DetailPanel`, tag `open-registration-card`; o diálogo/janela com o `PdfViewer`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/reception/CheckInDialog.kt` (no estado concluído, o botão "Abrir ficha de registro", tag `check-in-open-card`)
- Modify: `app/src/main/kotlin/com/zeny/desktop/app/AppRoot.kt` (fábrica do `RegistrationCardState` com `connection.registrationCards()`)
- Test: `app/src/test/kotlin/com/zeny/desktop/app/reception/RegistrationCardScreenTest.kt`

**Interfaces:**
- Consumes: Task 6.
- Produces: `ReceptionScreen` ganha o parâmetro opcional `registrationCard: ((reservationId: String, code: String) -> RegistrationCardState)? = null` (padrão nulo; os testes existentes não mudam); o `CheckInDialog` ganha `onOpenCard: (() -> Unit)? = null`.
  - o botão do detalhe aparece só com `frontdesk.stays.checkin` e a reserva em `CONFIRMED`, `IN_HOUSE` ou `CHECKED_OUT`;
  - o visualizador abre numa janela ou diálogo grande do app; fechar fecha o estado; trocar de reserva com a ficha aberta fecha a anterior;
  - o atalho do check-in abre a ficha da reserva que acabou de entrar.

- [ ] **Step 1: Testes que falham:** `buttonOnlyForAllowedStatusesAndPermission` (cancelada, `NO_SHOW` e sem permissão: some), `openingShowsViewer`, `serverRefusalShowsMessageNotViewer`, `checkInDoneOffersCard`, `closingViewerClosesState`.
- [ ] **Step 2:** FAIL. - [ ] **Step 3:** implemente. - [ ] **Step 4:** PASS; gate verde (os testes existentes da recepção continuam verdes sem mudança).
- [ ] **Step 5: Commit** (`Abre a ficha de registro no detalhe e depois do check-in`).

---

### Task 8: Verificação ao vivo da ficha e do JAR

**Estimativa:** 40 min · **Depende de:** Task 7 · **Risco:** alto (critérios da etapa; lê dado pessoal fictício do seed)

**Files:**
- Create: `app/src/test/kotlin/com/zeny/desktop/app/reception/LiveRegistrationCardCheck.kt`
- Modify: `app/build.gradle.kts` (input `zenyLiveCard` de `ZENY_LIVE_CARD` e o opt-in no `outputs.upToDateWhen`, como os outros)

**Interfaces:**
- Produces: `LiveRegistrationCardCheck`, pulada sem `ZENY_LIVE_CARD=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (a Recepcionista) e falha sem eles. Usa o `CoreConnection` real e os estados reais, no molde de `LiveTracesCheck`.

- [ ] **Step 1: A verificação** (contra o `zeny-front` com o seed, logado como Recepcionista):
  - `cardOfInHouseOpens`: a ficha da primeira reserva hospedada abre pelo `RegistrationCardState`; o texto extraído com o `PDFTextStripper` (só no teste, em memória) contém o código da reserva, o número da UH alocada e o nome do titular do detalhe; o documento aparece mascarado (nenhuma sequência de 11 dígitos seguidos de CPF no texto); a captura `app/build/live/registration-card.png` mostra o visualizador;
  - `cancelledReservationHasNoButton`: com uma reserva cancelada do seed, se houver, o botão não aparece (sem candidata, o teste diz isso e pula só esse caso);
  - `openingCreatesNoFile`: com `java.io.tmpdir` e `user.home` redirecionados, abrir e fechar a ficha real não cria arquivo.
- [ ] **Step 2:** sem o opt-in → pulada; gate verde.
- [ ] **Step 3:** o orquestrador roda a verificação com o arquivo 600, gera o JAR (`./gradlew :app:desktopJar`) e abre a ficha pelo JAR contra o ambiente.
- [ ] **Step 4: Commit** (`Verifica a ficha de registro ao vivo`).
