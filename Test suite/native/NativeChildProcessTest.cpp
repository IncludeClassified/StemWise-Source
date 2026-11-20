#include "BoundedTestChildProcess.h"
#include <cassert>
#include <filesystem>
#include <iostream>
#ifndef _WIN32
#include <fstream>
#include <sys/time.h>
static volatile sig_atomic_t interrupted = 0;
static void noteInterrupt(int) { interrupted = 1; }
#endif

int main(int argc, char **argv) {
    if (argc == 2 && std::string(argv[1]) == "--hold-output") {
        std::this_thread::sleep_for(std::chrono::milliseconds(750));
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--pipe-holder") {
#ifdef _WIN32
        STARTUPINFOA startup{};
        startup.cb = sizeof(startup);
        PROCESS_INFORMATION child{};
        std::string command = "\"" + std::filesystem::absolute(argv[0]).string() + "\" --hold-output";
        assert(CreateProcessA(nullptr, command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
            nullptr, nullptr, &startup, &child));
        std::cout << child.dwProcessId << '\n' << std::flush;
        CloseHandle(child.hThread); CloseHandle(child.hProcess);
#else
        const auto child = fork();
        assert(child >= 0);
        if (child == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(750));
            _exit(0);
        }
        std::cout << child << '\n' << std::flush;
#endif
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--output") {
        std::cout << std::string(256U * 1024U, 'x');
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--hang") {
        std::cout << "entered-hang\n" << std::flush;
        std::this_thread::sleep_for(std::chrono::seconds(60));
        return 0;
    }
    const auto executable = std::filesystem::absolute(argv[0]).string();
    assert(stemwise::test::runChild(executable, "--output", std::chrono::seconds(5)).size() == 64U * 1024U);
    bool rejected = false;
    try { stemwise::test::runChild(executable, "--hang", std::chrono::milliseconds(100)); }
    catch (const std::runtime_error &error) { rejected = std::string(error.what()).find("entered-hang") != std::string::npos; }
    assert(rejected);
    const auto started = std::chrono::steady_clock::now();
    const auto descendant = std::stoul(stemwise::test::runChild(
        executable, "--pipe-holder", std::chrono::milliseconds(100)));
    assert(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(500));
#ifdef _WIN32
    const auto descendantHandle = OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(descendant));
    if (descendantHandle) {
        // Job active-count retirement can precede the process object's final signal.
        assert(WaitForSingleObject(descendantHandle, 100) == WAIT_OBJECT_0);
        CloseHandle(descendantHandle);
    } else assert(GetLastError() == ERROR_INVALID_PARAMETER);
#else
    std::ifstream descendantStat("/proc/" + std::to_string(descendant) + "/stat");
    std::string stat;
    if (std::getline(descendantStat, stat)) assert(stat[stat.rfind(')') + 2] == 'Z');
    struct sigaction action{};
    action.sa_handler = noteInterrupt;
    struct sigaction oldAction{};
    assert(sigaction(SIGALRM, &action, &oldAction) == 0);
    itimerval timer{};
    timer.it_value.tv_usec = 1000;
    timer.it_interval.tv_usec = 1000;
    assert(setitimer(ITIMER_REAL, &timer, nullptr) == 0);
    assert(stemwise::test::runChild(executable, "--hold-output", std::chrono::seconds(3)).empty());
    timer = {};
    assert(setitimer(ITIMER_REAL, &timer, nullptr) == 0);
    assert(sigaction(SIGALRM, &oldAction, nullptr) == 0);
    assert(interrupted);
#endif
    rejected = false;
    try { stemwise::test::runChild(executable + ".missing", "--output", std::chrono::milliseconds(100)); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
}
