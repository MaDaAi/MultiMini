"""Runtime backend selection. Import Windows code only on Windows."""
import importlib
import sys


def runtime_module(platform=None):
    platform = sys.platform if platform is None else platform
    if platform == "win32":
        return importlib.import_module(".service", __package__)
    if platform.startswith("linux"):
        return importlib.import_module(".linux_service", __package__)
    raise RuntimeError(f"MultiMini has no backend for {platform}")


def service_class(platform=None):
    module = runtime_module(platform)
    return getattr(module, "LinuxService", None) or module.CaptureService
