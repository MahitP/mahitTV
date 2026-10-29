package io.freemytv;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, deliberately boring cache for Apple catalogue assets. */
final class AerialCache {
    static final long MAX_BYTES = 1_073_741_824L;
    interface Callback { void complete(File file); }

    private final File directory;
    private final long byteCap;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final byte[] buffer = new byte[16 * 1024];
    private static long lastTouch;

    AerialCache(File directory) { this(directory, MAX_BYTES); }
    AerialCache(File directory, long byteCap) { this.directory = directory; this.byteCap = byteCap; }

    void fetch(final String url, final Callback callback) {
        if (!Wallpaper.allowed(url)) { callback.complete(null); return; }
        worker.execute(() -> callback.complete(download(url)));
    }

    File get(String url) {
        if (!Wallpaper.allowed(url)) return null;
        File file = file(url);
        if (file.isFile() && file.length() > 0) { touch(file); return file; }
        return null;
    }

    File download(String value) {
        if (!Wallpaper.allowed(value) || !directory.exists() && !directory.mkdirs()) return null;
        File ready = get(value);
        if (ready != null) return ready;
        File part = new File(file(value).getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(value).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String redirect = connection.getHeaderField("Location");
                if (!Wallpaper.allowed(redirect)) return null;
                return download(redirect);
            }
            if (code < 200 || code >= 300) return null;
            String type = connection.getContentType();
            long declared = connection.getContentLengthLong();
            if (declared > MAX_BYTES) return null;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(part)) {
                int n; long written = 0;
                while ((n = in.read(buffer)) >= 0) {
                    if (written > MAX_BYTES - n) { part.delete(); return null; }
                    out.write(buffer, 0, n); written += n;
                }
                out.getFD().sync();
            }
            if (!valid(type, part.length())) { part.delete(); return null; }
            File done = file(value);
            if (!part.renameTo(done)) { part.delete(); return null; }
            touch(done);
            cleanup(done);
            return done.isFile() ? done : null;
        } catch (Exception e) {
            part.delete();
            return null;
        } finally { if (connection != null) connection.disconnect(); }
    }

    // Package-visible seam lets host-side tests use controlled bytes and metadata, never a network connection.
    boolean accept(String url, String type, byte[] bytes) {
        if (!Wallpaper.allowed(url) || !valid(type, bytes == null ? 0 : bytes.length)) return false;
        if (!directory.exists() && !directory.mkdirs()) return false;
        File part = new File(file(url).getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes); out.getFD().sync();
        } catch (Exception e) { part.delete(); return false; }
        File done = file(url);
        if (!part.renameTo(done)) { part.delete(); return false; }
        touch(done); cleanup(done); return done.exists();
    }

    /** Host-only stream seam verifies the same bounded-write policy without allocating a gigabyte. */
    boolean acceptStream(String url, String type, InputStream input, long declared, long limit) {
        if (!Wallpaper.allowed(url) || declared > limit || !directory.exists() && !directory.mkdirs()) return false;
        File part = new File(file(url).getPath() + ".part");
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(part)) {
            long written = 0; int n;
            while ((n = in.read(buffer)) >= 0) {
                if (written > limit - n) { part.delete(); return false; }
                out.write(buffer, 0, n); written += n;
            }
            out.getFD().sync();
            if (!valid(type, written)) { part.delete(); return false; }
        } catch (Exception e) { part.delete(); return false; }
        File done = file(url);
        if (!part.renameTo(done)) { part.delete(); return false; }
        return true;
    }

    File file(String url) { return new File(directory, asset(url)); }
    synchronized void touch(File file) {
        if (file == null) return;
        long next = Math.max(System.currentTimeMillis(), Math.max(file.lastModified() + 1, lastTouch + 1));
        file.setLastModified(next);
        lastTouch = next;
    }
    void cleanup() { cleanup(null); }
    void close() { worker.shutdownNow(); }
    int completeCount() { return complete().length; }
    long completeBytes() { long total = 0; for (File f : complete()) total += f.length(); return total; }

    private void cleanup(File preserve) {
        File[] all = directory.listFiles();
        if (all == null) return;
        for (File f : all) if (f.getName().endsWith(".part") || f.length() <= 0) f.delete();
        File[] complete = complete();
        Arrays.sort(complete, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File f : complete) total += f.length();
        int remaining = complete.length;
        for (File victim : complete) {
            if (remaining <= 2 && total <= byteCap) break;
            total -= victim.length();
            if (victim.delete()) remaining--;
        }
    }

    private File[] complete() {
        File[] files = directory.listFiles(f -> !f.getName().endsWith(".part") && f.isFile() && f.length() > 0);
        return files == null ? new File[0] : files;
    }
    private static boolean valid(String type, long bytes) { return bytes > 0 && type != null && (type.startsWith("video/") || type.startsWith("image/")); }
    private static String asset(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 12; i++) out.append(String.format("%02x", hash[i]));
            return out.append(value.endsWith(".mov") ? ".mov" : ".png").toString();
        } catch (Exception e) { return Integer.toHexString(value.hashCode()) + ".asset"; }
    }
}
