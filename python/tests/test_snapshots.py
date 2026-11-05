import base64
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock
from multimin_backend.snapshots import SnapshotStore, RETENTION_SECONDS
from multimin_backend.service import CaptureService

class ScreenshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = SnapshotStore(Path(self.temp.name))

    def test_cleanup_keeps_pinned_and_fresh_photos(self):
        for name in ('expired', 'kept', 'fresh'):
            photo = self.store.directory / f'snapshot-{name}.png'
            photo.write_bytes(b'png')
            if name != 'fresh':
                old = time.time() - RETENTION_SECONDS - 1
                os.utime(photo, (old, old))
        (self.store.directory / 'snapshot-kept.png.keep').write_text('keep')
        self.assertEqual(self.store.cleanup(), 1)
        self.assertTrue((self.store.directory / 'snapshot-kept.png').exists())
        self.assertTrue((self.store.directory / 'snapshot-fresh.png').exists())

    def test_capture_names_are_unique_and_published_after_save(self):
        image = MagicMock()
        image.__enter__.return_value = image
        image.save.side_effect = lambda path, fmt: Path(path).write_bytes(b'png')
        with patch.dict('sys.modules', {'PIL': MagicMock(ImageGrab=MagicMock(grab=MagicMock(return_value=image)))}):
            first = self.store.capture_temporary()
            second = self.store.capture_temporary()
        self.assertNotEqual(first, second)
        self.assertEqual(first.read_bytes(), b'png')
        self.assertFalse(list(self.store.directory.glob('*.tmp')))

    def test_failed_capture_does_not_publish_partial_photo(self):
        with patch.dict('sys.modules', {'PIL': MagicMock(ImageGrab=MagicMock(grab=MagicMock(side_effect=OSError('denied'))))}):
            with self.assertRaises(RuntimeError):
                self.store.capture_temporary()
        self.assertFalse(list(self.store.directory.glob('snapshot-*')))

    def test_service_reports_real_success_and_error(self):
        service = CaptureService.__new__(CaptureService)
        service.snapshots = MagicMock()
        path = self.store.directory / 'snapshot-unicode-test.png'
        service.snapshots.capture_temporary.return_value = path
        event = service.dispatch({'command':'snapshot'})
        self.assertEqual(event['event'], 'snapshot_created')
        self.assertEqual(base64.b64decode(event['path64']).decode(), str(path))
        service.snapshots.capture_temporary.side_effect = RuntimeError('denied')
        self.assertEqual(service.dispatch({'command':'snapshot'})['event'], 'snapshot_error')

    def test_overlay_region_command_does_not_set_input_target(self):
        service = CaptureService.__new__(CaptureService)
        service.native_input = MagicMock()
        service.dispatch({'command':'set_overlay_control_region','x':1,'y':2,'width':38,'height':38})
        service.native_input.set_overlay_control_region.assert_called_once_with((1,2,38,38))
        service.native_input.set_mouse_target.assert_not_called()
        service.native_input.focus_window.assert_not_called()
