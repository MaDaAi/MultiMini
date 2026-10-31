from __future__ import annotations

import os
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

RETENTION_SECONDS = 7 * 24 * 60 * 60


class SnapshotStore:
    def __init__(self) -> None:
        self.directory = Path(tempfile.gettempdir()) / "multimin" / "snapshots"
        self.directory.mkdir(parents=True, exist_ok=True)

    def capture_temporary(self) -> Path:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        output = self.directory / f"snapshot-{timestamp}.png"
        try:
            from PIL import ImageGrab
            ImageGrab.grab(all_screens=True).save(output, "PNG")
        except (ImportError, OSError) as error:
            raise RuntimeError("Screen capture needs Pillow and OS capture permission") from error
        return output

    def cleanup(self) -> int:
        cutoff = time.time() - RETENTION_SECONDS
        deleted = 0
        for snapshot in self.directory.glob("snapshot-*.png"):
            try:
                if snapshot.stat().st_mtime < cutoff:
                    snapshot.unlink()
                    deleted += 1
            except OSError:
                continue
        return deleted
