"""Recognize repeat layouts in the untimed performed sheet."""
from itertools import groupby
from collections import defaultdict

from stemwise_engine.lyric_comparison import units


def stanza_groups(lines):
    groups = defaultdict(list)
    for _,rows in groupby(enumerate(lines),lambda row:row[1]['sourceLine']-row[0]):
        stanza = list(rows)
        form = tuple(line['text'] for _,line in stanza)
        if len(set(form)) >= 2:
            groups[form].append(stanza[0][0])
    for form,starts in groups.items():
        if len(starts)>=3 and any(b-a==len(form) for a,b in zip(starts,starts[1:])):
            yield starts,len(form)


def repeating_unit(sequence):
    for size in range(1, len(sequence)//2+1):
        if len(sequence)%size == 0 and sequence[:size]*(len(sequence)//size) == sequence:
            return size
    return None


def find_passages(song):
    """Use lyrical form to nominate regions. No audio times or singer labels enter here."""
    stanzas = []
    for _,group in groupby(enumerate(song['lines']),lambda item:item[1]['sourceLine']-item[0]):
        stanzas.append([i for i,line in group])
    for pos in range(1,len(stanzas)-1):
        title, choir = stanzas[pos:pos+2]
        def words(indices):
            return [units(song['lines'][i]['text'],song['language']) for i in indices]
        form = words(title)
        unique = {w for line in form for w in line}
        if len(unique)!=1:
            continue
        unit = repeating_unit(form)
        block = repeating_unit(words(choir))
        earlier = [s for s in stanzas[:pos] if words(s)==form]
        if unit and block and earlier and len(set(sum(words(choir)[:block],())))>1:
            yield earlier[-1],title,choir,unit,block,unique.pop()
