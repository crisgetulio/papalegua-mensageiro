package com.papalegua.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class ChatActivity extends AppCompatActivity {

    private static final String SERVER_URL = "https://papalegua.duckdns.org";

    private ListView listView;
    private EditText etMessage;
    private Button btnSend, btnBack, btnCall, btnVideoCall;
    private ImageButton btnAudio;
    private TextView tvContactName, tvConnectionStatus;
    private SharedPreferences prefs;

    private List<MessageItem> messages = new ArrayList<>();
    private MessageAdapter adapter;

    private String contactId;
    private String contactName;
    private String myId;
    private String cookie;

    private PrivateKey myPrivateKey;
    private PublicKey contactPublicKey;
    private Socket mSocket;

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentAudioPath;

    // Modelo de mensagem ja decifrada (ou com o texto placeholder quando nao
    // da pra decifrar, igual o site faz).
    static class MessageItem {
        String id;
        String fromUserId, toUserId;
        String text;
        boolean isAudio;
        byte[] audioBytes; // preenchido so quando conseguimos decifrar um audio recebido
        long timestamp;
        int ttl;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        prefs = getSharedPreferences("papalegua", MODE_PRIVATE);
        myId = prefs.getString("userId", null);
        cookie = prefs.getString("cookie", "");
        contactId = getIntent().getStringExtra("contactId");
        contactName = getIntent().getStringExtra("contactName");

        tvContactName = findViewById(R.id.tvContactName);
        tvContactName.setText(contactName);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvConnectionStatus.setText("\uD83D\uDFE2 Conectado");
        tvConnectionStatus.setTextColor(0xFF22c55e);

        listView = findViewById(R.id.listView);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnBack = findViewById(R.id.btnBack);
        btnCall = findViewById(R.id.btnCall);
        btnVideoCall = findViewById(R.id.btnVideoCall);
        btnAudio = findViewById(R.id.btnAudio);

        adapter = new MessageAdapter();
        listView.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> sendMessage());
        btnCall.setOnClickListener(v -> Toast.makeText(this, "Chamada de audio iniciada", Toast.LENGTH_SHORT).show());
        btnVideoCall.setOnClickListener(v -> Toast.makeText(this, "Chamada de video iniciada", Toast.LENGTH_SHORT).show());
        btnAudio.setOnClickListener(v -> {
            if (isRecording) stopRecording(); else startRecording();
        });

        // Carrega a chave privada salva no login. Sem ela nao da pra decifrar
        // nada (limitacao do esquema: a chave so existe localmente no
        // aparelho onde a conta foi criada/logada pela primeira vez).
        String privateKeyPem = prefs.getString("privateKey", null);
        if (privateKeyPem == null) {
            Toast.makeText(this, "Chave de criptografia nao encontrada neste aparelho. Mensagens recebidas nao poderao ser lidas.", Toast.LENGTH_LONG).show();
        } else {
            try {
                myPrivateKey = HybridCrypto.importPrivateKey(privateKeyPem);
            } catch (Exception e) {
                Toast.makeText(this, "Erro ao carregar chave de criptografia: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        setupSocket();
        fetchContactPublicKey();
        loadMessages();
    }

    // ===== SOCKET.IO =====
    private void setupSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Cookie", Arrays.asList(cookie));
            opts.extraHeaders = headers;
            mSocket = IO.socket(SERVER_URL, opts);

            mSocket.on("private message", onPrivateMessage);
            mSocket.on("message expired", onMessageExpired);
            mSocket.connect();
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao conectar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private final Emitter.Listener onPrivateMessage = args -> runOnUiThread(() -> {
        try {
            JSONObject msg = (JSONObject) args[0];
            String fromUserId = msg.getString("fromUserId");
            String toUserId = msg.getString("toUserId");
            // So nos interessa essa conversa especifica.
            if (!fromUserId.equals(contactId) && !toUserId.equals(contactId)) return;
            // Mensagens que eu mesmo mandei ja foram adicionadas localmente
            // na hora do envio (o servidor so ecoa pro destinatario).
            if (fromUserId.equals(myId)) return;
            addIncomingMessage(msg);
        } catch (Exception ignored) {}
    });

    private final Emitter.Listener onMessageExpired = args -> runOnUiThread(() -> {
        try {
            JSONObject data = (JSONObject) args[0];
            String messageId = data.getString("messageId");
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messageId.equals(messages.get(i).id)) {
                    messages.remove(i);
                }
            }
            adapter.notifyDataSetChanged();
        } catch (Exception ignored) {}
    });

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSocket != null) {
            mSocket.off("private message", onPrivateMessage);
            mSocket.off("message expired", onMessageExpired);
            mSocket.disconnect();
        }
        if (playingAudio != null) {
            playingAudio.release();
            playingAudio = null;
        }
    }

    // ===== CHAVE PUBLICA DO CONTATO =====
    private void fetchContactPublicKey() {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL + "/api/public-key/" + contactId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
                br.close();
                JSONObject result = new JSONObject(resp.toString());
                String pem = result.getString("publicKey");
                contactPublicKey = HybridCrypto.importPublicKey(pem);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Erro ao carregar chave do contato: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ===== ENVIAR TEXTO =====
    private void sendMessage() {
        final String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (contactPublicKey == null) {
            Toast.makeText(this, "Aguardando chave do contato, tente novamente em instantes...", Toast.LENGTH_SHORT).show();
            fetchContactPublicKey();
            return;
        }
        etMessage.setText("");
        btnSend.setEnabled(false);
        new Thread(() -> {
            try {
                String encrypted = HybridCrypto.encryptForRecipient(text, contactPublicKey);
                JSONObject payload = new JSONObject();
                payload.put("toUserId", contactId);
                payload.put("encryptedContent", encrypted);
                payload.put("isAudio", false);
                payload.put("ttl", 0);
                mSocket.emit("private message", payload);

                MessageItem local = new MessageItem();
                local.id = "local-" + System.currentTimeMillis();
                local.fromUserId = myId;
                local.toUserId = contactId;
                local.text = text;
                local.isAudio = false;
                local.timestamp = System.currentTimeMillis();
                local.ttl = 0;

                runOnUiThread(() -> {
                    btnSend.setEnabled(true);
                    messages.add(local);
                    adapter.notifyDataSetChanged();
                    listView.setSelection(messages.size() - 1);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSend.setEnabled(true);
                    Toast.makeText(ChatActivity.this, "Erro ao enviar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ===== GRAVAR E ENVIAR AUDIO =====
    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }
        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            String path = getExternalFilesDir(null).getAbsolutePath() + "/papalegua_audio_" + System.currentTimeMillis() + ".m4a";
            currentAudioPath = path;
            mediaRecorder.setOutputFile(path);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            btnAudio.setImageResource(R.drawable.ic_mic_recording);
            Toast.makeText(this, "Gravando...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao gravar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            btnAudio.setImageResource(R.drawable.ic_mic_idle);
            Toast.makeText(this, "Enviando audio...", Toast.LENGTH_SHORT).show();
            encryptAndSendAudio(currentAudioPath);
        }
    }

    private void encryptAndSendAudio(String path) {
        if (contactPublicKey == null) {
            Toast.makeText(this, "Chave do contato ainda nao carregada, tente novamente.", Toast.LENGTH_SHORT).show();
            fetchContactPublicKey();
            return;
        }
        new Thread(() -> {
            try {
                File audioFile = new File(path);
                byte[] fileBytes = new byte[(int) audioFile.length()];
                FileInputStream fis = new FileInputStream(audioFile);
                fis.read(fileBytes);
                fis.close();
                audioFile.delete();

                String base64Audio = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                String encrypted = HybridCrypto.encryptForRecipient(base64Audio, contactPublicKey);

                JSONObject payload = new JSONObject();
                payload.put("toUserId", contactId);
                payload.put("encryptedContent", encrypted);
                payload.put("isAudio", true);
                payload.put("ttl", 0);
                mSocket.emit("private message", payload);

                MessageItem local = new MessageItem();
                local.id = "local-" + System.currentTimeMillis();
                local.fromUserId = myId;
                local.toUserId = contactId;
                local.text = "Audio enviado";
                local.isAudio = true;
                local.timestamp = System.currentTimeMillis();
                local.ttl = 0;

                runOnUiThread(() -> {
                    Toast.makeText(ChatActivity.this, "Audio enviado!", Toast.LENGTH_SHORT).show();
                    messages.add(local);
                    adapter.notifyDataSetChanged();
                    listView.setSelection(messages.size() - 1);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Erro ao enviar audio: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ===== TOCAR AUDIO RECEBIDO (ja decifrado em memoria) =====
    private MediaPlayer playingAudio;
    private String playingAudioId;

    private void playAudio(MessageItem msg) {
        if (msg.audioBytes == null) return;
        if (playingAudio != null) {
            boolean wasSame = msg.id.equals(playingAudioId);
            playingAudio.release();
            playingAudio = null;
            playingAudioId = null;
            if (wasSame) return;
        }
        try {
            File tempFile = File.createTempFile("papalegua_play_", ".m4a", getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(msg.audioBytes);
            fos.close();

            playingAudioId = msg.id;
            playingAudio = new MediaPlayer();
            playingAudio.setDataSource(tempFile.getAbsolutePath());
            playingAudio.setOnPreparedListener(MediaPlayer::start);
            playingAudio.setOnCompletionListener(mp -> {
                mp.release();
                playingAudio = null;
                playingAudioId = null;
                tempFile.delete();
            });
            playingAudio.prepareAsync();
            Toast.makeText(this, "Tocando audio...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao tocar audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ===== CARREGAR HISTORICO =====
    private void loadMessages() {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL + "/api/private-messages?withUserId=" + contactId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cookie", cookie);
                int responseCode = conn.getResponseCode();
                if (responseCode == 401) {
                    runOnUiThread(() -> {
                        prefs.edit().clear().apply();
                        finish();
                    });
                    return;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONArray jsonMsgs = new JSONArray(response.toString());
                List<MessageItem> loaded = new ArrayList<>();
                for (int i = 0; i < jsonMsgs.length(); i++) {
                    loaded.add(parseMessage(jsonMsgs.getJSONObject(i)));
                }

                runOnUiThread(() -> {
                    messages.clear();
                    messages.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    if (!messages.isEmpty()) listView.setSelection(messages.size() - 1);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Erro ao carregar mensagens", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // Constroi um MessageItem a partir do JSON cru vindo do servidor
    // (mesmo formato tanto no historico quanto no tempo real via socket).
    private MessageItem parseMessage(JSONObject m) throws Exception {
        MessageItem msg = new MessageItem();
        msg.id = m.getString("id");
        msg.fromUserId = m.getString("fromUserId");
        msg.toUserId = m.getString("toUserId");
        msg.isAudio = m.optBoolean("isAudio", false);
        msg.ttl = m.optInt("ttl", 0);
        msg.timestamp = parseTimestamp(m.optString("timestamp", null));

        boolean isMe = msg.fromUserId.equals(myId);
        if (isMe) {
            // Nao da pra decifrar o que eu mesmo mandei (foi cifrado com a
            // chave publica do destinatario, nao com a minha).
            msg.text = msg.isAudio ? "Audio enviado" : "Mensagem enviada";
        } else if (myPrivateKey == null) {
            msg.text = "Chave de criptografia nao disponivel";
        } else {
            try {
                String decrypted = HybridCrypto.decryptFromSender(m.getString("encryptedContent"), myPrivateKey);
                if (msg.isAudio) {
                    msg.audioBytes = Base64.decode(decrypted, Base64.NO_WRAP);
                    msg.text = "Audio (toque pra ouvir)";
                } else {
                    msg.text = decrypted;
                }
            } catch (Exception e) {
                msg.text = "Mensagem criptografada";
            }
        }
        return msg;
    }

    private long parseTimestamp(String iso) {
        if (iso == null) return System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(iso);
            return d != null ? d.getTime() : System.currentTimeMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private void addIncomingMessage(JSONObject m) {
        try {
            MessageItem msg = parseMessage(m);
            messages.add(msg);
            adapter.notifyDataSetChanged();
            listView.setSelection(messages.size() - 1);
        } catch (Exception ignored) {}
    }

    // ===== ADAPTER =====
    class MessageAdapter extends BaseAdapter {
        private LayoutInflater inflater;
        MessageAdapter() {
            inflater = LayoutInflater.from(ChatActivity.this);
        }
        @Override
        public int getCount() { return messages.size(); }
        @Override
        public Object getItem(int position) { return messages.get(position); }
        @Override
        public long getItemId(int position) { return position; }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_message, parent, false);
                holder = new ViewHolder();
                holder.llMessage = convertView.findViewById(R.id.llMessage);
                holder.tvText = convertView.findViewById(R.id.tvText);
                holder.tvTime = convertView.findViewById(R.id.tvTime);
                holder.tvRead = convertView.findViewById(R.id.tvRead);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            MessageItem msg = messages.get(position);
            boolean isMe = msg.fromUserId.equals(myId);

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.llMessage.getLayoutParams();
            if (isMe) {
                params.gravity = android.view.Gravity.END;
                holder.llMessage.setBackgroundResource(R.drawable.bg_message_me);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.llMessage.setBackgroundResource(R.drawable.bg_message_other);
            }
            holder.llMessage.setLayoutParams(params);

            holder.tvText.setText(msg.text);
            if (msg.isAudio && msg.audioBytes != null) {
                holder.tvText.setOnClickListener(v -> playAudio(msg));
            } else {
                holder.tvText.setOnClickListener(null);
            }

            holder.tvTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(msg.timestamp)));
            holder.tvRead.setVisibility(View.GONE);

            return convertView;
        }
        class ViewHolder {
            LinearLayout llMessage;
            TextView tvText;
            TextView tvTime;
            TextView tvRead;
        }
    }
}
