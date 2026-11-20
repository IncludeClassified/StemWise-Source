"""Trim only blank outer lyric lines, preserving approved interior text."""

from __future__ import annotations

def physical_lines(text: str) -> list[str]:
    return text.replace('\r\n', '\n').replace('\r', '\n').split('\n')


def trim_blank_outer_lines(text: str) -> str:
    lines = physical_lines(text)
    first, stop = 0, len(lines)
    while first < stop and not lines[first].strip():
        first += 1
    while stop > first and not lines[stop-1].strip():
        stop -= 1
    return '\n'.join(lines[first:stop])
