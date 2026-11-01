package __APPID__;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lets the Ario assistant talk: Android's own text-to-speech, resolves when the sentence has been spoken. */
@CapacitorPlugin(name = "ArioTts")
public class ArioTtsPlugin extends Plugin {
    private TextToSpeech tts;
    private boolean ready = false;
    private boolean initializing = false;
    private int seq = 0;
    private String lastLang = null;
    private final List<Runnable> queue = new ArrayList<>();
    private final Map<String, PluginCall> pending = new HashMap<>();

    private synchronized void ensure(final Runnable then) {
        if (tts != null && ready) { then.run(); return; }
        queue.add(then);
        if (initializing) return;
        initializing = true;
        tts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = (status == TextToSpeech.SUCCESS);
                initializing = false;
                if (ready) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {
                            if (id != null && id.endsWith("_0")) notifyListeners("start", new JSObject());
                        }
                        @Override public void onRangeStart(String id, int start, int end, int frame) {
                            notifyListeners("range", new JSObject());
                        }
                        @Override public void onDone(String id) { finish(id, true); }
                        @Override public void onError(String id) { finish(id, false); }
                    });
                }
                List<Runnable> run = new ArrayList<>(queue);
                queue.clear();
                for (Runnable r : run) r.run();
            }
        });
    }

    private void finish(String id, boolean ok) {
        PluginCall c;
        synchronized (pending) { c = pending.remove(id); }
        if (c != null) {
            JSObject o = new JSObject();
            o.put("ok", ok);
            c.resolve(o);
        }
    }

    private void failAll() {
        List<String> ids;
        synchronized (pending) { ids = new ArrayList<>(pending.keySet()); }
        for (String id : ids) finish(id, false);
    }

    private boolean langOk(String lang) {
        try {
            int r = tts.isLanguageAvailable(Locale.forLanguageTag(lang));
            return r >= TextToSpeech.LANG_AVAILABLE;
        } catch (Exception e) { return false; }
    }

    @PluginMethod
    public void isAvailable(final PluginCall call) {
        final String lang = call.getString("lang", "fa-IR");
        ensure(new Runnable() {
            @Override public void run() {
                JSObject o = new JSObject();
                o.put("available", ready && langOk(lang));
                o.put("engine", ready);
                call.resolve(o);
            }
        });
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        final String lang = call.getString("lang", "fa-IR");
        final Float rate = call.getFloat("rate", 1.0f);
        if (text == null || text.trim().isEmpty()) {
            JSObject o = new JSObject();
            o.put("ok", true);
            call.resolve(o);
            return;
        }
        ensure(new Runnable() {
            @Override public void run() {
                JSObject o = new JSObject();
                if (!ready) { o.put("ok", false); o.put("reason", "engine"); call.resolve(o); return; }
                if (!langOk(lang)) { o.put("ok", false); o.put("reason", "lang"); call.resolve(o); return; }
                try {
                    if (!lang.equals(lastLang)) { tts.setLanguage(Locale.forLanguageTag(lang)); lastLang = lang; }
                    tts.setSpeechRate(rate == null ? 1.0f : rate);
                    failAll();
                    /* short pieces: the first one starts sounding much sooner than one long sentence */
                    List<String> parts = new ArrayList<>();
                    for (String p : text.split("(?<=[.!?؟۔،,;؛:\\n])\\s+")) {
                        if (p != null && !p.trim().isEmpty()) parts.add(p.trim());
                    }
                    if (parts.isEmpty()) parts.add(text);
                    String base = "u" + (++seq);
                    String lastId = base + "_" + (parts.size() - 1);
                    synchronized (pending) { pending.put(lastId, call); }
                    for (int i = 0; i < parts.size(); i++) {
                        int r = tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, base + "_" + i);
                        if (r != TextToSpeech.SUCCESS) { finish(lastId, false); break; }
                    }
                } catch (Exception e) {
                    o.put("ok", false);
                    o.put("reason", "error");
                    call.resolve(o);
                }
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try { if (tts != null && ready) tts.stop(); } catch (Exception e) { }
        failAll();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception e) { }
        super.handleOnDestroy();
    }
}
