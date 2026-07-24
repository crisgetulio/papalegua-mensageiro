package com.papalegua.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "chat_notifications";
    private static int notificationIdCounter = 2000;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
    }

    // Chamado sempre que o Firebase gera/renova o token deste dispositivo.
    // Importante: isso NÃO dispara de novo a cada abertura do app, só quando o
    // token muda de verdade. Por isso o MainActivity também busca o token
    // manualmente logo após o login (ver getAndSendToken).
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        sendTokenToServer(this, token);
    }

    // Chamado quando a mensagem chega com o app ABERTO (foreground).
    // Com o app em segundo plano/fechado, o Android mostra a notificação
    // sozinho usando o bloco "notification" + o canal padrão do manifest —
    // por isso é importante montar a notificação aqui também, senão em
    // foreground nada aparece.
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "Papalegua";
        String body = "Nova mensagem";
        String fromUserId = null;

        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) title = remoteMessage.getNotification().getTitle();
            if (remoteMessage.getNotification().getBody() != null) body = remoteMessage.getNotification().getBody();
        }
        if (remoteMessage.getData().containsKey("fromUserId")) {
            fromUserId = remoteMessage.getData().get("fromUserId");
        }

        showNotification(this, title, body, fromUserId);
    }

    private void showNotification(Context ctx, String title, String body, String fromUserId) {
        Intent intent = new Intent(ctx, ContactsActivity.class);
        if (fromUserId != null) {
            intent = new Intent(ctx, ChatActivity.class);
            intent.putExtra("contactId", fromUserId);
            intent.putExtra("contactName", title);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            ctx, (int) System.currentTimeMillis(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(ctx).notify(notificationIdCounter++, builder.build());
    }

    // Envia o token pro backend usando o cookie de sessão já salvo no login.
    // Roda em background sempre que chamado (onNewToken já roda fora da main thread).
    static void sendTokenToServer(Context ctx, String token) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = ctx.getSharedPreferences("papalegua", Context.MODE_PRIVATE);
                String cookie = prefs.getString("cookie", "");
                if (cookie.isEmpty()) return; // ainda não logou, o MainActivity reenvia após o login

                URL url = new URL("https://papalegua.duckdns.org/api/register-push-token");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Cookie", cookie);
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("token", token);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Mensagens Papalegua",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notificações de novas mensagens");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}
