package com.papalegua.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // Client ID tipo "Web" criado no Google Cloud Console.
    // É o MESMO valor que deve estar em GOOGLE_CLIENT_ID no servidor (.env / ecosystem_config.js).
    // Não é o Client ID tipo "Android" — esse não entra em nenhum código, só é registrado no Console.
    private static final String WEB_CLIENT_ID = "766951284173-kuiqten73rt9nj38paeah2t7djnatfiq.apps.googleusercontent.com";

    private EditText etUsername, etPassword;
    private Button btnLogin, btnRegister, btnGoogleLogin;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MyFirebaseMessagingService.createChannel(this);

        prefs = getSharedPreferences("papalegua", MODE_PRIVATE);

        if (prefs.getBoolean("logged", false)) {
            startActivity(new Intent(this, ContactsActivity.class));
            finish();
            return;
        }

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        progressBar = findViewById(R.id.progressBar);
        // Precisa existir um botão com esse id no activity_main.xml, ex:
        // <Button android:id="@+id/btnGoogleLogin" android:text="Entrar com Google" .../>
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin);

        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 100);
                break;
            }
        }

        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        if (btnGoogleLogin != null) {
            btnGoogleLogin.setOnClickListener(v -> signInWithGoogle());
        }
    }

    // ===== LOGIN COM GOOGLE =====
    private void signInWithGoogle() {
        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build();

        CredentialManager credentialManager = CredentialManager.create(this);

        credentialManager.getCredentialAsync(
            this,
            request,
            new CancellationSignal(),
            Executors.newSingleThreadExecutor(),
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse response) {
                    try {
                        GoogleIdTokenCredential googleIdTokenCredential =
                            GoogleIdTokenCredential.createFrom(response.getCredential().getData());
                        String idToken = googleIdTokenCredential.getIdToken();
                        sendGoogleTokenToServer(idToken);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Erro ao ler credencial do Google: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onError(GetCredentialException e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Login com Google cancelado ou falhou: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }
        );
    }

    private void sendGoogleTokenToServer(String idToken) {
        runOnUiThread(() -> progressBar.setVisibility(ProgressBar.VISIBLE));
        new Thread(() -> {
            try {
                URL url = new URL("https://papalegua.duckdns.org/api/auth/google");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("idToken", idToken);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                String cookie = extractSessionCookie(conn);

                BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode == 200 ? conn.getInputStream() : conn.getErrorStream()
                ));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONObject result = new JSONObject(response.toString());

                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    try {
                        if (responseCode == 200 && result.has("success")) {
                            onLoginSuccess(result, cookie);
                        } else {
                            Toast.makeText(this, result.optString("error", "Erro no login com Google"), Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // Usado tanto pelo login normal quanto pelo login com Google
    private void onLoginSuccess(JSONObject result, String cookie) throws Exception {
        JSONObject user = result.getJSONObject("user");
        SharedPreferences.Editor editor = prefs.edit()
            .putBoolean("logged", true)
            .putString("userId", user.getString("id"))
            .putString("username", user.getString("username"))
            .putString("avatar", user.getString("avatar_url"))
            .putString("cookie", cookie);
        if (result.has("privateKey")) {
            editor.putString("privateKey", result.getString("privateKey"));
        }
        if (result.has("seedPhrase")) {
            editor.putString("seedPhrase", result.getString("seedPhrase"));
        }
        editor.apply();

        getAndSendFcmToken();
        requestIgnoreBatteryOptimizations();

        startActivity(new Intent(this, ContactsActivity.class));
        finish();
    }

    // Busca o token FCM atual do aparelho e manda pro servidor.
    // Precisa ser chamado logo após o login porque onNewToken() só dispara
    // quando o token muda, não sempre que o app abre.
    private void getAndSendFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                MyFirebaseMessagingService.sendTokenToServer(getApplicationContext(), task.getResult());
            }
        });
    }

    // Pede pro usuário isentar o app da otimização de bateria.
    // Sem isso, alguns fabricantes (Xiaomi, Samsung, etc.) matam o processo em
    // segundo plano e as notificações push demoram ou não chegam.
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + pkg));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private String extractSessionCookie(HttpURLConnection conn) {
        String cookie = "";
        Map<String, List<String>> headers = conn.getHeaderFields();
        if (headers.containsKey("Set-Cookie")) {
            List<String> cookies = headers.get("Set-Cookie");
            for (String c : cookies) {
                if (c.startsWith("connect.sid")) {
                    cookie = c.split(";")[0];
                    break;
                }
            }
        }
        return cookie;
    }

    private void doLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        progressBar.setVisibility(ProgressBar.VISIBLE);

        new Thread(() -> {
            try {
                URL url = new URL("https://papalegua.duckdns.org/api/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("username", username);
                json.put("password", password);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode == 200 ? conn.getInputStream() : conn.getErrorStream()
                ));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONObject result = new JSONObject(response.toString());
                String finalCookie = extractSessionCookie(conn);

                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    btnLogin.setEnabled(true);

                    try {
                        if (responseCode == 200 && result.has("success")) {
                            onLoginSuccess(result, finalCookie);
                        } else {
                            Toast.makeText(this, result.getString("error"), Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    btnLogin.setEnabled(true);
                    Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}
