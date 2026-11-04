import asyncio
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from bridge.desktop import Desktop
from bridge.panel import PluginPanel
from bridge.remote_input import RemoteInput
from bridge.service import BridgeService


@pytest.fixture
def panel():
    stars = [SimpleNamespace(name=name, root_dir_name=name, activated=True, reserved=False)
             for name in ("example", "astrbot_plugin_Android_connect")]
    async def enable(name):
        next(star for star in stars if star.name == name).activated = True
    async def disable(name):
        next(star for star in stars if star.name == name).activated = False
    manager = SimpleNamespace(turn_on_plugin=AsyncMock(side_effect=enable),
                              turn_off_plugin=AsyncMock(side_effect=disable))
    context = SimpleNamespace(_star_manager=manager, get_all_stars=lambda: stars,
                              get_registered_star=lambda name: next((s for s in stars if s.name == name), None))
    return PluginPanel(context)


async def test_plugin_state_idempotent_and_self_protected(panel):
    await asyncio.gather(*(panel.change("example", False) for _ in range(8)))
    panel.manager().turn_off_plugin.assert_awaited_once_with("example")
    assert not panel.listing()["plugins"][0]["enabled"]
    await panel.change("example", True)
    assert panel.listing()["plugins"][0]["enabled"]
    with pytest.raises(web.HTTPForbidden):
        await panel.change("astrbot_plugin_Android_connect", False)
    with pytest.raises(web.HTTPNotFound):
        await panel.change("missing", False)
    with pytest.raises(ValueError):
        await panel.change("example", "false")
    panel.context.get_all_stars()[0].reserved = True
    with pytest.raises(web.HTTPForbidden):
        await panel.change("example", False)


@pytest.fixture
async def remote():
    desktop = SimpleNamespace(supported_keys=Desktop.supported_keys, apply_state=AsyncMock())
    lease = RemoteInput(desktop, lambda: True, timeout=.1)
    await lease.open("session-one")
    yield lease
    await lease.close()


async def test_input_exact_order_and_disconnect_release(remote):
    await remote.update(dict(session="session-one", sequence=1, keys=["w", "shift"], buttons=["left"]))
    calls = remote.desktop.apply_state.await_count
    assert (await remote.update(dict(session="session-one", sequence=1, keys=[])))["ignored"]
    assert remote.desktop.apply_state.await_count == calls
    with pytest.raises(web.HTTPConflict):
        await remote.open("session-two")
    await remote.release("session-other")
    assert remote.keys == {"w", "shiftleft"}
    await remote.release("session-one")
    assert remote.keys == set() and remote.session is None
    args = remote.desktop.apply_state.await_args.args
    assert args[:2] == (set(), set()) and args[3:] == ({"w", "shiftleft"}, {"left"})
    with pytest.raises(web.HTTPForbidden):
        await remote.update(dict(session="session-one", sequence=2, keys=["w"]))


async def test_watchdog_and_revocation(remote):
    await remote.update(dict(session="session-one", sequence=1, keys=["w"]))
    await asyncio.sleep(.3)
    assert remote.session is None and remote.keys == set()
    await remote.open("session-two")
    await remote.update(dict(session="session-two", sequence=1, keys=["a"]))
    remote.allowed = lambda: False
    await asyncio.sleep(.3)
    assert remote.session is None and remote.keys == set()


@pytest.mark.parametrize("extra", [{"keys": ["unknown"]}, {"buttons": ["x"]}, {"dx": float("nan")},
                                   {"x": 1.2, "y": 0}, {"keys": "w"}, {"sequence": True}])
async def test_invalid_input_never_reaches_hardware(remote, extra):
    with pytest.raises(ValueError):
        await remote.update({"session": "session-one", "sequence": 1, **extra})
    remote.desktop.apply_state.assert_not_awaited()


async def test_partial_hardware_failure_releases_new_keys(remote):
    remote.desktop.apply_state.side_effect = [RuntimeError("input failed"), None]
    with pytest.raises(RuntimeError):
        await remote.update(dict(session="session-one", sequence=1, keys=["w"]))
    assert remote.desktop.apply_state.await_args.args[3] == {"w"}
    assert remote.session is None


async def test_panel_and_input_api_permissions(tmp_path, panel):
    config = {"api_token": "secret" * 8, "allow_desktop_control": True}
    bridge = BridgeService(config, tmp_path, AsyncMock(), panel=panel)
    bridge.remote_input = RemoteInput(SimpleNamespace(supported_keys=Desktop.supported_keys,
                                                     apply_state=AsyncMock()), bridge.is_control_enabled)
    client = TestClient(TestServer(bridge.app))
    await client.start_server()
    try:
        assert (await client.get("/api/v1/panel/plugins")).status == 401
        client.session.headers["Authorization"] = "Bearer " + config["api_token"]
        assert (await client.get("/api/v1/panel/plugins")).status == 403
        config["allow_plugin_management"] = True
        assert (await client.get("/api/v1/panel/plugins")).status == 200
        assert (await client.post("/api/v1/panel/plugins", json={"name": "example", "enabled": False})).status == 200
        assert (await client.post("/api/v1/desktop/session", json={"session": "session-one"})).status == 403
        bridge.control_until = time.time() + 100
        assert (await client.post("/api/v1/desktop/session", json={"session": "session-one"})).status == 200
        assert (await client.post("/api/v1/desktop/input", json={"session": "session-one", "sequence": 1, "keys": ["w"]})).status == 200
        await client.post("/api/v1/stop", json={})
        assert bridge.remote_input.session is None
    finally:
        await bridge.close()
        await client.close()
