import json
import os
import struct
import sys
import tempfile
import threading
import unittest
import zlib
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import linjian_server  # noqa: E402


def png_chunk(kind, payload):
    checksum = zlib.crc32(kind + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", checksum)


def make_test_png():
    header = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
    pixels = b"\x00\xff\x00\x00\xff\x00\xff\x00\xff" * 2
    comment = b"Comment\x00" + (b"screen-contract-test" * 4)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"tEXt", comment)
        + png_chunk(b"IDAT", zlib.compress(pixels))
        + png_chunk(b"IEND", b"")
    )


class ScreenshotContractTest(unittest.TestCase):
    TOKEN = "screen-contract-test-token"

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.original_env = {
            key: os.environ.get(key)
            for key in ("LINJIAN_TOKEN", "LINJIAN_DATA_DIR", "LINJIAN_KEEP")
        }
        os.environ["LINJIAN_TOKEN"] = self.TOKEN
        os.environ["LINJIAN_DATA_DIR"] = self.temp_dir.name
        os.environ["LINJIAN_KEEP"] = "1"

        state = linjian_server.State()
        linjian_server.Handler.state = state
        self.httpd = linjian_server.ThreadingHTTPServer(
            ("127.0.0.1", 0), linjian_server.Handler
        )
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.httpd.server_address
        self.base_url = f"http://{host}:{port}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()
        for key, value in self.original_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def request(self, path, *, token=None, data=None):
        headers = {}
        if token is not None:
            headers["X-Auth-Token"] = token
        request = Request(self.base_url + path, data=data, headers=headers)
        return urlopen(request, timeout=2)

    def test_authenticated_upload_round_trips_as_latest_screen(self):
        screenshot = make_test_png()

        with self.request(
            "/api/screenshot", token=self.TOKEN, data=screenshot
        ) as response:
            uploaded = json.load(response)

        self.assertTrue(uploaded["ok"])
        self.assertEqual(uploaded["size"], len(screenshot))
        self.assertTrue(uploaded["filename"].endswith(".png"))

        with self.request("/api/latest.json", token=self.TOKEN) as response:
            latest = json.load(response)

        self.assertTrue(latest["ok"])
        self.assertEqual(latest["filename"], uploaded["filename"])
        self.assertEqual(latest["size"], len(screenshot))

        with self.request("/api/latest", token=self.TOKEN) as response:
            self.assertEqual(response.headers.get_content_type(), "image/png")
            self.assertEqual(response.read(), screenshot)

    def test_latest_screen_rejects_missing_token(self):
        with self.assertRaises(HTTPError) as caught:
            self.request("/api/latest")

        self.assertEqual(caught.exception.code, 403)
        payload = json.loads(caught.exception.read().decode("utf-8"))
        self.assertEqual(payload["error"], linjian_server.ERR_BAD_TOKEN)


if __name__ == "__main__":
    unittest.main()
