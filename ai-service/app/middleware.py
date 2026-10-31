import json
from collections.abc import Awaitable, Callable
from typing import Any


ASGIMessage = dict[str, Any]
Receive = Callable[[], Awaitable[ASGIMessage]]
Send = Callable[[ASGIMessage], Awaitable[None]]
ASGIApp = Callable[[dict[str, Any], Receive, Send], Awaitable[None]]


class AnalysisBodySizeLimitMiddleware:
    def __init__(self, app: ASGIApp, max_body_bytes: int) -> None:
        self._app = app
        self._max_body_bytes = max_body_bytes

    async def __call__(
        self,
        scope: dict[str, Any],
        receive: Receive,
        send: Send,
    ) -> None:
        if (
            scope["type"] != "http"
            or scope.get("method") != "POST"
            or scope.get("path") != "/api/v1/analyze"
        ):
            await self._app(scope, receive, send)
            return

        body = bytearray()
        disconnected = False
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                disconnected = True
                break

            chunk = message.get("body", b"")
            if len(chunk) > self._max_body_bytes - len(body):
                await self._send_too_large(send)
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break

        buffered_body = bytes(body)
        replayed = False

        async def replay_receive() -> ASGIMessage:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {
                    "type": "http.request",
                    "body": buffered_body,
                    "more_body": disconnected,
                }
            if disconnected:
                return {"type": "http.disconnect"}
            return await receive()

        await self._app(scope, replay_receive, send)

    async def _send_too_large(self, send: Send) -> None:
        response_body = json.dumps(
            {"detail": "Analysis request is too large"},
            separators=(",", ":"),
        ).encode("utf-8")
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(response_body)).encode("ascii")),
                ],
            }
        )
        await send(
            {
                "type": "http.response.body",
                "body": response_body,
                "more_body": False,
            }
        )
