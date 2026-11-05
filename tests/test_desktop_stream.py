import asyncio
import contextlib
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from aiohttp import WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer

from bridge.config_labels import describe, groups
from bridge.desktop import Desktop
from bridge.remote_input import RemoteInput
from bridge.service import BridgeService


async def test_stream_auth_order_cursor_and_disconnect(tmp_path):
    config = {"api_token": "secret" * 8, "allow_desktop_control": True}
    service = BridgeService(config, tmp_path, AsyncMock())
    desktop = SimpleNamespace(supported_keys=Desktop.supported_keys, apply_state=AsyncMock(),
                              cursor=lambda: {"x": .25, "y": .5, "width": 2560, "height": 1440})
    service.remote_input = RemoteInput(desktop, service.is_control_enabled)
    async with TestClient(TestServer(service.app)) as client:
        with pytest.raises(WSServerHandshakeError) as error:
            await client.ws_connect("/api/v1/desktop/input?session=session-one")
        assert error.value.status == 401
        client.session.headers["Authorization"] = "Bearer " + service.token
        with pytest.raises(WSServerHandshakeError) as error:
            await client.ws_connect("/api/v1/desktop/input?session=session-one")
        assert error.value.status == 403
        service.control_until = time.time() + 100
        await service.remote_input.open("session-one")
        ws = await client.ws_connect("/api/v1/desktop/input?session=session-one")
        ready = await ws.receive_json()
        assert ready["ready"] and ready["cursor"]["width"] == 2560
        # Send both transitions without waiting for a network round trip.
        await ws.send_json(dict(session="session-one", sequence=1, keys=["ctrl", "c"], buttons=["left"], dx=3))
        await ws.send_json(dict(session="session-one", sequence=2, keys=[], buttons=["left"], dx=9))
        assert (await ws.receive_json())["sequence"] == 1
        assert (await ws.receive_json())["sequence"] == 2
        assert desktop.apply_state.await_args.args[2]["dx"] == 9
        await ws.close()
        for _ in range(20):
            if service.remote_input.session is None:
                break
            await asyncio.sleep(.01)
        assert service.remote_input.session is None
        assert desktop.apply_state.await_args.args[1] == set()
    await service.close()


async def test_old_stream_cannot_release_new_session(tmp_path):
    service = BridgeService({"api_token": "secret" * 8}, tmp_path, AsyncMock())
    service.remote_input = RemoteInput(SimpleNamespace(supported_keys=Desktop.supported_keys,
                                                       apply_state=AsyncMock()), lambda: True)
    async with TestClient(TestServer(service.app)) as client:
        client.session.headers["Authorization"] = "Bearer " + service.token
        await service.remote_input.open("session-one")
        ws = await client.ws_connect("/api/v1/desktop/input?session=session-one")
        await ws.receive_json()
        await service.remote_input.release("session-one")
        await service.remote_input.open("session-two")
        await ws.close()
        await asyncio.sleep(.03)
        assert service.remote_input.session == "session-two"
    await service.close()


async def test_bad_stream_packet_closes_and_releases(tmp_path):
    service = BridgeService({"api_token": "secret" * 8}, tmp_path, AsyncMock())
    desktop = SimpleNamespace(supported_keys=Desktop.supported_keys, apply_state=AsyncMock())
    service.remote_input = RemoteInput(desktop, lambda: True)
    async with TestClient(TestServer(service.app)) as client:
        client.session.headers["Authorization"] = "Bearer " + service.token
        await service.remote_input.open("session-one")
        ws = await client.ws_connect("/api/v1/desktop/input?session=session-one")
        await ws.receive_json()
        await ws.send_json(dict(session="session-one", sequence=1, buttons=["left"]))
        await ws.receive_json()
        await ws.send_json(dict(session="session-wrong", sequence=2, dx=10))
        assert "error" in await ws.receive_json()
        await ws.close()
        assert service.remote_input.session is None
        assert not service.remote_input.buttons
    await service.close()


async def test_origin_is_valid_pointer_movement(monkeypatch):
    desktop = Desktop()
    desktop._send_state = Mock()
    monkeypatch.setattr("bridge.desktop.os", SimpleNamespace(name="nt"))
    monkeypatch.setattr("bridge.desktop.physical_pixels", contextlib.nullcontext)
    await desktop.apply_state(set(), set(), {"x": 0, "y": 0}, set(), set())
    desktop._send_state.assert_called_once()


def test_chinese_groups_and_labels_preserve_raw_values():
    original = {"provider_sources": [{"id": "my-service", "key": ["keep-private"], "api_base": "https://example.test"}],
                "platform_settings": {"rate_limit": {"strategy": "stall"}},
                "new_future_field": 4}
    localized = describe(original, {})
    assert localized["provider_sources"]["description"] == "模型服务商"
    assert localized["provider_sources"]["items"]["key"]["secret"]
    assert localized["platform_settings"]["items"]["rate_limit"]["items"]["strategy"]["description"] == "超出限制时的处理方式"
    assert "keep-private" not in str(localized)
    assert original["platform_settings"]["rate_limit"]["strategy"] == "stall"
    layout = groups(original)
    assert [g["name"] for g in layout] == ["模型服务", "消息平台", "其他配置"]
    assert {k for group in layout for k in group["keys"]} == set(original)
