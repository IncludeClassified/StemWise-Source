"""Read public Qobuz album metadata when catalogue search omits a rendition."""
import json
import re
import sys
import time
import urllib.parse
from html.parser import HTMLParser


def album_ordinals(tracks):
    """Infer omitted ordinals only when explicit anchors agree with disc-local page order."""
    positions, missing, misaligned, seen, result = {}, set(), set(), set(), []
    for track in tracks:
        disc = track.get('discNumber', 1)
        if type(disc) is not int or disc <= 0:
            raise ValueError('Invalid authoritative disc ordinal')
        position = positions.get(disc, 0) + 1
        positions[disc] = position
        if 'trackNumber' not in track:
            missing.add(disc)
            number = position
        else:
            number = track['trackNumber']
            if type(number) is not int or number <= 0:
                raise ValueError('Invalid authoritative track ordinal')
            if number != position:
                misaligned.add(disc)
        pair = disc, number
        if pair in seen:
            raise ValueError('Duplicate authoritative disc and track ordinal')
        seen.add(pair)
        result.append(pair)
    if missing & misaligned:
        raise ValueError('Ambiguous mixed explicit and omitted album track ordinals')
    return result


class AlbumParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.current = None
        self.capture = None
        self.tracks = []
        self.expected = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = attrs.get("class", "").split()
        if "data-nbtracksdisplayed" in attrs:
            self.expected = int(attrs["data-nbtracksdisplayed"])
        if tag == "div":
            self.depth += 1
        if "data-track" in attrs and tag == "div":
            if self.current is not None:
                raise ValueError("Nested track metadata")
            metadata = json.loads(attrs["data-track-v2"])
            track_id = int(attrs["data-track"])
            if metadata.get("item_id") != track_id or track_id <= 0:
                raise ValueError("Mismatched track identifier")
            quality = re.fullmatch(r"([\d.]+)Khz - (\d+)-bits", metadata.get("item_variant_max", ""), re.I)
            self.current = dict(id=track_id, title=metadata["item_name"], artist=metadata["item_brand"],
                                album=metadata["item_category"], version=None, duration=None,
                                bitDepth=int(quality[2]) if quality else None,
                                sampleRateKHz=float(quality[1]) if quality else None,
                                url=f"https://play.qobuz.com/track/{track_id}", credits=[])
            disc = attrs.get('data-disc-number', attrs.get('data-media-number', metadata.get('media_number', 1)))
            self.current['discNumber'] = int(disc)
            if 'data-track-number' in attrs or 'track_number' in metadata:
                self.current['trackNumber'] = int(attrs.get('data-track-number', metadata.get('track_number')))
            self.root_depth = self.depth
        if self.current is None:
            return
        if "track__items" in classes and attrs.get("title"):
            displayed = attrs["title"]
            self.current["displayTitle"] = displayed
            title = self.current["title"]
            if displayed.startswith(title):
                suffix = displayed[len(title):].strip()
                if suffix.startswith("(") and suffix.endswith(")"):
                    self.current["version"] = suffix[1:-1]
        if tag == "span" and "track__item--duration" in classes:
            self.capture = "duration"
        elif tag == "p" and "track__info" in classes:
            self.capture = "credit"

    def handle_data(self, data):
        if self.current is None:
            return
        if self.capture == "duration" and data.strip():
            values = data.strip().split(":")
            if len(values) != 3 or not all(v.isdigit() for v in values):
                raise ValueError("Invalid displayed track duration")
            hours, minutes, seconds = map(int, values)
            if minutes >= 60 or seconds >= 60:
                raise ValueError("Invalid displayed track duration")
            self.current["duration"] = hours * 3600 + minutes * 60 + seconds
        elif self.capture == "credit" and data.strip():
            self.current["credits"].append(data.strip())

    def handle_endtag(self, tag):
        if tag in ("span", "p"):
            self.capture = None
        if tag == "div":
            if self.current is not None and self.depth == self.root_depth:
                if not self.current["duration"]:
                    raise ValueError("Missing full-track duration")
                self.tracks.append(self.current)
                self.current = None
            self.depth -= 1


def parse_album_html(source):
    parser = AlbumParser()
    parser.feed(source)
    if not parser.tracks or parser.current is not None or (parser.expected is not None and len(parser.tracks) != parser.expected):
        raise ValueError("Missing or partial album track list")
    if len({t["id"] for t in parser.tracks}) != len(parser.tracks):
        raise ValueError("Duplicate track identifier")
    for track, (disc, number) in zip(parser.tracks, album_ordinals(parser.tracks), strict=True):
        track.update(discNumber=disc, trackNumber=number)
    return parser.tracks


def read_album(url, *, budget=None):
    from stemwise_media.transfer_budget import TransferBudget, http_request
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "www.qobuz.com" or "/album/" not in parsed.path:
        raise ValueError("Use a public Qobuz album page")
    budget = budget or TransferBudget(time.monotonic()+25)
    content = http_request(url, budget, headers={"User-Agent": "Mozilla/5.0"}, max_bytes=3*1024*1024)
    tracks = parse_album_html(content.decode("utf-8"))
    for track in tracks:
        track["sourcePage"] = url
    return tracks


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: catalogue_page.py QOBUZ_ALBUM_PAGE_URL")
    print(json.dumps(read_album(sys.argv[1]), ensure_ascii=False))
