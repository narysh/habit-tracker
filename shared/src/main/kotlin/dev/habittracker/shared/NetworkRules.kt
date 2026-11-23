package dev.habittracker.shared

/** HTTP responses that can reasonably succeed when the sync worker retries later. */
fun isTransientHttpStatus(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status in 500..599
