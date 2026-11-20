"""Small state-specific job instructions; the Builder guide owns procedures."""
from dataclasses import dataclass
from stemwise_engine.workflow_states import JobState, JobProgress


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
            'After ordinary alignment refuses, only the owner\'s independent verification of these exact words, pronunciation and performed occurrences can authorize complete acoustic predictions through owner-verified-lyrics.json. Keep predicted timing estimated; never invent owner approval or missing word intervals.',
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
        lines += ['', '```powershell', f'$StemWiseAgent = {launcher}',
            f'& $StemWiseAgent -Action Status {selector}', f'& $StemWiseAgent -Action {action} {selector}', '```', '']
        return '\n'.join(lines).encode('utf-8')
