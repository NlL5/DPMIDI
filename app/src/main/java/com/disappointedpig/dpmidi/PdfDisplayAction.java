package com.disappointedpig.dpmidi;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.PdfRenderer.Page;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.URLUtil;
import android.widget.ImageView;

import com.github.barteksc.pdfviewer.PDFView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;

public class PdfDisplayAction {

    private final PDFView.Configurator configurator;
    private final File file;
    private int currentPage = 0;

    public PdfDisplayAction(PDFView pdfView) {
        this(pdfView, 0);
    }

    public PdfDisplayAction(PDFView pdfView, int startWithPage) {
        this.currentPage = startWithPage;
        Context context = DPMIDIApplication.getAppContext();

        this.file = new File(context.getCacheDir(), "Tablet.pdf");
        configurator = pdfView.fromFile(file)
                .swipeHorizontal(true)
                .pageSnap(true)
                .autoSpacing(true)
                .pageFling(true);

        tryDownload();
    }

    private void tryDownload() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadFile("https://cloud.flammenmeer.band/index.php/s/nzZLaMXj4BAjLGK/download/Tablet.pdf", file);
                    configurator.defaultPage(currentPage).load();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();
    }

    public PDFView.Configurator getConfigurator() {

        if (!file.exists()) {
            tryDownload();
        }
        return configurator;
    }

    public static File loadFile(String path, Context context) throws IOException {
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

    public static void downloadFile(String url, File outputFile) {
        try {
            URL u = new URL(url);
            URLConnection conn = u.openConnection();
            int contentLength = conn.getContentLength();

            DataInputStream stream = new DataInputStream(u.openStream());

            byte[] buffer = new byte[contentLength];
            stream.readFully(buffer);
            stream.close();

            DataOutputStream fos = new DataOutputStream(new FileOutputStream(outputFile));
            fos.write(buffer);
            fos.flush();
            fos.close();
        } catch(FileNotFoundException e) {
            return; // swallow a 404
        } catch (IOException e) {
            return; // swallow a 404
        }
    }

    public void gotoPage(int page) {
        if (page != currentPage) {
            getConfigurator().defaultPage(page).load();
        }
        currentPage = page;
    }
}
