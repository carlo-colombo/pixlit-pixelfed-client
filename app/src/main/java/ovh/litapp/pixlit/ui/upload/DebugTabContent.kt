package ovh.litapp.pixlit.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.litapp.pixlit.data.api.toSafeString

/**
 * The Debug tab body (index 3, only reachable when `BuildConfig.DEBUG_TOOLS` is true), extracted
 * from the former `UploadContent` (REF-02). Internal tooling, so it keeps its hardcoded labels.
 */
@Composable
fun DebugTabContent(
    state: UploadUiState,
    callbacks: UploadCallbacks,
    onSimulateNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Fetched Posts (${state.recentStatuses.size})",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cache", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = callbacks.onRefreshCacheSummary) {
                        Text("Refresh")
                    }
                }
                state.cacheSummary.forEach { cache ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cache.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = cache.entityCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Button(
            onClick = onSimulateNotification,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simulate Art Show Notification")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.recentStatuses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.isLoadingTags) "Loading posts..." else "No posts retrieved (tap refresh in Upload tab to fetch)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.recentStatuses) { status ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val statusId = status.id?.toSafeString() ?: "Unknown ID"
                            val tagsList = status.tags?.mapNotNull { it.name }?.filter { it.isNotBlank() } ?: emptyList()

                            Text("ID: $statusId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            status.content?.let { if (it.isNotBlank()) Text("Content: $it", style = MaterialTheme.typography.bodySmall) }
                            status.text?.let { if (it.isNotBlank()) Text("Text: $it", style = MaterialTheme.typography.bodySmall) }
                            status.description?.let { if (it.isNotBlank()) Text("Description: $it", style = MaterialTheme.typography.bodySmall) }
                            status.spoilerText?.let { if (it.isNotBlank()) Text("Spoiler: $it", style = MaterialTheme.typography.bodySmall) }

                            if (tagsList.isNotEmpty()) {
                                Text("Tags: ${tagsList.joinToString { "#$it" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
