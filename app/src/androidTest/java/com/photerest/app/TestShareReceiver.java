package com.photerest.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Separate test-APK UID. Uses only platform classes, since target dependencies are not on its classpath. */
public class TestShareReceiver extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean viewingOriginal = Intent.ACTION_VIEW.equals(getIntent().getAction());
        Uri uri = viewingOriginal ? getIntent().getData() : getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        long count = 0;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = stream.read(buffer)) >= 0) count += n;
        } catch (Exception error) { count = -1; }
        String result = getIntent().getType() + ":" + count;
        if (viewingOriginal) {
            result += "\n" + uri + "\n" + ((getIntent().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        }
        String receipt = viewingOriginal ? "original-receipt.txt" : "share-receipt.txt";
        try (FileOutputStream output = new FileOutputStream(new File(getExternalFilesDir(null), receipt))) {
            output.write(result.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new RuntimeException(error); }
        TextView text = new TextView(this);
        text.setText("收到图片\n" + result); text.setTextSize(22); text.setPadding(40, 160, 40, 40);
        setContentView(text);
    }
}
