"""Small Windows operations, each guarded by current owner authorization."""

import asyncio
import base64
import ctypes
import io
import os
import subprocess


class Desktop:
    supported_keys = set("abcdefghijklmnopqrstuvwxyz0123456789") | {
        "space", "enter", "esc", "tab", "backspace", "delete", "insert", "home", "end",
        "pageup", "pagedown", "up", "down", "left", "right", "shift", "ctrl", "alt",
        "shiftleft", "shiftright", "ctrlleft", "ctrlright", "altleft", "altright",
        "-", "=", "[", "]", ";", "'", ",", ".", "/", "\\", "`",
    } | {f"f{i}" for i in range(1, 13)}

    def __init__(self):
        self.lock = asyncio.Lock()
        self.gui = None

    def load(self):
        if os.name != "nt":
            raise RuntimeError("电脑控制需要 Windows 原生部署")
        if self.gui is None:
            import pyautogui
            self.gui = pyautogui
            self.gui.FAILSAFE = True
            self.gui.PAUSE = 0.05
        return self.gui

    async def perform(self, action, arguments, check):
        async with self.lock:
            check()
            if action == "shell":
                return await self.shell(arguments, check)
            gui = self.load()
            width, height = gui.size()
            if action == "screenshot":
                edge = arguments.get("max_size", 1600)
                if type(edge) is not int or not 480 <= edge <= 1920:
                    raise ValueError("画面尺寸必须为 480–1920")
                def capture():
                    picture = gui.screenshot()
                    picture.thumbnail((edge, edge))
                    stream = io.BytesIO()
                    picture.save(stream, format="JPEG", quality=65)
                    return {"image": base64.b64encode(stream.getvalue()).decode(),
                            "width": width, "height": height,
                            "image_width": picture.width, "image_height": picture.height}
                result = await asyncio.to_thread(capture)
                check()
                return result
            if action == "click":
                x, y = int(arguments["x"]), int(arguments["y"])
                if not (0 <= x < width and 0 <= y < height):
                    raise ValueError("坐标超出主屏幕")
                button = arguments.get("button", "left")
                if button not in ("left", "right", "middle"):
                    raise ValueError("无效鼠标按键")
                gui.click(x, y, button=button)
            elif action == "type_text":
                value = arguments.get("text", "")
                if not isinstance(value, str) or len(value) > 4000:
                    raise ValueError("文字不能超过 4000 字符")
                await self.unicode_input(value, check)
            elif action == "hotkey":
                keys = arguments.get("keys", [])
                if not isinstance(keys, list) or not 1 <= len(keys) <= 4:
                    raise ValueError("组合键必须包含 1–4 个按键")
                if any(k not in gui.KEYBOARD_KEYS for k in keys):
                    raise ValueError("无效按键")
                gui.hotkey(*keys)
            elif action == "scroll":
                amount = int(arguments.get("amount", 0))
                if not -20 <= amount <= 20:
                    raise ValueError("滚动范围为 -20 至 20")
                gui.scroll(amount)
            else:
                raise ValueError("未知电脑操作")
            return {"ok": True}

    async def apply_state(self, keys, buttons, movement, previous_keys, previous_buttons):
        async with self.lock:
            if not (keys or buttons or previous_keys or previous_buttons or any(movement.values())):
                return
            if os.name != "nt":
                raise RuntimeError("桌面输入仅支持 Windows")
            self._send_state(keys, buttons, movement, previous_keys, previous_buttons)

    def _send_state(self, keys, buttons, movement, previous_keys, previous_buttons):
        from ctypes import wintypes

        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", wintypes.WORD), ("scan", wintypes.WORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]

        class Mouse(ctypes.Structure):
            _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG), ("data", wintypes.DWORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]

        class Union(ctypes.Union):
            _fields_ = [("keyboard", Keyboard), ("mouse", Mouse)]

        class Input(ctypes.Structure):
            _fields_ = [("type", wintypes.DWORD), ("data", Union)]

        user32 = ctypes.windll.user32
        codes = {"space": 32, "enter": 13, "esc": 27, "tab": 9, "backspace": 8,
                 "delete": 46, "insert": 45, "home": 36, "end": 35, "pageup": 33,
                 "pagedown": 34, "up": 38, "down": 40, "left": 37, "right": 39,
                 "shift": 160, "ctrl": 162, "alt": 164, "shiftleft": 160,
                 "shiftright": 161, "ctrlleft": 162, "ctrlright": 163,
                 "altleft": 164, "altright": 165}
        codes.update({f"f{i}": 111 + i for i in range(1, 13)})
        extended = {33, 34, 35, 36, 37, 38, 39, 40, 45, 46, 163, 165}
        events = []
        for group, released in ((previous_keys - keys, True), (keys - previous_keys, False)):
            for key in sorted(group):
                vk = codes.get(key)
                if vk is None:
                    vk = user32.VkKeyScanW(ord(key)) & 0xff
                event = Input(type=1)
                event.data.keyboard = Keyboard(0, user32.MapVirtualKeyW(vk, 0),
                                               8 | (2 if released else 0) | (1 if vk in extended else 0), 0, 0)
                events.append(event)

        def mouse(flags, x=0, y=0, data=0):
            event = Input(type=0)
            event.data.mouse = Mouse(x, y, data & 0xffffffff, flags, 0, 0)
            events.append(event)

        if "x" in movement:
            width, height = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
            user32.SetCursorPos(int(movement["x"] * (width - 1)), int(movement["y"] * (height - 1)))
        if movement.get("dx") or movement.get("dy"):
            mouse(1, movement.get("dx", 0), movement.get("dy", 0))
        flags = {"left": (2, 4), "right": (8, 16), "middle": (32, 64)}
        for button in previous_buttons - buttons:
            mouse(flags[button][1])
        for button in buttons - previous_buttons:
            mouse(flags[button][0])
        if movement.get("scroll"):
            mouse(0x800, data=movement["scroll"] * 120)
        if events:
            inputs = (Input * len(events))(*events)
            if user32.SendInput(len(events), inputs, ctypes.sizeof(Input)) != len(events):
                raise RuntimeError("Windows 拒绝部分输入；请检查目标应用权限")

    async def unicode_input(self, value, check):
        from ctypes import wintypes

        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", wintypes.WORD), ("scan", wintypes.WORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("extra", ctypes.c_size_t)]

        class Mouse(ctypes.Structure):
            _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG),
                        ("data", wintypes.DWORD), ("flags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]

        class Union(ctypes.Union):
            _fields_ = [("keyboard", Keyboard), ("mouse", Mouse)]

        class Input(ctypes.Structure):
            _fields_ = [("type", wintypes.DWORD), ("data", Union)]

        raw = value.encode("utf-16-le")
        for offset in range(0, len(raw), 64):
            check()
            chunk, events = raw[offset:offset + 64], []
            for i in range(0, len(chunk), 2):
                unit = int.from_bytes(chunk[i:i + 2], "little")
                for flags in (4, 6):
                    entry = Input(type=1)
                    entry.data.keyboard = Keyboard(0, unit, flags, 0, 0)
                    events.append(entry)
            inputs = (Input * len(events))(*events)
            if ctypes.windll.user32.SendInput(len(events), inputs, ctypes.sizeof(Input)) != len(events):
                raise RuntimeError("Windows 拒绝输入；目标可能以更高权限运行")
            await asyncio.sleep(0)

    async def shell(self, arguments, check):
        command, timeout = arguments.get("command", ""), int(arguments.get("timeout_seconds", 60))
        if not isinstance(command, str) or not 1 <= len(command) <= 8000:
            raise ValueError("命令长度必须为 1–8000 字符")
        if not 1 <= timeout <= 120:
            raise ValueError("命令时限必须为 1–120 秒")
        check()
        command = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " + command
        encoded = base64.b64encode(command.encode("utf-16-le")).decode()
        process = await asyncio.create_subprocess_exec(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW)
        output = bytearray()

        async def drain():
            while chunk := await process.stdout.read(4096):
                output.extend(chunk[:max(0, 32000 - len(output))])

        reader = asyncio.create_task(drain())
        try:
            await asyncio.wait_for(process.wait(), timeout)
            await reader
        finally:
            if process.returncode is None:
                killer = await asyncio.create_subprocess_exec(
                    "taskkill.exe", "/PID", str(process.pid), "/T", "/F",
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                    creationflags=subprocess.CREATE_NO_WINDOW)
                await killer.wait()
                await process.wait()
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)
        return {"exit_code": process.returncode, "output": output.decode("utf-8", errors="replace")}
