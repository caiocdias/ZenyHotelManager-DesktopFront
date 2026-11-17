# 0012 — Revoga o processo acelerado: volta o ciclo padrão das souls

- Estado: aceita · 2026-11-17 · decidida pelo usuário
- Relacionadas: [ADR 0011](0011-processo-acelerado-ate-a-demo.md) (revogado por este) · souls do Second Brain (`99 System/Souls/DESIGN-desempenho.md` e `README.md` do vault; `~/.claude/agents/second-brain-souls/orquestrador.md`, passos 1, 3, 3a e 5)

## Contexto

O ADR 0011 valia "até segunda ordem" do usuário. Esta é a segunda ordem.

Em 2026-11-17 as souls mudaram no Second Brain (`DESIGN-desempenho.md`). O ciclo padrão passou a trazer o que o ADR 0011 buscava:

- o `testador` roda por tarefa só quando ela é `Risco: alto`, e `alto` virou uma lista fechada (autenticação ou autorização; dado pessoal ou segredo; cálculo de valor monetário; migração ou escrita destrutiva de dados; concorrência; parsing de entrada externa não confiável). Ele roda uma vez por etapa, sobre o diff inteiro;
- o plano traz a seção `## Ondas`, e `Depende de:` só vale quando a tarefa usa símbolo ou arquivo que a outra cria;
- o gate do projeto sai de dentro de cada agente e passa a rodar com o orquestrador, nos pontos de integração (o "gate de integração"). Desenvolvedor e testador rodam a verificação da tarefa, pela linha `verificação rápida` do `CLAUDE.md`.

A medição que sustentou a mudança (mesmo documento, §1) mostrou que a `revisor-seguranca` não era a causa da lentidão: ela custa de 1 a 2 minutos por despacho, de 3% a 5% da sessão. O tempo ia para o gate completo rodado dentro do laço de cada agente (227 execuções de `./gradlew build` numa sessão de 37 tarefas) e para o testador em quase toda tarefa. A sessão deste repositório sob o ADR 0011 (16/11) fez a M3 E03 em 2,5 h e a M3 E04 em 2,0 h, e é o dado real que o desenho novo usa como referência.

Pré-condição conferida nesta sessão: o `orquestrador.md` instalado traz o "Gate de integração" (passo 3a). O `osv-scanner` está no `PATH` desta máquina.

Estado do repositório na revogação: M3 E02, E03 e E04 fecharam pelo processo acelerado; a M3 E05 está `#em-andamento`, com a Task 1 integrada (`920dcd2`) e as demais interrompidas.

## Decisão

1. **O ADR 0011 está revogado** a partir do commit que registra este ADR. Nenhuma etapa nova entra pelo processo acelerado.
2. **Vale o ciclo padrão do orquestrador, inteiro:** a `revisor-seguranca` em cada tarefa e na revisão final, o scanner de dependências, o testador por tarefa na lista fechada de `Risco: alto` e uma vez por etapa, os planos com `## Ondas` e o gate de integração. A etapa só fecha `#concluida` com a revisão final limpa e a evidência de cada critério de aceite.
3. **`CLAUDE.md`:** sai a seção "Processo acelerado (até segunda ordem)". A tabela de comandos ganha a linha `verificação rápida`, e a linha `gate do projeto` fica como está. A forma da verificação rápida foi derivada do build real: os cinco módulos são Kotlin/JVM com a tarefa `test`, e a raiz aplica o ktlint e o detekt a cada um.

   ```
   ./gradlew :<módulo>:test --tests '<filtro>' :<módulo>:ktlintCheck :<módulo>:detekt
   ```

   Para scripts de `tools/` e `tests/`, `bash tests/run.sh tests/<arquivo>.sh`. A forma foi validada com `./gradlew -m` nos cinco módulos.
4. **Roadmaps:** a seção "Prompt acelerado" sai de todos. O "Protocolo de execução" e o "Prompt de execução" não mudam, porque já remetem ao orquestrador para o ciclo por tarefa. A regra 8 de `docs/roadmaps/README.md` fica encerrada.
5. **M3 E05, iniciada no processo acelerado:** não é reescrita. O restante segue o processo normal, e o trecho já commitado (`830f296..920dcd2`) entra na lista "Revisões de segurança adiadas". O plano dela não foi alterado.
6. **Dívida de revisão: paga antes do M4.** Decisão do usuário em 2026-11-17, por entrevista, pela opção recomendada. Entra a etapa **M3 E06**, depois da E05: `revisor-seguranca` com o prompt `code-reviewer.md` sobre `a300a23..HEAD`, com o scanner e a `check_test_integrity.py --base a300a23`, focada na lista do README. Os achados Críticos e Importantes são corrigidos nela, pelo ciclo normal. O M4 só começa com a M3 E06 `#concluida`. Este item substitui o parágrafo "Dívida de revisão" do ADR 0011, que deixava o pagamento para um marco final de análise do projeto.

O que fica do ADR 0011, como registro:

- as linhas `Revisão de segurança: adiada (ADR 0011)` nos handoffs das etapas que fecharam por ele;
- a lista "Revisões de segurança adiadas" do README, que é o escopo da dívida até a M3 E06 marcá-la como paga.

## Alternativas descartadas

- **Manter o ADR 0011 até o fim do M3.** A E05 é o gate do marco e a build de demo; fechá-la sem revisão aumentaria a dívida sem ganho de tempo relevante, já que a revisora custa minutos por tarefa.
- **Manter só a parte "sem `revisor-seguranca`".** A medição mostrou que ela não era a causa da lentidão. Sem ela, a conformidade com a spec depende só da evidência que o orquestrador confere.
- **Replanejar a M3 E05 pelo ciclo novo.** O plano é do orquestrador, e a etapa já tem trabalho commitado e trabalho guardado no workspace. O orquestrador novo aceita plano sem `## Ondas` (cada tarefa restante vira uma onda de uma tarefa só).
- **Dar a dívida por paga com as revisões finais das próximas etapas.** A revisão final de uma etapa cobre só o intervalo dela. O código da M3 E03 e da M3 E04 nunca foi lido por uma revisora.
- **Dívida: manter o pagamento no marco final de análise** (o que o ADR 0011 previa). O intervalo `a300a23..HEAD` cresceria com o M4 ao M7, misturando código já revisado, e um achado em reservas custaria mais depois que contas e check-out dependessem dele.
- **Dívida: roadmap próprio entre o M3 e o M4.** Daria um arquivo e uma rodada só para uma etapa. A etapa cabe no roadmap do M3, que é onde está o código devido.

## Consequências

- Cada tarefa volta a ter revisão de código antes de ser integrada, e cada etapa volta a ter scanner e revisão final. A rodada fica mais lenta do que sob o ADR 0011 pelo custo da revisora; a meta do desenho novo (rodada de 3 a 4 etapas em 4 a 6 horas) é projeção e ainda não foi medida neste repositório.
- A verificação rápida usa as tarefas do lint oficial (`ktlintCheck` e `detekt`). O `detektMain` e o `detektTest`, com resolução de tipos, rodam só no `check`, isto é, no gate de integração: um achado deles aparece no gate da onda, não dentro da tarefa.
- O plano da M3 E05 continua com todas as tarefas `Risco: normal` e sem `## Ondas`. Rever o risco pela lista fechada ou acrescentar as ondas fica com o orquestrador.
- O M3 passa a ter seis etapas. A E06 pode mudar código depois do pacote do gate (E05); por isso ela gera o pacote de novo quando corrige algo.
- A E06 é uma etapa incomum: a revisão vem antes do plano, e o tamanho da correção só se conhece depois dela. Se os achados não couberem num plano, o orquestrador para e relata, e as etapas de correção que faltarem são criadas numa sessão do arquiteto.
- Os planos já escritos em `docs/superpowers/plans/` citam o ADR 0011 e ficam como estão: são registro do que foi executado.
