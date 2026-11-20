# 0015 — Tarefas de governança no desktop: Governanta, Gerente e Recepção; a Camareira fica no mobile

- Estado: aceita · 2026-11-20 · decidida pelo usuário em entrevista (a opção recomendada), na análise da rodada 4 do M0
- Design: [spec](../superpowers/specs/2026-11-10-front-desktop-design.md) §1 e §2 · relacionadas: [ADR 0006](0006-marcos-por-valor-de-demo.md)
- Etapas afetadas: M6 E09, E10 e E11 ([roadmap do M6](../roadmaps/roadmap-m6-governanca-operacao.md))

## Contexto

O design §1 registra uma decisão do usuário: o desktop é o **superconjunto** do mobile, e governança e checklists entram. O design §2 deixa fora do desktop o modo offline: a ARCHITECTURE §9 do servidor limita o offline ao app operacional, e o desktop exige conexão.

No servidor em `8c563cda0575`, as tarefas de governança (M6 E01 a E06 e E19 dele) trazem 41 operações novas na tag `housekeeping`. As permissões ficam em `housekeeping/HousekeepingPermissions.kt`, e os modelos de papel são estes:

| Permissão | Gerente | Governanta | Recepção | Camareira |
|---|---|---|---|---|
| `housekeeping.tasks.read` (listar e consultar as tarefas, plano do dia, tópico `housekeeping`) | `ALL` | `ALL` | `ALL` | — |
| `housekeeping.tasks.request` (tarefa avulsa) | `ALL` | `ALL` | `ALL` | — |
| `housekeeping.assignments.manage` (sugerir, ajustar e publicar o plano) | `ALL` | `ALL` | — | — |
| `housekeeping.tasks.execute` (iniciar, pausar, retomar, recusar, tentar de novo, concluir) | `ALL` | `ALL` | — | `OWN` |
| `housekeeping.inspections.perform` | `ALL` | `ALL` | — | — |
| `housekeeping.occurrences.report` (registrar achados e danos) | `ALL` | `ALL` | `ALL` | `OWN` |
| `housekeeping.lostfound.manage` (guarda, devolução, descarte, doação) | `ALL` | `ALL` | `ALL` | — |
| `housekeeping.damages.manage` (revisão, cobrança, dispensa) | `ALL` | — | `ALL` | — |
| `housekeeping.config.read` e `.manage` (tipos de limpeza, faxinas programadas) | `ALL` | `ALL` | — | — |

A decisão D2 da M6 E04 do servidor diz que a Camareira **não lê as tarefas online**. Ela as vê pela resposta das ações e pela fonte offline `housekeeping-tasks` de `/api/v1/sync` (ARCHITECTURE §9 do servidor). As ações online exigem `If-Match`, e sem a lista a Camareira não tem de onde tirar o id nem a versão da tarefa. O próprio handoff do servidor registra que ela não consegue iniciar online a primeira tarefa (decisão (j) da M6 E04 dele).

Portanto, o superconjunto não se cumpre para a Camareira sem uma destas saídas: uma leitura online nova no servidor, ou o protocolo de sincronização no desktop, que o design §2 exclui.

## Decisão

1. No desktop, as tarefas de governança são da **Governanta, do Gerente e da Recepção**, que têm os escopos `ALL` acima. Quando é preciso registrar a execução no desktop, quem a faz é a Governanta ou o Gerente, em nome da operação, com `housekeeping.tasks.execute` `ALL`.
2. Um usuário só com `OWN` em `housekeeping.tasks.execute` (a Camareira) **não vê a área de tarefas** no desktop. O menu segue as permissões efetivas ([ADR 0013](0013-fonte-unica-de-permissoes-por-sessao.md)): sem `housekeeping.tasks.read`, a área não aparece. O registro de achados e danos com `housekeeping.occurrences.report` `OWN` não depende da lista de tarefas: a listagem com `OWN` mostra os registros da própria pessoa. A M6 E11 confere o que a Camareira consegue fazer ali com o modelo padrão (por exemplo, se ela lê as UHs para escolher o local).
3. É uma **exceção registrada** ao superconjunto do design §1, que ganha uma nota remetendo a este ADR. O offline continua fora do desktop (design §2).
4. Nenhum pedido ao servidor é aberto. Se o servidor abrir a leitura online com `OWN`, por conta própria ou por pedido futuro, uma rodada do M0 reavalia este ADR.

## Alternativas descartadas

- **Pedir ao servidor a leitura online das próprias tarefas com `OWN` (`PF-07`).** Manteria o superconjunto, mas a parte da Camareira nasceria `#bloqueada`, à espera de o servidor rever uma decisão que tomou de propósito (D2 da M6 E04 dele).
- **O `core` ler a fonte offline `housekeeping-tasks` só para a Camareira.** Contraria o design §2 e a ARCHITECTURE §9 do servidor, que reservam o sync ao mobile. Também traria ao `core` o protocolo de sincronização (versões, *tombstones*, posse).

## Consequências

- As etapas da governança do M6 (E09 a E11) desenham para a Governanta, o Gerente e a Recepção. Os critérios de aceite usam esses papéis, e a Camareira aparece só no teste de que a área de tarefas não é mostrada.
- O roteiro do M6 não tem passo da Camareira no desktop.
- Uma Camareira que abre o desktop vê só o que as permissões dela mostram.
- O desktop continua cobrindo todo o contrato online da governança. Só a visão da Camareira, que depende do sync, fica no mobile.
