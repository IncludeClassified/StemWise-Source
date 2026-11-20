#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <stdexcept>
#include <string>
#include <thread>
#ifdef _WIN32
#include <windows.h>
#else
#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

namespace stemwise::test {

// Test-owned children use argv directly, drain even discarded output, and always retire/reap.
inline std::string runChild(const std::string &executable, const char *argument,
    std::chrono::milliseconds timeout = std::chrono::seconds(60)) {
    constexpr std::size_t limit = 64U * 1024U;
    std::string output;
    output.reserve(limit);
    const auto append = [&](const char *data, std::size_t size) {
        output.append(data, (std::min)(size, limit - output.size()));
    };
    std::array<char, 4096U> buffer{};
    bool timedOut = false;
    int status = 0;
#ifdef _WIN32
    SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    HANDLE readPipe{}, writePipe{};
    if (!CreatePipe(&readPipe, &writePipe, &attributes, 0)) throw std::runtime_error("child pipe failed");
    if (!SetHandleInformation(readPipe, HANDLE_FLAG_INHERIT, 0)) {
        CloseHandle(readPipe); CloseHandle(writePipe);
        throw std::runtime_error("child pipe inheritance failed");
    }
    STARTUPINFOA startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdOutput = startup.hStdError = writePipe;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    PROCESS_INFORMATION process{};
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job || !SetInformationJobObject(job, JobObjectExtendedLimitInformation,
            &limits, sizeof(limits))) {
        if (job) CloseHandle(job);
        CloseHandle(readPipe); CloseHandle(writePipe);
        throw std::runtime_error("child job creation failed");
    }
    std::string command = "\"" + executable + "\" " + argument;
    const auto created = CreateProcessA(executable.c_str(), command.data(), nullptr, nullptr,
        TRUE, CREATE_NO_WINDOW | CREATE_SUSPENDED, nullptr, nullptr, &startup, &process);
    CloseHandle(writePipe);
    if (!created) { CloseHandle(job); CloseHandle(readPipe); throw std::runtime_error("child launch failed"); }
    if (!AssignProcessToJobObject(job, process.hProcess) || ResumeThread(process.hThread) == DWORD(-1)) {
        TerminateProcess(process.hProcess, 2);
        WaitForSingleObject(process.hProcess, 5000);
        CloseHandle(process.hThread); CloseHandle(process.hProcess); CloseHandle(job); CloseHandle(readPipe);
        throw std::runtime_error("child containment failed");
    }
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    auto cleanupDeadline = deadline + std::chrono::seconds(5);
    bool retiring = false;
    for (;;) {
        DWORD available = 0;
        for (unsigned chunk = 0; chunk != 16 && PeekNamedPipe(readPipe, nullptr, 0, nullptr, &available, nullptr) && available; ++chunk) {
            DWORD count = 0;
            if (!ReadFile(readPipe, buffer.data(), std::min<DWORD>(available, static_cast<DWORD>(buffer.size())), &count, nullptr)) break;
            append(buffer.data(), count);
        }
        if (WaitForSingleObject(process.hProcess, 0) == WAIT_OBJECT_0) {
            if (!retiring) {
                TerminateJobObject(job, 2);
                retiring = true;
                cleanupDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
            }
            if (!PeekNamedPipe(readPipe, nullptr, 0, nullptr, &available, nullptr) || !available) break;
        }
        if (!timedOut && std::chrono::steady_clock::now() >= deadline) {
            timedOut = true;
            TerminateJobObject(job, 2);
            retiring = true;
        }
        if (std::chrono::steady_clock::now() >= cleanupDeadline) {
            timedOut = true;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    bool retired = false;
    for (;;) {
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting{};
        if (!QueryInformationJobObject(job, JobObjectBasicAccountingInformation,
                &accounting, sizeof(accounting), nullptr)) break;
        if (accounting.ActiveProcesses == 0) { retired = true; break; }
        if (std::chrono::steady_clock::now() >= cleanupDeadline) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    DWORD exitCode = 0;
    const auto exitKnown = GetExitCodeProcess(process.hProcess, &exitCode);
    status = static_cast<int>(exitCode);
    CloseHandle(job);
    CloseHandle(process.hThread); CloseHandle(process.hProcess); CloseHandle(readPipe);
    if (!retired || !exitKnown) throw std::runtime_error("child retirement not established: " + output);
#else
    int pipeEnds[2];
    if (pipe(pipeEnds)) throw std::runtime_error("child pipe failed");
    const auto child = fork();
    if (child == 0) {
        if (setpgid(0, 0)) _exit(127);
        close(pipeEnds[0]);
        dup2(pipeEnds[1], STDOUT_FILENO); dup2(pipeEnds[1], STDERR_FILENO);
        close(pipeEnds[1]);
        execl(executable.c_str(), executable.c_str(), argument, static_cast<char *>(nullptr));
        _exit(127);
    }
    close(pipeEnds[1]);
    if (child < 0) { close(pipeEnds[0]); throw std::runtime_error("child launch failed"); }
    // The child also creates its group before exec; retain its zombie until retirement
    // so a group signal can never target a recycled direct-child PID.
    if (setpgid(child, child) && errno != EACCES) {
        kill(child, SIGKILL); while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
        close(pipeEnds[0]); throw std::runtime_error("child process group failed");
    }
    if (fcntl(pipeEnds[0], F_SETFL, O_NONBLOCK) < 0) {
        kill(-child, SIGKILL); while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
        close(pipeEnds[0]); throw std::runtime_error("child nonblocking pipe failed");
    }
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    bool exited = false;
    bool retiring = false;
    auto cleanupDeadline = deadline + std::chrono::seconds(5);
    for (;;) {
        bool ended = false;
        for (unsigned chunk = 0; chunk != 16; ++chunk) {
            const auto count = read(pipeEnds[0], buffer.data(), buffer.size());
            if (count > 0) append(buffer.data(), static_cast<std::size_t>(count));
            else { ended = count == 0; break; }
        }
        if (!exited) {
            siginfo_t info{};
            const auto result = waitid(P_PID, child, &info, WEXITED | WNOHANG | WNOWAIT);
            if (result == 0 && info.si_pid == child) exited = true;
            else if (result < 0 && errno != EINTR) {
                kill(-child, SIGKILL); while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
                close(pipeEnds[0]); throw std::runtime_error("child wait failed");
            }
        }
        if (exited && !retiring) {
            kill(-child, SIGKILL);
            retiring = true;
            cleanupDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        }
        if (exited && ended) break;
        if (!timedOut && std::chrono::steady_clock::now() >= deadline) {
            timedOut = true;
            kill(-child, SIGKILL);
            retiring = true;
        }
        if (std::chrono::steady_clock::now() >= cleanupDeadline) {
            timedOut = true;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    close(pipeEnds[0]);
    for (;;) {
        const auto result = waitpid(child, &status, WNOHANG);
        if (result == child) break;
        if (result < 0 && errno != EINTR) throw std::runtime_error("child reap failed");
        if (std::chrono::steady_clock::now() >= cleanupDeadline)
            throw std::runtime_error("child retirement deadline exceeded");
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (!WIFEXITED(status)) status = 1; else status = WEXITSTATUS(status);
#endif
    if (timedOut || status != 0) throw std::runtime_error(
        std::string(timedOut ? "child deadline exceeded: " : "child failed: ") + output);
    while (!output.empty() && (output.back() == '\r' || output.back() == '\n' || output.back() == ' ')) output.pop_back();
    return output;
}

} // namespace stemwise::test
