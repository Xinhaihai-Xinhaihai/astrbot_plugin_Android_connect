import asyncio
import time
import uuid

import pytest
from aiohttp.test_utils import TestClient, TestServer

from bridge.service import BridgeService
from bridge.store import Store


def request(**extra):
    return {"id": str(uuid.uuid4()), "session": "session-test", "prompt": "帮我整理", **extra}


@pytest.fixture
async def harness(tmp_path):
    calls = []
    gate = asyncio.Event()

    async def backend(job, history, service):
        calls.append((job, history))
        if job["prompt"] == "wait":
            await gate.wait()
        return "处理完成"

    config = {
        "api_token": "test-secret-" * 4,
        "allow_desktop_control": True,
        "allow_phone_control": True,
    }
    bridge = BridgeService(config, tmp_path, backend)
    client = TestClient(TestServer(bridge.app))
    await client.start_server()
    client.session.headers["Authorization"] = "Bearer " + config["api_token"]
    bridge.worker = asyncio.create_task(bridge.work())
    yield bridge, client, calls, gate
    await bridge.close()
    await client.close()


async def settle(bridge):
    await asyncio.wait_for(bridge.queue.join(), 2)


async def test_auth_and_invalid_payload(harness):
    bridge, client, _, _ = harness
    for route in ("/status", "/jobs"):
        response = await client.get("/api/v1" + route, headers={"Authorization": "Bearer wrong"})
        assert response.status == 401
    assert (await client.post("/api/v1/jobs", json=[])).status == 400
    assert (await client.post("/api/v1/jobs", json=request(control="true"))).status == 400
    assert (await client.post("/api/v1/jobs", json=request(prompt=""))).status == 400
    assert not bridge.is_control_enabled()


async def test_retry_is_idempotent_and_history_isolated(harness):
    bridge, client, calls, _ = harness
    job = request()
    assert (await client.post("/api/v1/jobs", json=job)).status == 202
    assert (await client.post("/api/v1/jobs", json=job)).status == 200
    await settle(bridge)
    assert len(calls) == 1
    assert bridge.store.get(job["id"])["result"] == "处理完成"
    assert (await client.post("/api/v1/jobs", json={**job, "prompt": "different"})).status == 409
    await client.post("/api/v1/jobs", json=request())
    await settle(bridge)
    assert calls[-1][1][0]["role"] == "user"
    await client.post("/api/v1/jobs", json=request(session="another-session"))
    await settle(bridge)
    assert calls[-1][1] == []


async def test_desktop_authorization_and_revocation(harness):
    bridge, client, calls, _ = harness
    assert (await client.post("/api/v1/jobs", json=request(control=True))).status == 403
    bridge.config["allow_desktop_control"] = False
    assert (await client.post("/api/v1/control", json={"enabled": True})).status == 403
    bridge.config["allow_desktop_control"] = True
    assert (await client.post("/api/v1/control", json={"enabled": True, "hours": -1})).status == 400
    assert (await client.post("/api/v1/control", json={"enabled": True, "hours": 1})).status == 200
    running = request(prompt="wait", control=True)
    await client.post("/api/v1/jobs", json=running)
    await asyncio.sleep(0.03)
    queued = request(control=True)
    await client.post("/api/v1/jobs", json=queued)
    await client.post("/api/v1/control", json={"enabled": False})
    await settle(bridge)
    assert bridge.store.get(running["id"])["state"] == "cancelled"
    assert bridge.store.get(queued["id"])["state"] == "cancelled"
    assert len(calls) == 1
    with pytest.raises(PermissionError):
        bridge.check_control(bridge.store.get(running["id"]))


async def test_cancel_does_not_kill_worker(harness):
    bridge, client, _, _ = harness
    job = request(prompt="wait")
    await client.post("/api/v1/jobs", json=job)
    await asyncio.sleep(0.03)
    await client.post("/api/v1/jobs/" + job["id"] + "/cancel", json={})
    next_job = request()
    await client.post("/api/v1/jobs", json=next_job)
    await settle(bridge)
    assert bridge.store.get(next_job["id"])["state"] == "completed"


async def test_expiry_cancels_running_desktop_job(harness):
    bridge, client, _, _ = harness
    await client.post("/api/v1/control", json={"enabled": True, "hours": 0.00004})
    job = request(prompt="wait", control=True)
    await client.post("/api/v1/jobs", json=job)
    await asyncio.sleep(0.25)
    assert bridge.store.get(job["id"])["state"] == "cancelled"
    assert not bridge.is_control_enabled()


async def test_phone_requires_device_consent_and_current_job(harness):
    bridge, client, _, _ = harness
    job = request(phone=True, device="phone-test", prompt="wait")
    assert (await client.post("/api/v1/jobs", json=job)).status == 403
    bridge.phone.devices["phone-test"] = {"enabled": True, "seen": time.monotonic()}
    assert (await client.post("/api/v1/jobs", json=job)).status == 202
    await asyncio.sleep(0.03)
    saved = bridge.store.get(job["id"])
    command = asyncio.create_task(bridge.phone.execute(saved, "inspect", {}))
    await asyncio.sleep(0)
    response = await client.post("/api/v1/devices/phone-test/poll", json={"enabled": True})
    payload = await response.json()
    cmd = payload["commands"][0]
    assert "future" not in cmd
    wrong = await client.post(
        "/api/v1/devices/other-phone/commands/" + cmd["id"], json={"result": "no"}
    )
    assert wrong.status == 404
    ok = await client.post(
        "/api/v1/devices/phone-test/commands/" + cmd["id"], json={"result": "界面内容"}
    )
    assert ok.status == 200
    assert await command == "界面内容"
    assert cmd["id"] not in bridge.phone.commands
    await client.post("/api/v1/devices/phone-test/disable", json={})
    assert bridge.store.get(job["id"])["state"] == "cancelled"


async def test_emergency_stop_both_devices(harness):
    bridge, client, _, _ = harness
    await client.post("/api/v1/control", json={"enabled": True})
    bridge.phone.devices["phone-test"] = {"enabled": True, "seen": time.monotonic()}
    job = request(prompt="wait", control=True, phone=True, device="phone-test")
    await client.post("/api/v1/jobs", json=job)
    await asyncio.sleep(0.03)
    await client.post("/api/v1/stop", json={})
    assert not bridge.is_control_enabled()
    assert not bridge.phone.enabled("phone-test")
    assert bridge.store.get(job["id"])["state"] == "cancelled"


def test_restart_never_replays_operations(tmp_path):
    path = tmp_path / "tasks.db"
    store = Store(path)
    store.create("job-restart", "session-test", "delete something", True)
    store.close()
    reopened = Store(path)
    assert reopened.get("job-restart")["state"] == "interrupted"
    reopened.close()
