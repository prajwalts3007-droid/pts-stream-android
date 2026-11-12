package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** One compact row; descriptions stay accessible without visible button labels. */
@Composable
internal fun LibrarySearchBar(query: String, onQuery: (String) -> Unit, onFilters: () -> Unit,
    view: String? = null, onViewChange: (String) -> Unit = {}, filtersActive: Boolean = false, filtersEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().testTag("library-search-row"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PtsSearchField(query, { onQuery(it.take(160)) }, "Titles or episode names", Modifier.weight(1f).testTag("library-search-input"))
        if (filtersEnabled) Box(Modifier.testTag("library-filter-action").semantics(mergeDescendants = true) { stateDescription = if (filtersActive) "Filters active" else "No active filters" }) {
            PtsIconAction(PtsIcons.Filter, "Library filters", onFilters, tint = Pts.accent)
            if (filtersActive) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp).background(Pts.accent, CircleShape).testTag("active-filter-dot"))
        }
        if (view != null) Box(Modifier.testTag("library-view-action")) {
            PtsIconAction(if (view == "grid") PtsIcons.List else PtsIcons.Grid,
                if (view == "grid") "Switch to list view" else "Switch to grid view",
                { onViewChange(if (view == "grid") "list" else "grid") }, tint = Pts.accent)
        }
    }
}
