#!/usr/bin/env python3
"""Temporary workaround for macOS Local Network permission.

When macOS blocks MacDroid.app from answering on the local network (common with
ad-hoc signed builds), run this relay from Terminal. Commands run from Terminal
use Terminal's Local Network permission. The relay accepts the phone's connection
on port 7320 and forwards it to MacDroid over 127.0.0.1, which the permission
doesn't cover.

    python3 tools/lan_relay.py            # listen on 7320, forward to 127.0.0.1:7319

On the phone, enter this Mac's IP and port 7320. Press Ctrl-C to stop.
"""
import asyncio
import sys

LISTEN_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 7320
TARGET = ("127.0.0.1", int(sys.argv[2]) if len(sys.argv) > 2 else 7319)


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(client_reader, client_writer) -> None:
    peer = client_writer.get_extra_info("peername")
    try:
        mac_reader, mac_writer = await asyncio.open_connection(*TARGET)
    except OSError as e:
        print(f"Phone {peer[0]} connected, but MacDroid isn't reachable on {TARGET}: {e}")
        client_writer.close()
        return
    print(f"Relaying {peer[0]} <-> MacDroid")
    await asyncio.gather(pipe(client_reader, mac_writer), pipe(mac_reader, client_writer))
    print(f"Closed {peer[0]}")


async def main() -> None:
    server = await asyncio.start_server(handle, host=None, port=LISTEN_PORT)
    print(f"MacDroid relay listening on port {LISTEN_PORT}, forwarding to {TARGET[0]}:{TARGET[1]}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
