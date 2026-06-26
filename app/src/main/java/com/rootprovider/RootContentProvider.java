package com.rootprovider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class RootContentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                       String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        
        String query = uri.getLastPathSegment();
        
        if ("is_rooted".equals(query)) {
            cursor.addRow(new Object[]{"rooted", "true"});
            cursor.addRow(new Object[]{"su_path", "/system/xbin/su"});
            cursor.addRow(new Object[]{"build_tags", "test-keys"});
            cursor.addRow(new Object[]{"selinux", "permissive"});
            cursor.addRow(new Object[]{"magisk_version", "28.0"});
        }
        
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.rootprovider";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                     String[] selectionArgs) {
        return 0;
    }
}
