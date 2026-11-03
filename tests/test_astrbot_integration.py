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
    assert await backend(job, [], bridge) == "done"
    args = context.tool_loop_agent.await_args.kwargs
    names = {tool.name for tool in args["tools"].tools}
    assert "android_desktop_screenshot" in names
    assert "android_phone_inspect" in names
    assert "android_desktop_shell" not in names
    phone_tool = args["tools"].get_tool("android_phone_inspect")
    assert await phone_tool.handler(args["event"]) == "phone result"
    config["allow_shell"] = True
    await backend(job, [], bridge)
    tool = context.tool_loop_agent.await_args.kwargs["tools"].get_tool("android_desktop_shell")
    config["allow_shell"] = False
    with pytest.raises(PermissionError):
        await tool.handler(args["event"], command="echo test")
