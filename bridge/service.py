import asyncio
import contextlib
import hmac
import json
import logging
import re
import time
from collections import OrderedDict
from pathlib import Path

from aiohttp import web

from . import VERSION
from .phone import PhoneBroker
from .store import Store

log = logging.getLogger(__name__)
ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,80}$")


class BridgeService:
    """Authenticated owner-only API; jobs survive phone disconnects, never host restarts."""

    def __init__(self, config, data_dir: Path, backend, desktop=None):
        self.config = config
        self.token = config["api_token"]
        if not isinstance(self.token, str) or len(self.token) < 32:
            raise ValueError("api_token 必须至少包含 32 个字符")
        data_dir.mkdir(parents=True, exist_ok=True)
        self.store = Store(data_dir / "tasks.sqlite3")
        self.backend = backend
        self.desktop = desktop
        self.phone = PhoneBroker(self)
        self.queue = asyncio.Queue(maxsize=32)
        self.control_until = 0.0
        self.runner = None
        self.worker = None
        self.current_task = None
        self.current_job = None
        self.expiry_task = None
        self.failures = OrderedDict()
        self.app = web.Application(middlewares=[self.middleware], client_max_size=32768)
        self.app.add_routes(
            [
                web.get("/api/v1/status", self.status),
                web.get("/api/v1/jobs", self.jobs),
                web.post("/api/v1/jobs", self.submit),
                web.get("/api/v1/jobs/{job_id}", self.job),
                web.post("/api/v1/jobs/{job_id}/cancel", self.cancel),
                web.post("/api/v1/control", self.control),
                web.post("/api/v1/stop", self.stop_all),
                web.post("/api/v1/desktop", self.desktop_action),
                web.post("/api/v1/devices/{device}/poll", self.phone.poll),
                web.post("/api/v1/devices/{device}/commands/{command_id}", self.phone.result),
                web.post("/api/v1/devices/{device}/disable", self.phone.disable),
            ]
        )

    @web.middleware
    async def middleware(self, request, handler):
        # Do not trust X-Forwarded-For: the same authenticated API serves LAN and tunnels.
        peer = request.remote or "unknown"
        now = time.monotonic()
        count, start = self.failures.get(peer, (0, now))
        if now - start > 60:
            count, start = 0, now
        supplied = request.headers.get("Authorization", "")
        valid = hmac.compare_digest(supplied.encode(), ("Bearer " + self.token).encode())
        if not valid:
            self.failures[peer] = (count + 1, start)
            self.failures.move_to_end(peer)
            while len(self.failures) > 1024:
                self.failures.popitem(last=False)
            return web.json_response(
                {"error": "连接密钥不正确"}, status=429 if count >= 15 else 401
            )
        try:
            response = await handler(request)
        except web.HTTPException as exc:
            response = web.json_response({"error": exc.reason}, status=exc.status)
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            response = web.json_response({"error": str(exc)[:500]}, status=400)
        except Exception:
            log.exception("Android Connect API request failed")
            response = web.json_response(
                {"error": "电脑端内部错误，请查看 AstrBot 日志"}, status=500
            )
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    async def body(self, request):
        if request.content_type != "application/json":
            raise web.HTTPUnsupportedMediaType(reason="请求必须为 JSON")
        data = await request.json()
        if not isinstance(data, dict):
            raise ValueError("JSON 必须为对象")
        return data

    def is_control_enabled(self):
        return (
            bool(self.config.get("allow_desktop_control", False))
            and time.time() < self.control_until
        )

    @staticmethod
    def validate_id(value):
        if not isinstance(value, str) or not ID_RE.fullmatch(value):
            raise ValueError("ID 格式不正确")

    async def desktop_action(self, request):
        if not self.is_control_enabled():
            raise web.HTTPForbidden(reason="电脑控制未开启")
        if not self.desktop:
            raise web.HTTPServiceUnavailable(reason="电脑操作服务不可用")
        data = await self.body(request)
        action = data.get("action", "")
        if action not in ("screenshot", "click", "type_text", "hotkey", "scroll"):
            raise ValueError("不支持此操作")
        arguments = data.get("arguments", {})
        if not isinstance(arguments, dict):
            raise ValueError("arguments 必须为对象")
        result = await self.desktop(action, arguments, self)
        return web.json_response(result)

    def check_control(self, job):
        if not job["control"] or not self.is_control_enabled():
            raise PermissionError("电脑控制未开启、已关闭或授权已过期")
        saved = self.store.get(job["id"])
        if not saved or saved["state"] != "running":
            raise PermissionError("任务已经停止")

    async def status(self, request):
        return web.json_response(
            {
                "name": "AstrBot Android Connect",
                "version": VERSION,
                "desktop_allowed": bool(self.config.get("allow_desktop_control", False)),
                "desktop_enabled": self.is_control_enabled(),
                "control_until": self.control_until if self.is_control_enabled() else 0,
                "shell_allowed": bool(self.config.get("allow_shell", False)),
                "phone_allowed": bool(self.config.get("allow_phone_control", False)),
                "max_control_hours": min(24, max(1, int(self.config.get("max_control_hours", 8)))),
                "running_job": self.current_job,
                "pending_jobs": self.queue.qsize(),
            }
        )

    async def jobs(self, request):
        session = request.query.get("session")
        if session and not ID_RE.fullmatch(session):
            raise ValueError("会话 ID 格式不正确")
        return web.json_response({"jobs": self.store.list(session)})

    async def job(self, request):
        item = self.store.get(request.match_info["job_id"])
        if not item:
            raise web.HTTPNotFound(reason="任务不存在")
        return web.json_response({**item, "events": self.store.events(item["id"])})

    async def submit(self, request):
        data = await self.body(request)
        job_id, session = data.get("id", ""), data.get("session", "")
        prompt, control = data.get("prompt", ""), data.get("control", False)
        phone, device = data.get("phone", False), data.get("device", "")
        if not isinstance(phone, bool):
            raise ValueError("phone 必须为布尔值")
        if phone:
            self.validate_id(device)
        else:
            device = ""
        if not isinstance(job_id, str) or not ID_RE.fullmatch(job_id):
            raise ValueError("请提供唯一任务 ID（8–80 个字母、数字、下划线或连字符）")
        if not isinstance(session, str) or not ID_RE.fullmatch(session):
            raise ValueError("会话 ID 格式不正确")
        if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= 8000:
            raise ValueError("消息长度必须为 1–8000 个字符")
        if not isinstance(control, bool):
            raise ValueError("control 必须为布尔值")
        prompt = prompt.strip()
        old = self.store.get(job_id)
        if old:
            if (
                old["session"],
                old["prompt"],
                bool(old["control"]),
                bool(old["phone"]),
                old["device"],
            ) != (session, prompt, control, phone, device):
                raise web.HTTPConflict(reason="任务 ID 已被另一条消息使用")
            return web.json_response(old)
        if control and not self.is_control_enabled():
            raise web.HTTPForbidden(reason="请先开启电脑控制并设置授权时长")
        if phone and not self.phone.enabled(device):
            raise web.HTTPForbidden(reason="请先在手机开启手机控制并保持服务在线")
        if self.queue.full():
            raise web.HTTPTooManyRequests(reason="任务队列已满，请稍后重试")
        item = self.store.create(job_id, session, prompt, control, phone, device)
        self.queue.put_nowait(job_id)
        self.store.audit("submitted", job_id, control=control)
        return web.json_response(item, status=202)

    async def cancel_job(self, job_id, reason="主人停止了任务"):
        item = self.store.get(job_id)
        if item and item["state"] in ("queued", "running"):
            self.store.update(job_id, "cancelled", error=reason)
            self.store.audit("cancelled", job_id)
            self.phone.abort(job_id=job_id)
            if self.current_job == job_id and self.current_task:
                self.current_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await self.current_task
        return self.store.get(job_id)

    async def cancel(self, request):
        item = await self.cancel_job(request.match_info["job_id"])
        if not item:
            raise web.HTTPNotFound(reason="任务不存在")
        return web.json_response(item)

    async def revoke(self, all_jobs=False):
        self.control_until = 0
        for item in self.store.list(limit=100):
            if all_jobs or item["control"]:
                await self.cancel_job(
                    item["id"], "电脑控制授权已撤销或到期" if not all_jobs else "紧急停止"
                )
        self.store.audit("stop_all" if all_jobs else "control_disabled")

    async def expire_control(self):
        try:
            await asyncio.sleep(max(0, self.control_until - time.time()))
            await self.revoke()
        except asyncio.CancelledError:
            pass

    async def control(self, request):
        data = await self.body(request)
        enabled = data.get("enabled")
        if not isinstance(enabled, bool):
            raise ValueError("enabled 必须为布尔值")
        if enabled:
            if not self.config.get("allow_desktop_control", False):
                raise web.HTTPForbidden(reason="请先在 AstrBot 插件配置中开启电脑控制总开关")
            hours = data.get("hours", 1)
            max_hours = min(24, max(1, int(self.config.get("max_control_hours", 8))))
            if (
                isinstance(hours, bool)
                or not isinstance(hours, (int, float))
                or not 0 < hours <= max_hours
            ):
                raise ValueError(f"授权时长必须大于 0 且不超过 {max_hours} 小时")
            self.control_until = time.time() + hours * 3600
            if self.expiry_task:
                self.expiry_task.cancel()
            self.expiry_task = asyncio.create_task(self.expire_control())
            self.store.audit("control_enabled", hours=hours)
        else:
            if self.expiry_task:
                self.expiry_task.cancel()
            await self.revoke()
        return await self.status(request)

    async def stop_all(self, request):
        if self.expiry_task:
            self.expiry_task.cancel()
        await self.revoke(all_jobs=True)
        self.phone.devices.clear()
        self.phone.abort()
        return web.json_response({"stopped": True})

    async def work(self):
        while True:
            job_id = await self.queue.get()
            try:
                job = self.store.get(job_id)
                if not job or job["state"] != "queued":
                    continue
                self.store.prune(max(1, int(self.config.get("retention_days", 30))))
                self.store.update(job_id, "running")
                self.current_job = job_id
                if job["control"]:
                    self.check_control(job)
                if job["phone"]:
                    self.phone.check(job)
                history = self.store.history(
                    job["session"], max(0, min(50, int(self.config.get("history_turns", 12))))
                )
                self.current_task = asyncio.create_task(self.backend(job, history, self))
                timeout = max(10, min(28800, int(self.config.get("task_timeout_seconds", 3600))))
                result = await asyncio.wait_for(self.current_task, timeout)
                if self.store.get(job_id)["state"] == "running":
                    self.store.update(
                        job_id, "completed", result=str(result or "任务已结束，模型未返回文本。")
                    )
            except asyncio.CancelledError:
                if self.store.get(job_id)["state"] == "running":
                    self.store.update(job_id, "cancelled", error="任务已停止")
                if asyncio.current_task().cancelling():
                    raise
            except TimeoutError:
                self.store.update(
                    job_id, "failed", error="任务超时，已停止。请拆分任务或调整执行时限。"
                )
            except PermissionError as exc:
                self.store.update(job_id, "failed", error=str(exc))
            except Exception:
                log.exception("Android Connect job %s failed", job_id)
                self.store.update(
                    job_id, "failed", error="任务执行失败，请查看 AstrBot 日志和模型配置。"
                )
            finally:
                self.current_job = None
                self.current_task = None
                self.queue.task_done()

    async def start(self):
        self.runner = web.AppRunner(self.app, access_log=None)
        try:
            await self.runner.setup()
            await web.TCPSite(
                self.runner,
                self.config.get("listen_host", "127.0.0.1"),
                int(self.config.get("listen_port", 8765)),
            ).start()
            self.worker = asyncio.create_task(self.work())
        except BaseException:
            await self.close()
            raise

    async def close(self):
        if self.expiry_task:
            self.expiry_task.cancel()
        await self.revoke(all_jobs=True)
        if self.worker:
            self.worker.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.worker
        if self.runner:
            await self.runner.cleanup()
        self.store.close()
