package ovh.litapp.pixlit.ui.upload

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.time.LocalTime
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.data.reminder.ReminderPreferences
import ovh.litapp.pixlit.data.reminder.ReminderScheduler
import ovh.litapp.pixlit.ui.about.AboutSection
import ovh.litapp.pixlit.ui.theme.PixlitTheme

/** The Settings tab body (last index), extracted from the former `UploadContent` (REF-02). */
@Composable
fun SettingsTabContent(
    preferences: ReminderPreferences?,
    scheduler: ReminderScheduler?,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (preferences != null && scheduler != null) {
            ReminderSettings(preferences, scheduler)
        }
        AboutSection()
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.action_logout))
        }
    }
}

@Composable
private fun ReminderSettings(preferences: ReminderPreferences, scheduler: ReminderScheduler) {
    var friday by remember { mutableStateOf(preferences.friday) }
    var saturday by remember { mutableStateOf(preferences.saturday) }
    Text("BlueSkyArtShow reminders", style = MaterialTheme.typography.headlineSmall)
    Text("Reminders are always on. Times use your device timezone.", style = MaterialTheme.typography.bodyMedium)
    NotificationPermissionNotice()
    ReminderTimeRow("Friday", friday) { time -> friday = time; preferences.friday = time; scheduler.rescheduleAll() }
    ReminderTimeRow("Saturday", saturday) { time -> saturday = time; preferences.saturday = time; scheduler.rescheduleAll() }
}

/**
 * Prompts for POST_NOTIFICATIONS only here, where enabling a reminder is the action that needs it,
 * and surfaces the denied state (read from the system, not from a cached "already asked" flag) so a
 * denied permission does not make the reminder fail invisibly. Tapping again re-prompts, and the
 * system-settings route lets a permanent denial be reversed without reinstalling.
 */
@Composable
private fun NotificationPermissionNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var showRationale by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        enabled = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    if (enabled) {
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.notifications_disabled_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.notifications_disabled_message),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showRationale = true }) {
                    Text(stringResource(R.string.notifications_enable))
                }
                TextButton(onClick = { context.openNotificationSettings() }) {
                    Text(stringResource(R.string.notifications_open_settings))
                }
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.notifications_rationale_title)) },
            text = { Text(stringResource(R.string.notifications_rationale_message)) },
            confirmButton = {
                Button(onClick = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun ReminderTimeRow(label: String, time: LocalTime, onChanged: (LocalTime) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label reminder")
        OutlinedButton(onClick = { android.app.TimePickerDialog(context, { _, hour, minute -> onChanged(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show() }) {
            Text(String.format("%02d:%02d", time.hour, time.minute))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsTabContentPreview() {
    PixlitTheme {
        SettingsTabContent(
            preferences = null,
            scheduler = null,
            onLogout = {}
        )
    }
}
