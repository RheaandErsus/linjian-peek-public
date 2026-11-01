#!/usr/bin/env python3
"""掌心窗 v0.4.1 volatile unified server.

零依赖标准库版，负责：
1. 给手机端下发 peek / open_app / back / home / recents / tap / swipe / set_alarm / send_notification 命令；
2. 接收手机端上传的截图；
3. 保存手机端最近状态；
4. 提供 /api/latest 与 /api/latest.json 给 MCP 读取。
"""
from __future__ import annotations

import hmac
import json
import os
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Event, Lock, RLock, Thread
from urllib.parse import parse_qs, urlparse

DEFAULT_PORT = 8513
MAX_UPLOAD_BYTES = 24 * 1024 * 1024
MAX_JSON_BYTES = 256 * 1024
VERSION = "0.4.1-volatile-guardian-bridge"
DEFAULT_DEVICE = os.environ.get("LINJIAN_DEFAULT_DEVICE", "android-phone")

ERR_BAD_TOKEN = "LINJIAN_ERR_BAD_TOKEN"
ERR_NO_IMAGE = "LINJIAN_ERR_NO_IMAGE"
ERR_TOO_LARGE = "LINJIAN_ERR_TOO_LARGE"
ERR_NOT_FOUND = "LINJIAN_ERR_NOT_FOUND"
ERR_BAD_METHOD = "LINJIAN_ERR_BAD_METHOD"

KNOWN_APPS = {
    "小红书": "com.xingin.xhs", "xhs": "com.xingin.xhs", "xiaohongshu": "com.xingin.xhs",
    "微信": "com.tencent.mm", "wechat": "com.tencent.mm",
    "QQ": "com.tencent.mobileqq", "qq": "com.tencent.mobileqq",
    "抖音": "com.ss.android.ugc.aweme", "douyin": "com.ss.android.ugc.aweme",
    "ChatGPT": "com.openai.chatgpt", "chatgpt": "com.openai.chatgpt",
    "Gemini": "com.google.android.apps.bard", "gemini": "com.google.android.apps.bard", "bard": "com.google.android.apps.bard",
    "Claude": "com.anthropic.claude", "claude": "com.anthropic.claude",
    "微博": "com.sina.weibo", "weibo": "com.sina.weibo",
    "X": "com.twitter.android", "x": "com.twitter.android", "Twitter": "com.twitter.android", "twitter": "com.twitter.android",
    "Speedcat": "", "speedcat": "",
}
SENSITIVE_PACKAGES = {"com.eg.android.AlipayGphone", "com.tencent.mm.plugin.wallet"}
ALLOWED_ACTIONS = {"noop", "peek", "open_app", "home", "back", "recents", "tap", "swipe", "set_alarm", "send_notification", "run_sequence", "save_known_app", "get_screen_nodes", "tap_text", "input_text", "lock_app", "unlock_app", "temporary_unlock_app", "extend_lock", "deny_unlock_request", "get_lock_state", "set_emergency_passphrase", "add_locked_app", "remove_locked_app", "list_lockable_apps", "get_guidian_state", "set_guidian_config", "trigger_guidian", "mark_guidian_returned", "curbox_status", "curbox_query", "curbox_list", "curbox_start_focus", "curbox_set_app_group", "curbox_set_keyword_group", "curbox_set_grayscale_group", "curbox_set_keyword_blocker", "curbox_set_reel_blocker", "curbox_set_reel_counter", "curbox_set_dnd"}


def load_dotenv(path: Path) -> None:
    if not path.exists(): return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line: continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))



def load_update_info() -> dict:
    here = Path(__file__).resolve().parent
    candidates = [here.parent / "update.json", here / "update.json"]
    for path in candidates:
        if path.exists():
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                pass
    return {
        "latest_version_name": "0.3.4.6",
        "latest_version_code": 30406,
        "apk_url": "",
        "sha256": "",
        "required": False,
        "changelog": [
            "修复部分 vivo / OriginOS 机型首次打开只显示左上角的问题",
            "优化全屏铺满与启动重测量",
            "新增归电感官卡片、归电设置与 MCP 远程配置"
        ]
    }

def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class State:
    def __init__(self, *, token: str | None = None, clock=time.monotonic,
                 screenshot_ttl: float = 120, command_ttl: float = 120) -> None:
        here = Path(__file__).resolve().parent
        if token is None:
            load_dotenv(here / ".env")
        self.token = os.environ.get("LINJIAN_TOKEN", "").strip() if token is None else token
        self.port = int(os.environ.get("PORT", os.environ.get("LINJIAN_PORT", DEFAULT_PORT)))
        self.host = os.environ.get("LINJIAN_HOST", "0.0.0.0")
        self.epoch = uuid.uuid4().hex
        self.started_at = now_iso()
        self.clock = clock
        if screenshot_ttl <= 0 or command_ttl <= 0:
            raise ValueError("Positive expiry required")
        self.screenshot_ttl = screenshot_ttl
        self.command_ttl = command_ttl
        self.closed = False
        self.commands_lock = RLock()
        self.upload_lock = Lock()
        self.image = None
        self.commands: list[dict] = []
        self.command_history: dict[str, dict] = {}
        self.command_times = {}
        self.result_times = {}
        self.device_states: dict[str, dict] = {}
        self.health_states: dict[str, dict] = {}
        self.state_times = {}
        self.unlock_requests: list[dict] = []
        self.counters = {"uploads": 0, "image_reads": 0, "image_expirations": 0,
                         "polls": 0, "states": 0, "reports": 0}

    @staticmethod
    def _erase(image) -> None:
        if image is not None:
            image["data"][:] = b"\0" * len(image["data"])

    def _clear_image(self) -> None:
        self._erase(self.image)
        self.image = None

    def sweep(self) -> None:
        with self.commands_lock:
            now = self.clock()
            if self.image is not None and now - self.image["created"] >= self.screenshot_ttl:
                self._clear_image()
                self.counters["image_expirations"] += 1
            for cid, created in list(self.command_times.items()):
                command = self.command_history[cid]
                if now - created >= self.command_ttl and command["status"] in ("pending", "dispatched"):
                    command.update(status="failed", invalidated=True, completed_at=now_iso(),
                                   result="expired_or_unconfirmed_do_not_replay")
                if now - created >= 600:
                    self.command_history.pop(cid, None)
                    self.command_times.pop(cid, None)
                    self.result_times.pop(cid, None)
            self.commands[:] = [c for c in self.commands
                               if self.command_history.get(c["id"], {}).get("status") == "pending"]
            for cid, received in list(self.result_times.items()):
                if now - received >= 60:
                    command = self.command_history.get(cid)
                    if command:
                        command.pop("report", None)
                        command["result"] = ""
                    self.result_times.pop(cid, None)
            for key, received in list(self.state_times.items()):
                if now - received >= 300:
                    kind, device = key
                    (self.device_states if kind == "device" else self.health_states).pop(device, None)
                    self.state_times.pop(key, None)

    def put_image(self, data: bytearray) -> dict:
        with self.commands_lock:
            if self.closed:
                self._erase({"data": data})
                raise ValueError("Session closed")
            self._clear_image()
            suffix = ".png" if data[:8] == b"\x89PNG\r\n\x1a\n" else ".jpg"
            info = {"filename": f"peek_{self.epoch}_{uuid.uuid4().hex}{suffix}",
                    "size": len(data), "mtime": time.time(), "url": "/api/latest",
                    "epoch": self.epoch}
            self.image = {"data": data, "info": info, "created": self.clock(),
                          "content_type": "image/png" if suffix == ".png" else "image/jpeg"}
            self.counters["uploads"] += 1
            return dict(info)

    def latest_info(self) -> dict | None:
        self.sweep()
        with self.commands_lock:
            return dict(self.image["info"]) if self.image else None

    def take_image(self, filename: str = ""):
        self.sweep()
        with self.commands_lock:
            if self.image is None:
                return None
            if filename and filename != self.image["info"]["filename"]:
                return None
            image, self.image = self.image, None
            self.counters["image_reads"] += 1
            return image  # Consumed before network send, including a failed/partial send.

    @staticmethod
    def _metadata(command: dict) -> dict:
        return {k: command[k] for k in ("id", "epoch", "device_id", "action", "status",
            "created_at", "dispatched_at", "completed_at", "invalidated") if k in command}

    def enqueue(self, command: dict) -> dict:
        self.sweep()
        with self.commands_lock:
            if self.closed or len(self.command_history) >= 256:
                raise ValueError("Queue unavailable or full")
            command.update(id=self.epoch + "~" + uuid.uuid4().hex, epoch=self.epoch,
                           status="pending", created_at=now_iso(),
                           dispatched_at=None, completed_at=None, result="")
            self.commands.append(command)
            self.command_history[command["id"]] = self._metadata(command)
            self.command_times[command["id"]] = self.clock()
            return dict(command)

    def poll(self, device: str):
        self.sweep()
        with self.commands_lock:
            self.counters["polls"] += 1
            idx = next((i for i, c in enumerate(self.commands) if c["device_id"] == device), None)
            if idx is None or self.closed:
                return None
            command = self.commands.pop(idx)
            command.update(status="dispatched", dispatched_at=now_iso())
            self.command_history[command["id"]] = self._metadata(command)
            return command

    def command_status(self, cid: str) -> dict:
        self.sweep()
        with self.commands_lock:
            command = self.command_history.get(cid)
            if command is None:
                return {"ok": False, "epoch": self.epoch, "command": {
                    "id": cid, "status": "failed", "invalidated": True, "epoch": self.epoch,
                    "result": "previous_or_unknown_session_do_not_replay"}}
            result = dict(command)
            if command["status"] in ("completed", "failed"):
                command.pop("report", None)
                command["result"] = ""
                self.result_times.pop(cid, None)
            return {"ok": True, "epoch": self.epoch, "command": result}

    def report(self, data: dict) -> bool:
        self.sweep()
        cid = data.get("command_id") or data.get("id") or ""
        with self.commands_lock:
            command = self.command_history.get(cid)
            if (self.closed or command is None or command["status"] != "dispatched"
                    or command["device_id"] != data.get("device_id", DEFAULT_DEVICE)):
                return False
            command.update(status="completed" if data.get("ok") else "failed",
                           completed_at=now_iso(), result=data.get("result", ""))
            self.result_times[cid] = self.clock()
            self.counters["reports"] += 1
            return True

    def diagnostics(self) -> dict:
        self.sweep()
        with self.commands_lock:
            return {"epoch": self.epoch, "started_at": self.started_at,
                    "counters": dict(self.counters), "pending_commands": len(self.commands),
                    "command_records": len(self.command_history),
                    "image_bytes": len(self.image["data"]) if self.image else 0,
                    "remote_relaxation_allowed": False}

    def close(self) -> None:
        with self.commands_lock:
            self.closed = True
            self._clear_image()
            self.commands.clear()
            self.command_history.clear()
            self.command_times.clear()
            self.result_times.clear()
            self.device_states.clear()
            self.health_states.clear()
            self.state_times.clear()
            self.unlock_requests.clear()


def package_for(app_name: str, package: str = "") -> str:
    pkg = (package or "").strip()
    if pkg: return pkg
    key = (app_name or "").strip()
    return KNOWN_APPS.get(key, KNOWN_APPS.get(key.lower(), ""))


def make_command(device_id: str, action: str, app: str = "", package: str = "", payload: dict | None = None) -> dict:
    action = (action or "noop").strip().lower()
    if action not in ALLOWED_ACTIONS: action = "noop"
    package = package_for(app, package)
    payload = dict(payload or {})
    if package in SENSITIVE_PACKAGES:
        action = "noop"
        payload["blocked_reason"] = "sensitive_package"
    cmd = {
        "id": str(uuid.uuid4()),
        "device_id": device_id or DEFAULT_DEVICE,
        "action": action,
        "app": app or "",
        "package": package or "",
        "payload": payload,
        "status": "pending",
        "created_at": now_iso(),
        "dispatched_at": None,
        "completed_at": None,
        "result": "",
    }
    reserved = {"id", "epoch", "device_id", "action", "app", "package", "status",
                "created_at", "dispatched_at", "completed_at", "result", "report"}
    cmd.update({k: v for k, v in payload.items() if k not in reserved})
    return cmd


def safe_restrictions(command: dict, depth: int = 0) -> bool:
    """Fail closed for relaxation and sequence-based bypasses; phone gate remains."""
    action = command.get("action", "noop")
    if not isinstance(action, str):
        return False
    if action in {"unlock_app", "temporary_unlock_app", "remove_locked_app"}:
        return False
    if action.startswith("curbox_") and "enable" in command:
        if not isinstance(command["enable"], bool) or not command["enable"]:
            return False
    if action == "run_sequence":
        steps = command.get("steps")
        return (depth < 4 and isinstance(steps, list) and 0 < len(steps) <= 50
                and all(isinstance(s, dict) and safe_restrictions(s, depth + 1) for s in steps))
    return True


class Handler(BaseHTTPRequestHandler):
    state: State

    def log_message(self, fmt: str, *args) -> None:
        pass  # Do not log URLs, query credentials, request bodies, or results.

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(30)

    def log_request(self, code="-", size="-") -> None:
        if str(code).isdigit() and int(code) >= 400:
            sys.stderr.write(f"[linjian-unified] HTTP {int(code)}\n")

    def _send_bytes(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Linjian-Epoch", self.state.epoch)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send_bytes(code, json.dumps({"epoch": self.state.epoch, **payload},
                         ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def _token_ok(self) -> bool:
        qs = parse_qs(urlparse(self.path).query)
        supplied = self.headers.get("X-Auth-Token", "") or qs.get("token", [""])[0]
        return (not self.state.closed and bool(self.state.token)
                and hmac.compare_digest(supplied.encode("utf-8"), self.state.token.encode("utf-8")))

    def _require_token(self) -> bool:
        if self._token_ok(): return True
        self._json(403, {"ok": False, "error": ERR_BAD_TOKEN}); return False

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0) or 0)
        if self.headers.get("Transfer-Encoding") or length < 0 or length > MAX_JSON_BYTES:
            raise ValueError("Invalid JSON body size")
        if length == 0: return {}
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise ValueError("Truncated JSON body")
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("Expected JSON object")
        return data

    def do_GET(self) -> None:
        self.state.sweep()
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        if path in ("/", "/health"):
            self._json(200, {"ok": not self.state.closed, "service": "linjian-unified", "name": "掌心窗", "version": VERSION, "tools": sorted(ALLOWED_ACTIONS), "guidian": True})
            return
        if path in ("/api/update.json", "/update.json"):
            payload = load_update_info()
            payload.setdefault("ok", True)
            self._json(200, payload)
            return
        if path == "/api/poll":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            cmd = self.state.poll(device_id)
            self._json(200, {"ok": True, "command": cmd})
            return
        if path == "/api/latest.json":
            if not self._require_token(): return
            info = self.state.latest_info()
            if not info: self._json(404, {"ok": False, "error": ERR_NOT_FOUND}); return
            self._json(200, {"ok": True, **info}); return
        if path == "/api/latest":
            if not self._require_token(): return
            image = self.state.take_image(qs.get("filename", [""])[0])
            if not image: self._json(404, {"ok": False, "error": ERR_NOT_FOUND}); return
            try:
                self._send_bytes(200, image["data"], image["content_type"])
            finally:
                self.state._erase(image)
            return
        if path == "/api/diagnostics":
            if not self._require_token(): return
            self._json(200, {"ok": True, **self.state.diagnostics()}); return
        if path in ("/api/device/state", "/api/life_state"):
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            state = self.state.device_states.get(device_id)
            self._json(200, {"ok": True, "device_id": device_id, "state": state, "life_state": state}); return
        if path == "/api/health/state":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            state = self.state.health_states.get(device_id)
            self._json(200, {"ok": True, "device_id": device_id, "has_health_data": bool(state), "health_state": state}); return
        if path == "/api/guidian_state":
            if not self._require_token(): return
            device_id = qs.get("device_id", [DEFAULT_DEVICE])[0] or DEFAULT_DEVICE
            st = self.state.device_states.get(device_id) or {}
            guidian = st.get("guidian_state") or {}
            self._json(200, {"ok": True, "device_id": device_id, "guidian_state": guidian}); return
        if path == "/api/command/status":
            if not self._require_token(): return
            cid = qs.get("id", [""])[0]
            self._json(200, self.state.command_status(cid)); return
        if path == "/api/appgate/unlock_requests":
            if not self._require_token(): return
            self._json(200, {"ok": True, "requests": self.state.unlock_requests[-50:]}); return
        if path == "/api/known_apps":
            self._json(200, {"ok": True, "apps": KNOWN_APPS}); return
        self._json(404, {"ok": False, "error": ERR_BAD_METHOD})

    def do_POST(self) -> None:
        try:
            self._do_POST()
        except (ValueError, TypeError, KeyError, UnicodeError):
            self._json(400, {"ok": False, "error": "invalid_payload"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass

    def _do_POST(self) -> None:
        self.state.sweep()
        path = urlparse(self.path).path
        if path == "/api/peek":
            if not self._require_token(): return
            command = self._queue(make_command(DEFAULT_DEVICE, "peek"))
            if command is not None: self._json(200, {"ok": True, "queued": True, "command": command})
            return
        if path == "/api/command":
            if not self._require_token(): return
            data = self._read_json()
            if not all(isinstance(data.get(k, ""), str) for k in ("device_id", "action", "app", "package")):
                raise ValueError("Invalid command routing")
            if any(k in data for k in ("id", "command_id", "status", "report", "result")):
                self._json(400, {"ok": False, "error": "new_explicit_command_required"}); return
            if (data.get("expected_epoch", self.state.epoch) != self.state.epoch
                    or data.get("epoch", self.state.epoch) != self.state.epoch):
                self._json(409, {"ok": False, "error": "stale_epoch_new_explicit_command_required"}); return
            if not isinstance(data.get("payload", {}), dict):
                raise ValueError("Invalid command payload")
            if any(k in data.get("payload", {}) for k in ("id", "command_id", "epoch", "status", "report", "result")):
                self._json(400, {"ok": False, "error": "new_explicit_command_required"}); return
            cmd = make_command(data.get("device_id") or DEFAULT_DEVICE, data.get("action") or "noop", data.get("app") or "", data.get("package") or "", data.get("payload") or data)
            if not safe_restrictions(cmd):
                self._json(403, {"ok": False, "error": "remote_relaxation_disabled"}); return
            queued = self._queue(cmd)
            if queued is not None: self._json(200, {"ok": True, "command": queued})
            return
        if path == "/api/device/state":
            if not self._require_token(): return
            data = self._read_json(); device_id = data.get("device_id") or DEFAULT_DEVICE
            if not isinstance(device_id, str) or not 0 < len(device_id) <= 128:
                raise ValueError("Invalid device")
            with self.state.commands_lock:
                if device_id not in self.state.device_states and len(self.state.device_states) >= 16:
                    self._json(429, {"ok": False, "error": "device_limit"}); return
                data["updated_at"] = now_iso()
                self.state.device_states[device_id] = data
                self.state.state_times["device", device_id] = self.state.clock()
                self.state.counters["states"] += 1
            self._json(200, {"ok": True, "device_id": device_id}); return
        if path == "/api/health/state":
            if not self._require_token(): return
            data = self._read_json()
            device_id = data.get("device_id") or DEFAULT_DEVICE
            health = data.get("health")
            if not isinstance(health, dict):
                self._json(400, {"ok": False, "error": "invalid_health_payload"}); return
            if not isinstance(device_id, str) or not 0 < len(device_id) <= 128:
                raise ValueError("Invalid device")
            record = {"device_id": device_id, "received_at": now_iso(), "health": health}
            with self.state.commands_lock:
                if device_id not in self.state.health_states and len(self.state.health_states) >= 16:
                    self._json(429, {"ok": False, "error": "device_limit"}); return
                self.state.health_states[device_id] = record
                self.state.state_times["health", device_id] = self.state.clock()
            self._json(200, {"ok": True, "device_id": device_id, "received_at": record["received_at"]}); return
        if path == "/api/device/report":
            if not self._require_token(): return
            data = self._read_json()
            if not self.state.report(data):
                self._json(409, {"ok": False, "error": "invalidated_or_duplicate_report_do_not_replay"}); return
            self._json(200, {"ok": True}); return
        if path == "/api/appgate/unlock_request":
            if not self._require_token(): return
            data = self._read_json()
            # Requests are diagnostic only; do not retain free-form reasons or passphrases.
            data = {"device_id": data.get("device_id", DEFAULT_DEVICE), "created_at": now_iso()}
            self.state.unlock_requests.append(data)
            self.state.unlock_requests = self.state.unlock_requests[-50:]
            self._json(200, {"ok": True, "request": data, "count": len(self.state.unlock_requests)}); return
        if path == "/api/screenshot":
            if not self._require_token(): return
            self._handle_screenshot(); return
        self._json(404, {"ok": False, "error": ERR_BAD_METHOD})

    def _queue(self, cmd: dict):
        with self.state.commands_lock:
            if cmd.get("action") == "peek" and sum(1 for c in self.state.commands if c.get("action") == "peek") >= 3:
                self._json(429, {"ok": False, "error": "queue_full"}); return None
            try:
                return self.state.enqueue(cmd)
            except ValueError:
                self._json(429, {"ok": False, "error": "queue_full"}); return None

    def _handle_screenshot(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("Chunking not supported")
        if length <= 0: self._json(400, {"ok": False, "error": ERR_NO_IMAGE}); return
        if length > MAX_UPLOAD_BYTES: self._json(413, {"ok": False, "error": ERR_TOO_LARGE}); return
        if not self.state.upload_lock.acquire(blocking=False):
            self._json(429, {"ok": False, "error": "upload_busy"}); return
        data = bytearray(length)
        transferred = False
        try:
            view = memoryview(data)
            position = 0
            while position < length:
                count = self.rfile.readinto(view[position:])
                if not count: raise ValueError("Truncated image")
                position += count
            if length < 100 or not (data[:8] == b"\x89PNG\r\n\x1a\n" or data[:2] == b"\xff\xd8"):
                self._json(400, {"ok": False, "error": ERR_NO_IMAGE}); return
            info = self.state.put_image(data)
            transferred = True
            self._json(200, {"ok": True, **info})
        finally:
            if not transferred: self.state._erase({"data": data})
            self.state.upload_lock.release()


def main() -> None:
    state = State()
    if not state.token or state.token == "please-change-me-to-a-long-random-token":
        sys.stderr.write("拒绝启动：请先设置 LINJIAN_TOKEN 为长随机密钥。\n")
        sys.exit(1)
    Handler.state = state
    httpd = ThreadingHTTPServer((state.host, state.port), Handler)
    print("=" * 56)
    print(f"  掌心窗 unified v{VERSION}")
    print(f"  listening: http://{state.host}:{state.port}")
    print("  screenshots: memory only; read once; unread TTL 120 seconds")
    print(f"  epoch: {state.epoch}")
    print("=" * 56)
    stop = Event()
    def expire():
        while not stop.wait(1):
            state.sweep()
    cleaner = Thread(target=expire, daemon=True)
    cleaner.start()
    try: httpd.serve_forever()
    except KeyboardInterrupt: print("\nbye.")
    finally:
        stop.set()
        state.close()
        httpd.server_close()

if __name__ == "__main__": main()
