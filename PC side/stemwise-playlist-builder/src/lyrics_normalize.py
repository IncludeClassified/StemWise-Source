"""Trim only blank outer lyric lines, preserving approved interior text."""

from __future__ import annotations

def physical_lines(text: str) -> list[str]:
    return text.replace('\r\n', '\n').replace('\r', '\n').split('\n')


def performance_lines(text: str) -> list[str]:
    """Retain physical indices and leave display-word-free decoration untimed."""
    from stemwise_engine.lyrics_tokenization import display_word_ranges
    return [line if display_word_ranges(line) else '' for line in physical_lines(text)]


def performed_line_indices(text: str) -> tuple[int, ...]:
    return tuple(index for index, line in enumerate(performance_lines(text)) if line)


def trim_blank_outer_lines(text: str) -> str:
    lines = physical_lines(text)
    first, stop = 0, len(lines)
    while first < stop and not lines[first].strip():
        first += 1
    while stop > first and not lines[stop-1].strip():
        stop -= 1
    return '\n'.join(lines[first:stop])
