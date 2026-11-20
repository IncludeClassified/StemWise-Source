"""Current JNI layout. Generate both platform projections; --check never rewrites source."""
from pathlib import Path
import sys
import re
import json

ROOT = Path(__file__).resolve().parents[1]
# Typed playback capacity owns both language projections.
_capacity = json.loads((ROOT / 'contracts/audio/playback-capacity.json').read_bytes())
MAX_TARGETS = _capacity['maximumTargets']
if _capacity.get('schemaVersion') != 1 or type(MAX_TARGETS) is not int or not 1 <= MAX_TARGETS <= 61:
    raise ValueError('Invalid playback capacity contract')
STEM_COUNT = MAX_TARGETS + 1  # materialized Remaining
MUSIC_BUS_COUNT = STEM_COUNT + 1  # Original
VERSIONS = {
    "OUTPUT_DIAGNOSTICS": 19, "RUNTIME_DIAGNOSTICS": 20, "CONTROL_PROGRESS": 3,
    "RENDER_READINESS": 11, "LOOP_INTENT": 5,
    "LOOP_INGRESS_READY": 2, "LOOP_PROTOCOL": 4,
    "STEM_GAIN_CALLBACK": 3, "AUDIBLE_CONTROL": 2,
}
COMMAND_LONG = ["SCHEMA", "REVISION", "OUTPUT_GENERATION"]
for bank in range(2):
    COMMAND_LONG += [f"BANK_{bank}_{field}" for field in
        ("REVISION", "ASSIGNED", "TOKEN", "GENERATION", "FIRST_FRAME", "OUTPUT_FRAME")]
COMMAND_LONG += ["SELECTED", "SELECTED_BANK", "SELECTED_TOKEN", "SELECTED_GENERATION",
    "PLAYING", "CONTROL_REVISION", "SOURCE_MODE", "SOURCE_RAMP", "LANE_RAMP", "MASTER_RAMP", "DECLICK"]
for lane in range(STEM_COUNT):
    COMMAND_LONG += [f"LANE_{lane}_MUTED", f"LANE_{lane}_SOLO"]
COMMAND_LONG += ["METRONOME_MODE", "METRONOME_SUBDIVISION", "METRONOME_METER", "METRONOME_UNIT",
    "SUCCESSOR_PRESENT", "SUCCESSOR_REVISION", "OUTGOING_TOKEN", "OUTGOING_GENERATION",
    "SUCCESSOR_BANK", "SUCCESSOR_TOKEN", "SUCCESSOR_GENERATION", "SUCCESSOR_INHERIT",
    "SOURCE_INTENT", "STEM_INTENT"]
for bank in range(2):
    COMMAND_LONG += [f"BANK_{bank}_{field}" for field in ("SONG_HASH", "ORIGINAL_HASH", "STEM_HASH")]
COMMAND_LONG += ["BANK_0_STEM_COUNT", "BANK_1_STEM_COUNT", "BANK_0_CACHE_BOUND", "BANK_1_CACHE_BOUND", "BANK_0_PLAYBACK_END", "BANK_1_PLAYBACK_END"]
COMMAND_FLOAT = [f"LANE_{lane}_GAIN" for lane in range(STEM_COUNT)] + [
    "MUSIC_GAIN", "HEADROOM", "METRONOME_BPM", "METRONOME_GAIN", "TEMPO", "PITCH"]

# Field order is the protocol. Repeated sections define their own width and count.
PACKETS = {
    'TRACK_CONTROL': ['PLAYING', 'RESET_SERIAL', 'RESET_ACTION', 'GENERATION', 'SESSION_ACTIVE', 'SOURCE_TARGET', 'WAKE_SEQUENCE'],
    'TRACK_METADATA': ['SEQUENCE', 'TERMINAL'],
    'TRACK_CONFIGURATION': ['RATE', 'CHANNELS', 'ENCODING', 'BUFFER', 'CAPACITY', 'THRESHOLD', 'PERFORMANCE'],
    'TRACK_ACTUAL': ['STATE', 'BUFFER', 'CAPACITY', 'RATE', 'PERFORMANCE', 'UNDERRUNS'],
    'TRACK_POSITION': ['HEAD', 'TIMESTAMP_FRAME', 'TIMESTAMP_NANOS', 'SUBMITTED'],
    'RUNTIME_DIAGNOSTICS': [
        'SCHEMA', 'STATUS', 'COMMAND_SNAPSHOTS_PUBLISHED', 'COMMAND_SNAPSHOTS_CONSUMED',
        'COMMAND_SNAPSHOTS_SUPERSEDED', 'REJECTED_COMMAND_SNAPSHOTS', 'BEAT_GRID_UPDATES_PUBLISHED', 'REJECTED_BEAT_GRID_UPDATES',
        'RENDER_CYCLES', 'IDLE_SLEEPS', 'OUTPUT_FLUSHES', 'OUTPUT_FLUSH_FAILURES',
        'OUTPUT_HANDOFF_BEGINS', 'OUTPUT_HANDOFF_BEGIN_RETRIES', 'OUTPUT_HANDOFF_COMMITS', 'OUTPUT_HANDOFF_COMMIT_RETRIES',
        'OUTPUT_BLOCKS_RENDERED', 'OUTPUT_FRAMES_RENDERED', 'OUTPUT_FRAMES_ACCEPTED', 'OUTPUT_PARTIAL_ACCEPTS',
        'OUTPUT_BACKPRESSURE_EVENTS', 'OUTPUT_INVALID_RENDER_RESULTS', 'OUTPUT_NEED_DATA_EVENTS', 'OUTPUT_END_OF_STREAM_EVENTS',
        'TARGET_QUEUED_FRAMES', 'LAST_OUTPUT_FINGERPRINT_SEQUENCE', 'LAST_RENDER_PAYLOAD_HASH', 'REQUESTED_TOKEN',
        'REQUESTED_GENERATION', 'ACCEPTED_TOKEN', 'ACCEPTED_GENERATION', 'COMMITTED_TOKEN',
        'COMMITTED_GENERATION', 'ACCEPTED_FIRST_FINGERPRINT_SEQUENCE', 'PRESENTATION_SEQUENCE', 'PRESENTATION_GENERATION',
        'PRESENTATION_SELECTION_TOKEN', 'PRESENTATION_FINGERPRINT_SEQUENCE', 'PRESENTATION_FIRST_TIMELINE_FRAME', 'PRESENTATION_FIRST_OUTPUT_FRAME',
        'PRESENTATION_CALLBACK_SEQUENCE', 'PRESENTATION_PCM_FRAMES', 'POSITION_SEQUENCE', 'POSITION_GENERATION',
        'POSITION_SELECTION_TOKEN', 'POSITION_FINGERPRINT_SEQUENCE', 'POSITION_LAST_TIMELINE_FRAME', 'POSITION_LAST_OUTPUT_FRAME',
        'POSITION_CALLBACK_SEQUENCE', 'POSITION_PCM_FRAMES', 'AUDIBLE_END_SEQUENCE', 'AUDIBLE_END_TOKEN',
        'AUDIBLE_END_GENERATION', ('BANK', 2, ['APPLIED_INTENT_REVISION', 'ASSIGNED', 'BEGIN_SONG_COUNT', 'SEEK_COUNT', 'REJECTED_BANK_INTENTS', 'BEAT_GRID_UPDATES', 'BEAT_GRID_CLEARS']), 'AUDIBLE_CONTROL_SEQUENCE', 'AUDIBLE_CONTROL_TOKEN',
        'AUDIBLE_CONTROL_GENERATION', 'AUDIBLE_CONTROL_CONTROL_REVISION', 'AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE', 'AUDIBLE_CONTROL_FIRST_FINGERPRINT_SEQUENCE',
        'AUDIBLE_CONTROL_LAST_FINGERPRINT_SEQUENCE', 'AUDIBLE_CONTROL_CALLBACK_SEQUENCE', 'AUDIBLE_CONTROL_PCM_FRAMES', 'PROCESS_CONSTRUCTION_COUNT',
        'PROCESS_LIVE_INSTANCE_COUNT', 'AUTOMATIC_PHASE', 'AUTOMATIC_CANCELLATION_STATUS', 'AUTOMATIC_REVISION',
        'AUTOMATIC_TRANSITION_REVISION', 'AUTOMATIC_OUTGOING_TOKEN', 'AUTOMATIC_OUTGOING_GENERATION', 'AUTOMATIC_SUCCESSOR_TOKEN',
        'AUTOMATIC_SUCCESSOR_GENERATION', 'AUTOMATIC_SUCCESSOR_QUEUED_FRAMES', 'AUTOMATIC_FIRST_FINGERPRINT_SEQUENCE', 'AUTOMATIC_CANCELLATION_DESIRED',
        'AUTOMATIC_SUCCESSOR_END_OBSERVED', 'RENDER_WORKER_STARTED', 'RENDER_WORKER_AUDIO_PRIORITY_REQUESTED', 'RENDER_WORKER_AUDIO_PRIORITY_APPLIED',
        'RENDER_WORKER_NICE_VALUE', 'RENDER_WORKER_PRIORITY_ERROR_CODE', 'OUTPUT_FLUSH_RETRIES', 'PROCESS_OUTPUT_INVALID_RENDER_RESULTS',
        'PROCESS_OUTPUT_FLUSH_FAILURES', 'LOW_WATER_SEQUENCE', 'LOW_WATER_GENERATION', 'LOW_WATER_FRAMES',
        'LOW_WATER_MONOTONIC_MICROS', 'NEED_ORIGINAL_LOW_WATER_EVIDENCE_SEQUENCE', 'NEED_ORIGINAL_LAST_MONOTONIC_MICROS', 'NEED_ORIGINAL_LAST_GENERATION',
        'NEED_STEM_LOW_WATER_EVIDENCE_SEQUENCE', 'NEED_STEM_LAST_MONOTONIC_MICROS', 'NEED_STEM_LAST_GENERATION', 'LOOP_BOUNDARY_LOW_WATER_EVIDENCE_SEQUENCE',
        'LOOP_BOUNDARY_LAST_MONOTONIC_MICROS', 'LOOP_BOUNDARY_LAST_GENERATION', 'POSITION_LOOP_POLICY_ADOPTED_REVISION', 'POSITION_LOOP_POLICY_ADOPTION_LATENCY_MICROS',
        'POSITION_LOOP_POLICY_REVISION', 'POSITION_LOOP_POLICY_INPUT_TO_ATOMIC_PUBLICATION_LATENCY_MICROS', 'POSITION_LOOP_POLICY_INPUT_TO_CALLBACK_ADOPTION_LATENCY_MICROS', 'AUDIBLE_END_DIRECT_LOOP_POLICY_REVISION',
        'RESOURCE_OPERATIONS_COMPLETED', 'RESOURCE_LAST_OPERATION_MICROS', 'RESOURCE_MAXIMUM_OPERATION_MICROS', 'RESOURCE_MAXIMUM_QUEUE_AGE_MICROS',
         'REFILL_DEADLINE_MISSES', 'CANONICAL_CACHE_TOKEN', 'CANONICAL_CACHE_GENERATION',
        'CANONICAL_CACHE_SONG_HASH', 'CANONICAL_CACHE_ORIGINAL_HASH', 'CANONICAL_CACHE_STEM_HASH',
        'CANONICAL_READ_OPERATIONS', 'CANONICAL_READ_BYTES', 'CANONICAL_LAST_READ_BYTE_OFFSET',
        'CANONICAL_LAST_READ_BYTES', 'CANONICAL_FILE_BYTES', 'CANONICAL_RESERVED_BYTES', 'CANONICAL_PEAK_FILE_BYTES',
        'CANONICAL_ADMITTED_LANES', 'CANONICAL_ADMITTED_FRAME_BOUND', 'CANONICAL_LAST_ADMISSION_STATUS',
        ('END_ORDERING', 6, ['SEQUENCE', 'WORKER_STATE_SEQUENCE', 'COMMAND_REVISION', 'TOKEN', 'GENERATION',
            'DESIRED_PLAYING', 'OUTPUT_PLAYING', 'PIPELINE_END_OBSERVED', 'COMMAND_UNREAD',
            'PENDING_OUTPUT_FRAMES', 'QUEUED_OUTPUT_FRAMES', 'TERMINAL_POSITION_VALID', 'TERMINAL_AFTER_FRAME']),
    ],
    'RENDER_READINESS': [
        'SCHEMA', 'SEQUENCE', 'ASSIGNED', 'BANK',
        'OWNERSHIP_SELECTION_TOKEN', 'OWNERSHIP_GENERATION', 'CONTROL_REVISION', 'DESIRED_SOURCE_MODE',
        'RENDER_START_TIMELINE_FRAME', 'PENDING_CONTROL_REVISION', 'PENDING_ACTUAL_SOURCE_MODE', 'PENDING_SOURCE_START_TIMELINE_FRAME',
        'PENDING_SOURCE_END_TIMELINE_FRAME_EXCLUSIVE', 'PENDING_FRAMES', 'OUTPUT_QUEUED_FRAMES', 'STEM_ACTIVATION_DEFERRALS',
        'STEM_SAFETY_FALLBACKS', 'NEED_STEM_DATA_COUNT', ('LANE', MUSIC_BUS_COUNT, ['GENERATION', 'FIRST_TIMELINE_FRAME', 'END_TIMELINE_FRAME_EXCLUSIVE', 'AVAILABLE_FROM_RENDER_START', 'RESERVOIR_GAP_REJECTION_COUNT', 'INGRESS_FAILURE_GENERATION', 'INGRESS_ACTIVE_GENERATION', 'INGRESS_PRODUCER_GENERATION', 'INGRESS_CONSUMER_GENERATION', 'INGRESS_READABLE_SOURCE_FRAMES', 'INGRESS_PULLS', 'INGRESS_STALE_AFTER_PULL', 'INGRESS_FUTURE_GENERATION_DEFERRALS']), 'CURRENT_SONG_CACHE_BOUND_TO_SELECTED_OWNER',
        'CURRENT_SONG_CACHE_COMPLETE_LANE_MASK', 'INGRESS_BANK_CONFIGURED_LANE_COUNT', 'DIRECT_ATOMIC_POLICY_GENERATION', 'DIRECT_ATOMIC_POLICY_PUBLISHED_REVISION',
        'DIRECT_ATOMIC_POLICY_ADOPTED_REVISION', 'DIRECT_ATOMIC_POLICY_OBSERVED_REVISION', 'INGRESS_SERVICE_POLICY_BREAKS', 'INGRESS_BANK_IDENTITY',
        'CURRENT_SONG_CACHE_CAN_GROW', 'REQUIRED_SONG_LANE_MASK', 'CACHE_STORAGE_STATUS', 'FIXED_LOOP_HEAD_PREPARED',
        'LOOP_FAILURE_TOKEN', 'LOOP_FAILURE_GENERATION', 'LOOP_FAILURE_REVISION', 'LOOP_FAILURE_REASON',
    ],
    'STEM_GAIN_CALLBACK': [
        'SCHEMA', 'VALID', 'SEQUENCE', 'GENERATION',
        'SELECTION_TOKEN', 'OFFERED_SEQUENCE', 'PUBLISHED_SEQUENCE', 'ACTIVE_MASK',
        ('GAIN_BITS', STEM_COUNT, None), 'CALLBACK_SEQUENCE', 'FIRST_TAGGED_FRAME', 'LAST_TAGGED_FRAME',
        'TAGGED_FRAME_COUNT', 'NON_ZERO_PCM_FRAME_COUNT', 'CALLBACK_OBSERVED_AT_NS',
    ],
    'LOOP_PROTOCOL': [
        'SCHEMA', 'ADOPTED', 'BANK', 'STATE_MODE',
        'STATE_PHASE', 'STATE_ACTIVATION', 'STATE_LATEST_REVISION', 'STATE_OWNER_SELECTION_TOKEN',
        'STATE_OWNER_RENDER_GENERATION', 'STATE_RENDER_CYCLE', 'STATE_ACTIVE_INGRESS_EPOCH', 'STATE_PREPARED_INGRESS_EPOCH',
        'STATE_CACHE_CHECKSUM', 'STATE_CACHE_ACCEPTED', 'STATE_RENDER_INSTALLED', 'STATE_RENDER_ACTIVE',
        'STATE_AUDIBLY_PROVEN', 'STATE_INGRESS_REQUEST_PENDING', 'STATE_RENDER_DIRECTIVE_PENDING', 'REQUEST_VALID',
        'REQUEST_SEQUENCE', 'REQUEST_LOOP_REVISION', 'REQUEST_TARGET_CYCLE', 'REQUEST_OWNER_SELECTION_TOKEN',
        'REQUEST_OWNER_RENDER_GENERATION', 'REQUEST_INGRESS_EPOCH', 'REQUEST_TARGET_TIMELINE_FRAME', 'REQUEST_REQUIRED_LANE_MASK',
        'REQUEST_ACTIVATION', 'DIRECTIVE_VALID', 'DIRECTIVE_SEQUENCE', 'DIRECTIVE_ACTION',
        'DIRECTIVE_LOOP_REVISION', 'DIRECTIVE_CYCLE', 'DIRECTIVE_OWNER_SELECTION_TOKEN', 'DIRECTIVE_OWNER_RENDER_GENERATION',
        'DIRECTIVE_INGRESS_EPOCH', 'DIRECTIVE_START_TIMELINE_FRAME', 'DIRECTIVE_END_TIMELINE_FRAME_EXCLUSIVE', 'DIRECTIVE_HEAD_END_TIMELINE_FRAME_EXCLUSIVE',
        'DIRECTIVE_CACHE_CHECKSUM', 'DIRECTIVE_REQUIRED_LANE_MASK', 'DIRECTIVE_DECLICK_FRAMES', 'AUDIBLE_VALID',
        'AUDIBLE_SEQUENCE', 'AUDIBLE_LOOP_REVISION', 'AUDIBLE_CYCLE', 'AUDIBLE_OWNER_SELECTION_TOKEN',
        'AUDIBLE_OWNER_RENDER_GENERATION', 'AUDIBLE_CALLBACK_SEQUENCE', 'CACHE_VALID', 'CACHE_SLOT_INDEX',
        'CACHE_KEY_SONG_IDENTITY_HASH', 'CACHE_KEY_ORIGINAL_CONTENT_HASH', 'CACHE_KEY_STEM_PACKAGE_HASH', 'CACHE_KEY_CONTENT_REVISION',
        'CACHE_KEY_LOOP_REVISION', 'CACHE_KEY_START_TIMELINE_FRAME', 'CACHE_KEY_END_TIMELINE_FRAME_EXCLUSIVE', 'CACHE_REQUIRED_LANE_MASK',
        'CACHE_COMPLETED_LANE_MASK', 'CACHE_FRAME_COUNT', 'CACHE_CACHE_ONLY', 'CACHE_COMBINED_CHECKSUM',
        ('LANE_CHECKSUM', MUSIC_BUS_COUNT, None), 'PREPARATION_PHASE', 'PREPARATION_KEY_LOOP_REVISION', 'PREPARATION_REQUIRED_LANE_MASK',
        'PREPARATION_COMPLETED_LANE_MASK', 'PREPARATION_ACTIVE_LANE', 'PREPARATION_ACTIVE_SOURCE_RATE_HZ', 'PREPARATION_ACTIVE_PREPARATION_GENERATION',
        'PREPARATION_NEXT_EXPECTED_TIMELINE_FRAME', 'PREPARATION_BEGIN_CALLS', 'PREPARATION_CONFIGURED_LANES', 'PREPARATION_OFFER_CALLS',
        'PREPARATION_INPUT_FRAMES_ACCEPTED', 'PREPARATION_CACHE_FRAMES_WRITTEN', 'PREPARATION_LANES_COMPLETED', 'PREPARATION_COMMITS',
        'PREPARATION_FAILURES', 'PREPARATION_ABORTS', 'ACCEPTED_INTENTS', 'UNCHANGED_INTENTS',
        'OWNERSHIP_REBINDS', 'STALE_INTENTS', 'REVISION_COLLISIONS', 'CACHES_ACCEPTED',
        'CACHE_REJECTIONS', 'INGRESS_REQUESTS', 'INGRESS_ACKS', 'INGRESS_ACK_REJECTIONS',
        'POSITION_OBSERVATIONS', 'REJECTED_POSITION_OBSERVATIONS', 'BOUNDARY_EVENTS', 'REJECTED_BOUNDARY_EVENTS',
        'RENDER_DIRECTIVES', 'RENDER_COMMITS', 'CACHE_ONLY_WRAPS', 'STREAMED_WRAPS',
        'DEADLINE_FALLBACKS', 'CALLBACK_PROOFS', 'REJECTED_CALLBACK_PROOFS', 'LAST_SUBMIT_STATUS',
        'LAST_CACHE_STATUS', 'LAST_INGRESS_STATUS', 'LAST_POSITION_STATUS', 'LAST_BOUNDARY_STATUS',
        'LAST_RENDER_COMMIT_STATUS', 'LAST_CALLBACK_STATUS', 'POSITION_EVIDENCE_SEQUENCE', 'LAST_OUTPUT_LOOP_EVIDENCE_SEQUENCE',
        'STATE_BOUNDARY_MODE', 'STATE_BOUNDARY_RESOLVED', 'STATE_RESOLVED_LOOP_END_TIMELINE_FRAME_EXCLUSIVE', 'REQUEST_REQUIRED_END_TIMELINE_FRAME_EXCLUSIVE',
        'DIRECTIVE_BOUNDARY_MODE', 'CACHE_BOUNDARY_MODE', 'CACHE_ORIGINAL_END_RESOLVED', 'CACHE_RESOLVED_ORIGINAL_END_TIMELINE_FRAME_EXCLUSIVE',
        'LAST_ORIGINAL_EOS_STATUS', 'ORIGINAL_EOS_ACCEPTED', 'ORIGINAL_EOS_REJECTED',
    ],
    'OUTPUT_DIAGNOSTICS': [
        'SCHEMA', 'STREAM_GENERATION', 'STREAM_STATE', 'BACKEND',
        'SAMPLE_RATE_HZ', 'CHANNEL_COUNT', 'FRAMES_PER_BURST', 'OUTPUT_BUFFER_CAPACITY_FRAMES',
        'OUTPUT_BUFFER_SIZE_FRAMES', 'RING_CAPACITY_FRAMES', 'QUEUED_FRAMES', 'TOTAL_FRAMES_ACCEPTED',
        'RENDERED_FRAMES', 'CALLBACK_COUNT', 'CALLBACK_FRAMES', 'X_RUN_COUNT',
        'UNDERRUN_CALLBACK_COUNT', 'UNDERRUN_FRAMES', 'STALE_FRAMES_REJECTED', 'PRODUCER_BACKPRESSURE_FRAMES',
        'APPLIED_GENERATION', 'LAST_PRESENTED_GENERATION', 'PLAYING',
        'RECOVERY_REQUIRED', 'LAST_ERROR_CODE', 'REOPEN_COUNT', 'LAST_ERROR_MONOTONIC_MILLIS',
        'CALLBACK_DEADLINE_MISS_COUNT', 'MAX_CALLBACK_DURATION_MICROS', 'PERFORMANCE_MODE',
        'LAST_PRESENTED_SELECTION_TOKEN', 'LAST_PRESENTED_FINGERPRINT_SEQUENCE', 'LAST_PRESENTED_TIMELINE_FRAME', 'PRESENTED_OWNER_SEQUENCE',
        'LAST_OWNER_FIRST_OUTPUT_FRAME', 'LAST_OWNER_FIRST_CALLBACK_SEQUENCE', 'LAST_OWNER_FIRST_PCM_FRAMES', 'INVALID_OWNERSHIP_TAG_FRAMES',
        'PROCESS_CONSTRUCTION_COUNT', 'PROCESS_LIVE_INSTANCE_COUNT', 'EFFECTIVE_RENDER_PLAYING', 'MANUAL_SELECTION_GATE_STATE',
        'MANUAL_SELECTION_AUTHORIZED_GENERATION', 'MANUAL_SELECTION_GATE_ARM_COUNT', 'MANUAL_SELECTION_GATE_AUTHORIZATION_COUNT', 'MANUAL_SELECTION_GATE_REJECTED_AUTHORIZATION_COUNT',
        'MANUAL_SELECTION_GATE_OPEN_COUNT', 'MANUAL_SELECTION_GATE_LAST_ARM_CALLBACK_SEQUENCE', 'PCM_LEVEL_SEQUENCE', 'PCM_LEVEL_GENERATION',
        'PCM_LEVEL_SELECTION_TOKEN', 'PCM_LEVEL_FINGERPRINT_SEQUENCE', 'PCM_LEVEL_CALLBACK_SEQUENCE', 'PCM_LEVEL_FRAMES',
        'PCM_LEVEL_MEAN_SQUARE', 'PCM_LEVEL_PEAK', 'PROCESS_INVALID_OWNERSHIP_TAG_FRAMES', 'PROCESS_UNDERRUN_CALLBACK_COUNT',
        'PROCESS_UNDERRUN_FRAMES', 'PROCESS_XRUN_COUNT', 'PROCESS_CALLBACK_DEADLINE_MISS_COUNT', 'CALLBACK_START_GAP_MICROS',
        'MAXIMUM_CALLBACK_START_GAP_MICROS', 'LAST_UNDERRUN_SEQUENCE', 'LAST_UNDERRUN_GENERATION_AT_RENDER_DECISION', 'LAST_UNDERRUN_GENERATION_AT_COMPLETION',
        'LAST_UNDERRUN_CALLBACK_SEQUENCE', 'LAST_UNDERRUN_REQUESTED_FRAMES', 'LAST_UNDERRUN_QUEUED_FRAMES_AT_RENDER_DECISION', 'LAST_UNDERRUN_PHYSICAL_FRAMES',
        'LAST_UNDERRUN_VALID_PCM_FRAMES', 'LAST_UNDERRUN_MISSING_FRAMES', 'LAST_UNDERRUN_CALLBACK_START_GAP_MICROS', 'LAST_UNDERRUN_LATEST_LATE_CALLBACK_START_GAP_MICROS',
        'LAST_UNDERRUN_LATEST_LATE_CALLBACK_SEQUENCE', 'LAST_UNDERRUN_MANUAL_ARM_CALLBACK_CEILING', 'LAST_UNDERRUN_APPLIED_COMMIT_SEQUENCE', 'UNDERRUN_EVIDENCE_ROLE_SWAP_AT_CALLBACK_START',
        'UNDERRUN_EVIDENCE_ROLE_SWAP_DURING_DRAIN', 'UNDERRUN_EVIDENCE_MANUAL_ARM_BOUNDARY', 'PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_CALLBACK_COUNT', 'PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_FRAMES',
        'SUCCESSFUL_ENQUEUE_SEQUENCE', 'LAST_SUCCESSFUL_ENQUEUE_MONOTONIC_MICROS', 'LAST_SUCCESSFUL_ENQUEUE_GENERATION', 'LAST_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES',
        'LAST_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES', 'MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS', 'LAST_UNDERRUN_MONOTONIC_MICROS', 'UNDERRUN_EVIDENCE_SUCCESSFUL_ENQUEUE_SNAPSHOT_COHERENT',
        'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_SEQUENCE', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_GENERATION', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_AGE_MICROS', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES',
        'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES', 'LAST_UNDERRUN_MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS', 'ACCEPTED_DEMAND_COUNT', 'ACCEPTED_CAPTURE_COUNT',
        'LATEST_DEMAND_REVISION', 'LATEST_DEMAND_AFRAME', 'LATEST_DEMAND_BFRAME_EXCLUSIVE', 'LATEST_DEMAND_PRESENTED_FRAME',
        'LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_ARMED', 'LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_CROSSED', 'LATEST_CAPTURE_REVISION', 'LATEST_CAPTURE_AFRAME',
        'LATEST_CAPTURE_BFRAME_EXCLUSIVE', 'LATEST_CAPTURE_PRESENTED_FRAME', 'SUCCESSFUL_CAPTURE_CUTOVER_COUNT', 'LATEST_CUTOVER_CAPTURE_REVISION',
        'LATEST_CUTOVER_CALLBACK_SEQUENCE', 'LATEST_CUTOVER_INPUT_LATENCY_MICROS', 'PROCESS_BOUNDARY_MISS_COUNT', 'LATEST_MISS_CAPTURE_REVISION',
        'LATEST_MISS_CALLBACK_SEQUENCE', 'LATEST_MISS_PRESENTED_FRAME', 'LATEST_DEMAND_GESTURE_ID', 'LATEST_CAPTURE_GESTURE_ID',
        'LOOP_DEMAND_LATEST_CAPTURE_INFERRED', 'LATEST_CUTOVER_GENERATION', 'LATEST_CUTOVER_SELECTION_TOKEN', 'LATEST_CUTOVER_AFRAME',
        'LATEST_CUTOVER_GESTURE_ID',
        'CALLBACK_MIX_SEQUENCE',
        'CALLBACK_MIX_CALLBACK_SEQUENCE',
        'CALLBACK_MIX_GENERATION',
        'CALLBACK_MIX_SELECTION_TOKEN',
        'CALLBACK_MIX_CONTROL_REVISION',
        'CALLBACK_MIX_MISSING_CONTROL_FRAMES',
        'CALLBACK_MIX_STALE_CONTROL_FRAMES',
        'CALLBACK_MIX_MIXER_REJECTED_FRAMES',
        'CALLBACK_MIX_INVALID_TAG_FRAMES',
        'CALLBACK_MIX_ADVANCE_FAILURES',
        'CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES',
        'CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED',
        'CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES',
        'CALLBACK_MIX_MIXER_LIMITER_SAFETY_CLAMPS',
        'CALLBACK_MIX_FINAL_NON_FINITE_SAMPLES_SCRUBBED',
        'CALLBACK_MIX_FINAL_SAFETY_CLAMPS',
        'PHYSICAL_RUN_SEQUENCE', 'LAST_UNDERRUN_PHYSICAL_RUN_SEQUENCE',
        'PROCESS_INCOMPLETE_STREAM_OBSERVATION_COUNT',
        'LAST_CALLBACK_DEADLINE_MISS_START_MICROS', 'LAST_CALLBACK_DEADLINE_MISS_END_MICROS',
    ],
    'LOOP_INTENT': [
        'SCHEMA', 'REVISION', 'ENABLED', 'TOKEN',
        'GENERATION', 'SONG_HASH', 'ORIGINAL_HASH', 'STEM_HASH',
        'CONTENT_REVISION', 'LOOP_REVISION', 'START_FRAME', 'END_FRAME',
        'REQUIRED_LANE_MASK', 'DECLICK_FRAMES', 'BOUNDARY_MODE',
    ],
    'LOOP_INGRESS_READY': [
        'SCHEMA', 'REQUEST_SEQUENCE', 'LOOP_REVISION', 'TARGET_CYCLE',
        'TOKEN', 'GENERATION', 'INGRESS_EPOCH', 'READY_LANE_MASK',
        'EOS_PADDED_LANE_MASK', 'FIRST_FRAME', 'END_FRAME',
    ],
    'AUDIBLE_CONTROL': [
        'SCHEMA', 'SEQUENCE', 'TOKEN', 'GENERATION',
        'CONTROL_REVISION', 'ACTUAL_SOURCE_MODE', 'FIRST_FINGERPRINT', 'LAST_FINGERPRINT',
        'CALLBACK_SEQUENCE', 'PCM_FRAMES', 'ACTUAL_TEMPO_BITS', 'ACTUAL_PITCH_SEMITONES_BITS',
    ],
    'PRESENTATION_JOURNAL_HEADER': [
        'SCHEMA', 'CAPACITY', 'REQUESTED_AFTER', 'WRITE_SEQUENCE',
        'OLDEST_SEQUENCE', 'OVERFLOW_COUNT', 'WRITER_COLLISIONS', 'INVALID_PUBLICATIONS',
        'SEQUENCE_EXHAUSTED', 'COHERENT', 'ENTRY_COUNT', 'ENTRY_WIDTH',
    ],
    'PRESENTATION_JOURNAL_ENTRY': [
        'SEQUENCE', 'GENERATION', 'TOKEN', 'FIRST_FINGERPRINT',
        'LAST_FINGERPRINT', 'CALLBACK_SEQUENCE', 'PCM_FRAMES', 'FIRST_LOOP_REVISION',
        'LAST_LOOP_REVISION', 'FIRST_TIMELINE_FRAME', 'LAST_TIMELINE_FRAME', 'CONTINUITY_VIOLATIONS',
        'RENDER_SOURCE_START_BITS', 'RENDER_SOURCE_END_BITS', 'RENDER_FRAME_COUNT',
        'FIRST_RENDER_FRAME_OFFSET', 'MAPPING_FIRST_RENDER_FRAME_OFFSET', 'ACTUAL_TEMPO_BITS', 'LAST_NEXT_TIMELINE_FRAME', 'CALLBACK_PCM_FRAMES',
    ],
}

def flatten(fields):
    result = []
    for field in fields:
        if isinstance(field, str):
            result.append(field)
        else:
            name, count, members = field
            for index in range(count):
                result.extend(f"{name}_{index}_{member}" for member in members) if members else result.append(f"{name}_{index}")
    assert len(set(result)) == len(result)
    return result

# Append numerical proof without shifting the existing diagnostic fields.
PACKETS['RUNTIME_DIAGNOSTICS'] += ['AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS', 'AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS']

# Wire codes are explicit; generated native assertions detect drift in typed engine enums.
# Control polling carries exact acknowledgements, not counters/history. Shared names bind its
# producer and decoder to the same field contract as the on-demand diagnostic packet.
_control_exact = {"SCHEMA", "STATUS", "REQUESTED_TOKEN", "REQUESTED_GENERATION", "ACCEPTED_TOKEN",
    "ACCEPTED_GENERATION", "COMMITTED_TOKEN", "COMMITTED_GENERATION", "ACCEPTED_FIRST_FINGERPRINT_SEQUENCE"}
_control_prefixes = ("PRESENTATION_", "POSITION_", "AUDIBLE_END_", "AUDIBLE_CONTROL_", "AUTOMATIC_")
PACKETS["CONTROL_PROGRESS"] = [field for field in PACKETS["RUNTIME_DIAGNOSTICS"]
    if isinstance(field, str) and (field in _control_exact or field.startswith(_control_prefixes))]
PACKETS["CONTROL_PROGRESS"] += [("BANK", 2, ["APPLIED_INTENT_REVISION", "ASSIGNED"])]
PACKETS["CONTROL_PROGRESS"] += ["READINESS_" + field for field in (
    "SEQUENCE", "ASSIGNED", "TOKEN", "GENERATION", "CONTROL_REVISION", "CACHE_BOUND", "CACHE_CAN_GROW",
    "COMPLETE_LANE_MASK", "REQUIRED_LANE_MASK", "STORAGE_STATUS", "FIXED_LOOP_HEAD_PREPARED",
    "DIRECT_ATOMIC_POLICY_GENERATION", "FAILURE_LANE", "FAILURE_GENERATION",
    "LOOP_FAILURE_TOKEN", "LOOP_FAILURE_GENERATION", "LOOP_FAILURE_REVISION", "LOOP_FAILURE_REASON")]

ENUMS = {
    'NativeRuntimeLoopPreparationFailureReason': ('stemwise::audio::engine::NativePlaybackLoopPreparationFailureReason', [('NONE', 'None', 0), ('PIPELINE', 'Pipeline', 1), ('LANES', 'Lanes', 2)]),
    'NativeRuntimeBank': ('stemwise::audio::engine::NativePlaybackBank', [('FIRST', 'First', 0), ('SECOND', 'Second', 1)]),
    'NativeRuntimeSourceMode': ('stemwise::audio::MixerSourceMode', [('ORIGINAL', 'Original', 0), ('STEMS', 'Stems', 1)]),
    'NativeRuntimeMetronomeMode': ('stemwise::audio::MetronomeMode', [('OFF', 'Off', 0), ('FREE_BPM', 'FreeBpm', 1), ('BEAT_GRID', 'BeatGrid', 2)]),
    'NativeRuntimeManagedLoopBoundaryMode': ('stemwise::audio::engine::LoopHeadBoundaryMode', [('UNRESOLVED', 'Unresolved', 0), ('ORIGINAL_EOS', 'OriginalEos', 1), ('RETAINED_WINDOW', 'RetainedWindow', 2)]),
    'NativeRuntimeLoopCycleMode': ('stemwise::audio::engine::LoopCycleMode', [('DISABLED', 'Disabled', 0), ('CACHE_ONLY', 'CacheOnly', 1), ('STREAMED', 'Streamed', 2)]),
    'NativeRuntimeLoopCyclePhase': ('stemwise::audio::engine::LoopCyclePhase', [('DISABLED', 'Disabled', 0), ('WAITING_FOR_CACHE', 'WaitingForCache', 1), ('WAITING_FOR_SAFE_WINDOW', 'WaitingForSafeWindow', 2), ('WAITING_FOR_INGRESS', 'WaitingForIngress', 3), ('AWAITING_RENDER_COMMIT', 'AwaitingRenderCommit', 4), ('AWAITING_CALLBACK_PROOF', 'AwaitingCallbackProof', 5), ('ACTIVE', 'Active', 6), ('DEADLINE_FALLBACK', 'DeadlineFallback', 7), ('WAITING_FOR_BOUNDARY', 'WaitingForBoundary', 8)]),
    'NativeRuntimeLoopCycleActivation': ('stemwise::audio::engine::LoopCycleActivation', [('NONE', 'None', 0), ('WARM_CURRENT_PASS', 'WarmCurrentPass', 1), ('COLD_AT_LOOP_START', 'ColdAtLoopStart', 2)]),
    'NativeRuntimeLoopDirectiveAction': ('stemwise::audio::engine::LoopCycleDirectiveAction', [('NONE', 'None', 0), ('CLEAR', 'Clear', 1), ('INSTALL_WARM_CACHE_ONLY', 'InstallWarmCacheOnly', 2), ('INSTALL_WARM_STREAMED', 'InstallWarmStreamed', 3), ('ACTIVATE_COLD_CACHE_ONLY', 'ActivateColdCacheOnly', 4), ('ACTIVATE_COLD_STREAMED', 'ActivateColdStreamed', 5), ('WRAP_CACHE_ONLY', 'WrapCacheOnly', 6), ('WRAP_STREAMED', 'WrapStreamed', 7), ('DEADLINE_FALLBACK', 'DeadlineFallback', 8)]),
    'NativeRuntimeLoopHeadPreparationPhase': ('stemwise::audio::engine::LoopHeadPreparationPhase', [('IDLE', 'Idle', 0), ('STAGING', 'Staging', 1), ('LANE_CONFIGURED', 'LaneConfigured', 2), ('COMMITTED', 'Committed', 3), ('FAILED', 'Failed', 4), ('ABORTED', 'Aborted', 5)]),
    'NativeRuntimeStatus': ('stemwise::audio::engine::NativePlaybackRuntimeStatus', [('CONSTRUCTED', 'Constructed', 0), ('RUNNING', 'Running', 1), ('STOP_REQUESTED', 'StopRequested', 2), ('STOPPED', 'Stopped', 3)]),
    'NativeRuntimeAutomaticSuccessorPhase': ('stemwise::audio::engine::NativePlaybackAutomaticSuccessorPhase', [('IDLE', 'Idle', 0), ('STAGING', 'Staging', 1), ('DRAIN_COMMITTED', 'DrainCommitted', 2), ('PRESENTED_AWAITING_SELECTION', 'PresentedAwaitingSelection', 3), ('APPLIED_PAUSED_AWAITING_SELECTION', 'AppliedPausedAwaitingSelection', 4)]),
    'NativeRuntimeDrainCancelStatus': ('stemwise::audio::NativeAudioDrainCancelStatus', [('NOT_REQUESTED', 'NotRequested', 0), ('CANCELLED_OPEN_STAGE', 'CancelledOpenStage', 1), ('CANCELLATION_REQUESTED', 'CancellationRequested', 2), ('OUTGOING_ACTIVE', 'OutgoingActive', 3), ('SUCCESSOR_ACTIVE', 'SuccessorActive', 4), ('ADOPTION_IN_FLIGHT', 'AdoptionInFlight', 5), ('NO_MATCHING_DRAIN', 'NoMatchingDrain', 6)]),
    'NativeCacheStorageStatus': ('stemwise::audio::engine::CurrentSongCacheStorageStatus', [('NOT_REQUESTED', 'NotRequested', 0), ('ADMITTED', 'Admitted', 1), ('LIMIT_EXCEEDED', 'LimitExceeded', 2), ('INSUFFICIENT_SPACE', 'InsufficientSpace', 3), ('IO_FAILURE', 'IoFailure', 4)]),
    'NativeAudioManualSelectionGateState': ('stemwise::audio::ManualSelectionRenderGateState', [('OPEN', 'Open', 0), ('CLOSED', 'Closed', 1), ('AUTHORIZED', 'Authorized', 2)]),
    'LoopSubmitStatus': ('stemwise::audio::engine::LoopCycleSubmitStatus', [('ACCEPTED', 'Accepted', 0), ('ACCEPTED_DISABLED', 'AcceptedDisabled', 1), ('UNCHANGED', 'Unchanged', 2), ('OWNERSHIP_REBOUND', 'OwnershipRebound', 3), ('STALE_REVISION', 'StaleRevision', 4), ('REVISION_COLLISION', 'RevisionCollision', 5), ('INVALID_INTENT', 'InvalidIntent', 6)]),
    'LoopCacheStatus': ('stemwise::audio::engine::LoopCycleCacheStatus', [('ACCEPTED', 'Accepted', 0), ('ALREADY_ACCEPTED', 'AlreadyAccepted', 1), ('NOT_WAITING', 'NotWaiting', 2), ('STALE_REVISION', 'StaleRevision', 3), ('KEY_MISMATCH', 'KeyMismatch', 4), ('INVALID_SNAPSHOT', 'InvalidSnapshot', 5)]),
    'LoopIngressStatus': ('stemwise::audio::engine::LoopCycleIngressStatus', [('ACCEPTED', 'Accepted', 0), ('STALE_REVISION', 'StaleRevision', 1), ('NO_REQUEST', 'NoRequest', 2), ('UNEXPECTED_REQUEST', 'UnexpectedRequest', 3), ('STALE_OWNER', 'StaleOwner', 4), ('STALE_CYCLE', 'StaleCycle', 5), ('UNEXPECTED_EPOCH', 'UnexpectedEpoch', 6), ('MISSING_REQUIRED_LANES', 'MissingRequiredLanes', 7), ('INVALID_COVERAGE', 'InvalidCoverage', 8)]),
    'LoopPositionStatus': ('stemwise::audio::engine::LoopCyclePositionStatus', [('OBSERVED', 'Observed', 0), ('INGRESS_REQUESTED', 'IngressRequested', 1), ('DIRECTIVE_PUBLISHED', 'DirectivePublished', 2), ('DISABLED', 'Disabled', 3), ('STALE_REVISION', 'StaleRevision', 4), ('STALE_OWNER', 'StaleOwner', 5), ('STALE_CYCLE', 'StaleCycle', 6), ('STALE_OBSERVATION', 'StaleObservation', 7), ('UNEXPECTED_INGRESS_EPOCH', 'UnexpectedIngressEpoch', 8), ('CYCLE_OVERFLOW', 'CycleOverflow', 9)]),
    'LoopBoundaryStatus': ('stemwise::audio::engine::LoopCycleBoundaryStatus', [('DIRECTIVE_PUBLISHED', 'DirectivePublished', 0), ('NOT_ACTIVE', 'NotActive', 1), ('DIRECTIVE_ALREADY_PENDING', 'DirectiveAlreadyPending', 2), ('STALE_REVISION', 'StaleRevision', 3), ('STALE_OWNER', 'StaleOwner', 4), ('STALE_CYCLE', 'StaleCycle', 5), ('STALE_BOUNDARY', 'StaleBoundary', 6), ('UNEXPECTED_INGRESS_EPOCH', 'UnexpectedIngressEpoch', 7), ('INVALID_BOUNDARY', 'InvalidBoundary', 8), ('CYCLE_OVERFLOW', 'CycleOverflow', 9)]),
    'LoopRenderCommitStatus': ('stemwise::audio::engine::LoopCycleRenderCommitStatus', [('ACCEPTED', 'Accepted', 0), ('NO_DIRECTIVE', 'NoDirective', 1), ('STALE_SEQUENCE', 'StaleSequence', 2)]),
    'LoopCallbackStatus': ('stemwise::audio::engine::LoopCycleCallbackStatus', [('ACCEPTED', 'Accepted', 0), ('ALREADY_PROVEN', 'AlreadyProven', 1), ('NO_ACTIVE_LOOP', 'NoActiveLoop', 2), ('STALE_REVISION', 'StaleRevision', 3), ('STALE_OWNER', 'StaleOwner', 4), ('STALE_CYCLE', 'StaleCycle', 5), ('STALE_CALLBACK', 'StaleCallback', 6), ('SUPERSEDED_PHYSICAL_POLICY', 'SupersededPhysicalPolicy', 7)]),
    'LoopOriginalEosStatus': ('stemwise::audio::engine::LoopCycleOriginalEosStatus', [('ACCEPTED', 'Accepted', 0), ('ALREADY_RESOLVED', 'AlreadyResolved', 1), ('NOT_REQUIRED', 'NotRequired', 2), ('NOT_READY', 'NotReady', 3), ('STALE_REVISION', 'StaleRevision', 4), ('STALE_OWNER', 'StaleOwner', 5), ('UNEXPECTED_INGRESS_EPOCH', 'UnexpectedIngressEpoch', 6), ('INVALID_BOUNDARY', 'InvalidBoundary', 7), ('CONFLICTING_BOUNDARY', 'ConflictingBoundary', 8)]),
    'NativeAudioBackend': ('stemwise::audio::NativeAudioBackend', [('UNSPECIFIED', 'Unspecified', 0), ('AUDIO_TRACK', 'AudioTrack', 3)]),
    'NativeAudioStreamState': ('stemwise::audio::NativeAudioStreamState', [('UNINITIALIZED', 'Uninitialized', 0), ('UNKNOWN', 'Unknown', 1), ('OPEN', 'Open', 2), ('STARTED', 'Started', 4), ('PAUSED', 'Paused', 6), ('CLOSED', 'Closed', 12), ('DISCONNECTED', 'Disconnected', 13)]),
}

# Typed Kotlin constructor bindings. @ is the packet index; $ is its default read.
KOTLIN_DIAGNOSTIC_MAPPINGS = [
    {
        "name": 'output-diagnostics', "file": 'NativeAudioDiagnostics.kt', "constructor": 'NativeAudioDiagnostics',
        "packet": 'OUTPUT_DIAGNOSTICS', "read": 'fields[@]', "indent": '            ',
        "fields": [
            ('streamGeneration', 'STREAM_GENERATION'),
            ('streamState', 'STREAM_STATE', 'NativeAudioStreamState.fromRaw($.nativePacketInt())'),
            ('backend', 'BACKEND', 'NativeAudioBackend.fromRaw($.nativePacketInt())'),
            ('sampleRateHz', 'SAMPLE_RATE_HZ', '$.nativePacketInt()'),
            ('channelCount', 'CHANNEL_COUNT', '$.nativePacketInt()'),
            ('framesPerBurst', 'FRAMES_PER_BURST', '$.nativePacketInt()'),
            ('outputBufferCapacityFrames', 'OUTPUT_BUFFER_CAPACITY_FRAMES', '$.nativePacketInt()'),
            ('outputBufferSizeFrames', 'OUTPUT_BUFFER_SIZE_FRAMES', '$.nativePacketInt()'),
            ('ringCapacityFrames', 'RING_CAPACITY_FRAMES', '$.nativePacketInt()'),
            ('queuedFrames', 'QUEUED_FRAMES'),
            ('totalFramesAccepted', 'TOTAL_FRAMES_ACCEPTED'),
            ('renderedFrames', 'RENDERED_FRAMES'),
            ('callbackCount', 'CALLBACK_COUNT'),
            ('callbackFrames', 'CALLBACK_FRAMES'),
            ('xRunCount', 'X_RUN_COUNT', '$.nativePacketInt()'),
            ('underrunCallbackCount', 'UNDERRUN_CALLBACK_COUNT'),
            ('underrunFrames', 'UNDERRUN_FRAMES'),
            ('staleFramesRejected', 'STALE_FRAMES_REJECTED'),
            ('producerBackpressureFrames', 'PRODUCER_BACKPRESSURE_FRAMES'),
            ('appliedGeneration', 'APPLIED_GENERATION'),
            ('lastPresentedGeneration', 'LAST_PRESENTED_GENERATION'),
            ('playing', 'PLAYING', '$.nativePacketBoolean()'),
            ('recoveryRequired', 'RECOVERY_REQUIRED', '$.nativePacketBoolean()'),
            ('lastErrorCode', 'LAST_ERROR_CODE', '$.nativePacketInt()'),
            ('reopenCount', 'REOPEN_COUNT'),
            ('lastErrorMonotonicMillis', 'LAST_ERROR_MONOTONIC_MILLIS'),
            ('callbackDeadlineMissCount', 'CALLBACK_DEADLINE_MISS_COUNT'),
            ('maxCallbackDurationMicros', 'MAX_CALLBACK_DURATION_MICROS'),
            ('performanceMode', 'PERFORMANCE_MODE', '$.nativePacketInt()'),
            ('lastPresentedSelectionToken', 'LAST_PRESENTED_SELECTION_TOKEN'),
            ('lastPresentedFingerprintSequence', 'LAST_PRESENTED_FINGERPRINT_SEQUENCE'),
            ('lastPresentedTimelineFrame', 'LAST_PRESENTED_TIMELINE_FRAME'),
            ('presentedOwnerSequence', 'PRESENTED_OWNER_SEQUENCE'),
            ('lastOwnerFirstOutputFrame', 'LAST_OWNER_FIRST_OUTPUT_FRAME'),
            ('lastOwnerFirstCallbackSequence', 'LAST_OWNER_FIRST_CALLBACK_SEQUENCE'),
            ('lastOwnerFirstPcmFrames', 'LAST_OWNER_FIRST_PCM_FRAMES', '$.nativePacketInt()'),
            ('invalidOwnershipTagFrames', 'INVALID_OWNERSHIP_TAG_FRAMES'),
            ('processConstructionCount', 'PROCESS_CONSTRUCTION_COUNT', '$.also { require(it >= 0L) }'),
            ('processLiveInstanceCount', 'PROCESS_LIVE_INSTANCE_COUNT', '$.also { require(it >= 0L) }'),
            ('effectiveRenderPlaying', 'EFFECTIVE_RENDER_PLAYING', '$.nativePacketBoolean()'),
            ('manualSelectionGateState', 'MANUAL_SELECTION_GATE_STATE', 'NativeAudioManualSelectionGateState.fromRaw($.nativePacketInt())'),
            ('manualSelectionAuthorizedGeneration', 'MANUAL_SELECTION_AUTHORIZED_GENERATION', '$.also { require(it >= 0L) }'),
            ('manualSelectionGateArmCount', 'MANUAL_SELECTION_GATE_ARM_COUNT', '$.also { require(it >= 0L) }'),
            ('manualSelectionGateAuthorizationCount', 'MANUAL_SELECTION_GATE_AUTHORIZATION_COUNT', '$.also { require(it >= 0L) }'),
            ('manualSelectionGateRejectedAuthorizationCount', 'MANUAL_SELECTION_GATE_REJECTED_AUTHORIZATION_COUNT', '$.also { require(it >= 0L) }'),
            ('manualSelectionGateOpenCount', 'MANUAL_SELECTION_GATE_OPEN_COUNT', '$.also { require(it >= 0L) }'),
            ('manualSelectionGateLastArmCallbackSequence', 'MANUAL_SELECTION_GATE_LAST_ARM_CALLBACK_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('pcmLevelSequence', 'PCM_LEVEL_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('pcmLevelGeneration', 'PCM_LEVEL_GENERATION', '$.also { require(it >= 0L) }'),
            ('pcmLevelSelectionToken', 'PCM_LEVEL_SELECTION_TOKEN', '$.also { require(it >= 0L) }'),
            ('pcmLevelFingerprintSequence', 'PCM_LEVEL_FINGERPRINT_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('pcmLevelCallbackSequence', 'PCM_LEVEL_CALLBACK_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('pcmLevelFrames', 'PCM_LEVEL_FRAMES', '$.also { require(it >= 0L) }'),
            ('pcmLevelMeanSquare', 'PCM_LEVEL_MEAN_SQUARE', '$.nativePacketFloat().also { require(it.isFinite() && it >= 0f) }'),
            ('pcmLevelPeak', 'PCM_LEVEL_PEAK', '$.nativePacketFloat().also { require(it.isFinite() && it >= 0f) }'),
            ('processInvalidOwnershipTagFrames', 'PROCESS_INVALID_OWNERSHIP_TAG_FRAMES', '$.also { require(it >= 0L) }'),
            ('processUnderrunCallbackCount', 'PROCESS_UNDERRUN_CALLBACK_COUNT', '$.also { require(it >= 0L) }'),
            ('processUnderrunFrames', 'PROCESS_UNDERRUN_FRAMES', '$.also { require(it >= 0L) }'),
            ('processXRunCount', 'PROCESS_XRUN_COUNT', '$.also { require(it >= 0L) }'),
            ('processCallbackDeadlineMissCount', 'PROCESS_CALLBACK_DEADLINE_MISS_COUNT', '$.also { require(it >= 0L) }'),
            ('callbackStartGapMicros', 'CALLBACK_START_GAP_MICROS', '$.also { require(it >= 0L) }'),
            ('maximumCallbackStartGapMicros', 'MAXIMUM_CALLBACK_START_GAP_MICROS', '$.also { require(it >= 0L) }'),
            ('physicalRunSequence', 'PHYSICAL_RUN_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunPhysicalRunSequence', 'LAST_UNDERRUN_PHYSICAL_RUN_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('processIncompleteStreamObservationCount', 'PROCESS_INCOMPLETE_STREAM_OBSERVATION_COUNT', '$.also { require(it >= 0L) }'),
            ('lastCallbackDeadlineMissStartMicros', 'LAST_CALLBACK_DEADLINE_MISS_START_MICROS'),
            ('lastCallbackDeadlineMissEndMicros', 'LAST_CALLBACK_DEADLINE_MISS_END_MICROS'),
            ('lastUnderrunSequence', 'LAST_UNDERRUN_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunGenerationAtRenderDecision', 'LAST_UNDERRUN_GENERATION_AT_RENDER_DECISION', '$.also { require(it >= 0L) }'),
            ('lastUnderrunGenerationAtCompletion', 'LAST_UNDERRUN_GENERATION_AT_COMPLETION', '$.also { require(it >= 0L) }'),
            ('lastUnderrunCallbackSequence', 'LAST_UNDERRUN_CALLBACK_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunRequestedFrames', 'LAST_UNDERRUN_REQUESTED_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunQueuedFramesAtRenderDecision', 'LAST_UNDERRUN_QUEUED_FRAMES_AT_RENDER_DECISION', '$.also { require(it >= 0L) }'),
            ('lastUnderrunPhysicalFrames', 'LAST_UNDERRUN_PHYSICAL_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunValidPcmFrames', 'LAST_UNDERRUN_VALID_PCM_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunMissingFrames', 'LAST_UNDERRUN_MISSING_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunCallbackStartGapMicros', 'LAST_UNDERRUN_CALLBACK_START_GAP_MICROS', '$.also { require(it >= 0L) }'),
            ('lastUnderrunLatestLateCallbackStartGapMicros', 'LAST_UNDERRUN_LATEST_LATE_CALLBACK_START_GAP_MICROS', '$.also { require(it >= 0L) }'),
            ('lastUnderrunLatestLateCallbackSequence', 'LAST_UNDERRUN_LATEST_LATE_CALLBACK_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunManualArmCallbackCeiling', 'LAST_UNDERRUN_MANUAL_ARM_CALLBACK_CEILING', '$.also { require(it >= 0L) }'),
            ('lastUnderrunAppliedCommitSequence', 'LAST_UNDERRUN_APPLIED_COMMIT_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunRoleSwapAtCallbackStart', 'UNDERRUN_EVIDENCE_ROLE_SWAP_AT_CALLBACK_START', 'boolean(@, "underrun callback-start swap")'),
            ('lastUnderrunRoleSwapDuringDrain', 'UNDERRUN_EVIDENCE_ROLE_SWAP_DURING_DRAIN', 'boolean(@, "underrun drain swap")'),
            ('lastUnderrunWasManualArmBoundary', 'UNDERRUN_EVIDENCE_MANUAL_ARM_BOUNDARY', 'boolean(@, "manual-arm boundary underrun")'),
            ('processManualArmBoundaryUnderrunCallbackCount', 'PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_CALLBACK_COUNT', '$.also { require(it >= 0L) }'),
            ('processManualArmBoundaryUnderrunFrames', 'PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_FRAMES', '$.also { require(it >= 0L) }'),
            ('successfulEnqueueSequence', 'SUCCESSFUL_ENQUEUE_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastSuccessfulEnqueueMonotonicMicros', 'LAST_SUCCESSFUL_ENQUEUE_MONOTONIC_MICROS', '$.also { require(it >= 0L) }'),
            ('lastSuccessfulEnqueueGeneration', 'LAST_SUCCESSFUL_ENQUEUE_GENERATION', '$.also { require(it >= 0L) }'),
            ('lastSuccessfulEnqueueAcceptedFrames', 'LAST_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastSuccessfulEnqueueQueueAfterFrames', 'LAST_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES', '$.also { require(it >= 0L) }'),
            ('maximumSuccessfulEnqueueGapMicros', 'MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS', '$.also { require(it >= 0L) }'),
            ('lastUnderrunMonotonicMicros', 'LAST_UNDERRUN_MONOTONIC_MICROS', '$.also { require(it >= 0L) }'),
            ('lastUnderrunSuccessfulEnqueueSnapshotCoherent', 'UNDERRUN_EVIDENCE_SUCCESSFUL_ENQUEUE_SNAPSHOT_COHERENT', 'boolean(@, "underrun successful-enqueue snapshot coherence")'),
            ('lastUnderrunSuccessfulEnqueueSequence', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_SEQUENCE', '$.also { require(it >= 0L) }'),
            ('lastUnderrunSuccessfulEnqueueGeneration', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_GENERATION', '$.also { require(it >= 0L) }'),
            ('lastUnderrunSuccessfulEnqueueAgeMicros', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_AGE_MICROS', '$.also { require(it >= 0L) }'),
            ('lastUnderrunSuccessfulEnqueueAcceptedFrames', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunSuccessfulEnqueueQueueAfterFrames', 'LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES', '$.also { require(it >= 0L) }'),
            ('lastUnderrunMaximumSuccessfulEnqueueGapMicros', 'LAST_UNDERRUN_MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS', '$.also { require(it >= 0L) }'),
        ],
    },
    {
        "name": 'callback-mix', "file": 'NativeAudioDiagnostics.kt', "constructor": 'NativeAudioCallbackMixDiagnostics',
        "packet": 'OUTPUT_DIAGNOSTICS', "read": 'fields[@]', "indent": '                ',
        "fields": [
            ('sequence', 'CALLBACK_MIX_SEQUENCE'),
            ('callbackSequence', 'CALLBACK_MIX_CALLBACK_SEQUENCE'),
            ('generation', 'CALLBACK_MIX_GENERATION'),
            ('selectionToken', 'CALLBACK_MIX_SELECTION_TOKEN'),
            ('controlRevision', 'CALLBACK_MIX_CONTROL_REVISION'),
            ('missingControlFrames', 'CALLBACK_MIX_MISSING_CONTROL_FRAMES'),
            ('staleControlFrames', 'CALLBACK_MIX_STALE_CONTROL_FRAMES'),
            ('mixerRejectedFrames', 'CALLBACK_MIX_MIXER_REJECTED_FRAMES'),
            ('invalidTagFrames', 'CALLBACK_MIX_INVALID_TAG_FRAMES'),
            ('advanceFailures', 'CALLBACK_MIX_ADVANCE_FAILURES'),
            ('safeOriginalFallbackFrames', 'CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES'),
            ('mixerNonFiniteSamplesScrubbed', 'CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED'),
            ('mixerLimiterActiveFrames', 'CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES'),
            ('mixerLimiterSafetyClamps', 'CALLBACK_MIX_MIXER_LIMITER_SAFETY_CLAMPS'),
            ('finalNonFiniteSamplesScrubbed', 'CALLBACK_MIX_FINAL_NON_FINITE_SAMPLES_SCRUBBED'),
            ('finalSafetyClamps', 'CALLBACK_MIX_FINAL_SAFETY_CLAMPS'),
        ],
    },
    {
        "name": 'output-loop', "file": 'NativeAudioDiagnostics.kt', "constructor": 'NativeAudioLoopDiagnostics',
        "packet": 'OUTPUT_DIAGNOSTICS', "read": 'fields[@]', "indent": '                ',
        "fields": [
            ('acceptedDemandCount', 'ACCEPTED_DEMAND_COUNT'),
            ('acceptedCaptureCount', 'ACCEPTED_CAPTURE_COUNT'),
            ('latestDemandRevision', 'LATEST_DEMAND_REVISION'),
            ('latestDemandAFrame', 'LATEST_DEMAND_AFRAME'),
            ('latestDemandBFrameExclusive', 'LATEST_DEMAND_BFRAME_EXCLUSIVE'),
            ('latestDemandPresentedFrame', 'LATEST_DEMAND_PRESENTED_FRAME'),
            ('latestDemandBoundaryArmed', 'LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_ARMED', 'boolean(@, "loop demand armed")'),
            ('latestDemandBoundaryCrossed', 'LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_CROSSED', 'boolean(@, "loop demand crossed")'),
            ('latestCaptureRevision', 'LATEST_CAPTURE_REVISION'),
            ('latestCaptureAFrame', 'LATEST_CAPTURE_AFRAME'),
            ('latestCaptureBFrameExclusive', 'LATEST_CAPTURE_BFRAME_EXCLUSIVE'),
            ('latestCapturePresentedFrame', 'LATEST_CAPTURE_PRESENTED_FRAME'),
            ('successfulCaptureCutoverCount', 'SUCCESSFUL_CAPTURE_CUTOVER_COUNT'),
            ('latestCutoverCaptureRevision', 'LATEST_CUTOVER_CAPTURE_REVISION'),
            ('latestCutoverCallbackSequence', 'LATEST_CUTOVER_CALLBACK_SEQUENCE'),
            ('latestCutoverInputLatencyMicros', 'LATEST_CUTOVER_INPUT_LATENCY_MICROS'),
            ('processBoundaryMissCount', 'PROCESS_BOUNDARY_MISS_COUNT'),
            ('latestMissCaptureRevision', 'LATEST_MISS_CAPTURE_REVISION'),
            ('latestMissCallbackSequence', 'LATEST_MISS_CALLBACK_SEQUENCE'),
            ('latestMissPresentedFrame', 'LATEST_MISS_PRESENTED_FRAME'),
            ('latestDemandGestureId', 'LATEST_DEMAND_GESTURE_ID'),
            ('latestCaptureGestureId', 'LATEST_CAPTURE_GESTURE_ID'),
            ('latestCaptureInferred', 'LOOP_DEMAND_LATEST_CAPTURE_INFERRED', 'boolean(@, "loop capture inferred")'),
            ('latestCutoverGeneration', 'LATEST_CUTOVER_GENERATION'),
            ('latestCutoverSelectionToken', 'LATEST_CUTOVER_SELECTION_TOKEN'),
            ('latestCutoverAFrame', 'LATEST_CUTOVER_AFRAME'),
            ('latestCutoverGestureId', 'LATEST_CUTOVER_GESTURE_ID'),
        ],
    },
    {
        "name": 'loop-counters', "file": 'NativePlaybackRuntimeCodecs.kt', "constructor": 'NativeRuntimeLoopProtocolCounters',
        "packet": 'LOOP_PROTOCOL', "read": 'values[@]', "indent": '            ',
        "fields": [
            ('acceptedIntents', 'ACCEPTED_INTENTS', 'nonNegative(@, "accepted loop intents")'),
            ('unchangedIntents', 'UNCHANGED_INTENTS', 'nonNegative(@, "unchanged loop intents")'),
            ('ownershipRebinds', 'OWNERSHIP_REBINDS', 'nonNegative(@, "loop ownership rebinds")'),
            ('staleIntents', 'STALE_INTENTS', 'nonNegative(@, "stale loop intents")'),
            ('revisionCollisions', 'REVISION_COLLISIONS', 'nonNegative(@, "loop revision collisions")'),
            ('cachesAccepted', 'CACHES_ACCEPTED', 'nonNegative(@, "accepted loop caches")'),
            ('cacheRejections', 'CACHE_REJECTIONS', 'nonNegative(@, "loop cache rejections")'),
            ('ingressRequests', 'INGRESS_REQUESTS', 'nonNegative(@, "loop ingress requests")'),
            ('ingressAcks', 'INGRESS_ACKS', 'nonNegative(@, "loop ingress acknowledgements")'),
            ('ingressAckRejections', 'INGRESS_ACK_REJECTIONS', 'nonNegative(@, "loop ingress acknowledgement rejections")'),
            ('positionObservations', 'POSITION_OBSERVATIONS', 'nonNegative(@, "loop position observations")'),
            ('rejectedPositionObservations', 'REJECTED_POSITION_OBSERVATIONS', 'nonNegative(@, "rejected loop positions")'),
            ('boundaryEvents', 'BOUNDARY_EVENTS', 'nonNegative(@, "loop boundary events")'),
            ('rejectedBoundaryEvents', 'REJECTED_BOUNDARY_EVENTS', 'nonNegative(@, "rejected loop boundaries")'),
            ('renderDirectives', 'RENDER_DIRECTIVES', 'nonNegative(@, "loop render directives")'),
            ('renderCommits', 'RENDER_COMMITS', 'nonNegative(@, "loop render commits")'),
            ('cacheOnlyWraps', 'CACHE_ONLY_WRAPS', 'nonNegative(@, "cache-only wraps")'),
            ('streamedWraps', 'STREAMED_WRAPS', 'nonNegative(@, "streamed wraps")'),
            ('deadlineFallbacks', 'DEADLINE_FALLBACKS', 'nonNegative(@, "loop deadline fallbacks")'),
            ('callbackProofs', 'CALLBACK_PROOFS', 'nonNegative(@, "loop callback proofs")'),
            ('rejectedCallbackProofs', 'REJECTED_CALLBACK_PROOFS', 'nonNegative(@, "rejected loop callback proofs")'),
            ('originalEosAccepted', 'ORIGINAL_EOS_ACCEPTED', 'nonNegative(@, "accepted Original EOS boundaries")'),
            ('originalEosRejected', 'ORIGINAL_EOS_REJECTED', 'nonNegative(@, "rejected Original EOS boundaries")'),
        ],
    },
    {
        "name": 'runtime-counters', "file": 'NativePlaybackRuntimeCodecs.kt', "constructor": 'NativeRuntimeCounters',
        "packet": 'RUNTIME_DIAGNOSTICS', "read": 'counter(@)', "indent": '                ',
        "fields": [
            ('commandSnapshotsPublished', 'COMMAND_SNAPSHOTS_PUBLISHED'),
            ('commandSnapshotsConsumed', 'COMMAND_SNAPSHOTS_CONSUMED'),
            ('commandSnapshotsSuperseded', 'COMMAND_SNAPSHOTS_SUPERSEDED'),
            ('rejectedCommandSnapshots', 'REJECTED_COMMAND_SNAPSHOTS'),
            ('beatGridUpdatesPublished', 'BEAT_GRID_UPDATES_PUBLISHED'),
            ('rejectedBeatGridUpdates', 'REJECTED_BEAT_GRID_UPDATES'),
            ('renderCycles', 'RENDER_CYCLES'),
            ('idleSleeps', 'IDLE_SLEEPS'),
            ('outputFlushes', 'OUTPUT_FLUSHES'),
            ('outputFlushFailures', 'OUTPUT_FLUSH_FAILURES'),
            ('outputHandoffBegins', 'OUTPUT_HANDOFF_BEGINS'),
            ('outputHandoffBeginRetries', 'OUTPUT_HANDOFF_BEGIN_RETRIES'),
            ('outputHandoffCommits', 'OUTPUT_HANDOFF_COMMITS'),
            ('outputHandoffCommitRetries', 'OUTPUT_HANDOFF_COMMIT_RETRIES'),
            ('outputBlocksRendered', 'OUTPUT_BLOCKS_RENDERED'),
            ('outputFramesRendered', 'OUTPUT_FRAMES_RENDERED'),
            ('outputFramesAccepted', 'OUTPUT_FRAMES_ACCEPTED'),
            ('outputPartialAccepts', 'OUTPUT_PARTIAL_ACCEPTS'),
            ('outputBackpressureEvents', 'OUTPUT_BACKPRESSURE_EVENTS'),
            ('outputInvalidRenderResults', 'OUTPUT_INVALID_RENDER_RESULTS'),
            ('outputNeedDataEvents', 'OUTPUT_NEED_DATA_EVENTS'),
            ('outputEndOfStreamEvents', 'OUTPUT_END_OF_STREAM_EVENTS'),
            ('targetQueuedFrames', 'TARGET_QUEUED_FRAMES'),
            ('lastOutputFingerprintSequence', 'LAST_OUTPUT_FINGERPRINT_SEQUENCE'),
            ('lastRenderPayloadHash', 'LAST_RENDER_PAYLOAD_HASH', 'values[@]'),
            ('outputFlushRetries', 'OUTPUT_FLUSH_RETRIES'),
            ('resourceOperationsCompleted', 'RESOURCE_OPERATIONS_COMPLETED'),
            ('resourceLastOperationMicros', 'RESOURCE_LAST_OPERATION_MICROS'),
            ('resourceMaximumOperationMicros', 'RESOURCE_MAXIMUM_OPERATION_MICROS'),
            ('resourceMaximumQueueAgeMicros', 'RESOURCE_MAXIMUM_QUEUE_AGE_MICROS'),
            ('refillDeadlineMisses', 'REFILL_DEADLINE_MISSES'),
        ],
    },
]

def kotlin_diagnostic_mapping_projections(values):
    outputs = {}
    for mapping in KOTLIN_DIAGNOSTIC_MAPPINGS:
        path = ROOT / "audio-engine/src/main/java/app/stemwise/playback/engine" / mapping["file"]
        source = outputs[path] if path in outputs else path.read_text(encoding="utf-8")
        start_marker = mapping["indent"] + "// BEGIN GENERATED " + mapping["name"] + "\n"
        end_marker = mapping["indent"] + "// END GENERATED " + mapping["name"] + "\n"
        if source.count(start_marker) != 1 or source.count(end_marker) != 1:
            raise ValueError(f"Missing or ambiguous Kotlin mapping boundary: {mapping['name']}")
        start = source.index(start_marker) + len(start_marker)
        end = source.index(end_marker)
        if end < start or mapping["constructor"] + "(" not in source[:start]:
            raise ValueError(f"Missing typed Kotlin mapping owner: {mapping['name']}")
        properties = set()
        bindings = []
        for binding in mapping["fields"]:
            property_name, field = binding[:2]
            if property_name in properties:
                raise ValueError(f"Duplicate Kotlin mapping property: {property_name}")
            properties.add(property_name)
            constant = mapping["packet"] + "_" + field
            if constant not in values:
                raise ValueError(f"Unknown Kotlin mapping packet field: {constant}")
            index = "NativePacketSchema." + constant
            read = mapping["read"].replace("@", index)
            expression = (binding[2] if len(binding) == 3 else "$").replace("$", read).replace("@", index)
            bindings.append(mapping["indent"] + property_name + " = " + expression + ",\n")
        outputs[path] = source[:start] + "".join(bindings) + source[end:]
    return outputs

def enum_prefix(name):
    return "ENUM_" + re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name).upper()

def projections():
    values = {"COMMAND_VERSION": 12, "COMMAND_LONG_COUNT": len(COMMAND_LONG),
        "COMMAND_FLOAT_COUNT": len(COMMAND_FLOAT), "PACKED_RESULT_STATUS_SHIFT": 32, "PRESENTATION_JOURNAL_VERSION": 4,
        "PRESENTATION_JOURNAL_HEADER": len(flatten(PACKETS["PRESENTATION_JOURNAL_HEADER"])),
        "PRESENTATION_JOURNAL_ENTRY": len(flatten(PACKETS["PRESENTATION_JOURNAL_ENTRY"]))}
    values.update(TRACK_BLOCK_FRAMES=512, TRACK_MAXIMUM_BUFFER_FRAMES=4_800, TRACK_CHANNELS=2, TRACK_SAMPLE_RATE=48_000)
    for name in ("TRACK_CONTROL", "TRACK_METADATA", "TRACK_CONFIGURATION", "TRACK_ACTUAL", "TRACK_POSITION"):
        values[name + "_COUNT"] = len(PACKETS[name])
    for name, version in VERSIONS.items():
        values[name + "_VERSION"] = version
        values[name + "_COUNT"] = len(flatten(PACKETS[name]))
    for name, fields in [("COMMAND_LONG", COMMAND_LONG), ("COMMAND_FLOAT", COMMAND_FLOAT),
                         *((name, flatten(fields)) for name, fields in PACKETS.items())]:
        assert len(set(fields)) == len(fields)
        values.update((name + "_" + field, index) for index, field in enumerate(fields))
    for name, fields in PACKETS.items():
        base = 0
        for field in fields:
            if isinstance(field, str):
                base += 1
                continue
            label, count, members = field
            prefix = name + "_" + label
            values[prefix + "_BASE"] = base
            values[prefix + "_COUNT"] = count
            values[prefix + "_STRIDE"] = len(members or [0])
            values.update((prefix + "_FIELD_" + member, offset) for offset, member in enumerate(members or []))
            base += count * len(members or [0])
    for name, (_, members) in ENUMS.items():
        values.update((enum_prefix(name) + "_" + member, code) for member, _, code in members)
    kotlin = "package app.stemwise.playback.engine\n\n// Generated by tools/native_packet_schema.py.\ninternal object NativePacketSchema {\n"
    native = "#pragma once\n#include <cstddef>\n#include <cstdint>\n#include <array>\n// Generated by tools/native_packet_schema.py.\nnamespace stemwise::audio::packet {\n"
    for key, value in values.items():
        is_version = key.endswith("_VERSION")
        kotlin += f"    const val {key} = {value}{'L' if is_version else ''}\n"
        native += f"inline constexpr std::size_t {key} = {value}U;\n"
    progress_fields = flatten(PACKETS["CONTROL_PROGRESS"])
    runtime_to_progress = [progress_fields.index(field) if field in progress_fields else -1
        for field in flatten(PACKETS["RUNTIME_DIAGNOSTICS"])]
    kotlin += "    val CONTROL_PROGRESS_RUNTIME_INDEX = intArrayOf(" + ", ".join(map(str, runtime_to_progress)) + ")\n"
    for name, (_, members) in ENUMS.items():
        kotlin += f"    fun is{name}(code: Long): Boolean = when (code) {{\n"
        kotlin += "        " + ", ".join(f"{enum_prefix(name)}_{member}.toLong()" for member, _, _ in members) + " -> true\n        else -> false\n    }\n"
    checks = '#pragma once\n// Generated by tools/native_packet_schema.py.\n#include "NativePacketSchema.h"\n#include "NativeAudioBackend.h"\n#include "native_engine/runtime/NativePlaybackRuntime.h"\n#include "ManualSelectionRenderGate.h"\nnamespace stemwise::audio::packet {\n'
    for name, (cpp, members) in ENUMS.items():
        for member, native_member, _ in members:
            checks += f"static_assert(static_cast<std::size_t>({cpp}::{native_member}) == {enum_prefix(name)}_{member});\n"
    checks += "static_assert(static_cast<std::size_t>(stemwise::audio::NativeAudioSourceMode::Original) == ENUM_NATIVE_RUNTIME_SOURCE_MODE_ORIGINAL);\n"
    checks += "static_assert(static_cast<std::size_t>(stemwise::audio::NativeAudioSourceMode::Stems) == ENUM_NATIVE_RUNTIME_SOURCE_MODE_STEMS);\n"
    checks += "}\n"
    # Named fields keep native capture code independent from serialized order.
    for name, fields in PACKETS.items():
        cpp_name = "".join(part.title() for part in name.split("_"))
        for field in fields:
            if isinstance(field, str) or field[2] is None:
                continue
            label, _, members = field
            native += f"template<typename Value> struct {cpp_name}{label.title()}Fields {{\n"
            native += "".join(f"    Value {member}{{}};\n" for member in members) + "};\n"
        native += f"template<typename Value> struct {cpp_name}Fields {{\n"
        for field in fields:
            if isinstance(field, str):
                native += f"    Value {field}{{}};\n"
            else:
                label, count, members = field
                item_type = f"{cpp_name}{label.title()}Fields<Value>" if members else "Value"
                native += f"    std::array<{item_type}, {count}U> {label}{{}};\n"
        native += "};\n"
        native += f"template<typename Value> constexpr std::array<Value, {len(flatten(fields))}U> pack{cpp_name}(const {cpp_name}Fields<Value> &fields) noexcept {{\n"
        native += f"    std::array<Value, {len(flatten(fields))}U> result{{}};\n"
        for field in fields:
            if isinstance(field, str):
                native += f"    result[{name}_{field}] = fields.{field};\n"
            else:
                label, count, members = field
                native += f"    for (std::size_t index = 0U; index < {name}_{label}_COUNT; ++index) {{\n"
                for offset, member in enumerate(members or [None]):
                    source = f"fields.{label}[index]" + (f".{member}" if member else "")
                    native += f"        result[{name}_{label}_BASE + index * {name}_{label}_STRIDE + {offset}U] = {source};\n"
                native += "    }\n"
        native += "    return result;\n}\n"
    return {
        **kotlin_diagnostic_mapping_projections(values),
        ROOT / "audio-engine/src/main/cpp/NativePacketEnumChecks.h": checks,
        ROOT / "audio-engine/src/main/java/app/stemwise/playback/engine/NativePacketSchema.kt": kotlin + "}\n",
        ROOT / "audio-engine/src/main/cpp/NativePacketSchema.h": native + "}\n",
        ROOT / "audio-engine/src/main/cpp/StemRoleLimits.h": (
            "#pragma once\n// Generated by tools/native_packet_schema.py from contracts/audio/playback-capacity.json.\n"
            "#include <cstddef>\nnamespace stemwise::audio {\n"
            f"inline constexpr std::size_t kStemwiseMaximumTargets = {MAX_TARGETS}U;\n"
            "inline constexpr std::size_t kStemwiseMaximumParts = kStemwiseMaximumTargets + 1U;\n}\n"),
    }

if __name__ == "__main__":
    check = sys.argv[1:] == ["--check"]
    if sys.argv[1:] not in ([], ["--check"]):
        raise SystemExit("usage: native_packet_schema.py [--check]")
    for path, text in projections().items():
        if check:
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                raise SystemExit(f"Stale native packet projection: {path}")
        else:
            path.write_text(text, encoding="utf-8", newline="\n")
