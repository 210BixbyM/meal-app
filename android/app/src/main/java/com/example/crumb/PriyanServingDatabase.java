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
        if (value == null) return "";
        String s = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.length() > 3 && word.endsWith("ies"))
                word = word.substring(0, word.length() - 3) + "y";
            else if (word.length() > 3 && word.endsWith("s"))
                word = word.substring(0, word.length() - 1);
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.toString();
    }

    private static double score(String query, String candidate) {
        Set<String> q = new HashSet<>();
        Set<String> c = new HashSet<>();
        for (String w : query.split(" ")) if (w.length() > 1) q.add(w);
        for (String w : candidate.split(" ")) if (w.length() > 1) c.add(w);
        if (q.isEmpty() || c.isEmpty()) return 0;
        Set<String> hit = new HashSet<>(q);
        hit.retainAll(c);
        return (double) hit.size() / q.size();
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

    private static SQLiteDatabase open(Context context) throws Exception {
        return SQLiteDatabase.openDatabase(
            databaseFile(context).getAbsolutePath(), null,
            SQLiteDatabase.OPEN_READONLY
        );
    }

    private static JSONObject makeFood(SQLiteDatabase db, String id,
                                       String name, String source,
                                       double calories, double protein,
                                       double fat, double carbs) throws Exception {
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("source", source);
        item.put("serving", "per 100 g");
        item.put("basis", "per 100 g");
        item.put("calories", calories);
        item.put("protein", protein);
        item.put("fat", fat);
        item.put("carbs", carbs);
        item.put("fiber", JSONObject.NULL);
        item.put("sugar", JSONObject.NULL);
        item.put("satFat", JSONObject.NULL);
        item.put("sodium", JSONObject.NULL);

        JSONArray portions = new JSONArray();
        try (Cursor c = db.rawQuery(
            "SELECT description, grams FROM portions WHERE food_id=? ORDER BY grams",
            new String[]{id})) {
            while (c.moveToNext()) {
                JSONObject p = new JSONObject();
                p.put("label", c.getString(0));
                p.put("grams", c.getDouble(1));
                portions.put(p);
            }
        }
        item.put("servingOptions", portions);
        return item;
    }

    public static JSONArray search(Context context, String query) throws Exception {
        JSONArray results = new JSONArray();
        String target = normalize(query);
        if (target.isEmpty()) return results;

        try (SQLiteDatabase db = open(context);
             Cursor c = db.rawQuery(
                "SELECT food_id,name,calories,protein,fat,carbs FROM foods", null)) {
            class Match {
                String id, name;
                double score, calories, protein, fat, carbs;
            }
            java.util.ArrayList<Match> matches = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                String name = c.getString(1);
                String candidate = normalize(name);
                double value = candidate.equals(target) ? 1.0 : score(target, candidate);
                if (value < 0.75) continue;
                Match m = new Match();
                m.id = c.getString(0);
                m.name = name;
                m.score = value;
                m.calories = c.isNull(2) ? 0 : c.getDouble(2);
                m.protein = c.isNull(3) ? 0 : c.getDouble(3);
                m.fat = c.isNull(4) ? 0 : c.getDouble(4);
                m.carbs = c.isNull(5) ? 0 : c.getDouble(5);
                matches.add(m);
            }
            matches.sort((a,b) -> Double.compare(b.score, a.score));
            for (int i = 0; i < Math.min(20, matches.size()); i++) {
                Match m = matches.get(i);
                results.put(makeFood(db, m.id, m.name, "Crumb food database",
                    m.calories, m.protein, m.fat, m.carbs));
            }
        }
        return results;
    }

    public static void enrich(Context context, JSONObject product) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            String target = normalize(product.optString("name", ""));
            if (target.isEmpty()) return;
            db = open(context);
            String bestId = null, bestName = null;
            double best = 0;
            c = db.rawQuery("SELECT food_id,name FROM foods", null);
            while (c.moveToNext()) {
                String name = c.getString(1);
                double value = normalize(name).equals(target)
                    ? 1.0 : score(target, normalize(name));
                if (value > best) {
                    best = value;
                    bestId = c.getString(0);
                    bestName = name;
                }
            }
            c.close(); c = null;
            if (bestId == null || best < 0.75) return;
            JSONArray portions = new JSONArray();
            c = db.rawQuery(
                "SELECT description,grams FROM portions WHERE food_id=? ORDER BY grams",
                new String[]{bestId});
            while (c.moveToNext()) {
                JSONObject p = new JSONObject();
                p.put("label", c.getString(0));
                p.put("grams", c.getDouble(1));
                portions.put(p);
            }
            if (portions.length() > 0) {
                product.put("servingOptions", portions);
                product.put("servingMatch", bestName);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }
}
