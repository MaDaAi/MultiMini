import unittest
from unittest.mock import Mock

from multimin_backend.linux_native_input import NativeInput


class NativeInputGeometryTests(unittest.TestCase):
    def test_explicit_identity_never_scans_other_windows_by_namespaced_pid(self):
        router=NativeInput()
        router.overlay_hint=77;router.control_hint=88
        control=Mock()
        router._window=Mock(return_value=control)
        router._property=Mock(return_value=[2])
        router._viewable=Mock(return_value=True)
        router._raise_own_controls(2,77,99)
        router._window.assert_called_once_with(88)
        control.configure.assert_called_once_with(stack_mode=0)
        router._property.assert_called_once_with(control,"_NET_WM_PID",())

    def test_input_hole_leaves_four_regions_around_rect(self):
        regions = NativeInput._rectangles_with_hole((100, 50, 500, 300), (200, 100, 200, 100))
        self.assertEqual(regions, [
            (0, 0, 500, 50),
            (0, 150, 500, 150),
            (0, 50, 100, 100),
            (300, 50, 200, 100),
        ])

    def test_rect_outside_overlay_keeps_full_input_region(self):
        self.assertEqual(NativeInput._rectangles_with_hole((10, 10, 20, 20), (40, 40, 5, 5)),
                         [(0, 0, 20, 20)])

    def test_rect_covering_overlay_produces_empty_input_region(self):
        self.assertEqual(NativeInput._rectangles_with_hole((10, 10, 20, 20), (0, 0, 50, 50)), [])


if __name__ == "__main__":
    unittest.main()
