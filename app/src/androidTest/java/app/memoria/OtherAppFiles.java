package app.memoria;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.IOException;

/**
 * Another app's files for OpenFromAppTest: the test APK's assets under a content URI MediaStore does not know.
 * Java, not Kotlin: it runs in the test APK's own process, which has no Kotlin library.
 */
public class OtherAppFiles extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return uri.getPath().endsWith(".mp4") ? "video/mp4" : "image/jpeg"; }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) {
        try {
            return getContext().getAssets().openFd(uri.getPath().substring(1));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // only the two columns a chat app's provider gives: the name and the size
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {uri.getLastPathSegment(), openAssetFile(uri, "r").getLength()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
