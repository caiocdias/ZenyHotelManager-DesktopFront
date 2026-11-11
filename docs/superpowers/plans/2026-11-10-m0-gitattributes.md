# `.gitattributes` do front Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** um clone do front no Windows (`core.autocrlf=true`) mantém o contrato byte a byte e os arquivos lidos por bash e Docker em LF.

**Architecture:** um `.gitattributes` na raiz, com regras explícitas por arquivo e sem `* text=auto`. Um teste de shell simula o checkout do Windows num clone temporário do estado atual da árvore e confere o resultado.

**Tech Stack:** git, bash, o harness `tests/run.sh` e `tests/lib/assert.sh`.

**Spec:** pedido do usuário em 2026-11-10 ("crie o .gitattributes antes do push") e o Menor adiado do handoff da E01 em `docs/roadmaps/roadmap-m0-sincronizacao.md` ("`.gitattributes` (`api/openapi.yaml -text`, `*.sh eol=lf`), recomendado antes de qualquer clone no Windows"). Contexto: as "Restrições e invariantes" do mesmo roadmap e o ADR 0002.

## Global Constraints

- `api/openapi.yaml` é cópia **byte a byte** do contrato no commit fixado, e `contract_sha256` em `api/server-pin.properties` confere com ela.
- O repositório do servidor (`/home/caio/repos/ZenyHotelManager`) é só leitura: os testes não o leem nem o tocam.
- Scripts em bash, sem dependência de Node.
- Documentação e comentários em pt-BR.
- Nenhum blob do índice muda: hoje os 53 arquivos rastreados estão `i/lf` (`git ls-files --eol`), e o commit só acrescenta `.gitattributes` e o teste.

## Review Focus

1. Checkout com `core.autocrlf=true`: o `sha256sum` do `api/openapi.yaml` no checkout precisa continuar igual ao `contract_sha256`.
2. Checkout com `core.autocrlf=true`: nenhum `*.sh`, em qualquer profundidade, pode ter `\r`, porque o bash e o `alpine` do `gen-env.sh` quebram com CRLF.
3. O dotfile `tools/server-env/.env.example` está coberto pela regra (padrões com ponto inicial).
4. Depois do clone, o `git status --porcelain` sai vazio: sem renormalização fantasma.
5. O controle negativo: sem o `.gitattributes`, o mesmo clone gera CRLF, o que prova que o teste exercita a condição.

---

### Task 1: `.gitattributes` e teste de checkout no Windows

Estimativa: 20 min · Depende de: nenhuma · Risco: alto (protege o contrato persistido byte a byte e o checkout no Windows)

**Files:**
- Create: `.gitattributes`
- Create: `tests/test_gitattributes.sh`

**Interfaces:**
- Consumes: `tests/lib/assert.sh` (as funções `assert_eq`, `assert_contains` etc., como nos testes existentes) e `tests/run.sh`, que já descobre `tests/test_*.sh`.
- Produces: o `.gitattributes` com exatamente estas regras e um comentário de política em pt-BR. O comentário diz que a lista é explícita, sem `* text=auto`, e que todo arquivo novo lido por bash ou Docker sem extensão `.sh` entra nela (na M1 E01: `gradlew text eol=lf` e `*.bat text eol=crlf`):

```
api/openapi.yaml -text
*.sh text eol=lf
api/server-pin.properties text eol=lf
tools/server-env/.env.example text eol=lf
tools/server-env/compose.override.yaml text eol=lf
```

- [ ] **Step 1: Write the failing test** `tests/test_gitattributes.sh`, no estilo dos testes existentes, com diretório temporário e limpeza por `trap`:
  - **atributos:** `git check-attr text eol -- <arquivo>` dá `text: unset` para `api/openapi.yaml`. Dá `text: set` e `eol: lf` para `tools/server-drift.sh`, `tools/server-env/gen-env.sh`, `tests/lib/assert.sh`, `api/server-pin.properties`, `tools/server-env/.env.example` e `tools/server-env/compose.override.yaml`.
  - **checkout Windows:** monte um repositório temporário com o **estado atual da árvore**: copie do working tree os arquivos de `git ls-files -z`, filtrando `.gitattributes`, e depois acrescente o `.gitattributes` do working tree, se existir. Assim o teste fica vermelho antes da implementação e verde sem depender de commit. No repositório temporário use `git add -A`; o `.superpowers/sdd/.gitignore`, que se ignora, pode ficar de fora sem prejuízo. Clone com `git clone -c core.autocrlf=true <repo-tmp> <clone>`, que grava o valor no `.git/config` do clone; **não** use `git -c core.autocrlf=true clone`. Confira:
    - `git -C <clone> config core.autocrlf` é `true`;
    - o `sha256sum` de `api/openapi.yaml` no clone é igual ao `contract_sha256` do `api/server-pin.properties` do front;
    - nenhum `*.sh` do clone contém `\r`;
    - `api/server-pin.properties`, `tools/server-env/.env.example` e `tools/server-env/compose.override.yaml` do clone não contêm `\r`;
    - o `git -C <clone> status --porcelain` sai vazio.
  - **controle negativo:** o mesmo clone, montado **sem** `.gitattributes` (filtrado da cópia também depois do commit), tem `\r` em `tools/server-drift.sh`. Isso prova que o `autocrlf` está em vigor no teste.
  - O teste usa `-c user.name=Teste -c user.email=teste@example.com`, como `_fgit` em `tests/lib/fixtures.sh`, e `GIT_CONFIG_GLOBAL=/dev/null`, `GIT_CONFIG_NOSYSTEM=1` e `-c core.attributesFile=/dev/null` (ou `HOME` e `XDG_CONFIG_HOME` no diretório temporário) nos comandos de git do repositório e do clone temporários. Assim, um `core.autocrlf`, `core.eol` ou arquivo de atributos global da máquina não altera o resultado.
  - Termina com `finish`, como os demais testes.

- [ ] **Step 2: Run test to verify it fails**

Run: `bash tests/run.sh tests/test_gitattributes.sh`
Expected: FAIL nos asserts de atributos e de checkout Windows; o controle negativo passa.

- [ ] **Step 3: Create `.gitattributes`** com as regras de "Produces".

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash tests/run.sh tests/test_gitattributes.sh` → PASS. Depois `bash tests/run.sh` → `Resumo: 17 arquivos, 0 falhas, 0 com casos pulados`. Depois `git ls-files --eol` → todos os rastreados `i/lf`, `api/openapi.yaml` com `attr/-text`. Isso prova que `text eol=lf` não muda nenhum blob. **Não** rode `git add --renormalize` antes do commit: ele implica `-u` e põe no índice o que estiver modificado.

- [ ] **Step 5: Commit**

```bash
git add .gitattributes tests/test_gitattributes.sh
git commit -m "Acrescenta o .gitattributes: contrato sem normalização e scripts em LF" -m "Soul: desenvolvedor"
```

- [ ] **Step 6: Verify after commit:** `git add --renormalize . && git diff --cached --quiet && git status --porcelain` → sem saída e exit 0.

## Revisão de arquitetura

- **Veredito:** aprovado com notas (`revisor-arquitetura`, opus).
- **Rodadas:** 1. A revisão inicial bloqueou por dois Importantes: o `--renormalize` antes do commit no Step 4 e o clone sem `core.autocrlf` gravado na config. Os dois foram corrigidos, junto com os Menores 1 a 4.
- **Nota Menor em aberto:** marcar o Menor adiado do `.gitattributes` como resolvido no handoff da E01 e no Histórico do roadmap M0. Fica com o orquestrador, no fechamento.
- **Sem ARCHITECTURE nem spec dedicada:** o plano foi julgado contra o Menor adiado, as invariantes do roadmap M0 e os ADRs 0002 e 0003.
