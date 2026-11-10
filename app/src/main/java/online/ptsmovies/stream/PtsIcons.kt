package online.ptsmovies.stream

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons matching the outline set used by the web interface. */
object PtsIcons {
    private fun stroke(name: String, data: String, width: Float = 1.8f, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                PathParser().parsePathString(data).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ).build()

    val Home by lazy { stroke("home", "M4 11l8-7 8 7 M6 10v10h12V10") }
    val Download by lazy { stroke("download", "M12 3v11 M8 10l4 4 4-4 M4 19h16") }
    val Queue by lazy { stroke("queue", "M4 6h16 M4 12h16 M4 18h10") }
    val Settings by lazy { stroke("settings", "M15 12a3 3 0 1 1 -6 0a3 3 0 1 1 6 0 M12 2v3 M12 19v3 M4.2 4.2l2.1 2.1 M17.7 17.7l2.1 2.1 M2 12h3 M19 12h3 M4.2 19.8l2.1-2.1 M17.7 6.3l2.1-2.1") }
    val Browse by lazy { stroke("browse", "M3 7h6l2 2h10v10H3z") }
    val Transfers by lazy { stroke("transfers", "M4 7h12l-3-3 M20 17H8l3 3") }
    val Movies by lazy { stroke("movies", "M4 4h16v16H4z M8 4v16 M16 4v16 M4 9h4 M4 15h4 M16 9h4 M16 15h4") }
    val Series by lazy { stroke("series", "M3 5h18v12H3z M8 21h8 M10 21v-4 M14 21v-4") }
    val Watchlist by lazy { stroke("watchlist", "M12 20 C9 18 3 14 3 8.8 C3 3.5 9 2 12 6 C15 2 21 3.5 21 8.8 C21 14 15 18 12 20 Z") }
    val Play by lazy { stroke("play", "M8 5l11 7-11 7z", filled = true) }
    val Pause by lazy { stroke("pause", "M8 5h3v14H8z M13 5h3v14h-3z", filled = true) }
    val Plus by lazy { stroke("plus", "M12 5v14 M5 12h14") }
    val Check by lazy { stroke("check", "M5 13l4 4 10-10") }
    val Search by lazy { stroke("search", "M18 11a7 7 0 1 1 -14 0a7 7 0 1 1 14 0 M20 20l-4.2-4.2") }
    val Back by lazy { stroke("back", "M15 6l-6 6 6 6") }
    val More by lazy { stroke("more", "M6 12h.01 M12 12h.01 M18 12h.01", width = 2.6f) }
    val Close by lazy { stroke("close", "M6 6l12 12 M18 6L6 18") }
    val Refresh by lazy { stroke("refresh", "M20 12 C20 7.6 16.4 4 12 4 C7.6 4 4 7.6 4 12 C4 16.4 7.6 20 12 20 C15.2 20 17.9 18.2 19.2 15.5 M20 5v7h-7") }
    val Retry by lazy { stroke("retry", "M4 12 C4 7.6 7.6 4 12 4 C16.4 4 20 7.6 20 12 C20 16.4 16.4 20 12 20 C8.8 20 6.1 18.2 4.8 15.5 M4 5v7h7") }
    val Edit by lazy { stroke("edit", "M4 20h4L20 8l-4-4L4 16z M14 6l4 4") }
    val Trash by lazy { stroke("trash", "M5 7h14 M9 7V5h6v2 M7 7l1 13h8l1-13") }
    val ChevronRight by lazy { stroke("chevron", "M9 6l6 6-6 6") }
    val Up by lazy { stroke("up", "M12 19V5 M6 11l6-6 6 6") }
    val Upload by lazy { stroke("upload", "M12 21V10 M8 14l4-4 4 4 M4 5h16") }
    val FolderPlus by lazy { stroke("folderplus", "M3 7h6l2 2h10v10H3z M12 12v5 M9.5 14.5h5") }
    val User by lazy { stroke("user", "M16 8a4 4 0 1 1 -8 0a4 4 0 1 1 8 0 M4 21c1.6-4 4.4-6 8-6s6.4 2 8 6") }
    val Shield by lazy { stroke("shield", "M12 3l7 3v6c0 4.2-2.9 7.4-7 9-4.1-1.6-7-4.8-7-9V6z") }
    val Info by lazy { stroke("info", "M21 12a9 9 0 1 1 -18 0a9 9 0 1 1 18 0 M12 11v6 M12 7.5h.01") }
    val Logout by lazy { stroke("logout", "M15 12H4 M8 8l-4 4 4 4 M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-4") }
    val Cpu by lazy { stroke("cpu", "M7 7h10v10H7z M9 2v3 M15 2v3 M9 19v3 M15 19v3 M2 9h3 M2 15h3 M19 9h3 M19 15h3") }
    val Memory by lazy { stroke("memory", "M4 6h16v10H4z M7 10h3 M12 10h3 M7 13h10 M6 19v-3 M10 19v-3 M14 19v-3 M18 19v-3") }
    val Storage by lazy { stroke("storage", "M4 7c0 1.7 3.6 3 8 3s8-1.3 8-3-3.6-3-8-3-8 1.3-8 3z M4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3") }
    val NetworkDown by lazy { stroke("netdown", "M12 4v13 M7 12l5 5 5-5 M5 21h14") }
    val NetworkUp by lazy { stroke("netup", "M12 20V7 M7 12l5-5 5 5 M5 3h14") }
    val Speed by lazy { stroke("speed", "M12 14l4-4 M5.6 18a9 9 0 1 1 12.8 0") }
    val Subtitles by lazy { stroke("subtitles", "M4 5h16v14H4z M7 11h4 M14 11h3 M7 15h9") }
    val Fit by lazy { stroke("fit", "M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5") }
    val Pip by lazy { stroke("pip", "M3 5h18v14H3z M12 11h7v6h-7z") }
    val Grid by lazy { stroke("grid", "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z") }
    val List by lazy { stroke("list", "M4 5h3v3H4z M11 6h9 M4 11h3v3H4z M11 12h9 M4 17h3v3H4z M11 18h9") }
    val Lock by lazy { stroke("lock", "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3") }
    val Clock by lazy { stroke("clock", "M21 12a9 9 0 1 1 -18 0a9 9 0 1 1 18 0 M12 7v5l3 2") }
    val Film by lazy { stroke("film", "M4 4h16v16H4z M8 4v16 M16 4v16 M4 9h4 M4 15h4 M16 9h4 M16 15h4") }
    val Tv by lazy { stroke("tv", "M3 5h18v12H3z M8 21h8 M10 21v-4 M14 21v-4") }
    val ChevronDown by lazy { stroke("chevrondown", "M6 9l6 6 6-6") }
    val Volume by lazy { stroke("volume", "M3 9h4l5-4v14l-5-4H3z M16 8c3 2 3 6 0 8 M19 5c5 4 5 10 0 14") }
    val Muted by lazy { stroke("muted", "M3 9h4l5-4v14l-5-4H3z M16 9l6 6 M22 9l-6 6") }
    val Previous by lazy { stroke("previous", "M5 5v14 M19 5L8 12l11 7z") }
    val Next by lazy { stroke("next", "M19 5v14 M5 5l11 7-11 7z") }
}
