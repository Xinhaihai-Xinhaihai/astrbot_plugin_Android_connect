import asyncio
import io
import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from bridge.config_panel import ConfigPanel, validate
from bridge.service import BridgeService
from bridge.video import AccessUnits, DesktopVideo


class Config(dict):
    def __init__(self, path, values, schema=None):
        super().__init__(values)
        self.config_path, self.schema = str(path), schema or {}
        self.save_config({})

    def save_config(self, values):
        self.update(values)
        with open(self.config_path, "w", encoding="utf-8") as file:
            json.dump(self, file)


@pytest.fixture
def panel(tmp_path):
    plugin = Config(tmp_path / "plugin.json", {"count": 3, "enabled": True, "nested": {"text": "原文"}},
                    {"count": {"type": "int"}, "nested": {"type": "object", "items": {"text": {"type": "text"}}}})
    core = Config(tmp_path / "core.json", {"provider": [], "dashboard": {"password": "private"}})
    own = Config(tmp_path / "own.json", {"api_token": "private-token", "system_prompt": "hello"})
    stars = {"example": SimpleNamespace(config=plugin, name="example", activated=True, reserved=False),
             "astrbot_plugin_Android_connect": SimpleNamespace(config=own, name="astrbot_plugin_Android_connect", reserved=False)}
    context = SimpleNamespace(get_registered_star=stars.get, get_config=lambda: core,
                              _star_manager=SimpleNamespace(reload=AsyncMock(return_value=(True, None))))
    return ConfigPanel(context, tmp_path)


async def test_config_save_revision_backup_and_reload(panel):
    document = await panel.read("plugin", "example")
    document["values"]["count"] = 9
    saved = await panel.save("plugin", "example", document["values"], document["revision"])
    assert saved["saved"] and saved["values"]["count"] == 9
    assert json.loads(next(panel.directory.glob("*.json")).read_text(encoding="utf-8"))["count"] == 3
    with pytest.raises(web.HTTPConflict):
        await panel.save("plugin", "example", document["values"], document["revision"])
    assert (await panel.reload("example"))["reloaded"]
    panel.context._star_manager.reload.assert_awaited_once_with("example")
    with pytest.raises(web.HTTPForbidden):
        await panel.reload("astrbot_plugin_Android_connect")


@pytest.mark.parametrize("values", [{"count": True, "enabled": True, "nested": {"text": "x"}},
                                   {"count": 1, "enabled": "yes", "nested": {"text": "x"}},
                                   {"count": 1, "enabled": True, "nested": {}},
                                   {"count": 1}])
async def test_bad_config_never_saved(panel, values):
    before = await panel.read("plugin", "example")
    with pytest.raises(ValueError):
        await panel.save("plugin", "example", values, before["revision"])
    assert (await panel.read("plugin", "example")) == before


async def test_external_file_change_and_locked_values(panel):
    before = await panel.read("global", "default")
    assert "dashboard" not in before["values"]
    assert before["locked"] == ["dashboard"]
    await panel.save("global", "default", {"provider": [{"id": "test"}]}, before["revision"])
    assert panel.context.get_config()["dashboard"]["password"] == "private"
    before = await panel.read("plugin", "example")
    path = panel.context.get_registered_star("example").config.config_path
    with open(path, "w", encoding="utf-8") as file:
        json.dump({"count": 8}, file)
    with pytest.raises(web.HTTPConflict):
        await panel.save("plugin", "example", before["values"], before["revision"])
    for kind, name in (("plugin", "../../core"), ("global", "../../core")):
        with pytest.raises(web.HTTPNotFound):
            await panel.read(kind, name)
    own = await panel.read("plugin", "astrbot_plugin_Android_connect")
    assert "api_token" not in own["values"]
    own["values"]["api_token"] = "replace-token"
    with pytest.raises(ValueError):
        await panel.save("plugin", "astrbot_plugin_Android_connect", own["values"], own["revision"])


async def test_persona_update_retains_nullable_tools(panel):
    persona = SimpleNamespace(persona_id="default", system_prompt="before", begin_dialogs=[], tools=None,
                              skills=[], custom_error_message=None)
    async def update(name, **values):
        assert name == "default"
        for key, value in values.items():
            setattr(persona, key, value)
    panel.context.persona_manager = SimpleNamespace(get_all_personas=AsyncMock(return_value=[persona]), update_persona=update)
    original = await panel.read("persona", "default")
    original["values"]["system_prompt"] = "after"
    result = await panel.save("persona", "default", original["values"], original["revision"])
    assert result["values"]["system_prompt"] == "after" and result["values"]["tools"] is None
    assert not result["reload_required"]


def test_nested_configuration_lists_and_templates():
    schema = {"type": "list", "items": {"enable": {"type": "bool"}}}
    validate([{"enable": True}], [], schema)
    with pytest.raises(ValueError):
        validate(["not-a-provider"], [], schema)
    with pytest.raises(ValueError):
        validate([{"enable": "yes"}], [], schema)
    template = {"type": "template_list", "templates": {"text": {"items": {"body": {"type": "string"}}}}}
    validate([{"__template_key": "text", "body": "hello"}], [], template)
    with pytest.raises(ValueError):
        validate([{"body": "hello"}], [], template)
    validate(None, ["tool"], {"type": "list", "nullable": True})


async def test_config_api_permissions_and_large_document(tmp_path, panel):
    config = {"api_token": "secret" * 8}
    service = BridgeService(config, tmp_path / "service", AsyncMock())
    service.config_panel = panel
    async with TestClient(TestServer(service.app)) as client:
        assert (await client.get("/api/v1/panel/configs")).status == 401
        client.session.headers["Authorization"] = "Bearer " + config["api_token"]
        assert (await client.get("/api/v1/panel/configs")).status == 403
        config["allow_config_management"] = True
        assert (await client.get("/api/v1/panel/configs")).status == 200
        result = await (await client.get("/api/v1/panel/config?kind=plugin&id=example")).json()
        result["values"]["nested"]["text"] = "a" * 40000
        assert (await client.post("/api/v1/panel/config", json=result)).status == 200
        assert (await client.post("/api/v1/panel/config", json={"values": "a" * 650000})).status == 413
    await service.close()


def test_annexb_parser_handles_every_split_and_frame_limit():
    units = [b"\x00\x00\x00\x01\x09\xf0\x00\x00\x01\x65frame-one",
             b"\x00\x00\x01\x09\xf0\x00\x00\x01\x41frame-two",
             b"\x00\x00\x00\x01\x09\xf0"]
    encoded = b"".join(units)
    for step in (1, 2, 3, 5, 32, 100):
        parser, result = AccessUnits(), []
        for offset in range(0, len(encoded), step):
            result.extend(parser.feed(encoded[offset:offset + step]))
        assert result == units[:2]
    with pytest.raises(ValueError):
        AccessUnits().feed(b"x" * (2 * 1024 * 1024 + 1))


async def test_video_checks_session_before_capture():
    video = DesktopVideo(SimpleNamespace(allowed=lambda: False, session="example", deadline=time.monotonic() + 3))
    with pytest.raises(PermissionError):
        video.check("example")
    video.input.allowed = lambda: True
    with pytest.raises(PermissionError):
        video.check("stale-session")
    video.check("example")
    video.input.deadline = 0
    with pytest.raises(PermissionError):
        video.check("example")


async def test_video_stream_disconnect_kills_encoder(tmp_path, monkeypatch):
    source = io.BufferedReader(io.BytesIO((b"\x00\x00\x00\x01\x09\xf0frame" * 8)))
    process = SimpleNamespace(stdout=source, poll=lambda: None, kill=lambda: killed.append(True), wait=lambda: 0)
    killed = []
    monkeypatch.setattr("bridge.video.subprocess.Popen", lambda *a, **kw: process)
    monkeypatch.setattr("bridge.video.subprocess.CREATE_NO_WINDOW", 0, raising=False)
    monkeypatch.setattr(DesktopVideo, "executable", staticmethod(lambda: "ffmpeg"))
    monkeypatch.setattr(DesktopVideo, "dimensions", staticmethod(lambda edge: (1920, 1080, 1280, 720)))
    video = DesktopVideo(SimpleNamespace(allowed=lambda: True, session="test-session", deadline=time.monotonic() + 5))
    service = BridgeService({"api_token": "secret" * 8}, tmp_path, AsyncMock())
    service.video = video
    async with TestClient(TestServer(service.app)) as client:
        assert (await client.get("/api/v1/desktop/video")).status == 401
        client.session.headers["Authorization"] = "Bearer " + service.token
        assert (await client.get("/api/v1/desktop/video?session=test-session&fps=1000")).status == 400
        ws = await client.ws_connect("/api/v1/desktop/video?session=test-session&fps=60")
        assert (await ws.receive_json())["fps"] == 60
        await ws.close()
        await asyncio.sleep(.1)
        await video.close()
        assert killed and not video.handlers and video.active is None
    await service.close()
