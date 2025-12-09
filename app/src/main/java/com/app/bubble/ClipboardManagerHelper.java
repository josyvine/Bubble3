package com.app.bubble;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages clipboard history for the custom keyboard.
 * Stores the last 10 copied items.
 * UPDATED: Learns vocabulary from copied text.
 */
public class ClipboardManagerHelper {

    private static ClipboardManagerHelper instance;
    private ClipboardManager systemClipboard;
    private SharedPreferences prefs;
    private List<String> clipHistory;
    private Context mContext; // Stored to access PredictionEngine
    
    private static final String PREFS_NAME = "BubbleClipboardPrefs";
    private static final String KEY_HISTORY = "ClipHistoryString";
    private static final int MAX_HISTORY_SIZE = 10;
    private static final String DELIMITER = "#####"; // Simple delimiter for saving list

    private ClipboardManagerHelper(Context context) {
        this.mContext = context;
        systemClipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        clipHistory = new ArrayList<>();
        
        loadHistory();
        syncWithSystemClipboard();
    }

    public static synchronized ClipboardManagerHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ClipboardManagerHelper(context);
        }
        return instance;
    }

    /**
     * Checks if the system clipboard has new text and adds it to our history.
     */
    public void syncWithSystemClipboard() {
        if (systemClipboard != null && systemClipboard.hasPrimaryClip()) {
            ClipData.Item item = systemClipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null) {
                addClip(item.getText().toString());
            }
        }
    }

    /**
     * Adds a text to the history (Top of the list).
     * Removes duplicates and keeps size limited.
     * NEW: Feeds the words to PredictionEngine to learn them.
     */
    public void addClip(String text) {
        if (text == null || text.trim().isEmpty()) return;

        // 1. Manage History List
        if (clipHistory.contains(text)) {
            clipHistory.remove(text);
        }
        clipHistory.add(0, text);

        if (clipHistory.size() > MAX_HISTORY_SIZE) {
            clipHistory.remove(clipHistory.size() - 1);
        }

        saveHistory();

        // 2. FIX Issue #3: Learn Vocabulary from Clipboard
        // Split sentence into words by whitespace
        String[] words = text.split("\\s+");
        PredictionEngine engine = PredictionEngine.getInstance(mContext);
        
        for (String word : words) {
            // Only learn valid words length > 1 to avoid garbage
            if (word.length() > 1) {
                // Remove punctuation like "hello." -> "hello"
                String cleanWord = word.replaceAll("[^a-zA-Z0-9]", "");
                if (!cleanWord.isEmpty()) {
                    engine.learnWord(cleanWord);
                }
            }
        }
    }

    public List<String> getHistory() {
        syncWithSystemClipboard(); // Ensure we have the very latest
        return new ArrayList<>(clipHistory);
    }

    public void clearHistory() {
        clipHistory.clear();
        saveHistory();
    }

    // --- Persistence Logic ---

    private void saveHistory() {
        String joined = TextUtils.join(DELIMITER, clipHistory);
        prefs.edit().putString(KEY_HISTORY, joined).apply();
    }

    private void loadHistory() {
        String saved = prefs.getString(KEY_HISTORY, "");
        if (!saved.isEmpty()) {
            String[] items = saved.split(DELIMITER);
            clipHistory.clear();
            clipHistory.addAll(Arrays.asList(items));
        }
    }
}