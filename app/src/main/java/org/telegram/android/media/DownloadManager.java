package org.telegram.android.media;

import android.graphics.*;
import android.media.MediaScannerConnection;
import android.os.*;
import com.extradea.framework.images.utils.ImageUtils;
import org.telegram.android.StelsApplication;
import org.telegram.android.core.files.DownloadController;
import org.telegram.android.core.model.media.*;
import org.telegram.android.log.Logger;
import org.telegram.android.ui.UiNotifier;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.*;

/**
 * Author: Korshakov Stepan
 * Created: 10.08.13 20:46
 */
public class DownloadManager {

    private static final String TAG = "Downloader";

    public static String getVideoKey(TLLocalVideo video) {
        if (video.getVideoLocation() instanceof TLLocalFileVideoLocation) {
            return ((TLLocalFileVideoLocation) video.getVideoLocation()).getDcId() + "_" + ((TLLocalFileVideoLocation) video.getVideoLocation()).getVideoId();
        } else if (video.getVideoLocation() instanceof TLLocalEncryptedFileLocation) {
            return ((TLLocalEncryptedFileLocation) video.getVideoLocation()).getDcId() + "_" + ((TLLocalEncryptedFileLocation) video.getVideoLocation()).getId();
        }
        return null;
    }

    public static String getPhotoKey(TLLocalPhoto photo) {
        if (photo.getFullLocation() instanceof TLLocalFileLocation) {
            return ((TLLocalFileLocation) photo.getFullLocation()).getDcId() + "_" + ((TLLocalFileLocation) photo.getFullLocation()).getVolumeId() + "_" + ((TLLocalFileLocation) photo.getFullLocation()).getLocalId();
        } else if (photo.getFullLocation() instanceof TLLocalEncryptedFileLocation) {
            return ((TLLocalEncryptedFileLocation) photo.getFullLocation()).getDcId() + "_" + ((TLLocalEncryptedFileLocation) photo.getFullLocation()).getId();
        } else {
            return null;
        }
    }

    private static int SMALL_THUMB_SIDE;

    private static final int ATTEMPTS_COUNT = 3;

    private static final int THREADS_COUNT = 2;

    private static final int TIMEOUT = 5 * 60 * 1000;

    private static final int OVERLORD_TIMEOUT = 60 * 1000;

    private static final int PAGE_SIZE = 128 * 1024;
    private static final int PAGE_SIZE_SLOW = 8 * 1024;

    private static final int NOTIFY_DELAY = 500;

    private DownloadPersistence downloadPersistence;

    private HashSet<String> enqueued = new HashSet<String>();

    private class DownloadRecord {
        public TLLocalVideo video;
        public TLLocalPhoto photo;
        public int downloaded;
        public int downloadedPercent;
        public DownloadState state;
    }

    private ExecutorService downloadService = Executors.newFixedThreadPool(THREADS_COUNT);

    private ExecutorService service;

    private UiNotifier notifier = new UiNotifier();
    private StelsApplication application;

    private HashMap<String, DownloadRecord> records = new HashMap<String, DownloadRecord>();

    private long lastDownloadEvent;

    private CopyOnWriteArrayList<WeakReference<DownloadListener>> listeners = new CopyOnWriteArrayList<WeakReference<DownloadListener>>();

    public DownloadManager(StelsApplication application) {
        this.service = Executors.newFixedThreadPool(3);
        this.application = application;
        this.downloadPersistence = new DownloadPersistence(application);
        this.downloadPersistence.tryLoad();

        SMALL_THUMB_SIDE = (int) (application.getResources().getDisplayMetrics().density * 75);
    }

    public void registerListener(DownloadListener listener) {
        for (WeakReference<DownloadListener> ref : listeners) {
            if (ref.get() == listener) {
                return;
            }
        }
        listeners.add(new WeakReference<DownloadListener>(listener));
    }

    public void unregisterListener(DownloadListener listener) {
        for (WeakReference<DownloadListener> ref : listeners) {
            if (ref.get() == listener) {
                listeners.remove(ref);
                return;
            }
        }
    }

    public synchronized DownloadState getState(String key) {
        if (downloadPersistence.isDownloaded(key))
            return DownloadState.COMPLETED;

        if (records.containsKey(key)) {
            DownloadRecord record = records.get(key);
            return record.state;
        }

        return DownloadState.NONE;
    }

    public synchronized int getDownloadProgress(String key) {
        if (downloadPersistence.isDownloaded(key))
            return 100;

        if (records.containsKey(key)) {
            DownloadRecord record = records.get(key);
            return record.downloadedPercent;
        }

        return 0;
    }

    public synchronized void requestDownload(TLLocalPhoto photo) {

        final String resourceKey = getPhotoKey(photo);

        if (downloadPersistence.isDownloaded(resourceKey))
            return;

        final DownloadRecord record;
        if (records.containsKey(resourceKey)) {
            record = records.get(resourceKey);
            if (record.state == DownloadState.CANCELLED ||
                    record.state == DownloadState.FAILURE ||
                    record.state == DownloadState.NONE) {
                record.state = DownloadState.PENDING;
                record.downloaded = 0;
                record.downloadedPercent = 0;
                record.photo = photo;
                updateState(resourceKey, record.state, 0, 0);
            }
        } else {
            record = new DownloadRecord();
            record.state = DownloadState.PENDING;
            record.downloaded = 0;
            record.downloadedPercent = 0;
            record.photo = photo;
            records.put(resourceKey, record);
            updateState(resourceKey, record.state, 0, 0);
        }

        requestDownload(resourceKey, record,
                getDownloadImageFile(resourceKey),
                photo.getFullLocation());
    }

    public synchronized void requestDownload(TLLocalVideo video) {
        final String resourceKey = getVideoKey(video);
        if (downloadPersistence.isDownloaded(resourceKey))
            return;

        final DownloadRecord record;
        if (records.containsKey(resourceKey)) {
            record = records.get(resourceKey);
            if (record.state == DownloadState.CANCELLED ||
                    record.state == DownloadState.FAILURE ||
                    record.state == DownloadState.NONE) {
                record.state = DownloadState.PENDING;
                record.downloaded = 0;
                record.downloadedPercent = 0;
                record.video = video;
                updateState(resourceKey, record.state, 0, 0);
            }
        } else {
            record = new DownloadRecord();
            record.state = DownloadState.PENDING;
            record.downloaded = 0;
            record.downloadedPercent = 0;
            record.video = video;
            records.put(resourceKey, record);
            updateState(resourceKey, record.state, 0, 0);
        }

        requestDownload(resourceKey, record,
                getDownloadVideoFile(resourceKey),
                video.getVideoLocation());
    }

    private void requestDownload(final String key, final DownloadRecord record, final String fileName, final TLAbsLocalFileLocation fileLocation) {
        if (enqueued.contains(key)) {
            return;
        }
        enqueued.add(key);

        final int dcId;
        final int size;
        if (fileLocation instanceof TLLocalFileVideoLocation) {
            dcId = ((TLLocalFileVideoLocation) fileLocation).getDcId();
            size = ((TLLocalFileVideoLocation) fileLocation).getSize();
        } else if (fileLocation instanceof TLLocalFileLocation) {
            dcId = ((TLLocalFileLocation) fileLocation).getDcId();
            size = ((TLLocalFileLocation) fileLocation).getSize();
        } else if (fileLocation instanceof TLLocalEncryptedFileLocation) {
            dcId = ((TLLocalEncryptedFileLocation) fileLocation).getDcId();
            size = ((TLLocalEncryptedFileLocation) fileLocation).getSize();
        } else {
            return;
        }

        service.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    updateState(key, DownloadState.IN_PROGRESS, 0, 0);

                    String destFile = application.getDownloadController().downloadFile(fileLocation, new DownloadController.DownloadListener() {
                        @Override
                        public boolean onPieceDownloaded(int percent, int downloadedSize) {
                            if (record.state == DownloadState.CANCELLED) {
                                return false;
                            } else {
                                updateState(key, DownloadState.IN_PROGRESS, percent, downloadedSize);
                                return true;
                            }
                        }
                    });

                    if (destFile == null) {
                        updateState(key, DownloadState.FAILURE, 0, 0);
                        return;
                    }

                    try {
                        copy(new File(destFile), new File(fileName));
                    } catch (IOException e) {
                        Logger.t(TAG, e);
                        updateState(key, DownloadState.FAILURE, 0, 0);
                        return;
                    }

                    if (record.photo != null) {
                        Logger.d(TAG, "@" + key + " = building thumb");
                        Bitmap thumb = ImageUtils.getBitmapThumb(fileName, SMALL_THUMB_SIDE, SMALL_THUMB_SIDE);
                        if (thumb == null) {
                            updateState(key, DownloadState.FAILURE, 0, 0);
                            return;
                        }
                        Logger.d(TAG, "@" + key + " = saving thumb");
                        FileOutputStream outputStream = null;
                        try {
                            outputStream = new FileOutputStream(getPhotoThumbSmallFileName(key));
                            thumb.compress(Bitmap.CompressFormat.JPEG, 87, outputStream);
                        } catch (FileNotFoundException e) {
                            Logger.t(TAG, e);
                            updateState(key, DownloadState.FAILURE, 100, size);
                            return;
                        } finally {
                            if (outputStream != null) {
                                try {
                                    outputStream.close();
                                } catch (IOException e) {
                                    Logger.t(TAG, e);
                                }
                            }
                        }

                        try {
                            Logger.d(TAG, "@" + key + " = writing to gallery");
                            if (application.getUserSettings().isSaveToGalleryEnabled()) {
                                writeToGallery(fileName, key + ".jpg");
                            }
                        } catch (Exception e) {
                            Logger.t(TAG, e);
                        }
                    } else {
                        Logger.d(TAG, "@" + key + " = writing to gallery");
                        if (application.getUserSettings().isSaveToGalleryEnabled()) {
                            try {
                                writeToGallery(fileName, key + ".mp4");
                            } catch (Exception e) {
                                Logger.t(TAG, e);
                            }
                        }
                    }
                    Logger.d(TAG, "@" + key + " = mark as downloaded");
                    downloadPersistence.markDownloaded(key);
                    updateState(key, DownloadState.COMPLETED, 100, size);
                } finally {
                    enqueued.remove(key);
                }
            }
        });
    }

    public synchronized void abortDownload(String key) {
        DownloadRecord record = records.get(key);
        if (record != null) {
            updateState(key, DownloadState.CANCELLED, record.downloadedPercent, record.downloaded);
        }
    }

    public synchronized void saveDownloadImage(String key, String fileName) throws IOException {
        Logger.d(TAG, "@" + key + " = saving downloaded image");
        copy(new File(fileName), new File(getDownloadImageFile(key)));
        Logger.d(TAG, "@" + key + " = mark as downloaded image");
        downloadPersistence.markDownloaded(key);
    }

    public synchronized void saveDownloadVideo(String key, String fileName) throws IOException {
        Logger.d(TAG, "@" + key + " = saving downloaded video");
        copy(new File(fileName), new File(getDownloadVideoFile(key)));
        Logger.d(TAG, "@" + key + " = mark as downloaded video");
        downloadPersistence.markDownloaded(key);
    }

    public void writeToGallery(String fileName, String destName) throws IOException {
        Logger.d(TAG, "Writing file to gallery: " + fileName + " -> " + destName);
        String directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath()
                + "/Telegram/";
        new File(directory).mkdirs();
        String file = directory + destName;
        File dest = new File(file);
        if (dest.exists()) {
            return;
        }
        copy(new File(fileName), dest);
        MediaScannerConnection.scanFile(application, new String[]{file}, null, null);
    }

    private void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);

        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    public String getVideoFileName(String key) {
        return getDownloadVideoFile(key);
    }

    public String getPhotoFileName(String key) {
        return getDownloadImageFile(key);
    }

    public String getPhotoThumbSmallFileName(String key) {
        return getDownloadImageThumbFile(key);
    }

    private String getDownloadVideoFile(String key) {
        if (Build.VERSION.SDK_INT >= 8) {
            try {
                return ((File) StelsApplication.class.getMethod("getExternalCacheDir").invoke(application)).getAbsolutePath() + "/video_" + key + ".mp4";
            } catch (Exception e) {
                // Log.e(TAG, e);
            }
        }

        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/cache/" + application.getPackageName() + "/video_" + key + ".mp4";
    }

    private String getDownloadImageFile(String key) {
        if (Build.VERSION.SDK_INT >= 8) {
            try {
                return ((File) StelsApplication.class.getMethod("getExternalCacheDir").invoke(application)).getAbsolutePath() + "/image_" + key + ".jpg";
            } catch (Exception e) {
                // Log.e(TAG, e);
            }
        }

        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/cache/" + application.getPackageName() + "/image_" + key + ".jpg";
    }

    private String getDownloadImageThumbFile(String key) {
        if (Build.VERSION.SDK_INT >= 8) {
            try {
                return ((File) StelsApplication.class.getMethod("getExternalCacheDir").invoke(application)).getAbsolutePath() + "/image_thumb_" + key + ".jpg";
            } catch (Exception e) {
                // Log.e(TAG, e);
            }
        }

        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/cache/" + application.getPackageName() + "/image_thumb_" + key + ".jpg";
    }

    private synchronized void updateState(final String key, final DownloadState state, final int percent, int bytes) {
        Logger.d(TAG, "State: " + key + " = " + state + " " + percent + "%");

        DownloadRecord record = records.get(key);
        record.state = state;
        record.downloadedPercent = percent;
        record.downloaded = bytes;

        notifier.notify(key, new Runnable() {
            @Override
            public void run() {
                for (WeakReference<DownloadListener> ref : listeners) {
                    DownloadListener listener = ref.get();
                    if (listener != null) {
                        listener.onStateChanged(key, state, percent);
                    }
                }
            }
        });
    }
}