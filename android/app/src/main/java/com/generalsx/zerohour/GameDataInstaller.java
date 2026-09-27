package com.generalsx.zerohour;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GameDataInstaller {

    private GameDataInstaller() {}

    /*
     * ============================================================
     * CHANGE THESE TWO VALUES LATER
     * ============================================================
     *
     * Use a server / release asset where you are legally allowed
     * to distribute the files.
     *
     * Example:
     * https://your-server.example/GeneralsZH-Android.zip
     */
    private static final String GAME_ZIP_URL =
        "https://github.com/WaruEviL/GeneralsZH-Android-Port/releases/download/v1/GeneralsZHWE.zip";

    /*
     * SHA-256 of the complete ZIP file.
     *
     * Put the real SHA-256 here before release.
     *
     * For development you can temporarily leave this empty.
     */
    private static final String GAME_ZIP_SHA256 = "8682eaae52cb41e9c1db0b35743de522ff985b6b49e582cc8d49e32a1a0a254d";

    private static final String ZIP_NAME =
        "GeneralsZH-Android.zip";

    private static final String PART_NAME =
        "GeneralsZH-Android.zip.part";

    private static final String GAME_DIRECTORY =
        "GeneralsZH";

    interface Listener {
        void onProgress(long downloaded, long total);
        void onStatus(String status);
        void onSuccess(File gameDirectory);
        void onError(String message);
    }

    static void install(
            Context context,
            Listener listener
    ) {
        final Context appContext = context.getApplicationContext();

        Thread worker = new Thread(() -> {
            try {
                File root = appContext.getExternalFilesDir(null);

                if (root == null) {
                    throw new IOException(
                        "Android storage directory is unavailable."
                    );
                }

                File downloadDir = new File(
                    root,
                    "downloads"
                );

                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new IOException(
                        "Cannot create download directory."
                    );
                }

                File zipFile = new File(
                    downloadDir,
                    ZIP_NAME
                );

                File partFile = new File(
                    downloadDir,
                    PART_NAME
                );

                File gameDir = new File(
                    root,
                    GAME_DIRECTORY
                );

                notifyStatus(
                    listener,
                    "Preparing download..."
                );

                downloadFile(
                    partFile,
                    listener
                );

                /*
                 * Rename the completed .part file.
                 */
                if (zipFile.exists() && !zipFile.delete()) {
                    throw new IOException(
                        "Cannot replace old ZIP file."
                    );
                }

                if (!partFile.renameTo(zipFile)) {
                    throw new IOException(
                        "Cannot finalize downloaded ZIP."
                    );
                }

                /*
                 * SHA-256 verification.
                 */
                if (!GAME_ZIP_SHA256.trim().isEmpty()) {

                    notifyStatus(
                        listener,
                        "Checking downloaded files..."
                    );

                    String actualHash =
                        sha256(zipFile);

                    if (!GAME_ZIP_SHA256.equalsIgnoreCase(actualHash)) {

                        if (!zipFile.delete()) {
                            // Nothing else to do here.
                        }

                        throw new IOException(
                            "SHA-256 verification failed.\n\n"
                            + "Expected:\n"
                            + GAME_ZIP_SHA256
                            + "\n\nActual:\n"
                            + actualHash
                        );
                    }
                }

                /*
                 * Remove an old installation only after the
                 * download has successfully completed.
                 */
                if (gameDir.exists()) {
                    notifyStatus(
                        listener,
                        "Removing old game data..."
                    );

                    deleteDirectory(gameDir);
                }

                if (!gameDir.mkdirs() && !gameDir.isDirectory()) {
                    throw new IOException(
                        "Cannot create game directory."
                    );
                }

                notifyStatus(
                    listener,
                    "Extracting game files..."
                );

                extractZip(
                    zipFile,
                    gameDir,
                    listener
                );

                /*
                 * Basic verification.
                 *
                 * The current Android port checks these files
                 * when deciding whether a game directory is valid.
                 */
                File iniZh = new File(
                    gameDir,
                    "INIZH.big"
                );

                File ini = new File(
                    gameDir,
                    "INI.big"
                );

                if (!iniZh.isFile() && !ini.isFile()) {
                    throw new IOException(
                        "Game data was extracted, but INIZH.big "
                        + "or INI.big was not found."
                    );
                }

                notifyStatus(
                    listener,
                    "Installation complete."
                );

                /*
                 * ZIP is no longer needed after extraction.
                 */
                if (!zipFile.delete()) {
                    // Not fatal. Keep it if Android refuses deletion.
                }

                notifySuccess(
                    listener,
                    gameDir
                );

            } catch (Exception e) {

                notifyError(
                    listener,
                    e.getMessage() != null
                        ? e.getMessage()
                        : e.toString()
                );
            }
        });

        worker.start();
    }

    private static void downloadFile(
            File partFile,
            Listener listener
    ) throws Exception {

        long existingLength = partFile.exists()
            ? partFile.length()
            : 0L;

        HttpURLConnection connection = null;

        try {

            URL url = new URL(GAME_ZIP_URL);

            connection =
                (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(
                15000
            );

            connection.setReadTimeout(
                30000
            );

            connection.setInstanceFollowRedirects(
                true
            );

            /*
             * Resume download when a .part file already exists.
             */
            if (existingLength > 0) {
                connection.setRequestProperty(
                    "Range",
                    "bytes=" + existingLength + "-"
                );
            }

            int responseCode =
                connection.getResponseCode();

            boolean resume =
                existingLength > 0
                && responseCode == HttpURLConnection.HTTP_PARTIAL;

            /*
             * Server ignored Range.
             *
             * Start again from zero instead of corrupting the ZIP.
             */
            if (!resume) {

                if (existingLength > 0) {
                    existingLength = 0;

                    if (partFile.exists()
                            && !partFile.delete()) {
                        throw new IOException(
                            "Cannot reset partial download."
                        );
                    }
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                        "Download failed. HTTP "
                        + responseCode
                    );
                }
            }

            long contentLength =
                connection.getContentLengthLong();

            long total;

            if (contentLength > 0) {
                total =
                    existingLength + contentLength;
            } else {
                total = -1L;
            }

            notifyStatus(
                listener,
                existingLength > 0
                    ? "Resuming download..."
                    : "Downloading game files..."
            );

            InputStream input =
                new BufferedInputStream(
                    connection.getInputStream()
                );

            FileOutputStream fos =
                new FileOutputStream(
                    partFile,
                    resume
                );

            OutputStream output =
                new BufferedOutputStream(
                    fos
                );

            byte[] buffer =
                new byte[1024 * 1024];

            long downloaded =
                existingLength;

            long lastNotify =
                System.currentTimeMillis();

            try {

                while (true) {

                    int read =
                        input.read(buffer);

                    if (read == -1) {
                        break;
                    }

                    output.write(
                        buffer,
                        0,
                        read
                    );

                    downloaded += read;

                    long now =
                        System.currentTimeMillis();

                    /*
                     * Don't update the UI for every packet.
                     */
                    if (now - lastNotify >= 250) {

                        notifyProgress(
                            listener,
                            downloaded,
                            total
                        );

                        lastNotify = now;
                    }
                }

                output.flush();

            } finally {

                try {
                    output.close();
                } catch (Exception ignored) {
                }

                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }

            notifyProgress(
                listener,
                downloaded,
                total
            );

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void extractZip(
            File zipFile,
            File destination,
            Listener listener
    ) throws Exception {

        String destinationPath =
            destination
                .getCanonicalPath()
                + File.separator;

        ZipInputStream input =
            new ZipInputStream(
                new BufferedInputStream(
                    new FileInputStream(zipFile)
                )
            );

        byte[] buffer =
            new byte[1024 * 1024];

        try {

            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {

                String name =
                    entry.getName();

                /*
                 * Never allow ZIP path traversal.
                 */
                File outputFile =
                    new File(
                        destination,
                        name
                    );

                String outputPath =
                    outputFile.getCanonicalPath();

                if (!outputPath.startsWith(
                        destinationPath)) {

                    throw new IOException(
                        "Unsafe ZIP entry: "
                        + name
                    );
                }

                if (entry.isDirectory()) {

                    if (!outputFile.exists()
                            && !outputFile.mkdirs()) {

                        throw new IOException(
                            "Cannot create directory: "
                            + name
                        );
                    }

                    input.closeEntry();
                    continue;
                }

                File parent =
                    outputFile.getParentFile();

                if (parent != null
                        && !parent.exists()
                        && !parent.mkdirs()) {

                    throw new IOException(
                        "Cannot create directory."
                    );
                }

                OutputStream output =
                    new BufferedOutputStream(
                        new FileOutputStream(
                            outputFile
                        )
                    );

                try {

                    int read;

                    while ((read =
                            input.read(buffer)) != -1) {

                        output.write(
                            buffer,
                            0,
                            read
                        );
                    }

                    output.flush();

                } finally {

                    try {
                        output.close();
                    } catch (Exception ignored) {
                    }
                }

                input.closeEntry();

                notifyStatus(
                    listener,
                    "Extracting: " + name
                );
            }

        } finally {

            try {
                input.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String sha256(
            File file
    ) throws Exception {

        MessageDigest digest =
            MessageDigest.getInstance(
                "SHA-256"
            );

        InputStream input =
            new BufferedInputStream(
                new FileInputStream(file)
            );

        byte[] buffer =
            new byte[1024 * 1024];

        try {

            int read;

            while ((read =
                    input.read(buffer)) != -1) {

                digest.update(
                    buffer,
                    0,
                    read
                );
            }

        } finally {
            input.close();
        }

        byte[] hash =
            digest.digest();

        StringBuilder result =
            new StringBuilder(64);

        for (byte b : hash) {
            result.append(
                String.format(
                    "%02x",
                    b & 0xff
                )
            );
        }

        return result.toString();
    }

    private static void deleteDirectory(
            File directory
    ) throws IOException {

        if (!directory.exists()) {
            return;
        }

        File[] files =
            directory.listFiles();

        if (files != null) {

            for (File file : files) {

                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else if (!file.delete()) {
                    throw new IOException(
                        "Cannot delete: "
                        + file
                    );
                }
            }
        }

        if (!directory.delete()) {
            throw new IOException(
                "Cannot delete directory: "
                + directory
            );
        }
    }

    private static void notifyProgress(
            Listener listener,
            long downloaded,
            long total
    ) {

        Handler handler =
            new Handler(
                Looper.getMainLooper()
            );

        handler.post(() ->
            listener.onProgress(
                downloaded,
                total
            )
        );
    }

    private static void notifyStatus(
            Listener listener,
            String status
    ) {

        Handler handler =
            new Handler(
                Looper.getMainLooper()
            );

        handler.post(() ->
            listener.onStatus(
                status
            )
        );
    }

    private static void notifySuccess(
            Listener listener,
            File directory
    ) {

        Handler handler =
            new Handler(
                Looper.getMainLooper()
            );

        handler.post(() ->
            listener.onSuccess(
                directory
            )
        );
    }

    private static void notifyError(
            Listener listener,
            String message
    ) {

        Handler handler =
            new Handler(
                Looper.getMainLooper()
            );

        handler.post(() ->
            listener.onError(
                message
            )
        );
    }
}
