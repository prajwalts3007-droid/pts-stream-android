package online.ptsmovies.stream

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One artwork worker and a bounded waiting list, never on the transfer completion path. */
internal object PosterWork {
    private data class Request(val context: Context, val api: PtsApi, val owner: Long, val title: Title)
    private val queue = Channel<Request>(16)
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init { scope.launch { for (request in queue) {
        try {
            if (DeviceState(request.context).account()?.optLong("id") == request.owner)
                OfflineStore(request.context, request.api, request.owner).cachePoster(request.title)
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { /* Artwork cannot terminate the worker or invalidate saved media. */
        } finally { pending.remove("${request.owner}/${request.title.id}") }
    } } }
    fun enqueue(context: Context, api: PtsApi, owner: Long, title: Title) {
        val key = "$owner/${title.id}"
        if (!pending.add(key)) return
        if (queue.trySend(Request(context.applicationContext, api, owner, title.copy(episodes = emptyList()))).isFailure) pending.remove(key)
    }
}
