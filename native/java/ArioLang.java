package __APPID__;

import android.content.Context;

/** The app language the user picked inside Ario ("fa", "en" or "ar"); the web app tells the native side. */
final class ArioLang {
    private static final String PREFS = "ario_lang";

    static boolean en(Context c) {
        return "en".equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "fa"));
    }

    static String t(Context c, String fa, String en) {
        String l = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "fa");
        if ("ar".equals(l)) {
            String a = ar(fa);
            return a != null ? a : fa;
        }
        return "en".equals(l) ? en : fa;
    }

    /** Arabic texts of the native notifications / widget / lock prompt, keyed by the Persian text. */
    private static String ar(String fa) {
        switch (fa) {
            case "باز کردن Ario": return "فتح Ario";
            case "رمز برنامه": return "رمز التطبيق";
            case "یادآورها": return "التذكيرات";
            case "خلاصه روزانه": return "الملخص اليومي";
            case "پایان تمرکز": return "انتهاء التركيز";
            case "۱۰ دقیقه بعد": return "بعد ١٠ دقائق";
            case "انجام شد": return "تم الإنجاز";
            case "امروز": return "اليوم";
            case "برای دیدن کارها، برنامه را یک بار باز کن": return "لعرض المهام، افتح التطبيق مرة واحدة";
            case "تایمر تمرکز": return "مؤقت التركيز";
            case "شمارش معکوس زندهٔ تمرکز": return "عدّ تنازلي مباشر للتركيز";
            case "تمرکز": return "التركيز";
            default: return null;
        }
    }

    static void set(Context c, String lang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", "en".equals(lang) ? "en" : "ar".equals(lang) ? "ar" : "fa").apply();
    }
}
