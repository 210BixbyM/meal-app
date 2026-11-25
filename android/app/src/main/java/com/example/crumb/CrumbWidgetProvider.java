package com.example.crumb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class CrumbWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.crumb_widget
            );
            android.content.SharedPreferences prefs =
                context.getSharedPreferences("crumb_widget", Context.MODE_PRIVATE);

            views.setTextViewText(R.id.crumbWidgetPlan,
                "Today's meals\n" + prefs.getString("plan", "Nothing planned today"));
            views.setTextViewText(R.id.crumbWidgetGroceries,
                "To buy\n" + prefs.getString("groceries", "Shopping list is clear"));

            Intent open = new Intent(context, MainActivity.class);
            PendingIntent pending = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(R.id.crumbWidgetRoot, pending);
            manager.updateAppWidget(id, views);
        }
    }
}
