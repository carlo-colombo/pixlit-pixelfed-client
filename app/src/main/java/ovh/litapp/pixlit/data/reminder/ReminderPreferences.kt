package ovh.litapp.pixlit.data.reminder

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private val DEFAULT_FRIDAY = LocalTime.of(20, 0)
private val DEFAULT_SATURDAY = LocalTime.of(8, 0)

/** The two reminder times, in the device timezone. */
data class ReminderTimes(
    val friday: LocalTime = DEFAULT_FRIDAY,
    val saturday: LocalTime = DEFAULT_SATURDAY
)

internal val Context.reminderPreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pixlit_reminders")

/**
 * Reminder times persisted with preferences DataStore (DATA-09). There is no `SharedPreferences` and
 * no blocking `commit()`: writes are launched onto an IO scope, and [times] exposes the values
 * reactively.
 *
 * The synchronous `friday`/`saturday` accessors are kept because the existing callers read them
 * from a Compose `remember` initialiser and write them back on a time-picker callback. They read an
 * in-memory snapshot that is primed from the store by [current]; [current] is also what the
 * scheduler uses so a receiver that runs before the snapshot is primed still schedules the stored
 * times rather than the defaults.
 */
@Singleton
class ReminderPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.reminderPreferencesDataStore
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val primeMutex = Mutex()

    @Volatile
    private var primed = false

    private val _times = MutableStateFlow(ReminderTimes())
    val times: StateFlow<ReminderTimes> = _times.asStateFlow()

    var friday: LocalTime
        get() = _times.value.friday
        set(value) {
            _times.value = _times.value.copy(friday = value)
            primed = true
            writeScope.launch {
                dataStore.edit { it[FRIDAY_HOUR] = value.hour; it[FRIDAY_MINUTE] = value.minute }
            }
        }

    var saturday: LocalTime
        get() = _times.value.saturday
        set(value) {
            _times.value = _times.value.copy(saturday = value)
            primed = true
            writeScope.launch {
                dataStore.edit { it[SATURDAY_HOUR] = value.hour; it[SATURDAY_MINUTE] = value.minute }
            }
        }

    /**
     * The authoritative stored times. Primes the in-memory snapshot on first use; when a setter has
     * already updated the snapshot the local value wins, so a just-picked time schedules even though
     * its disk write is still in flight.
     */
    suspend fun current(): ReminderTimes = primeMutex.withLock {
        if (!primed) {
            _times.value = read()
            primed = true
        }
        _times.value
    }

    private suspend fun read(): ReminderTimes {
        val prefs = dataStore.data.first()
        return ReminderTimes(
            friday = clampTime(
                prefs[FRIDAY_HOUR] ?: DEFAULT_FRIDAY.hour,
                prefs[FRIDAY_MINUTE] ?: DEFAULT_FRIDAY.minute
            ),
            saturday = clampTime(
                prefs[SATURDAY_HOUR] ?: DEFAULT_SATURDAY.hour,
                prefs[SATURDAY_MINUTE] ?: DEFAULT_SATURDAY.minute
            )
        )
    }

    companion object {
        private val FRIDAY_HOUR = intPreferencesKey("friday_hour")
        private val FRIDAY_MINUTE = intPreferencesKey("friday_minute")
        private val SATURDAY_HOUR = intPreferencesKey("saturday_hour")
        private val SATURDAY_MINUTE = intPreferencesKey("saturday_minute")
    }
}

/** A corrupt persisted value must degrade to a valid time instead of throwing on read. */
internal fun clampTime(hour: Int, minute: Int): LocalTime =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
