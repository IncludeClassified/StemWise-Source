"""Conservative comparison units with immutable display-word ownership.

An internal hyphen is an explicit boundary, never permission to concatenate words,
expand contractions, transliterate text, or remove pronunciation marks.
"""
from dataclasses import dataclass

from stemwise_engine.lyrics_tokenization import display_words, lexical_line_tokens


@dataclass(frozen=True)
class ComparisonProjection:
    words: tuple[tuple[int, int, str], ...]
    raw_units: tuple[str, ...]
    units: tuple[str, ...]
    owners: tuple[int, ...]
    boundaries: tuple[int, ...]
    languages: tuple[str | None, ...]


def _raw_parts(text):
    return tuple(part for _, _, surface in display_words(text)
        for part in surface.replace('\u2010', '-').replace('\u2011', '-').split('-'))


def fold_units(raw_units, languages):
    """Apply known ownership per comparison unit; never infer missing languages."""
    if len(raw_units) != len(languages):
        raise ValueError('Comparison units do not match language ownership')
    result = []
    for text, language in zip(raw_units, languages):
        folded = tuple(word for line in lexical_line_tokens(text, language=language) for word in line)
        if len(folded) != 1:
            raise ValueError('Comparison unit is not one lexical component')
        result.append(folded[0])
    return tuple(result)


def project(text, language=None, *, forms=None):
    """Project exact display spans, optionally using existing reviewed spoken forms."""
    words = tuple(display_words(text))
    forms = tuple(forms) if forms is not None else tuple((surface, language) for _, _, surface in words)
    if len(forms) != len(words):
        raise ValueError('Spoken forms do not match display-word ownership')
    raw, owners, boundaries, languages = [], [], [0], []
    for owner, (form, owner_language) in enumerate(forms):
        parts = _raw_parts(form)
        if not parts:
            raise ValueError('Comparison form has no lexical components')
        raw.extend(parts)
        owners.extend([owner]*len(parts))
        languages.extend([owner_language]*len(parts))
        boundaries.append(len(raw))
    return ComparisonProjection(words, tuple(raw), fold_units(raw, languages), tuple(owners),
        tuple(boundaries), tuple(languages))


def units(text, language=None):
    return project(text, language).units
