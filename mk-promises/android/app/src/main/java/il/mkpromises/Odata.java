package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Knesset OData v4 client. Entity keys are Id; PersonID, BillID and CommitteeID are foreign keys only. */
final class Odata {

    static final String BASE = "https://knesset.gov.il/OdataV4/ParliamentInfo/";
    private static final int PAGE = 100;

    private Odata() {
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /** Build "Entity?$filter=..&$select=..&$orderby=Id" (the server drops $ from nextLink, so paging is done with $skip). */
    static String url(String entity, String filter, String select) {
        StringBuilder b = new StringBuilder(BASE).append(entity).append("?$orderby=Id");
        if (filter != null && !filter.isEmpty()) {
            b.append("&$filter=").append(enc(filter));
        }
        if (select != null && !select.isEmpty()) {
            b.append("&$select=").append(enc(select));
        }
        return b.toString();
    }

    /** All rows of a query, 100 per page, stopping at maxRows. */
    static List<JSONObject> fetchAll(String entity, String filter, String select, int maxRows) throws IOException {
        List<JSONObject> rows = new ArrayList<>();
        String base = url(entity, filter, select);
        for (int skip = 0; rows.size() < maxRows; skip += PAGE) {
            JSONArray value = page(base + "&$skip=" + skip + "&$top=" + PAGE);
            for (int i = 0; i < value.length(); i++) {
                rows.add(value.optJSONObject(i));
            }
            if (value.length() < PAGE) {
                break;
            }
        }
        return rows;
    }

    static JSONArray page(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String body = Http.get(url, "application/json", Http.KNESSET);
                JSONObject o = new JSONObject(body);
                JSONArray value = o.optJSONArray("value");
                if (value == null) {
                    throw new IOException("מבנה הנתונים מהכנסת אינו צפוי");
                }
                return value;
            } catch (Http.HttpException e) {
                if (e.status >= 400 && e.status < 500) {
                    throw e;   // schema, filter and authorization errors are not transient
                }
                last = e;
            } catch (JSONException e) {
                throw new IOException("תשובת הכנסת אינה JSON תקין (ייתכן חסימה גאוגרפית)");
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(1500L * (attempt + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last == null ? new IOException("הבקשה נכשלה") : last;
    }

    static long id(Object v) {
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n > 0 ? n : -1;
        }
        if (v instanceof String && ((String) v).matches("\\d{1,15}")) {
            return Long.parseLong((String) v);
        }
        return -1;
    }

    /** "Id eq 1 or Id eq 2 ..." for at most 10 ids: the server limits a filter to about 100 nodes. */
    static String idFilter(String field, List<Long> ids) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                b.append(" or ");
            }
            b.append(field).append(" eq ").append(ids.get(i));
        }
        return b.toString();
    }
}
