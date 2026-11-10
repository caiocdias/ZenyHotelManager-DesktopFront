# Zeny Hotel Manager: servidor do ambiente de demonstração (Windows)

Este pacote sobe o servidor do Zeny (commit `@SERVER_COMMIT@`, imagem `zeny-hotel-manager:@SERVER_TAG@`) no Docker Desktop, como o projeto compose `zeny-front`. Todos os comandos são PowerShell, a partir da pasta deste pacote.

Atenção: o servidor fala HTTP puro. Use apenas em rede confiável. Nunca use `-p zeny` nos comandos (esse é o projeto de desenvolvimento do servidor); use sempre `-p zeny-front`.

## 0. Pré-requisitos

- Docker Desktop com Compose v2.24.4 ou mais novo (`docker compose version`), por causa do `!override` do arquivo `compose.override.yaml`.
- JDK 25, a partir da M1 (app e `demo-seed`).

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

A partir da M1, popule os dados com `java -jar app\demo-seed.jar ...`.

## 4. Firewall

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
