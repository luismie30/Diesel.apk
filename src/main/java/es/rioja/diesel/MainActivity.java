package es.rioja.diesel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {

    private static final int[] UMBRALES = {0, 1, 2, 3, 5};
    private static final long DIA_MS = 24 * 60 * 60 * 1000L;
    private static final int VERDE = Color.rgb(0, 130, 60);

    private final List<PriceChecker.Station> stations = new ArrayList<>();
    private ArrayAdapter<PriceChecker.Station> adapter;
    private TextView status;
    private Button refresh;
    private Button threshold;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Diésel en La Rioja");

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setPadding(pad, pad, pad, pad / 2);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad / 2, 0, pad / 2, 0);
        refresh = new Button(this);
        refresh.setText("Actualizar");
        refresh.setOnClickListener(v -> load());
        threshold = new Button(this);
        threshold.setOnClickListener(v -> nextThreshold());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        row.addView(refresh, lp1);
        row.addView(threshold, lp2);
        root.addView(row);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<PriceChecker.Station>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, stations) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                PriceChecker.Station s = stations.get(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                boolean reciente = s.delta != 0 && System.currentTimeMillis() - s.deltaAt < DIA_MS;
                String cambio = "";
                if (reciente) {
                    cambio = (s.delta < 0 ? "  ▼ " : "  ▲ ") + Notifier.euros(Math.abs(s.delta));
                }
                t1.setText(Notifier.euros(s.price) + "  ·  " + s.name + cambio);
                t1.setTextColor(reciente && s.delta < 0 ? VERDE : Color.BLACK);
                t2.setText(s.town + " — " + s.address);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openMap(stations.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        Notifier.createChannel(this);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        PriceJobService.schedule(this);

        showThreshold();
        show(PriceChecker.cached(this));
        load();
    }

    private SharedPreferences meta() {
        return getSharedPreferences(PriceChecker.PREFS_META, Context.MODE_PRIVATE);
    }

    private void showThreshold() {
        int c = meta().getInt("minCents", 0);
        threshold.setText(c == 0 ? "Avisar: cualquier bajada" : "Avisar: desde " + c + " cént.");
    }

    private void nextThreshold() {
        int c = meta().getInt("minCents", 0);
        int next = UMBRALES[0];
        for (int i = 0; i < UMBRALES.length; i++) {
            if (UMBRALES[i] == c) {
                next = UMBRALES[(i + 1) % UMBRALES.length];
                break;
            }
        }
        meta().edit().putInt("minCents", next).apply();
        showThreshold();
    }

    private void show(List<PriceChecker.Station> l) {
        stations.clear();
        stations.addAll(l);
        adapter.notifyDataSetChanged();
        long last = meta().getLong("lastCheck", 0L);
        if (last == 0L) {
            status.setText("Descargando precios…");
        } else {
            String hora = new SimpleDateFormat("d MMM HH:mm", Notifier.ES).format(new Date(last));
            status.setText(stations.size() + " gasolineras con diésel · última comprobación " + hora
                    + "\nSe comprueba sola cada ~15 min. Toca una gasolinera para verla en el mapa.");
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        refresh.setEnabled(false);
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            final PriceChecker.Result r = PriceChecker.check(ctx);
            if (r.error == null && !r.drops.isEmpty()) {
                Notifier.notifyDrops(ctx, r.drops);
            }
            runOnUiThread(() -> {
                loading = false;
                refresh.setEnabled(true);
                if (r.error != null) {
                    Toast.makeText(MainActivity.this,
                            "No se pudieron descargar los precios (" + r.error + ")",
                            Toast.LENGTH_LONG).show();
                    if (stations.isEmpty()) status.setText("Sin datos todavía. Pulsa Actualizar.");
                } else {
                    show(r.stations);
                }
            });
        }).start();
    }

    private void openMap(PriceChecker.Station s) {
        try {
            String q = s.lat + "," + s.lon + "(" + s.name + ")";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))));
        } catch (Exception e) {
            Toast.makeText(this, "No hay aplicación de mapas", Toast.LENGTH_SHORT).show();
        }
    }
}
