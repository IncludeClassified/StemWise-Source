"""Offline Matroska fixtures for real extractor track ordering and no-audio rejection."""
import json
from pathlib import Path
import subprocess
import tempfile


def generate():
    root = Path(__file__).resolve().parents[2]
    target = root / "android/instrumentation/assets/encoder"
    work = root / "work"
    work.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="decoder-tracks-", dir=work) as temporary:
        subtitle = Path(temporary) / "caption.srt"
        subtitle.write_text("1\n00:00:00,000 --> 00:00:00,100\nFixture\n", encoding="utf-8")
        for audio in (True, False):
            output = target / ("leading-text-track.mkv" if audio else "no-audio-track.mkv")
            command = ["ffmpeg", "-v", "error", "-y", "-i", str(subtitle)]
            if audio:
                command += ["-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000:duration=0.1",
                            "-map", "0:s:0", "-map", "1:a:0", "-c:a", "flac"]
            else:
                command += ["-map", "0:s:0"]
            subprocess.run(command + ["-c:s", "srt", "-map_metadata", "-1", str(output)], check=True, timeout=30)
            probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_streams",
                                                        "-of", "json", str(output)], timeout=10))
            assert [stream["codec_type"] for stream in probe["streams"]] == (
                ["subtitle", "audio"] if audio else ["subtitle"])
            if audio:
                assert probe["streams"][1]["sample_rate"] == "48000"


if __name__ == "__main__":
    generate()
