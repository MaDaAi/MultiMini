#!/usr/bin/env bash
# Build the MultiMini Flatpak.
#   dist/MultiMini-<VERSION>-Linux-x86_64.flatpak (+ .sha256): a bundle that
#     remembers the MultiMini update repository, so a file install still updates.
#   build/linux-site/: the signed OSTree repository + .flatpakref/.flatpakrepo
#     for GitHub Pages, only when FLATPAK_GPG_KEY_ID is set (CI release builds).
set -euo pipefail
cd "$(dirname "$0")/.."
repo_url=${MULTIMINI_REPO_URL:-https://madaai.github.io/MultiMini/repo/}
site_url=${repo_url%repo/}
pubkey=packaging/linux/multimini-repo.gpg
python3 scripts/stage-linux.py
if command -v flatpak-builder >/dev/null; then
    builder=(flatpak-builder)
else
    builder=(flatpak run org.flatpak.Builder)
fi
sign=()
if [ -n "${FLATPAK_GPG_KEY_ID:-}" ]; then
    sign=(--gpg-sign="$FLATPAK_GPG_KEY_ID")
    [ -n "${FLATPAK_GPG_HOMEDIR:-}" ] && sign+=(--gpg-homedir="$FLATPAK_GPG_HOMEDIR")
fi
"${builder[@]}" --user --force-clean "${sign[@]}" --default-branch=stable --repo=build/linux-repo build/flatpak packaging/linux/io.github.MaDaAi.MultiMini.json
mkdir -p dist
bundle_args=(--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo)
# A bundle that names the signed update repository is GPG-verified on install,
# so it must itself be signed; an unsigned bundle with --gpg-keys fails to
# install ("GPG verification enabled, but no signatures found").
if [ ${#sign[@]} -gt 0 ] && [ -f "$pubkey" ]; then
    bundle_args+=(--repo-url="$repo_url" --gpg-keys="$pubkey" "${sign[@]}")
else
    echo "Unsigned build: the bundle installs but will not update in-app (set FLATPAK_GPG_KEY_ID)." >&2
fi
flatpak build-bundle "${bundle_args[@]}" build/linux-repo "dist/MultiMini-$(cat VERSION)-Linux-x86_64.flatpak" io.github.MaDaAi.MultiMini stable
python3 - <<'HASH'
from pathlib import Path
import hashlib
version=Path('VERSION').read_text().strip()
archive=Path('dist')/f'MultiMini-{version}-Linux-x86_64.flatpak'
with archive.open('rb') as stream:
    checksum=hashlib.file_digest(stream,'sha256').hexdigest()
archive.with_suffix('.flatpak.sha256').write_text(f'{checksum}  {archive.name}\n')
HASH

if [ ${#sign[@]} -gt 0 ]; then
    flatpak build-update-repo "${sign[@]}" --generate-static-deltas --prune --prune-depth=2 \
        --title="MultiMini" --default-branch=stable build/linux-repo
    rm -rf build/linux-site
    mkdir -p build/linux-site
    cp -a build/linux-repo build/linux-site/repo
    key=$(base64 -w0 "$pubkey")
    cat > build/linux-site/multimini.flatpakref <<REF
[Flatpak Ref]
Name=io.github.MaDaAi.MultiMini
Branch=stable
Title=MultiMini
Url=$repo_url
SuggestRemoteName=multimini
RuntimeRepo=https://flathub.org/repo/flathub.flatpakrepo
IsRuntime=false
GPGKey=$key
REF
    cat > build/linux-site/multimini.flatpakrepo <<REF
[Flatpak Repo]
Title=MultiMini
Url=$repo_url
Homepage=https://github.com/MaDaAi/MultiMini
GPGKey=$key
REF
    cat > build/linux-site/index.html <<'HTML'
<!doctype html><meta charset="utf-8"><title>MultiMini for Linux</title>
<h1>MultiMini for Linux (beta)</h1>
<p>Install once, then MultiMini updates itself (Settings → Updates) or through your software centre:</p>
<pre>flatpak install --user https://madaai.github.io/MultiMini/multimini.flatpakref</pre>
<p><a href="multimini.flatpakref">multimini.flatpakref</a> · <a href="https://github.com/MaDaAi/MultiMini/releases">All releases</a></p>
HTML
    touch build/linux-site/.nojekyll
    printf 'Site: %s\n' "$PWD/build/linux-site"
fi
