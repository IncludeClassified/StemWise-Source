#include "NativePacketSchema.h"
#include "NativePacketEnumChecks.h"
#include "PresentationSpanJournal.h"
#include "native_engine/runtime/NativePlaybackCommandDecoder.h"
#include <algorithm>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
namespace packet = stemwise::audio::packet;
using Value = std::int64_t;
std::map<std::string, std::vector<Value>> goldens;
template<std::size_t Count> void check(const char *name, const std::array<Value, Count> &actual) {
    const auto &expected = goldens.at(name);
    if (expected.size() != actual.size() || !std::equal(actual.begin(), actual.end(), expected.begin()))
        throw std::runtime_error(std::string("native packet known-answer mismatch: ") + name);
}
void currentKotlinCommandPacketReachesTheActualJniDecoder() {
    using namespace stemwise::audio::engine;
    std::array<Value, packet::COMMAND_LONG_COUNT> longs{};
    std::array<float, packet::COMMAND_FLOAT_COUNT> floats{};
    const auto &longFixture = goldens.at("COMMAND_LONG");
    const auto &floatFixture = goldens.at("COMMAND_FLOAT_BITS");
    if (longFixture.size() != longs.size() || floatFixture.size() != floats.size()) throw std::runtime_error("command fixture width");
    std::copy(longFixture.begin(), longFixture.end(), longs.begin());
    for (std::size_t index = 0U; index < floats.size(); ++index) floats[index] = std::bit_cast<float>(static_cast<std::uint32_t>(floatFixture[index]));
    NativePlaybackCommandSnapshot decoded{};
    if (!command_codec::decodeCommand(longs, floats, decoded)) throw std::runtime_error("current selected Original command rejected by actual JNI decoder");
    if (!decoded.selectionAssigned || decoded.playing || decoded.selectedOwnership.selectionToken != 31U ||
        decoded.banks[0].cacheFrameUpperBound != 43'200'000U || decoded.banks[0].playbackEndFrameExclusive != 90'001U || decoded.automaticSuccessor.enabled)
        throw std::runtime_error("selected command decoded wrong owner/cache/successor");
    longs[packet::COMMAND_LONG_BANK_0_PLAYBACK_END] = 90'001;
    if (!command_codec::decodeCommand(longs, floats, decoded) || decoded.banks[0].playbackEndFrameExclusive != 90'001U)
        throw std::runtime_error("playback window lost by actual JNI decoder");
    for (const Value malformedEnd : {-1LL, 240LL}) {
        auto malformed = longs;
        malformed[packet::COMMAND_LONG_BANK_0_PLAYBACK_END] = malformedEnd;
        if (command_codec::decodeCommand(malformed, floats, decoded)) throw std::runtime_error("invalid playback window accepted");
    }
    longs[packet::COMMAND_LONG_BANK_0_PLAYBACK_END] = 0;
    for (auto field = packet::COMMAND_LONG_SUCCESSOR_REVISION; field <= packet::COMMAND_LONG_SUCCESSOR_INHERIT; ++field) {
        auto malformed = longs; malformed[field] = 1;
        if (command_codec::decodeCommand(malformed, floats, decoded)) throw std::runtime_error("absent successor accepted a nonzero reserved field");
    }
    longs[packet::COMMAND_LONG_BANK_0_STEM_COUNT] = 9;
    longs[packet::COMMAND_LONG_BANK_0_STEM_HASH] = 33;
    longs[packet::COMMAND_LONG_SOURCE_MODE] = 1;
    if (!command_codec::decodeCommand(longs, floats, decoded) || decoded.banks[0].stemLaneCount != 9U)
        throw std::runtime_error("maximum-part command rejected by actual JNI decoder");
    longs[packet::COMMAND_LONG_PLAYING] = 1;
    longs[packet::COMMAND_LONG_BANK_1_ASSIGNED] = 1;
    longs[packet::COMMAND_LONG_BANK_1_TOKEN] = 32;
    longs[packet::COMMAND_LONG_BANK_1_GENERATION] = 42;
    longs[packet::COMMAND_LONG_BANK_1_SONG_HASH] = 71;
    longs[packet::COMMAND_LONG_BANK_1_ORIGINAL_HASH] = 72;
    const std::array<Value, 8> successor{1, 8, 31, 41, 1, 32, 42, 0};
    std::copy(successor.begin(), successor.end(), longs.begin() + packet::COMMAND_LONG_SUCCESSOR_PRESENT);
    if (!command_codec::decodeCommand(longs, floats, decoded) || !decoded.automaticSuccessor.enabled ||
        decoded.automaticSuccessor.successorOwnership.generation != 42U) throw std::runtime_error("exact successor command failed");
    longs[packet::COMMAND_LONG_SUCCESSOR_GENERATION] = 43;
    if (command_codec::decodeCommand(longs, floats, decoded)) throw std::runtime_error("mismatched successor owner accepted");
}

void futureJournalCursorCapturesOnlyTheHeadAndKeepsTheNextSpan() {
    using namespace stemwise::audio;
    PresentationSpanJournal<4U> journal;
    NativeAudioPresentationSpan span{
        .generation = 16U, .selectionToken = 106U,
        .firstFingerprintSequence = 1U, .lastFingerprintSequence = 1U,
        .callbackSequence = 1U, .pcmFrames = 4U,
        .firstTimelineFrame = 40, .lastTimelineFrame = 43,
    };
    for (std::uint64_t callback = 1U; callback <= 7U; ++callback) {
        span.callbackSequence = callback;
        if (!journal.publish(span)) throw std::runtime_error("journal setup publication failed");
    }
    const auto head = journal.readAfter(static_cast<std::uint64_t>(std::numeric_limits<Value>::max()));
    if (head.requestedAfterSequence != static_cast<std::uint64_t>(std::numeric_limits<Value>::max()) ||
        head.writeSequence != 7U || head.oldestAvailableSequence != 4U ||
        !head.entries.empty() || head.entries.capacity() != 0U || head.overflowCount != 0U ||
        head.writerCollisionCount != 0U || head.invalidPublicationCount != 0U ||
        head.sequenceExhausted || !head.coherent)
        throw std::runtime_error("future journal cursor did not preserve header-only metadata");
    span.callbackSequence = 8U;
    if (!journal.publish(span)) throw std::runtime_error("next journal publication failed");
    const auto next = journal.readAfter(head.writeSequence);
    if (!next.coherent || next.overflowCount != 0U || next.writeSequence != 8U ||
        next.oldestAvailableSequence != 5U || next.entries.size() != 1U ||
        next.entries.front().sequence != 8U || next.entries.front().callbackSequence != 8U)
        throw std::runtime_error("head-only capture lost the next incremental journal span");
    journal.recordUnavailableCapture();
    const auto failed = journal.readAfter(static_cast<std::uint64_t>(std::numeric_limits<Value>::max()));
    if (failed.coherent || failed.invalidPublicationCount != 1U || !failed.entries.empty())
        throw std::runtime_error("head-only capture concealed a failed publication");
}

int main(int argc, char **argv) {
    try {
        if (argc != 2) throw std::runtime_error("known-answer fixture path required");
        std::ifstream input(argv[1]);
        if (!input) throw std::runtime_error("known-answer fixture unreadable");
        for (std::string line; std::getline(input, line);) {
            std::istringstream row(line); std::string name; row >> name;
            for (Value value; row >> value;) goldens[name].push_back(value);
        }
        { packet::OutputDiagnosticsFields<Value> fields{};
          fields.SCHEMA = 19;
          fields.STREAM_GENERATION = 501;
          fields.STREAM_STATE = 4;
          fields.BACKEND = 3;
          fields.SAMPLE_RATE_HZ = 48000;
          fields.CHANNEL_COUNT = 2;
          fields.FRAMES_PER_BURST = 192;
          fields.PCM_LEVEL_MEAN_SQUARE = 1048576000;
          fields.PCM_LEVEL_PEAK = 1056964608;
          fields.LATEST_CUTOVER_GESTURE_ID = 601;
          fields.CALLBACK_MIX_SEQUENCE = 6101;
          fields.CALLBACK_MIX_CALLBACK_SEQUENCE = 6102;
          fields.CALLBACK_MIX_GENERATION = 6103;
          fields.CALLBACK_MIX_SELECTION_TOKEN = 6104;
          fields.CALLBACK_MIX_CONTROL_REVISION = 6105;
          fields.CALLBACK_MIX_MISSING_CONTROL_FRAMES = 6106;
          fields.CALLBACK_MIX_STALE_CONTROL_FRAMES = 6107;
          fields.CALLBACK_MIX_MIXER_REJECTED_FRAMES = 6108;
          fields.CALLBACK_MIX_INVALID_TAG_FRAMES = 6109;
          fields.CALLBACK_MIX_ADVANCE_FAILURES = 6110;
          fields.CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES = 6111;
          fields.CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED = 6112;
          fields.CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES = 6113;
          fields.CALLBACK_MIX_MIXER_LIMITER_SAFETY_CLAMPS = 6114;
          fields.CALLBACK_MIX_FINAL_NON_FINITE_SAMPLES_SCRUBBED = 6115;
          fields.CALLBACK_MIX_FINAL_SAFETY_CLAMPS = 6117;
          fields.PHYSICAL_RUN_SEQUENCE = 6201;
          fields.LAST_UNDERRUN_PHYSICAL_RUN_SEQUENCE = 6199;
          fields.LAST_CALLBACK_DEADLINE_MISS_START_MICROS = 6301;
          fields.LAST_CALLBACK_DEADLINE_MISS_END_MICROS = 6321;
          check("OUTPUT_DIAGNOSTICS", packet::packOutputDiagnostics(fields));
          fields.BACKEND = 3;
          fields.FRAMES_PER_BURST = -1;
          fields.OUTPUT_BUFFER_CAPACITY_FRAMES = 2048;
          fields.OUTPUT_BUFFER_SIZE_FRAMES = 512;
          fields.PERFORMANCE_MODE = 1;
          fields.X_RUN_COUNT = 7;
          fields.PROCESS_XRUN_COUNT = 19;
          fields.PROCESS_INCOMPLETE_STREAM_OBSERVATION_COUNT = 5;
          check("AUDIO_TRACK_DIAGNOSTICS", packet::packOutputDiagnostics(fields)); }
        { packet::RuntimeDiagnosticsFields<Value> fields{};
          fields.SCHEMA = 20;
          fields.STATUS = 1;
          fields.COMMAND_SNAPSHOTS_PUBLISHED = 701;
          fields.LAST_RENDER_PAYLOAD_HASH = -7;
          fields.REQUESTED_TOKEN = 111;
          fields.REQUESTED_GENERATION = 222;
          fields.ACCEPTED_TOKEN = 333;
          fields.ACCEPTED_GENERATION = 444;
          fields.COMMITTED_TOKEN = 555;
          fields.COMMITTED_GENERATION = 666;
          fields.BANK[0].APPLIED_INTENT_REVISION = 61;
          fields.BANK[0].ASSIGNED = 1;
          fields.BANK[0].SEEK_COUNT = 12;
          fields.RESOURCE_OPERATIONS_COMPLETED = 2001;
          fields.RESOURCE_LAST_OPERATION_MICROS = 2002;
          fields.RESOURCE_MAXIMUM_OPERATION_MICROS = 2003;
          fields.RESOURCE_MAXIMUM_QUEUE_AGE_MICROS = 2004;
          fields.REFILL_DEADLINE_MISSES = 2005;
          fields.CANONICAL_CACHE_TOKEN = 102; fields.CANONICAL_CACHE_GENERATION = 103;
          fields.CANONICAL_CACHE_SONG_HASH = -104; fields.CANONICAL_CACHE_ORIGINAL_HASH = -105;
          fields.CANONICAL_CACHE_STEM_HASH = -106; fields.CANONICAL_READ_OPERATIONS = 4;
          fields.CANONICAL_READ_BYTES = 131072; fields.CANONICAL_LAST_READ_BYTE_OFFSET = 2400000;
          fields.CANONICAL_LAST_READ_BYTES = 32768; fields.CANONICAL_FILE_BYTES = 24032768;
          fields.CANONICAL_RESERVED_BYTES = 48000000; fields.CANONICAL_PEAK_FILE_BYTES = 24032768;
          fields.CANONICAL_ADMITTED_LANES = 1; fields.CANONICAL_ADMITTED_FRAME_BOUND = 28800000;
          fields.CANONICAL_LAST_ADMISSION_STATUS = 2;
          fields.END_ORDERING[0].SEQUENCE = 71; fields.END_ORDERING[0].WORKER_STATE_SEQUENCE = 70;
          fields.END_ORDERING[0].COMMAND_REVISION = 8; fields.END_ORDERING[0].TOKEN = 102;
          fields.END_ORDERING[0].GENERATION = 103; fields.END_ORDERING[0].OUTPUT_PLAYING = 1;
          fields.END_ORDERING[0].COMMAND_UNREAD = 1; fields.END_ORDERING[0].QUEUED_OUTPUT_FRAMES = 256;
          fields.END_ORDERING[4].SEQUENCE = 73; fields.END_ORDERING[4].WORKER_STATE_SEQUENCE = 73;
          fields.END_ORDERING[4].COMMAND_REVISION = 8; fields.END_ORDERING[4].TOKEN = 102;
          fields.END_ORDERING[4].GENERATION = 103; fields.END_ORDERING[4].PIPELINE_END_OBSERVED = 1;
          fields.END_ORDERING[4].TERMINAL_POSITION_VALID = 1; fields.END_ORDERING[4].TERMINAL_AFTER_FRAME = 1;
          check("RUNTIME_DIAGNOSTICS", packet::packRuntimeDiagnostics(fields)); }
        { packet::ControlProgressFields<Value> fields{};
          fields.SCHEMA = 3;
          fields.STATUS = 1;
          fields.REQUESTED_TOKEN = 111;
          fields.REQUESTED_GENERATION = 222;
          fields.ACCEPTED_TOKEN = 333;
          fields.ACCEPTED_GENERATION = 444;
          fields.COMMITTED_TOKEN = 555;
          fields.COMMITTED_GENERATION = 666;
          fields.ACCEPTED_FIRST_FINGERPRINT_SEQUENCE = 51;
          fields.PRESENTATION_SEQUENCE = 11;
          fields.PRESENTATION_GENERATION = 666;
          fields.PRESENTATION_SELECTION_TOKEN = 555;
          fields.PRESENTATION_FINGERPRINT_SEQUENCE = 51;
          fields.PRESENTATION_FIRST_TIMELINE_FRAME = 100;
          fields.PRESENTATION_FIRST_OUTPUT_FRAME = 200;
          fields.PRESENTATION_CALLBACK_SEQUENCE = 61;
          fields.PRESENTATION_PCM_FRAMES = 64;
          fields.POSITION_SEQUENCE = 12;
          fields.POSITION_GENERATION = 666;
          fields.POSITION_SELECTION_TOKEN = 555;
          fields.POSITION_FINGERPRINT_SEQUENCE = 52;
          fields.POSITION_LAST_TIMELINE_FRAME = 163;
          fields.POSITION_LAST_OUTPUT_FRAME = 263;
          fields.POSITION_CALLBACK_SEQUENCE = 62;
          fields.POSITION_PCM_FRAMES = 64;
          fields.AUDIBLE_END_SEQUENCE = 13;
          fields.AUDIBLE_END_TOKEN = 555;
          fields.AUDIBLE_END_GENERATION = 666;
          fields.AUDIBLE_CONTROL_SEQUENCE = 14;
          fields.AUDIBLE_CONTROL_TOKEN = 555;
          fields.AUDIBLE_CONTROL_GENERATION = 666;
          fields.AUDIBLE_CONTROL_CONTROL_REVISION = 21;
          fields.AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE = 1;
          fields.AUDIBLE_CONTROL_FIRST_FINGERPRINT_SEQUENCE = 51;
          fields.AUDIBLE_CONTROL_LAST_FINGERPRINT_SEQUENCE = 52;
          fields.AUDIBLE_CONTROL_CALLBACK_SEQUENCE = 61;
          fields.AUDIBLE_CONTROL_PCM_FRAMES = 64;
          fields.AUTOMATIC_PHASE = 1;
          fields.AUTOMATIC_REVISION = 81;
          fields.AUTOMATIC_TRANSITION_REVISION = 82;
          fields.AUTOMATIC_OUTGOING_TOKEN = 555;
          fields.AUTOMATIC_OUTGOING_GENERATION = 666;
          fields.AUTOMATIC_SUCCESSOR_TOKEN = 777;
          fields.AUTOMATIC_SUCCESSOR_GENERATION = 888;
          fields.AUTOMATIC_SUCCESSOR_QUEUED_FRAMES = 96;
          fields.AUTOMATIC_FIRST_FINGERPRINT_SEQUENCE = 101;
          fields.POSITION_LOOP_POLICY_ADOPTED_REVISION = 90;
          fields.POSITION_LOOP_POLICY_ADOPTION_LATENCY_MICROS = 91;
          fields.POSITION_LOOP_POLICY_REVISION = 92;
          fields.POSITION_LOOP_POLICY_INPUT_TO_ATOMIC_PUBLICATION_LATENCY_MICROS = 93;
          fields.POSITION_LOOP_POLICY_INPUT_TO_CALLBACK_ADOPTION_LATENCY_MICROS = 94;
          fields.AUDIBLE_END_DIRECT_LOOP_POLICY_REVISION = 95;
          fields.BANK[0].APPLIED_INTENT_REVISION = 61;
          fields.BANK[0].ASSIGNED = 1;
          fields.BANK[1].APPLIED_INTENT_REVISION = 62;
          fields.READINESS_SEQUENCE = 141;
          fields.READINESS_ASSIGNED = 1;
          fields.READINESS_TOKEN = 555;
          fields.READINESS_GENERATION = 666;
          fields.READINESS_CONTROL_REVISION = 21;
          fields.READINESS_CACHE_BOUND = 1;
          fields.READINESS_COMPLETE_LANE_MASK = 3;
          fields.READINESS_REQUIRED_LANE_MASK = 3;
          fields.READINESS_STORAGE_STATUS = 1;
          fields.READINESS_FIXED_LOOP_HEAD_PREPARED = 1;
          fields.READINESS_DIRECT_ATOMIC_POLICY_GENERATION = 666;
          fields.READINESS_FAILURE_LANE = 1;
          fields.READINESS_FAILURE_GENERATION = 667;
          fields.READINESS_LOOP_FAILURE_TOKEN = 555;
          fields.READINESS_LOOP_FAILURE_GENERATION = 666;
          fields.READINESS_LOOP_FAILURE_REVISION = 12;
          fields.READINESS_LOOP_FAILURE_REASON = 1;
                    fields.AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS = 1069547520;
          fields.AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS = 1077936128;
          check("CONTROL_PROGRESS", packet::packControlProgress(fields)); }
        { packet::RenderReadinessFields<Value> fields{};
          fields.SCHEMA = 11;
          fields.SEQUENCE = 41;
          fields.ASSIGNED = 1;
          fields.BANK = 1;
          fields.OWNERSHIP_SELECTION_TOKEN = 51;
          fields.OWNERSHIP_GENERATION = 61;
          fields.CONTROL_REVISION = 71;
          fields.LANE[0].GENERATION = 61;
          fields.LANE[0].END_TIMELINE_FRAME_EXCLUSIVE = 12000;
          fields.LANE[0].AVAILABLE_FROM_RENDER_START = 4096;
          fields.LANE[9].INGRESS_FUTURE_GENERATION_DEFERRALS = 91;
          fields.CURRENT_SONG_CACHE_BOUND_TO_SELECTED_OWNER = 1;
          fields.CURRENT_SONG_CACHE_COMPLETE_LANE_MASK = 1;
          fields.INGRESS_BANK_CONFIGURED_LANE_COUNT = 1;
          fields.REQUIRED_SONG_LANE_MASK = 1;
          fields.CACHE_STORAGE_STATUS = 1;
          fields.LOOP_FAILURE_TOKEN = 51;
          fields.LOOP_FAILURE_GENERATION = 61;
          fields.LOOP_FAILURE_REVISION = 17;
          fields.LOOP_FAILURE_REASON = 2;
          check("RENDER_READINESS", packet::packRenderReadiness(fields)); }
        { packet::LoopProtocolFields<Value> fields{};
          fields.SCHEMA = 4;
          fields.STATE_LATEST_REVISION = 29;
          fields.ACCEPTED_INTENTS = 8;
          fields.LAST_SUBMIT_STATUS = 2;
          fields.LAST_CACHE_STATUS = 2;
          fields.LAST_INGRESS_STATUS = 2;
          fields.LAST_POSITION_STATUS = 3;
          fields.LAST_BOUNDARY_STATUS = 1;
          fields.LAST_RENDER_COMMIT_STATUS = 1;
          fields.LAST_CALLBACK_STATUS = 2;
          fields.STATE_BOUNDARY_MODE = 1;
          fields.LAST_ORIGINAL_EOS_STATUS = 2;
          fields.ORIGINAL_EOS_ACCEPTED = 81;
          fields.ORIGINAL_EOS_REJECTED = 82;
          check("LOOP_PROTOCOL", packet::packLoopProtocol(fields)); }
        { packet::StemGainCallbackFields<Value> fields{};
          fields.SCHEMA = 3;
          fields.VALID = 1;
          fields.SEQUENCE = 11;
          fields.GENERATION = 41;
          fields.SELECTION_TOKEN = 31;
          fields.OFFERED_SEQUENCE = 21;
          fields.PUBLISHED_SEQUENCE = 22;
          fields.ACTIVE_MASK = 1;
          fields.GAIN_BITS[0] = 1056964608;
          fields.CALLBACK_SEQUENCE = 31;
          fields.FIRST_TAGGED_FRAME = 0;
          fields.LAST_TAGGED_FRAME = 63;
          fields.TAGGED_FRAME_COUNT = 64;
          fields.NON_ZERO_PCM_FRAME_COUNT = 64;
          fields.CALLBACK_OBSERVED_AT_NS = 12345;
          check("STEM_GAIN_CALLBACK", packet::packStemGainCallback(fields)); }
        { packet::AudibleControlFields<Value> fields{};
          fields.SCHEMA = 2;
          fields.SEQUENCE = 11;
          fields.TOKEN = 31;
          fields.GENERATION = 41;
          fields.CONTROL_REVISION = 21;
          fields.ACTUAL_SOURCE_MODE = 1;
          fields.FIRST_FINGERPRINT = 51;
          fields.LAST_FINGERPRINT = 52;
          fields.CALLBACK_SEQUENCE = 61;
          fields.PCM_FRAMES = 64;
                    fields.ACTUAL_TEMPO_BITS = 1069547520;
          fields.ACTUAL_PITCH_SEMITONES_BITS = 1077936128;
          check("AUDIBLE_CONTROL", packet::packAudibleControl(fields)); }
        { packet::LoopIntentFields<Value> fields{};
          fields.SCHEMA = 5;
          fields.REVISION = 21;
          fields.ENABLED = 1;
          fields.TOKEN = 31;
          fields.GENERATION = 41;
          fields.SONG_HASH = 411;
          fields.ORIGINAL_HASH = 412;
          fields.STEM_HASH = 413;
          fields.CONTENT_REVISION = 51;
          fields.LOOP_REVISION = 21;
          fields.START_FRAME = 100;
          fields.END_FRAME = 192100;
          fields.REQUIRED_LANE_MASK = 1;
          fields.DECLICK_FRAMES = 96;
          fields.BOUNDARY_MODE = 1;
          check("LOOP_INTENT", packet::packLoopIntent(fields)); }
        { packet::LoopIngressReadyFields<Value> fields{};
          fields.SCHEMA = 2;
          fields.REQUEST_SEQUENCE = 11;
          fields.LOOP_REVISION = 21;
          fields.TARGET_CYCLE = 2;
          fields.TOKEN = 31;
          fields.GENERATION = 41;
          fields.INGRESS_EPOCH = 42;
          fields.READY_LANE_MASK = 3;
          fields.EOS_PADDED_LANE_MASK = 2;
          fields.FIRST_FRAME = 100;
          fields.END_FRAME = 400;
          check("LOOP_INGRESS_READY", packet::packLoopIngressReady(fields)); }
        { packet::PresentationJournalHeaderFields<Value> fields{};
          fields.SCHEMA = 4;
          fields.CAPACITY = 4;
          fields.WRITE_SEQUENCE = 1;
          fields.OLDEST_SEQUENCE = 1;
          fields.COHERENT = 1;
          fields.ENTRY_COUNT = 1;
          fields.ENTRY_WIDTH = 20;
          check("PRESENTATION_JOURNAL_HEADER", packet::packPresentationJournalHeader(fields)); }
        { packet::PresentationJournalEntryFields<Value> fields{};
          fields.SEQUENCE = 1;
          fields.GENERATION = 32;
          fields.TOKEN = 31;
          fields.FIRST_FINGERPRINT = 9;
          fields.LAST_FINGERPRINT = 9;
          fields.CALLBACK_SEQUENCE = 1;
          fields.PCM_FRAMES = 4;
          fields.FIRST_LOOP_REVISION = 3;
          fields.LAST_LOOP_REVISION = 3;
          fields.FIRST_TIMELINE_FRAME = 40;
          fields.LAST_TIMELINE_FRAME = 43;
          fields.RENDER_SOURCE_START_BITS = 4630896685688553472;
          fields.RENDER_SOURCE_END_BITS = 4631530004285489152;
          fields.RENDER_FRAME_COUNT = 4;
          fields.ACTUAL_TEMPO_BITS = 1068289229;
          fields.LAST_NEXT_TIMELINE_FRAME = 45;
          fields.CALLBACK_PCM_FRAMES = 4;
          check("PRESENTATION_JOURNAL_ENTRY", packet::packPresentationJournalEntry(fields)); }
        currentKotlinCommandPacketReachesTheActualJniDecoder();
        futureJournalCursorCapturesOnlyTheHeadAndKeepsTheNextSpan();
        if (goldens.size() != 14U) throw std::runtime_error("packet kinds missing");
        std::cout << "Native current packet known answers passed\n";
        return 0;
    } catch (const std::exception &error) { std::cerr << error.what() << "\n"; return 1; }
}
