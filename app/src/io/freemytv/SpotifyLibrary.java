package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Metadata only. Playback always opens the Spotify app. */
final class SpotifyLibrary {
    static final String REDIRECT = "http://127.0.0.1:8765/spotify/callback";
    private static final String TOKEN = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1/";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static final class Item {
        final String title, uri, image;
        Bitmap cover;
        BitmapShader shader;
        boolean requested;

        Item(JSONObject json) {
            title = json.optString("title", json.optString("name"));
            uri = json.optString("uri");
            JSONObject album = json.optJSONObject("album");
            JSONArray images = (album == null ? json : album).optJSONArray("images");
            JSONObject first = images == null ? null : images.optJSONObject(Math.min(1, images.length() - 1));
            image = json.optString("image", first == null ? "" : first.optString("url"));
        }
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences settings;
    private final Runnable changed;
    private final List<Item> defaults = new ArrayList<>();
    private final List<Item> personal = new ArrayList<>();
    private boolean initialized, loading;
    private int generation;
    private long loadedAt;
    private ServerSocket login;
    String status = "";

    SpotifyLibrary(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        settings = activity.getSharedPreferences("spotify_account", Context.MODE_PRIVATE);
    }

    List<Item> items() {
        return personal.isEmpty() ? defaults : personal;
    }

    boolean connected() {
        return settings.contains("refresh");
    }

    void load() {
        if (loading || initialized && (!connected() || System.currentTimeMillis() - loadedAt < 15 * 60_000)) return;
        loading = true;
        int request = generation;
        String client = settings.getString("client", "");
        String refresh = settings.getString("refresh", "");
        IO.execute(() -> {
            List<Item> fixed = new ArrayList<>();
            List<Item> library = new ArrayList<>();
            String nextRefresh = refresh;
            boolean failed = false;
            try (InputStream stream = activity.getAssets().open("spotify-defaults.json")) {
                add(new JSONArray(read(stream, 64_000)), fixed);
            } catch (Exception ignored) {
                // The public selection is optional in source builds.
            }
            if (!refresh.isEmpty()) {
                try {
                    JSONObject token = request(TOKEN, "grant_type=refresh_token&client_id=" + encode(client)
                            + "&refresh_token=" + encode(refresh), null);
                    nextRefresh = token.optString("refresh_token", refresh);
                    String access = token.getString("access_token");
                    add(request(API + "me/top/tracks?limit=10", null, access).optJSONArray("items"), library);
                    JSONArray albums = request(API + "me/albums?limit=10", null, access).optJSONArray("items");
                    for (int i = 0; albums != null && i < albums.length(); i++) {
                        JSONObject wrapper = albums.optJSONObject(i);
                        if (wrapper != null) addItem(wrapper.optJSONObject("album"), library);
                    }
                    add(request(API + "me/playlists?limit=10", null, access).optJSONArray("items"), library);
                } catch (Exception ignored) {
                    failed = true;
                }
            }
            boolean error = failed;
            String savedRefresh = nextRefresh;
            main.post(() -> {
                if (request != generation) return;
                if (!initialized) {
                    defaults.addAll(fixed);
                    initialized = true;
                }
                if (!refresh.isEmpty()) {
                    settings.edit().putString("refresh", savedRefresh).apply();
                    personal.clear();
                    personal.addAll(library);
                    status = error ? "Alguns itens do Spotify não puderam ser carregados." : "";
                }
                loading = false;
                loadedAt = System.currentTimeMillis();
                changed.run();
            });
        });
    }

    private static void add(JSONArray rows, List<Item> into) {
        for (int i = 0; rows != null && i < rows.length(); i++) addItem(rows.optJSONObject(i), into);
    }

    private static void addItem(JSONObject json, List<Item> into) {
        if (json == null) return;
        Item item = new Item(json);
        if (!item.title.isEmpty() && item.uri.matches("spotify:(album|playlist|track):[A-Za-z0-9]{22}")) into.add(item);
    }

    void cover(Item item) {
        if (item.requested || !item.image.startsWith("https://")) return;
        item.requested = true;
        IO.execute(() -> {
            try {
                byte[] bytes = NetPicture.get(item.image);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                main.post(() -> {
                    item.cover = bitmap;
                    if (bitmap != null) item.shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    changed.run();
                });
            } catch (Exception ignored) {
                // Keep a text card when Spotify does not publish a cover.
            }
        });
    }

    void account() {
        if (connected()) {
            new AlertDialog.Builder(activity).setTitle("Conta Spotify")
                    .setMessage("Desconectar esta conta da prévia?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Desconectar", (dialog, which) -> {
                        generation++;
                        loading = false;
                        settings.edit().clear().apply();
                        personal.clear();
                        status = "";
                        load();
                        changed.run();
                    }).show();
            return;
        }
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("Client ID");
        new AlertDialog.Builder(activity).setTitle("Conectar Spotify")
                .setMessage("Use um app Spotify autorizado para a sua conta. Cadastre " + REDIRECT
                        + " como Redirect URI. O login abre no navegador desta TV.")
                .setView(input).setNegativeButton("Cancelar", null)
                .setPositiveButton("Conectar", (dialog, which) -> {
                    String client = input.getText().toString().trim();
                    if (!client.matches("[A-Fa-f0-9]{32}")) {
                        message("Client ID inválido.");
                    } else {
                        startLogin(client);
                    }
                }).show();
    }

    private void startLogin(String client) {
        closeLogin();
        try {
            ServerSocket server = new ServerSocket(8765, 4, InetAddress.getByName("127.0.0.1"));
            login = server;
            String verifier = random(), state = random();
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
            Uri authorize = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                    .appendQueryParameter("client_id", client).appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("state", state)
                    .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("scope", "user-top-read user-library-read playlist-read-private playlist-read-collaborative").build();
            activity.startActivity(new Intent(Intent.ACTION_VIEW, authorize));
            Thread callback = new Thread(() -> finishLogin(server, client, verifier, state), "SpotifyOAuth");
            callback.setDaemon(true);
            callback.start();
        } catch (ActivityNotFoundException e) {
            closeLogin();
            message("Instale um navegador na TV para autorizar a conta Spotify.");
        } catch (Exception e) {
            closeLogin();
            message("Não foi possível iniciar o login Spotify.");
        }
    }

    private void finishLogin(ServerSocket server, String client, String verifier, String state) {
        try (ServerSocket bound = server) {
            bound.setSoTimeout(180_000);
            for (int i = 0; i < 8; i++) {
                try (Socket socket = bound.accept()) {
                    socket.setSoTimeout(3000);
                    Uri callback = readCallback(socket.getInputStream());
                    String code = callback == null ? null : callback.getQueryParameter("code");
                    boolean valid = callback != null && state.equals(callback.getQueryParameter("state"));
                    if (valid && code != null) {
                        JSONObject token = request(TOKEN, "grant_type=authorization_code&client_id=" + encode(client)
                                + "&code=" + encode(code) + "&redirect_uri=" + encode(REDIRECT)
                                + "&code_verifier=" + encode(verifier), null);
                        String refresh = token.getString("refresh_token");
                        reply(socket, "Spotify conectado. Volte para o launcher.");
                        main.post(() -> {
                            if (login != server) return;
                            login = null;
                            settings.edit().putString("client", client).putString("refresh", refresh).apply();
                            loadedAt = 0;
                            load();
                            changed.run();
                        });
                        return;
                    }
                    reply(socket, valid ? "Autorizacao cancelada." : "Retorno invalido.");
                    if (valid) break;
                }
            }
        } catch (Exception ignored) {
            // Do not expose OAuth codes or token details in logs.
        }
        main.post(() -> {
            if (login == server) {
                login = null;
                message("A conexão com o Spotify não foi concluída.");
            }
        });
    }

    private static Uri readCallback(InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int b = stream.read();
            if (b < 0 || b == '\n') break;
            bytes.write(b);
        }
        String[] line = bytes.toString("UTF-8").trim().split(" ", 3);
        if (line.length != 3 || !"GET".equals(line[0])) return null;
        Uri uri = Uri.parse(line[1]);
        return "/spotify/callback".equals(uri.getPath()) ? uri : null;
    }

    private static void reply(Socket socket, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n"
                + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    void closeLogin() {
        ServerSocket server = login;
        login = null;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    private void message(String text) {
        if (!activity.isFinishing()) new AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show();
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return Uri.encode(value);
    }

    private static JSONObject request(String url, String form, String bearer) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(read(in, 512_000));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1; ) {
            if (content.size() + n > limit) throw new java.io.IOException("Response too large");
            content.write(buffer, 0, n);
        }
        return content.toString("UTF-8");
    }
}
