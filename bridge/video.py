"""Low-delay H.264 desktop stream over the existing authenticated connection."""

import asyncio
import contextlib
import ctypes
import os
import re
import subprocess
import time

from aiohttp import web


class AccessUnits:
    """Keep complete Annex B access units; x264 emits an AUD before each frame."""
    aud = re.compile(b"\x00\x00(?:\x00)?\x01\x09")

    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data):
        self.buffer.extend(data)
        starts = [m.start() for m in self.aud.finditer(self.buffer)]
        units = [bytes(self.buffer[starts[i]:starts[i + 1]]) for i in range(len(starts) - 1)]
        if len(starts) > 1:
            del self.buffer[:starts[-1]]
        if len(self.buffer) > 2 * 1024 * 1024:
            raise ValueError("视频帧过大，请降低分辨率")
        return units


class DesktopVideo:
    def __init__(self, remote_input):
        self.input = remote_input
        self.active = None
        self.active_session = None
        self.active_task = None
        self.start_lock = asyncio.Lock()
        self.handlers = set()

    @staticmethod
    def executable():
        if os.name != "nt":
            raise web.HTTPServiceUnavailable(reason="H.264 桌面视频目前支持 Windows 原生部署")
        try:
            import imageio_ffmpeg
            return imageio_ffmpeg.get_ffmpeg_exe()
        except (ImportError, RuntimeError):
            raise web.HTTPServiceUnavailable(reason="请在电脑安装插件新依赖 imageio-ffmpeg，或切换截图兼容模式") from None

    @staticmethod
    def dimensions(edge):
        user32 = ctypes.windll.user32
        user32.SetThreadDpiAwarenessContext.restype = ctypes.c_void_p
        user32.SetThreadDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        previous = user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
        try:
            width, height = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
        finally:
            user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(previous))
        scale = min(1, edge / max(width, height))
        return width, height, max(2, int(width * scale) // 2 * 2), max(2, int(height * scale) // 2 * 2)

    @staticmethod
    def command(executable, fps, dimensions, bitrate):
        width, height, out_width, out_height = dimensions
        return [executable, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "gdigrab", "-framerate", str(fps), "-draw_mouse", "1",
                "-offset_x", "0", "-offset_y", "0", "-video_size", f"{width}x{height}",
                "-i", "desktop", "-an", "-vf", f"scale={out_width}:{out_height}",
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-threads", "4",
                "-b:v", f"{bitrate}k", "-maxrate", f"{bitrate}k", "-bufsize", f"{bitrate // 4}k",
                "-x264-params", f"aud=1:repeat-headers=1:keyint={fps}:min-keyint={fps}:scenecut=0:bframes=0",
                "-flush_packets", "1", "-f", "h264", "pipe:1"]

    def check(self, session):
        if not self.input.allowed() or self.input.session != session or time.monotonic() >= self.input.deadline:
            raise PermissionError("桌面授权或会话已结束")

    async def stream(self, request):
        session = request.query.get("session", "")
        self.check(session)
        fps, edge = int(request.query.get("fps", "30")), int(request.query.get("size", "1280"))
        if fps not in (30, 60) or edge not in (960, 1280, 1600, 1920):
            raise ValueError("视频档位应为 30/60 FPS，分辨率为 960/1280/1600/1920")
        ws = web.WebSocketResponse(heartbeat=10, max_msg_size=1024, compress=False)
        if not ws.can_prepare(request).ok:
            raise web.HTTPBadRequest(reason="视频接口需要 WebSocket 连接")
        task = asyncio.current_task()
        async with self.start_lock:
            if self.active:
                if self.active_session == session:
                    raise web.HTTPConflict(reason="已有视频流，请关闭旧桌面窗口后重试")
                old = self.active_task
                if old:
                    if not old.cancelling():
                        old.cancel()
                    await asyncio.gather(old, return_exceptions=True)
            self.check(session)
            executable = self.executable()
            dimensions = self.dimensions(edge)
            self.active, self.active_session, self.active_task = ws, session, task
            self.handlers.add(task)
        process = None
        receiver = None
        try:
            process = subprocess.Popen(self.command(executable, fps, dimensions, 5000 if fps == 60 else 3000),
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
            await ws.prepare(request)
            await ws.send_json({"codec": "h264", "width": dimensions[2], "height": dimensions[3], "fps": fps})

            async def receive():
                async for _ in ws:
                    pass
            receiver = asyncio.create_task(receive())
            parser = AccessUnits()
            while not ws.closed:
                self.check(session)
                # A bounded read/send prevents a stalled peer from keeping capture alive.
                chunk = await asyncio.wait_for(asyncio.to_thread(process.stdout.read1, 65536), 2)
                if not chunk:
                    raise RuntimeError("电脑视频编码结束，请检查 FFmpeg 或切换截图兼容模式")
                for unit in parser.feed(chunk):
                    self.check(session)
                    await asyncio.wait_for(ws.send_bytes(unit), 1)
        except (ConnectionError, asyncio.CancelledError):
            pass
        except Exception as exc:
            if ws.prepared and not ws.closed:
                with contextlib.suppress(Exception):
                    await asyncio.wait_for(ws.send_json({"error": str(exc)[:200] or "视频流中断，请降低画质后重连"}), 1)
            elif not ws.prepared:
                raise
        finally:
            async def cleanup():
                if process:
                    if process.poll() is None:
                        process.kill()
                    await asyncio.to_thread(process.wait)
                    process.stdout.close()
                if callable(getattr(self.input, "release", None)):
                    with contextlib.suppress(Exception):
                        await self.input.release(session)
                if receiver:
                    receiver.cancel()
                    await asyncio.gather(receiver, return_exceptions=True)
                if ws.prepared:
                    with contextlib.suppress(Exception):
                        await asyncio.wait_for(ws.close(), 2)
            closing = asyncio.create_task(cleanup())
            try:
                try:
                    await asyncio.shield(closing)
                except asyncio.CancelledError:
                    await closing
            finally:
                if self.active is ws:
                    self.active = None
                    self.active_session = None
                    self.active_task = None
                self.handlers.discard(task)
        return ws

    async def close(self):
        tasks = list(self.handlers)
        for task in tasks:
            if not task.cancelling():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
