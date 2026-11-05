import asyncio
import contextlib
import hmac
import json
import logging
import re
import secrets
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

    def __init__(self, config, data_dir: Path, backend, desktop=None, panel=None, remote_input=None):
        self.config = config
        self.token = config["api_token"]
        if not isinstance(self.token, str) or len(self.token) < 32:
            raise ValueError("api_token 必须至少包含 32 个字符")
        data_dir.mkdir(parents=True, exist_ok=True)
        self.store = Store(data_dir / "tasks.sqlite3")
        self.backend = backend
        self.desktop = desktop
        self.panel = panel
        self.config_panel = None
        self.video = None
        self.remote_input = remote_input
        self.phone = PhoneBroker(self)
        self.queue = asyncio.Queue(maxsize=32)
        self.control_until = 0.0
        self.runner = None
        self.worker = None
        self.current_task = None
        self.current_job = None
        self.expiry_task = None
        self.repeat_tasks = {}
        self.failures = OrderedDict()
        self.pair_code = ""
        self.pair_until = 0
        self.pair_attempts = 0
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
                web.post("/api/v1/pair", self.pair),
                web.post("/api/v1/desktop", self.desktop_action),
                web.get("/api/v1/panel/plugins", self.panel_plugins),
                web.post("/api/v1/panel/plugins", self.panel_change),
                web.get("/api/v1/panel/configs", self.config_list),
                web.get("/api/v1/panel/config", self.config_read),
                web.post("/api/v1/panel/config", self.config_save),
                web.post("/api/v1/panel/reload", self.config_reload),
                web.get("/api/v1/desktop/video", self.video_stream),
                web.post("/api/v1/desktop/session", self.input_open),
                web.post("/api/v1/desktop/input", self.input_update),
                web.get("/api/v1/desktop/input", self.input_stream),
                web.post("/api/v1/desktop/release", self.input_release),
                web.post("/api/v1/devices/{device}/poll", self.phone.poll),
                web.post("/api/v1/devices/{device}/commands/{command_id}", self.phone.result),
                web.post("/api/v1/devices/{device}/disable", self.phone.disable),
            ]
        )

    @web.middleware
    async def middleware(self, request, handler):
        if request.path == "/api/v1/pair" and request.method == "POST":
            try:
                response = await self.pair(request)
            except (ValueError, TypeError, json.JSONDecodeError):
                response = web.json_response({"error": "配对请求格式错误"}, status=400)
            response.headers["Cache-Control"] = "no-store"
            return response
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
        except PermissionError as exc:
            response = web.json_response({"error": str(exc)[:500]}, status=403)
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

    def begin_pairing(self):
        self.pair_code = f"{secrets.randbelow(1000000):06d}"
        self.pair_until = time.monotonic() + 120
        self.pair_attempts = 0
        return self.pair_code

    async def pair(self, request):
        data = await self.body(request)
        code = data.get("code", "")
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
            return web.json_response({"error": "请输入 6 位配对码"}, status=400)
        if not self.pair_code or time.monotonic() >= self.pair_until:
            return web.json_response({"error": "请先在电脑端开启配对，配对码有效期 2 分钟"}, status=403)
        self.pair_attempts += 1
        if self.pair_attempts > 5:
            self.pair_code = ""
            return web.json_response({"error": "尝试次数过多，请在电脑重新开启配对"}, status=429)
        if not hmac.compare_digest(code, self.pair_code):
            return web.json_response({"error": "配对码不正确"}, status=403)
        self.pair_code = ""
        self.store.audit("paired")
        return web.json_response({"token": self.token, "name": "AstrBot Connect", "version": VERSION})

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

    def check_panel(self):
        if not self.config.get("allow_plugin_management", False):
            raise web.HTTPForbidden(reason="请先在电脑端 Android Connect 配置开启手机插件管理")
        if not self.panel:
            raise web.HTTPServiceUnavailable(reason="插件管理服务不可用")

    async def panel_plugins(self, request):
        self.check_panel()
        return web.json_response(self.panel.listing())

    async def panel_change(self, request):
        self.check_panel()
        data = await self.body(request)
        result = await self.panel.change(data.get("name"), data.get("enabled"))
        self.store.audit("plugin_state", name=data.get("name"), enabled=data.get("enabled"))
        return web.json_response(result)

    def check_input(self):
        if not self.remote_input:
            raise web.HTTPServiceUnavailable(reason="请将电脑插件升级到 v0.3.0")

    def check_config_panel(self):
        if not self.config.get("allow_config_management", False):
            raise web.HTTPForbidden(reason="请先在电脑 Android Connect 配置开启手机配置管理")
        if not self.config_panel:
            raise web.HTTPServiceUnavailable(reason="请升级电脑插件以支持配置编辑")

    async def config_list(self, request):
        self.check_config_panel()
        return web.json_response(await self.config_panel.listing())

    async def config_read(self, request):
        self.check_config_panel()
        return web.json_response(await self.config_panel.read(request.query.get("kind"), request.query.get("id")))

    async def config_save(self, request):
        self.check_config_panel()
        request = request.clone(client_max_size=600 * 1024)
        data = await self.body(request)
        result = await self.config_panel.save(data.get("kind"), data.get("id"), data.get("values"), data.get("revision"))
        self.store.audit("config_saved", kind=data.get("kind"), name=data.get("id"))
        return web.json_response(result)

    async def config_reload(self, request):
        self.check_config_panel()
        self.check_panel()
        data = await self.body(request)
        result = await self.config_panel.reload(data.get("name"))
        self.store.audit("plugin_reload", name=data.get("name"))
        return web.json_response(result)

    async def video_stream(self, request):
        if not self.video:
            raise web.HTTPServiceUnavailable(reason="请升级电脑插件以支持视频传输")
        return await self.video.stream(request)

    async def input_open(self, request):
        self.check_input()
        data = await self.body(request)
        self.validate_id(data.get("session"))
        return web.json_response(await self.remote_input.open(data["session"]))

    async def input_update(self, request):
        self.check_input()
        return web.json_response(await self.remote_input.update(await self.body(request)))

    async def input_stream(self, request):
        self.check_input()
        return await self.remote_input.stream(request)

    async def input_release(self, request):
        self.check_input()
        data = await self.body(request)
        self.validate_id(data.get("session"))
        return web.json_response(await self.remote_input.release(data["session"]))

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
                "plugin_management": bool(self.config.get("allow_plugin_management", False)),
                "config_management": bool(self.config.get("allow_config_management", False)),
                "desktop_video": self.video is not None,
                "desktop_input": self.remote_input is not None,
                "desktop_input_stream": self.remote_input is not None,
                "desktop_allowed": bool(self.config.get("allow_desktop_control", False)),
                "desktop_enabled": self.is_control_enabled(),
                "control_until": self.control_until if self.is_control_enabled() else 0,
                "shell_allowed": bool(self.config.get("allow_shell", False)),
                "phone_allowed": bool(self.config.get("allow_phone_control", False)),
                "max_control_hours": min(24, max(1, int(self.config.get("max_control_hours", 8)))),
                "running_job": self.current_job,
                "pending_jobs": self.queue.qsize(),
                "repeat_modes": [1, 5, 10, 0],
                "repeat_interval_seconds": self.repeat_delay(),
                "waiting_jobs": len(self.repeat_tasks),
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
        return web.json_response({
            **item, "events": self.store.events(item["id"]), "runs": self.store.runs(item["id"])
        })

    async def submit(self, request):
        data = await self.body(request)
        job_id, session = data.get("id", ""), data.get("session", "")
        prompt, control = data.get("prompt", ""), data.get("control", False)
        phone, device = data.get("phone", False), data.get("device", "")
        repeat_count = data.get("repeat_count", 1)
        if type(repeat_count) is not int or repeat_count not in (1, 5, 10, 0):
            raise ValueError("任务次数只能为 1、5、10，或 0（一直）")
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
                old["repeat_count"],
            ) != (session, prompt, control, phone, device, repeat_count):
                raise web.HTTPConflict(reason="任务 ID 已被另一条消息使用")
            return web.json_response(old)
        if control and not self.is_control_enabled():
            raise web.HTTPForbidden(reason="请先开启电脑控制并设置授权时长")
        if phone and not self.phone.enabled(device):
            raise web.HTTPForbidden(reason="请先在手机开启手机控制并保持服务在线")
        if self.queue.full() or len(self.store.active()) >= 33:
            raise web.HTTPTooManyRequests(reason="任务队列已满，请稍后重试")
        item = self.store.create(job_id, session, prompt, control, phone, device, repeat_count)
        self.queue.put_nowait(job_id)
        self.store.audit("submitted", job_id, control=control, repeat_count=repeat_count)
        return web.json_response(item, status=202)

    async def cancel_job(self, job_id, reason="主人停止了任务"):
        item = self.store.get(job_id)
        await self.cancel_jobs([item] if item else [], reason)
        return self.store.get(job_id)

    async def cancel_jobs(self, items, reason):
        pending = []
        # Mark the entire batch before awaiting anything, so queued rounds cannot start mid-revocation.
        for item in items:
            if item["state"] not in ("queued", "running", "waiting"):
                continue
            job_id = item["id"]
            self.store.update(job_id, "cancelled", error=reason)
            if item["state"] == "running" and item["runs_started"]:
                self.store.finish_run(job_id, item["runs_started"], "cancelled", error=reason)
            self.store.audit("cancelled", job_id)
            self.phone.abort(job_id=job_id)
            scheduled = self.repeat_tasks.pop(job_id, None)
            if scheduled:
                scheduled.cancel()
                pending.append(scheduled)
            if self.current_job == job_id and self.current_task:
                self.current_task.cancel()
                pending.append(self.current_task)
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

    async def cancel(self, request):
        item = await self.cancel_job(request.match_info["job_id"])
        if not item:
            raise web.HTTPNotFound(reason="任务不存在")
        return web.json_response(item)

    async def revoke(self, all_jobs=False):
        self.control_until = 0
        if self.remote_input:
            await self.remote_input.release()
        await self.cancel_jobs(
            [item for item in self.store.active() if all_jobs or item["control"]],
            "电脑控制授权已撤销或到期" if not all_jobs else "紧急停止",
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
        self.phone.stop_all()
        return web.json_response({"stopped": True})

    def repeat_delay(self):
        return max(1, min(3600, int(self.config.get("repeat_interval_seconds", 5))))

    def schedule_repeat(self, job_id):
        delay = self.repeat_delay()
        self.store.update(job_id, "waiting", next_run_at=time.time() + delay)

        async def enqueue():
            try:
                await asyncio.sleep(delay)
                job = self.store.get(job_id)
                if job and job["state"] == "waiting":
                    # Waiting here retains cancellation and keeps other jobs ahead of this round.
                    await self.queue.put(job_id)
                    if self.store.get(job_id)["state"] == "waiting":
                        self.store.update(job_id, "queued")
            finally:
                if self.repeat_tasks.get(job_id) is asyncio.current_task():
                    self.repeat_tasks.pop(job_id, None)

        self.repeat_tasks[job_id] = asyncio.create_task(enqueue())

    def fail_round(self, job_id, number, reason):
        saved = self.store.get(job_id)
        state = "cancelled" if saved["state"] == "cancelled" else "failed"
        error = saved["error"] if state == "cancelled" else reason
        if number:
            self.store.finish_run(job_id, number, state, error=error)
        if state != "cancelled":
            self.store.update(job_id, state, error=error)
        self.store.audit("round_stopped", job_id, number=number, reason=error)

    async def work(self):
        while True:
            job_id = await self.queue.get()
            number = 0
            try:
                job = self.store.get(job_id)
                if not job or job["state"] != "queued":
                    continue
                self.store.prune(max(1, int(self.config.get("retention_days", 30))))
                self.store.update(job_id, "running", error="")
                self.current_job = job_id
                if job["control"]:
                    self.check_control(job)
                if job["phone"]:
                    self.phone.check(job)
                job = self.store.begin_run(job_id)
                number = job["runs_started"]
                self.store.audit("round_started", job_id, number=number)
                history = self.store.history(
                    job["session"], max(0, min(50, int(self.config.get("history_turns", 12))))
                )
                self.current_task = asyncio.create_task(self.backend(job, history, self))
                timeout = max(10, min(28800, int(self.config.get("task_timeout_seconds", 3600))))
                result = await asyncio.wait_for(self.current_task, timeout)
                if self.store.get(job_id)["state"] == "running":
                    result = str(result or "本轮已结束，模型未返回文本。")
                    self.store.finish_run(job_id, number, "completed", result=result)
                    self.store.audit("round_completed", job_id, number=number)
                    job = self.store.get(job_id)
                    if job["repeat_count"] == 0 or job["runs_completed"] < job["repeat_count"]:
                        self.schedule_repeat(job_id)
                    else:
                        self.store.update(job_id, "completed", error="")
            except asyncio.CancelledError:
                saved = self.store.get(job_id)
                if saved["state"] == "running":
                    self.store.update(job_id, "cancelled", error="任务已停止")
                if number:
                    self.store.finish_run(job_id, number, "cancelled", error=saved["error"] or "任务已停止")
                if asyncio.current_task().cancelling():
                    raise
            except TimeoutError:
                self.fail_round(
                    job_id, number, "本轮超时，后续轮次已停止。请拆分任务或调整执行时限。"
                )
            except PermissionError as exc:
                self.fail_round(job_id, number, str(exc))
            except Exception:
                log.exception("Android Connect job %s failed", job_id)
                self.fail_round(
                    job_id, number, "本轮执行失败，后续轮次已停止。请查看 AstrBot 日志和模型配置。"
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
        if self.video:
            await self.video.close()
        if self.remote_input:
            await self.remote_input.close()
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
