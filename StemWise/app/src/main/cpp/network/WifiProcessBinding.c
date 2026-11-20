#include <android/multinetwork.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

static void reject_binding(void) {
    static const char message[] = "WIFI_BINDING_UNAVAILABLE\n";
    (void)write(STDERR_FILENO, message, sizeof(message) - 1U);
    _exit(125);
}

/* Loaded only in an owned Python/descendant executable, never in the app process.
 * https://developer.android.com/ndk/reference/group/networking#android_setprocnetwork
 * The binding covers future sockets and DNS and fails when that exact Network disappears.
 */
__attribute__((constructor)) static void bind_owned_child_to_wifi(void) {
    const char *text = getenv("STEMWISE_WIFI_NETWORK_HANDLE");
    if (text == NULL || *text == '\0') reject_binding();
    for (const char *cursor = text; *cursor != '\0'; ++cursor) {
        if (*cursor < '0' || *cursor > '9') reject_binding();
    }
    errno = 0;
    char *end = NULL;
    const unsigned long long value = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value == 0U) reject_binding();
    if (android_setprocnetwork((net_handle_t)value) != 0) reject_binding();

    /* Cancellation/process death must also stop QuickJS and FFmpeg descendants. The guard is
     * inherited through LD_PRELOAD; each exec owns a parent-death signal before its main runs.
     */
    const pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) reject_binding();
}
