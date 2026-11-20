#include <jni.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <sstream>
#include <signal.h>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
constexpr auto receiptName = "/.stemwise-tool-owner";
bool readSmallFile(const std::string& path, std::string& value) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    char buffer[512];
    const auto count = read(fd, buffer, sizeof(buffer));
    close(fd);
    if (count <= 0 || count == sizeof(buffer)) return false;
    value.assign(buffer, static_cast<size_t>(count));
    return true;
}
struct ProcessIdentity { char state{}; long group{}; unsigned long long birth{}; };
bool processIdentity(pid_t pid, ProcessIdentity& result) {
    std::string text;
    if (!readSmallFile("/proc/" + std::to_string(pid) + "/stat", text)) return false;
    const auto end = text.rfind(')');
    if (end == std::string::npos) return false;
    std::istringstream fields(text.substr(end + 1));
    std::string field;
    for (int number = 3; number <= 22; ++number) {
        if (!(fields >> field)) return false;
        if (number == 3) result.state = field[0];
        if (number == 5) result.group = strtol(field.c_str(), nullptr, 10);
        if (number == 22) result.birth = strtoull(field.c_str(), nullptr, 10);
    }
    return result.birth != 0 && result.group > 0;
}
bool validBootIdentity(const std::string& result) {
    if (result.size() != 36) return false;
    for (size_t i = 0; i < result.size(); ++i) {
        const auto c = result[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
        else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}
bool bootIdentity(std::string& result) {
    if (!readSmallFile("/proc/sys/kernel/random/boot_id", result)) return false;
    if (!result.empty() && result.back() == '\n') result.pop_back();
    return validBootIdentity(result);
}
// The lease inode is permanent. A receipt is published only while its child is blocked before exec.
bool reconcileOwner(const std::string& directory) {
    const auto path = directory + receiptName;
    struct stat info{};
    if (lstat(path.c_str(), &info) != 0) return errno == ENOENT;
    if (!S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_nlink != 1) return false;
    std::string text, boot, expectedBoot, extra;
    long pid = 0;
    unsigned long long birth = 0;
    if (!readSmallFile(path, text) || !bootIdentity(boot)) return false;
    std::istringstream receipt(text);
    std::string version;
    if (!(receipt >> version >> expectedBoot >> pid >> birth) || (receipt >> extra) ||
        version != "STEMWISE_TOOL_OWNER_1" || !validBootIdentity(expectedBoot) ||
        pid <= 0 || pid > std::numeric_limits<pid_t>::max() || birth == 0) return false;
    if (boot == expectedBoot) {
        ProcessIdentity leader;
        errno = 0;
        const bool exists = processIdentity(static_cast<pid_t>(pid), leader);
        if (!exists && errno != ENOENT && errno != ESRCH) return false;
        // A reused leader proves the old group ended; otherwise kernel group existence includes fd-closing descendants.
        if ((!exists || leader.birth == birth) &&
            (kill(-static_cast<pid_t>(pid), 0) == 0 || errno != ESRCH)) return false;
    }
    return unlink(path.c_str()) == 0;
}
bool publishOwner(const std::string& directory, pid_t pid) {
    std::string boot;
    ProcessIdentity identity;
    if (!bootIdentity(boot) || !processIdentity(pid, identity)) return false;
    const auto value = "STEMWISE_TOOL_OWNER_1\n" + boot + "\n" + std::to_string(pid) + "\n" +
        std::to_string(identity.birth) + "\n";
    const auto temporary = directory + "/.stemwise-tool-owner-pending";
    const int fd = open(temporary.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    const bool written = write(fd, value.data(), value.size()) == static_cast<ssize_t>(value.size()) && fsync(fd) == 0;
    close(fd);
    if (!written || rename(temporary.c_str(), (directory + receiptName).c_str()) != 0) return false;
    const int root = open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) return false;
    const bool synced = fsync(root) == 0;
    close(root);
    return synced;
}
std::string utf8(JNIEnv* env, jstring value) {
    auto type = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(type, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    env->DeleteLocalRef(encoding);
    env->DeleteLocalRef(type);
    if (!bytes || env->ExceptionCheck()) return {};
    std::string result(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data()));
    env->DeleteLocalRef(bytes);
    return result;
}
std::vector<std::string> strings(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (!value) return {};
        result.push_back(utf8(env, value));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck() || result.back().find('\0') != std::string::npos) return {};
    }
    return result;
}
std::vector<char*> pointers(std::vector<std::string>& values) {
    std::vector<char*> result;
    for (auto& value : values) result.push_back(value.data());
    result.push_back(nullptr);
    return result;
}
}
extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_integration_OwnedToolNative_acquireLease(JNIEnv* env, jobject, jstring directory) {
    const auto path = utf8(env, directory);
    if (env->ExceptionCheck() || path.empty()) return -1;
    const int fd = open((path + "/.stemwise-tool-lease").c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return -1;
    struct stat info{};
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_nlink != 1 ||
        flock(fd, LOCK_EX | LOCK_NB) != 0 || !reconcileOwner(path)) { close(fd); return -1; }
    return fd;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_integration_OwnedToolNative_releaseLease(JNIEnv* env, jobject, jint fd, jstring directory) {
    const auto path = utf8(env, directory);
    if (env->ExceptionCheck()) return JNI_FALSE;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!reconcileOwner(path)) {
        if (std::chrono::steady_clock::now() >= deadline) return JNI_FALSE;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    close(fd);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_app_stemwise_integration_OwnedToolNative_spawn(JNIEnv* env, jobject, jobjectArray command,
                                                  jobjectArray environment, jstring cwd, jint leaseFd,
                                                  jstring leaseDirectory) {
    auto args = strings(env, command);
    auto vars = strings(env, environment);
    auto directory = utf8(env, cwd);
    const auto ownershipDirectory = utf8(env, leaseDirectory);
    if (env->ExceptionCheck() || args.empty() || args[0].empty() || args[0][0] != '/' ||
        directory.find('\0') != std::string::npos) return nullptr;
    auto argv = pointers(args);
    auto envp = pointers(vars);
    auto result = env->NewIntArray(4);
    if (!result) return nullptr;
    int out[2], err[2], in[2], gate[2];
    if (pipe2(out, O_CLOEXEC) != 0) return nullptr;
    if (pipe2(err, O_CLOEXEC) != 0) { close(out[0]); close(out[1]); return nullptr; }
    if (pipe2(in, O_CLOEXEC) != 0) {
        close(out[0]); close(out[1]); close(err[0]); close(err[1]); return nullptr;
    }
    if (pipe2(gate, O_CLOEXEC) != 0) {
        close(in[0]); close(in[1]); close(out[0]); close(out[1]); close(err[0]); close(err[1]); return nullptr;
    }
    // Allocate all data before fork. The child performs only async-signal-safe system calls.
    const auto pid = fork();
    if (pid == 0) {
        close(gate[1]);
        char committed = 0;
        // EOF on owner death before publication never permits a tool to execute.
        if (read(gate[0], &committed, 1) != 1 || committed != 1 || fcntl(leaseFd, F_SETFD, 0) != 0) _exit(126);
        close(gate[0]);
        if (setsid() < 0 || dup2(in[0], STDIN_FILENO) < 0 || dup2(out[1], STDOUT_FILENO) < 0 ||
            dup2(err[1], STDERR_FILENO) < 0 || (!directory.empty() && chdir(directory.c_str()) != 0)) _exit(126);
        close(in[0]); close(in[1]); close(out[0]); close(out[1]); close(err[0]); close(err[1]);
        execve(argv[0], argv.data(), envp.data());
        _exit(127);
    }
    close(gate[0]);
    close(in[0]); close(out[1]); close(err[1]);
    if (pid < 0) { close(gate[1]); close(in[1]); close(out[0]); close(err[0]); return nullptr; }
    const char committed = 1;
    // A publication failure closes the gate; normal bounded retirement still owns this child and its pipes.
    if (publishOwner(ownershipDirectory, pid)) write(gate[1], &committed, 1);
    close(gate[1]);
    const jint fields[] = {static_cast<jint>(pid), in[1], out[0], err[0]};
    env->SetIntArrayRegion(result, 0, 4, fields);
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_app_stemwise_integration_OwnedToolNative_poll(JNIEnv*, jobject, jint pid) {
    siginfo_t status{};
    // WNOWAIT retains the exact child PID until the owner kills/retires its entire session group.
    if (waitid(P_PID, static_cast<id_t>(pid), &status, WEXITED | WNOHANG | WNOWAIT) != 0) return -2;
    if (status.si_pid == 0) return -1;
    return status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
}
extern "C" JNIEXPORT void JNICALL
Java_app_stemwise_integration_OwnedToolNative_killGroup(JNIEnv*, jobject, jint pid) {
    // The direct child is still unreaped, so neither its PID nor session-group ID can be reused.
    kill(-pid, SIGKILL);
    kill(pid, SIGKILL); // Covers cancellation racing the child's setsid call.
}
extern "C" JNIEXPORT jboolean JNICALL
Java_app_stemwise_integration_OwnedToolNative_reap(JNIEnv*, jobject, jint pid, jint timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    do {
        const auto result = waitpid(pid, nullptr, WNOHANG);
        if (result == pid) return JNI_TRUE;
        if (result < 0 && errno != EINTR) return JNI_FALSE;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    } while (std::chrono::steady_clock::now() < deadline);
    return JNI_FALSE;
}
