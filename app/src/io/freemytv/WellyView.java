package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.PopupWindow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Welly tab (WELLY-HOME-REMOTE-SPEC.md §3.5): a 1180-wide centre column with the greeting, the last exchange and
 * its cards, then the prompt pills (the one glass layer here) and the "Ask Welly…" capsule with the mic. Drawn by hand
 * over the frosted wallpaper HomeView draws; HomeView hands it the remote's keys while the tab has the focus.
 */
final class WellyView extends View {
    private static final float COL_W = 1180, GREET_Y = 268, CAPTION_Y = 314, USER_Y = 396, REPLY_TOP = 424;
    private static final float CARDS_TOP = 568, CARD_GAP = 28, PILLS_TOP = 856, PILL_H = 60, PILL_GAP = 18;
    private static final float INPUT_TOP = 942, INPUT_H = 74;
    private static final int ROW_CARDS = 0, ROW_PILLS = 1, ROW_INPUT = 2, MAX_CARDS = 6;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF1C1C1E, SMOKE = 0xFF111113;
    private static final int[] RIM = {0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF0A84FF, 0xFF64D2FF, 0xFFFF9F0A};
    private static final String[] PILL_IDS = {"watching", "movie-mode", "scores", "weather", "tonight", "roast"};
    private static final int VOICE_REQUEST = 0x3E11;

    private final Activity activity;
    private final Scene scene;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final WellyCards cardArt;
    private final WellyTools tools;
    private final WellyBrain brain;
    private final Click click;
    private final boolean preview = Config.PREVIEW;
    private JSONObject previewData;
    private WellyMemory memory;
    private String person = "";
    private int generation = -1;

    // What's on screen.
    private String greeting = "", caption = "", userWords = "", userFit = "", hint = "", note = "";
    private String mood = "Curious", partial = "", partialFit = "", placeholder = "";
    private StaticLayout reply;
    private boolean replyError, thinking, listening, typing, wantListen;
    private long thinkingSince, noteAt;
    private final List<WellyCards.Card> shown = new ArrayList<>();
    private final String[] pills = new String[PILL_IDS.length];
    private final float[] pillW = new float[PILL_IDS.length];
    private int ask;   // bumps with every question: a late answer to an older one is dropped

    // Focus.
    private boolean focused;
    private int row = ROW_PILLS;
    private final int[] col = new int[3];
    private final Spring[] cardFocus = new Spring[MAX_CARDS], pillFocus = new Spring[PILL_IDS.length], inputFocus = new Spring[2];
    private final Spring cardScroll = new Spring(0.34f, 1f, 0), pillScroll = new Spring(0.34f, 1f, 0);
    private final Spring listen = new Spring(0.3f, 1f, 0), think = new Spring(0.3f, 1f, 0);
    private long lastFrame;
    private float seconds;

    // Drawing (sized in onSizeChanged; nothing is made in onDraw).
    private float k = 1, colLeft;
    private final TextPaint greet = new TextPaint(Paint.ANTI_ALIAS_FLAG), cap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint capName = new TextPaint(Paint.ANTI_ALIAS_FLAG), user = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint replyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint field = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final RectF rect = new RectF(), inputRect = new RectF();
    private final Matrix rimMatrix = new Matrix();
    private SweepGradient rimShader;
    private float capNameW, dotW, previewW;

    // Input.
    private SpeechRecognizer recognizer;
    private PopupWindow popup;

    WellyView(Activity a, Scene scene) {
        super(a);
        activity = a;
        this.scene = scene;
        cardArt = new WellyCards(a);
        tools = new WellyTools(a);
        brain = new WellyBrain(tools);
        click = new Click(a);
        for (int i = 0; i < cardFocus.length; i++) cardFocus[i] = new Spring(0.30f, 0.80f, 0);   // snappy
        for (int i = 0; i < pillFocus.length; i++) pillFocus[i] = new Spring(0.30f, 0.80f, 0);
        for (int i = 0; i < inputFocus.length; i++) inputFocus[i] = new Spring(0.30f, 0.80f, 0);
        shadow.setColor(0x66000000);
        rim.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        outline.setStyle(Paint.Style.STROKE);
        if (preview) previewData = readPreview(a);
        person();
        texts();
        if (preview) answerPreview("movie-mode", false);
    }

    // MARK: - HomeView's calls

    /** A key while the Welly tab has the focus. false hands it to HomeView (↑ at the top, Back → the tab bar). */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) listen();
            return true;
        }
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return center;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                int up = row - 1;
                if (up == ROW_CARDS && shown.isEmpty()) up--;
                if (up < 0) return false;
                moveTo(up);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < ROW_INPUT) moveTo(row + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int n = count(row), next = col[row] + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next >= 0 && next < n) {
                    col[row] = next;
                    retarget();
                    click.move();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
                if (listening) {
                    stopListening();
                    return true;
                }
                return false;
            default:
                if (center) {
                    if (e.getRepeatCount() == 0) activate();
                    return true;
                }
                return false;
        }
    }

    void setFocused(boolean focused) {
        if (this.focused == focused) return;
        this.focused = focused;
        if (focused) {
            row = shown.isEmpty() ? ROW_PILLS : ROW_CARDS;
            col[row] = 0;
        }
        retarget();
    }

    /** The remote's voice button (or the mic): starts listening, or stops when already listening. */
    void listen() {
        if (listening) {
            stopListening();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            note(Ui.tr("Voice isn't available on this TV. Type instead."));
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wantListen = true;   // resume() starts listening once it's allowed
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, VOICE_REQUEST);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(voice);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ui.locale().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            note(Ui.tr("Voice isn't available on this TV. Type instead."));
            return;
        }
        listening = true;
        setPartial("");
        listen.target = 1;
        animateNow();
    }

    void resume() {
        if (Ui.generation != generation) texts();
        person();
        if (wantListen) {
            wantListen = false;
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen();
            else note(Ui.tr("Voice isn't available on this TV. Type instead."));
        }
        animateNow();
    }

    void pause() {
        if (listening) stopListening();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (popup != null) popup.dismiss();
    }

    // MARK: - Asking

    private void activate() {
        click.open();
        if (row == ROW_PILLS) {
            int i = col[ROW_PILLS];
            if (preview) answerPreview(PILL_IDS[i], true);
            else send(pills[i]);
        } else if (row == ROW_INPUT) {
            if (col[ROW_INPUT] == 0) listen(); else openKeyboard();
        } else if (col[ROW_CARDS] < shown.size()) {
            WellyCards.Card c = shown.get(col[ROW_CARDS]);
            switch (c.type) {
                case WellyCards.MEDIA:
                    if (!tools.openPick(c.pick)) note(Ui.tr("That app isn't on this TV."));
                    break;
                case WellyCards.HOME:
                    tools.toggle(c, () -> {
                        cardArt.prepare(c);
                        animateNow();
                    });
                    break;
                case WellyCards.MUSIC:
                    tools.playPause(c);
                    cardArt.prepare(c);
                    animateNow();
                    break;
                case WellyCards.SCHEDULE:
                    if (!preview && c.rows.length > 0) send(c.rows[0][1]);
                    break;
                default:
                    break;
            }
        }
    }

    private void send(String words) {
        words = words == null ? "" : words.trim();
        if (words.isEmpty()) return;
        setUser(words);
        int asked = ++ask;
        if (preview && !WellyBrain.configured()) {
            String id = null;
            for (int i = 0; i < pills.length; i++) if (pills[i].equalsIgnoreCase(words)) id = PILL_IDS[i];
            if (id != null) {
                answerPreview(id, true);
            } else {
                thinking(true);
                main.postDelayed(() -> {
                    if (asked != ask) return;
                    showReply(Ui.tr("In Preview I can only answer the questions below."), "Embarrassed", new ArrayList<>(), true);
                }, 700);
            }
            return;
        }
        thinking(true);
        brain.ask(words, r -> {
            if (asked != ask) return;
            showReply(r.text, r.mood, r.cards, r.error);
        });
    }

    /** One of the sample answers in welly-preview.json; `delay` waits a moment, as a brain would. */
    private void answerPreview(String id, boolean delay) {
        JSONArray all = previewData == null ? null : previewData.optJSONArray("pills");
        JSONObject pill = null;
        for (int i = 0; all != null && i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) pill = all.optJSONObject(i);
        if (pill == null) return;
        for (int i = 0; i < PILL_IDS.length; i++) if (PILL_IDS[i].equals(id)) setUser(pills[i]);
        int asked = ++ask;
        JSONObject p = pill;
        thinking(delay);
        long started = System.currentTimeMillis();
        loader.execute(() -> {
            List<WellyCards.Card> cards = new ArrayList<>();
            JSONArray list = p.optJSONArray("cards");
            for (int i = 0; list != null && i < list.length(); i++) {
                try {
                    WellyCards.Card c = WellyTools.fromPreview(activity, list.getJSONObject(i));
                    if (c != null) cards.add(c);
                } catch (Exception e) {
                    AppLog.w("Welly preview: " + e.getMessage());
                }
            }
            long wait = delay ? Math.max(0, 900 - (System.currentTimeMillis() - started)) : 0;
            main.postDelayed(() -> {
                if (asked == ask) showReply(p.optString("reply"), p.optString("mood", "Curious"), cards, false);
            }, wait);
        });
        if (!delay) showReply(pill.optString("reply"), pill.optString("mood", "Curious"), new ArrayList<>(), false);
    }

    private void showReply(String text, String mood, List<WellyCards.Card> cards, boolean error) {
        thinking(false);
        this.mood = mood;
        replyError = error;
        reply = layout(text);
        shown.clear();
        for (WellyCards.Card c : cards) if (shown.size() < MAX_CARDS) shown.add(c);
        cardArt.prepare(shown);
        for (Spring s : cardFocus) s.snap(0);
        col[ROW_CARDS] = 0;
        cardScroll.snap(0);
        if (row == ROW_CARDS && shown.isEmpty()) row = ROW_PILLS;
        texts();
        retarget();
    }

    private void thinking(boolean on) {
        thinking = on;
        if (on) {
            thinkingSince = System.currentTimeMillis();
            reply = null;
        }
        think.target = on ? 1 : 0;
        animateNow();
    }

    private void setUser(String words) {
        userWords = words;
        userFit = fit(words, user, COL_W * k, TextUtils.TruncateAt.START);
    }

    private void note(String text) {
        note = text;
        noteAt = System.currentTimeMillis();
        animateNow();
    }

    // MARK: - Typing (the system keyboard over the capsule)

    private void openKeyboard() {
        if (popup != null) return;
        EditText edit = new EditText(activity);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        edit.setBackground(null);
        edit.setPadding(0, 0, 0, 0);
        edit.setGravity(Gravity.CENTER_VERTICAL);
        edit.setTypeface(Ui.regular);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k);
        edit.setTextColor(INK);
        edit.setHintTextColor(Ui.alpha(INK, 0.45f));
        edit.setHint(placeholder);
        edit.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action != EditorInfo.IME_ACTION_SEND && action != EditorInfo.IME_ACTION_DONE && action != EditorInfo.IME_ACTION_GO && !enter) return false;
            String words = edit.getText().toString();
            if (popup != null) popup.dismiss();
            send(words);
            return true;
        });
        float left = inputRect.left + INPUT_H * k + 8 * k;
        int w = Math.round(inputRect.right - 28 * k - left), h = Math.round(inputRect.height());
        PopupWindow p = new PopupWindow(edit, w, h, true);
        p.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        p.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        p.setOnDismissListener(() -> {
            popup = null;
            typing = false;
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
            animateNow();
        });
        int[] at = new int[2];
        getLocationOnScreen(at);
        popup = p;
        typing = true;
        p.showAtLocation(this, Gravity.NO_GRAVITY, at[0] + Math.round(left), at[1] + Math.round(inputRect.top));
        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
        animateNow();
    }

    // MARK: - Voice

    private final RecognitionListener voice = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onPartialResults(Bundle b) {
            String best = best(b);
            if (best != null && !best.isEmpty()) setPartial(best);
        }

        @Override public void onResults(Bundle b) {
            String best = best(b);
            listeningOff();
            if (best == null || best.trim().isEmpty()) note(Ui.tr("I didn't catch that."));
            else send(best);
        }

        @Override public void onError(int error) {
            listeningOff();
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) note(Ui.tr("I didn't catch that."));
            else if (error != SpeechRecognizer.ERROR_CLIENT) note(Ui.tr("Voice isn't available on this TV. Type instead."));
        }

        private String best(Bundle b) {
            List<String> words = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return words == null || words.isEmpty() ? null : words.get(0);
        }
    };

    private void stopListening() {
        if (recognizer != null) recognizer.cancel();
        listeningOff();
    }

    private void listeningOff() {
        listening = false;
        listen.target = 0;
        setPartial("");
        animateNow();
    }

    private void setPartial(String text) {
        partial = text;
        partialFit = fit(text, field, inputRect.width() - INPUT_H * k - 44 * k, TextUtils.TruncateAt.START);
        animateNow();
    }

    // MARK: - Text and layout (never in onDraw)

    private void person() {
        List<Profiles.Profile> all = Profiles.all(activity);
        int i = Profiles.current(activity);
        String name = all.get(Math.max(0, Math.min(i, all.size() - 1))).name;
        if (name.equals(person) && memory != null) return;
        person = name;
        memory = new WellyMemory(activity, name);
        brain.setPerson(memory, name);
        texts();
    }

    private void texts() {
        generation = Ui.generation;
        pills[0] = Ui.tr("What am I watching?");
        pills[1] = Ui.tr("Dim my room & pick a movie");
        pills[2] = Ui.tr("Live sports scores");
        pills[3] = Ui.tr("South Brunswick weather");
        pills[4] = Ui.tr("What’s on tonight?");
        pills[5] = Ui.tr("Roast my watchlist");
        placeholder = Ui.tr("Ask Welly…");
        hint = Ui.tr("Ask me anything, or pick one below.");
        greeting = Ui.trf("Hey {name}.", "name", person);
        caption = " · " + moodWord(mood);
        sizeText();
    }

    private static String moodWord(String mood) {
        switch (mood) {
            case "Smug": return Ui.tr("Smug");
            case "Sleepy": return Ui.tr("Sleepy");
            case "On it": return Ui.tr("On it");
            case "Embarrassed": return Ui.tr("Embarrassed");
            default: return Ui.tr("Curious");
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0) return;
        k = w / 1920f;
        colLeft = (w - COL_W * k) / 2;
        style(greet, Ui.displaySemibold != null ? Ui.displaySemibold : Ui.semibold, 57, WHITE);
        style(capName, Ui.semibold, 24, Ui.alpha(WHITE, 0.7f));
        style(cap, Ui.regular, 24, Ui.alpha(WHITE, 0.7f));
        style(user, Ui.regular, 26, Ui.alpha(WHITE, 0.6f));
        style(replyPaint, Ui.regular, 31, WHITE);
        style(pillText, Ui.medium, 25, WHITE);
        style(field, Ui.regular, 28, Ui.alpha(WHITE, 0.55f));
        style(small, Ui.medium, 18, Ui.alpha(WHITE, 0.7f));
        shadow.setMaskFilter(new BlurMaskFilter(26 * k, BlurMaskFilter.Blur.NORMAL));
        rim.setStrokeWidth(3.5f * k);
        glow.setStrokeWidth(12 * k);
        glow.setMaskFilter(new BlurMaskFilter(14 * k, BlurMaskFilter.Blur.NORMAL));
        outline.setStrokeWidth(1.5f * k);
        inputRect.set(colLeft, INPUT_TOP * k, colLeft + COL_W * k, (INPUT_TOP + INPUT_H) * k);
        rimShader = new SweepGradient(inputRect.centerX(), inputRect.centerY(), RIM, null);
        rim.setShader(rimShader);
        glow.setShader(rimShader);
        cardArt.setScale(k);
        cardArt.prepare(shown);
        Symbols.warm(activity, "mic", 32 * k);
        sizeText();
        retarget();
        cardScroll.snap(cardScroll.target);
        pillScroll.snap(pillScroll.target);
    }

    private void style(TextPaint p, android.graphics.Typeface face, float size, int color) {
        p.setTypeface(face);
        p.setTextSize(size * k);
        p.setColor(color);
        p.setSubpixelText(true);
    }

    private void sizeText() {
        capNameW = capName.measureText("Welly");
        dotW = cap.measureText(caption);
        previewW = small.measureText(Ui.tr("Preview")) + 24 * k;
        for (int i = 0; i < pills.length; i++) pillW[i] = pillText.measureText(pills[i] == null ? "" : pills[i]) + 56 * k;
        if (!userWords.isEmpty()) setUser(userWords);
        if (reply != null) reply = layout(reply.getText().toString());
        setPartial(partial);
    }

    private StaticLayout layout(String text) {
        replyPaint.setColor(replyError ? Ui.alpha(WHITE, 0.85f) : WHITE);
        int width = Math.max(1, Math.round(COL_W * k));
        return StaticLayout.Builder.obtain(text, 0, text.length(), replyPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.1f).setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
    }

    private static String fit(String s, TextPaint p, float w, TextUtils.TruncateAt where) {
        return TextUtils.ellipsize(s, p, Math.max(0, w), where).toString();
    }

    // MARK: - Focus

    private int count(int r) {
        return r == ROW_CARDS ? shown.size() : r == ROW_PILLS ? pills.length : 2;
    }

    private void moveTo(int r) {
        // Keep roughly the same horizontal place: the field when coming down onto the capsule.
        if (r == ROW_INPUT && row != ROW_INPUT) col[ROW_INPUT] = 1;
        row = r;
        col[r] = Math.max(0, Math.min(col[r], count(r) - 1));
        click.move();
        retarget();
    }

    private void retarget() {
        for (int i = 0; i < cardFocus.length; i++) cardFocus[i].target = focused && row == ROW_CARDS && col[ROW_CARDS] == i ? 1 : 0;
        for (int i = 0; i < pillFocus.length; i++) pillFocus[i].target = focused && row == ROW_PILLS && col[ROW_PILLS] == i ? 1 : 0;
        for (int i = 0; i < inputFocus.length; i++) inputFocus[i].target = focused && row == ROW_INPUT && col[ROW_INPUT] == i ? 1 : 0;
        // Scroll a row only as far as it takes to show the focused item whole.
        float x = 0, total = 0;
        for (int i = 0; i < shown.size(); i++) {
            if (i == col[ROW_CARDS]) x = total;
            total += shown.get(i).w + CARD_GAP * k;
        }
        cardScroll.target = scrollFor(x, shown.isEmpty() ? 0 : shown.get(Math.min(col[ROW_CARDS], shown.size() - 1)).w,
                total - CARD_GAP * k, cardScroll.target);
        x = 0;
        total = 0;
        for (int i = 0; i < pillW.length; i++) {
            if (i == col[ROW_PILLS]) x = total;
            total += pillW[i] + PILL_GAP * k;
        }
        pillScroll.target = scrollFor(x, pillW[col[ROW_PILLS]], total - PILL_GAP * k, pillScroll.target);
        animateNow();
    }

    private float scrollFor(float x, float w, float total, float now) {
        float view = COL_W * k, margin = 40 * k;
        if (total <= view) return 0;
        float s = now;
        if (x - s < margin) s = x - margin;
        if (x + w - s > view - margin) s = x + w - view + margin;
        return Math.max(0, Math.min(s, total - view));
    }

    private void animateNow() {
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    // MARK: - Drawing

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        seconds += dt;
        boolean moving = false;
        for (Spring s : cardFocus) moving |= s.step(dt);
        for (Spring s : pillFocus) moving |= s.step(dt);
        for (Spring s : inputFocus) moving |= s.step(dt);
        moving |= cardScroll.step(dt);
        moving |= pillScroll.step(dt);
        moving |= listen.step(dt);
        moving |= think.step(dt);

        drawHeader(c);
        drawExchange(c);
        drawCards(c);
        moving |= drawNote(c);
        drawPills(c);
        drawInput(c);

        if (moving || listening || thinking) postInvalidateOnAnimation(); else lastFrame = 0;
    }

    private void drawHeader(Canvas c) {
        c.drawText(greeting, colLeft, GREET_Y * k, greet);
        float y = CAPTION_Y * k;
        c.drawText("Welly", colLeft, y, capName);
        c.drawText(caption, colLeft + capNameW, y, cap);
        if (preview) {
            float x = colLeft + capNameW + dotW + 16 * k, h = 30 * k;
            rect.set(x, y - Ui.cap(small) / 2 - h / 2 - 2 * k, x + previewW, y - Ui.cap(small) / 2 + h / 2 - 2 * k);
            outline.setColor(Ui.alpha(WHITE, 0.45f));
            c.drawRoundRect(rect, h / 2, h / 2, outline);
            c.drawText(Ui.tr("Preview"), x + 12 * k, rect.centerY() + Ui.halfCap(small), small);
        }
    }

    private void drawExchange(Canvas c) {
        if (!userFit.isEmpty()) c.drawText(userFit, colLeft + COL_W * k - user.measureText(userFit), USER_Y * k, user);
        float top = REPLY_TOP * k;
        if (think.value > 0.01f) {
            // Three dots that pulse in turn.
            float r = 7 * k, cy = top + 22 * k;
            for (int i = 0; i < 3; i++) {
                float phase = (float) Math.sin((seconds * 1.4f - i * 0.18f) * 2 * Math.PI) * 0.5f + 0.5f;
                fill.setColor(Ui.alpha(WHITE, think.value * (0.35f + 0.65f * phase)));
                c.drawCircle(colLeft + r + i * 26 * k, cy, r * (0.8f + 0.25f * phase), fill);
            }
        }
        if (thinking) return;
        if (reply != null) {
            c.save();
            c.translate(colLeft, top);
            reply.draw(c);
            c.restore();
        } else if (userFit.isEmpty()) {
            c.drawText(hint, colLeft, top + Ui.cap(user) + 6 * k, user);
        }
    }

    private void drawCards(Canvas c) {
        float h = WellyCards.HEIGHT * k, top = CARDS_TOP * k, x = colLeft - cardScroll.value;
        for (int i = 0; i < shown.size(); i++) {
            WellyCards.Card card = shown.get(i);
            float f = cardFocus[i].value;
            if (x + card.w > -60 * k && x < getWidth() + 60 * k) {
                float s = 1 + 0.06f * f;
                c.save();
                c.translate(x + card.w / 2, top + h / 2);
                c.scale(s, s);
                c.translate(-card.w / 2, -h / 2);
                if (f > 0.01f) {   // the single focus lift
                    rect.set(0, 10 * k, card.w, h + 10 * k);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.42f * f));
                    shadows.draw(c, rect, WellyCards.RADIUS * k, 26 * k, shadow);
                }
                cardArt.draw(c, card, f);
                c.restore();
            }
            x += card.w + CARD_GAP * k;
        }
    }

    private void drawPills(Canvas c) {
        float h = PILL_H * k, top = PILLS_TOP * k, x = colLeft - pillScroll.value;
        for (int i = 0; i < pills.length; i++) {
            float w = pillW[i], f = pillFocus[i].value;
            if (x + w > -60 * k && x < getWidth() + 60 * k) {
                float s = 1 + 0.06f * f;
                float cx = x + w / 2, cy = top + h / 2;
                rect.set(cx - w * s / 2, cy - h * s / 2, cx + w * s / 2, cy + h * s / 2);
                float r = rect.height() / 2;
                if (f > 0.01f) {
                    rect.offset(0, 8 * k);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.36f * f));
                    shadows.draw(c, rect, r, 22 * k, shadow);
                    rect.offset(0, -8 * k);
                }
                scene.liquid(c, rect, r, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f, 1 - f);
                if (f > 0.01f) {   // focused: a white pill with ink, like the tab bar
                    fill.setColor(Ui.alpha(WHITE, f));
                    c.drawRoundRect(rect, r, r, fill);
                }
                pillText.setColor(blend(WHITE, INK, f));
                c.save();
                c.scale(s, s, cx, cy);
                c.drawText(pills[i], x + 28 * k, cy + Ui.halfCap(pillText), pillText);
                c.restore();
            }
            x += w + PILL_GAP * k;
        }
    }

    private void drawInput(Canvas c) {
        float r = inputRect.height() / 2, mic = inputFocus[0].value, fieldF = typing ? 1 : inputFocus[1].value;
        scene.liquid(c, inputRect, r, SMOKE, 0.42f, 0.02f, 14 * k, 9 * k, 0.75f, 1 - fieldF);
        if (fieldF > 0.01f) {
            fill.setColor(Ui.alpha(WHITE, fieldF));
            c.drawRoundRect(inputRect, r, r, fill);
        }
        float mx = inputRect.left + r, my = inputRect.centerY();
        if (mic > 0.01f) {
            fill.setColor(Ui.alpha(WHITE, mic));
            c.drawCircle(mx, my, (r - 6 * k) * (1 + 0.06f * mic), fill);
        }
        int ink = blend(WHITE, INK, Math.max(mic, fieldF));
        Symbols.draw(activity, c, "mic", mx, my, 32 * k, listening ? blend(0xFFFF375F, INK, mic) : ink);
        if (!typing) {
            float tx = inputRect.left + INPUT_H * k + 8 * k, ty = my + Ui.halfCap(field);
            if (listening) {
                field.setColor(blend(WHITE, INK, fieldF));
                c.drawText(partialFit.isEmpty() ? Ui.tr("Listening…") : partialFit, tx, ty, field);
            } else {
                field.setColor(blend(Ui.alpha(WHITE, 0.55f), Ui.alpha(INK, 0.55f), fieldF));
                c.drawText(placeholder, tx, ty, field);
            }
        }
        if (listen.value > 0.01f && rimShader != null) {
            // The one moment of colour: a moving rim while the mic is open.
            rimMatrix.setRotate(seconds * 140f, inputRect.centerX(), inputRect.centerY());
            rimShader.setLocalMatrix(rimMatrix);
            glow.setAlpha(Math.round(150 * listen.value));
            rim.setAlpha(Math.round(255 * listen.value));
            c.drawRoundRect(inputRect, r, r, glow);
            c.drawRoundRect(inputRect, r, r, rim);
        }
    }

    /** The short line above the pills (no voice, an app that isn't there); fades after 3 s. */
    private boolean drawNote(Canvas c) {
        if (note.isEmpty()) return false;
        float age = (System.currentTimeMillis() - noteAt) / 1000f;
        if (age > 3.4f) {
            note = "";
            return false;
        }
        float a = age < 0.2f ? age / 0.2f : age > 3f ? (3.4f - age) / 0.4f : 1;
        user.setColor(Ui.alpha(WHITE, 0.8f * a));
        c.drawText(note, colLeft, (PILLS_TOP - 26) * k, user);
        user.setColor(Ui.alpha(WHITE, 0.6f));
        return true;
    }

    private static int blend(int a, int b, float t) {
        t = Ui.clamp(t);
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static JSONObject readPreview(Context c) {
        try (InputStream in = c.getAssets().open("welly-preview.json")) {
            return new JSONObject(new String(WellyTools.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("Welly preview: " + e.getMessage());
            return null;
        }
    }
}
