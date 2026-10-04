package com.simog.clipmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the text pieces visible on a Facebook comments screen into comments.
 * Pure geometry + wording, no Android types, so it can be tuned and tested off-device.
 *
 * Layout assumed (Facebook app): each comment is a bubble [name, text...] followed by an action
 * row [time, "Mi piace", "Rispondi"]. Replies have the same shape but are indented to the right.
 * So every "Rispondi" marks the end of one comment, and its bubble is the text above it, down
 * from the previous action row, aligned with the action row's left edge.
 */
public final class FbParser {

    public static final class Item {
        public final String s;
        public final int l, t, r, b;

        public Item(String s, int l, int t, int r, int b) {
            this.s = s;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }
    }

    public static final class Comment {
        public String name;
        public String text;
        /** Left edge of the comment's action row: bigger for replies (indented). */
        public int rowLeft;
    }

    private FbParser() {
    }

    private static final Pattern ACTION = Pattern.compile("(?i)^(rispondi|reply)$");

    private static final Pattern JUNK = Pattern.compile("(?i)^("
            // action row / reactions
            + "mi piace|like|non mi piace più|unlike|rispondi|reply|condividi|share|commenta|comment"
            + "|modificato|edited|autore|author|fan più attivo|top fan|segui|follow|segui già"
            + "|traduci|vedi traduzione|visualizza traduzione|see translation|nascondi|hide"
            + "|altro|see more|mostra altro|più pertinenti|most relevant|tutti i commenti|all comments"
            + "|adesso|ora|just now|ieri|yesterday|in evidenza|pinned"
            // relative times: "2 h", "3 g", "1 sett", "5 min", "2 anni", "10w"
            + "|\\d+\\s*(s|sec|secondi|m|min|minuti|h|ora|ore|hr|hrs|g|gg|giorno|giorni|d|sett|settimana|settimane|w|wk"
            + "|mese|mesi|a|anno|anni|y|yr)\\.?"
            // bare counters
            + "|[\\d.,]+\\s*[km]?"
            + ")$");

    private static final Pattern JUNK_PREFIX = Pattern.compile("(?is)^("
            + "(visualizza|vedi|mostra) (altr|tutt|precedent|le altre|\\d)"
            + "|(view|see) (more|previous|all|\\d)"
            + "|scrivi un commento|write a comment|rispondi a |reply to "
            + "|foto del profilo|immagine del profilo|profile picture"
            + "|\\d+\\s+(risposte|risposta|replies|reply|reazioni|reactions)"
            + ").*");

    private static final Pattern MORE_SUFFIX = Pattern.compile(
            "\\s*(…|\\.\\.\\.)\\s*(altro|see more|mostra altro|continua a leggere)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACTION_IN_ROW = Pattern.compile("(?is).*\\b(rispondi|reply)\\b.*");

    /** "Rispondi", or a short action row that contains it ("2 h  Mi piace  Rispondi"). */
    static boolean isAction(String s) {
        String t = s.trim();
        return ACTION.matcher(t).matches()
                || (t.length() <= 40 && ACTION_IN_ROW.matcher(t).matches() && !t.contains("?"));
    }

    static boolean isJunk(String s) {
        String t = s.trim();
        return t.isEmpty() || JUNK.matcher(t).matches() || JUNK_PREFIX.matcher(t).matches();
    }

    private static final Pattern MORE_LABEL = Pattern.compile(
            "(?i)^(…|\\.\\.\\.)?\\s*(altro|leggi altro|mostra altro|visualizza altro|continua a leggere"
            + "|see more|read more|show more)\\s*$");

    /** A standalone "Altro" / "See more" link. */
    static boolean isMoreLabel(String s) {
        return MORE_LABEL.matcher(s.trim()).matches();
    }

    private static final Pattern LOAD_MORE = Pattern.compile("(?i)^("
            + "(visualizza|mostra|vedi|carica)\\s+(altri|ulteriori|i)?\\s*(\\d+\\s+)?(altri\\s+)?commenti(\\s+precedenti)?"
            + "|(view|see|load)\\s+(\\d+\\s+)?(more|previous|earlier)\\s+(\\d+\\s+)?comments"
            + ")\\b.*");

    /** "Visualizza altri commenti" / "View more comments" (not replies). */
    static boolean isLoadMoreComments(String s) {
        return LOAD_MORE.matcher(s.trim()).matches();
    }

    /** Text of a collapsed comment, ending with "… Altro". */
    static boolean endsWithMore(String s) {
        return MORE_SUFFIX.matcher(s).find();
    }

    /** Removes the "… Altro" that marks a collapsed long comment. */
    static String stripMore(String s) {
        return MORE_SUFFIX.matcher(s).replaceAll("…").trim();
    }

    /** "Mario Rossi Bianchi" -> "Mario B." (only part of the name is kept). */
    public static String shortName(String name) {
        String[] p = name.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) return "";
        if (p.length == 1) return p[0];
        return p[0] + " " + p[p.length - 1].substring(0, 1).toUpperCase(Locale.ITALY) + ".";
    }

    public static List<Comment> parse(List<Item> in, int screenW) {
        ArrayList<Item> items = new ArrayList<>(in);
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.t != b.t ? Integer.compare(a.t, b.t) : Integer.compare(a.l, b.l);
            }
        });
        int margin = screenW * 4 / 100;
        ArrayList<Comment> out = new ArrayList<>();
        int prevBottom = Integer.MIN_VALUE;
        for (Item act : items) {
            if (!isAction(act.s)) continue;
            // the action row starts with the time label, left of "Rispondi"
            int h = Math.max(1, act.b - act.t);
            int rowLeft = act.l;
            for (Item o : items) {
                int c = (o.t + o.b) / 2;
                if (c >= act.t - h / 2 && c <= act.b + h / 2 && isJunk(o.s) && o.l < rowLeft) {
                    rowLeft = o.l;
                }
            }
            ArrayList<String> block = new ArrayList<>();
            for (Item o : items) {
                if (o.t < prevBottom || o.b > act.t + 2) continue;
                if (o.l < rowLeft - margin) continue;
                if (isJunk(o.s) || isAction(o.s)) continue;
                String s = stripMore(o.s);
                if (!s.isEmpty() && !block.contains(s)) block.add(s);
            }
            prevBottom = act.b;
            if (block.isEmpty()) continue;
            Comment c = new Comment();
            c.rowLeft = rowLeft;
            if (block.size() >= 2) {
                c.name = block.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < block.size(); i++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.get(i));
                }
                c.text = sb.toString();
            } else {
                // a single piece: maybe "Name\ntext", otherwise text only
                String s = block.get(0);
                int nl = s.indexOf('\n');
                if (nl > 0 && nl < 60) {
                    c.name = s.substring(0, nl).trim();
                    c.text = s.substring(nl + 1).trim();
                } else {
                    c.name = "";
                    c.text = s;
                }
            }
            if (!c.text.isEmpty()) out.add(c);
        }
        return out;
    }

    /** Accumulates comments across scroll positions, without duplicates, in reading order. */
    public static final class Collector {
        private final ArrayList<Comment> all = new ArrayList<>();
        private int minRowLeft = Integer.MAX_VALUE;
        private int screenW = 1;

        public void add(List<Comment> found, int screenW) {
            this.screenW = Math.max(1, screenW);
            for (Comment c : found) {
                minRowLeft = Math.min(minRowLeft, c.rowLeft);
                String key = norm(c.text);
                boolean dup = false;
                for (int i = 0; i < all.size(); i++) {
                    Comment o = all.get(i);
                    if (!o.name.equals(c.name)) continue;
                    String ok = norm(o.text);
                    if (ok.equals(key) || ok.startsWith(key)) {
                        dup = true;
                        break;
                    }
                    if (key.startsWith(ok)) { // collapsed version seen before, keep the full one
                        all.set(i, c);
                        dup = true;
                        break;
                    }
                }
                if (!dup) all.add(c);
            }
        }

        /** Top-level comments only: replies are indented further right than the leftmost rows. */
        public List<Comment> mainComments() {
            ArrayList<Comment> out = new ArrayList<>();
            int limit = minRowLeft + screenW * 8 / 100;
            for (Comment c : all) if (c.rowLeft <= limit) out.add(c);
            return out;
        }

        private static String norm(String s) {
            String t = s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (t.endsWith("…")) t = t.substring(0, t.length() - 1).trim();
            return t;
        }
    }
}
