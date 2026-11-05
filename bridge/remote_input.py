"""Exclusive input lease: full key states, ordered packets and disconnect release."""

import asyncio
import contextlib
import json
import logging
import math
import time

from aiohttp import WSMsgType, web


class RemoteInput:
    def __init__(self, desktop, allowed, timeout=3.0):
        self.desktop, self.allowed, self.timeout = desktop, allowed, timeout
        self.lock = asyncio.Lock()
        self.session = None
        self.sequence = -1
        self.deadline = 0
        self.keys, self.buttons = set(), set()
        self.watchdog = None
        self.streams = {}

    async def open(self, session):
        async with self.lock:
            if not self.allowed():
                raise web.HTTPForbidden(reason="请先开启限时电脑控制")
            if self.session and time.monotonic() >= self.deadline:
                await self._release()
            if self.session and self.session != session:
                raise web.HTTPConflict(reason="另一台设备正在控制桌面，请先退出该设备的桌面遥控")
            self.session = session
            self.deadline = time.monotonic() + self.timeout
            if not self.watchdog:
                self.watchdog = asyncio.create_task(self.watch())
            return {"session": session, "lease_seconds": self.timeout, "cursor": self.cursor()}

    def cursor(self):
        method = getattr(self.desktop, "cursor", None)
        return method() if callable(method) else None

    async def _release(self):
        # Invalidate first: late packets must not revive a closed session.
        self.session = None
        self.sequence = -1
        self.deadline = 0
        if self.keys or self.buttons:
            await self.desktop.apply_state(set(), set(), {}, self.keys, self.buttons)
        self.keys, self.buttons = set(), set()

    async def release(self, session=None):
        async with self.lock:
            if session is None or session == self.session:
                await self._release()
        return {"released": True}

    async def update(self, data):
        async with self.lock:
            if not self.allowed() or time.monotonic() >= self.deadline:
                await self._release()
                raise web.HTTPForbidden(reason="桌面控制已过期，请重新进入遥控面板")
            if not self.session or data.get("session") != self.session:
                raise web.HTTPConflict(reason="桌面会话已结束，请重新打开遥控面板")
            sequence = data.get("sequence")
            if type(sequence) is not int or not 0 <= sequence <= 2**53:
                raise ValueError("输入序号无效")
            if sequence <= self.sequence:
                return {"ok": True, "ignored": True}
            keys, buttons = data.get("keys", []), data.get("buttons", [])
            if not isinstance(keys, list) or len(keys) > 32 or any(
                not isinstance(key, str) or key not in self.desktop.supported_keys for key in keys
            ):
                raise ValueError("无效键盘按键")
            if not isinstance(buttons, list) or len(buttons) > 3 or any(
                button not in ("left", "right", "middle") for button in buttons
            ):
                raise ValueError("无效鼠标按键")
            movement = {}
            for key, limit in (("dx", 2000), ("dy", 2000), ("scroll", 20)):
                value = data.get(key, 0)
                if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > limit:
                    raise ValueError("鼠标移动或滚动范围无效")
                movement[key] = int(value)
            if "x" in data or "y" in data:
                x, y = data.get("x"), data.get("y")
                if any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in (x, y)):
                    raise ValueError("鼠标位置应为 0–1 之间的坐标")
                movement.update(x=x, y=y)
            aliases = {"shift": "shiftleft", "ctrl": "ctrlleft", "alt": "altleft"}
            keys, buttons = {aliases.get(key, key) for key in keys}, set(buttons)
            # Keep union until successful so partial SendInput failures also release new keys.
            old_keys, old_buttons = self.keys, self.buttons
            self.keys, self.buttons = old_keys | keys, old_buttons | buttons
            try:
                await self.desktop.apply_state(keys, buttons, movement, old_keys, old_buttons)
            except BaseException:
                await self._release()
                raise
            self.keys, self.buttons = keys, buttons
            self.sequence = sequence
            self.deadline = time.monotonic() + self.timeout
            return {"ok": True, "sequence": sequence, "cursor": self.cursor()}

    async def stream(self, request):
        session = request.query.get("session")
        if not session or session != self.session or not self.allowed() or time.monotonic() >= self.deadline:
            raise web.HTTPForbidden(reason="桌面控制未开启或会话已结束")
        if session in self.streams:
            raise web.HTTPConflict(reason="此桌面会话已有输入连接")
        ws = web.WebSocketResponse(heartbeat=10, max_msg_size=8192, compress=False)
        if not ws.can_prepare(request).ok:
            raise web.HTTPBadRequest(reason="实时输入接口需要 WebSocket 连接")
        self.streams[session] = ws
        try:
            await ws.prepare(request)
            await ws.send_json({"ready": True, "cursor": self.cursor()})
            started, count = time.monotonic(), 0
            while not ws.closed:
                if session != self.session or not self.allowed() or time.monotonic() >= self.deadline:
                    break
                try:
                    message = await asyncio.wait_for(ws.receive(), .5)
                except TimeoutError:
                    continue
                if message.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                    break
                if message.type != WSMsgType.TEXT:
                    raise ValueError("输入消息必须为 JSON 文本")
                now = time.monotonic()
                if now - started >= 1:
                    started, count = now, 0
                count += 1
                if count > 180:
                    raise ValueError("输入发送过快，请重新连接")
                data = json.loads(message.data)
                if not isinstance(data, dict) or data.get("session") != session:
                    raise ValueError("输入会话不匹配")
                result = await self.update(data)
                await asyncio.wait_for(ws.send_json(result), 1)
        except (ConnectionError, asyncio.CancelledError):
            pass
        except Exception as exc:
            if ws.prepared and not ws.closed:
                with contextlib.suppress(Exception):
                    reason = exc.reason if isinstance(exc, web.HTTPException) else str(exc)
                    await asyncio.wait_for(ws.send_json({"error": reason[:200]}), 1)
            elif not ws.prepared:
                raise
        finally:
            self.streams.pop(session, None)
            closing = asyncio.create_task(self.release(session))
            try:
                await asyncio.shield(closing)
            except asyncio.CancelledError:
                await closing
            if ws.prepared:
                with contextlib.suppress(Exception):
                    await asyncio.wait_for(ws.close(), 1)
        return ws

    async def watch(self):
        while True:
            await asyncio.sleep(0.25)
            async with self.lock:
                if (self.session or self.keys or self.buttons) and (not self.allowed() or time.monotonic() >= self.deadline):
                    try:
                        await self._release()
                    except Exception:
                        logging.getLogger(__name__).exception("Remote input release failed; retrying")

    async def close(self):
        for ws in list(self.streams.values()):
            await ws.close()
        if self.watchdog:
            self.watchdog.cancel()
            await asyncio.gather(self.watchdog, return_exceptions=True)
            self.watchdog = None
        await self.release()
