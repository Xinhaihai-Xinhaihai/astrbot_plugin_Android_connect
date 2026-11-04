import asyncio
from types import SimpleNamespace

import pytest

from bridge.round_guard import RepeatedOperationError, RoundGuard


async def test_repeated_call_is_stopped_even_if_sdk_swallows_tool_errors():
    guard = RoundGuard()
    performed = []
    stopped = asyncio.Event()
    async def generation():
        try:
            for _ in range(10):
                try:
                    guard.before("desktop", "type_text", {"text": "不能输入两次"})
                    performed.append("typed")
                except RepeatedOperationError:
                    pass  # AstrBot returns tool failures to the model; the outer guard must stop it.
                await asyncio.sleep(0)
        finally:
            stopped.set()
        return SimpleNamespace(completion_text="done")
    with pytest.raises(RepeatedOperationError, match="重复"):
        await guard.run(generation())
    assert performed == ["typed"]
    assert stopped.is_set()


def test_identical_observation_does_not_allow_duplicate_action():
    guard = RoundGuard()
    guard.observe("phone", {"screen": "unchanged"})
    guard.before("phone", "tap", {"x": 5, "y": 8})
    guard.observe("phone", {"screen": "unchanged"})
    with pytest.raises(RepeatedOperationError):
        guard.before("phone", "tap", {"y": 8, "x": 5})


def test_navigation_can_reuse_coordinates_after_screen_changes():
    guard = RoundGuard()
    for screen in ("desktop", "app"):
        guard.observe("desktop", {"screen": screen})
        guard.before("desktop", "click", {"x": 5, "y": 8})


def test_repeating_action_cycle_is_blocked_even_if_observations_change():
    guard = RoundGuard()
    for index in range(3):
        guard.observe("desktop", {"screen": index})
        guard.before("desktop", "click", {"x": index % 2, "y": 8})
    guard.observe("desktop", {"screen": 3})
    with pytest.raises(RepeatedOperationError, match="循环"):
        guard.before("desktop", "click", {"x": 1, "y": 8})


def test_shell_retry_with_different_timeout_is_not_reexecuted():
    guard = RoundGuard()
    guard.before("desktop", "shell", {"command": "write-file", "timeout_seconds": 60})
    guard.observe("desktop", {"screen": "new"})
    with pytest.raises(RepeatedOperationError):
        guard.before("desktop", "shell", {"command": " write-file ", "timeout_seconds": 120})


async def test_complete_tool_ends_round_and_blocks_later_operations():
    guard = RoundGuard()
    stopped = asyncio.Event()
    async def generation():
        try:
            await guard.complete(None, "已经完成")
            with pytest.raises(PermissionError):
                guard.before("phone", "home", {})
            await asyncio.sleep(30)
        finally:
            stopped.set()
    assert await guard.run(generation()) == "已经完成"
    assert stopped.is_set()


async def test_cancelling_round_also_cancels_agent():
    guard = RoundGuard()
    began, stopped = asyncio.Event(), asyncio.Event()
    async def generation():
        try:
            began.set()
            await asyncio.sleep(30)
        finally:
            stopped.set()
    task = asyncio.create_task(guard.run(generation()))
    await began.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert stopped.is_set()


async def test_step_limit_is_not_counted_as_completed():
    async def generation():
        return SimpleNamespace(completion_text="", tools_call_name=["android_desktop_click"])
    with pytest.raises(PermissionError, match="步骤上限"):
        await RoundGuard().run(generation())


def test_each_requested_round_has_a_fresh_operation_guard():
    for _ in range(5):
        RoundGuard().before("phone", "home", {})
