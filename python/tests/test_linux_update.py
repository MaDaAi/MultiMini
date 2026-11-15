import base64
import threading
import unittest

from multimin_backend.linux_update import FlatpakUpdater, describe_failure, NO_SOURCE_HINT, SPAWN_LATEST_VERSION


class Variant:
    def __init__(self, sig, value):
        self.sig, self.value = sig, value
    def unpack(self):
        return self.value


class GLib:
    Variant = Variant


class Gio:
    class DBusCallFlags:
        NONE = 0
    class DBusSignalFlags:
        NONE = 0
    class UnixFDList:
        @staticmethod
        def new():
            return "fds"


class Bus:
    def __init__(self, final):
        self.final, self.calls, self.handler = final, [], None
    def call_sync(self, dest, path, iface, method, params, *_):
        self.calls.append(method)
        if method == "CreateUpdateMonitor":
            return Variant("(o)", ("/org/freedesktop/portal/Flatpak/update_monitor/1/1",))
        if method == "Update":
            self.handler(None, None, None, None, "Progress", Variant("(a{sv})", ({"status": 0, "progress": 40},)))
            self.handler(None, None, None, None, "Progress", Variant("(a{sv})", (self.final,)))
        return Variant("()", ())
    def signal_subscribe(self, *args):
        self.handler = args[-1]
        return 7
    def signal_unsubscribe(self, sub):
        self.calls.append("unsubscribe")
    def call_with_unix_fd_list_sync(self, dest, path, iface, method, params, *_):
        self.calls.append((method, params.value))


class FlatpakUpdateTests(unittest.TestCase):
    def run_update(self, final):
        events, done = [], threading.Event()
        def emit(event):
            events.append(event)
            if event["state"] != "progress":
                done.set()
        bus = Bus(final)
        FlatpakUpdater(bus, Gio, GLib, emit).update()
        self.assertTrue(done.wait(2))
        for _ in range(100):
            if "Close" in bus.calls:
                break
            threading.Event().wait(.01)
        return events, bus

    def test_progress_then_done_and_monitor_closed(self):
        events, bus = self.run_update({"status": 2})
        self.assertEqual([e["state"] for e in events], ["progress", "done"])
        self.assertEqual(events[0]["progress"], 40)
        self.assertIn("Close", bus.calls)
        self.assertLess(bus.calls.index("CreateUpdateMonitor"), bus.calls.index("Update"))

    def test_nothing_to_update(self):
        events, _ = self.run_update({"status": 1})
        self.assertEqual(events[-1]["state"], "none")

    def test_bundle_without_remote_explains_one_time_install(self):
        events, _ = self.run_update({"status": 3, "error_message": "No remote refs found for 'multimini-origin'"})
        self.assertEqual(events[-1]["state"], "failed")
        self.assertEqual(base64.b64decode(events[-1]["message64"]).decode(), NO_SOURCE_HINT)

    def test_failure_text_mapping(self):
        self.assertIn("flatpak update", describe_failure("needs more permissions"))
        self.assertEqual(describe_failure("disk full"), "disk full")

    def test_restart_spawns_latest_version(self):
        bus = Bus({})
        FlatpakUpdater(bus, Gio, GLib, lambda e: None).restart()
        method, params = bus.calls[-1]
        self.assertEqual(method, "Spawn")
        self.assertEqual(params[1], [b"multimini\0"])
        self.assertEqual(params[4], SPAWN_LATEST_VERSION)


if __name__ == "__main__":
    unittest.main()
