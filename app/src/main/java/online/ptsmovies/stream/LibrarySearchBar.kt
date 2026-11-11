package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One compact row; descriptions stay accessible without visible button labels. */
@Composable
internal fun LibrarySearchBar(query: String, onQuery: (String) -> Unit, onFilters: () -> Unit,
    view: String? = null, onViewChange: (String) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().testTag("library-search-row"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PtsSearchField(query, { onQuery(it.take(160)) }, "Titles or episode names", Modifier.weight(1f).testTag("library-search-input"))
        Box(Modifier.testTag("library-filter-action")) {
            PtsIconAction(PtsIcons.Filter, "Library filters", onFilters, tint = Pts.accent)
        }
        if (view != null) Box(Modifier.testTag("library-view-action")) {
            PtsIconAction(if (view == "grid") PtsIcons.List else PtsIcons.Grid,
                if (view == "grid") "Switch to list view" else "Switch to grid view",
                { onViewChange(if (view == "grid") "list" else "grid") }, tint = Pts.accent)
        }
    }
}
