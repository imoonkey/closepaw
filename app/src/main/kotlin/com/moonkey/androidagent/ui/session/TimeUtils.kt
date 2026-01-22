package com.moonkey.androidagent.ui.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Utility functions for time formatting in the session list UI.
 */
object TimeUtils {
    
    private val dateFormat = SimpleDateFormat("MMM d", Locale.US)
    private val dateWithYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    
    /**
     * Format timestamp as relative time.
     * 
     * - < 1 minute: "Just now"
     * - < 60 minutes: "X minutes ago"
     * - < 24 hours: "X hours ago"
     * - < 7 days: "X days ago"
     * - Same year: "MMM d" (e.g., "Jan 21")
     * - Different year: "MMM d, yyyy" (e.g., "Jan 21, 2023")
     * 
     * @param timestamp The epoch timestamp in milliseconds
     * @return A human-readable relative time string
     */
    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff)
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                if (days == 1L) "Yesterday" else "$days days ago"
            }
            else -> {
                val date = Date(timestamp)
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val timestampYear = java.util.Calendar.getInstance().apply { 
                    timeInMillis = timestamp 
                }.get(java.util.Calendar.YEAR)
                
                if (timestampYear == currentYear) {
                    dateFormat.format(date)
                } else {
                    dateWithYearFormat.format(date)
                }
            }
        }
    }
    
    /**
     * Format message count for display.
     * 
     * @param count The number of messages
     * @return A formatted string like "3 messages" or "1 message"
     */
    fun formatMessageCount(count: Int): String {
        return if (count == 1) "1 message" else "$count messages"
    }
}
