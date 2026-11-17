# 0011 — Processo acelerado: testador por etapa, mais paralelismo e revisão de segurança adiada

- Estado: revogada em 2026-11-17 pelo [ADR 0012](0012-revoga-o-processo-acelerado.md)
- Relacionadas: [ADR 0006](0006-marcos-por-valor-de-demo.md) · soul `orquestrador` (`~/.claude/agents/second-brain-souls/orquestrador.md`, passos 3, 3a e 5)

## Contexto

A demo é em 17/11 (ADR 0006). Na M0 E03, três tarefas pequenas levaram cerca de 1h30 (22:34 → 00:05 de 15/11, pelo `git log` de `a300a23^1..a300a23^2`). Quase todo esse tempo foi o custo fixo de cada tarefa: desenvolvedor, testador, verificação mecânica e revisão pela `revisor-seguranca`, tudo em série. No fim da etapa ainda entram o testador de etapa, o scanner e a revisão final. O sistema roda só localmente, sem dado real e sem exposição externa até a demo.

## Decisão

Vale neste repositório, a partir de `a300a23`, até que o usuário a revogue:

1. **Testador só por etapa.** Toda tarefa é planejada com `Risco: normal`, então o `testador` não roda por tarefa (passo 3.2 do orquestrador). Ele roda uma vez por etapa, sobre `MERGE_BASE..HEAD`, como no passo 5.
2. **Mais paralelismo.** O plano é escrito para formar ondas: tarefas com arquivos disjuntos (contando testes, configuração de build e lockfiles) e `Depende de:` só quando a dependência é real. O limite continua em 3 tarefas por onda, porque a memória da máquina é curta (`CLAUDE.md`, "daemon disappeared") e o `~/.gradle` é compartilhado com o servidor.
3. **Sem `revisor-seguranca`.** Ela não é despachada nem por tarefa (passo 3.5) nem na revisão final (passo 5). O scanner de dependências também fica adiado, porque julga o estado do HEAD e roda uma vez só, na revisão futura.

O que continua valendo:

- a revisão do plano pelo `revisor-arquitetura` (passo 2);
- a conferência de cada despacho (trailers e `git status` limpo);
- a `check_test_integrity.py` por tarefa e na etapa, com a saída guardada no workspace. Exit 2 para tudo. Apontamentos e avisos não bloqueiam: vão para o ledger e o handoff, à espera da revisão futura;
- a suíte verde por tarefa e por onda e o gate do projeto antes do merge na principal;
- a evidência de cada critério de aceite, que o orquestrador confere sozinho, já que não há revisora para verificar a conformidade com a spec.

**Fechamento.** No lugar da "revisão limpa", a etapa exige suíte verde, gate verde e a evidência de cada critério de aceite. Ela pode fechar `#concluida`, e o handoff registra "revisão de segurança adiada (ADR 0011)" e o intervalo de commits da etapa.

**Como rodar.** Cada roadmap tem um "Prompt acelerado" logo abaixo do "Prompt de execução". O processo completo continua disponível pelo prompt de execução.

**Dívida de revisão.** Tudo o que for feito por esse modo fica marcado para voltar depois, em dois lugares:
- no handoff da etapa, com a linha `Revisão de segurança: adiada (ADR 0011)`;
- na lista "Revisões de segurança adiadas" de `docs/roadmaps/README.md` (regra 8).

O usuário paga a dívida num marco final de análise do projeto. Esse marco despacha a `revisor-seguranca` com o prompt `code-reviewer.md` sobre `a300a23..HEAD` da principal, com o scanner e a `check_test_integrity.py --base a300a23`, e usa a lista para focar nas etapas marcadas. Os achados viram etapas de correção.

## Alternativas descartadas

- **Manter o processo completo.** Não cabe no prazo da demo.
- **Manter a revisora só na revisão final da etapa.** Corta menos tempo do que o usuário pediu, e o risco de adiar é baixo enquanto tudo roda localmente.
- **Subir o limite de onda acima de 3.** Builds Gradle paralelos em vários worktrees esbarram na memória da máquina. O ganho vem de planos com tarefas mais independentes, não de um limite maior.
- **Mudar as souls.** Elas são globais e servem a outros projetos. A exceção é deste repositório e fica neste ADR.

## Consequências

- Cada tarefa vira desenvolvedor → suíte → verificação mecânica → integração. Ninguém revisa o código até a dívida ser paga.
- A conformidade de cada tarefa com a spec passa a depender da evidência que o orquestrador confere. Desvios podem aparecer só na demo ou na revisão futura.
- A revisão futura cobre um intervalo grande de uma vez e tende a gerar uma etapa de correção maior.
- O texto do orquestrador recusa fechar sem "revisão limpa". Esta decisão depende de ele aceitar o `CLAUDE.md` do projeto como instrução do usuário. O prompt da execução deve citar este ADR, e, se o orquestrador recusar, a saída é mudar a soul numa sessão comum.
