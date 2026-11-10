#!/usr/bin/env bash
# Grava num único tar as imagens do compose do servidor fixado (zeny-hotel-manager:<tag> no lugar de :dev).
# Uso: save-images.sh [OUT]   (padrão: build/demo-package/images/zeny-server-images.tar)
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

(($# <= 1)) || die 2 "uso: save-images.sh [OUT]"
tag="$(server_tag)" || exit "$?"
tree="$(ensure_server_tree)" || exit "$?"
out="${1:-}"
if [[ -z "$out" ]]; then
  main="$(front_main_checkout)" || exit "$?"
  out="$main/build/demo-package/images/zeny-server-images.tar"
fi

images=()
while IFS= read -r line; do
  img="${line#*image:}"
  img="${img%%#*}"
  img="${img//[[:space:]\"\']/}"
  [[ -n "$img" ]] || continue
  [[ "$img" == "zeny-hotel-manager:dev" ]] && img="zeny-hotel-manager:$tag"
  dup=0
  for seen in "${images[@]+"${images[@]}"}"; do [[ "$seen" == "$img" ]] && dup=1; done
  ((dup)) || images+=("$img")
done < <(grep -E '^[[:space:]]*image:' "$tree/docker/compose.yaml")
((${#images[@]} > 0)) || die 2 "nenhuma imagem encontrada em $tree/docker/compose.yaml"

missing=()
for img in "${images[@]}"; do
  docker image inspect "$img" >/dev/null 2>&1 || missing+=("$img")
done
if ((${#missing[@]} > 0)); then
  printf 'erro: imagens ausentes:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  printf 'rode tools/server-env/up.sh --build (zeny-hotel-manager) ou docker pull <imagem> e tente de novo\n' >&2
  exit 2
fi

mkdir -p "$(dirname "$out")" || die 1 "não foi possível criar o diretório de $out"
docker save -o "$out" "${images[@]}" || die 1 "falha ao gravar $out"
printf 'imagens gravadas em %s\n' "$out"
