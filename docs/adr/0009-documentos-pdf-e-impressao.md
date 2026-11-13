# 0009 — Documentos em PDF: visualizador em memória, impressão local agora e fila do servidor depois

- Estado: aceita · 2026-11-13 · decidida pelo usuário em entrevista (as três opções recomendadas), na análise da rodada 2 do M0
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §2, §6.2 e §7 · relacionadas: [ADR 0006](0006-marcos-por-valor-de-demo.md), [ADR 0008](0008-fronteira-core-sdk.md)

## Contexto

O servidor em `57eb5fac5b8a` (M5 E10 e E11 dele) passou a gerar três documentos, cada um com um par de rotas:

| Documento | PDF | Impressão | Permissão |
|---|---|---|---|
| Ficha de registro | `GET /api/v1/reservations/{id}/registration-card/pdf` | `POST …/registration-card/print` | `frontdesk.stays.checkin` |
| Confirmação da reserva | `GET /api/v1/reservations/{id}/confirmation/pdf` | `POST …/confirmation/print` | `reservations.reservations.read` |
| Extrato da conta | `GET /api/v1/folios/{id}/statement/pdf` | `POST …/statement/print` | `billing.folios.read` |

Fatos do contrato (`api/openapi.yaml` em `57eb5fac5b8a`):

- O `GET …/pdf` devolve `application/pdf` e não é guardado em cache pelo servidor. A ficha leva o nome, a nacionalidade, o nascimento, o endereço e os documentos mascarados de cada hóspede. A confirmação leva o titular.
- O `POST …/print` exige `Idempotency-Key` e `{printerId}` de uma impressora ativa. Ele devolve `202` com o job na fila do servidor. Quem imprime é um **agente de impressão**, "cliente na LAN, em repositório próprio" (README do servidor). Esse agente ainda não existe, e o design §2 o deixa fora do desktop.
- Listar impressoras (`GET /api/v1/printers`) exige `printing.jobs.read`. Cadastrar agentes e impressoras exige `printing.agents.manage`, que só o Administrador tem.
- No servidor, o `GET` da ficha não deixa trilha e a impressão deixa (D-E11-10, decidida pelo usuário na revisão humana da M5 E11 dele).

Restrição do design §6.2: nenhum dado de hóspede em log nem em cache de disco.

## Decisão

1. **A ficha de registro entra no M2** como etapa própria (M2 E07), depois do check-in e antes do gate. O visualizador nasce nela e é reaproveitado pela confirmação (M3 E03) e pelo extrato (M4 E02).
2. **Visualizador em memória.** O app baixa o PDF pelo `core` e o renderiza em memória, numa janela ou painel do app, com uma biblioteca de PDF para a JVM. A escolha da biblioteca fica com o construtor, registrada no handoff da M2 E07: a candidata é o Apache PDFBox, e a versão vai no catálogo e nos lockfiles. Nada vai para o disco, salvo por **"Salvar como…"**, ação explícita do usuário com destino escolhido por ele. O app não abre o leitor de PDF do sistema e não usa arquivo temporário.
3. **Impressão local agora, fila do servidor depois.**
   - **Imprimir** (M2 E07 em diante): o app manda o PDF em memória para uma impressora do sistema operacional, pelo diálogo de impressão da JVM. Não grava arquivo, não passa pela fila do servidor e não deixa trilha, como o `GET` do PDF.
   - **Enviar à impressora do hotel** (M7 E12): `POST …/print` com uma impressora ativa de `GET /api/v1/printers`. Entra junto com o cadastro de agentes e impressoras, nas três telas de documento. Só aparece com a permissão efetiva e com pelo menos uma impressora ativa.

## Alternativas descartadas

- **Ficha adiada para o M4**, numa etapa de documentos junto com o extrato. Deixaria o gate do M2 mais provável antes de 17/11. Foi descartada porque a ficha fecha a história do check-in, que o gerente conhece em papel, e o ADR 0006 favorece amplitude.
- **Ficha dentro da M2 E02.** Daria dois objetivos à etapa do check-in.
- **Arquivo temporário aberto no leitor do sistema.** Seria mais rápido de fazer, mas abriria exceção ao §6.2: o arquivo pode sobrar no disco, e o leitor guarda histórico.
- **Só "Salvar como…".** Pareceria um download na demo.
- **Só a fila do servidor.** Seria um caminho único e com trilha, mas na demo nada sairia no papel, porque não há agente.
- **Só a impressão local.** Deixaria sem tela as rotas `POST …/print` e o modelo de impressão central do servidor, que o mobile também usa.

## Consequências

- Entra uma dependência nova de renderização de PDF. O catálogo e os lockfiles mudam no mesmo commit (`CLAUDE.md`, "Stack e comandos").
- O componente de visualização e impressão é do `design`, e o download é uma fachada do `core` (ADR 0008). As features não tocam no `sdk`.
- Há dois caminhos de impressão. O local não deixa trilha no servidor, o que tem o mesmo efeito de baixar o PDF (D-E11-10). O da fila deixa.
- Os rótulos de motivo de viagem e meio de transporte da ficha saem como "(código FNRH)" até a M6 E15 do servidor. O conteúdo do PDF é do servidor, e o front não o corrige.
- As rotas `/api/v1/print-agents/me/*` (esquema `printAgentToken`) são do agente de impressão e ficam fora do desktop na tabela de cobertura do M7.
