#!/usr/bin/env bash
# Ataque ao roteiro do M1 reescrito do zero (E07, Task 5a): docs/demo/roteiro-m1.md como passo a passo único,
# numa pasta nova e sem nada do projeto no Docker. Folha de senhas, limpeza só pelo projeto, "Recomeçar do zero",
# "Esqueci uma senha", nenhum passo que dependa do LEIA-ME, linhas comuns com o LEIA-ME idênticas, a nota do IP
# e o login do administrador contra o gen-env.sh. Estático (sem pwsh nem Docker); o gen-env.sh roda num
# diretório temporário, como no pacote. Não chama o Gradle nem containers.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

rot="$ROOT/docs/demo/roteiro-m1.md"
leia_f="$ROOT/tools/server-env/LEIA-ME.md"
envex="$ROOT/tools/server-env/.env.example"
genenv="$ROOT/tools/server-env/gen-env.sh"
seedargs_kt="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/cli/SeedArgs.kt"
docs_test="$ROOT/tests/test_docs.sh"

missing=0
for f in "$rot" "$leia_f" "$envex" "$genenv" "$seedargs_kt" "$docs_test"; do
  if [[ ! -f "$f" ]]; then
    _assert_fail "arquivo lido pelo ataque existe: ${f#"$ROOT"/}"
    missing=1
  fi
done
((missing == 0)) || finish

# --- utilitários --------------------------------------------------------------------------------------------
# Só as linhas dentro de blocos ```powershell (de um arquivo ou de um texto na entrada).
ps_code() { awk '/^```powershell[[:space:]]*$/{inb=1; next} /^```/{inb=0; next} inb' "$@"; }
# Mesmo, com o número da linha no arquivo: "N<TAB>linha".
ps_code_n() { awk '/^```powershell[[:space:]]*$/{inb=1; next} /^```/{inb=0; next} inb {print NR "\t" $0}' "$1"; }
# Seção "## N. ..." pelo número, do título até antes do próximo "## ".
step() { awk -v h="## $1. " 'index($0, h) == 1 {on = 1; print; next} on && /^## / {exit} on' "$rot"; }
# Subseção "### Título" do roteiro, até o próximo "###" ou "##".
sub_of() { awk -v h="### $1" '$0 == h {on = 1; next} on && /^###? / {exit} on' "$rot"; }
# Número do passo que contém a linha N do roteiro.
step_of_line() { awk -v n="$1" 'NR > n {exit} /^## [0-9]+\. / {s = $2} END {sub(/\.$/, "", s); print s}' "$rot"; }
# Número do único passo cujo texto casa com o padrão (ERE); vazio se nenhum ou mais de um.
step_with() {
  PAT="$1" awk '
    function flush() { if (h != "" && hit) { n++; out = h } }
    /^## [0-9]+\. / { flush(); h = $2; hit = 0; next }
    h != "" && $0 ~ ENVIRON["PAT"] { hit = 1 }
    END { flush(); if (n == 1) { sub(/\.$/, "", out); print out } }' "$rot"
}
# Parágrafo logo antes da linha N (o texto que diz como rodar o bloco).
para_before() { awk -v s="$1" 'NR < s' "$rot" | awk 'BEGIN{RS=""} {p=$0} END{print p}'; }
first_ln() { grep -nE -- "$1" "$rot" | head -1 | cut -d: -f1; }

rot_text="$(cat "$rot")"
rot_ps="$(ps_code "$rot")"
rot_ps_n="$(ps_code_n "$rot")"
leia_ps="$(ps_code "$leia_f")"
last_step="$(grep -oE '^## [0-9]+\.' "$rot" | tail -1 | grep -oE '[0-9]+')"
cleanup='docker compose -p zeny-front down -v'
check_ls='docker compose ls -a'
check_vol='docker volume ls --filter label=com.docker.compose.project=zeny-front'

n_admin="$(step_with 'Select-String .*ZENY_ADMIN_')"
n_genenv="$(step_with 'bash gen-env\.sh')"
n_build="$(step_with 'docker compose .* build$')"
n_seed="$(step_with 'demo-seed\.jar --server')"
n_app="$(step_with 'app\\zeny-desktop\.jar')"
n_map="$(step_with 'Ficam só as UHs sujas')"
n_live="$(step_with 'auth/login')"
for pair in "login do admin (Select-String)|$n_admin" "gerar o .env|$n_genenv" "build|$n_build" "seed|$n_seed" \
  "abrir o app|$n_app" "mapa|$n_map" "mudança ao vivo|$n_live"; do
  [[ -n "${pair#*|}" ]] || _assert_fail "roteiro tem um único passo de ${pair%%|*}"
done

# =============================================================================================================
# 1. Folha de senhas
# =============================================================================================================

# 1.1 A folha fica no passo 0, antes do passo 1: antes de qualquer linha que peça segredo e antes de o .env existir.
folha_ln="$(first_ln '^### Folha de senhas$')"
step1_ln="$(first_ln '^## 1\. ')"
first_secret_ln="$(awk -F'\t' '$2 ~ /Get-Credential|Read-Host|SetEnvironmentVariable/ {print $1; exit}' <<<"$rot_ps_n")"
first_login_ln="$(first_ln '\*\*Senha\*\*')"
if [[ -z "$folha_ln" || -z "$step1_ln" || -z "$first_secret_ln" || -z "$first_login_ln" ]] ||
  ! ((folha_ln < step1_ln && step1_ln < first_secret_ln && folha_ln < first_login_ln)); then
  _assert_fail "folha de senhas no passo 0, antes do passo 1, da primeira linha de segredo e do primeiro login no app" \
    "folha: ${folha_ln:-ausente}; passo 1: ${step1_ln:-ausente}; segredo: ${first_secret_ln:-ausente}; login: ${first_login_ln:-ausente}"
fi
assert_eq "0" "$(step_of_line "${folha_ln:-1}")" "a folha de senhas está no passo 0"

# 1.2 A tabela: 5 itens numerados de 1 a 5, um por segredo do seed, com as regras do SeedArgs.kt.
folha="$(awk '/^### Folha de senhas$/ {on = 1; next} on && /^##/ {exit} on' "$rot")"
rows="$(grep -E '^\| [0-9]+ \|' <<<"$folha")"
assert_eq "1 2 3 4 5" "$(awk -F'|' '{gsub(/ /, "", $2); print $2}' <<<"$rows" | tr '\n' ' ' | sed 's/ $//')" \
  "folha: itens 1 a 5, em ordem"
item_of() { grep -E -- "$1" <<<"$rows" | awk -F'|' '{gsub(/ /, "", $2); print $2}'; }
i_admin="$(item_of 'senha nova do administrador')"
i_ger="$(item_of 'login `gerente`')"
i_rec="$(item_of 'login `recepcao`')"
i_gov="$(item_of 'login `governanta`')"
i_pin="$(item_of 'PIN do Gerente')"
for pair in "senha nova do administrador|$i_admin" "gerente|$i_ger" "recepcao|$i_rec" "governanta|$i_gov" "PIN|$i_pin"; do
  [[ "${pair#*|}" =~ ^[1-5]$ ]] || _assert_fail "folha: um só item para ${pair%%|*}" "achado: ${pair#*|}"
done
pw_rule="$(grep -oE 'PASSWORD_LENGTH = [0-9]+\.\.[0-9]+' "$seedargs_kt" | grep -oE '[0-9]+\.\.[0-9]+' | sed 's/\.\./ a /')"
pin_rule="$(grep -oE 'PIN_LENGTH = [0-9]+\.\.[0-9]+' "$seedargs_kt" | grep -oE '[0-9]+\.\.[0-9]+' | sed 's/\.\./ a /')"
[[ -n "$pw_rule" && -n "$pin_rule" ]] || _assert_fail "li as regras de senha e PIN do SeedArgs.kt"
for i in "$i_admin" "$i_ger" "$i_rec" "$i_gov"; do
  assert_contains "$(grep -E "^\\| $i \\|" <<<"$rows")" "de $pw_rule caracteres" "folha, item $i: senha de $pw_rule caracteres (SeedArgs)"
done
assert_contains "$(grep -E "^\\| ${i_pin:-x} \\|" <<<"$rows")" "de $pin_rule dígitos" "folha, item do PIN: de $pin_rule dígitos (SeedArgs)"
assert_contains "$folha" "anote" "folha: manda anotar"
assert_contains "$folha" "fora da pasta" "folha: anotar fora da pasta do pacote"

# 1.3 Cada janela de senha aponta o item certo da folha (e só ele); a senha atual do admin aponta o passo do .env.
#     O nome do usuário na janela é o login do item.
n_prompts=0
while IFS=: read -r start prompt_ln; do
  [[ -n "$start" ]] || continue
  n_prompts=$((n_prompts + 1))
  line="$(sed -n "${prompt_ln}p" "$rot")"
  prev="$(para_before "$start")"
  want="" login=""
  case "$line" in
    *'"ZENY_SEED_ADMIN_PASSWORD"'*) want="passo" ;;
    *'"ZENY_SEED_ADMIN_NEW_PASSWORD"'*) want="$i_admin" login="administrador" ;;
    *'"ZENY_SEED_MANAGER_PASSWORD"'*) want="$i_ger" login="gerente" ;;
    *'"ZENY_SEED_RECEPTIONIST_PASSWORD"'*) want="$i_rec" login="recepcao" ;;
    *'"ZENY_SEED_HOUSEKEEPER_PASSWORD"'*) want="$i_gov" login="governanta" ;;
    *Read-Host*) want="$i_pin" ;;
    *'Get-Credential governanta'*) want="$i_gov" login="governanta" ;;
    *) _assert_fail "janela de credencial conhecida (linha $prompt_ln)" "linha: $line" ;;
  esac
  got_items="$(grep -oE 'item [0-9]+ da folha' <<<"$prev" | grep -oE '[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ $//')"
  if [[ "$want" == "passo" ]]; then
    assert_eq "" "$got_items" "janela da senha atual do admin (linha $prompt_ln) não aponta item da folha"
    assert_contains "$prev" "passo $n_admin" "janela da senha atual do admin (linha $prompt_ln) aponta o passo $n_admin (.env)"
  elif [[ -n "$want" ]]; then
    assert_eq "$want" "$got_items" "janela da linha $prompt_ln aponta o item $want da folha, e só ele"
  fi
  if [[ -n "$login" ]]; then
    got_user="$(grep -oE 'Get-Credential (-UserName )?[A-Za-z0-9_.-]+' <<<"$line" | awk '{print $NF}')"
    assert_eq "$login" "$got_user" "janela da linha $prompt_ln com o usuário do item ($login)"
  fi
done < <(awk '
  /^```powershell/ { s = NR; p = ""; next }
  /^```/ && s { if (p != "") print s ":" p; s = 0; next }
  s && /Get-Credential|Read-Host/ && p == "" { p = NR }' "$rot")
assert_eq "7" "$n_prompts" "roteiro: 7 janelas de credencial (6 do seed e a da Governanta)"

# 1.4 Os logins no app apontam o item do usuário digitado.
while IFS= read -r l; do
  [[ -n "$l" ]] || continue
  user="$(grep -oE 'digite `[a-z]+`' <<<"$l" | grep -oE '[a-z]+`$' | tr -d '`')"
  case "$user" in
    gerente) want="$i_ger" ;; recepcao) want="$i_rec" ;; governanta) want="$i_gov" ;; *) want="?" ;;
  esac
  assert_eq "$want" "$(grep -oE 'item [0-9]+ da folha' <<<"$l" | grep -oE '[0-9]+' | tr '\n' ' ' | sed 's/ $//')" \
    "login de $user no app aponta o item $want da folha"
done < <(grep -E 'Em \*\*Login\*\*, digite' "$rot")
(($(grep -cE 'Em \*\*Login\*\*, digite' "$rot") >= 2)) || _assert_fail "roteiro: logins da Recepcionista e do Gerente no app"

# 1.5 Todo "item N da folha" existe; depois do seed, a senha do admin é o item da senha nova.
for n in $(grep -oE 'item [0-9]+ da folha' "$rot" | grep -oE '[0-9]+' | sort -u); do
  ((n >= 1 && n <= 5)) || _assert_fail "item $n citado existe na folha"
done
assert_contains "$(step "$n_seed")" "a senha do administrador é o item $i_admin da folha" \
  "depois do seed, a senha do admin é o item $i_admin (senha nova)"
# 1.6 "Você vai precisar deles nos passos ..." = os passos (antes do passo final) que citam um item da folha.
said="$(grep -oE 'precisar deles nos passos [0-9]+((, | e )[0-9]+)*' <<<"$folha" | grep -oE '[0-9]+' | sort -n | tr '\n' ' ')"
used="$(for ((n = 1; n < last_step; n++)); do step "$n" | grep -qE 'item [0-9]+ da folha' && echo "$n"; done | tr '\n' ' ')"
[[ -n "$said" ]] || _assert_fail "folha diz em que passos os valores são usados"
assert_eq "$used" "$said" "folha: os passos citados são os que usam um item da folha"

# =============================================================================================================
# 2. Passo 1: pasta nova, projeto e volumes, limpeza só pelo projeto
# =============================================================================================================
s1="$(step 1)"
s1_ps="$(ps_code <<<"$s1")"
l_ls="$(grep -nxF -- "$check_ls" <<<"$s1_ps" | head -1 | cut -d: -f1)"
l_vol="$(grep -nxF -- "$check_vol" <<<"$s1_ps" | head -1 | cut -d: -f1)"
l_down="$(grep -nxF -- "$cleanup" <<<"$s1_ps" | head -1 | cut -d: -f1)"
if [[ -z "$l_ls" || -z "$l_vol" || -z "$l_down" ]] || ! ((l_ls < l_vol && l_vol < l_down)); then
  _assert_fail "passo 1: confere projetos (ls -a) e volumes do zeny-front, e só depois limpa pelo projeto" \
    "linhas no passo: ls=${l_ls:-ausente} volume=${l_vol:-ausente} down=${l_down:-ausente}"
fi
assert_eq "" "$(grep -E '^docker ' <<<"$s1_ps" | grep -vxF -e "$check_ls" -e "$check_vol" -e "$cleanup")" \
  "passo 1: nenhum outro comando docker (nem compose com -f ou --env-file: ainda não há .env)"
assert_contains "$s1" "pasta nova" "passo 1: pasta nova"
assert_contains "$s1" "apaga os dados" "passo 1: avisa que a limpeza apaga os dados"
# A limpeza vale também quando só sobraram volumes (o compose ls -a não mostra projeto sem container).
cleanup_ln="$(first_ln "^$cleanup\$")"
cond="$(para_before "$((${cleanup_ln:-1} - 1))")"
assert_contains "$cond" "zeny-front" "passo 1: a condição da limpeza cita o projeto"
assert_contains "$cond" "volumes" "passo 1: limpa também quando só sobraram volumes"
after_down="$(awk -v c="$cleanup" '$0 == c {on = 1; next} on && /^```/ {f = 1; next} f && /^## / {exit} f' <<<"$s1")"
assert_contains "$after_down" "de novo" "passo 1: depois da limpeza, confere de novo"
# 2.1 Em todo o roteiro: todo down -v é só pelo projeto, e nada apaga fora dele (prune, rm de volume ou container).
assert_eq "$cleanup" "$(grep -E 'down -v' <<<"$rot_ps" | sort -u)" "roteiro: todo down -v é o só pelo projeto"
assert_eq "" "$(grep -nE 'prune|docker (volume |container )?rm( |$)|docker system' <<<"$rot_ps")" \
  "roteiro: sem prune nem rm de volume/container (apagariam fora do zeny-front)"
assert_eq "" "$(grep -E '^docker compose ' <<<"$rot_ps" | grep -vxF -e "$check_ls" -e 'docker compose version' |
  grep -vE '^docker compose -p zeny-front ')" \
  "roteiro: todo docker compose (fora o ls -a) com -p zeny-front"
assert_eq "" "$(grep -E '^docker volume ' <<<"$rot_ps" | grep -vxF "$check_vol")" \
  "roteiro: o único docker volume é a conferência filtrada pelo projeto"

# =============================================================================================================
# 3. Recomeçar do zero e Esqueci uma senha
# =============================================================================================================
recomecar="$(sub_of 'Recomeçar do zero')"
assert_eq "$cleanup" "$(ps_code <<<"$recomecar")" "Recomeçar do zero: só a limpeza do passo 1, igual"
assert_contains "$recomecar" "Apaga" "Recomeçar do zero: avisa que apaga os dados"
assert_contains "$recomecar" "volta a ser a \`ZENY_ADMIN_PASSWORD\` do \`.env\`" \
  "Recomeçar do zero: a senha inicial do admin volta à do .env"
assert_contains "$recomecar" "passo $n_seed" "Recomeçar do zero: diz onde a senha inicial é usada (passo $n_seed)"
assert_contains "$recomecar" "siga do passo $n_build em diante" "Recomeçar do zero: segue do build (passo $n_build)"
assert_contains "$recomecar" "não rode o passo $n_genenv de novo" "Recomeçar do zero: não gera o .env de novo"
assert_not_contains "$recomecar" "--force" "Recomeçar do zero: sem --force"
assert_contains "$recomecar" "conferências do passo 1" "Recomeçar do zero: as conferências do passo 1 voltam vazias"

esqueci="$(sub_of 'Esqueci uma senha')"
esq_user="$(grep -A20 -F '**Senha de um usuário' <<<"$esqueci" | awk '/^\*\*Senha do administrador/ {exit} {print}')"
esq_admin="$(grep -F '**Senha do administrador:**' <<<"$esqueci")"
[[ -n "$esq_user" && -n "$esq_admin" ]] || _assert_fail "Esqueci uma senha: um parágrafo para usuário/PIN e outro para o admin"
assert_contains "$esq_user" "passo $n_seed de novo" "Esqueci (usuário): roda o seed (passo $n_seed) de novo"
assert_contains "$esq_user" "item $i_admin da folha" "Esqueci (usuário): a senha atual do admin é o item $i_admin"
assert_contains "$esq_user" "**pule** o 5.2" "Esqueci (usuário): pula a senha nova do admin"
assert_contains "$esq_user" "Remove-Item Env:ZENY_SEED_ADMIN_NEW_PASSWORD" "Esqueci (usuário): apaga a senha nova que sobrou"
assert_contains "$esq_user" "redefine" "Esqueci (usuário): o seed redefine as senhas"
assert_contains "$esq_admin" "não recupera" "Esqueci (admin): o seed não recupera a senha do admin"
assert_contains "$esq_admin" '"Recomeçar do zero"' "Esqueci (admin): o caminho é Recomeçar do zero"
assert_contains "$esq_admin" "bloqueiam a conta" "Esqueci (admin): tentativas erradas bloqueiam a conta"
assert_contains "$esq_admin" "Não fique tentando" "Esqueci (admin): não manda tentar senhas"
# O código confirma: o seed redefine a senha de usuário que não entra, mas precisa da senha do admin para entrar.
user_seeder="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/people/UserSeeder.kt"
assert_contains "$(cat "$user_seeder")" "users.resetPassword(existing.id, provisional)" "UserSeeder redefine a senha do usuário"
assert_contains "$(cat "$user_seeder")" "SignIn.InvalidCredentials -> throw SeedFailure(REJECTED)" \
  "UserSeeder: senha do admin recusada é falha (não há recuperação)"

# =============================================================================================================
# 4. Nenhum passo depende do LEIA-ME; remissões internas apontam o passo certo
# =============================================================================================================
# O LEIA-ME só aparece como item de uma lista de arquivos ("`LEIA-ME.md`, `") e na frase que o dispensa, no passo 0.
dispensa='Você não precisa abrir o `LEIA-ME.md` para seguir este roteiro.'
disp_ln="$(grep -nF -- "$dispensa" "$rot" | head -1 | cut -d: -f1)"
[[ -n "$disp_ln" ]] || _assert_fail "roteiro diz que o LEIA-ME não é preciso"
assert_eq "0" "$(step_of_line "${disp_ln:-1}")" "roteiro: a dispensa do LEIA-ME está no passo 0"
drop() { D="$1" awk '{ while ((i = index($0, ENVIRON["D"])) > 0) $0 = substr($0, 1, i - 1) substr($0, i + length(ENVIRON["D"])); print }'; }
assert_eq "" "$(drop "$dispensa" <"$rot" | drop '`LEIA-ME.md`, `' | grep -nF 'LEIA-ME')" \
  "roteiro: fora a dispensa e as listas de arquivos, nenhuma menção ao LEIA-ME"
(($(grep -cF '`LEIA-ME.md`, `' "$rot") >= 1)) || _assert_fail "roteiro: o LEIA-ME aparece na lista do pacote"
# As duas listas do conteúdo do pacote (passo 0 e o Get-ChildItem do passo 1) têm os mesmos nomes.
names_of() { grep -oE '`[^`]+`' <<<"$1" | tr -d '`' | grep -vE '^(demo-package|\.env|\.example)$' | sort -u; }
list0="$(names_of "$(grep -F 'Ela tem as pastas' "$rot" | sed 's/ Você não precisa.*//')")"
list1="$(names_of "$(grep -F 'a lista tem `app`' "$rot" | sed 's/ Não pode ter.*//')")"
[[ -n "$list0" ]] || _assert_fail "passo 0 lista o conteúdo do pacote"
assert_eq "$list0" "$list1" "o Get-ChildItem do passo 1 espera o mesmo conteúdo que o passo 0 lista"
assert_eq "" "$(grep -noE '[A-Za-z0-9_./-]+\.md' "$rot" | grep -vE ':(roteiro-m1|LEIA-ME)\.md$')" "roteiro não remete a outro .md"
assert_eq "" "$(grep -nE 'README|images[\\/]LEIA-ME' "$rot")" "roteiro não remete ao README nem ao LEIA-ME das imagens"
# Remissões a passos e blocos existem.
for n in $(grep -oE 'passos? [0-9]+((, | e | a )[0-9]+)*' "$rot" | grep -oE '[0-9]+' | sort -un); do
  ((n <= last_step)) || _assert_fail "remissão ao passo $n existe (último: $last_step)"
done
labels="$(grep -oE '^\*\*[0-9]+\.[0-9]+\. ' "$rot" | tr -d '* ' | sed 's/\.$//')"
for b in $(sed -E 's/PowerShell 5\.1//g' "$rot" | grep -oE '(^|[^0-9.v:])[0-9]{1,2}\.[0-9]{1,2}([^0-9.]|\.[^0-9]|$)' |
  grep -oE '[0-9]{1,2}\.[0-9]{1,2}' | sort -u); do
  grep -qxF -- "$b" <<<"$labels" || _assert_fail "remissão ao bloco $b existe (**$b.)"
done
# Remissões com sentido: o passo citado é o que tem o que o texto diz.
for n in $(grep -oE '(senha inicial do |`\.env` \()passo [0-9]+' "$rot" | grep -oE '[0-9]+$' | sort -u); do
  assert_eq "$n_admin" "$n" "a senha inicial do admin é a do passo $n_admin (Select-String do .env)"
done
while IFS=$'\t' read -r tok n; do
  [[ -n "$tok" ]] || continue
  assert_contains "$(ps_code <<<"$(step "$n")")" "$tok" "\`$tok\` do passo $n: o passo $n tem o comando"
done < <(grep -oE '`[^`]+` do passo [0-9]+' "$rot" | sed -E 's/^`([^`]+)` do passo ([0-9]+)$/\1\t\2/' | sort -u)
assert_contains "$rot_text" "Todos os comandos dos passos 1 a $n_app rodam" "a pasta vale do passo 1 ao de abrir o app ($n_app)"
assert_contains "$rot_text" "Gerente para os passos $n_map e $n_live" "o Gerente fica logado no mapa ($n_map) e na mudança ao vivo ($n_live)"
assert_contains "$(sub_of 'Parar sem apagar')" "siga do passo $n_app" "Parar sem apagar: volta pelo passo de abrir o app ($n_app)"
assert_contains "$(step "$last_step")" "### Erros comuns" "os Erros comuns ficam no último passo ($last_step)"
assert_contains "$rot_text" "\"Erros comuns\", no passo $last_step" "a remissão a Erros comuns aponta o passo $last_step"

# =============================================================================================================
# 5. Linhas comuns ao roteiro e ao LEIA-ME: idênticas, e a checagem do test_docs.sh não passa no vazio
# =============================================================================================================
kinds='^(docker |curl\.exe |\[Environment\]::SetEnvironmentVariable|\$pin |Remove-Variable pin$|java -jar app\\demo-seed\.jar |java "-Dzeny)'
rot_shared="$(grep -E "$kinds" <<<"$rot_ps" | grep -vxF 'docker compose version' | sort -u)"
(($(grep -c . <<<"$rot_shared") >= 18)) ||
  _assert_fail "roteiro: ao menos 18 linhas de servidor, seed e app em comum com o LEIA-ME" "achadas: $(grep -c . <<<"$rot_shared")"
while IFS= read -r l; do
  [[ -n "$l" ]] || continue
  grep -qxF -- "$l" <<<"$leia_ps" || _assert_fail "linha do roteiro idêntica num bloco PowerShell do LEIA-ME" "$l"
done <<<"$rot_shared"
# A lista do test_docs.sh: não vazia e com toda linha comum do roteiro (só lida, nunca alterada aqui).
# Só as linhas da definição são avaliadas (compose='...' e itens '...' ou "$compose <subcomando>", sem substituição
# de comando); qualquer outra forma é falha, sem avaliar nada.
compose_def="$(grep -E "^compose='[^']*'\$" "$docs_test")"
arr_def="$(awk '/^shared_lines=\(/ {on = 1; first = NR} on {print} on && ((NR == first && /\)$/) || /^\)$/) {exit}' "$docs_test")"
arr_items="$(sed '1d;$d' <<<"$arr_def")"
docs_list=""
if [[ -z "$compose_def" || "$(head -1 <<<"$arr_def")" != "shared_lines=(" || "$(tail -1 <<<"$arr_def")" != ")" ]] ||
  grep -qvE "^  ('[^']*'|\"\\\$compose [a-z -]+\")\$" <<<"$arr_items"; then
  _assert_fail "test_docs.sh: compose='...' e shared_lines=( ... ) com um item entre aspas por linha" "definição: $arr_def"
else
  docs_list="$(
    eval "$compose_def"
    eval "items=($arr_items)"
    printf '%s\n' "${items[@]}"
  )"
fi
(($(grep -c . <<<"$docs_list") >= 17)) ||
  _assert_fail "test_docs.sh: a lista de linhas comuns tem ao menos 17 linhas" "achadas: $(grep -c . <<<"$docs_list")"
while IFS= read -r l; do
  [[ -n "$l" && "$l" != java\ \"-Dzeny* ]] || continue
  grep -qxF -- "$l" <<<"$docs_list" || _assert_fail "test_docs.sh confere a linha comum do roteiro" "$l"
done <<<"$rot_shared"

# =============================================================================================================
# 6. A nota do IP: só a linha ZENY_STORAGE_PUBLIC_ENDPOINT, nunca --force
# =============================================================================================================
for f in "$rot" "$leia_f"; do
  nm="${f#"$ROOT"/}"
  note="$(grep -F 'ZENY_STORAGE_PUBLIC_ENDPOINT' "$f")"
  assert_eq "1" "$(grep -c . <<<"$note")" "$nm: uma só nota troca o endereço"
  assert_contains "$note" "só a linha \`ZENY_STORAGE_PUBLIC_ENDPOINT\`" "$nm: a nota troca só a linha ZENY_STORAGE_PUBLIC_ENDPOINT"
  assert_contains "$note" "\`up -d\`" "$nm: a nota manda rodar o up -d de novo"
  assert_eq "" "$(grep -oE 'ZENY_[A-Z_]+' <<<"$note" | grep -vxF ZENY_STORAGE_PUBLIC_ENDPOINT)" "$nm: a nota não manda mexer em outra variável"
  # --force só em frase que o proíbe, e nunca num bloco de código.
  assert_eq "" "$(grep -F -- '--force' "$f" | grep -vE '([Nn]unca|Não use)([^.]|\.[^ ])*--force')" "$nm: --force só em frase que o proíbe"
  assert_eq "" "$(ps_code "$f" | grep -F -- '--force')" "$nm: nenhum bloco PowerShell com --force"
  assert_eq "1" "$(ps_code "$f" | grep -c 'gen-env\.sh')" "$nm: o .env é gerado uma vez só"
done
assert_eq "$n_genenv" "$(step_with 'ZENY_STORAGE_PUBLIC_ENDPOINT')" "roteiro: a nota do IP fica no passo do .env ($n_genenv)"

# =============================================================================================================
# 7. Ordem do fluxo do zero
# =============================================================================================================
ln_code() { awk -F'\t' -v p="$1" 'index($2, p) == 1 {print $1; exit}' <<<"$rot_ps_n"; }
ln_code_after() { awk -F'\t' -v p="$1" -v a="$2" '$1 > a && index($2, p) == 1 {print $1; exit}' <<<"$rot_ps_n"; }
o_ls="$(ln_code "$check_ls")"
o_vol="$(ln_code "$check_vol")"
o_down="$(ln_code "$cleanup")"
o_gen="$(ln_code 'docker run ')"
o_build="$(awk -F'\t' '$2 ~ /^docker compose .* build$/ {print $1; exit}' <<<"$rot_ps_n")"
o_up="$(awk -F'\t' '$2 ~ /^docker compose .* up -d$/ {print $1; exit}' <<<"$rot_ps_n")"
o_ready="$(ln_code 'curl.exe ')"
o_sel="$(ln_code 'Select-String ')"
o_set="$(ln_code '[Environment]::SetEnvironmentVariable(')"
o_pin="$(ln_code '$pin = Read-Host')"
o_seed="$(ln_code 'java -jar app\demo-seed.jar ')"
o_exit="$(ln_code_after '$LASTEXITCODE' "${o_seed:-0}")"
o_clear="$(ln_code 'Remove-Item Env:ZENY_SEED_*')"
o_app="$(ln_code 'java "-Dzeny.devShell=true"')"
o_login="$(awk -F'\t' '$2 ~ /auth\/login/ {print $1; exit}' <<<"$rot_ps_n")"
seq_ok=1
prev=0
for v in "$folha_ln" "$o_ls" "$o_vol" "$o_down" "$o_gen" "$o_build" "$o_up" "$o_ready" "$o_sel" "$o_set" "$o_pin" \
  "$o_seed" "$o_exit" "$o_clear" "$o_app" "$o_login"; do
  if [[ -z "$v" ]] || ((v <= prev)); then seq_ok=0; fi
  prev="${v:-$prev}"
done
((seq_ok)) || _assert_fail "roteiro em ordem: folha < ls -a < volumes < down -v < .env < build < up -d < readiness < admin do .env < senhas < PIN < seed < \$LASTEXITCODE < apagar as senhas < app < login da Governanta" \
  "linhas: $folha_ln $o_ls $o_vol $o_down $o_gen $o_build $o_up $o_ready $o_sel $o_set $o_pin $o_seed $o_exit $o_clear $o_app $o_login"
# O $LASTEXITCODE é o primeiro comando depois do seed (outro comando externo o trocaria).
assert_eq '$LASTEXITCODE' "$(awk -F'\t' -v a="${o_seed:-0}" '$1 > a {print $2; exit}' <<<"$rot_ps_n")" \
  "o comando logo depois do seed é o \$LASTEXITCODE"
assert_eq "1" "$(grep -cF 'java -jar app\demo-seed.jar' <<<"$rot_ps")" "roteiro: o seed roda num único comando"
# Nenhum segredo do seed definido depois de apagá-los (ficaria no ambiente até fechar o PowerShell).
assert_eq "" "$(awk -F'\t' -v a="${o_clear:-0}" '$1 > a && $2 ~ /SetEnvironmentVariable\("ZENY_SEED_/' <<<"$rot_ps_n")" \
  "nenhum segredo do seed definido depois do Remove-Item Env:ZENY_SEED_*"
# Compose da página 0 e do LEIA-ME: a mesma versão mínima.
min_v="$(grep -oE 'Compose v?[0-9]+\.[0-9]+\.[0-9]+' "$leia_f" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')"
assert_contains "$(step 0)" "${min_v:-?} ou mais nova" "passo 0: Compose >= $min_v, como no LEIA-ME"

# =============================================================================================================
# 8. Login do administrador: o do .env.example, o do gen-env.sh e o do --admin-login
# =============================================================================================================
login_of() { grep -oE -- '--admin-login [^ ]+' <<<"$1" | awk '{print $2}'; }
rot_login="$(login_of "$(grep -F 'java -jar app\demo-seed.jar' <<<"$rot_ps")")"
leia_login="$(login_of "$(grep -F 'java -jar app\demo-seed.jar' <<<"$leia_ps")")"
ex_login="$(grep -E '^ZENY_ADMIN_LOGIN=' "$envex" | cut -d= -f2-)"
[[ -n "$ex_login" ]] || _assert_fail ".env.example tem ZENY_ADMIN_LOGIN com valor"
assert_eq "$ex_login" "$rot_login" "roteiro: --admin-login = ZENY_ADMIN_LOGIN do .env.example"
assert_eq "$ex_login" "$leia_login" "LEIA-ME: --admin-login = ZENY_ADMIN_LOGIN do .env.example"
assert_eq "" "$(grep -E '<|>' <<<"$(grep -F 'demo-seed.jar' <<<"$rot_ps")")" "roteiro: a linha do seed roda colada (sem <...>)"

if ! command -v openssl >/dev/null 2>&1; then
  skip_case "sem openssl: o gen-env.sh não roda aqui"
else
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  cp "$genenv" "$envex" "$tmp/"
  gen_line="$(grep -F 'bash gen-env.sh' <<<"$rot_ps")"
  # Os argumentos do gen-env.sh, como o roteiro os passa dentro do sh -c "...".
  read -ra gen_args <<<"$(sed -E 's/.*bash gen-env\.sh ([^"]*)".*/\1/' <<<"$gen_line")"
  gen_out="$(cd "$tmp" && bash gen-env.sh "${gen_args[@]}" 2>"$tmp/gen.err")"
  gen_code=$?
  assert_eq "0" "$gen_code" "gen-env.sh com os argumentos do roteiro (${gen_args[*]})" "erro: $(tail -3 "$tmp/gen.err")"
  env_file="$tmp/${gen_args[-1]}"
  if [[ -f "$env_file" ]]; then
    gen_login="$(grep -E '^ZENY_ADMIN_LOGIN=' "$env_file" | cut -d= -f2-)"
    assert_eq "$gen_login" "$rot_login" "roteiro: --admin-login = ZENY_ADMIN_LOGIN gerado pelo gen-env.sh"
    assert_eq "$gen_login" "$leia_login" "LEIA-ME: --admin-login = ZENY_ADMIN_LOGIN gerado pelo gen-env.sh"
    # O resultado esperado do passo do .env cita a saída real do gen-env.sh.
    assert_contains "$(step "$n_genenv")" "\`$gen_out\`" "passo $n_genenv: resultado esperado = saída do gen-env.sh"
    # O endereço público gerado é o servidor que o seed, o readiness e o app usam.
    pub="$(grep -E '^ZENY_STORAGE_PUBLIC_ENDPOINT=' "$env_file" | cut -d= -f2-)"
    assert_contains "$(grep -F 'demo-seed.jar' <<<"$rot_ps")" "--server $pub " "seed no endereço público gerado ($pub)"
    assert_contains "$(grep -F 'curl.exe' <<<"$rot_ps")" "$pub/actuator/health/readiness" "readiness no endereço público gerado"
    # O Select-String do passo do admin mostra só as duas linhas do admin, nenhum outro segredo.
    pat="$(grep -F 'Select-String' <<<"$rot_ps" | sed -nE 's/.*-Pattern "([^"]+)".*/\1/p')"
    shown="$(grep -nE -- "${pat:-^$}" "$env_file" | sed 's/^/.env:/')"
    assert_eq "ZENY_ADMIN_LOGIN ZENY_ADMIN_PASSWORD" "$(cut -d: -f3 <<<"$shown" | cut -d= -f1 | tr '\n' ' ' | sed 's/ $//')" \
      "Select-String mostra só ZENY_ADMIN_LOGIN e ZENY_ADMIN_PASSWORD"
    admin_txt="$(step "$n_admin")"
    assert_contains "$admin_txt" "$(grep -F 'ZENY_ADMIN_LOGIN=' <<<"$shown")" "passo $n_admin: a linha do login como o Select-String mostra"
    pw_prefix="$(grep -F 'ZENY_ADMIN_PASSWORD=' <<<"$shown" | sed -E 's/=.*/=/')"
    assert_contains "$admin_txt" "$pw_prefix<senha inicial, " "passo $n_admin: a linha da senha com o mesmo número de linha"
    pw="$(grep -E '^ZENY_ADMIN_PASSWORD=' "$env_file" | cut -d= -f2-)"
    assert_contains "$admin_txt" "<senha inicial, ${#pw} caracteres>" "passo $n_admin: a senha inicial gerada tem ${#pw} caracteres"
  else
    _assert_fail "gen-env.sh criou ${gen_args[-1]}"
  fi
fi

finish
