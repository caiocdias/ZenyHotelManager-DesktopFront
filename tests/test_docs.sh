#!/usr/bin/env bash
# Testes da documentação: README cobre todo script executável e os códigos do pré-voo;
# docs/pedidos-servidor.md traz a tabela de controle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

readme="$ROOT/README.md"
pedidos="$ROOT/docs/pedidos-servidor.md"

assert_file "$readme" "README.md existe"
assert_file "$pedidos" "docs/pedidos-servidor.md existe"

readme_text="$(cat "$readme" 2>/dev/null)"
while IFS= read -r script; do
  assert_contains "$readme_text" "$script" "README cita $script"
done < <(cd "$ROOT" && find tools -name '*.sh' -type f -perm -u+x | sort)

for code in 0 10 11 12 2 3; do
  assert_contains "$readme_text" "| $code |" "README tem o código de saída $code"
done

assert_contains "$(cat "$pedidos" 2>/dev/null)" \
  "| PF-NN | título | estado | captura | etapa do front | commit de entrega |" \
  "cabeçalho da tabela de pedidos"

leia="$(cat "$ROOT/tools/server-env/LEIA-ME.md" 2>/dev/null)"
envex="$(cat "$ROOT/tools/server-env/.env.example" 2>/dev/null)"
pkgsh="$(cat "$ROOT/tools/server-env/package.sh" 2>/dev/null)"
assert_not_contains "$readme_text" "gen-env.sh .server/zeny-front.env" "README não manda gerar o env com caminho relativo"
assert_not_contains "$envex" "gen-env.sh .server/zeny-front.env" ".env.example não manda gerar o env com caminho relativo"
assert_contains "$readme_text" "tools/server-env/up.sh" "README manda começar pelo up.sh"
assert_contains "$envex" "tools/server-env/up.sh" ".env.example manda começar pelo up.sh"
assert_contains "$readme_text" "depois do \`package.sh\`" "README: save-images.sh roda depois do package.sh"
assert_contains "$pkgsh" "DEPOIS de package.sh" "images/LEIA-ME.txt: save-images.sh depois do package.sh"
assert_contains "$leia" "alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f" "LEIA-ME: alpine fixado por digest"
assert_contains "$leia" "Parar sem apagar" "LEIA-ME: parar sem apagar"
assert_contains "$leia" "--env-file .env down"$'\n' "LEIA-ME: down sem -v"
assert_contains "$leia" "down -v\` apaga" "LEIA-ME: aviso do down -v"
assert_contains "$leia" "Get-NetConnectionProfile" "LEIA-ME: checar categoria da rede"
assert_contains "$leia" "Set-NetConnectionProfile -InterfaceAlias" "LEIA-ME: ajustar categoria da rede"

# Comandos oficiais: mesmos textos no README e no CLAUDE.md.
claude="$ROOT/CLAUDE.md"
assert_file "$claude" "CLAUDE.md existe"
claude_text="$(cat "$claude" 2>/dev/null)"
official_commands=(
  '| build | `./gradlew build` |'
  '| testes | `./gradlew test` e `bash tests/run.sh` |'
  '| lint | `./gradlew ktlintCheck detekt` |'
  '| run | `./gradlew :app:run` |'
  '| JAR | `./gradlew :app:desktopJar` → `app/build/libs/zeny-desktop.jar` |'
  '| travas | `./gradlew resolveAndLockAll --write-locks` |'
  '| gate do projeto | `./gradlew build` + `bash tests/run.sh` |'
)
for cmd in "${official_commands[@]}"; do
  assert_contains "$readme_text" "$cmd" "README tem o comando: $cmd"
  assert_contains "$claude_text" "$cmd" "CLAUDE.md tem o comando: $cmd"
done
for section in "## Projeto" "## Stack e comandos" "## Git" "## Decisões do usuário"; do
  assert_contains $'\n'"$claude_text"$'\n' $'\n'"$section"$'\n' "CLAUDE.md tem a seção $section"
done
assert_contains "$readme_text" "0007" "README cita a ADR 0007"
assert_contains "$readme_text" "0008" "README cita a ADR 0008"
assert_not_contains "$readme_text" "Ainda não há código do app" "README não diz mais que não há código"

# Roteiro da demo do M1 (E07, Task 5a): um passo a passo só, do zero, numa pasta nova. Passos em ordem,
# comandos do PowerShell 5.1, nenhum segredo e as linhas comuns com o LEIA-ME idênticas.
roteiro="$ROOT/docs/demo/roteiro-m1.md"
leia_file="$ROOT/tools/server-env/LEIA-ME.md"
assert_file "$roteiro" "docs/demo/roteiro-m1.md existe"
rot_text="$(cat "$roteiro" 2>/dev/null)"
# rot_section PREFIXO : do título "## N. ..." até antes do próximo "## " (o prefixo inclui o ponto).
rot_section() { awk -v h="$1" 'index($0, h) == 1 {on = 1; print; next} on && /^## / {exit} on' "$roteiro"; }
# ps_code ARQUIVO : só as linhas dentro de blocos ```powershell.
ps_code() { awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' "$1" 2>/dev/null; }
steps=(
  "## 0. Antes de começar"
  "## 1. Pasta nova e nada do projeto no Docker"
  "## 2. Gerar o \`.env\`"
  "## 3. Construir e subir o servidor"
  "## 4. Login e senha inicial do administrador"
  "## 5. Popular a demo"
  "## 6. Abrir o app"
  "## 7. Entrar como Recepcionista"
  "## 8. Entrar como Gerente"
  "## 9. Mapa de UHs"
  "## 10. Mudança de status ao vivo"
  "## 11. Sair"
  "## 12. No fim"
)
prev=0
for step in "${steps[@]}"; do
  line="$(grep -nFx -- "$step" "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
  if [[ -z "$line" ]]; then
    _assert_fail "roteiro tem o passo: $step"
  elif ((line <= prev)); then
    _assert_fail "roteiro: passo fora de ordem: $step"
  else
    prev="$line"
  fi
done
assert_eq "${#steps[@]}" "$(grep -cE '^## ' "$roteiro" 2>/dev/null)" "roteiro: só os passos 0 a 12 são títulos ##"
for n in 1 2 3 4 5 6 7 8 9 10 11 12; do
  assert_contains "$(rot_section "## $n. ")" "**Resultado esperado" "roteiro: passo $n tem resultado esperado"
done
# Todo bloco PowerShell tem o resultado esperado (ou a tabela de códigos) antes do próximo bloco ou passo.
missing_res="$(awk '
  /^##/ { if (pending) print pending_ln; pending = 0 }
  /^```powershell/ { if (pending) print pending_ln; pending = 1; pending_ln = NR; inb = 1; next }
  /^```/ { inb = 0; next }
  inb { next }
  pending && (/\*\*Resultado esperado/ || /^\| Código \|/) { pending = 0 }
  END { if (pending) print pending_ln }' "$roteiro" 2>/dev/null)"
assert_eq "" "$missing_res" "roteiro: todo bloco PowerShell tem resultado esperado (blocos sem: $missing_res)"

# Passo 0: pré-requisitos e a folha de senhas, antes de qualquer linha que peça segredo.
step0="$(rot_section "## 0. ")"
for word in "docker compose version" "java -version" "JDK 25" "Folha de senhas" "senha nova do administrador" \
  '`gerente`' '`recepcao`' '`governanta`' "PIN do Gerente" "12 a 128" "4 a 8 dígitos" "anote"; do
  assert_contains "$step0" "$word" "roteiro, passo 0: cita $word"
done
folha_ln="$(grep -nF 'Folha de senhas' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
secret_ln="$(grep -nE 'Get-Credential|Read-Host|SetEnvironmentVariable' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
if [[ -z "$folha_ln" || -z "$secret_ln" ]] || ((folha_ln > secret_ln)); then
  _assert_fail "roteiro: a folha de senhas vem antes da primeira linha que pede segredo" \
    "folha: ${folha_ln:-ausente}; segredo: ${secret_ln:-ausente}"
fi

# Passo 1: pasta nova; conferir projeto e volumes; limpar só pelo projeto (sem compose nem .env).
step1="$(rot_section "## 1. ")"
step1_ps="$(awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' <<<"$step1")"
assert_contains "$step1" "pasta nova" "roteiro, passo 1: pasta nova"
for cmd in "docker compose ls -a" "docker volume ls --filter label=com.docker.compose.project=zeny-front" \
  "docker compose -p zeny-front down -v"; do
  grep -qxF -- "$cmd" <<<"$step1_ps" || _assert_fail "roteiro, passo 1: bloco com a linha: $cmd"
done
assert_contains "$step1" "apaga os dados" "roteiro, passo 1: avisa que o down -v apaga os dados"
# Uma pasta só, C:\zeny-demo: a de uma tentativa anterior é renomeada (sem apagar o .env dela), de fora dela,
# antes de entrar na nova; nenhum outro nome de pasta aparece como pasta de trabalho.
assert_eq "" "$(grep -oE 'C:\\zeny-[A-Za-z0-9_-]*' "$roteiro" 2>/dev/null | sort -u | grep -vxF -e 'C:\zeny-demo' -e 'C:\zeny-demo-antiga')" \
  "roteiro: todo caminho C:\\zeny-… é C:\\zeny-demo (ou a antiga renomeada)"
assert_eq "" "$(grep -nF 'zeny-demo-2' "$roteiro" 2>/dev/null)" "roteiro: sem zeny-demo-2"
assert_eq "" "$(grep -n 'zeny-demo-antiga' "$roteiro" 2>/dev/null | cut -d: -f1 | while read -r n; do
  awk -v n="$n" 'NR <= n && /^## / {h = $0} NR == n {print h; exit}' "$roteiro"; done | sort -u | grep -vF '## 1. ')" \
  "roteiro: a zeny-demo-antiga só aparece no passo 1 (o rename)"
rename_block="$(awk '/^```powershell/ {b = ""; inb = 1; next} /^```/ && inb {if (b ~ /Rename-Item/) print b; inb = 0; next} inb {b = b $0 "\n"}' <<<"$step1")"
assert_eq $'Set-Location C:\\\nRename-Item C:\\zeny-demo zeny-demo-antiga' "$rename_block" \
  "roteiro, passo 1: sai da pasta e renomeia a antiga para zeny-demo-antiga"
rename_ln="$(grep -nxF 'Rename-Item C:\zeny-demo zeny-demo-antiga' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
enter_ln="$(grep -nxF 'Set-Location C:\zeny-demo' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
if [[ -z "$rename_ln" || -z "$enter_ln" ]] || ((rename_ln > enter_ln)); then
  _assert_fail "roteiro, passo 1: o rename da pasta antiga vem antes de entrar na nova"
fi
assert_contains "$(grep -F 'Não pode ter `.env`' "$roteiro" 2>/dev/null)" "zeny-demo-antiga" \
  "roteiro, passo 1: com .env na pasta, renomeia para zeny-demo-antiga"

# Passo 2: a nota do endereço troca só o ZENY_STORAGE_PUBLIC_ENDPOINT, sem regerar o .env com --force.
step2="$(rot_section "## 2. ")"
for word in "ZENY_STORAGE_PUBLIC_ENDPOINT" "--force" "fora da pasta" "libere a porta 8088 no firewall do Windows"; do
  assert_contains "$step2" "$word" "roteiro, passo 2: cita $word"
done
# Passo 4: só as duas linhas do administrador.
assert_contains "$(rot_section "## 4. ")" "Select-String" "roteiro, passo 4: mostra o login e a senha por Select-String"
assert_contains "$(rot_section "## 5. ")" "Remove-Item Env:ZENY_SEED_*" "roteiro, passo 5: apaga as variáveis do seed"

# Nenhum passo manda abrir o LEIA-ME: nada de "seção" do LEIA-ME; ele só pode ser citado como dispensável.
assert_eq "" "$(grep -nE '[Ss]e[cç](ão|ões)' "$roteiro" 2>/dev/null)" "roteiro não remete a seção de outro arquivo"
# Onde o LEIA-ME aparece (na lista do pacote, ou como dispensável), nada manda abri-lo nem segui-lo.
assert_contains "$rot_text" 'não precisa abrir o `LEIA-ME.md`' "roteiro diz que o LEIA-ME não é preciso"
assert_eq "" "$(grep -F 'LEIA-ME' "$roteiro" 2>/dev/null | sed 's/não precisa abrir o `LEIA-ME.md`//' |
  grep -iE 'abr[aei]|sig[ao]|siga|vej[ao]|consult|conforme|instru|passo a passo')" \
  "roteiro: nenhuma linha manda abrir ou seguir o LEIA-ME"

# Passo 12: parar, recomeçar do zero e esqueci uma senha (com a ressalva do administrador).
step12="$(rot_section "## 12. ")"
for sub in "### Parar sem apagar" "### Recomeçar do zero" "### Esqueci uma senha" "### Erros comuns"; do
  grep -qxF -- "$sub" <<<"$step12" || _assert_fail "roteiro, passo 12: tem $sub"
done
recomecar="$(awk '/^### Recomeçar do zero$/{on=1; next} on && /^###? /{exit} on' <<<"$step12")"
grep -qxF -- "docker compose -p zeny-front down -v" <<<"$recomecar" ||
  _assert_fail "roteiro, Recomeçar do zero: down -v só pelo projeto"
assert_contains "$recomecar" "passo 3" "roteiro, Recomeçar do zero: volta ao passo 3"
assert_contains "$recomecar" "ZENY_ADMIN_PASSWORD" "roteiro, Recomeçar do zero: senha inicial volta à do .env"
esqueci="$(awk '/^### Esqueci uma senha$/{on=1; next} on && /^###? /{exit} on' <<<"$step12")"
for word in "passo 5" "senha atual" "senha nova" "redefine" "não recupera" "Recomeçar do zero" "bloqueia"; do
  assert_contains "$esqueci" "$word" "roteiro, Esqueci uma senha: cita $word"
done
parar="$(awk '/^### Parar sem apagar$/{on=1; next} on && /^###? /{exit} on' <<<"$step12")"
assert_contains "$parar" "--env-file .env down"$'\n' "roteiro, Parar sem apagar: down sem -v"

# Linhas PowerShell comuns ao roteiro e ao LEIA-ME: idênticas nos dois (as asserções de segurança do LEIA-ME
# valem para o roteiro por igualdade).
compose='docker compose -p zeny-front -f server/docker/compose.yaml -f compose.override.yaml --env-file .env'
shared_lines=(
  'docker run --rm -v "${PWD}:/w" -w /w alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f sh -c "apk add --no-cache bash openssl >/dev/null && bash gen-env.sh --public-endpoint http://localhost:8088 .env"'
  "$compose build"
  "$compose up -d"
  "$compose down"
  'docker compose -p zeny-front down -v'
  'docker compose ls -a'
  'docker volume ls --filter label=com.docker.compose.project=zeny-front'
  'curl.exe -fsS http://localhost:8088/actuator/health/readiness'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_PASSWORD", (Get-Credential -UserName administrador -Message "Senha atual do administrador").GetNetworkCredential().Password, "Process")'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_ADMIN_NEW_PASSWORD", (Get-Credential -UserName administrador -Message "Senha nova do administrador").GetNetworkCredential().Password, "Process")'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PASSWORD", (Get-Credential -UserName gerente -Message "Senha do Gerente").GetNetworkCredential().Password, "Process")'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_RECEPTIONIST_PASSWORD", (Get-Credential -UserName recepcao -Message "Senha da Recepção").GetNetworkCredential().Password, "Process")'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_HOUSEKEEPER_PASSWORD", (Get-Credential -UserName governanta -Message "Senha da Governança").GetNetworkCredential().Password, "Process")'
  '$pin = Read-Host -AsSecureString "PIN do Gerente"'
  '[Environment]::SetEnvironmentVariable("ZENY_SEED_MANAGER_PIN", [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pin)), "Process")'
  'Remove-Variable pin'
  'java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login admin'
)
rot_ps_code="$(ps_code "$roteiro")"
leia_ps_code="$(ps_code "$leia_file")"
for l in "${shared_lines[@]}"; do
  grep -qxF -- "$l" <<<"$rot_ps_code" || _assert_fail "roteiro: bloco PowerShell com a linha comum" "$l"
  grep -qxF -- "$l" <<<"$leia_ps_code" || _assert_fail "LEIA-ME: bloco PowerShell com a linha comum" "$l"
done
# Toda linha de roteiro com docker, curl.exe, SetEnvironmentVariable, $pin ou o java do seed é uma das comuns.
while IFS= read -r l; do
  [[ -n "$l" ]] || continue
  found=0
  for s in "${shared_lines[@]}"; do [[ "$l" == "$s" ]] && found=1 && break; done
  ((found)) || _assert_fail "roteiro: linha de servidor ou seed fora da lista comum com o LEIA-ME" "$l"
done < <(grep -E '^(docker (run|compose -p|compose ls|volume) |curl\.exe |\[Environment\]::SetEnvironmentVariable|\$pin |Remove-Variable pin|java -jar app\\demo-seed)' <<<"$rot_ps_code")
# No roteiro, o seed roda depois dos 6 segredos e o $pin sai da sessão depois de usado.
seed_ln="$(grep -nF 'java -jar app\demo-seed.jar' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
last_set_ln="$(grep -nF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$roteiro" 2>/dev/null | tail -1 | cut -d: -f1)"
assert_eq "6" "$(grep -cF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$roteiro" 2>/dev/null)" "roteiro: 6 segredos do seed"
if [[ -z "$seed_ln" || -z "$last_set_ln" ]] || ((seed_ln < last_set_ln)); then
  _assert_fail "roteiro: o java -jar do seed vem depois dos 6 SetEnvironmentVariable"
fi
pin_set_ln="$(grep -nF 'SetEnvironmentVariable("ZENY_SEED_MANAGER_PIN"' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
pin_rm_ln="$(grep -nxF 'Remove-Variable pin' "$roteiro" 2>/dev/null | head -1 | cut -d: -f1)"
if [[ -z "$pin_set_ln" || -z "$pin_rm_ln" ]] || ((pin_rm_ln < pin_set_ln)); then
  _assert_fail "roteiro: Remove-Variable pin depois do SetEnvironmentVariable do PIN"
fi

for word in recepcao gerente governanta '"-Dzeny.devShell=true"' "http://localhost:8088" MARK_CLEAN MARK_DIRTY \
  If-Match Get-Credential "[guid]::NewGuid().ToString()" "charset=utf-8" "-UseBasicParsing" \
  '$etag = [string]$status.Headers["ETag"]'; do
  assert_contains "$rot_text" "$word" "roteiro cita $word"
done
assert_contains "$rot_ps_code" 'java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar' "roteiro abre o app no modo da demo"
assert_eq "" "$(grep -nE '(^|[^"])-Dzeny\.' <<<"$rot_ps_code")" "roteiro: -Dzeny.* sempre entre aspas"
assert_eq "" "$(grep -E 'Invoke-WebRequest' <<<"$rot_ps_code" | grep -vF -- '-UseBasicParsing')" \
  "roteiro: todo Invoke-WebRequest com -UseBasicParsing"
assert_eq "" "$(grep -E -- '-Body ' <<<"$rot_ps_code" | grep -vF 'charset=utf-8')" "roteiro: todo -Body com charset=utf-8"
assert_eq "" "$(grep -E 'Headers\["ETag"\]' <<<"$rot_ps_code" | grep -vF '[string]$status.Headers["ETag"]')" \
  "roteiro: o ETag sempre com [string], intacto"
# Seed repetido (conferido ao vivo na E07): as reservas com check-in saem das alocações e a linha check-ins some.
rerun="$(grep -F 'Rodar o seed de novo' "$roteiro" 2>/dev/null)"
assert_contains "$rerun" '`alocações: 0 criados, 2 já existiam`' "roteiro: no seed repetido, alocações ficam em 2"
assert_contains "$rerun" 'a linha `check-ins` não aparece' "roteiro: no seed repetido, a linha check-ins some"
# O seed repetido só não cria nada se a UH 106 voltou a suja (senão o status dela é recriado); no passo 5,
# sem remissão aos blocos do passo da mudança ao vivo.
assert_contains "$rerun" "voltou a suja" "roteiro: \"nada é criado de novo\" condicionado à volta da UH 106 a suja"
assert_contains "$rerun" '`status de UH: 1 criados, 19 já existiam`' "roteiro: com a UH 106 limpa, a linha de status mostra 1 criado"
assert_eq "" "$(grep -oE '(^|[^0-9.])10\.[0-9]+' <<<"$rerun")" "roteiro: o seed repetido não remete aos blocos 10.x"
# Senha atual do admin errada: o seed tenta antes a senha nova, então cada execução gasta até duas tentativas.
assert_contains "$rot_text" "cada execução com a senha atual do admin errada gasta até duas tentativas (o seed tenta primeiro a senha nova)" \
  "roteiro: a execução com a senha do admin errada gasta até duas tentativas"
assert_eq "" "$(grep -nE 'cada (erro|senha errada) gasta uma tentativa' "$roteiro" 2>/dev/null)" "roteiro: sem \"gasta uma tentativa\""
# Bloco com Get-Credential ou Read-Host: a linha que pede a credencial é a única do bloco (colada junto com
# outras, ela leria a linha seguinte como resposta), e o parágrafo logo antes diz como rodá-la. No passo 5,
# o parágrafo liga a janela a um item da folha (ou à senha inicial do passo 4).
n_prompt_blocks=0
while IFS=: read -r start end prompt_ln; do
  n_prompt_blocks=$((n_prompt_blocks + 1))
  assert_eq "1" "$((end - start - 1))" "roteiro: o bloco da linha $start (com credencial) tem só a linha da credencial"
  prev="$(awk -v s="$start" 'NR < s' "$roteiro" | awk 'BEGIN{RS=""} {p=$0} END{print p}')"
  assert_contains "$prev" "(cole, Enter, responda a janela)" \
    "roteiro: o bloco da linha $start (com credencial) diz como rodá-lo"
  if sed -n "${prompt_ln}p" "$roteiro" | grep -qE 'SetEnvironmentVariable|Read-Host'; then
    [[ "$prev" == *folha* || "$prev" == *"passo 4"* ]] ||
      _assert_fail "roteiro: o bloco da linha $start diz de onde vem o valor (folha ou passo 4)" "parágrafo: ${prev:0:200}"
  fi
done < <(awk '
  /^```powershell/ { s = NR; p = ""; next }
  /^```/ && s { if (p != "") print s ":" NR ":" p; s = 0; next }
  s && /Get-Credential|Read-Host/ && p == "" { p = NR }
' "$roteiro")
assert_eq "7" "$n_prompt_blocks" "roteiro: 7 blocos com credencial (5 senhas e o PIN no passo 5; a Governanta no 10.1)"
# O bloco do corpo do login (hashtable em várias linhas) avisa que o >> do PowerShell é esperado.
body_start="$(awk '/^```powershell/ { s = NR; next } /^```/ { s = 0; next } s && /\$loginBody = @\{/ { print s; exit }' "$roteiro")"
body_prev="$(awk -v s="${body_start:-1}" 'NR < s' "$roteiro" | awk 'BEGIN{RS=""} {p=$0} END{print p}')"
assert_contains "$body_prev" '`>>`' "roteiro: o parágrafo antes do corpo do login explica o >>"

# Sem segredo nem comando perigoso.
assert_eq "" "$(grep -nE -e '-Password\b' "$roteiro" 2>/dev/null)" "roteiro sem -Password"
assert_eq "" "$(grep -niE 'password[[:space:]]*=[[:space:]]*["'"'"']?[^$<"'"'"'[:space:]]' "$roteiro" 2>/dev/null)" \
  "roteiro sem password= com valor literal"
assert_eq "" "$(grep -niE '"password"[[:space:]]*:[[:space:]]*"[^<$]' "$roteiro" 2>/dev/null)" \
  "roteiro sem \"password\":\"valor\""
assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*[[:space:]]*=[[:space:]]*"?[^<"[:space:]]' "$roteiro" 2>/dev/null)" \
  "roteiro sem valor atribuído a ZENY_SEED_*"
assert_eq "" "$(grep -nE -e '--[a-z-]*(password|pin)([=[:space:]]|$)' "$roteiro" 2>/dev/null)" \
  "roteiro sem senha ou PIN em argumento"
assert_eq "" "$(grep -nE -e '-p zeny( |$|`)' "$roteiro" 2>/dev/null)" "roteiro sem -p zeny"
assert_contains "$rot_ps_code" "Invoke-RestMethod" "roteiro tem blocos PowerShell com os comandos HTTP"
assert_eq "" "$(grep -nE '(^|[^.A-Za-z0-9_-])curl([[:space:]]|$)' <<<"$rot_ps_code")" "bloco PowerShell sem curl (alias), só curl.exe"

# LEIA-ME: remete ao roteiro para a demo; a seção 1 confere e limpa pelo projeto; a nota do endereço.
leia_head="$(awk '/^## /{exit} {print}' "$leia_file" 2>/dev/null)"
assert_contains "$leia_head" 'siga `roteiro-m1.md` do início ao fim' "LEIA-ME: no topo, manda seguir o roteiro na demo"
assert_contains "$leia_head" "referência de operação" "LEIA-ME: no topo, diz que é a referência de operação"
leia_s1="$(awk '/^## 1\. /{on=1; print; next} on && /^## /{exit} on' "$leia_file" 2>/dev/null)"
leia_s1_ps="$(awk '/^```powershell/{inb=1; next} /^```/{inb=0} inb' <<<"$leia_s1")"
for cmd in "docker compose ls -a" "docker volume ls --filter label=com.docker.compose.project=zeny-front" \
  "docker compose -p zeny-front down -v"; do
  grep -qxF -- "$cmd" <<<"$leia_s1_ps" || _assert_fail "LEIA-ME, seção 1: bloco com a linha: $cmd"
done
for word in "ZENY_STORAGE_PUBLIC_ENDPOINT" "--force" "up -d"; do
  assert_contains "$leia_s1" "$word" "LEIA-ME, seção 1: a nota do endereço cita $word"
done

# LEIA-ME do pacote: abrir o app, JDK 25 e o demo-seed.jar já em app\.
assert_contains "$leia" 'java -jar app\zeny-desktop.jar' "LEIA-ME: abrir o app"
assert_contains "$leia" 'java "-Dzeny.devShell=true" -jar app\zeny-desktop.jar' "LEIA-ME: abrir o app no modo da demo"
assert_contains "$leia" "JDK 25" "LEIA-ME: JDK 25"
assert_not_contains "$leia" "copie-o para" "LEIA-ME: o demo-seed.jar já vem em app\\"
assert_contains "$leia" 'java -jar app\demo-seed.jar --server http://localhost:8088 --admin-login' "LEIA-ME: comando do seed"

# Segredos do seed: nenhum valor digitado numa linha (o PSReadLine do 5.1 grava o histórico em texto puro);
# cada senha vem do Get-Credential e o PIN do Read-Host -AsSecureString.
leia_file="$ROOT/tools/server-env/LEIA-ME.md"
for f in "$leia_file" "$roteiro"; do
  n="${f#"$ROOT"/}"
  assert_eq "" "$(grep -nE 'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[A-Z_]*["'"'"']?[[:space:]]*=' "$f" 2>/dev/null)" \
    "$n: nenhuma atribuição a ZENY_SEED_*PASSWORD ou *_PIN"
  assert_eq "" "$(grep -nE 'SetEnvironmentVariable\([^,]*ZENY_SEED_[^,]*,[[:space:]]*["'"'"']' "$f" 2>/dev/null)" \
    "$n: SetEnvironmentVariable de ZENY_SEED_* sem valor literal"
done
for v in ADMIN_PASSWORD ADMIN_NEW_PASSWORD MANAGER_PASSWORD RECEPTIONIST_PASSWORD HOUSEKEEPER_PASSWORD; do
  set_line="$(grep -E "SetEnvironmentVariable\\(\"ZENY_SEED_$v\"," "$leia_file" 2>/dev/null)"
  assert_contains "$set_line" "(Get-Credential " "LEIA-ME: ZENY_SEED_$v vem do Get-Credential"
  assert_contains "$set_line" ".GetNetworkCredential().Password, \"Process\")" "LEIA-ME: ZENY_SEED_$v só no processo"
done
pin_line="$(grep -E 'SetEnvironmentVariable\("ZENY_SEED_MANAGER_PIN",' "$leia_file" 2>/dev/null)"
pin_var="$(grep -oE 'SecureStringToBSTR\(\$[A-Za-z_]+\)' <<<"$pin_line" | sed -E 's/.*\(\$//; s/\)$//')"
if [[ -z "$pin_var" ]]; then
  _assert_fail "LEIA-ME: ZENY_SEED_MANAGER_PIN vem de uma SecureString" "linha: $pin_line"
else
  assert_contains "$(grep -E "^\\\$$pin_var[[:space:]]*=" "$leia_file")" "Read-Host -AsSecureString" \
    "LEIA-ME: o PIN é lido por Read-Host -AsSecureString"
fi
assert_contains "$leia" "ConsoleHost_history.txt" "LEIA-ME explica por que não digitar o valor"
# Uma linha por vez: colado inteiro, o Read-Host e o Get-Credential leriam a linha seguinte do bloco.
one_ln="$(grep -niF 'uma linha por vez (cole, Enter, responda a janela)' "$leia_file" 2>/dev/null | head -1 | cut -d: -f1)"
first_set_ln="$(grep -nF '[Environment]::SetEnvironmentVariable("ZENY_SEED_' "$leia_file" 2>/dev/null | head -1 | cut -d: -f1)"
if [[ -z "$one_ln" || -z "$first_set_ln" ]] || ((one_ln > first_set_ln)); then
  _assert_fail "LEIA-ME manda rodar o bloco do seed uma linha por vez, antes do bloco" "linha da frase: $one_ln; bloco: $first_set_ln"
fi
assert_contains "$leia" "aquela variável não é definida (ou fica com o valor da rodada anterior, na mesma janela)" \
  "LEIA-ME: janela cancelada deixa o valor da rodada anterior"
assert_not_contains "$leia" "não a deixe vazia" "LEIA-ME: valor vazio não é proibido (apaga a variável)"
new_pw_note="$(grep -F '**omita** `ZENY_SEED_ADMIN_NEW_PASSWORD`' "$leia_file" 2>/dev/null)"
assert_contains "$new_pw_note" "valor vazio" "LEIA-ME: a nota da senha nova fala do valor vazio"
assert_contains "$new_pw_note" "apaga a variável" "LEIA-ME: valor vazio no SetEnvironmentVariable apaga a variável"

# README: link do roteiro e os JARs gerados antes do package.sh.
assert_contains "$readme_text" "(docs/demo/roteiro-m1.md)" "README liga o roteiro do M1"
pkg_row="$(grep -F '`tools/server-env/package.sh`' "$readme" | head -1)"
assert_contains "$pkg_row" ":demo-seed:seedJar" "linha do package.sh manda gerar os JARs"
pre="${pkg_row%%\`tools/server-env/package.sh\`*}"
assert_contains "$pre" './gradlew :app:desktopJar :demo-seed:seedJar' "JARs gerados antes do package.sh"
assert_contains "$pkg_row" "python3" "linha do package.sh diz que ele precisa do python3 para validar os JARs"

finish
