#!/usr/bin/env bash
# Monta o pacote do Windows em <checkout principal>/build/demo-package/ (nunca copia .env).
# Uso: package.sh
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

(($# == 0)) || die 2 "uso: package.sh"

main="$(front_main_checkout)" || exit "$?"
commit="$(pin_get commit)" || exit "$?"
tag="$(server_tag)" || exit "$?"
tree="$(ensure_server_tree)" || exit "$?"

ensure_state_dir "$main/build"
pkg="$main/build/demo-package"

# O único rm -rf do script: só o diretório do pacote, dentro do checkout principal.
if [[ "$pkg" != "$main"/build/demo-package || -L "$pkg" ]]; then
  die 1 "caminho do pacote inesperado: $pkg"
fi
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

cat >"$pkg/app/LEIA-ME.txt" <<'TXT'
Os JARs do app e do demo-seed entram nesta pasta na M1 E07.
TXT
cat >"$pkg/images/LEIA-ME.txt" <<'TXT'
Para pré-carregar as imagens do servidor (uso sem internet), gere zeny-server-images.tar
nesta pasta com tools/server-env/save-images.sh (no notebook, com as imagens já presentes)
e, no Windows, rode: docker load -i images\zeny-server-images.tar
TXT

# Salvaguarda: segredos nunca entram no pacote.
if [[ -n "$(find "$pkg" \( -name '.env' -o -name '*.env' \) -print -quit)" ]]; then
  rm -rf -- "$pkg"
  die 1 "o pacote continha um arquivo .env; removido"
fi

size="$(du -sh "$pkg" | cut -f1)"
printf 'pacote: %s (%s)\n' "$pkg" "$size"
