package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.view.Choreographer;
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
 * The Welly tab (WELLY-HOME-REMOTE-SPEC.md §3.5, ANIMATIONS-SPEC.md §4): a 1180-wide centre column. Welly's orb sits
 * centre stage over the greeting while idle, listening and thinking; when an answer arrives it shrinks beside
 * "Welly · mood", the reply is revealed word by word and its cards rise out of the orb. Below: the prompt pills (the
 * one glass layer here) and the "Ask Welly…" capsule with the mic. Drawn by hand over the frosted wallpaper HomeView
 * draws; HomeView hands it the remote's keys while the tab has the focus. Every spring is stepped in the Choreographer
 * callback, never in onDraw, and nothing is allocated per frame.
 */
final class WellyView extends View implements Choreographer.FrameCallback {
    private static final float COL_W = 1180, USER_Y = 396, REPLY_TOP = 424;
    private static final float CARDS_TOP = 568, CARD_GAP = 28, PILLS_TOP = 856, PILL_H = 60, PILL_GAP = 18;
    private static final float INPUT_TOP = 942, INPUT_H = 74;
    // The orb centre stage (and the lines under it), then small beside "Welly · mood".
    private static final float ORB_CY = 296, ORB_R = 104, DOCK_R = 21, DOCK_CY = 232, CAP_DOCK_Y = 240, CAP_DOCK_X = 56;
    private static final float CENTRE_Y = 492, CAP_FRESH_Y = 546, BELOW_Y = 600;
    private static final int ROW_CARDS = 0, ROW_PILLS = 1, ROW_INPUT = 2, MAX_CARDS = 6;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF1C1C1E, SMOKE = 0xFF111113;
    private static final int[] RIM = {0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF0A84FF, 0xFF64D2FF, 0xFFFF9F0A};
    private static final int[] BLOB = {0xFF64D2FF, 0xFFBF5AF2, 0xFFFF9F0A, 0xFF0A84FF};
    private static final String[] PILL_IDS = {"watching", "movie-mode", "scores", "weather", "tonight", "roast"};
    // The tool each sample answer stands for, for the thinking line in Preview.
    private static final String[] PILL_TOOLS = {"whats_playing", "lights", "scores", "weather", "whats_on", "search_titles"};
    private static final float WORDS_PER_SECOND = 28, CARD_STAGGER = 0.045f, CARD_FOCUS = 0.10f, TILT_PUSH = 90;
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
    private String mood = "Curious", partial = "", partialFit = "", placeholder = "", listeningWord = "";
    private StaticLayout reply;
    private boolean replyError, thinking, listening, typing, wantListen;
    private long noteAt;
    private final List<WellyCards.Card> shown = new ArrayList<>();
    private final String[] pills = new String[PILL_IDS.length];
    private final float[] pillW = new float[PILL_IDS.length];
    private int ask;   // bumps with every question: a late answer to an older one is dropped
    private String tool = "", previewTool = "";

    // The two lines under the orb, each a crossfade from the old text to the new.
    private String centre = "", centreOld = "", below = "", belowOld = "";
    private boolean centreBig, centreOldBig;
    private float centreW, centreOldW, belowW, belowOldW;
    private final Spring centreFade = new Spring(0.30f, 1f, 1), belowFade = new Spring(0.30f, 1f, 1);
    // Thinking lines (one per tool), set in texts().
    private String statusWeather = "", statusTitles = "", statusScores = "", statusLights = "", statusOn = "", statusDefault = "";

    // Focus.
    private boolean focused, armed;
    private int row = ROW_PILLS;
    private final int[] col = new int[3];
    private final Spring[] cardFocus = new Spring[MAX_CARDS], pillFocus = new Spring[PILL_IDS.length], inputFocus = new Spring[2];
    private final Spring[] cardTilt = new Spring[MAX_CARDS], cardEnter = new Spring[MAX_CARDS];
    private final float[] enterAt = new float[MAX_CARDS];
    private final Spring cardScroll = new Spring(0.34f, 1f, 0).px(), pillScroll = new Spring(0.34f, 1f, 0).px();
    private final Spring press = new Spring(Spring.MICRO_PRESS, 0), nudge = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private boolean nudgeX;

    // The orb and the exchange.
    private final Spring dock = new Spring(Spring.ORB, 0);      // 0: centre stage; 1: small beside "Welly · mood"
    private final Spring listen = new Spring(Spring.ORB, 0), think = new Spring(Spring.ORB, 0);
    private final Spring speak = new Spring(Spring.ORB, 0), level = new Spring(0.12f, 1f, 0);
    private final Spring replyH = new Spring(Spring.SPATIAL_SCROLL, 0).px(), fly = new Spring(Spring.TAB_SWAP, 0);
    private float revealAt, orbPhase, ringPhase, nodeAngle, orbX, orbY, orbR;
    private int words, shownWords;
    private int[] wordEnd = new int[0], wordLine = new int[0];
    private float[] wordRight = new float[0];
    private boolean flying;
    private final RectF flyFrom = new RectF();
    private String flyText = "";
    private float flyTextW;

    // Frames.
    private boolean posted;
    private long lastFrame;
    private float seconds;

    // Drawing (sized in onSizeChanged; nothing is made in onDraw).
    private float k = 1, colLeft;
    private final TextPaint greet = new TextPaint(Paint.ANTI_ALIAS_FLAG), cap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint capName = new TextPaint(Paint.ANTI_ALIAS_FLAG), user = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint lead = new TextPaint(Paint.ANTI_ALIAS_FLAG), status = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint replyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint field = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG), orbRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final RectF rect = new RectF(), inputRect = new RectF();
    private final Matrix rimMatrix = new Matrix(), tilt3d = new Matrix();
    private final Camera camera = new Camera();
    private final Path unitCircle = new Path();
    private SweepGradient rimShader;
    private final Shader halo = new RadialGradient(0, 0, 1, new int[] {0x99BF5AF2, 0x400A84FF, 0x000A84FF}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
    private final Shader specular = new RadialGradient(0, 0, 1, new int[] {0x8CFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
    private final Shader nodeGlow = new RadialGradient(0, 0, 1, new int[] {0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF}, new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP);
    private final Shader legacyBody = new RadialGradient(-0.3f, -0.35f, 1.4f, new int[] {0xFF64D2FF, 0xFF7A5CF0, 0xFFBF5AF2, 0xFF2A1240},
            new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP);
    private final Shader[] blobs = new Shader[BLOB.length];
    private final SweepGradient orbSweep = new SweepGradient(0, 0, RIM, null);
    private float capNameW, dotW, previewW, capLead;

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
        for (int i = 0; i < MAX_CARDS; i++) {
            cardFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            cardTilt[i] = new Spring(Spring.TILT, 0).deg();
            cardEnter[i] = new Spring(Spring.CARD_ENTER, 0);
        }
        for (int i = 0; i < pillFocus.length; i++) pillFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
        for (int i = 0; i < inputFocus.length; i++) inputFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
        for (int i = 0; i < BLOB.length; i++) {
            int c = BLOB[i] & 0xFFFFFF;
            blobs[i] = new RadialGradient(0, 0, 1, new int[] {0xE6000000 | c, 0x59000000 | c, c}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        unitCircle.addCircle(0, 0, 1, Path.Direction.CW);
        shadow.setColor(0x66000000);
        rim.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        outline.setStyle(Paint.Style.STROKE);
        orbRim.setStyle(Paint.Style.STROKE);
        orbRim.setShader(orbSweep);
        ring.setStyle(Paint.Style.STROKE);
        if (preview) previewData = readPreview(a);
        person();
        texts();
        updateLines();
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
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            // OK acts on release, after the press-down squish (a revealing reply shows whole first).
            if (center && e.getAction() == KeyEvent.ACTION_UP && armed) {
                armed = false;
                press.target = 0;
                kick();
                activate();
            }
            return center;
        }
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
                else bump(1, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, n = count(row), next = col[row] + dir;
                if (next >= 0 && next < n) {
                    col[row] = next;
                    if (row == ROW_CARDS) cardTilt[next].impulse(dir * TILT_PUSH);   // leans toward where it came from
                    retarget();
                    click.move();
                } else {
                    bump(dir, true);
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
                    if (e.getRepeatCount() == 0) {
                        if (revealing()) {   // OK while a reply is being revealed: all of it, now
                            shownWords = words;
                            kick();
                            return true;
                        }
                        armed = true;
                        press.target = 1;
                        kick();
                    }
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
        armed = false;
        press.target = 0;
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
        listen.target = 1;
        dock.target = 0;   // the orb comes back centre stage to listen
        setPartial("");
        kick();
    }

    void resume() {
        if (Ui.generation != generation) texts();
        person();
        if (wantListen) {
            wantListen = false;
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen();
            else note(Ui.tr("Voice isn't available on this TV. Type instead."));
        }
        kick();
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
            flyUp(i);
            if (preview && !WellyBrain.configured()) answerPreview(PILL_IDS[i], true);
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
                        kick();
                    });
                    break;
                case WellyCards.MUSIC:
                    tools.playPause(c);
                    cardArt.prepare(c);
                    kick();
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
                previewTool = "";
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
        for (int i = 0; i < PILL_IDS.length; i++) {
            if (PILL_IDS[i].equals(id)) {
                setUser(pills[i]);
                previewTool = PILL_TOOLS[i];
            }
        }
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
            long wait = delay ? Math.max(0, 1300 - (System.currentTimeMillis() - started)) : 0;
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
        words(text);
        revealAt = seconds;
        shownWords = Spring.instant ? words : 0;
        replyH.target = reply.getHeight();
        if (Spring.instant) replyH.snap(replyH.target);
        shown.clear();
        for (WellyCards.Card c : cards) if (shown.size() < MAX_CARDS) shown.add(c);
        cardArt.prepare(shown);
        for (int i = 0; i < MAX_CARDS; i++) {
            cardFocus[i].snap(0);
            cardTilt[i].snap(0);
            cardEnter[i].snap(Spring.instant ? 1 : 0);
            enterAt[i] = seconds + 0.16f + i * CARD_STAGGER;   // once the orb is on its way to the header
        }
        col[ROW_CARDS] = 0;
        cardScroll.snap(0);
        if (row == ROW_CARDS && shown.isEmpty()) row = ROW_PILLS;
        dock.target = 1;
        texts();
        updateLines();
        retarget();
    }

    private void thinking(boolean on) {
        thinking = on;
        think.target = on ? 1 : 0;
        if (on) {
            dock.target = 0;   // the orb comes back centre stage to think
            tool = "";
        }
        updateLines();
        kick();
    }

    private void setUser(String words) {
        userWords = words;
        userFit = fit(words, user, COL_W * k, TextUtils.TruncateAt.START);
    }

    private void note(String text) {
        note = text;
        noteAt = System.currentTimeMillis();
        kick();
    }

    /** The pressed pill flies up to the line under the orb and becomes the question there. */
    private void flyUp(int i) {
        float x = colLeft - pillScroll.value;
        for (int j = 0; j < i; j++) x += pillW[j] + PILL_GAP * k;
        flyFrom.set(x, PILLS_TOP * k, x + pillW[i], (PILLS_TOP + PILL_H) * k);
        flyText = pills[i];
        flyTextW = pillText.measureText(flyText);
        flying = !Spring.instant;
        fly.snap(0);
        fly.target = 1;
        kick();
    }

    /** No neighbour that way: a short rubber-band tug of the focused thing, and the knock. */
    private void bump(int dir, boolean alongX) {
        if (Spring.instant) return;
        nudgeX = alongX;
        nudge.impulse(dir * Spring.BUMP_VELOCITY);
        Click.boundary();
        kick();
    }

    // MARK: - The lines under the orb

    /** What the two lines under the orb say now; each change crossfades. */
    private void updateLines() {
        String c, b;
        boolean big = false;
        if (listening) {
            c = partial.isEmpty() ? listeningWord : partial;
            b = "";
        } else if (thinking) {
            c = userWords;
            b = statusFor(preview && !WellyBrain.configured() ? previewTool : tool);
        } else if (userWords.isEmpty()) {
            c = greeting;
            big = true;
            b = hint;
        } else {
            c = userWords;
            b = "";
        }
        if (!c.equals(centre) || big != centreBig) {
            centreOld = centre;
            centreOldBig = centreBig;
            centreOldW = centreW;
            centre = fit(c, big ? greet : lead, COL_W * k, TextUtils.TruncateAt.END);
            centreBig = big;
            centreW = (big ? greet : lead).measureText(centre);
            centreFade.snap(0);
            centreFade.target = 1;
        }
        if (!b.equals(below)) {
            belowOld = below;
            belowOldW = belowW;
            below = b;
            belowW = status.measureText(b);
            belowFade.snap(0);
            belowFade.target = 1;
        }
        kick();
    }

    private String statusFor(String tool) {
        switch (tool) {
            case "weather": return statusWeather;
            case "search_titles": case "open_title": case "recap": case "cast_crossref": return statusTitles;
            case "scores": return statusScores;
            case "lights": case "scene": return statusLights;
            case "whats_on": return statusOn;
            default: return statusDefault;
        }
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
            kick();
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
        kick();
    }

    // MARK: - Voice

    private final RecognitionListener voice = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}

        /** The mic's level (dB, about −2 to 10): the orb's rings follow it. */
        @Override public void onRmsChanged(float rmsdB) {
            level.target = Ui.clamp((rmsdB + 2) / 12f);
            kick();
        }

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
        level.target = 0;
        if (reply != null && !thinking) dock.target = 1;   // back beside the name over the last answer
        setPartial("");
    }

    private void setPartial(String text) {
        partial = text;
        partialFit = fit(text, field, inputRect.width() - INPUT_H * k - 44 * k, TextUtils.TruncateAt.START);
        updateLines();
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
        updateLines();
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
        listeningWord = Ui.tr("Listening…");
        hint = Ui.tr("Ask me anything, or pick one below.");
        greeting = Ui.trf("Hey {name}.", "name", person);
        caption = " · " + moodWord(mood);
        statusWeather = Ui.tr("Checking South Brunswick skies…");
        statusTitles = Ui.tr("Judging your watchlist…");
        statusScores = Ui.tr("Squinting at the scoreboard…");
        statusLights = Ui.tr("Finding the light switch…");
        statusOn = Ui.tr("Flipping through the channels…");
        statusDefault = Ui.tr("Thinking it over…");
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
        style(lead, Ui.regular, 34, Ui.alpha(WHITE, 0.85f));
        style(status, Ui.regular, 26, Ui.alpha(WHITE, 0.62f));
        style(replyPaint, Ui.regular, 31, WHITE);
        style(pillText, Ui.medium, 25, WHITE);
        style(field, Ui.regular, 28, Ui.alpha(WHITE, 0.55f));
        style(small, Ui.medium, 18, Ui.alpha(WHITE, 0.7f));
        capLead = Ui.cap(lead);
        shadow.setMaskFilter(new BlurMaskFilter(26 * k, BlurMaskFilter.Blur.NORMAL));
        rim.setStrokeWidth(2.5f * k);
        glow.setStrokeWidth(10 * k);
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
        centre = "";   // laid out again at this size
        below = "";
        updateLines();
        centreFade.snap(1);
        belowFade.snap(1);
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
        if (reply != null) {
            reply = layout(reply.getText().toString());
            words(reply.getText().toString());
            replyH.target = reply.getHeight();
            replyH.snap(replyH.target);
        }
        if (inputRect.width() > 0) partialFit = fit(partial, field, inputRect.width() - INPUT_H * k - 44 * k, TextUtils.TruncateAt.START);
    }

    private StaticLayout layout(String text) {
        replyPaint.setColor(replyError ? Ui.alpha(WHITE, 0.85f) : WHITE);
        int width = Math.max(1, Math.round(COL_W * k));
        return StaticLayout.Builder.obtain(text, 0, text.length(), replyPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.1f).setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
    }

    /** Where each word of the reply ends (its line and x), measured once, so the reveal draws without measuring. */
    private void words(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if ((i == text.length() - 1 || text.charAt(i + 1) == ' ') && text.charAt(i) != ' ') n++;
        wordEnd = new int[n];
        wordLine = new int[n];
        wordRight = new float[n];
        int w = 0, visible = reply.getLineEnd(reply.getLineCount() - 1);
        for (int i = 0; i < text.length() && w < n; i++) {
            if ((i == text.length() - 1 || text.charAt(i + 1) == ' ') && text.charAt(i) != ' ') {
                int end = Math.min(i + 1, visible), line = reply.getLineForOffset(Math.max(0, end - 1));
                wordEnd[w] = end;
                wordLine[w] = line;
                wordRight[w] = end >= reply.getLineEnd(line) ? reply.getLineRight(line) : reply.getPrimaryHorizontal(end);
                w++;
            }
        }
        words = n;
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
        kick();
    }

    private float scrollFor(float x, float w, float total, float now) {
        float view = COL_W * k, margin = 40 * k;
        if (total <= view) return 0;
        float s = now;
        if (x - s < margin) s = x - margin;
        if (x + w - s > view - margin) s = x + w - view + margin;
        return Math.max(0, Math.min(s, total - view));
    }

    // MARK: - Frames (every spring is stepped here, never in onDraw)

    private void kick() {
        if (posted) return;
        posted = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        kick();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
        posted = false;
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) kick();
    }

    /** The orb lives (an idle frame every vsync) only while the tab shows and animations are on. */
    private boolean orbAlive() {
        return !Spring.instant && isAttachedToWindow() && getVisibility() == VISIBLE && getAlpha() > 0.01f && isShown();
    }

    private boolean revealing() {
        return reply != null && shownWords < words;
    }

    @Override public void doFrame(long nanos) {
        posted = false;
        float dt = lastFrame == 0 ? 1 / 60f : (nanos - lastFrame) / 1e9f;
        lastFrame = nanos;
        float step = Math.min(dt, 1 / 30f);
        seconds += step;
        boolean moving = false;
        for (int i = 0; i < MAX_CARDS; i++) {
            if (cardEnter[i].target == 0 && i < shown.size() && seconds >= enterAt[i]) cardEnter[i].target = 1;
            moving |= cardFocus[i].step(dt) | cardTilt[i].step(dt) | cardEnter[i].step(dt);
            if (cardEnter[i].target == 0 && i < shown.size()) moving = true;   // waiting for its turn
        }
        for (Spring s : pillFocus) moving |= s.step(dt);
        for (Spring s : inputFocus) moving |= s.step(dt);
        moving |= cardScroll.step(dt) | pillScroll.step(dt) | press.step(dt) | nudge.step(dt);
        moving |= dock.step(dt) | listen.step(dt) | think.step(dt) | speak.step(dt) | level.step(dt);
        moving |= replyH.step(dt) | centreFade.step(dt) | belowFade.step(dt);
        if (flying) {
            moving |= fly.step(dt);
            if (fly.value > 0.995f && fly.settled()) flying = false;
            else moving = true;
        }
        // The reply, word by word; Welly "speaks" while it shows.
        if (reply != null && shownWords < words) {
            shownWords = Math.min(words, (int) ((seconds - revealAt) * WORDS_PER_SECOND));
            moving = true;
        }
        speak.target = revealing() ? 1 : 0;
        // The thinking line follows the tool the brain is running.
        if (thinking && !(preview && !WellyBrain.configured())) {
            String now = brain.running();
            if (!now.equals(tool)) {
                tool = now;
                updateLines();
            }
        }
        // The orb's slow motion: faster while thinking, the rings while listening.
        float speed = 1 + 2.5f * think.value + 1.2f * listen.value;
        orbPhase += step * speed;
        ringPhase += step * (0.7f + 1.6f * level.value);
        nodeAngle += step * (2 * (float) Math.PI / 1.6f) * think.value;
        if (!note.isEmpty()) moving = true;
        invalidate();
        if (moving || listening || thinking || orbAlive()) {
            posted = true;
            if (Ui.LEGACY && !moving) Choreographer.getInstance().postFrameCallbackDelayed(this, 33);   // the orb at 30 fps
            else Choreographer.getInstance().postFrameCallback(this);
        } else {
            lastFrame = 0;
        }
    }

    // MARK: - Drawing

    @Override
    protected void onDraw(Canvas c) {
        float d = Ui.clamp(dock.value);
        drawOrb(c, d);
        drawHeader(c, d);
        drawLines(c, d);
        drawExchange(c, d);
        drawCards(c, d);
        drawNote(c);
        drawPills(c);
        drawInput(c);
        drawFly(c);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Welly's orb: a slow aurora inside a dark glass sphere, a colour rim, rings while listening, nodes while thinking. */
    private void drawOrb(Canvas c, float d) {
        float cx = lerp(getWidth() / 2f, colLeft + DOCK_R * k, d), cy = lerp(ORB_CY * k, DOCK_CY * k, d);
        float t = seconds, breath = Spring.instant ? 0 : 0.015f * (float) Math.sin(t * 2 * Math.PI / 4);
        float pulse = Spring.instant ? 0 : 0.045f * (float) Math.sin(t * 2 * Math.PI / 0.8f) * think.value
                + 0.05f * (float) Math.abs(Math.sin(t * 2 * Math.PI * 3.5f)) * speak.value;
        float r = lerp(ORB_R, DOCK_R, d) * k * (1 + 0.18f * listen.value + breath + pulse);
        orbX = cx;
        orbY = cy;
        orbR = r;
        c.save();
        c.translate(cx, cy);
        // The halo, wider while listening.
        float hr = r * (1.9f + 0.4f * listen.value);
        c.save();
        c.scale(hr, hr);
        orbPaint.setShader(halo);
        orbPaint.setAlpha(Math.round(255 * (0.55f + 0.35f * listen.value)));
        c.drawCircle(0, 0, 1, orbPaint);
        c.restore();
        // Rings that follow the mic.
        float lv = listen.value;
        if (lv > 0.01f) {
            ring.setStrokeWidth(2 * k);
            for (int j = 0; j < 3; j++) {
                float f = (ringPhase + j / 3f) % 1f;
                ring.setColor(Ui.alpha(j == 1 ? 0xFFBF5AF2 : 0xFF64D2FF, (1 - f) * 0.55f * lv * (0.35f + 0.65f * level.value)));
                c.drawCircle(0, 0, r * (1.05f + f * (0.35f + 0.5f * level.value)), ring);
            }
        }
        c.save();
        c.scale(r, r);
        if (Ui.LEGACY) {
            orbPaint.setShader(legacyBody);   // Android 8–11: one gradient, no clip
            orbPaint.setAlpha(255);
            c.save();
            c.rotate((orbPhase * 40) % 360);
            c.drawCircle(0, 0, 1, orbPaint);
            c.restore();
        } else {
            orbPaint.setShader(null);
            orbPaint.setColor(0xF0121420);
            c.drawCircle(0, 0, 1, orbPaint);
            c.save();
            c.clipPath(unitCircle);
            for (int i = 0; i < blobs.length; i++) {
                float a = orbPhase * (0.9f + 0.25f * i) + i * 1.7f;
                float bx = 0.42f * (float) Math.sin(a), by = 0.42f * (float) Math.cos(a * 0.8f + i);
                float br = 0.78f + 0.12f * (float) Math.sin(a * 1.3f + i);
                c.save();
                c.translate(bx, by);
                c.scale(br, br);
                orbPaint.setShader(blobs[i]);
                orbPaint.setAlpha(Math.round(255 * (0.75f + 0.25f * listen.value)));
                c.drawCircle(0, 0, 1, orbPaint);
                c.restore();
            }
            c.restore();
        }
        // The glass: a highlight top left.
        c.save();
        c.translate(-0.34f, -0.42f);
        c.scale(0.5f, 0.42f);
        orbPaint.setShader(specular);
        orbPaint.setAlpha(255);
        c.drawCircle(0, 0, 1, orbPaint);
        c.restore();
        orbPaint.setShader(null);
        // The rim, turning; sharper and brighter while listening.
        c.save();
        c.rotate((seconds * (40 + 100 * lv)) % 360);
        orbRim.setStrokeWidth((1.5f + 2.5f * lv) * k / Math.max(1e-3f, r));
        orbRim.setAlpha(Math.round(255 * (0.35f + 0.65f * lv)));
        c.drawCircle(0, 0, 1, orbRim);
        c.restore();
        c.restore();
        // Thinking: three specular nodes orbiting.
        float tv = think.value;
        if (tv > 0.01f) {
            for (int j = 0; j < 3; j++) {
                double a = nodeAngle + j * 2 * Math.PI / 3;
                float nx = r * 1.12f * (float) Math.cos(a), ny = r * 1.12f * (float) Math.sin(a), nr = Math.max(4 * k, r * 0.09f);
                c.save();
                c.translate(nx, ny);
                c.scale(nr, nr);
                orbPaint.setShader(nodeGlow);
                orbPaint.setAlpha(Math.round(255 * tv));
                c.drawCircle(0, 0, 1, orbPaint);
                c.restore();
            }
            orbPaint.setShader(null);
        }
        c.restore();
    }

    /** "Welly · mood" (and the Preview badge): centred under the greeting, then beside the small orb. */
    private void drawHeader(Canvas c, float d) {
        float total = capNameW + dotW + (preview ? 16 * k + previewW : 0);
        float x = lerp(getWidth() / 2f - total / 2, colLeft + CAP_DOCK_X * k, d), y = lerp(CAP_FRESH_Y * k, CAP_DOCK_Y * k, d);
        c.drawText("Welly", x, y, capName);
        c.drawText(caption, x + capNameW, y, cap);
        if (preview) {
            float px = x + capNameW + dotW + 16 * k, h = 30 * k;
            rect.set(px, y - Ui.cap(small) / 2 - h / 2 - 2 * k, px + previewW, y - Ui.cap(small) / 2 + h / 2 - 2 * k);
            outline.setColor(Ui.alpha(WHITE, 0.45f));
            c.drawRoundRect(rect, h / 2, h / 2, outline);
            c.drawText(Ui.tr("Preview"), px + 12 * k, rect.centerY() + Ui.halfCap(small), small);
        }
    }

    /** The lines under the orb (the greeting, the question, "Listening…", the thinking line), fading as it docks. */
    private void drawLines(Canvas c, float d) {
        float a = 1 - d;
        if (a < 0.01f) return;
        float mid = getWidth() / 2f, cf = Ui.clamp(centreFade.value), bf = Ui.clamp(belowFade.value);
        float newA = flying ? (float) Math.pow(Ui.clamp(fly.value), 3) : 1;
        drawCentre(c, centreOld, centreOldBig, centreOldW, mid, a * (1 - cf));
        drawCentre(c, centre, centreBig, centreW, mid, a * cf * newA);
        status.setAlpha(Math.round(0x9E * a * (1 - bf)));
        if (!belowOld.isEmpty()) c.drawText(belowOld, mid - belowOldW / 2, BELOW_Y * k, status);
        status.setAlpha(Math.round(0x9E * a * bf));
        if (!below.isEmpty()) c.drawText(below, mid - belowW / 2, BELOW_Y * k, status);
    }

    private void drawCentre(Canvas c, String s, boolean big, float w, float mid, float a) {
        if (s.isEmpty() || a < 0.01f) return;
        TextPaint p = big ? greet : lead;
        int base = big ? 0xFF : 0xD9;
        p.setAlpha(Math.round(base * a));
        c.drawText(s, mid - w / 2, CENTRE_Y * k, p);
        p.setAlpha(base);
    }

    private void drawExchange(Canvas c, float d) {
        if (d < 0.01f) return;
        if (!userFit.isEmpty()) {
            user.setAlpha(Math.round(0x99 * d));
            c.drawText(userFit, colLeft + COL_W * k - user.measureText(userFit), USER_Y * k, user);
            user.setAlpha(0x99);
        }
        if (reply == null || thinking) return;
        // Revealed word by word: every line up to the last word shown, that line clipped at its end.
        int last = Math.min(shownWords, words) - 1;
        if (last < 0) return;
        float top = REPLY_TOP * k, height = replyH.value;
        int lastLine = wordLine[last];
        int base = replyError ? 0xD9 : 0xFF;
        replyPaint.setAlpha(Math.round(base * d));
        for (int line = 0; line <= lastLine && line < reply.getLineCount(); line++) {
            float right = line < lastLine ? reply.getWidth() : wordRight[last] + 2 * k;
            c.save();
            c.translate(colLeft, top);
            c.clipRect(0, reply.getLineTop(line), right, Math.min(reply.getLineBottom(line), height));
            reply.draw(c);
            c.restore();
        }
        replyPaint.setAlpha(base);
    }

    private void drawCards(Canvas c, float d) {
        float h = WellyCards.HEIGHT * k, top = CARDS_TOP * k, x = colLeft - cardScroll.value;
        for (int i = 0; i < shown.size(); i++) {
            WellyCards.Card card = shown.get(i);
            float f = cardFocus[i].value, e = cardEnter[i].value, p = Math.max(0, e);
            boolean mine = focused && row == ROW_CARDS && col[ROW_CARDS] == i;
            if (p > 0.001f && x + card.w > -60 * k && x < getWidth() + 60 * k) {
                // Out of the orb: from its centre (small) to the slot, rising the last 28 px.
                float pe = Math.min(1, p);
                float scx = x + card.w / 2, scy = top + h / 2;
                float ccx = lerp(orbX, scx, pe), ccy = lerp(orbY, scy, pe) + 28 * k * (1 - pe);
                float s = lerp(0.35f, 0.94f, pe) + 0.06f * Math.min(1, Math.max(0, (e - 0.8f) / 0.2f)) * pe;
                s *= 1 + CARD_FOCUS * f;
                if (mine) s *= 1 - 0.065f * press.value;
                c.save();
                if (mine && nudgeX) c.translate(Spring.rubber(nudge.value) * k, 0);
                c.translate(ccx, ccy);
                c.scale(s, s);
                float tilt = cardTilt[i].value;
                if (Math.abs(tilt) > 0.01f) {
                    camera.save();
                    camera.rotateY(-tilt);
                    camera.getMatrix(tilt3d);
                    camera.restore();
                    c.concat(tilt3d);
                }
                c.translate(-card.w / 2, -h / 2);
                float alpha = d * Ui.clamp(p * 1.6f);
                int save = alpha < 0.99f ? c.saveLayerAlpha(0, -20 * k, card.w, h + 40 * k, Math.round(255 * alpha)) : -1;
                if (f > 0.01f) {   // the single focus lift
                    rect.set(0, 10 * k, card.w, h + 10 * k);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.42f * f));
                    shadows.draw(c, rect, WellyCards.RADIUS * k, 26 * k, shadow);
                }
                cardArt.draw(c, card, f);
                if (save >= 0) c.restoreToCount(save);
                c.restore();
            }
            x += card.w + CARD_GAP * k;
        }
    }

    private void drawPills(Canvas c) {
        float h = PILL_H * k, top = PILLS_TOP * k, x = colLeft - pillScroll.value;
        for (int i = 0; i < pills.length; i++) {
            float w = pillW[i], f = pillFocus[i].value;
            boolean mine = focused && row == ROW_PILLS && col[ROW_PILLS] == i;
            if (x + w > -60 * k && x < getWidth() + 60 * k) {
                float s = (1 + 0.06f * f) * (mine ? 1 - 0.065f * press.value : 1);
                float cx = x + w / 2 + (mine && nudgeX ? Spring.rubber(nudge.value) * k : 0), cy = top + h / 2;
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
                c.drawText(pills[i], cx - w / 2 + 28 * k, cy + Ui.halfCap(pillText), pillText);
                c.restore();
            }
            x += w + PILL_GAP * k;
        }
    }

    private void drawInput(Canvas c) {
        boolean mine = focused && row == ROW_INPUT;
        float s = mine ? 1 - 0.03f * press.value : 1;
        float nx = mine && nudgeX ? Spring.rubber(nudge.value) * k : 0, ny = mine && !nudgeX ? Spring.rubber(nudge.value) * k : 0;
        c.save();
        c.translate(nx, ny);
        c.scale(s, s, inputRect.centerX(), inputRect.centerY());
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
        // The mic turns red while listening, following the listening spring (a crossfade, not a swap).
        Symbols.draw(activity, c, "mic", mx, my, 32 * k, blend(ink, blend(0xFFFF375F, INK, mic), listen.value));
        if (!typing) {
            float tx = inputRect.left + INPUT_H * k + 8 * k, ty = my + Ui.halfCap(field), lv = Ui.clamp(listen.value);
            int listenInk = blend(WHITE, INK, fieldF), restInk = blend(Ui.alpha(WHITE, 0.55f), Ui.alpha(INK, 0.55f), fieldF);
            if (lv < 0.99f) {
                field.setColor(Ui.alpha(restInk, 1 - lv));
                c.drawText(placeholder, tx, ty, field);
            }
            if (lv > 0.01f) {
                field.setColor(Ui.alpha(listenInk, lv));
                c.drawText(partialFit.isEmpty() ? listeningWord : partialFit, tx, ty, field);
            }
        }
        if (listen.value > 0.01f && rimShader != null && !Spring.instant) {
            // A quieter echo of the orb's rim on the capsule while the mic is open (the canvas turns, not the shader).
            glow.setAlpha(Math.round(90 * listen.value));
            rim.setAlpha(Math.round(170 * listen.value));
            c.save();
            c.clipRect(inputRect.left - 20 * k, inputRect.top - 20 * k, inputRect.right + 20 * k, inputRect.bottom + 20 * k);
            rimMatrix.setRotate((seconds * 140f) % 360, inputRect.centerX(), inputRect.centerY());
            if (!Ui.LEGACY) rimShader.setLocalMatrix(rimMatrix);   // 8–12 rebuild the gradient on this: a still rim there
            c.drawRoundRect(inputRect, r, r, glow);
            c.drawRoundRect(inputRect, r, r, rim);
            c.restore();
        }
        c.restore();
    }

    /** The pressed pill on its way up to the line under the orb. */
    private void drawFly(Canvas c) {
        if (!flying) return;
        float p = Ui.clamp(fly.value), scale = lerp(1, 34f / 25f, p);
        float fx = flyFrom.centerX(), fy = flyFrom.centerY(), tx = getWidth() / 2f, ty = CENTRE_Y * k - capLead / 2;
        float cx = lerp(fx, tx, p), cy = lerp(fy, ty, p) - 40 * k * (float) Math.sin(Math.PI * p);
        float w = flyFrom.width(), h = flyFrom.height(), capA = 1 - Ui.clamp(p / 0.7f);
        if (capA > 0.01f) {
            rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            fill.setColor(Ui.alpha(WHITE, capA));
            c.drawRoundRect(rect, h / 2, h / 2, fill);
        }
        pillText.setColor(Ui.alpha(blend(INK, WHITE, p), 1 - Ui.clamp((p - 0.85f) / 0.15f)));
        c.save();
        c.scale(scale, scale, cx, cy);
        c.drawText(flyText, cx - flyTextW / 2, cy + Ui.halfCap(pillText), pillText);
        c.restore();
        pillText.setColor(WHITE);
    }

    /** The short line above the pills (no voice, an app that isn't there); fades after 3 s. */
    private void drawNote(Canvas c) {
        if (note.isEmpty()) return;
        float age = (System.currentTimeMillis() - noteAt) / 1000f;
        if (age > 3.4f) {
            note = "";
            return;
        }
        float a = age < 0.2f ? age / 0.2f : age > 3f ? (3.4f - age) / 0.4f : 1;
        user.setColor(Ui.alpha(WHITE, 0.8f * a));
        c.drawText(note, colLeft, (PILLS_TOP - 26) * k, user);
        user.setColor(Ui.alpha(WHITE, 0.6f));
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
