"""Smoke test for server.main(): binds the requested host and streams frames.

Uses a replay route so the output is deterministic and no physics runs.
Run with: python3 -m unittest test_server  (from the simulator/ directory)
"""
import asyncio
import json
import os
import socket
import tempfile
import unittest

import websockets

import server


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ServerSmokeTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        fd, self.route = tempfile.mkstemp(suffix=".jsonl")
        with os.fdopen(fd, "w") as f:
            for i in range(3):
                f.write(json.dumps({"frame": i}) + "\n")
        self.port = _free_port()

    async def asyncTearDown(self):
        os.remove(self.route)

    async def _serve(self, host: str):
        task = asyncio.create_task(
            server.main(scenario=2, host=host, port=self.port, websocket_fps=30.0, route_path=self.route)
        )
        self.addAsyncCleanup(self._cancel, task)
        await asyncio.sleep(0.1)
        return task

    @staticmethod
    async def _cancel(task):
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass

    async def test_loopback_host_streams_replay(self):
        await self._serve("127.0.0.1")
        async with websockets.connect(f"ws://127.0.0.1:{self.port}") as ws:
            first = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
            self.assertEqual(first["frame"], 0)

    async def test_host_flag_rejects_other_interfaces(self):
        # Bound to loopback only: a non-loopback local address must be refused.
        ip = None
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            try:
                s.connect(("10.255.255.255", 1))
                ip = s.getsockname()[0]
            except OSError:
                pass
        if not ip or ip.startswith("127."):
            self.skipTest("no non-loopback interface available")
        await self._serve("127.0.0.1")
        with self.assertRaises(OSError):
            with socket.create_connection((ip, self.port), timeout=2):
                pass


if __name__ == "__main__":
    unittest.main()
