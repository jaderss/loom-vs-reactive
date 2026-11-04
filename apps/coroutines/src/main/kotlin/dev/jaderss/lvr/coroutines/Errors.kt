package dev.jaderss.lvr.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Duration
import kotlin.time.toKotlinDuration

class AccountNotFoundException : RuntimeException("account not found", null, false, false)

class InvalidLimitException : RuntimeException("limit out of range", null, false, false)

class DownstreamUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

suspend fun <T> withDeadline(
    timeout: Duration,
    failure: (Throwable) -> RuntimeException,
    call: suspend () -> T,
): T =
    try {
        withTimeout(timeout.toKotlinDuration()) { call() }
    } catch (e: TimeoutCancellationException) {
        throw failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw failure(e)
    }
