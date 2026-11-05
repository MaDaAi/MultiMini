from __future__ import annotations

import os
import shutil
import tempfile
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

RETENTION_SECONDS = 7 * 24 * 60 * 60

class SnapshotStore:
    def __init__(self, directory: Path | None = None) -> None:
        self.directory = directory or Path(os.environ.get("LOCALAPPDATA", str(Path.home() / ".local" / "share"))) / "MultiMin" / "screenshots"
        self.directory.mkdir(parents=True, exist_ok=True)
        if directory is None:
            legacy = Path(tempfile.gettempdir()) / "multimin" / "snapshots"
            with self.locked():
                for photo in legacy.glob("snapshot-*.png"):
                    try:
                        if photo.is_symlink():
                            continue
                        destination = self.directory / photo.name
                        if not destination.exists():
                            shutil.copy2(photo, destination)
                            photo.unlink()
                    except OSError:
                        continue

    @contextmanager
    def locked(self):
        # Shared with Java: keeping a photo and expiry deletion are atomic.
        with (self.directory / ".screenshots.lock").open("a+b") as lock:
            if lock.seek(0, 2) == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                lock.seek(0)
                if os.name == "nt":
                    msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def capture_temporary(self) -> Path:
        output = self.directory / f"snapshot-{time.time_ns()}-{uuid.uuid4().hex[:8]}.png"
        temporary = output.with_suffix(".tmp")
        try:
            from PIL import ImageGrab
            with ImageGrab.grab(all_screens=True) as image:
                image.save(temporary, "PNG")
            with self.locked():
                os.replace(temporary, output)
        except (ImportError, OSError) as error:
            temporary.unlink(missing_ok=True)
            raise RuntimeError("Screenshot could not be saved. Check Pillow, screen-capture permission and disk space.") from error
        return output

    def cleanup(self) -> int:
        deleted = 0
        with self.locked():
            for snapshot in self.directory.glob("snapshot-*.png"):
                try:
                    if not snapshot.is_symlink() and not snapshot.with_suffix(".png.keep").exists() and snapshot.stat().st_mtime < time.time() - RETENTION_SECONDS:
                        snapshot.unlink()
                        deleted += 1
                except OSError:
                    continue
        return deleted
