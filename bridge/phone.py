"""Deliver phone commands only to a locally enabled, recently connected device."""

import asyncio
import time
import uuid

from aiohttp import web


class PhoneBroker:
    def __init__(self, service):
        self.service = service
        self.devices = {}
        self.commands = {}
        self.lock = asyncio.Lock()

    def enabled(self, device):
        state = self.devices.get(device, {})
        return (
            bool(self.service.config.get("allow_phone_control", False))
            and state.get("enabled", False)
            and time.monotonic() - state.get("seen", 0) < 45
        )

    def check(self, job):
        if not job.get("phone") or not self.enabled(job.get("device")):
            raise PermissionError("手机控制未开启、手机离线或无障碍授权不可用")
        current = self.service.store.get(job["id"])
        if not current or current["state"] != "running":
            raise PermissionError("任务已经停止")

    def abort(self, device=None, job_id=None):
        for command in list(self.commands.values()):
            if (
                (device is None or command["device"] == device)
                and (job_id is None or command["job_id"] == job_id)
                and not command["future"].done()
            ):
                command["future"].set_exception(PermissionError("手机控制已停止"))

    async def execute(self, job, action, arguments):
        async with self.lock:
            self.check(job)
            command_id = str(uuid.uuid4())
            future = asyncio.get_running_loop().create_future()
            command = {
                "id": command_id,
                "device": job["device"],
                "job_id": job["id"],
                "action": action,
                "arguments": arguments,
                "future": future,
                "delivered": False,
                "expires": time.time() + 25,
            }
            self.commands[command_id] = command
            self.service.store.audit("phone_" + action, job["id"])
            try:
                return await asyncio.wait_for(future, 25)
            except TimeoutError as exc:
                raise RuntimeError("手机操作超时；请查看手机联网、通知和无障碍服务状态") from exc
            finally:
                self.commands.pop(command_id, None)

    async def poll(self, request):
        data = await self.service.body(request)
        device = request.match_info["device"]
        self.service.validate_id(device)
        enabled = data.get("enabled")
        if not isinstance(enabled, bool):
            raise ValueError("enabled 必须为布尔值")
        if device not in self.devices and len(self.devices) >= 16:
            raise web.HTTPTooManyRequests(reason="已达到最多 16 台手机的限制，请重启插件清理")
        self.devices[device] = {"enabled": enabled, "seen": time.monotonic()}
        if not self.enabled(device):
            self.abort(device=device)
            return web.json_response({"commands": [], "allowed": False})
        # At-most-once delivery; APK persists command IDs before execution as well.
        for _ in range(35):
            for command in list(self.commands.values()):
                if command["device"] != device or command["delivered"]:
                    continue
                job = self.service.store.get(command["job_id"])
                if not job or job["state"] != "running" or command["expires"] <= time.time():
                    continue
                command["delivered"] = True
                return web.json_response(
                    {
                        "allowed": True,
                        "commands": [
                            {
                                k: v
                                for k, v in command.items()
                                if k not in ("future", "delivered", "device")
                            }
                        ],
                    }
                )
            await asyncio.sleep(0.2)
            if not self.enabled(device):
                break
        return web.json_response({"commands": [], "allowed": self.enabled(device)})

    async def result(self, request):
        data = await self.service.body(request)
        command = self.commands.get(request.match_info["command_id"])
        if not command or command["device"] != request.match_info["device"]:
            raise web.HTTPNotFound(reason="指令已过期")
        if not command["delivered"] or command["future"].done():
            raise web.HTTPConflict(reason="指令未送达或已处理")
        result = data.get("result", "")
        if not isinstance(result, str) or len(result) > 24000:
            raise ValueError("手机结果必须为不超过 24000 字符的文本")
        command["future"].set_result(result)
        return web.json_response({"ok": True})

    async def disable(self, request):
        device = request.match_info["device"]
        self.devices.pop(device, None)
        self.abort(device=device)
        for job in self.service.store.list(limit=100):
            if job["phone"] and job["device"] == device:
                await self.service.cancel_job(job["id"], "手机控制已关闭")
        return web.json_response({"stopped": True})
