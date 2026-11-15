"""In-app Flatpak updates through the Flatpak portal (org.freedesktop.portal.Flatpak).

The app never runs host commands. It asks the portal to update its own
installation from the configured Flatpak remote (the signed MultiMini repo on
GitHub Pages), reports progress, and finally asks the portal to start the
newest installed version. Installs from a bundle without an update source fail
with a clear message; they need one install of the .flatpakref.
"""
from __future__ import annotations

import threading

BUS = "org.freedesktop.portal.Flatpak"
PATH = "/org/freedesktop/portal/Flatpak"
MONITOR = "org.freedesktop.portal.Flatpak.UpdateMonitor"
STATUS_RUNNING, STATUS_EMPTY, STATUS_DONE, STATUS_FAILED = 0, 1, 2, 3
SPAWN_LATEST_VERSION = 2
FLATPAKREF = "https://madaai.github.io/MultiMini/multimini.flatpakref"
NO_SOURCE_HINT = ("This copy was installed from a file without an update source. Install once with:\n"
                  f"flatpak install --user --reinstall {FLATPAKREF}\n"
                  "After that MultiMini updates itself. Your saved data is kept.")


def describe_failure(message):
    text = (message or "").lower()
    if any(word in text for word in ("remote", "origin", "no such ref", "not installed", "not found")):
        return NO_SOURCE_HINT
    if "permission" in text:
        return "This update needs new permissions, so Flatpak must install it: flatpak update io.github.MaDaAi.MultiMini"
    return message or "Flatpak could not update MultiMini"


class FlatpakUpdater:
    def __init__(self, bus, gio, glib, emit):
        self.bus, self.Gio, self.GLib, self.emit = bus, gio, glib, emit
        self.lock = threading.Lock()
        self.running = False

    def _event(self, state, message="", progress=0):
        import base64
        self.emit({"event": "flatpak_update", "state": state, "progress": int(progress),
                   "message64": base64.b64encode(str(message).encode()).decode("ascii")})

    def update(self):
        with self.lock:
            if self.running:
                return
            self.running = True
        threading.Thread(target=self._run, name="multimini-flatpak-update", daemon=True).start()

    def _run(self):
        handle = None
        sub = None
        done = threading.Event()
        try:
            handle = self.bus.call_sync(BUS, PATH, BUS, "CreateUpdateMonitor",
                                        self.GLib.Variant("(a{sv})", ({},)), None,
                                        self.Gio.DBusCallFlags.NONE, 10000, None).unpack()[0]

            def progress(_bus, _sender, _path, _iface, _signal, params):
                info = params.unpack()[0]
                status = int(info.get("status", STATUS_RUNNING))
                if status == STATUS_RUNNING:
                    self._event("progress", "", info.get("progress", 0))
                    return
                if status == STATUS_DONE:
                    self._event("done", "", 100)
                elif status == STATUS_EMPTY:
                    self._event("none", "MultiMini is already up to date in Flatpak")
                else:
                    self._event("failed", describe_failure(info.get("error_message", "") or info.get("error", "")))
                done.set()

            sub = self.bus.signal_subscribe(BUS, MONITOR, "Progress", handle, None,
                                            self.Gio.DBusSignalFlags.NONE, progress)
            self.bus.call_sync(BUS, handle, MONITOR, "Update", self.GLib.Variant("(sa{sv})", ("", {})), None,
                               self.Gio.DBusCallFlags.NONE, 30000, None)
            if not done.wait(1800):
                self._event("failed", "Flatpak update timed out")
        except Exception as error:
            self._event("failed", describe_failure(str(error)))
        finally:
            if sub is not None:
                self.bus.signal_unsubscribe(sub)
            if handle:
                try:
                    self.bus.call_sync(BUS, handle, MONITOR, "Close", None, None,
                                       self.Gio.DBusCallFlags.NONE, 5000, None)
                except Exception:
                    pass
            with self.lock:
                self.running = False

    def restart(self, command="multimini"):
        """Start the newest installed version in a fresh sandbox."""
        argv = [command.encode() + b"\0"]
        self.bus.call_with_unix_fd_list_sync(
            BUS, PATH, BUS, "Spawn",
            self.GLib.Variant("(ayaaya{uh}a{ss}ua{sv})", (b"/\0", argv, {}, {}, SPAWN_LATEST_VERSION, {})),
            None, self.Gio.DBusCallFlags.NONE, 10000, self.Gio.UnixFDList.new(), None)
