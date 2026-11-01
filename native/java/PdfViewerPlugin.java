package __APPID__;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.UUID;

/**
 * PDF library for the textbooks section.
 * pick()   : opens the system file picker, copies the chosen PDF into the app's private storage
 * info()   : page count and page proportions of a stored PDF
 * page()   : draws one page as a JPEG so the web view can show it inside the app
 *            (file:true -> saved in the cache folder and the path is returned; fast and reused; otherwise base64)
 * remove() : deletes a stored PDF
 * Uses android.graphics.pdf.PdfRenderer, which is part of Android, so no extra library is needed.
 */
@CapacitorPlugin(name = "PdfViewer")
public class PdfViewerPlugin extends Plugin {

    private final Object lock = new Object();
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private String curId;

    private File dir() {
        File d = new File(getContext().getFilesDir(), "pdfs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static boolean okId(String id) {
        return id != null && id.matches("[a-z0-9]{6,32}");
    }

    private File fileFor(String id) {
        return new File(dir(), id + ".pdf");
    }

    private void closeRenderer() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        renderer = null;
        pfd = null;
        curId = null;
    }

    /** Opens (or reuses) the renderer for a stored file. Must be called inside synchronized(lock). */
    private PdfRenderer open(String id) throws Exception {
        if (renderer != null && id.equals(curId)) return renderer;
        closeRenderer();
        File f = fileFor(id);
        if (!f.exists()) throw new java.io.FileNotFoundException("missing");
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pfd);
        curId = id;
        return renderer;
    }

    private static String cleanName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("(?i)\\.pdf$", "").replaceAll("[<>&\"']", "").trim();
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "PDF" : n;
    }

    @PluginMethod
    public void pick(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(call, intent, "pickResult");
    }

    @ActivityCallback
    private void pickResult(final PluginCall call, final ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            JSObject r = new JSObject();
            r.put("cancelled", true);
            call.resolve(r);
            return;
        }
        final Uri uri = result.getData().getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
                File out = fileFor(id);
                try {
                    String name = null;
                    Cursor c = null;
                    try {
                        c = getContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                        if (c != null && c.moveToFirst()) name = c.getString(0);
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) c.close();
                    }
                    InputStream in = getContext().getContentResolver().openInputStream(uri);
                    if (in == null) {
                        call.reject("open failed");
                        return;
                    }
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                    os.close();
                    in.close();

                    int pages;
                    synchronized (lock) {
                        try {
                            PdfRenderer pr = open(id);
                            pages = pr.getPageCount();
                        } finally {
                            closeRenderer();
                        }
                    }
                    if (pages < 1) {
                        out.delete();
                        call.reject("empty");
                        return;
                    }
                    JSObject r = new JSObject();
                    r.put("id", id);
                    r.put("name", cleanName(name));
                    r.put("size", total);
                    r.put("pages", pages);
                    call.resolve(r);
                } catch (SecurityException e) {
                    out.delete();
                    call.reject("password");
                } catch (Exception e) {
                    out.delete();
                    call.reject("invalid");
                }
            }
        }).start();
    }

    @PluginMethod
    public void info(final PluginCall call) {
        final String id = call.getString("id");
        if (!okId(id)) {
            call.reject("bad id");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        PdfRenderer pr = open(id);
                        int n = pr.getPageCount();
                        JSArray ratios = new JSArray();
                        for (int i = 0; i < n; i++) {
                            PdfRenderer.Page p = pr.openPage(i);
                            double w = Math.max(1, p.getWidth());
                            double h = Math.max(1, p.getHeight());
                            p.close();
                            ratios.put(Math.round(h / w * 10000.0) / 10000.0);
                        }
                        JSObject r = new JSObject();
                        r.put("pages", n);
                        r.put("ratios", ratios);
                        call.resolve(r);
                    }
                } catch (java.io.FileNotFoundException e) {
                    call.reject("missing");
                } catch (SecurityException e) {
                    call.reject("password");
                } catch (Exception e) {
                    call.reject("invalid");
                }
            }
        }).start();
    }

    /** Folder for the page pictures that were already drawn (so scrolling back or zooming again is instant). */
    private File cacheDir() {
        File d = new File(getContext().getCacheDir(), "pv");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Keeps the picture cache small: oldest pictures are deleted first. */
    private void trimCache() {
        try {
            File[] fs = cacheDir().listFiles();
            if (fs == null) return;
            long total = 0;
            for (File f : fs) total += f.length();
            final long max = 160L * 1024 * 1024;
            if (total <= max) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : fs) {
                if (total <= max * 0.6) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) { }
    }

    @PluginMethod
    public void page(final PluginCall call) {
        final String id = call.getString("id");
        final int index = call.getInt("index", 0);
        int w0 = call.getInt("width", 1080);
        if (w0 < 120) w0 = 120;
        if (w0 > 2400) w0 = 2400;
        final int width = w0;
        Boolean fb = call.getBoolean("file", false);
        final boolean asFile = fb != null && fb;
        if (!okId(id)) {
            call.reject("bad id");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    File cf = null;
                    if (asFile) {
                        cf = new File(cacheDir(), id + "_" + index + "_" + width + ".jpg");
                        if (cf.exists() && cf.length() > 0) {
                            cf.setLastModified(System.currentTimeMillis());
                            JSObject hit = new JSObject();
                            hit.put("path", cf.getAbsolutePath());
                            hit.put("w", width);
                            call.resolve(hit);
                            return;
                        }
                    }
                    int h;
                    synchronized (lock) {
                        PdfRenderer pr = open(id);
                        if (index < 0 || index >= pr.getPageCount()) {
                            call.reject("range");
                            return;
                        }
                        PdfRenderer.Page p = pr.openPage(index);
                        try {
                            double ratio = (double) p.getHeight() / Math.max(1, p.getWidth());
                            h = (int) Math.round(width * ratio);
                            if (h < 1) h = 1;
                            if (h > 4200) h = 4200;
                            bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                            new Canvas(bmp).drawColor(Color.WHITE);
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            p.close();
                        }
                    }
                    /* the page is drawn; the next page can start while this one is being saved */
                    JSObject r = new JSObject();
                    if (asFile && cf != null) {
                        File tmp = new File(cf.getPath() + ".tmp");
                        FileOutputStream fo = new FileOutputStream(tmp);
                        try {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                        } finally {
                            fo.close();
                        }
                        if (!tmp.renameTo(cf)) {
                            tmp.delete();
                            throw new java.io.IOException("rename");
                        }
                        r.put("path", cf.getAbsolutePath());
                    } else {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                        r.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                    }
                    r.put("w", width);
                    r.put("h", h);
                    call.resolve(r);
                } catch (java.io.FileNotFoundException e) {
                    call.reject("missing");
                } catch (OutOfMemoryError e) {
                    call.reject("memory");
                } catch (Exception e) {
                    call.reject("invalid");
                } finally {
                    if (bmp != null) bmp.recycle();
                }
            }
        }).start();
    }

    @PluginMethod
    public void exists(PluginCall call) {
        String id = call.getString("id");
        JSObject r = new JSObject();
        r.put("exists", okId(id) && fileFor(id).exists());
        call.resolve(r);
    }

    @PluginMethod
    public void close(PluginCall call) {
        synchronized (lock) {
            closeRenderer();
        }
        trimCache();
        call.resolve();
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String id = call.getString("id");
        if (!okId(id)) {
            call.reject("bad id");
            return;
        }
        synchronized (lock) {
            if (id.equals(curId)) closeRenderer();
        }
        fileFor(id).delete();
        try {
            File[] fs = cacheDir().listFiles();
            if (fs != null) for (File f : fs) if (f.getName().startsWith(id + "_")) f.delete();
        } catch (Exception ignored) { }
        call.resolve();
    }
}
