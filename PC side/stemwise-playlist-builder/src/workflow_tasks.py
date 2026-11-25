"""Small state-specific job instructions; the Builder guide owns procedures."""
from dataclasses import dataclass
from stemwise_engine.workflow_states import JobState, JobProgress


def timing_review_steps(report):
    """Surface the named word edge and the next usable action before the full evidence."""
    lines = []
    all_options = report.get('listeningOptions', [])
    options = [option for option in all_options if option['status'] == 'needs-listening']
    confirmed = {(o['line'], o['wordIndex']) for o in all_options if o['status'] == 'A-confirmed'}
    prioritized = set()
    for group in report.get('instrumentalBoundaryReview', {}).get('boundaries', []):
        if group['reviewRole'] != 'flagged-boundary' or (group['line'], group['wordIndex']) in confirmed:
            continue
        label = f"Line {group['line']}, word {group['wordIndex']} “{group['word']}” {group['boundary']}"
        if group['association'] == 'candidate-for-listening':
            time = group['listenFirstMs'][0]
            candidate = next(c for c in group['nativeComparisons'] if c['atMs'] == time)
            prioritized.add((group['line'], group['wordIndex']))
            lines.append(f"{label} — check the vocal at {time / 1000:.3f} s. "
                         f"The instrumental hit peaks at {candidate['impactMs'] / 1000:.3f} s.")
        else:
            rival = next((r for candidate in group['nativeComparisons'] if candidate['nearbyPeakMs']
                          for r in candidate['competingBoundaries']), None)
            if rival:
                lines.append(f"{label} — the hit is also near line {rival['line']}, word {rival['wordIndex']} "
                             f"“{rival['word']}” {rival['boundary']}. Check the vocal.")
            else:
                lines.append(f"{label} — the instrumental evidence cannot distinguish this boundary. Check the vocal.")
    if options:
        options = sorted(options, key=lambda option: (option['line'], option['wordIndex']) not in prioritized)
        lines.append("Recording comparisons available")
        for option in options:
            alternative = option['B']
            selectable = alternative['canSelectEnd'] or alternative['canSelectInterval']
            suffix = '' if selectable else ' (B crosses a neighbor or would move a locked boundary)'
            lines.append(f"Line {option['line']}, word {option['wordIndex']} “{option['word']}” — {option['id']}{suffix}")
        lines.extend(["Prepare the first comparison in PowerShell", options[0]['prepareCommand']])
    return '\n'.join(lines)


@dataclass(frozen=True)
class TaskPlan:
    video_id: str
    state: JobState
    policy: str
    guide: str
    directory: str
    child: str
    launcher: str
    translation: bool | None
    ipa: bool | None
    endpoint_directory: str | None = None
    display_directory: str | None = None
    recording_key: str | None = None
    progress: JobProgress | None = None
    next_action: str | None = None
    audio_candidate: str | None = None

    @property
    def selector(self):
        name, value = ('RecordingKey', self.recording_key) if self.recording_key else ('VideoId', self.video_id)
        return f"-{name} '{value.replace(chr(39), chr(39)*2)}'"

    def render(self):
        status = {True: 'required', False: 'forbidden', None: 'determine after original identification'}
        launcher = "'"+self.launcher.replace("'", "''")+"'"
        selector = self.selector
        lines = [f'StemWise job: {self.video_id}', f'State: {self.state.value}', f'Policy: {self.policy}',
            f'Builder guide: {self.guide}', f'Job and review inputs: {self.directory}', f'Child ZIP: {self.child}',
            f'- English translation: `{status[self.translation]}`', f'- IPA: `{status[self.ipa]}`',
            'Author and review each companion track marked required above.',
            'Original is timed word by word; translation and IPA follow its occurrence lines.',
            'Missing or unreviewed required companions remain pending and block final delivery; Original timing can proceed independently, and every word needs start and end timing.',
            'Use companion-review-inputs.json in the job folder for current original occurrences, candidate identities and review checks.',
            'Record exact role decisions in agent-review.json companionReviews, bound independently to the exact current candidates.',
            'Translation preserves source meaning, referents, polarity, modality, names and ambiguity; IPA describes this performed pronunciation.',
            'Keep uncertain alternatives and located issues private; timing, ASR and syntax never establish meaning or pronunciation.',
            'During unresolved recovery, examine retained short non-credit descriptions in sources.json as review candidates; length alone establishes neither approval nor absence.',
            'Without an Original, RecordingEvidence proposes unapproved wording candidates. Prepare and review original.txt or original.lrc and lyrics.meta.json before recording comparison.',
            'Try eligible source and recording evidence work, then Validate the complete-word Original target; companion tracks remain independent.',
            'Plain, partial, line-only, untimed and incomplete lyrics remain blocked recovery. Unavailable lyrics need qualified exhaustive search and recovery evidence; never invent listening or review decisions.',
            'If wording and performed order pass recording admission and recording review, Validate can finish a timing-only refusal with measured acoustic estimates. Preserve timing locks and estimated labels. The separate owner-verified-lyrics.json route requires actual owner confirmation.',
            'Keep lyric bodies in the local review inputs; report only identifiers and decisions.',
            f'For another lyric source, stage it with `& {launcher} -Action Source {selector} -Url \'<source-url>\'`.', '']
        if self.state == JobState.NEEDS_LYRICS:
            lines += ['Resolve missing or uncertain performed words, pronunciation and occurrence order before timing.',
                'Do not use forced alignment or prediction to resolve lyric content; unresolved claims are withheld from the package.']
        if self.endpoint_directory and self.state != JobState.NEEDS_LYRICS:
            lines += [f'Pending endpoint declaration and review: {self.endpoint_directory}',
                'Use EndpointAdjudicate or inspect EndpointEvidence. Preserve abstention; choose only declared candidates.']
        if self.display_directory:
            lines += [f'Pending display original.txt and declaration: {self.display_directory}',
                'Apply presentation-only changes there, then Validate. Word or line changes return to the draft.']
        if self.audio_candidate:
            lines += [f'Unapproved audio-first lyric candidate: {self.audio_candidate}',
                'Compare it with this recording before preparing the original; it grants no wording or timing authority.']
        action = self.next_action or (self.progress or JobProgress.current({'state': self.state.value})).next_action
        lines += ['', '```powershell', f'$StemWiseAgent = {launcher}', f'& $StemWiseAgent -Action Status {selector}']
        if action not in ('Status', 'Task'):
            lines.append(f'& $StemWiseAgent -Action {action} {selector}')
        lines += ['```', '']
        return '\n'.join(lines).encode('utf-8')
