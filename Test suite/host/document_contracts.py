"""Generate the small current-contract table in the technical reference."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
START = '<!-- stemwise-contract-values:start -->'
END = '<!-- stemwise-contract-values:end -->'


def values(root=ROOT):
    root = Path(root)
    capacity = json.loads((root/'StemWise/contracts/audio/playback-capacity.json').read_bytes())
    targets, leases = capacity['maximumTargets'], capacity['additionalDecoderLeases']
    if capacity.get('schemaVersion') != 1 or type(targets) is not int or not 1 <= targets <= 61 or type(leases) is not int or leases < 0:
        raise ValueError('Invalid playback capacity contract')
    schema = root/'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json'
    return {'Selected target limit':str(targets), 'Native music lane capacity':str(targets+2),
            'Maximum decoder leases':str(targets+2+leases), 'Installed schema SHA-256':hashlib.sha256(schema.read_bytes()).hexdigest()}


def render(root=ROOT):
    return START+'\n| Current contract | Executable value |\n|---|---|\n'+''.join(
        '| '+name+' | `'+value+'` |\n' for name, value in values(root).items())+END


def check(root=ROOT):
    text = (Path(root)/'STEMWISE_DOCUMENTATION.md').read_text(encoding='utf-8')
    if text.count(START) != 1 or text.count(END) != 1:
        raise ValueError('Current contract table is missing or duplicated')
    actual = text[text.index(START):text.index(END)+len(END)]
    if actual != render(root):
        raise ValueError('Current documentation values differ from executable contracts')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if args.write:
        path = ROOT/'STEMWISE_DOCUMENTATION.md'
        text = path.read_text(encoding='utf-8')
        text = text[:text.index(START)]+render()+text[text.index(END)+len(END):]
        path.write_text(text,encoding='utf-8',newline='\n')
    check()
