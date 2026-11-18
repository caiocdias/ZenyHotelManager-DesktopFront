# 0013 — Uma fonte de permissões por sessão: as áreas consomem o `me` lido pela casca

- Estado: aceita · 2026-11-17 · decidida pelo usuário em entrevista (as quatro respostas pela opção recomendada), numa sessão `claude --agent arquiteto` antes da M4 E01
- Origem: decisão D5 da [M3](../roadmaps/roadmap-m3-reservas.md) ("Hipóteses e decisões em aberto"), tomada na entrevista do fim do lote da M3 E06 · relacionadas: [ADR 0008](0008-fronteira-core-sdk.md), [ADR 0012](0012-revoga-o-processo-acelerado.md)
- Etapa que executa: M4 E06, "Fonte única de permissões" ([roadmap do M4](../roadmaps/roadmap-m4-contas-caixa-checkout.md))

## Contexto

O D5 decidiu que as áreas passam a consumir as permissões que o `ShellState` já lê. Faltava decidir onde fica o estado, o que acontece na falha e no "Tentar de novo", como uma sessão nova invalida o valor velho e quando as áreas existentes migram.

Estado do código no `dbeacff`: numa sessão, quatro estados leem `GET /api/v1/auth/me` (operação `getMe`, fachada `core.account.me()`), cada um pela própria porta e com o próprio tratamento de falha.

| Quem lê | Onde | Na falha |
|---|---|---|
| A casca | `shell/ShellState.kt`, `reload()`: monta o menu com `visibleAreas(me.permissions, …)` e descarta o conjunto | `error`, e a `ShellScreen` mostra o `ErrorState` com "Tentar de novo" (`reload`). Nenhuma área é desenhada |
| A área Reservas | `reservations/AreaPermissions.kt` (`AreaPermissionsState`), ligada em `AppRoot.kt` (`reservationsPermissions`), pela porta `ReceptionSource.permissions()` ou `AvailabilitySource.permissions()` | `Failed`: as ações somem, e o `PermissionsNotice` oferece "Tentar de novo". Foi a correção do `A-I1` na M3 E06 |
| A Recepção | `reception/DayPanelState.kt` (`listen()`), pela porta `ReceptionSource.permissions()`. O `WalkInState` recebe `panel.permissions` | Conjunto vazio, sem aviso: o painel fica sem as ações e sem a assinatura do tempo real |
| Os Recados | `traces/TracesListState.kt` (`readPermissions()`), pela porta `TracesSource.permissions()` | Conjunto vazio, sem aviso |

Hoje a Recepção e os Recados têm o mesmo defeito que o `A-I1` corrigiu na Reservas: a falha da leitura tira as ações sem dizer por quê.

O fato que decide o desenho é que a `ShellScreen` só chama o conteúdo da área (`areaContent`) quando o `ShellState` saiu de `loading` sem `error`. Ou seja, toda área desenhada já tem, na casca, um `me` desta sessão que deu certo. A segunda leitura não traz informação nova; só traz uma segunda forma de falhar.

## Decisão

1. **O estado fica no `ShellState`.** Ele guarda o conjunto de permissões do `me` que deu certo, numa cópia imutável. O conjunto só é atribuído quando o `me` e a data operacional dão certo, junto com o menu, e é zerado no início de cada `reload()`.
2. **Entrega por parâmetro.** A casca passa o conjunto às áreas como parâmetro do `AreaContent`. Os *state holders* o recebem no construtor, como o `WalkInState` já recebe o do painel. Nenhuma área lê o `me` por conta própria: as portas `permissions()` saem de `ReceptionSource`, `AvailabilitySource` e `TracesSource`, e as implementações delas em `ServerConnection` também. Os nomes, o tipo do conjunto e a forma do parâmetro ficam com o planejador da etapa.
3. **A falha é da casca.** A falha do `me` é a falha da casca, com o `ErrorState` e o "Tentar de novo" que já existem (`ShellState.reload`), que releem tudo. Saem:
   - o `AreaPermissionsState` e o `PermissionsNotice`;
   - o fallback "falha vira conjunto vazio" do `DayPanelState` e do `TracesListState`.

   A regra "nunca conceder sem uma leitura que deu certo", que o `AreaPermissionsState` garantia, passa a valer pela construção: sem leitura boa, não há área desenhada.
4. **Invalidação pela sessão.** O conjunto vive dentro do `ShellState`, que o `AppRoot` já recria a cada `sessionId` (`remember(sessionId)`) e fecha ao sair (`close()` cancela a leitura em voo). Os *state holders* das áreas continuam com a chave `(source, sessionId)`. Nenhum valor sobrevive à troca de sessão. Não há releitura no meio da sessão: uma mudança de papel vale a partir do próximo login ou do próximo `reload`, como hoje. Quem decide cada ação continua sendo o servidor (`403` e `APPROVAL_REQUIRED` tratados por ação).
5. **Migração numa etapa própria, antes da M4 E01.** Entra no roadmap do M4 a etapa **E06, "Fonte única de permissões"**, sem dependências e posta antes da E01 no índice. Ela cria a fonte e migra as três áreas (Reservas, Recepção com o walk-in e Recados). A E01 e a E02 passam a depender dela, e as áreas novas do M4 já nascem consumindo a fonte.

O `core` não muda: a fachada `account.me()` continua sendo lida só pela casca. A decisão é interna ao módulo `app` e não toca a fronteira do ADR 0008.

## Alternativas descartadas

Onde fica o estado:
- **CompositionLocal provido pela casca**, como o `LocalApprovalCoordinator`. Muda menos assinaturas, mas a dependência fica implícita, e um teste que esquecer de prover o valor só falha na execução.
- **Cache por sessão no `core`.** Contraria o D5, que manda consumir o que o `ShellState` já leu, e põe no `core` um estado de UI que hoje é do `app`.

Falha e "Tentar de novo":
- **A casca expõe Carregando/Falhou/Concedido, e cada área mantém o aviso e o "Tentar de novo" locais.** Preserva a UI da Task 4 da M3 E06, mas o estado "Falhou" nunca chega a uma área desenhada: seria código morto.

Invalidação:
- **Conjunto observável, com as áreas reagindo sem ser recriadas.** Prepara uma releitura no meio da sessão, que nenhuma etapa pede. Também abre a corrida entre uma ação em curso e um conjunto que mudou.

Migração:
- **Tudo na M4 E01** (a fonte, a migração, a extração do D4 e o turno). Um lote a menos, mas o plano passa de três fatias e mistura dois refactors transversais com a primeira tela de dinheiro.
- **A E01 cria a fonte e a usa só no caixa, e a migração vem depois.** Durante o M4 conviveriam dois padrões, e a Recepção e os Recados manteriam a falha silenciosa.

## Consequências

- Uma leitura do `me` por sessão, em vez de quatro. Somem três estados de falha diferentes, e a falha passa a ter uma tela só: o `ErrorState` da casca.
- **Mudança de comportamento:**
  - O aviso da área Reservas (`PermissionsNotice`, `A-I1` da M3 E06) deixa de existir. O caso que ele cobria (a casca leu, e a segunda leitura falhou) deixa de ser possível.
  - A Recepção e os Recados deixam de esconder ações por falha silenciosa.
  - O painel do dia passa a escolher os tópicos do tempo real pelo conjunto da casca.
- **Testes afetados:** a migração apaga ou reescreve os testes do `AreaPermissionsState` (`AreaPermissionsStateTest`, `AreaPermissionsStateAtaqueTest`, `ReservationsPermissionsAtaqueTest`) e as portas falsas que têm `permissions()` (`FakeReceptionSource`, `FakeAvailabilitySource`, `FakeTracesSource` e os testes delas). Vários desses arquivos foram endurecidos pelo testador. Pela regra do `C-M6`, quem mexe neles é o testador, não o desenvolvedor.
- **Os Menores M1, M2 e M5 da revisão final da M3 E06**, levados à M4 E01 com o D4, apontam para o `AreaPermissionsState` e para um teste dele. Se a E06 remover esse código antes da E01, o destino deles é "resolvido pela remoção", com o commit citado. Mesmo assim, a E01 confere que a leitura que ficou (a do `ShellState`) não engole o cancelamento alheio nem pega `Throwable`.
- Uma tela futura que precise de permissão fora de uma área (por exemplo, o indicador de turno no topo, M4 E01) lê o mesmo conjunto do `ShellState`.
- Se um dia for preciso reler as permissões no meio da sessão, a releitura passa pelo `reload` da casca, ou um ADR novo revê o item 4.
