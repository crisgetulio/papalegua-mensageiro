package com.papalegua.app;

import android.util.Base64;
import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

// Espelha o esquema usado no site (public/hybrid-crypto.js):
// AES-256-GCM cifra o conteudo (texto ou audio em base64 tratado como texto),
// e RSA-OAEP(SHA-256) cifra so a chave AES (32 bytes) com a chave publica do destinatario.
// Isso precisa bater byte a byte com o Web Crypto do navegador pra web e Android
// conseguirem ler as mensagens um do outro.
public class HybridCrypto {

    private static byte[] pemToDer(String pem) {
        String b64 = pem
            .replaceAll("-----BEGIN[^-]+-----", "")
            .replaceAll("-----END[^-]+-----", "")
            .replaceAll("\\s", "");
        return Base64.decode(b64, Base64.NO_WRAP);
    }

    public static PublicKey importPublicKey(String pem) throws Exception {
        byte[] der = pemToDer(pem);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    public static PrivateKey importPrivateKey(String pem) throws Exception {
        byte[] der = pemToDer(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    // RSA-OAEP com SHA-256 tanto no hash principal quanto no MGF1,
    // igual ao Web Crypto ({ name: 'RSA-OAEP', hash: 'SHA-256' }).
    private static OAEPParameterSpec oaepSha256() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static byte[] rsaWrap(byte[] aesKeyRaw, PublicKey recipientPublicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey, oaepSha256());
        return cipher.doFinal(aesKeyRaw);
    }

    private static byte[] rsaUnwrap(byte[] wrapped, PrivateKey myPrivateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, myPrivateKey, oaepSha256());
        return cipher.doFinal(wrapped);
    }

    // Cifra um texto (ou uma string base64 tratada como texto puro, igual o site
    // faz com audio) e devolve o payload JSON {iv, ciphertext, wrappedKey} pronto
    // pra mandar no campo encryptedContent via socket.
    public static String encryptForRecipient(String plainText, PublicKey recipientPublicKey) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = aesCipher.doFinal(plainText.getBytes("UTF-8"));

        byte[] wrappedKey = rsaWrap(aesKey.getEncoded(), recipientPublicKey);

        JSONObject payload = new JSONObject();
        payload.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        payload.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        payload.put("wrappedKey", Base64.encodeToString(wrappedKey, Base64.NO_WRAP));
        return payload.toString();
    }

    // Decifra um payload {iv, ciphertext, wrappedKey} recebido, devolvendo o texto original.
    public static String decryptFromSender(String payloadJson, PrivateKey myPrivateKey) throws Exception {
        JSONObject payload = new JSONObject(payloadJson);
        byte[] iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(payload.getString("ciphertext"), Base64.NO_WRAP);
        byte[] wrappedKey = Base64.decode(payload.getString("wrappedKey"), Base64.NO_WRAP);

        byte[] rawAesKey = rsaUnwrap(wrappedKey, myPrivateKey);
        SecretKey aesKey = new SecretKeySpec(rawAesKey, "AES");

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] plainBytes = aesCipher.doFinal(ciphertext);
        return new String(plainBytes, "UTF-8");
    }
}
