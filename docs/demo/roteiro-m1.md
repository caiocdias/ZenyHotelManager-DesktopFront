# Roteiro da demo do M1 (Windows)

Este roteiro mostra o que o M1 entrega: o app desktop abre, conecta ao servidor da demo, entra com perfis diferentes (cada um com o seu menu) e mostra o mapa de UHs mudando ao vivo quando outro cliente altera o status de uma UH.

Antes de começar:

- O pacote da demo está extraído numa pasta do Windows. O `LEIA-ME.md` e este `roteiro-m1.md` ficam na raiz dela; os JARs ficam em `app\`.
- Docker Desktop e JDK 25 instalados (seção "Pré-requisitos" do `LEIA-ME.md`).
- Todos os comandos são do Windows PowerShell 5.1, o que vem com o Windows, rodados a partir da pasta do pacote (`Set-Location <pasta do pacote>`), salvo onde o passo diz outra coisa.
- As senhas dos usuários da demo são as que você escolheu no passo 1, ao rodar o `demo-seed.jar`. Elas nunca vão na linha de comando: o app pede a senha na tela, e o PowerShell, numa janela de credencial.

Os usuários da demo:

| Login | Perfil | Senha |
|---|---|---|
| `recepcao` | Recepcionista | a de `ZENY_SEED_RECEPTIONIST_PASSWORD` |
| `gerente` | Gerente | a de `ZENY_SEED_MANAGER_PASSWORD` |
| `governanta` | Governanta | a de `ZENY_SEED_HOUSEKEEPER_PASSWORD` |

## 1. Recriar a demo

Comece com o servidor recém-criado e os dados da demo frescos.

- **Primeira vez nesta máquina:** siga a seção "Primeira vez (com internet)" do `LEIA-ME.md` (gerar o `.env`, `build`, `up -d` e esperar o readiness). Depois, rode o `demo-seed.jar` como na seção "Parar e recriar a demo" (o bloco com as variáveis `$env:ZENY_SEED_*` e o `java -jar app\demo-seed.jar`). Na primeira vez, a senha inicial do administrador é a `ZENY_ADMIN_PASSWORD` do `.env`.
- **Já usou a demo antes:** siga a seção "Parar e recriar a demo" do `LEIA-ME.md` inteira: `down -v` e `up -d`, espere o readiness com o `curl.exe` da seção "Primeira vez (com internet)" e rode o `demo-seed.jar` com as variáveis `$env:ZENY_SEED_*`.

Logo depois do seed, confira o código de saída:

```powershell
$LASTEXITCODE
```

**Resultado esperado:**

- `$LASTEXITCODE` mostra `0`.
- A saída do seed traz só contagens, uma linha por tipo de item, e termina em `Concluído em N s.` (cerca de 10 s; o tempo varia). Num servidor recém-recriado:

  ```text
  hotel: 1 criados, 0 já existiam
  políticas: 2 criados, 0 já existiam
  tipos de UH: 3 criados, 0 já existiam
  áreas: 3 criados, 0 já existiam
  UHs: 20 criados, 0 já existiam
  status de UH: 16 criados, 0 já existiam
  bloqueios OOO: 1 criados, 0 já existiam
  temporadas: 2 criados, 0 já existiam
  planos: 1 criados, 0 já existiam
  tabelas de preço: 6 criados, 0 já existiam
  usuários: 3 criados, 0 já existiam
  empresas: 2 criados, 0 já existiam
  hóspedes: 12 criados, 0 já existiam
  reservas: 11 criados, 0 já existiam
  alocações: 9 criados, 0 já existiam
  check-ins: 7 criados, 0 já existiam
  Concluído em 9,6 s.
  ```

- Avisos que começam com `SLF4J` antes dessas linhas não são erro.
- Rodar o seed de novo é seguro: as linhas passam a dizer `0 criados, N já existiam`.

Feche esta janela do PowerShell, ou apague as variáveis com `Remove-Item Env:ZENY_SEED_*`, para as senhas não ficarem no ambiente.

## 2. Abrir o app

Num PowerShell na pasta do pacote, abra o app no modo da demo (seção "Abrir o app" do `LEIA-ME.md`):

```powershell
java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar
```

As aspas em `"-Dzeny.devShell=true"` são obrigatórias: sem elas, o PowerShell parte o argumento no ponto e o Java responde que não encontra a classe `.devShell=true`. Esse modo mostra no menu todas as áreas que o perfil pode ver, com "(em breve)" nas que ainda não têm tela. Sem ele, o menu mostra só as áreas com tela (no M1, só a Governança), e os perfis não se distinguiriam.

**Resultado esperado:** abre a janela "Zeny Hotel Manager" com a tela "Entrar no Zeny Hotel Manager" (campos Servidor, Login e Senha, botões "Testar" e "Entrar"). Este PowerShell fica ocupado enquanto a janela estiver aberta; deixe-o assim.

## 3. Configurar o servidor

No campo **Servidor**, digite:

```text
http://localhost:8088
```

Clique em **Testar**.

**Resultado esperado:** aparece "Servidor encontrado (versão …)." abaixo do campo, sem travar a tela. O endereço fica salvo depois do primeiro login aceito; nas próximas aberturas ele já vem preenchido.

## 4. Entrar como Recepcionista

Em **Login**, digite `recepcao`; em **Senha**, a senha da Recepção que você escolheu no seed. Clique em **Entrar**.

**Resultado esperado:**

- No topo: "Pousada Zeny Demonstração", "Data operacional: dd/mm/aaaa" (a data operacional do servidor, a de hoje num servidor recém-recriado), o usuário "Recepção da Demo" e o botão "Sair".
- No menu lateral, exatamente estas áreas, nesta ordem:
  1. Recepção (em breve)
  2. Reservas (em breve)
  3. Contas e caixa (em breve)
  4. Hóspedes (em breve)
  5. Governança
- Não aparece "Gestão".
- No centro: "Selecione uma área no menu." Clicar numa área "(em breve)" não abre nada.

Clique em **Sair**. A tela volta ao login.

## 5. Entrar como Gerente

Em **Login**, digite `gerente`; em **Senha**, a senha do Gerente que você escolheu no seed. Clique em **Entrar**.

**Resultado esperado:**

- No topo: "Pousada Zeny Demonstração", a mesma data operacional, o usuário "Gerente da Demo" e "Sair".
- No menu lateral, exatamente estas áreas, nesta ordem:
  1. Recepção (em breve)
  2. Reservas (em breve)
  3. Contas e caixa (em breve)
  4. Hóspedes (em breve)
  5. Governança
  6. Gestão (em breve)
- A diferença para a Recepcionista é a área "Gestão", que só o Gerente vê.

Continue logado como Gerente para os passos 6 e 7.

## 6. Mapa de UHs

Clique em **Governança** no menu.

**Resultado esperado:**

- O título "Mapa de UHs" com o selo "Ao vivo" ao lado (antes dele pode aparecer "Conectando…" por um instante).
- Os filtros por tipo: "Luxo", "Standard" e "Suíte".
- A legenda de status, com o contador de cada um. Num servidor recém-recriado: "Fora de ordem: 1", "Fora de serviço: 0", "Ocupada: 7", "Suja: 3", "Limpa, aguardando inspeção: 2", "Limpa: 2" e "Inspecionada: 5".
- O quadro com "Andar 1" (UHs 101 a 110) e "Andar 2" (UHs 201 a 210), 20 UHs ao todo. Cada ladrilho mostra o número e o status; as ocupadas levam a governança junto (por exemplo, "Ocupada · Inspecionada"). As UHs 106, 107 e 108 aparecem como "Suja"; a 210, como "Fora de ordem".

Agora:

1. Clique na UH **106**. Abre à direita o painel com "UH 106", "Andar: 1", "Tipo: Standard", "Ocupação: Vaga", "Governança: Suja" e "Sinalização: Nenhuma". Clique em **Fechar**.
2. Clique no filtro **Suja: 3** da legenda. Ficam só as UHs sujas: 106, 107, 108 e a 210 (fora de ordem, mas suja). Clique de novo em **Suja: 3** para tirar o filtro.
3. Clique no filtro de tipo **Suíte**. Ficam só a 209 e a 210, e os contadores passam a contar só as suítes. Clique de novo em **Suíte** para tirar o filtro.

Deixe o mapa aberto, sem filtros, com a UH 106 à vista.

## 7. Mudança de status ao vivo

Com o mapa aberto, a Governanta marca a UH 106 (vaga e suja) como limpa a partir de outro cliente: uma **segunda janela do PowerShell**. O app não recarrega nada; o mapa recebe a mudança do servidor em tempo real.

Abra uma segunda janela do PowerShell (pode ser em qualquer pasta) e rode os blocos abaixo em ordem, na mesma janela. Nos blocos, `curl` não é usado: no Windows PowerShell 5.1, `curl` é outro nome do `Invoke-WebRequest`. Os comandos usam `Invoke-RestMethod` e `Invoke-WebRequest -UseBasicParsing`.

**7.1. Login da Governanta.** O `Get-Credential` abre uma janela de credencial com o usuário `governanta` já preenchido; digite ali a senha da Governança que você escolheu no seed.

```powershell
$base = "http://localhost:8088"
$cred = Get-Credential governanta
$loginBody = @{
    login    = "governanta"
    password = $cred.GetNetworkCredential().Password
    device   = @{ id = [guid]::NewGuid().ToString(); name = "Roteiro M1" }
} | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType "application/json; charset=utf-8" -Body $loginBody
Remove-Variable loginBody
$auth = @{ Authorization = "Bearer $($session.accessToken)" }
$session.passwordChangeRequired
```

**Resultado esperado:** a última linha mostra `False`. Uma mensagem de erro em vermelho com `INVALID_CREDENTIALS` quer dizer senha errada: confira e repita o bloco, com cuidado, porque erros seguidos bloqueiam o usuário (`ACCOUNT_LOCKED`).

**7.2. Achar a UH 106 e ler a versão dela.** O `ETag` da resposta é a versão atual da UH; ele vai intacto, com as aspas, no `If-Match` da mudança.

```powershell
$rooms = Invoke-RestMethod -Uri "$base/api/v1/rooms/status?limit=200" -Headers $auth
$id = ($rooms.items | Where-Object { $_.number -eq "106" }).roomId
$id
$status = Invoke-WebRequest -UseBasicParsing -Uri "$base/api/v1/rooms/$id/status" -Headers $auth
$etag = $status.Headers["ETag"]
$etag
($status.Content | ConvertFrom-Json).housekeepingStatus
```

**Resultado esperado:** três linhas: o id da UH (um UUID, como `0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d`), o `ETag` entre aspas (um número, como `"7"`; o valor varia) e `DIRTY`.

**7.3. Marcar a UH como limpa.** Olhe para o mapa no app ao rodar este bloco.

```powershell
$change = @{ transition = "MARK_CLEAN" } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "$base/api/v1/rooms/$id/housekeeping-status" -Headers @{ Authorization = $auth.Authorization; "If-Match" = $etag } -ContentType "application/json; charset=utf-8" -Body $change
$result | Select-Object number, housekeepingStatus, inspectionPending, version
```

**Resultado esperado:**

- No PowerShell: `106`, `CLEAN`, `False` e uma versão maior que a do `ETag` do bloco 7.2.
- No app, em até 2 segundos e sem recarregar nada: o ladrilho da UH 106 muda de "Suja" para "Limpa"; a legenda passa a "Suja: 2" e "Limpa: 3"; o selo continua "Ao vivo".

**7.4. Voltar a UH para suja (para ensaiar de novo).** Repetir o `MARK_CLEAN` numa UH já limpa não muda nada (o servidor responde sem erro e o mapa fica igual). Para mostrar a mudança outra vez, volte a UH para suja com `MARK_DIRTY`. A versão mudou no bloco 7.3, então o bloco relê o `ETag` antes:

```powershell
$status = Invoke-WebRequest -UseBasicParsing -Uri "$base/api/v1/rooms/$id/status" -Headers $auth
$etag = $status.Headers["ETag"]
$change = @{ transition = "MARK_DIRTY" } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "$base/api/v1/rooms/$id/housekeeping-status" -Headers @{ Authorization = $auth.Authorization; "If-Match" = $etag } -ContentType "application/json; charset=utf-8" -Body $change
$result | Select-Object number, housekeepingStatus, inspectionPending, version
```

**Resultado esperado:** no PowerShell, `106`, `DIRTY`, `False` e uma versão maior; no app, em até 2 segundos, o ladrilho volta a "Suja" e a legenda a "Suja: 3" e "Limpa: 2". Para ensaiar de novo, rode o bloco 7.2 e depois o 7.3.

**7.5. Sair da sessão da Governanta.**

```powershell
(Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/v1/auth/logout" -Headers $auth).StatusCode
```

**Resultado esperado:** `204`. Feche esta segunda janela do PowerShell.

**Se der erro.** O `Invoke-RestMethod` e o `Invoke-WebRequest` lançam exceção em qualquer resposta de erro: aparece uma mensagem em vermelho com o corpo do problema (o `code`), e a variável `$result` fica com o valor anterior. Para ver o código HTTP do último erro:

```powershell
$Error[0].Exception.Response.StatusCode.value__
```

| Código | O que quer dizer | O que fazer |
|---|---|---|
| `412` | A versão do `If-Match` não é mais a da UH: ela mudou depois do bloco 7.2, ou o `ETag` é de antes da mudança anterior. | Rode o bloco 7.2 de novo e repita a mudança. |
| `428` | Faltou o `If-Match` (`$etag` vazio). | Rode o bloco 7.2 de novo e confira que `$etag` mostra um valor entre aspas. |
| `422` | A transição não vale no status atual da UH (`HOUSEKEEPING_TRANSITION_NOT_ALLOWED`); por exemplo, `MARK_CLEAN` numa UH inspecionada. | Confira o status na última linha do bloco 7.2 e use uma UH vaga e suja (106, 107 ou 108). |
| `401` | A sessão venceu (o acesso dura 15 minutos) ou a senha está errada. | Rode o bloco 7.1 de novo e, depois, o 7.2. |

## 8. Sair

No app, clique em **Sair**, no topo.

**Resultado esperado:** a tela volta a "Entrar no Zeny Hotel Manager", com o servidor `http://localhost:8088` e o login `gerente` já preenchidos e a senha em branco. Feche a janela do app: o primeiro PowerShell volta ao prompt. Para parar o servidor sem apagar os dados, use o comando de parar da seção "Parar e recriar a demo" do `LEIA-ME.md`.
