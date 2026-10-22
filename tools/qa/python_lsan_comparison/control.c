#include <stdlib.h>
/* Deliberate diagnostic fault; never linked into scanner binaries. */
__attribute__((noinline, visibility("default"))) void planted_leak(void) {
    void *p = malloc(1237);
    if (!p) abort();
    __asm__ __volatile__("" : : "r"(p) : "memory");
}
#ifndef SHARED_CONTROL
int main(int argc, char **argv) {
    (void)argv;
    if (argc > 1) planted_leak();
    return 0;
}
#endif
