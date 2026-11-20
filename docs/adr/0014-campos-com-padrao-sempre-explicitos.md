# 0014 — Campos com valor padrão do servidor: o `core` sempre os envia explícitos

- Estado: aceita · 2026-11-20 · decidida pelo usuário em entrevista (a opção recomendada), na análise da rodada 4 do M0
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §6.1 e §6.2 · relacionadas: [ADR 0007](0007-gerador-do-sdk.md), [ADR 0008](0008-fronteira-core-sdk.md)
- Etapa que aplica primeiro: M0 E04 ([roadmap do M0](../roadmaps/roadmap-m0-sincronizacao.md))

## Contexto

No servidor em `8c563cda0575` (M6 E20 dele), o `required` dos schemas de requisição passou a sair de uma regra única do DTO (`shared/web/RequiredRequestProperties.kt` do servidor; ARCHITECTURE §7 dele, linha "Contrato"). Um campo anulável, ou com valor padrão no DTO, deixou de ser `required`. Pelo diff dos dois contratos, 34 campos de 26 schemas saíram do `required`, e nenhum entrou:

- `GuestProfileRequest.vip`, `CreateGuestRequest.documents`, `MergeGuestRequest.takeFromMerged`;
- `CheckInGuestRequest.primary`, `StayGuestRequest.primary`, `WalkInRequest.childAges`;
- `ReservationRequest.childAges`, `guests` e `nightlyRates`; `BlockReservationDto.childAges`, `guests` e `segment`; `ReservationCancelRequest.waivePenalty`; `ReservationDocumentSearchRequest.statuses`;
- `PostingRequest.quantity`, `ReceivePaymentRequest.deposit`, `CashPointRequest.blindClose`, `TransactionCodeCreateRequest.serviceChargeApplies`;
- `RoomRequest.featureIds`, `RoomTypeRequest.featureIds` e `displayOrder`;
- `RatePlanCreateRequest.packageIds`, `PackageRequest.includedInRate`, `RatePriceUpdateRequest.weekdays`, `RatePriceRemovalRequest.weekdays`, `RestrictionUpdateRequest` (os quatro campos), `OverbookingLimitRequest.weekdays`, `LimitRequest.unlimited`;
- `ChecklistRoutineRequest.weekdays`, `CreateChecklistTemplateRequest.applicability`, `CreateSchedulePatternRequest.weekdays`.

O valor padrão está só no DTO do servidor, às vezes descrito no texto do campo ("padrão 1", "vazio = todos"). Ele não aparece como `default:` no contrato.

Isso tem dois efeitos no front:

1. **Compilação.** O ogen gera campo opcional como `Option<T?> = Option.Undefined` (ADR 0007, "Forma de uso"). Hoje `deposit: Boolean` sai `Option<Boolean?>`, e chamadas como `ReceivePaymentRequest(deposit = input.deposit, …)` deixam de compilar. Constroem esses schemas o `core` (`GuestsFacade`, `ReservationsFacade`, `BookingsFacade`, `FoliosFacade`, `PaymentsFacade`) e o `demo-seed` (`SdkBookingGateway`, `SdkConfigGateway`, `SdkPendingGateway`, `SdkFolioGateway`).
2. **Sobrescrita silenciosa.** Nos DTOs compartilhados entre criação e alteração, um campo omitido num `PUT` recebe o valor padrão e apaga o valor gravado. O handoff da M6 E20 do servidor cita os casos: `GuestProfileRequest.vip`, `RoomTypeRequest.featureIds` e `displayOrder`, `RoomRequest.featureIds`, `CashPointRequest.blindClose`, `PackageRequest.includedInRate` e `ChecklistRoutineRequest.weekdays`. A separação desses DTOs está na pauta do arquiteto do servidor, sem data.

## Decisão

Nas escritas, o `core` (e o `demo-seed`) **sempre envia explícito** todo campo de requisição que o contrato não marca `required`, mas que tem valor padrão no servidor. Isso vale na criação e na alteração.

1. A fachada manda o valor que a tela decidiu, em `Option.Some(valor)`. Quando a tela não pergunta, ela manda o valor de hoje, o mesmo que o `core` já enviava como campo obrigatório. Numa alteração, ela manda o valor que veio na leitura do recurso.
2. `Option.Undefined` fica para o campo realmente opcional, isto é, "sem valor" (anulável e sem padrão), como já é hoje (por exemplo, `notes`, `tendered`, `nsu`).
3. A regra é verificada por teste da fachada: o corpo enviado traz o campo, e uma alteração reenvia o valor lido.
4. A cada rodada do M0, a análise lista os campos que saíram do `required` ou entraram nele. A etapa mecânica aplica a regra aos que o front já constrói, e as etapas pendentes que vão construir os outros ganham a nota.

## Alternativas descartadas

- **Explícito só na alteração (`PUT`).** Dá menos código na criação, mas são duas regras. Além disso, o resultado da criação passa a depender do padrão do servidor, que pode mudar numa rodada sem aparecer no contrato.
- **Omitir quando o valor é igual ao padrão.** Daria a carga mínima, mas o front teria de manter uma cópia dos padrões, que só existem em texto livre, e ela ficaria defasada a cada M0. Uma divergência apagaria dado sem aviso.
- **Configurar o ogen para gerar esses campos como obrigatórios, ou editar o código gerado.** Fere o ADR 0007: o `sdk` não tem código à mão nem filtro, e o contrato é a fonte.

## Consequências

- O JSON enviado nas escritas que já existem fica igual ao de hoje, byte a byte. A troca na M0 E04 é mecânica: `x` vira `Option.Some(x)`, sem mudança de comportamento.
- Nenhum `PUT` do front apaga dado por omissão. As etapas que vão escrever os DTOs compartilhados ganham a nota: a M5 E01 (o `vip` na edição do perfil) e, na M7, a E01 (UH e tipo), a E03 (pacote), a E07 (ponto de caixa) e a E08 (rotina).
- A regra cobre só campos de requisição. A leitura não muda: um campo opcional na resposta continua tratado como hoje.
- Se o servidor um dia separar os DTOs, ou publicar `default:` no contrato, a regra continua valendo e só fica mais fácil de verificar.
