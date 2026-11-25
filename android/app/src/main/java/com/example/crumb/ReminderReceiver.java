package com.example.crumb;

import android.content.BroadcastReceiver;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "meal_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        int index = intent.getIntExtra("index", -1);
        if (index < 0 || index > 3) return;

        NotificationManager manager =
            (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE
            );

        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Meal reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Gentle daily meal reminders");
            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ReminderScheduler.getTitle(index))
            .setContentText(ReminderScheduler.getMessage(index))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER);

        manager.notify(9101 + index, builder.build());

        ReminderScheduler.schedule(context, index);
    }
}
