#include "wavpack_local.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int reject_realloc;
void *stemwise_wavpack_tag_test_realloc(void *memory, size_t bytes) {
    if (reject_realloc) { reject_realloc = 0; return NULL; }
    return realloc(memory, bytes);
}

typedef struct { int call, reject; int32_t bytes[3]; } Sink;
static int32_t write_bytes(void *id, void *data, int32_t count) {
    Sink *sink = id;
    (void)data;
    assert(sink->call < 3);
    sink->bytes[sink->call++] = count;
    return sink->call == sink->reject ? 0 : count;
}
static int blockout(void *id, void *data, int32_t count) { return write_bytes(id, data, count) == count; }
static int seekable(void *id) { (void)id; return 1; }
static int seek_relative(void *id, int64_t offset, int mode) { (void)id; (void)offset; (void)mode; return 0; }

int main(void) {
    for (int binary = 0; binary < 2; ++binary) {
        WavpackContext context = {0};
        char value[16] = {0};
        assert(WavpackAppendTagItem(&context, "Title", "retained", 8));
        const APE_Tag_Hdr before = context.m_tag.ape_tag_hdr;
        unsigned char *const before_data = context.m_tag.ape_tag_data;
        reject_realloc = 1;
        assert(!(binary ? WavpackAppendBinaryTagItem(&context, "Cover", "image", 5)
                        : WavpackAppendTagItem(&context, "Artist", "second", 6)));
        assert(!reject_realloc);
        assert(context.m_tag.ape_tag_data == before_data);
        assert(memcmp(&context.m_tag.ape_tag_hdr, &before, sizeof(before)) == 0);
        assert(strstr(context.error_message, "allocat") != NULL);
        assert(WavpackGetTagItem(&context, "Title", value, sizeof(value)) == 8);
        assert(strcmp(value, "retained") == 0);
        assert(WavpackAppendTagItem(&context, "Artist", "retry", 5));
        assert(WavpackGetNumTagItems(&context) == 2);
        assert(WavpackGetTagItem(&context, "Artist", value, sizeof(value)) == 5);
        assert(strcmp(value, "retry") == 0);
        free(context.m_tag.ape_tag_data);
    }
    for (int reader_mode = 0; reader_mode < 2; ++reader_mode) {
        for (int reject = 0; reject <= 3; ++reject) {
            WavpackContext context = {0};
            Sink sink = {0}; sink.reject = reject;
            WavpackStreamReader64 reader = {0};
            unsigned char payload[13] = {0};
            APE_Tag_Hdr *header = &context.m_tag.ape_tag_hdr;
            memcpy(header->ID, "APETAGEX", 8);
            header->version = 2000; header->item_count = 1;
            header->length = sizeof(*header) + sizeof(payload);
            header->flags = APE_TAG_CONTAINS_HEADER;
            context.m_tag.ape_tag_data = payload;
            context.m_tag.tag_file_pos = -(int64_t)(header->length + sizeof(*header));
            const APE_Tag_Hdr before = *header;
            if (reader_mode) {
                reader.write_bytes = write_bytes; reader.can_seek = seekable; reader.set_pos_rel = seek_relative;
                context.reader = &reader; context.wv_in = &sink; context.open_flags = OPEN_EDIT_TAGS;
            } else { context.blockout = blockout; context.wv_out = &sink; }
            assert((WavpackWriteTag(&context) != 0) == (reject == 0));
            assert(memcmp(header, &before, sizeof(before)) == 0);
            assert(sink.call == 3 && sink.bytes[0] == 32 && sink.bytes[1] == 13 && sink.bytes[2] == 32);
            if (reject) assert(strstr(context.error_message, "write") != NULL);
        }
    }
    for (int track = 0; track <= 255; ++track) {
        if (track != 0 && track != 1 && track != 127 && track != 128 && track != 200 && track != 255) continue;
        WavpackContext context = {0};
        memcpy(context.m_tag.id3_tag.tag_id, "TAG", 3);
        context.m_tag.id3_tag.comment[29] = (char)track;
        char value[16] = {0}, expected[16];
        const int length = WavpackGetTagItem(&context, "track", value, sizeof(value));
        if (!track) assert(length == 0);
        else { snprintf(expected, sizeof(expected), "%d", track); assert(length > 0 && strcmp(value, expected) == 0); }
    }
    return 0;
}
