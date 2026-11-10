#!/usr/bin/env bash
# Fixa o commit do servidor: copia api/openapi.yaml byte a byte e grava api/server-pin.properties.
# Uso: tools/server-pin.sh [REF]   (padrão: main)
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

ref="${1:-main}"
commit="$(resolve_server_ref "$ref")"

api_dir="$(front_root)/api"
mkdir -p "$api_dir"
tmp_yaml="$(mktemp "$api_dir/.openapi.yaml.XXXXXX")"
tmp_pin="$(mktemp "$api_dir/.server-pin.XXXXXX")"
trap 'rm -f "$tmp_yaml" "$tmp_pin"' EXIT

srv_git cat-file blob "$commit:api/openapi.yaml" >"$tmp_yaml" 2>/dev/null ||
  die 2 "o commit ${commit:0:12} não tem api/openapi.yaml"

hash="$(sha256sum "$tmp_yaml" | cut -d' ' -f1)"
{
  printf 'commit=%s\n' "$commit"
  printf 'contract_sha256=%s\n' "$hash"
  printf 'synced_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$tmp_pin"

chmod 644 "$tmp_yaml" "$tmp_pin"
mv "$tmp_yaml" "$api_dir/openapi.yaml"
mv "$tmp_pin" "$api_dir/server-pin.properties"

printf 'Servidor fixado em %s (contrato sha256 %s)\n' "$(short_commit "$commit")" "${hash:0:12}"
