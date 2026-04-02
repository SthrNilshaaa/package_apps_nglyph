package org.aspends.nglyphs.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

public class RingtoneHelper {

    /**
     * Checks if the app has permission to write system settings and opens the settings screen if not.
     */
    public static void checkAndRequestPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                // This will open the system settings screen for your app
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        }
    }

    /**
     * Sets a system ringtone or notification sound directly from an InputStream.
     * 
     * @param context     The context.
     * @param inputStream The input stream of the audio file.
     * @param fileName    The name of the file (for MediaStore title).
     * @param type        RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION.
     */
    public static void setSystemTone(Context context, InputStream inputStream, String fileName, int type) {
        
        // 1. Double check permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            Toast.makeText(context, "Please grant Write Settings permission first", Toast.LENGTH_SHORT).show();
            checkAndRequestPermissions(context);
            return;
        }

        // 2. Prepare MediaStore metadata
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.TITLE, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg"); // Assuming .ogg as used in the app
        values.put(MediaStore.MediaColumns.SIZE, 0);

        if (type == RingtoneManager.TYPE_RINGTONE) {
            values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
            values.put(MediaStore.Audio.Media.IS_NOTIFICATION, false);
        } else if (type == RingtoneManager.TYPE_NOTIFICATION) {
            values.put(MediaStore.Audio.Media.IS_RINGTONE, false);
            values.put(MediaStore.Audio.Media.IS_NOTIFICATION, true);
        }
        
        values.put(MediaStore.Audio.Media.IS_ALARM, false);
        values.put(MediaStore.Audio.Media.IS_MUSIC, false);

        // 3. Insert into MediaStore to get a Uri
        ContentResolver contentResolver = context.getContentResolver();
        Uri newUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

        if (newUri == null) {
            Toast.makeText(context, "Failed to insert into MediaStore", Toast.LENGTH_SHORT).show();
            return;
        }

        // 4. Write the file data to the MediaStore Uri
        try (OutputStream os = contentResolver.openOutputStream(newUri)) {
            if (os == null) {
                Toast.makeText(context, "Failed to open output stream", Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            
            // 5. Actually set the Ringtone or Notification!
            RingtoneManager.setActualDefaultRingtoneUri(context, type, newUri);
            Toast.makeText(context, "Tone set successfully!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "Error setting tone: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
