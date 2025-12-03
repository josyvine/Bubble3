package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager Fixed for Build 33.0.1 and libtorrent4j 2.1.0-38 compatibility.
 * Replaced Reflection and incompatible API calls with direct, compatible methods.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events using direct AlertType checks
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return null; // Listen to all alerts
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert.type() == AlertType.STATE_UPDATE) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_FINISHED) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_ERROR) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            // FIX: Use handle().infoHash() instead of status.infoHash()
            String infoHex = status.handle().infoHash().toHex();
            if (infoHex == null) continue;

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                long totalDone = status.totalDone();
                long totalWanted = status.totalWanted();
                
                int progress = 0;
                if (totalWanted > 0) {
                    progress = (int) ((totalDone * 100) / totalWanted);
                }
                
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, progress);
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, 100);
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        cleanupTorrent(handle);
    }

    /**
     * Creates a torrent file and starts seeding it.
     */
    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            // 1. Create the .torrent file
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // 2. Add the torrent to the session
            // FIX: download() returns void, so we call it then retrieve handle separately
            sessionManager.download(torrentInfo, dataFile.getParentFile());
            
            // Retrieve the handle we just added
            TorrentHandle handle = sessionManager.find(torrentInfo.infoHash());

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = handle.infoHash().toHex();
                hashToIdMap.put(infoHex, dropRequestId);
                
                String magnetLink = handle.makeMagnetUri();
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();
            }
        }
    }

    /**
     * Helper to create a .torrent file from a source file.
     */
    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        
        // FIX: Replaced missing static libtorrent.add_files with manual add_file
        // This adds the file to the file_storage object
        fs.add_file(dataFile.getName(), dataFile.length());

        // FIX: Added '0' (auto piece size) as 2nd argument to satisfy constructor
        create_torrent ct = new create_torrent(fs, 0);
        ct.set_creator("HFM Drop");
        ct.set_priv(true); 

        entry e = ct.generate();
        byte_vector bencoded = e.bencode();
        
        byte[] torrentBytes = Vectors.byte_vector2bytes(bencoded);

        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    /**
     * Starts downloading a file from a magnet link.
     */
    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // FIX: Replaced direct download(string) which doesn't exist with fetchMagnet + download(info)
            // fetchMagnet retrieves the metadata (TorrentInfo) from the link
            byte[] data = sessionManager.fetchMagnet(magnetLink, 30); // 30 seconds timeout
            
            if (data != null) {
                TorrentInfo ti = TorrentInfo.bdecode(data);
                
                // Add to session
                sessionManager.download(ti, saveDirectory);
                
                // Retrieve handle
                TorrentHandle handle = sessionManager.find(ti.infoHash());

                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = handle.infoHash().toHex();
                    hashToIdMap.put(infoHex, dropRequestId);
                    
                    // FIX: Removed setSequentialDownload() as it caused symbol errors. 
                    // Standard download behavior is sufficient for file transfer.
                    
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                } else {
                    Log.e(TAG, "Failed to start download: Invalid handle returned.");
                    broadcastDownloadError("Failed to initialize download session.");
                }
            } else {
                Log.e(TAG, "Failed to fetch magnet metadata.");
                broadcastDownloadError("Could not retrieve file metadata from magnet link.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
            broadcastDownloadError("Download Error: " + e.getMessage());
        }
    }
    
    private void broadcastDownloadError(String msg) {
        Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, msg);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }

        sessionManager.remove(handle);

        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }
}