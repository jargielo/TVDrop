package com.example.tvdrop;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.EdgeToEdge;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageView ivQrCode;
    private TextView tvUrl, tvStatus;
    private Button btnRestart, btnClear;
    private ListView listViewFiles;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private boolean isServerRunning = false;
    private int port = 8080;
    private String localIp = "127.0.0.1";

    private File downloadsDir;
    private List<FileItem> fileItemList = new ArrayList<>();
    private FileAdapter fileAdapter;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        ivQrCode = findViewById(R.id.ivQrCode);
        tvUrl = findViewById(R.id.tvUrl);
        tvStatus = findViewById(R.id.tvStatus);
        btnRestart = findViewById(R.id.btnRestart);
        btnClear = findViewById(R.id.btnClear);
        listViewFiles = findViewById(R.id.listViewFiles);

        mainHandler = new Handler(Looper.getMainLooper());
        downloadsDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }

        fileAdapter = new FileAdapter(fileItemList);
        listViewFiles.setAdapter(fileAdapter);

        listViewFiles.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = fileItemList.get(position);
            if (item.isFile) {
                openFile(item.file);
            } else {
                Toast.makeText(this, "Tekst: " + item.textContent, Toast.LENGTH_LONG).show();
            }
        });

        btnRestart.setOnClickListener(v -> restartServer());
        btnClear.setOnClickListener(v -> clearAllFiles());

        refreshFileList();
        startHttpServer();
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String hostAddress = addr.getHostAddress();
                        if (hostAddress != null && !hostAddress.startsWith("169.254")) {
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    private void generateQrCode(String text) {
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ivQrCode.setImageBitmap(bmp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startHttpServer() {
        if (isServerRunning) return;
        localIp = getLocalIpAddress();
        String urlStr = "http://" + localIp + ":" + port;
        tvUrl.setText(urlStr);
        generateQrCode(urlStr);

        executorService = Executors.newCachedThreadPool();
        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(port);
                isServerRunning = true;
                mainHandler.post(() -> {
                    tvStatus.setText("Status: ONLINE");
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                });

                while (isServerRunning && !serverSocket.isClosed()) {
                    Socket clientSocket = serverSocket.accept();
                    executorService.execute(new ClientHandler(clientSocket));
                }
            } catch (IOException e) {
                isServerRunning = false;
                mainHandler.post(() -> {
                    tvStatus.setText("Status: OFFLINE");
                    tvStatus.setTextColor(Color.parseColor("#888888"));
                });
            }
        });
    }

    private void stopHttpServer() {
        isServerRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void restartServer() {
        stopHttpServer();
        Toast.sleep(500);
        startHttpServer();
        Toast.makeText(this, "Serwer zrestartowany", Toast.LENGTH_SHORT).show();
    }

    private void refreshFileList() {
        fileItemList.clear();
        File[] files = downloadsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".txt") && f.length() < 1000) {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        fileItemList.add(new FileItem(f, false, sb.toString()));
                    } catch (Exception e) {
                        fileItemList.add(new FileItem(f, true, null));
                    }
                } else {
                    fileItemList.add(new FileItem(f, true, null));
                }
            }
        }
        fileAdapter.notifyDataSetChanged();
    }

    private void clearAllFiles() {
        File[] files = downloadsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        refreshFileList();
        Toast.makeText(this, "Wyczyszczono pliki", Toast.LENGTH_SHORT).show();
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (file.getName().toLowerCase().endsWith(".apk")) {
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                String mime =getContentResolver().getType(uri);
                if (mime == null) mime = "*/*";
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Błąd otwarcia pliku: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static class FileItem {
        File file;
        boolean isFile;
        String textContent;
        FileItem(File file, boolean isFile, String textContent) {
            this.file = file;
            this.isFile = isFile;
            this.textContent = textContent;
        }
    }

    private class FileAdapter extends ArrayAdapter<FileItem> {
        public FileAdapter(List<FileItem> items) {
            super(MainActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            }
            FileItem item = getItem(position);
            TextView tvFileName = convertView.findViewById(R.id.tvFileName);
            TextView tvFileDetails = convertView.findViewById(R.id.tvFileDetails);

            if (item != null) {
                if (item.isFile) {
                    tvFileName.setText("📁 " + item.file.getName());
                    String size = String.format(Locale.getDefault(), "%.1f MB", item.file.length() / (1024.0 * 1024.0));
                    tvFileDetails.setText(size + " • " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.file.lastModified())));
                } else {
                    tvFileName.setText("💬 Tekst/Link");
                    tvFileDetails.setText(item.textContent);
                }
            }
            return convertView;
        }
    }

    private class ClientHandler implements Runnable {
        private Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream inputStream = socket.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String path = parts[1];

                int contentLength = 0;
                String boundary = null;

                String headerLine;
                while (!(headerLine = reader.readLine()).isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    } else if (headerLine.toLowerCase().startsWith("content-type:")) {
                        if (headerLine.contains("boundary=")) {
                            int idx = headerLine.indexOf("boundary=");
                            boundary = headerLine.substring(idx + 9).trim();
                        }
                    }
                }

                OutputStream out = socket.getOutputStream();

                if (method.equals("GET")) {
                    String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                            "<title>TVDrop</title>" +
                            "<style>" +
                            "body{background:#000;color:#fff;font-family:sans-serif;margin:0;padding:20px;display:flex;flex-direction:column;align-items:center;}" +
                            ".card{background:#111;border:1px solid #333;border-radius:12px;padding:20px;width:100%;max-width:400px;margin-bottom:20px;box-sizing:border-box;}" +
                            "h1{color:#FF6D00;text-align:center;font-size:24px;margin-top:0;}" +
                            "input[type=file],input[type=text],textarea{width:100%;padding:12px;background:#222;border:1px solid #444;color:#fff;border-radius:8px;margin-bottom:12px;box-sizing:border-box;}" +
                            "input[type=submit],button{background:#FF6D00;color:#fff;border:none;padding:12px;border-radius:8px;width:100%;font-size:16px;font-weight:bold;cursor:pointer;}" +
                            "input[type=submit]:active{background:#E65100;}" +
                            "</style></head><body>" +
                            "<h1>TVDrop</h1>" +
                            "<div class=\"card\">" +
                            "<h3>Wyślij plik (APK, foto, wideo)</h3>" +
                            "<form action=\"/upload\" method=\"POST\" enctype=\"multipart/form-data\">" +
                            "<input type=\"file\" name=\"file\" required><br>" +
                            "<input type=\"submit\" value=\"Wyślij Plik\">" +
                            "</form></div>" +
                            "<div class=\"card\">" +
                            "<h3>Wyślij tekst / link</h3>" +
                            "<form action=\"/text\" method=\"POST\">" +
                            "<textarea name=\"text\" rows=\"3\" placeholder=\"Wpisz tekst lub wklej link...\" required></textarea><br>" +
                            "<input type=\"submit\" value=\"Wyślij Tekst\">" +
                            "</form></div>" +
                            "</body></html>";

                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n".getBytes());
                    out.write(html.getBytes());
                    out.flush();

                } else if (method.equals("POST") && path.equals("/upload")) {
                    if (boundary != null && contentLength > 0) {
                        byte[] body = new byte[contentLength];
                        int totalRead = 0;
                        while (totalRead < contentLength) {
                            int read = inputStream.read(body, totalRead, contentLength - totalRead);
                            if (read == -1) break;
                            totalRead += read;
                        }

                        parseMultipartUpload(body, boundary);
                    }

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                            "<html><body style=\"background:#000;color:#fff;text-align:center;padding:50px;font-family:sans-serif;\">" +
                            "<h1 style=\"color:#FF6D00;\">Plik odebrany pomyślnie!</h1>" +
                            "<a href=\"/\" style=\"color:#FF6D00;font-size:18px;\">← Wróć</a>" +
                            "</body></html>";
                    out.write(response.getBytes());
                    out.flush();
                    mainHandler.post(() -> refreshFileList());

                } else if (method.equals("POST") && path.equals("/text")) {
                    byte[] body = new byte[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = inputStream.read(body, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    String bodyStr = new String(body);
                    if (bodyStr.startsWith("text=")) {
                        String decodedText = URLDecoder.decode(bodyStr.substring(5).replace("+", " "), "UTF-8");
                        File textFile = new File(downloadsDir, "text_" + System.currentTimeMillis() + ".txt");
                        FileOutputStream fos = new FileOutputStream(textFile);
                        fos.write(decodedText.getBytes());
                        fos.close();
                    }

                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                            "<html><body style=\"background:#000;color:#fff;text-align:center;padding:50px;font-family:sans-serif;\">" +
                            "<h1 style=\"color:#FF6D00;\">Tekst odebrany pomyślnie!</h1>" +
                            "<a href=\"/\" style=\"color:#FF6D00;font-size:18px;\">← Wróć</a>" +
                            "</body></html>";
                    out.write(response.getBytes());
                    out.flush();
                    mainHandler.post(() -> refreshFileList());
                }

                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void parseMultipartUpload(byte[] body, String boundary) {
            try {
                String boundaryStr = "--" + boundary;
                byte[] boundaryBytes = boundaryStr.getBytes();

                int start = indexOf(body, boundaryBytes, 0);
                while (start != -1) {
                    start += boundaryBytes.length;
                    if (start >= body.length || (body[start] == '-' && body[start+1] == '-')) {
                        break;
                    }
                    // skip \r\n
                    start += 2;

                    int headerEnd = indexOf(body, "\r\n\r\n".getBytes(), start);
                    if (headerEnd == -1) break;

                    String headers = new String(body, start, headerEnd - start);
                    start = headerEnd + 4;

                    int end = indexOf(body, boundaryBytes, start);
                    if (end == -1) end = body.length;
                    else end -= 2; // skip \r\n before boundary

                    if (headers.contains("filename=\"")) {
                        int fnIdx = headers.indexOf("filename=\"");
                        int fnEnd = headers.indexOf("\"", fnIdx + 10);
                        String filename = headers.substring(fnIdx + 10, fnEnd);
                        filename = new File(filename).getName(); // security
                        if (filename.isEmpty()) filename = "upload_" + System.currentTimeMillis();

                        File outFile = new File(downloadsDir, filename);
                        FileOutputStream fos = new FileOutputStream(outFile);
                        fos.write(body, start, end - start);
                        fos.close();
                        break;
                    }
                    start = end;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private int indexOf(byte[] outer, byte[] inner, int start) {
            for (int i = start; i <= outer.length - inner.length; i++) {
                boolean found = true;
                for (int j = 0; j < inner.length; j++) {
                    if (outer[i + j] != inner[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) return i;
            }
            return -1;
        }
    }
}
