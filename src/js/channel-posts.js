/* ---------- کانال Ario · Ario Channel: the posts ----------
   Every change or new feature of the app gets ONE post here (newest first). The app shows them in the channel page,
   which opens from the first banner of the "امروز" (Today) screen.

   Add a post with:   node scripts/add-channel-post.mjs --title "..." --text "..." [--kind new|imp|fix|info] [--img path/to/picture] [--en-title "..." --en-text "..."]
   (the script copies the picture into www/img and inserts the post below the marker line, so the post is built into the next release)

   Fields: id (unique)  d (YYYY-MM-DD, optional)  v (version label, optional)  k (new | imp | fix | info)
           t / x = title / text in Persian   en / ar = {t,x} (optional; Persian is shown when missing)
           img = file name inside www/img (optional) */
Object.assign(__AR,{
"کانال Ario":"قناة Ario","کانال رسمی برنامه":"القناة الرسمية للتطبيق","ویژگی تازه":"ميزة جديدة","بهبود":"تحسين","رفع اشکال":"إصلاح","اطلاعیه":"إعلان",
"جدید":"جديد","فقط خواندنی":"للقراءة فقط","پست":"منشور","هنوز پستی نیست":"لا توجد منشورات بعد","پست‌ها خودکار با هر تغییر برنامه اضافه می‌شوند":"تُضاف المنشورات تلقائياً مع كل تغيير في التطبيق",
"پست تازه در کانال Ario":"منشور جديد في قناة Ario","ببین":"عرض","برگشت":"رجوع","پست‌های تازه":"منشورات جديدة","بی‌صدا":"كتم","صدا را باز کن":"إلغاء الكتم","پسندیدن":"إعجاب","ارسال":"إرسال","متن پست کپی شد":"تم نسخ نص المنشور"
});
var CHAN_POSTS=[
/*POSTS*/
{id:'v298-polls',d:'2026-11-06',v:"v298",k:'new',img:'chn-v298-polls.webp',
 t:"نظرسنجی در گفتگو 📊",
 x:"حالا در هر گفتگو (مشاور، دوست و گروه) روی گیره بزن و «نظرسنجی» را انتخاب کن.\n\nسؤال و تا ۱۰ گزینه بنویس و اگر خواستی چندجوابه، قابل‌تغییر یا با ترتیب به‌هم‌ریخته‌اش کن. با «جواب درست» هم می‌توانی آزمون بسازی. درصد رأی‌ها زنده دیده می‌شود.",
 en:{t:"Polls in chats 📊",x:"In any chat (advisor, friend or group) tap the paper clip and choose “Poll”.\n\nWrite a question and up to 10 options; make it multiple-answer, revotable or shuffled. Set a correct answer to make a quiz. Vote percentages update live."}},
{id:'channel-created',d:'2026-11-06',k:'new',img:'chn-welcome.webp',
 t:'کانال Ario در برنامه ساخته شد 📣',
 x:'از این به بعد هر تغییر و هر ویژگی تازه‌ی برنامه با عکس و توضیح همین‌جا خودکار اضافه می‌شود.\n\nبنر کانال همیشه اولین بنر صفحه‌ی «امروز» است؛ رویش بزن تا وارد کانال شوی. پست‌های تازه با نشانه‌ی «جدید» و یک عدد روی بنر دیده می‌شوند.',
 en:{t:'Ario Channel is now in the app 📣',x:'From now on, every change and every new feature of the app is added here automatically, with a picture and a short text.\n\nThe channel banner is always the first banner on the Today screen; tap it to join. New posts are marked “New”, with a number on the banner.'},
 ar:{t:'تم إنشاء قناة Ario في التطبيق 📣',x:'من الآن فصاعداً تُضاف هنا تلقائياً كل تغييرات التطبيق وكل ميزة جديدة مع صورة ونص.\n\nلافتة القناة هي دائماً أول لافتة في شاشة «اليوم»؛ اضغط عليها للانضمام. تظهر المنشورات الجديدة بعلامة «جديد» ورقم على اللافتة.'}},
{id:'v296-groups',v:'v296',k:'new',
 t:'گروه‌ها در گفتگو',
 x:'حالا می‌توانی از بین دوستانت گروه بسازی، عضو اضافه یا حذف کنی، اسم گروه را عوض کنی، گروه را بی‌صدا کنی یا ترکش کنی.\n\nیک پیام را هم می‌شود هم‌زمان برای چند دوست فرستاد.',
 en:{t:'Groups in chat',x:'You can now create a group from your friends, add or remove members, rename it, mute it or leave it.\n\nOne message can also be sent to several friends at once.'}},
{id:'new-characters',k:'new',img:'ad1.webp',
 t:'چهار کاراکتر تازه: FROST، LOVEX، NOX و RAVEN',
 x:'از بنر «کاراکترهای جدید» در صفحه‌ی امروز هر کاراکتر را باز کن. با لمس کاراکتر، نمای پشتش را هم می‌بینی.',
 en:{t:'Four new characters: FROST, LOVEX, NOX and RAVEN',x:'Open any character from the “new characters” banner on the Today screen. Tap a character to see its back view too.'}},
{id:'v13-banners',v:'v13',k:'new',img:'bn-castle.webp',
 t:'بنرهای تازه برای پروفایل',
 x:'گروه Ario (سطح ۲)، خیابان (۳)، قلعه (۴)، اسب‌سواران (۵)، شب‌نشینی (۶)، کمپ جنگلی (۷) و دو بنر VIP: غروب شهر و گرافیتی.\n\nاز «حساب» ← «ویرایش مشخصات» ← «بنر پروفایل» انتخاب می‌شوند و بنر صفحه‌ی امروز هم همان را نشان می‌دهد.',
 en:{t:'New profile banners',x:'Ario group (level 2), Street (3), Castle (4), Horse riders (5), Evening party (6), Forest camp (7) and two VIP banners: City sunset and Graffiti.\n\nPick one in Account → Edit profile → Profile banner; the Today banner shows it too.'}},
{id:'v29-table-scan',v:'v29',k:'new',
 t:'اسکن عکس جدول کنکوریا',
 x:'در ویرایش جدول کنکوریا دکمه‌ی «اسکن عکس» را بزن، عکس جدول را انتخاب کن، نوشته‌ها را در صفحه‌ی بررسی درست کن و روی جدول اعمالش کن. خواندن متن بدون اینترنت انجام می‌شود.',
 en:{t:'Scan a timetable photo',x:'In the Konkoria table editor tap “Scan photo”, pick the picture, fix the text on the review page and apply it to the table. Text is read offline.'}},
{id:'v25-voice',v:'v25',k:'new',
 t:'ثبت با صدا',
 x:'میکروفون کنار نوار افزودن سریع و در کنکوریا کار می‌کند. مثلاً بگو «دو ساعت و نیم ریاضی خواندم» تا درس و مدت خودکار ثبت شود. در گزارش روزانه هم زیر هر سؤال «با صدا بگو» هست.',
 en:{t:'Voice entry',x:'The microphone works next to quick add and in Konkoria. Say “I studied maths for two and a half hours” and the lesson and time are filled in. The daily report has “Say it” under every question too.'}}
];
