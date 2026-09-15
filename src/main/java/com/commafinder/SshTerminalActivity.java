package com.commafinder;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint({"SetTextI18n", "SpellCheckingInspection"})
public class SshTerminalActivity extends AppCompatActivity {

    private static final String TAG = "SshTerminal";
    private static final int MAX_CONSOLE_CHARS = 200_000;
    private static final int MAX_MACRO_COUNT = 100;
    private static final int MAX_MACRO_LABEL_LENGTH = 100;
    private static final int MAX_MACRO_COMMAND_LENGTH = 10_000;
    private static final float KEYBOARD_COLUMN_COUNT = 10.0f;
    private static final int KEYBOARD_KEY_HEIGHT_DP = 48;
    private static final int KEYBOARD_KEY_GAP_DP = 2;
    private static final float KEYBOARD_TEXT_SIZE_SP = 18f;
    private static final float KEYBOARD_ICON_SIZE_SP = 21f;
    private static final String CUSTOM_KEY_FILE_NAME = "custom_id_rsa";
    private static final String DEFAULT_KEY_FILE_NAME = "id_rsa_current";
    private static final String DEFAULT_KEY_STAGING_FILE_NAME = "id_rsa_staging";
    private static final String DEFAULT_KEY_ASSET_NAME = "id_rsa.pem";
    private static final String TMUX_DETACH_COMMAND = "`d";
    private static final String TMUX_CLOSE_LEGACY_COMMAND = "`x";
    private static final String TMUX_CLOSE_COMMAND = "`xy\n";
    private static final Pattern OSC_PATTERN =
            Pattern.compile("\u001B].*?(?:\u0007|\u001B\\\\)");
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("\u001B[()][A-Za-z0-9]");
    private static final Pattern SINGLE_ESCAPE_PATTERN =
            Pattern.compile("\u001B[=><M78]");
    private static final Pattern CSI_PATTERN =
            Pattern.compile("\u001B\\[([0-9;?=<>]*)([a-zA-Z])");

    private String targetIp;

    private TextView tvStatusIcon;
    private TextView tvTitle;
    private TextView tvConsole;
    private ScrollView scrollConsole;
    private EditText etInput;
    private LinearLayout macroContainer;

    private LinearLayout inputLayout;
    private LinearLayout customKeyboardLayout;
    private boolean isAutoScroll = true;
    private TextView btnAutoScroll;

    private LinearLayout macroHeader;
    private HorizontalScrollView macroScroll;
    private TextView btnToggleKb;
    private EditText macroKeyboardTarget;
    private EditText macroLabelInput;
    private EditText macroCommandInput;

    private static final String SHIFT_INACTIVE_KEY = "SHIFT_INACTIVE";
    private static final String SHIFT_ACTIVE_KEY = "SHIFT_ACTIVE";
    private static final String BACKSPACE_KEY = "BACKSPACE";
    private static final String NUMERIC_KEY = "NUMERIC";
    private static final String SYMBOL_KEY = "SYMBOL";
    private static final String ABC_KEY = "ABC";
    private static final String TAB_KEY = "TAB";
    private static final String SPACE_KEY = "SPACE";
    private static final String ENTER_KEY = "ENTER";

    private enum KeyboardMode {
        LOWERCASE, UPPERCASE, NUMBERS, SPECIALS
    }

    private static final String[][] LOWERCASE_KEYBOARD = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {SHIFT_INACTIVE_KEY, "z", "x", "c", "v", "b", "n", "m", BACKSPACE_KEY},
            {NUMERIC_KEY, TAB_KEY, "/", "-", SPACE_KEY, ".", ENTER_KEY}
    };

    private static final String[][] UPPERCASE_KEYBOARD = {
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
            {SHIFT_ACTIVE_KEY, "Z", "X", "C", "V", "B", "N", "M", BACKSPACE_KEY},
            {NUMERIC_KEY, TAB_KEY, "/", "-", SPACE_KEY, ".", ENTER_KEY}
    };

    private static final String[][] NUMBER_KEYBOARD = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""},
            {SYMBOL_KEY, "_", ",", "?", "!", "`", BACKSPACE_KEY},
            {ABC_KEY, TAB_KEY, SPACE_KEY, ".", ENTER_KEY}
    };

    private static final String[][] SPECIAL_KEYBOARD = {
            {"[", "]", "{", "}", "#", "%", "^", "*", "+", "="},
            {"_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"},
            {NUMERIC_KEY, "-", ",", "?", "!", "'", BACKSPACE_KEY},
            {ABC_KEY, TAB_KEY, SPACE_KEY, ".", ENTER_KEY}
    };

    private KeyboardMode keyboardMode = KeyboardMode.LOWERCASE;

    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile OutputStream outStream;
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger connectionGeneration = new AtomicInteger();
    private volatile boolean destroyed;

    private boolean isTmuxMode = false;

    private int currentTextColor = Color.parseColor("#DDDDDD");
    private int currentBgColor = Color.TRANSPARENT;
    private boolean currentIsBold = false;
    private String ansiLeftover = "";

    private static final String PREF_NAME = "CommaFinderPrefs";
    private static final String KEY_USE_CUSTOM_KEY = "use_custom_key";
    private ActivityResultLauncher<Intent> keyPickerLauncher;
    private ActivityResultLauncher<Intent> macroExportLauncher;
    private ActivityResultLauncher<Intent> macroImportLauncher;

    private final List<MacroItem> macroList = new ArrayList<>();
    private static final String PREF_MACROS = "MacroPrefs";
    private static final String KEY_MACROS_JSON = "macros_json";

    private static class MacroItem {
        String label;
        String command;
        boolean isByte;

        MacroItem(String label, String command, boolean isByte) {
            this.label = label;
            this.command = command;
            this.isByte = isByte;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetIp = getIntent().getStringExtra("IP");
        if (targetIp == null) targetIp = "127.0.0.1";

        keyPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri == null) return;
                        try {
                            InputStream selectedInput = getContentResolver().openInputStream(uri);
                            if (selectedInput == null) return;
                            try (InputStream input = selectedInput;
                                 FileOutputStream output = openFileOutput(CUSTOM_KEY_FILE_NAME, MODE_PRIVATE)) {
                                copy(input, output);
                            }

                            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_USE_CUSTOM_KEY, true).apply();
                            Toast.makeText(this, "사용자 키 등록 완료! 다시 접속합니다.", Toast.LENGTH_SHORT).show();

                            reconnectSSH();
                        } catch (Exception e) {
                            Log.e(TAG, "Key import error", e);
                            Toast.makeText(this, "키 등록 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        macroExportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri == null) return;
                        try {
                            SharedPreferences prefs = getSharedPreferences(PREF_MACROS, Context.MODE_PRIVATE);
                            String jsonString = prefs.getString(KEY_MACROS_JSON, "[]");
                            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                                if (output == null) return;
                                output.write(jsonString.getBytes(StandardCharsets.UTF_8));
                            }
                            Toast.makeText(this, "매크로 내보내기 완료!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Macro export error", e);
                            Toast.makeText(this, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        macroImportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri == null) return;
                        try {
                            StringBuilder sb = new StringBuilder();
                            try (InputStream input = getContentResolver().openInputStream(uri)) {
                                if (input == null) return;
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = input.read(buffer)) != -1) {
                                    sb.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                                }
                            }

                            String jsonString = sb.toString();
                            Type type = new TypeToken<ArrayList<MacroItem>>(){}.getType();
                            List<MacroItem> imported = new Gson().fromJson(jsonString, type);

                            macroList.clear();
                            addValidMacros(imported);

                            saveMacros();
                            refreshMacroUI();
                            Toast.makeText(this, "매크로 가져오기 완료!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Macro import error", e);
                            Toast.makeText(this, "가져오기 실패 (잘못된 파일)", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        loadMacros();
        buildUI();
        connectSSH();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0A0A0A"));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(dp(16), dp(16) + top, dp(16), dp(16) + bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        tvStatusIcon = new TextView(this);
        tvStatusIcon.setText("🔴");
        tvStatusIcon.setTextSize(14f);
        tvStatusIcon.setPadding(0, 0, dp(8), 0);
        header.addView(tvStatusIcon);

        tvTitle = new TextView(this);
        tvTitle.setText("COMMA SSH ❯ " + targetIp);
        tvTitle.setTextColor(Color.parseColor("#00E676"));
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(tvTitle);

        btnAutoScroll = new TextView(this);
        btnAutoScroll.setText("📜스크롤:ON");
        btnAutoScroll.setTextColor(Color.parseColor("#4FC3F7"));
        btnAutoScroll.setPadding(dp(10), dp(5), dp(10), dp(5));
        btnAutoScroll.setOnClickListener(v -> {
            isAutoScroll = !isAutoScroll;
            btnAutoScroll.setText(isAutoScroll ? "📜스크롤:ON" : "📜스크롤:OFF");
            btnAutoScroll.setTextColor(Color.parseColor(isAutoScroll ? "#4FC3F7" : "#AAAAAA"));
            if (isAutoScroll) scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
        });
        header.addView(btnAutoScroll);

        TextView btnKey = new TextView(this);
        btnKey.setText("🔑 키 설정");
        btnKey.setTextColor(Color.parseColor("#4FC3F7"));
        btnKey.setPadding(dp(10), dp(5), dp(10), dp(5));
        btnKey.setOnClickListener(v -> showKeySettingDialog());
        header.addView(btnKey);

        TextView btnReconnect = new TextView(this);
        btnReconnect.setText("↻ 재접속");
        btnReconnect.setTextColor(Color.parseColor("#FFCA28"));
        btnReconnect.setPadding(dp(10), dp(5), dp(10), dp(5));
        btnReconnect.setOnClickListener(v -> reconnectSSH());
        header.addView(btnReconnect);

        TextView btnClose = new TextView(this);
        btnClose.setText("✕ 닫기");
        btnClose.setTextColor(Color.parseColor("#FF4444"));
        btnClose.setPadding(dp(10), dp(5), dp(10), dp(5));
        btnClose.setOnClickListener(v -> finish());
        header.addView(btnClose);

        root.addView(header);
        root.addView(vSpace(10));

        scrollConsole = new ScrollView(this);
        GradientDrawable bgConsole = new GradientDrawable();
        bgConsole.setColor(Color.parseColor("#111111"));
        bgConsole.setStroke(dp(1), Color.parseColor("#333333"));
        bgConsole.setCornerRadius(dp(8));
        scrollConsole.setBackground(bgConsole);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollConsole.setLayoutParams(scrollParams);
        scrollConsole.setPadding(dp(10), dp(10), dp(10), dp(10));

        tvConsole = new TextView(this);
        tvConsole.setTextColor(Color.parseColor("#DDDDDD"));
        tvConsole.setTextSize(12f);
        tvConsole.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvConsole.setText("", TextView.BufferType.EDITABLE);
        scrollConsole.addView(tvConsole);
        root.addView(scrollConsole);
        root.addView(vSpace(10));

        inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        etInput = new EditText(this);
        etInput.setHint("명령어를 입력하세요");
        etInput.setHintTextColor(Color.parseColor("#777777"));
        etInput.setTextColor(Color.parseColor("#FFFFFF"));
        etInput.setSingleLine(true);
        etInput.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ★ 안드로이드 기본 키보드가 절대 나타나지 않도록 차단
        etInput.setShowSoftInputOnFocus(false);
        // ★ 가로모드(가로형 디스플레이)에서 전체화면 입력창 추출 방지 (필수 옵션 유지)
        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(Color.parseColor("#151515"));
        etBg.setStroke(dp(1), Color.parseColor("#444444"));
        etBg.setCornerRadius(dp(8));
        etInput.setBackground(etBg);

        // ★ 입력창 클릭 시 키보드 열기(동시에 매크로 숨기기)
        etInput.setOnClickListener(v -> toggleKeyboard(true));

        etInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                etBg.setStroke(dp(2), Color.parseColor("#00E676"));
                toggleKeyboard(true);
            } else {
                etBg.setStroke(dp(1), Color.parseColor("#444444"));
            }
        });

        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        etParams.setMargins(0, 0, dp(10), 0);
        etInput.setLayoutParams(etParams);
        inputLayout.addView(etInput);

        btnToggleKb = new TextView(this);
        btnToggleKb.setText("⌨️");
        btnToggleKb.setTextColor(Color.parseColor("#AAAAAA"));
        btnToggleKb.setTextSize(20f);
        btnToggleKb.setGravity(android.view.Gravity.CENTER);
        btnToggleKb.setPadding(dp(24), 0, dp(24), 0);

        GradientDrawable kbBg = new GradientDrawable();
        kbBg.setColor(Color.parseColor("#151515"));
        kbBg.setStroke(dp(1), Color.parseColor("#444444"));
        kbBg.setCornerRadius(dp(8));
        btnToggleKb.setBackground(kbBg);

        LinearLayout.LayoutParams kbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        btnToggleKb.setLayoutParams(kbParams);

        btnToggleKb.setOnClickListener(v -> {
            boolean isKbVisible = customKeyboardLayout.getVisibility() == View.VISIBLE;
            toggleKeyboard(!isKbVisible);
        });
        inputLayout.addView(btnToggleKb);
        root.addView(inputLayout);
        root.addView(vSpace(15));

        macroHeader = new LinearLayout(this);
        macroHeader.setOrientation(LinearLayout.HORIZONTAL);
        macroHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView macroTitle = new TextView(this);
        macroTitle.setText("매크로 (길게 눌러 편집)");
        macroTitle.setTextColor(Color.parseColor("#888888"));
        macroTitle.setTextSize(11f);
        macroTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        macroHeader.addView(macroTitle);

        TextView btnExport = new TextView(this);
        btnExport.setText("↑내보내기");
        btnExport.setTextColor(Color.parseColor("#AAAAAA"));
        btnExport.setTextSize(12f);
        btnExport.setPadding(0, 0, dp(12), 0);
        btnExport.setOnClickListener(v -> exportMacros());
        macroHeader.addView(btnExport);

        TextView btnImport = new TextView(this);
        btnImport.setText("↓가져오기");
        btnImport.setTextColor(Color.parseColor("#AAAAAA"));
        btnImport.setTextSize(12f);
        btnImport.setPadding(0, 0, dp(12), 0);
        btnImport.setOnClickListener(v -> importMacros());
        macroHeader.addView(btnImport);

        TextView btnAddMacro = new TextView(this);
        btnAddMacro.setText("+ 추가");
        btnAddMacro.setTextColor(Color.parseColor("#4FC3F7"));
        btnAddMacro.setTextSize(13f);
        btnAddMacro.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnAddMacro.setOnClickListener(v -> showMacroDialog(-1));
        macroHeader.addView(btnAddMacro);

        root.addView(macroHeader);
        root.addView(vSpace(8));

        macroScroll = new HorizontalScrollView(this);
        macroScroll.setScrollbarFadingEnabled(false);
        macroContainer = new LinearLayout(this);
        macroContainer.setOrientation(LinearLayout.HORIZONTAL);
        macroScroll.addView(macroContainer);
        root.addView(macroScroll);

        customKeyboardLayout = createCustomKeyboard();
        root.addView(customKeyboardLayout);

        refreshMacroUI();
        setContentView(root);
    }

    private void toggleKeyboard(boolean show) {
        if (show) {
            customKeyboardLayout.setVisibility(View.VISIBLE);
            macroHeader.setVisibility(View.GONE);
            macroScroll.setVisibility(View.GONE);

            GradientDrawable bg = (GradientDrawable) btnToggleKb.getBackground();
            bg.setColor(Color.parseColor("#00E676"));
            btnToggleKb.setTextColor(Color.parseColor("#000000"));

            if (isAutoScroll) scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
        } else {
            customKeyboardLayout.setVisibility(View.GONE);
            macroHeader.setVisibility(View.VISIBLE);
            macroScroll.setVisibility(View.VISIBLE);

            GradientDrawable bg = (GradientDrawable) btnToggleKb.getBackground();
            bg.setColor(Color.parseColor("#151515"));
            btnToggleKb.setTextColor(Color.parseColor("#AAAAAA"));
        }
    }

    private LinearLayout createCustomKeyboard() {
        return createCustomKeyboard(false);
    }

    private LinearLayout createCustomKeyboard(boolean isMacroKeyboard) {
        LinearLayout kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setBackgroundColor(Color.parseColor("#050505"));
        kb.setPadding(0, dp(isMacroKeyboard ? 6 : 8), 0, 0);
        kb.setVisibility(isMacroKeyboard ? View.VISIBLE : View.GONE);
        kb.setTag(keyboardMode);
        renderKeyboard(kb, isMacroKeyboard);
        return kb;
    }

    private void renderKeyboard(LinearLayout keyboard, boolean isMacroKeyboard) {
        keyboard.removeAllViews();

        for (String[] row : getCurrentKeyboardLayout(keyboard)) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rowLayout.setGravity(android.view.Gravity.CENTER);

            float rowWeight = 0f;
            for (String key : row) rowWeight += getKeyWeight(key);
            float sideSpacerWeight = Math.max(0f, KEYBOARD_COLUMN_COUNT - rowWeight) / 2f;
            addKeyboardSpacer(rowLayout, sideSpacerWeight);

            for (String key : row) {
                TextView btn = new TextView(this);
                btn.setTag(key);
                btn.setText(getKeyLabel(key));
                boolean isActiveShift = SHIFT_ACTIVE_KEY.equals(key);
                boolean isEnterKey = ENTER_KEY.equals(key);
                boolean isLargeSpecialKey = BACKSPACE_KEY.equals(key)
                        || SPACE_KEY.equals(key)
                        || ENTER_KEY.equals(key)
                        || SHIFT_INACTIVE_KEY.equals(key)
                        || SHIFT_ACTIVE_KEY.equals(key);
                btn.setTextColor(isActiveShift || isEnterKey ? Color.BLACK : Color.WHITE);
                btn.setTextSize(isLargeSpecialKey ? KEYBOARD_ICON_SIZE_SP : KEYBOARD_TEXT_SIZE_SP);
                btn.setTypeface(android.graphics.Typeface.DEFAULT,
                        isLargeSpecialKey ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                btn.setContentDescription(getKeyContentDescription(key));
                btn.setGravity(android.view.Gravity.CENTER);
                btn.setIncludeFontPadding(false);
                btn.setPadding(0, 0, 0, 0);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor(isEnterKey
                        ? "#00E676"
                        : isActiveShift ? "#4FC3F7" : "#222222"));
                bg.setCornerRadius(dp(5));
                bg.setStroke(dp(1), Color.parseColor(isEnterKey ? "#69F0AE" : "#444444"));
                btn.setBackground(bg);

                float keyWeight = getKeyWeight(key);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, dp(KEYBOARD_KEY_HEIGHT_DP), keyWeight);
                params.setMargins(dp(KEYBOARD_KEY_GAP_DP / 2), dp(KEYBOARD_KEY_GAP_DP),
                        dp(KEYBOARD_KEY_GAP_DP / 2), dp(KEYBOARD_KEY_GAP_DP));
                btn.setLayoutParams(params);

                btn.setOnClickListener(v -> {
                    EditText target = isMacroKeyboard ? macroKeyboardTarget : etInput;
                    if (target == null || !target.isEnabled()) return;

                    int start = Math.max(0, target.getSelectionStart());
                    int end = Math.max(0, target.getSelectionEnd());
                    if (start > end) {
                        int temporary = start;
                        start = end;
                        end = temporary;
                    }

                    switch (key) {
                        case BACKSPACE_KEY -> {
                            if (start != end) {
                                target.getText().delete(start, end);
                            } else if (start > 0) {
                                target.getText().delete(start - 1, start);
                            }
                        }
                        case ENTER_KEY -> {
                            if (isMacroKeyboard) {
                                if (target == macroLabelInput && macroCommandInput != null) {
                                    macroCommandInput.requestFocus();
                                } else {
                                    replaceSelection(target, start, end, "\n");
                                }
                            } else {
                                sendCommand(etInput.getText().toString() + "\n");
                            }
                        }
                        case SPACE_KEY -> replaceSelection(target, start, end, " ");
                        case TAB_KEY -> {
                            if (isMacroKeyboard) {
                                replaceSelection(target, start, end, "\t");
                            } else {
                                String currentTxt = etInput.getText().toString();
                                if (!currentTxt.isEmpty()) {
                                    sendCommand(currentTxt + "\t");
                                    etInput.setText("");
                                } else {
                                    sendCommand(new byte[]{9});
                                }
                            }
                        }
                        case SHIFT_INACTIVE_KEY -> switchKeyboardMode(KeyboardMode.UPPERCASE, keyboard, isMacroKeyboard);
                        case SHIFT_ACTIVE_KEY -> switchKeyboardMode(KeyboardMode.LOWERCASE, keyboard, isMacroKeyboard);
                        case NUMERIC_KEY -> switchKeyboardMode(KeyboardMode.NUMBERS, keyboard, isMacroKeyboard);
                        case SYMBOL_KEY -> switchKeyboardMode(KeyboardMode.SPECIALS, keyboard, isMacroKeyboard);
                        case ABC_KEY -> switchKeyboardMode(KeyboardMode.LOWERCASE, keyboard, isMacroKeyboard);
                        default -> replaceSelection(target, start, end, key);
                    }
                });
                rowLayout.addView(btn);
            }
            addKeyboardSpacer(rowLayout, sideSpacerWeight);
            keyboard.addView(rowLayout);
        }
    }

    private void addKeyboardSpacer(LinearLayout row, float weight) {
        if (weight <= 0f) return;
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, weight));
        row.addView(spacer);
    }

    private void replaceSelection(EditText target, int start, int end, String value) {
        target.getText().replace(start, end, value);
    }

    private String[][] getCurrentKeyboardLayout(LinearLayout keyboard) {
        KeyboardMode mode = keyboard.getTag() instanceof KeyboardMode
                ? (KeyboardMode) keyboard.getTag()
                : KeyboardMode.LOWERCASE;
        return switch (mode) {
            case LOWERCASE -> LOWERCASE_KEYBOARD;
            case UPPERCASE -> UPPERCASE_KEYBOARD;
            case NUMBERS -> NUMBER_KEYBOARD;
            case SPECIALS -> SPECIAL_KEYBOARD;
        };
    }

    private void switchKeyboardMode(
            KeyboardMode mode, LinearLayout keyboard, boolean isMacroKeyboard) {
        keyboard.setTag(mode);
        if (!isMacroKeyboard) keyboardMode = mode;
        renderKeyboard(keyboard, isMacroKeyboard);
    }

    private String getKeyLabel(String key) {
        return switch (key) {
            case SHIFT_INACTIVE_KEY, SHIFT_ACTIVE_KEY -> "⇧";
            case BACKSPACE_KEY -> "⌫";
            case NUMERIC_KEY -> "123";
            case SYMBOL_KEY -> "#+=";
            case ABC_KEY -> "ABC";
            case TAB_KEY -> "Tab";
            case SPACE_KEY -> "␣";
            case ENTER_KEY -> "↵";
            default -> key;
        };
    }

    private String getKeyContentDescription(String key) {
        return switch (key) {
            case SHIFT_INACTIVE_KEY, SHIFT_ACTIVE_KEY -> "시프트";
            case BACKSPACE_KEY -> "백스페이스";
            case SPACE_KEY -> "스페이스";
            case ENTER_KEY -> "엔터";
            default -> getKeyLabel(key);
        };
    }

    private float getKeyWeight(String key) {
        return switch (key) {
            case SPACE_KEY -> 2.5f;
            case ENTER_KEY, SHIFT_INACTIVE_KEY, SHIFT_ACTIVE_KEY,
                    BACKSPACE_KEY, NUMERIC_KEY, SYMBOL_KEY, ABC_KEY, TAB_KEY -> 1.5f;
            default -> 1.0f;
        };
    }

    private void updateConnectionStatus(String status) {
        runOnUiThread(() -> {
            if (destroyed || tvStatusIcon == null) return;
            switch (status) {
                case "CONNECTING":
                    tvStatusIcon.setText("🟡");
                    break;
                case "CONNECTED":
                    tvStatusIcon.setText("🟢");
                    break;
                case "DISCONNECTED":
                    tvStatusIcon.setText("🔴");
                    break;
            }
        });
    }

    private void updateModeUI() {
        if (isTmuxMode) {
            tvTitle.setText("COMMA SSH ❯ TMUX 모드");
            tvTitle.setTextColor(Color.parseColor("#FFCA28"));

            if (inputLayout != null) inputLayout.setVisibility(View.GONE);
            if (customKeyboardLayout != null) customKeyboardLayout.setVisibility(View.GONE);

            if (macroHeader != null) macroHeader.setVisibility(View.VISIBLE);
            if (macroScroll != null) macroScroll.setVisibility(View.VISIBLE);
        } else {
            tvTitle.setText("COMMA SSH ❯ " + targetIp);
            tvTitle.setTextColor(Color.parseColor("#00E676"));

            if (inputLayout != null) inputLayout.setVisibility(View.VISIBLE);
            if (customKeyboardLayout != null && customKeyboardLayout.getVisibility() == View.VISIBLE) {
                if (macroHeader != null) macroHeader.setVisibility(View.GONE);
                if (macroScroll != null) macroScroll.setVisibility(View.GONE);
            } else {
                if (macroHeader != null) macroHeader.setVisibility(View.VISIBLE);
                if (macroScroll != null) macroScroll.setVisibility(View.VISIBLE);
            }
        }
        refreshMacroUI();
    }

    private void checkTmuxState(String cmd) {
        if (cmd == null) return;
        String cleanCmd = cmd.trim();

        boolean changed = false;
        if (cleanCmd.contains("tmux a") || cleanCmd.contains("tmux attach")) {
            if (!isTmuxMode) { isTmuxMode = true; changed = true; }
        } else if (cleanCmd.equals(TMUX_DETACH_COMMAND)
                || cleanCmd.equals(TMUX_CLOSE_LEGACY_COMMAND)
                || cleanCmd.equals(TMUX_CLOSE_COMMAND.trim())) {
            if (isTmuxMode) { isTmuxMode = false; changed = true; }
        }

        if (changed) {
            runOnUiThread(this::updateModeUI);
        }
    }

    private void showKeySettingDialog() {
        boolean useCustom = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_USE_CUSTOM_KEY, false);
        String currentStatus = useCustom ? "[사용자 키 사용 중]" : "[기본 키 사용 중]";

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("SSH 개인키 설정");
        builder.setMessage(currentStatus + "\n\n새로운 개인 키를 기기에서 불러오시겠습니까?");

        builder.setPositiveButton("새 키 가져오기", (dialog, which) -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            keyPickerLauncher.launch(intent);
        });

        builder.setNegativeButton("기본 키로 초기화", (dialog, which) -> {
            if (!clearDefaultKeyCache()) {
                Toast.makeText(this, "저장된 기본 키를 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_USE_CUSTOM_KEY, false).apply();
            Toast.makeText(this, "APK에 내장된 기본 키를 복원합니다.", Toast.LENGTH_SHORT).show();
            reconnectSSH();
        });

        builder.setNeutralButton("취소", null);
        builder.show();
    }

    private void reconnectSSH() {
        updateConnectionStatus("DISCONNECTED");
        disconnectCurrentConnection();

        tvConsole.setText("");
        resetAnsiState();
        ansiLeftover = "";
        isTmuxMode = false;
        runOnUiThread(this::updateModeUI);

        connectSSH();
    }

    private void loadMacros() {
        SharedPreferences prefs = getSharedPreferences(PREF_MACROS, Context.MODE_PRIVATE);
        String jsonString = prefs.getString(KEY_MACROS_JSON, null);
        macroList.clear();

        if (jsonString != null) {
            try {
                Type type = new TypeToken<ArrayList<MacroItem>>(){}.getType();
                List<MacroItem> loaded = new Gson().fromJson(jsonString, type);
                addValidMacros(loaded);
            } catch (Exception e) {
                Log.e(TAG, "Gson Parse Error", e);
            }
        }

        boolean changed = false;

        boolean hasTmux = false;
        for (MacroItem item : macroList) {
            if ("tmux a".equalsIgnoreCase(item.label)) {
                hasTmux = true;
                if (!item.command.contains("status off")) {
                    item.command = "tmux set status off\ntmux a\n";
                    changed = true;
                }
                break;
            }
        }
        if (!hasTmux) {
            macroList.add(0, new MacroItem("tmux a", "tmux set status off\ntmux a\n", false));
            changed = true;
        }

        boolean hasDetach = false;
        for (MacroItem item : macroList) {
            if (TMUX_DETACH_COMMAND.equals(item.command)) {
                hasDetach = true;
                if (!"세션 분리".equals(item.label)) {
                    item.label = "세션 분리";
                    changed = true;
                }
                break;
            }
        }
        if (!hasDetach) {
            macroList.add(1, new MacroItem("세션 분리", TMUX_DETACH_COMMAND, false));
            changed = true;
        }

        boolean hasClose = false;
        for (MacroItem item : macroList) {
            if (TMUX_CLOSE_LEGACY_COMMAND.equals(item.command)
                    || TMUX_CLOSE_COMMAND.equals(item.command)) {
                hasClose = true;
                if (!TMUX_CLOSE_COMMAND.equals(item.command)) {
                    item.command = TMUX_CLOSE_COMMAND;
                    changed = true;
                }
                if (!"세션 종료".equals(item.label)) {
                    item.label = "세션 종료";
                    changed = true;
                }
                break;
            }
        }
        if (!hasClose) {
            macroList.add(2, new MacroItem("세션 종료", TMUX_CLOSE_COMMAND, false));
            changed = true;
        }

        boolean hasCtrlC = false;
        for (MacroItem item : macroList) {
            if (item.isByte && "3".equals(item.command)) {
                hasCtrlC = true;
                break;
            }
        }
        if (!hasCtrlC) {
            macroList.add(new MacroItem("Ctrl+C", "3", true));
            changed = true;
        }

        boolean hasReboot = false;
        for (MacroItem item : macroList) {
            if ("sudo reboot\n".equals(item.command) || "sudo reboot".equals(item.command)) {
                hasReboot = true;
                if (!"reboot".equals(item.label)) {
                    item.label = "reboot";
                    changed = true;
                }
                break;
            }
        }
        if (!hasReboot) {
            macroList.add(new MacroItem("reboot", "sudo reboot\n", false));
            changed = true;
        }

        boolean hasClear = false;
        for (MacroItem item : macroList) {
            if ("clear\n".equals(item.command) || "clear".equals(item.command)) {
                hasClear = true;
                if (!"clear".equals(item.label)) {
                    item.label = "clear";
                    changed = true;
                }
                break;
            }
        }
        if (!hasClear) {
            macroList.add(new MacroItem("clear", "clear\n", false));
            changed = true;
        }

        if (changed) saveMacros();
    }

    private void addValidMacros(List<MacroItem> items) {
        if (items == null) return;
        for (MacroItem item : items) {
            if (macroList.size() >= MAX_MACRO_COUNT) break;
            if (item == null || item.label == null || item.command == null) continue;
            item.label = item.label.trim();
            if (item.label.isEmpty()
                    || item.label.length() > MAX_MACRO_LABEL_LENGTH
                    || item.command.length() > MAX_MACRO_COMMAND_LENGTH) {
                continue;
            }
            if (item.isByte) {
                try {
                    Byte.parseByte(item.command);
                } catch (NumberFormatException invalidByte) {
                    continue;
                }
            }
            macroList.add(item);
        }
    }

    private void saveMacros() {
        try {
            String jsonString = new Gson().toJson(macroList);
            SharedPreferences prefs = getSharedPreferences(PREF_MACROS, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_MACROS_JSON, jsonString).apply();
        } catch (Exception e) {
            Log.e(TAG, "Gson Create Error", e);
        }
    }

    private void exportMacros() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "comma_macros.json");
        macroExportLauncher.launch(intent);
    }

    private void importMacros() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        macroImportLauncher.launch(intent);
    }

    private void refreshMacroUI() {
        macroContainer.removeAllViews();
        for (int i = 0; i < macroList.size(); i++) {
            MacroItem item = macroList.get(i);

            boolean isTmuxSpecialMacro = isTmuxControlCommand(item.command);

            if (isTmuxMode) {
                if (!isTmuxSpecialMacro) continue;
            } else {
                if (isTmuxSpecialMacro) continue;
            }

            TextView btn = makeDynamicMacroBtn(item, i);
            macroContainer.addView(btn);
            macroContainer.addView(hSpace(8));
        }
    }

    private TextView makeDynamicMacroBtn(MacroItem item, int position) {
        TextView btn = new TextView(this);
        btn.setText(item.label);
        btn.setTextColor(Color.parseColor("#DDDDDD"));
        btn.setTextSize(13f);
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#2A2A2A"));

        if (isTmuxMode) {
            bg.setStroke(dp(1), Color.parseColor("#806600"));
        } else {
            bg.setStroke(dp(1), Color.parseColor("#444444"));
        }

        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);

        btn.setOnClickListener(v -> {
            if (item.isByte) {
                try {
                    byte b = Byte.parseByte(item.command);
                    sendCommand(new byte[]{b});
                } catch (Exception ignored) {}
            } else {
                sendCommand(item.command);
            }
        });

        btn.setOnLongClickListener(v -> {
            showMacroDialog(position);
            return true;
        });

        return btn;
    }

    private AlertDialog.Builder createMacroDialogBuilder(int position, boolean isEdit, EditText etLabel, EditText etCommand, LinearLayout layout) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(6), dp(16), dp(8));

        etLabel.setHint("버튼 이름 (예: Reboot)");
        etLabel.setTextColor(Color.WHITE);
        etLabel.setSingleLine(true);
        configureForBuiltInKeyboard(etLabel);
        layout.addView(etLabel);

        etCommand.setHint("명령어 (예: sudo reboot)");
        etCommand.setTextColor(Color.WHITE);
        etCommand.setSingleLine(false);
        etCommand.setMaxLines(3);
        configureForBuiltInKeyboard(etCommand);
        layout.addView(etCommand);

        View.OnFocusChangeListener macroFocusListener = (view, hasFocus) -> {
            if (hasFocus) macroKeyboardTarget = (EditText) view;
        };
        etLabel.setOnFocusChangeListener(macroFocusListener);
        etCommand.setOnFocusChangeListener(macroFocusListener);

        if (isEdit) {
            MacroItem item = macroList.get(position);
            etLabel.setText(item.label);
            String displayCmd = item.command.endsWith("\n")
                    ? item.command.substring(0, item.command.length() - 1)
                    : item.command;
            if (item.isByte && item.command.equals("3")) displayCmd = "Ctrl+C (특수)";
            etCommand.setText(displayCmd);
            if (item.isByte) etCommand.setEnabled(false);
        }

        builder.setView(layout);
        return builder;
    }

    private void configureForBuiltInKeyboard(EditText input) {
        input.setShowSoftInputOnFocus(false);
        input.setImeOptions(input.getImeOptions()
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    }

    private void hideSystemKeyboard(View view) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private LinearLayout createOrderLayout(final int position, final AlertDialog dialog) {
        LinearLayout orderLayout = new LinearLayout(this);
        orderLayout.setOrientation(LinearLayout.HORIZONTAL);
        orderLayout.setPadding(0, dp(15), 0, dp(5));

        if (position > 0) {
            TextView btnUp = new TextView(this);
            btnUp.setText("▲ 순서 앞으로");
            btnUp.setTextColor(Color.parseColor("#4FC3F7"));
            btnUp.setPadding(0, dp(10), dp(20), dp(10));
            btnUp.setOnClickListener(v -> {
                Collections.swap(macroList, position, position - 1);
                saveMacros();
                refreshMacroUI();
                dialog.dismiss();
            });
            orderLayout.addView(btnUp);
        }

        if (position < macroList.size() - 1) {
            TextView btnDown = new TextView(this);
            btnDown.setText("▼ 순서 뒤로");
            btnDown.setTextColor(Color.parseColor("#4FC3F7"));
            btnDown.setPadding(0, dp(10), dp(20), dp(10));
            btnDown.setOnClickListener(v -> {
                Collections.swap(macroList, position, position + 1);
                saveMacros();
                refreshMacroUI();
                dialog.dismiss();
            });
            orderLayout.addView(btnDown);
        }
        return orderLayout;
    }

    private TextView createMacroDialogAction(String text, int color) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(color);
        action.setTextSize(14f);
        action.setGravity(android.view.Gravity.CENTER);
        action.setPadding(dp(10), dp(8), dp(10), dp(8));
        return action;
    }

    private void showMacroDialog(final int position) {
        boolean isEdit = (position >= 0);
        LinearLayout layout = new LinearLayout(this);
        final EditText etLabel = new EditText(this);
        final EditText etCommand = new EditText(this);

        macroLabelInput = etLabel;
        macroCommandInput = etCommand;
        AlertDialog.Builder builder = createMacroDialogBuilder(position, isEdit, etLabel, etCommand, layout);
        macroKeyboardTarget = etLabel;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(isEdit ? "명령어 편집" : "새 명령어 추가");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView deleteAction = null;
        if (isEdit) {
            deleteAction = createMacroDialogAction("삭제", Color.parseColor("#FF8A80"));
            header.addView(deleteAction);
        }

        TextView cancelAction = createMacroDialogAction("취소", Color.parseColor("#BDBDBD"));
        TextView saveAction = createMacroDialogAction("저장", Color.parseColor("#4FC3F7"));
        header.addView(cancelAction);
        header.addView(saveAction);
        layout.addView(header, 0);

        layout.addView(createCustomKeyboard(true));
        final AlertDialog dialog = builder.create();

        cancelAction.setOnClickListener(v -> dialog.cancel());

        if (deleteAction != null) {
            deleteAction.setOnClickListener(v -> {
                macroList.remove(position);
                saveMacros();
                refreshMacroUI();
                dialog.dismiss();
            });
        }

        saveAction.setOnClickListener(v -> {
            String label = etLabel.getText().toString().trim();
            String cmd = etCommand.getText().toString().trim();

            if (label.isEmpty() || cmd.isEmpty() && (!isEdit || !macroList.get(position).isByte)) {
                Toast.makeText(this, "이름과 명령어를 모두 입력하세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isEdit || !macroList.get(position).isByte) {
                if (!cmd.equals("`x") && !cmd.equals("`d") && !cmd.endsWith("\n")) {
                    cmd += "\n";
                }
            } else {
                cmd = macroList.get(position).command;
            }

            if (isEdit) {
                macroList.get(position).label = label;
                macroList.get(position).command = cmd;
            } else {
                macroList.add(new MacroItem(label, cmd, false));
            }

            saveMacros();
            refreshMacroUI();
            dialog.dismiss();
        });

        if (isEdit) {
            layout.addView(createOrderLayout(position, dialog));
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        etLabel.requestFocus();
        etLabel.setSelection(etLabel.getText().length());
        hideSystemKeyboard(etLabel);
        dialog.setOnDismissListener(ignored -> {
            if (macroKeyboardTarget == etLabel || macroKeyboardTarget == etCommand) {
                macroKeyboardTarget = null;
            }
            if (macroLabelInput == etLabel) macroLabelInput = null;
            if (macroCommandInput == etCommand) macroCommandInput = null;
        });
    }

    private void connectSSH() {
        final int generation = connectionGeneration.incrementAndGet();
        updateConnectionStatus("CONNECTING");
        printConsole("SSH 키 준비 중...\n");
        connectionExecutor.execute(() -> runSshConnection(generation));
    }

    private void runSshConnection(int generation) {
        Session newSession = null;
        ChannelShell newChannel = null;
        try {
            if (!isConnectionActive(generation)) return;
            File customKeyFile = new File(getFilesDir(), CUSTOM_KEY_FILE_NAME);
            File defaultKeyFile = new File(getFilesDir(), DEFAULT_KEY_FILE_NAME);
            File targetKeyFile;

            boolean useCustomKey = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_USE_CUSTOM_KEY, false);

            if (useCustomKey && customKeyFile.exists()) {
                printConsole("사용자 개인 키를 불러옵니다.\n");
                targetKeyFile = customKeyFile;
                validateKeyForSsh(targetKeyFile);
            } else {
                if (!prepareDefaultKey(defaultKeyFile, generation)) return;
                targetKeyFile = defaultKeyFile;
            }

            if (!isConnectionActive(generation)) return;

            JSch jsch = new JSch();
            jsch.addIdentity(targetKeyFile.getAbsolutePath());

            printConsole("Connecting to " + targetIp + "...\n");

            newSession = jsch.getSession("comma", targetIp, 22);
            newSession.setConfig("StrictHostKeyChecking", "no");
            newSession.setServerAliveInterval(5000);
            newSession.setServerAliveCountMax(3);
            if (!assignSession(generation, newSession)) return;
            newSession.connect(10000);

            newChannel = (ChannelShell) newSession.openChannel("shell");
            newChannel.setPtyType("xterm-256color");

            InputStream input = newChannel.getInputStream();
            OutputStream output = newChannel.getOutputStream();
            if (!assignChannel(generation, newChannel)) return;
            newChannel.connect(10000);
            if (!assignOutput(generation, newChannel, output)) return;

            updateConnectionStatus("CONNECTED");
            printConsole("\n✅ 접속 성공! 쉘(Shell) 터미널을 시작합니다.\n\n");

            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int length;
                while (isConnectionActive(generation)
                        && (length = reader.read(buffer)) != -1) {
                    printConsole(new String(buffer, 0, length));
                }
            }

            if (isConnectionActive(generation)) {
                updateConnectionStatus("DISCONNECTED");
                printConsole("\n\u001B[31m[서버와의 연결이 종료되었습니다.]\u001B[0m\n");
            }
        } catch (Exception e) {
            if (isConnectionActive(generation)) {
                updateConnectionStatus("DISCONNECTED");
                printConsole("""
                        
                        \u001B[31m❌ [접속 실패 / 끊김]\u001B[0m
                        """ + e.getMessage() + """
                        
                        
                        우측 상단의 [🔑 키 설정] 또는 네트워크 상태를 확인해주세요.
                        """);
            }
        } finally {
            if (newChannel != null) newChannel.disconnect();
            if (newSession != null) newSession.disconnect();
            clearConnectionReferences(newSession, newChannel);
        }
    }

    private boolean isTmuxControlCommand(String command) {
        return TMUX_DETACH_COMMAND.equals(command)
                || TMUX_CLOSE_LEGACY_COMMAND.equals(command)
                || TMUX_CLOSE_COMMAND.equals(command);
    }

    private synchronized boolean assignSession(int generation, Session newSession) {
        if (!isConnectionActive(generation)) return false;
        session = newSession;
        return true;
    }

    private synchronized boolean assignChannel(int generation, ChannelShell newChannel) {
        if (!isConnectionActive(generation)) return false;
        channel = newChannel;
        return true;
    }

    private synchronized boolean assignOutput(
            int generation, ChannelShell expectedChannel, OutputStream output) {
        if (!isConnectionActive(generation) || channel != expectedChannel) return false;
        outStream = output;
        return true;
    }

    private synchronized void clearConnectionReferences(
            Session oldSession, ChannelShell oldChannel) {
        if (channel == oldChannel) {
            channel = null;
            outStream = null;
        }
        if (session == oldSession) session = null;
    }

    private boolean isConnectionActive(int generation) {
        return !destroyed && connectionGeneration.get() == generation;
    }

    private synchronized void disconnectCurrentConnection() {
        connectionGeneration.incrementAndGet();
        outStream = null;
        ChannelShell oldChannel = channel;
        Session oldSession = session;
        channel = null;
        session = null;
        if (oldChannel != null) oldChannel.disconnect();
        if (oldSession != null) oldSession.disconnect();
    }

    private boolean prepareDefaultKey(File defaultKeyFile, int generation) throws Exception {
        if (defaultKeyFile.isFile()) {
            try {
                validateKeyForSsh(defaultKeyFile);
                printConsole("저장된 기본 키를 사용합니다.\n");
                return true;
            } catch (Exception invalidCache) {
                Log.w(TAG, "Cached default key is invalid; restoring the embedded key", invalidCache);
                printConsole("저장된 기본 키가 손상되어 APK 내장 키로 복원합니다.\n");
            }
        } else {
            printConsole("저장된 기본 키가 없어 APK 내장 키를 준비합니다.\n");
        }

        if (!isConnectionActive(generation)) return false;

        File temporaryKeyFile = new File(getFilesDir(), DEFAULT_KEY_STAGING_FILE_NAME);
        if (temporaryKeyFile.exists() && !temporaryKeyFile.delete()) {
            throw new Exception("이전 기본 키 임시 파일을 정리할 수 없습니다.");
        }

        try {
            printConsole("APK 내장 기본 키 준비 중...\n");
            try (InputStream input = getAssets().open(DEFAULT_KEY_ASSET_NAME);
                 FileOutputStream output = new FileOutputStream(temporaryKeyFile)) {
                copy(input, output);
            }
            if (!isConnectionActive(generation)) return false;
            validateKeyForSsh(temporaryKeyFile);

            if (defaultKeyFile.exists() && !defaultKeyFile.delete()) {
                throw new Exception("기존 기본 키를 교체할 수 없습니다.");
            }
            if (!temporaryKeyFile.renameTo(defaultKeyFile)) {
                throw new Exception("APK 내장 기본 키를 저장할 수 없습니다.");
            }
            printConsole("APK 내장 기본 키 저장 완료.\n");
            return true;
        } finally {
            if (temporaryKeyFile.exists() && !temporaryKeyFile.delete()) {
                Log.w(TAG, "Could not delete temporary default key file");
            }
        }
    }

    private boolean clearDefaultKeyCache() {
        File defaultKeyFile = new File(getFilesDir(), DEFAULT_KEY_FILE_NAME);
        File stagingFile = new File(getFilesDir(), DEFAULT_KEY_STAGING_FILE_NAME);
        boolean defaultDeleted = !defaultKeyFile.exists() || defaultKeyFile.delete();
        boolean stagingDeleted = !stagingFile.exists() || stagingFile.delete();
        return defaultDeleted && stagingDeleted;
    }

    private void validateKeyForSsh(File keyFile) throws Exception {
        readAndValidateKey(keyFile);
        JSch validator = new JSch();
        validator.addIdentity(keyFile.getAbsolutePath());
        validator.removeAllIdentity();
    }

    private void readAndValidateKey(File targetKeyFile) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(targetKeyFile)) {
            copy(input, bytes);
        }
        if (bytes.size() == 0) throw new Exception("키 파일을 읽을 수 없습니다.");

        String keyContent = bytes.toString(StandardCharsets.UTF_8.name());

        if (keyContent.contains("PuTTY-User-Key-File")) {
            throw new Exception("""
                    ❌ 선택된 파일은 PuTTY(.ppk) 전용 파일입니다.
                    안드로이드 앱은 OpenSSH 형식(PEM)만 지원합니다.

                    PC의 PuTTYgen을 사용하여 [Conversions] -> [Export OpenSSH key]
                    로 변환한 파일을 서버에 업로드하거나 앱에 등록해주세요!""");
        }
        if (keyContent.trim().startsWith("<")
                || keyContent.toLowerCase(Locale.ROOT).contains("<!doctype html>")) {
            throw new Exception("""
                    ❌ 기본 키 대신 웹페이지(HTML) 내용이 발견되었습니다.
                    APK에 id_rsa.pem 파일이 올바르게 포함되었는지 확인해주세요.""");
        }

        if (!keyContent.endsWith("\n")) {
            keyContent += "\n";
            try (FileOutputStream fos = new FileOutputStream(targetKeyFile)) {
                fos.write(keyContent.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void sendCommand(String cmd) {
        OutputStream stream = outStream;
        int generation = connectionGeneration.get();
        if (stream != null && isConnectionActive(generation)) {
            checkTmuxState(cmd);
            commandExecutor.execute(() -> {
                try {
                    if (!isConnectionActive(generation) || outStream != stream) return;
                    stream.write(cmd.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                    runOnUiThread(() -> {
                        if (!destroyed) etInput.setText("");
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Send command error", e);
                    if (isConnectionActive(generation)) updateConnectionStatus("DISCONNECTED");
                }
            });
        } else {
            updateConnectionStatus("DISCONNECTED");
        }
    }

    private void sendCommand(byte[] cmd) {
        OutputStream stream = outStream;
        int generation = connectionGeneration.get();
        if (stream != null && isConnectionActive(generation)) {
            commandExecutor.execute(() -> {
                try {
                    if (!isConnectionActive(generation) || outStream != stream) return;
                    stream.write(cmd);
                    stream.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Send byte command error", e);
                    if (isConnectionActive(generation)) updateConnectionStatus("DISCONNECTED");
                }
            });
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        output.flush();
    }

    private void resetAnsiState() {
        currentTextColor = Color.parseColor("#DDDDDD");
        currentBgColor = Color.TRANSPARENT;
        currentIsBold = false;
    }

    private void applyAnsiCode(int code) {
        switch (code) {
            case 0: resetAnsiState(); break;
            case 1: currentIsBold = true; break;
            case 22: currentIsBold = false; break;

            case 30: currentTextColor = Color.parseColor("#808080"); break;
            case 31: case 91: currentTextColor = Color.parseColor("#FF5555"); break;
            case 32: case 92: currentTextColor = Color.parseColor("#55FF55"); break;
            case 33: case 93: currentTextColor = Color.parseColor("#FFFF55"); break;
            case 34: case 94: currentTextColor = Color.parseColor("#55AAFF"); break;
            case 35: case 95: currentTextColor = Color.parseColor("#FF55FF"); break;
            case 36: case 96: currentTextColor = Color.parseColor("#55FFFF"); break;
            case 37: case 97: currentTextColor = Color.parseColor("#FFFFFF"); break;
            case 39: currentTextColor = Color.parseColor("#DDDDDD"); break;

            case 90: currentTextColor = Color.parseColor("#555555"); break;

            case 40: currentBgColor = Color.parseColor("#000000"); break;
            case 41: currentBgColor = Color.parseColor("#AA0000"); break;
            case 42: currentBgColor = Color.parseColor("#00AA00"); break;
            case 43: currentBgColor = Color.parseColor("#AAAA00"); break;
            case 44: currentBgColor = Color.parseColor("#0000AA"); break;
            case 45: currentBgColor = Color.parseColor("#AA00AA"); break;
            case 46: currentBgColor = Color.parseColor("#00AAAA"); break;
            case 47: currentBgColor = Color.parseColor("#AAAAAA"); break;
            case 49: currentBgColor = Color.TRANSPARENT; break;
        }
    }

    private int get256Color(int index) {
        if (index >= 0 && index <= 15) {
            int[] std = {
                    0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
                    0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
            };
            return 0xFF000000 | std[index];
        } else if (index >= 16 && index <= 231) {
            index -= 16;
            int r = (index / 36) > 0 ? 55 + (index / 36) * 40 : 0;
            int g = ((index % 36) / 6) > 0 ? 55 + ((index % 36) / 6) * 40 : 0;
            int b = (index % 6) > 0 ? 55 + (index % 6) * 40 : 0;
            return Color.rgb(r, g, b);
        } else if (index >= 232 && index <= 255) {
            int gray = 8 + (index - 232) * 10;
            return Color.rgb(gray, gray, gray);
        }
        return Color.parseColor("#DDDDDD");
    }

    private void appendSpannableChunk(SpannableStringBuilder ssb, String text) {
        if (text.isEmpty()) return;
        int start = ssb.length();
        ssb.append(text);

        ssb.setSpan(new ForegroundColorSpan(currentTextColor), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (currentBgColor != Color.TRANSPARENT) {
            ssb.setSpan(new BackgroundColorSpan(currentBgColor), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (currentIsBold) {
            ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void printConsole(String text) {
        String combined = ansiLeftover + text;
        ansiLeftover = "";

        combined = combined.replace("\r\n", "\n");

        if (combined.endsWith("\r")) {
            ansiLeftover = "\r";
            combined = combined.substring(0, combined.length() - 1);
        }

        int lastEsc = combined.lastIndexOf('\u001B');
        if (lastEsc >= 0) {
            String afterEsc = combined.substring(lastEsc);
            boolean isComplete = false;

            if (afterEsc.length() > 1) {
                char type = afterEsc.charAt(1);
                if (type == '[') {
                    for (int i = 2; i < afterEsc.length(); i++) {
                        if (Character.isLetter(afterEsc.charAt(i))) {
                            isComplete = true; break;
                        }
                    }
                } else if (type == ']') {
                    if (afterEsc.contains("\u0007") || afterEsc.contains("\u001B\\")) {
                        isComplete = true;
                    }
                } else if (type == '(' || type == ')') {
                    if (afterEsc.length() >= 3) isComplete = true;
                } else {
                    isComplete = true;
                }
            }

            if (!isComplete) {
                ansiLeftover = afterEsc + ansiLeftover;
                combined = combined.substring(0, lastEsc);
            }
        }

        if (combined.isEmpty()) return;

        String preFiltered = OSC_PATTERN.matcher(combined).replaceAll("");
        preFiltered = CHARSET_PATTERN.matcher(preFiltered).replaceAll("");
        preFiltered = SINGLE_ESCAPE_PATTERN.matcher(preFiltered).replaceAll("");

        final String[] carriageParts = preFiltered.split("\r", -1);

        runOnUiThread(() -> {
            android.text.Editable editable = tvConsole.getEditableText();
            if (editable == null) return;

            for (int p = 0; p < carriageParts.length; p++) {
                if (p > 0) {
                    int lastNewline = android.text.TextUtils.lastIndexOf(editable, '\n');
                    if (lastNewline != -1) {
                        editable.delete(lastNewline + 1, editable.length());
                    } else {
                        editable.clear();
                    }
                }

                String part = carriageParts[p];
                if (part.isEmpty()) continue;

                Matcher matcher = CSI_PATTERN.matcher(part);
                int lastEnd = 0;
                SpannableStringBuilder ssb = new SpannableStringBuilder();

                while (matcher.find()) {
                    if (matcher.start() > lastEnd) {
                        appendSpannableChunk(ssb, part.substring(lastEnd, matcher.start()));
                    }

                    String codes = matcher.group(1);
                    String type = matcher.group(2);

                    if ("m".equals(type)) {
                        if (codes == null || codes.isEmpty()) {
                            resetAnsiState();
                        } else {
                            String[] parts = codes.split(";");
                            for (int i = 0; i < parts.length; i++) {
                                String colorCode = parts[i];
                                if (colorCode.isEmpty()) continue;
                                try {
                                    int code = Integer.parseInt(colorCode);
                                    if (code == 38 && i + 2 < parts.length && "5".equals(parts[i+1])) {
                                        currentTextColor = get256Color(Integer.parseInt(parts[i+2]));
                                        i += 2;
                                    } else if (code == 48 && i + 2 < parts.length && "5".equals(parts[i+1])) {
                                        currentBgColor = get256Color(Integer.parseInt(parts[i+2]));
                                        i += 2;
                                    } else {
                                        applyAnsiCode(code);
                                    }
                                }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    } else if ("J".equals(type)) {
                        if ("2".equals(codes) || "3".equals(codes)) {
                            editable.clear();
                            ssb.clear();
                        }
                    }
                    lastEnd = matcher.end();
                }

                if (lastEnd < part.length()) {
                    appendSpannableChunk(ssb, part.substring(lastEnd));
                }

                editable.append(ssb);
                trimConsole(editable);
            }

            if (isAutoScroll) {
                scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void trimConsole(android.text.Editable editable) {
        if (editable.length() <= MAX_CONSOLE_CHARS) return;
        int targetStart = editable.length() - MAX_CONSOLE_CHARS;
        int nextNewline = android.text.TextUtils.indexOf(editable, '\n', targetStart);
        editable.delete(0, nextNewline >= 0 ? nextNewline + 1 : targetStart);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        disconnectCurrentConnection();
        connectionExecutor.shutdownNow();
        commandExecutor.shutdownNow();
        super.onDestroy();
    }

    private View vSpace(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal)));
        return v;
    }

    @SuppressWarnings("SameParameterValue")
    private View hSpace(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dpVal), LinearLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private int dp(int dpVal) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpVal, getResources().getDisplayMetrics());
    }
}
