import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch, Mock

from multimin_backend.linux_service import LinuxService, portal_trigger, KWIN_HANDLE_BASE
from multimin_backend.linux_x11 import map_point
from multimin_backend.platforms import service_class


class Capture:
    def __init__(self):
        self.closed = False
    def close(self):
        self.closed = True


class Portal:
    def __init__(self):
        self.started = threading.Event()
        self.release = threading.Event()
        self.capture_object = Capture()
    def capture(self, source_type, publish, error, cursor, restore_token=""):
        self.restore_token = restore_token
        self.publish = publish
        self.error = error
        self.source_type = source_type
        self.started.set()
        self.release.wait(2)
        return self.capture_object
    def close(self):
        self.release.set()


class LinuxTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"XDG_DATA_HOME": self.temp.name, "LOCALAPPDATA": self.temp.name, "XDG_SESSION_TYPE": "wayland", "DISPLAY": ""})
        self.env.start()
        self.legacy_temp=patch("multimin_backend.snapshots.tempfile.gettempdir",return_value=self.temp.name)
        self.legacy_temp.start()
        # These tests simulate Linux, including when the suite runs on macOS CI.
        self.not_mac=patch("multimin_backend.snapshots.IS_MAC",False)
        self.not_mac.start()
        self.events = []
        self.portal = Portal()
        self.service = LinuxService(self.events.append, self.portal)
    def tearDown(self):
        self.service.dispatch({"command": "shutdown"})
        self.legacy_temp.stop()
        self.not_mac.stop()
        self.env.stop()
        self.temp.cleanup()
    def test_platform_selection(self):
        self.assertIs(service_class("linux"), LinuxService)
        self.assertEqual(service_class("win32").__name__, "CaptureService")
        self.assertEqual(service_class("darwin").__name__, "MacService")
        with self.assertRaises(RuntimeError):
            service_class("sunos5")
    def test_capabilities_do_not_claim_wayland_input(self):
        caps = self.service.dispatch({"command": "capabilities"})
        self.assertTrue(caps["window"])
        self.assertEqual(caps["native_wayland_window_input"], bool(self.service.x11 and self.service.kwin))
        self.assertFalse(caps["game"])
    def test_configure_target_does_not_focus_or_enable_input(self):
        self.service.dispatch({"command": "set_input_target", "hwnd": 42})
        self.assertFalse(self.service.enabled)
        self.assertEqual(self.service.target, 42)
    def test_opacity_independent_of_input_and_visual_commands(self):
        self.service.dispatch({"command": "set_opacity", "value": 37})
        self.service.dispatch({"command": "set_input_target", "hwnd": 42})
        self.service.dispatch({"command": "set_layer_layout", "items": []})
        self.assertEqual(self.service.opacity, 37)
    def test_remove_during_picker_cannot_resurrect_source(self):
        self.service.dispatch({"command": "register_window_capture", "path": "fixture"})
        self.assertTrue(self.portal.started.wait(1))
        self.service.dispatch({"command": "remove_window_capture", "path": "fixture"})
        self.portal.release.set()
        for _ in range(100):
            if self.portal.capture_object.closed:
                break
            time.sleep(.005)
        self.assertTrue(self.portal.capture_object.closed)
        self.assertNotIn("fixture", self.service.captures)
        self.portal.publish(1, 1, b'\0'*4)
        self.assertNotIn("fixture", self.service.frame_stream._frames)
    def test_cancelled_picker_teardown_does_not_hold_producer_lock(self):
        stopped=threading.Event()
        def close():
            callback=threading.Thread(target=lambda:(self.portal.publish(1,1,b'\0'*4),stopped.set()))
            callback.start();callback.join(timeout=1)
            if stopped.is_set():self.portal.capture_object.closed=True
        self.portal.capture_object.close=close
        self.service.dispatch(dict(command='register_window_capture',path='fixture'))
        self.assertTrue(self.portal.started.wait(1))
        self.service.dispatch(dict(command='remove_window_capture',path='fixture'))
        self.portal.release.set()
        self.assertTrue(stopped.wait(2),'Teardown held the lock needed by the producer')
        deadline=time.monotonic()+1
        while not self.portal.capture_object.closed and time.monotonic()<deadline:time.sleep(.01)
        self.assertTrue(self.portal.capture_object.closed)

    def test_capture_failure_invalidates_source_and_exits_controlled_target(self):
        import base64
        self.portal.release.set()
        self.service.dispatch(dict(command='register_window_capture',path='fixture',hwnd=71))
        self.assertTrue(self.portal.started.wait(1))
        self.service.enabled=True;self.service.target=71
        self.portal.error('Source closed')
        stopped=next(e for e in self.events if e.get('event')=='linux_capture_stopped')
        self.assertEqual(base64.b64decode(stopped['path64']),b'fixture')
        self.assertTrue(any(e.get('event')=='linux_target_lost' for e in self.events))

    def test_desktop_capture_failure_exits_desktop_input(self):
        self.portal.release.set()
        self.service.dispatch(dict(command='register_display_capture',path='display'))
        self.assertTrue(self.portal.started.wait(1))
        self.service.enabled=True;self.service.desktop_input=True
        self.portal.error('Display sharing ended')
        self.assertTrue(any(e.get('event')=='linux_target_lost' for e in self.events))

    def test_inactive_source_does_not_publish(self):
        self.portal.release.set()
        self.service.dispatch({"command": "register_display_capture", "path": "fixture"})
        self.assertTrue(self.portal.started.wait(1))
        self.assertEqual(self.portal.source_type, 1)
        self.service.dispatch({"command": "active_window_captures", "paths": []})
        self.portal.publish(1, 1, b'\0'*4)
        self.assertNotIn("fixture", self.service.frame_stream._frames)
        self.service.dispatch({"command": "active_window_captures", "paths": ["fixture"]})
        self.portal.publish(1, 1, b'\1'*4)
        self.assertEqual(self.service.frame_stream._frames['fixture'][4], b'\1'*4)
    def test_mapping_and_portal_keys(self):
        self.assertEqual(map_point(300, 200, (100, 100, 400, 200), (800, 400)), (400, 200))
        self.assertIsNone(map_point(-1, 999, (0, 0, 100, 100), (400, 200)))
        self.assertIsNone(map_point(1, 1, (0, 0, 0, 10), (100, 100)))
        self.assertIsNone(portal_trigger('RIGHT_CTRL'))
        self.assertEqual(portal_trigger('CTRL+SHIFT+F12'), 'CTRL+SHIFT+F12')
    def test_native_input_is_confined_to_multitask_and_selection_suspends_it(self):
        native=Mock()
        wire=Mock()
        self.service.native_input=native
        self.service.x11=wire
        self.service._start_hotkeys=Mock()
        message=dict(command='set_input_target',hwnd=71,x=10,y=20,width=300,height=200)
        self.service.dispatch(message)
        native.activate.assert_not_called()
        self.service.dispatch(dict(command='set_multitask_overlay',enabled=True,app_pid=99))
        native.activate.assert_called_with(71,(10,20,300,200),99)
        self.assertEqual(self.service.input_mode,'native')
        self.service.dispatch(dict(command='screenshot_selection',enabled=True))
        native.deactivate.assert_called()
        self.assertEqual(self.service.input_mode,'unavailable')
        self.service.dispatch(dict(command='screenshot_selection',enabled=False))
        self.assertEqual(self.service.input_mode,'native')
        self.service.dispatch(dict(command='set_multitask_overlay',enabled=False))
        self.assertEqual(self.service.input_mode,'unavailable')
        wire.release_all.assert_called()

    def test_native_failure_falls_back_explicitly_without_swapping_layers(self):
        native=Mock()
        native.activate.side_effect=RuntimeError('placement denied')
        self.service.native_input=native
        self.service.x11=Mock()
        self.service.enabled=True
        self.service.dispatch(dict(command='set_input_target',hwnd=71,x=0,y=0,width=30,height=20))
        self.assertEqual(self.service.input_mode,'directed')
        self.assertTrue(any(e.get('mode')=='directed' for e in self.events))
        self.service.dispatch(dict(command='linux_input',kind='key_press',keysym=97))
        self.service.x11.send.assert_called()

    def test_kde_target_uses_logical_geometry_and_never_sends_opaque_id_to_x11(self):
        native=Mock()
        wire=Mock()
        adapter=Mock()
        baseline=dict(rect=dict(x=5,y=6,width=400,height=250),focused=False,activeUuid='prior')
        placed=dict(rect=dict(x=20,y=30,width=400,height=250),focused=True)
        adapter.get.side_effect=[baseline,placed]
        adapter.place_focus.return_value=dict(ok=True)
        self.service.native_input=native
        self.service.x11=wire
        self.service.kwin=adapter
        self.service.kwin_checked=True
        self.service.kwin_handles[KWIN_HANDLE_BASE+1]='owned-token'
        self.service.enabled=True
        self.service.app_pid=99
        self.service.coordinate_scale=(2,2)
        self.service._start_hotkeys=Mock()
        self.service.dispatch(dict(command='set_input_target',hwnd=KWIN_HANDLE_BASE+1,x=40,y=60,width=800,height=500))
        adapter.place_focus.assert_called_once_with('owned-token',dict(x=20,y=30,width=400,height=250),keep_above=True,borderless=True)
        native.activate_external.assert_called_once_with((40,60,800,500),99)
        self.assertEqual(self.service.input_mode,'native')
        wire.set_target.assert_called_with(0,(40,60,800,500))
        self.service.dispatch(dict(command='screenshot_selection',enabled=True))
        adapter.restore.assert_called_once_with('owned-token',baseline)

    def test_kde_rejected_geometry_cannot_open_overlay_hole(self):
        native=Mock()
        adapter=Mock()
        adapter.get.return_value=dict(rect=dict(x=0,y=0,width=400,height=250),focused=False)
        adapter.place_focus.return_value=dict(ok=False,reason='fullscreen_geometry')
        self.service.native_input=native
        self.service.x11=Mock()
        self.service.kwin=adapter
        self.service.kwin_checked=True
        self.service.kwin_handles[KWIN_HANDLE_BASE+1]='owned-token'
        self.service.enabled=True
        self.service._start_hotkeys=Mock()
        self.service.dispatch(dict(command='set_input_target',hwnd=KWIN_HANDLE_BASE+1,x=40,y=60,width=80,height=50))
        native.activate_external.assert_not_called()
        self.assertEqual(self.service.input_mode,'unavailable')
        adapter.restore.assert_called_once()

    def test_kde_input_heartbeat_lost_focus_fails_closed(self):
        native=Mock()
        adapter=Mock()
        token='owned-token'
        baseline=dict(rect=dict(x=5,y=6,width=400,height=250),focused=False,activeUuid='prior')
        adapter.get.return_value=dict(rect=dict(x=20,y=30,width=400,height=250),focused=False)
        self.service.native_input=native
        self.service.kwin=adapter
        self.service.kwin_handles[KWIN_HANDLE_BASE+1]=token
        self.service.kwin_active=token
        self.service.kwin_baseline=baseline
        self.service.enabled=True
        self.service.input_mode='native'
        self.service.target=KWIN_HANDLE_BASE+1
        self.service.rect=(40,60,800,500)
        self.service.coordinate_scale=(2,2)

        self.service._check_input()

        native.deactivate.assert_called_once_with()
        adapter.restore.assert_called_once_with(token,baseline)
        self.assertEqual(self.service.input_mode,'unavailable')
        self.assertIn({'event':'linux_target_lost'},self.events)

    def test_kde_input_heartbeat_changed_geometry_fails_closed(self):
        native=Mock()
        adapter=Mock()
        token='owned-token'
        baseline=dict(rect=dict(x=5,y=6,width=400,height=250),focused=False,activeUuid='prior')
        adapter.get.return_value=dict(rect=dict(x=20,y=30,width=398,height=250),focused=True)
        self.service.native_input=native
        self.service.kwin=adapter
        self.service.kwin_handles[KWIN_HANDLE_BASE+1]=token
        self.service.kwin_active=token
        self.service.kwin_baseline=baseline
        self.service.enabled=True
        self.service.input_mode='native'
        self.service.target=KWIN_HANDLE_BASE+1
        self.service.rect=(40,60,800,500)
        self.service.coordinate_scale=(2,2)

        self.service._check_input()

        native.deactivate.assert_called_once_with()
        adapter.restore.assert_called_once_with(token,baseline)
        self.assertEqual(self.service.input_mode,'unavailable')
        self.assertIn({'event':'linux_target_lost'},self.events)

    def test_desktop_input_requires_matching_monitor_metadata(self):
        from types import SimpleNamespace

        for metadata,should_activate in (
            ({'position':(20,30),'size':(400,250)},True),
            ({'position':(21,30),'size':(400,250)},False),
            ({'position':(20,30),'size':(400,249)},False),
            ({'position':None,'size':(400,250)},False),
            ({'position':(20,30),'size':None},False),
        ):
            with self.subTest(metadata=metadata):
                native=Mock()
                self.service.native_input=native
                self.service.enabled=True
                self.service.desktop_input=True
                self.service.target=0
                self.service.rect=(40,60,800,500)
                self.service.coordinate_scale=(2,2)
                self.service.input_path='display-fixture'
                capture=Capture()
                capture.properties=metadata
                self.service.captures['display-fixture']=capture
                self.service.app_pid=99
                self.service._activate_input()
                if should_activate:
                    native.activate_desktop.assert_called_once_with((40,60,800,500),99)
                    self.assertEqual(self.service.input_mode,'native')
                else:
                    native.activate_desktop.assert_not_called()
                    native.deactivate.assert_called()
                    self.assertEqual(self.service.input_mode,'unavailable')

    def test_permission_dialog_suspends_native_control_until_it_closes(self):
        native=Mock()
        self.service.native_input=native
        self.service.x11=Mock()
        self.service.enabled=True
        self.service.permissions_pending=True
        self.service.dispatch(dict(command='set_input_target',hwnd=71,x=0,y=0,width=300,height=200))
        native.activate.assert_not_called()
        native.deactivate.assert_called()
        self.assertEqual(self.service.input_mode,'unavailable')
        self.service.permissions_pending=False
        self.service.dispatch(dict(command='set_input_target',hwnd=71,x=0,y=0,width=300,height=200))
        native.activate.assert_called_once()
        self.assertEqual(self.service.input_mode,'native')

    @unittest.skipIf(os.name=='nt','Linux XDG rule')
    def test_linux_snapshot_path_ignores_copied_windows_environment(self):
        from multimin_backend.snapshots import SnapshotStore
        with patch.dict(os.environ,{'LOCALAPPDATA':'unused-windows-profile'}):
            store=SnapshotStore()
        self.assertEqual(store.directory,Path(self.temp.name)/'MultiMin'/'screenshots')

    def test_fixed_fps_keeps_cadence_under_small_producer_jitter(self):
        self.portal.release.set()
        self.service.dispatch(dict(command='register_window_capture',path='paced'))
        self.assertTrue(self.portal.started.wait(1))
        with patch.object(self.service.frame_stream,'publish') as published:
            for i in range(120):
                now=10+i/60 + (.0001 if i%2 else -.0001)
                with patch('multimin_backend.linux_service.time.monotonic',return_value=now):
                    self.portal.publish(1,1,bytes(4))
            self.assertGreaterEqual(published.call_count,119)

    def test_fixed_fps_caps_fast_producer_without_catchup_burst(self):
        self.portal.release.set()
        self.service.dispatch(dict(command='register_window_capture',path='paced'))
        self.assertTrue(self.portal.started.wait(1))
        with patch.object(self.service.frame_stream,'publish') as published:
            for i in range(120):
                with patch('multimin_backend.linux_service.time.monotonic',return_value=10+i/120):
                    self.portal.publish(1,1,bytes(4))
            self.assertGreaterEqual(published.call_count,59)
            self.assertLessEqual(published.call_count,61)
            with patch('multimin_backend.linux_service.time.monotonic',return_value=100):
                for _ in range(10):self.portal.publish(1,1,bytes(4))
            self.assertLessEqual(published.call_count,62)

    def test_background_limit_caps_source_paced_capture_and_clears(self):
        self.portal.release.set()
        limits=[]
        self.portal.capture_object.set_rate_limit=limits.append
        self.service.dispatch(dict(command='set_capture_fps',value=60,source_paced=True))
        self.service.dispatch(dict(command='register_window_capture',path='direct'))
        self.assertTrue(self.portal.started.wait(1))
        for _ in range(100):
            if 'direct' in self.service.captures:break
            time.sleep(.005)
        self.service.dispatch(dict(command='linux_background_capture',path='direct',fps=12))
        self.assertEqual(limits[-1],12)
        with patch.object(self.service.frame_stream,'publish') as published:
            for i in range(120):
                with patch('multimin_backend.linux_service.time.monotonic',return_value=10+i/120):
                    self.portal.publish(1,1,bytes(4))
            self.assertGreaterEqual(published.call_count,11)
            self.assertLessEqual(published.call_count,13)
        self.service.dispatch(dict(command='linux_background_capture',path='direct',fps=0))
        self.assertEqual(limits[-1],0)
        with patch.object(self.service.frame_stream,'publish') as published:
            for i in range(30):
                with patch('multimin_backend.linux_service.time.monotonic',return_value=20+i/120):
                    self.portal.publish(1,1,bytes(4))
            self.assertEqual(published.call_count,30)

    def test_background_limit_does_not_affect_other_sources(self):
        self.service.background_limits['other']=5
        self.service.fps=60
        self.assertEqual(self.service._effective_fps('mine'),60)
        self.assertEqual(self.service._effective_fps('other'),5)

    def test_rate_limiter_admits_steady_cadence(self):
        from multimin_backend.linux_portal import RateLimiter
        now=[0.0]
        limiter=RateLimiter(lambda:now[0])
        self.assertTrue(all(limiter.admit() for _ in range(5)))
        limiter.set(10)
        admitted=0
        for i in range(600):
            now[0]=1+i/600*5
            admitted+=limiter.admit()
        self.assertGreaterEqual(admitted,49)
        self.assertLessEqual(admitted,51)

    def test_restore_token_is_forwarded_and_replacement_reported(self):
        import base64
        self.portal.release.set()
        self.portal.capture_object.restore_token="fresh-token"
        self.service.dispatch(dict(command='register_display_capture',path='screen',restore_token='saved-token'))
        self.assertTrue(self.portal.started.wait(1))
        for _ in range(200):
            if any(e.get('event')=='linux_restore_token' for e in self.events):break
            time.sleep(.005)
        self.assertEqual(self.portal.restore_token,'saved-token')
        event=[e for e in self.events if e.get('event')=='linux_restore_token'][0]
        self.assertEqual(base64.b64decode(event['path64']).decode(),'screen')
        self.assertEqual(base64.b64decode(event['token64']).decode(),'fresh-token')

    def test_without_token_capture_call_is_unchanged(self):
        self.portal.release.set()
        self.service.dispatch(dict(command='register_window_capture',path='w'))
        self.assertTrue(self.portal.started.wait(1))
        self.assertEqual(self.portal.restore_token,'')

    def test_xwayland_kde_window_is_captured_from_x11_without_picker(self):
        from multimin_backend.linux_x11 import encoded
        from multimin_backend import linux_service as ls
        class FakeKWin:
            def list_windows(self):
                return [dict(uuid="u1",title="Minecraft 1.21",process="java",pid=5,rect=dict(x=0,y=0,width=800,height=600),nativeWayland=False,x11WindowId=0),
                        dict(uuid="u2",title="Native",process="foot",pid=6,rect=dict(x=0,y=0,width=10,height=10),nativeWayland=True,x11WindowId=0)]
            def close(self):pass
        class FakeX11:
            def windows(self):
                return [dict(title64=encoded("Minecraft 1.21"),hwnd=4242),dict(title64=encoded("Other"),hwnd=7)]
        self.service.kwin, self.service.kwin_checked, self.service.x11 = FakeKWin(), True, FakeX11()
        rows=self.service._kwin_windows()
        mc=[r for r in rows if r["process64"]==encoded("kwin:java")][0]["hwnd"]
        native=[r for r in rows if r["process64"]==encoded("kwin:foot")][0]["hwnd"]
        self.assertEqual(self.service.kwin_xids.get(mc),4242)
        self.assertNotIn(native,self.service.kwin_xids)
        started=[]
        with patch.object(LinuxService,'_x11_capture',lambda s,xid,p,e,path='':started.append(xid) or Capture()):
            self.service.dispatch(dict(command='register_window_capture',path='mc',hwnd=mc))
            for _ in range(200):
                if started:break
                time.sleep(.005)
        self.assertEqual(started,[4242])
        self.assertFalse(self.portal.started.is_set())
        self.service.x11=None

    def test_x11_title_match_requires_unique_title(self):
        from multimin_backend.linux_service import match_x11_window
        from multimin_backend.linux_x11 import encoded
        rows=[dict(title64=encoded("A"),hwnd=1),dict(title64=encoded("A"),hwnd=2),dict(title64=encoded("B"),hwnd=3)]
        self.assertEqual(match_x11_window("A",rows),0)
        self.assertEqual(match_x11_window("B",rows),3)
        self.assertEqual(match_x11_window("",rows),0)

    def test_xdg_screenshot_path(self):
        self.assertEqual(self.service.snapshots.directory, Path(self.temp.name)/'MultiMin'/'screenshots')

if __name__ == '__main__':
    unittest.main()
