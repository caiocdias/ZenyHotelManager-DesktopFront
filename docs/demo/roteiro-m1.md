# Roteiro da demo do M1 (Windows)

Este roteiro mostra o que o M1 entrega: o app desktop abre, conecta ao servidor da demo, entra com perfis diferentes (cada um com o seu menu) e mostra o mapa de UHs mudando ao vivo quando outro cliente altera o status de uma UH.

Ele vai do zero até o fim da demo, num arquivo só: começa sem nenhum container do Zeny, numa pasta nova, e termina com a demo rodando. Siga os passos na ordem. Cada passo diz o que fazer e o **Resultado esperado**. Se o resultado não bater, pare e veja "Erros comuns", no passo 12.

Como rodar os comandos:

- Todos os comandos são do **Windows PowerShell 5.1**, o que vem com o Windows.
- Cada bloco de código é um comando (ou um grupo de comandos) para copiar e colar no PowerShell, seguido de Enter.
- Onde o bloco abre uma janela ou pede um valor, o texto diz: "cole, Enter, responda a janela". Rode esse bloco sozinho e responda antes de seguir.
- Nenhuma senha e nenhum PIN são digitados numa linha do PowerShell. Eles vão só nas janelas de credencial, no prompt sem eco e na tela do app. O PowerShell grava em texto puro cada linha digitada no histórico.

## 0. Antes de começar

Você precisa de:

- Windows com o **Docker Desktop** instalado e aberto (o ícone da baleia na bandeja diz "Engine running").
- **JDK 25** instalado. Com um Java mais antigo, o app e o seed não abrem.
- Internet, na primeira vez: o servidor é construído a partir do código e baixa as dependências.
- A pasta do pacote da demo (`demo-package`), copiada do notebook. Ela tem as pastas `app`, `images` e `server` e os arquivos `roteiro-m1.md` (este), `LEIA-ME.md`, `gen-env.sh`, `.env.example`, `compose.override.yaml`, `server-pin.properties` e `VERSAO.txt`. Você não precisa abrir o `LEIA-ME.md` para seguir este roteiro.

Abra o Windows PowerShell (menu Iniciar, digite `PowerShell`, clique em "Windows PowerShell") e confira as versões. Primeiro, o Docker Compose:

```powershell
docker compose version
```

**Resultado esperado:** uma linha `Docker Compose version v2.…`, com versão 2.24.4 ou mais nova (por exemplo, `v2.29.1`). Mais antiga que isso, atualize o Docker Desktop.

Depois, o Java:

```powershell
java -version
```

**Resultado esperado:** a primeira linha diz versão `25` (por exemplo, `openjdk version "25.0.1"`). Outra versão, ou `java` não reconhecido: instale o JDK 25.

### Folha de senhas

Antes de qualquer outro passo, escolha os valores abaixo e **anote** cada um fora da pasta do pacote: num gerenciador de senhas ou no papel. Não os grave num arquivo dentro da pasta. Você vai precisar deles nos passos 5, 7, 8 e 10, e de novo se rodar o seed outra vez.

| Item | Para quê | Regra |
|---|---|---|
| 1 | senha nova do administrador | de 12 a 128 caracteres |
| 2 | senha do Gerente (login `gerente`) | de 12 a 128 caracteres |
| 3 | senha da Recepção (login `recepcao`) | de 12 a 128 caracteres |
| 4 | senha da Governança (login `governanta`) | de 12 a 128 caracteres |
| 5 | PIN do Gerente | de 4 a 8 dígitos |

A senha **inicial** do administrador não entra na folha: ela vem do arquivo `.env` (passo 4) e só é usada uma vez, no passo 5, que a troca pela do item 1.

**Resultado esperado:** os 5 itens anotados fora da pasta.

## 1. Pasta nova e nada do projeto no Docker

A demo usa uma pasta só: `C:\zeny-demo`. Todos os comandos deste roteiro usam esse nome.

**Se `C:\zeny-demo` já existe** de uma tentativa anterior, renomeie a antiga, sem apagar nada: ela guarda o `.env` daquela tentativa. Feche o app, as janelas do Explorador de Arquivos e os PowerShell abertos dentro dela. Depois, num PowerShell, rode o bloco abaixo; a primeira linha sai da pasta, porque não dá para renomear a pasta em que o PowerShell está.

```powershell
Set-Location C:\
Rename-Item C:\zeny-demo zeny-demo-antiga
```

**Resultado esperado:** o prompt volta a `PS C:\>` sem mensagem em vermelho, e a pasta antiga passa a se chamar `C:\zeny-demo-antiga`. Uma mensagem em vermelho dizendo que a pasta está em uso quer dizer que algo ainda está aberto nela: feche e rode de novo. Se `C:\zeny-demo-antiga` também já existe, renomeie-a antes no Explorador de Arquivos, com qualquer outro nome. Se `C:\zeny-demo` não existe, pule este bloco.

Crie a pasta nova e vazia `C:\zeny-demo` e copie para dentro dela **todo o conteúdo** da pasta do pacote: as pastas `app`, `images` e `server` e os arquivos.

No PowerShell, entre na pasta:

```powershell
Set-Location C:\zeny-demo
```

**Resultado esperado:** o prompt passa a mostrar `PS C:\zeny-demo>`. Todos os comandos dos passos 1 a 6 rodam a partir desta pasta.

Confira o conteúdo:

```powershell
Get-ChildItem -Name
```

**Resultado esperado:** a lista tem `app`, `images`, `server`, `.env.example`, `compose.override.yaml`, `gen-env.sh`, `LEIA-ME.md`, `roteiro-m1.md`, `server-pin.properties` e `VERSAO.txt`. Não pode ter `.env` (sem `.example`): se tiver, a pasta não é nova. Nesse caso, volte ao começo deste passo: o bloco que renomeia a pasta para `zeny-demo-antiga` sai dela antes, e depois você cria a `C:\zeny-demo` de novo.

Agora confira que o Docker não tem nada do projeto `zeny-front`. Primeiro, os projetos:

```powershell
docker compose ls -a
```

**Resultado esperado:** só o cabeçalho `NAME   STATUS   CONFIG FILES`, ou linhas de outros projetos, mas **nenhuma** linha `zeny-front`.

Depois, os volumes (onde ficam o banco e os arquivos da demo):

```powershell
docker volume ls --filter label=com.docker.compose.project=zeny-front
```

**Resultado esperado:** só o cabeçalho `DRIVER    VOLUME NAME`, sem nenhum volume.

**Se sobrou algo de uma tentativa anterior** (uma linha `zeny-front` em `docker compose ls -a`, ou só volumes `zeny-front_…`), limpe pelo nome do projeto. Este comando funciona sem o `.env` e sem os arquivos do compose, e **apaga os dados da demo anterior** (banco e arquivos):

```powershell
docker compose -p zeny-front down -v
```

**Resultado esperado:** linhas `Container zeny-front-…  Removed`, `Volume zeny-front_…  Removed` e `Network zeny-front_default  Removed` (só as do que existia). Rode de novo as duas conferências acima: as duas têm de vir vazias. Se nada sobrou, pule este comando.

## 2. Gerar o `.env`

O `.env` guarda os segredos do servidor: chaves de cifragem, a senha inicial do administrador e as senhas internas. O comando abaixo o gera dentro de um container Alpine, sem precisar de bash no Windows. Ele usa o endereço `http://localhost:8088`, para a demo nesta máquina.

```powershell
docker run --rm -v "${PWD}:/w" -w /w alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f sh -c "apk add --no-cache bash openssl >/dev/null && bash gen-env.sh --public-endpoint http://localhost:8088 .env"
```

**Resultado esperado:** na primeira vez, o Docker baixa a imagem do Alpine (linhas `Pulling from library/alpine` e `Downloaded newer image`). No fim, aparece `Gerado .env (segredos: ZENY_ENCRYPTION_KEYS, ZENY_JWT_KEYS, ZENY_ADMIN_PASSWORD, ZENY_STORAGE_ROOT_PASSWORD, ZENY_STORAGE_SECRET_KEY)`. Se aparecer também `gen-env: aviso: não foi possível aplicar chmod 600`, não é erro no Windows.

Guarde uma cópia do `.env` fora da pasta (num pendrive ou no gerenciador de senhas). A chave de cifragem e as senhas internas valem enquanto os volumes existirem; perder o `.env` inutiliza os dados da demo.

Para acessar o servidor de outra máquina da rede (não é preciso nesta demo): abra o `.env` no Bloco de Notas, troque só a linha `ZENY_STORAGE_PUBLIC_ENDPOINT` para o IP desta máquina (`http://<IP do Windows>:8088`), salve, rode de novo o `up -d` do passo 3 e libere a porta 8088 no firewall do Windows. **Nunca** gere o `.env` de novo com `--force` com os volumes existentes: as chaves novas não abrem os dados antigos.

## 3. Construir e subir o servidor

Construa a imagem do servidor:

```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env build
```

**Resultado esperado:** muitas linhas de progresso. Na primeira vez, leva vários minutos (10 ou mais, conforme a máquina e a internet). Termina sem `ERROR` nem `failed to solve`, com uma linha `naming to docker.io/library/zeny-hotel-manager:…` ou `Built`.

Suba o servidor:

```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env up -d
```

**Resultado esperado:** linhas `Network zeny-front_default  Created`, `Volume "zeny-front_…"  Created` e `Container zeny-front-…` com `Started` ou `Healthy`, sem `Error`. O prompt volta.

Espere o servidor ficar pronto. Rode o comando abaixo e repita a cada 15 segundos até aparecer `{"status":"UP"}`. É `curl.exe`, com `.exe`: no Windows PowerShell 5.1, `curl` sozinho é outro comando.

```powershell
curl.exe -fsS http://localhost:8088/actuator/health/readiness
```

**Resultado esperado:** enquanto o servidor sobe, aparece `curl: (7) Failed to connect …` ou `curl: (22) The requested URL returned error: 502` (ou `503`); isso é normal. Em 1 a 3 minutos (o tempo varia), aparece `{"status":"UP"}`.

## 4. Login e senha inicial do administrador

Mostre só as duas linhas do administrador do `.env`:

```powershell
Select-String -Path .env -Pattern "^ZENY_ADMIN_(LOGIN|PASSWORD)="
```

**Resultado esperado:** duas linhas (os números depois de `.env:` podem variar):

```text
.env:46:ZENY_ADMIN_LOGIN=admin
.env:47:ZENY_ADMIN_PASSWORD=<senha inicial, 24 caracteres>
```

O login do administrador é o que vem depois do sinal de igual na linha `ZENY_ADMIN_LOGIN` (normalmente `admin`). A senha inicial é tudo o que vem depois do sinal de igual na linha `ZENY_ADMIN_PASSWORD`. Ela vai na primeira janela do passo 5; deixe esta tela à vista até lá. Para copiá-la, selecione com o mouse só o valor e clique com o botão direito (isso copia).

## 5. Popular a demo

O `demo-seed.jar` cria o hotel, as UHs, os usuários `gerente`, `recepcao` e `governanta`, as reservas e os check-ins da demo. Ele lê as senhas de variáveis de ambiente deste PowerShell, que você define pelas janelas abaixo, uma por vez.

Em cada janela, o nome do usuário já vem preenchido e é só um rótulo: só a senha importa. Se você cancelar uma janela, aparece um erro em vermelho; rode de novo só aquela linha.

**5.1. Senha atual do administrador.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Na janela "Senha atual do administrador", cole a senha inicial do passo 4.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_PASSWORD", (Get-Credential -UserName administrador -Message "Senha atual do administrador").GetNetworkCredential().Password, "Process")
```

**Resultado esperado:** abre a janela de credencial do Windows PowerShell com o usuário `administrador`. Depois de colar a senha e clicar em OK, a janela fecha e o prompt volta sem mensagem em vermelho.

**5.2. Senha nova do administrador.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Na janela "Senha nova do administrador", digite o item 1 da folha.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_NEW_PASSWORD", (Get-Credential -UserName administrador -Message "Senha nova do administrador").GetNetworkCredential().Password, "Process")
```

**Resultado esperado:** a janela fecha e o prompt volta sem mensagem em vermelho.

**5.3. Senha do Gerente.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Na janela "Senha do Gerente", digite o item 2 da folha.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PASSWORD", (Get-Credential -UserName gerente -Message "Senha do Gerente").GetNetworkCredential().Password, "Process")
```

**Resultado esperado:** a janela fecha e o prompt volta sem mensagem em vermelho.

**5.4. Senha da Recepção.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Na janela "Senha da Recepção", digite o item 3 da folha.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_RECEPTIONIST_PASSWORD", (Get-Credential -UserName recepcao -Message "Senha da Recepção").GetNetworkCredential().Password, "Process")
```

**Resultado esperado:** a janela fecha e o prompt volta sem mensagem em vermelho.

**5.5. Senha da Governança.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Na janela "Senha da Governança", digite o item 4 da folha.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_HOUSEKEEPER_PASSWORD", (Get-Credential -UserName governanta -Message "Senha da Governança").GetNetworkCredential().Password, "Process")
```

**Resultado esperado:** a janela fecha e o prompt volta sem mensagem em vermelho.

**5.6. PIN do Gerente.** Rode a linha abaixo sozinha (cole, Enter, responda a janela). Aqui não abre janela: o PowerShell mostra `PIN do Gerente:` e espera. Digite o item 5 da folha e tecle Enter.

```powershell
$pin = Read-Host -AsSecureString "PIN do Gerente"
```

**Resultado esperado:** enquanto você digita, aparecem asteriscos, não os dígitos. Depois do Enter, o prompt volta.

**5.7. Guardar o PIN para o seed.** Este bloco não pede nada; pode colar as duas linhas juntas.

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PIN", [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pin)), "Process")
Remove-Variable pin
```

**Resultado esperado:** o prompt volta sem mostrar nada.

**5.8. Rodar o seed.** O `admin` do fim da linha é o login do passo 4; se lá aparecer outro, troque.

```powershell
java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login admin
```

**Resultado esperado:**

- A saída traz só contagens, uma linha por tipo de item, e termina em `Concluído em N s.` (cerca de 10 s; o tempo varia). Num servidor recém-criado:

  ```text
  hotel: 1 criados, 0 já existiam
  políticas: 2 criados, 0 já existiam
  tipos de UH: 3 criados, 0 já existiam
  áreas: 3 criados, 0 já existiam
  UHs: 20 criados, 0 já existiam
  status de UH: 16 criados, 4 já existiam
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
- Rodar o seed de novo é seguro. Antes da demo, ou se você já fez a demo e a UH 106 voltou a suja no fim do passo 10, nada é criado de novo e as linhas passam a dizer `0 criados, N já existiam`, com duas diferenças: as reservas que já fizeram check-in não contam mais nas alocações, que ficam em `alocações: 0 criados, 2 já existiam`, e a linha `check-ins` não aparece. Se você já fez a demo e voltou aqui com a UH 106 limpa, o seed a devolve a suja e a linha de status mostra `status de UH: 1 criados, 19 já existiam`.
- Se a saída for uma linha só, com uma mensagem de falha, veja "Erros comuns" no passo 12 antes de repetir.

**5.9. Conferir o código de saída.** Logo depois do seed:

```powershell
$LASTEXITCODE
```

**Resultado esperado:** `0`.

**5.10. Apagar as senhas do ambiente.** Rode:

```powershell
Remove-Item Env:ZENY_SEED_*
```

**Resultado esperado:** o prompt volta sem mostrar nada. As senhas saem deste PowerShell. Daqui em diante, a senha do administrador é o item 1 da folha; a inicial do `.env` não vale mais.

## 6. Abrir o app

No mesmo PowerShell, na pasta `C:\zeny-demo`, abra o app no modo da demo:

```powershell
java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar
```

As aspas em `"-Dzeny.devShell=true"` são obrigatórias: sem elas, o PowerShell parte o argumento no ponto e o Java responde que não encontra a classe `.devShell=true`. Esse modo mostra no menu todas as áreas que o perfil pode ver, com "(em breve)" nas que ainda não têm tela. Sem ele, o menu mostra só as áreas com tela (no M1, só a Governança), e os perfis não se distinguiriam.

**Resultado esperado:** abre a janela "Zeny Hotel Manager" com a tela "Entrar no Zeny Hotel Manager" (campos Servidor, Login e Senha, botões "Testar" e "Entrar"). Este PowerShell fica ocupado enquanto a janela estiver aberta; deixe-o assim.

No campo **Servidor**, digite:

```text
http://localhost:8088
```

Clique em **Testar**.

**Resultado esperado:** aparece "Servidor encontrado (versão …)." abaixo do campo, sem travar a tela. O endereço fica salvo depois do primeiro login aceito; nas próximas aberturas ele já vem preenchido.

## 7. Entrar como Recepcionista

Em **Login**, digite `recepcao`; em **Senha**, a senha da Recepção (item 3 da folha). Clique em **Entrar**.

**Resultado esperado:**

- No topo: "Pousada Zeny Demonstração", "Data operacional: dd/mm/aaaa" (a data operacional do servidor, a de hoje num servidor recém-criado), o usuário "Recepção da Demo" e o botão "Sair".
- No menu lateral, exatamente estas áreas, nesta ordem:
  1. Recepção (em breve)
  2. Reservas (em breve)
  3. Contas e caixa (em breve)
  4. Hóspedes (em breve)
  5. Governança
- Não aparece "Gestão".
- No centro: "Selecione uma área no menu." Clicar numa área "(em breve)" não abre nada.

Clique em **Sair**. A tela volta ao login.

## 8. Entrar como Gerente

Em **Login**, digite `gerente`; em **Senha**, a senha do Gerente (item 2 da folha). Clique em **Entrar**.

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

Continue logado como Gerente para os passos 9 e 10.

## 9. Mapa de UHs

Clique em **Governança** no menu.

**Resultado esperado:**

- O título "Mapa de UHs" com o selo "Ao vivo" ao lado (antes dele pode aparecer "Conectando…" por um instante).
- Os filtros por tipo: "Luxo", "Standard" e "Suíte".
- A legenda de status, com o contador de cada um. Num servidor recém-criado: "Fora de ordem: 1", "Fora de serviço: 0", "Ocupada: 7", "Suja: 3", "Limpa, aguardando inspeção: 2", "Limpa: 2" e "Inspecionada: 5".
- O quadro com "Andar 1" (UHs 101 a 110) e "Andar 2" (UHs 201 a 210), 20 UHs ao todo. Cada ladrilho mostra o número e o status; as ocupadas levam a governança junto (por exemplo, "Ocupada · Inspecionada"). As UHs 106, 107 e 108 aparecem como "Suja"; a 210, como "Fora de ordem".

Agora:

1. Clique na UH **106**. Abre à direita o painel com "UH 106", "Andar: 1", "Tipo: Standard", "Ocupação: Vaga", "Governança: Suja" e "Sinalização: Nenhuma". Clique em **Fechar**.
2. Clique no filtro **Suja: 3** da legenda. Ficam só as UHs sujas: 106, 107, 108 e a 210 (fora de ordem, mas suja). Clique de novo em **Suja: 3** para tirar o filtro.
3. Clique no filtro de tipo **Suíte**. Ficam só a 209 e a 210, e os contadores passam a contar só as suítes. Clique de novo em **Suíte** para tirar o filtro.

Deixe o mapa aberto, sem filtros, com a UH 106 à vista.

## 10. Mudança de status ao vivo

Com o mapa aberto, a Governanta marca a UH 106 (vaga e suja) como limpa a partir de outro cliente: uma **segunda janela do PowerShell**. O app não recarrega nada; o mapa recebe a mudança do servidor em tempo real.

Abra uma segunda janela do PowerShell (pode ser em qualquer pasta) e rode os blocos abaixo em ordem, na mesma janela. Nos blocos, `curl` não é usado: no Windows PowerShell 5.1, `curl` é outro nome do `Invoke-WebRequest`. Os comandos usam `Invoke-RestMethod` e `Invoke-WebRequest -UseBasicParsing`.

**10.1. Login da Governanta.** Primeiro, a senha. Rode a linha abaixo sozinha (cole, Enter, responda a janela): o `Get-Credential` abre uma janela de credencial com o usuário `governanta` já preenchido; digite ali a senha da Governança (item 4 da folha). Ela fica num bloco próprio porque, colada junto com outras linhas, o `Get-Credential` lê a linha seguinte como resposta. Se você cancelar a janela, aparece um erro em vermelho e `$cred` fica vazio: rode esta linha de novo.

```powershell
$cred = Get-Credential governanta
```

**Resultado esperado:** a janela fecha e o prompt volta sem mensagem em vermelho.

Depois, cole o bloco abaixo inteiro e tecle Enter. Ao ler o `@{` sem o `}`, o PowerShell passa a mostrar `>>` no começo das linhas seguintes: é o esperado, porque o comando continua na linha seguinte. Nesse modo, ele só roda o que recebeu quando chega uma linha vazia; se, depois de colar, o cursor ficar parado num `>>`, tecle Enter mais uma vez.

```powershell
$base = "http://localhost:8088"
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

**Resultado esperado:** a última linha mostra `False`. Uma mensagem de erro em vermelho com `INVALID_CREDENTIALS` quer dizer senha errada: confira e repita os dois blocos do 10.1, com cuidado, porque erros seguidos bloqueiam o usuário (`ACCOUNT_LOCKED`).

**10.2. Achar a UH 106 e ler a versão dela.** O `ETag` da resposta é a versão atual da UH; ele vai intacto, com as aspas, no `If-Match` da mudança. O `[string]` garante um texto só (no PowerShell 7 o cabeçalho vem como lista); não corte nem tire as aspas do valor.

```powershell
$rooms = Invoke-RestMethod -Uri "$base/api/v1/rooms/status?limit=200" -Headers $auth
$id = ($rooms.items | Where-Object { $_.number -eq "106" }).roomId
$id
$status = Invoke-WebRequest -UseBasicParsing -Uri "$base/api/v1/rooms/$id/status" -Headers $auth
$etag = [string]$status.Headers["ETag"]
$etag
($status.Content | ConvertFrom-Json).housekeepingStatus
```

**Resultado esperado:** três linhas: o id da UH (um UUID, como `0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d`), o `ETag` entre aspas (um número, como `"7"`; o valor varia) e `DIRTY`.

**10.3. Marcar a UH como limpa.** Olhe para o mapa no app ao rodar este bloco.

```powershell
$change = @{ transition = "MARK_CLEAN" } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "$base/api/v1/rooms/$id/housekeeping-status" -Headers @{ Authorization = $auth.Authorization; "If-Match" = $etag } -ContentType "application/json; charset=utf-8" -Body $change
$result | Select-Object number, housekeepingStatus, inspectionPending, version
```

**Resultado esperado:**

- No PowerShell: `106`, `CLEAN`, `False` e uma versão maior que a do `ETag` do bloco 10.2.
- No app, em até 2 segundos e sem recarregar nada: o ladrilho da UH 106 muda de "Suja" para "Limpa"; a legenda passa a "Suja: 2" e "Limpa: 3"; o selo continua "Ao vivo".

**10.4. Voltar a UH para suja (para ensaiar de novo).** Cada mudança muda a versão da UH, e o `ETag` lido antes dela deixa de valer: qualquer mudança nova precisa do `ETag` relido (bloco 10.2), ou o servidor responde `412`. Mesmo com o `ETag` relido, repetir o `MARK_CLEAN` numa UH já limpa não muda nada (o servidor responde sem erro e o mapa fica igual). Para mostrar a mudança outra vez, volte a UH para suja com `MARK_DIRTY`; o bloco abaixo relê o `ETag` antes:

```powershell
$status = Invoke-WebRequest -UseBasicParsing -Uri "$base/api/v1/rooms/$id/status" -Headers $auth
$etag = [string]$status.Headers["ETag"]
$change = @{ transition = "MARK_DIRTY" } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "$base/api/v1/rooms/$id/housekeeping-status" -Headers @{ Authorization = $auth.Authorization; "If-Match" = $etag } -ContentType "application/json; charset=utf-8" -Body $change
$result | Select-Object number, housekeepingStatus, inspectionPending, version
```

**Resultado esperado:** no PowerShell, `106`, `DIRTY`, `False` e uma versão maior; no app, em até 2 segundos, o ladrilho volta a "Suja" e a legenda a "Suja: 3" e "Limpa: 2". Para ensaiar de novo, rode o bloco 10.2 (que relê o `ETag`) e só depois o 10.3; sem reler, o 10.3 recebe `412`.

**10.5. Sair da sessão da Governanta.**

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
| `412` | A versão do `If-Match` não é mais a da UH: ela mudou depois do bloco 10.2, ou o `ETag` é de antes da mudança anterior. | Rode o bloco 10.2 de novo e repita a mudança. |
| `403` | O usuário não pode mudar o status da UH: falta a permissão `rooms.status.manage` (por exemplo, o login do bloco 10.1 foi trocado para `recepcao`, que só lê o mapa). | Rode o bloco 10.5 para sair, depois os dois blocos do 10.1 de novo com `governanta`, e então o 10.2. |
| `428` | Faltou o `If-Match` (`$etag` vazio). | Rode o bloco 10.2 de novo e confira que `$etag` mostra um valor entre aspas. |
| `422` | A transição não vale no status atual da UH (`HOUSEKEEPING_TRANSITION_NOT_ALLOWED`); por exemplo, `MARK_CLEAN` numa UH inspecionada. | Confira o status na última linha do bloco 10.2 e use uma UH vaga e suja (106, 107 ou 108). |
| `401` | A sessão venceu (o acesso dura 15 minutos) ou a senha está errada. | Rode os dois blocos do 10.1 de novo e, depois, o 10.2. |

## 11. Sair

No app, clique em **Sair**, no topo.

**Resultado esperado:** a tela volta a "Entrar no Zeny Hotel Manager", com o servidor `http://localhost:8088` e o login `gerente` já preenchidos e a senha em branco. Feche a janela do app: o primeiro PowerShell volta ao prompt. A demo terminou; o passo 12 diz como parar o servidor e como recomeçar.

## 12. No fim

Os comandos deste passo rodam no PowerShell, na pasta `C:\zeny-demo` (`Set-Location C:\zeny-demo`, se abrir um novo).

### Parar sem apagar

Para o servidor e mantém os dados da demo nos volumes:

```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env down
```

**Resultado esperado:** linhas `Container zeny-front-…  Removed` e `Network zeny-front_default  Removed`, sem nenhuma linha `Volume`. Para voltar depois, rode o `up -d` e o `curl.exe` do passo 3 (sem o `build`) e siga do passo 6; as senhas são as da folha.

### Recomeçar do zero

Apaga o banco e os arquivos da demo e volta ao servidor recém-criado. O comando funciona mesmo sem o `.env` e sem os arquivos do compose:

```powershell
docker compose -p zeny-front down -v
```

**Resultado esperado:** linhas `Container zeny-front-…  Removed`, `Volume zeny-front_…  Removed` e `Network zeny-front_default  Removed`. As duas conferências do passo 1 (`docker compose ls -a` e `docker volume ls …`) voltam vazias.

Depois, siga do passo 3 em diante, na mesma pasta e com o mesmo `.env` (não rode o passo 2 de novo). A senha inicial do administrador volta a ser a `ZENY_ADMIN_PASSWORD` do `.env`: é ela que vai na janela "Senha atual do administrador" no passo 5. A senha nova da rodada anterior não vale mais; usá-la ali gasta tentativas e pode bloquear o administrador. Você pode reusar a mesma folha ou escolher valores novos.

### Esqueci uma senha

**Senha de um usuário (`gerente`, `recepcao` ou `governanta`) ou o PIN do Gerente:** escolha um valor novo, anote na folha e rode o passo 5 de novo, num PowerShell na pasta `C:\zeny-demo`, com duas diferenças:

- na janela "Senha atual do administrador" (5.1), digite a senha atual do administrador, que é o item 1 da folha (a inicial do `.env` já foi trocada);
- **pule** o 5.2 (a senha nova do administrador). Se a variável dela ficou definida neste PowerShell, apague-a antes com `Remove-Item Env:ZENY_SEED_ADMIN_NEW_PASSWORD`.

Nas janelas dos usuários e no PIN, use os valores da folha, com o novo no lugar do esquecido. O seed redefine as senhas dos três usuários e o PIN do Gerente para os valores dados. A saída mostra `usuários: 0 criados, 3 já existiam` e o resto como em "Rodar o seed de novo", no 5.8.

**Senha do administrador:** o seed não recupera a senha do administrador, porque precisa dela para entrar. O caminho é "Recomeçar do zero", acima, que apaga os dados da demo. Não fique tentando senhas: cada execução com a senha atual do admin errada gasta até duas tentativas (o seed tenta primeiro a senha nova), e falhas seguidas bloqueiam a conta: a partir da 5ª falha seguida, o bloqueio começa em 1 minuto e cresce a cada nova falha, até 1 hora.

### Erros comuns

| O que aparece | Por quê | O que fazer |
|---|---|---|
| `docker` não é reconhecido, ou `error during connect` | O Docker Desktop está fechado ou ainda iniciando. | Abra o Docker Desktop, espere "Engine running" e repita o comando. |
| `gen-env: .env já existe (use --force para sobrescrever)` | A pasta não é nova: já tem um `.env`. | Volte ao começo do passo 1: renomeie a pasta e crie a `C:\zeny-demo` de novo. Não use `--force`. |
| `port is already allocated` no `up -d` | Outro programa usa a porta 8088. | Feche o outro programa (ou pare o outro projeto do Docker) e repita o `up -d`. |
| O `curl.exe` do passo 3 não chega a `{"status":"UP"}` em 5 minutos | O servidor não subiu. | No Docker Desktop, em Containers, abra `zeny-front` e veja o log do container `api`. |
| `java` não é reconhecido, ou `UnsupportedClassVersionError` | Falta o JDK 25, ou o `java` do PowerShell é de outra versão. | Instale o JDK 25 e abra um PowerShell novo. |
| No seed: `Falta --… (ou ZENY_SEED_…).` | Uma janela do passo 5 foi cancelada, ou o PowerShell é outro. | No mesmo PowerShell, rode de novo só a linha que falta e depois o 5.8. |
| No seed: `Valor inválido em --…: a senha deve ter de 12 a 128 caracteres.` (ou o PIN, de 4 a 8 dígitos) | Um valor da folha não segue a regra. | Ajuste o valor na folha, rode de novo a linha dele e depois o 5.8. |
| No seed: `Login ou senha do administrador recusados.` | A "Senha atual do administrador" (5.1) está errada: na primeira vez é a do `.env` (passo 4); depois, o item 1 da folha. | Confira com calma antes de repetir o 5.1 e o 5.8: cada execução com a senha atual do admin errada gasta até duas tentativas (o seed tenta primeiro a senha nova), e o servidor bloqueia a conta a partir da 5ª falha seguida. Sem a senha, "Recomeçar do zero". |
| No seed: `A conta do administrador está bloqueada por tentativas; espere e tente de novo.` | Senhas erradas seguidas. | Espere alguns minutos (o bloqueio vai de 1 minuto até 1 hora) e repita com a senha certa, ou "Recomeçar do zero". |
| No app, o login recusa a senha | A senha não é a da folha para aquele login. | Confira o item da folha; erros seguidos bloqueiam o usuário. Se esqueceu, veja "Esqueci uma senha". |
