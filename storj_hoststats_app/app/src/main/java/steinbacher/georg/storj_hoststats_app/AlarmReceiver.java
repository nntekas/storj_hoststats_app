package steinbacher.georg.storj_hoststats_app;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import steinbacher.georg.storj_hoststats_app.data.DatabaseManager;

import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.POWER_SERVICE;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    private static final String STORJ_API_URL = "https://api.storj.io";

    private Context mContext;

    @Override
    public void onReceive(Context context, Intent intent) {
        mContext = context;
        Toast.makeText(context, "I'm running", Toast.LENGTH_SHORT).show();

        DatabaseManager databaseManager = DatabaseManager.getInstance(mContext);
        ArrayList<StorjNode> storjNodes = new ArrayList<>();
        Cursor cursor = databaseManager.queryAllNodes(getSavedSortOrder());

        for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            storjNodes.add(new StorjNode(cursor));
        }

        new StorjApiCommunicationTask().execute(storjNodes);
    }

    private String getSavedSortOrder() {
        SharedPreferences prefs = mContext.getSharedPreferences(Parameters.SHARED_PREF, MODE_PRIVATE);
        return prefs.getString(Parameters.SHARED_PREF_SORT_ORDER, Parameters.SHARED_PREF_SORT_ORDER_RESPONSE_ASC);
    }



    private class StorjApiCommunicationTask extends AsyncTask<List<StorjNode>, String, StorjNode> {

        @Override
        protected StorjNode doInBackground(List<StorjNode>... lists) {
            StorjNode node = null;

            for (StorjNode storjNode : lists[0]) {
                try {
                    JSONObject jsonObject = getJSONObjectFromURL(STORJ_API_URL + "/contacts/" + storjNode.getNodeID());
                    Log.d(TAG, "onReceive: " + jsonObject.toString());

                    DatabaseManager db = DatabaseManager.getInstance(mContext);

                    node = new StorjNode(jsonObject);
                    node.setLastChecked(Calendar.getInstance().getTime());

                    StorjNode previusNode = new StorjNode(db.getNode(node.getNodeID()));
                    node.setSimpleName(previusNode.getSimpleName());

                    if(isNodeOffline(node)) {
                        node.setResponseTime(-1);
                        node.setShouldSendNotification(false);

                        if(previusNode.getShouldSendNotification())
                            sendNodeOfflineNotification(node);
                    } else if(previusNode.getResponseTime() == 0){
                        //was the node offline before and went online now ?
                        node.setShouldSendNotification(true);
                    }

                    db.updateNode(node);

                    publishProgress(node.getNodeID());
                } catch (IOException e) {
                    Log.i(TAG, "doInBackground: " + storjNode.getNodeID() + " not found");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            return node;
        }



        @Override
        protected void onProgressUpdate(String... nodeId) {
            super.onProgressUpdate(nodeId);

            Intent updateUIIntent = new Intent(Parameters.UPDATE_UI_ACTION);
            updateUIIntent.putExtra(Parameters.UPDATE_UI_NODEID, nodeId[0]);
            Application.getAppContext().sendBroadcast(updateUIIntent);
        }

        @Override
        protected void onPostExecute(StorjNode receivedStorjNode) {
            super.onPostExecute(receivedStorjNode);
        }

        private JSONObject getJSONObjectFromURL(String urlString) throws IOException, JSONException {
            HttpURLConnection urlConnection = null;
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
            br.close();

            String jsonString = sb.toString();
            return new JSONObject(jsonString);
        }

        private void sendNodeOfflineNotification(StorjNode storjNode) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(mContext)
                            .setSmallIcon(R.drawable.storj_symbol)
                            .setContentTitle(storjNode.getSimpleName())
                            .setContentText(mContext.getString(R.string.node_is_offline, storjNode.getSimpleName()));

            NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(storjNode.getNodeID().hashCode(), mBuilder.build());
        }

        private boolean isNodeOffline(StorjNode storjNode) {
            Date currentTime = Calendar.getInstance().getTime();
            return (currentTime.getTime() - storjNode.getLastSeen().getTime()) >= getNodeOfflineAfter();

        }

        private long getNodeOfflineAfter() {
            SharedPreferences prefs = mContext.getSharedPreferences(Parameters.SHARED_PREF, MODE_PRIVATE);
            return prefs.getLong(Parameters.SHARED_PREF_OFLINE_AFTER, Parameters.SHARED_PREF_OFLINE_AFTER_DEFAULT);
        }
    }


}
