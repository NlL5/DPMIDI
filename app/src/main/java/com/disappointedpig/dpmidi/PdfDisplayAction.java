package com.disappointedpig.dpmidi;

import android.content.Context;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.disappointedpig.midi.MIDIConstants;
import com.disappointedpig.midi.MIDISession;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class PdfDisplayAction {

    private final PDFView.Configurator configurator;
    private final File file;
    private final PDFView pdfView;
    private int currentPage = 0;

    public PdfDisplayAction(PDFView pdfView) {
        this(pdfView, 0);
    }

    public PdfDisplayAction(PDFView pdfView, int startWithPage) {
        this.pdfView = pdfView;
        this.currentPage = startWithPage;
        Context context = DPMIDIApplication.getAppContext();

        this.file = new File(context.getCacheDir(), "Tablet.pdf");
        configurator = pdfView.fromFile(file)
                .swipeHorizontal(true)
                .pageSnap(true)
                .autoSpacing(true)
                .pageFling(true)
                .defaultPage(currentPage)
                .onLoad(new OnLoadCompleteListener() {
                    @Override
                    public void loadComplete(int nbPages) {
                        sendPlayReactivateCC();
                    }
                });
        if (file.exists()) {
            configurator.load(); // load and display on activity start
        }

        tryDownload();
    }

    private void tryDownload() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long existingSize = file.exists() ? file.length() : -1;
                    downloadFile("https://cloud.flammenmeer.band/index.php/s/nzZLaMXj4BAjLGK/download/Tablet.pdf", file);
                    // Skip reload if file hasn't changed (same size = same PDF)
                    if (file.exists() && file.length() > 0 && file.length() == existingSize) {
                        Log.d("PdfDisplayAction", "PDF unchanged, skipping reload");
                        return;
                    }
                    if (file.exists() && file.length() > 0) {
                        // PDFView.load() must be called on the UI thread
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    pdfView.fromFile(file)
                                            .swipeHorizontal(true)
                                            .pageSnap(true)
                                            .autoSpacing(true)
                                            .pageFling(true)
                                            .defaultPage(currentPage)
                                            .onLoad(new OnLoadCompleteListener() {
                                                @Override
                                                public void loadComplete(int nbPages) {
                                                    sendPlayReactivateCC();
                                                }
                                            })
                                            .load();
                                } catch (Exception e) {
                                    Log.e("PdfDisplayAction", "Error loading PDF", e);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e("PdfDisplayAction", "Error downloading PDF", e);
                }
            }
        });

        thread.start();
    }

    public PDFView getPdfView() {

        if (!file.exists()) {
            tryDownload();
        }
        return pdfView;
    }

    public static File loadAssetIntoCache(String path, Context context) throws IOException {
        File file = new File(context.getCacheDir(), path);
        if (!file.exists()) {
            // Since PdfRenderer cannot handle the compressed asset file directly, we copy it into
            // the cache directory.
            String[] strings = context.getAssets().list(".");
            System.err.println(Arrays.toString(strings));
            InputStream asset = context.getAssets().open(path);
            FileOutputStream output = new FileOutputStream(file);
            final byte[] buffer = new byte[1024];
            int size;
            while ((size = asset.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            asset.close();
            output.close();
        }

        return file;
    }

    private static SSLContext createSSLContext() {
        try {
            // Load the bundled Let's Encrypt root certificate
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = DPMIDIApplication.getAppContext().getResources().openRawResource(R.raw.lets_encrypt_isrg_root_x1);
            X509Certificate ca = (X509Certificate) cf.generateCertificate(certInput);
            certInput.close();

            // Create a KeyStore with both system CAs and the bundled cert
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("letsencrypt", ca);

            // Also add system CAs
            TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            systemTmf.init((KeyStore) null);

            // Create a combined KeyStore
            KeyStore combined = KeyStore.getInstance(KeyStore.getDefaultType());
            combined.load(null, null);
            combined.setCertificateEntry("letsencrypt", ca);

            // Add all system certs
            javax.net.ssl.TrustManager[] systemTms = systemTmf.getTrustManagers();
            if (systemTms.length > 0 && systemTms[0] instanceof javax.net.ssl.X509TrustManager) {
                javax.net.ssl.X509TrustManager systemTm = (javax.net.ssl.X509TrustManager) systemTms[0];
                for (X509Certificate cert : systemTm.getAcceptedIssuers()) {
                    String alias = cert.getSubjectDN().getName().replaceAll("[^a-zA-Z0-9]", "_");
                    try { combined.setCertificateEntry(alias, cert); } catch (Exception ignored) {}
                }
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(combined);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            Log.e("PdfDisplayAction", "Failed to create SSLContext", e);
            return null;
        }
    }

    public static void downloadFile(String url, File outputFile) {
        InputStream in = null;
        FileOutputStream out = null;
        HttpURLConnection conn = null;
        try {
            SSLContext sslContext = createSSLContext();
            if (sslContext != null) {
                // Fix broken SSL on older Android / emulator images:
                // Set as JVM-wide default so URL.openConnection() can find a factory
                SSLContext.setDefault(sslContext);
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            }

            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            in = new BufferedInputStream(conn.getInputStream());
            out = new FileOutputStream(outputFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            Log.d("PdfDisplayAction", "Download complete: " + outputFile.length() + " bytes");
        } catch (IOException e) {
            Log.e("PdfDisplayAction", "Download failed: " + url, e);
            if (outputFile.exists()) {
                outputFile.delete();
            }
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Sends CC#24 (PLAY_REACTIVATE) on Channel 2 with value 63 when PDF is loaded.
     * This signals the receiving system that the PDF view is ready.
     */
    private void sendPlayReactivateCC() {
        Log.d("PdfDisplayAction", "PDF loaded, sending PLAY_REACTIVATE_CC");
        Bundle msg = new Bundle();
        msg.putInt(MIDIConstants.MSG_COMMAND, 0x0B);  // Control Change
        msg.putInt(MIDIConstants.MSG_CHANNEL, 1);      // Channel 2 (0-indexed)
        msg.putInt(MIDIConstants.MSG_NOTE, 24);        // CC#24 = PLAY_REACTIVATE
        msg.putInt(MIDIConstants.MSG_VELOCITY, 63);    // Value 63
        MIDISession.getInstance().sendMessage(msg);
    }

    public void gotoPage(int page) {
        if (page != currentPage) {
            getPdfView().jumpTo(page, false);
        }
        currentPage = page;
    }
}
