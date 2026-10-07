package li.songe.gkd.bypass

import java.util.concurrent.atomic.AtomicLong

/** Node and visual actions share ownership through post-action verification. */
class BypassExecutionArbiter {
    private val sequence = AtomicLong(0)
    private val owner = AtomicLong(0)
    val busy: Boolean get() = owner.get() != 0L

    fun tryAcquire(): Long? {
        val token = sequence.incrementAndGet()
        return if (owner.compareAndSet(0L, token)) token else null
    }

    fun release(token: Long): Boolean = owner.compareAndSet(token, 0L)
}
