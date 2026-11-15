package dev.habittracker.shared

import kotlinx.serialization.Serializable
import java.time.*

@Serializable data class ReminderSettings(val quietEnabled: Boolean=false,val quietStart: String="22:00",val quietEnd: String="08:00",val summaryEnabled: Boolean=false,val summaryOnly: Boolean=false,val summaryTime: String="09:00") {
    fun validate(){LocalTime.parse(quietStart);LocalTime.parse(quietEnd);LocalTime.parse(summaryTime);require(!quietEnabled || quietStart!=quietEnd){"Начало и конец тихих часов должны отличаться"};require(!summaryOnly || summaryEnabled)}
}
fun nextAllowedNotification(instant: Instant,zone: ZoneId,settings: ReminderSettings): Instant {
    if(!settings.quietEnabled)return instant
    val start=LocalTime.parse(settings.quietStart);val end=LocalTime.parse(settings.quietEnd)
    val local=instant.atZone(zone);val time=local.toLocalTime()
    val quiet=if(start<end) time>=start && time<end else time>=start || time<end
    if(!quiet)return instant
    val date=if(start>end && time>=start)local.toLocalDate().plusDays(1) else local.toLocalDate()
    return date.atTime(end).atZone(zone).toInstant()
}
