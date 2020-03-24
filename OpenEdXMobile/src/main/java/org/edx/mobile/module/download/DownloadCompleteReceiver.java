package org.edx.mobile.module.download;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import com.google.inject.Inject;

import org.edx.mobile.core.IEdxEnvironment;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.model.VideoModel;
import org.edx.mobile.model.db.DownloadEntry;
import org.edx.mobile.model.download.NativeDownloadModel;
import org.edx.mobile.module.analytics.AnalyticsRegistry;
import org.edx.mobile.module.db.DataCallback;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import roboguice.receiver.RoboBroadcastReceiver;

public class DownloadCompleteReceiver extends RoboBroadcastReceiver {
    private final Logger logger = new Logger(getClass().getName());
    public static final String AES_ALGORITHM = "AES";
    public static final String AES_TRANSFORMATION = "AES/CTR/NoPadding";
    public static final String SECRET_KEY= "DCC789BCEB2C593D";
    public static final String IV = "B873CA042AAE847F";
    private Cipher mCipher;
    private SecretKeySpec mSecretKeySpec;
    private IvParameterSpec mIvParameterSpec;


    @Inject
    private IEdxEnvironment environment;

    @Override
    protected void handleReceive(final Context context, final Intent data) {
        if (data != null && data.getAction() != null) {
            switch (data.getAction()) {
                case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                    handleDownloadCompleteIntent(data);
                    break;
                case DownloadManager.ACTION_NOTIFICATION_CLICKED:
                    // Open downloads activity
                    environment.getRouter().showDownloads(context);
                    break;
            }
        }
    }

    private void handleDownloadCompleteIntent(final Intent data) {
        if (data.hasExtra(DownloadManager.EXTRA_DOWNLOAD_ID)) {
            final long id = data.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1) {
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Received download notification for id: " + id);

                        // check if download was SUCCESSFUL
                        NativeDownloadModel nm = environment.getDownloadManager().getDownload(id);

                        if (nm == null || nm.status != DownloadManager.STATUS_SUCCESSFUL) {
                            logger.debug("Download seems failed or cancelled for id : " + id);
                            final VideoModel downloadEntry = environment.getDatabase().getDownloadEntryByDmId(id, null);
                            if (downloadEntry != null) {
                                // This means that the download was either cancelled from the native
                                // download manager app or the cancel button on download notification
                                logger.debug("ERROR: 1");
                                System.out.println("ERROR: 1");
                                environment.getStorage().removeDownload(downloadEntry);
                            } else {
                                logger.debug("ERROR: 2");
                                System.out.println("ERROR: 2");
                                environment.getDownloadManager().removeDownloads(id);
                            }
                            return;
                        } else {
                            logger.debug("Download successful for id : " + id);
                            logger.debug("nm filepath: "+nm.filepath);
                            encryptVideo(nm.filepath);
                        }

                        // mark download as completed
                        environment.getStorage().markDownloadAsComplete(id, new DataCallback<VideoModel>() {
                            @Override
                            public void onResult(VideoModel result) {
                                if (result != null) {
                                    logger.debug("RESULT callback : "+result.toString());
                                    logger.debug("onResult : "+result.getFilePath());
                                    DownloadEntry download = (DownloadEntry) result;
                                    logger.debug(download.filepath);
                                    AnalyticsRegistry analyticsRegistry = environment.getAnalyticsRegistry();
                                    analyticsRegistry.trackDownloadComplete(download.videoId, download.eid,
                                            download.lmsUrl);
                                    /*try {
                                        scramble(result.getFilePath(), result.getSize()%1024);
                                        logger.debug("Scrambled: "+result.getFilePath());
                                    }
                                    catch (Exception e){
                                        e.printStackTrace();
                                    }*/
                                }
                            }

                            @Override
                            public void onFail(Exception ex) {
                                logger.error(ex);
                            }
                        });
                    }
                });
            }
        }
    }

    // Encrypt the downloaded video and delete original playable video -mohit741
    private void encryptVideo(String filePath){
        byte[] mKEY = SECRET_KEY.getBytes();
        byte[] mIV = IV.getBytes();
        mSecretKeySpec = new SecretKeySpec(mKEY, AES_ALGORITHM);
        mIvParameterSpec = new IvParameterSpec(mIV);
        try {
            mCipher = Cipher.getInstance(AES_TRANSFORMATION);
            mCipher.init(Cipher.ENCRYPT_MODE, mSecretKeySpec, mIvParameterSpec);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            File tmp = new File(filePath);
            File encryptedFile = new File(filePath+"_aes");
            FileInputStream inputStream = new FileInputStream(tmp);
            FileOutputStream fileOutputStream = new FileOutputStream(encryptedFile);
            CipherOutputStream cipherOutputStream = new CipherOutputStream(fileOutputStream, mCipher);

            byte[] buffer = new byte[1024 * 1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                cipherOutputStream.write(buffer, 0, bytesRead);
            }
            cipherOutputStream.close();
            fileOutputStream.close();
            inputStream.close();
            logger.debug("Done Encryption");
            logger.debug("CHECK " + encryptedFile.exists());
            boolean f = tmp.delete();
            if(f){
                logger.debug("Successfully deleted file "+ tmp.getName() +" after encryption");
                /*if(encryptedFile.renameTo(new File(filePath))){
                    logger.debug("File successfully renamed");
                }*/
                logger.debug("CHECK " + tmp.exists());
                logger.debug("CHECK " + encryptedFile.exists());
                logger.debug("CHECK " + encryptedFile.toString());
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
    // TODO Used to corrupt files, Remove as we don't need these
    private void scramble(String filePath, long scrambledByteCount) throws IOException{
        RandomAccessFile file = new RandomAccessFile(filePath, "rw");
        Random random = new Random();
        long fileLength = file.length();
        for(long count = 0; count < scrambledByteCount; count++) {
            long nextPosition = nextLong(random,fileLength-1);
            file.seek(nextPosition);

            int scrambleByte = random.nextInt(255) - 128;
            file.write(scrambleByte);
        }

        file.close();
    }
    long nextLong(Random rng, long n) {
        // error checking and 2^x checking removed for simplicity.
        long bits, val;
        do {
            bits = (rng.nextLong() << 1) >>> 1;
            val = bits % n;
        } while (bits-val+(n-1) < 0L);
        return val;
    }
}
