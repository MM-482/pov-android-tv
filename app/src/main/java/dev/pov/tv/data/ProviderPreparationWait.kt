package dev.pov.tv.data

/** Polls an already-created transfer. The creation operation is deliberately outside this helper. */
internal class ProviderPreparationWait(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = Thread::sleep,
    private val timeoutMs: Long = 300_000,
    private val intervalMs: Long = 1_800,
) {
    fun <T : Any> await(service: String, transferId: String, read: () -> T?): T {
        val deadline = clock() + timeoutMs
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Preparation canceled.")
            try {
                val result = read()
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Preparation canceled.")
                if (result != null) return result
            } catch (error: ApiException) {
                // Authentication, quota, invalid identities and provider-level failures stop immediately.
                if (error.code !in setOf(404, 408, 425) && error.code !in 500..599) throw error
            }
            val remaining = deadline - clock()
            if (remaining <= 0) throw ApiException(0,
                "$service is still processing transfer $transferId. It remains in your account; open it from the service library when ready.")
            pause(minOf(intervalMs, remaining).coerceAtLeast(1))
        }
    }
}
