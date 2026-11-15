#!/usr/bin/env python3
"""Check changing XWayland frames from controlled OpenGL and Vulkan renderers.

Only windows whose _NET_WM_PID (or XRes client PID when that property is absent)
matches a renderer process started by this script are considered. This verifies X11/XWayland drawable capture for these
test programs; it does not establish ordinary game compatibility or Wayland
capture support.
"""
import hashlib
import argparse
import os
import shutil
import subprocess
import sys
import threading
import time

from Xlib import X, Xatom, XK, display
from Xlib.ext import res
from Xlib.ext import xtest

from multimin_backend.linux_x11 import X11Capture


def own_window(owner, pid, deadline):
    root = owner.screen().root
    atom = owner.intern_atom("_NET_CLIENT_LIST")
    pid_atom = owner.intern_atom("_NET_WM_PID")
    while time.monotonic() < deadline:
        clients = root.get_full_property(atom, X.AnyPropertyType)
        if clients is not None:
            for xid in clients.value:
                try:
                    window = owner.create_resource_object("window", int(xid))
                    prop = window.get_full_property(pid_atom, X.AnyPropertyType)
                    source = "_NET_WM_PID"
                    actual_pid = int(prop.value[0]) if prop is not None and len(prop.value) else None
                    if actual_pid is None and owner.has_extension("X-Resource"):
                        # Some small Xlib clients (including glxgears) omit the
                        # EWMH PID property. XRes identifies the process owning
                        # this specific window resource without title matching.
                        try:
                            reply = owner.res_query_client_ids([
                                {"client": int(xid), "mask": res.LocalClientPIDMask}
                            ])
                            for client_id in reply.ids:
                                if client_id.spec.mask & res.LocalClientPIDMask and client_id.value:
                                    actual_pid = int(client_id.value[0])
                                    source = "XRes LocalClientPID"
                                    break
                        except Exception:
                            # Older servers may expose X-Resource without the
                            # v1.2 client-ID request; continue waiting safely.
                            pass
                    if actual_pid == pid:
                        attrs = window.get_attributes()
                        geom = window.get_geometry()
                        if attrs.map_state == X.IsViewable and geom.width > 0 and geom.height > 0:
                            point = root.translate_coords(window, 0, 0)
                            return int(xid), (int(point.x), int(point.y), geom.width, geom.height), source
                except Exception:
                    continue
        time.sleep(.1)
    raise RuntimeError(f"No mapped X11 window with _NET_WM_PID={pid} appeared")


def process_owns_window(owner, xid, pid):
    win = owner.create_resource_object("window", int(xid))
    prop = win.get_full_property(owner.intern_atom("_NET_WM_PID"), X.AnyPropertyType)
    if prop is not None and len(prop.value):
        return int(prop.value[0]) == int(pid)
    if not owner.has_extension("X-Resource"):
        return False
    try:
        reply = owner.res_query_client_ids([
            {"client": int(xid), "mask": res.LocalClientPIDMask}
        ])
        return any(client_id.spec.mask & res.LocalClientPIDMask and client_id.value
                   and int(client_id.value[0]) == int(pid) for client_id in reply.ids)
    except Exception:
        return False


def native_escape_check(owner, process, target, pid):
    """Send Escape through XTest only while this exact owned target has focus."""
    from multimin_backend.linux_native_input import NativeInput

    if not owner.has_extension("XTEST"):
        raise RuntimeError("XTest extension unavailable; native input check cannot run")
    if not process_owns_window(owner, target, pid):
        raise RuntimeError("Renderer window ownership changed before native input check")
    target_win = owner.create_resource_object("window", int(target))
    root = owner.screen().root
    geom = target_win.get_geometry()
    point = root.translate_coords(target_win, 0, 0)
    rect = (int(point.x), int(point.y), int(geom.width), int(geom.height))
    screen = owner.screen()
    overlay_width = min(screen.width_in_pixels, max(rect[2] + 48, rect[2] * 2))
    overlay_height = min(screen.height_in_pixels, max(rect[3] + 48, rect[3] * 2))
    if overlay_width <= rect[2] or overlay_height <= rect[3]:
        raise RuntimeError("Display is too small to create a larger owned input overlay")
    overlay_x = max(0, min(rect[0] - 24, screen.width_in_pixels - overlay_width))
    overlay_y = max(0, min(rect[1] - 24, screen.height_in_pixels - overlay_height))
    overlay = root.create_window(overlay_x, overlay_y, overlay_width, overlay_height, 0,
        screen.root_depth, X.InputOutput, X.CopyFromParent, background_pixel=0x202020)
    overlay.change_property(owner.intern_atom("_NET_WM_PID"), Xatom.CARDINAL, 32, [int(pid)])
    overlay.map()
    owner.sync()

    router = NativeInput()
    try:
        router._connect()
        # Ensure the helper will identify only this script-owned overlay.
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                found, found_rect = router.find_overlay(pid)
                if found == overlay.id:
                    break
            except Exception:
                pass
            time.sleep(.05)
        else:
            raise RuntimeError("Window manager did not publish the owned input overlay")

        router.activate(target, rect, app_pid=pid)
        focused = NativeInput._focus_id(owner.get_input_focus().focus)
        if focused != target:
            raise RuntimeError(f"Renderer did not retain focus before Escape: {focused} != {target}")
        if not process_owns_window(owner, target, pid) or process.poll() is not None:
            raise RuntimeError("Renderer ownership or process state changed before Escape")
        keycode = owner.keysym_to_keycode(XK.string_to_keysym("Escape"))
        if not keycode:
            raise RuntimeError("Escape has no X11 keycode")
        release_unverified = False
        key_pressed = False
        input_error = None
        try:
            # Verify before key down. Always release the simulated key
            # afterward, even if a later ownership/focus check fails.
            xtest.fake_input(owner, X.KeyPress, detail=keycode)
            key_pressed = True
            owner.sync()
        except Exception as exc:
            input_error = exc
        finally:
            if key_pressed:
                target_safe = False
                try:
                    target_safe = (process.poll() is None
                        and process_owns_window(owner, target, pid)
                        and NativeInput._focus_id(owner.get_input_focus().focus) == target)
                except Exception:
                    pass
                if not target_safe:
                    try:
                        if not process_owns_window(owner, overlay.id, pid):
                            raise RuntimeError("Owned overlay PID check failed")
                        overlay.set_input_focus(X.RevertToPointerRoot, X.CurrentTime)
                        owner.sync()
                        if (not process_owns_window(owner, overlay.id, pid)
                                or NativeInput._focus_id(owner.get_input_focus().focus) != overlay.id):
                            raise RuntimeError("Owned overlay did not retain focus")
                    except Exception as exc:
                        release_unverified = True
                        print(f"WARNING: could not verify owned overlay focus for Escape release: {exc}", file=sys.stderr)
                # Release even if focus could not be verified, avoiding a stuck
                # global XTest key state; the warning records the routing risk.
                try:
                    xtest.fake_input(owner, X.KeyRelease, detail=keycode)
                    owner.sync()
                except Exception as exc:
                    input_error = input_error or RuntimeError(f"Could not release XTest Escape key: {exc}")
        if input_error:
            raise RuntimeError(f"Native Escape injection failed: {input_error}")
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Renderer did not exit after focused Escape press/release")
        if process.returncode != 0:
            raise RuntimeError(f"Renderer exited after Escape with status {process.returncode}")
        if release_unverified:
            raise RuntimeError("Renderer exited after Escape, but key release focus could not be verified")
        print(f"PASS: native Escape press/release reached owned renderer window {target}; process exited")
    finally:
        try:
            try:
                router.close()
            except Exception as exc:
                # A renderer that exits on Escape destroys its target before
                # NativeInput can restore its original geometry. The overlay
                # is removed below, so restoration of that vanished window is
                # unnecessary; preserve other cleanup failures.
                target_gone = False
                try:
                    owner.create_resource_object("window", int(target)).get_attributes()
                except Exception:
                    target_gone = True
                if process.returncode == 0 and target_gone:
                    print("INFO: renderer exited; skipped restoration of its destroyed X11 window")
                else:
                    raise exc
        finally:
            try:
                overlay.destroy()
                owner.sync()
            except Exception:
                pass


def check_renderer(label, executable, args, owner, native_input=False):
    path = shutil.which(executable)
    if not path:
        raise RuntimeError(f"Required test renderer not installed: {executable}")
    env = os.environ.copy()
    # Force clients through XWayland even when the desktop is Wayland.
    env.pop("WAYLAND_DISPLAY", None)
    env["DISPLAY"] = owner.get_display_name()
    process = subprocess.Popen([path, *args], env=env, stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    capture = None
    try:
        xid, initial_rect, pid_source = own_window(owner, process.pid, time.monotonic() + 15)
        frames = []
        errors = []
        ready = threading.Event()

        def publish(width, height, pixels):
            frames.append((width, height, hashlib.sha256(pixels).digest()))
            ready.set()

        capture = X11Capture(xid, publish, errors.append, lambda: 30)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"{label} exited with status {process.returncode}")
            if len({frame[2] for frame in frames}) >= 3 and len(frames) >= 4:
                break
            ready.wait(.1)
            ready.clear()
        unique = len({frame[2] for frame in frames})
        if len(frames) < 4 or unique < 3:
            detail = errors[-1] if errors else "no changing frames observed"
            raise RuntimeError(f"{label} capture insufficient: {len(frames)} frames, {unique} hashes; {detail}")
        width, height = frames[-1][:2]
        if (width, height) != initial_rect[2:]:
            raise RuntimeError(f"{label} size changed unexpectedly: {initial_rect[2:]} -> {(width, height)}")
        print(f"PASS: {label} X11/XWayland test renderer, {width}x{height}, "
              f"{len(frames)} frames, {unique} distinct frame hashes; ownership via {pid_source}")
        if native_input:
            capture.close()
            capture = None
            native_escape_check(owner, process, xid, process.pid)
    finally:
        if capture:
            capture.close()
        # This process is the only process this script is permitted to stop.
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-input", action="store_true",
                        help="also focus each owned renderer through a script-owned overlay and send Escape")
    options = parser.parse_args()
    if not os.environ.get("DISPLAY"):
        raise RuntimeError("DISPLAY is required for X11/XWayland renderer checks")
    owner = display.Display()
    try:
        check_renderer("OpenGL glxgears", "glxgears", ["-geometry", "320x240+40+40"], owner,
                       native_input=options.native_input)
        check_renderer("Vulkan vkcube (xcb WSI)", "vkcube",
                       ["--wsi", "xcb", "--width", "320", "--height", "240"], owner,
                       native_input=options.native_input)
    finally:
        owner.close()
    print("Scope: controlled test renderers only; this does not verify ordinary games, "
          "graphics API hooking, native Wayland capture, or universal compatibility.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
