package ovh.litapp.pixlit.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SocialScreen(
    onTagClick: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SocialViewModel = hiltViewModel()
) {
    val challenge by viewModel.challenge.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Social", style = MaterialTheme.typography.headlineMedium)
        Text("Robyn's pinned art challenge", style = MaterialTheme.typography.titleMedium)
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            challenge != null -> {
                Text(challenge!!.dateRange, style = MaterialTheme.typography.titleLarge)
                challenge!!.tagsByDay.forEach { (day, tags) ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(day, style = MaterialTheme.typography.titleMedium)
                            tags.forEach { tag ->
                                AssistChip(
                                    onClick = { onTagClick(tag.includedTags) },
                                    label = { Text(listOfNotNull(tag.name, tag.description?.let { "($it)" }).joinToString(" ")) }
                                )
                            }
                        }
                    }
                }
                if (challenge!!.tagsByDay.isEmpty()) {
                    Text("No daily tags were found in the image description.")
                }
            }
        }
        if (!loading) OutlinedButton(onClick = viewModel::refresh) { Text("Refresh") }
    }
}
