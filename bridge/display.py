"""Physical primary-display coordinates shared by capture and pointer input."""

import contextlib
import ctypes
import os


@contextlib.contextmanager
def physical_pixels():
    previous = None
    if os.name == "nt":
        api = ctypes.windll.user32.SetThreadDpiAwarenessContext
        api.restype = ctypes.c_void_p
        api.argtypes = [ctypes.c_void_p]
        previous = api(ctypes.c_void_p(-4))
    try:
        yield
    finally:
        if previous:
            ctypes.windll.user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(previous))


def cursor_position():
    if os.name != "nt":
        return None
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    with physical_pixels():
        position = wintypes.POINT()
        if not user32.GetCursorPos(ctypes.byref(position)):
            return None
        width, height = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
        return {"x": min(1, max(0, position.x / max(1, width - 1))),
                "y": min(1, max(0, position.y / max(1, height - 1))),
                "width": width, "height": height,
                "visible": 0 <= position.x < width and 0 <= position.y < height}
