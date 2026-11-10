package online.ptsmovies.stream

/** Instrumentation injection exists in debug builds only. */
object ApiProvider {
    var override: (() -> PtsApi)? = null
    fun create(): PtsApi = override?.invoke() ?: PtsApi()
}
