cmake_minimum_required(VERSION 3.22.1)

project(stemwise_audio_output LANGUAGES C CXX)

option(STEMWISE_NATIVE_SANITIZERS "Host-only address and undefined-behavior checks" OFF)
option(STEMWISE_CACHE_IO_PROFILING "Explicit Android audit profiling only" OFF)
if(STEMWISE_CACHE_IO_PROFILING AND (NOT ANDROID OR NOT STEMWISE_AUDIT_OPTIMIZED))
    message(FATAL_ERROR "Cache I/O profiling requires an Android audit build")
endif()
option(STEMWISE_NATIVE_THREAD_SANITIZER "Host-only data-race checks" OFF)
if(STEMWISE_NATIVE_SANITIZERS AND STEMWISE_NATIVE_THREAD_SANITIZER)
    message(FATAL_ERROR "Memory and thread sanitizers require separate build directories")
endif()
if(STEMWISE_NATIVE_SANITIZERS OR STEMWISE_NATIVE_THREAD_SANITIZER)
    if(ANDROID OR MSVC)
        message(FATAL_ERROR "Use a separate Linux GCC/Clang host build for sanitizers")
    endif()
    if(STEMWISE_NATIVE_THREAD_SANITIZER)
        set(STEMWISE_SANITIZER_FLAGS -fsanitize=thread)
        if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
            # GCC cannot model atomic_thread_fence; retain its warning and the ordinary invariant tests.
            add_compile_options(-Wno-error=tsan)
        endif()
    else()
        set(STEMWISE_SANITIZER_FLAGS -fsanitize=address,undefined,float-cast-overflow)
    endif()
    add_compile_options(${STEMWISE_SANITIZER_FLAGS} -fno-omit-frame-pointer)
    add_link_options(${STEMWISE_SANITIZER_FLAGS})
endif()


# Pinned upstream codec; no CLI, threading, DSD, legacy formats, or shared-library dependency.
set(WAVPACK_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/wavpack")
# Descriptor-only Float32 decoder profile; keep unused upstream entrypoints in the source snapshot.
add_library(stemwise_wavpack STATIC
    ${WAVPACK_ROOT}/src/common_utils.c ${WAVPACK_ROOT}/src/decorr_utils.c
    ${WAVPACK_ROOT}/src/entropy_utils.c ${WAVPACK_ROOT}/src/open_utils.c
    ${WAVPACK_ROOT}/src/read_words.c ${WAVPACK_ROOT}/src/unpack.c
    ${WAVPACK_ROOT}/src/unpack_floats.c ${WAVPACK_ROOT}/src/unpack_seek.c
    ${WAVPACK_ROOT}/src/unpack_utils.c)
target_compile_definitions(stemwise_wavpack PRIVATE NO_TAGS)
set_target_properties(stemwise_wavpack PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(stemwise_wavpack SYSTEM PUBLIC ${WAVPACK_ROOT}/include)
if(MSVC)
    target_compile_options(stemwise_wavpack PRIVATE /w /O2)
    target_compile_definitions(stemwise_wavpack PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_compile_options(stemwise_wavpack PRIVATE -w -O2)
endif()

get_filename_component(
    STEMWISE_TEST_SUITE_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../Test suite"
    ABSOLUTE
)
set(STEMWISE_NATIVE_TEST_ROOT "${STEMWISE_TEST_SUITE_ROOT}/native")

option(
    STEMWISE_AUDIT_OPTIMIZED
    "Optimize the native engine while retaining local audit diagnostics and assertions"
    OFF
)

# The pinned SpeexDSP checkout intentionally contains Autoconf's type template, not a generated
# host-specific header. Generate a standard fixed-width variant in the build tree so vendor source
# remains byte-for-byte upstream and no ABI assumptions leak into the repository.
set(INCLUDE_STDINT "#include <stdint.h>")
set(SIZE16 "int16_t")
set(USIZE16 "uint16_t")
set(SIZE32 "int32_t")
set(USIZE32 "uint32_t")
configure_file(
    third_party/speexdsp/include/speex/speexdsp_config_types.h.in
    generated/speex/speexdsp_config_types.h
    @ONLY
)

set(
    STEMWISE_SPEEX_DEFINITIONS
    OUTSIDE_SPEEX
    RANDOM_PREFIX=stemwise_speex
    FLOATING_POINT
    EXPORT=
)


set(STEMWISE_RENDER_CORE_SOURCES
    native_engine/render/CanonicalPcmStore.cpp
    native_engine/render/CurrentSongPcmCache.cpp
    native_engine/render/LoopHeadCache.cpp
    native_engine/render/SongRenderPipeline.cpp
    native_engine/render/TimelineStereoBuffer.cpp
    StemwiseMixer.cpp
    native_engine/dsp/TimePitchProcessor.cpp
    native_engine/dsp/TimePitchTransition.cpp
)
set(STEMWISE_RUNTIME_SERVICE_SOURCES
    native_engine/runtime/NativePlaybackRuntime.cpp
    native_engine/runtime/NativePlaybackResources.cpp
    native_engine/ingress/LaneIngress.cpp
    native_engine/ingress/LoopHeadPreparationIngress.cpp
    native_engine/dsp/SampleRateConverter.cpp
    native_engine/render/AtomicLoopPolicy.cpp
    NativeAudioOutputLoopBoundaryGate.cpp
    native_engine/render/LoopCycleStateMachine.cpp
)

if(NOT ANDROID)
    enable_testing()
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    add_test(NAME stemwise_native_case_registry_test COMMAND "${Python3_EXECUTABLE}"
        "${STEMWISE_NATIVE_TEST_ROOT}/verify_native_cases.py" --build "${CMAKE_CURRENT_BINARY_DIR}"
        --ctest "${CMAKE_CTEST_COMMAND}")


    function(stemwise_enable_strict_cpp target)
        target_compile_features(${target} PRIVATE cxx_std_20)
        if(MSVC)
            # Cache-line-separated atomics intentionally add tail padding.
            target_compile_options(${target} PRIVATE /W4 /WX /wd4324 /UNDEBUG)
        else()
            target_compile_options(${target} PRIVATE -Wall -Wextra -Werror -UNDEBUG)
        endif()
    endfunction()

    get_target_property(STEMWISE_WAVPACK_SOURCES stemwise_wavpack SOURCES)
    add_library(stemwise_wavpack_probe STATIC ${STEMWISE_WAVPACK_SOURCES})
    target_include_directories(stemwise_wavpack_probe SYSTEM PUBLIC ${WAVPACK_ROOT}/include)
    # Only this parser/DSP fixture encodes known lossless Float32 packets; app target stays decoder-only.
    target_sources(stemwise_wavpack_probe PRIVATE
        ${WAVPACK_ROOT}/src/open_raw.c ${WAVPACK_ROOT}/src/open_filename.c ${WAVPACK_ROOT}/src/open_legacy.c
        ${WAVPACK_ROOT}/src/pack.c ${WAVPACK_ROOT}/src/pack_dns.c ${WAVPACK_ROOT}/src/pack_floats.c
        ${WAVPACK_ROOT}/src/pack_utils.c ${WAVPACK_ROOT}/src/write_words.c
        ${WAVPACK_ROOT}/src/extra1.c ${WAVPACK_ROOT}/src/extra2.c)

    target_compile_definitions(stemwise_wavpack_probe PRIVATE NO_TAGS
        malloc=stemwise_wavpack_test_malloc calloc=stemwise_wavpack_test_calloc
        realloc=stemwise_wavpack_test_realloc free=stemwise_wavpack_test_free)
    add_executable(stemwise_native_child_process_test "${STEMWISE_NATIVE_TEST_ROOT}/NativeChildProcessTest.cpp")
    stemwise_enable_strict_cpp(stemwise_native_child_process_test)
    add_test(NAME stemwise_native_child_process_test COMMAND stemwise_native_child_process_test)

    add_executable(stemwise_native_ownership_publication_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativeOwnershipPublicationTest.cpp")
    target_include_directories(stemwise_native_ownership_publication_test PRIVATE .)
    stemwise_enable_strict_cpp(stemwise_native_ownership_publication_test)
    add_test(NAME stemwise_native_ownership_publication_test COMMAND stemwise_native_ownership_publication_test)

    add_executable(stemwise_wavpack_reader_test
        "${STEMWISE_NATIVE_TEST_ROOT}/WavPackReaderTest.cpp" WavPackReader.cpp native_engine/dsp/TimePitchProcessor.cpp)
    target_include_directories(stemwise_wavpack_reader_test PRIVATE . native_engine/dsp)
    target_include_directories(stemwise_wavpack_reader_test SYSTEM PRIVATE
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include)
    target_link_libraries(stemwise_wavpack_reader_test PRIVATE stemwise_wavpack_probe)
    stemwise_enable_strict_cpp(stemwise_wavpack_reader_test)
    add_test(NAME stemwise_wavpack_reader_test COMMAND stemwise_wavpack_reader_test
        "${STEMWISE_TEST_SUITE_ROOT}/android/instrumentation/assets/encoder/remaining-float32.wv")
    add_test(NAME stemwise_wavpack_metadata_write_test COMMAND stemwise_wavpack_reader_test --metadata-writes)
    add_test(NAME stemwise_wavpack_hybrid_tail_test COMMAND stemwise_wavpack_reader_test --hybrid-tails)
    set_source_files_properties(${WAVPACK_ROOT}/src/tag_utils.c PROPERTIES
        COMPILE_DEFINITIONS realloc=stemwise_wavpack_tag_test_realloc)
    foreach(char_mode signed unsigned)
        add_executable(stemwise_wavpack_tag_${char_mode}_test
            "${STEMWISE_NATIVE_TEST_ROOT}/WavPackTagTest.c" ${WAVPACK_ROOT}/src/tag_utils.c)
        target_include_directories(stemwise_wavpack_tag_${char_mode}_test PRIVATE ${WAVPACK_ROOT}/src)
        target_link_libraries(stemwise_wavpack_tag_${char_mode}_test PRIVATE stemwise_wavpack)
        if(MSVC)
            target_compile_options(stemwise_wavpack_tag_${char_mode}_test PRIVATE /UNDEBUG)
            if(char_mode STREQUAL "unsigned")
                target_compile_options(stemwise_wavpack_tag_${char_mode}_test PRIVATE /J)
            endif()
        else()
            target_compile_options(stemwise_wavpack_tag_${char_mode}_test PRIVATE -f${char_mode}-char -UNDEBUG)
        endif()
        add_test(NAME stemwise_wavpack_tag_${char_mode}_test COMMAND stemwise_wavpack_tag_${char_mode}_test)
    endforeach()

    add_library(
        stemwise_speex_resampler
        STATIC
        third_party/speexdsp/libspeexdsp/resample.c
    )
    target_compile_definitions(
        stemwise_speex_resampler PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_speex_resampler
        PRIVATE
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
        third_party/speexdsp/include/speex
        third_party/speexdsp/include
    )
    if(MSVC)
        target_compile_options(stemwise_speex_resampler PRIVATE /w)
    else()
        target_compile_options(stemwise_speex_resampler PRIVATE -w)
    endif()

    add_executable(
        stemwise_multibus_ring_buffer_test
        "${STEMWISE_NATIVE_TEST_ROOT}/SpscTaggedMultibusRingBufferTest.cpp"
    )
    target_include_directories(stemwise_multibus_ring_buffer_test PRIVATE .)
    stemwise_enable_strict_cpp(stemwise_multibus_ring_buffer_test)
    add_test(
        NAME stemwise_multibus_ring_buffer_test
        COMMAND stemwise_multibus_ring_buffer_test
    )

    add_executable(
        stemwise_manual_selection_render_gate_test
        "${STEMWISE_NATIVE_TEST_ROOT}/ManualSelectionRenderGateTest.cpp"
    )
    target_include_directories(stemwise_manual_selection_render_gate_test PRIVATE .)
    stemwise_enable_strict_cpp(stemwise_manual_selection_render_gate_test)
    add_test(
        NAME stemwise_manual_selection_render_gate_test
        COMMAND stemwise_manual_selection_render_gate_test
    )

    add_executable(
        stemwise_mixer_test
        "${STEMWISE_NATIVE_TEST_ROOT}/StemwiseMixerTest.cpp"
        StemwiseMixer.cpp
        LateStemMixer.cpp
    )
    target_include_directories(stemwise_mixer_test PRIVATE .)
    stemwise_enable_strict_cpp(stemwise_mixer_test)
    add_test(NAME stemwise_mixer_test COMMAND stemwise_mixer_test)

    add_executable(
        stemwise_late_stem_mixer_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LateStemMixerTest.cpp"
        LateStemMixer.cpp
    )
    set(MERGER_ORACLE_HEADER "${CMAKE_CURRENT_BINARY_DIR}/generated/merger-oracle.h")
    add_custom_command(OUTPUT "${MERGER_ORACLE_HEADER}"
        COMMAND "${Python3_EXECUTABLE}" "${STEMWISE_NATIVE_TEST_ROOT}/../host/merger_math.py"
            --cpp-header "${MERGER_ORACLE_HEADER}"
        DEPENDS "${STEMWISE_NATIVE_TEST_ROOT}/../host/merger_math.py"
            "${STEMWISE_NATIVE_TEST_ROOT}/../android/fixtures/merger-oracle.json"
        VERBATIM)
    target_sources(stemwise_late_stem_mixer_test PRIVATE "${MERGER_ORACLE_HEADER}")
    target_compile_definitions(stemwise_late_stem_mixer_test PRIVATE STEMWISE_MERGER_TEST_CAPTURE)
    target_include_directories(stemwise_late_stem_mixer_test PRIVATE . "${CMAKE_CURRENT_BINARY_DIR}/generated")
    stemwise_enable_strict_cpp(stemwise_late_stem_mixer_test)
    add_test(
        NAME stemwise_late_stem_mixer_test
        COMMAND stemwise_late_stem_mixer_test
    )

    add_executable(
        stemwise_late_stem_mix_control_mailbox_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LateStemMixControlMailboxTest.cpp"
        LateStemMixer.cpp
    )
    target_include_directories(stemwise_late_stem_mix_control_mailbox_test PRIVATE .)
    stemwise_enable_strict_cpp(stemwise_late_stem_mix_control_mailbox_test)
    add_test(
        NAME stemwise_late_stem_mix_control_mailbox_test
        COMMAND stemwise_late_stem_mix_control_mailbox_test
    )

    add_executable(
        stemwise_native_audio_output_multibus_callback_stage_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativeAudioOutputMultibusCallbackStageTest.cpp"
        NativeAudioOutputMultibusCallbackStage.cpp
        LateStemMixer.cpp
    )
    target_include_directories(
        stemwise_native_audio_output_multibus_callback_stage_test
        PRIVATE .
    )
    target_compile_definitions(stemwise_native_audio_output_multibus_callback_stage_test PRIVATE
        STEMWISE_LATE_CONTROL_TEST_HOOKS=1)
    stemwise_enable_strict_cpp(
        stemwise_native_audio_output_multibus_callback_stage_test
    )
    add_test(
        NAME stemwise_native_audio_output_multibus_callback_stage_test
        COMMAND stemwise_native_audio_output_multibus_callback_stage_test
    )

    add_executable(
        stemwise_native_audio_output_final_device_stage_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativeAudioOutputFinalDeviceStageTest.cpp"
        NativeAudioOutputFinalDeviceStage.cpp
    )
    target_include_directories(
        stemwise_native_audio_output_final_device_stage_test
        PRIVATE .
    )
    stemwise_enable_strict_cpp(
        stemwise_native_audio_output_final_device_stage_test
    )
    add_test(
        NAME stemwise_native_audio_output_final_device_stage_test
        COMMAND stemwise_native_audio_output_final_device_stage_test
    )

    add_executable(
        stemwise_time_pitch_test
        "${STEMWISE_NATIVE_TEST_ROOT}/TimePitchProcessorTest.cpp"
        native_engine/dsp/TimePitchProcessor.cpp
        native_engine/dsp/TimePitchTransition.cpp
    )
    target_include_directories(
        stemwise_time_pitch_test
        PRIVATE native_engine/dsp
    )
    target_include_directories(
        stemwise_time_pitch_test
        SYSTEM PRIVATE
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include
    )
    stemwise_enable_strict_cpp(stemwise_time_pitch_test)
    add_test(NAME stemwise_time_pitch_test COMMAND stemwise_time_pitch_test)

    add_executable(
        stemwise_sample_rate_test
        "${STEMWISE_NATIVE_TEST_ROOT}/SampleRateConverterTest.cpp"
        native_engine/dsp/SampleRateConverter.cpp
    )
    target_compile_definitions(
        stemwise_sample_rate_test PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_sample_rate_test
        PRIVATE
        .
        native_engine/dsp
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_sample_rate_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
    )
    add_library(stemwise_speex_resampler_probe STATIC third_party/speexdsp/libspeexdsp/resample.c)
    target_compile_definitions(stemwise_speex_resampler_probe PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
        calloc=stemwise_test_calloc realloc=stemwise_test_realloc free=stemwise_test_free)
    target_include_directories(stemwise_speex_resampler_probe PRIVATE
        third_party/speexdsp/libspeexdsp ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
        third_party/speexdsp/include/speex third_party/speexdsp/include)
    target_link_libraries(stemwise_sample_rate_test PRIVATE stemwise_speex_resampler_probe)
    stemwise_enable_strict_cpp(stemwise_sample_rate_test)
    add_test(NAME stemwise_sample_rate_test COMMAND stemwise_sample_rate_test)

    add_executable(
        stemwise_timeline_buffer_test
        "${STEMWISE_NATIVE_TEST_ROOT}/TimelineStereoBufferTest.cpp"
        native_engine/render/TimelineStereoBuffer.cpp
    )
    target_include_directories(
        stemwise_timeline_buffer_test
        PRIVATE native_engine/render
    )
    stemwise_enable_strict_cpp(stemwise_timeline_buffer_test)
    add_test(NAME stemwise_timeline_buffer_test COMMAND stemwise_timeline_buffer_test)

    add_executable(
        stemwise_current_song_pcm_cache_test
        "${STEMWISE_NATIVE_TEST_ROOT}/CurrentSongPcmCacheTest.cpp"
        native_engine/render/CurrentSongPcmCache.cpp
        native_engine/render/CanonicalPcmStore.cpp
    )
    target_include_directories(
        stemwise_current_song_pcm_cache_test
        PRIVATE native_engine/render
    )
    stemwise_enable_strict_cpp(stemwise_current_song_pcm_cache_test)
    target_compile_definitions(stemwise_current_song_pcm_cache_test PRIVATE STEMWISE_CACHE_IO_TEST_HOOKS=1)
    target_include_directories(stemwise_current_song_pcm_cache_test PRIVATE "${STEMWISE_NATIVE_TEST_ROOT}")
    add_test(
        NAME stemwise_current_song_pcm_cache_test
        COMMAND stemwise_current_song_pcm_cache_test
    )
    add_test(NAME stemwise_current_song_pcm_cache_configured_directory_test
        COMMAND stemwise_current_song_pcm_cache_test --configured-directory)

    add_executable(
        stemwise_atomic_loop_policy_test
        "${STEMWISE_NATIVE_TEST_ROOT}/AtomicLoopPolicyTest.cpp"
        native_engine/render/AtomicLoopPolicy.cpp
    )
    target_include_directories(
        stemwise_atomic_loop_policy_test
        PRIVATE . native_engine/render
    )
    stemwise_enable_strict_cpp(stemwise_atomic_loop_policy_test)
    add_test(NAME stemwise_atomic_loop_policy_test COMMAND stemwise_atomic_loop_policy_test)

    add_executable(
        stemwise_native_audio_output_loop_boundary_gate_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativeAudioOutputLoopBoundaryGateTest.cpp"
        NativeAudioOutputLoopBoundaryGate.cpp
    )
    target_include_directories(
        stemwise_native_audio_output_loop_boundary_gate_test
        PRIVATE .
    )
    stemwise_enable_strict_cpp(stemwise_native_audio_output_loop_boundary_gate_test)
    target_compile_definitions(stemwise_native_audio_output_loop_boundary_gate_test PRIVATE
        STEMWISE_OUTPUT_ROLE_TEST_HOOKS=1)
    add_test(
        NAME stemwise_native_audio_output_loop_boundary_gate_test
        COMMAND stemwise_native_audio_output_loop_boundary_gate_test
    )

    add_executable(
        stemwise_loop_head_cache_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LoopHeadCacheTest.cpp"
        native_engine/render/LoopHeadCache.cpp
        native_engine/render/CurrentSongPcmCache.cpp
        native_engine/render/CanonicalPcmStore.cpp
    )
    target_include_directories(
        stemwise_loop_head_cache_test
        PRIVATE native_engine/render
    )
    stemwise_enable_strict_cpp(stemwise_loop_head_cache_test)
    add_test(NAME stemwise_loop_head_cache_test COMMAND stemwise_loop_head_cache_test)

    add_executable(
        stemwise_loop_cycle_state_machine_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LoopCycleStateMachineTest.cpp"
        native_engine/render/LoopCycleStateMachine.cpp
    )
    target_include_directories(
        stemwise_loop_cycle_state_machine_test
        PRIVATE native_engine/render
    )
    stemwise_enable_strict_cpp(stemwise_loop_cycle_state_machine_test)
    add_test(
        NAME stemwise_loop_cycle_state_machine_test
        COMMAND stemwise_loop_cycle_state_machine_test
    )

    add_executable(
        stemwise_lane_ingress_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LaneIngressTest.cpp"
        native_engine/ingress/LaneIngress.cpp
        native_engine/dsp/SampleRateConverter.cpp
    )
    target_compile_definitions(
        stemwise_lane_ingress_test PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_lane_ingress_test
        PRIVATE
        native_engine/ingress
        native_engine/dsp
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_lane_ingress_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
    )
    target_link_libraries(stemwise_lane_ingress_test PRIVATE stemwise_speex_resampler)
    stemwise_enable_strict_cpp(stemwise_lane_ingress_test)
    add_test(NAME stemwise_lane_ingress_test COMMAND stemwise_lane_ingress_test)

    add_executable(
        stemwise_loop_head_preparation_ingress_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LoopHeadPreparationIngressTest.cpp"
        native_engine/ingress/LoopHeadPreparationIngress.cpp
        native_engine/ingress/LaneIngress.cpp
        native_engine/dsp/SampleRateConverter.cpp
        native_engine/render/LoopHeadCache.cpp
        native_engine/render/CurrentSongPcmCache.cpp
        native_engine/render/CanonicalPcmStore.cpp
    )
    target_compile_definitions(
        stemwise_loop_head_preparation_ingress_test
        PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_loop_head_preparation_ingress_test
        PRIVATE
        native_engine/ingress
        native_engine/dsp
        native_engine/render
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_loop_head_preparation_ingress_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
    )
    target_link_libraries(
        stemwise_loop_head_preparation_ingress_test
        PRIVATE stemwise_speex_resampler
    )
    stemwise_enable_strict_cpp(stemwise_loop_head_preparation_ingress_test)
    add_test(
        NAME stemwise_loop_head_preparation_ingress_test
        COMMAND stemwise_loop_head_preparation_ingress_test
    )

    add_executable(
        stemwise_song_render_pipeline_test
        "${STEMWISE_NATIVE_TEST_ROOT}/SongRenderPipelineTest.cpp"
        LateStemMixer.cpp
        NativeAudioOutputFinalDeviceStage.cpp
            ${STEMWISE_RENDER_CORE_SOURCES}
)
    target_include_directories(
        stemwise_song_render_pipeline_test
        PRIVATE
        .
        native_engine/render
        native_engine/dsp
    )
    target_include_directories(
        stemwise_song_render_pipeline_test
        SYSTEM PRIVATE
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include
    )
    stemwise_enable_strict_cpp(stemwise_song_render_pipeline_test)
    target_compile_definitions(stemwise_song_render_pipeline_test PRIVATE STEMWISE_CACHE_IO_TEST_HOOKS=1)
    target_include_directories(stemwise_song_render_pipeline_test PRIVATE "${STEMWISE_NATIVE_TEST_ROOT}")

    add_test(NAME stemwise_formal_runner_test
        COMMAND ${Python3_EXECUTABLE} "${STEMWISE_NATIVE_TEST_ROOT}/formal/test_runner.py")
    add_executable(stemwise_latest_command_mailbox_test
        "${STEMWISE_NATIVE_TEST_ROOT}/LatestCommandMailboxTest.cpp")
    target_include_directories(stemwise_latest_command_mailbox_test PRIVATE
        . native_engine/dsp native_engine/ingress native_engine/render)
    target_compile_definitions(stemwise_latest_command_mailbox_test PRIVATE STEMWISE_MAILBOX_TEST_HOOKS=1)
    stemwise_enable_strict_cpp(stemwise_latest_command_mailbox_test)
    add_test(NAME stemwise_latest_command_mailbox_test COMMAND stemwise_latest_command_mailbox_test)

    add_executable(stemwise_native_playback_output_port_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativePlaybackOutputPortTest.cpp"
        native_engine/runtime/NativePlaybackOutputPort.cpp)
    target_include_directories(stemwise_native_playback_output_port_test PRIVATE
        . native_engine/runtime native_engine/ingress native_engine/dsp native_engine/render)
    stemwise_enable_strict_cpp(stemwise_native_playback_output_port_test)
    add_test(NAME stemwise_native_playback_output_port_test COMMAND stemwise_native_playback_output_port_test)

    add_executable(
        stemwise_native_playback_runtime_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativePlaybackRuntimeTest.cpp"
        LateStemMixer.cpp
            ${STEMWISE_RENDER_CORE_SOURCES}
        ${STEMWISE_RUNTIME_SERVICE_SOURCES}
)
    target_compile_definitions(
        stemwise_native_playback_runtime_test
        PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_native_playback_runtime_test
        PRIVATE
        .
        native_engine/runtime
        native_engine/ingress
        native_engine/dsp
        native_engine/render
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_native_playback_runtime_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include
    )
    target_link_libraries(
        stemwise_native_playback_runtime_test
        PRIVATE stemwise_speex_resampler
    )
    stemwise_enable_strict_cpp(stemwise_native_playback_runtime_test)
    target_compile_definitions(stemwise_native_playback_runtime_test PRIVATE STEMWISE_CACHE_IO_TEST_HOOKS=1 STEMWISE_RESOURCE_TEST_HOOKS=1)
    target_include_directories(stemwise_native_playback_runtime_test PRIVATE "${STEMWISE_NATIVE_TEST_ROOT}")
    function(stemwise_register_cases target registry prefix)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${registry}")
        file(STRINGS "${registry}" cases REGEX "^STEMWISE_FOCUSED_CASE")
        foreach(entry IN LISTS cases)
            string(REGEX REPLACE "^STEMWISE_FOCUSED_CASE\\(\"(--[a-z0-9-]+)\".*$" "\\1" selector "${entry}")
            string(REGEX REPLACE "^--" "" case_name "${selector}")
            string(REPLACE "-" "_" case_name "${case_name}")
            add_test(NAME "${prefix}_${case_name}_test" COMMAND ${target} "${selector}")
        endforeach()
    endfunction()
    stemwise_register_cases(stemwise_native_playback_runtime_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativePlaybackRuntimeCases.def" stemwise_native_playback)
    stemwise_register_cases(stemwise_song_render_pipeline_test
        "${STEMWISE_NATIVE_TEST_ROOT}/SongRenderPipelineCases.def" stemwise_song_render_pipeline)

    add_executable(
        stemwise_native_playback_pcm_closure_test
        "${STEMWISE_NATIVE_TEST_ROOT}/NativePlaybackPcmClosureTest.cpp"
        NativeAudioOutputBridge.cpp
        native_engine/runtime/NativePlaybackOutputPort.cpp
        NativeAudioOutputFinalDeviceStage.cpp
        NativeAudioOutputMultibusCallbackStage.cpp
        LateStemMixer.cpp
            ${STEMWISE_RENDER_CORE_SOURCES}
        ${STEMWISE_RUNTIME_SERVICE_SOURCES}
)
    target_compile_definitions(
        stemwise_native_playback_pcm_closure_test
        PRIVATE ${STEMWISE_SPEEX_DEFINITIONS} STEMWISE_OUTPUT_CALLBACK_TEST_HOOKS=1 STEMWISE_CACHE_IO_TEST_HOOKS=1
    )
    target_include_directories(
        stemwise_native_playback_pcm_closure_test
        PRIVATE
        "${STEMWISE_NATIVE_TEST_ROOT}"
        .
        native_engine/runtime
        native_engine/ingress
        native_engine/dsp
        native_engine/render
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_native_playback_pcm_closure_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include
    )
    target_link_libraries(
        stemwise_native_playback_pcm_closure_test
        PRIVATE stemwise_speex_resampler
    )
    stemwise_enable_strict_cpp(stemwise_native_playback_pcm_closure_test)
    add_test(
        NAME stemwise_native_playback_pcm_closure_test
        COMMAND stemwise_native_playback_pcm_closure_test
    )

    add_executable(
        stemwise_seven_lane_synchronization_test
        "${STEMWISE_NATIVE_TEST_ROOT}/SevenLaneSynchronizationTest.cpp"
        NativeAudioOutputMultibusCallbackStage.cpp
        LateStemMixer.cpp
        native_engine/dsp/SampleRateConverter.cpp
        native_engine/dsp/TimePitchProcessor.cpp
    )
    target_compile_definitions(
        stemwise_seven_lane_synchronization_test
        PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
    )
    target_include_directories(
        stemwise_seven_lane_synchronization_test
        PRIVATE
        .
        native_engine/dsp
        third_party/speexdsp/libspeexdsp
        ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    )
    target_include_directories(
        stemwise_seven_lane_synchronization_test
        SYSTEM PRIVATE
        third_party/speexdsp/include
        third_party/signalsmith-stretch/include
        third_party/signalsmith-stretch/third_party/signalsmith-linear/include
    )
    target_link_libraries(
        stemwise_seven_lane_synchronization_test
        PRIVATE stemwise_speex_resampler
    )
    stemwise_enable_strict_cpp(stemwise_seven_lane_synchronization_test)
    add_test(
        NAME stemwise_seven_lane_synchronization_test
        COMMAND stemwise_seven_lane_synchronization_test
    )

    add_test(NAME stemwise_native_playback_unpublished_original_range_test
        COMMAND stemwise_native_playback_pcm_closure_test --unpublished-original-range)
    add_test(NAME stemwise_native_playback_callback_owner_retirement_test
        COMMAND stemwise_native_playback_pcm_closure_test --callback-owner-retirement)
    add_test(NAME stemwise_native_playback_inactive_bank_loop_reuse_test
        COMMAND stemwise_native_playback_pcm_closure_test --inactive-bank-loop-reuse)
    add_test(NAME stemwise_native_playback_loop_clear_eof_test
        COMMAND stemwise_native_playback_pcm_closure_test --loop-clear-eof)
    add_test(NAME stemwise_native_playback_managed_loop_continuity_test
        COMMAND stemwise_native_playback_pcm_closure_test --managed-loop-continuity)
    add_test(NAME stemwise_native_playback_loop_physical_policy_test
        COMMAND stemwise_native_playback_pcm_closure_test --loop-physical-policy)
    add_test(NAME stemwise_native_playback_push_output_acceptance_test
        COMMAND stemwise_native_playback_pcm_closure_test --push-output-acceptance)
    add_test(NAME stemwise_native_cold_original_preroll_test
        COMMAND stemwise_native_playback_pcm_closure_test --cold-original-preroll)
    add_test(NAME stemwise_native_playback_source_transition_cursor_test
        COMMAND stemwise_native_playback_pcm_closure_test --source-transition-cursor)
    add_test(NAME stemwise_native_playback_negative_push_contracts_test
        COMMAND stemwise_native_playback_pcm_closure_test --negative-push-contracts)
    add_test(NAME stemwise_native_playback_negative_consumer_lifetime_test
        COMMAND stemwise_native_playback_pcm_closure_test --negative-consumer-lifetime)

    # Every native test, including future focused registrations, has an outer deadline.
    add_test(NAME stemwise_stft_counter_test COMMAND stemwise_time_pitch_test --stft-counter)
    add_executable(stemwise_signalsmith_header_test "${STEMWISE_NATIVE_TEST_ROOT}/SignalsmithHeaderTest.cpp")
    target_include_directories(stemwise_signalsmith_header_test SYSTEM PRIVATE
        third_party/signalsmith-stretch/third_party/signalsmith-linear)
    stemwise_enable_strict_cpp(stemwise_signalsmith_header_test)
    add_test(NAME stemwise_signalsmith_header_test COMMAND stemwise_signalsmith_header_test)
    add_test(NAME stemwise_native_playback_qualification_diagnostics_test
        COMMAND stemwise_native_playback_pcm_closure_test --qualification-diagnostics)
    get_property(STEMWISE_REGISTERED_TESTS DIRECTORY PROPERTY TESTS)
    set_tests_properties(${STEMWISE_REGISTERED_TESTS} PROPERTIES TIMEOUT 180)
    if(STEMWISE_NATIVE_SANITIZERS)
        # Full ten-lane SRC oracle measured 87s alone; allow bounded sanitizer-host contention.
        set_tests_properties(stemwise_native_playback_canonical_random_access_44100_9_parts_test
            PROPERTIES TIMEOUT 300)
        # Long PCM oracles run in isolated sanitizer shards; ownership waits stay bounded separately.
        foreach(seconds 30 37)
            set_tests_properties(stemwise_song_render_pipeline_long_loop_canonical_${seconds}s_tuned_9_parts_test
                PROPERTIES TIMEOUT 900)
        endforeach()
    endif()
    add_test(NAME stemwise_native_packet_schema_test COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_LIST_DIR}/../../../../tools/native_packet_schema.py" --check)
    set_tests_properties(stemwise_native_packet_schema_test PROPERTIES TIMEOUT 30)
    add_executable(stemwise_native_packet_golden_test "${STEMWISE_NATIVE_TEST_ROOT}/NativePacketGoldenTest.cpp")
    target_include_directories(stemwise_native_packet_golden_test PRIVATE
        . native_engine/dsp native_engine/ingress native_engine/render)
    stemwise_enable_strict_cpp(stemwise_native_packet_golden_test)
    add_test(NAME stemwise_native_packet_golden_test COMMAND stemwise_native_packet_golden_test
        "${STEMWISE_NATIVE_TEST_ROOT}/../fixtures/native-packets/current-protocol.txt")
    set_tests_properties(stemwise_native_packet_golden_test PROPERTIES TIMEOUT 30)
    return()
endif()

add_library(
    stemwise_audio_output
    SHARED
    NativeAudioOutput.cpp
    NativeAudioOutputBridge.cpp
    WavPackReader.cpp
    WavPackReaderJni.cpp
    NativeAudioOutputFinalDeviceStage.cpp
    NativeAudioOutputMultibusCallbackStage.cpp
    LateStemMixer.cpp
    native_engine/ingress/NativeLaneIngressJni.cpp
    native_engine/runtime/NativePlaybackOutputPort.cpp
    native_engine/runtime/NativePlaybackRuntimeJni.cpp
    third_party/speexdsp/libspeexdsp/resample.c
    ${STEMWISE_RENDER_CORE_SOURCES}
    ${STEMWISE_RUNTIME_SERVICE_SOURCES}
)

# SpeexDSP is pinned source, compiled into the one StemWise native library. Keep its public
# headers and Signalsmith's header-only implementation out of project warning ownership while
# retaining -Werror for every StemWise-authored translation unit.
set_source_files_properties(
    third_party/speexdsp/libspeexdsp/resample.c
    PROPERTIES COMPILE_OPTIONS "-w"
)

target_compile_definitions(
    stemwise_audio_output PRIVATE ${STEMWISE_SPEEX_DEFINITIONS}
)
target_include_directories(
    stemwise_audio_output
    PRIVATE
    .
    native_engine/dsp
    native_engine/ingress
    native_engine/render
    native_engine/runtime
    third_party/speexdsp/libspeexdsp
    ${CMAKE_CURRENT_BINARY_DIR}/generated/speex
    third_party/speexdsp/include/speex
)
target_include_directories(
    stemwise_audio_output
    SYSTEM PRIVATE
    third_party/speexdsp/include
    third_party/signalsmith-stretch/include
    third_party/signalsmith-stretch/third_party/signalsmith-linear/include
)

target_compile_features(stemwise_audio_output PRIVATE cxx_std_20)
target_compile_options(
    stemwise_audio_output
    PRIVATE
    -Wall
    -Wextra
    -Werror
)
if(STEMWISE_AUDIT_OPTIMIZED)
    # Android's ordinary debug native variant is deliberately -O0. Audio qualification must run
    # with production-representative DSP optimization without turning off assertions/test hooks.
    target_compile_options(stemwise_audio_output PRIVATE -O2 -g)
    target_compile_definitions(stemwise_audio_output PRIVATE STEMWISE_AUDIT_OPTIMIZED=1)
endif()
if(STEMWISE_CACHE_IO_PROFILING)
    target_compile_definitions(stemwise_audio_output PRIVATE STEMWISE_CACHE_IO_PROFILING=1)
    target_link_libraries(stemwise_audio_output PRIVATE android)
endif()
target_link_options(
    stemwise_audio_output
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(stemwise_audio_output PRIVATE stemwise_wavpack log)
