package com.example.crumb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class PriyanServingDatabase {
    private PriyanServingDatabase() {}

    private static String normalize(String value) {
        return value == null ? "" :
            value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static double similarity(String a, String b) {
        Set<String> left = new HashSet<>();
        Set<String> right = new HashSet<>();
        for (String word : a.split(" ")) if (word.length() > 1) left.add(word);
        for (String word : b.split(" ")) if (word.length() > 1) right.add(word);
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static File databaseFile(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "food_portions.db");
        if (!file.exists()) {
            try (InputStream in = context.getAssets().open("food_portions.db");
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        return file;
    }

    public static void enrich(Context context, JSONObject product) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            String target = normalize(product.optString("name", ""));
            if (target.isEmpty()) return;

            db = SQLiteDatabase.openDatabase(
                databaseFile(context).getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READONLY
            );

            String bestId = null;
            String bestName = null;
            double bestScore = 0;

            cursor = db.rawQuery(
                "SELECT food_id, name FROM foods", null
            );
            while (cursor.moveToNext()) {
                String candidate = normalize(cursor.getString(1));
                double score = candidate.equals(target)
                    ? 1.0 : similarity(target, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = cursor.getString(0);
                    bestName = cursor.getString(1);
                }
            }
            cursor.close();
            cursor = null;

            // Avoid attaching portions from a loosely related food.
            if (bestId == null || bestScore < 0.80) return;

            JSONArray portions = new JSONArray();
            cursor = db.rawQuery(
                "SELECT description, grams FROM portions WHERE food_id = ? ORDER BY grams",
                new String[]{bestId}
            );
            while (cursor.moveToNext()) {
                JSONObject portion = new JSONObject();
                portion.put("label", cursor.getString(0));
                portion.put("grams", cursor.getDouble(1));
                portions.put(portion);
            }

            if (portions.length() > 0) {
                product.put("servingOptions", portions);
                product.put("servingMatch", bestName);
                product.put("servingMatchScore", bestScore);
            }
        } catch (Exception ignored) {
            // Barcode lookup remains usable if the optional local database fails.
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }
}
