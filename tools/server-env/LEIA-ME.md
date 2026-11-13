# Zeny Hotel Manager: servidor do ambiente de demonstração (Windows)

Este pacote sobe o servidor do Zeny (commit `@SERVER_COMMIT@`, imagem `zeny-hotel-manager:@SERVER_TAG@`) no Docker Desktop, como o projeto compose `zeny-front`. Todos os comandos são PowerShell, a partir da pasta deste pacote.

Atenção: o servidor fala HTTP puro. Use apenas em rede confiável. Nunca use `-p zeny` nos comandos (esse é o projeto de desenvolvimento do servidor); use sempre `-p zeny-front`.

## 0. Pré-requisitos

- Docker Desktop com Compose v2.24.4 ou mais novo (`docker compose version`), por causa do `!override` do arquivo `compose.override.yaml`.
- JDK 25, obrigatório para o app e para o `demo-seed` (com um Java mais antigo eles não abrem). Confira com `java -version`.

## 1. Primeira vez (com internet)

Gere o `.env` sem precisar de bash no Windows (substitua `<IP do Windows>` pelo IP desta máquina na rede). O endereço de escuta `0.0.0.0` já é o padrão do `compose.override.yaml` deste pacote:

```powershell
docker run --rm -v "${PWD}:/w" -w /w alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f sh -c "apk add --no-cache bash openssl >/dev/null && bash gen-env.sh --public-endpoint http://<IP do Windows>:8088 .env"
```

Guarde uma cópia do `.env` fora da pasta do pacote: a chave de cifragem e a senha root do MinIO valem enquanto o volume existir, e perdê-las inutiliza os dados.

Construa a imagem e suba o servidor:

```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env build
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env up -d
```

Espere o readiness repetindo o comando abaixo até aparecer `{"status":"UP"}` (o `Invoke-RestMethod` lança exceção no 503 enquanto o servidor sobe, por isso `curl.exe`):

```powershell
curl.exe -fsS http://localhost:8088/actuator/health/readiness
```

Login do admin: usuário `ZENY_ADMIN_LOGIN` e senha `ZENY_ADMIN_PASSWORD`, ambos no `.env`. A troca de senha é obrigatória no primeiro login.

### Sem internet

Só depois de já ter um `.env`. Carregue as imagens e suba sem `build`:

```powershell
docker load -i images\zeny-server-images.tar
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env up -d
```

## 2. Atualizar

1. Extraia o pacote novo numa pasta nova.
2. Copie o `.env` antigo para a pasta nova.
3. Compare os nomes das variáveis do `.env` com os do `.env.example` novo e acrescente ao `.env` as que faltarem.
4. Rode `build` e `up -d` (comandos da seção 1).

## 3. Parar e recriar a demo

Parar sem apagar nada (os dados da demo continuam nos volumes):

```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env down
```

Recriar a demo do zero. Atenção: `down -v` apaga banco e arquivos da demo.


```powershell
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env down -v
docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env up -d
```

Depois de recriar, popule os dados com o `demo-seed.jar`, que já vem na pasta `app\` deste pacote. Depois do `down -v`, a senha inicial do administrador volta a ser a `ZENY_ADMIN_PASSWORD` do `.env`, mesmo que uma execução anterior a tenha trocado.

O seed lê os segredos de variáveis de ambiente (`$env:ZENY_SEED_ADMIN_PASSWORD` e as demais abaixo), nunca da linha de comando, que fica na lista de processos. O bloco abaixo pede cada valor numa janela de credencial ou num prompt sem eco. Não digite o valor numa linha do PowerShell (por exemplo, `$env:… =` com a senha): o PowerShell 5.1 grava em texto puro cada linha digitada no histórico (`ConsoleHost_history.txt`). Senhas têm de 12 a 128 caracteres; o PIN do Gerente, de 4 a 8 dígitos. Escolha os seus próprios valores; em cada janela, só a senha importa (o nome do usuário é só um rótulo):

```powershell
[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_PASSWORD", (Get-Credential -UserName administrador -Message "Senha atual do administrador").GetNetworkCredential().Password, "Process")
[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_NEW_PASSWORD", (Get-Credential -UserName administrador -Message "Senha nova do administrador").GetNetworkCredential().Password, "Process")
[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PASSWORD", (Get-Credential -UserName gerente -Message "Senha do Gerente").GetNetworkCredential().Password, "Process")
[Environment]::SetEnvironmentVariable("ZENY_SEED_RECEPTIONIST_PASSWORD", (Get-Credential -UserName recepcao -Message "Senha da Recepção").GetNetworkCredential().Password, "Process")
[Environment]::SetEnvironmentVariable("ZENY_SEED_HOUSEKEEPER_PASSWORD", (Get-Credential -UserName governanta -Message "Senha da Governança").GetNetworkCredential().Password, "Process")
$pin = Read-Host -AsSecureString "PIN do Gerente"
[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PIN", [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pin)), "Process")
Remove-Variable pin
java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login <login>
```

- Troque `<login>` pelo `ZENY_ADMIN_LOGIN` do `.env`.
- Se você cancelar uma janela de credencial, aparece um erro em vermelho e aquela variável não é definida: rode de novo só a linha dela antes do `java -jar`.
- Ao terminar, feche este PowerShell ou apague as variáveis com `Remove-Item Env:ZENY_SEED_*`.
- A primeira execução troca a senha inicial do administrador (que o servidor marca como provisória) para `ZENY_SEED_ADMIN_NEW_PASSWORD`. Nas seguintes, o programa entra direto com a senha nova.
- Se não houver senha nova a definir, **omita** `ZENY_SEED_ADMIN_NEW_PASSWORD` (`Remove-Item Env:ZENY_SEED_ADMIN_NEW_PASSWORD`); não a deixe vazia.
- Cuidado com tentativas erradas: senha do administrador errada repetida gasta as tentativas e bloqueia a conta; se a primeira execução falhar por senha, confira os valores antes de repetir.
- Repetir a execução é seguro: o que já existe é conferido e mantido, e a saída traz só contagens (criados e já existentes), sem nomes nem documentos de hóspedes.
- Ajuda: `java -jar app\demo-seed.jar --help`. Códigos de saída: 0 concluído, 1 falha (mensagem em português), 2 argumento inválido.

## 4. Abrir o app

O app (`app\zeny-desktop.jar`) abre com o JDK 25, a partir da pasta deste pacote:

```powershell
java -jar app\zeny-desktop.jar
```

Para a demo, abra no modo que mostra no menu todas as áreas do perfil, com "(em breve)" nas que ainda não têm tela. As aspas são necessárias: sem elas, o PowerShell parte o argumento no ponto e o Java não encontra a classe.

```powershell
java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar
```

O PowerShell fica ocupado enquanto a janela do app estiver aberta; fechar a janela devolve o prompt. Na tela inicial, informe o servidor (`http://localhost:8088` nesta máquina) e entre com um usuário da demo. O roteiro da demo está em `roteiro-m1.md`, nesta pasta.

## 5. Firewall

Para outras máquinas da rede acessarem a porta 8088, abra o PowerShell como administrador:

```powershell
New-NetFirewallRule -DisplayName "Zeny front 8088" -Direction Inbound -Protocol TCP -LocalPort 8088 -Action Allow -Profile Private -RemoteAddress LocalSubnet
```

A regra só vale em redes da categoria Private. Confira a categoria da rede com `Get-NetConnectionProfile`; se estiver Public, a regra não se aplica e as outras máquinas não alcançam a porta 8088, sem nenhum aviso. Para mudar para Private:

```powershell
Set-NetConnectionProfile -InterfaceAlias <nome> -NetworkCategory Private
```

Para remover a regra:

```powershell
Remove-NetFirewallRule -DisplayName "Zeny front 8088"
```
