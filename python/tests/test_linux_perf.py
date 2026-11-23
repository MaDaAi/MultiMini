import contextlib
import ctypes
import importlib
import os
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import Mock, patch

from multimin_backend import linux_portal
from multimin_backend.linux_portal import FrameRing, packed_bgra
from multimin_backend.linux_service import KWIN_HANDLE_BASE, LinuxService, _preload

try:
    from multimin_backend import linux_xshm
    from multimin_backend.linux_xshm import XShmGrabber
    _XSHM_IMPORT_ERROR = None
except Exception as error:  # pragma: no cover - depends on platform
    linux_xshm = None
    XShmGrabber = None
    _XSHM_IMPORT_ERROR = repr(error)


class FrameRingTests(unittest.TestCase):
    def test_take_returns_bytearrays_of_requested_length(self):
        ring = FrameRing(3)
        buffer = ring.take(12)
        self.assertIsInstance(buffer, bytearray)
        self.assertEqual(len(buffer), 12)

    def test_cycles_through_distinct_buffers(self):
        ring = FrameRing(3)
        first, second, third = (ring.take(8) for _ in range(3))
        self.assertEqual(len({id(first), id(second), id(third)}), 3)
        fourth = ring.take(8)
        self.assertIs(fourth, first)

    def test_reallocates_when_length_changes(self):
        ring = FrameRing(3)
        old = ring.take(8)
        new = ring.take(16)
        self.assertEqual(len(new), 16)
        self.assertIsNot(new, old)
        self.assertNotIn(id(old), {id(b) for b in ring.buffers})
        self.assertEqual({len(b) for b in ring.buffers}, {16})
        self.assertEqual(len(ring.buffers), 3)


class PackedRingTests(unittest.TestCase):
    def test_packed_memoryview_with_offset_copied_into_ring_buffer(self):
        data = bytearray(b"xxxx" + bytes(range(16)) + b"yy")
        ring = FrameRing(3)
        result = packed_bgra(memoryview(data), 2, 2, 8, 4, ring)
        self.assertIsInstance(result, bytearray)
        self.assertIs(result, ring.buffers[0])
        self.assertEqual(bytes(result), bytes(range(16)))

    def test_immutable_bytes_at_offset_zero_is_not_copied(self):
        pixels = bytes(range(24))
        self.assertIs(packed_bgra(pixels, 3, 2, 12, 0, FrameRing()), pixels)

    def test_padded_stride_with_ring_joins_rows(self):
        data = b"xxabcdefghPADijklmnopPAD"
        self.assertEqual(packed_bgra(data, 2, 2, 11, 2, FrameRing()), b"abcdefghijklmnop")

    def test_ring_none_keeps_bytes_result(self):
        result = packed_bgra(memoryview(bytearray(range(16))), 2, 2, 8, 0, None)
        self.assertIsInstance(result, bytes)
        self.assertEqual(result, bytes(range(16)))


@unittest.skipIf(XShmGrabber is None, f"linux_xshm not importable here: {_XSHM_IMPORT_ERROR}")
class XShmCopyOutTests(unittest.TestCase):
    def _grabber(self, source):
        # Keep the ctypes view alive on the test object so the address stays valid.
        self._source_view = (ctypes.c_char * len(source)).from_buffer(source)
        grabber = XShmGrabber.__new__(XShmGrabber)
        grabber._ring = []
        grabber._ring_index = 0
        grabber.info = types.SimpleNamespace(shmaddr=ctypes.addressof(self._source_view))
        return grabber

    def test_copies_segment_content_into_bytearray(self):
        source = bytearray(b"ABCDEFGH")
        grabber = self._grabber(source)
        result = grabber._copy_out(8)
        self.assertIsInstance(result, bytearray)
        self.assertEqual(bytes(result), b"ABCDEFGH")

    def test_three_distinct_buffers_rotate(self):
        source = bytearray(4)
        grabber = self._grabber(source)
        results = []
        for frame in range(4):
            source[:] = bytes([frame]) * 4  # the server writes new pixels each frame
            results.append(grabber._copy_out(4))
        self.assertEqual(len({id(r) for r in results[:3]}), 3)
        self.assertIs(results[3], results[0])
        self.assertEqual(len(grabber._ring), XShmGrabber.RING_SIZE)
        self.assertEqual(bytes(results[3]), bytes([3]) * 4)

    def test_size_change_reallocates_ring_and_copies_new_size(self):
        source = bytearray(b"12345678")
        grabber = self._grabber(source)
        small = grabber._copy_out(4)
        old_ids = {id(buffer) for buffer, _view in grabber._ring}
        self.assertEqual(bytes(small), b"1234")
        big = grabber._copy_out(8)
        self.assertEqual(bytes(big), b"12345678")
        self.assertEqual(len(big), 8)
        self.assertEqual({len(buffer) for buffer, _view in grabber._ring}, {8})
        self.assertTrue({id(buffer) for buffer, _view in grabber._ring}.isdisjoint(old_ids))


class FakePortal:
    def __init__(self):
        self.closed = False

    def capture(self, *args, **kwargs):  # pragma: no cover - not used here
        raise AssertionError("no capture expected")

    def close(self):
        self.closed = True


class LinuxServicePerfTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"XDG_DATA_HOME": self.temp.name, "LOCALAPPDATA": self.temp.name,
                                           "XDG_SESSION_TYPE": "wayland", "DISPLAY": ""})
        self.env.start()
        self.legacy_temp = patch("multimin_backend.snapshots.tempfile.gettempdir", return_value=self.temp.name)
        self.legacy_temp.start()
        self.not_mac = patch("multimin_backend.snapshots.IS_MAC", False)
        self.not_mac.start()
        self.events = []
        self.service = LinuxService(self.events.append, FakePortal())

    def tearDown(self):
        self.service.dispatch({"command": "shutdown"})
        self.not_mac.stop()
        self.legacy_temp.stop()
        self.env.stop()
        self.temp.cleanup()

    def test_kwin_input_check_runs_on_background_thread_with_target(self):
        target = KWIN_HANDLE_BASE + 1
        self.service.kwin_handles[target] = "token"
        self.service.target = target
        seen = {}
        done = threading.Event()

        def fake_check(expected_target=None):
            seen["thread"] = threading.current_thread()
            seen["args"] = (expected_target,)
            done.set()

        with patch.object(self.service, "_check_input", side_effect=fake_check) as check:
            reply = self.service.dispatch({"command": "linux_check_input"})
            self.assertTrue(done.wait(2), "background input check never ran")
            check.assert_called_once_with(target)
        self.assertEqual(reply, {"event": "input_checked"})
        self.assertIsNot(seen["thread"], threading.current_thread())
        self.assertEqual(seen["thread"].name, "multimini-input-check")
        self.assertEqual(seen["args"], (target,))

    def test_kwin_input_check_not_restarted_while_running(self):
        target = KWIN_HANDLE_BASE + 1
        self.service.kwin_handles[target] = "token"
        self.service.target = target
        gate = threading.Event()
        calls = []

        def slow_check(expected_target=None):
            calls.append(expected_target)
            gate.wait(2)

        with patch.object(self.service, "_check_input", side_effect=slow_check):
            self.service.dispatch({"command": "linux_check_input"})
            for _ in range(200):
                if calls:
                    break
                time.sleep(.01)
            self.assertTrue(self.service._input_check_running)
            # A second request while the first is still in flight must not start a thread.
            self.service.dispatch({"command": "linux_check_input"})
            gate.set()
            for _ in range(200):
                if not self.service._input_check_running:
                    break
                time.sleep(.01)
        self.assertEqual(calls, [target])
        self.assertFalse(self.service._input_check_running)

    def test_non_kwin_input_check_runs_synchronously_without_argument(self):
        self.service.target = 42
        self.assertNotIn(42, self.service.kwin_handles)
        caller = threading.current_thread()
        seen = {}

        def fake_check(*args, **kwargs):
            seen["thread"] = threading.current_thread()
            seen["args"] = args
            seen["kwargs"] = kwargs

        with patch.object(self.service, "_check_input", side_effect=fake_check) as check:
            self.service.dispatch({"command": "linux_check_input"})
            check.assert_called_once_with()
        self.assertIs(seen["thread"], caller)
        self.assertEqual(seen["args"], ())
        self.assertEqual(seen["kwargs"], {})
        self.assertFalse(getattr(self.service, "_input_check_running", False))

    def test_stale_kwin_check_does_not_deactivate_after_target_change(self):
        old_target = KWIN_HANDLE_BASE + 1
        new_target = KWIN_HANDLE_BASE + 2
        self.service.enabled = True
        self.service.selecting = False
        self.service.permissions_pending = False
        self.service.input_mode = "native"
        self.service.kwin_handles[new_target] = "token-new"
        self.service.target = new_target
        self.service.kwin = Mock()
        self.service.kwin.get.return_value = None
        with patch.object(self.service, "_deactivate_native") as deactivate:
            self.service._check_input(old_target)
        deactivate.assert_not_called()
        self.assertFalse(any(e.get("event") == "linux_target_lost" for e in self.events))

    def test_current_kwin_check_with_lost_target_still_deactivates(self):
        # Control for the test above: same target, lost focus, must deactivate.
        target = KWIN_HANDLE_BASE + 2
        self.service.enabled = True
        self.service.selecting = False
        self.service.permissions_pending = False
        self.service.input_mode = "native"
        self.service.kwin_handles[target] = "token"
        self.service.target = target
        self.service.kwin = Mock()
        self.service.kwin.get.return_value = None
        with patch.object(self.service, "_deactivate_native") as deactivate:
            self.service._check_input(target)
        deactivate.assert_called_once_with()
        self.assertTrue(any(e.get("event") == "linux_target_lost" for e in self.events))


class PreloadTests(unittest.TestCase):
    def test_preload_swallows_import_errors_for_every_module(self):
        with contextlib.ExitStack() as stack:
            # Patch by module object: string targets are resolved through importlib,
            # which would itself hit the mock.
            imp = stack.enter_context(patch.object(importlib, "import_module", side_effect=ImportError("missing")))
            stack.enter_context(patch.object(linux_portal, "video_module", side_effect=ImportError("no gi")))
            if linux_xshm is not None:
                stack.enter_context(patch.object(linux_xshm, "_load", side_effect=ImportError("no xshm")))
            self.assertIsNone(_preload())
        attempted = {call.args[0] for call in imp.call_args_list}
        self.assertEqual(attempted, {"multimin_backend.linux_native_input", "multimin_backend.linux_hotkeys",
                                     "Xlib.ext.shape", "Xlib.protocol.event", "Xlib.XK"})


if __name__ == "__main__":
    unittest.main()
