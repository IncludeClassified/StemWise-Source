"""One current evidence adapter catalogue for live and stored verification."""
from dataclasses import dataclass
from importlib import import_module


@dataclass(frozen=True)
class EvidenceAdapter:
    module: object
    arguments: str

    @property
    def method(self):
        return self.module.METHOD

    def stored(self, attempt, binding):
        return self.module.verify_stored_packet(attempt, binding)

    def verify(self, attempt, declaration, declaration_sha, audio, binding, *, config,
               mapping, evidence, canonical):
        args = (attempt, declaration, declaration_sha, audio, binding)
        if self.arguments == 'model':
            args += (config, mapping, evidence, canonical)
        elif self.arguments == 'repeat':
            args += (mapping, evidence, canonical)
        return self.module.verify_packet(*args)


_CURRENT = (('endpoint_audio_evidence', 'basic'),
    ('endpoint_ctc_evidence', 'basic'), ('endpoint_repeat_alignment', 'repeat'),
    ('endpoint_candidate_prediction', 'basic'), ('endpoint_native_ctc', 'model'),
    ('endpoint_owsm_ctc', 'model'))


def evidence_adapter(method):
    for name, arguments in _CURRENT:
        adapter = EvidenceAdapter(import_module('stemwise_engine.' + name), arguments)
        if method == adapter.method:
            return adapter
    raise ValueError('Unsupported current endpoint evidence method')
