package es.rioja.diesel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;
import java.util.Locale;

final class Notifier {

    private static final String CANAL = "bajadas";
    static final Locale ES = new Locale("es", "ES");

    private Notifier() {}

    static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CANAL, "Bajadas de precio", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Aviso cuando baja el diésel en una gasolinera de La Rioja");
        nm.createNotificationChannel(ch);
    }

    static String euros(double v) {
        return String.format(ES, "%.3f €", v);
    }

    static void notifyDrops(Context ctx, List<PriceChecker.Station> drops) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || drops.isEmpty()) return;
        createChannel(ctx);

        PriceChecker.Station first = drops.get(0);
        String title = drops.size() == 1
                ? "Baja el diésel: " + first.name + " (" + first.town + ")"
                : "Baja el diésel en " + drops.size() + " gasolineras";

        Notification.InboxStyle style = new Notification.InboxStyle();
        int max = Math.min(drops.size(), 6);
        for (int i = 0; i < max; i++) {
            style.addLine(line(drops.get(i)));
        }
        if (drops.size() > max) {
            style.setSummaryText("+" + (drops.size() - max) + " más");
        }

        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(line(first))
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();

        try {
            nm.notify((int) (System.currentTimeMillis() / 1000L), n);
        } catch (SecurityException ignored) {
            // Sin permiso de notificaciones: no se puede avisar.
        }
    }

    private static String line(PriceChecker.Station s) {
        return euros(s.price) + " (−" + euros(-s.delta) + ") · " + s.name + ", " + s.town;
    }
}
