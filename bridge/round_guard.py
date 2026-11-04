"""Stop an agent round before repeating an already issued operation or an action cycle."""

import asyncio
import hashlib
import json


class RepeatedOperationError(PermissionError):
    pass


class RoundGuard:
    def __init__(self):
        self.finished = asyncio.Event()
        self.summary = ""
        self.error = None
        self.observations = {}
        self.issued = set()
        self.actions = []

    def check_open(self):
        if self.finished.is_set():
            raise self.error or PermissionError("本轮已完成，不能继续操作设备。")

    def observe(self, channel, result):
        encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, default=str).encode()
        self.observations[channel] = hashlib.sha256(encoded).hexdigest()

    def before(self, channel, action, arguments):
        self.check_open()
        if action in ("screenshot", "inspect"):
            return
        normalized = dict(arguments)
        if action == "shell":
            normalized.pop("timeout_seconds", None)
            normalized["command"] = str(normalized.get("command", "")).strip()
        payload = json.dumps([channel, action, normalized], ensure_ascii=False, sort_keys=True)
        operation = hashlib.sha256(payload.encode()).hexdigest()
        observation = "round" if action == "shell" else self.observations.get(channel, "")
        key = (operation, observation)
        reason = ""
        if key in self.issued:
            reason = "检测到同一轮重复设备指令，已拦截并停止任务，避免重复执行。"
        candidate = self.actions + [operation]
        for size in range(2, min(8, len(candidate) // 2) + 1):
            if candidate[-size:] == candidate[-2 * size:-size]:
                reason = "检测到 Agent 重复循环同一组动作，已拦截并停止任务。"
                break
        if reason:
            self.error = RepeatedOperationError(reason)
            self.finished.set()
            raise self.error
        # Claim before execution: a timeout has an unknown outcome and must not trigger a replay.
        self.issued.add(key)
        self.actions = candidate[-16:]

    async def complete(self, event, summary):
        self.check_open()
        if not isinstance(summary, str) or not summary.strip() or len(summary) > 8000:
            raise ValueError("请提供 1–8000 字的本轮结果说明。")
        self.summary = summary.strip()
        self.finished.set()
        return "本轮已结束，插件会决定是否执行下一轮。"

    async def run(self, generation):
        runner = asyncio.create_task(generation)
        completed = asyncio.create_task(self.finished.wait())
        try:
            await asyncio.wait((runner, completed), return_when=asyncio.FIRST_COMPLETED)
            if self.finished.is_set():
                if self.error:
                    raise self.error
                return self.summary
            result = await runner
            if getattr(result, "tools_call_name", None):
                raise PermissionError("本轮达到 Agent 步骤上限但未完成，后续轮次已停止。")
            return result.completion_text
        finally:
            self.finished.set()
            runner.cancel()
            completed.cancel()
            await asyncio.gather(runner, completed, return_exceptions=True)
