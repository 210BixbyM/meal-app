package com.example.crumb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

public class ReminderScheduler {
    private static final int[] IDS = {9101, 9102, 9103, 9104};
    private static final int[] HOURS = {9, 13, 19, 23};
    private static final String[] TITLES = {
        "Breakfast", "Lunch", "Dinner", "Evening snack"
    };
    private static final String[] MESSAGES = {
        "It's breakfast time. Take a moment for yourself.",
        "It's lunchtime. You're welcome to enjoy a meal.",
        "It's dinner time. Take care of yourself.",
        "Evening snack, if you're hungry."
    };

    public static void scheduleAll(Context context) {
        for (int i = 0; i < IDS.length; i++) {
            schedule(context, i);
        }
    }

    public static void schedule(Context context, int index) {
        if (index < 0 || index >= IDS.length) return;

        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, HOURS[index]);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        if (!next.after(Calendar.getInstance())) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction("com.example.crumb.MEAL_REMINDER");
        intent.putExtra("index", index);

        PendingIntent pending = PendingIntent.getBroadcast(
            context, IDS[index], intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarms =
            (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        if (alarms != null) {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending
            );
        }
    }

    public static String getTitle(int index) {
        return TITLES[index];
    }

    public static String getMessage(int index) {
        return MESSAGES[index];
    }
}
