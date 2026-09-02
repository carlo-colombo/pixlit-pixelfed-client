package ovh.litapp.pixlit.data.reminder

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("pixlit_reminders", Context.MODE_PRIVATE)
    var friday: LocalTime
        get() = LocalTime.of(prefs.getInt("friday_hour", 20), prefs.getInt("friday_minute", 0))
        set(value) { prefs.edit().putInt("friday_hour", value.hour).putInt("friday_minute", value.minute).apply() }
    var saturday: LocalTime
        get() = LocalTime.of(prefs.getInt("saturday_hour", 8), prefs.getInt("saturday_minute", 0))
        set(value) { prefs.edit().putInt("saturday_hour", value.hour).putInt("saturday_minute", value.minute).apply() }
}
