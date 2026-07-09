package com.k1af.ft8af.log;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ThirdPartyService {
    public static String TAG = "ThirdPartyService";

    public static class StationProfile {
        public final String stationId;
        public final String profileName;
        public final String callsign;
        public final String gridsquare;

        public StationProfile(String stationId, String profileName,
                              String callsign, String gridsquare) {
            this.stationId = stationId;
            this.profileName = profileName;
            this.callsign = callsign;
            this.gridsquare = gridsquare;
        }

        public String displayLabel() {
            StringBuilder sb = new StringBuilder();
            sb.append(stationId);
            if (profileName != null && !profileName.isEmpty()) {
                sb.append(" - ").append(profileName);
            }
            if (callsign != null && !callsign.isEmpty()) {
                sb.append(" (").append(callsign);
                if (gridsquare != null && !gridsquare.isEmpty()) {
                    sb.append(", ").append(gridsquare);
                }
                sb.append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Fetches station profiles from a Cloudlog/Wavelog/Nextlog server.
     * Returns an empty list on any failure (network, bad JSON, 404, etc.) — never null.
     */
    public static List<StationProfile> FetchCloudlogStations(String address, String apiKey) {
        List<StationProfile> stations = new ArrayList<>();
        if (address == null || address.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return stations;
        }
        if (!address.endsWith("/")) {
            address += "/";
        }
        try {
            String url = address + "api/station_info/" + apiKey;
            String result = sendGetRequest(url);
            if (result == null || result.isEmpty()) return stations;
            JSONArray arr = new JSONArray(result);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                stations.add(new StationProfile(
                        obj.optString("station_id", ""),
                        obj.optString("station_profile_name", ""),
                        obj.optString("station_callsign", ""),
                        obj.optString("station_gridsquare", "")
                ));
            }
        } catch (Exception e) {
            Log.d(TAG, "FetchCloudlogStations error: " + e.getClass().getSimpleName());
        }
        return stations;
    }

    private static String QSLRecordToADIF(QSLRecord qslRecord){
        StringBuilder logStr = new StringBuilder();
        logStr.append(AdifFormat.callField(qslRecord.getToCallsign()));

        if (qslRecord.getToMaidenGrid() != null) {
            logStr.append(String.format("<gridsquare:%d>%s "
                    , qslRecord.getToMaidenGrid().length()
                    , qslRecord.getToMaidenGrid()));
        }

        if (qslRecord.getMode() != null) {
            logStr.append(String.format("<mode:%d>%s "
                    , qslRecord.getMode().length()
                    , qslRecord.getMode()));
        }

        // ADIF 3.x SUBMODE (SSTV mode display name, e.g. "Scottie 1"); only when set.
        if (qslRecord.getSubmode() != null && !qslRecord.getSubmode().isEmpty()) {
            logStr.append(String.format("<submode:%d>%s "
                    , qslRecord.getSubmode().length()
                    , qslRecord.getSubmode()));
        }

        String rstSent = AdifFormat.formatReport(qslRecord.getSendReport());
        logStr.append(String.format("<rst_sent:%d>%s ", rstSent.length(), rstSent));

        String rstRcvd = AdifFormat.formatReport(qslRecord.getReceivedReport());
        logStr.append(String.format("<rst_rcvd:%d>%s ", rstRcvd.length(), rstRcvd));

        if (qslRecord.getQso_date() != null) {
            logStr.append(String.format("<qso_date:%d>%s "
                    , qslRecord.getQso_date().length()
                    , qslRecord.getQso_date()));
        }

        if (qslRecord.getTime_on() != null) {
            logStr.append(String.format("<time_on:%d>%s "
                    , qslRecord.getTime_on().length()
                    , qslRecord.getTime_on()));
        }
        if (qslRecord.getBandLength() != null) {
            logStr.append(String.format("<band:%d>%s "
                    , qslRecord.getBandLength().length()
                    , qslRecord.getBandLength()));
        }

        if (qslRecord.getQso_date_off() != null) {
            logStr.append(String.format("<qso_date_off:%d>%s "
                    , qslRecord.getQso_date_off().length()
                    , qslRecord.getQso_date_off()));
        }

        if (qslRecord.getTime_off() != null) {
            logStr.append(String.format("<time_off:%d>%s "
                    , qslRecord.getTime_off().length()
                    , qslRecord.getTime_off()));
        }

        if (String.valueOf(qslRecord.getBandFreq()) != null) {
            Log.d(TAG,String.valueOf(qslRecord.getBandFreq()));
            String freq = String.valueOf((double) qslRecord.getBandFreq() / 1000000);

            logStr.append(String.format("<freq:%d>%s "
                    , freq.length()
                    , freq));
        }

        if (qslRecord.getMyCallsign() != null) {
            logStr.append(String.format("<station_callsign:%d>%s "
                    , qslRecord.getMyCallsign().length()
                    , qslRecord.getMyCallsign()));
        }

        if (qslRecord.getMyMaidenGrid() != null) {
            logStr.append(String.format("<my_gridsquare:%d>%s "
                    , qslRecord.getMyMaidenGrid().length()
                    , qslRecord.getMyMaidenGrid()));
        }

        String comment = qslRecord.getComment();

        //<comment:15>Distance: 99 km <eor>
        //When writing to the database, be sure to append " km"
        logStr.append(String.format("<comment:%d>%s <eor>\n"
                , comment.length()
                , comment));
        return logStr.toString();
    }
    public static boolean UploadToCloudLog(QSLRecord qslRecord){
        // Convert to ADIF format
        String logStr = QSLRecordToADIF(qslRecord);
        return uploadAdifToCloudlog(logStr);
    }

    /**
     * Posts a single ADIF record (or any ADIF body) to Cloudlog/Wavelog/Nextlog.
     * Returns true on HTTP 2xx, false otherwise.
     */
    public static boolean uploadAdifToCloudlog(String adif) {
        String address = GeneralVariables.getCloudlogServerAddress();
        if (address == null || address.isEmpty()) return false;
        if (!address.endsWith("/")){
            address+="/";
        }
        JSONStringer js = new JSONStringer();
        try {
            String result = js.object().key("key").value(GeneralVariables.getCloudlogServerApiKey()).key("station_profile_id").value(GeneralVariables.getCloudlogStationID())
                    .key("type").value("adif").key("string").value(adif).endObject().toString();
            // Cloudlog's documented endpoint is /api/qso (no trailing slash). Wavelog and
            // Nextlog both 308-redirect when the trailing slash is present, which
            // HttpURLConnection won't follow on a POST.
            String clRes = sendPostRequest(address+"api/qso",result);
            Log.d(TAG, "Cloudlog upload " + (clRes != null ? "succeeded" : "failed"));
            return clRes != null;
        }catch (Exception k){
            Log.d(TAG, "Cloudlog upload error: " + k.getClass().getSimpleName());
            return false;
        }
    }
    public static boolean CheckCloudlogConnection(){
        String address = GeneralVariables.getCloudlogServerAddress();
        String apiKey = GeneralVariables.getCloudlogServerApiKey();
        // Check if the address ends with /
        if (!address.endsWith("/")){
            address+="/";
        }
        try{
            // The Cloudlog auth endpoint takes the key as a path segment, so the constructed
            // URL is unavoidably credential-bearing. Do not log it.
            String url = address + "api/auth/"+ apiKey;
            String result = sendGetRequest(url);
            if (result == null) {
                Log.d(TAG, "Cloudlog connection failed: no response");
                return false;
            }
            // Nextlog and Wavelog both implement /api/auth but return slightly different shapes
            // (XML declaration, extra whitespace, etc.). Match on the meaningful markers so all
            // three Cloudlog-compatible backends report Pass.
            String compact = result.replaceAll("\\s+", "");
            return compact.contains("<status>Valid</status>")
                    && compact.contains("<rights>rw</rights>");
        }catch (Exception e){
            Log.d(TAG, "Cloudlog auth error: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Progress callback used during a batch re-upload.
     */
    public interface SyncProgress {
        void onProgress(int done, int total, int cloudlogOk);
    }

    public static class SyncResult {
        public final int total;
        public final int cloudlogOk;
        public final boolean cloudlogAttempted;

        SyncResult(int total, int cloudlogOk, boolean cloudlogAttempted) {
            this.total = total;
            this.cloudlogOk = cloudlogOk;
            this.cloudlogAttempted = cloudlogAttempted;
        }
    }

    /**
     * The {@code WHERE} clause (with a leading space) selecting QSLTable rows that
     * still need an upload to Cloudlog. Returns an empty string when the service is
     * disabled (caller should not query in that case). Single source of truth shared
     * by {@link #syncAllQSOs} and {@link #countUnsyncedQSOs}.
     */
    private static String unsyncedFilter(boolean cloudlog) {
        return cloudlog ? " where synced_cloudlog = 0" : "";
    }

    /**
     * Number of QSLTable rows still awaiting upload to Cloudlog. Returns 0 when
     * Cloudlog is disabled (nothing to do). Lets the auto-sync skip spawning upload
     * work when there's nothing pending. Uses the same filter as {@link #syncAllQSOs}
     * so the count and the actual sync always agree.
     */
    public static int countUnsyncedQSOs(SQLiteDatabase db) {
        boolean cl = GeneralVariables.enableCloudlog;
        if (db == null || !cl) return 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "select count(*) from QSLTable" + unsyncedFilter(cl), null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "countUnsyncedQSOs error: " + e.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /**
     * Re-upload every QSO in QSLTable to Cloudlog if the user has it enabled.
     * The service dedupes by callsign+date+time+mode so repeated calls are safe.
     *
     * Blocks the calling thread — invoke from a background thread/coroutine.
     */
    public static SyncResult syncAllQSOs(SQLiteDatabase db, SyncProgress progress) {
        boolean cl = GeneralVariables.enableCloudlog;
        int total = 0;
        int cloudlogOk = 0;
        if (db == null || !cl) {
            return new SyncResult(0, 0, cl);
        }
        Cursor cursor = null;
        try {
            // Skip rows already accepted. The user can still tell something happened
            // via the dialog's row counts, and a re-press isn't wasted on
            // already-confirmed records.
            cursor = db.rawQuery(
                    "select * from QSLTable" + unsyncedFilter(cl) + " order by id asc", null);
            total = cursor.getCount();
            if (progress != null) progress.onProgress(0, total, 0);
            int idCol = cursor.getColumnIndex("id");
            int syncedClCol = cursor.getColumnIndex("synced_cloudlog");
            int done = 0;
            while (cursor.moveToNext()) {
                long rowId = idCol >= 0 ? cursor.getLong(idCol) : -1;
                boolean alreadyCl = syncedClCol >= 0 && cursor.getInt(syncedClCol) == 1;
                if (!alreadyCl) {
                    String adif = buildAdifFromCursor(cursor);
                    if (uploadAdifToCloudlog(adif)) {
                        cloudlogOk++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_cloudlog");
                    }
                }
                done++;
                if (progress != null) progress.onProgress(done, total, cloudlogOk);
            }
        } catch (Exception e) {
            Log.e(TAG, "syncAllQSOs error: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return new SyncResult(total, cloudlogOk, cl);
    }

    /**
     * Builds a single-record ADIF body from a QSLTable cursor row. Mirrors the field
     * set produced by {@link #QSLRecordToADIF} so Cloudlog sees identical payloads
     * to the immediate-after-QSO upload path.
     */
    private static String buildAdifFromCursor(Cursor c) {
        StringBuilder s = new StringBuilder();
        appendAdif(s, "call", colStr(c, "call"));
        appendAdif(s, "gridsquare", colStr(c, "gridsquare"));
        appendAdif(s, "mode", colStr(c, "mode"));
        appendAdif(s, "submode", colStr(c, "submode"));
        appendAdif(s, "rst_sent", colStr(c, "rst_sent"));
        appendAdif(s, "rst_rcvd", colStr(c, "rst_rcvd"));
        appendAdif(s, "qso_date", colStr(c, "qso_date"));
        appendAdif(s, "time_on", colStr(c, "time_on"));
        appendAdif(s, "band", colStr(c, "band"));
        appendAdif(s, "qso_date_off", colStr(c, "qso_date_off"));
        appendAdif(s, "time_off", colStr(c, "time_off"));

        // QSLTable stores freq as a string; QSLRecordToADIF outputs MHz floats.
        // The DB column is already in MHz form (set by the ADIF export path) so we
        // can pass it through verbatim.
        appendAdif(s, "freq", colStr(c, "freq"));

        appendAdif(s, "station_callsign", colStr(c, "station_callsign"));
        appendAdif(s, "my_gridsquare", colStr(c, "my_gridsquare"));

        String comment = colStr(c, "comment");
        if (comment == null) comment = "";
        s.append(String.format("<comment:%d>%s <eor>\n", comment.length(), comment));
        return s.toString();
    }

    private static void appendAdif(StringBuilder sb, String tag, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(String.format("<%s:%d>%s ", tag, value.length(), value));
    }

    private static String colStr(Cursor c, String name) {
        int idx = c.getColumnIndex(name);
        if (idx < 0) return null;
        return c.getString(idx);
    }

    private static void markRowSynced(SQLiteDatabase db, long rowId, String column) {
        try {
            db.execSQL("update QSLTable set " + column + " = 1 where id = ?",
                    new Object[]{rowId});
        } catch (Exception e) {
            Log.w(TAG, "markRowSynced(" + column + ") failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Mark the freshly-inserted QSL row as accepted by a service. Looks the row up
     * by (call, qso_date, time_on, mode) because the immediate-sync path doesn't
     * carry the row id. Safe to call from a background thread.
     */
    public static void markQsoSynced(SQLiteDatabase db, QSLRecord r,
                                     boolean cloudlogOk) {
        if (db == null || r == null) return;
        if (!cloudlogOk) return;
        try {
            db.execSQL("update QSLTable set synced_cloudlog = 1"
                            + " where [call] = ? and qso_date = ? and time_on = ? and mode = ?",
                    new Object[]{
                            r.getToCallsign(),
                            r.getQso_date(),
                            r.getTime_on(),
                            r.getMode()
                    });
        } catch (Exception e) {
            Log.w(TAG, "markQsoSynced failed: " + e.getClass().getSimpleName());
        }
    }

    public static String sendPostRequest(String url, String json) throws IOException {
        // HttpURLConnection does not auto-follow 30x on a POST. Walk redirects manually
        // (capped) so deployments that rewrite trailing slashes, http→https, or move
        // the API path still work.
        String currentUrl = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            BufferedReader reader = null;
            try {
                URL urlObj = new URL(currentUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                OutputStream os = conn.getOutputStream();
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                // Cloudlog uses HTTP_CREATED as the response for successful record creation
                if (responseCode == HttpURLConnection.HTTP_OK
                        || responseCode == HttpURLConnection.HTTP_CREATED) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                            StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
                if (responseCode == 301 || responseCode == 302 || responseCode == 307
                        || responseCode == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                                + " (no Location header)");
                        return null;
                    }
                    // Resolve relative redirect against the previous URL.
                    URL resolved = new URL(urlObj, loc);
                    Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                            + " redirect to " + resolved);
                    currentUrl = resolved.toString();
                    continue;
                }
                // Non-2xx, non-redirect: capture error body (avoiding the JSON request body
                // since it contains the API key).
                StringBuilder err = new StringBuilder();
                try {
                    java.io.InputStream es = conn.getErrorStream();
                    if (es != null) {
                        BufferedReader eread = new BufferedReader(new InputStreamReader(es,
                                StandardCharsets.UTF_8));
                        String line;
                        while ((line = eread.readLine()) != null) {
                            err.append(line);
                            if (err.length() > 400) break;
                        }
                        eread.close();
                    }
                } catch (Exception ignored) {}
                Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                        + (err.length() > 0 ? " body=" + err : ""));
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                if (reader != null) {
                    reader.close();
                }
            }
        }
        Log.d(TAG, "POST " + url + " exceeded redirect limit");
        return null;
    }
    public static String sendGetRequest(String url) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();

            // Set request method to GET
            conn.setRequestMethod("GET");
            // Set request headers
            conn.setRequestProperty("Content-Type", "application/json");

            // Get server response
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (reader != null) {
                reader.close();
            }
        }
        return null;
    }
}
