package io.freemytv;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;

/** Host-side cache contract: no network is needed to exercise its safety rules. */
public final class AerialCacheTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("aerial-cache").toFile();
        try {
            AerialCache cache = new AerialCache(root, 9);
            String one = Wallpaper.aerials()[0].video;
            String two = Wallpaper.aerials()[1].preview;
            no(cache.accept("https://sylvan.apple.com/not-in-the-catalogue.mov", "video/mp4", new byte[]{1}), "rejects non-exact URL");
            no(cache.accept(one, "text/html", new byte[]{1}), "rejects invalid content type");
            no(cache.accept(one, "video/mp4", new byte[0]), "rejects empty content");
            no(cache.acceptStream(one, "video/mp4", new ByteArrayInputStream(new byte[]{1}), 10, 9), "rejects known oversized content length");
            no(cache.file(one).exists(), "oversized header leaves no completed file");
            no(cache.acceptStream(one, "video/mp4", new ByteArrayInputStream(new byte[]{1,2,3,4,5,6,7,8,9,10}), -1, 9), "caps streamed bytes");
            no(new File(cache.file(one).getPath() + ".part").exists(), "oversized stream deletes partial");
            yes(cache.accept(one, "video/mp4", new byte[]{1, 2, 3, 4}), "accepts video");
            File first = cache.file(one);
            yes(first.isFile(), "complete file is atomically named");
            no(new File(first.getPath() + ".part").exists(), "no partial survives successful write");
            yes(cache.accept(two, "image/png", new byte[]{5, 6, 7, 8}), "accepts preview image");
            cache.touch(first);
            yes(cache.accept(Wallpaper.aerials()[2].video, "video/quicktime", new byte[]{9, 10, 11, 12}), "adds third entry");
            yes(first.exists(), "touch preserves requested LRU file");
            no(cache.file(two).exists(), "evicts oldest complete file");
            no(new File(root, "orphan.part").exists(), "partial cleanup is bounded to cache directory");
            Files.write(new File(root, "orphan.part").toPath(), new byte[]{1});
            Files.write(new File(root, "zero.mov").toPath(), new byte[0]);
            cache.cleanup();
            no(new File(root, "orphan.part").exists(), "cleans partial files");
            no(new File(root, "zero.mov").exists(), "cleans invalid complete files");
            ok(cache.completeCount() <= 2, "at most two complete files");
            ok(cache.completeBytes() <= 9, "injected byte cap is obeyed");
            cache.close();
            cache.close();
            yes(cache.closed(), "close is owned and idempotent");
            final File[] afterClose = {first};
            cache.fetch(one, file -> afterClose[0] = file);
            same(null, afterClose[0], "closed cache accepts no more preview work");
        } finally { delete(root); }
        System.out.println("AerialCacheTest: passed");
    }
    private static void delete(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); f.delete(); }
    private static void yes(boolean v, String m) { if (!v) throw new AssertionError(m); }
    private static void no(boolean v, String m) { yes(!v, m); }
    private static void ok(boolean v, String m) { yes(v, m); }
    private static void same(Object want, Object got, String m) { if (want != got) throw new AssertionError(m); }
}
