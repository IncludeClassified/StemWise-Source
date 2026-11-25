"""Select a font for the actual subtitle text, including extended Arabic letters."""
from pathlib import Path

from stemwise_engine.common import run_process_tree


def subtitle_font(lines):
    codes=sorted({ord(char) for line in lines for char in line['text'] if char.isprintable()})
    pattern=':charset='+' '.join(f'{code:x}' for code in codes)
    def query(args):
        return run_process_tree(args,check=True,capture_output=True,text=True,timeout=30).stdout.strip()
    families=query(['fc-list','-f','%{family[0]}\n',pattern]).splitlines()
    for family in ('DejaVu Sans','Noto Sans Arabic','Noto Naskh Arabic',*families):
        if family in families and ',' not in family:
            return family,None
    # WSL fontconfig does not normally index the host fonts. Make just one
    # matching installed face visible to this render without installing it.
    for filename in ('arial.ttf','tahoma.ttf','segoeui.ttf','arabtype.ttf'):
        font=Path('/mnt/c/Windows/Fonts')/filename
        if not font.is_file():
            continue
        family,charset=query(['fc-scan','-f','%{family[0]}|%{charset}',str(font)]).split('|',1)
        ranges=[r.split('-') for r in charset.split()]
        covered=[(int(r[0],16),int(r[-1],16)) for r in ranges]
        if all(any(a<=code<=b for a,b in covered) for code in codes):
            return family,font
    # libass can combine several installed fallback fonts in one line.
    return 'DejaVu Sans',None
