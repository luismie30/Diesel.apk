package es.rioja.diesel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Descarga los precios del Ministerio, los compara con los guardados y detecta bajadas. */
public final class PriceChecker {

    private static final String BASE =
            "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/";
    /** 26 = código de provincia de La Rioja. */
    private static final String PROVINCIA = "26";
    private static final String CAMPO_PRECIO = "Precio Gasoleo A";

    static final String PREFS_PRECIOS = "precios";
    static final String PREFS_META = "meta";

    public static final class Station {
        String id = "";
        String name = "";
        String town = "";
        String address = "";
        String lat = "";
        String lon = "";
        double price;
        /** Último cambio de precio (negativo = bajada). 0 si no se conoce. */
        double delta;
        /** Momento en que se detectó ese cambio. */
        long deltaAt;
    }

    public static final class Result {
        List<Station> stations = new ArrayList<>();
        List<Station> drops = new ArrayList<>();
        String error;
    }

    private PriceChecker() {}

    /** Comprueba precios. Bloquea: llamar siempre fuera del hilo principal. */
    public static synchronized Result check(Context ctx) {
        Result r = new Result();
        try {
            JSONArray lista = download();
            SharedPreferences precios = ctx.getSharedPreferences(PREFS_PRECIOS, Context.MODE_PRIVATE);
            SharedPreferences meta = ctx.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = precios.edit();
            double minDrop = meta.getInt("minCents", 0) / 100.0;
            long now = System.currentTimeMillis();

            for (int i = 0; i < lista.length(); i++) {
                JSONObject o = lista.getJSONObject(i);
                String raw = o.optString(CAMPO_PRECIO, "").trim().replace(',', '.');
                if (raw.isEmpty()) continue;
                double price;
                try {
                    price = Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    continue;
                }
                Station s = new Station();
                s.id = o.optString("IDEESS", "");
                if (s.id.isEmpty()) continue;
                s.name = o.optString("Rótulo", "Gasolinera").trim();
                s.town = o.optString("Localidad", o.optString("Municipio", "")).trim();
                s.address = o.optString("Dirección", "").trim();
                s.lat = o.optString("Latitud", "").replace(',', '.');
                s.lon = o.optString("Longitud (WGS84)", "").replace(',', '.');
                s.price = price;

                float old = precios.getFloat("p_" + s.id, -1f);
                s.delta = precios.getFloat("d_" + s.id, 0f);
                s.deltaAt = precios.getLong("t_" + s.id, 0L);
                if (old > 0 && Math.abs(price - old) > 0.0005) {
                    s.delta = price - old;
                    s.deltaAt = now;
                    ed.putFloat("d_" + s.id, (float) s.delta);
                    ed.putLong("t_" + s.id, now);
                    if (s.delta < 0 && -s.delta >= minDrop - 0.0005) {
                        r.drops.add(s);
                    }
                }
                ed.putFloat("p_" + s.id, (float) price);
                r.stations.add(s);
            }
            ed.apply();

            sortByPrice(r.stations);
            sortByPrice(r.drops);
            meta.edit()
                    .putLong("lastCheck", now)
                    .putString("cache", toJson(r.stations))
                    .apply();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    /** Última lista descargada, para mostrarla sin conexión. */
    public static List<Station> cached(Context ctx) {
        List<Station> out = new ArrayList<>();
        try {
            String json = ctx.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE).getString("cache", "[]");
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Station s = new Station();
                s.id = o.optString("id");
                s.name = o.optString("name");
                s.town = o.optString("town");
                s.address = o.optString("address");
                s.lat = o.optString("lat");
                s.lon = o.optString("lon");
                s.price = o.optDouble("price", 0);
                s.delta = o.optDouble("delta", 0);
                s.deltaAt = o.optLong("deltaAt", 0);
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void sortByPrice(List<Station> l) {
        Collections.sort(l, (a, b) -> Double.compare(a.price, b.price));
    }

    private static String toJson(List<Station> l) throws Exception {
        JSONArray a = new JSONArray();
        for (Station s : l) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("town", s.town);
            o.put("address", s.address);
            o.put("lat", s.lat);
            o.put("lon", s.lon);
            o.put("price", s.price);
            o.put("delta", s.delta);
            o.put("deltaAt", s.deltaAt);
            a.put(o);
        }
        return a.toString();
    }

    /** Pide solo La Rioja; si ese filtro falla, baja toda España y filtra aquí. */
    private static JSONArray download() throws Exception {
        try {
            JSONArray a = new JSONObject(get(BASE + "FiltroProvincia/" + PROVINCIA)).getJSONArray("ListaEESSPrecio");
            if (a.length() > 0) return a;
        } catch (Exception ignored) {
        }
        JSONArray todas = new JSONObject(get(BASE)).getJSONArray("ListaEESSPrecio");
        JSONArray rioja = new JSONArray();
        for (int i = 0; i < todas.length(); i++) {
            JSONObject o = todas.getJSONObject(i);
            if (PROVINCIA.equals(o.optString("IDProvincia"))) rioja.put(o);
        }
        if (rioja.length() == 0) throw new Exception("sin datos de La Rioja");
        return rioja;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
