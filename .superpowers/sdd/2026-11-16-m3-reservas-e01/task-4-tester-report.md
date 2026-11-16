# Task 4 - relatório do testador

**Status: VERDE.** Nenhum defeito de produção encontrado. Nenhum teste existente alterado; nenhum código de produção tocado.

## Arquivos novos
- `app/src/test/kotlin/com/zeny/desktop/app/reservations/ReservationSearchStateAtaqueTest.kt` (35 testes)
- `app/src/test/kotlin/com/zeny/desktop/app/reservations/ReservationSearchTextAtaqueTest.kt` (6 testes)

## O que cada grupo cobre
Corridas: duas `search()` seguidas (só a última vence); busca por documento vence `search` presa; `search` vence busca por documento presa; `loadMore` em voo durante busca nova é descartado e o cursor recomeça; `close()` com `businessDay` preso (nada atribuído depois, nem erro); `close()` com primeira página em voo, inclusive com falha programada (descartada); `close()` libera o `fetch` que espera a primeira consulta; `close()` antes do `start()` e duplo; `start()` duas vezes (com e sem `businessDay` preso, sem linhas repetidas); três `loadMore()` antes do `start()` geram uma só primeira página; `loadMore` após falha do `businessDay` nunca vai à rede e o `start()` seguinte recupera.

Número do documento: o `current` (por reflexão) alcança uma `DocumentQuery` só enquanto a busca por documento vive, e não depois de `search()` nem de `close()`; reenvio em 4 páginas; segunda busca por documento substitui a primeira; busca inválida (documento ou filtro) mantém a consulta vigente; erros, `toString`, `fieldError` e `searchedDocument` sem o número (recusa local e erro 400 do servidor); documento curto mascarado só com `***`; pontuação mascarada pelos 4 últimos alfanuméricos.

Bordas: nome 1/2/200/201 e só espaços; `R-` com 30/31, `B-`/`b-` 30/31, OTA 60/61, `R-` sozinho (vai como `code = "R-"`); documento 40/41 e 40 com espaços; país `ar`→`AR`, `a` e `arg` recusados, país informado em RG; `29/02/2028` aceito e `29/02/2027` recusado; datas iguais e período aberto; erro de campo some após busca válida; situações desconhecidas nunca chegam à fonte.

Titular e tipos de UH: titular lido uma vez por hóspede entre páginas e buscas; tipos de UH lidos uma vez por estado; `Problem` em tipos vira "—" sem nova leitura; falha de rede em tipos aparece na tabela e o "Carregar mais" tenta de novo; erro 500 de titular aparece na tabela (não vira "—"); 403 de titular em página posterior vira "—"; busca nova durante leitura de titular presa (serialização) termina consistente.

Texto: prefixos `r-`/`b-` minúsculos, `R-`/`b-` sozinhos, prefixo só no início (`XR-1`, `R1`, `B1` são OTA), espaços internos mantidos; máscara nas bordas de 4 e 5 alfanuméricos.

## Evidência
Foco: `./gradlew :app:test --tests '*ReservationSearch*' --max-workers=2` -> BUILD SUCCESSFUL. Contagens (XML): SourceAtaque 33, Source 14, StateAtaque 35, State 16, TextAtaque 6, Text 2; 0 falhas/erros/pulados.
Um teste meu falhou na primeira rodada (`unknownStatusesNeverReachTheSource`) por erro do próprio teste (faltava `runCurrent()` entre `search()` e `searchByDocument()`, a busca foi cancelada antes de chamar a fonte); corrigido, não era defeito do código.

Gate: `./gradlew build --max-workers=2` -> `BUILD SUCCESSFUL in 6m 11s`, `87 actionable tasks`. `bash tests/run.sh` -> `Resumo: 48 arquivos, 0 falhas, 0 com casos pulados`.
Nota de ambiente: uma rodada do `build` terminou com `:app:test FAILED` quando eu tinha por engano 3 builds Gradle concorrentes neste worktree; não consegui ver qual teste (saída truncada). A rodada isolada seguinte ficou verde. Sugiro o orquestrador rodar o gate uma vez isolado se quiser confirmar que não há teste frágil sob contenção.

## Observações (sem alterar nada)
1. Corrida de `start()` com busca por documento: se o usuário chama `searchByDocument()` enquanto o `businessDay` do `start()` ainda está preso, o `search()` do `start()` chega depois e substitui a busca por documento (limpa `searchedDocument`). A Task 5 deve manter a tela inerte até o `day` chegar; não escrevi teste porque o brief não define a regra.
2. `R-` sozinho (e `B-`) é enviado como filtro `code`/`bookingCode` sem validação de mínimo; o servidor decide.
3. Os testes do estado leem por reflexão o campo privado `current` (nome vem do brief); renomeá-lo quebra `documentQueryIsGoneAfterNewSearchAndAfterClose`.
4. Depois do `close()`, `documentNumber` digitado pelo chamador permanece no campo (`search*` ignoram); só o que o estado limpa no `close()` é apagado.
5. Mutação de código de produção para provar a sensibilidade dos testes não foi feita (regra da soul); a sensibilidade das corridas se apoia nas mutações registradas pelo desenvolvedor e nos asserts de contagem de chamadas.

## Testes existentes que parecem errados
Nenhum.

## Commit
Ver o resultado final do despacho (SHA e assunto), trailer `Soul: testador`.
