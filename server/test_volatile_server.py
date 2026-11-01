"""Local-only regression and process-restart protocol tests. No device/Render calls."""
import concurrent.futures
import contextlib
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
import urllib.error
import urllib.request
import zlib

import linjian_server as server


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
            + chunk(b"tEXt", b"synthetic-only\0" + b"x" * 80)
            + chunk(b"IDAT", zlib.compress(b"\0" + b"\xff\0\0\xff" * 2 + b"\0" + b"\0\xff\0\xff" * 2))
            + chunk(b"IEND", b""))


def request(origin, path, token="", body=None, raw=None, headers=None):
    send_headers = {"X-Auth-Token": token, **(headers or {})}
    if body is not None:
        raw = json.dumps(body).encode()
        send_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(origin + path, data=raw, headers=send_headers)
    try:
        with urllib.request.urlopen(req, timeout=4) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)


class StateTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.state = server.State(token=secrets.token_urlsafe(16), clock=lambda: self.now,
                                  screenshot_ttl=5, command_ttl=10)

    def tearDown(self):
        self.state.close()

    def command(self, action="send_notification"):
        return self.state.enqueue(server.make_command("test-phone", action, payload={"message": "synthetic"}))

    def test_metadata_does_not_consume_and_ttl_erases(self):
        data = bytearray(png())
        self.state.put_image(data)
        self.assertEqual(self.state.latest_info()["size"], len(data))
        self.assertEqual(self.state.latest_info()["size"], len(data))
        self.now += 5
        self.state.sweep()
        self.assertIsNone(self.state.latest_info())
        self.assertFalse(any(data))

    def test_replacement_close_and_stale_filename(self):
        first = bytearray(png())
        old = self.state.put_image(first)
        second = bytearray(png())
        self.state.put_image(second)
        self.assertFalse(any(first))
        self.assertIsNone(self.state.take_image(old["filename"]))
        self.assertIsNotNone(self.state.latest_info())
        self.state.close()
        self.assertFalse(any(second))

    def test_concurrent_take_has_exactly_one_owner(self):
        self.state.put_image(bytearray(png()))
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            owners = list(pool.map(lambda _: self.state.take_image(), range(8)))
        self.assertEqual(sum(o is not None for o in owners), 1)
        for owner in owners:
            self.state._erase(owner)
        self.assertIsNone(self.state.latest_info())

    def test_pending_and_unconfirmed_expire_and_do_not_requeue(self):
        pending = self.command()
        dispatched = self.command()
        self.assertEqual(self.state.poll("test-phone")["id"], pending["id"])
        # Leave the other command pending and the first outcome unknown.
        self.now += 10
        self.assertIsNone(self.state.poll("test-phone"))
        for command in (pending, dispatched):
            result = self.state.command_status(command["id"])["command"]
            self.assertEqual(result["status"], "failed")
            self.assertTrue(result["invalidated"])
        self.assertFalse(self.state.report({"command_id": pending["id"], "device_id": "test-phone", "ok": True}))

    def test_epoch_changes_old_pending_and_dispatched_are_invalid(self):
        old = self.command()
        self.state.poll("test-phone")
        other = server.State(token=self.state.token)
        try:
            self.assertNotEqual(other.epoch, self.state.epoch)
            self.assertIsNone(other.poll("test-phone"))
            self.assertTrue(other.command_status(old["id"])["command"]["invalidated"])
            self.assertFalse(other.report({"command_id": old["id"], "device_id": "test-phone", "ok": True}))
        finally:
            other.close()

    def test_report_is_once_and_result_is_short_lived(self):
        command = self.command()
        self.state.poll("test-phone")
        report = {"command_id": command["id"], "device_id": "test-phone", "ok": True, "result": "private-result"}
        self.assertTrue(self.state.report(report))
        self.assertFalse(self.state.report(report))
        self.assertEqual(self.state.command_status(command["id"])["command"]["result"], "private-result")
        self.assertEqual(self.state.command_status(command["id"])["command"]["result"], "")

    def test_result_ttl_metadata_capacity_and_retention(self):
        command = self.command()
        self.state.poll("test-phone")
        self.state.report({"command_id": command["id"], "device_id": "test-phone", "ok": True, "result": "private"})
        self.now += 60
        self.state.sweep()
        self.assertEqual(self.state.command_status(command["id"])["command"]["result"], "")
        for _ in range(255):
            self.command()
        with self.assertRaises(ValueError):
            self.command()
        self.now += 600
        self.state.sweep()
        self.assertFalse(self.state.command_history)
        self.assertFalse(self.state.commands)

    def test_routing_metadata_cannot_be_overridden_by_payload(self):
        command = server.make_command("test-phone", "send_notification",
            payload={"id": "old", "epoch": "old", "action": "unlock_app", "status": "completed",
                     "device_id": "another", "message": "hello"})
        queued = self.state.enqueue(command)
        self.assertTrue(queued["id"].startswith(self.state.epoch + "~"))
        self.assertEqual((queued["action"], queued["device_id"], queued["status"]),
                         ("send_notification", "test-phone", "pending"))

    def test_dispatched_history_contains_only_diagnostic_metadata(self):
        command = self.state.enqueue(server.make_command("test-phone", "send_notification",
            payload={"message": "DO-NOT-KEEP-PAYLOAD"}))
        self.state.poll("test-phone")
        self.assertNotIn("DO-NOT-KEEP-PAYLOAD", json.dumps(self.state.command_history))
        self.assertNotIn("payload", self.state.command_history[command["id"]])

    def test_latest_business_states_expire_without_disk(self):
        self.state.device_states["test-phone"] = {"screen_text": "temporary"}
        self.state.state_times["device", "test-phone"] = self.now
        self.now += 300
        self.state.sweep()
        self.assertFalse(self.state.device_states)

    def test_remote_relaxation_and_sequence_bypass_are_blocked(self):
        for action in ("unlock_app", "temporary_unlock_app", "remove_locked_app"):
            self.assertFalse(server.safe_restrictions({"action": action}))
        self.assertFalse(server.safe_restrictions({"action": "curbox_set_app_group", "enable": False}))
        self.assertFalse(server.safe_restrictions({"action": "curbox_set_app_group", "enable": "false"}))
        self.assertFalse(server.safe_restrictions({"action": "run_sequence", "steps": [
            {"action": "run_sequence", "steps": [{"action": "curbox_set_dnd", "enable": False}]}]}))
        self.assertTrue(server.safe_restrictions({"action": "curbox_set_app_group", "enable": True}))


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.token = secrets.token_urlsafe(16)
        self.state = server.State(token=self.token)
        handler = type("TestHandler", (server.Handler,), {"state": self.state})
        self.http = server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.origin = "http://127.0.0.1:" + str(self.http.server_port)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.state.close()

    def call(self, path, **kwargs):
        return request(self.origin, path, self.token, **kwargs)

    def queue(self):
        code, data, _ = self.call("/api/command", body={"device_id": "test-phone", "action": "send_notification"})
        self.assertEqual(code, 200)
        return json.loads(data)["command"]

    def test_current_APK_and_MCP_image_contract_consumes_once(self):
        image = png()
        code, data, _ = self.call("/api/screenshot", raw=image)
        self.assertEqual(code, 200)
        uploaded = json.loads(data)
        info = json.loads(self.call("/api/latest.json")[1])
        self.assertEqual((info["filename"], info["size"]), (uploaded["filename"], len(image)))
        code, data, headers = self.call("/api/latest")
        self.assertEqual((code, data), (200, image))
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(headers["X-Linjian-Epoch"], self.state.epoch)
        self.assertEqual(self.call("/api/latest")[0], 404)
        self.assertEqual(self.call("/api/latest.json")[0], 404)

    def test_authentication_failure_does_not_consume_image(self):
        self.call("/api/screenshot", raw=png())
        for token in ("", "wrong"):
            self.assertEqual(request(self.origin, "/api/latest", token)[0], 403)
        self.assertEqual(self.call("/api/latest")[0], 200)
        self.assertEqual(request(self.origin, "/api/diagnostics")[0], 403)

    def test_failed_network_send_still_consumes_and_wipes(self):
        retained = bytearray(png())
        self.state.put_image(retained)
        class FailedSink(server.Handler):
            state = self.state
            def _require_token(self):
                return True
            def _send_bytes(self, *_):
                raise BrokenPipeError()
        handler = object.__new__(FailedSink)
        handler.path = "/api/latest"
        with self.assertRaises(BrokenPipeError):
            handler.do_GET()
        self.assertIsNone(self.state.image)
        self.assertFalse(any(retained))

    def test_APK_poll_report_shapes_and_never_automatic_replay(self):
        command = self.queue()
        first = json.loads(self.call("/api/poll?device_id=test-phone")[1])
        self.assertEqual(first["command"]["id"], command["id"])
        self.assertIsNone(json.loads(self.call("/api/poll?device_id=test-phone")[1])["command"])
        report = {"device_id": "test-phone", "command_id": command["id"], "ok": True, "result": "synthetic"}
        self.assertEqual(self.call("/api/device/report", body=report)[0], 200)
        self.assertEqual(json.loads(self.call("/api/command/status?id=" + command["id"])[1])["command"]["status"], "completed")
        self.assertEqual(self.call("/api/device/report", body=report)[0], 409)
        self.assertEqual(self.call("/api/command", body={"id": command["id"], "action": "send_notification"})[0], 400)

    def test_wrong_device_and_premature_report_rejected(self):
        command = self.queue()
        report = {"device_id": "test-phone", "command_id": command["id"], "ok": True}
        self.assertEqual(self.call("/api/device/report", body=report)[0], 409)
        self.call("/api/poll?device_id=test-phone")
        report["device_id"] = "other"
        self.assertEqual(self.call("/api/device/report", body=report)[0], 409)

    def test_legacy_state_health_and_guidian_protocol(self):
        data = {"device_id": "test-phone", "service_runtime": {"companion_running": True},
                "guidian_state": {"active": False}, "screen_text": "business-only"}
        self.assertEqual(self.call("/api/device/state", body=data)[0], 200)
        for path in ("/api/device/state", "/api/life_state"):
            value = json.loads(self.call(path + "?device_id=test-phone")[1])
            self.assertEqual(value["state"], value["life_state"])
            self.assertTrue(value["state"]["service_runtime"]["companion_running"])
        self.assertEqual(self.call("/api/health/state", body={"device_id": "test-phone", "health": {"test": 1}})[0], 200)
        self.assertTrue(json.loads(self.call("/api/health/state?device_id=test-phone")[1])["has_health_data"])
        diagnostic = self.call("/api/diagnostics")[1]
        self.assertNotIn(b"business-only", diagnostic)
        self.assertNotIn(self.token.encode(), diagnostic)

    def test_stale_epoch_relaxation_injection_and_bad_json(self):
        self.assertEqual(self.call("/api/command", body={"action": "noop", "expected_epoch": "old"})[0], 409)
        self.assertEqual(self.call("/api/command", body={"action": "curbox_set_dnd", "payload": {"enable": False}})[0], 403)
        self.assertEqual(self.call("/api/command", body={"action": "run_sequence", "steps": [{"action": "unlock_app"}]})[0], 403)
        for raw in (b"{", b"[]"):
            self.assertEqual(self.call("/api/command", raw=raw)[0], 400)
        self.assertEqual(self.call("/api/command", body={"action": {}})[0], 400)
        self.assertEqual(self.call("/api/screenshot", raw=b"x" * 101)[0], 400)
        self.assertEqual(self.call("/api/command", body={"action": "noop", "epoch": "old"})[0], 409)
        self.assertEqual(self.call("/api/command", body={"action": "send_notification", "payload": {"id": "old"}})[0], 400)

    def test_oversized_body_is_rejected_before_read(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.http.server_port, timeout=3)
        connection.putrequest("POST", "/api/screenshot")
        connection.putheader("X-Auth-Token", self.token)
        connection.putheader("Content-Length", str(server.MAX_UPLOAD_BYTES + 1))
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 413)
        connection.close()

    def test_no_request_credential_or_body_in_logs(self):
        logs = io.StringIO()
        with contextlib.redirect_stderr(logs):
            request(self.origin, "/api/latest?token=" + self.token + "&private=DO-NOT-LOG")
            request(self.origin, "/api/latest?token=DO-NOT-LOG")
        self.assertNotIn("DO-NOT-LOG", logs.getvalue())
        self.assertNotIn(self.token, logs.getvalue())

    def test_no_files_or_legacy_hooks_are_used(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"LINJIAN_DATA_DIR": tmp, "LINJIAN_HOOK": "never-execute"}):
                self.call("/api/screenshot", raw=png())
                self.call("/api/latest")
            self.assertEqual(list(Path(tmp).iterdir()), [])


class ProcessAndMCPTests(unittest.TestCase):
    def test_actual_process_restart_and_real_MCP_helpers(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._exercise(Path(tmp))

    def _exercise(self, tmp):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        token = secrets.token_urlsafe(24)
        env = dict(os.environ, LINJIAN_TOKEN=token, LINJIAN_HOST="127.0.0.1",
                   PORT=str(port), LINJIAN_DATA_DIR=str(tmp))
        origin = "http://127.0.0.1:" + str(port)
        child = None
        def start():
            proc = subprocess.Popen([sys.executable, "-u", str(Path(server.__file__).resolve())],
                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            for _ in range(100):
                try:
                    status, data, _ = request(origin, "/health")
                    if status == 200:
                        return proc, json.loads(data)["epoch"]
                except OSError:
                    pass
                time.sleep(0.05)
            proc.terminate()
            proc.wait(timeout=5)
            raise AssertionError("Local server readiness failed")
        def post(action):
            return json.loads(request(origin, "/api/command", token,
                body={"device_id": "test-phone", "action": action})[1])["command"]
        try:
            child, first_epoch = start()
            unknown = post("send_notification")
            request(origin, "/api/poll?device_id=test-phone", token)
            pending = post("send_notification")
            request(origin, "/api/screenshot", token, raw=png())
            child.terminate()
            child.wait(timeout=5)
            child, second_epoch = start()
            self.assertNotEqual(first_epoch, second_epoch)
            self.assertIsNone(json.loads(request(origin, "/api/poll?device_id=test-phone", token)[1])["command"])
            self.assertEqual(request(origin, "/api/latest", token)[0], 404)
            for command in (unknown, pending):
                result = json.loads(request(origin, "/api/command/status?id=" + command["id"], token)[1])
                self.assertEqual(result["command"]["status"], "failed")
                self.assertTrue(result["command"]["invalidated"])
                self.assertEqual(request(origin, "/api/device/report", token, body={
                    "device_id": "test-phone", "command_id": command["id"], "ok": True})[0], 409)
            # Execute the existing JS MCP helper functions over real HTTP, without
            # importing Express/SDK or mocking their transport.
            node = shutil.which("node")
            self.assertIsNotNone(node, "Node is required for MCP protocol compatibility check")
            script = Path(__file__).with_name("test_mcp_volatile.mjs")
            env.update(P0_LOCAL_ORIGIN=origin, P0_LOCAL_TOKEN=token, P0_SYNTHETIC_PNG=png().hex())
            result = subprocess.run([node, str(script)], env=env, capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
            self.assertIn(b"PASS", result.stdout)
            self.assertEqual(list(tmp.iterdir()), [])
        finally:
            if child is not None and child.poll() is None:
                child.terminate()
                child.wait(timeout=5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
