package com.pilipala.marcomovie;

import org.json.*;
import java.util.*;

/** Device-local bookmarks. Resolve fresh media URLs; never persist signed playback URLs. */
final class WatchHistory {
    static final int LIMIT = 60;

    static final class Entry {
        String site, id, title, poster, line, episode;
        int index, position, duration;
        long updated;

        String key() {
            return site + "\n" + id;
        }

        boolean complete() {
            return duration > 0 && position >= duration - Math.min(5000, duration / 20);
        }

        int resume() {
            return complete() ? 0 : Math.max(0, position);
        }

        JSONObject json() throws JSONException {
            return new JSONObject()
                    .put("site", site)
                    .put("id", id)
                    .put("title", title)
                    .put("poster", poster)
                    .put("line", line)
                    .put("episode", episode)
                    .put("index", index)
                    .put("position", position)
                    .put("duration", duration)
                    .put("updated", updated);
        }
    }

    static List<Entry> read(String text) {
        List<Entry> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length() && out.size() < LIMIT; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.site = o.optString("site");
                e.id = o.optString("id");
                e.title = o.optString("title");
                e.poster = o.optString("poster");
                e.line = o.optString("line");
                e.episode = o.optString("episode");
                e.index = Math.max(0, o.optInt("index"));
                e.position = Math.max(0, o.optInt("position"));
                e.duration = Math.max(0, o.optInt("duration"));
                e.updated = o.optLong("updated");
                if (Safety.hideRecord(e.title)) continue;
                if (!e.site.isEmpty() && !e.id.isEmpty() && keys.add(e.key())) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String upsert(String text, Entry entry) {
        List<Entry> entries = read(text);
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().key().equals(entry.key())) it.remove();
        }
        entries.add(0, entry);
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(LIMIT, entries.size()); i++)
            try {
                a.put(entries.get(i).json());
            } catch (Exception ignored) {
            }
        return a.toString();
    }

    static String remove(String text, String key) {
        JSONArray a = new JSONArray();
        for (Entry e : read(text))
            if (!e.key().equals(key))
                try {
                    a.put(e.json());
                } catch (Exception ignored) {
                }
        return a.toString();
    }
}
