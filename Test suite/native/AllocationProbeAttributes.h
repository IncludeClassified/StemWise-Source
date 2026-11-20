#pragma once

// Prevent GCC from treating a malloc-backed replacement new/delete test pair as mismatched.
// Keep the allocation interception itself active in optimized probe builds.
#if defined(_MSC_VER)
#define STEMWISE_ALLOCATION_HOOK __declspec(noinline)
#elif defined(__GNUC__) || defined(__clang__)
#define STEMWISE_ALLOCATION_HOOK __attribute__((noinline))
#else
#define STEMWISE_ALLOCATION_HOOK
#endif
