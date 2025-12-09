package com.app.bubble;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles "Type Memory" and Dictionary Suggestions.
 * Saves new words used by the user and retrieves them based on typed prefix.
 */
public class PredictionEngine {

    private static PredictionEngine instance;
    private SharedPreferences prefs;
    private Set<String> userDictionary;
    private static final String PREFS_NAME = "BubbleDict";
    private static final String KEY_WORDS = "UserWords";

    // A small hardcoded base dictionary for immediate results
    private final String[] BASE_DICT = {
        "the", "and", "that", "have", "for", "not", "with", "you", "this", "but", "his", "from",
        "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would",
        "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which",
        "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know",
        "take", "people", "into", "year", "your", "good", "some", "could", "them", "see",
        "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
        "also", "back", "after", "use", "two", "how", "our", "work", "first", "well",
        "way", "even", "new", "want", "because", "any", "these", "give", "day", "most",
        "apple", "application", "app", "bubble", "keyboard", "translate"
    };

    private PredictionEngine(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        userDictionary = new HashSet<>();
        
        // Load saved words
        Set<String> saved = prefs.getStringSet(KEY_WORDS, new HashSet<String>());
        if (saved != null) {
            userDictionary.addAll(saved);
        }
        
        // Add base dict
        Collections.addAll(userDictionary, BASE_DICT);
    }

    public static synchronized PredictionEngine getInstance(Context context) {
        if (instance == null) {
            instance = new PredictionEngine(context);
        }
        return instance;
    }

    /**
     * Returns a list of suggestions that start with the given prefix.
     */
    public List<String> getSuggestions(String prefix) {
        List<String> results = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return results;

        String check = prefix.toLowerCase();

        for (String word : userDictionary) {
            if (word.toLowerCase().startsWith(check)) {
                // Don't suggest the exact same thing (optional, but good for UI)
                if (!word.equalsIgnoreCase(check)) {
                    results.add(word);
                }
            }
        }

        // Sort by length (shortest first) or usage frequency (simple sort here)
        Collections.sort(results);

        // Limit to 3-5 suggestions
        if (results.size() > 5) {
            return results.subList(0, 5);
        }
        return results;
    }

    /**
     * Learns a new word when the user types Space/Enter.
     */
    public void learnWord(String word) {
        if (word == null || word.trim().length() < 2) return;
        
        String cleanWord = word.trim();
        
        if (!userDictionary.contains(cleanWord)) {
            userDictionary.add(cleanWord);
            
            // Save to Prefs
            SharedPreferences.Editor editor = prefs.edit();
            editor.putStringSet(KEY_WORDS, userDictionary);
            editor.apply();
        }
    }
}