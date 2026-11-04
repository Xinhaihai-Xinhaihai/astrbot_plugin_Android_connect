"""Optional tests against the real AstrBot SDK; no model credentials needed."""

import importlib.util
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

if importlib.util.find_spec("astrbot") is None:
    pytest.skip("Install AstrBot 4.29.4 to run SDK integration tests", allow_module_level=True)

from bridge.agent import AgentBackend, make_event


async def test_real_sdk_chat_and_scoped_device_tools():
    context = SimpleNamespace(
        get_current_chat_provider_id=AsyncMock(return_value="test-provider"),
        persona_manager=SimpleNamespace(
            get_default_persona_v3=AsyncMock(return_value=SimpleNamespace(system_prompt="personality"))
        ),
        llm_generate=AsyncMock(return_value=SimpleNamespace(completion_text="hello")),
        tool_loop_agent=AsyncMock(return_value=SimpleNamespace(completion_text="done")),
    )
    desktop = SimpleNamespace(perform=AsyncMock(return_value={"ok": True}))
    config = {"allow_shell": False}
    backend = AgentBackend(context, config, desktop)
    job = {"id": "test-job", "session": "test-session", "prompt": "hello", "control": False, "phone": False}
    bridge = SimpleNamespace(
        check_control=lambda _: None,
        store=SimpleNamespace(audit=lambda *a, **kw: None),
        phone=SimpleNamespace(execute=AsyncMock(return_value="phone result")),
    )
    assert make_event(job, bridge).unified_msg_origin == "android_connect:FriendMessage:test-session"
    assert await backend(job, [], bridge) == "hello"
    context.tool_loop_agent.assert_not_awaited()
    job.update(control=True, phone=True)
    async def exercise_tools(**args):
        names = {tool.name for tool in args["tools"].tools}
        assert "android_desktop_screenshot" in names
        assert "android_phone_inspect" in names
        assert "android_task_complete" in names
        if not config["allow_shell"]:
            assert "android_desktop_shell" not in names
        phone_tool = args["tools"].get_tool("android_phone_inspect")
        assert await phone_tool.handler(args["event"]) == "phone result"
        if config["allow_shell"]:
            tool = args["tools"].get_tool("android_desktop_shell")
            config["allow_shell"] = False
            with pytest.raises(PermissionError):
                await tool.handler(args["event"], command="echo test")
        return SimpleNamespace(completion_text="done")
    context.tool_loop_agent.side_effect = exercise_tools
    assert await backend(job, [], bridge) == "done"
    args = context.tool_loop_agent.await_args.kwargs
    assert "本轮任务只执行一次" in args["system_prompt"]
    assert args["max_steps"] == 80
    config["allow_shell"] = True
    await backend(job, [], bridge)


async def test_agent_round_blocks_repeated_hardware_operation():
    import asyncio

    from bridge.round_guard import RepeatedOperationError

    context = SimpleNamespace(
        get_current_chat_provider_id=AsyncMock(return_value="test-provider"),
        persona_manager=SimpleNamespace(
            get_default_persona_v3=AsyncMock(return_value=SimpleNamespace(system_prompt=""))
        ),
    )
    desktop = SimpleNamespace(perform=AsyncMock(return_value={"ok": True}))
    job = {"id": "guard-job", "session": "guard-session", "prompt": "输入一次", "control": True, "phone": False}
    bridge = SimpleNamespace(check_control=lambda _: None, store=SimpleNamespace(audit=lambda *a, **kw: None))
    async def run_tools(**args):
        tool = args["tools"].get_tool("android_desktop_type_text")
        await tool.handler(args["event"], text="只输入一次")
        try:
            await tool.handler(args["event"], text="只输入一次")
        except RepeatedOperationError:
            pass
        await asyncio.sleep(30)
    context.tool_loop_agent = AsyncMock(side_effect=run_tools)
    with pytest.raises(RepeatedOperationError):
        await AgentBackend(context, {}, desktop)(job, [], bridge)
    assert desktop.perform.await_count == 1
