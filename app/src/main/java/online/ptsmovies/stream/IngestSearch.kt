package online.ptsmovies.stream

/** Only read-only searches may retry; never replay queue submissions or authentication failures. */
internal fun retryableIngestSearch(error: Exception): Boolean = when (error) {
    is ApiException -> error.status == 408 || error.status in 500..599
    is java.io.IOException -> true
    else -> false
}
