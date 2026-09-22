package ovh.litapp.pixlit.ui.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SocialScreen(
    onTagClick: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SocialViewModel = hiltViewModel()
) {
    val challenge by viewModel.challenge.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val uriHandler = LocalUriHandler.current

    val currentDay = remember {
        LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }

    val daysList = remember(challenge) {
        challenge?.tagsByDay?.entries?.toList().orEmpty()
    }

    val currentDayIndex = remember(daysList, currentDay) {
        daysList.indexOfFirst { (day, _) -> day.equals(currentDay, ignoreCase = true) }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(daysList, currentDayIndex) {
        if (currentDayIndex != -1) {
            // Header is item 0, so days start at item index 1
            listState.animateScrollToItem(currentDayIndex + 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Social", style = MaterialTheme.typography.headlineMedium)
                Text("Robyn's pinned art challenge", style = MaterialTheme.typography.titleMedium)
                if (challenge != null) {
                    Text(challenge!!.dateRange, style = MaterialTheme.typography.titleLarge)
                    challenge!!.postUrl?.let { url ->
                        OutlinedButton(
                            onClick = { uriHandler.openUri(url) }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View pinned post on Bluesky")
                        }
                    }
                }
            }
        }

        when {
            loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> item {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            challenge != null -> {
                itemsIndexed(
                    items = daysList,
                    key = { _, entry -> entry.key }
                ) { _, (day, tags) ->
                    val isCurrentDay = day.equals(currentDay, ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isCurrentDay) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (isCurrentDay) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isCurrentDay) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isCurrentDay) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = "Today",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            tags.forEach { tag ->
                                AssistChip(
                                    onClick = { onTagClick(tag.includedTags) },
                                    label = { Text(listOfNotNull(tag.name, tag.description?.let { "($it)" }).joinToString(" ")) }
                                )
                            }
                        }
                    }
                }
                if (daysList.isEmpty()) {
                    item {
                        Text("No daily tags were found in the image description.")
                    }
                }
            }
        }

        if (!loading) {
            item {
                OutlinedButton(onClick = viewModel::refresh) {
                    Text("Refresh")
                }
            }
        }
    }
}
