#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/stage-linux.py
if command -v flatpak-builder >/dev/null; then
    builder=(flatpak-builder)
else
    builder=(flatpak run org.flatpak.Builder)
fi
"${builder[@]}" --user --force-clean --repo=build/linux-repo build/flatpak packaging/linux/io.github.MaDaAi.MultiMini.json
mkdir -p dist
flatpak build-bundle --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo build/linux-repo "dist/MultiMini-$(cat VERSION)-Linux-x86_64.flatpak" io.github.MaDaAi.MultiMini
python3 - <<'HASH'
from pathlib import Path
import hashlib
version=Path('VERSION').read_text().strip()
archive=Path('dist')/f'MultiMini-{version}-Linux-x86_64.flatpak'
with archive.open('rb') as stream:
    checksum=hashlib.file_digest(stream,'sha256').hexdigest()
archive.with_suffix('.flatpak.sha256').write_text(f'{checksum}  {archive.name}\n')
HASH
