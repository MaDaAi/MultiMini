#!/usr/bin/env python3
"""Produce an allowlisted Flatpak build context under ignored build/."""
from pathlib import Path
import shutil
import hashlib
import urllib.request

root = Path(__file__).resolve().parents[1]
stage = root / 'build' / 'linux-source'
stage.mkdir(parents=True, exist_ok=True)
for folder in ('app/src/main/java','app/src/main/resources','python/multimin_backend','packaging/linux'):
    dest = stage / folder
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(root/folder, dest, ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
(stage/'scripts').mkdir(exist_ok=True)
shutil.copy2(root/'scripts/build-linux.sh',stage/'scripts/build-linux.sh')
for name in ('VERSION','LICENSE','TERMS.md','EULA.md','PRIVACY.md','COOKIE_POLICY.md','REFUND_POLICY.md','HELP.md','THIRD_PARTY_NOTICES.md'):
    shutil.copy2(root/name,stage/name)
(stage/'native').mkdir(exist_ok=True)
shutil.copy2(root/'native/LinuxWindow.cpp',stage/'native/LinuxWindow.cpp')
# The shared version is authoritative for desktop metadata as well.
import re
metadata=stage/'packaging/linux/io.github.MaDaAi.MultiMini.metainfo.xml'
metadata.write_text(re.sub(r'release version="[^"]+"', 'release version="'+(root/'VERSION').read_text().strip()+'"', metadata.read_text()))
# Pure Python X11 dependencies: immutable PyPI URLs and verified hashes below.
vendor=stage/'python/vendor'
vendor.mkdir(parents=True,exist_ok=True)
wheels = [
    ("https://files.pythonhosted.org/packages/fc/b8/ff33610932e0ee81ae7f1269c890f697d56ff74b9f5b2ee5d9b7fa2c5355/python_xlib-0.33-py2.py3-none-any.whl", "c3534038d42e0df2f1392a1b30a15a4ff5fdc2b86cfa94f072bf11b10a164398"),
    ("https://files.pythonhosted.org/packages/b7/ce/149a00dd41f10bc29e5921b496af8b574d8413afcd5e30dfa0ed46c2cc5e/six-1.17.0-py2.py3-none-any.whl", "4721f391ed90541fddacab5acf947aa0d3dc7d27b2e1e8eda2be8970586c3274")
]
for url, expected in wheels:
    data=urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError("Build dependency checksum mismatch")
    import zipfile, io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        archive.extractall(vendor)
print(stage)
