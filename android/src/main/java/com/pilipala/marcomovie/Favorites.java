package com.pilipala.marcomovie;

import java.util.*;
import org.json.*;

/** Local title references only; refresh details before playback. */
final class Favorites {
    static final int LIMIT = 300;

    static final class Entry {
        String site, id, title, poster;

        String key() {
            return site + "\n" + id;
        }

        Cms.Video video() {
            Cms.Video v = new Cms.Video();
            v.id = id;
            v.title = title;
            v.poster = poster;
            v.remarks = "";
            v.action = "";
            v.tag = "";
            return v;
        }

        JSONObject json() throws JSONException {
            return new JSONObject()
                    .put("site", site)
                    .put("id", id)
                    .put("title", title)
                    .put("poster", poster);
        }
    }

    static List<Entry> read(String raw) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray list = new JSONArray(raw);
            for (int i = 0; i < list.length() && out.size() < LIMIT; i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.site = o.optString("site");
                e.id = o.optString("id");
                e.title = o.optString("title");
                e.poster = o.optString("poster");
                if (Safety.hideRecord(e.title)) continue;
                if (!e.site.isEmpty() && !e.id.isEmpty() && seen.add(e.key())) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean contains(String raw, String site, String id) {
        for (Entry e : read(raw)) if (e.site.equals(site) && e.id.equals(id)) return true;
        return false;
    }

    static String toggle(String raw, String site, Cms.Video video) {
        List<Entry> list = read(raw);
        boolean removed = false;
        for (Iterator<Entry> it = list.iterator(); it.hasNext(); ) {
            Entry cur = it.next();
            if (cur.site.equals(site) && cur.id.equals(video.id)) {
                it.remove();
                removed = true;
            }
        }
        if (!removed) {
            Entry e = new Entry();
            e.site = site;
            e.id = video.id;
            e.title = video.title;
            e.poster = video.poster;
            list.add(0, e);
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(LIMIT, list.size()); i++)
            try {
                out.put(list.get(i).json());
            } catch (Exception ignored) {
            }
        return out.toString();
    }
}
