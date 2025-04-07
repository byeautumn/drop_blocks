package org.service;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer; // Import for HttpServer
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

public class DropBlocks {

    private static final int PORT = 8080; // Default port, can be changed.
    private static final String UPLOAD_PATH = "/upload"; // Endpoint for file uploads
    private static final String DOWNLOAD_PATH_PREFIX = "/download/"; // Prefix for download URLs
    private static final Map<String, Path> uploadedFiles = new HashMap<>(); // Store file paths by unique ID
    private static String STORAGE_DIRECTORY = "uploads"; //  Changed to absolute path
    private static final Map<String, String> fileNames = new HashMap<>(); // Add this line

    public static void main(String[] args) throws IOException {
        // Create the server.
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Create contexts for handling requests.
        HttpContext uploadContext = server.createContext(UPLOAD_PATH, new UploadHandler(fileNames)); // Pass fileNames
        HttpContext downloadContext = server.createContext(DOWNLOAD_PATH_PREFIX, new DownloadHandler(fileNames)); // Pass fileNames

        //set the executor
        server.setExecutor(Executors.newFixedThreadPool(10)); // Use a thread pool

        // Allow the storage directory to be configurable.
        if (args.length > 0) {
            STORAGE_DIRECTORY = args[0]; // Use the first command-line argument
        }

        // Create the directory if it doesn't exist
        File storageDir = new File(STORAGE_DIRECTORY);
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                throw new IOException("Failed to create storage directory: " + STORAGE_DIRECTORY);
            }
        }

        //scan for existing files
        initializeStorage();

        // Start the server.
        server.start();

        System.out.println("DropBlocks server is running on port " + PORT);
        System.out.println("Upload endpoint: " + UPLOAD_PATH);
        System.out.println("Download prefix: " + DOWNLOAD_PATH_PREFIX);
        System.out.println("Storage directory: " + STORAGE_DIRECTORY);
    }

    private static void initializeStorage() {
        File storageDir = new File(STORAGE_DIRECTORY);
        File[] files = storageDir.listFiles();
        if (files == null) {
            System.out.println("Storage directory is empty or does not exist.");
            return;
        }

        for (File file : files) {
            if (file.isFile()) {
                //generate ID from filename
                String fileName = file.getName();
                int separatorIndex = fileName.indexOf("_");
                if (separatorIndex > 0) {
                    String fileId = fileName.substring(0, separatorIndex);
                    Path filePath = Paths.get(STORAGE_DIRECTORY, fileName);
                    String originalFileName = fileName.substring(separatorIndex + 1);
                    fileNames.put(fileId, originalFileName); // Extract the filename
                    uploadedFiles.put(fileId, filePath);
                    System.out.println("Loaded file: " + fileName + " with ID: " + fileId);
                } else {
                    // Handle files without the separator.  This is important for files uploaded before the change.
                    String fileId = generateFileId(fileName);  // Generate a unique ID.
                    Path filePath = Paths.get(STORAGE_DIRECTORY, fileName);
                    uploadedFiles.put(fileId, filePath);
                    fileNames.put(fileId, fileName);
                    System.out.println("Loaded file: " + fileName + " with ID: " + fileId);
                }
            }
        }
    }

    // Handler for file upload requests
    static class UploadHandler implements HttpHandler {
        private final Map<String, String> fileNames; //store file names

        public UploadHandler(Map<String, String> fileNames) {
            this.fileNames = fileNames;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handlePost(exchange);
            } else {
                // Method not allowed
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            InputStream is = null;
            try {
                is = exchange.getRequestBody();
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                String fileName = null;

                if (contentType != null && contentType.startsWith("multipart/form-data")) {
                    //handle multipart
                    String boundary = null;
                    String[] parts = contentType.split(";");
                    for (String part : parts) {
                        part = part.trim();
                        if (part.startsWith("boundary=")) {
                            boundary = part.substring(part.indexOf('=') + 1);
                            break;
                        }
                    }
                    if (boundary == null) {
                        sendResponse(exchange, 400, "Bad Request: Missing boundary in Content-Type");
                        return;
                    }
                    fileName = handleMultipart(exchange, is, boundary);
                    if (fileName == null) {
                        return; //error already sent
                    }
                } else {
                    fileName = getFileNameSimple(exchange);
                    if (fileName == null || fileName.isEmpty()) {
                        fileName = "uploaded_file_" + System.currentTimeMillis();
                    }
                    //handle non-multipart
                    handleNonMultipart(exchange, is, fileName);
                }



            } catch (Exception e) {
                // Handle errors during file upload.
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            } finally {
                if (is != null) {
                    is.close(); // Ensure the input stream is closed.
                }
            }
        }

        private void handleNonMultipart(HttpExchange exchange, InputStream is, String fileName) throws IOException{
            // Generate a unique ID for the file.
            String fileId = UUID.randomUUID().toString();
            //construct the file path
            Path filePath = Paths.get(STORAGE_DIRECTORY, fileId + "_" + fileName);
            // Check if the directory exists
            File directory = new File(STORAGE_DIRECTORY);
            if (!directory.exists()) {
                System.err.println("Error: Storage directory does not exist: " + STORAGE_DIRECTORY);
                if (!directory.mkdirs()) {
                    System.err.println("Error: Failed to create storage directory: " + STORAGE_DIRECTORY);
                    sendResponse(exchange, 500, "Internal Server Error: Could not create directory");
                    return;
                }
                System.out.println("Storage directory created successfully: " + STORAGE_DIRECTORY);
            }
            long bytesCopied = Files.copy(is, filePath);
            if(bytesCopied == 0){
                sendResponse(exchange, 500, "Internal Server Error: 0 bytes copied");
                return;
            }
            // Store the file path and its ID.
            uploadedFiles.put(fileId, filePath);
            fileNames.put(fileId, fileName); //store
            // Construct the download URL.
            String downloadUrl = DOWNLOAD_PATH_PREFIX + fileId;
            // Send the response with the download URL.
            sendResponse(exchange, 200, downloadUrl);
            System.out.println("Uploaded: " + fileName + " (ID: " + fileId + ") to " + filePath.toString());
        }

        private String handleMultipart(HttpExchange exchange, InputStream is, String boundary) throws IOException {
            String fileName = null;
            String contentTypeHeader = exchange.getRequestHeaders().getFirst("Content-Type");
            MultipartParser parser = new MultipartParser(is, contentTypeHeader);
            MultipartParser.Part part;

            while ((part = parser.readNextPart()) != null) {
                if (part.isParamPart()) {
                    // Handle form fields if any (not the file itself in this case)
                    String name = part.getName();
                    String value = new String(part.getValueBytes(), StandardCharsets.UTF_8);
                    System.out.println("Form parameter: " + name + " = " + value);
                } else if (part.isFilePart()) {
                    fileName = part.getFilename();
                    if (fileName == null || fileName.isEmpty()) {
                        sendResponse(exchange, 400, "Bad Request: Missing filename in Content-Disposition");
                        return null;
                    }

                    // Generate a unique ID for the file.
                    String fileId = UUID.randomUUID().toString();
                    // Construct the file path
                    Path filePath = Paths.get(STORAGE_DIRECTORY, fileId + "_" + fileName);
                    // Check if the directory exists
                    File directory = new File(STORAGE_DIRECTORY);
                    if (!directory.exists()) {
                        System.err.println("Error: Storage directory does not exist: " + STORAGE_DIRECTORY);
                        if (!directory.mkdirs()) {
                            System.err.println("Error: Failed to create storage directory: " + STORAGE_DIRECTORY);
                            sendResponse(exchange, 500, "Internal Server Error: Could not create directory");
                            return null;
                        }
                        System.out.println("Storage directory created successfully: " + STORAGE_DIRECTORY);
                    }

                    try (InputStream partInputStream = part.getInputStream()) {
                        Files.copy(partInputStream, filePath);
                        uploadedFiles.put(fileId, filePath);
                        fileNames.put(fileId, fileName);
                        String downloadUrl = DOWNLOAD_PATH_PREFIX + fileId;
                        sendResponse(exchange, 200, downloadUrl);
                        return fileName;
                    } catch (IOException e) {
                        e.printStackTrace();
                        sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
                        return null;
                    }
                }
            }
            return fileName;
        }

        private String getFileNameSimple(HttpExchange exchange) {
            String fileName = null;
            String contentDispositionHeader = exchange.getRequestHeaders().getFirst("Content-Disposition");
            if (contentDispositionHeader != null) {
                for (String part : contentDispositionHeader.split(";")) {
                    part = part.trim();
                    if (part.startsWith("filename=")) {
                        fileName = part.substring(part.indexOf('=') + 1).trim();
                        if (fileName.startsWith("\"") && fileName.endsWith("\"")) {
                            fileName = fileName.substring(1, fileName.length() - 1);
                        }
                        // Decode the filename to handle special characters
                        try {
                            fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException e) {
                            // Log the error
                            e.printStackTrace();
                            fileName = null; // Set fileName to null to indicate failure
                        }
                        return fileName;
                    }
                }
            }
            // If Content-Disposition header is null, try to extract from the URL.
            if (fileName == null) {
                String requestUrl = exchange.getRequestURI().toString();
                fileName = extractFileNameFromUrl(requestUrl);
            }
            return fileName;
        }

        private String extractFileNameFromUrl(String url) {
            try {
                // Decode the URL to handle any special characters
                String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
                // Use a regular expression to extract the filename
                Pattern pattern = Pattern.compile("[^/]+$");
                Matcher matcher = pattern.matcher(decodedUrl);
                if (matcher.find()) {
                    return matcher.group(0);
                }
            } catch (IllegalArgumentException e) {
                // Log the error
                e.printStackTrace();
                return null;
            }
            return null;
        }
    }

    // Handler for file download requests
    static class DownloadHandler implements HttpHandler {
        private final Map<String, String> fileNames;

        public DownloadHandler(Map<String, String> fileNames) {
            this.fileNames = fileNames;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String fileId = path.substring(DOWNLOAD_PATH_PREFIX.length()); // Extract the file ID

            System.out.println("Download request for file ID: " + fileId);

            Path filePath = uploadedFiles.get(fileId);
            if (filePath != null) {
                File file = filePath.toFile();
                if (file.exists()) {
                    // Send the file as a response.
                    try (FileInputStream fis = new FileInputStream(file)) {
                        // Set the content type.  Try to guess from the file extension.
                        String contentType = guessContentType(filePath);
                        if (contentType != null) { // Add null check here
                            exchange.getResponseHeaders().set("Content-Type", contentType);
                        } else {
                            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream"); // set default
                        }
                        exchange.getResponseHeaders().set("Content-Length", String.valueOf(file.length()));
                        String fileName = fileNames.get(fileId);
                        if (fileName != null) {
                            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                        } else {
                            fileName = file.getName();
                            int separatorIndex = fileName.indexOf("_");
                            if (separatorIndex > 0) {
                                fileName = fileName.substring(separatorIndex + 1);
                            }
                            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                        }
                        exchange.sendResponseHeaders(200, file.length());
                        // Print the headers:
                        System.out.println("Response Headers: ");
                        exchange.getResponseHeaders().forEach((key, values) -> {
                            System.out.println(key + ": " + values);
                        });
                        try (OutputStream os = exchange.getResponseBody()) {
                            //send file
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                        }
                        System.out.println("Downloaded file with ID: " + fileId + " from " + filePath.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
                    }
                } else {
                    // File not found
                    sendResponse(exchange, 404, "File Not Found");
                }
            } else {
                // File not found (ID not found)
                sendResponse(exchange, 404, "File Not Found");
            }
        }

        private String guessContentType(Path filePath) throws IOException {
            return Files.probeContentType(filePath);
        }
    }

    // Helper method to send HTTP responses
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        try (OutputStream os = exchange.getResponseBody()) {
            byte[] bytes = response.getBytes();
            exchange.sendResponseHeaders(statusCode, bytes.length);
            os.write(bytes);
        }
    }

    private static String generateFileId(String filename) {
        return UUID.randomUUID().toString();
    }
}
