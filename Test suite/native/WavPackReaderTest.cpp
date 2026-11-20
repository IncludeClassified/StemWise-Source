#ifdef _WIN32
#define _CRT_SECURE_NO_WARNINGS
#endif
#include "WavPackReader.h"
#include "TimePitchProcessor.h"
#include <wavpack.h>
#include <bit>
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdint>
#include <exception>
#include <iterator>
#include <vector>
#include <cstdlib>
#include <limits>
#include <string>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#include <io.h>
#define fileno _fileno
#else
#include <sys/mman.h>
#include <unistd.h>
#endif


namespace wavpack_allocation_probe {
int failAfter = -1;
std::size_t calls = 0;
std::int64_t live = 0;
bool fail() { ++calls; return failAfter >= 0 && failAfter-- == 0; }
}
extern "C" void *stemwise_wavpack_test_malloc(std::size_t bytes) {
    if (wavpack_allocation_probe::fail()) return nullptr;
    void *result = std::malloc(bytes);
    if (result) ++wavpack_allocation_probe::live;
    return result;
}
extern "C" void *stemwise_wavpack_test_calloc(std::size_t count, std::size_t bytes) {
    if (wavpack_allocation_probe::fail()) return nullptr;
    void *result = std::calloc(count, bytes);
    if (result) ++wavpack_allocation_probe::live;
    return result;
}
extern "C" void *stemwise_wavpack_test_realloc(void *old, std::size_t bytes) {
    if (wavpack_allocation_probe::fail()) return nullptr;
    void *result = std::realloc(old, bytes);
    if (result && !old) ++wavpack_allocation_probe::live;
    return result;
}
extern "C" void stemwise_wavpack_test_free(void *memory) {
    if (memory) --wavpack_allocation_probe::live;
    std::free(memory);
}

namespace {
constexpr std::uint32_t kFrames = 48017;
std::uint32_t expected(std::uint32_t i) {
    constexpr std::uint32_t boundary[] = {0, 0x80000000, 0x3fc00000, 0xc0300000,
        1, 0x80000001, 0x0d800000, 0x8d800000, 0x3f7fffff, 0x477fff00};
    if (i < std::size(boundary)) return boundary[i];
    return ((i * 2654435761U) & 0x7fffffU) | ((112U + i % 20U) << 23) | ((i & 1U) << 31);
}
template <typename F> void rejects(F action) {
    bool rejected = false;
    try { action(); } catch (const std::exception &) { rejected = true; }
    assert(rejected);
}
int writeEncodedBlock(void *file, void *data, int32_t bytes) {
    return std::fwrite(data, 1U, static_cast<std::size_t>(bytes), static_cast<FILE *>(file)) == static_cast<std::size_t>(bytes);
}
void rawDecoderRejectsShortGuardedInputsAndUnsignedHighBits() {
#ifdef _WIN32
    SYSTEM_INFO system{}; GetSystemInfo(&system);
    const auto pageSize = static_cast<std::size_t>(system.dwPageSize);
    auto *pages = static_cast<unsigned char *>(VirtualAlloc(nullptr, pageSize * 2U, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
    DWORD prior{};
    assert(pages && VirtualProtect(pages + pageSize, pageSize, PAGE_NOACCESS, &prior));
#else
    const auto pageSize = static_cast<std::size_t>(sysconf(_SC_PAGESIZE));
    auto *pages = static_cast<unsigned char *>(mmap(nullptr, pageSize * 2U, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(pages != MAP_FAILED && mprotect(pages + pageSize, pageSize, PROT_NONE) == 0);
#endif
    char error[128]{};
    auto *last = pages + pageSize - 1U; *last = 'w';
    assert(!WavpackOpenRawDecoder(last, 1, nullptr, 0, 0x410, error, 0, 0));
    assert(!WavpackOpenRawDecoder(last, -1, nullptr, 0, 0x410, error, 0, 0));
    assert(!WavpackOpenRawDecoder(nullptr, 0, nullptr, 0, 0x410, error, 0, 0));
#ifdef _WIN32
    assert(VirtualFree(pages, 0, MEM_RELEASE));
#else
    assert(munmap(pages, pageSize * 2U) == 0);
#endif
    unsigned char highBits[]{1, 0, 0, 0, 0, 0x18, 0, 0, 0, 0, 0, 0xff};
    if (auto *context = WavpackOpenRawDecoder(highBits, sizeof(highBits), nullptr, 0, 0x410, error, 0, 0))
        WavpackCloseFile(context);
}

void encoderMetadataWriteFailuresAreReported() {
    struct Sink { bool reject = false; unsigned accepted = 0U, rejected = 0U; };
    const auto write = +[](void *id, void *, int32_t) {
        auto &sink = *static_cast<Sink *>(id);
        if (sink.reject) { ++sink.rejected; return 0; }
        ++sink.accepted; return 1;
    };
    for (bool reject : {false, true}) for (bool wrapper : {false, true}) {
        Sink sink;
        auto *encoder = WavpackOpenFileOutput(write, &sink, nullptr); assert(encoder);
        WavpackConfig config{};
        config.sample_rate = 48000; config.num_channels = 1; config.channel_mask = 4;
        config.bits_per_sample = 16; config.bytes_per_sample = 2; config.flags = CONFIG_MD5_CHECKSUM;
        assert(WavpackSetConfiguration(encoder, &config, 16));
        if (wrapper) {
            std::vector<unsigned char> data(17000U, 0x55);
            assert(WavpackAddWrapper(encoder, data.data(), static_cast<uint32_t>(data.size())));
            sink.reject = reject;
            assert((WavpackPackInit(encoder) != 0) == !reject);
        } else {
            assert(WavpackPackInit(encoder));
            int32_t pcm[16]{};
            assert(WavpackPackSamples(encoder, pcm, 16) && WavpackFlushSamples(encoder));
            assert(sink.accepted != 0U);
            unsigned char md5[16]{};
            assert(WavpackStoreMD5Sum(encoder, md5));
            sink.reject = reject;
            assert((WavpackFlushSamples(encoder) != 0) == !reject);
        }
        assert((sink.rejected != 0U) == reject);
        if (reject) assert(std::string(WavpackGetErrorMessage(encoder)).find("write") != std::string::npos);
        WavpackCloseFile(encoder);
    }
}

void hybridShortTailsRemainDecodable() {
    for (int channels : {1, 2}) for (uint32_t frames : {1U, 14U, 15U, 16U, 48001U, 48015U})
      for (bool correction : {false, true}) for (bool oneFrameFlush : {false, true}) {
        if (oneFrameFlush && frames > 16U) continue;
        FILE *file = std::tmpfile(); assert(file);
        FILE *wvc = correction ? std::tmpfile() : nullptr; assert(!correction || wvc);
        auto *encoder = WavpackOpenFileOutput(writeEncodedBlock, file, wvc); assert(encoder);
        WavpackConfig config{};
        config.sample_rate = 48000; config.num_channels = channels; config.channel_mask = channels == 1 ? 4 : 3;
        config.bits_per_sample = 16; config.bytes_per_sample = 2; config.bitrate = 3;
        config.flags = CONFIG_HYBRID_FLAG | CONFIG_DYNAMIC_SHAPING | (correction ? CONFIG_CREATE_WVC : 0);
        config.block_samples = 48000;
        assert(WavpackSetConfiguration(encoder, &config, frames) && WavpackPackInit(encoder));
        std::vector<int32_t> pcm(static_cast<std::size_t>(frames) * channels);
        for (std::size_t i = 0U; i < pcm.size(); ++i) pcm[i] = static_cast<int32_t>((i * 997U) % 32000U) - 16000;
        const auto expected = pcm;
        if (oneFrameFlush) {
            for (uint32_t frame = 0; frame < frames; ++frame)
                assert(WavpackPackSamples(encoder, pcm.data() + frame * channels, 1) && WavpackFlushSamples(encoder));
        } else assert(WavpackPackSamples(encoder, pcm.data(), frames) && WavpackFlushSamples(encoder));
        WavpackCloseFile(encoder);
        assert(std::fflush(file) == 0);
        std::rewind(file);
        if (wvc) { assert(std::fflush(wvc) == 0); std::rewind(wvc); }
        WavpackStreamReader reader{
            +[](void *id, void *data, int32_t count) { return static_cast<int32_t>(std::fread(data, 1U, static_cast<std::size_t>(count), static_cast<FILE *>(id))); },
            +[](void *id) { return static_cast<uint32_t>(std::ftell(static_cast<FILE *>(id))); },
            +[](void *id, uint32_t position) { return std::fseek(static_cast<FILE *>(id), static_cast<long>(position), SEEK_SET); },
            +[](void *id, int32_t delta, int mode) { return std::fseek(static_cast<FILE *>(id), delta, mode); },
            +[](void *id, int c) { return std::ungetc(c, static_cast<FILE *>(id)); },
            +[](void *id) { auto *input = static_cast<FILE *>(id); const auto pos = std::ftell(input);
                assert(std::fseek(input, 0, SEEK_END) == 0); const auto size = std::ftell(input);
                assert(std::fseek(input, pos, SEEK_SET) == 0); return static_cast<uint32_t>(size); },
            +[](void *) { return 1; }, nullptr};
        char error[128]{};
        auto *decoder = WavpackOpenFileInputEx(&reader, file, wvc, error, correction ? OPEN_WVC : 0, 0); assert(decoder);
        assert(WavpackGetNumChannels(decoder) == channels && WavpackGetNumSamples(decoder) == frames);
        assert(WavpackUnpackSamples(decoder, pcm.data(), frames) == frames);
        if (correction) assert(pcm == expected);
        assert(WavpackGetNumErrors(decoder) == 0);
        assert(WavpackUnpackSamples(decoder, pcm.data(), 1) == 0);
        WavpackCloseFile(decoder);
        std::fclose(file);
        if (wvc) std::fclose(wvc);
    }
}

void optionalDecodersPreserveAllocationOwnership(std::vector<unsigned char> &encoded, const char *filename) {
    WavpackStreamReader legacy{
        +[](void *id, void *data, int32_t count) { return static_cast<int32_t>(std::fread(data, 1U, static_cast<std::size_t>(count), static_cast<FILE *>(id))); },
        +[](void *id) { return static_cast<uint32_t>(std::ftell(static_cast<FILE *>(id))); },
        +[](void *id, uint32_t position) { return std::fseek(static_cast<FILE *>(id), static_cast<long>(position), SEEK_SET); },
        +[](void *id, int32_t delta, int mode) { return std::fseek(static_cast<FILE *>(id), delta, mode); },
        +[](void *id, int c) { return std::ungetc(c, static_cast<FILE *>(id)); },
        +[](void *id) { auto *file = static_cast<FILE *>(id); const auto position = std::ftell(file);
            assert(std::fseek(file, 0, SEEK_END) == 0); const auto size = std::ftell(file);
            assert(std::fseek(file, position, SEEK_SET) == 0); return static_cast<uint32_t>(size); },
        +[](void *) { return 1; }, nullptr};
    std::vector<unsigned char> headerless(encoded.begin() + 20, encoded.begin() + 32);
    const auto firstBlockBytes = static_cast<std::size_t>(encoded[4]) + (static_cast<std::size_t>(encoded[5]) << 8U) +
        (static_cast<std::size_t>(encoded[6]) << 16U) + (static_cast<std::size_t>(encoded[7]) << 24U) + 8U;
    assert(firstBlockBytes <= encoded.size());
    headerless.insert(headerless.end(), encoded.begin() + 32, encoded.begin() + static_cast<std::ptrdiff_t>(firstBlockBytes));
    for (const int operation : {0, 1, 2, 3}) {
        auto exercise = [&](int failure) {
            char error[128]{};
            FILE *input = std::fopen(filename, "rb"); assert(input);
            wavpack_allocation_probe::calls = 0;
            wavpack_allocation_probe::failAfter = failure;
            WavpackContext *context{};
            if (operation == 0) context = WavpackOpenRawDecoder(encoded.data(), static_cast<int32_t>(encoded.size()),
                encoded.data(), static_cast<int32_t>(encoded.size()), 0x410, error, OPEN_WRAPPER, 0);
            else if (operation == 1) context = WavpackOpenRawDecoder(headerless.data(), static_cast<int32_t>(headerless.size()),
                nullptr, 0, 0x410, error, OPEN_WRAPPER, 0);
            else if (operation == 2) context = WavpackOpenFileInputEx(&legacy, input, input, error, OPEN_WVC, 0);
            else context = WavpackOpenFileInput(filename, error, OPEN_WVC, 0);
            if (failure < 0) assert(context);
            if (context) WavpackCloseFile(context);
            wavpack_allocation_probe::failAfter = -1;
            std::fclose(input);
        };
        const auto before = wavpack_allocation_probe::live;
        exercise(-1); const auto calls = wavpack_allocation_probe::calls;
        assert(calls != 0U && wavpack_allocation_probe::live == before);
        for (std::size_t failure = 0; failure < calls; ++failure) {
            exercise(static_cast<int>(failure));
            if (wavpack_allocation_probe::live != before) std::fprintf(stderr,
                "optional decoder operation=%d allocation=%zu live=%lld expected=%lld\n", operation, failure,
                static_cast<long long>(wavpack_allocation_probe::live), static_cast<long long>(before));
            assert(wavpack_allocation_probe::live == before);
        }
    }
    FILE *wrapped = std::tmpfile(); assert(wrapped);
    WavpackConfig configuration{};
    configuration.bytes_per_sample = 2; configuration.bits_per_sample = 16;
    configuration.num_channels = 2; configuration.channel_mask = 3; configuration.sample_rate = 48'000;
    auto *encoder = WavpackOpenFileOutput(writeEncodedBlock, wrapped, nullptr);
    assert(encoder && WavpackSetConfiguration64(encoder, &configuration, 64, nullptr));
    unsigned char header[]{'h', 'e', 'a', 'd'}, trailer[]{'t', 'a', 'i', 'l'};
    std::int32_t pcm[128]{};
    assert(WavpackAddWrapper(encoder, header, sizeof(header)) && WavpackPackInit(encoder) &&
        WavpackPackSamples(encoder, pcm, 64) && WavpackFlushSamples(encoder));
    assert(WavpackAddWrapper(encoder, trailer, sizeof(trailer)) && WavpackFlushSamples(encoder));
    WavpackCloseFile(encoder); assert(std::fflush(wrapped) == 0); std::rewind(wrapped);
    char error[128]{};
    const auto before = wavpack_allocation_probe::live;
    auto *decoder = WavpackOpenFileInputEx(&legacy, wrapped, nullptr, error, OPEN_WRAPPER, 0);
    assert(decoder && WavpackGetWrapperBytes(decoder) == sizeof(header));
    auto *retained = WavpackGetWrapperData(decoder);
    wavpack_allocation_probe::failAfter = 0;
    WavpackSeekTrailingWrapper(decoder);
    wavpack_allocation_probe::failAfter = -1;
    assert(WavpackGetWrapperData(decoder) == retained && WavpackGetWrapperBytes(decoder) == sizeof(header));
    assert(std::equal(std::begin(header), std::end(header), retained));
    WavpackSeekTrailingWrapper(decoder);
    assert(WavpackGetWrapperBytes(decoder) == sizeof(header) + sizeof(trailer));
    assert(std::equal(std::begin(trailer), std::end(trailer), WavpackGetWrapperData(decoder) + sizeof(header)));
    WavpackCloseFile(decoder); std::fclose(wrapped);
    assert(wavpack_allocation_probe::live == before);
}
void allocationFailuresAreClosableForCurrentLayouts() {
    for (const int channelMask : {3, 0}) {
        FILE *file = std::tmpfile();
        assert(file);
        WavpackConfig config{};
        config.bytes_per_sample = 4; config.bits_per_sample = 32;
        config.num_channels = 2; config.channel_mask = channelMask;
        config.sample_rate = 48'000; config.float_norm_exp = 127;
        config.block_samples = 64;
        std::vector<std::int32_t> samples(256U * 2U);
        for (std::size_t i = 0; i < samples.size(); ++i) samples[i] = static_cast<std::int32_t>(expected(static_cast<std::uint32_t>(i)));
        auto *encoder = WavpackOpenFileOutput(writeEncodedBlock, file, nullptr);
        assert(encoder && WavpackSetConfiguration64(encoder, &config, 256, nullptr));
        assert(WavpackPackInit(encoder) && WavpackPackSamples(encoder, samples.data(), 256));
        assert(WavpackFlushSamples(encoder));
        WavpackCloseFile(encoder);
        assert(std::fflush(file) == 0);
        const auto bytes = std::ftell(file);
        // Count the actual allocator calls of each operation on a fresh production reader.
        for (const int operation : {0, 1, 2, 3}) {
            const auto exercise = [&] (int failure) {
                std::vector<std::int32_t> decoded(samples.size());
                if (operation == 0) wavpack_allocation_probe::failAfter = failure;
                stemwise::audio::WavPackReader reader(fileno(file), 0, bytes);
                if (operation == 2) assert(reader.read(0, decoded.data(), 64) == 64);
                if (operation != 0) {
                    wavpack_allocation_probe::calls = 0;
                    wavpack_allocation_probe::failAfter = failure;
                }
                const auto first = operation == 2 ? 64U : operation == 3 ? 128U : 0U;
                if (operation != 0) {
                    assert(reader.read(first, decoded.data(), 64) == 64);
                    assert(std::equal(decoded.begin(), decoded.begin() + 128, samples.begin() + first * 2));
                }
            };
            wavpack_allocation_probe::calls = 0;
            exercise(-1);
            const auto calls = wavpack_allocation_probe::calls;
            for (std::size_t failure = 0; failure < calls; ++failure) {
                const auto live = wavpack_allocation_probe::live;
                try { exercise(static_cast<int>(failure)); }
                catch (const std::exception &) {}
                wavpack_allocation_probe::failAfter = -1;
                assert(wavpack_allocation_probe::live == live);
            }
        }
        std::fclose(file);
    }
}
void decodedFiniteExtremeIsRejectedBeforeSpectralPrime(FILE *file) {
    using namespace stemwise::audio::engine;
    for (const float amplitude : {1.0e15F, 17.0F}) {
        TimePitchProcessor processor(48'000U, 480U);
        assert(processor.setControls(0.75F, 3.0F));
        const auto required = processor.primeRequirements().lookaheadInputFrames;
        assert(required <= 65'536U);
        std::vector<std::int32_t> samples(required * 2U, std::bit_cast<std::int32_t>(amplitude));
        std::rewind(file);
        WavpackConfig config{};
        config.bytes_per_sample = 4; config.bits_per_sample = 32; config.num_channels = 2;
        config.sample_rate = 48'000; config.channel_mask = 3; config.float_norm_exp = 127;
        auto *encoder = WavpackOpenFileOutput(writeEncodedBlock, file, nullptr);
        assert(encoder && WavpackSetConfiguration64(encoder, &config, static_cast<std::int64_t>(required), nullptr));
        assert(WavpackPackInit(encoder) && WavpackPackSamples(encoder, samples.data(), static_cast<std::uint32_t>(required)));
        assert(WavpackFlushSamples(encoder));
        WavpackCloseFile(encoder);
        assert(std::fflush(file) == 0);
        const auto bytes = std::ftell(file);
        stemwise::audio::WavPackReader reader(fileno(file), 0, bytes);
        assert(reader.read(0, samples.data(), static_cast<std::uint32_t>(required)) == required);
        std::vector<float> decoded(samples.size());
        for (std::size_t i = 0U; i != samples.size(); ++i) decoded[i] = std::bit_cast<float>(samples[i]);
        assert(decoded[0] == amplitude);
        const auto result = processor.prime(decoded.data(), required);
        assert(result.status == (amplitude > TimePitchProcessor::kMaximumSpectralInputMagnitude
            ? TimePitchPrimeStatus::ProcessingFailure : TimePitchPrimeStatus::Ok));
    }
}
void randomAccessPreservesNonzeroInitialIndex() {
    for (const int mask : {3, 0}) for (const std::uint32_t origin : {0U, 1000U}) {
        FILE *file = std::tmpfile(); assert(file);
        WavpackConfig config{};
        config.bytes_per_sample = 4; config.bits_per_sample = 32;
        config.num_channels = 2; config.channel_mask = mask;
        config.sample_rate = 48'000; config.float_norm_exp = 127; config.block_samples = 64;
        std::vector<std::int32_t> samples(512);
        for (std::size_t i = 0; i < samples.size(); ++i)
            samples[i] = static_cast<std::int32_t>(expected(static_cast<std::uint32_t>(i)));
        auto *encoder = WavpackOpenFileOutput(writeEncodedBlock, file, nullptr);
        assert(encoder && WavpackSetConfiguration64(encoder, &config, 256, nullptr));
        assert(WavpackPackInit(encoder) && WavpackPackSamples(encoder, samples.data(), 256));
        assert(WavpackFlushSamples(encoder)); WavpackCloseFile(encoder);
        assert(std::fflush(file) == 0);
        const auto bytes = std::ftell(file);
        // Embedded sequences can start at an absolute index; update the enclosing header checksum
        // after changing only that index. The independent decoded PCM CRC stays untouched.
        for (long offset = 0; offset < bytes;) {
            WavpackHeader header{};
            char headerFormat[] = WavpackHeaderFormat;
            assert(std::fseek(file, offset, SEEK_SET) == 0);
            assert(std::fread(&header, sizeof(header), 1, file) == 1);
            WavpackLittleEndianToNative(&header, headerFormat);
            header.block_index += origin;
            header.total_samples += origin;
            const auto next = offset + static_cast<long>(header.ckSize) + 8;
            WavpackNativeToLittleEndian(&header, headerFormat);
            assert(std::fseek(file, offset, SEEK_SET) == 0);
            assert(std::fwrite(&header, sizeof(header), 1, file) == 1);
            std::vector<unsigned char> block(static_cast<std::size_t>(next - offset));
            assert(std::fseek(file, offset, SEEK_SET) == 0);
            assert(std::fread(block.data(), 1, block.size(), file) == block.size());
            assert((block[block.size() - 6] & ID_UNIQUE) == ID_BLOCK_CHECKSUM && block[block.size() - 5] == 2);
            std::uint32_t checksum = 0xffffffffU;
            for (std::size_t i = 0; i < block.size() - 6; i += 2)
                checksum = checksum * 3U + block[i] + (static_cast<std::uint32_t>(block[i + 1]) << 8U);
            for (std::size_t i = 0; i < 4; ++i)
                block[block.size() - 4 + i] = static_cast<unsigned char>(checksum >> (i * 8U));
            assert(std::fseek(file, offset, SEEK_SET) == 0);
            assert(std::fwrite(block.data(), 1, block.size(), file) == block.size());
            offset = next;
        }
        assert(std::fflush(file) == 0);
        std::int32_t decoded[194]{};
        for (const std::uint32_t first : {0U, 1U, 63U, 64U, 128U}) {
            stemwise::audio::WavPackReader reader(fileno(file), 0, bytes);
            for (const auto target : {first, 159U, 11U, 255U, 0U}) {
                const auto count = std::min(97U, 256U - target);
                try { assert(reader.read(target, decoded, count) == count); }
                catch (const std::exception &error) {
                    std::fprintf(stderr, "mask=%d origin=%u target=%u: %s\n", mask, origin, target, error.what());
                    throw;
                }
                assert(std::equal(decoded, decoded + count * 2U, samples.begin() + target * 2U));
            }
        }
        std::fclose(file);
    }
}
}
int main(int argc, char **argv) {
    if (argc == 2 && std::string(argv[1]) == "--metadata-writes") {
        encoderMetadataWriteFailuresAreReported(); return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--hybrid-tails") {
        hybridShortTailsRemainDecodable(); return 0;
    }
    encoderMetadataWriteFailuresAreReported();
    hybridShortTailsRemainDecodable();
    randomAccessPreservesNonzeroInitialIndex();
    rawDecoderRejectsShortGuardedInputsAndUnsignedHighBits();
    {
        FILE *invalid = std::tmpfile();
        assert(invalid);
        const unsigned char bytes[64]{};
        assert(std::fwrite(bytes, 1U, sizeof(bytes), invalid) == sizeof(bytes));
        assert(std::fflush(invalid) == 0);
        std::string malformed, unavailable;
        try { stemwise::audio::WavPackReader reader(fileno(invalid), 0, sizeof(bytes)); }
        catch (const std::exception &error) { malformed = error.what(); }
        try { stemwise::audio::WavPackReader reader(-1, 0, sizeof(bytes)); }
        catch (const std::exception &error) { unavailable = error.what(); }
        assert(malformed.starts_with("WavPack open: ") && malformed.size() > 14U);
        assert(unavailable.starts_with("WavPack descriptor: ") && unavailable != malformed);
        assert(malformed.size() <= 256U && unavailable.size() <= 256U);
        std::fclose(invalid);
    }
    assert(argc == 2);
    allocationFailuresAreClosableForCurrentLayouts();
    FILE *input = std::fopen(argv[1], "rb");
    assert(input);
    assert(std::fseek(input, 0, SEEK_END) == 0);
    const long size = std::ftell(input);
    assert(size > 32);
    std::rewind(input);
    std::vector<unsigned char> encoded(static_cast<std::size_t>(size));
    assert(std::fread(encoded.data(), 1, encoded.size(), input) == encoded.size());
    std::fclose(input);
    optionalDecodersPreserveAllocationOwnership(encoded, argv[1]);
    // Exercise the exact descriptor-slice reader with unrelated bytes before and after the media.
    FILE *slice = std::tmpfile();
    assert(slice);
    for (int i = 0; i < 17; ++i) assert(std::fputc(0x55, slice) != EOF);
    assert(std::fwrite(encoded.data(), 1, encoded.size(), slice) == encoded.size());
    for (int i = 0; i < 19; ++i) assert(std::fputc(0xaa, slice) != EOF);
    assert(std::fflush(slice) == 0);
    std::vector<std::int32_t> pcm(kFrames * 2);
    {
        stemwise::audio::WavPackReader reader(fileno(slice), 17, size);
        assert(reader.frames() == kFrames);
        for (std::uint32_t frame = 0; frame < kFrames;) {
            const auto count = reader.read(frame, pcm.data(), 1024);
            assert(count == std::min(1024U, kFrames - frame));
            for (std::uint32_t i = 0; i < count * 2; ++i)
                assert(static_cast<std::uint32_t>(pcm[i]) == expected(frame * 2 + i));
            frame += count;
        }
        for (auto frame : {kFrames - 1, 24003U, 0U, 11U, kFrames - 31}) {
            const auto count = reader.read(frame, pcm.data(), 31);
            for (std::uint32_t i = 0; i < count * 2; ++i)
                assert(static_cast<std::uint32_t>(pcm[i]) == expected(frame * 2 + i));
        }
        assert(reader.read(kFrames, pcm.data(), 1) == 0);
        rejects([&] { reader.read(-1, pcm.data(), 1); });
        rejects([&] { reader.read(kFrames + 1, pcm.data(), 1); });
    }
    // Count each actual seek allocation, then fail each one with a fresh decoder.
    for (auto target : {24003U, 11U, kFrames - 31U}) {
        std::size_t allocations = 0;
        {
            stemwise::audio::WavPackReader reader(fileno(slice), 17, size);
            wavpack_allocation_probe::calls = 0;
            reader.read(target, pcm.data(), 31U);
            allocations = wavpack_allocation_probe::calls;
        }
        for (std::size_t failure = 0; failure < allocations; ++failure) {
            const auto live = wavpack_allocation_probe::live;
            {
                stemwise::audio::WavPackReader reader(fileno(slice), 17, size);
                wavpack_allocation_probe::failAfter = static_cast<int>(failure);
                bool rejected = false;
                try { reader.read(target, pcm.data(), 31U); }
                catch (const std::exception &) { rejected = true; }
                wavpack_allocation_probe::failAfter = -1;
                if (!rejected) std::fprintf(stderr, "seek failure was ignored: target=%u allocation=%zu/%zu\n", target, failure, allocations);
                assert(rejected);
            }
            assert(wavpack_allocation_probe::live == live);
        }
    }
    for (int length = 0; length < 32; ++length) {
        rejects([&] { stemwise::audio::WavPackReader shortHeader(fileno(slice), 17, length); });
    }
    rejects([&] {
        stemwise::audio::WavPackReader truncated(fileno(slice), 17, size - 17);
        truncated.read(0, pcm.data(), kFrames);
    });
    assert(std::fseek(slice, 17 + 28, SEEK_SET) == 0);
    assert(std::fputc(encoded[28] ^ 1U, slice) != EOF); // independently corrupt decoded-block CRC
    assert(std::fflush(slice) == 0);
    rejects([&] {
        stemwise::audio::WavPackReader corrupt(fileno(slice), 17, size);
        corrupt.read(0, pcm.data(), kFrames);
    });
    decodedFiniteExtremeIsRejectedBeforeSpectralPrime(slice);
    std::fclose(slice);
    std::puts("WavPack exact Float32, descriptor bounds, seeking, EOF and corruption: PASS");
}
