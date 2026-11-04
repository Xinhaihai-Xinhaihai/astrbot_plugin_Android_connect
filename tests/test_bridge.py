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


async def wait_state(bridge, job_id, state):
    async def wait():
        while bridge.store.get(job_id)["state"] != state:
            await asyncio.sleep(0.005)
    await asyncio.wait_for(wait(), 3)


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
    response = await client.post("/api/v1/devices/phone-test/poll", json={"enabled": True, "grant": "grant-test"})
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


async def test_pairing_is_closed_by_default_and_single_use(harness):
    bridge, client, _, _ = harness
    headers = {"Authorization": ""}
    assert (await client.post("/api/v1/pair", json={"code": "123456"}, headers=headers)).status == 403
    code = bridge.begin_pairing()
    response = await client.post("/api/v1/pair", json={"code": code}, headers=headers)
    assert response.status == 200
    assert (await response.json())["token"] == bridge.token
    assert response.headers["Cache-Control"] == "no-store"
    assert (await client.post("/api/v1/pair", json={"code": code}, headers=headers)).status == 403


async def test_pairing_rate_limit_and_expiry(harness):
    bridge, client, _, _ = harness
    code = bridge.begin_pairing()
    wrong = "000000" if code != "000000" else "111111"
    for _ in range(5):
        assert (await client.post("/api/v1/pair", json={"code": wrong})).status == 403
    assert (await client.post("/api/v1/pair", json={"code": code})).status == 429
    code = bridge.begin_pairing()
    bridge.pair_until = time.monotonic() - 1
    assert (await client.post("/api/v1/pair", json={"code": code})).status == 403


async def test_emergency_stop_cannot_be_undone_by_old_phone_heartbeat(harness):
    bridge, client, _, _ = harness
    bridge.phone.devices["phone-test"] = {
        "enabled": True, "seen": time.monotonic(), "grant": "grant-test"
    }
    await client.post("/api/v1/stop", json={})
    response = await client.post(
        "/api/v1/devices/phone-test/poll", json={"enabled": True, "grant": "grant-test"}
    )
    assert (await response.json())["stopped"] is True
    assert not bridge.phone.enabled("phone-test")


@pytest.mark.parametrize("count", [1, 5, 10])
async def test_exact_repeat_counts_and_retry_does_not_add_rounds(harness, count):
    bridge, client, calls, _ = harness
    bridge.repeat_delay = lambda: 0.005
    job = request(repeat_count=count)
    responses = await asyncio.gather(*[
        client.post("/api/v1/jobs", json=job) for _ in range(12)
    ])
    assert sum(response.status == 202 for response in responses) == 1
    assert all(response.status in (200, 202) for response in responses)
    await wait_state(bridge, job["id"], "completed")
    await client.post("/api/v1/jobs", json=job)
    bridge.queue.put_nowait(job["id"])
    await settle(bridge)
    assert len(calls) == count
    assert [call[0]["runs_started"] for call in calls] == list(range(1, count + 1))
    saved = bridge.store.get(job["id"])
    assert saved["runs_completed"] == count
    assert saved["runs_started"] == count
    detail = await (await client.get("/api/v1/jobs/" + job["id"])).json()
    assert len(detail["runs"]) == count
    assert all(item["state"] == "completed" for item in detail["runs"])
    conflict = {**job, "repeat_count": 10 if count != 10 else 1}
    assert (await client.post("/api/v1/jobs", json=conflict)).status == 409
    assert not bridge.repeat_tasks


@pytest.mark.parametrize("count", [-1, 2, 100, True, False, "5", 5.0, None])
async def test_invalid_repeat_mode_is_rejected(harness, count):
    bridge, client, calls, _ = harness
    assert (await client.post("/api/v1/jobs", json=request(repeat_count=count))).status == 400
    assert not bridge.store.active()
    assert not calls


async def test_forever_yields_to_other_jobs_and_can_be_stopped(harness):
    bridge, client, calls, _ = harness
    bridge.repeat_delay = lambda: 0.03
    job = request(repeat_count=0)
    await client.post("/api/v1/jobs", json=job)
    await wait_state(bridge, job["id"], "waiting")
    other = request(prompt="另一个任务", session="another-session")
    await client.post("/api/v1/jobs", json=other)
    await wait_state(bridge, other["id"], "completed")
    async def two_rounds():
        while bridge.store.get(job["id"])["runs_completed"] < 2:
            await asyncio.sleep(0.005)
    await asyncio.wait_for(two_rounds(), 2)
    await client.post("/api/v1/jobs/" + job["id"] + "/cancel", json={})
    count = len(calls)
    await asyncio.sleep(0.08)
    assert len(calls) == count
    assert bridge.store.get(job["id"])["state"] == "cancelled"
    assert bridge.store.get(job["id"])["result"] == "处理完成"
    assert not bridge.repeat_tasks


@pytest.mark.parametrize("stop", ["desktop", "phone", "all"])
async def test_revoke_also_cancels_waiting_rounds(harness, stop):
    bridge, client, calls, _ = harness
    bridge.repeat_delay = lambda: 60
    await client.post("/api/v1/control", json={"enabled": True})
    bridge.phone.devices["phone-test"] = {"enabled": True, "seen": time.monotonic()}
    job = request(repeat_count=5, control=True, phone=True, device="phone-test")
    await client.post("/api/v1/jobs", json=job)
    await wait_state(bridge, job["id"], "waiting")
    scheduled = bridge.repeat_tasks[job["id"]]
    if stop == "desktop":
        await client.post("/api/v1/control", json={"enabled": False})
    elif stop == "phone":
        await client.post("/api/v1/devices/phone-test/disable", json={})
    else:
        await client.post("/api/v1/stop", json={})
    assert bridge.store.get(job["id"])["state"] == "cancelled"
    assert bridge.store.get(job["id"])["runs_completed"] == 1
    assert len(calls) == 1
    assert scheduled.done()
    assert not bridge.repeat_tasks


@pytest.mark.parametrize("failure", [RuntimeError("failed"), TimeoutError(), PermissionError("blocked")])
async def test_failed_round_stops_remaining_rounds(harness, failure):
    bridge, client, calls, _ = harness
    bridge.repeat_delay = lambda: 0.005
    async def backend(job, history, service):
        calls.append((job, history))
        if job["runs_started"] == 2:
            raise failure
        return "第一轮结果"
    bridge.backend = backend
    job = request(repeat_count=10)
    await client.post("/api/v1/jobs", json=job)
    await wait_state(bridge, job["id"], "failed")
    assert len(calls) == 2
    assert bridge.store.get(job["id"])["runs_completed"] == 1
    assert bridge.store.get(job["id"])["result"] == "第一轮结果"
    assert [item["state"] for item in bridge.store.runs(job["id"])] == ["failed", "completed"]
    assert not bridge.repeat_tasks


def test_waiting_rounds_do_not_restart_and_results_are_preserved(tmp_path):
    path = tmp_path / "repeat.db"
    store = Store(path)
    store.create("repeat-job", "session-test", "work", False, repeat_count=0)
    store.update("repeat-job", "running")
    store.begin_run("repeat-job")
    store.finish_run("repeat-job", 1, "completed", result="一次结果")
    store.update("repeat-job", "waiting", next_run_at=time.time() + 5)
    store.close()
    reopened = Store(path)
    job = reopened.get("repeat-job")
    assert job["state"] == "interrupted"
    assert job["runs_completed"] == 1
    assert job["result"] == "一次结果"
    assert reopened.runs("repeat-job")[0]["state"] == "completed"
    assert not reopened.active()
    reopened.close()


def test_existing_database_migrates_without_losing_jobs(tmp_path):
    import sqlite3
    path = tmp_path / "legacy.db"
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE jobs (
            id TEXT PRIMARY KEY,session TEXT,prompt TEXT,control INTEGER,phone INTEGER,
            device TEXT,state TEXT,result TEXT,error TEXT,created REAL,updated REAL);
        INSERT INTO jobs VALUES ('legacy-job','old-session','旧消息',0,0,'','completed','旧回复','',1,1);
    """)
    db.close()
    store = Store(path)
    job = store.get("legacy-job")
    assert (job["repeat_count"], job["runs_completed"], job["runs_started"]) == (1, 1, 1)
    assert job["result"] == "旧回复"
    store.close()
