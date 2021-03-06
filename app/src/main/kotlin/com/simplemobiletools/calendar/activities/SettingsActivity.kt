package com.simplemobiletools.calendar.activities

import android.content.Intent
import android.content.res.Resources
import android.media.AudioManager
import android.os.Bundle
import android.text.TextUtils
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.dialogs.SelectCalendarsDialog
import com.simplemobiletools.calendar.extensions.*
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.models.EventType
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.CustomIntervalPickerDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.dialogs.SelectAlarmSoundDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ALARM_SOUND_TYPE_NOTIFICATION
import com.simplemobiletools.commons.helpers.PERMISSION_READ_CALENDAR
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_CALENDAR
import com.simplemobiletools.commons.models.AlarmSound
import com.simplemobiletools.commons.models.RadioItem
import kotlinx.android.synthetic.main.activity_settings.*
import java.util.*

class SettingsActivity : SimpleActivity() {
    private val GET_RINGTONE_URI = 1

    lateinit var res: Resources
    private var mStoredPrimaryColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        res = resources
        mStoredPrimaryColor = config.primaryColor
        setupCaldavSync()
    }

    override fun onResume() {
        super.onResume()

        setupCustomizeColors()
        setupUseEnglish()
        setupManageEventTypes()
        setupHourFormat()
        setupSundayFirst()
        setupAvoidWhatsNew()
        setupDeleteAllEvents()
        setupReplaceDescription()
        setupWeekNumbers()
        setupShowGrid()
        setupWeeklyStart()
        setupWeeklyEnd()
        setupVibrate()
        setupReminderSound()
        setupUseSameSnooze()
        setupSnoozeTime()
        setupDisplayPastEvents()
        setupFontSize()
        setupCustomizeWidgetColors()
        updateTextColors(settings_holder)
        checkPrimaryColor()
        setupSectionColors()
    }

    override fun onPause() {
        super.onPause()
        mStoredPrimaryColor = config.primaryColor
    }

    private fun checkPrimaryColor() {
        if (config.primaryColor != mStoredPrimaryColor) {
            dbHelper.getEventTypes {
                if (it.filter { it.caldavCalendarId == 0 }.size == 1) {
                    val eventType = it.first { it.caldavCalendarId == 0 }
                    eventType.color = config.primaryColor
                    dbHelper.updateEventType(eventType)
                }
            }
        }
    }

    private fun setupSectionColors() {
        val adjustedPrimaryColor = getAdjustedPrimaryColor()
        arrayListOf(reminders_label, caldav_label, weekly_view_label, monthly_view_label, simple_event_list_label, simple_font_size_label).forEach {
            it.setTextColor(adjustedPrimaryColor)
        }
    }

    private fun setupCustomizeColors() {
        settings_customize_colors_holder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        settings_use_english_holder.beVisibleIf(config.wasUseEnglishToggled || Locale.getDefault().language != "en")
        settings_use_english.isChecked = config.useEnglish
        settings_use_english_holder.setOnClickListener {
            settings_use_english.toggle()
            config.useEnglish = settings_use_english.isChecked
            System.exit(0)
        }
    }

    private fun setupManageEventTypes() {
        settings_manage_event_types_holder.setOnClickListener {
            startActivity(Intent(this, ManageEventTypesActivity::class.java))
        }
    }

    private fun setupHourFormat() {
        settings_hour_format.isChecked = config.use24HourFormat
        settings_hour_format_holder.setOnClickListener {
            settings_hour_format.toggle()
            config.use24HourFormat = settings_hour_format.isChecked
        }
    }

    private fun setupCaldavSync() {
        settings_caldav_sync.isChecked = config.caldavSync
        settings_caldav_sync_holder.setOnClickListener {
            if (config.caldavSync) {
                toggleCaldavSync(false)
            } else {
                handlePermission(PERMISSION_WRITE_CALENDAR) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CALENDAR) {
                            if (it) {
                                toggleCaldavSync(true)
                            }
                        }
                    }
                }
            }
        }

        settings_manage_synced_calendars_holder.beVisibleIf(config.caldavSync)
        settings_manage_synced_calendars_holder.setOnClickListener {
            showCalendarPicker()
        }
    }

    private fun toggleCaldavSync(enable: Boolean) {
        if (enable) {
            showCalendarPicker()
        } else {
            settings_caldav_sync.isChecked = false
            config.caldavSync = false
            settings_manage_synced_calendars_holder.beGone()
            config.getSyncedCalendarIdsAsList().forEach {
                CalDAVHandler(applicationContext).deleteCalDAVCalendarEvents(it.toLong())
            }
            dbHelper.deleteEventTypesWithCalendarId(config.caldavSyncedCalendarIDs)
        }
    }

    private fun showCalendarPicker() {
        val oldCalendarIds = config.getSyncedCalendarIdsAsList()

        SelectCalendarsDialog(this) {
            val newCalendarIds = config.getSyncedCalendarIdsAsList()
            if (newCalendarIds.isEmpty() && !config.caldavSync) {
                return@SelectCalendarsDialog
            }

            settings_manage_synced_calendars_holder.beVisibleIf(newCalendarIds.isNotEmpty())
            settings_caldav_sync.isChecked = newCalendarIds.isNotEmpty()
            config.caldavSync = newCalendarIds.isNotEmpty()
            if (settings_caldav_sync.isChecked) {
                toast(R.string.syncing)
            }

            Thread {
                if (newCalendarIds.isNotEmpty()) {
                    val existingEventTypeNames = dbHelper.getEventTypesSync().map { it.getDisplayTitle().toLowerCase() } as ArrayList<String>
                    getSyncedCalDAVCalendars().forEach {
                        val calendarTitle = it.getFullTitle()
                        if (!existingEventTypeNames.contains(calendarTitle.toLowerCase())) {
                            val eventType = EventType(0, it.displayName, it.color, it.id, it.displayName, it.accountName)
                            existingEventTypeNames.add(calendarTitle.toLowerCase())
                            dbHelper.insertEventType(eventType)
                        }
                    }
                    CalDAVHandler(applicationContext).refreshCalendars(this) {}
                }

                val removedCalendarIds = oldCalendarIds.filter { !newCalendarIds.contains(it) }
                removedCalendarIds.forEach {
                    CalDAVHandler(applicationContext).deleteCalDAVCalendarEvents(it.toLong())
                    dbHelper.getEventTypeWithCalDAVCalendarId(it.toInt())?.apply {
                        dbHelper.deleteEventTypes(arrayListOf(this), true) {}
                    }
                }
                dbHelper.deleteEventTypesWithCalendarId(TextUtils.join(",", removedCalendarIds))
                if (settings_caldav_sync.isChecked) {
                    toast(R.string.synchronization_completed)
                }
            }.start()
        }
    }

    private fun setupSundayFirst() {
        settings_sunday_first.isChecked = config.isSundayFirst
        settings_sunday_first_holder.setOnClickListener {
            settings_sunday_first.toggle()
            config.isSundayFirst = settings_sunday_first.isChecked
        }
    }

    private fun setupAvoidWhatsNew() {
        settings_avoid_whats_new.isChecked = config.avoidWhatsNew
        settings_avoid_whats_new_holder.setOnClickListener {
            settings_avoid_whats_new.toggle()
            config.avoidWhatsNew = settings_avoid_whats_new.isChecked
        }
    }

    private fun setupDeleteAllEvents() {
        settings_delete_all_events_holder.setOnClickListener {
            ConfirmationDialog(this, messageId = R.string.delete_all_events_confirmation) {
                Thread {
                    dbHelper.deleteAllEvents()
                }.start()
            }
        }
    }

    private fun setupReplaceDescription() {
        settings_replace_description.isChecked = config.replaceDescription
        settings_replace_description_holder.setOnClickListener {
            settings_replace_description.toggle()
            config.replaceDescription = settings_replace_description.isChecked
        }
    }

    private fun setupWeeklyStart() {
        settings_start_weekly_at.text = getHoursString(config.startWeeklyAt)
        settings_start_weekly_at_holder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            (0..24).mapTo(items) { RadioItem(it, getHoursString(it)) }

            RadioGroupDialog(this@SettingsActivity, items, config.startWeeklyAt) {
                if (it as Int >= config.endWeeklyAt) {
                    toast(R.string.day_end_before_start)
                } else {
                    config.startWeeklyAt = it
                    settings_start_weekly_at.text = getHoursString(it)
                }
            }
        }
    }

    private fun setupWeeklyEnd() {
        settings_end_weekly_at.text = getHoursString(config.endWeeklyAt)
        settings_end_weekly_at_holder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            (0..24).mapTo(items) { RadioItem(it, getHoursString(it)) }

            RadioGroupDialog(this@SettingsActivity, items, config.endWeeklyAt) {
                if (it as Int <= config.startWeeklyAt) {
                    toast(R.string.day_end_before_start)
                } else {
                    config.endWeeklyAt = it
                    settings_end_weekly_at.text = getHoursString(it)
                }
            }
        }
    }

    private fun setupWeekNumbers() {
        settings_week_numbers.isChecked = config.showWeekNumbers
        settings_week_numbers_holder.setOnClickListener {
            settings_week_numbers.toggle()
            config.showWeekNumbers = settings_week_numbers.isChecked
        }
    }

    private fun setupShowGrid() {
        settings_show_grid.isChecked = config.showGrid
        settings_show_grid_holder.setOnClickListener {
            settings_show_grid.toggle()
            config.showGrid = settings_show_grid.isChecked
        }
    }

    private fun setupReminderSound() {
        settings_reminder_sound.text = config.reminderSoundTitle

        settings_reminder_sound_holder.setOnClickListener {
            SelectAlarmSoundDialog(this, config.reminderSoundUri, AudioManager.STREAM_NOTIFICATION, GET_RINGTONE_URI, ALARM_SOUND_TYPE_NOTIFICATION, false,
                    onAlarmPicked = {
                        if (it != null) {
                            updateReminderSound(it)
                        }
                    }, onAlarmSoundDeleted = {
                if (it.uri == config.reminderSoundUri) {
                    val defaultAlarm = getDefaultAlarmSound(ALARM_SOUND_TYPE_NOTIFICATION)
                    updateReminderSound(defaultAlarm)
                }
            })
        }
    }

    private fun updateReminderSound(alarmSound: AlarmSound) {
        config.reminderSoundTitle = alarmSound.title
        config.reminderSoundUri = alarmSound.uri
        settings_reminder_sound.text = alarmSound.title
    }

    private fun setupVibrate() {
        settings_vibrate.isChecked = config.vibrateOnReminder
        settings_vibrate_holder.setOnClickListener {
            settings_vibrate.toggle()
            config.vibrateOnReminder = settings_vibrate.isChecked
        }
    }

    private fun setupUseSameSnooze() {
        settings_snooze_time_holder.beVisibleIf(config.useSameSnooze)
        settings_use_same_snooze.isChecked = config.useSameSnooze
        settings_use_same_snooze_holder.setOnClickListener {
            settings_use_same_snooze.toggle()
            config.useSameSnooze = settings_use_same_snooze.isChecked
            settings_snooze_time_holder.beVisibleIf(config.useSameSnooze)
        }
    }

    private fun setupSnoozeTime() {
        updateSnoozeTime()
        settings_snooze_time_holder.setOnClickListener {
            showPickSecondsDialogHelper(config.snoozeTime, true) {
                config.snoozeTime = it / 60
                updateSnoozeTime()
            }
        }
    }

    private fun updateSnoozeTime() {
        settings_snooze_time.text = formatMinutesToTimeString(config.snoozeTime)
    }

    private fun getHoursString(hours: Int) = String.format("%02d:00", hours)

    private fun setupDisplayPastEvents() {
        var displayPastEvents = config.displayPastEvents
        updatePastEventsText(displayPastEvents)
        settings_display_past_events_holder.setOnClickListener {
            CustomIntervalPickerDialog(this, displayPastEvents * 60) {
                val result = it / 60
                displayPastEvents = result
                config.displayPastEvents = result
                updatePastEventsText(result)
            }
        }
    }

    private fun updatePastEventsText(displayPastEvents: Int) {
        settings_display_past_events.text = getDisplayPastEventsText(displayPastEvents)
    }

    private fun getDisplayPastEventsText(displayPastEvents: Int): String {
        return if (displayPastEvents == 0) {
            getString(R.string.never)
        } else {
            getFormattedMinutes(displayPastEvents, false)
        }
    }

    private fun setupFontSize() {
        settings_font_size.text = getFontSizeText()
        settings_font_size_holder.setOnClickListener {
            val items = arrayListOf(
                    RadioItem(FONT_SIZE_SMALL, res.getString(R.string.small)),
                    RadioItem(FONT_SIZE_MEDIUM, res.getString(R.string.medium)),
                    RadioItem(FONT_SIZE_LARGE, res.getString(R.string.large)))

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                settings_font_size.text = getFontSizeText()
                updateWidgets()
                updateListWidget()
            }
        }
    }

    private fun getFontSizeText() = getString(when (config.fontSize) {
        FONT_SIZE_SMALL -> R.string.small
        FONT_SIZE_MEDIUM -> R.string.medium
        else -> R.string.large
    })

    private fun setupCustomizeWidgetColors() {
        settings_customize_widget_colors_holder.setOnClickListener {
            Intent(this, WidgetListConfigureActivity::class.java).apply {
                putExtra(IS_CUSTOMIZING_COLORS, true)
                startActivity(this)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == GET_RINGTONE_URI && resultCode == RESULT_OK && resultData != null) {
            val newAlarmSound = storeNewYourAlarmSound(resultData)
            updateReminderSound(newAlarmSound)
        }
    }
}
