English | [简体中文](2026-03-30-redis-cli-pipe-hang.zh-CN.md)

# Postmortem: redis-cli pipe waits for its final reply

## Summary

The RESP spike's own pipeline tests passed. The real `redis-cli --pipe` waited for its final reply because its input contained an empty line before a binary ECHO marker. The parser entered inline parsing at the empty line and mishandled its already-read carriage return. It consumed the next RESP marker as inline text, returned errors, and never echoed the binary marker.

## Impact

The spike could not complete this client interoperability check. This was an experimental server, not a production ticketing service.

## Timeline

- The initial RESP server accepted arrays and split commands.
- A binary safety correction retained raw bulk bytes for keys, values, and ECHO replies.
- A real `redis-cli --pipe` run exposed the remaining hang.
- `scripts/capture-pipe-bytes.sh` recorded the empty CRLF before the final ECHO.
- The parser was changed to skip empty lines and select RESP or inline parsing for each command; a regression test added the exact sequence.

## Root cause and trigger

The client inserted an empty line between its SET pipeline and its final ECHO. The old line reader skipped its first-byte check for the carriage return, so it consumed the following RESP marker as inline text and returned errors. The internal test generated the ECHO without the empty line, so it missed the trigger.

## Resolution

Handle the first byte of each line correctly, skip empty inline lines, then read a fresh command marker. Keep bulk payloads as bytes. A socket regression test checks the final binary ECHO, and the real client completes its pipe exchange.

## What went well

The real client and byte capture identified the mismatch. Existing tests already covered split writes and multiple commands on a connection.

## What went wrong

The tests modeled the protocol the team expected, not the extra line the real client sent.

## Where we got lucky

The issue was found in a spike before a ticketing dependency was built on it.

## Action items

- Keep the empty-line and binary payload regression tests.
- Use the real client in interoperability checks when adopting a protocol implementation.
- Use a maintained Redis service for any later ticketing cache; this spike is not a production server.
