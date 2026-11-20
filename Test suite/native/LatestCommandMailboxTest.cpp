#include "native_engine/runtime/LatestCommandMailbox.h"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace stemwise::audio::engine;

namespace stemwise::audio::engine::command_mailbox_test {
LatestCommandMailbox *churnMailbox = nullptr;
std::uint64_t nextRevision = 1U;
unsigned attempts = 0U;
void afterReaderHazard() noexcept {
    if (!churnMailbox) return;
    ++attempts;
    NativePlaybackCommandSnapshot next{};
    next.revision = ++nextRevision;
    if (!churnMailbox->publish(next, [] {}).accepted) std::abort();
}
}

static void require(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}

int main() {
    try {
        LatestCommandMailbox mailbox;
        NativePlaybackCommandSnapshot first{};
        first.revision = 1U;
        require(mailbox.publish(first, [] {}).accepted, "first publication rejected");
        NativePlaybackCommandSnapshot second{};
        second.revision = 2U;
        const auto publication = mailbox.publish(second, [&] {
            NativePlaybackCommandSnapshot read{};
            require(mailbox.readLatest(0U, read) && read.revision == 1U,
                "previous command was not consumed before replacement visibility");
        });
        require(publication.accepted && !publication.supersededUnread,
            "a consumed command was also classified as superseded");
        NativePlaybackCommandSnapshot read{};
        require(mailbox.readLatest(1U, read) && read.revision == 2U,
            "replacement command was lost");
        require(!mailbox.hasUnreadPublication(), "consumed mailbox remains pending");

        LatestCommandMailbox unread;
        require(unread.publish(first, [] {}).accepted, "unread first publication rejected");
        require(unread.publish(second, [] {}).supersededUnread, "unread command was not superseded");
        require(unread.readLatest(0U, read) && read.revision == 2U, "latest-only delivery changed");

        LatestCommandMailbox churning;
        require(churning.publish(first, [] {}).accepted, "churn initial publication rejected");
        command_mailbox_test::churnMailbox = &churning;
        read.revision = 999U;
        require(!churning.readLatest(0U, read), "contended worker read must defer");
        command_mailbox_test::churnMailbox = nullptr;
        require(command_mailbox_test::attempts == 8U && read.revision == 999U,
            "publication churn must be bounded and preserve the caller's snapshot");
        require(churning.hasUnreadPublication() && churning.readLatest(0U, read) && read.revision == 9U,
            "a deferred worker iteration must retain the newest command");

        LatestCommandMailbox concurrent;
        constexpr std::uint64_t total = 50'000U;
        std::atomic<bool> finished{false};
        std::uint64_t consumed = 0U;
        std::uint64_t superseded = 0U;
        std::uint64_t lastRead = 0U;
        std::thread reader([&] {
            while (!finished.load(std::memory_order_acquire) || concurrent.hasUnreadPublication()) {
                NativePlaybackCommandSnapshot value{};
                if (concurrent.readLatest(lastRead, value)) {
                    if (value.revision <= lastRead) std::abort();
                    lastRead = value.revision;
                    ++consumed;
                }
            }
        });
        for (std::uint64_t revision = 1U; revision <= total; ++revision) {
            NativePlaybackCommandSnapshot value{};
            value.revision = revision;
            const auto result = concurrent.publish(value, [] { std::this_thread::yield(); });
            if (!result.accepted) std::abort();
            if (result.supersededUnread) ++superseded;
        }
        finished.store(true, std::memory_order_release);
        reader.join();
        require(lastRead == total && consumed + superseded == total,
            "concurrent command accounting is not an exclusive partition");
        std::cout << "Latest command mailbox tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
