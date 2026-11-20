#!/usr/bin/env python3
"""Deterministic Stemwise enrichment-v1 waveform encoder.

This module implements the enrichment contract in ``STEMWISE_DOCUMENTATION.md``
and the executable schemas. The
normative input is interleaved IEEE-754 binary32 little-endian PCM, either in a
RIFF/WAVE container or as a headerless file whose sample rate and channel count
are supplied by the caller.  No normalization or floating-point arithmetic is
used after reading the input bits.

The public result is the exact ``analysis.waveform`` object expected by the
phone contract.  Errors are raised rather than silently dropping samples; the
orchestrator is responsible for turning an error into an honest ``unavailable``
feature and a failed tool-run record.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
import math
import os
from pathlib import Path
import struct
from typing import Iterable

import numpy as np
from stemwise_engine.contract import (
    WAVEFORM_ENCODING as ENCODING,
    WAVEFORM_LEVELS_MS as _WAVEFORM_LEVELS_MS,
)


FRAME_DURATIONS_MS = tuple(sorted(_WAVEFORM_LEVELS_MS))

_Q31_MAX = 2_147_483_647
_Q31_MIN = -2_147_483_648
_S16_MAX = 32_767
_IEEE_FLOAT_SUBFORMAT = bytes.fromhex("0300000000001000800000aa00389b71")


class WaveformError(ValueError):
    """The canonical PCM cannot be encoded without violating the contract."""


@dataclass(frozen=True)
class _PcmSource:
    path: Path
    data_offset: int
    data_size: int
    sample_rate_hz: int
    channels: int

    @property
    def total_frames(self) -> int:
        return self.data_size // (self.channels * 4)


def _float32_bits_to_q31(bits: np.ndarray, *, frame_offset: int = 0) -> np.ndarray:
    """Convert binary32 bit patterns to Q1.31 using exact integer arithmetic.

    ``bits`` may have any shape and must contain the host-independent uint32
    representation of little-endian binary32 samples.  The returned array is
    signed int64 because NumPy has no signed 32-bit representation for the
    positive Q1.31 endpoint during intermediate operations.
    """

    values = np.asarray(bits, dtype=np.uint32)
    sign = values >> np.uint32(31)
    exponent = (values >> np.uint32(23)) & np.uint32(0xFF)
    fraction = values & np.uint32(0x7FFFFF)

    non_finite = exponent == 0xFF
    if np.any(non_finite):
        flat_index = int(np.flatnonzero(non_finite.reshape(-1))[0])
        if values.ndim == 2:
            frame = frame_offset + flat_index // values.shape[1]
            channel = flat_index % values.shape[1]
            where = f"frame {frame}, channel {channel}"
        else:
            where = f"sample {frame_offset + flat_index}"
        raise WaveformError(f"canonical PCM contains NaN or infinity at {where}")

    result = np.zeros(values.shape, dtype=np.int64)

    # Every finite value with a biased exponent >= 127 has magnitude >= 1.0.
    saturated = exponent >= 127
    result[saturated & (sign == 0)] = _Q31_MAX
    result[saturated & (sign != 0)] = _Q31_MIN

    # For a normal binary32 number below one:
    #
    #   abs(x) = (2**23 + fraction) / 2**(150 - exponent)
    #
    # Multiplying that rational by Q31_MAX and adding half the denominator is
    # exact in uint64.  Exponents <= 94 are strictly below the first half-step
    # and therefore map to zero. Select that useful range once; its variable
    # shifts are bounded to 24..55 and the rounded numerator fits in uint64.
    selected = (exponent >= 95) & (exponent < 127)
    mantissa = fraction[selected].astype(np.uint64) + np.uint64(1 << 23)
    numerator = mantissa * np.uint64(_Q31_MAX)
    shift = np.uint64(150) - exponent[selected].astype(np.uint64)
    rounded = (
        (numerator + (np.uint64(1) << (shift - np.uint64(1)))) >> shift
    ).astype(np.int64)
    result[selected] = np.where(sign[selected] != 0, -rounded, rounded)

    # Zero and subnormal binary32 values, plus normals through exponent 94,
    # correctly remain zero after rational rounding.
    return result


def _square_sums_prefix(samples: np.ndarray) -> np.ndarray:
    """Share exact squared-sample reductions across all waveform frames/levels.

    The caller bounds each block to one million Q1.31 samples. Splitting at
    bit 16 keeps all three prefix sums below 2**63; frame sums are recombined
    with unbounded Python integers instead of overflowing int64 Q2.62 sums.
    """
    magnitude = np.abs(samples)
    high = magnitude >> 16
    low = magnitude & 0xFFFF
    prefix = np.empty((3, samples.size + 1), dtype=np.int64)
    prefix[:, 0] = 0
    np.cumsum(high * high, out=prefix[0, 1:])
    np.cumsum(2 * high * low, out=prefix[1, 1:])
    np.cumsum(low * low, out=prefix[2, 1:])
    return prefix


def _q31_to_s16(value: int) -> int:
    if value == _Q31_MIN:
        return -32_768
    mapped = (abs(value) * _S16_MAX + _Q31_MAX // 2) // _Q31_MAX
    return -mapped if value < 0 else mapped


def _rms_q31_to_u16(value: int) -> int:
    mapped = (value * _S16_MAX + 1_073_741_823) // _Q31_MAX
    return min(_S16_MAX, max(0, mapped))


class _LevelAccumulator:
    def __init__(
        self,
        total_frames: int,
        sample_rate_hz: int,
        frame_duration_ms: int,
        timeline_duration_ms: int | None,
    ):
        self.total_frames = total_frames
        self.sample_rate_hz = sample_rate_hz
        self.duration_ms = frame_duration_ms
        self.timeline_duration_ms = timeline_duration_ms
        if timeline_duration_ms is None:
            denominator = sample_rate_hz * frame_duration_ms
            self.frame_count = (total_frames * 1000 + denominator - 1) // denominator
        else:
            self.frame_count = (
                timeline_duration_ms + frame_duration_ms - 1
            ) // frame_duration_ms
        self.sample_cursor = 0
        self.frame_index = 0
        self.minimum = _Q31_MAX
        self.maximum = _Q31_MIN
        self.square_sum = 0
        self.samples_in_frame = 0
        self.payload = bytearray()

    def _frame_end(self) -> int:
        if (
            self.timeline_duration_ms is not None
            and self.frame_index + 1 == self.frame_count
        ):
            # The canonical millisecond timeline is rounded from PCM duration.
            # Keep its exact declared frame count while folding the sub-ms tail
            # into the final real frame instead of emitting an Android-invalid
            # extra frame or silently discarding samples.
            return self.total_frames
        return min(
            self.total_frames,
            ((self.frame_index + 1) * self.sample_rate_hz * self.duration_ms) // 1000,
        )

    def feed(self, samples: np.ndarray, prefix: np.ndarray) -> None:
        samples = np.asarray(samples, dtype=np.int64)
        channels = samples.shape[1]
        offset = 0
        while offset < len(samples):
            if self.frame_index >= self.frame_count:
                raise WaveformError("waveform framing consumed more samples than declared")
            frame_end = self._frame_end()
            if frame_end <= self.sample_cursor:
                raise WaveformError(
                    "sample rate is too low for the required waveform frame duration"
                )
            take = min(len(samples) - offset, frame_end - self.sample_cursor)
            part = samples[offset : offset + take]
            self.minimum = min(self.minimum, int(part.min()))
            self.maximum = max(self.maximum, int(part.max()))
            high, cross, low = prefix[:, (offset + take)*channels] - prefix[:, offset*channels]
            self.square_sum += (int(high) << 32) + (int(cross) << 16) + int(low)
            self.samples_in_frame += take*channels
            self.sample_cursor += take
            offset += take
            if self.sample_cursor == frame_end:
                self._finish_frame()

    def _finish_frame(self) -> None:
        if self.samples_in_frame <= 0:
            raise WaveformError("the waveform contract cannot encode an empty frame")
        mean_square_q62 = (
            self.square_sum + self.samples_in_frame // 2
        ) // self.samples_in_frame
        rms_q31 = math.isqrt(mean_square_q62)
        self.payload.extend(
            struct.pack(
                "<hhH",
                _q31_to_s16(self.minimum),
                _q31_to_s16(self.maximum),
                _rms_q31_to_u16(rms_q31),
            )
        )
        self.frame_index += 1
        self.minimum = _Q31_MAX
        self.maximum = _Q31_MIN
        self.square_sum = 0
        self.samples_in_frame = 0

    def finish(self) -> dict[str, object]:
        if self.sample_cursor != self.total_frames:
            raise WaveformError(
                f"waveform received {self.sample_cursor} of {self.total_frames} PCM frames"
            )
        if self.samples_in_frame:
            # With the Section 9.9 frame-count formula the final feed ends at the
            # clamped final boundary and normally finalizes it.  Keep this guard
            # explicit so a future framing change cannot discard a partial frame.
            self._finish_frame()
        if self.frame_index != self.frame_count:
            raise WaveformError(
                f"encoded {self.frame_index} frames; expected {self.frame_count}"
            )
        if len(self.payload) != self.frame_count * 6:
            raise AssertionError("internal waveform payload-size invariant failed")
        return {
            "frameDurationMs": self.duration_ms,
            "frameCount": self.frame_count,
            "data": base64.b64encode(self.payload).decode("ascii"),
        }


def _encode_bit_chunks(
    chunks: Iterable[tuple[int, np.ndarray]],
    *,
    total_frames: int,
    sample_rate_hz: int,
    channels: int,
    duration_ms: int | None = None,
) -> dict[str, object]:
    if sample_rate_hz <= 0:
        raise WaveformError("sample rate must be positive")
    if channels <= 0:
        raise WaveformError("channel count must be positive")
    if total_frames <= 0:
        raise WaveformError("canonical PCM contains no sample frames")
    if duration_ms is not None:
        if type(duration_ms) is not int or duration_ms <= 0:
            raise WaveformError("canonical timeline duration must be a positive integer")
        if duration_ms > 7_200_000:
            raise WaveformError("canonical timeline duration exceeds two hours")
        # The analysis duration and waveform are derived from this same decoded
        # PCM. Permit the expected integer-ms rounding difference, but reject a
        # foreign or stale duration rather than stretching waveform data.
        delta_sample_ms = abs(total_frames * 1000 - duration_ms * sample_rate_hz)
        if 2 * delta_sample_ms > sample_rate_hz:
            raise WaveformError(
                "canonical timeline differs from PCM duration by more than 0.5 ms"
            )

    levels = [
        _LevelAccumulator(total_frames, sample_rate_hz, frame_duration, duration_ms)
        for frame_duration in FRAME_DURATIONS_MS
    ]
    expected_start = 0
    for frame_offset, bits in chunks:
        if frame_offset != expected_start:
            raise WaveformError("PCM chunks are missing, overlapping, or out of order")
        bits = np.asarray(bits, dtype=np.uint32)
        if bits.ndim != 2 or bits.shape[1] != channels:
            raise WaveformError("PCM chunk channel shape does not match its declaration")
        q31 = _float32_bits_to_q31(bits, frame_offset=frame_offset)
        block_frames = max(1, 1_000_000 // channels)
        for start in range(0, len(q31), block_frames):
            block = q31[start : start + block_frames]
            prefix = _square_sums_prefix(block.reshape(-1))
            for level in levels:
                level.feed(block, prefix)
        expected_start += bits.shape[0]

    if expected_start != total_frames:
        raise WaveformError(f"read {expected_start} of {total_frames} PCM frames")
    return {
        "status": "complete",
        "encoding": ENCODING,
        "levels": [level.finish() for level in levels],
    }


def waveform_from_samples(
    samples: np.ndarray,
    sample_rate_hz: int,
    *,
    duration_ms: int | None = None,
    chunk_frames: int = 262_144,
) -> dict[str, object]:
    """Encode a mono ``(frames,)`` or frame-major ``(frames, channels)`` array.

    Values are first converted to binary32.  For normative production use,
    prefer :func:`build_waveform`, which reads the canonical little-endian bits
    directly from disk and therefore cannot accidentally begin with float64.
    """

    if chunk_frames <= 0:
        raise ValueError("chunk_frames must be positive")
    array = np.asarray(samples, dtype="<f4")
    if array.ndim == 1:
        array = array.reshape(-1, 1)
    if array.ndim != 2 or array.shape[1] < 1:
        raise WaveformError("samples must have shape (frames,) or (frames, channels)")
    array = np.ascontiguousarray(array, dtype="<f4")
    bits = array.view("<u4")

    def chunks() -> Iterable[tuple[int, np.ndarray]]:
        for start in range(0, array.shape[0], chunk_frames):
            yield start, bits[start : start + chunk_frames]

    return _encode_bit_chunks(
        chunks(),
        total_frames=array.shape[0],
        sample_rate_hz=sample_rate_hz,
        channels=array.shape[1],
        duration_ms=duration_ms,
    )


def _parse_wave(path: Path) -> _PcmSource:
    file_size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(12)
        if len(header) != 12 or header[:4] != b"RIFF" or header[8:] != b"WAVE":
            raise WaveformError("not a little-endian RIFF/WAVE file")
        if struct.unpack_from('<I', header, 4)[0] + 8 != file_size:
            raise WaveformError('WAVE RIFF length must equal the exact file length')

        fmt: bytes | None = None
        data_offset: int | None = None
        data_size: int | None = None
        while stream.tell() + 8 <= file_size:
            chunk_header = stream.read(8)
            if len(chunk_header) != 8:
                break
            chunk_id, chunk_size = struct.unpack("<4sI", chunk_header)
            chunk_offset = stream.tell()
            chunk_end = chunk_offset + chunk_size
            if chunk_end > file_size:
                raise WaveformError(f"WAVE chunk {chunk_id!r} exceeds the file length")
            if chunk_id == b"fmt ":
                if fmt is not None:
                    raise WaveformError("WAVE contains more than one fmt chunk")
                if chunk_size not in (16, 18, 40):
                    raise WaveformError('WAVE fmt chunk must use a supported bounded structure')
                fmt = stream.read(chunk_size)
            elif chunk_id == b"data":
                if data_offset is not None:
                    raise WaveformError("canonical WAVE must contain exactly one data chunk")
                data_offset = chunk_offset
                data_size = chunk_size
                stream.seek(chunk_end)
            else:
                stream.seek(chunk_end)
            if chunk_size & 1:
                if stream.tell() >= file_size:
                    raise WaveformError("WAVE chunk is missing its pad byte")
                stream.seek(1, os.SEEK_CUR)
        if stream.tell() != file_size:
            raise WaveformError('WAVE has unexplained trailing bytes')

    if fmt is None or data_offset is None or data_size is None:
        raise WaveformError("WAVE requires one fmt chunk and one data chunk")
    if len(fmt) < 16:
        raise WaveformError("WAVE fmt chunk is shorter than 16 bytes")
    format_tag, channels, rate, byte_rate, block_align, bits_per_sample = struct.unpack(
        "<HHIIHH", fmt[:16]
    )
    if format_tag == 0xFFFE:
        if len(fmt) != 40:
            raise WaveformError("WAVE_FORMAT_EXTENSIBLE fmt chunk is shorter than 40 bytes")
        extension_size = struct.unpack_from("<H", fmt, 16)[0]
        valid_bits = struct.unpack_from("<H", fmt, 18)[0]
        if extension_size != 22 or valid_bits != 32 or fmt[24:40] != _IEEE_FLOAT_SUBFORMAT:
            raise WaveformError("WAVE_FORMAT_EXTENSIBLE input is not 32-bit IEEE float")
    elif format_tag != 3:
        raise WaveformError("canonical WAVE must use IEEE_FLOAT format tag 3")
    elif len(fmt) not in (16, 18) or len(fmt) == 18 and struct.unpack_from('<H', fmt, 16)[0] != 0:
        raise WaveformError('IEEE_FLOAT fmt extension must be empty')

    if channels <= 0 or rate <= 0:
        raise WaveformError("WAVE has an invalid sample rate or channel count")
    expected_align = channels * 4
    if bits_per_sample != 32 or block_align != expected_align:
        raise WaveformError("canonical WAVE must use interleaved 32-bit float samples")
    if byte_rate != rate * expected_align:
        raise WaveformError("WAVE byte rate does not match sample rate and channel count")
    if data_size == 0 or data_size % expected_align:
        raise WaveformError("WAVE data size is empty or not a whole interleaved frame")
    return _PcmSource(path, data_offset, data_size, rate, channels)


def _describe_source(
    path: Path, sample_rate_hz: int | None, channels: int | None
) -> _PcmSource:
    with path.open("rb") as stream:
        magic = stream.read(12)
    if len(magic) >= 12 and magic[:4] == b"RIFF" and magic[8:12] == b"WAVE":
        source = _parse_wave(path)
        if sample_rate_hz is not None and sample_rate_hz != source.sample_rate_hz:
            raise WaveformError("supplied sample rate disagrees with the WAVE header")
        if channels is not None and channels != source.channels:
            raise WaveformError("supplied channel count disagrees with the WAVE header")
        return source

    if sample_rate_hz is None or channels is None:
        raise WaveformError("headerless f32le PCM requires sample_rate_hz and channels")
    if sample_rate_hz <= 0 or channels <= 0:
        raise WaveformError("sample rate and channel count must be positive")
    data_size = path.stat().st_size
    frame_size = channels * 4
    if data_size == 0 or data_size % frame_size:
        raise WaveformError("raw f32le size is empty or not a whole interleaved frame")
    return _PcmSource(path, 0, data_size, sample_rate_hz, channels)


def build_waveform(
    path: str | os.PathLike[str],
    *,
    sample_rate_hz: int | None = None,
    channels: int | None = None,
    duration_ms: int | None = None,
    chunk_frames: int = 262_144,
) -> dict[str, object]:
    """Encode canonical IEEE-float WAV or headerless f32le PCM.

    Headerless input requires ``sample_rate_hz`` and ``channels``.  WAVE input
    derives them from its header and rejects conflicting caller declarations.
    App-bound callers must supply the canonical integer ``duration_ms`` so every
    level has exactly ``ceil(duration_ms / frameDurationMs)`` frames. Omitting it
    retains sample-derived framing only for low-level golden-vector tests.
    The source is memory-mapped and processed in bounded chunks, so memory use
    does not grow with song duration apart from the required output payload.
    """

    if chunk_frames <= 0:
        raise ValueError("chunk_frames must be positive")
    source_path = Path(path)
    if not source_path.is_file():
        raise FileNotFoundError(source_path)
    source = _describe_source(source_path, sample_rate_hz, channels)
    mapped = np.memmap(
        source.path,
        mode="r",
        dtype="<u4",
        offset=source.data_offset,
        shape=(source.total_frames, source.channels),
        order="C",
    )

    def chunks() -> Iterable[tuple[int, np.ndarray]]:
        for start in range(0, source.total_frames, chunk_frames):
            yield start, mapped[start : start + chunk_frames]

    try:
        return _encode_bit_chunks(
            chunks(),
            total_frames=source.total_frames,
            sample_rate_hz=source.sample_rate_hz,
            channels=source.channels,
            duration_ms=duration_ms,
        )
    finally:
        # Release the mapping promptly on Windows so temporary canonical PCM can
        # be deleted immediately after a successful package build.
        mmap_object = getattr(mapped, "_mmap", None)
        if mmap_object is not None:
            mmap_object.close()


__all__ = [
    "ENCODING",
    "FRAME_DURATIONS_MS",
    "WaveformError",
    "build_waveform",
    "waveform_from_samples",
]
