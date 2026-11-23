import contextlib
import ctypes
import importlib
import os
import sys
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


from multimin_backend import linux_x11
from multimin_backend.linux_x11 import DamageProbe, X11Capture, hold_for_publish


class HoldForPublishTests(unittest.TestCase):
    def test_no_wait_when_far_enough_apart(self):
        self.assertEqual(hold_for_publish(10.0, 0.0167, 10.02), 0.0)

    def test_waits_for_the_remainder_of_the_spacing(self):
        wait = hold_for_publish(10.0, 0.020, 10.005)
        self.assertAlmostEqual(wait, 0.020 * linux_x11.PUBLISH_SPACING - 0.005, places=6)

    def test_first_frame_never_waits(self):
        self.assertEqual(hold_for_publish(0.0, 0.0167, time.monotonic()), 0.0)

    def test_spacing_is_at_least_one_period(self):
        # linux_service pushes its deadline a full period ahead per accepted frame;
        # frames closer than a period make it drop one every few frames.
        self.assertGreaterEqual(linux_x11.PUBLISH_SPACING, 1.0)


class DamageProbeTests(unittest.TestCase):
    def test_first_look_is_immediate(self):
        self.assertEqual(DamageProbe().due_in(5.0), 0.0)

    def test_idle_window_backs_off_to_the_longest_delay(self):
        probe = DamageProbe(first=0.1, longest=1.0)
        now = 0.0
        delays = []
        for _ in range(6):
            probe.probed(now, changed=False, explained=False)
            delays.append(probe.delay)
            now += probe.delay
        self.assertEqual(delays, [0.2, 0.4, 0.8, 1.0, 1.0, 1.0])
        self.assertTrue(probe.trusted)

    def test_damage_resets_the_wait(self):
        probe = DamageProbe()
        probe.probed(0.0, changed=False, explained=False)
        probe.probed(0.2, changed=False, explained=False)
        probe.damaged(1.0)
        self.assertAlmostEqual(probe.due_in(1.0), probe.first)
        self.assertAlmostEqual(probe.due_in(1.0 + probe.first), 0.0)

    def test_change_explained_by_damage_is_not_a_surprise(self):
        probe = DamageProbe(limit=2)
        for _ in range(10):
            probe.probed(0.0, changed=True, explained=True)
        self.assertTrue(probe.trusted)

    def test_unexplained_changes_stop_trusting_damage(self):
        probe = DamageProbe(limit=3)
        probe.probed(0.0, changed=True, explained=False)
        probe.probed(0.1, changed=True, explained=False)
        self.assertTrue(probe.trusted)
        probe.probed(0.2, changed=True, explained=False)
        self.assertFalse(probe.trusted)


class FakeXlib:
    """Just enough python-xlib for X11Capture's loop; no X server needed."""
    IsViewable = 2
    DestroyNotify = 17
    StructureNotifyMask = 1 << 17

    def install(self):
        fake = self
        window = types.SimpleNamespace(change_attributes=lambda **k: None,
                                       get_attributes=lambda: types.SimpleNamespace(map_state=fake.IsViewable))
        display_object = types.SimpleNamespace(create_resource_object=lambda *a: window, sync=lambda: None,
                                               pending_events=lambda: 0, close=lambda: None)
        module = types.ModuleType("Xlib")
        module.display = types.SimpleNamespace(Display=lambda: display_object)
        module.X = fake
        return patch.dict("sys.modules", {"Xlib": module})


class FakeGrabber:
    """Stands in for XShmGrabber; every grab returns what `frames` yields."""

    def __init__(self, frames, silent=True, delays=None):
        self.frames, self.silent, self.delays = frames, silent, delays
        self.grabs = 0
        self.event_driven = True
        self.closed = False

    def wait_for_change(self, timeout):
        if timeout:
            time.sleep(min(timeout, 0.01))
        return not self.silent

    def grab(self):
        self.grabs += 1
        if self.delays:
            time.sleep(self.delays[self.grabs % len(self.delays)])
        return 2, 2, bytearray(self.frames(self.grabs))

    def close(self):
        self.closed = True


def run_capture(grabber, seconds, fps=200):
    published = []
    fake = FakeXlib()
    with fake.install(), patch("multimin_backend.linux_xshm.XShmGrabber", lambda xid: grabber):
        capture = X11Capture(1, lambda w, h, px: published.append((time.perf_counter(), bytes(px))),
                             lambda message: None, lambda: fps)
        time.sleep(seconds)
        capture.close()
    return published


class X11CaptureLoopTests(unittest.TestCase):
    def test_idle_window_without_damage_is_shown_once_then_left_alone(self):
        grabber = FakeGrabber(lambda n: b"\x07" * 16)
        published = run_capture(grabber, 0.8)
        self.assertEqual(len(published), 1)  # a static window still gets its first frame
        self.assertLess(grabber.grabs, 6)  # and the probes back off instead of polling at the cap
        self.assertTrue(grabber.closed)

    def test_silent_damage_with_changing_pixels_falls_back_to_timer_pacing(self):
        grabber = FakeGrabber(lambda n: bytes([n % 250]) * 16)
        quick = lambda: DamageProbe(first=0.01, longest=0.05)  # real delays only matter for idle windows
        with patch.object(linux_x11, "DamageProbe", quick):
            published = run_capture(grabber, 1.0, fps=50)
        self.assertGreater(len(published), 8)  # keeps flowing instead of the 4 per second the damage timeout allowed
        self.assertLessEqual(len(published), 55)

    def test_working_damage_drives_every_grab(self):
        grabber = FakeGrabber(lambda n: bytes([n % 250]) * 16, silent=False)
        published = run_capture(grabber, 0.5, fps=40)
        self.assertGreater(len(published), 3)
        self.assertLessEqual(len(published), 22)

    @unittest.skipUnless(sys.platform.startswith("linux"), "needs a precise clock; shared Windows and macOS runners are too coarse")
    def test_jittery_grabs_are_never_published_early(self):
        # Grab times that shrink and grow must not produce frames closer together
        # than the service tolerates, or every early frame is dropped there.
        grabber = FakeGrabber(lambda n: bytes([n % 250]) * 16, silent=False,
                              delays=[0.0, 0.006, 0.001, 0.007, 0.0, 0.005])
        fps = 50
        published = run_capture(grabber, 1.0, fps=fps)
        times = [t for t, _ in published]
        gaps = [b - a for a, b in zip(times, times[1:])]
        self.assertGreater(len(gaps), 5)
        self.assertGreaterEqual(min(gaps), (1 / fps) * 0.999)
