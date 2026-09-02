package ovh.litapp.pixlit.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.data.repository.BlueskyArtShowRepository
import ovh.litapp.pixlit.data.repository.WeeklyChallenge
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val repository: BlueskyArtShowRepository
) : ViewModel() {
    private val _challenge = MutableStateFlow<WeeklyChallenge?>(null)
    val challenge = _challenge.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.fetchWeeklyChallenge().fold(
                onSuccess = { _challenge.value = it; _error.value = null },
                onFailure = { _error.value = it.localizedMessage ?: "Unable to load the pinned post" }
            )
            _isLoading.value = false
        }
    }
}
