#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <limits.h>

/* Worker termination removes its stale stack roots before the leak check.
 * The compiler barrier preserves the allocation without storing a live root. */
__attribute__((noinline)) static void *lose_native_allocation(void *unused) {
    (void)unused;
    void *p = malloc(12347);
    if (!p) abort();
    __asm__ __volatile__("" : : "r"(p) : "memory");
    return NULL;
}
void rf_proof_leak(void) {
    pthread_t worker;
    if (pthread_create(&worker, NULL, lose_native_allocation, NULL)) abort();
    if (pthread_join(worker, NULL)) abort();
}
void rf_proof_uaf(void) {
    volatile unsigned char *p = malloc(32);
    if (!p) abort();
    free((void *)p);
    p[7] = 42;
}
void rf_proof_ub(void) {
    volatile int max = INT_MAX;
    volatile int result = max + 1;
    (void)result;
}
