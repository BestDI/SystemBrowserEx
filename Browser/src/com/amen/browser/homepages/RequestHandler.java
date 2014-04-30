
/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amen.browser.homepages;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.amen.browser.R;
import com.amen.browser.homepages.Template.ListEntityIterator;

public class RequestHandler extends Thread {

    private static final String TAG = "RequestHandler";
    private static final int INDEX = 1;
    private static final int RESOURCE = 2;
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    Uri mUri;
    Context mContext;
    OutputStream mOutput;

    static {
        sUriMatcher.addURI(HomeProvider.AUTHORITY, "/", INDEX);
        sUriMatcher.addURI(HomeProvider.AUTHORITY, "res/*/*", RESOURCE);
    }

    public RequestHandler(Context context, Uri uri, OutputStream out) {
        mUri = uri;
        mContext = context.getApplicationContext();
        mOutput = out;
    }

    @Override
    public void run() {
        super.run();
        try {
            doHandleRequest();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle request: " + mUri, e);
        } finally {
            cleanup();
        }
    }

    void doHandleRequest() throws IOException {
        if ("file".equals(mUri.getScheme())) {
            writeFolderIndex();
            return;
        }
        int match = sUriMatcher.match(mUri);
        switch (match) {
        case INDEX:
            break;
        case RESOURCE:
            writeResource(getUriResourcePath());
            break;
        }
    }

    byte[] htmlEncode(String s) {
        return TextUtils.htmlEncode(s).getBytes();
    }

    // We can reuse this for both History and Bookmarks queries because the
    // columns defined actually belong to the CommonColumn and ImageColumn
    // interfaces that both History and Bookmarks implement

    private static final Comparator<File> sFileComparator = new Comparator<File>() {
        @Override
        public int compare(File lhs, File rhs) {
            if (lhs.isDirectory() != rhs.isDirectory()) {
                return lhs.isDirectory() ? -1 : 1;
            }
            return lhs.getName().compareTo(rhs.getName());
        }
    };

    void writeFolderIndex() throws IOException {
        File f = new File(mUri.getPath());
        final File[] files = f.listFiles();
        Arrays.sort(files, sFileComparator);
        Template t = Template.getCachedTemplate(mContext, R.raw.folder_view);
        t.assign("path", mUri.getPath());
        t.assign("parent_url", f.getParent() != null ? f.getParent() : f.getPath());
        t.assignLoop("files", new ListEntityIterator() {
            int index = -1;

            @Override
            public void writeValue(OutputStream stream, String key) throws IOException {
                File f = files[index];
                if ("name".equals(key)) {
                    stream.write(f.getName().getBytes());
                }
                if ("url".equals(key)) {
                    stream.write(("file://" + f.getAbsolutePath()).getBytes());
                }
                if ("type".equals(key)) {
                    stream.write((f.isDirectory() ? "dir" : "file").getBytes());
                }
                if ("size".equals(key)) {
                    if (f.isFile()) {
                        stream.write(readableFileSize(f.length()).getBytes());
                    }
                }
                if ("last_modified".equals(key)) {
                    String date = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT)
                            .format(f.lastModified());
                    stream.write(date.getBytes());
                }
                if ("alt".equals(key)) {
                    if (index % 2 == 0) {
                        stream.write("alt".getBytes());
                    }
                }
            }

            @Override
            public ListEntityIterator getListIterator(String key) {
                return null;
            }

            @Override
            public void reset() {
                index = -1;
            }

            @Override
            public boolean moveToNext() {
                return (++index) < files.length;
            }
        });
        t.write(mOutput);
    }

    static String readableFileSize(long size) {
        if(size <= 0) return "0";
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(
                size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    String getUriResourcePath() {
        final Pattern pattern = Pattern.compile("/?res/([\\w/]+)");
        Matcher m = pattern.matcher(mUri.getPath());
        if (m.matches()) {
            return m.group(1);
        } else {
            return mUri.getPath();
        }
    }

    void writeResource(String fileName) throws IOException {
        Resources res = mContext.getResources();
        String packageName = R.class.getPackage().getName();
        int id = res.getIdentifier(fileName, null, packageName);
        if (id != 0) {
            InputStream in = res.openRawResource(id);
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) > 0) {
                mOutput.write(buf, 0, read);
            }
        }
    }

    void writeString(String str) throws IOException {
        mOutput.write(str.getBytes());
    }

    void writeString(String str, int offset, int count) throws IOException {
        mOutput.write(str.getBytes(), offset, count);
    }

    void cleanup() {
        try {
            mOutput.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to close pipe!", e);
        }
    }

}
