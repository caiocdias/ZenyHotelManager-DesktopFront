#!/usr/bin/env bash
# Monta o pacote do Windows em <checkout principal>/build/demo-package/ (nunca copia .env).
# JARs e roteiro vêm do checkout onde o script está (pode ser um worktree); o shell não chama o Gradle.
# Uso: package.sh
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

(($# == 0)) || die 2 "uso: package.sh"

main="$(front_main_checkout)" || exit "$?"
root="$(front_root)" || die 2 "não foi possível localizar o checkout do front"
commit="$(pin_get commit)" || exit "$?"
tag="$(server_tag)" || exit "$?"
tree="$(ensure_server_tree)" || exit "$?"

pkg="$main/build/demo-package"

# Antes de criar ou apagar qualquer coisa: build e build/demo-package, se existirem, são diretórios reais
# (symlink é recusado) e o caminho físico fica dentro do checkout principal.
for p in "$main/build" "$pkg"; do
  if [[ -e "$p" || -L "$p" ]]; then
    { [[ -d "$p" && ! -L "$p" ]] && [[ "$(cd "$p" && pwd -P)" == "$(cd "$main" && pwd -P)"/* ]]; } ||
      die 2 "caminho do pacote inseguro (não é um diretório real dentro do checkout): $p"
  fi
done
ensure_state_dir "$main/build"

# O único rm -rf do script: só o diretório do pacote, dentro do checkout principal.
if [[ "$pkg" != "$main"/build/demo-package || -L "$pkg" ]]; then
  die 1 "caminho do pacote inesperado: $pkg"
fi

app_jar="$root/app/build/libs/zeny-desktop.jar"
seed_jar="$root/demo-seed/build/libs/demo-seed.jar"
roteiro="$root/docs/demo/roteiro-m1.md"

# require_artifact FILE HINT [zip] : arquivo regular (não symlink), não vazio e, com zip, assinatura PK\x03\x04.
require_artifact() {
  local file="$1" hint="$2" magic
  [[ -f "$file" && ! -L "$file" && -s "$file" ]] ||
    die 1 "artefato ausente ou inválido (precisa ser arquivo regular, não vazio): $file; $hint"
  if [[ "${3:-}" == zip ]]; then
    magic="$(head -c 4 -- "$file" | od -An -tx1 | tr -d ' \n')"
    [[ "$magic" == 504b0304 ]] || die 1 "artefato não é um JAR (ZIP) válido: $file; $hint"
  fi
}

# Antes do rm -rf: um artefato ausente ou inválido deixa o pacote anterior intacto.
require_artifact "$app_jar" "gere com ./gradlew :app:desktopJar" zip
require_artifact "$seed_jar" "gere com ./gradlew :demo-seed:seedJar" zip
require_artifact "$roteiro" "o roteiro docs/demo/roteiro-m1.md é versionado no front"

# JAR mais antigo que o último commit no código dos JARs: avisa (o pacote sai mesmo assim).
code_ct="$(git -C "$root" log -1 --format=%ct -- app core design sdk demo-seed gradle '*.kts')"
# warn_if_stale JAR COMANDO
warn_if_stale() {
  [[ -n "$code_ct" ]] || return 0
  (($(stat -c %Y -- "$1") < code_ct)) &&
    printf 'aviso: %s é mais antigo que o último commit do código dos JARs; regere com %s\n' "${1##*/}" "$2" >&2
  return 0
}
warn_if_stale "$app_jar" "./gradlew :app:desktopJar"
warn_if_stale "$seed_jar" "./gradlew :demo-seed:seedJar"

rm -rf -- "$pkg"
mkdir -p "$pkg/app" "$pkg/images" "$pkg/server" || die 1 "não foi possível criar $pkg"

cp -a "$tree/." "$pkg/server/" || die 1 "falha ao copiar a árvore do servidor"
render_override "$pkg/compose.override.yaml" 0.0.0.0 || exit "$?"
cp "$_SERVER_ENV_DIR/.env.example" "$_SERVER_ENV_DIR/gen-env.sh" "$pkg/" || die 1 "falha ao copiar .env.example e gen-env.sh"
chmod +x "$pkg/gen-env.sh"
cp "$(pin_file)" "$pkg/server-pin.properties" || die 1 "falha ao copiar o pin"

leia="$(<"$_SERVER_ENV_DIR/LEIA-ME.md")"
leia="$(_replace_all "$leia" @SERVER_COMMIT@ "$commit")"
leia="$(_replace_all "$leia" @SERVER_TAG@ "$tag")"
printf '%s\n' "$leia" >"$pkg/LEIA-ME.md"

cp -- "$app_jar" "$pkg/app/zeny-desktop.jar" || die 1 "falha ao copiar zeny-desktop.jar"
cp -- "$seed_jar" "$pkg/app/demo-seed.jar" || die 1 "falha ao copiar demo-seed.jar"
cp -- "$roteiro" "$pkg/roteiro-m1.md" || die 1 "falha ao copiar o roteiro"

# Origem dos artefatos, sem caminho absoluto. O sha256 é dos JARs copiados; não prova que saíram de front_commit.
front_commit="$(git -C "$root" rev-parse HEAD)" || die 1 "não foi possível ler o HEAD do front"
status="$(git -C "$root" status --porcelain --untracked-files=no)" || die 1 "não foi possível ler o git status do front"
front_dirty=false
[[ -z "$status" ]] || front_dirty=true
{
  printf 'front_commit=%s\n' "$front_commit"
  printf 'front_dirty=%s\n' "$front_dirty"
  printf 'server_commit=%s\n' "$commit"
  printf 'zeny-desktop.jar sha256=%s\n' "$(sha256sum <"$pkg/app/zeny-desktop.jar" | cut -d' ' -f1)"
  printf 'demo-seed.jar sha256=%s\n' "$(sha256sum <"$pkg/app/demo-seed.jar" | cut -d' ' -f1)"
} >"$pkg/VERSAO.txt"

cat >"$pkg/images/LEIA-ME.txt" <<'TXT'
Para pré-carregar as imagens do servidor (uso sem internet), gere zeny-server-images.tar
nesta pasta com tools/server-env/save-images.sh (no notebook, com as imagens já presentes)
e, no Windows, rode: docker load -i images\zeny-server-images.tar
Rode save-images.sh DEPOIS de package.sh: o package.sh recria build/demo-package/ e apaga
um images/zeny-server-images.tar anterior.
TXT

# Salvaguarda: segredos nunca entram no pacote.
if [[ -n "$(find "$pkg" \( -name '.env' -o -name '*.env' \) -print -quit)" ]]; then
  rm -rf -- "$pkg"
  die 1 "o pacote continha um arquivo .env; removido"
fi

size="$(du -sh "$pkg" | cut -f1)"
printf 'pacote: %s (%s)\n' "$pkg" "$size"
