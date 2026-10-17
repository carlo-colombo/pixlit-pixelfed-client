package ovh.litapp.pixlit.ui.upload

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.litapp.pixlit.ui.upload.components.RecentPostsSection

/**
 * The "Last" tab body (tab 2), extracted from the former `UploadContent` (REF-02). [onCopyPost]
 * carries the host's tab switch, so the body itself only reports the copied post.
 */
@Composable
fun RecentPostsTabContent(
    state: UploadUiState,
    callbacks: UploadCallbacks,
    onCopyPost: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = callbacks.onRefreshRecentPosts,
        modifier = modifier.fillMaxSize()
    ) {
        RecentPostsSection(
            statuses = state.recentStatuses.take(state.visibleRecentPostCount),
            onCopyPost = onCopyPost,
            onLoadMore = callbacks.onLoadMorePosts,
            isLoadingMore = state.isLoadingMorePosts,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        )
    }
}
