package __APPID__;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Saves a PNG, JPEG or WebP picture (and videos) straight into the phone gallery (Pictures/Ario) and copies a PNG to the clipboard. */
@CapacitorPlugin(name = "ImageSave")
public class ImageSavePlugin extends Plugin {

    @PluginMethod
    public void saveImage(PluginCall call) {
        String data = call.getString("data");
        String name = call.getString("name", "ario.png");
        if (data == null || data.isEmpty()) {
            call.reject("no data");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("unsupported");
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            boolean isJpg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
            boolean isWebp = bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            ContentResolver resolver = getContext().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, isJpg ? "image/jpeg" : isWebp ? "image/webp" : "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Ario");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                call.reject("insert failed");
                return;
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                call.reject("open failed");
                return;
            }
            out.write(bytes);
            out.close();
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            JSObject res = new JSObject();
            res.put("uri", uri.toString());
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Saves a video straight into the phone gallery (Movies/Ario). */
    @PluginMethod
    public void saveVideo(PluginCall call) {
        String data = call.getString("data");
        String name = call.getString("name", "ario.mp4");
        String mime = call.getString("mime", "video/mp4");
        if (data == null || data.isEmpty()) {
            call.reject("no data");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("unsupported");
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            ContentResolver resolver = getContext().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Ario");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                call.reject("insert failed");
                return;
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                call.reject("open failed");
                return;
            }
            out.write(bytes);
            out.close();
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            JSObject res = new JSObject();
            res.put("uri", uri.toString());
            call.resolve(res);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Puts the picture itself (not text) on the clipboard so it can be pasted into chats and notes. */
    @PluginMethod
    public void copyImage(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.isEmpty()) {
            call.reject("no data");
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            boolean isJpg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
            boolean isWebp = bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            File dir = new File(getContext().getCacheDir(), "clip");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File o : old) o.delete();
            File f = new File(dir, "ario-" + System.currentTimeMillis() + (isJpg ? ".jpg" : ".png"));
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(bytes);
            fo.close();
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            ClipDescription desc = new ClipDescription("Ario", new String[]{isJpg ? "image/jpeg" : "image/png"});
            ClipData clip = new ClipData(desc, new ClipData.Item(uri));
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(clip);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
