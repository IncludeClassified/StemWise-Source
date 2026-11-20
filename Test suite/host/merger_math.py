"""Exact, test-owned merger oracle; no production audio/math helpers are imported."""
from __future__ import annotations

import argparse
from contextlib import ExitStack, redirect_stdout
from fractions import Fraction
from functools import lru_cache
import hashlib
import io
import json
import math
import os
from pathlib import Path
import random
import struct
import sys
import tempfile
import unittest
from unittest import mock
import wave

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "Test suite/android/fixtures/merger-oracle.json"
ROLES = ["lead-vocals", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth"]
SEEDS = [0x535745, 0x480009, 0x512]
U32, U64, H32 = Fraction(1, 2**24), Fraction(1, 2**53), Fraction(1, 2**150)


def binary_value(bits: int, width: int = 32) -> Fraction:
    """Interpret a finite IEEE bit pattern as an exact dyadic (zero sign is separate)."""
    fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    if width not in (32, 64) or not 0 <= bits < 2**width:
        raise ValueError("Invalid binary representation")
    fraction_mask = 2**fraction_bits - 1
    exponent = bits >> fraction_bits & (2**exponent_bits - 1)
    if exponent == 2**exponent_bits - 1:
        raise ValueError("Nonfinite operand")
    mantissa = bits & fraction_mask
    bias = 2**(exponent_bits - 1) - 1
    power = 1 - bias - fraction_bits
    if exponent:
        mantissa += 2**fraction_bits
        power = exponent - bias - fraction_bits
    value = Fraction(mantissa * 2**max(power, 0), 2**max(-power, 0))
    return -value if bits >> (width - 1) else value


def round_binary(value: Fraction, width: int = 32, *, negative_zero: bool = False) -> int:
    """Integer division implements round-to-nearest, ties-to-even, including underflow."""
    if width not in (32, 64):
        raise ValueError("Unsupported binary width")
    fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
    sign = value < 0 or value == 0 and negative_zero
    sign_bit = int(sign) << (width - 1)
    if not value:
        return sign_bit
    numerator, denominator = abs(value).as_integer_ratio()
    exponent = numerator.bit_length() - denominator.bit_length()
    if exponent >= 0:
        exponent -= numerator < denominator << exponent
    else:
        exponent -= numerator << -exponent < denominator
    bias = 2**(exponent_bits - 1) - 1
    minimum = 1 - bias
    quantum = max(exponent, minimum) - fraction_bits
    if quantum < 0:
        numerator <<= -quantum
    else:
        denominator <<= quantum
    rounded, remainder = divmod(numerator, denominator)
    if remainder * 2 > denominator or remainder * 2 == denominator and rounded & 1:
        rounded += 1
    if exponent < minimum:
        return sign_bit | rounded
    if rounded == 2**(fraction_bits + 1):
        rounded >>= 1
        exponent += 1
    if exponent > bias:
        raise ValueError("Arithmetic overflow")
    return sign_bit | (exponent + bias) << fraction_bits | (rounded - 2**fraction_bits)


def add_bits(left: int, right: int, width: int = 32) -> int:
    value = binary_value(left, width) + binary_value(right, width)
    both_negative_zero = left == right == 1 << (width - 1)
    return round_binary(value, width, negative_zero=both_negative_zero)


def multiply_bits(left: int, right: int) -> int:
    return round_binary(binary_value(left) * binary_value(right),
                        negative_zero=bool((left ^ right) >> 31))


def residual_bits(original: int, targets: list[int]) -> int:
    current = round_binary(binary_value(original), 64, negative_zero=original == 0x80000000)
    for target in targets:
        operand = round_binary(Fraction(target, 32768), 64)
        current = add_bits(current, operand ^ (1 << 63), 64)
    return round_binary(binary_value(current, 64), negative_zero=current == 1 << 63)


def dot_bits(lanes: list[int], gains: list[int], *, fused: bool = False) -> int:
    if len(lanes) != len(gains):
        raise ValueError("Lane/gain extent differs")
    current = 0
    for lane, gain in zip(lanes, gains):
        if fused:
            product = binary_value(lane) * binary_value(gain)
            product_negative = bool((lane ^ gain) >> 31)
            current = round_binary(binary_value(current) + product,
                                   negative_zero=current == 0x80000000 and not product and product_negative)
        else:
            current = add_bits(current, multiply_bits(lane, gain))
    return current


def residual_bound(original: Fraction, targets: list[Fraction]) -> Fraction:
    gamma = len(targets) * U64 / (1 - len(targets) * U64)
    ideal = original - sum(targets)
    return (1 + U32) * gamma * (abs(original) + sum(map(abs, targets))) + U32 * abs(ideal) + H32


def mix_bound(lanes: list[int], gains: list[int]) -> Fraction:
    operations = 2 * len(lanes)
    gamma = operations * U32 / (1 - operations * U32)
    absolute_products = sum(abs(binary_value(x) * binary_value(g)) for x, g in zip(lanes, gains))
    return gamma * absolute_products + operations * H32 / (1 - operations * U32)


def outward(value: Fraction, *, upper: bool) -> float:
    approximate = float(value)
    exact = Fraction.from_float(approximate)
    if upper and exact < value or not upper and exact > value:
        approximate = math.nextafter(approximate, math.inf if upper else -math.inf)
    return approximate


def compare_pcm(expected: list[int], actual: list[int], *, channels: int = 2) -> dict:
    """Strict bit comparator: never align, normalize, trim, pad or reorder."""
    if channels <= 0 or len(expected) % channels or len(actual) % channels:
        raise ValueError("Partial PCM frame")
    mismatch = [index for index, pair in enumerate(zip(expected, actual)) if pair[0] != pair[1]]
    extent = abs(len(expected) - len(actual))
    first = mismatch[0] if mismatch else min(len(expected), len(actual)) if extent else None
    errors = [abs(binary_value(a) - binary_value(b)) for a, b in zip(expected, actual)]
    peak = max(errors, default=Fraction(0))
    squared = sum(error * error for error in errors)
    return dict(equal=not mismatch and not extent, mismatchCount=len(mismatch) + extent,
                expectedFrames=len(expected) // channels, actualFrames=len(actual) // channels,
                firstFrame=None if first is None else first // channels,
                firstChannel=None if first is None else first % channels,
                peakError=outward(peak, upper=True),
                rmsError=math.sqrt(float(squared / max(len(errors), 1))))


def _enclose(low: Fraction, high: Fraction) -> tuple[Fraction, Fraction]:
    """Keep interval endpoints small; both grid roundings are exact and outward."""
    scale = 10**12
    a, b = low * scale, high * scale
    return Fraction(a.numerator // a.denominator, scale), Fraction(-(-b.numerator // b.denominator), scale)


def _interval_product(a, b):
    values = [x * y for x in a for y in b]
    return _enclose(min(values), max(values))


def _interval_quotient(a, b):
    if b[0] <= 0 <= b[1]:
        raise ArithmeticError("Kernel normalization interval contains zero")
    values = [x / y for x in a for y in b]
    return _enclose(min(values), max(values))


@lru_cache(maxsize=None)
def _pi_interval():
    # Machin's identity with alternating-series remainder; no platform sin/pi is trusted.
    def atan(reciprocal):
        x = Fraction(1, reciprocal)
        partial = sum((-1)**k * x**(2*k+1) / (2*k+1) for k in range(20))
        return partial, partial + x**41 / 41
    a, b = atan(5), atan(239)
    return _enclose(16*a[0] - 4*b[1], 16*a[1] - 4*b[0])


@lru_cache(maxsize=None)
def _sin_pi_interval(turns):
    nearest = (turns + Fraction(1, 2)).numerator // (turns + Fraction(1, 2)).denominator
    reduced = turns - nearest
    if reduced == 0:
        return Fraction(0), Fraction(0)
    low_pi, high_pi = _pi_interval()
    x = reduced * (low_pi + high_pi) / 2
    partial = sum((-1)**k * x**(2*k+1) / math.factorial(2*k+1) for k in range(16))
    error = abs(x)**33 / math.factorial(33) + abs(reduced) * (high_pi - low_pi) / 2
    low, high = partial - error, partial + error
    return _enclose(low, high) if nearest % 2 == 0 else _enclose(-high, -low)


@lru_cache(maxsize=None)
def _i0_interval(q):
    # I0(beta*sqrt(v)) = sum((beta^2*v/4)^k/(k!)^2), so no square-root oracle is needed.
    term = total = Fraction(1)
    for k in range(1, 24):
        term *= q / (k*k)
        total += term
    next_term = term * q / (24*24)
    tail = next_term / (1 - q / (25*25))
    return _enclose(total, total + tail)


@lru_cache(maxsize=None)
def conversion_fixture():
    """Finite DC contract: Kaiser-sinc definition in FFmpeg n7.1.1/libswresample/resample.c."""
    rows = []
    generation_allowance = U32
    for rate in (44_100, 96_000):
        factor = Fraction(1) if rate == 44_100 else Fraction.from_float(0.485)
        taps = -(-32 * factor.denominator // factor.numerator)
        taps += taps % 2
        phases = 48_000 // math.gcd(48_000, rate)
        center = (taps - 1) // 2
        kernels = []
        for phase in range(phases):
            coefficients = []
            for index in range(taps):
                distance = index - center - Fraction(phase, phases)
                turns = distance * factor
                if turns == 0:
                    sinc = (Fraction(1), Fraction(1))
                else:
                    denominator = _interval_product(_pi_interval(), (turns, turns))
                    sinc = _interval_quotient(_sin_pi_interval(turns), denominator)
                q = Fraction(81, 4) * max(1 - (2 * distance / taps)**2, 0)
                coefficients.append(_interval_product(sinc, _i0_interval(q)))
            kernels.append(coefficients)
        norm = (sum(value[0] for value in kernels[0]), sum(value[1] for value in kernels[0]))
        dc_error, l1 = Fraction(0), Fraction(0)
        for kernel in kernels:
            coefficients = [_interval_quotient(value, norm) for value in kernel]
            dc_error = max(dc_error, abs(sum(value[0] for value in coefficients) - 1),
                           abs(sum(value[1] for value in coefficients) - 1))
            l1 = max(l1, sum(max(abs(value[0]), abs(value[1])) for value in coefficients))
        if dc_error >= Fraction(1, 1024) or l1 >= 4:
            raise AssertionError("DC kernel enclosure exceeds its independent coarse guard")
        # Explicitly budget implementation kernel generation before binary32 coefficient storage.
        coefficient_error = taps * generation_allowance + U32 * (l1 + taps * generation_allowance) + taps * H32
        gamma = (2 * taps * U32) / (1 - 2 * taps * U32)
        gain_error = dc_error + coefficient_error + gamma * (l1 + coefficient_error)
        codes = [12345, -23456]
        accepted = []
        for code in codes:
            underflow_codes = 2 * taps * H32 * 32768 / (1 - 2 * taps * U32)
            bound = abs(code) * gain_error + underflow_codes + 1 + Fraction(1, 2**16)
            low, high = code - bound, code + bound
            accepted.append(dict(minimum=-(-low.numerator // low.denominator),
                                 maximum=high.numerator // high.denominator,
                                 absoluteErrorUpper=outward(bound, upper=True)))
        rows.append(dict(inputRate=rate, outputRate=48_000, taps=taps, phases=phases,
                         factorNumerator=str(factor.numerator), factorDenominator=str(factor.denominator),
                         codes=codes, acceptedCodes=accepted,
                         dcGainDeviationUpper=outward(dc_error, upper=True), l1Upper=outward(l1, upper=True)))
    return dict(profile=dict(libswresample="5.3.100", filterSize=32, phaseShift=10, kaiserBeta=9,
                             cutoff="0.97", internalFormat="fltp", normalization="phase-zero",
                             linearInterpolation=True, exactRational=True,
                             kernelSource="https://github.com/FFmpeg/FFmpeg/blob/n7.1.1/libswresample/resample.c",
                             coefficientGenerationAbsoluteAllowance=outward(generation_allowance, upper=True),
                             implementationAssumption="Normalized double kernel coefficients differ from the mathematical Kaiser-sinc kernel by at most 2^-24 before Float32 storage; this explicit budget is not a universal libm/SRC proof",
                             quantizerAbsoluteErrorCodesUpper=outward(1 + Fraction(1, 2**16), upper=True)), cases=rows)


def make_fixture() -> dict:
    rng = random.Random(SEEDS[0])
    originals = [rng.randrange(-2**22, 2**22) for _ in range(66)]
    targets = [[rng.randrange(-32768, 32768) for _ in originals] for _ in ROLES]
    originals[:8] = [1, -1, 0, 0, 2**23 - 1, -2**23, 1, -1]
    for lane, samples in enumerate(targets):
        samples[:8] = [16384, -16384, 32767, -32768, lane + 1, -(lane + 1), 0, 0]
    original_bits = [round_binary(Fraction(value, 2**23)) for value in originals]
    residual_cases = []
    for count in range(1, 9):
        case = dict(targetCount=count, remainingBits=[], boundUpper=[], idealLower=[], idealUpper=[])
        for index, original in enumerate(original_bits):
            operands = [samples[index] for samples in targets[:count]]
            ideal = binary_value(original) - sum(Fraction(x, 32768) for x in operands)
            bound = residual_bound(binary_value(original), [Fraction(x, 32768) for x in operands])
            result = residual_bits(original, operands)
            if abs(binary_value(result) - ideal) > bound:
                raise AssertionError("Residual specification exceeds its independent bound")
            case["remainingBits"].append(result)
            case["boundUpper"].append(outward(bound, upper=True))
            case["idealLower"].append(outward(ideal, upper=False))
            case["idealUpper"].append(outward(ideal, upper=True))
        residual_cases.append(case)
    mix_cases = []

    def mix_case(name: str, lanes: list[int], gains: list[int]) -> None:
        ideal = sum(binary_value(x) * binary_value(g) for x, g in zip(lanes, gains))
        bound = mix_bound(lanes, gains)
        separate, fused = dot_bits(lanes, gains), dot_bits(lanes, gains, fused=True)
        if any(abs(binary_value(result) - ideal) > bound for result in (separate, fused)):
            raise AssertionError("Dot specification exceeds its independent bound")
        mix_cases.append(dict(id=name, laneBits=lanes, gainBits=gains,
                              separateBits=separate, fusedBits=fused,
                              idealLower=outward(ideal, upper=False), idealUpper=outward(ideal, upper=True),
                              boundUpper=outward(bound, upper=True)))

    unity = round_binary(Fraction(1))
    for index in range(len(originals)):
        lanes = [round_binary(Fraction(x[index], 32768)) for x in targets]
        lanes.append(residual_cases[-1]["remainingBits"][index])
        mix_case(f"unity-{index}", lanes, [unity] * 9)
    for seed in SEEDS:
        rng = random.Random(seed)
        for index in range(32):
            lanes = [rng.randrange(1, 0x40000000) | (rng.randrange(2) << 31) for _ in range(9)]
            gains = [round_binary(Fraction(rng.randrange(2**24 + 1), 2**23)) for _ in range(9)]
            mix_case(f"seed-{seed}-{index}", lanes, gains)
    for bits, gain in [(1, unity), (3, 0x3F000000), (0x80000001, unity), (0x00800000, 0x3F000000)]:
        mix_case(f"gradual-{bits}-{gain}", [bits] + [0] * 8, [gain] + [unity] * 8)
    mix_case("signed-zeros", [0x80000000] * 9, [unity] * 9)
    return dict(schemaVersion=1, roles=ROLES, seeds=SEEDS,
                numericProfile=dict(residual="RN64 sequential subtraction then RN32",
                                    mixer="RN32 separate multiply/add or verified fused multiply/add",
                                    rounding="nearest-even", underflow="gradual", laneOrder="targets then Remaining"),
                reference=dict(classification="REFERENCE_PRESET_UNVERIFIED", presetId=None,
                               reason="Registered preset graph and coherent output operands are not available"),
                residual=dict(frameCount=1001, patternFrames=33, originalPcm24=originals,
                              targetsPcm16=targets, cases=residual_cases), mixCases=mix_cases,
                conversion=conversion_fixture())


def cpp_header(fixture: dict) -> str:
    cases = fixture["mixCases"]
    lines = ["// Generated from the exact test-owned merger oracle; never edit expected bits.",
             "#pragma once", "#include <array>", "#include <cstdint>", "namespace merger_oracle {",
             "struct MixCase { const char* id; std::array<std::uint32_t,9> laneBits, gainBits; "
             "std::uint32_t separateBits, fusedBits; double idealLower, idealUpper, boundUpper; };",
             f"inline const std::array<MixCase,{len(cases)}> kMixCases{{{{"]
    for case in cases:
        integers = lambda values: "{{" + ",".join(f"0x{x:08x}U" for x in values) + "}}"
        lines.append("{" + json.dumps(case["id"]) + "," + integers(case["laneBits"]) + "," +
                     integers(case["gainBits"]) + f",0x{case['separateBits']:08x}U,0x{case['fusedBits']:08x}U," +
                     ",".join(float(case[key]).hex() for key in ("idealLower", "idealUpper", "boundUpper")) + "},")
    lines.extend(["}};", "} // namespace merger_oracle", ""])
    return "\n".join(lines)


class OracleChecks(unittest.TestCase):
    def test_conversion_kernel_known_answers_and_gain_fault_rejection(self):
        for turns, expected in [(Fraction(0), Fraction(0)), (Fraction(1), Fraction(0)),
                                (Fraction(1, 2), Fraction(1)), (Fraction(-1, 6), Fraction(-1, 2))]:
            low, high = _sin_pi_interval(turns)
            self.assertLessEqual(low, expected)
            self.assertGreaterEqual(high, expected)
        self.assertEqual(_i0_interval(Fraction(0)), (Fraction(1), Fraction(1)))
        cases = conversion_fixture()['cases']
        self.assertEqual([(row['inputRate'], row['taps'], row['phases']) for row in cases],
                         [(44100, 32, 160), (96000, 66, 1)])
        for row in cases:
            for code, accepted in zip(row['codes'], row['acceptedCodes']):
                self.assertLess(accepted['absoluteErrorUpper'], 2)
                self.assertLessEqual(accepted['minimum'], code)
                self.assertGreaterEqual(accepted['maximum'], code)
                self.assertFalse(accepted['minimum'] <= code // 2 <= accepted['maximum'])

    def test_closed_form_ties_cancellation_and_signed_zero(self):
        for width, fraction_bits in [(32, 23), (64, 52)]:
            one = round_binary(Fraction(1), width)
            self.assertEqual(binary_value(one, width), 1)
            self.assertEqual(round_binary(Fraction(1) + Fraction(1, 2**(fraction_bits + 1)), width), one)
            self.assertEqual(round_binary(Fraction(1) + Fraction(3, 2**(fraction_bits + 1)), width), one + 2)
            self.assertEqual(add_bits(one, one ^ (1 << (width - 1)), width), 0)
            self.assertEqual(add_bits(1 << (width - 1), 1 << (width - 1), width), 1 << (width - 1))
        self.assertEqual(multiply_bits(0x80000000, 0x3F800000), 0x80000000)
        self.assertEqual(round_binary(Fraction(-1, 2**151)), 0x80000000)
        self.assertEqual(round_binary(Fraction(1, 2**150)), 0)
        self.assertEqual(round_binary(Fraction(3, 2**150)), 2)
        self.assertEqual(round_binary(Fraction(2**24 - 1, 2**150)), 0x00800000)
        for value in [0, 1, 0x007FFFFF, 0x00800000, 0x3F800000, 0x7F7FFFFF, 0x80000001]:
            self.assertEqual(round_binary(binary_value(value), negative_zero=value == 0x80000000), value)
        for value in [0x7F800000, 0xFF800000, 0x7FC00001]:
            with self.assertRaisesRegex(ValueError, "Nonfinite"):
                binary_value(value)

    def test_expected_nonidentity_witness_and_algebra(self):
        original = round_binary(Fraction(1, 2**23))
        targets = [16384] * 8
        remaining = residual_bits(original, targets)
        self.assertEqual(remaining, 0xC0800000)
        self.assertEqual(dot_bits([0x3F000000] * 8 + [remaining], [0x3F800000] * 9), 0)
        self.assertEqual(binary_value(original), Fraction(1, 2**23))
        for seed in SEEDS:
            rng = random.Random(seed)
            for count in range(1, 9):
                o = Fraction(rng.randrange(-100, 100), 64)
                operands = [Fraction(rng.randrange(-32768, 32768), 32768) for _ in range(count)]
                gains = [binary_value(round_binary(Fraction(rng.randrange(200), 100))) for _ in range(count + 1)]
                residual = o - sum(operands)
                direct = sum(g * x for g, x in zip(gains[:-1], operands)) + gains[-1] * residual
                self.assertEqual(direct, gains[-1] * o + sum((g - gains[-1]) * x for g, x in zip(gains, operands)))

    def test_rounding_matches_independent_platform_boundary_values(self):
        rng = random.Random(SEEDS[1])
        for _ in range(1000):
            bits = rng.randrange(0x7F800000)
            value = struct.unpack("<f", struct.pack("<I", bits))[0]
            self.assertEqual(Fraction.from_float(value), binary_value(bits))
            self.assertEqual(round_binary(Fraction.from_float(value)), bits)
        for value in [Fraction(1, 3), Fraction(-1, 7), Fraction(2**53 + 3, 2**53)]:
            self.assertEqual(round_binary(value, 64), struct.unpack("<Q", struct.pack("<d", float(value)))[0])

    def test_precomputed_bounds_and_fused_profile_are_independent(self):
        fixture = make_fixture()
        self.assertTrue(any(c["separateBits"] != c["fusedBits"] for c in fixture["mixCases"]))
        for case in fixture["mixCases"]:
            bound = mix_bound(case["laneBits"], case["gainBits"])
            self.assertGreaterEqual(Fraction.from_float(case["boundUpper"]), bound)
            ideal = sum(binary_value(x) * binary_value(g) for x, g in zip(case["laneBits"], case["gainBits"]))
            self.assertLessEqual(Fraction.from_float(case["idealLower"]), ideal)
            self.assertGreaterEqual(Fraction.from_float(case["idealUpper"]), ideal)
        for count in range(1, 9):
            samples = fixture["residual"]
            for index, raw in enumerate(samples["originalPcm24"]):
                original = Fraction(raw, 2**23)
                operands = [Fraction(values[index], 32768) for values in samples["targetsPcm16"][:count]]
                residual = residual_bits(round_binary(original), [int(x * 32768) for x in operands])
                unity = dot_bits([round_binary(x) for x in operands] + [residual], [0x3F800000] * (count + 1))
                br = residual_bound(original, operands)
                self.assertLessEqual(abs(binary_value(unity) - original), br + U32 * (abs(original) + br) + H32)

    def test_strict_comparator_detects_isolated_faults(self):
        lanes = [[round_binary(Fraction(lane * 100 + frame * 2 + channel + 1, 32768))
                  for frame in range(17) for channel in range(2)] for lane in range(9)]
        expected = lanes[0]
        faults = dict(shift=expected[2:] + [0, 0], dropped=expected[:8] + expected[12:],
                      duplicated=expected[:8] + expected[4:8] + expected[8:],
                      channels=[x for pair in zip(expected[1::2], expected[::2]) for x in pair],
                      role=lanes[1], gain=[multiply_bits(x, 0x3F000000) for x in expected],
                      clipping=[round_binary(Fraction(1))] * len(expected), original=lanes[8])
        tiny = expected.copy()
        tiny[10] += 1
        faults["tiny_perturbation"] = tiny
        for name, actual in faults.items():
            result = compare_pcm(expected, actual)
            self.assertFalse(result["equal"], name)
            self.assertIsNotNone(result["firstFrame"], name)
        self.assertEqual(compare_pcm(expected, tiny)["firstFrame"], 5)
        # A compensating residual can hide the wrong lane in a unity sum, but not this gate.
        original, wrong_target = Fraction(1, 2), Fraction(1, 4)
        self.assertEqual(wrong_target + (original - wrong_target), original)
        self.assertFalse(compare_pcm(lanes[0], lanes[1])["equal"])
        bound = mix_bound([round_binary(Fraction(1, 4))] * 9, [0x3F800000] * 9)
        self.assertGreater(Fraction(1, 128), bound)
        with self.assertRaisesRegex(ValueError, "Partial"):
            compare_pcm([0], [0])

    def test_streaming_capture_uses_real_bytes_and_separates_external_boundary(self):
        work = (ROOT / 'Test suite/work').resolve()
        work.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix='merger-capture-check-', dir=work)).resolve()
        self.assertEqual(directory.parent, work)
        try:
            fixture = make_fixture()['residual']
            def pcm(name, bits):
                data = b''.join(struct.pack('<I', value) for value in bits)
                path = directory / name
                path.write_bytes(data)
                return dict(path=name, sha256=hashlib.sha256(data).hexdigest(), byteLength=len(data))
            original = [round_binary(Fraction(value, 8388608)) for value in fixture['originalPcm24']]
            expected = fixture['cases'][1]['remainingBits']
            target_bits = [[round_binary(Fraction(value, 32768)) for value in lane]
                           for lane in fixture['targetsPcm16'][:2]]
            document = dict(schemaVersion=1, sampleRate=48000, channels=2, frames=len(original)//2,
                            original=pcm('original.f32', original),
                            targets=[dict(role='role-' + str(index), **pcm('target-' + str(index) + '.f32', lane))
                                     for index, lane in enumerate(target_bits)],
                            remaining=pcm('remaining.f32', expected))
            manifest = directory / 'capture.json'
            def compare():
                manifest.write_text(json.dumps(document), encoding='utf-8')
                return compare_capture(manifest, chunk_frames=7)
            result = compare()
            self.assertEqual(result['status'], 'PASS')
            self.assertEqual(result['classification'], 'LOCAL_CROSS_MODEL_MERGER')
            self.assertNotIn('providerOther', result)
            self.assertFalse(any('Provider internal' in item for item in result['limitations']))
            self.assertEqual(result['specifiedOperation']['mismatches'], 0)
            document['source'] = pcm('source.encoded', original)
            for invalid in (False, True):
                manifest.write_text(json.dumps(document if not invalid else {**document, 'frames': -1}), encoding='utf-8')
                for destination in (manifest, directory/'original.f32', directory/'target-0.f32', directory/'source.encoded'):
                    before = destination.read_bytes()
                    with mock.patch.object(sys, 'argv', ['merger_math.py', '--compare-capture', str(manifest), '--output', str(destination)]):
                        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'overwrite input'):
                            main()
                    self.assertEqual(destination.read_bytes(), before)
            compare()
            self.assertEqual(result['residualBound']['samplesBeyond'], 0)
            original_path = directory / 'original.f32'
            original_bytes = original_path.read_bytes()
            real_open = Path.open
            appended = False
            class AppendOnRead:
                def __init__(self, stream): self.stream = stream
                def __enter__(self): return self
                def __exit__(self, *args): return self.stream.__exit__(*args)
                def __getattr__(self, name): return getattr(self.stream, name)
                def read(self, size=-1):
                    nonlocal appended
                    data = self.stream.read(size)
                    if not appended:
                        with real_open(original_path, 'ab') as writer:
                            writer.write(b'\0\0\0\0')
                        appended = True
                    return data
            def append_after_precheck(path, *args, **kwargs):
                stream = real_open(path, *args, **kwargs)
                return AppendOnRead(stream) if path == original_path and args == ('rb',) else stream
            with mock.patch.object(Path, 'open', append_after_precheck):
                with self.assertRaisesRegex(ValueError, 'EOF/extent changed'):
                    compare()
            self.assertTrue(appended)
            original_path.write_bytes(original_bytes)
            result_path = directory / 'result.json'
            _publish_result(result_path, json.dumps(result))
            manifest.write_text('{malformed', encoding='utf-8')
            with mock.patch.object(sys, 'argv', ['merger_math.py', '--compare-capture', str(manifest), '--output', str(result_path)]):
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(main(), 1)
            self.assertEqual(json.loads(result_path.read_text(encoding='utf-8'))['status'], 'FAIL')
            self.assertEqual(list(directory.glob('result.json.*.tmp')), [])
            faults = dict(shift=expected[2:] + [0, 0],
                          channels=[value for pair in zip(expected[1::2], expected[::2]) for value in pair],
                          original=original,
                          clipping=[round_binary(max(Fraction(-1), min(Fraction(1), binary_value(value)))) for value in expected])
            tiny = expected.copy(); tiny[10] ^= 1
            faults['tiny'] = tiny
            for name, bits in faults.items():
                document['remaining'] = pcm('remaining.f32', bits)
                failed = compare()
                self.assertEqual(failed['status'], 'FAIL', name)
                self.assertGreater(failed['specifiedOperation']['mismatches'], 0, name)
                self.assertIsNotNone(failed['specifiedOperation']['firstMismatch'], name)
            document['remaining'] = pcm('remaining.f32', expected)
            document['targets'][1]['role'] = document['targets'][0]['role']
            with self.assertRaisesRegex(ValueError, 'distinct target roles'):
                compare()
            document['targets'][1]['role'] = 'role-1'
            raw = (directory / 'remaining.f32').read_bytes()
            (directory / 'remaining.f32').write_bytes(raw[:-4])
            with self.assertRaisesRegex(ValueError, 'extent'):
                compare()
            document['remaining'] = pcm('remaining.f32', expected)
            (directory / 'remaining.f32').write_bytes(b'\x01' + raw[1:])
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                compare()
            document['remaining'] = pcm('remaining.f32', expected)
            other = directory / 'other.wav'
            with wave.open(str(other), 'wb') as output:
                output.setparams((2, 2, 44100, len(original)//2, 'NONE', 'not compressed'))
                output.writeframes(b'\0' * len(original) * 2)
            document['providerOther'] = dict(
                delivered=dict(path=other.name, sha256=hashlib.sha256(other.read_bytes()).hexdigest(),
                               byteLength=other.stat().st_size, sampleRate=44100, channels=2, frames=len(original)//2),
                canonical=dict(**pcm('other.f32', [0]*len(original)), sampleRate=48000, channels=2,
                               frames=len(original)//2, transformation='explicit test conversion diagnostic'))
            compared = compare()
            self.assertEqual(compared['status'], 'PASS')
            self.assertEqual(compared['classification'], 'EXPORT_BOUNDARY_COMPARISON')
            self.assertIn('Provider internal Original, targets and residual operation are unobserved', compared['limitations'])
            self.assertEqual(compared['providerOther']['deliveredBoundary']['status'], 'METADATA_MISMATCH')
            self.assertGreater(compared['providerOther']['commonBoundary']['pcm']['mismatches'], 0)
        finally:
            self.assertEqual(directory.parent, work)
            for path in directory.iterdir():
                path.unlink()
            directory.rmdir()


def _float32_units(bits: int) -> int:
    """Every finite binary32 is an integer multiple of 2^-149."""
    exponent = bits >> 23 & 255
    if exponent == 255:
        raise ValueError("Nonfinite captured Float32 sample")
    units = ((bits & 0x7fffff) | (0x800000 if exponent else 0)) << max(exponent - 1, 0)
    return -units if bits >> 31 else units


def _fast_residual_bits(original: float, targets) -> tuple[int, float]:
    value = original
    for target in targets:
        value -= target
    return struct.unpack('<I', struct.pack('<f', value))[0], value


def _upper(value: float) -> float:
    return math.nextafter(value, math.inf)


def _within_float_bound(units: int, bound: float) -> bool:
    numerator, denominator = bound.as_integer_ratio()
    return abs(units) * denominator <= numerator << 149


class _ExactDifference:
    def __init__(self):
        self.samples = self.mismatches = self.peak = self.squares = 0
        self.first = None

    def add(self, expected: int, actual: int, index: int, expected_bits=None, actual_bits=None):
        error = actual - expected
        mismatch = error != 0 or (expected_bits is not None and expected_bits != actual_bits)
        self.samples += 1
        self.peak = max(self.peak, abs(error))
        self.squares += error * error
        if mismatch:
            self.mismatches += 1
            if self.first is None:
                self.first = dict(frame=index // 2, channel=index % 2,
                                  expectedUnits=str(expected), actualUnits=str(actual))
                if expected_bits is not None:
                    self.first.update(expectedBits=expected_bits, actualBits=actual_bits)

    def result(self):
        return dict(samples=self.samples, mismatches=self.mismatches, firstMismatch=self.first,
                    peakAbsoluteError=math.ldexp(float(self.peak), -149),
                    rmsError=math.ldexp(math.sqrt(self.squares / self.samples), -149) if self.samples else 0,
                    exactPeakUnits=str(self.peak), exactSumSquaredUnits=str(self.squares), unitExponent=-149)


def _verify_fast_residual_boundary(fixture):
    if sys.float_info.radix != 2 or sys.float_info.mant_dig != 53 or sys.byteorder != 'little':
        raise RuntimeError("Streaming comparator requires IEEE binary64 on a little-endian host")
    residual = fixture['residual']
    for case in residual['cases']:
        count = case['targetCount']
        for index, code in enumerate(residual['originalPcm24']):
            actual, _ = _fast_residual_bits(code / 8388608,
                [lane[index] / 32768 for lane in residual['targetsPcm16'][:count]])
            if actual != case['remainingBits'][index]:
                raise RuntimeError("Fast streaming residual profile differs from the exact dyadic oracle")
    for original_bits in (0x00000001, 0x00800000, 0x80000000, 0x3f800001, 0x60000001):
        original = struct.unpack('<f', struct.pack('<I', original_bits))[0]
        for operands in ([0], [32767, -32768, 1], [-32768, 32767, 1, -1, 0, 0, 0, 1]):
            actual, _ = _fast_residual_bits(original, [code / 32768 for code in operands])
            if actual != residual_bits(original_bits, list(operands)):
                raise RuntimeError("Fast residual fails a cancellation/exponent boundary")


def compare_capture(manifest_path: Path, *, chunk_frames=8192) -> dict:
    """Compare actual exported PCM in bounded memory; provider internal operands remain unknown."""
    manifest_path = Path(manifest_path).resolve()
    document = json.loads(manifest_path.read_text(encoding='utf-8'))
    if document.get('schemaVersion') != 1 or document.get('sampleRate') != 48000 or document.get('channels') != 2:
        raise ValueError("Capture must declare schema 1, 48000 Hz stereo")
    frames = document.get('frames')
    if not isinstance(frames, int) or isinstance(frames, bool) or frames <= 0 or chunk_frames <= 0:
        raise ValueError("Invalid capture frame extent")
    targets = document['targets']
    roles = [target['role'] for target in targets]
    if not 1 <= len(targets) <= 8 or len(set(roles)) != len(roles) or any(not role for role in roles):
        raise ValueError("Capture requires one through eight distinct target roles")
    fixture = json.loads(FIXTURE.read_text(encoding='utf-8'))
    _verify_fast_residual_boundary(fixture)
    descriptors = [('Original', document['original'])] + list(zip(roles, targets)) + [('Remaining', document['remaining'])]
    other = document.get('providerOther')
    if other:
        canonical = other['canonical']
        if any(canonical.get(key) != document[key] for key in ('sampleRate', 'channels', 'frames')):
            raise ValueError("Canonical Other metadata differs from the capture boundary")
        descriptors.append(('canonical Other', canonical))
    paths, identities = [], []
    for role, record in descriptors:
        path = (manifest_path.parent / record['path']).resolve()
        if not path.is_file() or path.stat().st_size != frames * 8 or record.get('byteLength', frames * 8) != frames * 8:
            raise ValueError(f"{role}: Float32 byte/frame extent mismatch")
        if not isinstance(record.get('sha256'), str) or len(record['sha256']) != 64:
            raise ValueError(f"{role}: missing SHA256")
        paths.append(path)
        identities.append(dict(role=role, path=str(path), sha256=record['sha256'].lower(), byteLength=frames * 8))
    if len(set(paths)) != len(paths):
        raise ValueError("Capture reuses an ambiguous PCM file slot")
    receipt_records = []
    if document.get('source'):
        receipt_records.append(('source', document['source']))
    for target in targets:
        for boundary in ('input', 'prepared'):
            if target.get(boundary):
                receipt_records.append((target['role'] + ':' + boundary, target[boundary]))
        if target.get('bytePreserving') and target['input']['sha256'] != target['prepared']['sha256']:
            raise ValueError(target['role'] + ': byte-preserving preparation changed encoded bytes')
    if document['remaining'].get('encoded'):
        receipt_records.append(('Remaining:encoded', document['remaining']['encoded']))
    if other and other['canonical'].get('prepared'):
        receipt_records.append(('Other:prepared', other['canonical']['prepared']))
    receipts = []
    for role, record in receipt_records:
        path = (manifest_path.parent / record['path']).resolve()
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
        if digest.hexdigest() != record['sha256'].lower() or path.stat().st_size != record['byteLength']:
            raise ValueError(role + ': bound encoded/source bytes changed')
        receipts.append(dict(role=role, path=str(path), sha256=digest.hexdigest(), byteLength=path.stat().st_size))
    delivered_result = None
    delivered_path = None
    strict_wave = False
    if other:
        delivered = other['delivered']
        delivered_path = (manifest_path.parent / delivered['path']).resolve()
        digest = hashlib.sha256()
        with delivered_path.open('rb') as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
        if digest.hexdigest() != delivered['sha256'].lower() or delivered_path.stat().st_size != delivered['byteLength']:
            raise ValueError("Delivered Other bytes differ from its receipt")
        mismatch = {key: dict(local=document[key], delivered=delivered.get(key))
                    for key in ('sampleRate', 'channels', 'frames') if document[key] != delivered.get(key)}
        delivered_result = dict(status='METADATA_MISMATCH' if mismatch else 'DECODE_BLOCKED', metadataMismatches=mismatch,
                                path=str(delivered_path), sha256=digest.hexdigest())
        if delivered_path.suffix.lower() == '.wav':
            with wave.open(str(delivered_path), 'rb') as wav:
                actual_metadata = dict(sampleRate=wav.getframerate(), channels=wav.getnchannels(), frames=wav.getnframes())
                if any(delivered.get(key) != value for key, value in actual_metadata.items()) or wav.getsampwidth() != 2:
                    raise ValueError("Delivered Other WAV metadata differs from its receipt/PCM16 declaration")
                read_frames = 0
                while block := wav.readframes(8192):
                    if len(block) % (wav.getnchannels() * 2):
                        raise ValueError("Delivered Other has an incomplete PCM frame")
                    read_frames += len(block) // (wav.getnchannels() * 2)
                if read_frames != actual_metadata['frames']:
                    raise ValueError("Delivered Other WAV actual frame extent differs from its receipt")
            strict_wave = not mismatch
    residual_stats, unity_stats = _ExactDifference(), _ExactDifference()
    exact_residual_error, common_stats = _ExactDifference(), _ExactDifference()
    exported_delta, strict_stats = _ExactDifference(), _ExactDifference()
    beyond_residual = beyond_unity = 0
    peak_residual_bound = peak_unity_bound = 0.0
    hashes = [hashlib.sha256() for _ in paths]
    count = len(targets)
    gamma64 = _upper((count * 2.0**-53) / (1 - count * 2.0**-53))
    gamma32 = _upper(((count + 1) * 2.0**-24) / (1 - (count + 1) * 2.0**-24))
    with ExitStack() as stack:
        streams = [stack.enter_context(path.open('rb')) for path in paths]
        native_other = stack.enter_context(wave.open(str(delivered_path), 'rb')) if strict_wave else None
        for start in range(0, frames, chunk_frames):
            block_frames = min(chunk_frames, frames - start)
            blocks = [stream.read(block_frames * 8) for stream in streams]
            if any(len(block) != block_frames * 8 for block in blocks):
                raise ValueError("Capture truncated during streaming read")
            for digest, block in zip(hashes, blocks):
                digest.update(block)
            values = [memoryview(block).cast('f') for block in blocks]
            bits = [memoryview(block).cast('I') for block in blocks]
            delivered_samples = memoryview(native_other.readframes(block_frames)).cast('h') if native_other else None
            if delivered_samples is not None and len(delivered_samples) != block_frames * 2:
                raise ValueError("Delivered Other WAV truncated during read")
            for offset in range(block_frames * 2):
                index = start * 2 + offset
                for role_index, (role, _) in enumerate(descriptors):
                    if not math.isfinite(values[role_index][offset]):
                        raise ValueError(f"{role}: nonfinite frame={index // 2} channel={index % 2}")
                original, actual = values[0][offset], values[count + 1][offset]
                target_values = [values[lane + 1][offset] for lane in range(count)]
                target_units = []
                for lane, value in enumerate(target_values):
                    code = value * 32768
                    if code != int(code) or not -32768 <= code <= 32767:
                        raise ValueError(f"{roles[lane]}: target is not canonical PCM16 at frame={index // 2}")
                    target_units.append(int(code) << 134)
                expected_bits, specified64 = _fast_residual_bits(original, target_values)
                original_units = _float32_units(bits[0][offset])
                ideal_units = original_units - sum(target_units)
                actual_units = _float32_units(bits[count + 1][offset])
                residual_stats.add(_float32_units(expected_bits), actual_units, index, expected_bits, bits[count + 1][offset])
                exact_residual_error.add(ideal_units, actual_units, index)
                magnitude = abs(original)
                for value in target_values:
                    magnitude = _upper(magnitude + abs(value))
                residual_bound = _upper(_upper(gamma64 * magnitude) + _upper(2.0**-24 * abs(specified64) + 2.0**-150))
                peak_residual_bound = max(peak_residual_bound, residual_bound)
                beyond_residual += not _within_float_bound(actual_units - ideal_units, residual_bound)
                mixed = 0.0
                sum_magnitude = abs(actual)
                for value in target_values + [actual]:
                    mixed = struct.unpack('<f', struct.pack('<f', mixed + value))[0]
                for value in target_values:
                    sum_magnitude = _upper(sum_magnitude + abs(value))
                mixed_bits = struct.unpack('<I', struct.pack('<f', mixed))[0]
                mixed_units = _float32_units(mixed_bits)
                unity_stats.add(original_units, mixed_units, index, bits[0][offset], mixed_bits)
                unity_bound = _upper(residual_bound + _upper(gamma32 * sum_magnitude + (count + 1) * 2.0**-150))
                peak_unity_bound = max(peak_unity_bound, unity_bound)
                beyond_unity += not _within_float_bound(mixed_units - original_units, unity_bound)
                if other:
                    provider_bits = bits[count + 2][offset]
                    provider_units = _float32_units(provider_bits)
                    common_stats.add(provider_units, actual_units, index, provider_bits, bits[count + 1][offset])
                    exported_delta.add(ideal_units, provider_units, index)
                    if delivered_samples is not None:
                        provider_value = delivered_samples[offset] / 32768
                        delivered_bits = struct.unpack('<I', struct.pack('<f', provider_value))[0]
                        strict_stats.add(_float32_units(delivered_bits), actual_units, index,
                                         delivered_bits, bits[count + 1][offset])
        for role_index, stream in enumerate(streams):
            if stream.read(1) or os.fstat(stream.fileno()).st_size != frames * 8 or paths[role_index].stat().st_size != frames * 8:
                raise ValueError(descriptors[role_index][0] + ': capture EOF/extent changed during comparison')
        if native_other and native_other.readframes(1):
            raise ValueError('Delivered Other WAV has extra PCM frames')
        if other and delivered_path.stat().st_size != other['delivered']['byteLength']:
            raise ValueError('Delivered Other byte extent changed during comparison')
    for identity, digest in zip(identities, hashes):
        if digest.hexdigest() != identity['sha256']:
            raise ValueError(identity['role'] + ': captured bytes differ from their bound SHA256')
    local_pass = residual_stats.mismatches == 0 and beyond_residual == 0 and beyond_unity == 0
    result = dict(schemaVersion=1,
                  classification='EXPORT_BOUNDARY_COMPARISON' if other else 'LOCAL_CROSS_MODEL_MERGER',
                  status='PASS' if local_pass else 'FAIL',
                  sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  fixtureSha256=hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
                  captureManifestPath=str(manifest_path),
                  captureManifestSha256=hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                  sampleRate=48000, channels=2, frames=frames, inputs=identities, encodedReceipts=receipts,
                  targetBindings=[{key: target.get(key) for key in ('role', 'input', 'prepared', 'bytePreserving')}
                                  for target in targets], reference=document.get('reference'),
                  specifiedOperation=residual_stats.result(), residualExactRealError=exact_residual_error.result(),
                  residualBound=dict(peakUpper=peak_residual_bound, samplesBeyond=beyond_residual),
                  calculatedUnityClosure=unity_stats.result(),
                  unityBound=dict(peakUpper=peak_unity_bound, samplesBeyond=beyond_unity),
                  limitations=['Unity closure is calculated from captured operands, not captured production playback',
                               'Per-role binding comes from the hashed capture manifest; musical/source correctness is not inferred from a residual sum'])
    if other:
        result['limitations'].append('Provider internal Original, targets and residual operation are unobserved')
        if strict_wave:
            delivered_result.update(status='DECODED_PCM_EQUAL' if strict_stats.mismatches == 0 else 'DECODED_PCM_DIFFERENT',
                                    pcm=strict_stats.result())
        result['providerOther'] = dict(deliveredBoundary=delivered_result,
            commonBoundary=dict(classification='CONVERTED_EXPORT_DIAGNOSTIC',
                                transformation=other['canonical'].get('transformation'), pcm=common_stats.result()),
            decomposition=dict(equation='R_local - R_export = E_local - D_export',
                               definitions='E_local=R_local-(O_local-sum(T_local)); D_export=R_export-(O_local-sum(T_local))',
                               exportDefinitionDelta=exported_delta.result(),
                               scope='Captured export operands only; this does not identify provider internal operands or arithmetic error'))
    return result


def validate_capture_output(manifest: Path, output: Path):
    """Resolve input paths before either a success or failure report can replace a file."""
    manifest, output = manifest.resolve(), output.resolve()
    protected = {manifest, FIXTURE.resolve()}
    complete = True
    try:
        values = [json.loads(manifest.read_text(encoding='utf-8'))]
        if not isinstance(values[0], dict):
            raise ValueError('Capture manifest must be an object')
        while values:
            value = values.pop()
            if isinstance(value, dict):
                if 'path' in value:
                    if not isinstance(value['path'], str) or not value['path']:
                        raise ValueError('Invalid artifact path')
                    protected.add((manifest.parent / value['path']).resolve())
                values.extend(value.values())
            elif isinstance(value, list):
                values.extend(value)
    except (ValueError, OSError):
        complete = False
    if any(output == path or (output.exists() and path.exists() and output.samefile(path))
           for path in protected):
        raise ValueError('Output must not overwrite input manifests or artifacts')
    if not complete and output.exists():
        try:
            previous = json.loads(output.read_text(encoding='utf-8'))
            owned = (isinstance(previous, dict) and previous.get('schemaVersion') == 1
                     and previous.get('classification') in {'LOCAL_CROSS_MODEL_MERGER', 'EXPORT_BOUNDARY_COMPARISON'}
                     and previous.get('captureManifestPath') == str(manifest)
                     and previous.get('status') in {'PASS', 'FAIL'})
        except (ValueError, OSError):
            owned = False
        if not owned:
            raise ValueError('Incomplete input paths: existing output is not this capture report')


def _publish_result(path: Path, payload: str):
    path = Path(path)
    descriptor, name = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write-fixture", action="store_true")
    parser.add_argument("--cpp-header", type=Path)
    parser.add_argument("--compare-capture", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    if arguments.compare_capture:
        if arguments.output:
            validate_capture_output(arguments.compare_capture, arguments.output)
        try:
            result = compare_capture(arguments.compare_capture)
            payload = json.dumps(result, indent=2, allow_nan=False) + '\n'
        except Exception as error:
            result = dict(schemaVersion=1, classification='EXPORT_BOUNDARY_COMPARISON', status='FAIL', error=str(error),
                          captureManifestPath=str(arguments.compare_capture.resolve()))
            payload = json.dumps(result, indent=2, allow_nan=False) + '\n'
        if arguments.output:
            _publish_result(arguments.output, payload)
        print(payload, end='')
        return 0 if result['status'] == 'PASS' else 1
    fixture = make_fixture()
    payload = json.dumps(fixture, separators=(",", ":"), allow_nan=False) + "\n"
    if arguments.write_fixture:
        FIXTURE.write_text(payload, encoding="utf-8", newline="\n")
    if not FIXTURE.is_file() or FIXTURE.read_text(encoding="utf-8") != payload:
        parser.error("Committed merger fixture is stale; explicitly regenerate with --write-fixture")
    if arguments.cpp_header:
        arguments.cpp_header.write_text(cpp_header(fixture), encoding="utf-8", newline="\n")
    if arguments.check:
        result = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(OracleChecks))
        if not result.wasSuccessful() or result.skipped:
            return 1
    print(json.dumps(dict(schemaVersion=1, fixtureSha256=hashlib.sha256(payload.encode()).hexdigest(),
                          residualCounts=list(range(1, 9)), mixCases=len(fixture["mixCases"]),
                          reference=fixture["reference"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
